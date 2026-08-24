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

## CP-0503 post-fix producer-polling diagnostic

The CP-0502 reproduction proved a concrete owner-side projection gap: an
active owner with an issued, current-epoch, unconsumed REVIEW grant targeted
to a peer received ordinary `IMPLEMENT` with no executable continuation. The
narrow fix in `AgentNextActionService` now projects `WAIT` with the exact
grant, WorkGroup, target participant, intent, epoch, and
`review_grant_consumption` context. It grants no mutation authority.

The fresh post-fix project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0503-001` used the
rebuilt current bundle and two independent GPT-5.6 Luna sessions. Both passed
ten-tool `ready / isolated` preflight, held disjoint `todo.py` /
`test_todo.py` claims, and converged on WorkGroup
`49082d5e-ecc5-3503-82fb-3d62f37597c8`.

The owner accepted REVIEW request
`10fe11a8-c4bc-46ae-a11f-cd70489741d2`, the peer consumed grant
`c9cb80ae-679d-3290-902c-c55647723aae`, and the owner remained active through
the exact `snapshot_publication_required` → `finish_lane` projection. Snapshot
`snap_5733de0976ad177cc349e9fa2fbdebcb` was published and integrated.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0503-postfix-diagnostic-2026-08-24.md`.

## Work completed

The CP-0502 owner-side waiting projection is implemented with deterministic
MCP regression coverage. Focused MCP and workspace tests pass, and the
platform bundle was rebuilt. Existing grant, participant, intent, epoch,
snapshot, and WorkGroup checks remain fail-closed.

## Current failures

CP-0503 stopped at the reviewer after it consumed the grant and executed the
exact `WAIT` → `get_next_action` projection twice. It ended before polling
after the producer published the snapshot, so validation, the second lane's
snapshot, cleanup, and WorkGroup closure remain unproven. No exact projected
tool failed; this is agent-compliance evidence, not a new production defect.
The known Git subprocess stall, bootstrap migration failures, and six Doctor
warnings remain separately classified.

The focused Javadocs, deferred/fixture validators, `go vet`, and
`git diff --check` pass. The root check is not green: `:link:formatCheck`
still reports pre-existing trailing whitespace in CP-0488, CP-0489, and the
CP-0494 evidence file, and the run reproduced the Git subprocess stall in
`ProviderApplicationServiceTest` / `ProcessCommandRunner` before it was
bounded and stopped.

## Immediate next action

Run one fresh bounded diagnostic with both agents remaining alive after every
`WAIT` projection and after peer-side publication; capture the first
post-publication reviewer projection and continue only through exact projected
actions. If an agent stops again without a projected action failure, preserve
agent-compliance evidence and do not broaden production code. Do not push or
create SYN-040.

## CP-0501 producer-polling diagnostic

The fresh project
`1a67c646-9725-48ba-b6ec-63618ef2cd89` used the current bundled MCP and two
independent GPT-5.6 Luna sessions. Both reached ten tools and distinct
`ready / isolated` sessions, held disjoint `todo.py` / `test_todo.py` claims,
and converged on WorkGroup
`1f8bc962-fbb5-376b-9f72-1e0b4135a495`.

