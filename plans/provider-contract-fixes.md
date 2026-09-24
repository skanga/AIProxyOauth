# Provider contract fixes

Goal: fix the ten findings from the provider consistency review without replacing provider-specific protocols or changing credential ownership.

Done when:
- A shared offline HTTP contract suite covers Codex, Anthropic, and Copilot Chat/Responses/Messages upstream protocols.
- Premature Chat EOF never synthesizes successful completion; incomplete terminal responses preserve output, usage, and length status.
- Every listed model id can be routed, including collisions and aliases.
- Codex has connection and request deadlines.
- Implicit Responses messages work with Anthropic, including replay and Copilot eligibility checks.
- Anthropic cached input counts toward canonical and native usage accounting, without changing native response payloads.
- Translated Responses failures use the normal event envelope, id, sequence, and terminal lifecycle.
- Refusals remain distinct from text in both Chat modes.
- Copilot eligibility and execution share preparation/validation; unrepresentable encrypted reasoning is rejected locally.

Workflow: add contract/regression tests; run focused Maven tests and establish RED; implement small fixes; run focused tests to GREEN; run full Maven package and offline Python tests; review diff and document remaining live-validation limits.

Constraints: preserve existing uncommitted work; no live inference/credential operations; qualified and replay requests never fail over; retain native Anthropic and Codex Responses passthrough semantics; no new dependencies.

Progress: tests being added. Baseline review: 443 Java tests and 23 Python tests passed, ten review-only probes reproduced the findings.
