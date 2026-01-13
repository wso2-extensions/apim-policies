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

package org.wso2.apim.policies.mediation.ai.intelligent.model.routing;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.AbstractMediator;
import org.wso2.apim.policies.mediation.ai.intelligent.model.routing.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.api.APIConstants.AIAPIConstants;
import org.wso2.carbon.apimgt.api.LLMProviderServiceForChatCompletion;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.api.gateway.ModelEndpointDTO;

import com.jayway.jsonpath.JsonPath;
import org.apache.commons.lang3.StringUtils;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.List;

/**
 * Mediator for AI API Intelligent Model Routing using LLM-based classification.
 */
public class IntelligentModelRouting extends AbstractMediator implements ManagedLifecycle {
    private static final Log log = LogFactory.getLog(IntelligentModelRouting.class);

    private String intelligentModelRoutingConfigs;
    private LLMProviderServiceForChatCompletion llmProvider;

    public void setIntelligentModelRoutingConfigs(String intelligentModelRoutingConfigs) {
        this.intelligentModelRoutingConfigs = intelligentModelRoutingConfigs;
    }

    /**
     * Initializes the mediator by loading the LLM provider service.
     *
     * @param synapseEnvironment the Synapse environment
     */
    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        if (log.isDebugEnabled()) {
            log.debug("Initializing IntelligentModelRouting.");
        }
        llmProvider = ServiceReferenceHolder.getInstance().getLLMProvider();
        if (llmProvider == null) {
            throw new IllegalStateException("LLM provider (classifier) is not registered or available");
        }
    }

    /**
     * Mediates the request by classifying it using LLM and routing to the appropriate model.
     *
     * @param messageContext the message context containing the request
     * @return true to continue the mediation flow, false on critical errors
     */
    @Override
    public boolean mediate(MessageContext messageContext) {
        try {
            if (!hasConfiguration()) {
                return true;
            }

            IntelligentModelRoutingConfigDTO policyConfig = parseConfiguration(intelligentModelRoutingConfigs);
            if (policyConfig == null) {
                return false;
            }

            return processRouting(messageContext, policyConfig);
        } catch (Exception e) {
            log.error("Error in IntelligentModelRouting mediation", e);
            return false;
        }
    }


    private boolean hasConfiguration() {
        return !StringUtils.isEmpty(intelligentModelRoutingConfigs);
    }

    /**
     * Processes the routing logic by classifying the request and selecting the appropriate endpoint.
     *
     * @param messageContext the message context
     * @param policyConfig the policy configuration
     * @return true to continue mediation
     */
    private boolean processRouting(MessageContext messageContext, IntelligentModelRoutingConfigDTO policyConfig) {
        String apiKeyType = (String) messageContext.getProperty(APIConstants.API_KEY_TYPE);
        IntelligentModelRoutingConfigDTO.DeploymentConfigDTO targetConfig = getTargetConfig(messageContext, policyConfig);

        if (targetConfig == null) {
            if (log.isDebugEnabled()) {
                log.debug("IntelligentModelRouting policy is not set for " + apiKeyType + ", bypassing mediation.");
            }
            return true;
        }
        
        String classifiedRouteRule = classifyRequest(messageContext, policyConfig, targetConfig);
        ModelEndpointDTO selectedEndpoint = selectEndpointForRouteRule(targetConfig, classifiedRouteRule);

        if (selectedEndpoint != null && isValidEndpoint(selectedEndpoint)) {
            setEndpointProperties(messageContext, selectedEndpoint, policyConfig);
        } else {
            log.warn("No valid endpoint found for routing");
            messageContext.setProperty(AIAPIConstants.TARGET_ENDPOINT, AIAPIConstants.REJECT_ENDPOINT);
        }
        return true;
    }

    /**
     * Validates whether an endpoint has the required model and endpoint ID.
     *
     * @param endpoint the endpoint to validate
     * @return true if the endpoint is valid
     */
    private boolean isValidEndpoint(ModelEndpointDTO endpoint) {
        return endpoint != null 
                && !StringUtils.isEmpty(endpoint.getModel())
                && !StringUtils.isEmpty(endpoint.getEndpointId());
    }

    /**
     * Selects the appropriate endpoint based on the classified route rule.
     *
     * @param targetConfig the deployment configuration
     * @param routeRuleName the classified route rule name
     * @return the selected endpoint, or default endpoint if no match found
     */
    private ModelEndpointDTO selectEndpointForRouteRule(IntelligentModelRoutingConfigDTO.DeploymentConfigDTO targetConfig, 
                                                        String routeRuleName) {
        if (!StringUtils.isEmpty(routeRuleName) && targetConfig.getRoutingrules() != null) {
            for (IntelligentModelRoutingConfigDTO.RoutingRuleDTO routeRule : targetConfig.getRoutingrules()) {
                if (routeRule != null && routeRule.getName() != null && routeRule.getName().equals(routeRuleName)) {
                    if (isValidRouteRuleEndpoint(routeRule)) {
                        ModelEndpointDTO endpoint = new ModelEndpointDTO();
                        endpoint.setModel(routeRule.getModel());
                        endpoint.setEndpointId(routeRule.getEndpointId());
                        log.info("Selected route rule '" + routeRuleName + "' with model: " + routeRule.getModel());
                        return endpoint;
                    }
                }
            }
        }
        
        if (targetConfig.getDefaultModel() != null) {
            log.info("Using default model: " + targetConfig.getDefaultModel().getModel());
        }
        return targetConfig.getDefaultModel();
    }

    private boolean isValidRouteRuleEndpoint(IntelligentModelRoutingConfigDTO.RoutingRuleDTO routeRule) {
        return !StringUtils.isEmpty(routeRule.getModel())
                && !StringUtils.isEmpty(routeRule.getEndpointId());
    }

    /**
     * Parses the routing configuration from JSON format.
     *
     * @param config the configuration in JSON format
     * @return the parsed configuration object, or null on error
     */
    private IntelligentModelRoutingConfigDTO parseConfiguration(String config) {
        try {
            IntelligentModelRoutingConfigDTO endpoints = new Gson().fromJson(config, IntelligentModelRoutingConfigDTO.class);
            if (endpoints == null) {
                log.error("Failed to parse intelligent model routing configuration: null config");
            }
            return endpoints;
        } catch (JsonSyntaxException e) {
            log.error("Failed to parse intelligent model routing configuration", e);
            return null;
        }
    }

    /**
     * Retrieves the target configuration based on the API key type (production or sandbox).
     *
     * policyConfig contains BOTH production and sandbox configurations.
     * This method selects the appropriate one based on the current request's environment.
     *
     * Flow:
     *   policyConfig (full config with both environments)
     *       ↓ select based on apiKeyType
     *   targetConfig (environment-specific config: either production OR sandbox)
     *
     * @param messageContext the message context containing the API key type property
     * @param policyConfig the full policy configuration containing both production and sandbox configs
     * @return the deployment configuration for the current environment (production or sandbox)
     */
    private IntelligentModelRoutingConfigDTO.DeploymentConfigDTO getTargetConfig(
            MessageContext messageContext, IntelligentModelRoutingConfigDTO policyConfig) {
        if (policyConfig == null) {
            return null;
        }

        // Determine if this is a production or sandbox request
        String apiKeyType = (String) messageContext.getProperty(APIConstants.API_KEY_TYPE);

        // Select the environment-specific config based on the API key type
        IntelligentModelRoutingConfigDTO.DeploymentConfigDTO selectedConfig = APIConstants.API_KEY_TYPE_PRODUCTION
                .equals(apiKeyType)
                ? policyConfig.getProduction()
                : policyConfig.getSandbox();

        if (log.isDebugEnabled()) {
            log.debug("Using " + apiKeyType + " configuration");
        }
        return selectedConfig;
    }

    /**
     * Sets the endpoint properties in the message context and modifies the request.
     *
     * @param messageContext the message context
     * @param selectedEndpoint the selected model endpoint
     * @param endpoints the routing configuration
     */
    private void setEndpointProperties(MessageContext messageContext, ModelEndpointDTO selectedEndpoint, IntelligentModelRoutingConfigDTO endpoints) {
        messageContext.setProperty(AIAPIConstants.TARGET_ENDPOINT, selectedEndpoint.getEndpointId());

        Map<String, Object> routeConfigs = new HashMap<>();
        routeConfigs.put(AIAPIConstants.TARGET_MODEL_ENDPOINT, selectedEndpoint);
        messageContext.setProperty(AIAPIConstants.INTELLIGENT_MODEL_ROUTING_CONFIGS, routeConfigs);
    }

    /**
     * Extracts the user request content from the message payload using the configured JSON path.
     *
     * @param messageContext the message context
     * @param policyConfig the policy configuration containing the content path
     * @return the extracted content, or null if extraction fails
     */
    private String extractUserRequestContent(MessageContext messageContext, 
                                              IntelligentModelRoutingConfigDTO policyConfig) {
        if (policyConfig.getContentPath() == null || StringUtils.isEmpty(policyConfig.getContentPath().getPath())) {
            log.error("Content path is not configured or empty");
            return null;
        }
        
        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        String jsonPayload = JsonUtil.jsonPayloadToString(axis2MC);

        if (StringUtils.isEmpty(jsonPayload)) {
            log.error("Request payload is empty");
            return null;
        }
        
        String contentPath = policyConfig.getContentPath().getPath();
        try {
            String result = JsonPath.read(jsonPayload, contentPath);
            return result;
        } catch (Exception e) {
            log.error("Error parsing JSON path '" + contentPath + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Classifies the user request using the LLM provider to determine the appropriate route rule.
     *
     * @param messageContext the message context
     * @param policyConfig the policy configuration
     * @param targetConfig the deployment configuration
     * @return the classified route rule name, or null if classification fails
     */
    private String classifyRequest(MessageContext messageContext, IntelligentModelRoutingConfigDTO policyConfig, IntelligentModelRoutingConfigDTO.DeploymentConfigDTO targetConfig) {
        try {
            String content = extractUserRequestContent(messageContext, policyConfig);
            Set<String> availableRouteRules = getAvailableRouteRules(targetConfig);

            if (availableRouteRules == null || availableRouteRules.isEmpty() || StringUtils.isEmpty(content)) {
                return null;
            }

            if (llmProvider == null) {
                log.warn("LLM provider service is not available");
                return null;
            }

            String systemPrompt = APIConstants.AI.CLASSIFICATION_SYSTEM_PROMPT;
            String userPrompt = buildClassificationPrompt(targetConfig, content);
            String response = llmProvider.getChatCompletion(systemPrompt, userPrompt);

            return validateResponse(response, availableRouteRules);
        } catch (Exception e) {
            log.error("Error classifying request", e);
            return null;
        }
    }

    /**
     * Builds the classification prompt for the LLM with available route rules and user content.
     *
     * @param targetConfig the deployment configuration
     * @param content the user request content
     * @return the formatted classification prompt
     */
    private String buildClassificationPrompt(IntelligentModelRoutingConfigDTO.DeploymentConfigDTO targetConfig, String content) {
        String routeRuleOptions = buildRouteRulePrompt(targetConfig);
        String routeRuleNames = getRouteRuleNames(targetConfig);
        return "You are a strict classifier. These are the available route rules:\n" + routeRuleOptions +
                "\n\nRespond with ONLY one of these route rule names: " + routeRuleNames +
                "\nIf the request doesn't clearly fit any route rule, respond 'NONE'.\n" +
                "Request: " + content;
    }

    /**
     * Validates the LLM classification response against available route rules.
     *
     * @param response the LLM response
     * @param availableRouteRules the set of valid route rule names
     * @return the validated route rule name, or null if invalid
     */
    private String validateResponse(String response, Set<String> availableRouteRules) {
        if (response == null) return null;

        String cleanResponse = response.trim();
        if ("NONE".equals(cleanResponse)) return null;

        if (availableRouteRules.contains(cleanResponse)) {
            return cleanResponse;
        }

        for (String routeRuleName : availableRouteRules) {
            if (cleanResponse.toLowerCase().contains(routeRuleName.toLowerCase()) ||
                    routeRuleName.toLowerCase().contains(cleanResponse.toLowerCase())) {
                return routeRuleName;
            }
        }
        return null;
    }

    /**
     * Extracts the set of available route rule names from the routing rules.
     *
     * @param targetConfig the deployment configuration
     * @return the set of valid route rule names, or null if none available
     */
    private Set<String> getAvailableRouteRules(IntelligentModelRoutingConfigDTO.DeploymentConfigDTO targetConfig) {
        if (targetConfig == null || targetConfig.getRoutingrules() == null) {
            return null;
        }

        Set<String> routeRules = new HashSet<>();
        for (IntelligentModelRoutingConfigDTO.RoutingRuleDTO routeRule : targetConfig.getRoutingrules()) {
            if (routeRule.isValid()) {
                routeRules.add(routeRule.getName());
            }
        }
        return routeRules.isEmpty() ? null : routeRules;
    }

    /**
     * Builds the route rule options section of the classification prompt.
     *
     * @param targetConfig the deployment configuration
     * @return the formatted route rule options string
     */
    private String buildRouteRulePrompt(IntelligentModelRoutingConfigDTO.DeploymentConfigDTO targetConfig) {
        if (targetConfig == null || targetConfig.getRoutingrules() == null) {
            return "";
        }

        StringBuilder prompt = new StringBuilder();
        for (IntelligentModelRoutingConfigDTO.RoutingRuleDTO routeRule : targetConfig.getRoutingrules()) {
            if (routeRule.isValid()) {
                prompt.append("- ").append(routeRule.getName());
                String context = routeRule.getContext();
                if (!StringUtils.isEmpty(context)) {
                    prompt.append(": ").append(context);
                }
                prompt.append("\n");
            }
        }
        return prompt.toString().trim();
    }

    /**
     * Retrieves a comma-separated list of route rule names for the prompt.
     *
     * @param targetConfig the deployment configuration
     * @return the comma-separated route rule names
     */
    private String getRouteRuleNames(IntelligentModelRoutingConfigDTO.DeploymentConfigDTO targetConfig) {
        if (targetConfig == null || targetConfig.getRoutingrules() == null) {
            return "";
        }

        StringBuilder names = new StringBuilder();
        for (IntelligentModelRoutingConfigDTO.RoutingRuleDTO routeRule : targetConfig.getRoutingrules()) {
            if (routeRule.isValid()) {
                if (names.length() > 0) names.append(", ");
                names.append(routeRule.getName());
            }
        }
        return names.toString();
    }

    @Override
    public boolean isContentAware() {
        return true;
    }

    public String getIntelligentModelRoutingConfigs() {
        return intelligentModelRoutingConfigs;
    }

    @Override
    public void destroy() {

    }
}


