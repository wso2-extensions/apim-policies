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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.AbstractMediator;
import org.wso2.apim.policies.mediation.ai.semantic.routing.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.EmbeddingProviderService;
import org.wso2.carbon.apimgt.api.gateway.ModelEndpointDTO;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.api.APIConstants.AIAPIConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SemanticRouting extends AbstractMediator implements ManagedLifecycle {
    private static final Log logger = LogFactory.getLog(SemanticRouting.class);
    private ExecutorService executor;

    private String semanticRoutingConfigs;
    private int embeddingDimension;
    private EmbeddingProviderService embeddingProvider;

    private SemanticRoutingConfigDTO config;

    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        if (logger.isDebugEnabled()) {
            logger.debug("Initializing SemanticRouting.");
        }

        executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        try {
            embeddingProvider = ServiceReferenceHolder.getInstance().getEmbeddingProvider();

            if (embeddingProvider == null) {
                throw new IllegalStateException("Embedding provider is not registered or available");
            }

            embeddingDimension = embeddingProvider.getEmbeddingDimension();
        } catch (APIManagementException e) {
            throw new IllegalStateException("Failed to initialize Semantic Routing", e);
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
                    precomputeEmbeddings(routeConfig);
                }
            }

            if (config.getSandbox() != null) {
                for (SemanticRoutingConfigDTO.RouteConfig routeConfig : config.getSandbox()) {
                    initializeRouteConfig(routeConfig);
                    precomputeEmbeddings(routeConfig);
                }
            }
            
            // Initialize default route if provided
            if (config.getDefaultRoute() != null && !config.getDefaultRoute().isEmpty()) {
                for (SemanticRoutingConfigDTO.RouteConfig routeConfig : config.getDefaultRoute()) {
                    initializeRouteConfig(routeConfig);
                    // No need to precompute embeddings for default route as it has no utterances
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
        // Create ModelEndpointDTO from model and endpointId fields
        if (routeConfig.getModel() != null && routeConfig.getEndpointId() != null) {
            ModelEndpointDTO endpoint = new ModelEndpointDTO();
            endpoint.setModel(routeConfig.getModel());
            endpoint.setEndpointId(routeConfig.getEndpointId());
            routeConfig.setEndpoint(endpoint);
        }
        
        // Parse scoreThreshold from user input (no default - user must provide or route goes to Default)
        if (routeConfig.getScoreThresholdStr() != null && !routeConfig.getScoreThresholdStr().trim().isEmpty()) {
            try {
                double threshold = Double.parseDouble(routeConfig.getScoreThresholdStr());
                if (threshold < 0.0 || threshold > 1.0) {
                    logger.warn("Score threshold out of range [0.0-1.0]: " + threshold + 
                               " for model: " + routeConfig.getModel() + ". Route will use Default fallback.");
                    routeConfig.setScoreThreshold(-1.0); // Invalid - will always fail threshold check
                } else {
                    routeConfig.setScoreThreshold(threshold);
                }
            } catch (NumberFormatException e) {
                logger.warn("Invalid score threshold format: " + routeConfig.getScoreThresholdStr() + 
                           " for model: " + routeConfig.getModel() + ". Route will use Default fallback.");
                routeConfig.setScoreThreshold(-1.0); // Invalid - will always fail threshold check
            }
        } else {
            // No threshold provided - this route will never match, always use Default
            routeConfig.setScoreThreshold(Double.MAX_VALUE); // Impossible to reach
            if (logger.isDebugEnabled()) {
                logger.debug("No score threshold provided for model: " + routeConfig.getModel() + 
                           ". This route requires Default fallback.");
            }
        }
    }

    private void precomputeEmbeddings(SemanticRoutingConfigDTO.RouteConfig routeConfig) throws APIManagementException {
        List<String> utterances = routeConfig.getUtterances();
        
        if (utterances == null || utterances.isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("No utterances provided for route: " + routeConfig.getModel());
            }
            return;
        }
        
        // Generate embeddings for all utterances
        double[][] embeddings = new double[utterances.size()][embeddingDimension];
        
        for (int i = 0; i < utterances.size(); i++) {
            double[] embedding = embeddingProvider.getEmbedding(utterances.get(i));
            System.arraycopy(embedding, 0, embeddings[i], 0, embeddingDimension);
        }
        
        // Apply K-Means clustering to create multi-centroids
        int optimalK = KMeansClustering.determineOptimalK(embeddings.length);
        double[][] centroids = KMeansClustering.cluster(embeddings, optimalK, 
                                                         routeConfig.getModel().hashCode());
        
        routeConfig.setCentroids(centroids);
        
        if (logger.isDebugEnabled()) {
            logger.debug("Created " + centroids.length + " sub-centroids for route: " + 
                        routeConfig.getModel() + " from " + utterances.size() + " utterances");
        }
    }


    @Override
    public void destroy() {
        executor.shutdown();
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
                if (logger.isDebugEnabled()) {
                    logger.debug("Semantic routing policy is not set for " + apiKeyType + ", bypassing mediation.");
                }
                return true;
            }

            String userRequest = extractUserRequest(messageContext);
            if (userRequest == null || userRequest.isEmpty()) {
                return true;
            }
            
            if (logger.isDebugEnabled()) {
                logger.debug("User request extracted for embedding: " + userRequest);
            }

            double[] requestEmbedding = embeddingProvider.getEmbedding(userRequest);

            // Find the SINGLE best matching route above its threshold
            // Also track second-best score to enforce confidence gap
            SemanticRoutingConfigDTO.RouteConfig matchedRoute = null;
            double highestScore = 0.0;
            double secondHighestScore = 0.0;
            
            // Minimum confidence gap required (winner must be this much better than second place)
            final double CONFIDENCE_GAP = 0.10; // 10% difference required

            for (SemanticRoutingConfigDTO.RouteConfig route : routeConfigs) {
                // Skip routes without centroids or with invalid thresholds
                if (route.getCentroids() == null || route.getCentroids().length == 0) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Skipping route " + route.getModel() + " - no centroids available");
                    }
                    continue;
                }
                
                // Skip routes with invalid thresholds (negative or impossible values)
                if (route.getScoreThreshold() < 0.0 || route.getScoreThreshold() > 1.0) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Skipping route " + route.getModel() + " - invalid threshold: " + route.getScoreThreshold());
                    }
                    continue;
                }
                
                double maxScore = calculateMaxSimilarity(requestEmbedding, route.getCentroids());

                if (logger.isDebugEnabled()) {
                    logger.debug("Route: " + route.getModel() + ", Score: " + maxScore + 
                               ", Threshold: " + route.getScoreThreshold() + 
                               ", Pass: " + (maxScore >= route.getScoreThreshold()));
                }

                // Only consider routes where score meets user-defined threshold
                if (maxScore >= route.getScoreThreshold()) {
                    if (maxScore > highestScore) {
                        secondHighestScore = highestScore;
                        highestScore = maxScore;
                        matchedRoute = route;
                    } else if (maxScore > secondHighestScore) {
                        secondHighestScore = maxScore;
                    }
                }
            }
            
            // Enforce confidence gap: winner must be significantly better than second place
            if (matchedRoute != null && (highestScore - secondHighestScore) < CONFIDENCE_GAP) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Confidence gap too small (highest: " + highestScore + 
                               ", second: " + secondHighestScore + 
                               ", gap: " + (highestScore - secondHighestScore) + 
                               "), routing to default");
                }
                matchedRoute = null; // Clear match, will route to default
            }

            if (matchedRoute != null) {
                ModelEndpointDTO targetEndpoint = matchedRoute.getEndpoint();
                
                if (targetEndpoint != null) {
                    // Set the target endpoint ID as a string for routing
                    messageContext.setProperty(AIAPIConstants.TARGET_ENDPOINT, targetEndpoint.getEndpointId());
                    
                    if (logger.isDebugEnabled()) {
                        logger.debug("Set TARGET_ENDPOINT property: " + targetEndpoint.getEndpointId());
                    }
                    
                    // Also store in configs map for additional context
                    Map<String, Object> configs = new HashMap<>();
                    configs.put(AIAPIConstants.TARGET_MODEL_ENDPOINT, targetEndpoint);
                    messageContext.setProperty(SemanticRoutingConstants.SEMANTIC_ROUTING_CONFIGS, configs);

                    if (logger.isDebugEnabled()) {
                        logger.debug("Routed to model: " + targetEndpoint.getModel() + ", endpoint: " + targetEndpoint.getEndpointId() + ", score: " + highestScore);
                    }
                } else {
                    logger.warn("Endpoint is null in matched route configuration");
                    messageContext.setProperty(AIAPIConstants.TARGET_ENDPOINT, AIAPIConstants.REJECT_ENDPOINT);
                }
            } else {
                // No match found or score threshold not met, route to default
                if (config.getDefaultRoute() != null && !config.getDefaultRoute().isEmpty()) {
                    SemanticRoutingConfigDTO.RouteConfig defaultRoute = config.getDefaultRoute().get(0);
                    ModelEndpointDTO defaultEndpoint = defaultRoute.getEndpoint();
                    
                    if (defaultEndpoint != null) {
                        messageContext.setProperty(AIAPIConstants.TARGET_ENDPOINT, defaultEndpoint.getEndpointId());
                        
                        Map<String, Object> configs = new HashMap<>();
                        configs.put(AIAPIConstants.TARGET_MODEL_ENDPOINT, defaultEndpoint);
                        messageContext.setProperty(SemanticRoutingConstants.SEMANTIC_ROUTING_CONFIGS, configs);
                        
                        if (logger.isDebugEnabled()) {
                            logger.debug("No match found (highest score: " + highestScore + 
                                       "), routing to default model: " + defaultEndpoint.getModel() + 
                                       ", endpoint: " + defaultEndpoint.getEndpointId());
                        }
                    } else {
                        logger.warn("Default endpoint is null, no routing applied");
                    }
                } else {
                    if (logger.isDebugEnabled()) {
                        logger.debug("No match found (highest score: " + highestScore + ") and no default route configured");
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Exception occurred during semantic routing.", e);
            return false;
        }

        return true;
    }


    private String extractUserRequest(MessageContext messageContext) {
        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        String jsonPayload = JsonUtil.jsonPayloadToString(axis2MC);
        
        if (jsonPayload == null || jsonPayload.isEmpty()) {
            return null;
        }
        
        try {
            Gson gson = new Gson();
            JsonObject payload = gson.fromJson(jsonPayload, JsonObject.class);
            
            // Extract content from messages array
            if (payload.has("messages") && payload.get("messages").isJsonArray()) {
                JsonArray messages = payload.getAsJsonArray("messages");
                
                // Look for the last user message
                for (int i = messages.size() - 1; i >= 0; i--) {
                    JsonObject message = messages.get(i).getAsJsonObject();
                    if (message.has("content")) {
                        String content = message.get("content").getAsString();
                        if (content != null && !content.isEmpty()) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Extracted user request: " + content);
                            }
                            return content;
                        }
                    }
                }
            }
            
            // Fallback: if no messages array, return the whole payload
            return jsonPayload;
            
        } catch (Exception e) {
            logger.warn("Failed to parse request JSON, using raw payload", e);
            return jsonPayload;
        }
    }

    /**
     * Calculate maximum similarity against all centroids (multi-centroid routing).
     * Compares the user request embedding against all sub-centroids and returns the highest score.
     */
    private double calculateMaxSimilarity(double[] requestEmbedding, double[][] centroids) {
        if (centroids == null || centroids.length == 0) {
            return 0.0;
        }
        
        double maxScore = 0.0;
        double requestNorm = l2Norm(requestEmbedding);

        List<CompletableFuture<Double>> tasks = new ArrayList<>();

        // Compare against each centroid (sub-centroid)
        for (int i = 0; i < centroids.length; i++) {
            final int index = i;
            tasks.add(CompletableFuture.supplyAsync(() -> {
                double[] centroid = centroids[index];
                double dot = dotProduct(requestEmbedding, centroid);
                double centroidNorm = l2Norm(centroid);
                double denominator = requestNorm * centroidNorm;
                return (denominator == 0.0) ? 0.0 : (dot / denominator);
            }, executor));
        }

        for (CompletableFuture<Double> task : tasks) {
            try {
                double score = task.get();
                if (score > maxScore) {
                    maxScore = score;
                }
            } catch (Exception e) {
                logger.error("Error calculating similarity", e);
            }
        }

        return maxScore;
    }


    private double dotProduct(double[] a, double[] b) {
        double result = 0.0;
        for (int i = 0; i < a.length; i++) {
            result += a[i] * b[i];
        }
        return result;
    }

    private double l2Norm(double[] vector) {
        double sum = 0.0;
        for (double v : vector) {
            sum += v * v;
        }
        return Math.sqrt(sum);
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
}
