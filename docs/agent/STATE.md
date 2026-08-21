# State

## SYN-039 current verification state

SYN-039, Autonomous Workgroup Completion, is ACTIVE. The first production
implementation slice is complete and the task remains active for the next
evidence-bounded lifecycle slice. SYN-038 remains DONE
at CP-0458 and its prior App Server history, acceptance evidence, and
`turn_interrupted_command_remained_active` limitation remain authoritative.

The task is grounded in the user-supplied previous unattended Todo smoke-test
failure. No raw Todo run artifact is present in this checkout, so the first
implementation action is to reproduce and capture that failure. The checked-in
zero-touch architecture ledger marks its two-process path DEMO_ONLY and
manually driven, while the multi-chat provider checklist does not claim
autonomous end-to-end integration:

- `docs/architecture/zero-touch-agent-collaboration.md`
- `docs/validation/multi-chat-provider-acceptance.md`

The accepted scope is limited to the existing Synesis model: reviewer/
validator access to another agent's immutable snapshot without conflicting
write ownership; structured accept/reject decisions; rejection routing to the
correct implementer; accepted guarded integration; WorkGroup completion;
cleanup of pending requests, ownerships, detached coordination state, and
temporary artifacts; and healthy final Doctor diagnostics. No new orchestrator,
UI, daemon, Fleet system, centralized launcher, or provider intelligence is
authorized by this task.

No architecture ADR is required for activation alone. An ADR is required if
implementation changes a product boundary or protocol guarantee.

## SYN-039 first implementation slice — CP-0465 follow-up

The unattended Todo baseline was reproduced on 2026-08-22 in disposable
fixture `C:\Users\LIPARA~1\AppData\Local\Temp\syn039-unattended-todo-baseline-20260822`.
Formal evidence is
`docs/evidence/syn039-unattended-todo-baseline-2026-08-22.md`; raw Codex JSONL
captures remain in the fixture's `baseline-logs` directory.

Agent A created WorkGroup `7c5ab815-5f05-365b-a78b-3478440036af`, lane/intent
`e167026d-3340-3892-b66c-0cbcb5a1c7ee`, participant
`agt_19a81786-c8c0-3dc3-a31d-0f8335a28ad0`, and task
`39168b91-9fc9-37d5-9703-6d06d251620e`. It implemented and tested the Todo
completion change in an isolated lane (`pytest`: 3 passed) and published
snapshot `snap_6162f6fd4ff4d51aadb5484609270ab3`. `finish_lane` then returned
`integration_pending` / `integration_failed`; retry returned
`integration_conflict` / `TESTS_FAILED` and requested human help.

Agent B discovered the WorkGroup and A's claims but could not obtain a review
grant: its status request was rejected with
`COORDINATION_FIELD_NOT_ALLOWED:body`, and `work_group_join` was rejected with
`COORDINATION_FIELD_REQUIRED:grantId`. No validation item, snapshot projection,
handoff, accept/reject decision, or actionable reviewer lane was exposed.

At post-run inspection, the fixture control checkout remained at baseline
`7a5925f`; coordination status reported zero tasks and zero ownerships, while
three managed worktrees remained. Doctor was `DEGRADED` with two
`stale_session_lease` warnings, reconciliation recommended, and no repair
available. This is a failed baseline, not implementation success. No SYN-039
production code changed.

The first implementation slice is recorded in
`docs/evidence/syn039-unattended-todo-slice-2026-08-22.md`. `REVIEW` is now a
typed coordination request. Owner acceptance issues deterministic single-use
grant `79ef69cd-55bc-3925-a179-ff272cc94d12` targeted at reviewer
`agt_93870f01-30a0-30f9-bf9b-29ac7de500dd`, for owner intent
`3db91f37-9ad0-364c-aaab-cb26494fdee1` at claim epoch 1, without changing the
owner's claims. Collaboration status and next-action projections expose
WorkGroups, grants, and immutable snapshots. The integration-check adapter no
longer classifies the recorded `pytest` 3/3 evidence as `TESTS_FAILED`.

The exact unattended rerun used fixture
`C:\Users\LIPARA~1\AppData\Local\Temp\syn039-unattended-todo-slice-20260822`.
Agent A published `snap_3e673171518792f078f394bf5dab7cd5`, which integrated to
clean control commit `97664dc`; the control checkout's `pytest` run reported
`3 passed in 0.02s`. WorkGroup `932d024e-06ff-3176-bef6-12c33279e486` remains
ACTIVE because Agent B did not consume the grant or validate the snapshot.
There is no validation decision or clean-close evidence. Doctor is DEGRADED
with five warnings and reconciliation recommended.

Exact next implementation action: make the admitted reviewer validate the
published snapshot through the existing path. Preserve any later lifecycle
failure as the next bounded SYN-039 blocker; do not create SYN-040.

## SYN-038 current verification state

The SYN-038 Codex App Server lifecycle phase is complete and recorded at
CP-0447/CP-0448; the durable project-command extension is complete at CP-0458.
The existing `synesis coordination serve` process is
the production owner and `ProjectRuntimeHost` retains the Codex-only lifecycle
attachment. Prior deterministic lifecycle tests, coordination and CLI checks, strict
Javadocs, static/format gates, deferred validation, bootstrap Go tests/vet,
and diff checks pass. The final focused and full repository gates, bootstrap Go
tests/vet, deferred and fixture validators, and `git diff --check` are green.

The real-owner acceptance is complete for the required lifecycle path and
evidence-backed: exact authority was
established before START; the generated-schema App Server reached exact
thread/turn control; WAIT did not starve STEER or INTERRUPT; interruption,
passive exact-thread resume, explicit continuation, duplicate STATUS/WAIT
replay, and bounded hard-stop outcomes were observed. The restarted-harness
owner run used local Codex `0.146.0-alpha.9.2` with the existing global command
override and reached `ensure_session`, `apply_patch`, and validation. Its
foreground barrier remained alive after the exact interrupted terminal event,
so command cancellation/tree cleanup remains independently unproven and is
classified as `turn_interrupted_command_remained_active`. A fresh exact
binding run with installed Codex `0.146.0-alpha.9.2` then resumed the exact
thread and used Codex-driven MCP mutation, validation, snapshot publication,
and `finish_lane` integration; snapshot
`snap_f3bd879455900258bb77ca6cea8fac22` integrated at clean control HEAD
`9e30f4183434a5f282a6e42fb55e4339c0879578`. Historical Codex 0.145.0 runs still record
`INTERACTION_REQUIRED` and SYN-038 correctly does not guess approval. See
`docs/evidence/syn038-real-codex-app-server-acceptance-2026-08-03.md`.

