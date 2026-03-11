# Intelligent Model Routing Mediator for WSO2 API Manager Universal Gateway

The **Intelligent Model Routing** is a custom Synapse mediator for **WSO2 API Manager Universal Gateway**, designed to perform **LLM-powered routing** for AI API requests. Unlike Semantic Routing, which uses vector embeddings and utterance similarity, Intelligent Model Routing asks an LLM to read each incoming request and classify it into one of your predefined routing rules — making it effective even for short, keyword-style queries.

You define each rule with a **name** (which can be any label you choose, such as `Weather Information`, `Coding Questions`, or `test123`) and a **context description** that tells the LLM what kinds of requests belong to that rule. The LLM then classifies each incoming request against those descriptions and routes to the matching model.

---

## Features

- **LLM-Powered Classification** using a configured LLM provider to understand request intent
- **Flexible Rule Naming** — descriptive names (e.g., `Weather Information`) or arbitrary identifiers (e.g., `test123`)
- **Context-Driven Routing** with free-text context descriptions for full control over classification logic
- **Short Query Support** — works reliably with single words, short phrases, or full sentences
- **Default Fallback** to a configured model when the LLM cannot confidently match any rule
- Support for **production and sandbox** routing configurations

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

---

## How It Works

Intelligent Model Routing operates through the following process:

1. **Request Processing**: When a user request arrives, the policy extracts the relevant content using a JSONPath expression (the **Content Path**).
2. **Prompt Construction**: The policy builds a classification prompt containing all configured rule names and their context descriptions.
3. **LLM Classification**: The prompt and extracted content are sent to the configured LLM provider, which responds with the name of the matching rule or `NONE` if no rule fits.
4. **Route Selection**: The policy routes the request to the model and endpoint associated with the matched rule.
5. **Fallback**: If the LLM responds with `NONE`, or if classification fails, the request is routed to the configured default model.

> **Tip — When to use Intelligent Model Routing vs Semantic Routing**
>
> Use **Intelligent Model Routing** when:
> - Users send **short queries** (single words, 2–3 word phrases like `"weather"`, `"recipe ideas"`, `"rain forecast"`)
> - You want to describe routing intent in **plain language** rather than managing lists of utterances
> - You need flexible, context-aware classification without configuring an embedding provider
>
> Use **Semantic Routing** when users consistently send **full sentences** expressing a clear intent, and you want deterministic, embedding-based similarity matching.

---

## How to Use

Follow these steps to integrate the Intelligent Model Routing policy into your WSO2 API Manager instance:

1. **Unzip the Build Artifact**
   After the build, unzip the artifact generated in the `target/` directory:

   ```bash
   unzip target/org.wso2.am.policies.mediation.ai.intelligent-model-routing-<version>-distribution.zip -d intelligent-model-routing
   ```

2. **Copy the Mediator JAR**
   Place the mediator JAR into your API Manager's runtime libraries:

   ```bash
   cp intelligent-model-routing/org.wso2.am.policies.mediation.ai.intelligent-model-routing-<version>.jar $APIM_HOME/repository/components/lib/
   ```

3. **Register the Policy in Publisher**
   Use the provided `policy-definition.json` and `artifact.j2` files to define the policy in the Publisher Portal.

    - Place these files in the correct directory structure expected by your deployment process or manually register via REST APIs or UIs.

4. **Apply and Deploy the Policy**
    - Open the **API Publisher**
    - Select your API
    - Go to **Runtime > Request Flow**
    - Click **Add Policy**, select the new **Intelligent Model Routing** policy
    - Provide the required configuration
    - **Save and Deploy** the API

> **Note — AWS Bedrock Configuration**
> When configuring intelligent model routing with AWS Bedrock as a multi-model provider service, you must select both the **Provider** (model family) and the **Model** for each rule and the default model. The **Provider** dropdown lists the model families you have set up in the Admin Portal (such as Meta, Anthropic, DeepSeek, etc.), and once a provider is selected, the **Model** dropdown will display the specific models available under that provider.

