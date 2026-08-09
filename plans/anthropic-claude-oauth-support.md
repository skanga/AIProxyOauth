# Anthropic/Claude OAuth Support Design

Status: Implementation complete — Steps 0–7 complete
Target: AIProxyOauth 1.3.x
Primary client contract: OpenAI-compatible `/v1/chat/completions`, `/v1/responses`, and `/v1/models`
Upstreams: existing ChatGPT Codex backend plus Anthropic Messages API using Claude OAuth

## 1. Objective

Add Anthropic/Claude OAuth as a second upstream provider without regressing the current Codex behavior. A client continues to use the proxy's OpenAI-compatible endpoints and selects a Claude model in the request. The proxy:

1. routes the model to Anthropic;
2. obtains and refreshes a Claude OAuth access token;
3. translates the OpenAI request into Anthropic Messages format;
4. translates Anthropic SSE events back into the requested OpenAI response format;
5. merges Claude and Codex models in `/v1/models`; and
6. keeps API-key enforcement, usage tracking, logging, error handling, and CORS behavior provider-independent.

The design uses the working Java implementation in `../ajent/ajent-provider` as the compatibility reference for Claude OAuth and Anthropic wire details. The public Anthropic documentation confirms the Messages API and Claude Code OAuth as product capabilities, but does not document every Claude Code OAuth wire constant used by the reference implementation. Those constants must therefore be isolated, pinned by tests, and easy to update.

## 2. Scope

### In scope

- Interactive PKCE login for a Claude Pro/Max or supported Anthropic account.
- Refresh-token rotation and safe credential persistence.
- `CLAUDE_CODE_OAUTH_TOKEN` as a non-refreshable override for automation.
- Claude model discovery with a configured/offline fallback.
- Model-based provider routing.
- OpenAI Chat Completions request/response compatibility for text, images, tools, tool results, sampling controls, stop sequences, streaming, non-streaming, reasoning effort, errors, and usage.
- OpenAI Responses request/response compatibility for the same core content and tool-call subset already supported by this proxy.
- Optional native Anthropic `/v1/messages` passthrough is explicitly deferred; it is not needed to make the existing proxy surface support Claude.
- Unit, protocol, integration, and live compatibility tests.

### Out of scope

- Bedrock, Vertex AI, Anthropic API-key billing, or organization admin APIs.
- Importing undocumented credentials directly from Claude Code's OS keychain.
- Persisting conversations or hidden reasoning server-side.
- Automatic failover between Codex and Claude.
- Rewriting the existing client-facing API into a generic multi-provider API.
- Claiming every Anthropic beta is stable. Beta headers remain an isolated compatibility profile.

## 3. Existing constraints

The current design is Codex-specific at four important seams:

| Seam | Current implementation | Consequence |
|---|---|---|
| Startup wiring | `AIProxyOauth` creates one `AuthManager`, one `CodexHttpClient`, and one `ModelResolver` | Startup currently requires Codex auth even if only Claude is desired |
| Transport | `CodexHttpClient` injects ChatGPT account and Responses beta headers | It cannot safely send Anthropic requests |
| Chat translation | `ChatCompletionsHandler` builds a Codex Responses request and decodes Codex Responses SSE | Provider branching here would spread through a large, protocol-specific class |
| Responses endpoint | `ResponsesHandler` assumes Codex Responses is the upstream protocol | Claude requires a request and event adapter |

The server-level concerns in `ProxyServer`, `ApiKeyStore`, `UsageTracker`, `RequestLogger`, `AccessLogFields`, and CORS configuration are already provider-neutral and should remain shared.

## 4. Reference implementation findings

The following behavior is present in `../ajent/ajent-provider` and should be ported by behavior, not copied as a coupled module:

### OAuth

- Public-client ID: `9d1c250a-e61b-44d9-88ed-5944d1962f5e`.
- Authorization endpoint: `https://claude.ai/oauth/authorize`.
- Token endpoint: `https://platform.claude.com/v1/oauth/token`.
- Redirect URI: `https://platform.claude.com/oauth/code/callback`.
- Authorization Code + PKCE S256 with a random verifier and state.
- Current scopes:
  `user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload`.
- Callback text may be returned as `code#state`; the code and returned state must be split and state must be verified locally.
- Refresh uses `application/x-www-form-urlencoded` with `grant_type=refresh_token`, client ID, and refresh token.
- Token responses supply `access_token`, optional rotated `refresh_token`, and `expires_in`.

### Anthropic wire protocol

- Messages endpoint: `https://api.anthropic.com/v1/messages?beta=true`.
- Required stable version header: `anthropic-version: 2023-06-01`.
- OAuth bearer authentication plus the `oauth-2025-04-20` beta.
- OAuth requests include the Claude Code system preamble:
  `You are Claude Code, Anthropic's official CLI for Claude.`
- Requests stream upstream even when the downstream client requested a synchronous response.
- Anthropic SSE uses `message_start`, `content_block_start`, `content_block_delta`,
  `content_block_stop`, `message_delta`, `message_stop`, `ping`, and `error`.
- Tool arguments arrive as partial JSON deltas.
- Usage can be split across the start and delta events and includes cache-read/cache-write tokens.
- Model discovery calls `GET /v1/models?limit=100` and falls back to a seed catalog if discovery fails.

### Important qualification

The OAuth client ID, scopes, OAuth beta, Claude Code preamble, and beta combinations are compatibility-sensitive, non-secret constants. Keep them in one `AnthropicCompatibilityProfile` class and cover their exact values with contract tests. Do not scatter them across handlers.

