# AIProxyOauth 2.0

AIProxyOauth is a Java 21 OAuth proxy exposing OpenAI-compatible and Anthropic-compatible APIs. It routes requests to the `codex` and `anthropic` upstream providers while keeping provider credentials on the proxy host.

## Build and run

```bash
mvn clean package
java -jar target/AIProxyOauth-2.0.0.jar
```

With no arguments, the proxy starts on `127.0.0.1:10531`, enables every provider with usable credentials, prefers Codex for unqualified OpenAI-compatible model names, and performs one minimal inference check per enabled provider. `serve` is optional:

```bash
java -jar target/AIProxyOauth-2.0.0.jar serve --startup-check off
```

Codex credentials are discovered through `CODEX_HOME/auth.json` and `~/.codex/auth.json`. Anthropic credentials can be created and inspected with:

```bash
java -jar target/AIProxyOauth-2.0.0.jar auth anthropic login
java -jar target/AIProxyOauth-2.0.0.jar auth status
java -jar target/AIProxyOauth-2.0.0.jar auth anthropic logout
```

`CLAUDE_CODE_OAUTH_TOKEN` remains supported for Claude Code interoperability.

## Client APIs and providers

“OpenAI-compatible” and “Anthropic-compatible” describe the protocols clients use. They are not upstream provider names.

| Client API | Endpoints | Routing |
|---|---|---|
| OpenAI-compatible | `/v1/chat/completions`, `/v1/responses`, `/v1/models` | Qualified models choose a provider; unqualified models use `routing.default_provider` |
| Anthropic-compatible | `/v1/messages` | Always uses the Anthropic provider |

Provider identifiers are `codex` and `anthropic`. Qualify an OpenAI-compatible model as `codex/gpt-5.5` or `anthropic/claude-sonnet-4-5` when routing must be explicit.

## Commands

```text
aiproxy [serve] [options]
aiproxy auth anthropic login [options]
aiproxy auth anthropic logout [options]
aiproxy auth status [--config <yaml>]
aiproxy key generate [name]
aiproxy config show [--config <yaml>]
aiproxy doctor [--config <yaml>] [--inference]
```

`config show` prints the resolved value and source for each setting. OAuth tokens, proxy keys, and admin keys are never printed. `doctor` validates local configuration and credential availability; `--inference` makes missing usable provider credentials a failure.

### Serve options

```text
General:
  --config <yaml>
  --host <address>
  --port <port>
  --provider <auto|codex|anthropic|both>
  --default-provider <codex|anthropic>
  --startup-check <off|credentials|inference>
  --verbose

Proxy client authentication:
  --client-keys-file <path>
  --admin-client-key-file <path>

CORS and logging:
  --cors-origin <origin>       repeatable or comma-separated
  --allow-any-cors
  --log-requests
  --request-log-dir <path>

Codex:
  --codex-models <ids>
  --codex-version <version>
  --codex-base-url <url>
  --codex-oauth-file <path>
  --codex-oauth-client-id <id>
  --codex-oauth-token-url <url>
  --codex-store
  --codex-forward-prompt-cache-headers
  --codex-instructions-mode <none|file|latest>
  --codex-instructions-file <path>
  --codex-instructions-cache-dir <path>

Anthropic:
  --anthropic-models <ids>
  --anthropic-base-url <url>
  --anthropic-oauth-file <path>
  --anthropic-token-url <url|default>
```

Model lists are explicit overrides. When omitted, normal discovery, caching, and built-in fallback behavior is used.

## YAML configuration

YAML is loaded only when explicitly selected with `--config`. Relative paths are resolved from the YAML file’s directory.

A ready-to-copy configuration is available in [`aiproxy.example.yaml`](aiproxy.example.yaml).

```yaml
server:
  host: 127.0.0.1
  port: 10531

routing:
  provider: auto
  default_provider: codex

client_auth:
  keys_file: ./keys.txt
  admin_key_file: ./admin-key.txt

codex:
  oauth_file: ~/.codex/auth.json
  models: []
  version: null
  base_url: https://chatgpt.com/backend-api/codex
  oauth_client_id: app_EMoamEEZ73f0CkXaXp7hrann
  oauth_token_url: null
  store: false
  forward_prompt_cache_headers: false
  instructions:
    mode: none
    file: null
    cache_dir: ./cache/codex-instructions

anthropic:
  oauth_file: ~/.aiproxy/anthropic-auth.json
  models: []
  base_url: https://api.anthropic.com
  token_url: default

cors:
  origins: []
  allow_any: false

logging:
  requests: false
  directory: ./logs/requests

startup:
  check: inference
```

Unknown keys, malformed values, unreadable required files, conflicting instruction settings, and inline client secrets fail validation. Client and admin keys must come from files or environment variables.

## Precedence and environment variables

Configuration precedence is:

```text
CLI > AIPROXY_* environment > YAML > ecosystem credential discovery > defaults
```

Every serve option has a corresponding uppercase environment variable, including:

