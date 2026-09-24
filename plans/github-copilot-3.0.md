# GitHub Copilot 3.0 implementation record

## Status

The approved design requires live contract validation **before** the full backend
is implemented. The GitHub.com device-login, discovery, and minimal inference
gate passed on 2026-09-23. The user supplied a Copilot CLI config;
GitHub.com authentication, discovery, and one minimal Chat Completions inference
passed on 2026-09-23. A second, successfully authorized device-login attempt used
the public client ID with no requested scopes and also passed discovery/inference.
The user removed
enterprise validation as an implementation and release requirement on 2026-09-23;
enterprise support remains in scope but is explicitly unverified until separately
tested. No enterprise account or tenant is required to continue. See
[sanitized observations](fixtures/copilot-github-com-2026-09-23.json).

The production implementation and 3.0.0 JAR are built. The standalone contract
probe remains available for account-specific investigation. Mock test success is
not evidence of live coverage; the validation record below separates the two.

The observed GitHub.com catalog contained seven models and no `supported_endpoints`
metadata. An explicit investigation of `/chat/completions` using its listed
`gpt-4o-mini` model succeeded (13 reported tokens); the upstream identified the
served model as `gpt-4o-mini-2024-07-18`. No fallback or model substitution was
performed by the probe. This establishes one path, not full account/model coverage.

Follow-up live checks with the same explicit credential and model also passed:
streamed text with exactly one `[DONE]`, a forced `lookup` function call with valid
JSON arguments, and a tool-result continuation returning text. No tool was run
on the host; the test supplied a fixed synthetic tool result.

## Agreed release behavior

- Java 21 standalone JAR; add direct HTTP `copilot` provider.
- GitHub.com and Enterprise Cloud `tenant.ghe.com`; exclude on-premises GHES.
  Live enterprise validation is optional and does not block implementation or
  release. Label enterprise support unverified until tested on a real tenant.
- One active Copilot account/host per process. Device login/logout and explicit
  file/environment token inputs; no discovery of other applications' credentials.
- Public OAuth client profile only after verification, with a client-ID override.
- Copilot supports both OpenAI-compatible inference APIs, streaming/non-streaming,
  and model discovery. Client `/v1/messages` remains Anthropic-only.
- Support upstream Chat Completions, Responses, and Messages according to model
  metadata. Text, tools/results, inline images, reasoning controls, and reported
  usage are required where the selected model supports them; reject unsupported
  capabilities. No structured-output expansion or billing estimates.
- List Copilot models as `copilot/<raw-id>`; preserve existing provider listings.
- `auto` enables credentialed providers; `all` requires all three. Explicit
  comma-separated selections work; legacy `both` remains Codex plus Anthropic.
- Default order is Copilot, Codex, Anthropic. `--provider-order` may reorder them
  without enabling them; append omitted providers in default order. Reject
  duplicates/unknown names. Retain explicit `--default-provider` precedence.
- Failover is opt-in through `--failover`. Only unqualified, non-replay requests
  qualify. Require identical raw IDs and supported requested capabilities.
  Never change models or infer equivalence. Preserve ambiguity errors when off.
- Try each candidate at most once in provider order; explicit default leads when
  eligible. Retry connection failures, timeouts, 429, and 5xx only before client
  response commitment. Never retry invalid input, auth/permission errors, client
  cancellation, or an already-started response. Qualified requests stay pinned.
- Copilot Responses uses bounded same-process replay isolated by client and
  provider/account. Cache completed responses; fail missing references explicitly.
  Pin continuations to the original provider. No persistent response storage.
- Model discovery cache: five minutes fresh, maximum one hour from last success
  for stale fallback. No hardcoded model catalog. Explicit model lists restrict
  exposure without fabricating capabilities. Other providers survive outages.
- Preserve configuration precedence and strict validation. Add `copilot` settings
  for GitHub host, OAuth file/client ID, explicit token file, and models, with
  matching CLI/environment settings. Explicit token file takes precedence over
  dedicated environment token, then login store; no silent credential fallback.
- Integrate auth status, config show, doctor, usage, redaction, and startup modes.
  Preserve off/credentials/inference behavior and existing serve/doctor exit rules.

## Contract investigation

Reference source, pinned during investigation:

https://github.com/microsoft/vscode/blob/b4abde1c921a634d4caef8492e4f67f13eba6444/src/vs/platform/agentHost/node/shared/copilotApiService.ts

This source uses the GitHub OAuth token directly and discovers account endpoints
using `/copilot_internal/user`. It is a research input, not proof that our profile
works. The probe intentionally identifies itself as AIProxyOauth, does not assume
additional compatibility headers, and must be adjusted from observed evidence
if the upstream rejects the request.

Device-login research input:

https://github.com/microsoft/vscode-copilot-chat/blob/main/script/setup/getToken.mts

That example names public client ID `01ab8ac9400c4e429b23`. A live device flow with
that public ID and no requested scopes succeeded, followed by discovery and
inference. Production uses this verified default with an explicit override.
The observed token had no expiry/refresh response; managed credentials with an
expiry fail closed and require re-login. External token files are reread for
rotation. The proxy requests no repository scope.

