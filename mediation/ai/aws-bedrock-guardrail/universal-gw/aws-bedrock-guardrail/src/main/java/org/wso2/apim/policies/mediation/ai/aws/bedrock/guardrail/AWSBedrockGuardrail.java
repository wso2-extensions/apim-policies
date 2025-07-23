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

package org.wso2.apim.policies.mediation.ai.aws.bedrock.guardrail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.apache.axis2.AxisFault;
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
import org.json.JSONException;
import org.json.JSONObject;
import org.wso2.apim.policies.mediation.ai.aws.bedrock.guardrail.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.GuardrailProviderService;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AWSBedrockGuardrail is a custom Synapse mediator responsible for evaluating API requests and responses
 * against AWS Bedrock Guardrails for policy enforcement, content safety, and PII redaction.
 * <p>
 * This mediator integrates with the AWS GuardrailProviderService to send content for assessment and
 * takes appropriate action based on the response—such as blocking requests, masking/redacting PII,
 * or allowing the flow to continue.
 * <p>
 * This class is intended for use in WSO2 API Manager as part of guardrail mediation policy enforcement,
 * and depends on a registered {@link GuardrailProviderService} implementation.
 *
 */
public class AWSBedrockGuardrail extends AbstractMediator implements ManagedLifecycle {
    private static final Log logger = LogFactory.getLog(AWSBedrockGuardrail.class);

    private String name;
    private String region;
    private String guardrailId;
    private String guardrailVersion;
    private String jsonPath = "";
    private boolean passthroughOnError = false;
    private boolean redactPII = false;
    private boolean showAssessment = false;

    private GuardrailProviderService guardrailProviderService;

    /**
     * Initializes the AWSBedrockGuardrail mediator.
     *
     * @param synapseEnvironment The Synapse environment instance.
     */
    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        if (logger.isDebugEnabled()) {
            logger.debug("Initializing AWSBedrockGuardrail.");
        }

        guardrailProviderService = ServiceReferenceHolder.getInstance().getGuardrailProviderService();

