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
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.AbstractMediator;
import org.wso2.apim.policies.mediation.ai.intelligent.model.routing.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.api.APIConstants.AIAPIConstants;
import org.wso2.carbon.apimgt.api.AILLMProviderService;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.api.gateway.ModelEndpointDTO;
import com.jayway.jsonpath.JsonPath;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;

/**
 * Mediator for AI API Intelligent Model Routing using LLM-based classification.
 */
public class IntelligentModelRouting extends AbstractMediator implements ManagedLifecycle {

    private static final Log log = LogFactory.getLog(IntelligentModelRouting.class);

    private String intelligentModelRoutingConfigs;
    private AILLMProviderService llmProvider;

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
            throw new IllegalStateException(IntelligentModelRoutingConstants.ERROR_LLM_PROVIDER_UNAVAILABLE);
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
            return processRouting(messageContext, policyConfig);
        } catch (IllegalStateException e) {
            log.error(IntelligentModelRoutingConstants.ERROR_CONFIG_PARSE_FAILED, e);
            return handleError(messageContext, IntelligentModelRoutingConstants.ROUTING_APIM_EXCEPTION_CODE,
                    e.getMessage());
        } catch (Exception e) {
            log.error("Error in IntelligentModelRouting mediation", e);
            return handleError(messageContext, IntelligentModelRoutingConstants.APIM_INTERNAL_EXCEPTION_CODE,
                    "Error during intelligent model routing: " + e.getMessage());
        }
    }

    private boolean hasConfiguration() {

        return !StringUtils.isEmpty(intelligentModelRoutingConfigs);
    }

    /**
     * Processes the routing logic by classifying the request and selecting the appropriate endpoint.
     *
     * @param messageContext the message context
     * @param policyConfig   the policy configuration
     * @return true to continue mediation
     */
    private boolean processRouting(MessageContext messageContext, IntelligentModelRoutingConfigDTO policyConfig) {

        String apiKeyType = (String) messageContext.getProperty(APIConstants.API_KEY_TYPE);
        IntelligentModelRoutingConfigDTO.DeploymentConfigDTO targetConfig = getTargetConfig(messageContext, policyConfig);

        if (targetConfig == null || targetConfig.getRoutingrules() == null) {
            if (log.isDebugEnabled()) {
                log.debug("IntelligentModelRouting policy is not set for " + apiKeyType + ", bypassing mediation.");
            }
            return true;
        }

        String classifiedRouteRule = classifyRequest(messageContext, policyConfig, targetConfig);
        ModelEndpointDTO selectedEndpoint = selectEndpointForRouteRule(targetConfig, classifiedRouteRule);

        if (selectedEndpoint != null && isValidEndpoint(selectedEndpoint)) {
            setEndpointProperties(messageContext, selectedEndpoint);
        } else {
            if (log.isDebugEnabled()) {
                log.debug(IntelligentModelRoutingConstants.ERROR_NO_ROUTE_FOUND);
            }
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
     * @param targetConfig  the deployment configuration
     * @param routeRuleName the classified route rule name
     * @return the selected endpoint, or default endpoint if no match found
     */
    private ModelEndpointDTO selectEndpointForRouteRule(IntelligentModelRoutingConfigDTO.DeploymentConfigDTO targetConfig,
                                                        String routeRuleName) {

        if (StringUtils.isNotEmpty(routeRuleName) && targetConfig.getRoutingrules() != null) {
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
     * @return the parsed configuration object
     * @throws IllegalStateException if parsing fails
     */
    private IntelligentModelRoutingConfigDTO parseConfiguration(String config)
            throws IllegalStateException {

        try {
            IntelligentModelRoutingConfigDTO endpoints = new Gson().fromJson(config, IntelligentModelRoutingConfigDTO.class);
            if (endpoints == null) {
                throw new IllegalStateException(IntelligentModelRoutingConstants.ERROR_CONFIG_PARSE_FAILED + ": null config");
            }
            return endpoints;
        } catch (JsonSyntaxException e) {
            throw new IllegalStateException(IntelligentModelRoutingConstants.ERROR_CONFIG_PARSE_FAILED, e);
        }
    }

    /**
     * Retrieves the target configuration based on the API key type (production or sandbox).
     */
    private IntelligentModelRoutingConfigDTO.DeploymentConfigDTO getTargetConfig(
            MessageContext messageContext, IntelligentModelRoutingConfigDTO policyConfig) {

        if (policyConfig == null) {
            return new IntelligentModelRoutingConfigDTO.DeploymentConfigDTO();
        }
        String apiKeyType = (String) messageContext.getProperty(APIConstants.API_KEY_TYPE);
        return APIConstants.API_KEY_TYPE_PRODUCTION.equals(apiKeyType)
                ? policyConfig.getProduction()
                : policyConfig.getSandbox();
    }

    /**
     * Sets the endpoint properties in the message context.
     */
    private void setEndpointProperties(MessageContext messageContext, ModelEndpointDTO selectedEndpoint) {

        messageContext.setProperty(AIAPIConstants.TARGET_ENDPOINT, selectedEndpoint.getEndpointId());
        Map<String, Object> routeConfigs = new HashMap<>();
        routeConfigs.put(AIAPIConstants.TARGET_MODEL_ENDPOINT, selectedEndpoint);
        messageContext.setProperty(AIAPIConstants.ROUTING_CONFIGS, routeConfigs);
    }

    /**
     * Extracts the user request content from the message payload using the configured JSON path.
     */
    private String extractUserRequestContent(MessageContext messageContext,
                                             IntelligentModelRoutingConfigDTO policyConfig) {

        if (policyConfig.getContentPath() == null || StringUtils.isEmpty(policyConfig.getContentPath().getPath())) {
            if (log.isDebugEnabled()) {
                log.debug(IntelligentModelRoutingConstants.ERROR_CONTENT_PATH_NOT_CONFIGURED);
            }
            return IntelligentModelRoutingConstants.EMPTY_RESULT;
        }

        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        String jsonPayload = JsonUtil.jsonPayloadToString(axis2MC);

        if (StringUtils.isEmpty(jsonPayload)) {
            if (log.isDebugEnabled()) {
                log.debug(IntelligentModelRoutingConstants.ERROR_EMPTY_PAYLOAD);
            }
            return IntelligentModelRoutingConstants.EMPTY_RESULT;
        }

        String contentPath = policyConfig.getContentPath().getPath();
        try {
            Object result = JsonPath.read(jsonPayload, contentPath);
            return result != null ? result.toString() : IntelligentModelRoutingConstants.EMPTY_RESULT;
        } catch (Exception e) {
            log.warn(IntelligentModelRoutingConstants.ERROR_JSON_PATH_PARSE + " '" + contentPath + "': " + e.getMessage());
            return IntelligentModelRoutingConstants.EMPTY_RESULT;
        }
    }

    /**
     * Classifies the user request using the LLM provider to determine the appropriate route rule.
     */
    private String classifyRequest(MessageContext messageContext, IntelligentModelRoutingConfigDTO policyConfig,
            IntelligentModelRoutingConfigDTO.DeploymentConfigDTO targetConfig) {

        try {
            String content = extractUserRequestContent(messageContext, policyConfig);
            Set<String> availableRouteRules = getAvailableRouteRules(targetConfig);

            if (availableRouteRules.isEmpty() || StringUtils.isEmpty(content)) {
                if (log.isDebugEnabled()) {
                    log.debug("No route rules available or content is empty, using default route");
                }
                return IntelligentModelRoutingConstants.EMPTY_RESULT;
            }

            if (llmProvider == null) {
                log.warn(IntelligentModelRoutingConstants.ERROR_LLM_PROVIDER_UNAVAILABLE);
                return IntelligentModelRoutingConstants.EMPTY_RESULT;
            }

            String systemPrompt = APIConstants.AI.CLASSIFICATION_SYSTEM_PROMPT;
            String userPrompt = buildClassificationPrompt(targetConfig, content);
            String response = llmProvider.getChatCompletion(systemPrompt, userPrompt);

            return validateResponse(response, availableRouteRules);
        } catch (Exception e) {
            log.error(IntelligentModelRoutingConstants.ERROR_CLASSIFICATION_FAILED, e);
            return IntelligentModelRoutingConstants.EMPTY_RESULT;
        }
    }

    /**
     * Builds the classification prompt for the LLM with available route rules and user content.
     */
    private String buildClassificationPrompt(IntelligentModelRoutingConfigDTO.DeploymentConfigDTO targetConfig, String content) {

        String[] promptComponents = buildPromptComponents(targetConfig);
        return "You are a strict classifier. These are the available route rules:\n" + promptComponents[0] +
                "\n\nRespond with ONLY one of these route rule names: " + promptComponents[1] +
                "\nIf the request doesn't clearly fit any route rule, respond 'NONE'.\n" +
                "Request: " + content;
    }

    /**
     * Validates the LLM classification response against available route rules.
     */
    private String validateResponse(String response, Set<String> availableRouteRules) {

        if (StringUtils.isEmpty(response) || "NONE".equals(response.trim())) {
            return IntelligentModelRoutingConstants.EMPTY_RESULT;
        }
        String cleanResponse = response.trim();
        if (availableRouteRules.contains(cleanResponse)) {
            return cleanResponse;
        }
        for (String routeRuleName : availableRouteRules) {
            if (cleanResponse.toLowerCase().contains(routeRuleName.toLowerCase()) ||
                    routeRuleName.toLowerCase().contains(cleanResponse.toLowerCase())) {
                return routeRuleName;
            }
        }
        return IntelligentModelRoutingConstants.EMPTY_RESULT;
    }

    /**
     * Handles errors by setting error properties in the message context.
     */
    private boolean handleError(MessageContext messageContext, int errorCode, String errorMessage) {

        messageContext.setProperty(SynapseConstants.ERROR_CODE, errorCode);
        messageContext.setProperty(SynapseConstants.ERROR_MESSAGE, errorMessage);
        return false;
    }

    /**
     * Extracts the set of available route rule names from the routing rules.
     */
    private Set<String> getAvailableRouteRules(IntelligentModelRoutingConfigDTO.DeploymentConfigDTO targetConfig) {

        Set<String> routeRules = new HashSet<>();
        if (targetConfig == null || targetConfig.getRoutingrules() == null) {
            return routeRules;
        }
        for (IntelligentModelRoutingConfigDTO.RoutingRuleDTO routeRule : targetConfig.getRoutingrules()) {
            if (routeRule != null && routeRule.isValid()) {
                routeRules.add(routeRule.getName());
            }
        }
        return routeRules;
    }

    /**
     * Builds the classification prompt components from routing rules.
     * Returns array: [0] = rule options with context, [1] = comma-separated names
     */
    private String[] buildPromptComponents(IntelligentModelRoutingConfigDTO.DeploymentConfigDTO targetConfig) {

        if (targetConfig == null || targetConfig.getRoutingrules() == null) {
            return new String[]{IntelligentModelRoutingConstants.EMPTY_RESULT, IntelligentModelRoutingConstants.EMPTY_RESULT};
        }

        StringBuilder options = new StringBuilder();
        StringBuilder names = new StringBuilder();
        for (IntelligentModelRoutingConfigDTO.RoutingRuleDTO routeRule : targetConfig.getRoutingrules()) {
            if (routeRule != null && routeRule.isValid()) {
                options.append("- ").append(routeRule.getName());
                if (!StringUtils.isEmpty(routeRule.getContext())) {
                    options.append(": ").append(routeRule.getContext());
                }
                options.append("\n");
                if (names.length() > 0) names.append(", ");
                names.append(routeRule.getName());
            }
        }
        return new String[]{options.toString().trim(), names.toString()};
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


