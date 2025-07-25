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

package org.wso2.apim.policies.mediation.ai.prompt.template;

public class PromptTemplateConstants {
    public static final String PROMPT_TEMPLATE_REGEX = "template://[a-zA-Z0-9_-]+\\?[^\\s\"']*";
    public static final String PROMPT_TEMPLATE_NAME = "name";
    public static final String PROMPT_TEMPLATE_PROMPT = "prompt";
    public static final int APIM_INTERNAL_EXCEPTION_CODE = 900967;
}