## 5. Proposed architecture

### 5.1 Provider boundary

Introduce a provider boundary above protocol-specific request translation:

```text
Javalin route
  -> OpenAI request validation
  -> ProviderRouter(model)
      -> CodexChatBackend
      -> AnthropicChatBackend
  -> canonical CompletionEvent stream
  -> OpenAI Chat or Responses encoder
  -> client
```

Use these core types:

```java
enum ProviderId { CODEX, ANTHROPIC }

record ModelRoute(
    ProviderId provider,
    String requestedModel,
    String upstreamModel,
    String reasoningEffort
) {}

interface ChatBackend {
    ProviderId provider();
    BackendResponse execute(ChatRequest request, RequestContext context) throws Exception;
}

record BackendResponse(
    int statusCode,
    Map<String, List<String>> headers,
    InputStream body,
    CompletionStreamDecoder decoder
) implements AutoCloseable {
    @Override public void close() throws IOException {
        body.close();
    }
}
```

`CompletionStreamDecoder` emits a block-aware canonical event algebra. Block identity is
required to preserve ordering, multiple thinking blocks, redacted thinking, tool calls, and
signatures when encoding either OpenAI surface:

```java
sealed interface CompletionEvent {
    record Started(String id, String model, long createdEpochSeconds) implements CompletionEvent {}
    record BlockStarted(int index, BlockType type, String id, String name) implements CompletionEvent {}
    record TextDelta(int index, String text) implements CompletionEvent {}
    record ReasoningDelta(int index, String text) implements CompletionEvent {}
    record ReasoningSignature(int index, String signature) implements CompletionEvent {}
    record RedactedReasoning(int index, JsonNode data) implements CompletionEvent {}
    record ToolCallArgumentsDelta(int index, String json) implements CompletionEvent {}
    record BlockFinished(int index) implements CompletionEvent {}
    record UsageSnapshot(long input, long output, long cacheWrite, long cacheRead)
        implements CompletionEvent {}
    record Finished(FinishReason reason) implements CompletionEvent {}
    record Error(int status, String type, String message) implements CompletionEvent {}
    record Heartbeat() implements CompletionEvent {}
}
```

`UsageSnapshot` is cumulative, never a delta. Collectors replace each component with the newest
value and call `UsageTracker.record` exactly once after terminal success. Retries and failed
streams are not double-counted.

This algebra is intentionally smaller than either upstream protocol but retains content-block
lifecycle and ordering. Provider-specific raw JSON must not leak into the generic layer except
for an opaque redacted-reasoning payload required for lossless continuation.

### 5.2 Migration strategy

Do not rewrite `ChatCompletionsHandler` in one change. First extract its existing Codex SSE interpretation and OpenAI output construction behind the canonical events while keeping golden tests unchanged. Then add Anthropic as a second producer of the same events.

If the Codex extraction proves too risky for the first merge, use a transitional router:

- rename the current class to `CodexChatCompletionsHandler`;
- add `AnthropicChatCompletionsHandler`;
- make `ChatCompletionsHandler` route between them;
- extract shared OpenAI encoders in the following step.

The transitional approach is preferred over adding `if (provider == ANTHROPIC)` throughout the current handler.

### 5.3 Shared versus provider-specific responsibilities

| Shared | Codex-specific | Anthropic-specific |
|---|---|---|
| Client authentication | ChatGPT token/account headers | Claude PKCE and token refresh |
| Request ID/access log | Responses request body | Messages request body |
| Full request logging/redaction | Responses SSE decoder | Anthropic SSE decoder |
| OpenAI response encoders | Codex model aliases/effort clamp | Claude model aliases/effort mapping |
| Usage tracker | Codex model discovery | Anthropic model discovery |
| Provider/model router | Codex instructions | OAuth preamble and beta profile |
| Error envelope | Codex usage-limit mapping | Anthropic error mapping |

## 6. Configuration and CLI

### 6.1 New options

| Option | Default | Purpose |
|---|---|---|
| `--providers <ids>` | `codex,anthropic` when both credentials exist; otherwise available provider | Enable an explicit provider set |
| `--default-provider <id>` | first enabled provider, preferring `codex` | Resolves unqualified/ambiguous configured aliases |
| `--anthropic-oauth-file <path>` | platform config dir `AIProxyOauth/anthropic-auth.json` | Credential store |
| `--anthropic-base-url <url>` | `https://api.anthropic.com` | Test/gateway override |
| `--anthropic-token-url <url>` | platform default | OAuth test override |
| `--anthropic-models <ids>` | discover, then seed fallback | Explicit Claude catalog |
| `--anthropic-login` | false | Run interactive login and exit |
| `--anthropic-logout` | false | Remove only the proxy's Anthropic credential file and exit |
| `--anthropic-max-tokens <n>` | `8192` | Default when the OpenAI request omits a limit |

Keep existing Codex option names working. A later breaking release may rename generic-looking options such as `--base-url` to `--codex-base-url`; for this feature, add aliases and deprecation messages rather than breaking scripts.

### 6.2 Environment precedence

For Anthropic:

1. `CLAUDE_CODE_OAUTH_TOKEN` — access-token override, treated as non-expiring and non-refreshable.
2. `--anthropic-oauth-file`.
3. Default proxy-owned credential file.

Do not silently read `ANTHROPIC_API_KEY` in the OAuth-only milestone; that changes the billing/auth mode and wire behavior. API-key support can be added explicitly later.

### 6.3 Startup behavior