```text
AIPROXY_HOST
AIPROXY_PORT
AIPROXY_PROVIDER
AIPROXY_DEFAULT_PROVIDER
AIPROXY_STARTUP_CHECK
AIPROXY_VERBOSE
AIPROXY_CLIENT_KEYS_FILE
AIPROXY_ADMIN_CLIENT_KEY_FILE
AIPROXY_CORS_ORIGINS
AIPROXY_ALLOW_ANY_CORS
AIPROXY_LOG_REQUESTS
AIPROXY_REQUEST_LOG_DIR
AIPROXY_CODEX_MODELS
AIPROXY_CODEX_VERSION
AIPROXY_CODEX_BASE_URL
AIPROXY_CODEX_OAUTH_FILE
AIPROXY_CODEX_OAUTH_CLIENT_ID
AIPROXY_CODEX_OAUTH_TOKEN_URL
AIPROXY_CODEX_STORE
AIPROXY_CODEX_FORWARD_PROMPT_CACHE_HEADERS
AIPROXY_CODEX_INSTRUCTIONS_MODE
AIPROXY_CODEX_INSTRUCTIONS_FILE
AIPROXY_CODEX_INSTRUCTIONS_CACHE_DIR
AIPROXY_ANTHROPIC_MODELS
AIPROXY_ANTHROPIC_BASE_URL
AIPROXY_ANTHROPIC_OAUTH_FILE
AIPROXY_ANTHROPIC_TOKEN_URL
```

Secrets use only:

```text
AIPROXY_CLIENT_KEYS=name:sk-proxy-...,other:sk-proxy-...
AIPROXY_ADMIN_CLIENT_KEY=sk-proxy-...
```

`CODEX_HOME` and `CLAUDE_CODE_OAUTH_TOKEN` remain ecosystem discovery inputs. The old `CHATGPT_LOCAL_*` variables are no longer read.

## Startup checks

| Mode | Behavior |
|---|---|
| `off` | Sends no inference requests. Credential/model discovery still occurs and each provider displays `Check: skipped`. |
| `credentials` | Loads credentials, refreshes when needed, and validates local token/account metadata without a model prompt. |
| `inference` | After the server starts, calls `/v1/chat/completions` for Codex and `/v1/messages` for Anthropic. This is the default. |

Inference failures are nonfatal for `serve`: warnings are grouped at the end and the proxy reports `Ready with warnings.` Diagnostic text is bounded, sanitized, and redacted. Use `doctor --inference` in automation when a failed check must produce a nonzero exit code.

## Client authentication and CORS

Generate keys with:

```bash
java -jar target/AIProxyOauth-2.0.0.jar key generate cursor
```

A keys file contains one `name:key` or bare `key` per line. The admin key is stored in a separate file. `/health` remains unauthenticated; protected OpenAI-compatible endpoints accept `Authorization: Bearer <proxy-key>`, and Anthropic-compatible endpoints also accept `x-api-key`.

Binding to a non-loopback host requires client authentication. Wildcard CORS also requires client authentication. Explicit CORS origins must be HTTP/HTTPS origins without paths, queries, fragments, or user information.

## URL rules

Provider bases and OAuth token URLs must use HTTPS. Plain HTTP is accepted only for explicit loopback development URLs such as `http://127.0.0.1:8081` or `http://localhost:8082/v1`.

Trailing slashes are removed. Anthropic bases may include `/v1`; it is normalized away so generated endpoints contain exactly one `/v1`.

## Request logging

`--log-requests` can store bounded request and response bodies as well as metadata. OAuth tokens, proxy client keys, authorization headers, and sensitive reasoning material are redacted, but prompts and tool output may still be sensitive. Protect and rotate the log directory.

## Migration from 1.x

This is a breaking release; removed flags are rejected with replacement guidance.

| 1.x | 2.0 |
|---|---|
| `--models` | `--codex-models` |
| `--base-url` | `--codex-base-url` |
| `--oauth-file` | `--codex-oauth-file` |
| `--oauth-client-id` | `--codex-oauth-client-id` |
| `--oauth-token-url` | `--codex-oauth-token-url` |
| `--providers` | `--provider auto|codex|anthropic|both` |
| `--store` | `--codex-store` |
| `--forward-prompt-cache-headers` | `--codex-forward-prompt-cache-headers` |
| `--codex-instructions configured|latest-codex` | `--codex-instructions-mode none|file|latest` |
| `--api-keys-file` | `--client-keys-file` |
| `--admin-key` | `--admin-client-key-file` or `AIPROXY_ADMIN_CLIENT_KEY` |
| `--api-key` | `AIPROXY_CLIENT_KEYS` |
| `--generate-key [name]` | `key generate [name]` |
| `--anthropic-login` | `auth anthropic login` |
| `--anthropic-logout` | `auth anthropic logout` |

## Development

```bash
mvn test
mvn clean package
mvn test -Dtest=ClassName
```

See [MANUAL_TEST_PLAN.md](MANUAL_TEST_PLAN.md) for the release matrix.
