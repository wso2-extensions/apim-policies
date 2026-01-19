/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
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
import com.google.gson.JsonSyntaxException;
import com.jayway.jsonpath.JsonPath;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.Mediator;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.AbstractMediator;
import org.wso2.apim.policies.mediation.ai.semantic.routing.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.EmbeddingProviderService;
import org.wso2.carbon.apimgt.api.gateway.ModelEndpointDTO;
import org.wso2.carbon.apimgt.api.APIConstants.AIAPIConstants;
import org.wso2.carbon.apimgt.impl.APIConstants;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mediator for AI API Semantic Routing.
 */
public class SemanticRouting extends AbstractMediator implements ManagedLifecycle {

    private static final Log log = LogFactory.getLog(SemanticRouting.class);

    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.90;
    private String semanticRoutingConfigs;
    private EmbeddingProviderService embeddingProvider;
    private SemanticRoutingConfigDTO routingConfig;

    /**
     * Initializes the mediator by loading the embedding provider and routing configuration.
     *
     * @param synapseEnvironment the Synapse environment
     */
    @Override
    public void init(SynapseEnvironment synapseEnvironment) {

        if (log.isDebugEnabled()) {
            log.debug("Initializing SemanticRouting mediator.");
        }
        embeddingProvider = ServiceReferenceHolder.getInstance().getEmbeddingProvider();
        if (embeddingProvider == null) {
            throw new IllegalStateException(SemanticRoutingConstants.ERROR_EMBEDDING_PROVIDER_UNAVAILABLE);
        }
        loadRoutingConfiguration(semanticRoutingConfigs);
    }

