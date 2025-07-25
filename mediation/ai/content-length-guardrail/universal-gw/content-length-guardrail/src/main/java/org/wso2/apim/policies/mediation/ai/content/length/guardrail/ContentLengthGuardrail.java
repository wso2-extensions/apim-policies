/*
 *
 * Copyright (c) 2025 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.wso2.apim.policies.mediation.ai.content.length.guardrail;

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
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Content Length Guardrail mediator.
 * <p>
 * A Synapse mediator that validates the content length of incoming payloads. This guardrail ensures
 * that the size of the request payload (in bytes) adheres to the defined minimum and maximum content
 * length bounds. It helps enforce payload size restrictions, ensuring that the message size falls
 * within the acceptable limits, enhancing system performance and security by preventing oversized payloads.
 * <p>
 * The validator supports JSONPath expressions to apply validation rules to specific sections of the payload.
 * If no JSONPath is specified, the entire payload is validated against the content length constraints.
 * The content length is calculated based on the UTF-8 byte size of the message.
 * <p>
 * If the validation fails, the mediator interrupts message processing, sets error details in the message context,
 * and triggers a fault sequence. The response includes a structured assessment object with metadata about
 * the content length violation, providing clear information about the size limits and the specific rule violation.
 */
public class ContentLengthGuardrail extends AbstractMediator implements ManagedLifecycle {
    private static final Log logger = LogFactory.getLog(ContentLengthGuardrail.class);

    private String name;
    private int min;
    private int max;
    private String jsonPath = "";
    private boolean invert = false;
    private boolean showAssessment = true;

    /**
     * Initializes the ContentLengthGuardrail mediator.
     *
     * @param synapseEnvironment The Synapse environment instance.
     */
    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        if (logger.isDebugEnabled()) {
            logger.debug("Initializing ContentLengthGuardrail.");
        }

