# AIProxyOauth 2.0 Manual Test Plan

Run this matrix with disposable Codex and Anthropic test credentials. Never paste production OAuth tokens or proxy keys into transcripts.

## 1. Build and automated gate

```bash
mvn test
mvn clean package
java -jar target/AIProxyOauth-2.0.0.jar --version
```

Expected: all tests pass, the fat JAR exists, and the version is `2.0.0`.

Repeat the command checks below on Windows PowerShell and one Unix-like shell.

## 2. CLI and subcommands

Run `--help` at the root and for `serve`, `auth`, `auth anthropic login`, `auth anthropic logout`, `key generate`, `config show`, and `doctor`.

Verify:

- the description names both OpenAI-compatible and Anthropic-compatible APIs;
- zero arguments and `serve` both enter server startup;
- `key generate` emits `sk-proxy-` plus 32 lowercase hex characters;
- a named key emits `name:key`;
- login accepts `--allow-stdin-oauth-code` only on the login command;
- logout accepts `--yes` only on the logout command.

Confirm every removed 1.x spelling exits with code 2 and gives its replacement: `--models`, `--base-url`, `--oauth-file`, `--oauth-client-id`, `--oauth-token-url`, `--api-key`, `--api-keys-file`, `--admin-key`, `--providers`, `--store`, `--generate-key`, `--anthropic-login`, and `--anthropic-logout`.

## 3. YAML and precedence

Create a minimal YAML file, then a complete file using the README schema. Verify no config is loaded unless `--config` is present.

For representative string, integer, boolean, list, enum, and path fields, test:

```text
CLI > AIPROXY_* environment > YAML > defaults
```

Verify relative paths resolve from the YAML directory, not the process working directory. Test malformed YAML, unknown top-level and nested keys, invalid enums, out-of-range ports, unreadable key files, an unreadable instructions file in `file` mode, and `instructions.file` combined with `none` or `latest`.

Put `keys:` and `admin_key:` literals under `client_auth`; both must be rejected as inline secrets. Set `AIPROXY_CLIENT_KEYS` and `AIPROXY_ADMIN_CLIENT_KEY`, run `config show`, and confirm the values appear only as `<redacted>` while each ordinary field includes its source.

Confirm `CODEX_HOME` and `CLAUDE_CODE_OAUTH_TOKEN` interoperability and that `CHATGPT_LOCAL_*` no longer changes resolution.

## 4. Startup modes

Test the default, YAML, and CLI override cases:

- omitted setting: `inference`;
- YAML `startup.check: off`;
- YAML `off` plus CLI `--startup-check inference`;
- CLI `--startup-check off`;
- `--startup-check credentials`;
- invalid values.

With a recording stub upstream, prove `off` sends zero inference POSTs, still performs ordinary model discovery, starts the server, and prints `Check: skipped` for every enabled provider.

In `credentials` mode, use valid, refresh-required, missing, expired, and malformed credentials. Confirm refresh/token metadata calls are allowed but no model prompt is sent.

In `inference` mode with both providers, confirm exactly one provider-qualified Codex request goes through `/v1/chat/completions` and one Anthropic request goes through `/v1/messages`. Make each upstream fail separately. `serve` must remain running and print `Ready with warnings.`; `doctor --inference` must return nonzero.

## 5. Provider selection and models

Exercise `auto`, `codex`, `anthropic`, and `both` with every credential availability combination. Explicitly selected providers without credentials must fail validation. With both enabled, confirm the default is Codex unless Anthropic is explicitly selected.

Verify `--default-provider` changes only unqualified OpenAI-compatible models. `/v1/messages` must always use Anthropic.

For each provider, test configured model overrides, successful discovery, warm cache, stale/last-good cache, and built-in fallback. Explicit model lists must prevent discovery from changing the exposed list. The banner shows count and one of `configured`, `discovered`, `cache`, or `fallback`; IDs appear only with `--verbose`.

## 6. URL normalization and validation

Test Anthropic bases with no suffix, `/v1`, `/v1/`, and repeated trailing slashes. Requests must contain exactly one `/v1`. Test equivalent trailing-slash handling for Codex.

Accept HTTPS URLs and explicit loopback HTTP URLs (`localhost`, `127.0.0.1`, and `::1`). Reject remote HTTP, relative URLs, malformed hosts, user information, and fragments. Apply the same checks to OAuth token URLs; keep Anthropic `token_url: default` working.

## 7. Client authentication, CORS, and logging

Test open loopback mode, a client keys file, an admin key file, environment keys, and combined sources. Call all endpoints with missing, invalid, regular, and admin keys using both Bearer and native Anthropic `x-api-key` forms. Confirm `/health` stays public and admin usage visibility works.

Reject non-loopback binding without authentication. Reject `--allow-any-cors` without authentication. Accept wildcard CORS with authentication. Accept explicit HTTP/HTTPS origins without paths and reject origins with paths, queries, fragments, user information, or unsupported schemes.

Enable request logging and exercise large bodies, tools, errors, and both credential types. Confirm stored bodies are bounded; authorization, OAuth tokens, proxy keys, and sensitive reasoning fields are redacted. Protect the directory before live testing.

## 8. Startup display and redaction

Capture Codex-only, Anthropic-only, and dual-provider banners. Verify server address, network exposure, client auth, CORS, request logging, startup mode, both client API groups, enabled providers, default provider, credential source, model count/source, and per-provider check.

Inject account email, OAuth tokens, proxy/admin keys, multiline control characters, and a very long upstream error. Confirm no email or secret appears; diagnostics are single-line, bounded, and redacted; warnings are grouped at the end.

## 9. Protocol regression

For Codex and Anthropic routing, run synchronous and streaming Chat Completions and Responses calls with system/user/assistant/tool messages, tool calls/results, reasoning effort, image input, usage, finish reasons, malformed requests, upstream 401/403/429/5xx, and disconnects.

For `/v1/messages`, cover synchronous and streaming text, tool use/result, system blocks, thinking/redacted-thinking blocks, image input, stop reasons, usage, and Anthropic-shaped error responses. Confirm `/v1/models` works for both OpenAI and native Anthropic clients.

## 10. Release sign-off

- Automated tests and package pass on Java 21.
- Windows and Unix-like CLI matrices pass.
- Both providers pass live inference or the failure is documented and release-blocking.
- `off` produces no inference traffic.
- `serve` failures are nonfatal; `doctor --inference` failures are nonzero.
- README examples match `--help`.
- No logs, output, fixtures, or artifacts contain credentials or PII.
