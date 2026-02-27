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

package org.wso2.apim.policies.mediation.ai.semantic.tool.filtering;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.axis2.AxisFault;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.AbstractMediator;
import org.wso2.apim.policies.mediation.ai.semantic.tool.filtering.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.EmbeddingProviderService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SemanticToolFiltering is a Synapse mediator that dynamically filters the tools provided
 * within an API request based on their semantic relevance to the user query.
 * <p>
 * This policy extracts both the query and the tool definitions from the incoming payload,
 * generates an embedding for the query, and performs a similarity search against the provided tools.
 * It then replaces the original 'tools' array with a filtered subset, optimizing the request
 * before it reaches the LLM.
 * <p>
 * Supports two selection modes:
 * <ul>
 *   <li><b>By Rank</b>: Selects a fixed number (topK) of the most relevant tools.</li>
 *   <li><b>By Threshold</b>: Selects all tools exceeding a similarity score threshold.</li>
 * </ul>
 * <p>
 * Tools and queries can be in JSON format (using JSONPath extraction) or text format
 * (using XML-like tags for extraction).
 * <p>
 */
public class SemanticToolFiltering extends AbstractMediator implements ManagedLifecycle {

    private static final Log logger = LogFactory.getLog(SemanticToolFiltering.class);

    /**
     * Regex pattern to validate simple JSONPath expressions.
     * Supports patterns like: $.tools, $.data.items, $.results[0].tools, $.messages[-1].content
     */
    private static final Pattern SIMPLE_JSON_PATH_PATTERN = Pattern.compile(
            "^\\$\\.([a-zA-Z_][a-zA-Z0-9_]*(\\[-?\\d+\\])?\\.)*[a-zA-Z_][a-zA-Z0-9_]*(\\[-?\\d+\\])?$"
    );

    // Policy properties (set via Synapse reflection from artifact.j2)
    private String selectionMode = SemanticToolFilteringConstants.SELECTION_MODE_TOP_K;
    private int limit = SemanticToolFilteringConstants.DEFAULT_TOP_K;
    private double threshold = SemanticToolFilteringConstants.DEFAULT_THRESHOLD;
    private String queryJSONPath = SemanticToolFilteringConstants.DEFAULT_QUERY_JSON_PATH;
    private String toolsJSONPath = SemanticToolFilteringConstants.DEFAULT_TOOLS_JSON_PATH;
    private boolean userQueryIsJson = SemanticToolFilteringConstants.DEFAULT_USER_QUERY_IS_JSON;
    private boolean toolsIsJson = SemanticToolFilteringConstants.DEFAULT_TOOLS_IS_JSON;

    private EmbeddingProviderService embeddingProvider;

    // ---------- Lifecycle ----------

    /**
     * Initializes the SemanticToolFiltering mediator.
     *
     * @param synapseEnvironment The Synapse environment instance.
     */
    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        if (logger.isDebugEnabled()) {
            logger.debug("Initializing SemanticToolFiltering mediator.");
        }

        embeddingProvider = ServiceReferenceHolder.getInstance().getEmbeddingProvider();
        if (embeddingProvider == null) {
            throw new IllegalStateException("Embedding provider is not registered or available");
        }

        // Validate selectionMode
        if (!SemanticToolFilteringConstants.SELECTION_MODE_TOP_K.equals(selectionMode)
                && !SemanticToolFilteringConstants.SELECTION_MODE_THRESHOLD.equals(selectionMode)) {
            throw new IllegalArgumentException("Invalid selectionMode: '" + selectionMode
                    + "'. Allowed values are '" + SemanticToolFilteringConstants.SELECTION_MODE_TOP_K
                    + "' or '" + SemanticToolFilteringConstants.SELECTION_MODE_THRESHOLD + "'.");
        }

        // Validate limit (topK)
        if (limit <= 0) {
            throw new IllegalArgumentException("Invalid limit: " + limit
                    + ". Must be a positive integer.");
        }

