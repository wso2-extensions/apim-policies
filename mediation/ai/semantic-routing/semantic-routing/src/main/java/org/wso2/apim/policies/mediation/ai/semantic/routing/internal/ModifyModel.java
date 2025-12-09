package org.wso2.apim.policies.mediation.ai.semantic.routing;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import org.apache.axis2.AxisFault;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.apache.synapse.transport.passthru.PassThroughConstants;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.gateway.handlers.streaming.sse.utils.JsonUtil;

import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Utility class for modifying model in requests for semantic routing.
 * Handles both JSON payload and URL path modifications for different LLM providers.
 */
public class ModifyModel {

    private static final Log logger = LogFactory.getLog(ModifyModel.class);

    /**
     * Modifies the request to use the model from the selected semantic route endpoint.
     * Supports both JSON payload modification and URL path modification for all providers.
     *
     * @param messageContext The Synapse message context
     * @param targetEndpoint The selected model endpoint from semantic routing
     */
    public static void modifyRequestForSemanticRoute(MessageContext messageContext, ModelEndpointDTO targetEndpoint) {
        try {
            org.apache.axis2.context.MessageContext axis2Ctx =
                    ((Axis2MessageContext) messageContext).getAxis2MessageContext();
            
            String targetModel = targetEndpoint.getModel();
            if (logger.isDebugEnabled()) {
                logger.debug("Modifying request for semantic route with model: " + targetModel);
            }
            
            // Check content type to determine if we should try payload modification
            String contentType = (String) axis2Ctx.getProperty(APIMgtGatewayConstants.REST_CONTENT_TYPE);
            boolean isJsonContent = contentType != null && contentType.toLowerCase().contains(MediaType.APPLICATION_JSON);
            
            // First, try to modify JSON payload if it's JSON content
            if (isJsonContent) {
                String jsonPayload = JsonUtil.jsonPayloadToString(axis2Ctx);
                if (jsonPayload != null && !jsonPayload.isEmpty()) {
                    RelayUtils.buildMessage(axis2Ctx);
                    
                    String modifiedPayload = tryModifyJsonPayload(jsonPayload, targetModel);
                    
                    if (modifiedPayload != null) {
                        JsonUtil.getNewJsonPayload(axis2Ctx, modifiedPayload, true, true);
                        if (logger.isDebugEnabled()) {
                            logger.debug("Modified request payload with model: " + targetModel);
                        }
                        return;
                    }
                }
            }
            
            // If payload modification failed or not applicable, try path modification
            modifyRequestPath(messageContext, targetModel);
            
        } catch (Exception e) {
            logger.error("Error modifying request for semantic route", e);
        }
    }