A prior bounded retry supplied the existing task and exact claim to
`ensure_session`; Synesis returned `overlapping_claim` for the already-owned
lane. That historical lane was not replayed; the fresh exact binding above
provided the normal completion evidence.

The harness-restart final3 run also observed a real foreground barrier,
WAIT/STEER control concurrency, exact-turn interruption with an observable
command, owner restart/passive reconciliation, explicit exact-thread RESUME,
and duplicate replay. The direct global MCP smoke returned `initialize` and
the unchanged ten-tool catalog. SYN-038 remains fail-closed and does not add
an approval operation or claim Codex-driven command cleanup.

The sequential root `./gradlew.bat check --no-daemon --max-workers=1
--console=plain` completed successfully; module reports contain zero failures
and zero errors. No new approval operation, daemon, listener, provider
abstraction, or MCP tool was added.

The durable-command extension bookkeeping is recorded in ADR-0044. No prior
command namespace, permanent command locks, durable command records, or typed
request replay path existed before this phase. The immediate implementation
slice is the bounded namespace/lock/format/process-anchor spike and its
deterministic two-process fixtures. Prior App Server commits, checkpoints, and
acceptance evidence must remain unchanged.

The implementation spike now covers the host-wide namespace skeleton,
permanent namespace/worktree lock objects, atomic metadata writes,
compatibility/integrity verification, supported older-format migration
evidence, real-path worktree locators, typed request-ID and digest
canonicalization, one fresh MCP process identity, process-anchor persistence,
durable phase records, read-only replay, bounded command protection, strict
MCP framing, WorkIntent mutation preconditions, namespace reconciliation,
terminal-history compaction, and doctor/cleanup/repair/next-action diagnostics.
`ProjectCommandNamespaceSpikeTest`, `ProcessCommandRunnerTest`, and
`McpFrameReaderTest` pass. The MCP Git setup hang is fixed in the shared
runner, and `McpServerTest` passes 32 tests.

The remaining MCP failure was diagnosed from the exact response
`command_admission_stale` / `LEASE_RENEWAL_FAILED` with the underlying
`IOException:PARTICIPANT_NOT_FOUND`. `ensure_session` creates an active lease
without a collaboration participant when no claims are requested. The durable
path now calls `heartbeatIfPresent`: the no-participant case is a valid
no-intent session, while all other heartbeat, lease, readiness, and authority
failures remain blocking. The failed run persisted only the scope and anchor
(object revision 1), renewed the lease once to ACTIVE, and wrote no STARTING
record; after the fix the no-claims regression reaches terminal completion.

The shared Git runner also owns the raw integration-orchestration Git calls,
uses optional-lock/fsmonitor suppression, and has monotonic plus wall-clock
deadlines. The full root `check` passed in 8m53s; focused SYN-038 tests,
validators, doctor, bootstrap Go tests/vet, and `git diff --check` also pass.

At CP-0457, the full aggregate gate is green. Doctor's read-only regression
now allows only the valid host-wide durable-command reconciliation/retention
warnings that earlier tests may leave, and rejects unrelated findings.

## SYN-038 closure

Implementation commit `ad9fdd8addc9f71e806dfb2da5b5d78f050f87ac` completed the
durable project-command extension without changing the earlier lifecycle
commit, tag/history, checkpoints, ADR-0043, or acceptance evidence. CP-0457 is
the prior verification evidence; CP-0458 is the final closure checkpoint.

The resolved Git subprocess hang is covered by the shared bounded runner:
stdin closes immediately, output is concurrently safe and bounded, Git is
non-interactive, and timeout cleanup terminates descendants. The admission
regression was narrowed to `PARTICIPANT_NOT_FOUND` for a valid no-claims
session; `heartbeatIfPresent` ignores only that absence and preserves all other
lease/readiness failures. The lease protocol still renews once, snapshots
exactly after renewal, reacquires and revalidates, and persists no STARTING
record after an unexpected change.

Final closure verification passed: full `check`; focused 32-test
`McpServerTest`; durable namespace/process/MCP framing and supporting
regressions; deferred and fixture validators; bootstrap Go tests/vet; strict
Javadocs and format; doctor; and `git diff --check`. No SYN-039 was created.

## SYN-037 implementation and closure state

SYN-037 is DONE at CP-0415. The implementation keeps exactly ten raw MCP
tools and replaces the prerelease command-intent/adapter path with the direct
argv `ProjectProcessExecutor`. The same executor serves `run_command`,
server-owned pre-publication validation, and integration validation. Evidence
is bounded to 65,536 raw bytes per stream using a 32,768-byte head and rolling
tail; every result exposes bytes read, bytes retained, and truncation flags.

The repository-private contract is exact and common-directory based:
`/.synesis/local/`, `/.synesis/coordination/`, and `/.codex/hooks.json`. The
Codex hook materializer classifies absent, canonical/stale Synesis-owned,
user/provider-owned, mixed, tracked, malformed, symlinked, non-regular, and
concurrently changed content. Ambiguous content fails closed with
`PROVIDER_CONFIGURATION_CONFLICT`; Git exclusion never proves ownership.

Project metadata schema v2 accepts an optional project-owned validation argv;
schema v1 remains readable as no configured gate. Validation metadata is
strictly parsed, and completion/integration cannot be overridden by agents or
toolchain inference. Legacy intent fields, adapters, and `run-tests.cmd` are
not reachable from production code.

Real Codex evidence is recorded in
`docs/evidence/syn037-real-codex-acceptance-2026-08-03.md`: participant
`agt_4f3186fb-0313-3410-ba2b-5b5bfb3c88c7`, session
`session-935acfb9-2c5e-4c48-9fa3-b67ffcad05de`, lane/intent
`c5404e85-cbbd-3ad0-878d-25e438939a32`, epoch 1, exact claim
`src/task_tracker.txt`, three command results, snapshot
`snap_76f58a1737e488a2a248413dd95d3a3e`, integration attempt
`att_7c31901698014874`, final control HEAD
`0237b31e1d1423861c6ce7e4fbe99b1027315189`, and empty final Git status.

