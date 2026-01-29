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

package org.wso2.apim.policies.mediation.ai.cbr;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.AbstractMediator;
import org.wso2.apim.policies.mediation.ai.cbr.dto.CBRConditionDTO;
import org.wso2.apim.policies.mediation.ai.cbr.dto.CBRPolicyConfigDTO;
import org.wso2.apim.policies.mediation.ai.cbr.dto.CBRRoutingEntryDTO;
import org.wso2.apim.policies.mediation.ai.cbr.dto.CBRTargetDTO;
import org.wso2.carbon.apimgt.api.gateway.ModelEndpointDTO;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Content-Based Model Router mediator.
 * <p>
 * A mediator that performs content-based routing (CBR) for AI API requests.
 * Routes requests to specific model endpoints based on query parameter or header values.
 * This enables dynamic routing decisions based on request attributes.
 */
public class ContentBasedModelRouter extends AbstractMediator implements ManagedLifecycle {

    private static final Log logger = LogFactory.getLog(ContentBasedModelRouter.class);

    private String contentBasedModelRoutingConfigs;
    private CBRPolicyConfigDTO config;

    /**
     * Initializes the ContentBasedModelRouter mediator.
     *
     * @param synapseEnvironment The Synapse environment instance.
     */
    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        if (logger.isDebugEnabled()) {
            logger.debug("Initializing ContentBasedModelRouter.");
        }

        try {
            config = new Gson().fromJson(contentBasedModelRoutingConfigs, CBRPolicyConfigDTO.class);
        } catch (JsonSyntaxException e) {
            logger.error("Failed to parse content based routing configuration", e);
        }
    }

    /**
     * Destroys the ContentBasedModelRouter mediator instance and releases any allocated resources.
     */
    @Override
    public void destroy() {
        // No specific resources to release
    }

    /**
     * Processes the message and routes to the appropriate endpoint based on configured conditions.
     *
     * @param messageContext Synapse message context.
     * @return Always returns true to continue message mediation.
     */
    @Override
    public boolean mediate(MessageContext messageContext) {
        if (logger.isDebugEnabled()) {
            logger.debug("ContentBasedModelRouter mediation started.");
        }

        String apiKeyType = (String) messageContext.getProperty(ContentBasedModelRouterConstants.API_KEY_TYPE);
        List<CBRRoutingEntryDTO> routingEntries =
                ContentBasedModelRouterConstants.API_KEY_TYPE_PRODUCTION.equals(apiKeyType)
                        ? config.getProduction()
                        : config.getSandbox();

        if (routingEntries == null || routingEntries.isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("No routing entries for " + apiKeyType + ", using default endpoint.");
            }
            messageContext.setProperty(ContentBasedModelRouterConstants.TARGET_ENDPOINT,
                    ContentBasedModelRouterConstants.DEFAULT_ENDPOINT);
            return true;
        }

        org.apache.axis2.context.MessageContext axis2MessageContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

        // Find the first routing entry whose condition matches (first-match-wins)
        CBRRoutingEntryDTO matchedEntry = findMatchingEntry(routingEntries, axis2MessageContext);

        if (matchedEntry != null) {
            // Use the nested target DTO to build ModelEndpointDTO for AIAPIMediator compatibility
            CBRTargetDTO target = matchedEntry.getTarget();
            ModelEndpointDTO targetEndpoint = new ModelEndpointDTO();
            targetEndpoint.setModel(target.getModel());
            targetEndpoint.setEndpointId(target.getEndpointId());

            Map<String, Object> routingConfigs = new HashMap<>();
            routingConfigs.put(ContentBasedModelRouterConstants.TARGET_MODEL_ENDPOINT, targetEndpoint);
            messageContext.setProperty(ContentBasedModelRouterConstants.TARGET_MODEL_CONFIGS, routingConfigs);

            if (logger.isDebugEnabled()) {
                logger.debug("Matched routing entry: model=" + target.getModel()
                        + ", endpointId=" + target.getEndpointId());
            }
        } else {
            messageContext.setProperty(ContentBasedModelRouterConstants.TARGET_ENDPOINT,
                    ContentBasedModelRouterConstants.DEFAULT_ENDPOINT);
            if (logger.isDebugEnabled()) {
                logger.debug("No matching routing entry found, using default endpoint.");
            }
        }

        return true;
    }

    /**
     * Finds the first routing entry that matches the request parameters (first-match-wins).
     *
     * @param entries             List of routing entries to evaluate.
     * @param axis2MessageContext Axis2 message context.
     * @return The first matching routing entry, or null if no match found.
     */
    private CBRRoutingEntryDTO findMatchingEntry(List<CBRRoutingEntryDTO> entries,
            org.apache.axis2.context.MessageContext axis2MessageContext) {
        for (CBRRoutingEntryDTO entry : entries) {
            if (evaluateCondition(entry.getCondition(), axis2MessageContext)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Evaluates whether a CBR condition matches the current request.
     * Currently only supports EQUALS operator.
     *
     * @param condition           The CBR condition to evaluate.
     * @param axis2MessageContext Axis2 message context.
     * @return true if the condition matches, false otherwise.
     */
    private boolean evaluateCondition(CBRConditionDTO condition,
            org.apache.axis2.context.MessageContext axis2MessageContext) {
        String actualValue = extractParameterValue(axis2MessageContext, condition.getType(), condition.getKey());

        if (actualValue == null) {
            return false;
        }

        if (ContentBasedModelRouterConstants.OPERATOR_EQUALS.equals(condition.getOperator())) {
            return actualValue.equals(condition.getValue());
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Unsupported CBR operator: " + condition.getOperator());
        }
        return false;
    }

    /**
     * Extracts the parameter value from the message context based on the parameter type.
     *
     * @param axis2MessageContext Axis2 message context.
     * @param parameterType       The type of parameter (HEADER or QUERY_PARAMETER).
     * @param parameterKey        The name of the parameter to extract.
     * @return The parameter value, or null if not found.
     */
    @SuppressWarnings("unchecked")
    private String extractParameterValue(org.apache.axis2.context.MessageContext axis2MessageContext,
            String parameterType,
            String parameterKey) {
        if (ContentBasedModelRouterConstants.API_KEY_IDENTIFIER_TYPE_HEADER.equals(parameterType)) {
            Map<String, String> transportHeaders = (Map<String, String>) axis2MessageContext
                    .getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
            if (transportHeaders != null) {
                return transportHeaders.get(parameterKey);
            }
        } else if (ContentBasedModelRouterConstants.API_KEY_IDENTIFIER_TYPE_QUERY_PARAMETER.equals(parameterType)) {
            String queryString = (String) axis2MessageContext.getProperty("REST_URL_POSTFIX");
            if (queryString != null && queryString.contains("?")) {
                String params = queryString.substring(queryString.indexOf("?") + 1);
                for (String param : params.split("&")) {
                    String[] keyValue = param.split("=");
                    if (keyValue.length == 2 && keyValue[0].equals(parameterKey)) {
                        return keyValue[1];
                    }
                }
            }
        }
        return null;
    }

    public String getContentBasedModelRoutingConfigs() {
        return contentBasedModelRoutingConfigs;
    }

    public void setContentBasedModelRoutingConfigs(String contentBasedModelRoutingConfigs) {
        this.contentBasedModelRoutingConfigs = contentBasedModelRoutingConfigs;
    }
}