- A missing Codex credential disables Codex if Anthropic is configured; it no longer terminates the process.
- A missing Anthropic credential disables Anthropic if Codex is configured.
- Startup fails only when no enabled provider has usable credentials.
- If `--default-provider` was explicitly set to a disabled provider, startup fails validation.
  Otherwise the effective default is selected from enabled providers, preferring Codex.
- An explicitly listed but uncredentialed provider is a configuration error; implicit
  auto-detection may disable an unavailable provider.
- Print one line per provider: enabled/disabled, credential source, and model-discovery result. Never print token fragments.
- Run the startup probe only against the effective enabled default provider. Add
  `--startup-probe-provider` only if operational demand appears.
- `GET /health` remains liveness-only. A future authenticated readiness endpoint may expose provider status.

## 7. OAuth design

### 7.1 Components

Add:

- `anthropic/auth/AnthropicOAuthLogin`
- `anthropic/auth/AnthropicOAuthClient`
- `anthropic/auth/AnthropicCredential`
- `anthropic/auth/AnthropicCredentialStore`
- `anthropic/auth/AnthropicAuthManager`
- `anthropic/AnthropicCompatibilityProfile`

`AnthropicCredential`:

```java
record AnthropicCredential(
    String accessToken,
    String refreshToken,
    Instant expiresAt,
    Instant updatedAt
) {}
```

Credential file:

```json
{
  "version": 1,
  "provider": "anthropic",
  "access_token": "...",
  "refresh_token": "...",
  "expires_at": "2026-07-30T03:00:00Z",
  "updated_at": "2026-07-30T02:00:00Z"
}
```

### 7.2 Interactive login

`--anthropic-login` performs:

1. Generate a 128-character PKCE verifier and 32-character state with `SecureRandom`.
2. Derive the base64url-without-padding SHA-256 challenge.
3. Print the authorization URL and attempt to open it only when desktop browsing is supported.
4. Read the returned `code#state` from `System.console()`. Never accept the code as a normal command-line argument because process lists and shell history expose it.
5. Split the returned value, compare state using a constant-time byte comparison, and reject mismatch.
6. Exchange the code with a 30-second timeout and a genuinely bounded 1 MiB body subscriber.
   Reject an excessive `Content-Length` before reading and cancel a chunked response after
   reading N+1 bytes; do not use `BodyHandlers.ofByteArray()` followed by a size check.
7. Compute `expiresAt = now + expires_in`, requiring a positive bounded value.
8. Save atomically and with owner-only permissions where supported.
9. Reload the saved file and report success without displaying secrets.

If `System.console()` is unavailable, print a clear error and allow code input from stdin only with an explicit `--allow-stdin-oauth-code` flag.

### 7.3 Refresh policy

`AnthropicAuthManager` mirrors the good concurrency property of the existing `AuthManager` but uses explicit expiry metadata rather than JWT parsing:

- fast path reads a volatile immutable credential;
- refresh when expiration is within five minutes;
- serialize refresh through a `ReentrantLock`;
- recheck after acquiring the lock;
- preserve the existing refresh token when a successful response omits a new one;
- publish a successfully refreshed access token and rotated refresh token together in memory;
- then atomically persist the same immutable credential;
- if persistence fails, mark authentication degraded, retain the new in-memory credential
  (the old refresh token may already be invalid), and retry persistence on later requests;
- if refresh fails and the old token is still valid, use it until expiry;
- if expired, return a typed authentication failure;
- after an upstream 401, invalidate and perform at most one refresh/retry, and only before response bytes have been sent downstream.

No background refresh thread is required. Request-driven refresh keeps lifecycle and shutdown simple.
Credential files are single-process resources. Acquire an OS file lock, reload under that lock,
and atomically replace during refresh/login/logout. If reliable locking is unavailable, fail with
a clear “credential file already in use” error instead of allowing two processes to rotate the
same refresh token.

### 7.4 Secret handling

- Add `access_token`, `refresh_token`, OAuth `code`, `code_verifier`, and reasoning signatures
  to recursive JSON/body redaction tests.
- OAuth request/response bodies must never use full request logging.
- Redact sensitive keys recursively in inbound, upstream, error, and debug JSON. For malformed
  or non-JSON bodies on an auth path, suppress the whole body.
- Use atomic replace and owner-only permissions as in the current Codex auth persistence.
- On Windows, document that POSIX mode bits are unavailable; use the user profile config directory and avoid claiming encryption.
- `--anthropic-logout` deletes only the resolved Anthropic credential file after showing its exact path and requiring an interactive confirmation unless `--yes` is present.

## 8. Model routing and discovery

### 8.1 Routing rules

Routing must be deterministic:

1. Exact match in the merged model catalog.
2. Explicit provider-qualified alias such as `anthropic/sonnet` or `codex/gpt-5.5`.
3. `claude-*` routes to Anthropic.
4. `gpt-*` and `codex-*` route to Codex.
5. Any other configured alias resolves through `--default-provider`.
6. Ambiguous catalog entries fail with HTTP 400 and list qualified alternatives.

Provider prefixes are removed before sending upstream. Return the originally requested model in downstream responses for client consistency, while logging the upstream model separately.

### 8.2 Claude aliases

Keep aliases in configuration or a small data table:

- `anthropic/opus`
- `anthropic/sonnet`
- `anthropic/haiku`

Resolve aliases to discovered concrete IDs. Do not hardcode “latest” dated model IDs throughout the code. If an alias cannot be resolved from discovery, use the tested seed catalog and mark the model source as `fallback`.

### 8.3 Discovery

`AnthropicModelResolver`:

