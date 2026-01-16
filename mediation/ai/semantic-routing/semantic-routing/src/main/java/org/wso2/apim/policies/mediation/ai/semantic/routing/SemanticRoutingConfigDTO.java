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

import org.wso2.carbon.apimgt.api.gateway.ModelEndpointDTO;

import java.util.List;

public class SemanticRoutingConfigDTO {

    private EnvironmentConfig production;

    private EnvironmentConfig sandbox;

    private PathConfig path;

    public static class EnvironmentConfig {

        private DefaultConfig defaultModel;
        private List<RouteConfig> routes;

        public DefaultConfig getDefaultModel() {

            return defaultModel;
        }

        public void setDefaultModel(DefaultConfig defaultModel) {

            this.defaultModel = defaultModel;
        }

        public List<RouteConfig> getRoutes() {

            return routes;
        }

        public void setRoutes(List<RouteConfig> routes) {

            this.routes = routes;
        }
    }

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

    public EnvironmentConfig getProduction() {

        return production;
    }

    public void setProduction(EnvironmentConfig production) {

        this.production = production;
    }

    public EnvironmentConfig getSandbox() {

        return sandbox;
    }

    public void setSandbox(EnvironmentConfig sandbox) {

        this.sandbox = sandbox;
    }

    public PathConfig getPath() {

        return path;
    }

    public void setPath(PathConfig path) {

        this.path = path;
    }
}