Focused workspace/MCP suites, strict module Javadocs, deferred validation,
bootstrap Go tests/vet, and `git diff --check` pass. The two root failures were
test defects: one asserted LF instead of logical lines on Windows, and one
snapshotted the parent of the managed baseline instead of a lane based on that
baseline. The assertions/fixture now preserve process bytes and strict
SnapshotArtifactPolicy behavior. `:workspace:check` and the complete profiled
root `check` both pass; details are in
`docs/evidence/syn037-root-verification-fix-2026-08-03.md`.

## SYN-036 implementation state

SYN-035 is closed at CP-0399. SYN-036 is closed at CP-0407.
Task 1 is complete at commit `71b33c5`; the authoritative objective is the
Canonical Baselines and Lineage-Aware Integration specification in the active
goal attachment.

Tasks 2 through 10 are now complete at the current checkpoint: managed-path
classification, transaction-owned provenance, semantic index comparison, and
the journaled baseline transaction are implemented and wired into fresh Git
project initialization. Keep exactly ten MCP tools, recognize only journaled
transaction-owned managed files, and preserve fail-closed dirty-control
behavior.

Task 1 evidence: the handler now consumes exact schemas from the shared
`mcp-contract` catalog; provider health separates wire compatibility,
catalog-content freshness, and guidance-artifact integrity; and
`AdministrativeStateLocator` derives external state from the canonical Git
common directory. Focused `:mcp-contract:check`, `:workspace:check`, and
`:mcp:check` all pass.

SYN-036 task 2/3/4 evidence: commits `14ff54f` and `9e62c9f` add
managed-path classification, transaction ownership checks, semantic Git-index
fingerprints, a journaled managed-baseline transaction service, project-init
wiring, and crash/replay/provenance tests. Strict `:workspace:javadoc` and
focused workspace tests pass. A repository-wide `:workspace:check` attempt
timed out in the test runner without a completed report; this is retained as a
runner limitation, not represented as product evidence. Reset recovery adds
common-directory-keyed durable journaling, exclusive locking, old-authority
fencing, namespace staging/transfer, restart discovery, and idempotent
recovery; its focused crash-point suite passes. Complete-tree portability and
artifact policy are implemented at `192f839`. Authority lineage now survives
intent, capability, implementation, and snapshot replay, and capability
publication is fenced to the exact active lineage. Stable event replay covers
historical dependency code 42 and current V3 intent payloads. The fenced
integration pump now orders eligible snapshots by dependency-ready
lineage, keeps transient failures pending, and durably blocks structural
invalidity without releasing claims. Atomic repair transfer is complete: an
authenticated repair join verifies the immutable snapshot ref and lineage,
materializes the conflict on the current control head, rejects unowned target
changes, and holds the project append lock across materialization and the
source-release/target-announcement event. The transfer is epoch-fenced and
idempotent. Prerelease migration and legacy cleanup are verified: canonical
provider IDs and raw ten-tool names are enforced, obsolete current-facing
references were removed, provider/project migration and rollback tests pass,
and historical replay decoders remain only for signed state that must be read.
Task 10 is complete under the documented external-provider exception. A real
Codex process established the exact source claim, negotiated and published the
authority-lineage capability implementation, and remained correctly fenced
while requester validation was pending. A clean Antigravity noninteractive
process did not drive the MCP workflow beyond initial/read-only activity, so
no claim, mutation, publication, or completion was inferred. Direct
Antigravity MCP transport is evidenced separately; model-driven provider
autonomy is not claimed. The complete evidence is recorded in
`docs/evidence/syn036-real-provider-acceptance-2026-08-03.md`.

The final sequential Gradle check, strict Javadocs and static checks, deferred
validator, bootstrap Go tests/vet, and diff check pass after the task-10
regression fixes. No active SYN-036 lane remains.

## SYN-035 closure evidence

The prerelease MCP lifecycle surface now has ten raw tools. Capability
publication uses a server-issued capability handle; ordinary completion uses
`finish_lane`; cancellation uses `cancel_lane`; implementation validation is a
strict `respond_coordination` variant. Legacy lifecycle names and
non-discriminated coordination payloads are rejected. CP-0393 records the
implementation and verification checkpoint; provider acceptance remains the
next action.

## Repository state

Contract revision 1 is ACTIVE. SYN-028 is DONE at CP-0331 under ADR-0040; SYN-027 is DONE under ADR-0039; SL-005 through SL-008 are complete;
SL-012 is VERIFYING, SL-013 is DONE and frozen at CP-0054, SL-014 and SL-015
are DONE, SYN-002 is DONE at CP-0075, SYN-003 is DONE at CP-W3, SYN-009B is
  DONE at CP-0102, SYN-009B.1 is VERIFYING, SYN-009C is DONE at CP-0110, and
  SYN-010A is VERIFYING, SYN-010B is VERIFYING, SYN-011 is VERIFYING, and
SYN-012 is DONE at CP-0144, SYN-013's planning package is complete, and
SYN-013A is DONE and SYN-013B is DONE at CP-0151;
SYN-009D is complete. SYN-001 is DONE at
CP-R4 and CP-R5 is deferred; SL-DEMO-001 is VERIFYING; SL-ARCH-001 is
complete and SL-009 is deferred. The repository is a Synesis root with Link as the first
implemented transport/session subproject. Public GitHub preview preparation has
an authorized AGPL-3.0-only license decision recorded; publication remains
unperformed pending authorization and remaining review gates.

SYN-010B has a reusable Go aggregation command and a workflow aggregation job.
Matrix outputs are short-retention internal artifacts; the final job retains
one 30-day `synesis-release-candidate` artifact and removes internal artifacts
on ordinary writable-token runs. No GitHub Release was created.

SYN-020 through SYN-025 and SYN-032 are DONE; SYN-034 handoff is complete and
SYN-035 is the active implementation task. SYN-032 was reopened after its
recorded acceptance was contradicted by the real TestProject lane evidence and
closed again only after the corrected acceptance and repair-lane verification.
SYN-025 covers authenticated real-provider collaboration acceptance and
evidence. Direct Antigravity MCP transport has isolated claim,
mutation/readback, handoff, and clean-EOF evidence; model-driven Antigravity
mutation remains a harness limitation and is not a native-hook maturity claim.

