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

package org.wso2.apim.policies.mediation.ai.semantic.routing;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * K-Means clustering implementation for Multi-Centroid Routing.
 * Creates multiple sub-centroids per route to handle diverse utterance patterns.
 */
public class KMeansClustering {
    private static final Log logger = LogFactory.getLog(KMeansClustering.class);
    private static final int MAX_ITERATIONS = 100;
    private static final double CONVERGENCE_THRESHOLD = 0.0001;
    
    /**
     * Performs K-Means clustering on embeddings.
     * 
     * @param embeddings The embeddings to cluster
     * @param k Number of clusters (auto-determined if embeddings.length < k)
     * @param seed Random seed for reproducibility
     * @return Array of centroids
     */
    public static double[][] cluster(double[][] embeddings, int k, long seed) {
        if (embeddings == null || embeddings.length == 0) {
            return new double[0][];
        }
        
        // Auto-adjust k if we have fewer embeddings than desired clusters
        int actualK = Math.min(k, embeddings.length);
        
        if (actualK == 1 || embeddings.length == 1) {
            // Only one cluster needed, return mean of all embeddings
            return new double[][] { calculateMean(embeddings) };
        }
        
        int dimensions = embeddings[0].length;
        Random random = new Random(seed);
        
        // Initialize centroids using K-Means++ algorithm for better initial placement
        double[][] centroids = initializeCentroidsKMeansPlusPlus(embeddings, actualK, random);
        
        // Track cluster assignments
        int[] assignments = new int[embeddings.length];
        boolean converged = false;
        int iteration = 0;
        
        while (!converged && iteration < MAX_ITERATIONS) {
            boolean changed = false;
            
            // Assignment step: assign each embedding to nearest centroid
            for (int i = 0; i < embeddings.length; i++) {
                int nearestCentroid = findNearestCentroid(embeddings[i], centroids);
                if (assignments[i] != nearestCentroid) {
                    assignments[i] = nearestCentroid;
                    changed = true;
                }
            }
            
            if (!changed) {
                converged = true;
                break;
            }
            
            // Update step: recalculate centroids
            double[][] newCentroids = new double[actualK][dimensions];
            int[] counts = new int[actualK];
            
            for (int i = 0; i < embeddings.length; i++) {
                int cluster = assignments[i];
                counts[cluster]++;
                for (int d = 0; d < dimensions; d++) {
                    newCentroids[cluster][d] += embeddings[i][d];
                }
            }
            
            // Calculate new centroids (mean of assigned points)
            for (int c = 0; c < actualK; c++) {
                if (counts[c] > 0) {
                    for (int d = 0; d < dimensions; d++) {
                        newCentroids[c][d] /= counts[c];
                    }
                } else {
                    // Empty cluster - reinitialize with random embedding
                    newCentroids[c] = embeddings[random.nextInt(embeddings.length)].clone();
                }
            }
            
            // Check convergence
            double maxShift = 0.0;
            for (int c = 0; c < actualK; c++) {
                double shift = euclideanDistance(centroids[c], newCentroids[c]);
                maxShift = Math.max(maxShift, shift);
            }
            
            centroids = newCentroids;
            
            if (maxShift < CONVERGENCE_THRESHOLD) {
                converged = true;
            }
            
            iteration++;
        }
        
        if (logger.isDebugEnabled()) {
            logger.debug("K-Means converged in " + iteration + " iterations with " + actualK + " clusters");
        }
        
        return centroids;
    }
    
    /**
     * Initialize centroids using K-Means++ algorithm for better initial placement.
     */
    private static double[][] initializeCentroidsKMeansPlusPlus(double[][] embeddings, int k, Random random) {
        int n = embeddings.length;
        int dimensions = embeddings[0].length;
        double[][] centroids = new double[k][dimensions];
        
        // Choose first centroid randomly
        int firstIdx = random.nextInt(n);
        centroids[0] = embeddings[firstIdx].clone();
        
        // Choose remaining centroids with probability proportional to distance from nearest existing centroid
        double[] minDistances = new double[n];
        for (int c = 1; c < k; c++) {
            double sumDistances = 0.0;
            
            // Calculate minimum distance to existing centroids for each embedding
            for (int i = 0; i < n; i++) {
                double minDist = Double.MAX_VALUE;
                for (int j = 0; j < c; j++) {
                    double dist = euclideanDistance(embeddings[i], centroids[j]);
                    minDist = Math.min(minDist, dist);
                }
                minDistances[i] = minDist * minDist; // Square for weighted probability
                sumDistances += minDistances[i];
            }
            
            // Select next centroid with weighted probability
            double target = random.nextDouble() * sumDistances;
            double cumulative = 0.0;
            int selectedIdx = 0;
            
            for (int i = 0; i < n; i++) {
                cumulative += minDistances[i];
                if (cumulative >= target) {
                    selectedIdx = i;
                    break;
                }
            }
            
            centroids[c] = embeddings[selectedIdx].clone();
        }
        
        return centroids;
    }
    
    /**
     * Determines optimal number of clusters using elbow method.
     * For utterances, we use a heuristic: sqrt(n/2) with min=2, max=5
     */
    public static int determineOptimalK(int numEmbeddings) {
        if (numEmbeddings <= 2) {
            return 1;
        } else if (numEmbeddings <= 4) {
            return 2;
        } else {
            // Use heuristic: sqrt(n/2) bounded between 2 and 5
            int k = (int) Math.ceil(Math.sqrt(numEmbeddings / 2.0));
            return Math.max(2, Math.min(5, k));
        }
    }
    
    /**
     * Calculate mean of embeddings.
     */
    private static double[] calculateMean(double[][] embeddings) {
        int dimensions = embeddings[0].length;
        double[] mean = new double[dimensions];
        
        for (double[] embedding : embeddings) {
            for (int d = 0; d < dimensions; d++) {
                mean[d] += embedding[d];
            }
        }
        
        for (int d = 0; d < dimensions; d++) {
            mean[d] /= embeddings.length;
        }
        
        return mean;
    }
    
    /**
     * Find nearest centroid to an embedding using cosine similarity.
     */
    private static int findNearestCentroid(double[] embedding, double[][] centroids) {
        int nearest = 0;
        double maxSimilarity = -1.0;
        
        for (int i = 0; i < centroids.length; i++) {
            double similarity = cosineSimilarity(embedding, centroids[i]);
            if (similarity > maxSimilarity) {
                maxSimilarity = similarity;
                nearest = i;
            }
        }
        
        return nearest;
    }
    
    /**
     * Calculate cosine similarity between two vectors.
     */
    private static double cosineSimilarity(double[] a, double[] b) {
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        
        normA = Math.sqrt(normA);
        normB = Math.sqrt(normB);
        
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        
        return dot / (normA * normB);
    }
    
    /**
     * Calculate Euclidean distance between two vectors.
     */
    private static double euclideanDistance(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }
}
