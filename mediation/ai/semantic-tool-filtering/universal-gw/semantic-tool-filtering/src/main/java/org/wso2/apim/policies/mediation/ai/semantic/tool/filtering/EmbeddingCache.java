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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe LRU embedding cache for storing tool embeddings per API.
 * <p>
 * This cache provides a two-level hierarchy:
 * <ul>
 *   <li>Level 1: API ID → API Cache (bounded by maxAPIs)</li>
 *   <li>Level 2: Tool description hash → Embedding entry (bounded by maxToolsPerAPI)</li>
 * </ul>
 * <p>
 * Both levels use LRU eviction when capacity is reached. The cache is thread-safe
 * using a {@link ReentrantReadWriteLock}.
 * <p>
 * Ported from Go: embeddingcache.go
 */
public class EmbeddingCache {

    private static final Log logger = LogFactory.getLog(EmbeddingCache.class);

    private static volatile EmbeddingCache instance;
    private static final Object LOCK = new Object();

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Map<String, APICache> cache = new LinkedHashMap<>();
    private int maxAPIs;
    private int maxToolsPerAPI;

    /**
     * Represents a cached embedding entry for a single tool.
     */
    public static class EmbeddingEntry {
        private final String name;
        private final double[] embedding;
        private long lastAccessed;

        public EmbeddingEntry(String name, double[] embedding) {
            this.name = name;
            this.embedding = embedding != null ? embedding.clone() : new double[0];
            this.lastAccessed = System.currentTimeMillis();
        }

        public String getName() {
            return name;
        }

        public double[] getEmbedding() {
            return embedding.clone();
        }

        public long getLastAccessed() {
            return lastAccessed;
        }

        void touch() {
            this.lastAccessed = System.currentTimeMillis();
        }
    }

    /**
     * Represents the cache for a single API, containing tool embeddings.
     */
    private static class APICache {
        private final Map<String, EmbeddingEntry> tools = new LinkedHashMap<>();
        private long lastAccessed;

        APICache() {
            this.lastAccessed = System.currentTimeMillis();
        }

        void touch() {
            this.lastAccessed = System.currentTimeMillis();
        }
    }

    /**
     * Represents a tool entry to be bulk-added to the cache.
     */
    public static class ToolEntry {
        private final String hashKey;
        private final String name;
        private final double[] embedding;

        public ToolEntry(String hashKey, String name, double[] embedding) {
            this.hashKey = hashKey;
            this.name = name;
            this.embedding = embedding;
        }

        public String getHashKey() {
            return hashKey;
        }

        public String getName() {
            return name;
        }

        public double[] getEmbedding() {
            return embedding;
        }
    }

    /**
     * Result of a bulk add operation.
     */
    public static class BulkAddResult {
        private final List<String> added = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();
        private final List<String> cached = new ArrayList<>();

        public List<String> getAdded() {
            return added;
        }

        public List<String> getSkipped() {
            return skipped;
        }

        public List<String> getCached() {
            return cached;
        }
    }

    private EmbeddingCache() {
        this.maxAPIs = SemanticToolFilteringConstants.DEFAULT_MAX_APIS;
        this.maxToolsPerAPI = SemanticToolFilteringConstants.DEFAULT_MAX_TOOLS_PER_API;
    }

