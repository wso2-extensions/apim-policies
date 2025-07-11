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

package org.wso2.apim.policies.mediation.ai.aws.bedrock.guardrail;

public class AWSBedrockConstants {
    public static final String GUARDRAIL_PROVIDER_TYPE = "awsbedrock-guardrails";
    public static final String BEDROCK_RUNTIME = "bedrock-runtime";
    public static final String BEDROCK_HOST = "amazonaws.com";
    public static final String GUARDRAIL_SERVICE = "guardrail";
    public static final String BEDROCK_SERVICE = "bedrock";
    public static final String GUARDRAIL_VERSION = "version";
    public static final String GUARDRAIL_CALL = "apply";
    public static final String GUARDRAIL_PROTOCOL = "https";
    public static final String BEDROCK_GUARDRAIL_REQUEST_SOURCE = "INPUT";
    public static final String BEDROCK_GUARDRAIL_RESPONSE_SOURCE = "OUTPUT";
    public static final String BEDROCK_GUARDRAIL_SOURCE_HEADER = "source";
    public static final String BEDROCK_GUARDRAIL_TEXT = "text";
    public static final String BEDROCK_GUARDRAIL_OUTPUT = "output";
    public static final String BEDROCK_GUARDRAIL_CONTENT = "content";
    public static final String BEDROCK_GUARDRAIL_SIP = "sensitiveInformationPolicy";
    public static final String BEDROCK_GUARDRAIL_PII_ENTITIES = "piiEntities";
    public static final String BEDROCK_GUARDRAIL_PII_REGEXES = "regexes";
    public static final String BEDROCK_GUARDRAIL_PII_ACTION = "action";
    public static final String BEDROCK_GUARDRAIL_PII_MATCH = "match";
    public static final String BEDROCK_GUARDRAIL_PII_TYPE = "type";
    public static final String BEDROCK_GUARDRAIL_PII_NAME = "name";
    public static final String GUARDRAIL_PROVIDER_AWSBEDROCK_CALLOUT_SERVICE = "service";
    public static final String GUARDRAIL_PROVIDER_AWSBEDROCK_CALLOUT_SERVICE_REGION = "guardrail_region";
    public static final String GUARDRAIL_PROVIDER_AWSBEDROCK_CALLOUT_HOST = "request_host";
    public static final String GUARDRAIL_PROVIDER_AWSBEDROCK_CALLOUT_URI = "request_uri";
    public static final String GUARDRAIL_PROVIDER_AWSBEDROCK_CALLOUT_URL = "request_url";
    public static final String GUARDRAIL_PROVIDER_AWSBEDROCK_CALLOUT_PAYLOAD = "request_payload";
    public static final String MESSAGE_CONTEXT_PII_ENTITIES_PROPERTY_KEY = "PII_ENTITIES";
    public static final String AWS_BEDROCK_PASSED = "NONE";
    public static final String AWS_BEDROCK_INTERVENED = "GUARDRAIL_INTERVENED";
    public static final String AWS_BEDROCK_INTERVENED_AND_BLOCKED = "Guardrail blocked.";
    public static final String AWS_BEDROCK_INTERVENED_AND_MASKED = "Guardrail masked.";
    public static final String AWS_BEDROCK_INTERVENED_AND_ANONYMIZED = "ANONYMIZED";
    public static final String AWS_BEDROCK_INVOCATION_METRICS = "invocationMetrics";

    public static final int GUARDRAIL_ERROR_CODE = 446;
    public static final String ERROR_TYPE = "ERROR_TYPE";
    public static final String CUSTOM_HTTP_SC = "CUSTOM_HTTP_SC";
    public static final String FAULT_SEQUENCE_KEY = "guardrail_fault";
    public static final String TEXT_CLEAN_REGEX = "^\"|\"$";

    public static final String ASSESSMENT_ACTION = "action";
    public static final String ASSESSMENT_REASON = "actionReason";
    public static final String INTERVENING_GUARDRAIL = "interveningGuardrail";
    public static final String DIRECTION = "direction";
    public static final String ASSESSMENTS = "assessments";

    public static final int GUARDRAIL_APIM_EXCEPTION_CODE = 900514;
    public static final int APIM_INTERNAL_EXCEPTION_CODE = 900967;
    public static final String AWS_BEDROCK_GUARDRAIL = "AWS_BEDROCK_GUARDRAIL";
}
