# Current Task

## Identity

- Task ID: SYN-035
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0391
- Responsible agent: primary architecture-closure engineer
- Related decisions: ADR-0001, ADR-0008, ADR-0038, ADR-0039, ADR-0040, ADR-0041

## Objective

Replace the self-imposed eleven-tool prerelease MCP surface with ten clear
tools, distinguish capability publication from ordinary lane completion, and
make durable next actions executable without identifier guessing.

## Immediate slice

Centralize the MCP tool catalog, rename lifecycle tools, fold implementation
validation into strict coordination responses, update the managed Synesis
Manual, and preserve SYN-032 completion/integration semantics.

## Verification target

The ten raw tools, schemas, dispatch, attestation, documentation, CLI parity,
and real Codex ordinary completion all agree; wrong identifiers and removed
tool names fail closed without mutation.

## Immediate next action

Run the real Claude ordinary acceptance only if `claude auth status` reports
authenticated; otherwise preserve the explicit OAuth blocker and make no
implementation changes.

## Completion state

The collaboration roadmap remains open for SYN-032 hardening after the
prepared-snapshot and integration implementation slice. SYN-026 is complete at the
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

SYN-035 real-provider evidence is now current: after rebuilding the local
distribution and repairing the managed manual, a bounded Codex CLI process
using the current ten-tool MCP surface selected `finish_lane` for an ordinary
claimed feature, published logical snapshot `snap_79678eeae2012100f8047ec17ec895d0`,
integrated it, and closed lane `a8e162d7-1231-3e07-a088-451bb3ec4612` at claim
epoch 1. It did not call capability publication. Claude authentication status
is currently `loggedIn: false`; no Claude provider run is claimed.
The real Claude contract publication probe initially exposed an MCP
serialization defect; contract/dependency records are now projected to
JSON-safe maps, the focused MCP regression passes, and the reinstalled
distribution returns contract revision and content hash successfully.
The subsequent full Gradle check with strict dependency verification also
passes (50 actionable tasks).
Real Claude also inspected the published contract projection successfully,
including revision, supersession, selector, and content-hash fields.
Deterministic MCP tests now cover both publish and status JSON projections.
The isolated two-chat acceptance now publishes two dirty lane snapshots with
explicit work-group, lane, participant, binding, epoch, claim, and snapshot
provenance; provider metadata is excluded from source snapshots and both
snapshots integrate into a dedicated worktree successfully. The CLI exposes
the shared exact-lane release operation and MCP collaboration schemas describe
work-group and lane-grant lifecycle operations without changing the 11-tool
surface. The same fixture now runs the integrated candidate's pytest test and
requires it to pass. The final post-change Gradle check passes all 50
actionable tasks with strict dependency verification; bootstrap Go tests/vet
and the deferred validator pass; the task-tracker fixture remains 45/45
passing.
Activated collaboration publication now fails closed before snapshot
persistence when a managed changed path is outside the caller's current exact
path/subtree claims; the focused regression passes.
The integration orchestration regression also proves an unresolved
coordination request blocks before any integration-attempt event is appended.
The grant lifecycle regression proves owner-independent revocation fences a
continuation grant before consumption.
Cancellation lifecycle coverage now confirms the exact caller's collaboration
claim is released without deleting its isolated worktree.
The post-regression Gradle check passes all 50 actionable tasks; bootstrap Go
tests/vet, the deferred validator, and the 45-test task-tracker fixture pass.
The final lifecycle additions pass the focused strict Gradle suites, including
clean EOF detachment, cancellation release, and continuation-grant revocation
regressions. The sequential repository-wide check passes all 50 actionable
tasks with strict dependency verification; bootstrap Go tests/vet, strict
Javadocs, and the deferred validator also pass. The parallel CLI result-file
race remains a known test-runner limitation and is not a product regression.
The authority audit removed the mutation broker's sole-bound-session fallback:
mutation authority now requires an exact connection or session binding. A
recovery continuation restores the verified immutable snapshot into a new
lane, atomically consumes its single-use grant, transfers claims, and fences
the suspended source. Clean MCP EOF now records `DETACHED` instead of
`COMPLETED`; task completion and cancellation retain their terminal states.
The real Codex stdio recovery acceptance now passes against a disposable
project: abrupt process loss remained `SUSPECTED_STALE`, transitioned to
`RECOVERY_ELIGIBLE` only after the five-minute grace period, reconciled to
`RECOVERY_HELD`, and continued through a second isolated lane. The final
status showed source `DETACHED`, target `ACTIVE`, transferred claim, and no
control-checkout mutation. Old-process reacquisition was blocked by
`overlapping_claim`.
The installed-provider audit recorded Codex CLI `0.140.0` authenticated with
direct Synesis stdio initialization working, but its bounded real CLI probe
cancelled both `ensure_session` calls before a claim was established. Claude
Code `2.1.220` remains blocked by a malformed global MCP file using `servers`
instead of `mcpServers`; Antigravity `1.1.8` was present but no new multi-chat
row was run. A bounded Claude probe using an ephemeral valid MCP config did
successfully acquire and release an exact claim without file mutation. The
Antigravity probe repeatedly returned `workspace_not_ready` because no active
workspace was available. These are provider-boundary evidence only.
An explicit Antigravity `--new-project` retry entered project onboarding rather
than exposing the existing project's MCP session, so it also established no
claim.
The post-probe strict Gradle check still passes all 50 actionable tasks and the
deferred validator passes.