    /**
     * Parses and loads the semantic routing configuration from JSON.
     *
     * @param configJson the routing configuration in JSON format
     */
    private void loadRoutingConfiguration(String configJson) {

        try {
            this.routingConfig = new Gson().fromJson(configJson, SemanticRoutingConfigDTO.class);
            if (routingConfig == null) {
                throw new IllegalStateException(SemanticRoutingConstants.ERROR_CONFIG_PARSE_FAILED + ": null config");
            }

            if (routingConfig.getProduction() != null && routingConfig.getProduction().getRoutes() != null) {
                for (SemanticRoutingConfigDTO.RouteConfig route : routingConfig.getProduction().getRoutes()) {
                    initializeRouteConfig(route);
                }
            }
            if (routingConfig.getSandbox() != null && routingConfig.getSandbox().getRoutes() != null) {
                for (SemanticRoutingConfigDTO.RouteConfig route : routingConfig.getSandbox().getRoutes()) {
                    initializeRouteConfig(route);
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("Semantic routing configuration loaded successfully.");
            }
        } catch (JsonSyntaxException e) {
            throw new IllegalStateException(SemanticRoutingConstants.ERROR_CONFIG_PARSE_FAILED, e);
        }
    }

    /**
     * Initializes a route configuration by setting up the endpoint and precomputing embeddings.
     *
     * @param route the route configuration to initialize
     */
    private void initializeRouteConfig(SemanticRoutingConfigDTO.RouteConfig route) {

        if (!StringUtils.isEmpty(route.getModel()) && !StringUtils.isEmpty(route.getEndpointId())) {
            ModelEndpointDTO endpoint = new ModelEndpointDTO();
            endpoint.setModel(route.getModel());
            endpoint.setEndpointId(route.getEndpointId());
            route.setEndpoint(endpoint);
        }

        double threshold = DEFAULT_SIMILARITY_THRESHOLD;
        if (!StringUtils.isEmpty(route.getScorethreshold())) {
            try {
                double parsedThreshold = Double.parseDouble(route.getScorethreshold());
                if (parsedThreshold >= 0.0 && parsedThreshold <= 1.0) {
                    threshold = parsedThreshold;
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid score threshold value: " + route.getScorethreshold() + ", using default: " + DEFAULT_SIMILARITY_THRESHOLD, e);
            }
        }
        route.setScoreThreshold(threshold);

        precomputeEmbeddings(route);
    }

    /**
     * Precomputes embeddings for all utterances in a route to optimize runtime performance.
     *
     * @param route the route configuration containing utterances
     */
    private void precomputeEmbeddings(SemanticRoutingConfigDTO.RouteConfig route) {

        List<String> utterances = route.getUtterances();
        if (utterances == null || utterances.isEmpty()) {
            return;
        }

        try {
            double[][] utteranceEmbeddings = new double[utterances.size()][];
            for (int i = 0; i < utterances.size(); i++) {
                utteranceEmbeddings[i] = embeddingProvider.getEmbedding(utterances.get(i));
            }
            route.setUtteranceEmbeddings(utteranceEmbeddings);

            if (log.isDebugEnabled()) {
                log.debug("Precomputed " + utteranceEmbeddings.length + " embeddings for route: " + route.getModel());
            }
        } catch (APIManagementException e) {
            log.error("Failed to precompute embeddings for route: " + route.getModel(), e);
        }
    }

    @Override
    public void destroy() {
        // No cleanup required
    }

    /**
     * Mediates the request by analyzing semantic similarity and routing to the best matching model.
     *
     * @param messageContext the message context containing the request
     * @return true to continue the mediation flow
     */
    @Override
    public boolean mediate(MessageContext messageContext) {

        if (log.isDebugEnabled()) {
            log.debug("Starting semantic routing mediation.");
        }

        try {
            String apiKeyType = (String) messageContext.getProperty(APIConstants.API_KEY_TYPE);
            SemanticRoutingConfigDTO.EnvironmentConfig environmentConfig = APIConstants.API_KEY_TYPE_PRODUCTION.equals(apiKeyType) ? routingConfig.getProduction() : routingConfig.getSandbox();

            if (environmentConfig == null) {
                if (log.isDebugEnabled()) {
                    log.debug("SemanticRouting policy is not set for " + apiKeyType + ", bypassing mediation.");
                }
                return true;
            }

            List<SemanticRoutingConfigDTO.RouteConfig> routes = environmentConfig.getRoutes();
            String userRequestContent = extractUserRequest(messageContext);
            if (StringUtils.isEmpty(userRequestContent)) {
                if (log.isDebugEnabled()) {
                    log.debug("User request content is empty, routing to default.");
                }
                return routeToDefault(messageContext, environmentConfig);
            }

            if (routes == null || routes.isEmpty()) {
                return routeToDefault(messageContext, environmentConfig);
            }

            double[] requestEmbedding = embeddingProvider.getEmbedding(userRequestContent);
            if (requestEmbedding == null) {
                log.error(SemanticRoutingConstants.ERROR_EMBEDDING_COMPUTATION);
                return routeToDefault(messageContext, environmentConfig);
            }

            RouteMatch bestMatch = findBestRoute(routes, requestEmbedding);
            if (bestMatch.route != null && bestMatch.score >= bestMatch.route.getScoreThreshold()) {
                applyRoute(messageContext, bestMatch.route.getEndpoint());
            } else {
                return routeToDefault(messageContext, environmentConfig);
            }
        } catch (APIManagementException e) {
            log.error(SemanticRoutingConstants.ERROR_EMBEDDING_COMPUTATION, e);
            return handleError(messageContext, SemanticRoutingConstants.APIM_INTERNAL_EXCEPTION_CODE, SemanticRoutingConstants.ERROR_EMBEDDING_COMPUTATION + ": " + e.getMessage());
        } catch (Exception e) {
            log.error("Exception during semantic routing.", e);
            return handleError(messageContext, SemanticRoutingConstants.APIM_INTERNAL_EXCEPTION_CODE, "Error during semantic routing: " + e.getMessage());
        }
        return true;
    }

    private static class RouteMatch {

        SemanticRoutingConfigDTO.RouteConfig route;
        double score;
    }

    /**
     * Finds the best matching route by comparing request embedding with precomputed utterance embeddings.
     *
     * @param routes           the list of available routes
     * @param requestEmbedding the embedding vector of the user request
     * @return the best matching route with its similarity score
     */
    private RouteMatch findBestRoute(List<SemanticRoutingConfigDTO.RouteConfig> routes, double[] requestEmbedding) {

        RouteMatch bestMatch = new RouteMatch();

        for (SemanticRoutingConfigDTO.RouteConfig route : routes) {
            double[][] utteranceEmbeddings = route.getUtteranceEmbeddings();
            if (utteranceEmbeddings == null || utteranceEmbeddings.length == 0) {
                continue;
            }

            double similarityScore = computeMaxCosineSimilarity(requestEmbedding, utteranceEmbeddings);

            if (log.isDebugEnabled()) {
                log.debug("Route: " + route.getModel() + ", Score: " + String.format("%.4f", similarityScore) + ", Threshold: " + route.getScoreThreshold());
            }

            if (similarityScore > bestMatch.score) {
                bestMatch.score = similarityScore;
                bestMatch.route = route;
            }
        }
        return bestMatch;
    }

    /**
     * Computes the maximum cosine similarity between the request and all utterance embeddings.
     */
    private double computeMaxCosineSimilarity(double[] requestEmbedding, double[][] utteranceEmbeddings) {

        double maxSimilarity = 0.0;
        for (double[] utteranceEmbedding : utteranceEmbeddings) {
            double similarity = cosineSimilarity(requestEmbedding, utteranceEmbedding);
            if (similarity > maxSimilarity) {
                maxSimilarity = similarity;
            }
        }
        return maxSimilarity;
    }

    /**
     * Calculates the cosine similarity between two embedding vectors.
     */
    private double cosineSimilarity(double[] vectorA, double[] vectorB) {

        if (vectorA == null || vectorB == null || vectorA.length != vectorB.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0.0 ? 0.0 : dotProduct / denominator;
    }

    /**
     * Applies the selected route by setting the target endpoint.
     */
    private boolean applyRoute(MessageContext messageContext, ModelEndpointDTO targetEndpoint) {

        if (targetEndpoint == null || StringUtils.isEmpty(targetEndpoint.getEndpointId())) {
            log.warn("Target endpoint is null or has empty endpoint ID");
            messageContext.setProperty(AIAPIConstants.TARGET_ENDPOINT, AIAPIConstants.REJECT_ENDPOINT);
            return true;
        }

        messageContext.setProperty(AIAPIConstants.TARGET_ENDPOINT, targetEndpoint.getEndpointId());

        Map<String, Object> routingConfigs = new HashMap<>();
        routingConfigs.put(AIAPIConstants.TARGET_MODEL_ENDPOINT, targetEndpoint);
        messageContext.setProperty(AIAPIConstants.SEMANTIC_ROUTING_CONFIGS, routingConfigs);

        if (log.isDebugEnabled()) {
            log.debug("Routed to model: " + targetEndpoint.getModel());
        }
        return true;
    }

    /**
     * Routes the request to the default model when no suitable match is found.
     */
    private boolean routeToDefault(MessageContext messageContext, SemanticRoutingConfigDTO.EnvironmentConfig environmentConfig) {

        if (environmentConfig == null || environmentConfig.getDefaultModel() == null) {
            log.warn(SemanticRoutingConstants.ERROR_NO_ROUTE_FOUND);
            messageContext.setProperty(AIAPIConstants.TARGET_ENDPOINT, AIAPIConstants.REJECT_ENDPOINT);
            return true;
        }

        SemanticRoutingConfigDTO.DefaultConfig defaultModelConfig = environmentConfig.getDefaultModel();
        if (StringUtils.isEmpty(defaultModelConfig.getEndpointId())) {
            log.warn("Default model endpoint ID is empty");
            messageContext.setProperty(AIAPIConstants.TARGET_ENDPOINT, AIAPIConstants.REJECT_ENDPOINT);
            return true;
        }

        ModelEndpointDTO defaultEndpoint = new ModelEndpointDTO();
        defaultEndpoint.setModel(defaultModelConfig.getModel());
        defaultEndpoint.setEndpointId(defaultModelConfig.getEndpointId());

        messageContext.setProperty(AIAPIConstants.TARGET_ENDPOINT, defaultEndpoint.getEndpointId());

        Map<String, Object> routingConfigs = new HashMap<>();
        routingConfigs.put(AIAPIConstants.TARGET_MODEL_ENDPOINT, defaultEndpoint);
        messageContext.setProperty(AIAPIConstants.SEMANTIC_ROUTING_CONFIGS, routingConfigs);

        if (log.isDebugEnabled()) {
            log.debug("Routed to default model: " + defaultEndpoint.getModel());
        }
        return true;
    }

    /**
     * Handles errors by setting error properties and triggering the fault sequence.
     */
    private boolean handleError(MessageContext messageContext, int errorCode, String errorMessage) {

        messageContext.setProperty(SynapseConstants.ERROR_CODE, errorCode);
        messageContext.setProperty(SemanticRoutingConstants.ERROR_TYPE, SemanticRoutingConstants.SEMANTIC_ROUTING);
        messageContext.setProperty(SemanticRoutingConstants.CUSTOM_HTTP_SC, SemanticRoutingConstants.ROUTING_ERROR_CODE);
        messageContext.setProperty(SynapseConstants.ERROR_MESSAGE, errorMessage);

        if (log.isDebugEnabled()) {
            log.debug("Error in semantic routing - triggering fault sequence. Error: " + errorMessage);
        }

        Mediator faultMediator = messageContext.getSequence(SemanticRoutingConstants.FAULT_SEQUENCE_KEY);
        if (faultMediator == null) {
            faultMediator = messageContext.getFaultSequence();
        }

        if (faultMediator != null) {
            faultMediator.mediate(messageContext);
        }
        return false;
    }

    /**
     * Extracts the user request content from the message payload using the configured JSON path.
     */
    private String extractUserRequest(MessageContext messageContext) {

        if (routingConfig.getPath() == null || StringUtils.isEmpty(routingConfig.getPath().getContentpath())) {
            if (log.isDebugEnabled()) {
                log.debug(SemanticRoutingConstants.ERROR_CONTENT_PATH_NOT_CONFIGURED);
            }
            return SemanticRoutingConstants.EMPTY_RESULT;
        }

        org.apache.axis2.context.MessageContext axis2MessageContext = ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        String requestPayload = JsonUtil.jsonPayloadToString(axis2MessageContext);

        if (StringUtils.isEmpty(requestPayload)) {
            if (log.isDebugEnabled()) {
                log.debug(SemanticRoutingConstants.ERROR_EMPTY_PAYLOAD);
            }
            return SemanticRoutingConstants.EMPTY_RESULT;
        }

        String jsonPath = routingConfig.getPath().getContentpath();
        try {
            Object result = JsonPath.read(requestPayload, jsonPath);
            return result != null ? result.toString() : SemanticRoutingConstants.EMPTY_RESULT;
        } catch (Exception e) {
            log.warn(SemanticRoutingConstants.ERROR_JSON_PATH_PARSE + " '" + jsonPath + "': " + e.getMessage());
            return SemanticRoutingConstants.EMPTY_RESULT;
        }
    }

    public String getSemanticRoutingConfigs() {

        return semanticRoutingConfigs;
    }

    public void setSemanticRoutingConfigs(String semanticRoutingConfigs) {

        this.semanticRoutingConfigs = semanticRoutingConfigs;
    }
}