    /**
     * Attempts to modify the JSON payload with the new model.
     * Tries multiple common paths used by different providers.
     *
     * @param jsonPayload The original JSON payload
     * @param targetModel The target model to set
     * @return Modified JSON payload or null if modification failed
     */
    private static String tryModifyJsonPayload(String jsonPayload, String targetModel) {
        try {
            DocumentContext jsonContext = JsonPath.parse(jsonPayload);
            
            // Try common model paths used by different providers
            String[] modelPaths = {
                "$.model",                    // OpenAI, Anthropic, most providers
                "$.options.model",            // Alternative path
                "$.modelId",                  // Amazon Bedrock
                "$.model_id",                 // Alternative naming
                "$.config.model",             // Configuration-based
                "$.parameters.model"          // Parameter-based
            };
            
            for (String path : modelPaths) {
                try {
                    jsonContext.read(path);
                    jsonContext.set(path, targetModel);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Successfully modified model at path: " + path);
                    }
                    return jsonContext.jsonString();
                } catch (PathNotFoundException e) {
                    // Path doesn't exist, try next one
                    continue;
                }
            }
            
            // If no existing path found, try to add model to root if object exists
            try {
                Object root = jsonContext.read("$");
                if (root instanceof java.util.Map) {
                    jsonContext.put("$", "model", targetModel);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Added model field to root JSON object");
                    }
                    return jsonContext.jsonString();
                }
            } catch (Exception e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Could not add model to root: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Error modifying JSON payload: " + e.getMessage());
            }
        }
        
        return null;
    }

    /**
     * Modifies the request path to include the target model.
     * Handles different URL patterns used by various providers.
     *
     * @param messageContext The Synapse message context
     * @param targetModel The target model to set
     */
    private static void modifyRequestPath(MessageContext messageContext, String targetModel) {
        try {
            org.apache.axis2.context.MessageContext axis2Ctx =
                    ((Axis2MessageContext) messageContext).getAxis2MessageContext();
            
            String requestPath = (String) axis2Ctx.getProperty(NhttpConstants.REST_URL_POSTFIX);
            if (requestPath == null || requestPath.isEmpty()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("No request path to modify");
                }
                return;
            }
            
            URI uri = URI.create(requestPath);
            String rawPath = uri.getRawPath();
            String rawQuery = uri.getRawQuery();
            
            // Encode the model name properly for URL path segments
            String encodedModel = encodePathSegmentRFC3986(targetModel);
            
            String modifiedPath = rawPath;
            
            // Handle multiple path patterns for different providers:
            
            // 1. OpenAI/Mistral style: /v1/models/MODEL_NAME or /models/MODEL_NAME
            if (rawPath.contains("/models/")) {
                modifiedPath = rawPath.replaceAll("/models/[^/]+", 
                        "/models/" + java.util.regex.Matcher.quoteReplacement(encodedModel));
            }
            // 2. Amazon Bedrock style: /model/MODEL_NAME/action
            else if (rawPath.contains("/model/")) {
                modifiedPath = rawPath.replaceAll("/model/[^/]+", 
                        "/model/" + java.util.regex.Matcher.quoteReplacement(encodedModel));
            }
            // 3. Azure OpenAI style: /deployments/MODEL_NAME
            else if (rawPath.contains("/deployments/")) {
                modifiedPath = rawPath.replaceAll("/deployments/[^/]+", 
                        "/deployments/" + java.util.regex.Matcher.quoteReplacement(encodedModel));
            }
            // 4. Google Vertex AI / Gemini style: /publishers/google/models/MODEL_NAME or /models/MODEL_NAME:action
            else if (rawPath.contains("/publishers/")) {
                // Handle: /publishers/google/models/gemini-pro:generateContent
                modifiedPath = rawPath.replaceAll("/models/[^/:]+", 
                        "/models/" + java.util.regex.Matcher.quoteReplacement(targetModel));
            }
            // 5. Gemini API style: /v1beta/models/MODEL_NAME:action or /v1/models/MODEL_NAME:action
            else if (rawPath.matches(".*/(v1|v1beta)/models/[^/:]+:.*")) {
                // Extract the action part (e.g., :generateContent, :streamGenerateContent)
                String actionPart = "";
                int colonIndex = rawPath.indexOf(':', rawPath.lastIndexOf("/models/"));
                if (colonIndex > 0) {
                    actionPart = rawPath.substring(colonIndex);
                }
                // Replace model keeping the action
                modifiedPath = rawPath.replaceAll("/(v1|v1beta)/models/[^/:]+", 
                        "/$1/models/" + java.util.regex.Matcher.quoteReplacement(targetModel));
            }
            // 6. Anthropic style: might use query parameter or header instead of path
            // 7. Cohere style: /v1/generate or /v1/chat with model in payload
            // 8. Hugging Face style: /models/MODEL_NAME/generate
            else if (rawPath.contains("/models/") && rawPath.contains("/generate")) {
                modifiedPath = rawPath.replaceAll("/models/[^/]+/generate", 
                        "/models/" + java.util.regex.Matcher.quoteReplacement(encodedModel) + "/generate");
            }
            
            // Build final path with query parameters
            StringBuilder finalPath = new StringBuilder(modifiedPath);
            if (rawQuery != null) {
                finalPath.append("?").append(rawQuery);
            }
            
            // Only update if path was actually modified
            if (!modifiedPath.equals(rawPath)) {
                axis2Ctx.setProperty(NhttpConstants.REST_URL_POSTFIX, finalPath.toString());
                if (logger.isDebugEnabled()) {
                    logger.debug("Modified request path from: " + requestPath + " to: " + finalPath);
                }
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debug("No path modification needed for model: " + targetModel);
                }
            }
            
        } catch (Exception e) {
            logger.error("Error modifying request path", e);
        }
    }

    /**
     * Encodes a path segment according to RFC 3986 standards.
     * This handles special characters in model names like colons, dots, etc.
     *
     * @param segment The path segment to encode
     * @return The encoded path segment
     */
    private static String encodePathSegmentRFC3986(String segment) {
        if (logger.isDebugEnabled()) {
            logger.debug("Encoding path segment: " + segment);
        }
        StringBuilder out = new StringBuilder();
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            if (isUnreserved(c) || isSubDelim(c) || c == ':' || c == '@') {
                out.append(c);
            } else {
                out.append('%');
                String hx = Integer.toHexString(b & 0xFF).toUpperCase();
                if (hx.length() == 1) out.append('0');
                out.append(hx);
            }
        }
        return out.toString();
    }

    /**
     * Checks if the given character is an unreserved character as per RFC 3986.
     *
     * @param c The character to check
     * @return true if unreserved, false otherwise
     */
    private static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }

    /**
     * Checks if the given character is a sub-delimiter as per RFC 3986.
     *
     * @param c The character to check
     * @return true if sub-delimiter, false otherwise
     */
    private static boolean isSubDelim(char c) {
        return c == '!' || c == '$' || c == '&' || c == '\'' || c == '(' || c == ')'
                || c == '*' || c == '+' || c == ',' || c == ';' || c == '=';
    }
}
