# Content Based Model Router Mediator for WSO2 API Manager Universal Gateway

The **Content Based Model Router** is a custom Synapse mediator for **WSO2 API Manager Universal Gateway**, designed to perform **content-based routing (CBR)** for AI API requests. This component routes requests to specific model endpoints based on query parameter or header values, enabling dynamic routing decisions based on request attributes.

---

## Features

- Route requests based on **header values**
- Route requests based on **query parameter values**
- Support for **production and sandbox** routing configurations
- **First-match-wins** routing strategy
- Fallback to **default endpoint** when no condition matches

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

> This will generate a `.zip` file in the `target/` directory containing the mediator JAR, policy-definition.json and artifact.j2.

## How to Use

Follow these steps to integrate the Content Based Model Router policy into your WSO2 API Manager instance:

1. **Unzip the Build Artifact**
   After the build, unzip the artifact generated in the `target/` directory:

   ```bash
   unzip target/org.wso2.am.policies.mediation.ai.content-based-model-router-<version>-distribution -d content-based-model-router
   ```

2. **Copy the Mediator JAR**
   Place the mediator JAR into your API Manager's runtime libraries:

   ```bash
   cp content-based-model-router/org.wso2.am.policies.mediation.ai.content-based-model-router-<version>.jar $APIM_HOME/repository/components/lib/
   ```

3. **Register the Policy in Publisher**
   Use the provided `policy-definition.json` and `artifact.j2` files to define the policy in the Publisher Portal.

    - Place these files in the correct directory structure expected by your deployment process or manually register via REST APIs or UIs.

4. **Apply and Deploy the Policy**
    - Open the **API Publisher**
    - Select your API
    - Go to **Runtime > Request Flow**
    - Click **Add Policy**, select the new **Content Based Model Router** policy
    - Provide the required configuration
    - **Save and Deploy** the API

---

## Configuration Format

The `contentBasedModelRoutingConfigs` parameter accepts a JSON string with the following structure:

```json
{
  "production": [
    {
      "condition": {
        "type": "HEADER",
        "key": "X-Model-Tier",
        "operator": "EQUALS",
        "value": "premium"
      },
      "target": {
        "model": "gpt-4",
        "endpointId": "premium-endpoint-001"
      }
    },
    {
      "condition": {
        "type": "QUERY_PARAMETER",
        "key": "model_tier",
        "operator": "EQUALS",
        "value": "standard"
      },
      "target": {
        "model": "gpt-3.5-turbo",
        "endpointId": "standard-endpoint-001"
      }
    }
  ],
  "sandbox": [
    {
      "condition": {
        "type": "HEADER",
        "key": "X-Model-Tier",
        "operator": "EQUALS",
        "value": "test"
      },
      "target": {
        "model": "gpt-3.5-turbo",
        "endpointId": "sandbox-endpoint-001"
      }
    }
  ]
}
```

### Condition Fields

| Field      | Description                                              | Values                           |
|------------|----------------------------------------------------------|----------------------------------|
| `type`     | The type of parameter to match against                   | `HEADER`, `QUERY_PARAMETER`      |
| `key`      | The name of the header or query parameter                | Any string                       |
| `operator` | The comparison operator                                  | `EQUALS`                         |
| `value`    | The value to match against                               | Any string                       |

### Target Fields

| Field        | Description                                            |
|--------------|--------------------------------------------------------|
| `model`      | The model name to route to                             |
| `endpointId` | The endpoint identifier for the target model           |

---

## Example Usage

1. Create an AI API using OpenAI or another LLM provider.
2. Add the Content Based Model Router policy to the API's request flow.
3. Configure the routing rules based on your requirements.
4. Save and re-deploy the API.
5. Invoke the API with the appropriate header or query parameter:

```bash
# Route to premium model using header
curl -X POST "https://api.example.com/ai/chat" \
  -H "X-Model-Tier: premium" \
  -H "Content-Type: application/json" \
  -d '{"messages": [{"role": "user", "content": "Hello"}]}'

# Route to standard model using query parameter
curl -X POST "https://api.example.com/ai/chat?model_tier=standard" \
  -H "Content-Type: application/json" \
  -d '{"messages": [{"role": "user", "content": "Hello"}]}'
```

---
