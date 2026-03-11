/*
 * Copyright (c) 2026 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.wso2.apim.policies.mediation.ai.intelligent.model.routing;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.AbstractMediator;
import org.wso2.apim.policies.mediation.ai.intelligent.model.routing.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.api.AILLMProviderService;
import org.wso2.carbon.apimgt.api.APIConstants.AIAPIConstants;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.gateway.ModelEndpointDTO;
import org.wso2.carbon.apimgt.impl.APIConstants;

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
            if (StringUtils.isEmpty(intelligentModelRoutingConfigs)) {
                return true;
            }

            IntelligentModelRoutingConfigDTO policyConfig = parseConfiguration(intelligentModelRoutingConfigs);
            return processRouting(messageContext, policyConfig);
        } catch (APIManagementException e) {
            log.error(IntelligentModelRoutingConstants.ERROR_CONFIG_PARSE_FAILED, e);
            messageContext.setProperty(AIAPIConstants.TARGET_ENDPOINT, AIAPIConstants.REJECT_ENDPOINT);
            return true;
        }
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
            log.warn("IntelligentModelRouting policy is not set for " + apiKeyType + ", bypassing mediation.");
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
                        if (log.isDebugEnabled()) {
                            log.debug("Selected route rule '" + routeRuleName + "' with model: " + routeRule.getModel());
                        }
                        return endpoint;
                    }
                }
            }
        }

        if (targetConfig.getDefaultModel() != null) {
            if (log.isDebugEnabled()) {
                log.debug("Using default model: " + targetConfig.getDefaultModel().getModel());
            }
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
     * @throws APIManagementException if parsing fails
     */
    private IntelligentModelRoutingConfigDTO parseConfiguration(String config)
            throws APIManagementException {

        try {
            config = config.replace("&quot;", "\"");
            IntelligentModelRoutingConfigDTO endpoints = new Gson().fromJson(config, IntelligentModelRoutingConfigDTO.class);
            if (endpoints == null) {
                throw new APIManagementException(IntelligentModelRoutingConstants.ERROR_CONFIG_PARSE_FAILED + ": null config");
            }
            return endpoints;
        } catch (JsonSyntaxException e) {
            throw new APIManagementException(IntelligentModelRoutingConstants.ERROR_CONFIG_PARSE_FAILED, e);
        }
    }

    /**
     * Retrieves the target configuration based on the API key type (production or sandbox).
     *
     * @param messageContext the message context
     * @param policyConfig   the policy configuration
     * @return the deployment configuration for the current environment
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
     *
     * @param messageContext   the message context
     * @param selectedEndpoint the selected endpoint to set
     */
    private void setEndpointProperties(MessageContext messageContext, ModelEndpointDTO selectedEndpoint) {

        messageContext.setProperty(AIAPIConstants.TARGET_ENDPOINT, selectedEndpoint.getEndpointId());
        Map<String, Object> routeConfigs = new HashMap<>();
        routeConfigs.put(AIAPIConstants.TARGET_MODEL_ENDPOINT, selectedEndpoint);
        messageContext.setProperty(AIAPIConstants.TARGET_MODEL_CONFIGS, routeConfigs);
    }

    /**
     * Extracts the user request content from the message payload using the configured JSON path.
     *
     * @param messageContext the message context containing the request payload
     * @param policyConfig   the policy configuration containing the content path
     * @return the extracted user request content, or empty string if not found
     */
    private String extractUserRequestContent(MessageContext messageContext,
                                             IntelligentModelRoutingConfigDTO policyConfig) {

        if (policyConfig.getContentPath() == null || StringUtils.isEmpty(policyConfig.getContentPath().getPath())) {
            log.warn(IntelligentModelRoutingConstants.ERROR_CONTENT_PATH_NOT_CONFIGURED);
            return StringUtils.EMPTY;
        }

        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        String jsonPayload = JsonUtil.jsonPayloadToString(axis2MC);

        if (StringUtils.isEmpty(jsonPayload)) {
            if (log.isDebugEnabled()) {
                log.debug(IntelligentModelRoutingConstants.ERROR_EMPTY_PAYLOAD);
            }
            return StringUtils.EMPTY;
        }

        String contentPath = policyConfig.getContentPath().getPath();
        try {
            Object result = JsonPath.read(jsonPayload, contentPath);
            return result != null ? result.toString() : StringUtils.EMPTY;
        } catch (PathNotFoundException e) {
            log.error(IntelligentModelRoutingConstants.ERROR_JSON_PATH_PARSE + " '" + contentPath + "': " + e.getMessage());
            return StringUtils.EMPTY;
        } catch (InvalidPathException | InvalidJsonException e) {
            log.error(IntelligentModelRoutingConstants.ERROR_JSON_PATH_PARSE + " '" + contentPath + "': " + e.getMessage());
            return StringUtils.EMPTY;
        }
    }

    /**
     * Classifies the user request using the LLM provider to determine the appropriate route rule.
     *
     * @param messageContext the message context containing the request
     * @param policyConfig   the policy configuration
     * @param targetConfig   the deployment configuration for the current environment
     * @return the matched route rule name, or empty string if classification fails
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
                return StringUtils.EMPTY;
            }

            if (llmProvider == null) {
                log.warn(IntelligentModelRoutingConstants.ERROR_LLM_PROVIDER_UNAVAILABLE);
                return StringUtils.EMPTY;
            }

            String systemPrompt = IntelligentModelRoutingConstants.CLASSIFICATION_SYSTEM_PROMPT;
            String userPrompt = buildClassificationPrompt(targetConfig, content);
            String response = llmProvider.getChatCompletion(systemPrompt, userPrompt);

            return validateResponse(response, availableRouteRules);
        } catch (APIManagementException e) {
            log.error(IntelligentModelRoutingConstants.ERROR_CLASSIFICATION_FAILED, e);
            return StringUtils.EMPTY;
        }
    }

    /**
     * Builds the classification prompt for the LLM with available route rules and user content.
     *
     * @param targetConfig the deployment configuration containing routing rules
     * @param content      the user request content
     * @return the formatted classification prompt
     */
    private String buildClassificationPrompt(IntelligentModelRoutingConfigDTO.DeploymentConfigDTO targetConfig, String content) {

        String[] promptComponents = buildPromptComponents(targetConfig);
        return "## TASK\n" +
                "Classify the user request into exactly ONE route rule from the list below.\n\n" +
                "## ROUTE RULES\n" + promptComponents[0] + "\n\n" +
                "## ALLOWED RESPONSES\n" +
                "You MUST respond with ONLY one of: " + promptComponents[1] + ", NONE\n\n" +
                "## STRICT OUTPUT RULES\n" +
                "1. Output ONLY the route rule name - no explanations, no punctuation, no quotes\n" +
                "2. Match based on the context description of each rule\n" +
                "3. If the request clearly matches a rule's context, output that rule name\n" +
                "4. If no rule matches or unclear, output: NONE\n\n" +
                "## USER REQUEST\n" + content + "\n\n" +
                "## YOUR RESPONSE (single word only):";
    }

    /**
     * Validates the LLM classification response against available route rules.
     *
     * @param response            the raw LLM response
     * @param availableRouteRules the set of valid route rule names
     * @return the matched route rule name, or empty string if no match found
     */
    private String validateResponse(String response, Set<String> availableRouteRules) {

        if (StringUtils.isEmpty(response)) {
            if (log.isDebugEnabled()) {
                log.debug(IntelligentModelRoutingConstants.WARN_EMPTY_LLM_RESPONSE);
            }
            return StringUtils.EMPTY;
        }

        String cleanResponse = response.trim();

        if (StringUtils.isEmpty(cleanResponse) || cleanResponse.equalsIgnoreCase("NONE")) {
            if (log.isDebugEnabled()) {
                log.debug(IntelligentModelRoutingConstants.DEBUG_LLM_RETURNED_NONE);
            }
            return StringUtils.EMPTY;
        }

        String matchedRule = findMatchingRouteRule(cleanResponse, availableRouteRules);
        if (matchedRule != null) {
            if (log.isDebugEnabled()) {
                log.debug("Route rule matched: " + matchedRule);
            }
            return matchedRule;
        }

        log.warn(IntelligentModelRoutingConstants.WARN_NO_ROUTE_RULE_MATCHED + 
                " LLM response: '" + cleanResponse + "', Available rules: " + availableRouteRules);
        return StringUtils.EMPTY;
    }

    private String findMatchingRouteRule(String cleanResponse, Set<String> availableRouteRules) {

        for (String rule : availableRouteRules) {
            if (rule.equalsIgnoreCase(cleanResponse)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * Extracts the set of available route rule names from the routing rules.
     *
     * @param targetConfig the deployment configuration containing routing rules
     * @return the set of valid route rule names
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
     *
     * @param targetConfig the deployment configuration containing routing rules
     * @return array where [0] = rule options with context, [1] = comma-separated names
     */
    private String[] buildPromptComponents(IntelligentModelRoutingConfigDTO.DeploymentConfigDTO targetConfig) {

        if (targetConfig == null || targetConfig.getRoutingrules() == null) {
            return new String[]{StringUtils.EMPTY, StringUtils.EMPTY};
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