        if (guardrailProviderService == null) {
            throw new IllegalStateException("AWS Bedrock Guardrail provider is not registered or available.");
        }
    }

    /**
     * Destroys the AWSBedrockGuardrail mediator instance and releases any allocated resources.
     */
    @Override
    public void destroy() {
        // No specific resources to release
    }

    @Override
    public boolean mediate(MessageContext messageContext) {
        if (logger.isDebugEnabled()) {
            logger.debug("Beginning guardrail evaluation");
        }

        try {
            // Transform response if redactPII is disabled and PIIs identified in request
            if (!redactPII && messageContext.isResponse()) {
                identifyPIIAndTransform(messageContext);
                return true; // Continue processing if not redacting PII in response
            }

            // Extract the request/ response body from message context
            String jsonContent = extractJsonContent(messageContext);

            if (jsonPath != null && !jsonPath.trim().isEmpty()) {
                String content = JsonPath.read(jsonContent, jsonPath).toString();
                jsonContent = content.replaceAll(AWSBedrockConstants.TEXT_CLEAN_REGEX, "").trim();
            }

            // Create request payload for AWS Bedrock
            String payload = createBedrockRequestPayload(jsonContent, messageContext.isResponse());

            // Construct the Bedrock endpoint URL
            String host = String.format(
                    "%s.%s.%s", AWSBedrockConstants.BEDROCK_RUNTIME, region, AWSBedrockConstants.BEDROCK_HOST);
            String uri = String.format("/%s/%s/%s/%s/%s", AWSBedrockConstants.GUARDRAIL_SERVICE, guardrailId,
                    AWSBedrockConstants.GUARDRAIL_VERSION, guardrailVersion, AWSBedrockConstants.GUARDRAIL_CALL);
            String url = AWSBedrockConstants.GUARDRAIL_PROTOCOL + "://" + host + uri;

            if (logger.isDebugEnabled()) {
                logger.debug("Initiating bedrock request: " + url);
            }

            // Prepare callout configuration
            Map<String, Object> callOutConfig = new HashMap<>();
            callOutConfig.put(AWSBedrockConstants.GUARDRAIL_PROVIDER_AWSBEDROCK_CALLOUT_SERVICE,
                    AWSBedrockConstants.BEDROCK_SERVICE);
            callOutConfig.put(AWSBedrockConstants.GUARDRAIL_PROVIDER_AWSBEDROCK_CALLOUT_SERVICE_REGION, region);
            callOutConfig.put(AWSBedrockConstants.GUARDRAIL_PROVIDER_AWSBEDROCK_CALLOUT_HOST, host);
            callOutConfig.put(AWSBedrockConstants.GUARDRAIL_PROVIDER_AWSBEDROCK_CALLOUT_URI, uri);
            callOutConfig.put(AWSBedrockConstants.GUARDRAIL_PROVIDER_AWSBEDROCK_CALLOUT_URL, url);
            callOutConfig.put(AWSBedrockConstants.GUARDRAIL_PROVIDER_AWSBEDROCK_CALLOUT_PAYLOAD, payload);

            String response = guardrailProviderService.callOut(callOutConfig);
            return evaluateGuardrailResponse(response, messageContext);
        } catch (APIManagementException | JsonProcessingException | AxisFault e) {
            logger.error("Error during AWS Bedrock Guardrail evaluation.", e);

            if (passthroughOnError) {
                return true; // Continue processing if passthroughOnError is enabled
            }
            messageContext.setProperty(AWSBedrockConstants.CUSTOM_HTTP_SC, AWSBedrockConstants.GUARDRAIL_ERROR_CODE);
            messageContext.setProperty(SynapseConstants.ERROR_CODE, AWSBedrockConstants.APIM_INTERNAL_EXCEPTION_CODE);
            messageContext.setProperty(SynapseConstants.ERROR_MESSAGE, "Violation of " + name + " detected.");
            Mediator faultMediator = messageContext.getFaultSequence();
            faultMediator.mediate(messageContext);
            return false; // Stop further processing
        }
    }

    /**
     * Reverts masked PII (Personally Identifiable Information) placeholders back to their original values
     * in the response payload. This method is intended to be used when the guardrail has masked PII
     * in the request but PII redaction is disabled, allowing the original values to be restored
     * in the response for downstream processing or display.
     *
     * <p>The mapping between placeholders and original PII values is retrieved from the
     * message context property {@code MESSAGE_CONTEXT_PII_ENTITIES_PROPERTY_KEY}. If found,
     * each placeholder in the JSON body is replaced with its corresponding original value.</p>
     *
     * <p>If any replacements are performed, the updated payload is set back into the message context.</p>
     *
     * @param messageContext The current Synapse message context containing request and response metadata.
     * @throws AxisFault If an error occurs while updating the message payload.
     */
    @SuppressWarnings("unchecked")
    private void identifyPIIAndTransform(MessageContext messageContext) throws AxisFault {
        Object piiObj = messageContext.getProperty(AWSBedrockConstants.MESSAGE_CONTEXT_PII_ENTITIES_PROPERTY_KEY);
        if (!(piiObj instanceof Map)) {
            if (logger.isDebugEnabled()) {
                logger.debug("No PII mapping found in the message context.");
            }
            return;
        }

        Map<String, String> maskedPIIEntities = (Map<String, String>) piiObj;
        boolean foundMasked = false;
        String maskedContent = extractJsonContent(messageContext);

        for (Map.Entry<String, String> entry : maskedPIIEntities.entrySet()) {
            String original = entry.getKey();
            String placeholder = entry.getValue();
            maskedContent = maskedContent.replace(placeholder, original);
            foundMasked = true;
        }

        if (foundMasked) {
            if (logger.isDebugEnabled()) {
                logger.debug("PII entities found and in the request. Replacing masked PIIs back in response.");
            }

            // Update the message context with the masked content
            org.apache.axis2.context.MessageContext axis2MC =
                    ((Axis2MessageContext) messageContext).getAxis2MessageContext();
            JsonUtil.getNewJsonPayload(axis2MC, maskedContent, true, true);
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("No PII entities found in the request. No response transformation needed.");
            }
        }
    }

    /**
     * Evaluates the response returned from the AWS Bedrock Guardrail service to determine whether the request or
     * response should be allowed to proceed, blocked, or transformed based on the assessment results.
     *
     * <p>This method handles the following scenarios based on the guardrail action:
     * <ul>
     *     <li><b>GUARDRAIL_INTERVENED:</b> Triggers PII masking or blocking based on the assessment reason.</li>
     *     <li><b>NONE:</b> Indicates no intervention; processing continues as normal.</li>
     * </ul>
     *
     * <p>If {@code redactPII} is disabled and PII masking is applied by Bedrock, the original request is updated
     * with placeholders. In the event of a blocking action, the fault sequence is triggered.</p>
     *
     * @param response         The raw JSON response string from the AWS Bedrock Guardrail API.
     * @param messageContext   The current Synapse message context.
     * @return {@code true} if the request should proceed; {@code false} if processing should stop.
     * @throws APIManagementException If an unexpected response is received or processing fails.
     * @throws JsonProcessingException If parsing the response JSON fails.
     * @throws AxisFault If a Synapse-level error occurs while updating the payload or context.
     */
    private boolean evaluateGuardrailResponse(String response, MessageContext messageContext)
            throws APIManagementException, JsonProcessingException, AxisFault {

        if (response == null || response.isEmpty()) {
            if (passthroughOnError) return true;

            setErrorProperties(messageContext,
                    "AWS Bedrock Guardrails API is unreachable or returned an invalid response.");
            triggerFaultSequence(messageContext);
            return false;
        }

        JsonNode responseBody = new ObjectMapper().readTree(response);
        String action = responseBody.path(AWSBedrockConstants.ASSESSMENT_ACTION).asText(null);
        String reason = responseBody.path(AWSBedrockConstants.ASSESSMENT_REASON).asText(null);
        boolean isResponse = messageContext.isResponse();

        // Check if guardrail intervened
        if (AWSBedrockConstants.AWS_BEDROCK_INTERVENED.equals(action)) {

            if (logger.isDebugEnabled()) {
                logger.debug("AWS Bedrock Guardrail has intervened in the "
                        + (isResponse ? "response." : "request."));
            }

            // Check if guardrail blocked the request
            if (AWSBedrockConstants.AWS_BEDROCK_INTERVENED_AND_BLOCKED.equals(reason)) {
                setErrorProperties(messageContext, buildAssessmentObject(responseBody));
                triggerFaultSequence(messageContext);
                return false;
            }

            boolean maskApplied = AWSBedrockConstants.AWS_BEDROCK_INTERVENED_AND_MASKED.equals(reason);

            // Check if guardrail masked any PII and redactPII is disabled
            if (maskApplied && !redactPII && !isResponse) {
                if (logger.isDebugEnabled()) {
                    logger.debug("PII masking applied by Bedrock service. Masking PII in request.");
                }

                JsonNode sipNode = responseBody.path(AWSBedrockConstants.ASSESSMENTS).path(0)
                        .get(AWSBedrockConstants.BEDROCK_GUARDRAIL_SIP);
                if (sipNode != null && !sipNode.isMissingNode()) {
                    maskPIIEntities(sipNode, messageContext);
                }
                return true; // Continue processing after masking PII
            }

            if (redactPII && maskApplied) {
                redactPIIEntities(responseBody, messageContext);
                return true; // Continue processing after redacting PII
            }
        }

        if (AWSBedrockConstants.AWS_BEDROCK_PASSED.equals(action)) {
            return true; // No intervention, continue processing
        }

        // Unexpected response format or unsupported action
        throw new APIManagementException("AWS Bedrock Guardrails returned unexpected response: " + response);
    }

    /**
     * Sets structured error properties in the Synapse message context to signal guardrail intervention.
     * This method is typically invoked when the AWS Bedrock Guardrail service detects a policy violation.
     *
     * @param context           The current Synapse message context.
     * @param detail  A string containing additional details about the assessment.
     *                          This can be a raw string or a JSON-formatted object.
     */
    private void setErrorProperties(MessageContext context, String detail) {
        context.setProperty(SynapseConstants.ERROR_CODE, AWSBedrockConstants.GUARDRAIL_APIM_EXCEPTION_CODE);
        context.setProperty(AWSBedrockConstants.ERROR_TYPE, AWSBedrockConstants.AWS_BEDROCK_GUARDRAIL);
        context.setProperty(AWSBedrockConstants.CUSTOM_HTTP_SC, AWSBedrockConstants.GUARDRAIL_ERROR_CODE);

        JSONObject assessment = new JSONObject();
        assessment.put(AWSBedrockConstants.ASSESSMENT_ACTION, "GUARDRAIL_INTERVENED");
        assessment.put(AWSBedrockConstants.INTERVENING_GUARDRAIL, name);
        assessment.put(AWSBedrockConstants.DIRECTION, context.isResponse() ? "RESPONSE" : "REQUEST");
        assessment.put(AWSBedrockConstants.ASSESSMENT_REASON, "Violation of AWS Bedrock Guardrails detected.");

        if (showAssessment) {
            try {
                // Attempt to parse as JSON if possible
                JSONObject detailJson = new JSONObject(detail);
                assessment.put(AWSBedrockConstants.ASSESSMENTS, detailJson);
            } catch (JSONException e) {
                // Fallback to raw string if JSON parsing fails
                assessment.put(AWSBedrockConstants.ASSESSMENTS, detail);
            }
        }
        context.setProperty(SynapseConstants.ERROR_MESSAGE, assessment.toString());
    }

    /**
     * Triggers the appropriate fault sequence in the Synapse message context when an error occurs during mediation.
     * This method attempts to use a custom fault sequence identified by {@link AWSBedrockConstants#FAULT_SEQUENCE_KEY}.
     * If unavailable, it gracefully falls back to the default fault sequence.
     *
     * @param context The Synapse {@link MessageContext} carrying message and error metadata.
     */
    private void triggerFaultSequence(MessageContext context) {
        if (logger.isDebugEnabled()) {
            logger.debug("Triggering fault sequence for guardrail: " + name);
        }

        Mediator faultMediator = context.getSequence(AWSBedrockConstants.FAULT_SEQUENCE_KEY);
        if (faultMediator == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Guardrail fault sequence not found. Falling back to default fault sequence.");
            }

            context.setProperty(SynapseConstants.ERROR_MESSAGE, "Violation of " + name + " detected.");
            faultMediator = context.getFaultSequence(); // Fall back to default error sequence
        }
        faultMediator.mediate(context);
    }

    /**
     * Identifies and masks Personally Identifiable Information (PII) in the message payload based on the
     * Bedrock Guardrail's Structured Information Payload (SIP). Applies transformations using both named
     * entities and regex matches, and stores the mapping for later reversal (e.g., in the response flow).
     *
     * @param sipNode The SIP node containing PII entities and regex matches identified by Bedrock.
     * @param context The Synapse message context from which payload is extracted and into which updates are written.
     * @throws AxisFault if payload extraction or update fails.
     */
    private void maskPIIEntities(JsonNode sipNode, MessageContext context) throws AxisFault {
        if (logger.isDebugEnabled()) {
            logger.debug("PII masking applied by Bedrock service. Masking PII in "
                    + (context.isResponse() ? "response." : "request."));
        }

        String originalPayload = extractJsonContent(context);
        String workingPayload = originalPayload;
        Map<String, String> maskedPII = new LinkedHashMap<>();
        AtomicInteger counter = new AtomicInteger();

        boolean hasJsonPath = jsonPath != null && !jsonPath.trim().isEmpty();
        if (hasJsonPath) {
            workingPayload = JsonPath.read(workingPayload, jsonPath).toString()
                    .replaceAll(AWSBedrockConstants.TEXT_CLEAN_REGEX, "").trim();
        }

        JsonNode entities = sipNode.get(AWSBedrockConstants.BEDROCK_GUARDRAIL_PII_ENTITIES);
        JsonNode regexes = sipNode.get(AWSBedrockConstants.BEDROCK_GUARDRAIL_PII_REGEXES);

        workingPayload = processPIIEntities(entities, workingPayload, maskedPII, counter);
        String updatedPayload = processPIIEntities(regexes, workingPayload, maskedPII, counter);

        if (!maskedPII.isEmpty()) {
            context.setProperty(AWSBedrockConstants.MESSAGE_CONTEXT_PII_ENTITIES_PROPERTY_KEY, maskedPII);
        }

        if (hasJsonPath) {
            DocumentContext ctx = JsonPath.parse(originalPayload);
            ctx.set(jsonPath, updatedPayload);
            updatedPayload = ctx.jsonString();
        }

        JsonUtil.getNewJsonPayload(((Axis2MessageContext) context).getAxis2MessageContext(), updatedPayload,
                true, true);
    }

    /**
     * Processes a list of PII entities and replaces any anonymized matches in the given payload
     * with generated placeholders. The mapping of original to masked values is stored in the provided map.
     *
     * @param entities   The JSON array containing PII entities to process.
     * @param payload    The original message content to search and replace.
     * @param maskedPII  The map in which masked replacements are recorded for later use.
     * @param counter    A counter used to generate unique placeholder suffixes.
     * @return The updated payload with all applicable matches replaced by placeholders.
     */
    private String processPIIEntities(JsonNode entities, String payload,
                                    Map<String, String> maskedPII, AtomicInteger counter) {

        if (entities == null || !entities.isArray()) {
            return payload;
        }

        for (JsonNode entity : entities) {
            String action = entity.path(AWSBedrockConstants.BEDROCK_GUARDRAIL_PII_ACTION).asText();
            if (AWSBedrockConstants.AWS_BEDROCK_INTERVENED_AND_ANONYMIZED.equals(action)) {
                String match = entity.path(AWSBedrockConstants.BEDROCK_GUARDRAIL_PII_MATCH).asText();
                // Skip if already processed
                if (maskedPII.containsKey(match)) {
                    continue;
                }

                String type = entity.path(AWSBedrockConstants.BEDROCK_GUARDRAIL_PII_TYPE).asText(null);
                if (type == null || type.isEmpty()) {
                    type = entity.path(AWSBedrockConstants.BEDROCK_GUARDRAIL_PII_NAME).asText().toUpperCase();
                }
                String replacement = type + "_" + generateHexId(counter);
                // Escape regex special characters in the match string
                String escapedMatch = Pattern.quote(match);
                payload = payload.replaceAll(escapedMatch, Matcher.quoteReplacement(replacement));

                maskedPII.put(match, replacement);
            }
        }
        return payload;
    }

    /**
     * Redacts PII content in the payload based on the response from AWS Bedrock Guardrails.
     * This method extracts the redacted text and injects it back into the original message payload,
     * replacing the section specified by a configured JSONPath.
     *
     * @param responseBody    The JSON response body from AWS Bedrock containing redaction details.
     * @param context         The Synapse message context containing the payload to update.
     * @throws AxisFault If an error occurs while updating the message context payload.
     */
    private void redactPIIEntities(JsonNode responseBody, MessageContext context) throws AxisFault {
        if (logger.isDebugEnabled()) {
            logger.debug("PII masking applied by Bedrock service. Redacting PII in "
                    + (context.isResponse() ? "response." : "request."));
        }

        JsonNode output = responseBody.get(AWSBedrockConstants.BEDROCK_GUARDRAIL_OUTPUT);
        if (output == null || !output.isArray() || output.isEmpty()) return;

        JsonNode textNode = output.get(0).path(AWSBedrockConstants.BEDROCK_GUARDRAIL_TEXT);
        String text = textNode.isMissingNode() ? "" : textNode.asText("");
        if (jsonPath != null && !jsonPath.trim().isEmpty()) {
            String originalPayload = extractJsonContent(context);
            DocumentContext ctx = JsonPath.parse(originalPayload);
            ctx.set(jsonPath, text);
            text = ctx.jsonString();
        }

        JsonUtil.getNewJsonPayload(((Axis2MessageContext) context).getAxis2MessageContext(), text,
                true, true);
    }

    private String generateHexId(AtomicInteger counter) {
        int count = counter.getAndIncrement();
        return String.format("%04x", count); // 4-digit hex string, zero-padded
    }

    /**
     * Extracts the first assessment object from the guardrail response, removes any internal metrics,
     * and returns it as a JSON string.
     *
     * <p>This method is primarily used for generating structured diagnostic or error detail output.</p>
     *
     * @param responseBody The root JSON node of the AWS Bedrock guardrail response.
     * @return A JSON string representation of the first assessment object, or an empty string if none found.
     */
    private String buildAssessmentObject(JsonNode responseBody) {
        JsonNode assessments = responseBody.path(AWSBedrockConstants.ASSESSMENTS);
        if (assessments.isArray() && !assessments.isEmpty()) {
            JsonNode firstAssessment = assessments.get(0);
            if (firstAssessment.isObject()) {
                // Clone node to avoid modifying original responseBody
                ObjectNode clonedAssessment = firstAssessment.deepCopy();
                clonedAssessment.remove(AWSBedrockConstants.AWS_BEDROCK_INVOCATION_METRICS);
                return clonedAssessment.toString();
            }
            return firstAssessment.toString();
        }
        return "";
    }

    /**
     * Constructs the JSON request payload for AWS Bedrock Guardrails API.
     * The payload includes the source type (request or response) and the content
     * to be assessed, formatted according to Bedrock API requirements.
     *
     * @param content    The user-generated content to assess.
     * @param isResponse Whether the content originates from a response (true) or a request (false).
     * @return JSON-formatted payload string ready to be sent to the Bedrock API.
     * @throws JsonProcessingException If an error occurs during JSON serialization.
     */
    private String createBedrockRequestPayload(String content, boolean isResponse) throws JsonProcessingException {

        if (logger.isDebugEnabled()) {
            logger.debug("Creating Bedrock Guardrail request payload");
        }

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> requestMap = new TreeMap<>();

        // Set source based on whether it's a request or response
        // Set the content source: REQUEST or RESPONSE
        String source = isResponse
                ? AWSBedrockConstants.BEDROCK_GUARDRAIL_RESPONSE_SOURCE
                : AWSBedrockConstants.BEDROCK_GUARDRAIL_REQUEST_SOURCE;
        requestMap.put(AWSBedrockConstants.BEDROCK_GUARDRAIL_SOURCE_HEADER, source);

        // Create content array with text object
        Map<String, Object> textObj = new TreeMap<>();
        textObj.put(AWSBedrockConstants.BEDROCK_GUARDRAIL_TEXT, content);

        Map<String, Object> contentItem = new TreeMap<>();
        contentItem.put(AWSBedrockConstants.BEDROCK_GUARDRAIL_TEXT, textObj);

        // Add to content array
        requestMap.put(AWSBedrockConstants.BEDROCK_GUARDRAIL_CONTENT, new Object[]{contentItem});

        if (logger.isDebugEnabled()) {
            logger.debug("Successfully created Bedrock Guardrail request payload");
        }

        return mapper.writeValueAsString(requestMap);
    }

    /**
     * Extracts the raw JSON payload as a string from the given Synapse message context.
     *
     * @param messageContext The Axis2 {@link MessageContext} containing the request or response.
     * @return The JSON payload as a String, or null if an error occurs during extraction.
     */
    private String extractJsonContent(MessageContext messageContext) {
        org.apache.axis2.context.MessageContext axis2MC =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        return JsonUtil.jsonPayloadToString(axis2MC);
    }

    public String getName() {

        return name;
    }

    public void setName(String name) {

        this.name = name;
    }

    public String getRegion() {

        return region;
    }

    public void setRegion(String region) {

        this.region = region;
    }

    public String getGuardrailId() {

        return guardrailId;
    }

    public void setGuardrailId(String guardrailId) {

        this.guardrailId = guardrailId;
    }

    public String getGuardrailVersion() {

        return guardrailVersion;
    }

    public void setGuardrailVersion(String guardrailVersion) {

        this.guardrailVersion = guardrailVersion;
    }

    public String getJsonPath() {

        return jsonPath;
    }

    public void setJsonPath(String jsonPath) {

        this.jsonPath = jsonPath;
    }

    public boolean isRedactPII() {

        return redactPII;
    }

    public void setRedactPII(boolean redactPII) {

        this.redactPII = redactPII;
    }

    public boolean isPassthroughOnError() {

        return passthroughOnError;
    }

    public void setPassthroughOnError(boolean passthroughOnError) {

        this.passthroughOnError = passthroughOnError;
    }

    public boolean isShowAssessment() {

        return showAssessment;
    }

    public void setShowAssessment(boolean showAssessment) {

        this.showAssessment = showAssessment;
    }
}