- calls `GET /v1/models?limit=100`;
- supplies bearer auth, stable version, browser-access header, app ID, and OAuth beta;
- enforces 10-second timeout and 1 MiB response bound;
- parses `data[].id` and `data[].display_name`;
- caches success for five minutes;
- on failure, uses the last good result, then `--anthropic-models`, then a versioned seed catalog;
- never lets one provider's discovery failure erase the other provider's catalog.
- uses the same bounded streaming reader as OAuth; a post-allocation byte-array check is not
  considered a response limit.

`CompositeModelResolver` merges provider models and supplies `owned_by` as `codex-oauth` or `anthropic-oauth`.

## 9. Anthropic request translation

Create an internal `ChatRequest` parsed once from the OpenAI request. Apply common JSON/schema
validation, select the provider, then apply provider-specific semantic validation. Image modes,
reasoning, model token limits, tool-choice support, and state references must not be rejected by
the common layer merely because one backend lacks them.

### 9.1 Field mapping

| OpenAI field | Anthropic Messages field/behavior |
|---|---|
| `model` | routed upstream model ID |
| system/developer messages | ordered `system` text blocks after OAuth preamble |
| user text | user `text` block |
| user image content | Anthropic `image` source; support bounded base64 data URLs first |
| assistant text | assistant `text` block |
| assistant `tool_calls` | assistant `tool_use` blocks |
| `tool` role | user `tool_result` block keyed by `tool_call_id` |
| `tools[].function` | `tools[]` with `name`, `description`, `input_schema` |
| `tool_choice: auto` | `{"type":"auto"}` |
| `tool_choice: none` | omit tools or use supported `none` behavior after contract test |
| `tool_choice: required` | `{"type":"any"}` |
| named function choice | `{"type":"tool","name":"..."}` |
| `max_tokens` / `max_completion_tokens` | `max_tokens`; otherwise configured default |
| `temperature` | `temperature` |
| `top_p` | `top_p` |
| `stop` | `stop_sequences` |
| `reasoning_effort` | adaptive thinking plus `output_config.effort`, only for supported models |
| `stream` | always true upstream; controls only downstream collection |

### 9.2 Message normalization

Anthropic requires alternating user/assistant messages and embeds tool results in user content. The translator must:

- preserve content block order;
- merge adjacent messages with the same effective role;
- preserve chronology: a tool result must follow its matching tool-use turn and precede the
  next unrelated assistant turn; merge only consecutive tool-result messages into the following
  effective user message;
- reject duplicate tool-call IDs and orphan tool results with HTTP 400;
- preserve multiple tool results in one user content array;
- serialize tool input as JSON objects, returning a protocol error if arguments are invalid JSON;
- never send OpenAI's `tool` role directly upstream;
- preserve empty assistant text when tool-use blocks are present;
- reject histories that would require moving a tool result backward across another turn;
- reject unsupported remote image URLs initially with a precise 400 rather than fetching arbitrary URLs server-side;
- allow only Anthropic-supported image media types, validate base64 strictly, and enforce
  configured per-image, image-count, and aggregate decoded-byte limits before materializing
  decoded content.

### 9.3 OAuth compatibility profile

For OAuth requests, `AnthropicRequestBuilder` adds:

- `Authorization: Bearer ...`;
- `anthropic-version: 2023-06-01`;
- `anthropic-beta` values selected by the compatibility profile;
- `anthropic-dangerous-direct-browser-access: true`;
- proxy-specific `user-agent` and `x-app`;
- the Claude Code OAuth system preamble;
- `stream: true`;
- a stable, non-personal `metadata.user_id` derived from a random installation ID, not raw host/user identifiers.

Prompt caching, one-million-token context, fine-grained tool streaming, and context-management betas must be individually feature-gated. The baseline OAuth milestone enables only the betas required for OAuth and the selected request feature.

Persist the non-secret installation ID in a small proxy settings file owned by a
`ProxySettingsStore`. Create it atomically, inject it in tests, and keep its lifecycle separate
from OAuth credentials. If metadata is not required by the tested OAuth contract, omit
`metadata.user_id` rather than regenerating it each process start.

## 10. Anthropic stream decoding

Implement an incremental byte-oriented decoder. Do not assume network reads align with UTF-8 characters, lines, or SSE events.

State tracked per response:

- terminal event emitted;
- current content-block index, type, and stable identity;
- open tool-call ID/name;
- accumulated partial tool JSON for synchronous collection;
- stop reason;
- message ID/model;
- latest input/output/cache usage totals.

Event mapping:

| Anthropic event | Canonical event |
|---|---|
| `message_start` | `Started`, initial `Usage` |
| `ping` | `Heartbeat` |
| any supported block start | indexed `BlockStarted` |
| text block + `text_delta` | indexed `TextDelta` |
| thinking/signature delta | indexed `ReasoningDelta` / `ReasoningSignature` |
| redacted thinking block | indexed opaque `RedactedReasoning` |
| `input_json_delta` | `ToolCallArgumentsDelta` |
| content block stop | indexed `BlockFinished` |
| `message_delta` | updated cumulative `UsageSnapshot`, remembered finish reason |
| `message_stop` | exactly one `Finished` |
| `error` | exactly one `Error`, terminal |
| unknown event | ignored and optionally debug-logged |

EOF before `message_stop` is a typed truncation error, never a successful unspecified finish.
For a synchronous client it becomes HTTP 502; for a stream whose headers were already sent it
becomes the documented in-band error followed by exactly one terminal chunk and `[DONE]`.
Malformed JSON for a known event is likewise an upstream protocol error, not silently converted
to success. Tests must include EOF mid-frame, EOF after a complete block but before
`message_stop`, and EOF mid-tool JSON.

