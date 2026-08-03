# SYN-036 real-provider acceptance evidence — 2026-08-03

## Scope

This record covers the final real-provider attempt for SYN-036. The fixture was
freshly initialized as a Git project and Synesis project. Codex and Antigravity
were launched as separate provider processes against the same logical project;
each MCP connection retained its own authenticated binding and isolated lane.

The Synesis protocol and repository checks are authoritative. Provider model
behavior is recorded separately and is not used to claim universal native-hook
or provider-supervisor enforcement.

## Codex process

The real Codex CLI completed the MCP workflow through the installed ten-tool
server:

- participant: `agt_8bf9c3da-1071-3525-b0e9-0b50a7a442ff`;
- intent: `d9bfe930-8b36-33ed-a9f0-46f81244eeda`;
- work group: `0b02c354-73ba-3638-91af-caa30e1d53e6`;
- exact claim: `PATH_EXACT src/task_tracker.py`;
- source lane: `session-ad0f4df7-c6fd-40c3-ae75-944f713edae4`;
- authority lineage: `7292cd5e-c1b4-37b3-ac92-4eb2ae20d792`;
- capability request: `req_85C4V5X5Z080P4X5Z1P493G1T7B51483`;
- capability publication revision: `1`.

Codex inspected the source, published the contract, implemented the source
lane through Synesis MCP, accepted the server-issued capability request, and
published the capability implementation. Its configured generic test command
returned `tool_unavailable`; Codex correctly did not perform requester-only
validation, republish the capability, or finish while the dependent lane was
unresolved. The durable status projection retained the source claim and the
pending dependent request.

## Antigravity process

Antigravity's direct stdio MCP transport and the ten-tool catalog are verified
separately by the existing provider evidence. A clean-home noninteractive
Antigravity CLI attempt was then run against the same acceptance fixture with
the MCP-only task prompt and no native editing tools.

Observed behavior:

- the process produced an initial `ensure_session`/read-only narrative;
- no new active Antigravity participant or exact test-lane claim appeared in
  the durable projection;
- no `apply_patch`, publication, validation, completion, or cancellation was
  recorded for Antigravity;
- the CLI streamed model requests approximately every two seconds for more
  than three minutes and exited without completing the MCP workflow;
- the Codex source claim remained the only active mutation claim.

The result is a provider-side limitation of this Antigravity noninteractive
model/CLI path: it did not reliably drive the MCP protocol to claim, implement,
publish, validate, and finish. Synesis correctly preserved the owner lane and
did not infer a claim or accept an unverified mutation. This does not invalidate
the Synesis MCP transport, claim arbitration, lineage checks, or integration
guarantees, and it is not evidence of native-hook maturity.

## Repository and protocol verification

After the provider attempt, the repository passed:

- sequential `./gradlew.bat check --no-daemon --max-workers=1 --no-parallel --dependency-verification=strict`;
- `:mcp:check`, including the two-process collaboration regressions;
- focused SYN-036 baseline, reset, portability, lineage, integration, repair,
  and provider-session tests;
- `powershell -ExecutionPolicy Bypass -File scripts/agent-validate-deferred.ps1`;
- `bootstrap\go test -count=1 ./...`;
- `bootstrap\go vet ./...`;
- `git diff --check`.

The platform bundle smoke test also passes after creating a Git baseline before
MCP session establishment. The raw MCP catalog remains exactly ten tools. No
control-checkout mutation, user-content adoption, stale-epoch publication, or
unowned repair scope was observed.

## Completion disposition

All ten SYN-036 implementation tasks and repository guarantees are verified.
The only incomplete part of the requested final scenario is Antigravity's
noninteractive model-driven execution, which is an external provider
capability limitation documented above. Synesis MCP transport and safety
behavior remain evidenced; no universal Antigravity autonomy claim is made.