        // Validate threshold
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("Invalid threshold: " + threshold
                    + ". Must be between 0.0 and 1.0.");
        }

        // Validate queryJSONPath
        if (userQueryIsJson && !isValidSimpleJSONPath(queryJSONPath)) {
            throw new IllegalArgumentException("Invalid queryJSONPath: " + queryJSONPath
                    + ". Only simple dotted paths with optional array indices are supported "
                    + "(e.g., '$.messages[-1].content', '$.query').");
        }

        // Validate toolsJSONPath
        if (toolsIsJson && !isValidSimpleJSONPath(toolsJSONPath)) {
            throw new IllegalArgumentException("Invalid toolsJSONPath: " + toolsJSONPath
                    + ". Only simple dotted paths with optional array indices are supported "
                    + "(e.g., '$.tools', '$.data.items', '$.results[0].tools').");
        }

        if (logger.isDebugEnabled()) {
            logger.debug("SemanticToolFiltering initialized: selectionMode=" + selectionMode
                    + ", limit=" + limit + ", threshold=" + threshold
                    + ", queryJSONPath=" + queryJSONPath + ", toolsJSONPath=" + toolsJSONPath
                    + ", userQueryIsJson=" + userQueryIsJson + ", toolsIsJson=" + toolsIsJson);
        }
    }

    /**
     * Destroys the SemanticToolFiltering mediator instance and releases allocated resources.
     */
    @Override
    public void destroy() {
        // No resources to release
    }

    // ---------- Mediation ----------

    /**
     * Main mediation entry point. Extracts user query and tools from the request,
     * computes semantic similarity, and filters tools accordingly.
     *
     * @param messageContext The message context containing the request.
     * @return {@code true} to continue mediation, {@code false} to halt.
     */
    @Override
    public boolean mediate(MessageContext messageContext) {
        if (logger.isDebugEnabled()) {
            logger.debug("Starting semantic tool filtering mediation.");
        }

        try {
            org.apache.axis2.context.MessageContext axis2MC =
                    ((Axis2MessageContext) messageContext).getAxis2MessageContext();
            String jsonContent = JsonUtil.jsonPayloadToString(axis2MC);

            if (jsonContent == null || jsonContent.isEmpty()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Empty request body - skipping tool filtering.");
                }
                return true;
            }

            byte[] content = jsonContent.getBytes();

            // Route based on format type
            if (userQueryIsJson && toolsIsJson) {
                return handleJSONRequest(messageContext, axis2MC, jsonContent);
            } else if (!userQueryIsJson && !toolsIsJson) {
                return handleTextRequest(messageContext, axis2MC, jsonContent);
            } else {
                return handleMixedRequest(messageContext, axis2MC, jsonContent);
            }

        } catch (Exception e) {
            logger.warn("Exception occurred during semantic tool filtering mediation. "
                    + "Passing request through without filtering.", e);
            return true;
        }
    }

    // ---------- JSON Mode ----------

    /**
     * Handles requests where both user query and tools are in JSON format.
     */
    private boolean handleJSONRequest(MessageContext messageContext,
                                      org.apache.axis2.context.MessageContext axis2MC,
                                      String jsonContent) throws APIManagementException {
        // Extract user query using JSONPath
        String userQuery = extractJsonPathString(jsonContent, queryJSONPath);
        if (userQuery == null || userQuery.isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("User query cannot be identified - skipping tool filtering.");
            }
            return true;
        }

        // Parse as JSON object using Gson
        JsonObject requestBody = JsonParser.parseString(jsonContent).getAsJsonObject();

        // Extract tools array using path navigation
        JsonElement toolsElement = readAtPath(requestBody, toolsJSONPath);
        JsonArray toolsArray = (toolsElement != null && toolsElement.isJsonArray())
                ? toolsElement.getAsJsonArray() : null;

        if (toolsArray == null || toolsArray.size() == 0) {
            if (logger.isDebugEnabled()) {
                logger.debug("No tools can be identified.");
            }
            return true;
        }

        // Generate embedding for user query
        double[] queryEmbedding = embeddingProvider.getEmbedding(userQuery);

        // Get embedding cache
        EmbeddingCache embeddingCache = EmbeddingCache.getInstance();
        String apiId = (String) messageContext.getProperty(SemanticToolFilteringConstants.API_UUID);
        if (apiId == null) {
            apiId = "unknown";
        }
        embeddingCache.addAPICache(apiId);

        // Process tools and compute similarity
        List<ToolWithScore> toolsWithScores = new ArrayList<>();
        List<EmbeddingCache.ToolEntry> toolEntriesToCache = new ArrayList<>();

        int[] cacheLimits = embeddingCache.getCacheLimits();
        int maxToolsPerAPI = cacheLimits[1];
        int currentCachedCount = embeddingCache.getAPICacheSize(apiId);
        int availableSlots = Math.max(0, maxToolsPerAPI - currentCachedCount);
        int newToolIndex = 0;

        for (int i = 0; i < toolsArray.size(); i++) {
            JsonElement toolRaw = toolsArray.get(i);
            if (!toolRaw.isJsonObject()) {
                continue;
            }
            JsonObject toolObj = toolRaw.getAsJsonObject();

            String toolDesc = extractToolDescription(toolObj);
            if (toolDesc == null || toolDesc.isEmpty()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("No description found for tool - skipping.");
                }
                continue;
            }

            String toolName = getStringOr(toolObj, "name", "");
            String cacheKey = getCacheKey(toolDesc);

            // Try cache first
            double[] toolEmbedding;
            EmbeddingCache.EmbeddingEntry cachedEntry = embeddingCache.getEntry(apiId, cacheKey);
            if (cachedEntry != null) {
                toolEmbedding = cachedEntry.getEmbedding();
                if (logger.isDebugEnabled()) {
                    logger.debug("Cache hit for tool: " + toolName);
                }
            } else {
                // Generate embedding
                toolEmbedding = embeddingProvider.getEmbedding(toolDesc);

                // Only cache if slots are available
                if (newToolIndex < availableSlots) {
                    toolEntriesToCache.add(new EmbeddingCache.ToolEntry(cacheKey, toolName, toolEmbedding));
                }
                newToolIndex++;
            }

            double similarity = cosineSimilarity(queryEmbedding, toolEmbedding);
            toolsWithScores.add(new ToolWithScore(toolObj, similarity));
        }

        // Bulk add to cache
        if (!toolEntriesToCache.isEmpty()) {
            embeddingCache.bulkAddTools(apiId, toolEntriesToCache);
        }

        if (toolsWithScores.isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("No valid tools after embedding generation.");
            }
            return true;
        }

        // Filter tools
        List<JsonObject> filteredTools = filterTools(toolsWithScores);

        if (logger.isDebugEnabled()) {
            logger.debug("Filtered tools: original=" + toolsArray.size()
                    + ", filtered=" + filteredTools.size()
                    + ", selectionMode=" + selectionMode);
        }

        // Update request body with filtered tools
        JsonArray filteredArray = new JsonArray();
        for (JsonObject tool : filteredTools) {
            filteredArray.add(tool);
        }
        updateToolsInRequestBody(requestBody, toolsJSONPath, filteredArray);

        // Replace the payload
        String modifiedBody = requestBody.toString();
        try {
            JsonUtil.getNewJsonPayload(axis2MC, modifiedBody, true, true);
        } catch (AxisFault e) {
            logger.warn("Error replacing JSON payload. Passing request through without filtering.");
            return true;
        }

        return true;
    }

    // ---------- Text Mode ----------

    /**
     * Handles requests where both user query and tools are in text format with tags.
     */
    private boolean handleTextRequest(MessageContext messageContext,
                                      org.apache.axis2.context.MessageContext axis2MC,
                                      String content) throws APIManagementException {
        // Extract user query from <userq> tags
        String userQuery = extractUserQueryFromText(content);
        if (userQuery == null || userQuery.isEmpty()) {
            logger.warn("User query start tag <userq> not found in text content. "
                    + "Passing request through without filtering.");
            return true;
        }

        // Extract tools from tags
        List<TextTool> textTools = extractToolsFromText(content);
        if (textTools == null || textTools.isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("No tools to filter in text mode.");
            }
            return true;
        }

        // Generate embedding for user query
        double[] queryEmbedding = embeddingProvider.getEmbedding(userQuery);

        // Get embedding cache
        EmbeddingCache embeddingCache = EmbeddingCache.getInstance();
        String apiId = (String) messageContext.getProperty(SemanticToolFilteringConstants.API_UUID);
        if (apiId == null) {
            apiId = "unknown";
        }
        embeddingCache.addAPICache(apiId);

        // Process tools and compute similarity
        List<TextToolWithScore> toolsWithScores = new ArrayList<>();
        List<EmbeddingCache.ToolEntry> toolEntriesToCache = new ArrayList<>();

        int[] cacheLimits = embeddingCache.getCacheLimits();
        int maxToolsPerAPI = cacheLimits[1];
        int currentCachedCount = embeddingCache.getAPICacheSize(apiId);
        int availableSlots = Math.max(0, maxToolsPerAPI - currentCachedCount);
        int newToolIndex = 0;

        for (TextTool tool : textTools) {
            String toolText = tool.getName() + ": " + tool.getDescription();
            String cacheKey = getCacheKey(toolText);

            double[] toolEmbedding;
            EmbeddingCache.EmbeddingEntry cachedEntry = embeddingCache.getEntry(apiId, cacheKey);
            if (cachedEntry != null) {
                toolEmbedding = cachedEntry.getEmbedding();
            } else {
                toolEmbedding = embeddingProvider.getEmbedding(toolText);
                if (newToolIndex < availableSlots) {
                    toolEntriesToCache.add(new EmbeddingCache.ToolEntry(cacheKey, tool.getName(), toolEmbedding));
                }
                newToolIndex++;
            }

            double similarity = cosineSimilarity(queryEmbedding, toolEmbedding);
            toolsWithScores.add(new TextToolWithScore(tool, similarity));
        }

        // Bulk add to cache
        if (!toolEntriesToCache.isEmpty()) {
            embeddingCache.bulkAddTools(apiId, toolEntriesToCache);
        }

        if (toolsWithScores.isEmpty()) {
            return true;
        }

        // Sort by score descending
        toolsWithScores.sort(Comparator.comparingDouble(TextToolWithScore::getScore).reversed());

        // Filter based on selection mode
        Set<String> filteredToolNames = new HashSet<>();
        if (SemanticToolFilteringConstants.SELECTION_MODE_TOP_K.equals(selectionMode)) {
            int k = Math.min(limit, toolsWithScores.size());
            for (int i = 0; i < k; i++) {
                filteredToolNames.add(toolsWithScores.get(i).getTool().getName());
            }
        } else {
            for (TextToolWithScore item : toolsWithScores) {
                if (item.getScore() >= threshold) {
                    filteredToolNames.add(item.getTool().getName());
                }
            }
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Filtered tools (text mode): original=" + textTools.size()
                    + ", filtered=" + filteredToolNames.size()
                    + ", selectionMode=" + selectionMode);
        }

        // Rebuild text with only filtered tools and strip all tags
        String modifiedContent = rebuildTextWithFilteredTools(content, textTools, filteredToolNames);
        modifiedContent = stripAllTags(modifiedContent);
        try {
            JsonUtil.getNewJsonPayload(axis2MC, modifiedContent, true, true);
        } catch (AxisFault e) {
            logger.warn("Error replacing text payload. Passing request through without filtering.");
            return true;
        }

        return true;
    }

    // ---------- Mixed Mode ----------

    /**
     * Handles requests where user query and tools have different format types.
     */
    private boolean handleMixedRequest(MessageContext messageContext,
                                       org.apache.axis2.context.MessageContext axis2MC,
                                       String content) throws APIManagementException {
        // Extract user query
        String userQuery;
        if (userQueryIsJson) {
            userQuery = extractJsonPathString(content, queryJSONPath);
        } else {
            userQuery = extractUserQueryFromText(content);
        }

        if (userQuery == null || userQuery.isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("User query cannot be identified in mixed mode - skipping.");
            }
            return true;
        }

        // Generate embedding for user query
        double[] queryEmbedding = embeddingProvider.getEmbedding(userQuery);

        // Get embedding cache
        EmbeddingCache embeddingCache = EmbeddingCache.getInstance();
        String apiId = (String) messageContext.getProperty(SemanticToolFilteringConstants.API_UUID);
        if (apiId == null) {
            apiId = "unknown";
        }
        embeddingCache.addAPICache(apiId);

        if (toolsIsJson) {
            // Tools in JSON format - parse with Gson
            JsonObject requestBody = JsonParser.parseString(content).getAsJsonObject();
            JsonElement toolsElement = readAtPath(requestBody, toolsJSONPath);
            JsonArray toolsArray = (toolsElement != null && toolsElement.isJsonArray())
                    ? toolsElement.getAsJsonArray() : null;

            if (toolsArray == null || toolsArray.size() == 0) {
                return true;
            }

            List<ToolWithScore> toolsWithScores = new ArrayList<>();
            List<EmbeddingCache.ToolEntry> toolEntriesToCache = new ArrayList<>();

            int[] cacheLimits = embeddingCache.getCacheLimits();
            int availableSlots = Math.max(0, cacheLimits[1] - embeddingCache.getAPICacheSize(apiId));
            int newToolIndex = 0;

            for (int i = 0; i < toolsArray.size(); i++) {
                JsonElement toolRaw = toolsArray.get(i);
                if (!toolRaw.isJsonObject()) {
                    continue;
                }
                JsonObject toolObj = toolRaw.getAsJsonObject();
                String toolDesc = extractToolDescription(toolObj);
                if (toolDesc == null || toolDesc.isEmpty()) {
                    continue;
                }

                String toolName = getStringOr(toolObj, "name", "");
                String cacheKey = getCacheKey(toolDesc);

                double[] toolEmbedding;
                EmbeddingCache.EmbeddingEntry cachedEntry = embeddingCache.getEntry(apiId, cacheKey);
                if (cachedEntry != null) {
                    toolEmbedding = cachedEntry.getEmbedding();
                } else {
                    toolEmbedding = embeddingProvider.getEmbedding(toolDesc);
                    if (newToolIndex < availableSlots) {
                        toolEntriesToCache.add(new EmbeddingCache.ToolEntry(cacheKey, toolName, toolEmbedding));
                    }
                    newToolIndex++;
                }

                double similarity = cosineSimilarity(queryEmbedding, toolEmbedding);
                toolsWithScores.add(new ToolWithScore(toolObj, similarity));
            }

            if (!toolEntriesToCache.isEmpty()) {
                embeddingCache.bulkAddTools(apiId, toolEntriesToCache);
            }

            if (toolsWithScores.isEmpty()) {
                return true;
            }

            List<JsonObject> filteredTools = filterTools(toolsWithScores);
            JsonArray filteredArray = new JsonArray();
            for (JsonObject tool : filteredTools) {
                filteredArray.add(tool);
            }
            updateToolsInRequestBody(requestBody, toolsJSONPath, filteredArray);

            String modifiedBody = requestBody.toString();
            try {
                JsonUtil.getNewJsonPayload(axis2MC, modifiedBody, true, true);
            } catch (AxisFault e) {
                logger.warn("Error replacing JSON payload in mixed mode. "
                        + "Passing request through without filtering.");
                return true;
            }

        } else {
            // Tools in text format
            List<TextTool> textTools = extractToolsFromText(content);
            if (textTools == null || textTools.isEmpty()) {
                return true;
            }

            List<TextToolWithScore> toolsWithScores = new ArrayList<>();
            List<EmbeddingCache.ToolEntry> toolEntriesToCache = new ArrayList<>();

            int[] cacheLimits = embeddingCache.getCacheLimits();
            int availableSlots = Math.max(0, cacheLimits[1] - embeddingCache.getAPICacheSize(apiId));
            int newToolIndex = 0;

            for (TextTool tool : textTools) {
                String toolText = tool.getName() + ": " + tool.getDescription();
                String cacheKey = getCacheKey(toolText);

                double[] toolEmbedding;
                EmbeddingCache.EmbeddingEntry cachedEntry = embeddingCache.getEntry(apiId, cacheKey);
                if (cachedEntry != null) {
                    toolEmbedding = cachedEntry.getEmbedding();
                } else {
                    toolEmbedding = embeddingProvider.getEmbedding(toolText);
                    if (newToolIndex < availableSlots) {
                        toolEntriesToCache.add(new EmbeddingCache.ToolEntry(cacheKey, tool.getName(), toolEmbedding));
                    }
                    newToolIndex++;
                }

                double similarity = cosineSimilarity(queryEmbedding, toolEmbedding);
                toolsWithScores.add(new TextToolWithScore(tool, similarity));
            }

            if (!toolEntriesToCache.isEmpty()) {
                embeddingCache.bulkAddTools(apiId, toolEntriesToCache);
            }

            if (toolsWithScores.isEmpty()) {
                return true;
            }

            toolsWithScores.sort(Comparator.comparingDouble(TextToolWithScore::getScore).reversed());

            Set<String> filteredToolNames = new HashSet<>();
            if (SemanticToolFilteringConstants.SELECTION_MODE_TOP_K.equals(selectionMode)) {
                int k = Math.min(limit, toolsWithScores.size());
                for (int i = 0; i < k; i++) {
                    filteredToolNames.add(toolsWithScores.get(i).getTool().getName());
                }
            } else {
                for (TextToolWithScore item : toolsWithScores) {
                    if (item.getScore() >= threshold) {
                        filteredToolNames.add(item.getTool().getName());
                    }
                }
            }

            String modifiedContent = rebuildTextWithFilteredTools(content, textTools, filteredToolNames);
            modifiedContent = stripAllTags(modifiedContent);
            try {
                JsonUtil.getNewJsonPayload(axis2MC, modifiedContent, true, true);
            } catch (AxisFault e) {
                logger.warn("Error replacing text payload in mixed mode. "
                        + "Passing request through without filtering.");
                return true;
            }
        }

        return true;
    }

    // ---------- Helper Methods ----------

    /**
     * Extracts a string value from JSON content using a JSONPath expression.
     *
     * @param jsonContent the JSON string
     * @param path        the JSONPath expression
     * @return the extracted string, or null if extraction fails
     */
    private String extractJsonPathString(String jsonContent, String path) {
        try {
            JsonElement root = JsonParser.parseString(jsonContent);
            JsonElement result = readAtPath(root, path);
            if (result == null || result.isJsonNull()) {
                return null;
            }
            if (result.isJsonPrimitive()) {
                return result.getAsString().trim();
            }
            // For non-primitive results, stringify and clean
            return result.toString().replaceAll(SemanticToolFilteringConstants.JSON_CLEAN_REGEX, "").trim();
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to extract value using path: " + path);
            }
            return null;
        }
    }

    /**
     * Navigates a simple JSONPath expression on a parsed JsonElement.
     * Supports paths like: $.tools, $.data.items, $.results[0].tools
     *
     * @param root the root JSON element
     * @param path the JSONPath expression (e.g., "$.tools")
     * @return the element at the path, or null if not found
     */
    private JsonElement readAtPath(JsonElement root, String path) {
        if (root == null || root.isJsonNull()) {
            return null;
        }
        String cleanPath = path.startsWith("$.") ? path.substring(2) : path;
        String[] parts = cleanPath.split("\\.");

        JsonElement current = root;
        for (String part : parts) {
            if (current == null || current.isJsonNull()) {
                return null;
            }
            int openIdx = part.indexOf('[');
            if (openIdx != -1 && part.endsWith("]")) {
                String field = part.substring(0, openIdx);
                int arrayIndex = Integer.parseInt(part.substring(openIdx + 1, part.length() - 1));
                if (!current.isJsonObject()) {
                    return null;
                }
                JsonElement arr = current.getAsJsonObject().get(field);
                if (arr == null || !arr.isJsonArray()) {
                    return null;
                }
                JsonArray jsonArray = arr.getAsJsonArray();
                // Support negative indices (e.g., -1 means last element)
                if (arrayIndex < 0) {
                    arrayIndex = jsonArray.size() + arrayIndex;
                }
                if (arrayIndex < 0 || arrayIndex >= jsonArray.size()) {
                    return null;
                }
                current = jsonArray.get(arrayIndex);
            } else {
                if (!current.isJsonObject()) {
                    return null;
                }
                current = current.getAsJsonObject().get(part);
            }
        }
        return current;
    }

    /**
     * Returns the string value of a property from a JsonObject, or a default value.
     *
     * @param obj          the JSON object
     * @param key          the property name
     * @param defaultValue value to return if property is absent or null
     * @return the string value or default
     */
    private String getStringOr(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            JsonElement el = obj.get(key);
            if (el.isJsonPrimitive()) {
                return el.getAsString();
            }
            return el.toString();
        }
        return defaultValue;
    }

    /**
     * Extracts description text from a tool JSON object.
     * Tries common fields: description, desc, summary, info, then function.description.
     *
     * @param tool the tool JSON object
     * @return the description text, or null if not found
     */
    private String extractToolDescription(JsonObject tool) {
        // Try common description fields
        String[] fields = {"description", "desc", "summary", "info"};
        for (String field : fields) {
            if (tool.has(field)) {
                String desc = getStringOr(tool, field, "");
                if (!desc.isEmpty()) {
                    return desc;
                }
            }
        }

        String name = getStringOr(tool, "name", "");

        // Check for OpenAI function format: { "function": { "description": "..." } }
        if (tool.has("function") && tool.get("function").isJsonObject()) {
            JsonObject function = tool.getAsJsonObject("function");
            String desc = getStringOr(function, "description", "");
            if (!desc.isEmpty()) {
                if (!name.isEmpty()) {
                    return name + ": " + desc;
                }
                return desc;
            }
        }

        // Fallback to name
        return name.isEmpty() ? null : name;
    }

    /**
     * Generates a cache key that includes the embedding provider info to avoid
     * stale/incompatible embeddings if the provider changes.
     *
     * @param description the tool description text
     * @return SHA-256 hash of the cache key
     */
    private String getCacheKey(String description) {
        // Include a fixed prefix to differentiate cache keys
        String combinedKey = "semantic-tool-filtering:" + description;
        return EmbeddingCache.hashDescription(combinedKey);
    }

    /**
     * Calculates cosine similarity between two embedding vectors.
     *
     * @param a first embedding vector
     * @param b second embedding vector
     * @return cosine similarity score between -1 and 1
     */
    private double cosineSimilarity(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0.0;
        }
        if (a.length != b.length) {
            logger.warn("Embedding dimensions do not match: " + a.length + " vs " + b.length);
            return 0.0;
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Filters tools based on the configured selection mode.
     *
     * @param toolsWithScores tools with their similarity scores
     * @return filtered list of tool JSON objects
     */
    private List<JsonObject> filterTools(List<ToolWithScore> toolsWithScores) {
        // Sort by score descending
        toolsWithScores.sort(Comparator.comparingDouble(ToolWithScore::getScore).reversed());

        List<JsonObject> filtered = new ArrayList<>();

        if (SemanticToolFilteringConstants.SELECTION_MODE_TOP_K.equals(selectionMode)) {
            int k = Math.min(limit, toolsWithScores.size());
            for (int i = 0; i < k; i++) {
                filtered.add(toolsWithScores.get(i).getTool());
            }
        } else if (SemanticToolFilteringConstants.SELECTION_MODE_THRESHOLD.equals(selectionMode)) {
            for (ToolWithScore item : toolsWithScores) {
                if (item.getScore() >= threshold) {
                    filtered.add(item.getTool());
                }
            }
        }

        return filtered;
    }

    /**
     * Updates the tools array at the specified JSONPath in the request body.
     *
     * @param requestBody  the request body JSON object
     * @param path         the JSONPath (e.g., "$.tools", "$.data[0].tools")
     * @param filteredTools the filtered tools array
     */
    private void updateToolsInRequestBody(JsonObject requestBody, String path, JsonArray filteredTools) {
        // Remove leading "$."
        String cleanPath = path.startsWith("$.") ? path.substring(2) : path;
        String[] parts = cleanPath.split("\\.");

        JsonObject current = requestBody;

        for (int idx = 0; idx < parts.length; idx++) {
            String part = parts[idx];

            // Check for array index, e.g., "results[0]"
            int openIdx = part.indexOf('[');
            if (openIdx != -1 && part.endsWith("]")) {
                String field = part.substring(0, openIdx);
                int arrayIndex = Integer.parseInt(part.substring(openIdx + 1, part.length() - 1));

                if (idx == parts.length - 1) {
                    // Last part - set value at array index
                    JsonElement arrElement = current.get(field);
                    JsonArray arr;
                    if (arrElement == null || !arrElement.isJsonArray()) {
                        arr = new JsonArray();
                        for (int j = 0; j <= Math.max(arrayIndex, 0); j++) {
                            arr.add(JsonNull.INSTANCE);
                        }
                    } else {
                        arr = arrElement.getAsJsonArray();
                    }
                    // Support negative indices (e.g., -1 means last element)
                    int resolvedIndex = arrayIndex < 0 ? arr.size() + arrayIndex : arrayIndex;
                    if (resolvedIndex < 0) {
                        return; // Out of bounds
                    }
                    while (arr.size() <= resolvedIndex) {
                        arr.add(JsonNull.INSTANCE);
                    }
                    arr.set(resolvedIndex, filteredTools);
                    current.add(field, arr);
                } else {
                    // Not last - descend into array element
                    JsonElement arrElement = current.get(field);
                    if (arrElement == null || !arrElement.isJsonArray()) {
                        return; // Path doesn't exist
                    }
                    JsonArray arr = arrElement.getAsJsonArray();
                    // Support negative indices (e.g., -1 means last element)
                    int resolvedIndex = arrayIndex < 0 ? arr.size() + arrayIndex : arrayIndex;
                    if (resolvedIndex < 0 || resolvedIndex >= arr.size()) {
                        return; // Path doesn't exist
                    }
                    current = arr.get(resolvedIndex).getAsJsonObject();
                }
            } else {
                if (idx == parts.length - 1) {
                    // Last part - set the filtered tools
                    current.add(part, filteredTools);
                } else {
                    // Descend into nested object
                    JsonElement next = current.get(part);
                    if (next == null || !next.isJsonObject()) {
                        return; // Path doesn't exist
                    }
                    current = next.getAsJsonObject();
                }
            }
        }
    }

    /**
     * Extracts user query from text content using {@code <userq>} tags.
     *
     * @param content the text content
     * @return the user query, or null if not found
     */
    private String extractUserQueryFromText(String content) {
        String startTag = SemanticToolFilteringConstants.USER_QUERY_START_TAG;
        String endTag = SemanticToolFilteringConstants.USER_QUERY_END_TAG;

        int startIdx = content.indexOf(startTag);
        if (startIdx == -1) {
            return null;
        }

        int contentStart = startIdx + startTag.length();
        int endIdx = content.indexOf(endTag, contentStart);
        if (endIdx == -1) {
            return null;
        }

        return content.substring(contentStart, endIdx).trim();
    }

    /**
     * Extracts tools from text content using {@code <toolname>} and {@code <tooldescription>} tags.
     *
     * @param content the text content
     * @return list of extracted text tools
     */
    private List<TextTool> extractToolsFromText(String content) {
        List<TextTool> tools = new ArrayList<>();

        String nameStartTag = SemanticToolFilteringConstants.TOOL_NAME_START_TAG;
        String nameEndTag = SemanticToolFilteringConstants.TOOL_NAME_END_TAG;
        String descStartTag = SemanticToolFilteringConstants.TOOL_DESC_START_TAG;
        String descEndTag = SemanticToolFilteringConstants.TOOL_DESC_END_TAG;

        int searchStart = 0;
        while (true) {
            // Find tool name
            int nameStartIdx = content.indexOf(nameStartTag, searchStart);
            if (nameStartIdx == -1) {
                break;
            }

            int nameContentStart = nameStartIdx + nameStartTag.length();
            int nameEndIdx = content.indexOf(nameEndTag, nameContentStart);
            if (nameEndIdx == -1) {
                break;
            }

            String toolName = content.substring(nameContentStart, nameEndIdx).trim();

            // Find tool description after the name
            int descSearchStart = nameEndIdx + nameEndTag.length();
            int descStartIdx = content.indexOf(descStartTag, descSearchStart);
            if (descStartIdx == -1) {
                break;
            }

            int descContentStart = descStartIdx + descStartTag.length();
            int descEndIdx = content.indexOf(descEndTag, descContentStart);
            if (descEndIdx == -1) {
                break;
            }

            String toolDesc = content.substring(descContentStart, descEndIdx).trim();

            tools.add(new TextTool(toolName, toolDesc, nameStartIdx, descEndIdx + descEndTag.length()));

            searchStart = descEndIdx + descEndTag.length();
        }

        return tools;
    }

    /**
     * Rebuilds text content keeping only filtered tools.
     * Removes tool definitions that are not in the filtered set.
     *
     * @param originalContent  the original text content
     * @param allTools         all extracted tools
     * @param filteredToolNames set of tool names to keep
     * @return the modified content with only filtered tools
     */
    private String rebuildTextWithFilteredTools(String originalContent,
                                                List<TextTool> allTools,
                                                Set<String> filteredToolNames) {
        if (allTools.isEmpty()) {
            return originalContent;
        }

        // Process from end to start so positions stay valid
        StringBuilder result = new StringBuilder(originalContent);

        // Sort by startPos descending
        List<TextTool> sorted = new ArrayList<>(allTools);
        sorted.sort(Comparator.comparingInt(TextTool::getStartPos).reversed());

        for (TextTool tool : sorted) {
            if (!filteredToolNames.contains(tool.getName())) {
                result.delete(tool.getStartPos(), tool.getEndPos());
            }
        }

        // Clean up excessive blank lines
        return cleanupWhitespace(result.toString());
    }

    /**
     * Strips all text-format tags (userq, toolname, tooldescription) from the content.
     * Called after filtering so the downstream payload is clean plain text.
     *
     * @param content the text content possibly containing tags
     * @return the content with all tags removed
     */
    private String stripAllTags(String content) {
        content = content.replace(SemanticToolFilteringConstants.USER_QUERY_START_TAG, "");
        content = content.replace(SemanticToolFilteringConstants.USER_QUERY_END_TAG, "");
        content = content.replace(SemanticToolFilteringConstants.TOOL_NAME_START_TAG, "");
        content = content.replace(SemanticToolFilteringConstants.TOOL_NAME_END_TAG, "");
        content = content.replace(SemanticToolFilteringConstants.TOOL_DESC_START_TAG, "");
        content = content.replace(SemanticToolFilteringConstants.TOOL_DESC_END_TAG, "");
        return cleanupWhitespace(content);
    }

    /**
     * Removes excessive blank lines (3+ consecutive newlines → 2).
     */
    private String cleanupWhitespace(String content) {
        while (content.contains("\n\n\n")) {
            content = content.replace("\n\n\n", "\n\n");
        }
        return content;
    }

    /**
     * Validates that a JSONPath is a simple dotted path with optional array indices.
     */
    private boolean isValidSimpleJSONPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (!path.startsWith("$.")) {
            return false;
        }
        return SIMPLE_JSON_PATH_PATTERN.matcher(path).matches();
    }

    // ---------- Inner Classes ----------

    /**
     * Represents a JSON tool with its similarity score.
     */
    private static class ToolWithScore {
        private final JsonObject tool;
        private final double score;

        ToolWithScore(JsonObject tool, double score) {
            this.tool = tool;
            this.score = score;
        }

        JsonObject getTool() {
            return tool;
        }

        double getScore() {
            return score;
        }
    }

    /**
     * Represents a text-format tool parsed from tag-based content.
     */
    private static class TextTool {
        private final String name;
        private final String description;
        private final int startPos;
        private final int endPos;

        TextTool(String name, String description, int startPos, int endPos) {
            this.name = name;
            this.description = description;
            this.startPos = startPos;
            this.endPos = endPos;
        }

        String getName() {
            return name;
        }

        String getDescription() {
            return description;
        }

        int getStartPos() {
            return startPos;
        }

        int getEndPos() {
            return endPos;
        }
    }

    /**
     * Represents a text tool with its similarity score.
     */
    private static class TextToolWithScore {
        private final TextTool tool;
        private final double score;

        TextToolWithScore(TextTool tool, double score) {
            this.tool = tool;
            this.score = score;
        }

        TextTool getTool() {
            return tool;
        }

        double getScore() {
            return score;
        }
    }

    // ---------- JavaBean Getters/Setters (called by Synapse via reflection) ----------

    public String getSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(String selectionMode) {
        this.selectionMode = selectionMode;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public String getQueryJSONPath() {
        return queryJSONPath;
    }

    public void setQueryJSONPath(String queryJSONPath) {
        this.queryJSONPath = queryJSONPath;
    }

    public String getToolsJSONPath() {
        return toolsJSONPath;
    }

    public void setToolsJSONPath(String toolsJSONPath) {
        this.toolsJSONPath = toolsJSONPath;
    }

    public boolean isUserQueryIsJson() {
        return userQueryIsJson;
    }

    public void setUserQueryIsJson(boolean userQueryIsJson) {
        this.userQueryIsJson = userQueryIsJson;
    }

    public boolean isToolsIsJson() {
        return toolsIsJson;
    }

    public void setToolsIsJson(boolean toolsIsJson) {
        this.toolsIsJson = toolsIsJson;
    }
}