## 11. OpenAI response encoding

### 11.1 Chat Completions streaming

- Emit an initial assistant-role chunk.
- Text becomes `choices[0].delta.content`.
- Tool starts/deltas become indexed `choices[0].delta.tool_calls`.
- `end_turn` and `stop_sequence` map to `stop`.
- `tool_use` maps to `tool_calls`.
- `max_tokens` maps to `length`.
- Emit exactly one non-null `finish_reason`, then optional usage chunk when requested, then `[DONE]`.
- Heartbeats may be emitted as SSE comments, not fake OpenAI chunks.
- After downstream headers/body begin, an upstream error is represented as an SSE error object followed by a terminal chunk and `[DONE]`; it cannot change the HTTP status.

### 11.2 Chat Completions synchronous

Collect canonical events with bounded memory:

- cap aggregate response and tool-argument bytes;
- require valid final tool JSON;
- assemble text and tool calls in original block order;
- return an OpenAI `chat.completion`;
- reject a successful but empty completion with `502 upstream_protocol_error`, matching current behavior.

### 11.3 Reasoning

Reasoning is off unless `reasoning_effort` is explicitly requested or supplied by a Claude alias.

For Chat Completions, expose non-standard but common extension fields:

- streaming: `delta.reasoning_content`;
- synchronous: `message.reasoning_content`;
- signature: `message.reasoning_signature`.

Accept block-indexed reasoning/signature extension fields on assistant history so a capable
client can round-trip multiple signed or redacted thinking blocks in order. A convenience
single-string field may be emitted only when there is exactly one thinking block. If a request
enables thinking but omits a required prior signature, return a precise 400 or disable thinking
according to model rules; never combine blocks or fabricate a signature.

For Responses, map thinking to reasoning items when the existing schema can represent it. Document any loss of signature round-tripping in `COMPATIBILITY.md`.

### 11.4 Usage

Map:

- `prompt_tokens = input_tokens`;
- `completion_tokens = output_tokens`;
- `total_tokens = input + output`;
- `prompt_tokens_details.cached_tokens = cache_read_input_tokens`;
- provider extension `usage.anthropic.cache_creation_input_tokens`.

The final cumulative snapshot replaces earlier snapshots; it is not summed with them.
`UsageTracker` records exactly once after terminal success. Partial failed generations are
excluded for backward compatibility. Provider/model dimensions may be added separately.

## 12. Responses API

The Responses endpoint should route Claude models rather than forward them to Codex. Preserve
the existing Codex raw-forwarding path unchanged. Claude Responses compatibility is a separate,
explicit adapter and must not be described as full parity with the upstream OpenAI Responses API.

Implement two adapters around the same `ChatRequest` and canonical event stream:

1. `ResponsesRequestAdapter` converts supported Responses input items into `ChatRequest`.
2. `ResponsesEventEncoder` converts canonical events into Responses SSE or a collected response object.

Supported initial item types:

- `message` with `input_text` and supported image inputs;
- `function_call`;
- `function_call_output`;
- function tools and tool choice;
- system instructions;
- reasoning effort;
- sampling/token limits where both APIs support them.

Before implementation, add an exact matrix to `COMPATIBILITY.md` for accepted input item types
and emitted event types. Golden fixtures must define IDs, content-part lifecycle/status,
response metadata, incomplete details, annotations, function-call ordering, and terminal event
sequence. Fields that cannot be reconstructed from the canonical stream are documented as
unsupported rather than silently synthesized with misleading values.

Stateful Codex-only features require explicit handling:

- `previous_response_id` and unresolved `item_reference` are not sent to Anthropic;
- if the local replay cache can expand them into concrete items, use the expanded input;
- otherwise return HTTP 400 `unsupported_provider_feature`;
- `store` remains a downstream compatibility field and has no Anthropic persistence effect.

This explicit failure is safer than silently dropping conversation state.

## 13. Error mapping and retry

Normalize Anthropic errors into the proxy's OpenAI-shaped error envelope:

| Anthropic condition | Downstream status/type |
|---|---|
| invalid request | 400 `invalid_request_error` |
| invalid/expired OAuth after one refresh | 401 `authentication_error` |
| permission/plan restriction | 403 `permission_error` |
| not found/model unavailable | 404 `not_found_error` |
| rate limit or usage limit | 429 `rate_limit_error` |
| request too large | 413 `request_too_large` |
| overload | 529 or normalized 503 `overloaded_error` (choose and test one policy) |
| timeout/network failure before response | 502/504 `upstream_error` |
| malformed/truncated successful stream | 502 `upstream_protocol_error` |

Retry policy:

- one refresh/retry on 401 before streaming begins;
- honor `Retry-After` for client visibility but do not automatically retry 429;
- optionally retry one idempotent, not-yet-streamed request on 529 with jitter in a later milestone;
- never retry after any response byte has reached the client;
- never switch providers automatically.

## 14. Observability

Add structured access-log fields:

- `provider=codex|anthropic`;
- `requested_model`;
- `upstream_model`;
- existing request ID, stream mode, status, and byte counts.

Full request logging:

- continue redacting authorization and credential-like headers;
- redact OAuth form bodies entirely;
- identify the provider in metadata;
- bound logged upstream error bodies;
- never log reasoning signatures because they are continuation credentials for signed thinking;
- use a recursive JSON redactor for request, upstream, response, error, and debug bodies;
- suppress auth-path bodies entirely when parsing/redaction is uncertain.

Startup and model-discovery warnings should distinguish authentication, transport, invalid response, and fallback-catalog states.