SYN-032 hardening is implemented and closed at CP-0389. Completion now
fails closed for clean lanes (`NO_CHANGES_TO_PUBLISH`) and rejects stable
snapshot-ID collisions instead of creating another advertised record. The fresh
real-provider acceptance integrated Codex source and Antigravity tests through
separate immutable snapshots and a recovered test lane; `python -m pytest -q`
passes 41/41 and active claims are empty. The native launcher preserves caller
cwd after the provider-install correction. Exact-caller prepared-completion
unwind is now available through the existing ensure-session schema, advances
the claim epoch, and replays atomically through a dedicated event. Repair
joining now provisions the authenticated lane's isolated binding, materializes
the immutable conflicting snapshot there, and transfers the exact scope in one
signed repair event. No universal Antigravity autonomy claim is made.

The final sequential repository check passes all 51 actionable tasks after the
repair-join slice. Bootstrap `go test -count=1 ./...` and `go vet ./...` pass;
the fresh TestProject acceptance still has both immutable source/test snapshots
on the control branch, 41 passing pytest tests, and no active claims. Historical
detached provider sessions remain durable audit records and are not active
authority.

## SYN-012 implementation state

The coordination vertical slice is verified at CP-0144 and its public CLI
extension is in progress: bounded prediction contracts, signed command
envelopes (including version-two logical actor binding), coordinator-signed
hash-chained event files, deterministic task/ownership/prediction projections,
durable command idempotency, loopback HTTP commands, replayable SSE
subscriptions, isolated speculation worktrees, provider REQUEST_OWNER
enforcement, and an external-project two-process CLI acceptance run. Existing
Link and provider behavior remain unchanged. ADR-0027 records the bounded MVP
and ADR-0028 records the public CLI/actor decision.

## SYN-013 planning state

The zero-touch architecture package is documentation-only. It selects a hidden
project-local runtime/session manager over the existing loopback coordinator,
per-session Git worktrees, and an explicit provider capability gate. Codex and
Antigravity remain unready until trusted real workspace-transition and
pre-mutation evidence exists. ADR-0029 records the decision.

## SYN-013D implementation state

Stage 2A Slice 1 (foundation) and Stage 2A Slice 2 (provider MCP configuration installers & installed process validation) are DONE at CP-0167:
- Provider capability audit completed (`MCP_CONFIG_DISCOVERED`).
- `AgentSessionService` ambient resolver implemented in `:workspace` (74 tests passing).
- `:mcp` Gradle subproject created with stdio JSON-RPC 2.0 protocol handler (`SynesisMcpServer`).
- Registered tool `synesis.ensure_session` returns concise status output.
- `ProviderApplicationService` and `synesis init` automatically install `.codex/mcp.json` and `.agents/mcp.json` (and `.gemini/mcp.json`) idempotently while preserving unrelated provider MCP entries.
- Platform bundle updated (`:cli:platformBundle`), launcher `synesis init` and installed MCP stdio process tested on `SynesisTestProject`.
- Full root `./gradlew.bat check --no-daemon` passes cleanly (49 tasks).

## SYN-014A implementation state

Post-MVP Hardening Slice 1 is DONE. Read-only lifecycle inventory and cleanup planning foundation implemented:
- 12 `LifecycleResourceType` values covering all managed resource categories.
- 6 `CleanupClassification` values: `PROTECTED`, `ACTIVE`, `RECOVERABLE`, `DIAGNOSTIC_RETAINED`, `CLEANUP_ELIGIBLE`, `ORPHANED`.
- 5 `ProcessEvidenceState` values with conservative liveness evaluation.
- `LifecyclePathVerifier`: path normalization, canonical real-path traversal check, control-checkout protection, `.git` protection, git common-dir identity verification, fail-closed on error.
- `RetentionPolicy`: configurable 24h worker/validation/integration, 7d evidence, 1h temp, injectable clock, 2 GB storage warning threshold.
- `LifecycleInventoryService`: read-only discovery of sessions, worker worktrees, validation/integration worktrees, snapshots, evidence files, temp files, dangling Git worktrees.
- `CleanupEligibilityService`: evaluates inventory against path verifier, retention policy, process liveness, Git porcelain status.
- `CleanupPlanService`: orchestrates full plan generation into immutable `CleanupPlan` record.
- `CleanupCommand` (`synesis cleanup --dry-run`): concise, verbose, and JSON output modes; `--dry-run` required; exits code 10 without it.
- `synesis cleanup` registered in `SynesisCli`.
- Tests: `LifecyclePathVerifierTest`, `CleanupEligibilityServiceTest`, `ProcessInspectorTest`, `CleanupCommandTest`, `FullMutationSafetyTest` (proves zero mutations).
- Full root `./gradlew.bat check --no-daemon` BUILD SUCCESSFUL (49 tasks, all pass).

## SYN-014B implementation state

