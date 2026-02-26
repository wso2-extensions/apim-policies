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

/**
 * Constants for the SemanticToolFiltering mediator.
 * <p>
 * Contains configuration defaults, error codes, selection modes, and other constant values
 * used throughout the semantic tool filtering implementation.
 */
public final class SemanticToolFilteringConstants {

    private SemanticToolFilteringConstants() {
        // Prevent instantiation
    }

    // Selection Modes
    public static final String SELECTION_MODE_TOP_K = "By Rank";
    public static final String SELECTION_MODE_THRESHOLD = "By Threshold";

    // Default values
    public static final int DEFAULT_TOP_K = 5;
    public static final double DEFAULT_THRESHOLD = 0.7;
    public static final String DEFAULT_QUERY_JSON_PATH = "$.messages[-1].content";
    public static final String DEFAULT_TOOLS_JSON_PATH = "$.tools";
    public static final boolean DEFAULT_USER_QUERY_IS_JSON = true;
    public static final boolean DEFAULT_TOOLS_IS_JSON = true;

    // Error handling
    public static final int GUARDRAIL_ERROR_CODE = 446;
    public static final int GUARDRAIL_APIM_EXCEPTION_CODE = 900514;
    public static final int APIM_INTERNAL_EXCEPTION_CODE = 900967;
    public static final String ERROR_TYPE_VALUE = "SEMANTIC_TOOL_FILTERING";
    public static final String ERROR_TYPE = "ERROR_TYPE";
    public static final String CUSTOM_HTTP_SC = "CUSTOM_HTTP_SC";
    public static final String FAULT_SEQUENCE_KEY = "guardrail_fault";

    // JSON clean-up
    public static final String JSON_CLEAN_REGEX = "^\"|\"$";

    // Text format tags
    public static final String USER_QUERY_START_TAG = "<userq>";
    public static final String USER_QUERY_END_TAG = "</userq>";
    public static final String TOOL_NAME_START_TAG = "<toolname>";
    public static final String TOOL_NAME_END_TAG = "</toolname>";
    public static final String TOOL_DESC_START_TAG = "<tooldescription>";
    public static final String TOOL_DESC_END_TAG = "</tooldescription>";

    // Embedding cache defaults
    public static final int DEFAULT_MAX_APIS = 25;
    public static final int DEFAULT_MAX_TOOLS_PER_API = 200;

    // Message context property keys
    public static final String API_UUID = "API_UUID";
}
