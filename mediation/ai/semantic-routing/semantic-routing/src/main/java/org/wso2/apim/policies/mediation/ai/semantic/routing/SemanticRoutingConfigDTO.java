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

import com.google.gson.annotations.SerializedName;
import org.wso2.carbon.apimgt.api.gateway.ModelEndpointDTO;

import java.util.List;

public class SemanticRoutingConfigDTO {

    private List<RouteConfig> production;
    
    private List<RouteConfig> sandbox;
    
    @SerializedName("Path")
    private PathConfig Path;
    
    @SerializedName("Default")
    private DefaultConfig Default;
    
    public static class PathConfig {
        private String contentpath;
        
        public String getContentpath() {
            return contentpath;
        }
        
        public void setContentpath(String contentpath) {
            this.contentpath = contentpath;
        }
    }
    
    public static class DefaultConfig {
        private String model;
        private String endpointId;
        
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

    public static class RouteConfig {
        private String model;
        
        private String endpointId;
        
        private List<String> utterances;
        
        private String scorethreshold;
        
        // Transient fields for runtime use
        private transient ModelEndpointDTO endpoint;
        private transient double scoreThreshold;
        private transient double[][] utteranceEmbeddings;

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

        public String getScorethreshold() {
            return scorethreshold;
        }

        public void setScorethreshold(String scorethreshold) {
            this.scorethreshold = scorethreshold;
            // Threshold parsing and validation handled in SemanticRouting.initializeRouteConfig()
            // No default threshold - user must provide valid value or route uses Default fallback
        }

        public double getScoreThreshold() {
            return scoreThreshold;
        }

        public void setScoreThreshold(double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
        }

        public double[][] getUtteranceEmbeddings() {
            return utteranceEmbeddings;
        }

        public void setUtteranceEmbeddings(double[][] utteranceEmbeddings) {
            this.utteranceEmbeddings = utteranceEmbeddings;
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
        return sandbox;
    }

    public void setSandbox(List<RouteConfig> sandbox) {
        this.sandbox = sandbox;
    }
    
    public DefaultConfig getDefault() {
        return Default;
    }
    
    public void setDefault(DefaultConfig Default) {
        this.Default = Default;
    }
    
    public PathConfig getPath() {
        return Path;
    }
    
    public void setPath(PathConfig Path) {
        this.Path = Path;
    }
}
