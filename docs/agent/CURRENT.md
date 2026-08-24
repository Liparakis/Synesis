# Current Task

## Identity

- Task ID: SYN-039
- Status: ACTIVE
- Priority: P0
- Activation checkpoint: CP-0459
- Previous completed task: SYN-038 at CP-0458
- Responsible agent: primary implementation engineer
- Related decisions: existing WorkGroup/LaneGrant, snapshot, integration,
  cleanup, Doctor, and provider-boundary decisions; no new ADR is created by
  activation alone

## CP-0487 role-order diagnostic

The fresh CP-0487 fixture reached one shared WorkGroup, exact REVIEW admission,
two exact owner `respond_coordination` acceptances, and exact single-use grant
consumption. Agent B established the WorkGroup first, so its test intent was
the producer/owner and Agent A's implementation intent became the reviewer.
After A consumed the grant, A correctly received `SNAPSHOT_PENDING` → `wait`;
the producer had not yet been queried after grant consumption. No projected
producer publication action failed or was missing when requested. This is
role-order/agent-compliance evidence, not a new production defect.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0487-role-order-diagnostic-2026-08-24.md`.

## CP-0486 exact-rule diagnostic

The agent-facing contract clarification is now present in the generated
`AGENTS.md` and provider manual. A fresh external-harness fixture used the
current bundled MCP, the same initialized project, and two distinct
`ready / isolated` sessions with disjoint `todo.py` / `test_todo.py` claims.
The sessions converged on WorkGroup
`9527b8ec-0971-3f33-995c-ac0833d506c7`.

Agent A received and executed the exact projected
`request_coordination(work_group_join)` action, then implemented `todo.py`
without calling unprojected `finish_lane`. Agent B instead supplied an
unprojected `integrationCheck` request while its own isolated worktree lacked
A's unintegrated implementation. Synesis correctly returned
`integration_conflict` / `TESTS_FAILED` and `request_human_help`. No exact
projected lifecycle action failed, and no grant, snapshot, validation,
integration, or closure state was reached. This is agent-compliance evidence,
not a new production lifecycle defect.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0486-exact-rule-diagnostic-2026-08-24.md`.

## CP-0485 clean-harness exact-rule diagnostic

The clean-harness rerun used a fresh Git + Synesis project and the rebuilt
bundled MCP (`0.1.0-SNAPSHOT`, SHA-256
`27D6BE820B82A8C8CED3966DF9DD2A0AEE1FC897659F46462D8B7166D46CF7E3`). Both
GPT-5.6 Luna sessions reached `ready / isolated` on the same project with
distinct identities, disjoint `todo.py` / `test_todo.py` claims, and one
shared WorkGroup. The harness was outside the project and the control
checkout was clean before launch.

WorkGroup `a5b6fdc4-51cb-3398-be5a-76126258984f` was reached. The reviewer
received the exact `REVIEW_ADMISSION_REQUIRED` projection and executed the
projected `request_coordination(work_group_join)` action. The owner executed
both exact projected `respond_coordination` acceptance actions, producing
requests `4a2d5e88-22b4-40d6-95b3-2053472487b0` and
`e4617626-b3b8-4772-99d1-57b3b7ffea03`, and grants
`ce12bf95-e493-38c7-a75b-fc78f5b03782` and
`7b4f4964-8631-3b80-bb99-0552b05c67d7` targeted to the reviewer at epoch 1.

