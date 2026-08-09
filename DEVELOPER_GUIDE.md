# AIProxyOauth Developer Guide

This document explains the architecture, design decisions, and internals of the AIProxyOauth project for developers who want to understand, modify, or extend the codebase.

## Table of Contents

- [Overview](#overview)
- [Technology Stack](#technology-stack)
- [Build and Run](#build-and-run)
- [Project Structure](#project-structure)
- [Architecture](#architecture)
- [Package Walkthrough](#package-walkthrough)
  - [config](#config)
  - [auth](#auth)
  - [transport](#transport)
  - [sse](#sse)
  - [state](#state)
  - [model](#model)
  - [server](#server)
  - [Entry Point](#entry-point)
- [Request Flow](#request-flow)
- [Chat Completions Translation Layer](#chat-completions-translation-layer)
- [SSE Streaming](#sse-streaming)
- [Concurrency Model](#concurrency-model)
- [Design Decisions](#design-decisions)
- [Extending the Project](#extending-the-project)

---

## Overview

AIProxyOauth is a local HTTP proxy that translates standard OpenAI API calls for two OAuth-backed providers: OpenAI's Codex/Responses backend and Anthropic's Claude Messages backend. Codex uses ChatGPT credentials from `auth.json`; Claude uses a proxy-owned OAuth credential or `CLAUDE_CODE_OAUTH_TOKEN`.

The providers expose different wire protocols. Requests are normalized into a shared canonical model, routed by provider/model, and encoded back into OpenAI Chat Completions or Responses output without mixing provider-specific transport logic into the public API layer.

## Technology Stack

| Component | Library | Why |
|---|---|---|
| HTTP Server | Javalin 7.1.0 (Jetty 12) | Lightweight, virtual thread support, simple handler API |
| JSON | Jackson 2.21.1 (`ObjectMapper`, `JsonNode`) | Dynamic JSON manipulation without POJOs, industry standard |
| CLI | picocli 4.7.7 | Annotation-driven argument parsing, auto-generated help |
| HTTP Client | `java.net.http.HttpClient` | Built-in, supports streaming via `InputStream`, no extra deps |
| Logging | SLF4J 2.0.17 + slf4j-simple | Satisfies Javalin/Jetty's SLF4J requirement |
| Concurrency | Virtual threads (Java 21) | Lightweight, scales to many concurrent connections |

## Build and Run

```bash
# Compile
mvn clean compile

# Package fat JAR
mvn package -DskipTests

# Run
java -jar target/AIProxyOauth-2.0.0.jar [serve] [options]

# Run tests
mvn test
```

The `maven-shade-plugin` produces a self-contained JAR with all dependencies at `target/AIProxyOauth-2.0.0.jar`.

## Project Structure

```
AIProxyOauth/
├── pom.xml                                          # Maven build config
├── README.md                                        # User guide
├── DEVELOPER_GUIDE.md                               # This file
└── src/main/java/com/aiproxyoauth/
    ├── AIProxyOauth.java                            # CLI entry point (picocli)
    ├── config/
    │   └── ServerConfig.java                        # Immutable config record
    ├── auth/
    │   ├── JwtParser.java                           # JWT payload decoding
    │   ├── AuthFileResolver.java                    # Auth file path discovery
    │   ├── AuthLoader.java                          # Token loading and refresh
    │   └── AuthManager.java                         # Thread-safe cached auth
    ├── transport/
    │   ├── UrlResolver.java                         # URL path normalization
    │   └── CodexHttpClient.java                     # Outbound HTTP with auth
    ├── sse/
    │   ├── ServerSentEvent.java                     # SSE record type
    │   ├── SseParser.java                           # SSE stream parser
    │   └── SseCollector.java                        # Collect response from SSE
    ├── state/
    │   └── ResponsesState.java                      # LRU response/item caches
    ├── model/
    │   └── ModelResolver.java                       # Model discovery and caching
    └── server/
        ├── ProxyServer.java                         # Javalin setup and routing
        ├── JsonHelper.java                          # JSON/SSE/CORS utilities
        ├── HealthHandler.java                       # GET /health
        ├── ModelsHandler.java                       # GET /v1/models
        ├── ResponsesHandler.java                    # POST /v1/responses
        └── ChatCompletionsHandler.java              # POST /v1/chat/completions
```

## Architecture

```
┌─────────────┐     ┌──────────────────────────────────────────────────────┐     ┌─────────────────┐
│ OpenAI      │     │                   AIProxyOauth                       │     │ OpenAI Upstream │
│ Client      │────>│  Javalin Server                                      │────>│ Codex Backend   │
│ (any SDK)   │     │   ├─ ChatCompletionsHandler (translate chat→resp)    │     │ (Responses API) │
│             │<────│   ├─ ResponsesHandler (normalize + passthrough)      │<────│                 │
│             │     │   ├─ ModelsHandler (cached model list)               │     │                 │
│             │     │   └─ HealthHandler                                   │     │                 │
└─────────────┘     │                                                      │     └─────────────────┘
                    │  AuthManager ──> AuthLoader ──> OAuth Token Endpoint │
                    └──────────────────────────────────────────────────────┘
```

### Data flow for `POST /v1/chat/completions`:

1. Client sends standard OpenAI chat completion request
2. `ChatCompletionsHandler` translates messages to Responses API `input` format
3. Request is forwarded to upstream `/responses` (always streaming)
4. **Non-streaming:** SSE events are collected, final response is extracted, translated back to `chat.completion` JSON
5. **Streaming:** SSE events are parsed in real-time, translated to `chat.completion.chunk` SSE events, and streamed to the client

### Multi-provider architecture

`ProviderStartupResolver` selects enabled providers from explicit CLI configuration or available credentials. `CompositeModelCatalog` merges provider catalogs, while `ProviderRouter` resolves qualified names (`codex/...`, `anthropic/...`), known aliases/prefixes, and the configured default provider. Ambiguity is an error.

The routing handlers delegate to provider backends:

- Codex retains `ChatCompletionsHandler` and `ResponsesHandler`, backed by `CodexHttpClient` and the Codex SSE parser.
- Claude uses `AnthropicChatBackend` and `AnthropicResponsesBackend`. `AnthropicRequestTranslator` converts canonical inputs to Messages requests; `AnthropicStreamDecoder` validates incremental SSE and emits canonical events; the public encoders produce OpenAI-compatible sync or streaming output.
- `ResponsesState` supplies bounded, client-isolated expansion of Claude `previous_response_id` and `item_reference` values because Anthropic has no corresponding persisted-state feature.
- `AnthropicMessagesHandler` bypasses canonical/OpenAI conversion for native `/v1/messages`, preserving JSON and SSE while applying OAuth preamble/model/header security. `AnthropicModelsHandler` content-negotiates native Claude-only model discovery on the existing `/v1/models` path.

The Claude compatibility constants are centralized in `AnthropicCompatibilityProfile`. Treat its client id, endpoints, scopes, beta headers, API version, and system preamble as one versioned unit. See `RELEASE.txt` for the pinned values and reference revision.

## Package Walkthrough

### config

**`ServerConfig.java`** - A Java `record` holding immutable runtime configuration, including host/port, OAuth settings, API keys, CORS, request logging, prompt-cache forwarding, and Codex instruction options.

`ServerConfig` also carries compatibility and diagnostics flags: request logging, prompt-cache header forwarding, and optional Codex instruction source/cache settings. Full request logging defaults off because request bodies can contain prompts, tool outputs, paths, and other sensitive data.

### logging

`RequestLogger` is an opt-in JSON request logger. It logs inbound and upstream request/response metadata only when enabled, caps body capture, marks truncation, and redacts sensitive headers case-insensitively (`Authorization`, API keys, cookies, token/secret/key-like headers). Logging failures are warnings and must not fail proxied requests.

`ProxyServer` also prints a default one-line access log to the CLI for each request. That log is intentionally minimal: timestamp, method, path, status, duration, request ID, stream/sync mode when known, request content length, response status, and response byte count. Do not add headers, query strings, bodies, API keys, or OAuth material to the access log.

### auth

**`JwtParser.java`** — Decodes JWT tokens without verification (we only need the payload claims). Uses `Base64.getUrlDecoder()` to decode the middle segment, then parses with Jackson. The `deriveAccountId()` method extracts `chatgpt_account_id` from the `https://api.openai.com/auth` claim in the `id_token`.

**`AuthFileResolver.java`** — Resolves candidate paths for `auth.json` in priority order: explicit path, `$CODEX_HOME`, then `~/.codex/`. Also determines the write-back path for refreshed tokens.

**`AuthLoader.java`** — The core authentication logic:
- Reads `auth.json` from the first candidate path that exists
- Checks if the access token needs refreshing (expired within 5 minutes, or last refresh was >55 minutes ago)
- Refreshes via POST to the OAuth token endpoint with `grant_type=refresh_token`
- Writes updated tokens back to the auth file
- Returns an `AuthResult` record with `accessToken`, `accountId`, etc.

**`AuthManager.java`** — Thread-safe wrapper around `AuthLoader`. Caches the current `AuthResult` and provides `getAuthHeaders()` which returns a map with `Authorization`, `chatgpt-account-id`, and `OpenAI-Beta` headers. Uses `ReentrantLock` for safe concurrent access.

Claude authentication lives under `provider/anthropic/auth`. `AnthropicAuthCommands` owns interactive login/logout; `AnthropicCredentialStore` provides bounded parsing, atomic replacement, locking, and restrictive permissions; `AnthropicAuthManager` serializes refresh and retries one pre-body 401. An environment access token is deliberately non-refreshable. Never log access tokens, refresh tokens, authorization codes, PKCE verifiers, signed reasoning, or redacted thinking.

### transport

**`UrlResolver.java`** — Given an input path like `/v1/models` and a base URL like `https://chatgpt.com/backend-api/codex`, it:
1. Strips the base path prefix if present
2. Strips the `/v1` prefix
3. Reconstructs the full URL: `https://chatgpt.com/backend-api/codex/models`

**`CodexHttpClient.java`** — Wraps `java.net.http.HttpClient` with auth header injection. Provides two methods:
- `request()` — Returns `HttpResponse<InputStream>` for streaming
- `requestString()` — Returns `HttpResponse<String>` for simple responses

Both methods resolve URLs via `UrlResolver` and inject auth headers via `AuthManager`.

### sse

**`ServerSentEvent.java`** — Simple `record(String event, String data)`.

**`SseParser.java`** — Parses Server-Sent Events from an `InputStream` using `BufferedReader`. Handles:
- `event:` lines → event type
- `data:` lines → event data (multiple data lines joined with `\n`)
- Blank lines → event boundaries
- Supports both batch parsing (`parse()`) and callback-based iteration (`iterateEvents()`), the latter used for streaming to avoid buffering the full stream.

**`SseCollector.java`** — Iterates over an SSE stream looking for events whose JSON `data` contains a `response` object. Returns the last such object found. Used to extract the final completed response from an always-streaming upstream. Tracks `error` events for diagnostics.

### state

**`ResponsesState.java`** — Bounded LRU caches for the Responses API's stateful features:
- **Items cache** (max 2,000): Maps item IDs to their JSON objects, enabling `item_reference` expansion
- **Responses cache** (max 256): Maps response IDs to their input/output pairs, enabling `previous_response_id` expansion

Uses `LinkedHashMap` with `removeEldestEntry` for automatic LRU eviction. All public methods are `synchronized` for thread safety. Key operations:
- `requiresCachedState()` — Checks if a request body references cached state
- `expandRequestBody()` — Replaces `previous_response_id` and `item_reference` with actual cached data
- `rememberResponse()` — Caches a response's output items and input/output pair

> **Note:** `ResponsesState` is wired into `ResponsesHandler` as a best-effort, same-process replay cache. It is scoped per API key when key enforcement is enabled and is not durable storage.

### model

**`ModelResolver.java`** — Discovers available models with multi-level caching:

1. **Codex version resolution** (cached 1 hour):
   - Explicit `--codex-version` flag
   - Local CLI: `ProcessBuilder("codex", "--version")`
   - NPM registry: `GET https://registry.npmjs.org/@openai/codex/latest`
   - Fallback: `0.111.0`

2. **Model list** (cached 5 minutes):
   - Fetches `GET /models?client_version=X` from upstream
   - Extracts `slug` from each model entry
   - Deduplicates

Both caches use double-checked locking with `ReentrantLock` and `volatile` fields.

**`ModelAliasResolver.java`** - Normalizes convenience aliases such as `gpt-5.2-codex-xhigh` to the backend model plus a default `reasoning.effort`. It also clamps unsupported reasoning values before forwarding, which avoids preventable upstream 400s.

**`CodexInstructionsProvider.java`** - Supplies configured instructions by default. In opt-in `latest-codex` mode, it fetches model-family instructions, caches them for 15 minutes, sends conditional requests when an ETag exists, and falls back to stale cache or configured instructions on fetch failure.

### server

**`JsonHelper.java`** — Utility class providing:
- `toJsonResponse()` / `toErrorResponse()` — Standard JSON response formatting (OpenAI error format)
- `mapFinishReason()` — Translates between upstream and OpenAI finish reasons
- `toUsage()` — Converts upstream usage JSON to OpenAI format (prompt/completion/total tokens, with optional detail breakdowns)
- `setCorsHeaders()` / `setSseHeaders()` — Standard header configuration

**`HealthHandler.java`** — Returns safe liveness/status fields: `ok`, `service`, `version`, and `uptime_seconds`.

**`ModelsHandler.java`** — Calls `ModelResolver.resolveModels()`, formats as OpenAI model list response with `owned_by: "codex-oauth"`.

**`ResponsesHandler.java`** — Passthrough to upstream `/responses` with normalization:
1. Validates body is a JSON object
2. Expands `previous_response_id` and `item_reference` only from bounded in-memory same-process cache when available
3. Normalizes: forces upstream `stream=true`, sets model aliases, default `instructions`, and `store`
4. In stateless mode, strips item IDs, removes unresolved `item_reference`, and converts orphaned tool outputs to assistant messages
5. Optionally forwards `prompt_cache_key` as upstream conversation/session headers
6. Forwards to upstream
7. Maps usage-limit style upstream 404s to 429
8. If client wants streaming: pipes SSE directly
9. If non-streaming: collects completed response via `SseCollector`, records usage, and remembers it only in memory

**`ChatCompletionsHandler.java`** — The most complex handler. See [Chat Completions Translation Layer](#chat-completions-translation-layer).

**`ProxyServer.java`** — Javalin application setup:
- Enables virtual threads (`useVirtualThreads = true`)
- Registers CORS preflight handler for `OPTIONS /*`
- Registers all route handlers
- Global exception handler returning OpenAI error format
- Custom 404 handler
- **API key enforcement** (opt-in): if `config.apiKeys()` is non-empty, a `beforeMatched` hook is registered after `Javalin.create()`. The hook skips `/health`, then checks the `Authorization: Bearer <key>` header against the key set. Invalid or missing keys get a `401` `auth_error` response and `ctx.skipRemainingHandlers()` short-circuits the request.

### Entry Point

**`AIProxyOauth.java`** — picocli `@Command` class:
1. `key generate` prints a freshly generated `sk-proxy-<32hex>` key and returns immediately.
2. `EffectiveConfigLoader` resolves CLI, `AIPROXY_*`, explicit YAML, ecosystem credential discovery, and defaults before any transport is built.
3. Builds `ServerConfig` record
4. Verifies auth file exists
5. Creates `AuthManager`, performs initial auth load
6. Creates `CodexHttpClient`, `ModelResolver`
7. Discovers models (warning on failure, not fatal)
8. Starts `ProxyServer`
9. Prints startup message with endpoint URL, available models, and key count (if enforcement is active)
10. Registers shutdown hook
11. Blocks main thread with `Thread.currentThread().join()`

Key generation uses `java.security.SecureRandom` to fill 16 bytes, then `HexFormat.of().formatHex()` (built-in since Java 17) to produce the 32-character lowercase hex suffix.

## Request Flow

### Chat Completions (non-streaming)

```
Client                    AIProxyOauth                        Upstream
  │                            │                                  │
  │  POST /v1/chat/completions │                                  │
  │  {"messages":[...]}        │                                  │
  │───────────────────────────>│                                  │
  │                            │  Translate messages → input      │
  │                            │  POST /responses {"stream":true} │
  │                            │─────────────────────────────────>│
  │                            │                                  │
  │                            │  SSE: response.output_text.delta │
  │                            │  SSE: response.completed         │
  │                            │<─────────────────────────────────│
  │                            │                                  │
  │                            │  Collect final response          │
  │                            │  Translate → chat.completion     │
  │  {"choices":[...]}         │                                  │
  │<───────────────────────────│                                  │
```

### Chat Completions (streaming)

```
Client                    AIProxyOauth                         Upstream
  │                             │                                  │
  │  POST /v1/chat/completions  │                                  │
  │  {"stream":true}            │                                  │
  │────────────────────────────>│                                  │
  │                             │  POST /responses {"stream":true} │
  │                             │─────────────────────────────────>│
  │                             │                                  │
  │  SSE: chat.completion.chunk │ SSE: response.output_text.delta  │
  │  (role:assistant)           │<─────────────────────────────────│
  │<────────────────────────────│                                  │
  │                             │                                  │
  │  SSE: chat.completion.chunk │ SSE: response.output_text.delta  │
  │  (content delta)            │<─────────────────────────────────│
  │<────────────────────────────│                                  │
  │                             │                                  │
  │  SSE: chat.completion.chunk │ SSE: response.completed          │
  │  (finish_reason:stop)       │<─────────────────────────────────│
  │<────────────────────────────│                                  │
  │  SSE: [DONE]                │                                  │
  │<────────────────────────────│                                  │
```

## Chat Completions Translation Layer

This is the core logic in `ChatCompletionsHandler`. It bridges two different API formats.

### Request Translation (Chat → Responses API)

| Chat Completions Field | Responses API Field |
|---|---|
| `messages` with `role: "system"` or `"developer"` | `instructions` parameter (concatenated) |
| `messages` with `role: "user"` | `input` item: `{type:"message", role:"user", content:[{type:"input_text", text:"..."}]}` |
| `messages` with `role: "assistant"` (text only) | `input` item: `{type:"message", role:"assistant", content:[{type:"output_text", text:"..."}]}` |
| `messages` with `role: "assistant"` + `tool_calls` | `input` items: text message + `{type:"function_call", call_id, name, arguments}` per tool call |
| `messages` with `role: "tool"` | `input` item: `{type:"function_call_output", call_id, output:"..."}` |
| `model` | `model` |
| `temperature`, `top_p` | `temperature`, `top_p` |
| `max_tokens` | `max_output_tokens` |
| `tools` (function definitions) | `tools` (same structure, wrapped in `{type:"function", ...}`) |
| `tool_choice` | `tool_choice` (passed through) |
| `reasoning_effort` | `reasoning: {effort: "..."}` |

### Upstream SSE Event → Chat Completion Chunk Translation

| Upstream SSE Event | Chat Completion Chunk |
|---|---|
| (initial) | `delta: {role: "assistant"}`, `finish_reason: null` |
| `response.output_text.delta` | `delta: {content: "..."}`, `finish_reason: null` |
| `response.output_item.added` (function_call) | `delta: {tool_calls: [{index, id, type:"function", function:{name, arguments:""}}]}` |
| `response.function_call_arguments.delta` | `delta: {tool_calls: [{index, function:{arguments:"..."}}]}` |
| `response.completed` | `delta: {}`, `finish_reason: "stop"/"tool_calls"/"length"` + usage chunk |

### Non-Streaming Response Translation

For non-streaming requests, the handler collects the completed response from the SSE stream and builds a `chat.completion` object:

- Iterates `response.output[]` items
- `type: "message"` → extracts `output_text` parts into `message.content`
- `type: "function_call"` → builds `message.tool_calls[]`
- `response.status` → mapped to `finish_reason` (`completed`→`stop`, `incomplete`→`length`)
- `response.usage` → mapped to `usage` object

## SSE Streaming

### Upstream → Proxy (parsing)

`SseParser.iterateEvents()` reads from `InputStream` line by line:
- Blank lines delimit event boundaries
- `event:` prefix → event type
- `data:` prefix → event data (multiple `data:` lines joined with `\n`)
- Accepts a `Consumer<ServerSentEvent>` callback, enabling real-time processing without buffering

### Proxy → Client (writing)

For streaming responses, the handler writes directly to `ctx.res().getOutputStream()`:
- Each chunk is formatted as `data: {json}\n\n`
- The stream is flushed after every chunk for low latency
- SSE headers are set: `Content-Type: text/event-stream`, `Cache-Control: no-cache, no-transform`, `Connection: keep-alive`, `X-Accel-Buffering: no`

## Concurrency Model

- **Virtual threads**: Javalin is configured with `useVirtualThreads = true`. Each incoming request runs on a virtual thread, allowing thousands of concurrent connections without thread pool exhaustion.
- **Auth refresh**: `AuthManager` uses `ReentrantLock` to prevent concurrent token refreshes. Only one thread performs the refresh; others wait.
- **Model cache**: `ModelResolver` uses double-checked locking with `volatile` fields and `ReentrantLock`.
- **State cache**: `ResponsesState` uses `synchronized` methods on all public operations.
- **HTTP client**: `CodexHttpClient` creates its internal `HttpClient` with a virtual thread executor.

## Design Decisions

### Why Jackson `JsonNode` instead of POJOs?

The upstream API returns dynamic, deeply nested JSON structures that vary by event type. Using `JsonNode` for dynamic manipulation avoids creating dozens of data classes and provides the flexibility needed for passthrough and transformation. Only `ServerConfig` uses a Java `record` since its shape is fixed and known at compile time.

### Why Javalin over Spring Boot / Micronaut / etc.?

Javalin is a micro-framework with minimal ceremony — no annotation scanning, no dependency injection container, no auto-configuration. It starts in milliseconds, has built-in virtual thread support, and is a natural fit for a lightweight proxy server.

### Why `maven-shade-plugin` for the fat JAR?

Shade produces a single self-contained JAR that bundles all dependencies. Users run it with a simple `java -jar` command with no classpath setup. The `ManifestResourceTransformer` sets the main class, and signature file exclusion prevents JAR signing conflicts.

### Why `InputStream`-based streaming instead of reactive streams?

Virtual threads make blocking I/O efficient. Reading from `InputStream` with `BufferedReader` is straightforward, debuggable, and doesn't require reactive programming patterns. The upstream SSE events are parsed line by line and forwarded to the client incrementally.

## Extending the Project

### Adding a new endpoint

1. Create a new `Handler` class in `com.aiproxyoauth.server`
2. Register it in `ProxyServer.java`'s constructor

### Responses replay state

The project intentionally avoids durable local Responses storage. `ResponsesState` is a bounded in-memory compatibility cache for same-process replay only. Non-streaming Responses populate the cache after `SseCollector` sees the completed response. Streaming Responses are forwarded to clients while the proxy performs bounded best-effort SSE bookkeeping for usage and replay cache population after a `response.completed` event. Do not add database-backed response/conversation emulation unless the project explicitly introduces an opt-in stateful mode with tenant scoping and privacy documentation.

### Adding request logging

Use the existing `RequestLogger` and keep logging opt-in. New log fields must pass through the same redaction path, and streaming response bodies must not be buffered solely for logs.

### Changing the upstream API

Modify `UrlResolver.resolveTargetUrl()` for path mapping changes, or override `--codex-base-url` at runtime. The `CodexHttpClient` handles all outbound requests, so changes there affect all endpoints.
