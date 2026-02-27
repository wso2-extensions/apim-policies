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
| `Query JSON Path`  | `$.contents[0].parts[0].text`   |
| `Tools JSON Path`  | `$.tools[0].function_declarations`                  |
| `User Query Is JSON` | `true`                   |
| `Tools Is JSON`    | `true`                     |

3. Save and deploy the API.
4. Invoke the API with a request containing many tools:

```json
{
  "contents": [
    {
      "role": "user",
      "parts": [
        {
          "text": "Get weather forecast. what are the tools you have?"
        }
      ]
    }
  ],
  
  "tools": [
    {
      "function_declarations": [
        {
          "name": "get_weather",
          "description": "Get current weather and 7-day forecast for a location.",
          "parameters": {
            "type": "OBJECT",
            "properties": {
              "location": { "type": "string", "description": "The city and state, e.g. Denver, CO" }
            },
            "required": ["location"]
          }
        },
        {
          "name": "book_venue",
          "description": "Reserve a conference room or meeting space.",
          "parameters": {
            "type": "OBJECT",
            "properties": {
              "location": { "type": "string" },
              "capacity": { "type": "integer", "description": "Number of people" },
              "date": { "type": "string", "description": "ISO date format" }
            },
            "required": ["location", "capacity", "date"]
          }
        },
        {
          "name": "find_restaurants",
          "description": "Locate dining options based on cuisine and dietary needs.",
          "parameters": {
            "type": "OBJECT",
            "properties": {
              "location": { "type": "string" },
              "dietary_options": { "type": "array", "items": { "type": "string" }, "description": "e.g. ['vegan', 'gluten-free']" }
            },
            "required": ["location"]
          }
        },
        {
          "name": "calendar_add",
          "description": "Create a new event on the users primary calendar.",
          "parameters": {
            "type": "OBJECT",
            "properties": {
              "summary": { "type": "string" },
              "start_time": { "type": "string" },
              "end_time": { "type": "string" }
            },
            "required": ["summary", "start_time"]
          }
        },
        {
          "name": "send_email",
          "description": "Send an email to a specific recipient.",
          "parameters": {
            "type": "OBJECT",
            "properties": {
              "recipient": { "type": "string", "description": "Email address" },
              "subject": { "type": "string" },
              "body": { "type": "string" }
            },
            "required": ["recipient", "body"]
          }
        }
      ]
    }
  ]

}
```

The policy will filter down to the 3 most relevant tools (e.g., `get_weather`, `book_venue`, `calendar_add`) based on semantic similarity to the query.

### Example: By Threshold Mode

| Field              | Example                    |
|--------------------|----------------------------|
| `Selection Mode`   | `By Threshold`             |
| `Threshold`        | `0.7`                      |

Only tools with a similarity score >= 0.7 will be included in the filtered request.

### Example: Text Format (userQueryIsJson=false, toolsIsJson=false)

When the request body contains the user query and tool definitions as plain text using XML-like tags, set both `User Query Is JSON` and `Tools Is JSON` to `false`. The policy will scan the raw request body for `<userq>`, `<toolname>`, and `<tooldescription>` tags — no JSONPath configuration is needed.

| Field                | Example     |
|----------------------|-------------|
| `Selection Mode`     | `By Rank`   |
| `Limit`              | `3`         |
| `User Query Is JSON` | `false`     |
| `Tools Is JSON`      | `false`     |

Invoke the API with a request body where the text content embeds tool definitions and the user query using tags:

```json
{
  "contents": [
    {
      "parts": [
        {
          "text": "You are a logistics assistant with access to the following tools:\n\n<toolname>get_weather</toolname><tooldescription>Get current weather and 7-day forecast for a location</tooldescription>\n<toolname>book_venue</toolname><tooldescription>Reserve meeting spaces or conference rooms</tooldescription>\n<toolname>book_flight</toolname><tooldescription>Search and book airline tickets</tooldescription>\n<toolname>find_restaurants</toolname><tooldescription>Locate dining options based on cuisine and dietary needs</tooldescription>\n<toolname>calendar_add</toolname><tooldescription>Create a new event on the user's primary calendar</tooldescription>\n<toolname>send_email</toolname><tooldescription>Send an email to a specific recipient</tooldescription>\n\n<userq>I'm planning a corporate retreat in Denver next weekend. Check the weather, book a conference room for 15 people, find vegan catering, and email the itinerary to sarah@company.com.</userq>"
        }
      ]
    }
  ]
}
```

The policy extracts the `<userq>` tag as the query and each `<toolname>`/`<tooldescription>` pair as a tool, then filters down to the 3 most relevant (e.g., `get_weather`, `book_venue`, `find_restaurants`) before forwarding the request.