The owner then violated the diagnostic rule by selecting unprojected
`finish_lane` while `get_next_action` still returned ordinary `IMPLEMENT`.
That is agent-compliance evidence, not a production defect. The reviewer later
received the concrete recovery projection `workspace_stale` →
`ensure_session`, executed `ensure_session({})` twice exactly, and both calls
returned `internal_failure` / `request_human_help`. No validation decision or
WorkGroup closure was reached. The integrated control checkout was clean at
`166228f5a6b17208175231984f7cbce9e4090dfc`, but the WorkGroup remained ACTIVE
with the two pending REVIEW grants.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0485-exact-rule-diagnostic-2026-08-24.md`.

## Work completed

The reviewer-first snapshot-admission projection fix is committed as
`5fe613f`. The CP-0485 stale-recovery trace confirmed that the existing
`WORKSPACE_STALE_DIRTY` path fails closed when an unprojected owner transition
advances control while the reviewer's worktree is dirty. The smallest
agent-facing contract clarification is implemented in
`ProviderManualService` and generated `AGENTS.md`, with deterministic tests
covering the distinction between ordinary `IMPLEMENT` and a concrete
projected lifecycle action. Focused workspace/MCP regression coverage,
Javadocs, bundle rebuild, validators, and `git diff --check` pass. CP-0486
verified that Agent A obeyed the clarified contract and did not call
unprojected `finish_lane`.

## Current failures

CP-0487 reached exact REVIEW admission and grant consumption, but the test
agent established the WorkGroup first. The implementation agent therefore
became the reviewer and correctly waited for a snapshot from the test-agent
producer. The producer's last `get_next_action` occurred before grant
consumption and returned ordinary `IMPLEMENT`; it did not poll again after the
reviewer consumed the grant. No exact projected action failed, and no new
production defect is proven. The duplicate retry-safe review requests/grants
are recorded for later idempotency/cleanup review. The recurring Git
subprocess stall, bootstrap migration failures, and six CP-0487 fixture Doctor
warnings remain separately classified.

## Immediate next action

Run a fresh bounded two-agent diagnostic using the current bundle, with the
implementation agent launched first so it establishes the producer WorkGroup.
After both sessions are ready, do not relay or trigger transitions. Capture
every projection/action pair through producer snapshot publication and
reviewer validation. If an exact projected action fails, implement only that
proven narrow protocol defect; if the agents fail to poll or choose an
unprojected action, preserve it as compliance evidence. Only after this
diagnostic completes may the second ordinary unattended acceptance run. Do not
push or create SYN-040.

## CP-0480 convergence projection slice

The CP-0480 diagnostic and ordinary runs did not reproduce a backend
WorkGroup split. Claim-bearing sessions with complementary exact scopes
converged on one deterministic active WorkGroup. The ordinary run exposed the
existing WorkGroup and exact REVIEW admission payload, but the executable
workflow reduced `request_coordination` to empty arguments. `AgentNextActionService`
now promotes the selected review protocol kind/payload to the response root,
and `AgentWorkflowReducer` copies it into exact executable arguments. Focused
MCP/workspace tests pass. Evidence:
`docs/evidence/syn039-unattended-todo-cp0480-convergence-projection-2026-08-24.md`.

Immediate next action: rerun a fresh post-fix diagnostic and ordinary
acceptance, then preserve the first lifecycle blocker after exact REVIEW
admission. Do not broaden cleanup, integration, Doctor, or ownership behavior.

## Objective

Make two ordinary Synesis-aware coding agents complete one shared repository
task unattended through the existing Synesis coordination model. Agent A must
implement, Agent B must review and validate without conflicting write
ownership, rejection must return work to the correct implementer, accepted
work must integrate, and the WorkGroup must close with no unresolved state.

## Activation boundary

- The reviewer-validation and producer-publication slices are implemented and
  under verification; reviewer admission, grant consumption, and the explicit
  owner publication action are now covered.
- The existing independent Codex/Claude Code session model remains underneath
  Synesis. Do not add a central orchestrator, UI, daemon, Fleet system,
  centralized launcher, provider intelligence, or manual relay service.
- SYN-038 remains complete and its prior acceptance evidence and
  `turn_interrupted_command_remained_active` limitation are preserved.

## Evidence and known gap

- Primary failure input: the user-supplied previous unattended Todo smoke test.
- The raw Todo smoke-test artifact was not present in the checkout. The
  reproduction is now captured in
  `docs/evidence/syn039-unattended-todo-baseline-2026-08-22.md`; raw Codex
  JSONL remains in the disposable fixture's `baseline-logs` directory.
- Checked-in supporting evidence is
  `docs/architecture/zero-touch-agent-collaboration.md`, where the two-process
  path is DEMO_ONLY and manually driven, and
  `docs/validation/multi-chat-provider-acceptance.md`, which does not claim
  autonomous end-to-end integration.

## Acceptance target

- Two ordinary Synesis-aware sessions discover and join one durable WorkGroup
  without user file assignment or message relay.
- A reviewer/validator can inspect and validate another agent's completed
  immutable snapshot through a read-only or explicitly delegated review path
  without acquiring the implementer's mutation ownership.
- Validation emits structured accept/reject evidence. Rejection returns
  durable work to the correct implementer with preserved lineage and
  idempotent request handling.
- Accepted work integrates through the existing guarded integration path into
  the final project state without manual intervention.
- Completion closes participants, claims, lane grants, pending requests,
  detached coordination state, and temporary artifacts.
- Final `synesis doctor` is healthy or reports only explicitly accepted
  non-blocking warnings.
- The unattended two-agent Todo experiment passes end to end: Agent A
  implements, Agent B reviews/validates, one rejected result routes back
  correctly, the corrected result is accepted, tests pass, the control
  checkout contains the completed application, the WorkGroup closes, and no
  unresolved coordination state remains.
- The existing ten-tool MCP boundary and provider model remain intact.

## Baseline result

The real two-session reproduction failed. Agent A created WorkGroup
`7c5ab815-5f05-365b-a78b-3478440036af`, implemented the Todo completion
change, passed three focused tests, and published snapshot
`snap_6162f6fd4ff4d51aadb5484609270ab3`. Integration failed with
`integration_failed` / `TESTS_FAILED`. Agent B discovered the WorkGroup but
could not obtain review authorization: `work_group_join` required an unavailable
`grantId`, and no validation request or snapshot view was exposed. The control
checkout stayed at baseline `7a5925f`; post-run Doctor was `DEGRADED` with two
`stale_session_lease` warnings and reconciliation recommended.

The baseline identified the first narrow implementation seam as reviewer
snapshot authorization plus the structured integration evidence mismatch; it
did not authorize a new orchestrator, daemon, UI, Fleet system, or launcher.

## Work completed

Implemented the first two defects and deterministic regressions. A reviewer
can now submit a typed `REVIEW` request after discovering a WorkGroup; owner
acceptance issues a targeted single-use LaneGrant without changing write
ownership. Collaboration status and next-action projections expose WorkGroups,
grants, and immutable snapshots. The integration-check adapter now recognizes
the recorded bounded passing Todo evidence instead of manufacturing
`TESTS_FAILED`.

The prior implementation and rerun evidence is recorded in
`docs/evidence/syn039-unattended-todo-review-validation-2026-08-22.md`. The
producer-publication slice is recorded in
`docs/evidence/syn039-unattended-todo-snapshot-publication-2026-08-22.md`.

The CP-0475 publication slice fixed the executable workflow projection: the
existing `nextProtocolPayload.summary` is now passed to `finish_lane` instead
of being discarded. A deterministic MCP fixture reached real `finish_lane`
execution, produced a `PUBLISHED` immutable snapshot, and exposed its ID in
the reviewer coordination projection. Evidence is recorded in
`docs/evidence/syn039-unattended-todo-snapshot-publication-cp0475-2026-08-24.md`.

## Current failures

The CP-0475 deterministic reproduction confirmed that the publication action
was emitted with an empty executable argument map even though the protocol
payload supplied the required summary. The minimal reducer fix is verified.
The first fresh two-agent harness attempt then failed before coordination:
Agent A remained at `workspace_not_ready` / `ensure_session`, while Agent B
reached a ready session with no peer WorkGroup and canceled its lane. A second
fresh attempt remained non-terminal for a bounded five-minute observation and
was stopped. These are harness/configuration failures, not a valid lifecycle
result and not a reason to change production readiness behavior.

The CP-0476 fresh fixture passed an explicit two-connection control preflight
with the current bundled MCP, but both independent agent harnesses failed
three times at `ensure_session(refresh=true)` with
`retry_required / workspace_not_ready / ensure_session`. No Todo work or
coordination lifecycle state was created. The first blocker is therefore the
agent-route configuration/session mismatch; it is not yet a production
readiness defect because the same executable and project passed through the
explicit control route. Evidence:
`docs/evidence/syn039-unattended-todo-harness-preflight-cp0476-2026-08-24.md`.

CP-0477 then corrected the harness with explicit per-agent current-bundle
configuration and reached a real WorkGroup. The owner implemented the Todo
completion change and passed 3 tests, but called `finish_lane` before review
readiness and received `task_not_ready / retry`. The reviewer saw the exact
`request_coordination(work_group_join)` review-admission projection, attempted
an invalid inbox acknowledgement, received `policy_denied / INBOX_ITEM_NOT_FOUND`,
and stopped. No grant, snapshot, validation, integration, or closure was
reached. This is agent action ordering, not a proven production protocol
defect. Evidence:
`docs/evidence/syn039-unattended-todo-cp0477-2026-08-24.md`.

The serialized root `check` remains incomplete. A focused
`:mcp:test --tests org.synesis.mcp.application.McpServerTest` reproduced the
Git subprocess stall at `McpServerTest.java:181`; worker `24912` was blocked
through `AgentNextActionService` → `RepositoryPrivateStateService` →
`GitProcessRunner` → `ProcessCommandRunner`. It was stopped only after thread
and child-process evidence was captured. Doctor remains `DEGRADED` with the
existing documented warnings.

The corrected CP-0481 diagnostic used the current bundled MCP with two
distinct ready/isolated sessions and disjoint claims. The agents converged on
WorkGroup `f0666aa0-31db-3025-a7e7-2e46f3fad1de`; Agent A published
`snap_0c58f76fb959553d7d64d64ce7b0d21c` but selected unprojected
`finish_lane`, which returned `integration_failed`. No REVIEW action was
projected and Agent B never reached admission. This is agent-compliance
evidence, not a production defect. Evidence:
`docs/evidence/syn039-unattended-todo-cp0481-postfix-review-admission-2026-08-24.md`.

The CP-0482 diagnostic then required exact execution of every concrete
projection. Both agents completed visible work and repeatedly received
ordinary `IMPLEMENT` with no executable tool or arguments. No REVIEW,
publication, grant, validation, or integration action was exposed. This is a
confirmed projection defect, not agent noncompliance. Evidence:
`docs/evidence/syn039-unattended-todo-cp0482-actionable-projection-2026-08-24.md`.

## CP-0483 active-reviewer projection rerun

The fresh post-fix diagnostic used the current bundled MCP, two distinct
ready/isolated sessions, and disjoint `todo.py` / `test_todo.py` claims. Both
intents converged on WorkGroup `af1807bc-ab46-3c98-8908-7073a807a7a6`. Agent A
published snapshot `snap_2ecbf452a75a69a8048168e6a1f177f2`, but the reviewer
intent was recorded first. The reviewer then repeatedly received ordinary
`IMPLEMENT` with no executable action even though the implementation snapshot
was visible. No REVIEW request, grant, validation, integration, or closure was
created. Evidence:
`docs/evidence/syn039-unattended-todo-cp0483-active-reviewer-projection-2026-08-24.md`.

The active-peer admission slice is verified by focused workspace/MCP tests,
but the live run proves an order-dependent producer/reviewer selection gap.
The known unprojected `finish_lane` calls remain agent-compliance evidence.

## Implementation order

1. Reproduce and capture the supplied unattended Todo failure.
2. Implement reviewer admission and the integration evidence fix.
3. Re-run the admitted-review path through the new owner `finish_lane` action;
   if reached, preserve the next lifecycle failure as a bounded blocker.
4. Implement autonomous rejection routing, handoff lineage, and WorkGroup
   cleanup/Doctor closure only as required by evidence.
5. Rerun the same unattended Todo experiment with no babysitting and record
   the complete evidence.

## Immediate next action

Trace the order-dependent review-admission owner selection in
`AgentNextActionService.reviewActions`. Reproduce the reviewer-first state with
a deterministic fixture, then implement only the smallest existing-model fix
that lets the reviewer discover the implementation snapshot and exact
`request_coordination(work_group_join)` action using WorkGroup, intent,
claim-epoch, and snapshot provenance. Preserve fail-closed ownership and do
not add roles, orchestration, cleanup, or a side channel. Keep the Git stall,
bootstrap migration failures, and Doctor warnings separate. Do not push or
create SYN-040.

## CP-0471 owner REVIEW-acceptance slice

The owner-side projection defect is fixed. For a pending REVIEW request,
`get_next_action` now includes the exact request-specific strict
`respond_coordination` payload and WorkGroup/intent/claim-epoch context;
`AgentWorkflowReducer` carries that payload into the existing executable
workflow action. The collaboration service still performs authorization and
replay checks. Deterministic MCP, workspace, coordination, Javadocs,
validator, Go, vet, and diff checks pass. Evidence:
`docs/evidence/syn039-unattended-todo-owner-acceptance-2026-08-22.md`.

The fresh app-managed two-agent rerun did not reach this state: initial
ownership admission returned the existing typed `overlapping_claim` blocker
because another participant already held both Todo paths. It is not a valid
end-to-end result for this slice. Root `check` remains incomplete at the known
Git subprocess stall in `WorkspaceCliTest.setUp:74`; Doctor remains DEGRADED.

Immediate next action: run the exact fresh two-agent Todo acceptance with
isolated initial ownership, then verify autonomous owner acceptance and
preserve the next lifecycle failure. Do not broaden SYN-039 or create SYN-040.

## CP-0472 unattended acceptance result

The fresh two-agent run is recorded in
`docs/evidence/syn039-unattended-todo-workspace-not-ready-2026-08-23.md`.
Both independent Luna High agents stopped at the same typed
`workspace_not_ready` → `ensure_session` recovery projection. No WorkGroup,
claim, request, grant, snapshot, validation, integration, or closure state was
created. The fixture remained at coordination sequence zero with zero tasks and
zero ownerships. This is the first blocker for this run, but it is currently
classified as per-project MCP/session readiness until reproduced
deterministically outside the agent harness. No production behavior was
changed.

## CP-0473 workspace-readiness implementation and rerun

The readiness trace identified that Codex's managed global MCP entry omitted
the initialized project root. `ensure_session` therefore depended on the MCP
process directory when a provider did not send MCP roots. Codex installation
now writes the existing `--project <root>` argument through the explicit-root
configuration path. Fresh provider installation, repeated session ensure, and
two independent bindings are covered by deterministic tests. Commit:
`bea47c4`.

The direct MCP process with the generated project-pinned entry returned
`ready/isolated`. The fresh unattended CP-0473 rerun still stopped before
coordination because the agent harness reported an incompatible/stale MCP
distribution and project-schema-v2 readiness failure. No lifecycle state was
created. Evidence:
`docs/evidence/syn039-workspace-readiness-cp0473-2026-08-23.md`.

## CP-0474 current-bundle unattended acceptance

The fresh acceptance used two independent GPT-5.6 Luna High agents and the
rebuilt Windows platform bundle. Both MCP processes launched the current
`synesis-mcp.exe` with the exact disposable project root. Initialize reported
protocol `2025-06-18`, the catalog contained exactly ten tools, and both agents
reached `ready / isolated`.

The run reached the real lifecycle: Agent B discovered WorkGroup
`33e8329c-fd66-3174-9e3f-f115f6dae550`, autonomously obtained and consumed
REVIEW grant `496f1893-ca32-3939-82a1-24f860dea86a`, and did not take write
ownership. Agent A implemented the Todo operation and passed four pytest tests,
but its projected `PUBLISH` action remained blocked by
`snapshot_publication_required`; repeated `finish_lane` returned
`task_not_ready` / `retry`. No snapshot, validation, integration, or closure
was reached. Evidence:
`docs/evidence/syn039-unattended-todo-cp0474-2026-08-24.md`.

Immediate next action: reproduce the owner-side `PUBLISH` /
`snapshot_publication_required` stop deterministically and trace why the
implementer does not execute the already-projected snapshot-publication path.
Implement only that narrow producer transition if the evidence confirms a
protocol defect. Keep cleanup, Doctor, ownership, integration redesign, the
Git stall, and bootstrap migration failures separate; do not create SYN-040.

## CP-0475 snapshot-publication projection slice

The exact contradiction was reproduced: `get_next_action` projected
`snapshot_publication_required` and `nextProtocolPayload.summary`, while the
workflow reducer emitted empty `finish_lane` arguments. The reducer now carries
that existing payload into the executable action. The deterministic fixture
verified the exact WorkGroup, intent, claim epoch, and summary, then executed
the real MCP `finish_lane` path and observed a `PUBLISHED` immutable snapshot
visible in reviewer coordination status. Focused MCP/workspace tests and
Javadocs pass.

The fresh CP-0475 agent harness attempts did not reach a valid shared
WorkGroup: the first had one readiness failure and one isolated peer with no
WorkGroup; the second remained non-terminal and was stopped after bounded
observation. Evidence:
`docs/evidence/syn039-unattended-todo-snapshot-publication-cp0475-2026-08-24.md`.

Immediate next action: run the exact unattended two-agent Todo acceptance with
both MCP processes independently verified against the current bundle and
project root. Preserve the first post-publication lifecycle blocker if the run
reaches it; do not modify review, cleanup, Doctor, or integration behavior
speculatively.

## CP-0477 explicit harness and lifecycle rerun

The CP-0477 comparison confirmed that one ordinary multi-agent route selected a
stale installed launcher without `--project`, while a pinned current-bundle
route failed when its disposable project had disappeared. The acceptance
harness was corrected with explicit per-agent current-bundle, project,
provider, and connection-instance overrides; no production code changed.

Both explicit preflight agents passed. The unattended lifecycle reached
WorkGroup `62f4a6d0-0061-3e3d-8cc5-7536b556782c`, intent
`8fcbab57-9293-3321-8945-e5a5fd4af6b9`, claim epoch 1, and a passing 3-test
Todo implementation. The owner called `finish_lane` before review readiness
and received `task_not_ready / retry`. The reviewer observed the exact
`REVIEW_ADMISSION_REQUIRED → request_coordination(work_group_join)` projection,
attempted an invalid inbox acknowledgement, received
`policy_denied / INBOX_ITEM_NOT_FOUND`, and stopped. No grant, snapshot,
validation, integration, or closure was reached. This is agent action ordering,
not a proven production protocol defect. Evidence:
`docs/evidence/syn039-unattended-todo-cp0477-2026-08-24.md`.

## CP-0476 harness preflight

Evidence is recorded in
`docs/evidence/syn039-unattended-todo-harness-preflight-cp0476-2026-08-24.md`.
The fresh fixture and exact repository-bundled MCP passed an explicit
two-connection control preflight: protocol `2025-06-18`, exactly ten tools,
the same initialized project ID, distinct isolated worktrees, and
`ensure_session(refresh=true)=ready`.

Both independent `gpt-5.6-luna` agent harnesses nevertheless failed their
required preflight three times at `ensure_session(refresh=true)` with the
typed state `retry_required / workspace_not_ready / ensure_session`. Neither
agent began Todo work; coordination sequence remained zero and no WorkGroup,
claim, request, grant, snapshot, validation, integration, or closure state was
created. This is the first CP-0476 blocker and is not evidence for changing
production readiness or lifecycle behavior because the same bundled executable
and project succeed through the explicit control invocation.

Exact next action: capture the effective MCP executable, startup version/commit,
project arguments, connection identity, and readiness trace from the agent
route itself, then reconcile that route with the passing control invocation.
Keep the Git subprocess stall, bootstrap migration failures, and Doctor
warnings separate. Do not push or create SYN-040.

## CP-0479 agent contract clarification and acceptance reruns

The CP-0478 audit found an ambiguous agent-facing gap: `IMPLEMENT` with no
concrete `recommendedTool` and `arguments` did not explicitly mean continue
ordinary coding in the visible assigned worktree. The managed provider manual
and `get_next_action` MCP description now state that behavior, explicitly
forbid `.synesis/**` inspection through workspace file tools, and require exact
execution only when a concrete tool and arguments are projected. Deterministic
catalog/manual tests pass; path protection and lifecycle semantics are
unchanged.

The bounded CP-0479 diagnostic reached one shared WorkGroup, review admission,
review grants, a passing four-test implementation, snapshot publication, and
integrated control checkout commit `24ed805`. The owner still selected
unprojected `finish_lane` once and received `task_not_ready / retry` before a
later retry succeeded. No structured reviewer ACCEPT/REJECT decision was
captured, and three managed worktrees remained. Evidence:
`docs/evidence/syn039-unattended-todo-cp0479-contract-and-ordinary-acceptance-2026-08-24.md`.

The required second ordinary acceptance did not form a shared WorkGroup. Both
agents independently published snapshots, each reached `integration_blocked`,
and the control checkout stayed at its managed baseline. One agent's
non-projected integration-check facts reported pytest green but received
`integration_conflict / TESTS_FAILED`; this is preserved as an observation,
not a production-fix authorization. The next concrete blocker is ordinary
agent coordination discoverability/compliance, with integration classification
still secondary.

Both fixtures reported coordination sequence zero, zero tasks, and zero
ownerships after the runs. Doctor remained `DEGRADED` with six warnings. The
Git subprocess stall and bootstrap migration failures remain separate.

Exact next action: preserve CP-0479 and design the smallest evidence-led slice
for ordinary peer/WorkGroup discovery and convergence, without changing
hidden-path protection or adding orchestration. Do not push or create SYN-040.

## CP-0481 post-fix diagnostic

The fresh corrected fixture `syn039-cp0480-006` used the current bundled MCP,
the same initialized project, distinct ready/isolated sessions, and disjoint
`todo.py` / `test_todo.py` claims. Both agents converged on WorkGroup
`f0666aa0-31db-3025-a7e7-2e46f3fad1de`. Agent A published snapshot
`snap_0c58f76fb959553d7d64d64ce7b0d21c`, but selected unprojected
`finish_lane`; integration returned `integration_failed` and no REVIEW action
was projected. Evidence:
`docs/evidence/syn039-unattended-todo-cp0481-postfix-review-admission-2026-08-24.md`.

This is agent action/compliance evidence, not a production integration defect.
The next bounded diagnostic must execute every concrete projected action
exactly and preserve the first post-publication result. Do not push or create
SYN-040.

## CP-0482 actionable-projection defect

The fresh bounded diagnostic `syn039-cp0481-001` used the current bundled MCP,
two ready/isolated sessions, and disjoint claims. Both agents converged on
WorkGroup `ffd58516-2313-3ccc-a402-b20c921d2f8f`, completed visible work, and
obeyed the exact-action rule. Repeated `get_next_action` calls still exposed
ordinary `IMPLEMENT` with no concrete tool or arguments, while no REVIEW,
publication, grant, validation, or other progress action was projected. This
is the first confirmed CP-0482 production projection defect. Evidence:
`docs/evidence/syn039-unattended-todo-cp0482-actionable-projection-2026-08-24.md`.

## CP-0478 exact-action protocol diagnostic

The fresh CP-0478 fixture used the current bundled MCP, explicit project pin,
distinct connection IDs, exactly ten tools, and two `ready / isolated`
sessions. Both agents reached an initial `IMPLEMENT` projection, but no
specific lifecycle tool or arguments were projected because no WorkGroup yet
existed. Agent A then selected `read_file(".synesis/project.json")` and Agent
B selected the same hidden-path read; both received `blocked / invalid_path`.
Agent B confirmed the metadata through the valid command
`git show HEAD:.synesis/project.json` and stopped. No files or coordination
state changed. Evidence:
`docs/evidence/syn039-unattended-todo-cp0478-protocol-diagnostic-2026-08-24.md`.

This is an agent-selected repository-inspection failure, not an exact
projected lifecycle action failure and not a proven production defect. No
second ordinary-agent acceptance was run. Coordination sequence remained
zero with no WorkGroup, claims, requests, grants, snapshots, validation, or
integration. Doctor was `DEGRADED` with six existing warnings; the Git
subprocess stall, bootstrap migration failures, and Doctor findings remain
separate.

Exact next action: preserve CP-0478, assess the hidden metadata path contract,
and only rerun a diagnostic after the initial agent inspection uses valid
repository operations. Do not modify production lifecycle code, push, or
create SYN-040.