Post-MVP Hardening Slice 2 is DONE at CP-0188. Controlled lifecycle cleanup execution implemented:
- Immutable persisted cleanup plan store (`CleanupPlanStore`, `PersistedCleanupPlan`, `PersistedCleanupPlanEntry`) under `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\cleanup-plans\<plan-id>.json`. Verified canonical SHA-256 content hashes.
- Project-scoped execution lock (`CleanupExecutionLock`) at `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\cleanup-execution.lock`.
- Append-only cleanup execution journal (`CleanupExecutionJournal`, `CleanupExecutionRecord`, `CleanupEntryExecutionState`) under `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\cleanup-executions\<execution-id>.jsonl`.
- Safe Git worktree removal for finalized worker, completed validation, and integrated integration worktrees using `git worktree remove <path>` (NO `--force`!).
- Exact temporary file deletion and empty temp parent directory cleanup.
- Atomic orphan resource quarantine (`LifecycleQuarantineService`) under `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\quarantine\<quarantine-id>\` writing quarantine manifests.
- CLI command options added: `synesis cleanup --prepare`, `synesis cleanup --show-plan <plan-id>`, `synesis cleanup --execute <plan-id>`.
- 10 new test classes added (PlanPersistenceTest, StalePlanTest, WorkerCleanupTest, ValidationAndIntegrationCleanupTest, TemporaryFileCleanupTest, QuarantineTest, LockAndConcurrencyTest, JournalAndRestartTest, NoForceAndSafetyArchitectureTest, Slice2MutationBoundaryTest).
- Full root `./gradlew.bat check --no-daemon` BUILD SUCCESSFUL (49 tasks, all pass).

## Implementation state

Repository hygiene is the sole active primary task under `SYN-018`.
`Reorganize Synesis package structure` and its `SYN-016`/`SYN-017` follow-up
slices are preserved as prior implementation work. `STRUCT-1A — Foundational
packages` completed on 2026-07-26 at
commit `376f2d2ce6003b32d28994b19b6728926ab0af6e`; `STRUCT-1B — Workspace
packages` completed on 2026-07-26 across commits `b67ac1c` and `248889a`. `STRUCT-1C` and
`STRUCT-1D` remain inactive. `SYN-014E` is paused with its prior evidence preserved and without
production rollback.

The hygiene preflight at `a67dd00` did not pass the exact root check: the
workspace architecture test expects root facades that the current source has
moved into responsibility packages, and Gradle's parallel test result reader
reported missing in-progress binary result files. This workstream records the
failure and does not change package behavior to hide it.

SYN-018 hygiene implementation is complete through commits `4b7f530` and
`59f7c63`. The final sequential root check still fails only on the stale
`WorkspaceApplicationPackageArchitectureTest.rootContainsOnlyStableFacades`
assertion. `repositoryHygieneCheck`, focused MCP initialize/tools-list tests,
Go tests/vet, CLI help/version, provider list, and generated init instructions
pass. No safe script consolidation was identified; no historical evidence was
deleted or rewritten.

SYN-019 closes that stale architecture rule at `a87d3d8`. Inspection found only
`ProjectApplicationService.java` directly under the application root; the test
allowlist was narrowed to that deliberate stable facade. No production type,
runtime behavior, module dependency, CLI/MCP/provider surface, schema, or event
format changed. All requested Gradle, Go, MCP, CLI, provider, init, and hygiene
checks passed before an unrelated README edit appeared. That preserved edit
currently makes the existing hygiene count regex misread its `%20tools-11`
badge URL as a 20-tool claim; it remains outside this task.

`STRUCT-1B` was restricted to intra-module package restructuring inside
`:workspace`. It completed with `workspace.application`, `workspace.project`,
`workspace.provider` and provider-specific subtrees, lifecycle packages, and
responsibility-specific infrastructure packages. The required checks passed:
`:workspace:check`, `:coordination:check`, `:cli:check`, `:mcp:check`, root
`check`, `go test -count=1 ./...`, `go vet ./...`, and focused ten-tool MCP test.

`STRUCT-1A` was restricted to intra-module package restructuring inside
`:project-record`, `:coordination`, and `:link`. The approved direction is:
- `project-record`: `domain`, `persistence`, `security`, `sync`,
  `sync.protocol`, `guardrail`
- `coordination`: `domain`, `application`, `persistence`, `transport.http`
- `link`: `transport.quic`, `transport.control`, `onboarding`, `cli`

Global structural-phase constraints: no type moves across Gradle modules, no
new Gradle dependencies, no Go bootstrap edits, no CLI/MCP surface changes, no
provider/schema/reason-code/event-format changes, exactly 10 MCP tools
preserved, and no deduplication/warning/god-class cleanup.

Pre-STRUCT-1A baseline: clean working tree at
`8dc7d90e8286c5f954111c9619c88fc8b5c8d355`; `.\gradlew.bat check --no-daemon`
PASS; `bootstrap\go test -count=1 ./...` PASS; `bootstrap\go vet ./...` PASS.

`STRUCT-1A` completion evidence: foundational packages were reorganized within
`:project-record`, `:coordination`, and `:link`; `DemoCli` moved to
`org.synesis.link.cli` with the `link/build.gradle.kts` main-class update; the
required validations passed: `.\gradlew.bat :project-record:check --no-daemon`,
`.\gradlew.bat :coordination:check --no-daemon`,
`.\gradlew.bat :link:check --no-daemon`, `.\gradlew.bat check --no-daemon`,
`go test -count=1 ./...`, and `go vet ./...`; the slice was committed as
`Reorganize Synesis foundational packages` (`376f2d2ce6003b32d28994b19b6728926ab0af6e`).

The project contains bounded identity, automatic identity bootstrap, signed
candidate descriptors and single-use invitations, direct candidate
providers/racing, authenticated QUIC sessions, control readiness, graceful
close, heartbeat/liveness, compact terminal QR rendering, and the demo-only authenticated
`synesis-demo-work/1` request/result stream. `DemoCli` remains the diagnostic
fallback. The standalone `cli` module owns Picocli, terminal rendering, exit
mapping, doctor, QR rendering, and Gradle Application distributions; Link owns
onboarding through the typed `Onboarding` façade. `:project-record` now owns
the CP-R2 canonical signed decision model, immutable local store, recovery, and
JDK-only inspection launcher. CP-R4 adds only configured peer authorization,
bounded one-shot messages, authenticated validation, deterministic outcomes,
and quarantine. SYN-009D now owns the explicit stable installation follow-up;
broader production installation claims remain out of scope until its evidence
passes.

## Planning boundary

The CAF Phase 1 map and first signed-decision-record proposal are recorded in
`docs/architecture/CAF-PHASE-MAP-AND-RECORD-SLICE.md`, ADR-0011, and ADR-0013.
ADR-0011 is accepted and SYN-001 is DONE at CP-R4. CP-R5 is deferred. SYN-002
is DONE at CP-0075. ADR-0014 selects SYN-003 CP-W1/CP-W2 as a thin workspace
bootstrap and one-shot sync layer; no background sync,
reconnect, discovery, membership, retries, second transport, shared-Markdown
authority, extra record types, physical two-machine claim, or CLI change is
allowed.

## Verification state

- Strict clean verification after removing the package-info gate: PASS.
- Package-info files and the package-info verification task were intentionally removed at the user's request; strict Javadocs for public/protected API elements remain enabled.
- Latest candidate-provider fix: targeted PASS; full strict check PASS.
- Source/tooling artifact verification metadata: PASS; twenty-three exact SHA-256 entries cover main, test, Gradle, and Kotlin resolution.
- Deferred register validation: PASS, 9 active entries; historical IDs SL-D-001
  through SL-D-030 are preserved in the deferred-functionality archive.
- Local and two-process demo request/result: PASS.
- CLI help and identity creation: PASS.
- Invitation, identity-bootstrap, admission, QR, and strict Javadoc checks: PASS.
- Two-profile two-process host/join onboarding, including work exchange and
  graceful close: PASS; this is not physical two-machine evidence.
- Compact QR output skips safely when the process output charset cannot encode
  the Unicode glyphs; the copyable share link is always retained.
- Resume, doctor, and fixture validation: PASS.
- Physical two-machine diagnostic `DemoCli` Scenario A normal operation:
  `TWO_MACHINE_VERIFIED`; evidence is in
  `docs/evidence/PHYSICAL-DEMO-2026-07-20.md`. Physical source-run onboarding
  remains unverified and is not covered by that evidence.
- Physical abrupt-loss and wrong-identity scenarios: NOT CLAIMED.
- Physical zero-configuration onboarding: NOT CLAIMED; it is outside the frozen
  SL-013 development-distribution baseline.
- Generated launcher two-profile onboarding: PASS; evidence is
  `docs/evidence/CLI-DISTRIBUTION-VALIDATION.md`.
- Generated Antigravity wrapper from a non-project working directory: PASS;
  `AntigravityHookProcessTest` proves PATH-first launcher resolution,
  project-root setup, compact deny JSON on stdout, exit `0`, and protected-file
  preservation. The wrapper fallback is the stable
  `%LOCALAPPDATA%\Synesis\bin\synesis.cmd` path; no versioned path is used.
- CP-R2 local decision record encoding, signing, storage, recovery, and
  inspection: PASS; evidence is
  `docs/evidence/DECISION-RECORD-CP-R2-2026-07-21.md`.
- CP-R4 configured peer authorization, bounded messages, one-shot publish/sync,
  authenticated owner/signature/project/predecessor checks, deterministic
  outcomes, conflict quarantine, and two-profile process scenarios: PASS;
  evidence is `docs/evidence/DECISION-RECORD-CP-R4-2026-07-21.md`.
- SYN-001 closure and SYN-002 planning comparison: PASS as documentation-only
  state; ADR-0013 defines the searchable-view boundary.
- SYN-002 implementation: PASS; `DecisionSearch` and validated-head snapshot
  remain local, on-demand, bounded, and non-mutating.
- SYN-002 closure review: PASS at CP-0075; evidence is
  `docs/evidence/PROJECT-VIEW-SYN-002-2026-07-21.md`.
- SYN-003 planning: ADR-0014, first-demo script, task promotion, and CP-0076
  are recorded; CP-W1, CP-W2, and CP-W3 implementations are verified.
- SYN-003 CP-W1/CP-W2/CP-W3 implementation: PASS; `:workspace` provides bounded
  profile bootstrap, one-peer project creation, signed revision-1 decision
  creation, one-shot authenticated host/join sync, decision search, and single-record
  inspection. Evidence is in `docs/evidence/WORKSPACE-CP-W1-2026-07-21.md`,
  `docs/evidence/WORKSPACE-CP-W2-2026-07-21.md`, and
  `docs/evidence/WORKSPACE-CP-W3-2026-07-21.md`.
- SYN-PRODUCT-REVIEW: PASS; completed the product review and roadmap planning through CP-0079. The review is recorded in `docs/agent/PRODUCT_REVIEW.md`.
- SYN-004 planning: PASS; completed the minimal guided workspace demo flow design and task promotion under CP-0081. Detailed design is in `docs/agent/SYN_004_DESIGN.md`.
- SYN-009A implementation: PASS; unreleased `WorkspaceOperations`, `DecisionRecordCli`, and legacy process harnesses were removed, synchronization orchestration now belongs to `SyncApplicationService`, and the unified `synesis` launcher is the only supported command entry point. Strict clean verification passed after the removal.
- SYN-009B closure: PASS; obsolete CLI compatibility tests remain deleted, valid
  process/protocol scenarios were rewritten in `UnifiedCliSyncProcessTest`
  against the generated launcher, malformed invitations map to
  `INVITE_INVALID`, and provider lifecycle evidence includes unrelated hook
    configuration preservation. Closed at CP-0102. SYN-009C is READY with no
    implementation started.

- SYN-009B.1 promotion: PASS as a documentation/architecture gate; the
    installed Codex baseline is `codex-cli 0.140.0`, the official hook/config
    boundary and trust limitation are recorded, and no SYN-009C implementation
    has started. Parser, adapter, lifecycle, fixture, and real-agent evidence
  remain pending.

- SYN-009B.1 implementation slice: PASS for bounded parser/adapter behavior,
  provider lifecycle, Windows launcher command shape, generated process
  coverage, and 20-call synthetic latency. Codex remains
  `EXPERIMENTAL`/`DEGRADED` with `TRUST_STATUS=REVIEW_REQUIRED`; the real
  `/hooks` trust review and qualifying denial/re-plan/hash evidence are not
  complete. See `docs/validation/codex-real-agent-experiment.md`.

- SYN-009C promotion: PASS as a user-authorized task-state change. The
  distribution architecture is EVOLUTION; implementation and release evidence
  are pending. Codex remains EXPERIMENTAL/DEGRADED/REVIEW_REQUIRED.

- SYN-009C.1 implementation: PASS. `synesis version`, embedded build metadata,
  `jlink` runtime, relative launcher, ZIP/tar.gz archives, and clean-room
  Windows provider/init/doctor smoke all pass. The bootstrapper source, signed
  manifest helper, safe extraction, version validation, and six-platform CI
  matrix are present. SYN-009C.2 verification now passes: `gofmt`, `go test
  ./...`, `go vet ./...`, native Windows subprocess install/update/doctor/
  uninstall, all six cross-compiles, clean-room Java `clean check
  --dependency-verification=strict`, and platform archive generation. SYN-009C.3
  final gate: Java full build, Go full test, CI matrix definition, native smoke
  where available, bootstrap native smoke, manifest aggregation, test-key
  signature verification, release documentation, and clean working tree all
  PASS at CP-0110: archive extraction smoke is cross-platform, bundled
  launchers set their own launcher path, CI verifies the sidecar actually
  produced by signing, and the real Windows archive bootstrap trial passes.
  Production key replacement and OS vendor signing remain deferred
  release-hardening work.

## Honest limitations

Serverless direct internet connectivity, manual forwarding, router mapping,
hole punching, reconnection, physical IPv6/public IPv4, temporary
application-silence recovery, and all-firewall operation are not claimed; see
the deferred register and network validation matrix. Synesis intentionally has
no STUN, TURN, relay, hosted rendezvous, or production peer-discovery
dependency. Production installation/signing, GUI, and broad production
cooperation semantics are also not claimed. Abrupt child-process loss remains
classified only as the documented transport-failure or liveness-expiry
category.
## SYN-011 Antigravity real-integration investigation

- Preserved pre-change test-project evidence before modifications under
  `docs/evidence/antigravity-real-investigation-2026-07-22/before`.
- The original Windows managed hook command had invalid `.cmd` quoting and the
  first wrapper invocation also inherited Antigravity's working directory. The
  generated integration now emits an unquoted, space-free PowerShell `-File`
  command for a project-local wrapper; the wrapper sets the project root before
  invoking Synesis.
- The exact observed Antigravity structured tool is `replace_file_content`; its
  real-shaped payload is covered by `AntigravityHookAdapterTest` and the exact
  managed command returns `decision=deny`, exit `0`, with the bounded reason.
- Direct constraint evaluation remains `BLOCKED`, exit `10`.
- A real Antigravity 1.0.16 protected edit still changed the protected file;
  current transcript/database diagnostics contain no hook decision or Synesis
  marker. This is evidence of project-hook discovery/loading failure, not an
  adapter constraint failure.
- Provider health is intentionally `DEGRADED`, `TRUST_STATUS=UNVALIDATED`, and
  `REAL_AGENT_VALIDATION=NOT_COMPLETED`.
- Real unrelated structured editing succeeded with `--add-dir`; real replan
  content reached `proposals/tcp-fallback.md`. The protected file was restored
  exactly and its final SHA-256 is
  `1A73CA051A3BE63084451D3744632F227FB5236BBE3F2334DE20BCF5498D98D9`.
- Complete sanitized evidence is in
  `docs/evidence/antigravity-real-investigation-2026-07-22/report.md`.

## SYN-014C Post-MVP Hardening Slice 3 implementation state

- Provider-session lease subsystem (`SessionLeaseService`, `SessionLeaseStore`, `SessionLeaseRecord`, `SessionProcessIdentity`, `SessionLeaseState`, `SessionLeasePolicy`) implemented under `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\session-leases\<connection>.json`.
- Strong process identity verification via PID, process start time, executable identity, command-line string, connection nonce.
- Suspected-stale and abandonment grace period evaluation with configurable policy (30s heartbeat, 2m suspected stale, 5m abandonment grace period).
- Persisted reconciliation plan/lock/journal (`ReconciliationPlan`, `ReconciliationPlanEntry`, `ReconciliationPlanStore`, `ReconciliationExecutionLock`, `ReconciliationExecutionJournal`) under `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\`.
- CLI command `synesis reconcile` supporting `--dry-run`, `--prepare`, `--show-plan`, `--execute`.
- Ambient `synesis.cancel_lane` MCP tool (bounded reason 1-1000 characters).
- Event-store transitions for `SESSION_ABANDONED`, `TASK_CANCELLATION_REQUESTED`, `TASK_CANCELLED`, `DEPENDENCY_INVALIDATED`, `OWNERSHIP_RELEASED`, and `SESSION_FINALIZED`.
- All 49 Gradle tasks and test suites passed cleanly at CP-0189.

## SYN-014D Post-MVP Hardening Slice 4 implementation state

- Read-only `DoctorService` diagnostics (`synesis doctor`) implementing 38 distinct machine-readable finding codes across Installation, Project/Git, Durable State, Sessions/Ownership/Worktrees, Cleanup/Storage, Admin State, and Provider Config.
- Actionable Doctor severity levels (`INFO`, `WARNING`, `ERROR`, `CRITICAL`), confidence levels (`CONFIRMED`, `HIGH_CONFIDENCE`, `SUSPECTED`, `AMBIGUOUS`), and overall health states (`HEALTHY`, `DEGRADED`, `UNHEALTHY`, `UNSAFE`).
- Read-only guarantee: `DoctorService` performs zero file creations, zero file modifications, zero file deletions, zero process terminations, and zero lock acquisitions.
- Separate, reviewable, narrowly scoped administrative repair system (`synesis repair`): `--dry-run`, `--prepare`, `--show-plan`, `--execute`, `--rollback`.
- Persisted immutable repair plans (`RepairPlan`, `RepairPlanEntry`, `RepairPlanStore`) stored outside control checkout under `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\repair-plans\<plan-id>.json`.
- Repair execution lock (`RepairExecutionLock`) at `%LOCALAPPDATA%\Synesis\workspaces\<project-id>\admin\repair-execution.lock` and append-only execution journal (`RepairExecutionJournal`) at `admin/repair-executions/<execution-id>.jsonl`.
- Pre-mutation backup and exact atomic rollback service (`RepairBackupService`) under `admin/repair-backups/<execution-id>/`.
- MCP tools count remains exactly 10 tools; lifecycle names are explicit and strict.
- All 49 Gradle tasks and test suites passed cleanly at CP-0190.

## SYN-038 historical implementation verification

- Before CP-0458, SYN-038 was the sole active implementation task. Repository inspection found
  the concrete long-lived owner: `CoordinationServeCommand` starts the existing
  `CoordinationHttpServer`; the implementation will retain `ProjectRuntimeHost`
  in that process and add only the Codex loopback route.
- The prerequisite authority workflow is verified in existing code:
  `AgentSessionService.resolveSessionContext`,
  `ProviderSessionBindingService.ensure/find`, and
  `WorkspaceCollaborationService.announce` establish the exact binding,
  participant, WorkIntent, acquired claim, lane/epoch, and isolated worktree.
- No Codex App Server lifecycle classes, route, ledger, or protocol client exist
  yet. Do not claim production reachability or acceptance until they are added
  and tested.

## SYN-029 current verification

- Added `bootstrap/cmd/synesis-mcp` as a native stdio launcher. The Gradle
  distribution task cross-builds Windows x64/ARM64, Linux x64/ARM64, and macOS
  x64/ARM64 artifacts; the installed Windows launcher is copied to
  `bin/synesis-mcp.exe`.
- Provider MCP registrations now point directly at the native launcher. Normal
  CLI and hook launchers remain separate. Codex, Claude, and Antigravity were
  reinstalled against the fresh local distribution without adding duplicate
  provider entries.
- Installer health probe passed MCP `initialize` and `tools/list` with exactly
  10 tools. Provider install remains `DEGRADED` where real provider validation
  is incomplete; this is not transport failure evidence.
- Focused workspace provider/configuration tests pass. Sequential
  `./gradlew.bat check --no-daemon --max-workers=1
  --dependency-verification=strict` passes all 51 actionable tasks. Bootstrap
  `go test -count=1 ./...`, `go vet ./...`, and native launcher tests pass.
- The first full check attempt timed out after a stale decorated MCP tool name
  in bundle smoke; the smoke request now uses the raw prerelease tool name and
  the subsequent full check passes. Real Codex/Antigravity model-driven
  autonomy remains unclaimed pending provider-process acceptance.
- Follow-up provider evidence: Codex CLI with approvals bypassed established a
  real session and called `get_next_action`; the normal read-only invocation
  was cancelled by the provider harness before the MCP call. Antigravity
  `agy --print` returned a generic response without invoking MCP. These are
  provider-boundary results, not evidence against the native launcher.
- Began SYN-030 without changing its task status: `get_next_action` responses
  now carry a stable opaque `actionId`, `delivery=AT_LEAST_ONCE`, and explicit
  acknowledgement metadata. Repeated retrieval is covered by an MCP regression
  and does not consume the underlying durable inbox item. CLI parity now
  includes `synesis collaboration acknowledge --item ...` backed by the same
  exact-caller service.
- The shared workspace reducer now derives workflow types (`IMPLEMENT`,
  `REVIEW_CONTRACT`, `REVISE_SCOPE`, `WAIT`, `PUBLISH`, `INTEGRATION_REPAIR`,
  and `RECOVER`) with strict payload, blockers, permitted operations, and
  retry-safety metadata. Claim overlap now creates idempotent durable inbox
  requests for both owner and contender. Default collaboration announcements
  join one project-level active work group; explicit group IDs still isolate
  independent experiments. Focused reducer, coordination, MCP, and multi-chat
  tests pass.
- The reducer now emits `CLOSE` for terminal lane states and `RECOVER` for
  suspended or recovery-held lanes. Incremental integration consumes only
  projection `readySnapshots()`; integrated snapshots remain audit-visible but
  are not replayed into later candidates. Focused suites and the sequential
  repository check pass with 51 actionable tasks.
- SYN-033 groundwork adds `ProviderProcessSupervisor`, which launches only
  tokenized commands directly in an isolated worktree, observes process loss,
  supports bounded interrupt/close, and starts a continuation only in a
  distinct lane. It deliberately does not transfer claims; signed recovery
  grants and immutable snapshots remain the authority boundary. Focused tests
  and strict workspace Javadocs pass; provider-specific process wiring remains
  unverified. Codex and Claude integrations now expose their documented
  noninteractive argv forms; Antigravity remains explicitly unsupported for
  autonomous launch pending real MCP invocation evidence. The full sequential
  Gradle check passes 51 actionable tasks, and bootstrap Go tests/vet pass. A
  one-shot supervisor exit callback now reports verified process exit without
  changing claims or inferring abandonment; callers must route it through the
  signed lease/recovery policy. `ProviderSupervisionService` now bridges
  supervised launches to the exact session lease, recording child process
  identity and marking only explicit supervisor close as clean shutdown.
  `startWithAutomaticRecovery` now schedules the existing reconciliation plan
  after the configured grace period; no direct claim release or abandonment
  inference is performed by the scheduler.
  Starts are serialized per lane, callbacks carry monotonic process
  generations from the exact exited process, delayed recovery is cancelled and
  generation-checked on reuse or clean close, and lease closure compares the
  expected process ID. The continuation launch bridge validates distinct
  source and target lanes while grant consumption and snapshot restoration
  remain owned by collaboration services.
Recent hardening: durable event appends derive their head from on-disk signed
events, preventing stale provider store instances from overwriting concurrent
coordination events. Integration refreshes pending coordination requests before
attempt allocation, and completed provider bindings are terminalized for the
exact caller while preserving their worktree. Snapshot publication excludes
provider/admin metadata (`AGENTS.md`, `.synesis`, `.codex`, `.claude`, `.agents`).

## SYN-038 implementation verification

- The existing `synesis coordination serve` process now retains
  `ProjectRuntimeHost`, which owns the Codex-only loopback lifecycle route,
  project/binding ownership locks, lifecycle checkpoints, idempotency ledger,
  protocol attachments, evidence, and shutdown/reconciliation behavior.
- `ProviderSessionCommand` establishes authority through the existing
  `AgentSessionService` and `WorkspaceCollaborationService` before freezing the
  exact binding, participant, WorkIntent/claim, lane/epoch, control root,
  worktree, and MCP connection identity into the immutable START envelope.
- The lifecycle implementation enforces durable idempotency before every
  state-changing mutation, immutable transport retries, bounded raw protocol
  frames and HTTP bodies, 256 late-response tombstones, event-driven WAIT
  capacity, 8 MiB generation journals, repeated-discovery hard stop, and
  independent interruption/command-cleanup outcomes.
- Focused lifecycle tests (26 tests), compilation, strict Javadocs, deferred
  validation, and diff checks pass. The sequential root Gradle check completed
  successfully after the existing Git-heavy initialization fixtures, with zero
  module test failures/errors. The disposable real owner now also records WAIT control,
  STEER/INTERRUPT, passive exact-thread resume, explicit continuation,
  duplicate replay, and the installed Codex MCP elicitation boundary. MCP
  command cancellation and normal lane completion remain unproven. See
  `docs/evidence/syn038-real-codex-app-server-acceptance-2026-08-03.md`.

- Harness-restart MCP retry: a fresh installed `synesis-mcp.exe` stdio process
  returned protocol `2025-06-18`, exactly ten tools, and `ensure_session=ready`.
  The callable desktop MCP child was stale and returned an internal
  session-construction error, so it was not used as evidence. The restarted
  production owner passively reconciled the exact stored thread to STOPPED;
  the old claim was already released, and lifecycle STATUS failed closed with
  `lifecycle_claim_not_acquired` rather than guessing continuation authority.
