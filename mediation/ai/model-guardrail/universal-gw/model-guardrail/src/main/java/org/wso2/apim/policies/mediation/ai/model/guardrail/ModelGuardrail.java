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

package org.wso2.apim.policies.mediation.ai.model.guardrail;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jayway.jsonpath.JsonPath;
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
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Model Guardrail mediator validates incoming requests against a configured list of allowed models.
 * The configuration accepts an array of model groups, each containing a name and list of models.
 * The mediator extracts all models from the configuration and validates if the requested model
 * (extracted from the payload) is in the allowed list.
 */
public class ModelGuardrail extends AbstractMediator implements ManagedLifecycle {
    private static final Log logger = LogFactory.getLog(ModelGuardrail.class);

    private String name;
    private String modelConfig;
    private boolean showAssessment;

    private Set<String> allowedModels;

    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        if (logger.isDebugEnabled()) {
            logger.debug("Initializing Model Guardrail mediator.");
        }

        // Parse the configuration and extract all allowed models
        allowedModels = new HashSet<>();
        
        if (modelConfig != null && !modelConfig.trim().isEmpty()) {
            try {
                Gson gson = new Gson();
                JsonArray configArray = gson.fromJson(modelConfig, JsonArray.class);
                
                for (JsonElement element : configArray) {
                    if (element.isJsonObject()) {
                        JsonObject groupObj = element.getAsJsonObject();
                        
                        // Extract models array from each group
                        if (groupObj.has("models") && groupObj.get("models").isJsonArray()) {
                            JsonArray modelsArray = groupObj.getAsJsonArray("models");
                            
                            for (JsonElement modelElement : modelsArray) {
                                if (modelElement.isJsonPrimitive()) {
                                    allowedModels.add(modelElement.getAsString());
                                }
                            }
                        }
                    }
                }
                
                if (logger.isDebugEnabled()) {
                    logger.debug("Loaded " + allowedModels.size() + " allowed models: " + allowedModels);
                }
            } catch (Exception e) {
                logger.error("Failed to parse model configuration: " + modelConfig, e);
                throw new IllegalArgumentException("Invalid model configuration provided", e);
            }
        } else {
            logger.warn("No model configuration provided. All requests will be blocked.");
        }
    }

    @Override
    public void destroy() {
        if (logger.isDebugEnabled()) {
            logger.debug("Destroying Model Guardrail mediator.");
        }
    }

    @Override
    public boolean mediate(MessageContext messageContext) {
        if (logger.isDebugEnabled()) {
            logger.debug("Starting model validation.");
        }

        try {
            // Extract the requested model from the payload
            String requestedModel = extractRequestedModel(messageContext);
            
            if (requestedModel == null || requestedModel.trim().isEmpty()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("No model found in the request payload.");
                }
                // If no model is found, allow the request to proceed
                return true;
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Extracted model from request: " + requestedModel);
            }

            // Validate if the requested model is in the allowed list
            if (!allowedModels.contains(requestedModel)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Model validation failed. Model '" + requestedModel + "' is not in the allowed list.");
                }
                
                // Set error properties
                messageContext.setProperty(SynapseConstants.ERROR_CODE,
                        ModelGuardrailConstants.GUARDRAIL_APIM_EXCEPTION_CODE);
                messageContext.setProperty(ModelGuardrailConstants.ERROR_TYPE,
                        ModelGuardrailConstants.MODEL_GUARDRAIL);
                messageContext.setProperty(ModelGuardrailConstants.CUSTOM_HTTP_SC,
                        ModelGuardrailConstants.GUARDRAIL_ERROR_CODE);

                // Build assessment object
                String assessmentObject = buildAssessmentObject(requestedModel, messageContext.isResponse());
                messageContext.setProperty(SynapseConstants.ERROR_MESSAGE, assessmentObject);

                if (logger.isDebugEnabled()) {
                    logger.debug("Triggering fault sequence for invalid model.");
                }

                // Trigger fault sequence
                Mediator faultMediator = messageContext.getSequence(ModelGuardrailConstants.FAULT_SEQUENCE_KEY);
                if (faultMediator == null) {
                    messageContext.setProperty(SynapseConstants.ERROR_MESSAGE,
                            "Invalid model. If you want to use this model, configure it first.");
                    faultMediator = messageContext.getFaultSequence();
                }

                faultMediator.mediate(messageContext);
                return false; // Stop further processing
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Model validation passed. Model '" + requestedModel + "' is allowed.");
            }

        } catch (Exception e) {
            logger.error("Exception occurred during model validation.", e);

            messageContext.setProperty(SynapseConstants.ERROR_CODE,
                    ModelGuardrailConstants.APIM_INTERNAL_EXCEPTION_CODE);
            messageContext.setProperty(SynapseConstants.ERROR_MESSAGE,
                    "Error occurred during Model Guardrail mediation");
            
            Mediator faultMediator = messageContext.getFaultSequence();
            faultMediator.mediate(messageContext);
            return false;
        }

        return true;
    }

    /**
     * Extracts the requested model from the message context.
     * Tries to extract from JSON payload first ($.model or $.options.model),
     * then falls back to URL path extraction for various provider patterns.
     *
     * @param messageContext The Synapse message context
     * @return The model name extracted from payload or URL, or null if not found
     */
    private String extractRequestedModel(MessageContext messageContext) {
        // First try to extract from JSON payload
        String model = extractModelFromPayload(messageContext);
        
        if (model != null && !model.trim().isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Model extracted from payload: " + model);
            }
            return model;
        }
        
        // If not found in payload, try to extract from URL path
        model = extractModelFromUrlPath(messageContext);
        
        if (model != null && !model.trim().isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Model extracted from URL path: " + model);
            }
            return model;
        }
        
        return null;
    }

    /**
     * Extracts the model name from the JSON payload.
     * Tries common model paths: $.model and $.options.model
     *
     * @param messageContext The Synapse message context
     * @return The model name from payload, or null if not found
     */
    private String extractModelFromPayload(MessageContext messageContext) {
        try {
            String jsonContent = extractJsonContent(messageContext);
            
            if (jsonContent == null || jsonContent.isEmpty()) {
                return null;
            }

            // Try $.model first (most common - OpenAI, Azure, Mistral, etc.)
            try {
                Object result = JsonPath.read(jsonContent, "$.model");
                if (result instanceof String && !((String) result).trim().isEmpty()) {
                    return (String) result;
                }
            } catch (Exception e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Model not found at $.model path");
                }
            }

            // Try $.options.model (Ollama style)
            try {
                Object result = JsonPath.read(jsonContent, "$.options.model");
                if (result instanceof String && !((String) result).trim().isEmpty()) {
                    return (String) result;
                }
            } catch (Exception e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Model not found at $.options.model path");
                }
            }

            return null;
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to extract model from payload: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Extracts the model name from the URL path.
     * Supports multiple provider patterns:
     * - Google Gemini: /models/MODEL_NAME:generateContent or /models/MODEL_NAME:streamGenerateContent
     * - OpenAI/Mistral: /v1/models/MODEL_NAME or /models/MODEL_NAME
     * - Amazon Bedrock: /model/MODEL_NAME/action
     * - Azure/Vertex AI: /deployments/MODEL_NAME
     *
     * @param messageContext The Synapse message context
     * @return The model name from URL path, or null if not found
     */
    private String extractModelFromUrlPath(MessageContext messageContext) {
        try {
            org.apache.axis2.context.MessageContext axis2Ctx =
                    ((Axis2MessageContext) messageContext).getAxis2MessageContext();
            
            String requestPath = (String) axis2Ctx.getProperty(NhttpConstants.REST_URL_POSTFIX);
            
            if (requestPath == null || requestPath.isEmpty()) {
                return null;
            }

            URI uri = URI.create(requestPath);
            String rawPath = uri.getRawPath();
            
            if (rawPath == null || rawPath.isEmpty()) {
                return null;
            }

            String model = null;

            // Pattern 1: Google Gemini style - /models/MODEL_NAME:action
            if (rawPath.matches(".*/models/[^/:]+:[^/]+.*")) {
                Pattern pattern = Pattern.compile("/models/([^/:]+):");
                Matcher matcher = pattern.matcher(rawPath);
                if (matcher.find()) {
                    model = URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8.name());
                }
            }
            // Pattern 2: OpenAI/Mistral style - /models/MODEL_NAME (without colon)
            else if (rawPath.contains("/models/")) {
                Pattern pattern = Pattern.compile("/models/([^/]+)");
                Matcher matcher = pattern.matcher(rawPath);
                if (matcher.find()) {
                    model = URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8.name());
                }
            }
            // Pattern 3: Amazon Bedrock style - /model/MODEL_NAME/action
            else if (rawPath.contains("/model/")) {
                Pattern pattern = Pattern.compile("/model/([^/]+)");
                Matcher matcher = pattern.matcher(rawPath);
                if (matcher.find()) {
                    model = URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8.name());
                }
            }
            // Pattern 4: Azure/Vertex AI style - /deployments/MODEL_NAME
            else if (rawPath.contains("/deployments/")) {
                Pattern pattern = Pattern.compile("/deployments/([^/]+)");
                Matcher matcher = pattern.matcher(rawPath);
                if (matcher.find()) {
                    model = URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8.name());
                }
            }

            return model;
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to extract model from URL path: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Extracts JSON content from the message context.
     *
     * @param messageContext The message context containing the JSON payload
     * @return The JSON payload as a string, or null if extraction fails
     */
    private String extractJsonContent(MessageContext messageContext) {
        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        return JsonUtil.jsonPayloadToString(axis2MC);
    }

    /**
     * Builds a JSON object containing assessment details for guardrail responses.
     *
     * @param requestedModel The model that was requested but not allowed
     * @param isResponse Whether this is a response or request
     * @return A JSON string representing the assessment object
     */
    private String buildAssessmentObject(String requestedModel, boolean isResponse) {
        if (logger.isDebugEnabled()) {
            logger.debug("Building guardrail assessment object.");
        }

        JSONObject assessmentObject = new JSONObject();

        assessmentObject.put(ModelGuardrailConstants.ASSESSMENT_ACTION, "GUARDRAIL_INTERVENED");
        assessmentObject.put(ModelGuardrailConstants.INTERVENING_GUARDRAIL, name);
        assessmentObject.put(ModelGuardrailConstants.DIRECTION, isResponse ? "RESPONSE" : "REQUEST");
        assessmentObject.put(ModelGuardrailConstants.ASSESSMENT_REASON,
                "Invalid model detected. Model is not configured in the allowed list.");

        if (showAssessment) {
            String message = "Invalid model '" + requestedModel + "'. If you want to use this model, configure it first. "
                    + "Allowed models: " + allowedModels;
            assessmentObject.put(ModelGuardrailConstants.ASSESSMENTS, message);
        }

        return assessmentObject.toString();
    }

    // Getters and setters

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getModelConfig() {
        return modelConfig;
    }

    public void setModelConfig(String modelConfig) {
        this.modelConfig = modelConfig;
    }

    public boolean isShowAssessment() {
        return showAssessment;
    }

    public void setShowAssessment(boolean showAssessment) {
        this.showAssessment = showAssessment;
    }
}