---

## Configuration Format

### Basic Configuration

| Field | Description | Example |
|-------|-------------|---------|
| **Content Path** | JSONPath expression to extract the user's request content from the payload | `$.messages[-1].content` |

### Routing Rule Configuration

For each environment (Production/Sandbox), you can configure multiple routing rules:

| Field | Description | Required |
|-------|-------------|----------|
| **Rule Name** | A label for this rule. Can be any name you choose (e.g., `Weather Information`, `Coding Questions`, `test123`). The LLM uses this name to identify the matched rule. | Yes |
| **Context** | A plain-language description of the types of requests this rule should handle. The LLM reads this description when classifying incoming requests. | Yes |
| **Model** | The target AI model to route matching requests to | Yes |
| **Endpoint** | The endpoint to route matching requests to | Yes |

> **Note**: The **Rule Name** you provide is passed directly to the LLM classifier. Choose names that are meaningful to you — they do not need to follow any specific format. However, if you use a rule name like `test123`, ensure your context description clearly describes what requests should match, since the LLM relies on the context to make classification decisions.

### Default Model Configuration

| Field | Description | Required |
|-------|-------------|----------|
| **Default Model** | The model to use when no routing rule matches | Yes |
| **Default Endpoint** | The endpoint for the default model | Yes |

---

## Example Policy Configuration

1. Create an AI API with multiple model endpoints configured.
2. Add the Intelligent Model Routing policy to the API with the following configuration:

| Field | Example |
|-------|---------|
| `Content Path` | `$.messages[-1].content` |

**Production Routing Rules**:

**Rule 1**
- **Rule Name**: `Coding`
- **Context**: `Code Related Question`
- **Model**: `gpt-4o-mini`
- **Endpoint**: `gpt-4o-mini`

**Default Model**:
- **Model**: `gpt-4o`
- **Endpoint**: `gpt-4o`

3. Save and deploy the API.
4. Test with different queries:

**Request 1 (Routes to Coding)**:
```json
{
  "messages": [
    {
      "role": "user",
      "content": "Generate a small HTML code"
    }
  ]
}
```

**Request 2 — No match (Routes to Default Model)**:
```json
{
  "messages": [
    {
      "role": "user",
      "content": "What is the weather forecast for tomorrow in Paris"
    }
  ]
}
```

---

## Best Practices

1. **Write clear context descriptions**: The LLM classification quality depends entirely on how well your context descriptions describe the intended request types. Be specific and list relevant topics.
2. **Use distinct contexts**: Avoid overlapping context descriptions across rules. If two rules have similar descriptions, the LLM may classify requests inconsistently.
3. **Rule naming**: Choose rule names that are meaningful to you. Descriptive names (e.g., `Weather Information`) make logs easier to read, but arbitrary names (e.g., `rule-A`, `test123`) work equally well as long as the context is clear.
4. **Default model**: Always configure a capable default model to handle requests that do not match any rule.
5. **Testing**: Test with both short keyword inputs and full sentences to verify that your context descriptions produce the expected routing behaviour.
6. **Monitoring**: Monitor routing decisions in logs (enable debug logging) to identify rules that are being missed or incorrectly matched, and refine context descriptions accordingly.

---

## Troubleshooting

### Common Issues

| Issue | Possible Cause | Solution |
|-------|----------------|----------|
| All requests route to default model | Context descriptions are too vague or narrow | Rewrite context descriptions to more clearly cover the expected request types |
| Requests routing to wrong rule | Overlapping context descriptions | Make each rule's context more specific and distinct from other rules |
| Inconsistent routing for the same query | LLM non-determinism | Add a more explicit and detailed context description; ensure rule names are unambiguous |
| Classification fails entirely | LLM provider not configured or unreachable | Verify the LLM provider is correctly configured for the API and is reachable |
| Policy not applying | Incorrect content path | Check that the **Content Path** JSONPath expression correctly targets the user message field in your request payload |

---

