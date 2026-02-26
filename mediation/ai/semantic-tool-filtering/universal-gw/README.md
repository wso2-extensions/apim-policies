# Semantic Tool Filtering Policy for WSO2 API Manager Universal Gateway

The **Semantic Tool Filtering Policy** is a custom Synapse mediator that dynamically filters the tools provided within an API request based on their semantic relevance to the user query. It extracts both the query and the tool definitions from the incoming payload, generates embeddings for similarity comparison, and replaces the original tools array with a filtered subset optimizing the request before it reaches the LLM.

This policy helps reduce token consumption and improve LLM response quality by sending only the most relevant tools for each request.

---

## Features

- **Semantic similarity-based filtering** of tools using embedding vectors
- Two selection modes: **By Rank** (top-K) and **By Threshold**
- Supports both **JSON** and **text-based** tool/query formats
- **Embedding cache** with LRU eviction to minimize redundant API calls
- Configurable **JSONPath** expressions for flexible payload extraction
- **Mixed mode** support (JSON query + text tools, or vice versa)

---

## Selection Modes

### 1. By Rank (Top-K)

Selects a fixed number of the most semantically relevant tools. Configure the `limit` parameter to control how many tools are returned (default: 5, max: 20).

### 2. By Threshold

Selects all tools whose semantic similarity score exceeds a configurable threshold (0.0 to 1.0). Configure the `threshold` parameter (default: 0.7).

---

## Format Support

### JSON Format (Default)

Tools and queries are extracted from JSON payloads using JSONPath expressions:
- **Query**: Extracted via `queryJSONPath` (default: `$.messages[-1].content`)
- **Tools**: Extracted via `toolsJSONPath` (default: `$.tools`)

### Text Format

Tools and queries are extracted from text content using XML-like tags:
- **Query**: `<userq>What is the weather?</userq>`
- **Tools**:
  ```
  <toolname>get_weather</toolname>
  <tooldescription>Get current weather for a location</tooldescription>
  <toolname>search_web</toolname>
  <tooldescription>Search the web for information</tooldescription>
  ```

---

## Prerequisites

- Java 11 (JDK)
- Maven 3.6.x or later
- WSO2 API Manager or Synapse-compatible runtime
- A compatible embedding provider configured (e.g., OpenAI, Mistral, Azure OpenAI)

---

## Building the Project

To compile and package the policy:

```bash
mvn clean install
```

> This will generate a `.zip` file in the `target/` directory containing the mediator JAR, policy-definition.json, and artifact.j2.

---

## How to Use

Follow these steps to integrate the Semantic Tool Filtering Policy into your WSO2 API Manager instance:

1. **Unzip the Build Artifact**

   ```bash
   unzip target/org.wso2.am.policies.mediation.ai.semantic-tool-filtering-<version>-distribution.zip -d semantic-tool-filtering
   ```

2. **Copy the Mediator JAR**

   ```bash
   cp semantic-tool-filtering/org.wso2.am.policies.mediation.ai.semantic-tool-filtering-<version>.jar $APIM_HOME/repository/components/dropins/
   ```

3. **Register the Policy in Publisher**

   Use the provided `policy-definition.json` and `artifact.j2` files to define the policy in the Publisher Portal.

4. **Apply and Deploy the Policy**

    - Navigate to your API in **API Publisher**
    - Go to **API Configurations > Policies**
    - Add the **Semantic Tool Filtering** policy
    - Configure selection mode, limit/threshold, and JSONPath expressions
    - **Save and Deploy** the API

---

## Example Policy Configuration

> **Note:** An embedding provider must be configured in WSO2 API Manager. Add the following to `$APIM_HOME/repository/conf/deployment.toml`:

```toml
[apim.ai.embedding_provider]
type = "openai"
[apim.ai.embedding_provider.properties]
embedding_endpoint = "https://api.openai.com/v1/embeddings"
apikey = "sk-..."
embedding_model = "text-embedding-3-small"
```

### Example: By Rank Mode (JSON Format)

1. Create an AI API with OpenAI.
2. Add the `Semantic Tool Filtering` policy in the request flow with the following configuration:

| Field              | Example                    |
|--------------------|----------------------------|
| `Selection Mode`   | `By Rank`                  |
| `Limit`            | `3`                        |
| `Query JSON Path`  | `$.messages[-1].content`   |
| `Tools JSON Path`  | `$.tools`                  |
| `User Query Is JSON` | `true`                   |
| `Tools Is JSON`    | `true`                     |

3. Save and deploy the API.
4. Invoke the API with a request containing many tools:

```json
{
  "model": "gpt-4",
  "messages": [
    {
      "role": "user",
      "content": "What is the weather in London today?"
    }
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "get_weather",
        "description": "Get current weather information for a specified location"
      }
    },
    {
      "type": "function",
      "function": {
        "name": "search_web",
        "description": "Search the web for general information"
      }
    },
    {
      "type": "function",
      "function": {
        "name": "calculate_math",
        "description": "Perform mathematical calculations"
      }
    },
    {
      "type": "function",
      "function": {
        "name": "translate_text",
        "description": "Translate text between languages"
      }
    },
    {
      "type": "function",
      "function": {
        "name": "get_forecast",
        "description": "Get weather forecast for the next several days"
      }
    }
  ]
}
```

The policy will filter down to the 3 most relevant tools (e.g., `get_weather`, `get_forecast`, `search_web`) based on semantic similarity to the query.

### Example: By Threshold Mode

| Field              | Example                    |
|--------------------|----------------------------|
| `Selection Mode`   | `By Threshold`             |
| `Threshold`        | `0.7`                      |

Only tools with a similarity score >= 0.7 will be included in the filtered request.
