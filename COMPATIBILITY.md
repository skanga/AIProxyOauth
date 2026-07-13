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

Run `python scripts/live-compatibility.py` against a running authenticated proxy to distinguish proxy translation behavior from current backend capabilities. The script accepts `--base-url`, `--model`, and `--api-key`; if `--api-key` is omitted, it uses `AIPROXY_API_KEY`.