The SYN-032 fresh TestProject acceptance subsequently ran two real provider
processes. Codex claimed and integrated `src/task_tracker.py`; Antigravity
claimed `tests/task_tracker_test.py`, created an immutable test snapshot, and
after detached-session recovery the snapshot integrated through a newly
authorized lane. The final control checkout contains both files, explicit
`python -m pytest -q` passes 41/41, active claims are empty, and the only
control-branch commits are Synesis snapshot integrations. The native MCP
launcher now preserves provider cwd and was reinstalled; direct MCP
initialize/tools-list resolves the active project correctly. This is real
local Codex/Antigravity MCP evidence, not a universal autonomy claim.

## Work completed

SYN-035 is implemented. The MCP catalog now advertises exactly ten raw tools:
`ensure_session`, `read_file`, `apply_patch`, `run_command`, `get_next_action`,
`request_coordination`, `respond_coordination`,
`publish_capability_implementation`, `finish_lane`, and `cancel_lane`.
Implementation validation is a strict `respond_coordination` variant;
capability publication requires a server-issued capability handle, while
`finish_lane` is ordinary lane completion. Strict discriminated request and
response schemas reject unknown fields and legacy lifecycle shapes. The
managed Synesis Manual and executable next-action envelope now describe the
same lifecycle. ADR-0041 and maintained provider/architecture documentation
record the first-release ten-tool contract. `:mcp:test`, `:workspace:test`,
`:coordination:test`, the sequential Gradle `check`, and bootstrap Go tests/vet
all pass.

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

SYN-028 now adds explicit SUSPENDED, RECOVERY_HELD, REVOKED, and CANCELLED
participant transitions; stable event codes for suspension, recovery,
revocation, cancellation, and inbox acknowledgement; exact-caller idempotent
inbox acknowledgement; immutable recovery worktree snapshots;
provider-managed Synesis Manual installation and tamper attestation; safe
authority-reduction handling for invalid manuals; exclusive project migration
locking and durable preparation markers; and raw MCP names request_coordination,
respond_coordination, publish_capability_implementation, finish_lane, and cancel_lane.

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
The MCP suite is now aligned with the prerelease raw-name contract and managed
manual attestation. Decorated names are intentionally rejected; no legacy
compatibility reader remains.
The deferred-register cleanup leaves nine active capabilities, archives all
historical `SL-D-001` through `SL-D-030` IDs, and moves network cases to the
validation matrix. The three coordination-correctness entries
(`SL-D-037`–`SL-D-039`) now explicitly define revision invalidation,
out-of-band mutation enforcement, and wait-for cycle detection without
claiming implementation.

