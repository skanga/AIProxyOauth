## Build Commands

```bash
mvn clean package -DskipTests   # Build fat JAR → target/AIProxyOauth-3.0.0.jar
mvn clean package               # Build with tests
mvn test                        # Run all tests
mvn test -Dtest=ClassName       # Run a single test class
mvn clean compile               # Compile only
```

**Run the proxy:**
```bash
java -jar target/AIProxyOauth-3.0.0.jar --port 8080
java -jar target/AIProxyOauth-3.0.0.jar key generate myapp   # Generate an API key
```

## Architecture Overview

AIProxyOauth is a Java 21 proxy with Copilot, Codex, and Anthropic providers. OpenAI Chat Completions and Responses endpoints route across enabled providers. The native Anthropic Messages endpoint always uses Anthropic.

**Request flow:**
```
Client (OpenAI SDK) → Javalin HTTP server → Handler → CodexHttpClient (injects OAuth headers)
                                                    ↓
                               ChatCompletionsHandler: translates request to Responses API format,
                               receives SSE stream, translates back to Chat Completions format
```

**Key architectural components:**

| Component | Location | Responsibility |
|-----------|----------|----------------|
| `AIProxyOauth` | root | picocli entry point; wires all components |
| `ServerConfig` | config/ | Immutable record holding all runtime configuration |
| `ProxyServer` | server/ | Javalin setup, routing, API key enforcement |
| `EffectiveConfig` / `EffectiveConfigLoader` | config/ | Strict CLI/environment/YAML settings and precedence |
| `ProviderDispatch` | server/ | Provider maps, exact-model opt-in failover, replay route pinning |
| `CopilotBackend` | server/ | Both OpenAI client APIs, bounded streaming and client/account-isolated replay |
| `CopilotClient` / `CopilotCredentials` / `CopilotOAuth` | provider/copilot/ | Account endpoint discovery, explicit credentials, device login |
| `CopilotModelCatalog` | model/ | Account model capabilities, 5-minute fresh/1-hour maximum stale cache |
| `CopilotRequestEncoder` / `CopilotStreamDecoder` | provider/copilot/ | Chat and Responses upstream protocols; reuse Anthropic Messages translation/decoding |
| `ChatCompletionsHandler` | server/ | Bidirectional translation: Chat Completions ↔ Responses API |
| `ResponsesHandler` | server/ | Passthrough with normalization to upstream |
| `AuthManager` | auth/ | Thread-safe OAuth token loading and auto-refresh |
| `CodexHttpClient` | transport/ | Outbound HTTP with auth header injection |
| `SseParser` / `SseCollector` | sse/ | Line-by-line SSE parsing; collects stream into final response |
| `ModelResolver` | model/ | Multi-level cached model discovery (1h version, 5m model list) |
| `UsageTracker` | usage/ | Per-API-key token usage with `ConcurrentHashMap` + `LongAdder` |

## API Endpoints

- `GET /health` — liveness check
- `GET /v1/models` — model list (cached 5 minutes)
- `POST /v1/chat/completions` — main endpoint; translates to/from Responses API
- `POST /v1/responses` — passthrough to upstream Responses API
- `GET /v1/usage` — per-key usage stats (admin key sees all keys)

## Key Design Details

**Copilot:** Direct GitHub bearer authentication; credentials come from an explicitly configured file, dedicated environment token, or proxy-managed login, in that order. Never discover other applications' stores. No fabricated Copilot models. List IDs as `copilot/<id>`. Enterprise Cloud host isolation is offline-tested; live enterprise validation is optional and currently unverified.

**Routing:** Default order is Copilot, Codex, Anthropic. `both` retains its Codex/Anthropic meaning; `all` selects all three. Failover is disabled by default, requires exact raw model/capability matches, and stops after downstream response commitment. Qualified and replay requests never fail over. Preserve existing provider behavior and use behavioral RED/GREEN tests for changes.

**API Translation (`ChatCompletionsHandler`):** Converts `messages[]` (system/user/assistant/tool roles), `tools`/`function_call`, `stream`, `reasoning_effort`, and token usage between the two formats. This is the most complex file (~561 lines).

**Streaming:** Each request runs on a virtual thread. SSE lines are parsed and forwarded in real time. For non-streaming requests, `SseCollector` buffers the SSE stream and emits a single JSON response.

**Auth refresh:** `AuthManager` uses a `ReentrantLock` to serialize refreshes. Tokens are refreshed if they expire within 5 minutes or if more than 55 minutes have elapsed since last refresh. OAuth config is read from `auth.json`.

**Codex auth asymmetry (intentional):** Anthropic and Copilot have proxy-native `auth <provider> login`/`logout` commands (Anthropic: authorization-code + PKCE with a pasted `code#state`; Copilot: GitHub device flow). Codex has none — the proxy only *discovers* (`CODEX_HOME/auth.json`, `~/.codex/auth.json`) and *refreshes* Codex credentials (`AuthLoader`/`AuthManager`); initial login is delegated to the official `codex login` CLI. This is deliberate: ChatGPT's login is a private, undocumented flow that requires the fixed `http://localhost:1455/auth/callback` redirect and an extra token exchange, and can change without notice. Delegating it keeps that fragility in the official CLI, while the proxy depends only on the comparatively stable refresh endpoint. Do not add a native Codex login without accepting that maintenance/breakage risk.

**Model discovery fallback chain:** local Codex CLI binary → NPM registry → hardcoded version string. Results are cached with double-checked locking.

**API key enforcement:** Optional. Keys follow the format `sk-proxy-<32 hex chars>`. An admin key is designated at startup for viewing all usage stats. By default the server runs in open mode.

## Technology Stack

- **Java 21** — required (uses virtual threads)
- **Javalin 7 / Jetty 12** — HTTP server
- **Jackson** — JSON processing (uses `JsonNode` for dynamic manipulation)
- **picocli** — CLI argument parsing
- **java.net.http** — outbound HTTP client (no extra dependency)
