# OpenAI API compatibility

Baseline: OpenAI Chat Completions and Responses API documentation reviewed July 12, 2026.

| Behavior | Chat Completions | Responses |
|---|---|---|
| Text input/output | Translated | Forwarded |
| String `input` | N/A | Normalized to a typed user message |
| Function definitions | Translated to flat Responses tools | Forwarded |
| Named `tool_choice` | Translated from the nested Chat shape | Forwarded |
| Function-call output | Returned as `message.tool_calls` | Preserved |
| Streaming function calls | Stable indexes; delta/done/completed reconciliation | SSE bytes preserved |
| Tool result continuation | Translated from tool messages | Bounded replay or upstream state |
| Empty completed output | Recovered from prior SSE events or returned as a 502 protocol error | Preserved |

## Validation and limitations

- Responses `input` must be a string or array; other JSON types receive an OpenAI-style HTTP 400 error.
- A named Chat `tool_choice` must identify a declared function.
- Unknown Responses fields and output item types are preserved for forward compatibility.
- The upstream Codex route determines model and custom-tool availability. The proxy never invents a call when upstream returns `output: []`.
- Non-streaming Chat aggregation reconstructs text and function calls from preceding SSE events when `response.completed.output` omits them. If no usable text, tool call, or refusal exists anywhere in the stream, Chat returns `502 upstream_protocol_error` instead of a successful empty choice.
- Replay state is process-local, bounded, and isolated by API-key identity. Missing or evicted response IDs are forwarded upstream.
- `max_output_tokens` is removed because the Codex backend rejects it. Chat token-limit fields therefore cannot be guaranteed on this route.

Run `python scripts/live-compatibility.py` against a running authenticated proxy to distinguish proxy translation behavior from current backend capabilities. Select `--provider codex` or `--provider anthropic`; model defaults can be overridden with `--model`, `AIPROXY_CODEX_MODEL`, or `AIPROXY_CLAUDE_MODEL`. If `--api-key` is omitted, the runner uses `AIPROXY_API_KEY`.

Claude live checks can consume account quota and therefore require an explicit `--allow-live-claude` flag or `AIPROXY_LIVE_CLAUDE=1`. The runner validates streaming Chat text with exactly one `[DONE]`, non-streaming Responses text, a forced function call, and a `previous_response_id` continuation. It uses bounded responses and a 60-second request timeout. Offline parser/guard tests run with `python -m unittest scripts/test_live_compatibility.py` and never contact either provider.

## Anthropic protocol compatibility

The Anthropic protocol layer accepts the canonical request items below. Claude models are routed
to this layer from `/v1/chat/completions`; `/v1/responses` routing remains a later step.

| Canonical input | Anthropic Messages representation | Status |
|---|---|---|
| System/developer text | Ordered `system` text blocks after the Claude OAuth preamble | Supported |
| User/assistant text | Alternating `user`/`assistant` content blocks | Supported |
| Base64 JPEG, PNG, GIF, WebP | `image` block with a base64 source | Supported with count and decoded-byte limits |
| Remote image URL | None | Unsupported; must fail locally rather than fetch the URL |
| Assistant tool call | `tool_use` with object-valued `input` | Supported |
| Tool result | User `tool_result` block in chronological order | Supported |
| Tool definitions | `name`, `description`, and object `input_schema` | Supported |
| Auto/none/required/named tool choice | Omitted or mapped to `auto`, `any`, or `tool` | Supported |
| Temperature, top-p, stop sequences | Native Anthropic fields | Supported |
| Reasoning effort | Adaptive thinking plus `output_config.effort` | `low`, `medium`, `high`, `max` |
| Signed/redacted reasoning history | `thinking` and `redacted_thinking` blocks | Supported and log-redacted |
| OpenAI persisted-state references | None | Unsupported until locally expanded |

The incremental Anthropic decoder emits these canonical events:

| Anthropic SSE input | Canonical event |
|---|---|
| `message_start` | `Started`, followed by an optional cumulative `UsageSnapshot` |
| `ping` | `Heartbeat` |
| Text block start/delta/stop | `BlockStarted(TEXT)`, `TextDelta`, `BlockFinished` |
| Thinking block start/deltas/stop | `BlockStarted(REASONING)`, `ReasoningDelta`, `ReasoningSignature`, `BlockFinished` |
| Redacted thinking block | `BlockStarted(REDACTED_REASONING)`, `RedactedReasoning`, `BlockFinished` |
| Tool-use start/input deltas/stop | `BlockStarted(TOOL_CALL)`, `ToolCallArgumentsDelta`, `BlockFinished` |
| `message_delta` | Cumulative `UsageSnapshot` and stored finish reason |
| `message_stop` | Exactly one `Finished` |
| `error` | Exactly one typed `Error` |

Unknown future SSE events and delta types are ignored. Malformed known events, invalid UTF-8,
oversized frames, illegal block lifecycles, and streams ending before `message_stop` produce one
`502` protocol error rather than a successful partial completion. The decoder never synthesizes
response fields that are absent from Anthropic events.

