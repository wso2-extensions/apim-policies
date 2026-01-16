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

package org.wso2.apim.policies.mediation.ai.semantic.routing;

/**
 * Constants for Semantic Routing policy.
 */
public class SemanticRoutingConstants {

    public static final String EMPTY_RESULT = "";

    public static final int ROUTING_ERROR_CODE = 447;
    public static final int ROUTING_APIM_EXCEPTION_CODE = 900515;
    public static final int APIM_INTERNAL_EXCEPTION_CODE = 900967;
    public static final String ERROR_TYPE = "ERROR_TYPE";
    public static final String CUSTOM_HTTP_SC = "CUSTOM_HTTP_SC";
    public static final String FAULT_SEQUENCE_KEY = "ai_routing_fault";
    public static final String SEMANTIC_ROUTING = "SEMANTIC_ROUTING";

    public static final String ERROR_CONFIG_PARSE_FAILED = "Failed to parse semantic routing configuration";
    public static final String ERROR_EMBEDDING_PROVIDER_UNAVAILABLE = "Embedding provider is not available";
    public static final String ERROR_CONTENT_PATH_NOT_CONFIGURED = "Content path is not configured or empty";
    public static final String ERROR_EMPTY_PAYLOAD = "Request payload is empty";
    public static final String ERROR_JSON_PATH_PARSE = "Error parsing JSON path";
    public static final String ERROR_EMBEDDING_COMPUTATION = "Failed to compute embeddings";
    public static final String ERROR_NO_ROUTE_FOUND = "No matching route found and no default configured";


}