The diagnostic executed exact REVIEW admission, owner acceptance, and
single-use grant consumption successfully. A then stopped after a normal
`IMPLEMENT` projection with no executable action while B's grant was still
unconsumed. B later consumed grant
`e6b09aa2-0cf8-35de-b80c-1e4180ccb6a7` and received the exact `WAIT` →
`get_next_action` projection, but A was no longer polling to receive
`finish_lane`. No exact projected action failed; no production code changed.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0501-producer-polling-diagnostic-2026-08-24.md`.

The source contract is internally consistent: the owner cannot publish until
the targeted REVIEW grant is consumed, and the reviewer had the next
authorized action. The first stop is therefore agent-compliance evidence, not
a new backend defect. Exact next action: run one fresh bounded diagnostic with
both agents remaining alive through peer-side state changes and collecting the
post-consumption owner projection. Do not broaden lifecycle code or create
SYN-040.

## CP-0500 REVIEW admission idempotency diagnostic

The fresh post-fix diagnostic used project
`5c4700bd-9765-4886-9aea-261bfb65be4a`, the rebuilt current bundled MCP, and
two independent GPT-5.6 Luna sessions. Both exposed ten tools, reached
`ready / isolated`, held disjoint `todo.py` / `test_todo.py` claims, and
converged on WorkGroup `4c0005dc-4358-32b5-922a-3cf554cfb54d`.

The narrow REVIEW admission idempotency fix worked. Repeated execution of
the same projected `request_coordination(work_group_join)` returned the same
request ID `90ab5c3b-e663-4230-94df-5f0077015508`; it did not create duplicate
requests or grants. The run reached exact owner acceptance, grant consumption,
producer `finish_lane`, immutable snapshot publication, integration, and
structured ACCEPT. Evidence:
`docs/evidence/syn039-unattended-todo-cp0500-review-admission-idempotency-2026-08-24.md`.

The first deviation was Agent A ignoring two repeated concrete review-admission
projections after its successful request, then stopping. Agent B later
accepted the request and received grant
`b1b5b243-b6a5-308d-af57-bce3d3fc63d4`, but A was no longer polling to consume
it. The WorkGroup remained ACTIVE with B's active test intent and no B
snapshot. This is agent-compliance evidence; no further production change is
authorized from this run.

## CP-0499 post-fix bounded diagnostic

The fresh CP-0499 diagnostic used project
`ac5d791a-9f5f-419c-8252-5261c090931b`, the current bundled MCP, and two
independent GPT-5.6 Luna sessions. Both agents preflighted exactly ten tools,
reached `ready / isolated`, held disjoint `todo.py` / `test_todo.py` claims,
and converged on WorkGroup
`3621a4f6-6b2b-3379-9174-9cdcb45b8186`.

The post-fix diagnostic reached exact REVIEW admission, owner acceptance,
grant consumption, projected producer `finish_lane`, immutable snapshot
publication, integration, and structured ACCEPT validation. No exact
projected action failed. Evidence:
`docs/evidence/syn039-unattended-todo-cp0499-postfix-diagnostic-2026-08-24.md`.

The first remaining protocol blocker is after ACCEPT: Agent B's own active
intent remains `ANNOUNCED`, the WorkGroup remains `ACTIVE`, and its final
`get_next_action` returns ordinary `IMPLEMENT` with no executable lifecycle
action, so the reviewer lane cannot publish/finish and the WorkGroup cannot
close. The same run also produced three duplicate REVIEW requests and grants
because the same successful admission projection remained actionable; this is
related idempotency/projection evidence, not a grant replay bypass.

The exact next action is to reproduce the post-ACCEPT active-reviewer
no-action state and trace the existing completion/publication projection, with
duplicate REVIEW admission handled in the same narrow trace. Do not broaden
into cleanup, detached-agent retention, ownership redesign, or a new
orchestrator. Do not push or create SYN-040.

## CP-0498 completed-review continuity diagnostic

The fresh CP-0498 diagnostic used project
`ff3603f4-67bd-4972-99d0-c075b7c10c5f`, the current bundled MCP, and two
independent GPT-5.6 Luna sessions with distinct ready/isolated bindings and
disjoint `todo.py` / `test_todo.py` claims. WorkGroup
`1d24011b-99a6-37bd-b56b-ca09eab8edef` reached exact REVIEW admission, grant
consumption, projected `finish_lane`, snapshot publication, integration, stale
reviewer recovery, and exact ACCEPT decisions. The status correction now
reports `workGroupStatus=ACTIVE` when the durable group remains active.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0498-completed-review-continuity-diagnostic-2026-08-24.md`.