## 15. Testing strategy

### 15.1 OAuth tests

- Exact authorization URL and percent encoding.
- PKCE challenge known vector.
- Cryptographically random verifier/state length and alphabet.
- State mismatch rejection.
- `code#state` parsing.
- Exact authorization-code and refresh forms.
- Token rotation and preservation when refresh token is omitted.
- Expiry margin behavior.
- Single-flight refresh under concurrent virtual threads.
- Refresh failure with valid old token versus expired token.
- 1 MiB response limit, malformed JSON, missing access token, timeout, interruption.
- oversized `Content-Length` and oversized chunked bodies aborted at N+1 bytes.
- Atomic store, strict permissions where supported, corrupted file, and no secret in exception text/logs.
- file-lock contention and two-manager reload-under-lock behavior.

### 15.2 Wire request tests

Golden JSON/header tests for:

- OAuth preamble and beta profile;
- system/developer ordering;
- adjacent role merging;
- text and base64 images;
- assistant tool calls and user tool results;
- multiple parallel tool calls;
- all tool-choice modes;
- sampling, stop, max tokens, and reasoning;
- orphan/duplicate/invalid tool calls;
- disabled feature betas absent from baseline requests.

### 15.3 Stream decoder tests

Feed each fixture:

- as one byte array;
- one byte at a time;
- split at every UTF-8 byte, newline, and SSE frame boundary;
- with CRLF and multi-line `data:` fields.

Cover text, thinking/signature, tool JSON deltas, usage/cache tokens, heartbeats, every stop reason, unknown future events, malformed events, explicit error, premature EOF, and exactly-once terminal behavior.

### 15.4 Handler tests

Use a local `HttpServer`, as existing transport tests do:

- Claude model routes only to Anthropic.
- Codex requests remain structurally unchanged against checked-in golden fixtures.
- Streaming and synchronous Chat responses.
- Streaming and synchronous Responses output.
- 401 refresh/retry occurs once.
- 429/529/error bodies map correctly.
- cumulative usage is recorded once and attributed to the client API key.
- one provider can start and serve when the other lacks credentials.
- merged `/v1/models` survives either discovery failure.

Check in redacted Codex and Anthropic golden fixtures before refactoring. Prefer structural JSON
assertions plus hashes for raw SSE fixtures over a claim of byte-for-byte stability with no
recorded baseline. Tests for secret absence must capture actual log files, stderr, debug sinks,
and exception text.

### 15.5 Live compatibility script

Extend `scripts/live-compatibility.py` with opt-in Claude cases guarded by `AIPROXY_TEST_ANTHROPIC=1`:

- text sync/stream;
- one tool call and tool-result continuation;
- usage;
- reasoning when the account/model supports it;
- model listing.

Never run OAuth login or destructive logout from automated tests.
Live reasoning is conditional and records a skip reason when the selected account/model does
not advertise support; it is not a mandatory release invariant.

## 16. Delivery plan

Each step is sized as an independently reviewable change. Existing tests must pass after every step.

### Step 0 — Freeze shared contracts and compatibility profile (complete)

Dependencies: none

Context brief:
The provider router, transports, and wire decoders must agree on immutable shared request,
content-block event, usage-snapshot, error, and compatibility-profile contracts before parallel
work begins.

Tasks:

- Add `ProviderId`, shared request/value types, block-aware `CompletionEvent`, cumulative usage
  semantics, and typed provider errors.
- Add the versioned `AnthropicCompatibilityProfile`.
- Add adapter tests proving current Codex behavior can be represented without loss.
- Document ownership: closing a backend response closes its body; a decoder never owns transport.

Verification:

```bash
mvn test -Dtest=CompletionEventTest,AnthropicCompatibilityProfileTest
mvn test
```

Exit criteria:

- Event and usage semantics are frozen and compile.
- Known Codex text/tool/usage fixtures map without ambiguity.
- Steps 1–4 implement against the same checked-in contracts.

Rollback:
The unused contract types can be removed without runtime or persistence changes.

### Step 1 — Introduce provider identity and composite model routing (complete)

Dependencies: Step 0
Can run in parallel with: Step 2 after interfaces are agreed

Context brief:
The current `ModelResolver` and startup wiring assume Codex. Add provider-aware model metadata and deterministic routing without changing handler behavior yet.

Tasks:

- Add `ProviderId`, `ProviderModel`, `ModelRoute`, and `ProviderRouter`.
- Wrap the current resolver as the Codex catalog.
- Add `CompositeModelResolver`.
- Keep `/v1/models` response backward-compatible while setting provider-specific `owned_by`.
- Add routing/ambiguity/fallback tests.

Verification:

```bash
mvn test -Dtest=ModelResolverTest,ModelAliasResolverTest,ModelsHandlerTest,ProviderRouterTest
mvn test
```

Exit criteria:

- All current models still route to Codex.
- Qualified and `claude-*` routing is unit-tested.
- No Anthropic network call exists yet.

Rollback:
Remove the provider wrapper and restore direct `ModelResolver` injection; no persisted data is introduced.

### Step 2 — Add Claude PKCE login, credential store, and refresh manager (complete)

Dependencies: Step 0 and compatibility-profile values approved
Can run in parallel with: Step 1

Context brief:
Port the behavior of Ajent's OAuth classes into proxy-owned components, adapting persistence to this application's conventions and treating the OAuth constants as a versioned compatibility profile.

Tasks:

- Add compatibility profile, PKCE login, token client, credential record/store, and auth manager.
- Add CLI login/logout and environment override.
- Make OAuth HTTP timeouts and endpoints injectable for tests.
- Add redaction coverage before enabling login.

