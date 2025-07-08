# Word Count Guardrail Mediator for WSO2 API Manager Universal Gateway

The **Word Count Guardrail** is a custom Synapse mediator for **WSO2 API Manager Universal Gateway**, designed to perform **word count-based validation** on incoming or outgoing JSON payloads. This component acts as a *guardrail* to enforce specific content moderation rules based on configurable minimum and maximum word counts and JSONPath expressions.

---

## Features

- Validate payload content by counting words
- Define **minimum and maximum word count thresholds**
- Target specific fields in JSON payloads using **JSONPath**
- Optionally **invert validation logic** (e.g., allow only content *outside* the specified word range)
- Trigger fault sequences on rule violations
- Include optional **assessment messages** in error responses for better observability

---

## Prerequisites

- Java 11 (JDK)
- Maven 3.6.x or later
- WSO2 API Manager or Synapse-compatible runtime

---

## Building the Project

To compile and package the mediator:

```bash
mvn clean install
```

> ℹ️ This will generate a `.zip` file in the `target/` directory containing the mediator JAR, policy-definition.json and artifact.j2.

## How to Use

Follow these steps to integrate the Word Count Guardrail policy into your WSO2 API Manager instance:

1. **Unzip the Build Artifact**  
   After the build, unzip the artifact generated in the `target/` directory:

   ```bash
   unzip target/org.wso2.apim.policies.mediation.ai.word-count-guardrail-<version>-distribution.zip -d word-count-guardrail
   ```

2. **Copy the Mediator JAR**  
   Place the mediator JAR into your API Manager’s runtime libraries:

   ```bash
   cp word-count-guardrail/org.wso2.apim.policies.mediation.ai.word-count-guardrail-<version>.jar $APIM_HOME/repository/components/lib/
   ```

3. **Register the Policy in Publisher**  
   Use the provided `policy-definition.json` and `artifact.j2` files to define the policy in the Publisher Portal.

   - Place these files in the correct directory structure expected by your deployment process or manually register via REST APIs or UIs.

4. **Apply and Deploy the Policy**
   - Open the **API Publisher**
   - Select your API
   - Go to **Runtime > Request/Response Flow**
   - Click **Add Policy**, select the new **Word Count Guardrail** policy
   - Provide the required configuration (name, min, max, etc.)
   - **Save and Deploy** the API

---

## Example Policy Configuration

1. Create an AI API using Mistral AI.
2. Add the Word Count Guardrail policy to the API with the following configuration:

| Field                           | Example                  |
|---------------------------------|--------------------------|
| `Guardrail Name`                | `Word Limiter`           |
| `Minimum Word Count`            | `2`                      |
| `Maximum Word Count`            | `5`                      |
| `JSON Path`                     | `$.messages[-1].content` |
| `Invert the Guardrail Decision` | `false`                  |
| `Show Guardrail Assessment`     | `true`                   |

3. Save and re-deploy the API.
4. Invoke the API's `chat/completion` endpoint with a prompt that violates the word count, such as having only one word (if min is set to 2):

```json
{
   "messages": [
      {
         "role": "user",
         "content": "This is a short prompt."
      }
   ]
}
```

The following guardrail error response will be returned with http status code `446`:

```json
{
   "code": "900514",
   "type": "WORD_COUNT_GUARDRAIL",
   "message": {
      "interveningGuardrail": "Word Limiter",
      "action": "GUARDRAIL_INTERVENED",
      "actionReason": "Violation of applied word count constraints detected.",
      "direction": "REQUEST"
   }
}
```
---
