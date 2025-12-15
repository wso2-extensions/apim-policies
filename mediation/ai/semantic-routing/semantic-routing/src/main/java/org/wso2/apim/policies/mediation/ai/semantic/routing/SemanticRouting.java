/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.apim.policies.mediation.ai.semantic.routing;

import com.google.gson.Gson;
import com.jayway.jsonpath.JsonPath;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.AbstractMediator;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.apim.policies.mediation.ai.semantic.routing.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.EmbeddingProviderService;
import org.wso2.carbon.apimgt.api.gateway.ModelEndpointDTO;
import org.wso2.carbon.apimgt.api.APIConstants.AIAPIConstants;
import org.wso2.carbon.apimgt.impl.APIConstants;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SemanticRouting extends AbstractMediator implements ManagedLifecycle {
    private static final Log logger = LogFactory.getLog(SemanticRouting.class);
    
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.90;
    private static final double MIN_CONFIDENCE_GAP = 0.05;

    private String semanticRoutingConfigs;
    private EmbeddingProviderService embeddingProvider;
    private SemanticRoutingConfigDTO config;

    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        if (logger.isDebugEnabled()) {
            logger.debug("Initializing SemanticRouting.");
        }
        embeddingProvider = ServiceReferenceHolder.getInstance().getEmbeddingProvider();
        if (embeddingProvider == null) {
            throw new IllegalStateException("Embedding provider is not registered or available");
        }
        loadRoutingConfiguration(semanticRoutingConfigs);
    }

    private void loadRoutingConfiguration(String configStr) {
        try {
            Gson gson = new Gson();
            this.config = gson.fromJson(configStr, SemanticRoutingConfigDTO.class);
            
            if (config.getProduction() != null) {
                for (SemanticRoutingConfigDTO.RouteConfig routeConfig : config.getProduction()) {
                    initializeRouteConfig(routeConfig);
                }
            }
            if (config.getSandbox() != null) {
                for (SemanticRoutingConfigDTO.RouteConfig routeConfig : config.getSandbox()) {
                    initializeRouteConfig(routeConfig);
                }
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Routing configuration loaded successfully.");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to load routing configuration: " + configStr, e);
        }
    }

    private void initializeRouteConfig(SemanticRoutingConfigDTO.RouteConfig routeConfig) {
        if (routeConfig.getModel() != null && routeConfig.getEndpointId() != null) {
            ModelEndpointDTO endpoint = new ModelEndpointDTO();
            endpoint.setModel(routeConfig.getModel());
            endpoint.setEndpointId(routeConfig.getEndpointId());
            routeConfig.setEndpoint(endpoint);
        }
        
        double threshold = DEFAULT_SIMILARITY_THRESHOLD;
        if (routeConfig.getScorethreshold() != null && !routeConfig.getScorethreshold().trim().isEmpty()) {
            try {
                double parsed = Double.parseDouble(routeConfig.getScorethreshold());
                if (parsed >= 0.0 && parsed <= 1.0) {
                    threshold = parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        routeConfig.setScoreThreshold(threshold);
        
        precomputeEmbeddings(routeConfig);
    }

    private void precomputeEmbeddings(SemanticRoutingConfigDTO.RouteConfig routeConfig) {
        List<String> utterances = routeConfig.getUtterances();
        if (utterances == null || utterances.isEmpty()) {
            return;
        }
        try {
            double[][] embeddings = new double[utterances.size()][];
            for (int i = 0; i < utterances.size(); i++) {
                embeddings[i] = embeddingProvider.getEmbedding(utterances.get(i));
            }
            routeConfig.setUtteranceEmbeddings(embeddings);
            if (logger.isDebugEnabled()) {
                logger.debug("Precomputed " + embeddings.length + " embeddings for route: " + routeConfig.getModel());
            }
        } catch (APIManagementException e) {
            logger.error("Failed to precompute embeddings for route: " + routeConfig.getModel(), e);
        }
    }


    @Override
    public void destroy() {
    }


    @Override
    public boolean mediate(MessageContext messageContext) {
        if (logger.isDebugEnabled()) {
            logger.debug("Starting semantic routing.");
        }
        try {
            String apiKeyType = (String) messageContext.getProperty(APIConstants.API_KEY_TYPE);
            List<SemanticRoutingConfigDTO.RouteConfig> routeConfigs = APIConstants.API_KEY_TYPE_PRODUCTION
                    .equals(apiKeyType) ? config.getProduction() : config.getSandbox();

            if (routeConfigs == null || routeConfigs.isEmpty()) {
                routeToDefault(messageContext);
                return true;
            }

            String userRequest = extractUserRequest(messageContext);
            if (userRequest == null || userRequest.isEmpty()) {
                routeToDefault(messageContext);
                return true;
            }

            double[] requestEmbedding = embeddingProvider.getEmbedding(userRequest);
            RouteMatch bestMatch = findBestRoute(routeConfigs, requestEmbedding);

            if (bestMatch.route != null && bestMatch.score >= bestMatch.route.getScoreThreshold()
                    && (bestMatch.score - bestMatch.secondBestScore) >= MIN_CONFIDENCE_GAP) {
                applyRoute(messageContext, bestMatch.route.getEndpoint());
            } else {
                routeToDefault(messageContext);
            }
        } catch (Exception e) {
            logger.error("Exception during semantic routing.", e);
            routeToDefault(messageContext);
        }
        return true;
    }

    private static class RouteMatch {
        SemanticRoutingConfigDTO.RouteConfig route;
        double score;
        double secondBestScore;
    }

    private RouteMatch findBestRoute(List<SemanticRoutingConfigDTO.RouteConfig> routes, double[] requestEmbedding) {
        RouteMatch match = new RouteMatch();
        for (SemanticRoutingConfigDTO.RouteConfig route : routes) {
            double[][] embeddings = route.getUtteranceEmbeddings();
            if (embeddings == null || embeddings.length == 0) {
                continue;
            }
            double score = computeMaxCosineSimilarity(requestEmbedding, embeddings);
            if (logger.isDebugEnabled()) {
                logger.debug("Route: " + route.getModel() + ", Score: " + String.format("%.4f", score) 
                        + ", Threshold: " + route.getScoreThreshold());
            }
            if (score > match.score) {
                match.secondBestScore = match.score;
                match.score = score;
                match.route = route;
            } else if (score > match.secondBestScore) {
                match.secondBestScore = score;
            }
        }
        return match;
    }

    private double computeMaxCosineSimilarity(double[] request, double[][] utteranceEmbeddings) {
        double maxSim = 0.0;
        for (double[] utterance : utteranceEmbeddings) {
            double sim = cosineSimilarity(request, utterance);
            if (sim > maxSim) {
                maxSim = sim;
            }
        }
        return maxSim;
    }

    private double cosineSimilarity(double[] a, double[] b) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0.0 ? 0.0 : dot / denom;
    }

    private void applyRoute(MessageContext messageContext, ModelEndpointDTO endpoint) {
        if (endpoint == null) {
            routeToDefault(messageContext);
            return;
        }
        messageContext.setProperty(AIAPIConstants.TARGET_ENDPOINT, endpoint.getEndpointId());
        Map<String, Object> configs = new HashMap<>();
        configs.put(AIAPIConstants.TARGET_MODEL_ENDPOINT, endpoint);
        messageContext.setProperty(SemanticRoutingConstants.SEMANTIC_ROUTING_CONFIGS, configs);
        modifyRequestForSemanticRoute(messageContext, endpoint);
        if (logger.isDebugEnabled()) {
            logger.debug("Routed to: " + endpoint.getModel());
        }
    }

    private void routeToDefault(MessageContext messageContext) {
        if (config.getDefault() == null) {
            return;
        }
        SemanticRoutingConfigDTO.DefaultConfig defaultConfig = config.getDefault();
        ModelEndpointDTO defaultEndpoint = new ModelEndpointDTO();
        defaultEndpoint.setModel(defaultConfig.getModel());
        defaultEndpoint.setEndpointId(defaultConfig.getEndpointId());
        
        messageContext.setProperty(AIAPIConstants.TARGET_ENDPOINT, defaultEndpoint.getEndpointId());
        Map<String, Object> configs = new HashMap<>();
        configs.put(AIAPIConstants.TARGET_MODEL_ENDPOINT, defaultEndpoint);
        messageContext.setProperty(SemanticRoutingConstants.SEMANTIC_ROUTING_CONFIGS, configs);
        modifyRequestForSemanticRoute(messageContext, defaultEndpoint);
        if (logger.isDebugEnabled()) {
            logger.debug("Routed to default: " + defaultEndpoint.getModel());
        }
    }


    private String extractUserRequest(MessageContext messageContext) {
        if (config.getPath() == null || config.getPath().getContentpath() == null 
                || config.getPath().getContentpath().trim().isEmpty()) {
            logger.error("Content path is not configured or empty.");
            return null;
        }
        
        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        String jsonPayload = JsonUtil.jsonPayloadToString(axis2MC);

        if (jsonPayload == null || jsonPayload.isEmpty()) {
            logger.error("Request payload is empty.");
            return null;
        }
        
        String contentPath = config.getPath().getContentpath();
        try {
            String result = JsonPath.read(jsonPayload, contentPath);
            return result;
        } catch (Exception e) {
            logger.error("Error parsing JSON path '" + contentPath + "': " + e.getMessage());
            return null;
        }
    }

    public String getSemanticRoutingConfigs() {
        return semanticRoutingConfigs;
    }

    public void setSemanticRoutingConfigs(String semanticRoutingConfigs) {
        this.semanticRoutingConfigs = semanticRoutingConfigs;
    }

    // Alias method for XML configuration compatibility
    public String getRoutingConfig() {
        return semanticRoutingConfigs;
    }

    public void setRoutingConfig(String routingConfig) {
        this.semanticRoutingConfigs = routingConfig;
    }

    /**
     * Modifies the request to use the model from the selected semantic route endpoint.
     * Supports both JSON payload modification and URL path modification for all providers.
     *
     * @param messageContext The Synapse message context
     * @param targetEndpoint The selected model endpoint from semantic routing
     */
    private void modifyRequestForSemanticRoute(MessageContext messageContext, ModelEndpointDTO targetEndpoint) {
        try {
            org.apache.axis2.context.MessageContext axis2Ctx =
                    ((Axis2MessageContext) messageContext).getAxis2MessageContext();
            
            // First, try to modify JSON payload - most common case
            String jsonPayload = JsonUtil.jsonPayloadToString(axis2Ctx);
            if (jsonPayload != null && !jsonPayload.isEmpty()) {
                RelayUtils.buildMessage(axis2Ctx);
                
                // Try common model paths in the payload
                String modifiedPayload = null;
                try {
                    modifiedPayload = JsonPath.parse(jsonPayload)
                            .set("$.model", targetEndpoint.getModel()).jsonString();
                } catch (Exception e1) {
                    try {
                        modifiedPayload = JsonPath.parse(jsonPayload)
                                .set("$.options.model", targetEndpoint.getModel()).jsonString();
                    } catch (Exception e2) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Common model paths not found in payload");
                        }
                    }
                }
                
                if (modifiedPayload != null) {
                    JsonUtil.getNewJsonPayload(axis2Ctx, modifiedPayload, true, true);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Modified request payload with model: " + targetEndpoint.getModel());
                    }
                    return;
                }
            }
            
            // If payload modification failed, try path modification
            String requestPath = (String) axis2Ctx.getProperty(NhttpConstants.REST_URL_POSTFIX);
            if (requestPath != null && !requestPath.isEmpty()) {
                URI uri = URI.create(requestPath);
                String rawPath = uri.getRawPath();
                String rawQuery = uri.getRawQuery();
                
                // Encode the model name properly for URL path segments (handles special chars, colons, etc.)
                String encodedModel = encodePathSegmentRFC3986(targetEndpoint.getModel());
                
                String modifiedPath = rawPath;
                
                // Handle multiple path patterns:
                // 1. Google Gemini style: /models/MODEL_NAME:generateContent or /models/MODEL_NAME:streamGenerateContent
                if (rawPath.matches(".*/models/[^/:]+:[^/]+.*")) {
                    modifiedPath = rawPath.replaceAll("/models/[^/:]+:", 
                            "/models/" + java.util.regex.Matcher.quoteReplacement(encodedModel) + ":");
                }
                // 2. OpenAI/Mistral style: /v1/models/MODEL_NAME or /models/MODEL_NAME
                else if (rawPath.contains("/models/")) {
                    modifiedPath = rawPath.replaceAll("/models/[^/]+", 
                            "/models/" + java.util.regex.Matcher.quoteReplacement(encodedModel));
                }
                // 3. Amazon Bedrock style: /model/MODEL_NAME/action
                else if (rawPath.contains("/model/")) {
                    modifiedPath = rawPath.replaceAll("/model/[^/]+", 
                            "/model/" + java.util.regex.Matcher.quoteReplacement(encodedModel));
                }
                // 4. Azure/Vertex AI style: /deployments/MODEL_NAME or /publishers/.../models/MODEL_NAME
                else if (rawPath.contains("/deployments/")) {
                    modifiedPath = rawPath.replaceAll("/deployments/[^/]+", 
                            "/deployments/" + java.util.regex.Matcher.quoteReplacement(encodedModel));
                }
                
                StringBuilder finalPath = new StringBuilder(modifiedPath);
                if (rawQuery != null) {
                    finalPath.append("?").append(rawQuery);
                }
                
                axis2Ctx.setProperty(NhttpConstants.REST_URL_POSTFIX, finalPath.toString());
                if (logger.isDebugEnabled()) {
                    logger.debug("Modified request path from: " + requestPath + " to: " + finalPath);
                }
            }
        } catch (Exception e) {
            logger.error("Error modifying request for semantic route", e);
        }
    }

    /**
     * Encodes a path segment according to RFC 3986 standards.
     * This handles special characters in model names like colons, dots, etc.
     *
     * @param segment The path segment to encode
     * @return The encoded path segment
     */
    private String encodePathSegmentRFC3986(String segment) {
        if (logger.isDebugEnabled()) {
            logger.debug("Encoding path segment: " + segment);
        }
        StringBuilder out = new StringBuilder();
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            if (isUnreserved(c) || isSubDelim(c) || c == ':' || c == '@') {
                out.append(c);
            } else {
                out.append('%');
                String hx = Integer.toHexString(b & 0xFF).toUpperCase();
                if (hx.length() == 1) out.append('0');
                out.append(hx);
            }
        }
        return out.toString();
    }

    /**
     * Checks if the given character is an unreserved character as per RFC 3986.
     *
     * @param c The character to check
     * @return true if unreserved, false otherwise
     */
    private boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }

    /**
     * Checks if the given character is a sub-delimiter as per RFC 3986.
     *
     * @param c The character to check
     * @return true if sub-delimiter, false otherwise
     */
    private boolean isSubDelim(char c) {
        return c == '!' || c == '$' || c == '&' || c == '\'' || c == '(' || c == ')'
                || c == '*' || c == '+' || c == ',' || c == ';' || c == '=';
    }
}
