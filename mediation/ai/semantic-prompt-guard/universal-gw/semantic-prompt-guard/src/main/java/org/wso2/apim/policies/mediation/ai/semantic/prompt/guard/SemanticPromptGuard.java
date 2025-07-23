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

package org.wso2.apim.policies.mediation.ai.semantic.prompt.guard;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
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
import org.json.JSONArray;
import org.json.JSONObject;
import org.wso2.apim.policies.mediation.ai.semantic.prompt.guard.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.EmbeddingProviderService;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SemanticPromptGuard is a Synapse mediator that applies semantic rule-based validation
 * using vector similarity on incoming or outgoing JSON payloads.
 *
 * <p>This guard evaluates payloads against configured "allow" and/or "deny" prompts
 * using embedding-based cosine similarity. It supports three modes of operation:
 * <ul>
 *   <li><b>DENY_ONLY</b>: Rejects payloads similar to deny prompts.</li>
 *   <li><b>ALLOW_ONLY</b>: Allows only payloads similar to allow prompts.</li>
 *   <li><b>HYBRID</b>: Combines both allow and deny evaluations.</li>
 * </ul>
 *
 * <p>For each payload, embeddings are computed using the configured {@link EmbeddingProviderService}.
 * Based on a configurable similarity threshold, the payload is either allowed or blocked.
 * If a match is blocked, a structured assessment is added to the message context.
 *
 * <p>Supports optional JSONPath-based extraction for partial content evaluation,
 * and asynchronous embedding similarity calculation using a thread pool.
 *
 * <p>This class implements {@link AbstractMediator} and {@link ManagedLifecycle},
 * enabling integration into the Synapse mediation engine and lifecycle management.
 *
 */
public class SemanticPromptGuard extends AbstractMediator implements ManagedLifecycle {
    private static final Log logger = LogFactory.getLog(SemanticPromptGuard.class);
    private ExecutorService executor;

    private String name;
    private String rules;
    private String jsonPath = "";
    private boolean showAssessment = false;
    private double threshold = 90;

    private int embeddingDimension;
    private double[][] ruleEmbeddings;
    private List<SemanticPromptGuardConstants.PromptType> ruleTypes;
    private List<String> ruleContent;

    private EmbeddingProviderService embeddingProvider;
    private SemanticPromptGuardConstants.RuleProcessingMode processingMode;

    /**
     * Initializes the SemanticPromptGuard mediator.
     *
     * @param synapseEnvironment The Synapse environment instance.
     */
    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        if (logger.isDebugEnabled()) {
            logger.debug("Initializing SemanticPromptGuard.");
        }

        // Initialize thread pool
        executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        try {
            // Resolve and initialize the embedding provider
            embeddingProvider = ServiceReferenceHolder.getInstance().getEmbeddingProvider();

            if (embeddingProvider == null) {
                throw new IllegalStateException("Embedding provider is not registered or available");
            }

            // Fetch embedding dimension for prompt embedding
            embeddingDimension = embeddingProvider.getEmbeddingDimension();
        } catch (APIManagementException e) {
            throw new IllegalStateException("Failed to initialize Semantic Prompt Guard " , e);
        }

