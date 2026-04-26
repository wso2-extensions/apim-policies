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

package org.wso2.apim.policies.mediation.ai.regex.guardrail;

import org.apache.synapse.Mediator;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@PrepareForTest({RegexGuardrail.class})
@RunWith(PowerMockRunner.class)
public class RegexGuardrailTest {

    private Axis2MessageContext messageContext;
    private org.apache.axis2.context.MessageContext axis2MsgContext;
    private RegexGuardrail guardrail;
    private Mediator faultMediator;

    @Before
    public void init() throws Exception {
        messageContext = Mockito.mock(Axis2MessageContext.class);
        axis2MsgContext = Mockito.mock(org.apache.axis2.context.MessageContext.class);
        faultMediator = Mockito.mock(Mediator.class);

        Mockito.when(messageContext.getAxis2MessageContext()).thenReturn(axis2MsgContext);
        Mockito.when(messageContext.getFaultSequence()).thenReturn(faultMediator);
        Mockito.when(faultMediator.mediate(Mockito.any(MessageContext.class))).thenReturn(true);

        guardrail = PowerMockito.spy(new RegexGuardrail());
        guardrail.setRegex("(?i)(forbidden|restricted|blocked)");
        guardrail.setJsonPath("$['choices']");
        guardrail.setInvert(true);
        guardrail.setName("TestRegexGuardrail");
        guardrail.setShowAssessment(true);
    }

    private void mockExtractJsonContent(String payload) throws Exception {
        PowerMockito.doReturn(payload).when(guardrail, "extractJsonContent", Mockito.any(MessageContext.class));
    }

    @Test
    public void testSkipsValidationFor401Response() {
        Mockito.when(messageContext.isResponse()).thenReturn(true);
        Mockito.when(axis2MsgContext.getProperty("HTTP_SC")).thenReturn(401);

        Assert.assertTrue("Should pass through 401 response without validation",
                guardrail.mediate(messageContext));
    }

    @Test
    public void testSkipsValidationFor403Response() {
        Mockito.when(messageContext.isResponse()).thenReturn(true);
        Mockito.when(axis2MsgContext.getProperty("HTTP_SC")).thenReturn(403);

        Assert.assertTrue("Should pass through 403 response without validation",
                guardrail.mediate(messageContext));
    }

    @Test
    public void testSkipsValidationFor500Response() {
        Mockito.when(messageContext.isResponse()).thenReturn(true);
        Mockito.when(axis2MsgContext.getProperty("HTTP_SC")).thenReturn(500);

        Assert.assertTrue("Should pass through 500 response without validation",
                guardrail.mediate(messageContext));
    }

    @Test
    public void testSkipsValidationFor502Response() {
        Mockito.when(messageContext.isResponse()).thenReturn(true);
        Mockito.when(axis2MsgContext.getProperty("HTTP_SC")).thenReturn(502);

        Assert.assertTrue("Should pass through 502 response without validation",
                guardrail.mediate(messageContext));
    }

    @Test
    public void testSkipsValidationFor503Response() {
        Mockito.when(messageContext.isResponse()).thenReturn(true);
        Mockito.when(axis2MsgContext.getProperty("HTTP_SC")).thenReturn(503);

        Assert.assertTrue("Should pass through 503 response without validation",
                guardrail.mediate(messageContext));
    }

    @Test
    public void testSkipsValidationFor400Response() {
        Mockito.when(messageContext.isResponse()).thenReturn(true);
        Mockito.when(axis2MsgContext.getProperty("HTTP_SC")).thenReturn(400);

        Assert.assertTrue("Should pass through 400 response without validation",
                guardrail.mediate(messageContext));
    }

    @Test
    public void testSkipsValidationFor404Response() {
        Mockito.when(messageContext.isResponse()).thenReturn(true);
        Mockito.when(axis2MsgContext.getProperty("HTTP_SC")).thenReturn(404);

        Assert.assertTrue("Should pass through 404 response without validation",
                guardrail.mediate(messageContext));
    }

    @Test
    public void testSkipsValidationForBoundary199Response() {
        Mockito.when(messageContext.isResponse()).thenReturn(true);
        Mockito.when(axis2MsgContext.getProperty("HTTP_SC")).thenReturn(199);

        Assert.assertTrue("Should skip validation for 199 response (below 2xx range)",
                guardrail.mediate(messageContext));
    }

    @Test
    public void testSkipsValidationForBoundary300Response() {
        Mockito.when(messageContext.isResponse()).thenReturn(true);
        Mockito.when(axis2MsgContext.getProperty("HTTP_SC")).thenReturn(300);

        Assert.assertTrue("Should skip validation for 300 response (above 2xx range)",
                guardrail.mediate(messageContext));
    }