    /**
     * Returns the global singleton instance of the embedding cache.
     *
     * @return the singleton instance
     */
    public static EmbeddingCache getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new EmbeddingCache();
                }
            }
        }
        return instance;
    }

    /**
     * Updates the cache limits for APIs and tools per API.
     *
     * @param maxAPIs        maximum number of APIs
     * @param maxToolsPerAPI maximum number of tools per API
     */
    public void setCacheLimits(int maxAPIs, int maxToolsPerAPI) {
        rwLock.writeLock().lock();
        try {
            if (maxAPIs > 0) {
                this.maxAPIs = maxAPIs;
            }
            if (maxToolsPerAPI > 0) {
                this.maxToolsPerAPI = maxToolsPerAPI;
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Returns the current cache limits as [maxAPIs, maxToolsPerAPI].
     *
     * @return an int array with [maxAPIs, maxToolsPerAPI]
     */
    public int[] getCacheLimits() {
        rwLock.readLock().lock();
        try {
            return new int[]{maxAPIs, maxToolsPerAPI};
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Creates a new empty cache for the given API ID if one doesn't already exist.
     * Evicts the LRU API if the cache is at capacity.
     *
     * @param apiId the API identifier
     */
    public void addAPICache(String apiId) {
        rwLock.writeLock().lock();
        try {
            if (!cache.containsKey(apiId)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Adding new API cache: apiId=" + apiId
                            + ", currentSize=" + cache.size() + ", maxAPIs=" + maxAPIs);
                }
                evictLRUAPIIfNeeded();
                cache.put(apiId, new APICache());
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Retrieves an embedding entry for a specific API and hash key.
     * Updates access timestamps on hit.
     *
     * @param apiId   the API identifier
     * @param hashKey the SHA-256 hash of the tool description
     * @return the embedding entry, or null if not found
     */
    public EmbeddingEntry getEntry(String apiId, String hashKey) {
        rwLock.writeLock().lock();
        try {
            APICache apiCache = cache.get(apiId);
            if (apiCache != null) {
                EmbeddingEntry entry = apiCache.tools.get(hashKey);
                if (entry != null) {
                    apiCache.touch();
                    entry.touch();
                    if (logger.isDebugEnabled()) {
                        logger.debug("Cache hit: apiId=" + apiId + ", toolName=" + entry.getName());
                    }
                    return new EmbeddingEntry(entry.getName(), entry.getEmbedding());
                }
            }
            return null;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Returns the number of cached tools for a specific API.
     *
     * @param apiId the API identifier
     * @return the number of cached tools, or 0 if API not found
     */
    public int getAPICacheSize(String apiId) {
        rwLock.readLock().lock();
        try {
            APICache apiCache = cache.get(apiId);
            if (apiCache != null) {
                return apiCache.tools.size();
            }
            return 0;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Adds multiple tools to the cache for a specific API in an optimized way.
     * First checks which tools are already cached, then only adds new tools up to the cache limit.
     * This prevents wasteful evictions.
     *
     * @param apiId the API identifier
     * @param tools the list of tool entries to add
     * @return the result indicating which tools were added, skipped, or already cached
     */
    public BulkAddResult bulkAddTools(String apiId, List<ToolEntry> tools) {
        rwLock.writeLock().lock();
        try {
            BulkAddResult result = new BulkAddResult();

            if (tools == null || tools.isEmpty()) {
                return result;
            }

            if (logger.isDebugEnabled()) {
                logger.debug("BulkAddTools: apiId=" + apiId + ", toolCount=" + tools.size()
                        + ", maxToolsPerAPI=" + maxToolsPerAPI);
            }

            // Ensure API cache exists
            if (!cache.containsKey(apiId)) {
                evictLRUAPIIfNeeded();
                cache.put(apiId, new APICache());
            }

            APICache apiCache = cache.get(apiId);
            apiCache.touch();

            // Separate into already-cached and new tools
            List<ToolEntry> newTools = new ArrayList<>();

            for (ToolEntry tool : tools) {
                EmbeddingEntry existing = apiCache.tools.get(tool.getHashKey());
                if (existing != null) {
                    // Already cached - update timestamp
                    existing.touch();
                    result.getCached().add(tool.getName());
                } else {
                    // Remove any existing entry with the same name but different hash
                    String keyToRemove = null;
                    for (Map.Entry<String, EmbeddingEntry> entry : apiCache.tools.entrySet()) {
                        if (entry.getValue().getName().equals(tool.getName())) {
                            keyToRemove = entry.getKey();
                            break;
                        }
                    }
                    if (keyToRemove != null) {
                        apiCache.tools.remove(keyToRemove);
                    }
                    newTools.add(tool);
                }
            }

            // Calculate available slots
            int availableSlots = maxToolsPerAPI - apiCache.tools.size();
            if (availableSlots < 0) {
                availableSlots = 0;
            }

            // Add new tools that fit
            int toolsToAddCount = Math.min(newTools.size(), availableSlots);

            for (int i = 0; i < toolsToAddCount; i++) {
                ToolEntry tool = newTools.get(i);
                apiCache.tools.put(tool.getHashKey(), new EmbeddingEntry(tool.getName(), tool.getEmbedding()));
                result.getAdded().add(tool.getName());
            }

            // Skip tools that don't fit
            for (int i = toolsToAddCount; i < newTools.size(); i++) {
                result.getSkipped().add(newTools.get(i).getName());
                if (logger.isDebugEnabled()) {
                    logger.debug("BulkAddTools: skipping tool due to cache limit: " + newTools.get(i).getName());
                }
            }

            if (logger.isDebugEnabled()) {
                logger.debug("BulkAddTools completed: apiId=" + apiId
                        + ", added=" + result.getAdded().size()
                        + ", skipped=" + result.getSkipped().size()
                        + ", cached=" + result.getCached().size()
                        + ", totalInCache=" + apiCache.tools.size());
            }

            return result;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Computes SHA-256 hash of the given description string.
     *
     * @param description the text to hash
     * @return hex-encoded SHA-256 hash
     */
    public static String hashDescription(String description) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(description.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Evicts the least recently used API from the cache if at capacity.
     * Must be called with write lock held.
     */
    private void evictLRUAPIIfNeeded() {
        if (cache.size() >= maxAPIs) {
            String lruApiId = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, APICache> entry : cache.entrySet()) {
                if (entry.getValue().lastAccessed < oldestTime) {
                    oldestTime = entry.getValue().lastAccessed;
                    lruApiId = entry.getKey();
                }
            }
            if (lruApiId != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Evicting LRU API: " + lruApiId);
                }
                cache.remove(lruApiId);
            }
        }
    }
}
