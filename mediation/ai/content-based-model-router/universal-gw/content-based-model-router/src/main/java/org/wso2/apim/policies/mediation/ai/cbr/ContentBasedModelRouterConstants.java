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

/**
 * Constants for Content Based Model Router Policy.
 */
public class ContentBasedModelRouterConstants {
    public static final String API_KEY_TYPE = "AM_KEY_TYPE";
    public static final String API_KEY_TYPE_PRODUCTION = "PRODUCTION";
    public static final String TARGET_ENDPOINT = "TARGET_ENDPOINT";
    public static final String DEFAULT_ENDPOINT = "DEFAULT";
    public static final String TARGET_MODEL_ENDPOINT = "TARGET_MODEL_ENDPOINT";
    public static final String TARGET_MODEL_CONFIGS = "TARGET_MODEL_CONFIGS";
    public static final String API_KEY_IDENTIFIER_TYPE_HEADER = "HEADER";
    public static final String API_KEY_IDENTIFIER_TYPE_QUERY_PARAMETER = "QUERY_PARAMETER";
    public static final String OPERATOR_EQUALS = "EQUALS";
}