    @Test
    public void testProcessesValidationFor200Response() throws Exception {
        Mockito.when(messageContext.isResponse()).thenReturn(true);
        Mockito.when(axis2MsgContext.getProperty("HTTP_SC")).thenReturn(200);

        String payload = "{\"choices\":[{\"message\":{\"content\":\"Hello world\"}}]}";
        mockExtractJsonContent(payload);

        Assert.assertTrue("Should process and pass 200 response with valid content",
                guardrail.mediate(messageContext));
    }

    @Test
    public void testProcessesValidationFor201Response() throws Exception {
        Mockito.when(messageContext.isResponse()).thenReturn(true);
        Mockito.when(axis2MsgContext.getProperty("HTTP_SC")).thenReturn(201);

        String payload = "{\"choices\":[{\"message\":{\"content\":\"Hello world\"}}]}";
        mockExtractJsonContent(payload);

        Assert.assertTrue("Should process and pass 201 response with valid content",
                guardrail.mediate(messageContext));
    }

    @Test
    public void testValidationFailsFor200WithViolatingContent() throws Exception {
        Mockito.when(messageContext.isResponse()).thenReturn(true);
        Mockito.when(axis2MsgContext.getProperty("HTTP_SC")).thenReturn(200);
        Mockito.when(messageContext.getSequence(RegexGuardrailConstants.FAULT_SEQUENCE_KEY)).thenReturn(faultMediator);

        String payload = "{\"choices\":[{\"message\":{\"content\":\"This is forbidden content\"}}]}";
        mockExtractJsonContent(payload);

        Assert.assertFalse("Should fail validation for 200 response with violating content",
                guardrail.mediate(messageContext));
        Mockito.verify(messageContext).setProperty(
                Mockito.eq(SynapseConstants.ERROR_CODE),
                Mockito.eq(RegexGuardrailConstants.GUARDRAIL_APIM_EXCEPTION_CODE));
    }

    @Test
    public void testRequestMessageStillValidates() throws Exception {
        Mockito.when(messageContext.isResponse()).thenReturn(false);

        String payload = "{\"messages\":[{\"content\":\"Hello world\"}]}";
        mockExtractJsonContent(payload);

        guardrail.setJsonPath("");
        Assert.assertTrue("Request messages should still be validated (no regex match = pass with invert)",
                guardrail.mediate(messageContext));
    }

    @Test
    public void testRequestMessageValidationFailsOnViolation() throws Exception {
        Mockito.when(messageContext.isResponse()).thenReturn(false);
        Mockito.when(messageContext.getSequence(RegexGuardrailConstants.FAULT_SEQUENCE_KEY)).thenReturn(faultMediator);

        String payload = "{\"messages\":[{\"content\":\"This is forbidden\"}]}";
        mockExtractJsonContent(payload);

        guardrail.setJsonPath("");
        Assert.assertFalse("Request with violating content should fail validation",
                guardrail.mediate(messageContext));
    }

    @Test
    public void testNullHttpScProceedsWithValidation() throws Exception {
        Mockito.when(messageContext.isResponse()).thenReturn(true);
        Mockito.when(axis2MsgContext.getProperty("HTTP_SC")).thenReturn(null);

        String payload = "{\"choices\":[{\"message\":{\"content\":\"Hello world\"}}]}";
        mockExtractJsonContent(payload);

        Assert.assertTrue("Should proceed with validation when HTTP_SC is null",
                guardrail.mediate(messageContext));
    }

    @Test
    public void testEmptyPayloadWithInvertTrue() throws Exception {
        Mockito.when(messageContext.isResponse()).thenReturn(true);
        Mockito.when(axis2MsgContext.getProperty("HTTP_SC")).thenReturn(200);
        mockExtractJsonContent("");

        // validatePayload returns false (empty), invert(true) != false => finalResult=true
        Assert.assertTrue("Empty payload with invert=true should pass through",
                guardrail.mediate(messageContext));
    }

    @Test
    public void testNullPayloadWithInvertTrue() throws Exception {
        Mockito.when(messageContext.isResponse()).thenReturn(true);
        Mockito.when(axis2MsgContext.getProperty("HTTP_SC")).thenReturn(200);
        mockExtractJsonContent(null);

        // validatePayload returns false (null), invert(true) != false => finalResult=true
        Assert.assertTrue("Null payload with invert=true should pass through",
                guardrail.mediate(messageContext));
    }
}