The SYN-030 first vertical slice now has a shared workspace workflow reducer
with stable action IDs, strict action types and payloads, explicit blockers and
permitted operations, retry-safety, and at-least-once acknowledgement metadata.
Claim overlap appends idempotent durable inbox requests for both owner and
contender, and default collaboration announcements join one active project
work group while explicit group IDs remain isolated. CLI acknowledgement,
reducer, conflict-inbox, MCP, coordination, and multi-chat regressions pass.
Terminal and recovery-held lane states now map to `CLOSE` and `RECOVER` workflow
actions. Incremental integration excludes already integrated snapshots through
the projection-level `readySnapshots()` query while retaining them for audit.
SYN-033 groundwork adds a shell-free `ProviderProcessSupervisor` for direct
argv launch, observation, bounded interrupt/close, and distinct-lane
continuation. It does not transfer claims; recovery grants and snapshots remain
the authority boundary. Codex and Claude now expose documented noninteractive
argv forms; Antigravity remains unsupported for autonomous launch pending real
MCP invocation evidence. The supervisor also exposes a one-shot verified-exit
callback that leaves lease/recovery decisions to signed protocol services. The
`ProviderSupervisionService` now records supervised child process identity in
the exact session lease and marks clean shutdown only on explicit close. The
`startWithAutomaticRecovery` schedules the existing signed reconciliation plan
after the configured grace period without direct claim release or abandonment
inference. The full sequential check passes 51 actionable tasks.
Provider supervision now serializes starts per lane, assigns monotonic process
generations, captures exit observations from the exact exited process, bounds
forced shutdown, and suppresses clean-close callbacks by generation rather than
lane name alone. Recovery tasks are cancelled and generation-checked when a
lane is reused or explicitly closed. Lease closure compares the expected
process ID so a late callback cannot close a replacement lease. A continuation
launch bridge validates distinct source and target lanes before starting the
target provider; signed grant consumption, snapshot restoration, and authority
transfer remain owned by the collaboration service.

## Current verification

`./gradlew.bat check --no-daemon --max-workers=1
--dependency-verification=strict` PASS (51 actionable tasks); coordination,
workspace, MCP, and CLI tests pass; strict
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

SYN-029 implementation hardening: provider process supervision now uses
generation-correlated exit observations, per-lane launch serialization, and
PID-checked clean lease closure. Successful task integration now completes the
exact caller binding while retaining its worktree, and subsequent inbox reads
return a terminal completed state instead of re-entering stale-workspace
readiness. Immutable task snapshots exclude provider/admin files such as
`AGENTS.md`, preventing untracked harness metadata from blocking control-branch
advancement. The full sequential Gradle check now passes (51 actionable tasks),
including Doctor, strict Javadocs, MCP, workspace, CLI, and repository hygiene.

The event-count failure exposed a real stale-store race: one connection could
append through a fresh store and a long-lived store would reuse its old head.
`PredictionEventStore.append` now derives the sequence and previous digest from
the durable event directory, preserving the append-only chain across concurrent
store instances. Integration also refreshes collaboration requests through a
fresh projection before allocating an attempt, so unresolved coordination
blocks without starting integration. The focused unresolved-coordination test
now passes.

Fresh disposable-provider acceptance now passes for Claude Code 2.1.220 as
well as Codex: both completed ordinary claimed-file work through immutable
snapshot and control integration without manual coordination. A fresh
Antigravity 1.1.8 `agy --print` probe returned a generic response without
invoking MCP, so its model-driven path remains unverified despite passing
native transport health.

Provider lifecycle tests isolate `user.home`, and Doctor scopes provider
migration findings to projects with durable provider installation records.