The first concrete post-fix defect is completed-participant continuity. B's
lane became `COMPLETED` after its snapshot integrated while A's sibling
implementation lane remained `ACTIVE`. The early terminal return prevented B
from discovering the existing same-WorkGroup REVIEW admission projection, so
A's lane could not receive a grant and publish its snapshot. This is a
protocol defect, not agent non-compliance.

## CP-0497 reviewer-continuity diagnostic

The fresh current-bundle diagnostic used project
`4d0fa215-d2e4-4a72-9a1c-0e7b858a3b1e`, two independent GPT-5.6 Luna sessions,
the same pinned MCP executable, ten tools, distinct ready/isolated bindings,
and disjoint `todo.py` / `test_todo.py` claims. The reviewer-continuity fix
preserved Agent A's participant/session identity across the control checkout
advance and exact `ensure_session` recovery.

The run reached one WorkGroup
`7c5ac4f7-c538-39c2-8e5d-ed9fadbdc771`, exact REVIEW admission, both exact
owner acceptances, grant consumption, exact projected producer
`finish_lane`, immutable snapshot `snap_3eb0df616deb0c00e78540f63877b1c2`,
integration, and two exact projected `review_validation` ACCEPT decisions.
No exact projected action failed. Evidence:
`docs/evidence/syn039-unattended-todo-cp0497-review-continuity-diagnostic-2026-08-24.md`.

The first concrete post-validation defect is inconsistent terminal reporting:
the accepted `respond_coordination` response returned
`workGroupStatus=COMPLETED`, while final durable status remained `ACTIVE` with
Agent A's separate active implementation intent and two duplicate REVIEW
grants. The diagnostic was therefore not a clean product acceptance, and the
second ordinary run was not started. This is a protocol/reporting blocker,
not agent non-compliance; the exact projected actions were followed.

## CP-0494 post-fix review-projection diagnostic

The fresh CP-0494 fixture used the rebuilt current bundle, two independent
GPT-5.6 Luna sessions, the same project root, and distinct ready/isolated
sessions. Project `03dad00b-fbb4-4500-aa9a-22f91c7d7494` reached one shared
WorkGroup `471a4f65-5210-327f-ad5a-ba2897d022ab` with producer claim `todo.py`
at epoch 1. The MCP route was the current bundled executable, protocol
`2025-06-18`, server `0.1.0-SNAPSHOT`, and exactly ten tools.

CP-0493 proved that the reviewer validation projection was not executable:
the exact projected payload included `workGroupId` and
`targetParticipant`, while strict `respond_coordination` correctly rejected
them with `COORDINATION_RESPONSE_FIELD_NOT_ALLOWED:workGroupId`. The smallest
fix now keeps those identifiers in surrounding review context and emits only
the accepted validation fields in the executable payload. Deterministic MCP
coverage executes the projected ACCEPT branch successfully. The same slice
also fixes the CP-0490 Python `__pycache__` artifact-policy failure.

In the post-fix diagnostic, Agent B consumed grant
`2d616273-a235-3cec-b2fd-054a855fb8c6`; Agent A executed the exact projected
`finish_lane` action, publishing snapshot
`snap_3e7c0ee281c5190f43bcd2102a5853f7` and integrating commit
`67542ea641379d5eaef7a6b2b73d97541efd161d` into clean control commit `45fc60a`.
Agent B then received the now-executable exact `review_validation` projection
but chose an unprojected `read_file("todo.py")`, which produced
`workspace_stale`. No validation decision or WorkGroup closure was reached;
this is agent-compliance evidence, not a new production defect.

Evidence: `docs/evidence/syn039-unattended-todo-cp0494-review-projection-2026-08-24.md`.

## CP-0489 role-order diagnostic