### Claude through Chat Completions

- Qualified names such as `anthropic/claude-sonnet-4-5`, discovered Claude ids, aliases, and
  `claude-*` names route to Anthropic. Other unqualified names use `--default-provider`.
- Both synchronous `chat.completion` responses and streaming `chat.completion.chunk` events
  preserve the client-requested model name and expose text, tool calls, finish reason, and usage.
- Anthropic cache-read and cache-creation token counts are reported under
  `prompt_tokens_details` when present; standard prompt/completion totals remain available to the
  shared usage tracker.
- The transport retries one HTTP 401 only before a response body is exposed. A downstream stream
  is never replayed, and each accepted stream writes one `[DONE]` terminator.
- Reasoning text is represented as the compatibility extension `reasoning_content`. Signatures
  and redacted thinking are retained only in the canonical protocol layer and are not exposed in
  an OpenAI Chat response.

### Claude through Responses

Claude models also route through `/v1/responses`. This is an explicit compatibility subset rather
than a claim of complete OpenAI Responses API parity.

| Responses request input | Claude mapping | Status |
|---|---|---|
| String `input` | User text message | Supported |
| `message` with `input_text`/`output_text` | User/assistant/system/developer text | Supported |
| `message` with base64 `input_image.image_url` | Anthropic base64 image block | Supported with the same image limits as Chat |
| Remote image URL or `file_id` | None | Unsupported; fails locally |
| `function_call` | Assistant `tool_use` | Supported |
| `function_call_output` | User `tool_result` | Supported |
| `reasoning` item | Signed/redacted assistant thinking history | Supported through `reasoning_signature` and `redacted_data` extensions |
| Resolved `item_reference` | Locally cached concrete item | Supported |
| Unresolved `item_reference` | None | HTTP 400 `unsupported_provider_feature` |
| Resolved `previous_response_id` | Locally expanded input/output history | Supported within the same process and API-key namespace |
| Unresolved `previous_response_id` | None | HTTP 400 `unsupported_provider_feature` |

Supported root controls are `instructions`, flat function `tools`, `tool_choice`,
`max_output_tokens`, `temperature`, `top_p`, `stop`, `reasoning.effort`, and `stream`. The `store`
field must be boolean when supplied but is compatibility metadata only: Anthropic persistence is
not requested and the proxy keeps only its bounded in-memory replay cache.

| Canonical event | Responses streaming events | Collected output |
|---|---|---|
| `Started` | `response.created` | Response id, requested model, creation time |
| Text block lifecycle | `response.output_item.added`, `response.content_part.added`, `response.output_text.delta`, `response.output_text.done`, part/item done | Completed assistant `message` with `output_text` and empty annotations |
| Refusal block lifecycle | Refusal delta/done plus part/item lifecycle | Assistant `message` with `refusal` |
| Reasoning block lifecycle | Reasoning item added, text delta/done, item done | `reasoning` item; signature/redacted extensions when supplied upstream |
| Tool-call lifecycle | Function item added, argument delta/done, item done | Completed `function_call` with deterministic item id and Anthropic tool id as `call_id` |
| `UsageSnapshot` | Included in terminal response event | Input/output/total, cached input, and Anthropic cache-creation tokens |
| `Finished(STOP/TOOL_CALLS)` | Exactly one `response.completed` | `status=completed` |
| `Finished(LENGTH)` | Exactly one `response.incomplete` | `status=incomplete`, reason `max_output_tokens` |
| `Error` | Exactly one `response.failed` after streaming starts | OpenAI-shaped HTTP error before streaming starts |

Responses streams terminate with their single terminal response event and do not add Chat's
`[DONE]` sentinel. Output item ids are deterministic within the response but are proxy-generated
because Anthropic content blocks do not provide Responses item ids.

## Native Anthropic Messages compatibility

`POST /v1/messages` preserves Anthropic request/response semantics for official SDKs and Claude
Code gateways. The proxy makes only the compatibility mutations required for Claude Code OAuth:
it resolves the model to Anthropic, strips an `anthropic/` qualifier, prepends the pinned OAuth
system preamble, injects the proxy-owned bearer credential, and merges validated client beta
names with the required OAuth betas. Unknown body fields and future content blocks are preserved.

Synchronous JSON, Anthropic error envelopes, request/rate-limit headers, and streaming SSE bytes
are returned without OpenAI conversion. Native streams end with Anthropic's `message_stop` and
never receive `[DONE]`. Usage observation is best-effort and cannot fail or rewrite a valid native
response. Native requests require `anthropic-version: 2023-06-01`; client authentication accepts
the configured proxy key through `Authorization: Bearer` or `x-api-key`, neither of which is sent
upstream.

`GET /v1/models` returns the native Anthropic Claude-only catalog when an Anthropic version or API
key header identifies a native client; otherwise its existing OpenAI-compatible merged catalog is
unchanged. Native support currently covers Messages creation and model listing, not token counting,
batches, files, skills, agents, or sessions.
