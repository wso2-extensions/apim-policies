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

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import org.wso2.carbon.apimgt.api.gateway.ModelEndpointDTO;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SemanticRoutingConfigDTO {

    private List<RouteConfig> production;
    
    @SerializedName("sandbox")
    private Object sandboxRaw; // Can be either object or array
    
    private transient List<RouteConfig> sandbox;
    
    @SerializedName("Default")
    private List<RouteConfig> defaultRoute;

    public static class RouteConfig {
        private String model;
        
        @SerializedName("endpointId")
        private String endpointId;
        
        private List<String> utterances;
        
        @SerializedName("scorethreshold")
        private String scoreThresholdStr;
        
        // Transient fields for runtime use
        private transient ModelEndpointDTO endpoint;
        private transient double scoreThreshold;
        private transient double[][] centroids; // Multi-centroid embeddings (K sub-centroids)
        private transient int numCentroids; // Number of sub-centroids for this route

        public ModelEndpointDTO getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(ModelEndpointDTO endpoint) {
            this.endpoint = endpoint;
        }

        public List<String> getUtterances() {
            return utterances;
        }

        public void setUtterances(List<String> utterances) {
            this.utterances = utterances;
        }

        public String getScoreThresholdStr() {
            return scoreThresholdStr;
        }

        public void setScoreThresholdStr(String scoreThresholdStr) {
            this.scoreThresholdStr = scoreThresholdStr;
            // Threshold parsing and validation handled in SemanticRouting.initializeRouteConfig()
            // No default threshold - user must provide valid value or route uses Default fallback
        }

        public double getScoreThreshold() {
            return scoreThreshold;
        }

        public void setScoreThreshold(double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
        }

        public double[][] getCentroids() {
            return centroids;
        }

        public void setCentroids(double[][] centroids) {
            this.centroids = centroids;
            this.numCentroids = (centroids != null) ? centroids.length : 0;
        }
        
        public int getNumCentroids() {
            return numCentroids;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getEndpointId() {
            return endpointId;
        }

        public void setEndpointId(String endpointId) {
            this.endpointId = endpointId;
        }
    }

    public List<RouteConfig> getProduction() {
        return production;
    }

    public void setProduction(List<RouteConfig> production) {
        this.production = production;
    }

    public List<RouteConfig> getSandbox() {
        if (sandbox == null && sandboxRaw != null) {
            // Parse sandboxRaw on first access
            Gson gson = new Gson();
            if (sandboxRaw instanceof JsonObject) {
                // Single object - convert to list
                RouteConfig config = gson.fromJson((JsonObject) sandboxRaw, RouteConfig.class);
                sandbox = Collections.singletonList(config);
            } else if (sandboxRaw instanceof JsonArray) {
                // Array of objects
                Type listType = new TypeToken<List<RouteConfig>>(){}.getType();
                sandbox = gson.fromJson((JsonArray) sandboxRaw, listType);
            }
        }
        return sandbox;
    }

    public void setSandbox(List<RouteConfig> sandbox) {
        this.sandbox = sandbox;
    }
    
    public List<RouteConfig> getDefaultRoute() {
        return defaultRoute;
    }
    
    public void setDefaultRoute(List<RouteConfig> defaultRoute) {
        this.defaultRoute = defaultRoute;
    }
}
