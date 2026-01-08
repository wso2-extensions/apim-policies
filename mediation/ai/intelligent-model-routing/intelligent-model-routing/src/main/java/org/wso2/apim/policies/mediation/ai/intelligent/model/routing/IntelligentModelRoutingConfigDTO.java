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

package org.wso2.apim.policies.mediation.ai.intelligent.model.routing;

import org.apache.commons.lang3.StringUtils;
import org.wso2.carbon.apimgt.api.gateway.ModelEndpointDTO;

import java.util.List;

public class IntelligentModelRoutingConfigDTO {

    private DeploymentConfigDTO production;
    private DeploymentConfigDTO sandbox;
    private ContentPathConfig contentPath;

    public static class DeploymentConfigDTO {
        private ModelEndpointDTO defaultModel;
        private List<RoutingRuleDTO> routingrules;

        public ModelEndpointDTO getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(ModelEndpointDTO defaultModel) {
            this.defaultModel = defaultModel;
        }

        public List<RoutingRuleDTO> getRoutingrules() {
            return routingrules;
        }

        public void setRoutingrules(List<RoutingRuleDTO> routingrules) {
            this.routingrules = routingrules;
        }
    }

    public static class ContentPathConfig {
        private String path;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }

    public static class RoutingRuleDTO {
        private String name;
        private String context;
        private String model;
        private String endpointId;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getContext() {
            return context;
        }

        public void setContext(String context) {
            this.context = context;
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

        public boolean isValid() {
            return !StringUtils.isEmpty(name)
                    && !StringUtils.isEmpty(model)
                    && !StringUtils.isEmpty(endpointId);
        }
    }

    public DeploymentConfigDTO getProduction() {
        return production;
    }

    public void setProduction(DeploymentConfigDTO production) {
        this.production = production;
    }

    public DeploymentConfigDTO getSandbox() {
        return sandbox;
    }

    public void setSandbox(DeploymentConfigDTO sandbox) {
        this.sandbox = sandbox;
    }

    public ContentPathConfig getContentPath() {
        return contentPath;
    }

    public void setContentPath(ContentPathConfig contentPath) {
        this.contentPath = contentPath;
    }
}