The fresh CP-0489 fixture used the current bundled MCP and two independent
GPT-5.6 Luna sessions on project `bceaf899-f1a3-4a65-8538-4f303a072e5d`.
Both sessions reached `ready / isolated` with disjoint exact claims and one
shared WorkGroup `2176bfbd-6199-303f-805c-a91c382b92ff`.

The diagnostic reached exact REVIEW admission, exact owner acceptance for
both requests, grants `215ba3af-5cf9-352a-ac5e-5685438a7d12` and
`d831734a-d597-3457-b817-ae5b3f7e6e70`, and exact consumption of the first
grant. The reviewer correctly received `SNAPSHOT_PENDING` → `wait`. The
producer's last `get_next_action` occurred before that consumption and
returned ordinary `IMPLEMENT` with no executable action; it did not poll
again after the later reviewer action. No projected producer action failed,
so no production defect is proven. Evidence:
`docs/evidence/syn039-unattended-todo-cp0489-role-order-diagnostic-2026-08-24.md`.

Focused SYN-039 tests, affected Javadocs, validators, `go vet`, and
`git diff --check` pass. Bootstrap Go tests retain the three known migration
failures. Full `check` now passes its format/compile/Javadoc/static-analysis
stages and reproduces the known `McpServerTest.setUp` Git subprocess stall at
`ProcessCommandRunner.execute:81`; exact thread evidence is in the evidence
file. Fixture Doctor remains DEGRADED with six warnings, separately
classified.

Exact next action: run a fresh bounded diagnostic that keeps both agents
returning to `get_next_action` after a wait or peer-side state change, then
capture producer snapshot publication and reviewer validation without relay
or manual lifecycle transitions. Do not modify production code unless an
exact projected action fails. Do not push or create SYN-040.

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
`5fe613f`. The CP-0490 Python bytecode-cache artifact policy fix and CP-0493
reviewer-validation MCP schema fix are committed in `706b743`. The strict
review response remains fail-closed, while its executable projection now
matches the accepted `respond_coordination` payload contract. The CP-0496
reviewer recovery-continuity fix is committed in `d578223`; a control-only
checkout advance now preserves reviewer session identity while allocating a
fresh isolated worktree. Deterministic workspace/MCP regressions, focused
Javadocs, bundle rebuild, validators, and diff checks pass. CP-0497 proves
exact admission, grant consumption, exact producer publication, immutable
snapshot creation, integration, reviewer recovery, and exact ACCEPT decisions
on the rebuilt bundle. CP-0498 completed-participant continuity is committed
in `ca6d644`; its deterministic review-only projection and authority tests
pass. CP-0499 then proved the next active-reviewer no-action blocker after
ACCEPT. The bounded evidence is recorded in
`docs/evidence/syn039-unattended-todo-cp0499-postfix-diagnostic-2026-08-24.md`.
The CP-0500 coordination idempotency slice is covered by
`WorkIntentServiceTest`; focused coordination, workspace, MCP, Javadoc,
validator, Doctor, and diff checks pass. The known Git subprocess stall,
bootstrap migration failures, and Doctor warnings remain separate.

## Current failures

CP-0500 verified that repeated REVIEW admission execution is idempotent, but
the bounded agents did not reach clean closure: Agent A ignored two repeated
concrete `request_coordination` projections after its first successful request,
and B's later grant could not be consumed. The WorkGroup remained ACTIVE with
B's active claim and no B snapshot. This is agent-compliance evidence, not a
new lifecycle defect. The recurring Git subprocess stall, bootstrap migration
failures, and six fixture Doctor warnings remain separate.

## Immediate next action

Run the next fresh bounded diagnostic with the same exact-projection rule and
verify that both agents continue polling after an idempotent REVIEW admission
until the second grant is consumed, B publishes its snapshot, validation and
integration complete, and the WorkGroup closes. If an agent again ignores a
concrete projection, preserve that as compliance evidence; do not change
production lifecycle code. Do not broaden cleanup, ownership, Doctor, push, or
create SYN-040.

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
