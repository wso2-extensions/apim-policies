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

package org.wso2.apim.policies.mediation.ai.model.guardrail;

/**
 * Constants used in the Model Guardrail mediator.
 */
public class ModelGuardrailConstants {

    // Error codes
    public static final String GUARDRAIL_APIM_EXCEPTION_CODE = "900514";
    public static final String APIM_INTERNAL_EXCEPTION_CODE = "900515";
    public static final int GUARDRAIL_ERROR_CODE = 446;

    // Property keys
    public static final String ERROR_TYPE = "ERROR_TYPE";
    public static final String CUSTOM_HTTP_SC = "HTTP_SC";
    public static final String FAULT_SEQUENCE_KEY = "_production_fault_";

    // Guardrail type
    public static final String MODEL_GUARDRAIL = "MODEL_GUARDRAIL";

    // Assessment object keys
    public static final String ASSESSMENT_ACTION = "action";
    public static final String INTERVENING_GUARDRAIL = "interveningGuardrail";
    public static final String DIRECTION = "direction";
    public static final String ASSESSMENT_REASON = "actionReason";
    public static final String ASSESSMENTS = "assessments";
}