Verification:

```bash
mvn test -Dtest=AnthropicOAuthLoginTest,AnthropicOAuthClientTest,AnthropicCredentialStoreTest,AnthropicAuthManagerTest,RequestLoggerTest
mvn test
```

Exit criteria:

- Login can create a reloadable, exclusively locked credential file.
- Concurrent refresh issues one token request.
- Tests capture actual log files, stderr, debug sinks, and exception text and prove secrets never
  appear.

Rollback:
Remove the new credential file manually or through the new logout command; Codex auth remains untouched.

### Step 3 — Add Anthropic transport and model discovery (complete)

Dependencies: Steps 0–2

Context brief:
Create a dedicated transport because Anthropic and Codex require different authentication and protocol headers. Reuse only HTTP-client construction, logging, response bounds, and request IDs.

Tasks:

- Add `AnthropicHttpClient`.
- Add `AnthropicModelResolver` with last-good/configured/seed fallback.
- Wire provider-optional startup.
- Merge model catalogs and provider status in the startup banner.
- Add typed Anthropic error parsing.

Verification:

```bash
mvn test -Dtest=AnthropicHttpClientTest,AnthropicModelResolverTest,CompositeModelResolverTest,AIProxyOauthTest
mvn test
```

Exit criteria:

- `/v1/models` contains both catalogs when both providers are available.
- Startup succeeds with only one credential type.
- Discovery failures are bounded and use a visible fallback.

Rollback:
Disable Anthropic in `--providers`; no request handler routes to it yet.

### Step 4 — Add request translation and Anthropic SSE decoder (complete)

Dependencies: Steps 0–2
Can run in parallel with: Step 3 after shared request/event interfaces land

Context brief:
Build pure protocol components before integrating Javalin. This is the highest-risk correctness step and should use golden fixtures from Ajent plus locally captured, redacted fixtures where permitted.

Tasks:

- Add internal `ChatRequest`.
- Add OpenAI-to-Anthropic message/tool translator.
- Add compatibility-profile header/body builder.
- Add incremental SSE framer and `AnthropicStreamDecoder`.
- Add canonical events and finish/usage mappings.

Verification:

```bash
mvn test -Dtest=AnthropicRequestTranslatorTest,AnthropicWireTest,AnthropicStreamDecoderTest
mvn test
```

Exit criteria:

- Byte-split decoder tests pass, including truncated and malformed known-event fixtures.
- Golden wire tests cover every supported request field.
- Invalid tool histories fail locally with typed 400 errors.

Rollback:
Pure unused components can be reverted without behavior or data migration.

### Step 5 — Route Chat Completions to Claude

Dependencies: Steps 0–4

Context brief:
Integrate the Anthropic backend while preserving the existing Codex path. Prefer a thin routing handler and shared OpenAI event encoder over provider branches in the Codex translator.

Tasks:

- Extract or wrap the current Codex handler behind `ChatBackend`.
- Add `AnthropicChatBackend`.
- Add streaming and synchronous OpenAI encoders.
- Implement one pre-stream 401 refresh/retry.
- Record provider-aware logs and standard usage.
- Keep startup probe provider-selectable through the default-provider setting.

Verification:

```bash
mvn test -Dtest=ChatCompletionsHandlerTest,AnthropicChatCompletionsHandlerTest,HandlersTest,ProxyServerTest
mvn test
```

Exit criteria:

- Existing Codex Chat golden tests are unchanged.
- Claude text and tool calls work in sync and streaming modes.
- Every stream terminates exactly once.
- No retry occurs after downstream bytes are written.

Rollback:
Disable Anthropic routing and retain the credential/catalog code for a later release.

### Step 6 — Route Responses API to Claude

Dependencies: Step 5

Context brief:
Reuse the same backend and canonical events. Only the downstream request and event encoders differ. Explicitly reject unexpandable Codex state references for Claude.

Tasks:

- Add `ResponsesRequestAdapter`.
- Add streaming and collected Responses encoders.
- Map function calls/results and reasoning.
- Enforce provider-specific restrictions for `previous_response_id`, `item_reference`, and `store`.
- Add the exact supported Responses input/event/output matrix and golden fixtures.

Verification:

```bash
mvn test -Dtest=ResponsesHandlerTest,AnthropicResponsesHandlerTest,ResponsesStateTest
mvn test
```

Exit criteria:

- Claude works through both public OpenAI-compatible endpoints.
- Unsupported stateful features fail explicitly.
- Chat and Responses usage totals agree for equivalent fixtures.

Rollback:
Return `unsupported_provider_feature` for Claude models only on `/v1/responses`; Chat support remains available.

### Step 7 — Documentation, live verification, and release gate

Dependencies: Steps 1–6

Context brief:
Finish operator-facing documentation and verify the complete flow without making live OAuth a normal unit-test dependency.

Tasks:

- Update README, developer guide, compatibility matrix, CLI help, and security notes.
- Add opt-in live Claude cases.
- Build the fat JAR and test startup with Codex-only, Claude-only, and dual-provider configurations.
- Record compatibility-profile constants and their Ajent source revision in release notes.

Verification:

```bash
mvn clean package
python scripts/live-compatibility.py
```

Run the Claude live suite only with explicit credentials and opt-in environment configuration.

Exit criteria:

- Clean build and complete unit suite pass.
- Live text stream and tool round-trip pass for both providers.
- Documentation explains login, routing, credential location, limitations, and logout.

Rollback:
Release with Anthropic disabled by default if live compatibility fails; do not weaken protocol tests to ship.

