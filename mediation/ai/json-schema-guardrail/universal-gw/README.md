# JSON Schema Guardrail Mediator for WSO2 API Manager Universal Gateway

The **JSON Schema Guardrail** is a custom Synapse mediator for **WSO2 API Manager Universal Gateway**, designed to validate JSON payloads against a user-defined **JSON Schema**. This mediator enables API publishers to enforce structural and content compliance dynamically in both request and response flows.

---

## Features

- Validate payload structure and fields using **JSON Schema**
- Target specific segments of a payload using **JSONPath**
- Support for **inverted validation** (fail when schema matches)
- **Guardrail assessment** for better observability on violations
- Works on both **request and response** flows
- Integrates with WSO2 **fault sequences** on failure

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

> ℹ️ This will generate a `.zip` file in the `target/` directory containing the mediator JAR, `policy-definition.json`, and `artifact.j2`.

---

## How to Use

Follow these steps to integrate the JSON Schema Guardrail policy into your WSO2 API Manager instance:

1. **Unzip the Build Artifact**

```bash
unzip target/org.wso2.apim.policies.mediation.ai.json-schema-guardrail-<version>-distribution.zip -d json-schema-guardrail
```

2. **Copy the Mediator JAR**

```bash
cp json-schema-guardrail/org.wso2.apim.policies.mediation.ai.json-schema-guardrail-<version>.jar $APIM_HOME/repository/components/lib/
```

3. **Register the Policy in Publisher**

- Use the `policy-definition.json` and `artifact.j2` files to define the policy in the Publisher Portal.
- Place them in your custom policy deployment directory or register them using the Admin REST API.

4. **Apply and Deploy the Policy**

- Go to **API Publisher**
- Select your API
- Navigate to **Runtime > Request/Response Flow**
- Click **Add Policy** and choose **JSON Schema Guardrail**
- Drag and drop the policy into the response flow
- Configure the policy parameters (name, JSONPath, schema, etc.)
- **Save and Deploy** the API

---

## Example Policy Configuration

1. Create an AI API with Mistral AI.
2. Add the JSON Schema Guardrail policy to the API with the following configuration:

| Field                       | Example                        |
|-----------------------------|--------------------------------|
| `Guardrail Name`            | `Form Validator`               |
| `JSON Path`                 | `$.choices[0].message.content` |
| `Invert the Decision`       | `false`                        |
| `Show Guardrail Assessment` | `false`                        |

`JSON Schema`
```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "properties": {
    "fullName": {
      "type": "string",
      "minLength": 1
    },
    "email": {
      "type": "string",
      "format": "email"
    },
    "phoneNumber": {
      "type": "string",
      "pattern": "^\\+?[0-9\\-\\s]{7,20}$"
    },
    "organization": {
      "type": "string",
      "minLength": 1
    },
    "preferredPlan": {
      "type": "string",
      "enum": ["Free", "Pro", "Enterprise"]
    },
    "referralCode": {
      "type": "string",
      "minLength": 1
    }
  },
  "required": ["fullName", "email"],
  "additionalProperties": false
}
```


3. Save and re-deploy the API.
4. Invoke the API's `chat/completion` endpoint with a prompt that will generate a response that violates the enforced schema.

```json
{
  "model": "mistral-large-latest",
  "messages": [
    {
      "role": "user",
      "content": "Extract the following fields from the given text and return a JSON object matching this format:\n\n{\n  \"fullName\": \"string\",\n  \"email\": \"string\",\n  \"phoneNumber\": \"string\",\n  \"organization\": \"string\",\n  \"preferredPlan\": \"Free | Pro | Enterprise\",\n  \"referralCode\": \"string\"\n}\n\nOnly include the keys that are present in the input. The JSON should not contain any extra text or explanation.\n\nInput:\nPlease register a new client with the following details:\n\n- Full Name: John Doe\n- - Phone Number: +1-555-123-4567\n- Organization: Acme Corp\n- Preferred Plan: Enterprise\n- Referral Code: ACME2025"
    }
  ]
}
```

The following guardrail error response will be returned with http status code `446`:

```json
{
  "code": "900514",
  "type": "JSON_SCHEMA_GUARDRAIL",
  "message": {
    "interveningGuardrail": "Form Validator",
    "action": "GUARDRAIL_INTERVENED",
    "actionReason": "Violation of enforced JSON schema detected.",
    "direction": "RESPONSE"
  }
}
```

---

## Limitations

The **JSON Schema Guardrail** uses the following regular expression to extract json portions from the inspected content:

```regex
\{.*?\}
```

This pattern is designed to extract **JSON objects only**; **JSON arrays are not supported**.

> 🔒 If at least one JSON object match is found, mediation will proceed.
If no JSON object match is found, the guardrail will intervene and block the mediation flow.
---