        // Generate the rule embeddings based on the provided rules
        processRules(rules);
    }

    /**
     * Parses the configured guardrail rules and embeds each prompt into vector form.
     * Supports both "allowPrompts" and "denyPrompts" rule categories.
     * <p>
     * - DENY_ONLY: If only deny prompts are provided.
     * - ALLOW_ONLY: If only allow prompts are provided.
     * - HYBRID: If both are provided.
     *
     * @param rules JSON string representing allow and deny prompt rules
     *              Example:
     *              {
     *                "allowPrompts": ["allowed prompt 1", "allowed prompt 2"],
     *                "denyPrompts": ["denied prompt 1", "denied prompt 2"]
     *              }
     * @throws RuntimeException if rules cannot be parsed or embeddings fail
     */
    private void processRules(String rules) {
        try {
            Gson gson = new Gson();
            Type mapType = new TypeToken<Map<String, List<String>>>() {}.getType();
            Map<String, List<String>> rulesMap = gson.fromJson(rules, mapType);

            if (rulesMap == null) {
                throw new IllegalArgumentException("Rules JSON attribute is invalid or empty.");
            }

            List<String> allowPrompts = rulesMap.getOrDefault("allowPrompts", Collections.emptyList());
            List<String> denyPrompts = rulesMap.getOrDefault("denyPrompts", Collections.emptyList());
            int totalPrompts = allowPrompts.size() + denyPrompts.size();

            ruleEmbeddings = new double[totalPrompts][embeddingDimension];
            ruleTypes = new ArrayList<>(totalPrompts);
            ruleContent = new ArrayList<>();

            int row = 0;

            // Embed deny prompts
            if (!denyPrompts.isEmpty()) {
                processingMode = SemanticPromptGuardConstants.RuleProcessingMode.DENY_ONLY;
                row = embedPrompts(denyPrompts, SemanticPromptGuardConstants.PromptType.DENY, row);
            }

            // Embed allow prompts
            if (!allowPrompts.isEmpty()) {
                processingMode = (processingMode == null)
                        ? SemanticPromptGuardConstants.RuleProcessingMode.ALLOW_ONLY
                        : SemanticPromptGuardConstants.RuleProcessingMode.HYBRID;
                embedPrompts(allowPrompts, SemanticPromptGuardConstants.PromptType.ALLOW, row);
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Rules added successfully: " + rules);
            }

        } catch (APIManagementException e) {
            throw new IllegalArgumentException("Failed to process semantic rules: " + rules, e);
        }
    }

    /**
     * Embeds the provided prompts and stores their metadata.
     *
     * @param prompts list of textual prompts to embed
     * @param type the prompt type (ALLOW or DENY)
     * @return the next available row index after processing
     * @throws APIManagementException if embedding fails
     */
    private int embedPrompts(List<String> prompts, SemanticPromptGuardConstants.PromptType type, int row)
            throws APIManagementException {

        for (String prompt : prompts) {
            double[] embedding = embeddingProvider.getEmbedding(prompt);
            storePrompt(row, embedding, type, prompt);
            row++;
        }
        return row;
    }

    /**
     * Destroys the SemanticPromptGuard mediator instance and releases any allocated resources.
     */
    @Override
    public void destroy() {
        // Release resources and shutdown executor
        executor.shutdown();
    }

    @Override
    public boolean mediate(MessageContext messageContext) {
        if (logger.isDebugEnabled()) {
            logger.debug("Starting payload validation.");
        }

        try {
            boolean isValid = applyRules(messageContext);

            if (!isValid) {
                // Set error properties in message context
                messageContext.setProperty(SynapseConstants.ERROR_CODE,
                        SemanticPromptGuardConstants.GUARDRAIL_APIM_EXCEPTION_CODE);
                messageContext.setProperty(SemanticPromptGuardConstants.ERROR_TYPE,
                        SemanticPromptGuardConstants.SEMANTIC_PROMPT_GUARD);
                messageContext.setProperty(SemanticPromptGuardConstants.CUSTOM_HTTP_SC,
                        SemanticPromptGuardConstants.GUARDRAIL_ERROR_CODE);

                // Build assessment details
                String assessmentObject = buildAssessmentObject(messageContext);
                messageContext.setProperty(SynapseConstants.ERROR_MESSAGE, assessmentObject);

                if (logger.isDebugEnabled()) {
                    logger.debug("Validation failed - triggering fault sequence.");
                }

                Mediator faultMediator = messageContext.getSequence(SemanticPromptGuardConstants.FAULT_SEQUENCE_KEY);
                if (faultMediator == null) {
                    messageContext.setProperty(SynapseConstants.ERROR_MESSAGE,
                            "Violation of " + name + " detected.");
                    faultMediator = messageContext.getFaultSequence(); // Fall back to default error sequence
                }
                faultMediator.mediate(messageContext);
                return false; // Stop further processing
            }
        } catch (Exception e) {
            logger.error("Exception occurred during mediation.", e);

            messageContext.setProperty(SynapseConstants.ERROR_CODE,
                    SemanticPromptGuardConstants.APIM_INTERNAL_EXCEPTION_CODE);
            messageContext.setProperty(SynapseConstants.ERROR_MESSAGE, "Error occurred during " + name + " mediation");
            Mediator faultMediator = messageContext.getFaultSequence();
            faultMediator.mediate(messageContext);
            return false;
        }

        return true;
    }

    /**
     * Applies allow/deny rule validation on the incoming JSON payload.
     * If a JSON path is specified, validation is applied to the extracted value.
     * Otherwise, the entire payload is validated.
     *
     * @param messageContext The current message context.
     * @return true if the content is allowed to pass; false if denied.
     * @throws APIManagementException if embedding or rule evaluation fails.
     */
    private boolean applyRules(MessageContext messageContext) throws APIManagementException {
        if (logger.isDebugEnabled()) {
            logger.debug("Applying semantic prompt guard rules.");
        }

        String jsonContent = extractJsonContent(messageContext);
        if (jsonContent == null || jsonContent.isEmpty()) {
            return true;
        }

        // If no JSON path is specified, apply validation rules to the entire JSON content
        if (jsonPath == null || jsonPath.trim().isEmpty()) {
            return isAllowed(jsonContent, messageContext);
        }

        String content = JsonPath.read(jsonContent, jsonPath).toString();

        // Remove wrapping quotes
        String cleanedText = content.replaceAll(SemanticPromptGuardConstants.JSON_CLEAN_REGEX, "").trim();
        return isAllowed(cleanedText, messageContext);
    }

    /**
     * Determines whether the given JSON content is allowed based on similarity with
     * predefined allow/deny prompts.
     * <p>
     * - If a deny rule matches above the threshold, the request is blocked.
     * - If an allow rule matches, the request is allowed.
     * - If no match and processing mode is DENY_ONLY, request is allowed.
     * - Otherwise, request is blocked.
     *
     * @param jsonContent     The extracted payload content to validate.
     * @param messageContext  The current Synapse message context.
     * @return {@code true} if the content is allowed to proceed, {@code false} if blocked.
     * @throws APIManagementException if the embedding provider fails.
     */
    private boolean isAllowed(String jsonContent, MessageContext messageContext) throws APIManagementException {
        if (jsonContent == null || jsonContent.isEmpty()) {
            return true;
        }

        double[] similarityScores = calculateSimilarity(jsonContent);

        // The loop first go through all deny prompt scores and then only allow prompt scores.
        for (int i = 0; i < similarityScores.length; i++) {
            double score = similarityScores[i] * 100;
            SemanticPromptGuardConstants.PromptType type = ruleTypes.get(i);

            if (score >= threshold) {
                if (type == SemanticPromptGuardConstants.PromptType.DENY) {
                    messageContext.setProperty(SemanticPromptGuardConstants.DENIED_PROMPT_KEY, ruleContent.get(i));
                    return false; // Deny match overrides all
                } else if (type == SemanticPromptGuardConstants.PromptType.ALLOW) {
                    return true; // Allow match found
                }
            }
        }

        // Allow only if using deny-only mode
        return processingMode == SemanticPromptGuardConstants.RuleProcessingMode.DENY_ONLY;
    }

    /**
     * Computes cosine similarity scores between the embedding vector of the given prompt
     * and all stored rule embeddings concurrently.
     * <p>
     * Cosine Similarity = (A • B) / (||A|| * ||B||)
     *
     * @param prompt The input text to be compared against rule embeddings.
     * @return An array of similarity scores, one per rule embedding.
     * @throws APIManagementException If the embedding provider fails to generate embeddings.
     */
    private double[] calculateSimilarity(String prompt) throws APIManagementException {
        double[] queryVector = embeddingProvider.getEmbedding(prompt);
        double queryNorm = l2Norm(queryVector);
        int numRules = ruleEmbeddings.length;
        double[] results = new double[numRules];

        List<CompletableFuture<Void>> tasks = new ArrayList<>(numRules);

        // CosineSimilarity = dotProduct(ruleVec, queryVector)/ (l2Norm(ruleVec) * l2Norm(queryVector))
        for (int i = 0; i < numRules; i++) {
            final int rowIndex = i;
            tasks.add(CompletableFuture.runAsync(() -> {
                double[] ruleVec = ruleEmbeddings[rowIndex];
                double dot = dotProduct(ruleVec, queryVector);
                double ruleNorm = l2Norm(ruleVec);
                double denominator = queryNorm * ruleNorm;
                results[rowIndex] = (denominator == 0.0) ? 0.0 : (dot / denominator);
            }, executor));
        }

        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        return results;
    }

    /**
     * Computes the dot product of two vectors.
     */
    private double dotProduct(double[] a, double[] b) {
        double result = 0.0;
        for (int i = 0; i < a.length; i++) {
            result += a[i] * b[i];
        }
        return result;
    }

    /**
     * Computes the Euclidean (L2) norm of a vector.
     */
    private double l2Norm(double[] vector) {
        double sum = 0.0;
        for (double v : vector) {
            sum += v * v;
        }
        return Math.sqrt(sum);
    }

    /**
     * Constructs a JSON-formatted assessment result indicating a rule violation.
     * Includes metadata about the violation, the processing mode, and violated/allowed rules
     * based on configuration and evaluation mode.
     *
     * @param messageContext The Synapse message context used to extract request direction and rule violation details.
     * @return A JSON string representing the guardrail assessment result.
     */
    private String buildAssessmentObject(MessageContext messageContext) {
        if (logger.isDebugEnabled()) {
            logger.debug("Building guardrail assessment object.");
        }

        JSONObject assessmentObject = new JSONObject();
        assessmentObject.put(SemanticPromptGuardConstants.ASSESSMENT_ACTION, "GUARDRAIL_INTERVENED");
        assessmentObject.put(SemanticPromptGuardConstants.INTERVENING_GUARDRAIL, name);
        assessmentObject.put(SemanticPromptGuardConstants.DIRECTION,
                messageContext.isResponse()? "RESPONSE" : "REQUEST");
        assessmentObject.put(SemanticPromptGuardConstants.ASSESSMENT_REASON, "Violation of guard prompts detected.");

        if (showAssessment) {
            JSONObject assessmentDetails = new JSONObject();

            switch (processingMode) {
                case DENY_ONLY:
                    assessmentDetails.put("message", "One or more semantic rules are violated.");
                    assessmentDetails.put("deniedRule",
                            messageContext.getProperty(SemanticPromptGuardConstants.DENIED_PROMPT_KEY));
                    break;

                case ALLOW_ONLY:
                case HYBRID:
                    assessmentDetails.put("message", "None of the allowed prompts are met.");
                    JSONArray allowedRulesArray = new JSONArray();
                    for (int i = 0; i < ruleTypes.size(); i++) {
                        if (SemanticPromptGuardConstants.PromptType.ALLOW.equals(ruleTypes.get(i))) {
                            allowedRulesArray.put(ruleContent.get(i));
                        }
                    }
                    assessmentDetails.put("allowedRules", allowedRulesArray);
                    break;

                default:
                    logger.warn("Unknown processing mode while building assessment object.");
            }

            assessmentObject.put(SemanticPromptGuardConstants.ASSESSMENTS, assessmentDetails);
        }
        return assessmentObject.toString();
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

    /**
     * Stores the prompt metadata and embedding vector in the in-memory store.
     *
     * @param row       the index (row) at which to store the embedding vector
     * @param embedding the double array representing the embedding vector
     * @param type      the prompt type classification
     * @param prompt    the original prompt string
     */
    private void storePrompt(int row, double[] embedding, SemanticPromptGuardConstants.PromptType type, String prompt) {
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Storing prompt at row %d | Type: %s", row, type));
        }

        System.arraycopy(embedding, 0, ruleEmbeddings[row], 0, embeddingDimension);
        ruleTypes.add(type);
        ruleContent.add(prompt);
    }

    public String getName() {

        return name;
    }

    public void setName(String name) {

        this.name = name;
    }

    public String getRules() {

        return rules;
    }

    public void setRules(String rules) throws IOException {

        this.rules = rules;
    }

    public String getJsonPath() {

        return jsonPath;
    }

    public void setJsonPath(String jsonPath) {

        this.jsonPath = jsonPath;
    }

    public boolean isShowAssessment() {

        return showAssessment;
    }

    public void setShowAssessment(boolean showAssessment) {

        this.showAssessment = showAssessment;
    }

    public double getThreshold() {

        return threshold;
    }

    public void setThreshold(double threshold) {

        this.threshold = threshold;
    }
}