### Run the probe

Python standard library only; it needs no installed proxy or third-party packages.

Discovery only, using an explicitly supplied OAuth token file:

```powershell
python scripts/copilot-contract.py --host github.com --token-file C:\secure\copilot-token.txt
```

Alternatively supply `--token-env VARIABLE_NAME`, or use interactive authorization:

```powershell
python scripts/copilot-contract.py --host github.com --device-login --client-id 01ab8ac9400c4e429b23
```

The token file must contain only the token, not JSON. For a user-supplied Copilot
CLI config use `--copilot-config PATH` instead. This supports JSON comments, selects
only the last logged-in account, and requires its host to match `--host`. It never
searches for other configs or copies credentials into the repository.

Device-login tokens remain
in memory and are discarded on exit. The device verification URL/code is printed
to stderr. Tokens and raw upstream response/error bodies are never printed.
The probe never reads credentials belonging to other applications.

After inspecting the catalog, explicitly select a returned model and one of its
advertised endpoints to send a minimal inference request:

```powershell
python scripts/copilot-contract.py --host github.com --token-file C:\secure\copilot-token.txt --model EXACT_MODEL_ID --endpoint /chat/completions
```

The probe normally requires the endpoint to be advertised. When the metadata is
absent, `--allow-unadvertised-endpoint` permits an explicitly selected experimental
request and marks the endpoint as unadvertised in its report. It does not override
an existing advertised endpoint list or invent a capability. This is an
investigation option, not a production routing policy.

The probe never substitutes a different model or endpoint. Optional enterprise
checks use the actual `tenant.ghe.com` host and its separate credentials.
Discovery requires a trusted HTTPS endpoint; redirects
are disabled. A failure stops that run without falling back to GitHub.com.

Stdout contains an allowlisted JSON report: host kind, reference revision, model
IDs/capabilities/endpoints, and which checks passed. It omits account identity,
tenant hostname, tokens, endpoint hostnames, and generated response text. A report
is evidence of a single run, not proof that the entire gate or release has passed.

### Gate acceptance

For **GitHub.com** (enterprise checks are optional):

1. Verify device authorization and explicit-token authentication.
2. Establish minimum scopes, credential lifetime/renewal behavior, required
   headers, and account-specific endpoint discovery using sanitized observations.
3. Capture sanitized model metadata and prove advertised protocols with explicit
   minimal inference calls. Do not treat HTTP 200 without text as success.
4. Record the pinned authentication/transport references and tested environments.

The GitHub.com device-login and minimal inference gate passed. Broader live
coverage of native Responses and Messages models remains unverified; all six
client/upstream protocol combinations are tested with local HTTP fixtures.
Enterprise validation remains optional and its unverified status is retained.

## Implementation and validation record

Implemented authentication/configuration, model discovery and adapters,
provider-indexed wiring, replay/failover, startup and diagnostic integration,
3.0 versioning, documentation, and live compatibility tooling. Startup always
lists all available model IDs, grouped in configured provider order, regardless
of verbosity. Connection-reset classification is shared across inference
transports and excludes credential failures and cancellation.

- Java: 443 tests, zero failures/errors/skips; isolated `mvn clean package` passed.
- Python: 23 offline tests passed.
- Packaged JAR: version, root/serve help, Copilot login/logout help, and example
  config checks passed on Windows and Ubuntu 24.04 (JDK 25, Java 21 bytecode).
- Live GitHub.com: discovery, both OpenAI client APIs streaming/non-streaming,
  tool call/results, usage, qualified model listing, and Responses replay passed
  through the Chat Completions upstream with `gpt-4o-mini`.
- The live preamble with an empty completion ID exposed a decoder issue;
  the reproducing regression test and fix are included.
- Root `mvn clean package` encountered a locked pre-existing 1.2.0 JAR. The clean
  build ran from `target/release-verification` without stopping that process;
  its source snapshot was checked against the working Java sources and POM.
  The resulting artifact is `target/AIProxyOauth-3.0.0.jar`.

Required checks: `mvn test`, `mvn clean package`, Python offline tooling tests,
live GitHub.com matrix, packaged JAR on Windows and a Unix-like OS. The ghe.com
matrix is optional; offline enterprise host-isolation tests remain required.
Cover auth concurrency/errors/redaction, catalog failures/collisions, all protocol
pairs, streaming truncation/cancellation, tool/image/reasoning capabilities,
exact-model failover exclusions, replay isolation/eviction, and 2.0 regressions.

Version, release documentation, example YAML, compatibility matrix, manual tests,
and AGENTS.md now describe 3.0. No tag, commit, push, or publication was performed.
Remaining live account/model checks are listed explicitly in the compatibility
matrix and manual test plan; enterprise checks are optional.

Offline investigation checks:

```powershell
python -m unittest scripts/test_copilot_contract.py scripts/test_live_compatibility.py
```
