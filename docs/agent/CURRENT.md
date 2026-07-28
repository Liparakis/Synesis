# Current Task

## Identity

- Task ID: SYN-021
- Status: ACTIVE
- Priority: P1
- Started checkpoint: CP-0230
- Responsible agent: primary architecture-closure engineer
- Related decisions: ADR-0001, ADR-0008; no architecture change

## Objective

Implement authenticated claim lifecycle, presence, and stale fencing without adding a broker, remote service, or control-checkout mutation path.

## Immediate slice

Add participant presence projection, lease-backed lifecycle, explicit release,
verified-abandonment recovery, and claim-epoch fencing on top of the completed
SYN-020 claim service.

## Verification target

Two concurrent intents cannot acquire overlapping claims; unrelated claims can
coexist; all selectors are acquired atomically; and existing event/session
records replay without migration loss.

## Immediate next action

Add deterministic lifecycle events and tests for lease renewal, stale/grace
classification, owner-independent abandonment recovery, and old-epoch fencing.

## Work completed

`SYN-018` hygiene is complete. `SYN-019` is DONE at `a87d3d8`; no
production type moved and the stale allowlist was narrowed to
`ProjectApplicationService.java`. The user explicitly authorized one narrow
bootstrap portability correction for the GitHub Actions failure; `SYN-014E`
remains paused. The correction moves a validated versioned payload before
applying immutable permissions and restores/removes a partially hardened
payload if hardening fails. The Unix stable launcher is explicitly restored to
0755 after atomic replacement, and uninstall makes immutable trees removable.

SYN-020 now includes signed `WORK_INTENT_ANNOUNCED` and
`WORK_INTENT_RELEASED` events, stable event wire codes preserving historical
dependency events, replayable collaboration projections, exact-file and
subtree selector overlap evaluation, refresh-on-race claim arbitration, shared
workspace claim authorization, project append locking, durable post-activation
claim fencing, exact connection binding resolution for task completion and
cancellation, MCP `ensure_session` claim input and refresh-empty release,
lease renewal on verified MCP activity, clean stdio shutdown release, and CLI
`collaboration announce/status/release` adapters. The existing 11-tool MCP
count is unchanged.

## Current failures

The original GitHub Actions failure is not reproducible on this Windows host.
Bootstrap `go test -count=1 ./...`, `go vet ./...`, and `git diff --check` pass
after the follow-up launcher/removal correction. All six CI target
cross-builds pass. The unrelated README edit remains outside this task and must
not be reverted or included. Linux test cleanup now explicitly removes
immutable installation roots before `t.TempDir` cleanup. A subsequent Linux CI
failure identified incorrect stripping of leading slashes from absolute
`file:` URIs in MCP root binding; that fix is now authorized for this slice.
`McpServerTest` and `./gradlew.bat clean check --no-daemon
--dependency-verification=strict` now pass locally.
The deferred-register cleanup leaves nine active capabilities, archives all
historical `SL-D-001` through `SL-D-030` IDs, and moves network cases to the
validation matrix. The three coordination-correctness entries
(`SL-D-037`–`SL-D-039`) now explicitly define revision invalidation,
out-of-band mutation enforcement, and wait-for cycle detection without
claiming implementation.

## Current verification

`./gradlew.bat check --no-daemon --dependency-verification=strict` PASS (50
actionable tasks); coordination, workspace, MCP, and CLI tests pass; strict
Javadocs and repository hygiene pass; bootstrap `go test -count=1 ./...` and
`go vet ./...` pass; deferred validation and `git diff --check` pass. The
Python task-tracker fixture remains 45/45 passing. The two-handler MCP
acceptance passes: the first exact claim is acquired, the second receives
`overlapping_claim`, and its competing mutation is blocked. Real Claude/Codex
provider sessions and Antigravity remain separate evidence tasks. The earlier
combined test run hit the known CLI in-progress result-file race; the final
sequential root check passed.

SYN-020 closure evidence is recorded in CP-0260; historical event compatibility
is covered by the wire-code replay test and durable state is reconciled.

SYN-021 negotiation slice is now implemented: signed coordination requests and
responses replay through the collaboration projection; opaque participant
projections expose provider, goal, state, and claims; CLI status/request/respond
and additive MCP coordination request/response fields use the shared workspace
service. `WorkIntentServiceTest.conflictingParticipantsCanDiscoverAndResolveNegotiation`
passes. Signed `PARTICIPANT_HEARTBEAT` events now record verified MCP activity
and terminal participant projections remain visible after release. Lease-backed
stale recovery now plans and executes `RELEASE_ABANDONED_CLAIMS` through the
shared event service. Atomic two-party handoff is implemented with pending
ownership retention, target-only acceptance, append-lock serialization, and
intent-version fencing. Lease boundary tests and dirty-artifact handoff
validation remain next. Verified process absence now appends
`PARTICIPANT_ABANDONED` and preserves an auditable terminal projection;
reconciliation remains idempotent when a dead session held no collaboration
claims.
