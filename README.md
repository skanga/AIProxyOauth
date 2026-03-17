# AIProxyOauth

A local HTTP proxy server that exposes OpenAI-compatible API endpoints using your ChatGPT OAuth tokens. It authenticates via an `auth.json` file (produced by `codex login`) and forwards requests to OpenAI's upstream Codex backend, translating between standard OpenAI API formats and the internal Responses API.

This is a Java 21+ project, packaged as a single self-contained JAR.

## Prerequisites

- **Java 21** or later
- **Maven 3.9+** (for building from source)
- A valid `auth.json` file (if you don't have one run `npx @openai/codex login` to create it in ~/.codex)

## Quick Start

### Download from Github releases
https://github.com/skanga/AIProxyOauth/releases
or
### Build manually

```bash
mvn clean package -DskipTests
```

This produces a fat JAR at `target/AIProxyOauth-1.0.0.jar`.

### Run

```bash
java -jar target/AIProxyOauth-1.0.0.jar
```

The server starts on `http://127.0.0.1:10531/v1` by default.

### Verify

```bash
# Health check
curl http://127.0.0.1:10531/health

# List available models
curl http://127.0.0.1:10531/v1/models

# Chat completion (non-streaming)
curl -X POST http://127.0.0.1:10531/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-5.2","messages":[{"role":"user","content":"Hello!"}]}'

# Chat completion (streaming)
curl -X POST http://127.0.0.1:10531/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-5.2","messages":[{"role":"user","content":"Hello!"}],"stream":true}'
```

## CLI Options

| Option | Default | Description |
|---|---|---|
| `--host <host>` | `127.0.0.1` | Network interface to bind to |
| `--port <port>` | `10531` | Port to listen on |
| `--models <ids>` | auto-discover | Comma-separated model IDs to expose |
| `--codex-version <ver>` | auto-detect | Codex API version for model discovery |
| `--base-url <url>` | `https://chatgpt.com/backend-api/codex` | Upstream Codex base URL |
| `--oauth-client-id <id>` | `app_EMoamEEZ73f0CkXaXp7hrann` | OAuth client ID for token refresh |
| `--oauth-token-url <url>` | `https://auth.openai.com/oauth/token` | OAuth token endpoint |
| `--oauth-file <path>` | auto-discover | Path to `auth.json` |
| `--api-key <keys>` | _(open mode)_ | Comma-separated API keys clients must present |
| `--api-keys-file <path>` | _(open mode)_ | Path to a file with one API key per line |
| `--generate-key [name]` | | Print a new random API key and exit; optional name produces `name:key` output |
| `--admin-key <key>` | _(none)_ | Owner key that sees all users' stats at `GET /v1/usage` |
| `--store` | `false` | Whether to store responses on the server |
| `--help` | | Show help and exit |
| `--version` | | Show version and exit |

### Examples

```bash
# Bind to all interfaces on port 8080
java -jar AIProxyOauth-1.0.0.jar --host 0.0.0.0 --port 8080

# Expose only specific models
java -jar AIProxyOauth-1.0.0.jar --models gpt-5.2,gpt-5.1

# Use a custom auth file location
java -jar AIProxyOauth-1.0.0.jar --oauth-file /path/to/auth.json

# Generate a new API key for a client
java -jar AIProxyOauth-1.0.0.jar --generate-key

# Require clients to present a key (inline)
java -jar AIProxyOauth-1.0.0.jar --api-key sk-proxy-a3f9c2d1e4b5f6a7b8c9d0e1f2a3b4c5

# Require clients to present a key (from file)
java -jar AIProxyOauth-1.0.0.jar --api-keys-file /path/to/keys.txt
```

## API Key Enforcement

By default the proxy runs in **open mode** — any client on the network can make requests. To restrict access, configure one or more API keys. When keys are configured, all requests except `GET /health` must include a valid key in the `Authorization: Bearer <key>` header; otherwise the proxy returns `401 Unauthorized`.

**Note:** If an `--admin-key` is set (either via CLI or in the keys file), the proxy **automatically disables open mode** and enforces authentication for all endpoints (except `/health`), even if no regular `--api-key` entries are provided. In this case, only the admin key will be accepted for general API requests.

### Generating a key

```bash
# Without a name (bare key)
java -jar AIProxyOauth-1.0.0.jar --generate-key
# sk-proxy-a3f9c2d1e4b5f6a7b8c9d0e1f2a3b4c5

# With a name — output is ready to paste into a keys file or --api-key
java -jar AIProxyOauth-1.0.0.jar --generate-key cursor
# cursor:sk-proxy-a3f9c2d1e4b5f6a7b8c9d0e1f2a3b4c5
```

Keys use the format `sk-proxy-` followed by 32 random lowercase hex characters, matching OpenAI's visual style so existing clients accept them as API keys. The name is stored alongside the key so the server can identify which client made each request.

### Configuring keys

Each key can optionally carry a human-readable name using the `name:key` format. Names are used in startup output and future per-key reporting (token usage, etc.). If no name is given the key value is used as its own name.

**Inline (one or more, comma-separated):**
```bash
# Named keys
java -jar AIProxyOauth-1.0.0.jar --api-key cursor:sk-proxy-a3f9c2d1e4b5f6a7b8c9d0e1f2a3b4c5

# Multiple, mixed named/unnamed
java -jar AIProxyOauth-1.0.0.jar --api-key cursor:sk-proxy-a3f9...,vscode:sk-proxy-1a2b...
```

**From a file (one entry per line; blank lines and `#` comments are ignored):**
```
# keys.txt — format: [name:]key
cursor:sk-proxy-a3f9c2d1e4b5f6a7b8c9d0e1f2a3b4c5
vscode:sk-proxy-1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d
admin:sk-proxy-99887766554433221100aabbccddeeff    # promoted to --admin-key
sk-proxy-ffeeddccbbaa00112233445566778899          # unnamed — key used as its own name
```
```bash
java -jar AIProxyOauth-1.0.0.jar --api-keys-file keys.txt
```

If an entry in the file uses the reserved name `admin`, it is automatically promoted to the admin key (equivalent to `--admin-key`). If both are provided, the CLI flag takes precedence.

Both options can be combined; keys from all sources are merged.

### Using a key from a client

```bash
curl http://127.0.0.1:10531/v1/models \
  -H "Authorization: Bearer sk-proxy-a3f9c2d1e4b5f6a7b8c9d0e1f2a3b4c5"
```

The `GET /health` endpoint is always open regardless of key configuration.

## Security Considerations

### Network Access

The `--host` option determines which network interfaces the proxy binds to:

- **Local access only (Default):** `--host 127.0.0.1`
  The proxy is only accessible from the machine it is running on. This is the most secure setting for personal use.
- **Full network access:** `--host 0.0.0.0`
  The proxy is accessible from any device on your local network (or the internet, if your port is forwarded). 

**Warning:** If you use `--host 0.0.0.0`, it is **strongly recommended** to configure API keys (see [API Key Enforcement](#api-key-enforcement)) to prevent unauthorized use of your ChatGPT account.

### Token Security

The proxy stores sensitive OAuth tokens in `auth.json`. On the first write (or refresh), the proxy attempts to set strict file permissions (read/write by owner only, `chmod 600`) to protect these tokens from other users on the same machine.

---

## API Endpoints

### `GET /health`

Returns server health status.

**Response:**
```json
{"ok": true, "replay_state": "stateless"}
```

### `GET /v1/models`

Returns the list of available models. Models are auto-discovered from your account and cached for 5 minutes, unless overridden with `--models`.

**Response:**
```json
{
  "object": "list",
  "data": [
    {"id": "gpt-5.2", "object": "model", "created": 0, "owned_by": "codex-oauth"},
    {"id": "gpt-5.1", "object": "model", "created": 0, "owned_by": "codex-oauth"}
  ]
}
```

### `POST /v1/chat/completions`

Standard OpenAI Chat Completions API. Supports both streaming (`"stream": true`) and non-streaming requests.

**Supported request fields:**
- `model` — model ID (default: `gpt-5.2`)
- `messages` — array of message objects (`system`, `developer`, `user`, `assistant`, `tool` roles)
- `stream` — boolean for SSE streaming
- `temperature`, `top_p` — sampling parameters
- `max_tokens` — maximum output tokens
- `stop` — stop sequence(s)
- `tools` — function tool definitions
- `tool_choice` — `"auto"`, `"none"`, `"required"`, or `{"type":"function","function":{"name":"..."}}`
- `reasoning_effort` — `"low"`, `"medium"`, or `"high"`

**Non-streaming response:**
```json
{
  "id": "chatcmpl_...",
  "object": "chat.completion",
  "created": 1710000000,
  "model": "gpt-5.2",
  "choices": [{
    "index": 0,
    "message": {"role": "assistant", "content": "Hello!"},
    "finish_reason": "stop"
  }],
  "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
}
```

**Streaming response:** Server-Sent Events with `chat.completion.chunk` objects, followed by `data: [DONE]`.

### `GET /v1/usage`

Returns accumulated token usage broken down by API key name, plus an overall total. Counts are in-memory and reset when the server restarts.

**Response:**
```json
{
  "keys": [
    {"name": "cursor", "prompt_tokens": 1234, "completion_tokens": 567, "total_tokens": 1801},
    {"name": "vscode", "prompt_tokens": 890,  "completion_tokens": 234, "total_tokens": 1124}
  ],
  "total": {"prompt_tokens": 2124, "completion_tokens": 801, "total_tokens": 2925}
}
```

Token counts are tracked for `POST /v1/chat/completions` (streaming and non-streaming) and `POST /v1/responses` (non-streaming only). In open mode (no API keys configured) `keys` is always empty.

Each key sees only its own stats. The proxy owner can configure an `--admin-key` to see all users' stats:

```bash
java -jar AIProxyOauth-1.0.0.jar \
  --api-key cursor:sk-proxy-... \
  --api-key vscode:sk-proxy-... \
  --admin-key sk-proxy-...
```

### `POST /v1/responses`

Passthrough to the upstream Responses API with normalization. Requests are always streamed upstream; the response is either streamed back to the client or collected into a single JSON object depending on the `stream` field.

**Restrictions:** This endpoint operates in stateless mode. Requests containing `previous_response_id` or `item_reference` inputs are rejected with an error instructing the caller to replay the full conversation history.

## Authentication

### Auth File Discovery

The server searches for `auth.json` in this order:

1. Path provided via `--oauth-file`
2. `$CHATGPT_LOCAL_HOME/auth.json`
3. `$CODEX_HOME/auth.json`
4. `~/.chatgpt-local/auth.json`
5. `~/.codex/auth.json`

### Auth File Format

```json
{
  "tokens": {
    "id_token": "eyJ...",
    "access_token": "eyJ...",
    "refresh_token": "eyJ...",
    "account_id": "acct_..."
  },
  "last_refresh": "2026-01-15T10:30:00.000Z"
}
```

To create this file, run:

```bash
npx @openai/codex login
```

### Token Refresh

Tokens are automatically refreshed when:
- The access token expires within 5 minutes (based on JWT `exp` claim)
- More than 55 minutes have passed since the last refresh

After a successful refresh, the updated tokens are written back to the auth file.

## Model Discovery

If `--models` is not specified, models are discovered automatically:

1. The Codex client version is resolved: `--codex-version` flag, then local `codex --version`, then npm registry, then fallback `0.111.0`
2. The upstream `/models?client_version=X` endpoint is queried
3. Results are cached for 5 minutes

## Using with OpenAI Client Libraries

Point any OpenAI-compatible client at the proxy's base URL:

### Python

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://127.0.0.1:10531/v1",
    api_key="not-needed"  # open mode: any non-empty string works
    # api_key="sk-proxy-a3f9c2d1e4b5f6a7b8c9d0e1f2a3b4c5"  # enforcement mode
)

response = client.chat.completions.create(
    model="gpt-5.2",
    messages=[{"role": "user", "content": "Hello!"}]
)
print(response.choices[0].message.content)
```

### Node.js

```javascript
import OpenAI from 'openai';

const client = new OpenAI({
  baseURL: 'http://127.0.0.1:10531/v1',
  apiKey: 'not-needed',   // open mode: any non-empty string works
  // apiKey: 'sk-proxy-a3f9c2d1e4b5f6a7b8c9d0e1f2a3b4c5',  // enforcement mode
});

const response = await client.chat.completions.create({
  model: 'gpt-5.2',
  messages: [{ role: 'user', content: 'Hello!' }],
});
console.log(response.choices[0].message.content);
```

### curl

```bash
# Open mode
curl http://127.0.0.1:10531/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-5.2","messages":[{"role":"user","content":"What is 2+2?"}]}'

# Enforcement mode
curl http://127.0.0.1:10531/v1/chat/completions \
  -H "Authorization: Bearer sk-proxy-a3f9c2d1e4b5f6a7b8c9d0e1f2a3b4c5" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-5.2","messages":[{"role":"user","content":"What is 2+2?"}]}'
```

## Troubleshooting

### "No auth file was found"
Run `npx @openai/codex login` to create the auth file, or specify its location with `--oauth-file`.

### "ChatGPT access token not found"
Your `auth.json` exists but contains no valid access token. Re-run `npx @openai/codex login`.

### "ChatGPT account id not found"
The `id_token` in your auth file does not contain the expected account ID claim. Re-run `npx @openai/codex login`.

### Model discovery fails
Ensure your auth tokens are valid. You can bypass discovery by specifying models explicitly: `--models gpt-5.2,gpt-5.1`.

### Connection refused
Check that no other process is using port 10531, or specify a different port with `--port`.

## License

See repository for license details.
