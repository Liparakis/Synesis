# Current Task

## Identity

- Task ID: SYN-027
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0298
- Responsible agent: primary architecture-closure engineer
- Related decisions: ADR-0001, ADR-0008, ADR-0038, ADR-0039

## Objective

Implement multi-chat logical workgroups with isolated mutation lanes, exact
caller authority, immutable snapshots, and guarded integration.

## Immediate slice

Phase 0 authorized SYN-027 and recorded ADR-0039. The next slice hardens exact
calling-connection authority before introducing WorkGroup lane records.

## Verification target

Authority-sensitive operations resolve only the verified calling binding;
stale, cross-binding, unclaimed, and incompatible integration paths fail closed.

## Immediate next action

Complete provider-lifecycle lane close/revocation wiring and contract-aware
integration checks, then run the two-snapshot integration acceptance.

## Completion state

The collaboration roadmap is complete at CP-0292. SYN-026 is complete at the
current checkpoint after fixing synthetic-check configuration leakage. The
installed distribution
includes `mcp-0.1.0-SNAPSHOT.jar`; direct launcher
initialize passes. Codex global MCP entry and the task-tracker Claude project
entry point to that install. A real Codex CLI run completed `ensure_session`
with an exact `src/task_tracker.py` claim and isolated worktree; no source file
was edited. A separate real Codex probe created and reread an isolated file
with a matching revision hash through MCP. Claude is now authenticated and a
real run blocked on Codex's `src/task_tracker.py` claim before mutation; a
separate real Claude run created and reread an isolated file with a matching
revision hash and exercised explicit release/reacquisition. Antigravity is
installed and authenticated locally; its direct MCP process completed the
bounded transport acceptance while model-driven prompting remained limited by
harness behavior. Two
independent installed MCP processes demonstrated Codex claim → Claude overlap
denial → clean-EOF release → Claude reacquisition. Historical task-tracker
events now replay successfully after stable legacy dependency wire-code
decoding. A read-only reconciliation inspection classified one provider
session as `suspectedStale` without transferring ownership; the dirty fixture
was not altered. Its current Python acceptance suite passes all 45 tests.
The repository-wide Gradle check also passes with strict dependency
verification (50 actionable tasks).
The real Claude contract publication probe initially exposed an MCP
serialization defect; contract/dependency records are now projected to
JSON-safe maps, the focused MCP regression passes, and the reinstalled
distribution returns contract revision and content hash successfully.
The subsequent full Gradle check with strict dependency verification also
passes (50 actionable tasks).
Real Claude also inspected the published contract projection successfully,
including revision, supersession, selector, and content-hash fields.
Deterministic MCP tests now cover both publish and status JSON projections.

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

SYN-022 contract revision slice is implemented: signed publication, exact consumer bindings, supersession, deterministic REPLAN_REQUIRED invalidation, content hashes, and replay fixtures are covered by ContractServiceTest.

SYN-023 first slice is implemented: `IntegrationCompatibilityService` produces
deterministic actionable results for stale ancestry, overlapping snapshots,
uncovered claims, stale contracts, out-of-band paths, and failed tests;
orchestration blocks stale/overlapping metadata before worktree preparation;
Python projects use `python -m pytest -q` and the synthetic two-process fixture
contains a real pytest smoke target.

SYN-025 provider acceptance now has direct Antigravity MCP evidence: the
installed `antigravity` server initialized, acquired an isolated exact claim,
mutated and read back a probe with a matching revision hash, and released on
clean EOF. The Antigravity model CLI still failed to carry the structured claim
through a mutation prompt (`coordination_intent_required`, then provider
`internal_failure`); this is recorded as harness behavior and does not alter
native-hook maturity. Claude and Codex real-provider evidence remains valid.
Direct Codex/Claude MCP processes also exposed and then exercised the shared
JSON-safe collaboration discovery projection. An accepted handoff transferred
an intent from Codex to Claude with version fencing, and Claude mutated the
transferred path successfully. Timed deleted-chat recovery remains an
additional evidence case; deterministic stale/abandonment tests already pass.
The real deleted-chat v3 probe now passes end to end: a lease existed before
forced JVM termination, stale and abandonment thresholds were observed,
owner-independent reconciliation completed without control-checkout changes,
the participant became `ABANDONED` with claims removed, and the old connection
epoch was blocked with `workspace_generation_changed`. This required fixing
first-ensure lease creation and refreshing the reconciliation event-store head
after owner-independent claim release.
An additional direct Codex/Antigravity MCP process probe completed an accepted
handoff and revision-checked mutation. Antigravity model-prompt behavior and
native hooks remain separately unclaimed.
The fixed 11-tool MCP surface now exposes additive coordination request and
handoff operations through `describe_required_capability`, structured response
fields through `respond_to_owner_request`, and JSON-safe collaboration details
through `get_next_action`; CLI and MCP parity tests pass. SYN-025 is DONE:
direct MCP acceptance is verified for Codex, Claude, and Antigravity, while
Antigravity model-driven prompting and native-hook maturity remain explicitly
unclaimed.