## 17. Dependency graph and parallelism

```text
Step 0 shared contracts/profile
        ├─> Step 1 provider routing ─┐
        └─> Step 2 OAuth/auth ───────┼─> Step 3 transport/discovery ─┐
                                     └─> Step 4 wire/decoder ────────┤
                                                                    v
                                                               Step 5 Chat
                                                                    │
                                                                    v
                                                               Step 6 Responses
                                                                    │
                                                                    v
                                                               Step 7 release
```

Steps 1 and 2 can proceed in parallel after Step 0 freezes public interfaces. Step 4 can begin
alongside Step 3 after Steps 1 and 2 land. Steps 5–7 are serial because each depends on the
previous public behavior.

## 18. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Undocumented OAuth constants change | Login or inference stops working | Isolated versioned profile, exact contract tests, clear auth diagnostics |
| Claude OAuth terms or entitlement differ by account | 401/403 despite valid login | Preserve upstream error class/message safely; document account prerequisites |
| Huge refactor regresses Codex | Existing users break | Transitional router, unchanged Codex golden tests, small merges |
| SSE boundary bugs | Corrupt Unicode/tool JSON or hanging clients | Incremental byte tests and exactly-once terminal invariant |
| Reasoning signatures are lost | Multi-turn thinking request fails | Opt-in reasoning, extension fields, explicit compatibility limits |
| Ambiguous model aliases | Wrong account/provider billed | Deterministic catalog routing and qualified aliases |
| Token leakage in logs/history | Account compromise | Console input, whole-body OAuth redaction, strict persistence |
| Model discovery outage | Claude disappears from `/v1/models` | Last-good cache, configured list, versioned seed |
| Retry duplicates work | Duplicate inference/tool intent | Retry only pre-stream on authentication failure; no cross-provider failover |
| Beta header accumulation | Unexpected API behavior | Feature-gated beta builder and minimal baseline |

## 19. Anti-patterns to reject in review

- Adding Anthropic branches throughout `ChatCompletionsHandler`.
- Reusing `CodexHttpClient` with different headers.
- Selecting a provider only by string prefix when an exact catalog route exists.
- Treating the Anthropic access token as a JWT.
- Passing OAuth codes or refresh tokens as normal CLI arguments.
- Logging OAuth form bodies under full request logging.
- Refreshing independently on every virtual thread.
- Buffering all streaming responses without size limits.
- Calling `BodyHandlers.ofByteArray()` and checking the limit only after allocation.
- Parsing tool JSON before all partial deltas arrive.
- Treating EOF without `message_stop` as successful completion.
- Summing cumulative Anthropic usage snapshots.
- Reordering tool results to repair an invalid conversation history.
- Silently dropping unsupported Responses state references.
- Enabling every beta copied from another client for every model.
- Hardcoding a single dated Claude model as “latest.”
- Automatically failing over to another provider and account.

## 20. Acceptance criteria

The feature is complete when:

1. A user can run `--anthropic-login`, complete PKCE, and start the proxy with only Claude credentials.
2. `/v1/models` reports Claude models with `owned_by: anthropic-oauth` and continues reporting Codex models when available.
3. OpenAI clients can make synchronous and streaming Claude Chat Completions with text and tools.
4. Supported Responses requests work with Claude; unsupported state references return a documented 400.
5. Tokens refresh once under concurrent load and rotate safely on disk.
6. Anthropic and Codex streams both produce exactly one terminal result and correct usage.
7. Existing Codex tests remain green.
8. No access token, refresh token, OAuth code, verifier, API key, or reasoning signature appears in logs.
9. A provider outage or missing credential does not disable the other provider.
10. The compatibility profile is documented, contract-tested, and replaceable without handler changes.

## 21. Source anchors

Local implementation references:

- `../ajent/ajent-provider/src/main/java/com/github/skanga/ajent/provider/auth/AnthropicOAuthLogin.java`
- `../ajent/ajent-provider/src/main/java/com/github/skanga/ajent/provider/auth/AnthropicOAuthClient.java`
- `../ajent/ajent-provider/src/main/java/com/github/skanga/ajent/provider/auth/CredentialStore.java`
- `../ajent/ajent-provider/src/main/java/com/github/skanga/ajent/provider/anthropic/AnthropicWire.java`
- `../ajent/ajent-provider/src/main/java/com/github/skanga/ajent/provider/anthropic/AnthropicMessages.java`
- `../ajent/ajent-provider/src/main/java/com/github/skanga/ajent/provider/anthropic/AnthropicStreamDecoder.java`
- `../ajent/ajent-provider/src/main/java/com/github/skanga/ajent/provider/ProviderModelCatalog.java`

Official background:

- Anthropic Claude Code setup and authentication:
  https://docs.anthropic.com/en/docs/claude-code/getting-started
- Anthropic LLM gateway/auth header behavior:
  https://docs.anthropic.com/en/docs/claude-code/llm-gateway
- Anthropic tool-use message structure:
  https://docs.anthropic.com/en/docs/agents-and-tools/tool-use/implement-tool-use

## 22. Plan mutation protocol

If implementation evidence invalidates an assumption:

1. Record the failed assumption and fixture or upstream response that disproved it.
2. Update the compatibility profile or mapping table first.
3. Split a delivery step if its rollback boundary is no longer independent.
4. Do not reorder a dependent step until its new prerequisite tests exist.
5. Mark intentionally deferred behavior in `COMPATIBILITY.md`; do not silently omit it.
6. Re-run the full Codex suite after every plan mutation affecting shared events or encoders.