        if (min > max) {
            throw new IllegalArgumentException("'min' cannot be greater than 'max'");
        }
    }

    /**
     * Destroys the ContentLengthGuardrail mediator instance and releases any allocated resources.
     */
    @Override
    public void destroy() {
        // No specific resources to release
    }

    /**
     * The entry point for mediation. This method validates the content length of the incoming payload
     * based on the configured bounds. If the content length is within the bounds, processing continues;
     * otherwise, a fault sequence is triggered and further processing is stopped.
     *
     * @param messageContext The Synapse message context, which contains the request payload.
     * @return {@code true} if validation is successful and processing should continue; {@code false}
     *         if validation fails and further processing should be stopped.
     */
    @Override
    public boolean mediate(MessageContext messageContext) {
        if (logger.isDebugEnabled()) {
            logger.debug("Beginning payload validation.");
        }

        try {
            int count = getContentCount(messageContext);
            boolean validationResult = isCountWithinBounds(count);
            boolean finalResult = invert != validationResult;

            if (!finalResult) {
                // Set error properties in message context
                messageContext.setProperty(SynapseConstants.ERROR_CODE,
                        ContentLengthGuardrailConstants.GUARDRAIL_APIM_EXCEPTION_CODE);
                messageContext.setProperty(ContentLengthGuardrailConstants.ERROR_TYPE,
                        ContentLengthGuardrailConstants.CONTENT_LENGTH_GUARDRAIL);
                messageContext.setProperty(ContentLengthGuardrailConstants.CUSTOM_HTTP_SC,
                        ContentLengthGuardrailConstants.GUARDRAIL_ERROR_CODE);

                // Build assessment details
                String assessmentObject = buildAssessmentObject(count, messageContext.isResponse());
                messageContext.setProperty(SynapseConstants.ERROR_MESSAGE, assessmentObject);

                if (logger.isDebugEnabled()) {
                    logger.debug("Validation failed - triggering fault sequence.");
                }

                Mediator faultMediator = messageContext.getSequence(ContentLengthGuardrailConstants.FAULT_SEQUENCE_KEY);
                if (faultMediator == null) {
                    messageContext.setProperty(SynapseConstants.ERROR_MESSAGE,
                            "Violation of " + name + " detected.");
                    faultMediator = messageContext.getFaultSequence(); // Fall back to default error sequence
                }

                faultMediator.mediate(messageContext);
                return false; // Stop further processing
            }
        } catch (Exception e) {
            logger.error("Error during mediation", e);

            messageContext.setProperty(SynapseConstants.ERROR_CODE,
                    ContentLengthGuardrailConstants.APIM_INTERNAL_EXCEPTION_CODE);
            messageContext.setProperty(SynapseConstants.ERROR_MESSAGE,
                    "Error occurred during ContentLengthGuardrail mediation");
            Mediator faultMediator = messageContext.getFaultSequence();
            faultMediator.mediate(messageContext);
            return false; // Stop further processing
        }

        return true;
    }

    /**
     * Checks whether the given character count is within the configured minimum and maximum bounds.
     *
     * @param count The number of words to evaluate.
     * @return {@code true} if the word count is within bounds; {@code false} otherwise.
     */
    private boolean isCountWithinBounds(int count) {
        return min <= count && max >= count;
    }

    /**
     * Validates the payload by extracting JSON and calculating content length.
     *
     * @param messageContext the Synapse message context
     * @return {@code true} if the byte length of the input is between {@code min} and {@code max} (inclusive),
     * {@code false} otherwise.
     */
    private int getContentCount(MessageContext messageContext) {
        String jsonContent = extractJsonContent(messageContext);
        if (jsonContent == null || jsonContent.isEmpty()) {
            return count("");
        }

        // If no JSON path is specified, apply validation to the entire JSON content
        if (jsonPath == null || jsonPath.trim().isEmpty()) {
            return count(jsonContent);
        }

        String content = JsonPath.read(jsonContent, jsonPath).toString();

        // Remove quotes at beginning and end
        String cleanedText = content.replaceAll(ContentLengthGuardrailConstants.TEXT_CLEAN_REGEX, "").trim();

        return count(cleanedText);
    }

    /**
     * Counts the number of bytes in a given text.
     * <p>
     * Words are separated by whitespace. Quotes at the beginning and end of the text are removed
     * before counting.
     *
     * @param text The text to analyze.
     * @return The number of bytes found.
     */
    private int count(String text) {
        if (logger.isDebugEnabled()) {
            logger.debug("Counting bytes in extracted text.");
        }

        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Extracts JSON content from the message context.
     * This utility method converts the Axis2 message payload to a JSON string.
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
     * This JSON includes information about why the guardrail intervened.
     *
     * @return A JSON string representing the assessment object
     */
    private String buildAssessmentObject(int count, boolean isResponse) {
        if (logger.isDebugEnabled()) {
            logger.debug("Building assessment");
        }

        JSONObject assessmentObject = new JSONObject();

        assessmentObject.put(ContentLengthGuardrailConstants.ASSESSMENT_ACTION, "GUARDRAIL_INTERVENED");
        assessmentObject.put(ContentLengthGuardrailConstants.INTERVENING_GUARDRAIL, name);
        assessmentObject.put(ContentLengthGuardrailConstants.DIRECTION, isResponse? "RESPONSE" : "REQUEST");
        assessmentObject.put(ContentLengthGuardrailConstants.ASSESSMENT_REASON,
                "Violation of applied content length constraints detected.");

        if (showAssessment) {
            String message = String.format(
                    "Expected %s %d %s %d bytes. But found %d.", invert? "less than" : "between", min,
                    invert? "or more than": "and", max, count
            );

            assessmentObject.put(ContentLengthGuardrailConstants.ASSESSMENTS, message);
        }

        return assessmentObject.toString();
    }

    public String getName() {

        return name;
    }

    public void setName(String name) {

        this.name = name;
    }

    public int getMin() {

        return min;
    }

    public void setMin(int min) {

        this.min = min;
    }

    public int getMax() {

        return max;
    }

    public void setMax(int max) {

        this.max = max;
    }

    public String getJsonPath() {

        return jsonPath;
    }

    public void setJsonPath(String jsonPath) {

        this.jsonPath = jsonPath;
    }

    public boolean isInvert() {

        return invert;
    }

    public void setInvert(boolean invert) {

        this.invert = invert;
    }

    public boolean isShowAssessment() {

        return showAssessment;
    }

    public void setShowAssessment(boolean showAssessment) {

        this.showAssessment = showAssessment;
    }
}
