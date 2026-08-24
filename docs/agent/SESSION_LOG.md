# 2026-08-24 — SYN-039 CP-0524 clean-recovery identity fix diagnostic

- CP-0523's concrete recovery trace showed a clean worker already at the
  advanced control HEAD being rebound to a new provider session, which
  stranded its durable active intent and review grant on the old participant.
- Added the narrow existing-model fix in `dd9f0eb`: preserve session identity
  when the clean worker already contains the control HEAD, while continuing to
  allocate a fresh isolated worktree. The deterministic workspace regression
  and focused coordination/MCP tests pass.
- Rebuilt the current bundled MCP. Fresh CP-0524 exact-action diagnostic
  reached two ready/isolated peers, disjoint claims, one WorkGroup, exact
  REVIEW admission, owner acceptance, and grant issuance. Both agents then
  stopped during continued polling before implementation/snapshot work; no
  exact projected action failed. Evidence:
  `docs/evidence/syn039-unattended-todo-cp0524-recovery-fix-diagnostic-2026-08-24.md`.
- Doctor remains DEGRADED with six warnings. The Git subprocess stall,
  bootstrap migration failures, and agent-engagement boundary remain separate.
- Exact next action: run one fresh ordinary unattended two-agent Todo
  acceptance with no manual relay or lifecycle prompting. Do not push or create
  SYN-040.

# 2026-08-24 — SYN-039 CP-0520 post-fix exact-rule diagnostic

- Implemented the smallest stale-dirty continuation projection in
  `AgentNextActionService`; the deterministic pending-owner-response regression
  is green and preserves the dirty binding/worktree.
- Focused MCP/workspace tests, Javadocs, validators, bundle rebuild, Go vet,
  and diff checks passed. Bootstrap Go tests still reproduce the three known
  migration failures.
- Fresh project `syn039-diagnostic-cp0520-001` used the rebuilt current MCP and
  two independent GPT-5.6 Luna High Codex processes. Both reached ready/
  isolated, disjoint claims, one WorkGroup, exact REVIEW admission, both
  single-use grants, both snapshots, both integrations, and one structured
  ACCEPT. Control ended clean at `2fb8168`.
- The first run stop was agent engagement after exact WAIT polling: A ended
  before observing B's later snapshot, so reciprocal validation and WorkGroup
  closure were not reached. Malformed arguments were fail-closed compliance
  evidence; no exact projected action failed.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0520-stale-projection-diagnostic-2026-08-24.md`.
- Exact next action: run a fresh ordinary two-agent acceptance without the
  diagnostic lifecycle rule and preserve the first closure or compliance
  boundary. Do not relay, push, broaden SYN-039, or create SYN-040.

# Session Log

## 2026-08-24 — SYN-039 CP-0521 ordinary acceptance and CP-0522 diagnostic

- Fresh ordinary project `syn039-ordinary-cp0521-001` used two independent
  GPT-5.6 Luna High Codex sessions, the current bundled MCP, and complementary
  `todo.py`/`test_todo.py` prompts. It reached one WorkGroup, exact REVIEW
  admission, grant consumption, A's snapshot publication and integration, and
  structured ACCEPT. B remained in reciprocal grant WAIT after A ended before
  polling the later grant targeted to A. Evidence:
  `docs/evidence/syn039-unattended-todo-cp0521-ordinary-2026-08-24.md`.
- Fresh CP-0522 exact-action project used the same MCP and explicit exact-call
  rule. B changed the projected REVIEW intent ID; the exact re-projection was
  valid but the altered call returned `INTENT_NOT_FOUND` twice. No production
  code changed and no exact projected action failed. Evidence:
  `docs/evidence/syn039-unattended-todo-cp0522-exact-rule-diagnostic-2026-08-24.md`.
- Doctor remained DEGRADED with six separately classified warnings. The root
  Git subprocess stall and bootstrap migration failures remain independent.
- Exact next action: run a fresh bounded exact-action diagnostic with no
  manual relay and preserve the first unchanged projected-action failure or
  missing usable action. Do not push or create SYN-040.

# 2026-08-24 — SYN-039 CP-0519 exact-rule diagnostic

- Fresh Git + Synesis Todo fixture used the current bundled MCP and two
  independent GPT-5.6 Luna High Codex processes with complementary
  `todo.py`/`test_todo.py` claims. Both reached ready/isolated and one shared
  WorkGroup without relay.
- Exact REVIEW admission, owner acceptance, single-use grant consumption,
  producer `finish_lane`, immutable snapshot publication, integration, and
  structured ACCEPT passed. A's snapshot
  `snap_48423ea02f57776f0064595b971197ab` integrated at control commit
  `2563b0c`.
- First genuine blocker: after ACCEPT, B's exact projected
  `ensure_session({})` for `workspace_stale` returned
  `internal_failure / request_human_help` while B's assigned
  `test_todo.py` worktree remained dirty. The WorkGroup remained ACTIVE and B
  could not publish its own work. This is a concrete stale-dirty continuation
  gap, not an ignored projection.
- Focused MCP/workspace tests, deferred/fixture validators, and
  `git diff --check` passed. Doctor remains DEGRADED with six unrelated
  warnings; root Git stall, bootstrap migrations, and document format
  findings remain separate.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0519-exact-rule-diagnostic-2026-08-24.md`.
- Exact next action: reproduce and fix only the post-ACCEPT dirty-lane
  continuation projection, then rebuild and rerun the diagnostic. Do not
  relay, push, or create SYN-040.

# 2026-08-24 — SYN-039 CP-0517 dirty-review continuation and diagnostic

- Implemented the smallest stale-reviewer continuation slice. A dirty bound
  reviewer whose control checkout advanced can receive only the existing
  grant-backed durable review projection; review validation remains fenced by
  exact participant, grant, intent, epoch, and immutable snapshot authority.
  Clean stale recovery and dirty-worktree refusal remain fail-closed.
- Added and passed deterministic coverage for dirty reviewer ACCEPT, preserved
  worktree/binding, and existing clean recovery. Focused tests, Javadocs,
  validators, bundle rebuild, and diff checks passed.
- Fresh exact-action diagnostic used two independent GPT-5.6 Luna sessions,
  current bundled MCP, one project, disjoint claims, and one WorkGroup. B
  executed the exact projected REVIEW admission request. A's last poll was
  before that request existed and its Codex turn ended; no later concrete
  owner action was ignored. WorkGroup remained ACTIVE with no grant, snapshot,
  validation, integration, or closure.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0517-dirty-review-fix-diagnostic-2026-08-24.md`.
- Full root check separately reproduced the Git subprocess startup stall;
  bootstrap migration failures, Doctor warnings, and existing document
  trailing-whitespace findings remain separate.
- Exact next action: run a fresh completely ordinary two-agent acceptance
  without lifecycle-conformance instructions and preserve the first concrete
  post-admission result. Do not relay, push, or create SYN-040.

# 2026-08-24 — SYN-039 CP-0516 producer-first exact-action diagnostic

- Fresh producer-first diagnostic used the current bundled MCP and two
  independent GPT-5.6 Luna agents against project
  `84886099-22fe-489a-ae5c-94fcb6159b9d`. Both claim-bearing sessions were
  ready/isolated with disjoint `todo.py` and `test_todo.py` claims, and one
  WorkGroup formed without relay.
- Exact REVIEW admission, owner acceptance, single-use grant consumption,
  producer `snapshot_publication_required`, exact `finish_lane`, immutable
  snapshot `snap_171a6f766e26454cf60e6cebc3106f63`, and integration passed.
- The first later blocker was reviewer B's exact projected
  `ensure_session({})` after control advanced. B's dirty worktree correctly
  triggered the fail-closed `WORKSPACE_STALE_DIRTY` guard, but the agent-facing
  result was `internal_failure / request_human_help`; no validation or closure
  was reached. This is a concrete stale-reviewer continuation gap, not agent
  projection noncompliance. Evidence:
  `docs/evidence/syn039-unattended-todo-cp0516-producer-first-diagnostic-2026-08-24.md`.
- Focused SYN-039/workspace tests, Javadocs, validators, `go vet`, and
  `git diff --check` passed. Existing bootstrap migration failures, root Git
  subprocess stall, and six fixture Doctor warnings remain separate.
- Exact next action: implement and test the smallest safe reviewer
  continuation that preserves dirty work and keeps all grant/claim/epoch
  fencing fail-closed; then rerun the exact diagnostic.

# 2026-08-24 — SYN-039 CP-0515 exact-action continuation diagnostic

- Fresh exact-action diagnostic reached one shared WorkGroup
  `ed155087-41fd-39e6-8380-d2c5663aae64`, exact REVIEW admission, owner
  acceptance, and single-use grant consumption. Both agents executed the
  observed concrete actions and repeated `WAIT -> get_next_action({})` polls.
- The producer stopped before polling after grant consumption; the reviewer
  stopped at `SNAPSHOT_PENDING`. No snapshot, validation, integration, or
  closure was reached, and no exact projected call failed. This remains
  agent-compliance/order evidence, not a production defect.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0515-exact-action-diagnostic-2026-08-24.md`.
- Exact next action: run one fresh bounded diagnostic with the implementation
  producer establishing the WorkGroup first, then preserve the first post-
  grant lifecycle result without relay or manual transition.

# 2026-08-24 — SYN-039 CP-0513/CP-0514 claim contract and ordinary acceptance

- CP-0513 showed both ordinary agents calling `ensure_session` with only
  `goal`, `acceptance`, and descriptive `likelyScopes`; no intent, claim, or
  WorkGroup was created. The catalog exposed `claims` without explaining its
  role, and the manual said to announce intent without naming the existing
  `ensure_session.task.claims` path. This was a concrete agent-facing contract
  ambiguity, not a lifecycle authorization defect.
- The narrow fix clarified `task.claims`, `likelyScopes`, exact selector kinds,
  and disjoint ownership in the MCP catalog, generated `AGENTS.md`, provider
  manual, and provider documentation. Deterministic contract tests, focused
  MCP/workspace tests, bundle rebuild, and `git diff --check` passed.
- CP-0514 fresh ordinary acceptance proved both agents independently created
  disjoint claims and one shared WorkGroup, reached REVIEW admission, accepted
  the request, and consumed the targeted single-use grant. Agent A stopped
  during grant-pending WAIT before polling after consumption; Agent B stopped
  during `SNAPSHOT_PENDING` WAIT. B's omitted `targetParticipant` was rejected
  fail-closed and corrected. No snapshot, validation, integration, or closure
  was reached; no exact projected action failed.
- Evidence: `docs/evidence/syn039-unattended-todo-cp0514-ordinary-claims-contract-2026-08-24.md`.
- Exact next action: run the fresh bounded exact-projection diagnostic through
  snapshot, validation, integration, and closure without relay or manual
  transitions. Keep known Git stall, bootstrap migration failures, and six
  Doctor warnings separate.

# 2026-08-24 — SYN-039 CP-0511/CP-0512 acceptance evidence

- CP-0511 ordinary acceptance used only complementary visible coding prompts.
  A implemented `todo.py` and then used the optional `integrationCheck`
  argument on `get_next_action`, which returned accepted with no actions; B
  reached the exact owner response and a REVIEW grant but stopped at WAIT.
  No accepted snapshot was falsely classified as `TESTS_FAILED`.
- CP-0512 exact-action diagnostic used the current bundled MCP and the exact
  projected-action rule. Reciprocal REVIEW admissions, grants, snapshots,
  integrations, and one structured ACCEPT passed. Agent A stopped during the
  second review's projected WAIT continuation before reciprocal validation.
  No exact projected action failed; WorkGroup remained ACTIVE.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0511-ordinary-2026-08-24.md` and
  `docs/evidence/syn039-unattended-todo-cp0512-exact-action-diagnostic-2026-08-24.md`.
- Doctor for both fixtures: DEGRADED with six warnings, zero errors and zero
  critical findings. Known root Git subprocess stall and bootstrap migration
  failures remain separate.
- Exact next action: inspect whether `integrationCheck` timing and WAIT
  continuation are unambiguous in the catalog and generated/provider
  guidance; change only an evidenced contract ambiguity.

# 2026-08-24 — SYN-039 CP-0510 review-decision postfix verification

- CP-0509 proved that review validation was projected as executable
  `respond_coordination` without `result`; the exact call failed closed with
  `COORDINATION_RESPONSE_FIELD_REQUIRED:result`.
- The narrow fix exposes `review_decision` with exact review context and
  explicit `accepted` / `rejected` choices, without recommending an incomplete
  response command. Focused MCP/workspace tests and the rebuilt bundle pass.
- Fresh CP-0510 project
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0510-001` reached
  WorkGroup `ba7d9344-fa33-3564-832d-b68222c93296`, exact REVIEW admission,
  grant consumption, snapshot `snap_9b3fae1c10ee7f69f381d035d77d211b`, and
  integration.
- Agent A then received `nextAction=review_decision` with exact grant,
  snapshot, intent, epoch, and choice data. Agent B later changed a projected
  intent ID in `work_group_join`; Synesis returned `INTENT_NOT_FOUND`. This is
  agent-compliance evidence, not a production defect. WorkGroup remained
  ACTIVE, so no ordinary second acceptance was started.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0510-review-decision-postfix-2026-08-24.md`.
- Fixture Doctor remained DEGRADED with six warnings; the known Git stall,
  bootstrap migration failures, and Doctor warnings remain separate.
- Exact next action: run the ordinary unattended acceptance with only
  complementary coding prompts. Do not push or create SYN-040.

# 2026-08-24 — SYN-039 CP-0508 review-decision postfix diagnostic

- Fresh project `syn039-cp0508-001` used the rebuilt current bundled MCP
  (`49D0FFB4696EABE62F3C54BF98ED0011FDC7F4CDD63B842DC2E23BF7511E36B8`),
  two independent GPT-5.6 Luna sessions, exactly ten tools, ready/isolated
  bindings, disjoint `todo.py` / `test_todo.py` claims, and one WorkGroup
  `e0ef5af5-844c-3f77-b4ad-29767b4b13c3`.
- Agent A implemented the visible Todo behavior, passed three tests, obeyed
  the exact publication projection, and integrated
  `snap_806145a00668f970adaaf4af734a9d81`.
- Agent B consumed the single-use REVIEW grant, recovered from the expected
  workspace-stale response, added a meaningful test, passed four tests, and
  saw the corrected `reviewDecision` projection with valid `accepted` /
  `rejected` choices and no fabricated executable result. B submitted
  structured `accepted`; Synesis returned `ACCEPTED`.
- First deviation: B omitted projected `targetParticipant` while consuming
  the grant and received `COORDINATION_FIELD_REQUIRED:targetParticipant`,
  then corrected the call. Later A stopped before polling after B accepted
  the reciprocal REVIEW request, leaving the reciprocal grant available and
  the WorkGroup ACTIVE. No exact complete projected server action failed;
  this is agent-compliance evidence, not a new production defect.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0508-review-decision-postfix-2026-08-24.md`.
- Focused tests, Javadocs, bundle rebuild, validators, `go vet`, Doctor, and
  `git diff --check` passed. Doctor remains DEGRADED with six warnings; the
  Git subprocess stall and bootstrap migration failures remain separate.
- Exact next action: fresh bounded diagnostic through reciprocal grant
  consumption and second-lane closure. Do not push or create SYN-040.

# 2026-08-24 — SYN-039 CP-0507 validation-result projection diagnostic

- Fresh project `syn039-cp0507-001` used the rebuilt current bundled MCP and
  two independent GPT-5.6 Luna sessions. Both reached ten-tool
  `ready / isolated` sessions, distinct worktrees, disjoint claims, and one
  WorkGroup `9b605c00-d45c-34e6-a9dd-f0ad4d31be3b`.
- Agent A followed ordinary `IMPLEMENT`, implemented `todo.py`, passed the
  visible pytest suite, executed the exact projected `finish_lane`, published
  `snap_760b1bf37251e2c2f64e92e73ece42a9`, and integrated it.
- Agent B consumed REVIEW grant
  `4c3eae33-35d4-3015-bdcf-bf84895f6aad`. Synesis projected exact validation
  arguments containing literal `result: "accepted|rejected"`; B executed them
  unchanged and received `policy_denied` /
  `COORDINATION_RESPONSE_INVALID_RESULT`.
- WorkGroup stayed ACTIVE with no validation decision or closure. This is a
  concrete production projection defect. Evidence:
  `docs/evidence/syn039-unattended-todo-cp0507-review-result-projection-2026-08-24.md`.
- Focused MCP/workspace tests, validators, and the bundle build were already
  green for the CP-0506 slice. Doctor remains DEGRADED with six warnings; the
  Git subprocess stall and bootstrap migration failures remain separate.
- Exact next action: trace and minimally fix review-validation projection,
  add valid ACCEPT/REJECT plus invalid/stale/replay coverage, then rerun the
  exact-projection diagnostic. Do not push or create SYN-040.

# 2026-08-24 — SYN-039 CP-0505 exact-projection diagnostic

- Fresh Git + Synesis project `syn039-cp0505-001` used the current bundled
  MCP and two independent GPT-5.6 Luna sessions. Both reached distinct
  `ready / isolated` sessions, exactly ten tools, and one WorkGroup
  `35aa138a-a6bf-389a-a4b5-e7bbe66024ec` with disjoint epoch-1 claims.
- Exact REVIEW admission, idempotent replay, owner acceptance, and
  single-use grant consumption all succeeded. Grant
  `a92067d7-7d0f-365b-b514-7b3efb314428` was consumed exactly once.
- Both agents then stopped after executing exact `WAIT` →
  `get_next_action({})` continuations. The producer did not poll again after
  grant consumption, so snapshot publication, validation, integration, and
  closure were not reached. No exact projected action failed; this is
  agent-compliance evidence and no production code changed.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0505-exact-rule-diagnostic-2026-08-24.md`.
- Doctor was `DEGRADED` with six warnings; the known Git stall, bootstrap
  migration failures, and Doctor warnings remain separate.
- Exact next action: run a fresh bounded diagnostic with both agents alive
  through post-consumption polling and peer snapshot publication. Do not push
  or create SYN-040.

# 2026-08-24 — SYN-039 CP-0503 post-fix producer-polling diagnostic

- The CP-0502 fresh diagnostic reproduced the owner-side missing continuation
  projection: after REVIEW acceptance, an active owner with an unconsumed
  peer grant received ordinary `IMPLEMENT` with no executable action. The
  owner had no safe mutation, but unattended progress still required it to
  remain active until peer grant consumption. Evidence:
  `docs/evidence/syn039-unattended-todo-cp0502-owner-polling-diagnostic-2026-08-24.md`.
- Implemented the smallest fix in `AgentNextActionService`: current active
  owner intent + active WorkGroup + matching current-epoch available REVIEW
  grant targeted to another participant + no owner snapshot now projects
  read-only `WAIT` with exact grant context and `get_next_action({})`.
- Added deterministic MCP coverage in `McpSyn039SliceTest` for the grant,
  WorkGroup, intent, epoch, target, snapshot-required state, and executable
  WAIT continuation. The focused MCP and workspace suites passed; the current
  platform bundle was rebuilt.
- Fresh CP-0503 post-fix diagnostic used project
  `syn039-cp0503-001`, current MCP SHA-256
  `D27A9F4D3C833C3C5581DD012254E7AE767D96FC71F53DC2718461CBC6822CD1`, and
  two independent ready/isolated sessions. WorkGroup
  `49082d5e-ecc5-3503-82fb-3d62f37597c8` reached exact REVIEW admission,
  grant `c9cb80ae-679d-3290-902c-c55647723aae` consumption, owner
  `finish_lane`, snapshot publication, and integration.
- Reviewer B received the correct `SNAPSHOT_PENDING` / `WAIT` projection with
  executable `get_next_action({})` twice, then ended before polling after
  publication. No exact projected action failed, so this is agent-compliance
  evidence and no further production code changed from CP-0503.
- Fixture Doctor remained DEGRADED with six warnings; known Git subprocess
  and bootstrap migration issues remain separate.
- Focused Javadocs, deferred/fixture validators, `go vet`, and `git diff --check`
  passed. `:link:formatCheck` still fails on pre-existing trailing whitespace
  in CP-0488, CP-0489, and the CP-0494 evidence file. The bounded root
  `check` reproduced the Git subprocess stall in
  `ProviderApplicationServiceTest.codexInstallationMaterializesHookIntoAssignedWorktree`;
  a thread dump captured `ProcessCommandRunner.execute:81` waiting while its
  output collector remained in `ProcessCommandRunner.java:333`. It was stopped
  after evidence and is not reported green.
- Exact next action: run a fresh bounded diagnostic with both agents alive
  after every WAIT and after peer-side publication, then preserve the first
  later blocker. Do not push or create SYN-040.

# 2026-08-24 — SYN-039 CP-0501 producer-polling diagnostic

- Fresh project `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0501-002`
  used the current bundled MCP (`0.1.0-SNAPSHOT`, protocol `2025-06-18`,
  commit `bc334ac`, ten tools) and two independent GPT-5.6 Luna sessions.
  Both reached distinct `ready / isolated` bindings and converged on
  WorkGroup `1f8bc962-fbb5-376b-9f72-1e0b4135a495` with disjoint claims.
- Exact REVIEW admission, owner `respond_coordination` acceptance, and
  single-use grant consumption succeeded. No exact projected action failed.
- The producer then stopped at ordinary `IMPLEMENT` with no executable action
  before the reviewer consumed grant
  `e6b09aa2-0cf8-35de-b80c-1e4180ccb6a7`. The reviewer consumed it and received
  exact `WAIT` → `get_next_action`, but the producer no longer polled for the
  post-consumption `finish_lane` projection. WorkGroup remained ACTIVE with no
  snapshot, validation, or integration.
- This is agent-compliance evidence, not a proven production defect. Evidence:
  `docs/evidence/syn039-unattended-todo-cp0501-producer-polling-diagnostic-2026-08-24.md`.
- Next action: run a fresh bounded diagnostic that keeps both agents alive
  through peer-side progress and captures the post-consumption owner action.
  Do not push or create SYN-040.

# 2026-08-24 — SYN-039 CP-0500 REVIEW admission idempotency diagnostic

- Fresh project `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0500-002`
  used the rebuilt bundled MCP and two independent GPT-5.6 Luna sessions.
  Both reached ten-tool `ready / isolated` sessions with disjoint claims and
  one WorkGroup `4c0005dc-4358-32b5-922a-3cf554cfb54d`.
- Replaying the same projected REVIEW admission returned the same request
  `90ab5c3b-e663-4230-94df-5f0077015508`; no duplicate request or grant was
  created. The run reached exact acceptance, grant consumption, producer
  `finish_lane`, immutable snapshot publication, integration, and structured
  ACCEPT. The regression is covered by `WorkIntentServiceTest`.
- Agent A then ignored two repeated concrete review-admission projections
  after request `d9d89b66-c0bf-46ac-958f-926c411564e7` and stopped. B later
  accepted the request and received grant
  `b1b5b243-b6a5-308d-af57-bce3d3fc63d4`, but A was no longer polling to
  consume it. WorkGroup remained ACTIVE with B's active claim and no B
  snapshot. This is agent-compliance evidence, not a new production defect.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0500-review-admission-idempotency-2026-08-24.md`.

# 2026-08-24 — SYN-039 CP-0499 post-fix bounded diagnostic

- Fresh project `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0499-003`
  used the current bundled MCP and two independent GPT-5.6 Luna sessions.
  Both agents preflighted exactly ten tools and distinct `ready / isolated`
  bindings on the same project root, with disjoint `todo.py` and
  `test_todo.py` claims.
- WorkGroup `3621a4f6-6b2b-3379-9174-9cdcb45b8186` reached exact REVIEW
  admission, owner acceptance, grant consumption, projected producer
  `finish_lane`, immutable snapshot publication
  `snap_d03b1424511d73cbf6d1e13ed23937de`, integration, and structured ACCEPT.
  No exact projected action failed.
- The first remaining blocker is a missing post-ACCEPT completion projection:
  Agent B's active intent remains live, but final `get_next_action` returns
  ordinary IMPLEMENT with no executable lifecycle action. The WorkGroup is
  ACTIVE, B's test snapshot is unpublished, and clean closure does not occur.
  The same admission projection also produced three duplicate requests and
  three single-use grants; no grant replay bypass occurred.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0499-postfix-diagnostic-2026-08-24.md`.
  The next action is a narrow trace/regression of the active-reviewer
  post-ACCEPT projection and repeated admission idempotency. Do not push or
  create SYN-040.

# 2026-08-24 — SYN-039 CP-0498 completed-review continuity diagnostic

- Fresh project `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0498-001`
  used the current bundled MCP and two independent GPT-5.6 Luna sessions.
  Both agents reached the same pinned project, ten tools, and distinct
  ready/isolated bindings. WorkGroup
  `1d24011b-99a6-37bd-b56b-ca09eab8edef` reached exact REVIEW admission, grant
  consumption, projected `finish_lane`, immutable snapshot publication,
  integration, reviewer recovery, and exact ACCEPT decisions.
- The CP-0497 status contradiction was fixed and verified: ACCEPT returned the
  durable `ACTIVE` status while a sibling intent or grant remained live.
- The first remaining production blocker is completed-participant continuity:
  B became terminal `COMPLETED` after its own lane integrated while A's
  sibling implementation lane remained active, so B could not discover/request
  review admission for A. Evidence:
  `docs/evidence/syn039-unattended-todo-cp0498-completed-review-continuity-diagnostic-2026-08-24.md`.
- The next slice is restricted to same-WorkGroup review-only projection and
  authority for completed participants. Completed write mutation, cleanup,
  Doctor, and unrelated verification issues remain separate.

# 2026-08-24 — SYN-039 CP-0494 post-fix review-projection diagnostic

- Fresh project `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0494-001`
  used external harness `harness-cp0494-001`, the rebuilt current bundled MCP,
  and two independent GPT-5.6 Luna sessions. The project ID was
  `03dad00b-fbb4-4500-aa9a-22f91c7d7494`; both agents used the same pinned
  executable, protocol `2025-06-18`, server `0.1.0-SNAPSHOT`, and ten tools,
  with distinct ready/isolated sessions.
- WorkGroup `471a4f65-5210-327f-ad5a-ba2897d022ab` reached one producer claim
  on `todo.py` at epoch 1. Participants were
  `agt_11e6b518-98a2-318e-a663-ae7ef7beab69` and
  `agt_c938232e-73c6-363d-98be-c8103a68e6e1`. Requests
  `970731a8-6e01-43bb-a976-2294b6c977ce` and
  `47980751-2ccc-4d06-a115-78611afa098c` were accepted; grant
  `2d616273-a235-3cec-b2fd-054a855fb8c6` was consumed.
- CP-0493 proved the executable reviewer validation projection contained
  fields rejected by strict `respond_coordination`. The projection now emits
  only accepted `review_validation` fields; focused workspace/MCP tests and a
  forced platform bundle rebuild pass. The related CP-0490 Python
  `__pycache__` artifact-policy failure is also fixed and covered.
- Agent A executed exact projected `finish_lane`, published
  `snap_3e7c0ee281c5190f43bcd2102a5853f7`, and integrated control commit
  `45fc60a`. Agent B received the corrected exact validation projection but
  chose unprojected `read_file("todo.py")`, received `workspace_stale`, and
  did not record ACCEPT/REJECT. WorkGroup remained ACTIVE and Doctor was
  DEGRADED with six warnings. This is agent-compliance evidence, not a new
  production defect. No ordinary second acceptance was run.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0494-review-projection-2026-08-24.md`.
  Exact next action: run another fresh bounded diagnostic to observe ordinary
  reviewer execution of the corrected projected validation action. Do not
  push or create SYN-040.

# 2026-08-24 — SYN-039 CP-0489 role-order diagnostic

- Fresh fixture `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0489-001`
  used an external harness and the current bundled MCP (`0.1.0-SNAPSHOT`,
  SHA-256 `8F17CF71691F407093D607C0BB947924BDAC05951CA3A84BB98EBFAEFE6704C7`).
  Project ID was `bceaf899-f1a3-4a65-8538-4f303a072e5d`; the control checkout
  was clean at `f27bbec` before and after the run. Both independent
  GPT-5.6 Luna sessions reached `ready / isolated` with disjoint exact claims.
- The test intent established WorkGroup
  `2176bfbd-6199-303f-805c-a91c382b92ff`, making that agent the producer and
  the implementation intent the reviewer. The reviewer executed exact
  `request_coordination(work_group_join)` admission. The producer executed
  exact `respond_coordination` acceptance for both requests, issuing grants
  `215ba3af-5cf9-352a-ac5e-5685438a7d12` and
  `d831734a-d597-3457-b817-ae5b3f7e6e70`. The reviewer consumed the first
  grant exactly and correctly received `SNAPSHOT_PENDING` → `wait`.
- The producer's last `get_next_action` occurred before grant consumption and
  returned ordinary `IMPLEMENT` with no executable lifecycle action. It did
  not poll after the reviewer action, so no producer publication projection
  was requested. No exact projected action failed; this is agent-side
  lifecycle polling evidence, not a production defect. No second ordinary
  acceptance was run.
- Focused SYN-039 tests, Javadocs, validators, Go vet, and diff check passed.
  Bootstrap Go tests retained three migration failures. Full root check passed
  format and build gates, then reproduced the known `McpServerTest.setUp` Git
  subprocess stall; a thread dump showed `ProcessCommandRunner.execute:81`
  through `GitProcessRunner` and `McpServerTest.setUp:38`. The fixture Doctor
  remained DEGRADED with six warnings.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0489-role-order-diagnostic-2026-08-24.md`.
  Exact next action: rerun a bounded diagnostic that keeps both agents polling
  after waits/peer-side state changes and captures publication through
  validation without relay or manual transitions. Do not push or create
  SYN-040.

# 2026-08-24 — SYN-039 CP-0485 clean-harness exact-rule diagnostic

- Fresh fixture `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0485-001`
  used a harness outside the project, a clean control checkout, and the
  rebuilt bundled MCP (`0.1.0-SNAPSHOT`, SHA-256
  `27D6BE820B82A8C8CED3966DF9DD2A0AEE1FC897659F46462D8B7166D46CF7E3`). Both
  independent GPT-5.6 Luna sessions reached the same project in distinct
  `ready / isolated` sessions and held disjoint claims.
- The sessions converged on WorkGroup
  `a5b6fdc4-51cb-3398-be5a-76126258984f`. The reviewer executed exact
  projected `request_coordination(work_group_join)`; the owner executed exact
  projected acceptance for requests
  `4a2d5e88-22b4-40d6-95b3-2053472487b0` and
  `e4617626-b3b8-4772-99d1-57b3b7ffea03`. Grants
  `ce12bf95-e493-38c7-a75b-fc78f5b03782` and
  `7b4f4964-8631-3b80-bb99-0552b05c67d7` targeted the reviewer at epoch 1.
- The owner chose unprojected `finish_lane` while ordinary `IMPLEMENT` was
  projected. This is agent-compliance evidence and caused no production
  change. The first exact projected action failure was reviewer recovery:
  `workspace_stale` → `ensure_session({})`, twice returning
  `internal_failure` / `request_human_help`.
- No grant consumption, snapshot validation, structured decision, or closure
  was reached. WorkGroup remained ACTIVE; the integrated control checkout was
  clean at `166228f5a6b17208175231984f7cbce9e4090dfc`. Doctor remained
  DEGRADED with six warnings, including two `stale_session_lease` warnings.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0485-exact-rule-diagnostic-2026-08-24.md`.
  No second ordinary acceptance was run because the diagnostic did not
  complete. Exact next action: trace the live reviewer lease/readiness failure
  before changing production code.

# 2026-08-24 — SYN-039 CP-0483 active-reviewer projection diagnostic

- Fresh fixture `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0483-001`
  used the current bundled MCP (`0.1.0-SNAPSHOT`, SHA-256
  `9064A9A96B0DD09595409E886214C5C2F35D4778B221ABB77DCE60D6161B576E`) and
  two independent GPT-5.6 Luna sessions. Both reached `ready / isolated` on
  project `da065c4f-a823-4792-9245-95a179766d9e` with disjoint claims.
- The sessions converged on WorkGroup
  `af1807bc-ab46-3c98-8908-7073a807a7a6`. Agent A published snapshot
  `snap_2ecbf452a75a69a8048168e6a1f177f2` for `todo.py`; the reviewer intent
  was recorded first and then received ordinary `IMPLEMENT` with no concrete
  action even though the implementation snapshot was visible.
- No REVIEW request, grant, validation decision, accepted integration, or
  WorkGroup closure was created. Agent A and B each selected unprojected
  `finish_lane` at different points; those results remain agent-compliance
  evidence. The first genuine blocker is order-dependent review-admission
  producer selection.
- The active-peer projection slice is committed locally as `9e6d971`.
  Focused MCP/workspace tests, workspace Javadocs, validators, Doctor
  structural checks, and `git diff --check` pass. Doctor in the disposable
  project remains DEGRADED with six existing warnings. Evidence:
  `docs/evidence/syn039-unattended-todo-cp0483-active-reviewer-projection-2026-08-24.md`.
- Exact next action: reproduce reviewer-first ordering deterministically and
  trace/fix only the existing-model producer selection in
  `AgentNextActionService.reviewActions`. Do not push or create SYN-040.

# 2026-08-24 — SYN-039 CP-0482 actionable projection defect

- Fresh fixture `syn039-cp0481-001` used the current bundled MCP, project
  `bca62af3-e1c9-482d-b69b-f9ba2197d4b1`, and two independent ready/isolated
  sessions with disjoint claims.
- Both agents converged on WorkGroup
  `ffd58516-2313-3ccc-a402-b20c921d2f8f`, completed visible changes, and
  followed the exact projected-action rule. Repeated projections stayed at
  ordinary `IMPLEMENT` with no tool or arguments. No REVIEW admission,
  publication, grant, validation, or integration action was exposed.
- Coordination remained sequence zero with no tasks or ownerships in the CLI
  control view. Doctor was `DEGRADED` with six existing warnings. This is now
  classified as a concrete projection defect, not agent noncompliance.
- Evidence: `docs/evidence/syn039-unattended-todo-cp0482-actionable-projection-2026-08-24.md`.
- Exact next action: trace and minimally fix the completion-state projection in
  `AgentNextActionService` and coordination projections, then add deterministic
  coverage. Do not push or create SYN-040.

# 2026-08-24 — SYN-039 CP-0481 post-fix diagnostic

- The first fresh launch (`syn039-cp0480-005`) was invalid as a product
  fixture because Agent A claimed both Todo paths while Agent B claimed
  `test_todo.py`; Synesis correctly rejected the overlap. Logs remain
  preserved and no production code changed.
- The corrected fixture (`syn039-cp0480-006`, project
  `df45ded4-0b90-4f6e-8cc5-f810d714942f`) used the current bundled MCP and two
  independent GPT-5.6 Luna sessions. Both reached `ready / isolated`, held
  disjoint exact claims, and converged on WorkGroup
  `f0666aa0-31db-3025-a7e7-2e46f3fad1de`.
- Agent A implemented `todo.py` and published snapshot
  `snap_0c58f76fb959553d7d64d64ce7b0d21c` / commit
  `cf4e313abe5175b53b5240415c376af4c3e38994`. Its `get_next_action` remained
  ordinary `IMPLEMENT` with no concrete lifecycle action, but it selected
  `finish_lane`; integration returned `integration_failed`. Agent B never saw
  REVIEW admission. This is agent-compliance evidence, not a proven
  production defect; no ordinary second acceptance was run.
- Doctor was `DEGRADED` with six warnings. The Git subprocess stall and
  bootstrap migration failures remain separate. Evidence:
  `docs/evidence/syn039-unattended-todo-cp0481-postfix-review-admission-2026-08-24.md`.
- Exact next action: run a fresh bounded diagnostic with exact execution of
  concrete projected actions and preserve the first post-publication result.
  Do not push or create SYN-040.

# 2026-08-24 — SYN-039 CP-0480 convergence projection slice

- Fresh claim-bearing diagnostic `syn039-cp0480-003` converged two disjoint
  claims on WorkGroup `82223437-5c6b-38a3-bf18-c0216df46d5e`; no split occurred.
- Ordinary `syn039-cp0480-004` also converged. It exposed REVIEW admission but
  the executable workflow had empty `request_coordination` arguments; the
  agent inferred the nested payload, then succeeded after one fail-closed
  unsupported-field retry.
- Promoted the existing review protocol payload into exact executable
  `request_coordination` arguments and added deterministic regression coverage.
- Focused tests and bundle rebuild passed. Bootstrap Go tests retain three
  migration failures; Doctor remains DEGRADED; root check remains separate.

# 2026-08-24 — SYN-039 CP-0479 contract clarification and acceptance reruns

- Audited root `AGENTS.md`, `docs/providers/codex.md`, the managed
  `synesis-manual`, `McpToolCatalog`, `get_next_action`, and protected-path
  tests. The distinction between ordinary `IMPLEMENT` work and a concrete
  lifecycle action was ambiguous; `.synesis/**` protection was already
  correct.
- Added the smallest agent-facing clarification to `ProviderManualService`
  and the `get_next_action` catalog description, with deterministic
  manual/catalog tests. Rebuilt the bundled MCP successfully; no lifecycle
  semantics or hidden-path protection changed.
- CP-0479 diagnostic fixture
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0479-001` reached
  WorkGroup `6e69d6f1-75b7-352b-a582-13a2a0a011cb`, review requests and grants,
  snapshot `snap_701ed39a6a23b972bc5d723a4cbd630a`, and integrated control
  commit `24ed805`. The owner first chose unprojected `finish_lane` and got
  `task_not_ready / retry`; a later retry integrated. No structured reviewer
  ACCEPT/REJECT decision was captured, and three worktrees remained.
- The required ordinary second run used fresh fixture
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0479-002` without
  diagnostic lifecycle instructions. The agents did not form one shared
  WorkGroup. They published separate snapshots
  `snap_694e9a6456a6a9469c65fa7ab817b7d4` and
  `snap_8e9d2df7bbc472abefe455b9e2131cec`; both finish attempts reached
  `integration_blocked`. A non-projected integration-check payload reported
  pytest 4/4 but returned `integration_conflict / TESTS_FAILED`.
- Both fixtures ended with coordination sequence zero, no tasks, no
  ownerships, and three retained worktrees; Doctor was `DEGRADED` with six
  warnings. Focused tests, validators, and diff check passed. Evidence:
  `docs/evidence/syn039-unattended-todo-cp0479-contract-and-ordinary-acceptance-2026-08-24.md`.
- Classification: contract clarification verified; next blocker is ordinary
  peer/WorkGroup discoverability and convergence. Do not change integration
  or lifecycle behavior speculatively, push, or create SYN-040.

# 2026-08-24 — SYN-039 CP-0478 exact-action protocol diagnostic

- Created fresh fixture `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0478-001`
  with project `72b38176-c65c-47a6-942e-cf91eee4348f`, baseline `25bd256`,
  and managed baseline `7ee03be`.
- Both independent agents used the current bundled MCP
  (`0.1.0-SNAPSHOT`, commit `bc334ac`, protocol `2025-06-18`, ten tools),
  the same project root, distinct connection IDs, and reached
  `ready / isolated` sessions.
- Agent A's `IMPLEMENT` projection was action
  `6995fdb3-51b4-3939-9872-f123eaa804fd`; Agent B's was
  `4fe9cbb5-1478-3b1a-8066-bf3b613acec0`. Neither projection supplied a
  specific lifecycle tool or argument because no WorkGroup existed.
- Both agents selected the unprojected `read_file(".synesis/project.json")`
  path and received `blocked / invalid_path`. Agent B successfully inspected
  the metadata with `git show HEAD:.synesis/project.json` and stopped. No
  claims, edits, WorkGroup, requests, grants, snapshots, validation,
  integration, or closure were created.
- Coordination status remained sequence zero with zero tasks and ownerships.
  Doctor was `DEGRADED` with six existing warnings. No production code
  changed; the exact evidence is
  `docs/evidence/syn039-unattended-todo-cp0478-protocol-diagnostic-2026-08-24.md`.
- Classification: agent-selected hidden-path inspection before lifecycle,
  not a proven production defect. No second acceptance was run. Exact next
  action: assess the hidden metadata path contract, then run only a bounded
  diagnostic with valid initial repository inspection. Do not push or create
  SYN-040.

# 2026-08-24 — SYN-039 CP-0477 explicit harness and lifecycle rerun

- Compared the failing agent routes with the direct current-bundle control:
  one stale installed launcher omitted `--project`; the pinned current-bundle
  route pointed at a disposable project that no longer existed.
- Corrected only the acceptance harness by using explicit per-agent Codex CLI
  MCP overrides for the current bundled executable, exact stable fixture root,
  provider, and distinct connection IDs. No production code changed.
- Both independent preflight agents passed: project ID
  `065d8765-ab19-46c6-bcb2-f919854e95dd`, current bundled MCP, ten tools,
  `ensure_session=ready`, and isolated distinct worktrees.
- Full unattended run reached WorkGroup
  `62f4a6d0-0061-3e3d-8cc5-7536b556782c`, intent
  `8fcbab57-9293-3321-8945-e5a5fd4af6b9`, claim epoch 1, and implementer
  `pytest -q` 3/3. The owner called `finish_lane` before review readiness and
  received `task_not_ready / retry`.
- The reviewer saw the exact review admission projection but attempted an
  invalid inbox acknowledgement, received `policy_denied / INBOX_ITEM_NOT_FOUND`,
  and stopped. No grant, snapshot, validation, integration, or closure state
  was reached. This is agent action ordering, not a proven production defect.
- Final Doctor is DEGRADED with eight warnings; stale session leases,
  command-state warnings, and provider migration remain separate.
- Evidence: `docs/evidence/syn039-unattended-todo-cp0477-2026-08-24.md`.
- Exact continuation: rerun the explicit two-agent acceptance with strict
  exact `get_next_action` execution and preserve the first typed result after
  the reviewer submits `request_coordination(work_group_join)`. Do not push or
  create SYN-040.

# 2026-08-24 — SYN-039 CP-0476 harness preflight

- Created fresh fixture
  `C:\Users\Liparakis\AppData\Local\Temp\syn039-unattended-todo-cp0476-20260824-001`
  with project ID `c6fe0862-b75a-4e9f-8d9a-6c0e9aa0ea43`; the fixture remained
  clean at managed baseline `b6165ec686a1df3518ccc8ffa0567e8ec9bd4df0`.
- Explicit control connections using the repository-bundled MCP
  `synesis-mcp.exe` (SHA-256
  `FAECFCB1B9ED43E9786C922BA880841FCD950FE612B1C359DCD61CD9807FB1BA`,
  startup `0.1.0-SNAPSHOT`, commit `bc334ac`) passed the required preflight:
  protocol `2025-06-18`, exactly ten tools, same project, distinct isolated
  sessions, and `ensure_session(refresh=true)=ready`.
- Both independent Luna High agents failed before Todo work. Each received
  exactly `retry_required / workspace_not_ready / ensure_session` on three
  attempts. No WorkGroup, claim, request, grant, snapshot, validation,
  integration, or closure state was created; coordination sequence remained
  zero.
- This is a harness/configuration blocker because the explicit current-bundle
  control invocation succeeds against the same fixture. No production code
  changed. Doctor remains degraded and the Git subprocess stall/bootstrap
  migration failures remain separately classified.
- Evidence:
  `docs/evidence/syn039-unattended-todo-harness-preflight-cp0476-2026-08-24.md`.
- Exact continuation: capture the effective agent-route executable, startup
  line, project arguments, connection identity, and readiness trace before
  changing readiness or lifecycle code. Do not push or create SYN-040.

# 2026-08-24 — SYN-039 snapshot-publication projection slice

- Reproduced the owner contradiction with a deterministic MCP fixture:
  `get_next_action` supplied `nextProtocolPayload.summary` for
  `snapshot_publication_required`, while `AgentWorkflowReducer` emitted empty
  `finish_lane` arguments. The exact projected summary was required for the
  existing completion path to execute.
- Applied the minimal reducer fix only. The fixture now reaches real
  `finish_lane`, publishes an immutable `PUBLISHED` snapshot, and exposes its
  snapshot ID through the reviewer coordination projection. Existing
  wrong-snapshot, replay, wrong-reviewer, epoch, and fail-closed fixtures
  remain in scope.
- Focused SYN-039 tests, Javadocs, deferred/fixture validators, Go vet, and
  `git diff --check` passed. Bootstrap Go tests retain the three known
  `update migrations not prepared` failures. Root `check` again reaches the
  known `McpServerTest` Git subprocess stall and was stopped without changing
  timeout or behavior.
- Fresh unattended agent-harness attempts did not yield a valid shared
  WorkGroup: one failed at `workspace_not_ready`, one saw no peer WorkGroup,
  and a second retry remained non-terminal until bounded shutdown. Direct
  preflight against the current bundled MCP did return `ready / isolated`, so
  these are recorded as harness/configuration evidence rather than a new
  production readiness defect.
- Evidence: `docs/evidence/syn039-unattended-todo-snapshot-publication-cp0475-2026-08-24.md`.
- Exact continuation: run the fresh two-agent acceptance with both MCP
  processes independently proven current/project-pinned, then preserve the
  first lifecycle result after corrected `finish_lane` execution. Do not push
  or create SYN-040.

# 2026-08-22 — SYN-039 activation

- Ran `scripts/agent-resume.ps1`: SYN-038 is DONE at CP-0458, the roadmap had
  no active task, and the worktree contained only the modified
  `CoordinationService.java`.
- Inspected and restored `CoordinationService.java` to the `HEAD` blob. The
  uncommitted change was mechanical formatting and redundant exception
  rethrow cleanup with no task record or verification evidence; it was not
  folded into SYN-039.
- Reconciled the stale SYN-038 `TEST_MATRIX.md` rows with CP-0458. The limited
  real-Codex row now records PASS only for its documented scope; deterministic-
  only cleanup/capacity cases and command-tree cleanup remain explicitly
  bounded.
- Activated SYN-039 as a bookkeeping-only task. No production code changed.
- The user-supplied unattended Todo smoke failure is the primary failure input;
  no raw copy was found in the repository, so reproducing and capturing it is
  the exact next implementation action.

# 2026-08-22 — SYN-039 reviewer/integration slice

- Implemented typed `REVIEW` admission using the existing coordination request
  and LaneGrant model. Owner acceptance issues a deterministic single-use grant
  targeted to the requesting reviewer and the owner's existing intent/claim
  epoch; owner file claims remain unchanged and duplicate acceptance is
  idempotent.
- Exposed WorkGroups, targeted grants, and immutable task snapshots through
  collaboration status and `get_next_action`. Normalized a missing-`grantId`
  `work_group_join` into a fail-closed typed review request; supplied grants
  still use the existing consuming path.
- Fixed the narrow integration-check evidence mismatch: recorded bounded
  `pytest`-passing evidence is no longer treated as false `TESTS_FAILED`.
  Direct project validation remains arbitrary direct argv.
- Deterministic regressions passed in `WorkIntentServiceTest` and
  `McpSyn039SliceTest`; focused workspace integration/completion tests and
  `cli:installDist` passed; `git diff --check` passed. Full root verification
  reached the long-running `:mcp:test` stage after compilation, packaging,
  Javadoc, static analysis, hygiene, and launcher stages, then was bounded and
  stopped without an emitted test failure.
- Exact unattended rerun fixture:
  `C:\Users\Liparakis\AppData\Local\Temp\syn039-unattended-todo-slice-20260822`.
  Review request `0c85ee01-5841-4a6b-896e-789815a378d8` was accepted and grant
  `79ef69cd-55bc-3925-a179-ff272cc94d12` was issued. Snapshot
  `snap_3e673171518792f078f394bf5dab7cd5` integrated into clean control commit
  `97664dc`; `pytest` reported `3 passed in 0.02s`.
- The rerun then stopped at the next concrete blocker: the reviewer did not
  consume the grant or validate the snapshot, so WorkGroup
  `932d024e-06ff-3176-bef6-12c33279e486` remained ACTIVE and Doctor remained
  DEGRADED with five warnings. Evidence is
  `docs/evidence/syn039-unattended-todo-slice-2026-08-22.md`.
- Exact continuation: run `scripts/agent-resume.ps1`, inspect the existing
  admitted-review validation/closure transition, and implement only the next
  evidence-backed blocker. Do not create SYN-040.

# 2026-08-05 — SYN-038 durable project-command closure

- Preserved CP-0457 as prior evidence and committed the verified durable
  project-command extension as `ad9fdd8addc9f71e806dfb2da5b5d78f050f87ac`.
- Created CP-0458 for closure. It records the shared Git subprocess hang fix,
  the `heartbeatIfPresent` no-participant admission fix, one-renewal lease
  semantics, final verification, preservation of the prior lifecycle history,
  and the absence of SYN-039.
- The repository is ready for the annotated durable-command completion tag and
  safe branch/tag push after the closure documentation commit.

## 2026-08-05 — SYN-038 bounded Git subprocess repair

- Timestamp: 2026-08-05 Europe/Athens
- Active task: SYN-038 durable project-command extension
- Reproduction: focused `McpServerTest` initially blocked at
  `ManagedBaselineTransactionService.runOutput` on `git status --porcelain`
  in a per-test `synesis-mcp-test-*` repository while calling unbounded
  `readAllBytes()` before `waitFor()`. A later thread dump found the same
  pattern in `ManagedPathPolicy.run`.
- Implementation: added the reusable bounded process/Git runner; it closes
  stdin, merges and bounds output, disables Git prompts/editors/signing/hooks,
  applies local Synesis author/committer identity, enforces a ten-second
  deadline, and kills exact descendants on timeout. Routed baseline,
  path-policy, semantic-index, common-directory, project-init, and focused
  test Git setup through it. Added deterministic regression coverage for
  stderr pressure, stdin EOF, timeout diagnostics, descendant cleanup, and
  ordinary/indexed Git setup.
- Verification: runner tests, ProjectApplicationService tests, SYN-038
  namespace tests, MCP framing tests, deferred/fixture validators, and diff
  checks pass. Focused MCP setup no longer hangs; its repeated 31-test class
  still has one existing `command_admission_stale` assertion at line 535.
  Full `check` reaches the same non-hang assertion after 48 tests.
- Exact continuation: capture the stable admission failure and preserve the
  durable SYN-038 protocol while resolving it.

## 2026-08-05 — SYN-038 full-check retry

- Timestamp: 2026-08-05 Europe/Athens
- Active task: SYN-038 durable project-command extension
- Retry: `./gradlew.bat check --no-daemon --max-workers=1 --console=plain`
  timed out after 184 seconds. A `jcmd` dump of the surviving Gradle worker
  showed `org.synesis.mcp.application.McpServerTest.setUp` blocked in
  `Process.getInputStream().readAllBytes()` while its Git helper waited for a
  child process pipe to close. No durable-command test failure was reported.
- Cleanup: stopped only the Gradle daemon/test-worker processes created by the
  retry; the pre-existing long-lived Synesis CLI process was not touched.
- Exact continuation: inspect the existing `McpServerTest` Git helper and
  rerun the checked-in verification without changing unrelated test behavior.

## 2026-08-04 — SYN-038 durable project-command extension activated

- Timestamp: 2026-08-04 Europe/Athens
- Active task: SYN-038
- Completed work: Reconciled SYN-038 as an extension of the completed Codex
  App Server lifecycle phase. Preserved the prior commit, CP-0447/CP-0448,
  ADR-0043, and all prior acceptance evidence. No SYN-038 tag existed in the
  repository, so no historical tag was overwritten or recreated.
- Decisions: Added ADR-0044 for the frozen durable-command architecture.
  Durable project commands are the active SYN-038 phase. Capacity, cleanup,
  unsupported-format, crash-injection, and pinned-evidence cases are
  deterministic fixtures only; real-Codex acceptance is limited to identity,
  durability, replay, admission, protected interruption, and natural
  completion.
- Remaining work: Begin the bounded namespace, permanent-lock, format,
  process-anchor, and two-process implementation spike.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`

- Timestamp: 2026-08-05 Europe/Athens
- Checkpoint: CP-0450
- Active task: SYN-038 durable project-command extension
- Completed work: Added the bounded command namespace, permanent physical
  locks, format/integrity gates and supported migration evidence, one fresh
  MCP process identity, typed replay/conflict admission, read-only existing
  lookup, STARTING/RUNNING/TERMINAL persistence, strict bounded MCP framing,
  WorkIntent mutation preconditions, namespace reconciliation, terminal
  history compaction through cleanup, cleanup/repair fail-closed guards, and
  doctor/get_next_action diagnostics. Preserved the existing ten-tool catalog
  and prior App Server history.
- Verification: focused namespace/lock/capacity/migration/cleanup tests,
  focused MCP framing tests, workspace/MCP compilation, strict Javadocs,
  `check -x test`, deferred/fixture validators, bootstrap Go tests/vet,
  doctor, and `git diff --check` pass.
- Failed attempts: requested aggregate tests and full `check` blocked in
  pre-existing Git/CLI subprocess readers (`McpServerTest` setup,
  `WorkspacePatchServiceTest`, and `UnifiedCliSyncProcessTest`); runs were
  stopped after thread-stack evidence, with no durable-command assertion
  failure reported. Do not reinterpret this as a durable-command pass.
- Exact continuation: investigate the independent subprocess-test hang, then
  rerun the full requested verification. Preserve prior interruption evidence,
  do not create SYN-039, and do not add an approval operation.

## 2026-08-04 — SYN-038 durable-command namespace and admission slice

- Active task: SYN-038 durable project-command extension
- Completed work: Added the host-wide namespace skeleton, permanent lock
  objects, atomic compatibility/integrity metadata, physical worktree
  identity, fresh process anchors, typed request/digest canonicalization,
  durable records, process-local command protection, and the first MCP
  `run_command` admission/replay path. Added `ProjectCommandNamespaceSpikeTest`.
- Verification: The focused workspace spike suite passed; workspace and MCP
  Java compilation passed. Strict workspace Javadocs failed only because new
  public APIs still need complete `@param`/`@return` documentation.
- Remaining work: Repair Javadocs, add lease-gap/capacity/cleanup/format/crash
  and two-process deterministic fixtures, then complete lifecycle integration.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`

Append-only operational history.

## Entry format

- Timestamp:
- Checkpoint:
- Active task:
- Completed work:
- Files changed:
- Commands run:
- Results:
- Decisions:
- Failed attempts:
- Remaining work:
- Exact continuation:

## 2026-08-03 — SYN-038 restarted-harness real-owner verification

- Timestamp: 2026-08-03 Europe/Athens
- Checkpoint: CP-0444 (next checkpoint records this entry)
- Active task: SYN-038
- Completed work: Re-ran the production `synesis coordination serve` owner
  after the harness restart with local Codex `0.146.0-alpha.9.2`; verified
  authority-before-START, durable START ordering, exact thread/turn identity,
  concurrent WAIT plus STEER, exact-turn INTERRUPT, independent live-MCP
  barrier evidence, passive owner restart, exact-thread RESUME, explicit
  continuation, duplicate STATUS/WAIT replay, and clean fixture process
  cleanup. Codex MCP `ensure_session`, revision-matched `apply_patch`, and the
  approved validation command completed through the existing ten-tool surface.
- Files changed: `docs/evidence/syn038-real-codex-app-server-acceptance-2026-08-03.md`,
  `docs/agent/CURRENT.md`, `docs/agent/GOAL.md`, `docs/agent/NEXT_SESSION.md`,
  `docs/agent/STATE.md`, and this log. No production source was changed in
  this verification slice.
- Commands run: focused lifecycle Gradle suite; sequential root
  `./gradlew.bat check --no-daemon --max-workers=1 --console=plain`; deferred
  validator; fixture validator; bootstrap `go test -count=1 ./...`; bootstrap
  `go vet ./...`; `git diff --check`.
- Results: all deterministic and repository gates passed. Root `check` ended
  `BUILD SUCCESSFUL` in 13m11s; focused lifecycle tests and 26 tests passed;
  deferred/fixture validators, Go tests/vet, and diff checks passed.
- Decisions: Preserve the independent interruption outcome
  `turn_interrupted_command_remained_active`; do not infer MCP cancellation or
  command-tree cleanup from the interrupted turn. Preserve the authoritative
  `finish_lane` result `task_not_ready` with no snapshot/integration action;
  do not bypass Synesis or fabricate completion metadata.
- Failed attempts: The first temporary WAIT/STEER JShell wrapper contained
  unescaped Windows backslashes and submitted neither request; the corrected
  production calls succeeded. The valid STEER run proved acceptance and exact
  identity, but the foreground MCP command serialized the later mutation; the
  file change is attributed only to the subsequent explicit continuation.
- Remaining work: Codex-to-MCP cancellation/tree termination and normal
  Codex-driven `finish_lane` snapshot/integration remain unproven. Keep the
  current partial acceptance unless an existing Synesis workflow reports a
  ready completion action.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`

## 2026-08-03 — SYN-038 final verification and completion

- Result: SYN-038 is complete at CP-0447. The restarted production owner
  cleanly reconciled its lifecycle record to `STOPPED` after the acceptance
  run; no owner or App Server process was left running.
- Verification: focused Codex lifecycle tests passed; the full sequential
  `./gradlew.bat check --no-daemon --max-workers=1 --console=plain` ended
  `BUILD SUCCESSFUL`; bootstrap `go test -count=1 ./...` and `go vet ./...`,
  deferred and fixture validators, and `git diff --check` passed.
- Acceptance: the fresh exact binding completed Codex-driven mutation,
  validation, snapshot publication, and integrated `finish_lane` on the exact
  resumed thread. The independent command-cleanup outcome remains
  `turn_interrupted_command_remained_active`; no MCP cancellation or process
  cleanup claim was added.
- Documentation: CURRENT, GOAL, STATE, TASKS, NEXT_SESSION, and the acceptance
  evidence now record completion and the explicit cleanup limitation. No new
  architecture, approval operation, daemon, listener, provider abstraction,
  or MCP tool was introduced.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`

## 2026-07-25 — Post-MVP Hardening Slice 4: Doctor diagnostics and safe administrative repair

- Timestamp: 2026-07-25 Europe/Athens
- Checkpoint: CP-0190
- Active task: SYN-014D
- Completed work: Implemented unified read-only `DoctorService` diagnostics (`synesis doctor`), 38 machine-readable finding codes, severity/status/confidence models, actionable recommendations, read-only guarantees, concise/verbose/JSON formatters, and a separate, reviewable, narrowly scoped repair-plan system (`synesis repair`: `--dry-run`, `--prepare`, `--show-plan`, `--execute`, `--rollback`) with immutable persisted plans (`RepairPlanStore`), repair execution lock (`RepairExecutionLock`), append-only journal (`RepairExecutionJournal`), and pre-mutation backup & exact atomic rollback (`RepairBackupService`). Added unit/integration tests for DoctorService, RepairPlan, DoctorCommand, and RepairCommand.
- Files changed: workspace/src/main/java/org/synesis/workspace/doctor/*, workspace/src/main/java/org/synesis/workspace/repair/*, cli/src/main/java/org/synesis/cli/command/DoctorCommand.java, cli/src/main/java/org/synesis/cli/command/RepairCommand.java, cli/src/main/java/org/synesis/cli/SynesisCli.java, tests in workspace/cli, docs/agent/*
- Commands run: `.\gradlew.bat check --no-daemon`, `powershell -ExecutionPolicy Bypass -File scripts/agent-checkpoint.ps1`
- Results: BUILD SUCCESSFUL — 49 actionable tasks, all 135+ unit tests and smoke tests passed cleanly.
- Decisions: DoctorService is strictly read-only by construction; DoctorCommand performs no repair logic; repair actions require prepared immutable plans with SHA-256 content verification; backups stored outside control checkout under admin directory; MCP tools count remains 11; provider config remains diagnostic-only.
- Failed attempts: `DoctorServiceTest` initially failed on `ProjectApplicationService.init` argument count; fixed by using single Path parameter `init(tempDir)`; Javadoc doclint warning on record constructors; fixed by adding compact constructor doc comments.
- Remaining work: Post-MVP operational milestone.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`

## 2026-07-27 — Bootstrap launcher and immutable-tree cleanup correction

- Checkpoint: CP-0236
- Completed work: The Unix stable launcher is chmodded `0755` after atomic
  replacement, and uninstall makes immutable installation trees removable.
- Verification: `go test -count=1 ./...`, `go vet ./...`, and `git diff --check`
  pass on the local Windows host. Docker was unavailable for a local Linux run.
- Failed attempts: The first portability correction fixed the staging rename but
  exposed launcher mode and immutable cleanup failures on Linux CI.
- Remaining work: GitHub Actions verification.

## 2026-07-27 — Linux MCP absolute file-URI binding correction

- Completed work: `McpProtocolHandler.parseUriOrPath` now preserves absolute
  Unix paths by parsing `file:` values through `Path.of(URI)`; a portable
  absolute-file-URI regression assertion was added.
- Root cause: the previous parser stripped all leading slashes from decoded
  `file:` URIs, turning `/tmp/...` into a relative path on Linux while Windows
  remained masked by drive-letter paths.
- Verification: focused `McpServerTest` and full
  `./gradlew.bat clean check --no-daemon --dependency-verification=strict`
  pass locally.
- Remaining work: review/commit disposition and GitHub Actions verification.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`

## 2026-07-27 — Immutable bootstrap test cleanup

- Completed work: Versioned-install tests now remove immutable installation roots
  through the safe cleanup helper before Go's `TempDir` cleanup runs.
- Verification: `go test -count=1 ./...` and `go vet ./...` pass locally.
- Remaining work: GitHub Actions verification.

## 2026-07-25 — Post-MVP Hardening Slice 3: Crash reconciliation and task cancellation

- Timestamp: 2026-07-25 Europe/Athens
- Checkpoint: CP-0189
- Active task: SYN-014C
- Completed work: Implemented provider-session lease evidence, strong process verification, suspected-stale grace periods, immutable reconciliation plans, explicit reconciliation execution CLI (`synesis reconcile`), safe recovery of interrupted integrations, durable session abandonment, ambient `synesis.cancel_task` MCP tool (tool #11), dependency invalidation, and semantic ownership release. Added unit/integration/MCP tests proving non-destructive worktree preservation, lease state evaluation, plan immutability, and 11 MCP tools schema registration.
- Files changed: workspace/src/main/java/org/synesis/workspace/lease/*, workspace/src/main/java/org/synesis/workspace/reconcile/*, workspace/src/main/java/org/synesis/workspace/application/AgentTaskCancellationService.java, coordination/src/main/java/org/synesis/coordination/*, mcp/src/main/java/org/synesis/mcp/McpProtocolHandler.java, cli/src/main/java/org/synesis/cli/command/ReconcileCommand.java, cli/src/main/java/org/synesis/cli/SynesisCli.java, tests in workspace/mcp/cli, docs/agent/*
- Commands run: `.\gradlew.bat check --no-daemon`, `powershell -ExecutionPolicy Bypass -File scripts/agent-checkpoint.ps1`
- Results: BUILD SUCCESSFUL — 49 actionable tasks, all 133+ unit tests and smoke tests passed cleanly.
- Decisions: Non-destructive worktree preservation on session abandonment and task cancellation; external administration directory storage for leases, plans, locks, and journals; ambient task cancellation authorization enforced strictly for owning worker; 11 MCP tools registered in `tools/list`.
- Failed attempts: `CoordinationService` authorization initially failed for `SESSION_ABANDONED` event due to missing bypass entry; fixed by adding new event types to `CoordinationService` bypass list; `PredictionProjection` initially failed on `current == null` for non-prediction events; fixed in `PredictionProjection.transition`.
- Remaining work: SYN-014D, SYN-014E (installer/updater, demo fixtures).
- Exact continuation: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`

## 2026-07-25 — Post-MVP Hardening Slice 2: Controlled lifecycle cleanup execution

- Timestamp: 2026-07-25 Europe/Athens
- Checkpoint: CP-0188
- Active task: SYN-014B
- Completed work: Implemented controlled lifecycle cleanup execution using Slice 1 foundation. Created `PersistedCleanupPlan`, `PersistedCleanupPlanEntry`, `CleanupPlanStore`, `CleanupExecutionLock`, `CleanupExecutionRecord`, `CleanupEntryExecutionState`, `CleanupExecutionJournal`, `LifecycleQuarantineService`, and `CleanupExecutionService`. Extended `CleanupCommand` with `--prepare`, `--show-plan <plan-id>`, and `--execute <plan-id>`. Added 10 unit/integration/mutation tests proving safe worktree removal (without `--force`), exact file deletion, atomic quarantine move, lock acquisition, journal idempotency, and zero control checkout / durable state mutations.
- Files changed: workspace/src/main/java/org/synesis/workspace/cleanup/*, workspace/src/test/java/org/synesis/workspace/cleanup/*, cli/src/main/java/org/synesis/cli/command/CleanupCommand.java, cli/src/test/java/org/synesis/cli/Slice2MutationBoundaryTest.java, cli/src/test/java/org/synesis/cli/CleanupCommandTest.java, docs/agent/*
- Commands run: `.\gradlew.bat check --no-daemon`, `powershell -ExecutionPolicy Bypass -File scripts/agent-checkpoint.ps1`
- Results: BUILD SUCCESSFUL — 49 actionable tasks, all pass. Checkpoint CP-0188 created.
- Decisions: Atomic move for quarantine; non-forced git worktree removal for worktrees; strict precondition re-verification at execution time; project-scoped lock stored outside control checkout under admin directory.
- Failed attempts: `NoForceAndSafetyArchitectureTest` initially failed due to comments containing `--force`; fixed by stripping comments before code checks; `WorkerCleanupTest` failed due to relative git-common-dir output resolution; fixed in `LifecyclePathVerifier` and `CleanupEligibilityService`.
- Remaining work: SYN-014C through SYN-014E (remaining hardening slices).
- Exact continuation: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`

## 2026-07-25 — Post-MVP Hardening Slice 1: Read-only lifecycle inventory and cleanup dry-run

- Timestamp: 2026-07-25 Europe/Athens
- Checkpoint: CP-0187 (pending)
- Active task: SYN-014A
- Completed work: Implemented full read-only lifecycle cleanup planning foundation. Created 12 domain enums and records in `org.synesis.workspace.cleanup`: `LifecycleResourceType`, `CleanupClassification`, `ProcessEvidenceState`, `CleanupReason`, `LifecycleResourceFingerprint`, `RetentionPolicy`, `LifecyclePathVerifier`, `CleanupPlanEntry`, `CleanupPlan`, `ProcessInspector`. Implemented `LifecycleInventoryService`, `CleanupEligibilityService`, and `CleanupPlanService`. Added `synesis cleanup --dry-run` CLI command (`CleanupCommand`) with concise/verbose/JSON output modes registered in `SynesisCli`. Added 5 test classes including `FullMutationSafetyTest` proving zero runtime mutations. All 49 Gradle tasks pass.
- Files changed: workspace/src/main/java/org/synesis/workspace/cleanup/*, workspace/src/test/java/org/synesis/workspace/cleanup/*, cli/src/main/java/org/synesis/cli/command/CleanupCommand.java, cli/src/main/java/org/synesis/cli/SynesisCli.java, cli/src/test/java/org/synesis/cli/CleanupCommandTest.java, cli/src/test/java/org/synesis/cli/FullMutationSafetyTest.java, docs/agent/*
- Commands run: `.\gradlew.bat check --no-daemon`
- Results: BUILD SUCCESSFUL — 49 actionable tasks, all pass.
- Decisions: Snapshot types (`TASK_SNAPSHOT`, `IMPLEMENTATION_SNAPSHOT`) are evaluated before path safety to ensure they always receive `PROTECTED`+`SNAPSHOT_CLEANUP_NOT_SUPPORTED` regardless of path location.
- Failed attempts: Initial `CleanupCommandTest` used non-existent `TestTerminal` and `initialize()` method; corrected to `ConsoleTerminal` pattern from `WorkspaceCliTest`; `FullMutationSafetyTest` initially used `Files.readString` causing `MalformedInputException` on binary files; corrected to SHA-256 byte hashing.
- Remaining work: SYN-014B through SYN-014E (remaining hardening slices).
- Exact continuation: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`

## 2026-07-25 — Stage 2B Slice 1 capability negotiation implementation

- Timestamp: 2026-07-25 Europe/Athens
- Checkpoint: CP-0179
- Active task: SYN-013D
- Completed work: Implemented Stage 2B Slice 1 capability negotiation with durable handle domain types, binary payload codecs, event store projections, application services, and 2 new MCP tools (synesis.describe_required_capability, synesis.respond_to_owner_request).
- Files changed: coordination/src/main/java/org/synesis/coordination/*, workspace/src/main/java/org/synesis/workspace/*, mcp/src/main/java/org/synesis/mcp/*, docs/agent/*
- Commands run: `.\gradlew.bat :coordination:check --no-daemon`; `.\gradlew.bat :workspace:check --no-daemon`; `.\gradlew.bat :mcp:check --no-daemon`; `.\gradlew.bat :cli:check --no-daemon`; `.\gradlew.bat check --no-daemon`; `git commit -m "Add Synesis MCP capability negotiation"`; `powershell -ExecutionPolicy Bypass -File scripts/agent-checkpoint.ps1`
- Results: PASS; full test suite passed (49 actionable tasks); git commit 1fb83df; checkpoint CP-0179 created.
- Decisions: Handles use SecureRandom Base32 tokens with req_ prefix and upper-case normalization; total tools exposed via tools/list is exactly 7.
- Failed attempts: None.
- Remaining work: Awaiting Stage 2B Slice 2 implementation instructions.
- Exact continuation: powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1

## 2026-07-20 — persistence installation

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0002
- Active task: SL-SETUP-002
- Completed work: Installed and validated durable memory, scripts, and fixtures.
- Files changed: AGENTS.md, docs/agent/*, scripts/*, src/test/resources/agent-persistence-fixtures/*
- Commands run: `powershell -ExecutionPolicy Bypass -File scripts/agent-validate-fixtures.ps1`; resume; doctor; checkpoint
- Results: PASS; invalid fixtures rejected; valid fixture accepted; checkpoint written.
- Decisions: No product architecture decisions.
- Failed attempts: None.
- Remaining work: Install complete v1 contract.
- Exact continuation: Install the complete contract into `docs/agent/CONTRACT.md`, reconcile `GOAL.md`, create the initial product task graph, and unblock `SL-001`.

## 2026-07-20 — contract and architecture installation

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0005
- Active task: SL-001
- Completed work: Installed the complete contract verbatim at revision 1, ran the constraint-driven architecture pass, recorded the modular-monolith and Netty QUIC decisions, created the initial protocol/security/operations documentation, and activated Slice 1.
- Files changed: `docs/agent/CONTRACT.md`, `docs/agent/GOAL.md`, `docs/agent/STATE.md`, `docs/agent/TASKS.md`, `docs/agent/CURRENT.md`, `docs/agent/DECISIONS.md`, `docs/agent/TEST_MATRIX.md`, `docs/agent/NEXT_SESSION.md`, `docs/architecture/`, `docs/adr/`, `docs/protocol/`, `docs/security/`, `docs/operations/`.
- Commands run: resume; durable-memory read; Java 25 check; architecture skill/reference review; primary-source QUIC research; checkpoint.
- Results: Contract and architecture gates pass; product build not yet run.
- Decisions: One Gradle project; Netty 4.2 native QUIC behind an internal adapter, pending build validation.
- Failed attempts: None.
- Remaining work: Materialize the Gradle project and first passing test.
- Exact continuation: Create `settings.gradle.kts`, `build.gradle.kts`, and `gradle/libs.versions.toml` for the single Java 25 project, without adding production networking code.

## 2026-07-20 — build and identity slices

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0006
- Active task: SL-003
- Completed work: Added Gradle Wrapper, Java 25 build, strict compile/Javadocs/tests, formatting/package/static checks, dependency locking and verification, protocol ALPN marker, Ed25519 identity generation/signing/verification, bounded file persistence, and tests.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS.
- Decisions: JDK Ed25519 identity with SHA-256 lowercase hexadecimal `sl1-` node IDs; file storage refuses overwrite and uses atomic sibling moves.
- Failed attempts: JUnit launcher was initially absent; adding the explicit JUnit Platform launcher fixed the test runtime. Provider key algorithm reports `EdDSA`; the implementation accepts the provider alias while generating with `Ed25519`.
- Remaining work: Candidate descriptors.
- Exact continuation: Create `package-info.java`, `Candidate`, and `CandidateDescriptor` under `src/main/java/org/synesis/link/candidate/` with bounded canonical encoding and signature verification.

## 2026-07-20 — candidate descriptor slice

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0007
- Active task: SL-004
- Completed work: Added bounded immutable candidates, deterministic normalization, versioned canonical descriptor encoding, Ed25519 signing/verification, expiry and clock-skew checks, parser limits, and tests.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS.
- Decisions: Descriptor signatures cover canonical unsigned bytes; routes remain distinct from identity.
- Failed attempts: Javadocs initially failed on missing `@return` tags for descriptor accessors; tags were added and the gate passed.
- Remaining work: First real local QUIC connection.
- Exact continuation: Resolve `io.netty:netty-codec-native-quic:4.2.16.Final` with the local platform classifier and add only an internal transport adapter package; do not expose Netty types publicly.

## 2026-07-20 — QUIC transport validation slice

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0007
- Active task: SL-004
- Completed work: Added Netty 4.2.16 native QUIC dependency with Windows runtime classifier, updated locks and verification metadata, enabled explicit native access for tests, and passed a real local QUIC loopback handshake and deterministic close.
- Commands run: `gradlew.bat dependencies --write-locks --write-verification-metadata sha256`; `gradlew.bat clean check --dependency-verification=strict`; targeted `NettyQuicLoopbackTest`.
- Results: PASS for dependency resolution and in-process QUIC loopback; two-process and identity binding remain unverified.
- Decisions: Keep Netty types internal; use insecure TLS trust only in the pre-identity transport test and replace it in Slice 5.
- Failed attempts: Initial JUnit runtime lacked the launcher; deprecated Netty test certificate failed on Java 25; missing QUIC token/connection handlers caused handshake setup failures; each was corrected with explicit launcher, keytool-generated test TLS material, token handler, and connection handlers.
- Remaining work: Production-internal adapter and two-process local harness.
- Exact continuation: Move the passing loopback path into a production-internal transport adapter and add a two-process local integration harness; do not expose Netty types publicly.

## 2026-07-20 - QUIC authenticated control stream

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: pending
- Active task: SL-005
- Completed work: Added bounded `SLH1` handshake envelopes, Netty length framing, internal client/server stream handlers, and a real local QUIC test proving both sides receive an authenticated `PeerSession` only after transcript proofs pass.
- Commands run: targeted `NettyQuicLoopbackTest.establishesIdentityBoundSessionOnLocalQuicControlStream`.
- Results: PASS.
- Decisions: Keep the stream adapter package-private and retain insecure TLS trust only inside the test fixture; application identity is established by the signed transcript proofs.
- Remaining work: Advertised-version negotiation, wrong-identity transport rejection, and authenticated two-process evidence.
- Exact continuation: Extend the control exchange with version offers and update the two-process harness to exchange persistent node identities.

## 2026-07-20 - SL-005 completion

- Timestamp: 2026-07-20 Europe/Athens
- Active task: SL-005 completed; SL-006 is handoff-only.
- Results: PASS. Repeated independent JVMs authenticated expected identities and agreed on version 1/session ID; real-QUIC wrong-identity and no-common-version cases returned categorized failures without `PeerSession`; transcript version tampering was rejected; strict clean verification passed.
- Evidence: `NettyQuicLoopbackTest`, `SessionAuthenticatorTest`, `gradlew.bat clean check --dependency-verification=strict`.
- Handoff: No SL-006 code was implemented.

## 2026-07-20 - SL-006 completion

- Active task: SL-006 completed; SL-007 is handoff-only.
- Completed work: Added bounded SLH1 control frames, CONTROL_READY gating, one-control-stream ownership, protocol-error closure, GOODBYE/GOODBYE_ACK, categorized close reasons, exactly-once terminal completion, idempotent graceful close, duplicate-stream rejection, malformed/oversized parser tests, large non-control stream isolation, and process-level graceful close.
- Results: PASS — `ControlFrameTest`, local QUIC control integration, repeated process test, and `gradlew.bat clean check --dependency-verification=strict`.
- Handoff: No heartbeat, liveness, reconnect, candidate, or application protocol behavior was implemented.

## 2026-07-20 - SL-007 completion

- Checkpoint: CP-0027
- Active task: SL-007 completed; SL-008 is handoff-only.
- Completed work: Added fixed-size versioned HEARTBEAT/HEARTBEAT_ACK payloads, sequence and session binding validation, control-ready liveness start, manual-clock LIVE/SUSPECT/EXPIRED state machine with recovery and delayed-expiry history, public liveness state/metrics/listener API, bounded callback dispatch, terminal precedence and cleanup, local QUIC heartbeat evidence, healthy two-process heartbeat evidence, golden vectors, ADR-0005, protocol state/wire/security updates, and durable-memory reconciliation.
- Files changed: `src/main/java/org/synesis/link/session/`, `src/main/java/org/synesis/link/transport/`, affected tests, `docs/adr/0005-heartbeat-liveness.md`, `docs/protocol/`, `docs/security/`, `docs/agent/`.
- Commands run: targeted codec/liveness tests; local QUIC and two-process tests; `gradlew.bat clean check --dependency-verification=strict`; fixture validator; doctor; resume; checkpoint.
- Results: PASS. Abrupt child-process loss is classified within the documented transport-failure/liveness-expiry bound; physical migration, temporary application silence, and QUIC idle-timeout tuning remain explicitly unverified.
- Decisions: newest valid current-session heartbeat/ACK is the only v1 liveness refresh; duplicates/stale messages do not refresh; first terminal reason wins; expiry is irreversible; transport closure before expiry remains transport failure.
- Failed attempts: initial RED compile failure before liveness production types; one malformed test assumption about heartbeat type binding; one process assertion race fixed with a readiness marker. No failed approach was repeated.
- Remaining work: SL-008 candidate providers and racing.
- Exact continuation: Run `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`, read durable memory, and inspect candidate boundaries; do not implement SL-008 in the handoff.

## 2026-07-20 — SL-008 completion

- Checkpoint: CP-0030
- Active task: SL-008 completed; SL-009 is handoff-only.
- Completed work: Added bounded manual and local-interface providers, explicit provider policy and diagnostics, cancellable concurrent gathering, normalization and privacy filtering, deterministic same-family pair ranking, bounded staggered racing, exact authenticated expected-identity/control-ready winner selection, loser cancellation and late-session cleanup, redacted diagnostics, ADR-0006, protocol/state/wire/security/operations documentation, and local/two-process QUIC candidate-pair integration evidence.
- Files changed: `src/main/java/org/synesis/link/candidate/`, candidate tests, QUIC integration harnesses, `docs/protocol/`, `docs/security/`, `docs/operations/`, `docs/adr/ADR-0006-bounded-direct-candidates.md`, and `docs/agent/`.
- Commands run: targeted candidate tests; `NettyQuicLoopbackTest`; `gradlew.bat clean check --dependency-verification=strict`; fixture validator; doctor; resume; checkpoint.
- Results: PASS. Unsupported router discovery, relays, NAT traversal, physical reachability, path migration, reconnect, and temporary liveness-suppression recovery remain explicitly unverified or out of scope.
- Decisions: direct-only manual/local providers; no fake PCP/NAT-PMP/UPnP/STUN/TURN support; only authenticated control-ready sessions win; deterministic bounded cleanup is required.
- Failed attempts: one combined documentation patch failed due to an exact-text/encoding mismatch and made no changes; smaller exact patches succeeded. No product code was reverted.
- Remaining work: SL-009 reconnect and path behavior, not started here.
- Exact continuation: Read CP-0030 and the SL-009 contract; do not infer SL-009 implementation from this handoff.

## 2026-07-20 — SL-DEMO-001 automated readiness

- Checkpoint: CP-0031
- Active task: SL-DEMO-001; SL-009 deferred.
- Completed work: Added the 27-entry deferred register and validator enforcement in resume, doctor, fixtures, and checkpoints; added demo-gap analysis; implemented bounded `synesis-demo-work/1` request/result records, strict codec, authenticated control-ready binding, bounded QUIC application streams, source-run identity/server/client CLI, local/two-process exchange evidence, first-demo procedure, threat/protocol/release documentation, and ADR-0007.
- Files changed: `docs/agent/DEFERRED.md`, `docs/agent/DEMO_GAP_ANALYSIS.md`, `scripts/agent-validate-deferred.ps1`, durable scripts/docs, `src/main/java/org/synesis/link/demo/`, `PeerSession`, transport demo stream/CLI, tests, `docs/demo/FIRST_DEMO.md`, and release readiness draft.
- Commands run: deferred validator; resume; doctor; fixture validator; RED demo tests; targeted demo/local/process tests; `gradlew.bat demoCli --args=--help`; `gradlew.bat check --dependency-verification=strict`.
- Results: PASS. Physical two-machine normal, abrupt-loss, and wrong-identity runs are not available in this workspace and remain unclaimed.
- Decisions: Keep SL-009 reconnect/path behavior deferred; ship only one fixed demo operation; no NAT/router/STUN/relay/discovery work; preserve two-process versus two-machine evidence distinction.
- Failed attempts: Initial deferred validator invocation needed explicit `$LASTEXITCODE` initialization under PowerShell strict mode; initial application stream ordering incorrectly classified app frames as duplicate control and was fixed at the shared responder boundary. Both were retested.
- Remaining work: Execute and record `docs/demo/FIRST_DEMO.md` on two independent computers; do not add transport features.
- Exact continuation: Run the physical normal, abrupt-loss, and invalid-identity scenarios with sanitized evidence, then update `TEST_MATRIX.md`, `CURRENT.md`, and the checkpoint without claiming unsupported capabilities.

## 2026-07-20 — authenticated session core

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: pending
- Active task: SL-005
- Completed work: Added canonical bounded handshake transcripts, role-specific transcript challenges, Ed25519 proof creation/verification, bounded process-local replay protection, and an immutable transport-neutral `PeerSession`.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS. Unit tests cover transcript round trips, role binding, expected-identity rejection, cross-connection proof substitution, and replay rejection.
- Decisions: The transcript binds ALPN, version, session ID, both role identities, both nonces, and both epochs. QUIC stream integration is still required before calling the session authenticated over transport.
- Failed attempts: Strict Javadocs initially failed because new public accessors lacked `@return` tags; corrected and reran the full gate.
- Remaining work: Exchange transcript/proofs through a bounded bidirectional QUIC control stream and prove wrong-identity rejection with a real transport.
- Exact continuation: Add the internal QUIC control-stream handshake adapter; do not expose Netty types publicly or publish `PeerSession` before authentication completes.

## 2026-07-20 — two-process QUIC slice

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0009
- Active task: SL-005
- Completed work: Added test-only keytool TLS material, a process entry point, and a separate-process integration test. Two Java processes now establish and close a real local Netty QUIC connection using `synesis-link/1`.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS.
- Decisions: The proof is transport-only and uses test-only insecure TLS trust; identity binding is explicitly deferred to Slice 5.
- Failed attempts: Process launch initially shadowed the `java` package name; the executable variable was renamed.
- Remaining work: Identity-bound handshake and PeerSession.
- Exact continuation: Create `package-info.java`, `ProtocolVersion`, and a bounded signed handshake transcript under `src/main/java/org/synesis/link/protocol/`; do not expose Netty types publicly.

## 2026-07-20 — internal transport adapter

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: pending
- Active task: SL-004
- Completed work: Moved Netty codec construction behind package-private `NettyQuicTransport`; the public API remains Netty-free.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS.
- Decisions: Insecure QUIC token handling remains test-only; production adapter accepts a token handler supplied by the future transport configuration.
- Failed attempts: A client builder was incorrectly given connection handlers; Netty 4.2 requires those handlers on `QuicChannel` bootstrap, so the adapter now owns codec construction only.
- Remaining work: Two-process local integration and later identity-bound TLS/session handshake.
- Exact continuation: Move the passing loopback path into a production-internal transport adapter and add a two-process local integration harness; do not expose Netty types publicly.

## 2026-07-20 â€” dependency verification repair

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0032
- Active task: SL-DEMO-001
- Completed work: Added SHA-256 verification entries for the nine Netty 4.2.16.Final source JARs named by the failing Gradle verification report. Each downloaded artifact matched Maven Central's published SHA-256 sidecar.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS; all eight verification tasks completed successfully.
- Decisions: Keep strict dependency verification enabled; do not use `--offline`, disable verification, or record unverified local hashes.
- Failed attempts: None.
- Remaining work: Physical two-computer demonstration remains pending under SL-DEMO-001.
- Exact continuation: Run `docs/demo/FIRST_DEMO.md` on two independent computers and record sanitized evidence.

## 2026-07-20 — candidate scan bug fix

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0037
- Active task: SL-DEMO-001
- Completed work: Changed the local interface provider to skip down adapters instead of terminating the scan. Added `demo/` protection to `.gitignore`.
- Commands run: direct Java candidate gather check; `gradlew.bat :link:test --tests org.synesis.link.candidate.CandidateGathererTest --tests org.synesis.link.candidate.CandidateNormalizationTest --dependency-verification=strict`; full `gradlew.bat clean check --dependency-verification=strict`.
- Results: Direct gather returned 10 candidates; targeted tests PASS. Full check remains blocked by seven package-info files removed in the user commit `0cc4d3a`.
- Decisions: Keep the minimal `continue` fix; do not add interface-specific or VPN-specific filtering.
- Failed attempts: None in the fix; the original failure was the provider’s premature `break`.
- Remaining work: Restore package-info files before a clean gate; rerun the physical demo with a fresh descriptor.
- Exact continuation: Restore the seven required `package-info.java` files, rerun strict check, then restart the server and recopy `demo\node-a.descriptor`.

## 2026-07-20 — package metadata restoration and strict verification

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0038
- Active task: SL-DEMO-001
- Completed work: Restored the seven required package-info.java files from the last valid commit.
- Commands run: `gradlew.bat :link:packageInfoCheck --dependency-verification=strict`; `gradlew.bat clean check --dependency-verification=strict`.
- Results: Focused package check PASS. First full run had one `NettyQuicLoopbackTest` NoClassDefFoundError; targeted rerun PASS and immediate full rerun PASS with all 40 tests.
- Decisions: No test or production workaround for the transient class-loading failure; retain the strict suite unchanged.
- Remaining work: Physical two-computer demonstration remains pending under SL-DEMO-001.
- Exact continuation: Restart the server with the candidate-provider fix and recopy the fresh descriptor.

## 2026-07-20 — first physical normal-operation demo

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0041
- Active task: SL-DEMO-001
- Completed work: Recorded a successful two-computer same-LAN normal-operation run.
- Evidence: `docs/evidence/PHYSICAL-DEMO-2026-07-20.md`.
- Results: Host A advertised 10 candidates; Host B gathered 6 and generated 8 compatible pairs; direct LAN pair selected; both sides authenticated the expected node, reached `CONTROL_READY=true` and `LIVENESS=LIVE`, returned `WORK_RESULT=OK`, closed cleanly, and reported cleanup.
- Claim boundary: Classify only Scenario A as `TWO_MACHINE_VERIFIED`; abrupt process loss and wrong expected identity remain unverified.
- Remaining work: Execute and record Scenario B and Scenario C if physical evidence for those cases is required.
- Exact continuation: Repeat the demo for abrupt process loss, then wrong expected identity.

## 2026-07-20 — package-info gate removed by user request

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0042
- Active task: SL-DEMO-001
- Completed work: Removed the seven package-info.java files, removed the `packageInfoCheck` Gradle task from `:link:check`, and removed the corresponding active-contract requirements.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS; compilation, strict Javadocs, formatting, static analysis, and all 40 tests passed.
- Decision: Package-level `package-info.java` documentation is intentionally out of scope by explicit user direction; public/protected API Javadocs remain required.
- Remaining work: Physical abrupt-loss and wrong-identity demo scenarios remain pending.
- Exact continuation: Repeat the demo for Scenario B abrupt process loss and Scenario C wrong expected identity.

## 2026-07-20 — Synesis root and Link module migration

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0036
- Active task: SL-DEMO-001 after completing SL-ARCH-001.
- Completed work: Moved Link `src/`, module `build.gradle.kts`, and module lockfile into `link/`; added root Synesis settings and build delegation; updated Link CLI commands and durable architecture/contract state; added ADR-0008.
- Commands run: `gradlew.bat projects --dependency-verification=strict`; `gradlew.bat clean check --dependency-verification=strict`; `gradlew.bat :link:demoCli --args=--help --dependency-verification=strict`; resume; doctor; fixture validator; deferred validator.
- Results: PASS; root discovery shows `synesis` and `:link`; root check executes `:link:check`; CLI and durable validators pass.
- Decisions: Keep one root modular monolith with Link as the first subproject; do not invent placeholder Synesis modules.
- Failed attempts: None.
- Remaining work: Physical two-computer demonstration remains pending under SL-DEMO-001.
- Exact continuation: Run `docs/demo/FIRST_DEMO.md` on two independent computers and record sanitized evidence.

## 2026-07-20 â€” Gradle and Kotlin tooling verification repair

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0035
- Active task: SL-DEMO-001
- Completed work: Added verification entries for the Gradle 9.0.0 source distribution and four Kotlin 2.2.0 artifacts named by the failing compile classpath report. The Gradle ZIP matched its official SHA-256 sidecar; Kotlin artifacts were byte-identical between the Gradle Plugin Portal and Maven Central.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS; all eight verification tasks completed successfully.
- Decisions: Keep strict dependency verification enabled; do not disable verification or record unverified local hashes.
- Failed attempts: Kotlin repository `.sha256` sidecar URLs returned 404; exact artifact bytes were independently compared with Maven Central before recording hashes.
- Remaining work: Physical two-computer demonstration remains pending under SL-DEMO-001.
- Exact continuation: Run `docs/demo/FIRST_DEMO.md` on two independent computers and record sanitized evidence.

## 2026-07-20 â€” test runtime dependency verification repair

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0034
- Active task: SL-DEMO-001
- Completed work: Added SHA-256 verification entries for the three JUnit engine/runtime source JARs named by the failing `testRuntimeClasspath` report. Each downloaded artifact matched Maven Central's published SHA-256 sidecar.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS; main, test compile, and test runtime classpaths now pass strict verification and all eight verification tasks completed successfully.
- Decisions: Keep strict dependency verification enabled; source artifacts remain allowlisted only by exact module/version/artifact/hash.
- Failed attempts: None.
- Remaining work: Physical two-computer demonstration remains pending under SL-DEMO-001.
- Exact continuation: Run `docs/demo/FIRST_DEMO.md` on two independent computers and record sanitized evidence.

## 2026-07-20 â€” test dependency verification repair

- Timestamp: 2026-07-20 Europe/Athens
- Checkpoint: CP-0033
- Active task: SL-DEMO-001
- Completed work: Added SHA-256 verification entries for the six JUnit-related source JARs named by the failing `testCompileClasspath` report. Each downloaded artifact matched Maven Central's published SHA-256 sidecar.
- Commands run: `gradlew.bat clean check --dependency-verification=strict`.
- Results: PASS; main and test classpaths now pass strict verification and all eight verification tasks completed successfully.
- Decisions: Keep strict dependency verification enabled; source artifacts are allowlisted only by exact module/version/artifact/hash.
- Failed attempts: The first PowerShell hash-collection command used an unescaped colon in an interpolated variable name and failed before downloading; the corrected command succeeded with no repository impact.
- Remaining work: Physical two-computer demonstration remains pending under SL-DEMO-001.
- Exact continuation: Run `docs/demo/FIRST_DEMO.md` on two independent computers and record sanitized evidence.
## 2026-07-20 — SL-012 zero-configuration onboarding slice

- Checkpoint: CP-0047
- Active task: SL-012
- Completed work: Added automatic identity bootstrap, bounded signed
  listener-first invitations, transcript capability binding, single-use
  admission with 15-second reservation expiry, ephemeral QUIC TLS material,
  source-run `SynesisCli`, and compact Unicode QR rendering while preserving
  `DemoCli`.
- Verification: invitation/bootstrap/admission/QR tests PASS; prior strict
  clean check PASS; two isolated-profile host/join processes completed control,
  liveness, demo work, and close. Physical onboarding is not claimed.
- Remaining work: physical onboarding and deferred demo negative-path
  scenarios remain unverified.
- Exact continuation: perform the documented two-machine onboarding validation
  when two physical computers are available.

## 2026-07-20 — SL-012 behavior-preserving cleanup

- Checkpoint: CP-0048
- Active task: SL-012
- Completed work: Replaced oversized doubled-width ASCII QR output with
  compact Unicode half-block rendering, terminal-width skipping, focused exact
  matrix/dimension tests, and a clearer renderer name. No package moves were
  made because transport classes share package-private handshake ownership.
- Verification: focused QR/protocol/admission tests PASS; forced narrow-terminal
  two-profile onboarding PASS with authenticated control, liveness, work, and
  graceful close. Invitation bytes, handshake, identity, admission/replay,
  and stable onboarding semantics remain unchanged.
- Physical status: physical SL-012 onboarding remains unclaimed; existing
  physical `DemoCli` Scenario A evidence remains valid.
- Exact continuation: perform the documented two-machine onboarding validation
  when two physical computers are available; do not claim it from process
  evidence.

## 2026-07-20 â€” SL-012 terminal QR compatibility fix

- Checkpoint: CP-0050
- Active task: SL-012
- Completed work: Detect whether the process output charset can encode the
  compact Unicode QR glyphs. Unsupported consoles now emit the explicit
  `QR_SKIPPED=UNICODE_UNSUPPORTED` status while preserving `SHARE_LINK`; the
  existing narrow-terminal skip remains unchanged.
- Verification: focused QR tests and `gradlew.bat clean check
  --dependency-verification=strict` PASS; no invitation or transport bytes
  changed.
- Remaining work: physical onboarding and deferred demo negative-path
  scenarios remain unverified.
- Exact continuation: perform the documented two-machine onboarding validation
  when two physical computers are available; do not claim it from process
  evidence.
## 2026-07-20 — standalone CLI development distribution

- Task: SL-013.
- Completed: promoted SL-013; added ADR-0010; added `:cli` with Picocli,
  Application distributions, typed commands, terminal output, QR rendering,
  exit mapping, doctor, and launcher tests; extracted Link onboarding into
  typed `Onboarding` events/failures; removed the legacy `:link:synesisCli` and
  Link-side ZXing/QR path while retaining `DemoCli`.
- Verification: `./gradlew.bat clean check --dependency-verification=strict`,
  `:cli:installDist`, generated launcher smoke, and generated two-profile
  onboarding all PASS.
- Remaining: physical generated-launcher onboarding and physical abrupt-loss/
  wrong-identity scenarios are not claimed.

## 2026-07-20 — SL-013 local generated-launcher validation

- Task: SL-013; validation scope CP-0054.
- Commands: `gradlew.bat clean check --dependency-verification=strict` and
  `gradlew.bat :cli:installDist --dependency-verification=strict` — PASS.
- Generated launcher: `--help`, `--version`, `identity show`, and valid
  `doctor` exited `0`; corrupt identity `doctor` exited `10`; invalid join
  exited `11` with `FAILURE=INVITE_INVALID`.
- Two-profile onboarding: generated `.bat` host/join completed authenticated
  control readiness, liveness, work result, and graceful close; a fresh second
  session also passed. This is one-machine process evidence, not physical
  two-machine evidence.
- Link-level negative paths: abrupt process loss and wrong identity tests PASS.
  Generated early-kill was attempted but did not reach a bounded host terminal
  status, so it is not claimed as generated-launcher abrupt-loss evidence.
- Reconnect: transparent reconnect is deferred; only fresh-session restart was
  observed.
- Next action: review the complete diff for secrets/artifacts, checkpoint, and
  commit the current SL-013/CP-0054 workspace changes without claiming
  physical generated-launcher validation.

## 2026-07-21 — CAF Phase 1 planning review

- Active task: SL-013 remains ACTIVE but frozen by the user's planning-only
  instruction; no implementation was performed.
- Completed work: Read the CAF concept PDF, current roadmap/task/evidence
  records, ADRs, deferred register, protocol/security documents, and the
  actual Link/CLI surfaces. Added the CAF phase map, proposed ADR-0011, and
  blocked `SYN-001` plan for one signed shared decision record.
- Finding: Link's public `PeerSession` permits only fixed demo work and
  `Onboarding` retains the session lifecycle. Authenticated record sync above
  Link cannot be implemented while Link is frozen. Recreating transport or
  treating Markdown as canonical state is rejected.
- Remaining work: user review must choose whether to keep the freeze or permit
  the one transport-neutral Link application-stream prerequisite described in
  ADR-0011. CLI remains frozen in either choice.
- Exact continuation: review `docs/architecture/CAF-PHASE-MAP-AND-RECORD-SLICE.md`
  and ADR-0011 before any task promotion.

## 2026-07-21 — SL-014 activation

- User approved ADR-0011's required Link prerequisite.
- SL-013/CP-0054 is frozen and marked DONE; `:cli` remains unchanged.
- `SL-014` is now the only ACTIVE task. `SYN-001` remains BLOCKED on it.
- Scope is limited to one bounded transport-neutral authenticated application
  stream. No project, record, owner, sync, storage, or CLI work is permitted.
- Exact continuation: implement and verify the SL-014 seam, then checkpoint;
  do not begin SYN-001.

## 2026-07-21 — SL-014 verified

- SL-014 implemented the bounded opaque `SLA1` application-stream seam; Link
  retains framing, bounds, deadlines, liveness, and cleanup, and `:cli` is
  unchanged.
- Focused unit/integration/two-JVM tests and the full strict root check passed.
- Evidence: `docs/evidence/APPLICATION-STREAM-SEAM-2026-07-21.md`.
- `SL-014` is DONE. `SL-015` is the only ACTIVE review gate; `SYN-001`
  remains BLOCKED. No record storage or sync was implemented.

## 2026-07-21 — SYN-001 CP-R2 activation

- User approved closing `SL-015` as DONE and promoting `SYN-001`.
- Active scope is CP-R2 only: canonical signed `decision` v1, bounded
  deterministic encoding, immutable local revision/head storage, restart
  recovery, and a JDK-only inspection launcher in `:project-record`.
- CP-R4 networking, synchronization, extra record types, background behavior,
  and `:cli` changes remain deferred.

## 2026-07-21 — SYN-001 CP-R2 verified

- Added the JDK-only `:project-record` module with canonical bounded `SDR1`
  decisions, Ed25519 signatures, immutable revision/head storage, recovery,
  and local inspection.
- Duplicate, stale-base, conflict, signature/corruption, and restart-recovery
  tests pass; `:cli` and Link sources are unchanged.
- `gradlew.bat clean check --dependency-verification=strict` passed for all
  three modules. Stop before CP-R4 networking or sync.

## 2026-07-21 — SYN-001 CP-R4 verified

- Activated only CP-R4 above the verified SL-014 seam; `:cli` remains
  unchanged and SL-013/SL-015 remain frozen DONE.
- Added strict local project configuration, bounded `SRP1` messages, one-shot
  authenticated publish/sync, deterministic outcomes, and conflict quarantine.
- Two isolated JVM profiles prove initial publish, duplicate retry, valid
  successor, stale detection, same-version conflict, and a head-preserving
  quarantine; a sync request observes the shared head.
- Focused project-record check and full strict root verification pass. Stop
  before CP-R5 and broader CAF functionality.

## 2026-07-21 — SYN-001 closure and SYN-002 planning

- Closed `SYN-001` as DONE at CP-R4; recorded CP-R5 physical decision-record
  transfer as deferred under `SL-D-027`.
- Compared a second `failed-experiment` record with a minimal read-only
  searchable decision view. Selected the view for planning because it reuses
  verified signed decisions without adding schema, sync, or authority surface.
- Added ADR-0013 and promoted `SYN-002` as planning-only. Link and
  `:project-record` remain frozen except for proven blockers; no production
  implementation was started.

## 2026-07-21 — SYN-002 implementation verified

- Implemented only the local `DecisionSearch` view inside `:project-record`.
  It scans bounded fully validated current heads on demand and renders stable
  safe results; it does not persist an index or mutate state.
- Focused search tests and full strict root verification pass. Corrupt heads,
  stale revisions, conflicts, temporary files, bounds, empty results, filters,
  ordering, and restart equivalence are covered.
- Evidence: `docs/evidence/PROJECT-VIEW-SYN-002-2026-07-21.md`. Stop after
  SYN-002 review/closure; no broader CAF slice is started.

## 2026-07-21 — SYN-003 planning and CP-W1 activation

- User approved SYN-003 and ADR-0014 after review through CP-0075.
- Closed SYN-002 as DONE and promoted SYN-003 as the sole ACTIVE task.
- Selected a thin JDK-only `:workspace` composition layer for isolated profile
  bootstrap, one-peer project creation, and revision-1 signed decision
  creation. Link, `:cli`, and `:project-record` production code remain frozen.
- CP-W1 is active. Host/join, sync, networking, and broader CAF behavior remain
  deferred to CP-W2 or later.

## 2026-07-21 — SYN-003 CP-W1 verified

- Added the JDK-only `:workspace` application and generated
  `synesis-workspace` launcher.
- Focused workspace tests and full strict root verification pass. Identity
  reuse, isolated profiles, project overwrite/mismatch refusal, signed
  revision-1 creation, restart readability, bounds, and safe output are
  covered.
- Evidence: `docs/evidence/WORKSPACE-CP-W1-2026-07-21.md`.
- Stop before CP-W2 host/join or sync.

## 2026-07-21 — SYN-003 CP-W2 verified

- Implemented only `:workspace` one-shot `sync host` and `sync join` over the
  existing Link onboarding seam and CP-R4 project-record sync API.
- Join authenticates and checks the expected host before creating B's project
  configuration; existing mismatched configuration is never overwritten. Host
  uses exactly one configured peer. Invitations are emitted only by host and
  are omitted from tests and evidence.
- Generated-launcher process tests cover APPLIED, DUPLICATE, wrong host,
  malformed invitation, project mismatch, missing record, and UNKNOWN on close
  before result. Full strict verification and validators pass.
- Evidence: `docs/evidence/WORKSPACE-CP-W2-2026-07-21.md`. Stop before CP-W3.

## 2026-07-21 — SYN-003 CP-W3 verified and closed

- Implemented `:workspace` search and inspect subcommands, completing CP-W3 and closing SYN-003.
- `decision search` composes the existing `DecisionSearch` API to scan only fully validated current heads.
- `decision inspect --record <uuid>` implements a dedicated single-record validator that validates the head and revision chain of the requested record, isolating it from corruption in other records.
- Outputs for search and inspect are stable, safe, and byte-stable. Standard CLI exit codes are preserved (0 for success, 10 for failure).
- Generated-launcher integration tests verify APPLIED, DUPLICATE, restart stability, empty search, malformed filters, missing records, corruption, conflicts, and stale revisions. Full strict check and validators pass.
- Evidence: `docs/evidence/WORKSPACE-CP-W3-2026-07-21.md`.

## 2026-07-21 — SYN-004 planning and activation

- User requested SYN-004 minimal guided workspace demo flow design.
- Analyzed the two-person demo. Determined that a single copyable URI can safely embed the Link invitation payload, project ID, record ID, and host Node ID parameters while preserving cryptographic host pinning.
- Corrected the roadmap in `PRODUCT_REVIEW.md` to ensure decision records are treated as declarative policies/metadata, never as executable tasks or job scripts.
- Documented the exact operator commands, security guarantees, command contracts, failure behavior, hints, and test matrix in `docs/agent/SYN_004_DESIGN.md`.
- Closed the planning/review task and promoted `SYN-004` as the ACTIVE task. All validators and checkpoint pass.
## 2026-07-22 — SYN-009A promotion

- User supplied the SYN-009A scope for a unified CLI, application services,
  project initialization, and local `.synesis` state layout.
- Repository truth showed SYN-008 DONE and zero ACTIVE tasks, so the mandatory
  resume validator failed before implementation. SYN-009A is now the sole
  ACTIVE task and durable state agrees on its scope.
- Architecture mode is EVOLUTION. The four existing Gradle modules remain;
  `:cli` becomes the only composition root, while `:workspace` becomes a
  library exposing small structured application services.
- Exact continuation: write the implementation note, then implement and test
  the smallest service/CLI vertical slice. Do not begin SYN-009B or SYN-009C.

## 2026-07-22 — SYN-009A verified at CP-0096

- Unified `synesis` launcher, application-service boundaries, project discovery,
  `.synesis` initialization, local profile layout, adapter package boundaries,
  launcher retirement, and documentation are implemented.
- `gradlew.bat clean check --dependency-verification=strict` passed with 34
  actionable tasks. `:cli:installDist`, generated init/repeat/help/hook checks,
  `:workspace:architectureCheck`, and focused service tests passed.
- CP-0096 was created. SYN-009B provider management and SYN-009C portable
  packaging remain unpromoted and unimplemented.
- Exact continuation: commit the verified SYN-009A changes; do not expand scope.

## 2026-07-22 — SYN-009C.1 native bundle

- Checkpoint: CP-0107
- Active task: SYN-009C
- Completed work: Promoted the supplied distribution goal; added the
  implementation note and ADR-0023 through ADR-0025; embedded build metadata,
  `synesis version`, a native jlink runtime, relative launchers, platform
  ZIP/tar.gz tasks, clean-room provider/init/doctor smoke, the Go bootstrapper,
  install scripts, and the CI matrix.
- Commands run: `:cli:platformBundle`; `:cli:bundleSmokeTest`;
  `:cli:platformArchive`; `clean check --dependency-verification=strict`;
  PyYAML workflow parse; attempted `gofmt -w . && go test ./...` with a
  temporary Go 1.26.5 toolchain.
- Results: Java bundle/archive/smoke/strict check PASS; workflow YAML PASS;
  Go attempt failed because the temporary toolchain exhausted disk space and
  its partial GOROOT lacked standard-library sources.
- Decisions: Keep Java authoritative; bootstrapper uses standard-library Go,
  detached Ed25519 plus SHA-256, bounded extraction, version pointer, and
  manual/public-release signing gates. Codex remains degraded and separate.
- Failed attempts: Initial smoke omitted the temp project directory and then
  lacked a configured provider launcher; both were fixed by creating the
  project and setting the bundle launcher fixture. A YAML parse found an
  unquoted colon and was fixed.
- Remaining work: Run/fix Go tests, build native bootstrap, verify fixture
  install/update/rollback/uninstall/doctor, then complete CI manifest evidence.
- Exact continuation: Run `gofmt -w . && go test ./...` from `bootstrap` using
  a complete Go 1.26.5 toolchain.

## 2026-07-22 — SYN-009C.2 Go bootstrap verification

- Active task: SYN-009C
- Completed work: Fixed the missing artifact download call, bounded ZIP/TAR
  size arithmetic, version-path validation, safe executable permissions,
  unsigned file-based development manifests, checksum-verifying installer
  scripts, and native subprocess smoke wiring in CI.
- Commands run: temporary Go 1.26.5 `gofmt -w .`; `go test -v ./...`; `go vet
  ./...`; native Windows `TestNativeBootstrapProcess`; six `GOOS/GOARCH`
  cross-compiles; PowerShell installer parse; YAML parse.
- Results: PASS. Install/update/no-op/failed-update preservation/doctor/
  uninstall-project-preservation, Ed25519 fixture verification, checksum and
  size rejection, ZIP traversal/absolute-path rejection, TAR executable
  preservation, and native process smoke all pass.
- Java follow-up: D: clean-room `clean check --dependency-verification=strict`
  PASS with 39 actionable tasks; `:cli:platformArchive` PASS with ZIP
  25,825,206 bytes and tar.gz 25,591,351 bytes. Two earlier clean-room runs
  were invalidated by copied Gradle caches/C: temp state; the final run used
  a fresh project, disabled build cache, and D: TEMP/TMP.
- Remaining work: create the phase-2 checkpoint, commit the verified slice,
  and continue to SYN-009C.3.

## 2026-07-22 — SYN-009C.3 release-consistency gate

- Checkpoint: CP-0108 phase evidence; CP-0109 final completion.
- Completed work: made native Java bundle smoke explicit in both Windows and
  Unix CI branches; validated the six-platform matrix, manifest aggregation,
  checksum/signature handling, install scripts, release docs, and deferred
  signing limitations.
- Results: final Java/Go/CI/native-smoke/manifest/signature/documentation/clean
  tree gate PASS. No secret was accessed and no release was published.
- Exact continuation: run `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1` and select a separately authorized task.

## 2026-07-22 — SYN-009C post-CP-0109 audit

- CP-0109 was reopened after an isolated real-archive trial found three gaps:
  Windows-only archive smoke, missing bundled launcher self-identification,
  and CI verification of the wrong manifest signature path.
- Fixes: bundle smoke now extracts the native ZIP/tar.gz archive and selects
  the native launcher; platform launchers set `SYNESIS_LAUNCHER`; CI verifies
  `bootstrap/manifest.json.sig`.
- Evidence: D: clean source copy `:cli:bundleSmokeTest :cli:platformArchive`
  PASS; actual Windows ZIP bootstrap trial PASS, including provider lifecycle,
  doctor, project preservation, uninstall, and an install root with spaces.
- Exact continuation: rerun final validators, create CP-0110, commit, and only
  then close SYN-009C.

## 2026-07-22 — SYN-009C final closure

- Checkpoint: CP-0110.
- Commit: `7a40324 Harden cross-platform bundle release trial`.
- Final evidence: Java strict clean build with archive extraction smoke, Go
  native test/vet and six cross-compiles, real Windows ZIP bootstrap trial with
  bundled version/init/provider lifecycle/doctor/uninstall, CI YAML and
  signature-sidecar validation, durable validators, and clean working tree.
- Status: `SYN-009C_STATUS=DONE`. Production signing-key replacement, OS vendor
  signing/notarization, public hosting, and real Codex trust validation remain
  outside this task.

## 2026-07-22 — SYN-010A public preview preparation

- Promoted SYN-010A as the sole ACTIVE task after SYN-009C closed at CP-0110.
- Prepared README, SECURITY.md, CONTRIBUTING.md, and `.gitignore` for an early
  developer preview without adding product behavior, release assets, or
  open-source claims.
- Added `docs/agent/SYN_010A_PUBLICATION_AUDIT.md` and
  `docs/legal/LICENSE_DECISION_REQUIRED.md`.
- Equivalent current-tree/reachable-history secret and privacy scans found no
  credential material; the only password-pattern match is an interactive TLS
  keystore prompt. Personal-looking commit author metadata and the old remote
  target require owner review; no history rewrite or push was performed.
- Verification: resume, doctor, deferred-register validation, fixture
  validation, `git diff --check`, ignore-rule checks, and documentation-link
  checks PASS. Publication was initially blocked by the unfinished license.
- Checkpoint: CP-0111.
- Exact continuation: record the supplied license decision, then obtain
  explicit push authorization before any public GitHub action.

## 2026-07-22 — SYN-010A license decision recorded

- Owner selected GNU AGPL v3.0 only (`AGPL-3.0-only`) and requested the
  commercial-license availability statement in README.
- Replaced the unfinished `LICENSE` placeholder with the canonical GNU AGPL
  text and updated the license decision, audit, and durable-state records.
- Checkpoint: CP-0112.
- No public push or repository visibility change was performed.

## 2026-07-22 — SYN-010A Go verification completed

- Go 1.26.5 was installed and verified with `bootstrap` `go test ./...` and
  `go vet ./...`: PASS.
- The Codex process PATH had not refreshed, so the standard install executable
  was invoked directly. No code changes were required.
- Checkpoint: CP-0113.

## 2026-07-22 — SYN-010A Linux Gradle wrapper permission fix

- Root cause: Git tracked `gradlew` as `100644`, so Ubuntu could not execute
  `./gradlew` and returned exit 126.
- Changed only the tracked mode to `100755`; Git Bash wrapper smoke and full
  `./gradlew check --dependency-verification=strict` both PASS.
- Checkpoint: CP-0114.

## 2026-07-22 — SYN-010A Linux dependency verification repair

- Added the Maven Central SHA-256 for the Linux x86_64 Netty QUIC native JAR
  missing from `gradle/verification-metadata.xml`.
- Updated CLI test process launch to use `synesis` directly on Unix and retain
  `cmd.exe`/`synesis.bat` on Windows.
- Native Windows `gradlew.bat clean check --dependency-verification=strict`:
  PASS. Linux CI remains the required final verification environment.
- Checkpoint: CP-0115.

## 2026-07-22 — SYN-010A remaining Linux test portability fixes

- Claude path test now derives absolute fixtures from the OS-native temp path
  instead of hard-coding a Windows drive path.
- QUIC loopback tests now reuse `TestTlsMaterial`, which selects `keytool` or
  `keytool.exe` by platform; the duplicate hard-coded helper was removed.
- Targeted workspace and link tests pass on Windows. Linux remains the final CI
  validation environment.
- Native Windows `gradlew.bat clean check --dependency-verification=strict`:
  PASS. Checkpoint: CP-0116.

## 2026-07-22 — SYN-010A bundle smoke executable-mode fix

- Root cause: Gradle `tarTree` extraction dropped the Unix launcher mode, so
  `bundleSmokeTest` received `EACCES` on Linux.
- `bundleSmokeTest` now asserts the source launcher mode and restores executable
  mode after extraction before running it. Native Windows bundle smoke passes.
- Checkpoint: CP-0117.

## 2026-07-22 — SYN-010A bundled Java runtime permission fix

- Root cause: Gradle `tarTree` extraction also dropped the Unix mode for
  `runtime/bin/java`, causing the next `EACCES` after the launcher fix.
- `bundleSmokeTest` now asserts and restores the bundled Java runtime mode as
  well. Native Windows bundle smoke passes.
- Checkpoint: CP-0118.

## 2026-07-22 — SYN-010A Unix launcher argument quoting fix

- Root cause: the shared process-test helper added literal `"` characters to
  arguments containing `&` or `*` even when invoking the Unix launcher directly.
- Quoting is now limited to the Windows `cmd.exe` path. Codex and unified-sync
  process tests pass locally; Ubuntu remains the final validation environment.
- Checkpoint: CP-0119.

## 2026-07-22 — SYN-010A macOS dependency verification repair

- Added the Maven Central SHA-256 for the macOS x86_64 Netty QUIC native JAR.
- Strict `:cli:startScripts` resolution with `os.name=Mac OS X` passes.
- Checkpoint: CP-0120.

## 2026-07-22 — SYN-010A CI abrupt-loss timing repair

- GitHub Ubuntu full strict check failed at `NettyQuicLoopbackTest` line 312:
  the server process did not classify the killed client within the ten-second
  parent wait. Local reproduction passed, identifying a timing-sensitive test
  bound rather than a production protocol failure.
- The independent-process helper now uses a test-only 250 ms heartbeat, 1 s
  suspicion, and 2 s expiry policy. Production `LivenessConfiguration.DEFAULT`
  remains unchanged.
- Focused abrupt-loss test passed three consecutive forced runs on Windows.
- Checkpoint: CP-0121.

## 2026-07-22 — SYN-010A Windows ARM64 Java/Actions runtime repair

- GitHub `bundles (windows-arm64)` could not resolve Temurin Java 25; the
  runner exposed only older cached distributions.
- `.github/workflows/release.yml` now uses Microsoft OpenJDK 25, which has a
  Windows ARM64 JDK 25 archive, and upgrades checkout, setup-java, setup-go,
  upload-artifact, and download-artifact to Node 24-compatible majors.
- Workflow YAML parses successfully. GitHub rerun remains required for native
  Windows ARM64 confirmation.
- Checkpoint: CP-0122.

## 2026-07-22 — SYN-010A Windows Gradle version-argument repair

- GitHub Windows bundle packaging parsed the unquoted
  `-PsynesisVersion=0.1.0-dev.10` suffix as task `.1.0-dev.10`.
- Quoting the complete version property in both Windows and Unix archive
  commands fixes the Gradle argument boundary. The exact Windows x64 command
  passes locally with `bundleSmokeTest` and `platformArchive`.
- Checkpoint: CP-0123.

## 2026-07-22 — SYN-010B aggregated release-candidate artifact

- Promoted SYN-010B as the sole active task from the supplied continuation
  request; no GitHub Release or remote publication was performed.
- Added `bootstrap/cmd/aggregate-release-candidate`, its local PowerShell
  wrapper, and validation tests for completeness, duplicates, manifest
  digests, signatures, archive traversal, exact output layout, checksums, and
  sensitive-text/path leakage.
- Changed matrix artifact names to short-retention internal names, added the
  aggregation job, and retained one 30-day `synesis-release-candidate` output.
- Verification: `go test ./...`, `go vet ./...`, PowerShell parse, and
  `gradlew.bat clean check --dependency-verification=strict` PASS.
- Hosted workflow confirmation remains pending until an authorized push/rerun.
- Checkpoint: CP-0126.
## 2026-07-22 — SYN-011 Antigravity real-integration investigation

- Promoted SYN-011 as the sole active task after the supplied failed real
  Antigravity protected-edit observation; preserved the test-project evidence
  before changing configuration or code.
- Found the original generated Windows hook command was not executable because
  the `.cmd` launcher was not wrapped through `cmd.exe`; corrected that command
  minimally and added an exact real-shape `replace_file_content` regression
  fixture.
- Manual exact-command result: `deny`, bounded reason on stdout/stderr, exit
  `0`. Direct constraint result: `BLOCKED`, exit `10`.
- Real Antigravity 1.0.16 still modified the protected file with no observed
  hook invocation or decision in transcript/database diagnostics. Provider
  status is now `DEGRADED`/`UNVALIDATED`; the protected file was restored to
  the canonical bytes.
- Real unrelated editing succeeded with `--add-dir`; proposal replan reached
  `proposals/tcp-fallback.md`. Full sanitized evidence is in
  `docs/evidence/antigravity-real-investigation-2026-07-22/report.md`.
## 2026-07-22 — Local Synesis installation

- Built the current source as `0.1.0-dev.16` with the Windows platform bundle
  task and installed it under
  `C:\Users\Liparakis\AppData\Local\Synesis\versions\0.1.0-dev.16`.
- Switched the local `current` pointer from `0.1.0-dev.15` to `0.1.0-dev.16`;
  the previous version directory was preserved.
- Installed launcher verification passed: `synesis version` reports
  `0.1.0-dev.16`, help exits `0`, and Antigravity status remains intentionally
  `DEGRADED`/`UNVALIDATED`.
- Existing `bundleSmokeTest` still fails because it expects Antigravity
  `HEALTHY`; that expectation conflicts with the real-validation health gate
  and was not weakened.

## 2026-07-22 — SYN-009D stable flat installation promotion

- The supplied stable-layout request activated the ready packaging capability
  `SL-D-024` as SYN-009D and superseded the old deferred entry.
- SYN-011 remains VERIFYING with its failed real Antigravity evidence intact;
  it is no longer the sole active task.
- ADR-0026 records one stable root, sibling staging, one temporary rollback,
  legacy migration, user PATH ownership, and the provider launcher policy.

## 2026-07-23 — SYN-009D implementation and verification

- Replaced versioned bootstrap activation with a flat stable root, sibling
  staging, one rollback root, signed/checksum validation, safe extraction,
  rollback recovery, legacy migration, user PATH ownership, doctor, and
  uninstall cleanup.
- Updated provider launcher resolution to PATH-first plus OS stable fallback;
  preserved the Antigravity degraded-health expectation and existing Link data.
- Passed Go tests/vet, Windows/Linux/macOS cross-builds, strict Java checks,
  disposable migration/rollback tests, and native Windows archive smoke.
- Migrated the local install from `current`/`versions/` to
  `%LOCALAPPDATA%\Synesis` after automated verification; stable `version`,
  application `doctor`, bootstrap `doctor`, PATH resolution, identity
  preservation, and cleanup checks passed.

## 2026-07-23 — Antigravity generated-wrapper correction

- Traced the external `SynesisTestProject` failure: quoted PowerShell `-File`
  parsing prevented wrapper launch; removing quotes exposed the inherited
  working-directory bug; Synesis returned `Project is not initialized.` with
  exit `10` outside the project root and the expected protected-scope deny with
  exit `0` from the root.
- Changed the generated Antigravity integration to write a project-local
  wrapper, resolve `synesis.cmd` PATH-first with the stable fallback, set the
  project root, forward stdin unchanged, keep diagnostics on bounded stderr,
  emit one compact JSON decision, and return exit `0`.
- Added `cli` process regression coverage for a non-project working directory;
  the protected file remained unchanged. No constraints, protected project
  files, or remote repositories were modified.
- Commands: `gradlew.bat :workspace:test --tests
  org.synesis.workspace.ProviderApplicationServiceTest --no-daemon`;
  `gradlew.bat :cli:test --tests org.synesis.cli.AntigravityHookProcessTest
  --no-daemon`; `gradlew.bat clean check
  --dependency-verification=strict --no-daemon`.
- Results: PASS.
- Remaining work: real Antigravity project-hook discovery/loading remains
  unverified; provider status remains `DEGRADED`/`UNVALIDATED` until a real
  protected edit is denied and replanning is observed.
- Exact continuation: run the repository resume script and inspect the new
  checkpoint for durable-state consistency.

## 2026-07-23 — Antigravity reinstall and final wrapper correction

- The real external-project smoke test caught a malformed `cmd.exe /c`
  argument quote in the generated wrapper; corrected it and added an explicit
  `ProcessStartInfo.WorkingDirectory` set to the project root.
- Built a clean versioned Windows bundle as `0.1.0-dev.17` and reinstalled it
  under the stable root using the local development manifest. The stable
  launcher reports `0.1.0-dev.17`.
- Reinstalled the Antigravity provider into
  `C:\Users\Liparakis\Desktop\SynesisTestProject` and invoked its wrapper
  from outside the project root. Result: one compact protected-scope deny
  JSON line on stdout, exit `0`, bounded diagnostic on stderr, and the
  protected-file hash unchanged.
- Final commands: `gradlew.bat clean check
  --dependency-verification=strict --no-daemon`; repository resume; external
  wrapper smoke. Results: PASS.
- Exact continuation: run the repository resume script and inspect CP-0134;
  no feature work remains for this slice.

## 2026-07-24 — SYN-013B project-session binding

- Added automatic project-scoped provider session binding for Codex and
  Antigravity. Bindings are atomic, versioned, local-only, credential-free,
  and preserve project/node identity; explicit provider evidence is hashed and
  missing evidence is reported as fallback.
- Wired both project hook paths and provider install/status. Added ADR-0030,
  migration/installation guidance, protocol and maturity updates, and the
  deferred real-validation gate.
- Verification: `:workspace:test` focused binding tests PASS;
  `:coordination:check` PASS; root `check` PASS; Windows x64 bundle rebuilt and
  installed at `%LOCALAPPDATA%\\Synesis`; external project provider reinstall
  yielded distinct Codex/Antigravity session, supervisor, and worker records
  with unchanged project/node IDs.
- Remaining: real provider mutation interception, workspace transition, and
  re-plan evidence remain deferred; no provider is marked real-validated.

## 2026-07-23 — SYN-012 coordination foundation

- Promoted SYN-012 as the sole active task after the explicit speculative
  coordination request; recorded ADR-0027 and the bounded architecture/state
  machine/protocol plan.
- Added the `:coordination` module with bounded prediction contracts, signed
  command envelopes, coordinator-signed hash-chained events, deterministic
  lifecycle projection, durable command idempotency, and replayable subscriber
  queues. Existing Link and provider behavior were not changed.
- Verification: `.\gradlew.bat :coordination:check --no-daemon` passed,
  including strict Javadocs, format/static checks, and two focused tests.
- Exact continuation: implement loopback HTTP commands and SSE replay, then
  verify reconnect semantics before touching supervisor/provider integration.

## 2026-07-23 — SYN-012 coordination transport

- Added loopback HTTP `/command` and replayable SSE `/events` over the
  coordinator service, preserving signed command idempotency and durable event
  replay.
- Added an HTTP command/SSE replay test. `.\gradlew.bat :coordination:test
  --no-daemon` passed; strict `:coordination:check` passed before this slice.
- Exact continuation: implement provider `REQUEST_OWNER` enforcement and a
  minimal foreground supervisor inbox before worktree speculation.

## 2026-07-23 — SYN-012 real CLI acceptance complete

- Ran the built Synesis CLI as one coordinator and two independent supervisor
  processes across separate profiles, identities, cursors, worktrees, and
  branches. The run passed live ownership/prediction delivery, controlled A
  restart with ordered replay, unresolved and resolved Git gates, real owner
  implementation, validation, and retirement.
- Evidence: `docs/evidence/speculative-coordination-real-cli-2026-07-23/report.md`.
- Root strict verification passed after adding the integration-gate regression
  test: coordination check and root check both PASS.
- Exact continuation: remote HTTPS/enrollment remains deferred; no remote
  publication is authorized.

## 2026-07-23 — SYN-012 ownership and supervisor slice

- Added durable semantic ownership evaluation, policy-first `REQUEST_OWNER`
  guardrail handling for Antigravity/Claude adapters, optional ownership-aware
  constructors, and a foreground `SupervisorInbox` with local event copies.
- Added two-supervisor coordination and ownership non-transfer tests. Root
  `.\gradlew.bat check --no-daemon` passed in 90 seconds, including CLI,
  workspace architecture, Link, project-record, and coordination checks.
- Exact continuation: implement bounded worktree/speculation metadata and a
  Git acceptance gate for accepted predictions.

## 2026-07-23 — SYN-012 speculation isolation

- Added `SpeculationWorkspace` for detached worktrees, bounded metadata, and a
  fail-closed Git gate; whitespace and unmerged index states reject before
  integration.
- Added a temporary Git repository integration test. `.\gradlew.bat
  :coordination:check :workspace:check --no-daemon` passed.
- Exact continuation: run the exact two-node acceptance scenario and preserve
  event sequence, digest, commit, gate, and final Git-state evidence.

## 2026-07-23 — SYN-012 lifecycle evidence

- Added supervisor lifecycle methods and an end-to-end test covering
  `PROPOSED` through `RETIRED` (nine ordered events) with replay evidence.
- Focused coordination tests pass; the remaining unverified requirement is a
  real two-process/CLI acceptance transcript, not just an in-process harness.
- Exact continuation: run the real two-node acceptance scenario and preserve
  event sequence, digest, commit, gate, and final Git-state evidence.

## 2026-07-23 — SYN-012 final local verification

- Final root `.\gradlew.bat check --no-daemon` passed: 42 actionable tasks,
  including strict Javadocs/static analysis, workspace architecture, CLI
  bundle/launcher smoke, Link, project-record, and all coordination tests.
- No real two-process CLI acceptance was claimed; the next slice must capture
  that external transcript before SYN-012 can be marked DONE.

## 2026-07-23 — SYN-012 final bound/race correction

- Enforced the aggregate prediction-contract encoded-size bound and made SSE
  subscription replay registration atomic with respect to command publication.
- Final coordination check passed. Real two-process CLI evidence is still an
  explicit next action, so SYN-012 remains ACTIVE.

## 2026-07-23 — SYN-012 root verification

- Root `.\gradlew.bat check --no-daemon` passed after adding the coordination
  module; workspace architecture validation and existing Link/project-record/
  workspace/CLI checks remain green.
- Exact continuation: implement provider `REQUEST_OWNER` enforcement and a
  minimal foreground supervisor inbox before worktree speculation.
# 2026-07-24 — SYN-013 activation and architecture plan

- Closed SYN-012 against the recorded CP-0144 real CLI evidence and activated
  SYN-013 as the sole ACTIVE task.
- Added the zero-touch architecture package, explicit provider/workspace
  capability gates, phased MVP plan, ADR-0029, and durable-state reconciliation.
- No production code or runtime behavior changed.
- Exact continuation: run the deferred-register validator, strict repository
  check, and checkpoint validator; then create the documentation-only commit.

## 2026-07-24 — SYN-013A activation

- User authorized implementation of the approved AGENTS.md bootstrap contract.
- Promoted SYN-013A as the sole ACTIVE task; scope is limited to init-managed
  AGENTS.md content and focused tests.
# 2026-07-24 — SYN-013B provider-session binding activation

- Closed SYN-013A after AGENTS.md bootstrap tests and bundled smoke passed.
- Activated SYN-013B as the sole ACTIVE task for automatic project-scoped
  provider session binding.
- The implementation preserves project/node identity and keeps fallback
  provider evidence explicitly degraded; real Codex/Antigravity validation is
  not claimed.

# 2026-07-24 — SYN-013B Codex workspace safety correction

- Removed only the stale Codex proof file from the external validation project;
  the Antigravity proof file was absent. The external project has no committed
  `HEAD`, so it cannot receive a detached session worktree.
- Added durable session worktree allocation for committed Git projects and a
  fail-closed hook cwd/worktree gate. Provider status now reports workspace
  assignment and interception as unproven rather than readiness.
- Focused workspace and CLI tests pass; the external project remains degraded
  by design until a real provider session can transition into its assigned
  worktree.

# 2026-07-24 — SYN-013B worktree provisioning and routing

- Added explicit `GIT_HEAD_UNAVAILABLE` handling and an unborn-repository init
  policy that creates one minimal Synesis-only commit when Git has no `HEAD`.
- Added user-local per-project worktree allocation, durable branch/common-dir/
  control-path/verification fields, Git registration and ancestry checks, and
  recovery for deleted or stale worktrees.
- Added an assigned-worktree marker so hooks can load policy from the control
  checkout while resolving patch paths in the assigned tree. The final bundle
  produced a valid external Codex worktree and a no-output assigned-worktree
  hook result; actual Codex interception is still not claimed.

# 2026-07-26 — SYN-014E Slice 5C.2 Codex TOML migration

- Replaced the Codex JSON MCP assumption with bounded editing of the real
  `%USERPROFILE%\\.codex\\config.toml`, preserving unrelated text and nested
  tables while managing only `mcp_servers.synesis`.
- Added compare-and-set migration, verified backup/rollback, idempotent
  lifecycle integration, and read-only Doctor inspection. Source commit:
  `e278ea735318c6dbf84d8bcff3435034335c2322`.
- `codex mcp get/list` recognizes the stable Synesis command and args. A
  bounded real Codex retry reached the agent loop but did not expose Synesis
  tools because MCP handshake timed out; no real provider success is claimed.

# 2026-07-26 — SYN-014E Slice 5C.3 Codex MCP handshake correction

- Resolved the CP-0202 base discrepancy against actual `HEAD` `b61b0dc`; the
  reported `fe8b3e3` is a descendant of reported `e278ea7`.
- Isolated all four local layers. Direct payload and the stable `.cmd` pointer
  returned clean stdio and exactly 11 tools. Opt-in tracing showed Codex
  `0.140.0` sending `initialize` with `2025-06-18`, while Synesis serialized
  integer JSON-RPC IDs as floating-point values (`0.0`), which Codex rejected.
- Corrected `ProviderJson` integer parsing, added protocol-version negotiation,
  and added disabled-by-default trace-file diagnostics. Rebuilt and activated
  Version B `0.1.0-dev-local-5c6`.
- Real Codex now reaches initialize, receives the valid response, sends
  `notifications/initialized` and `tools/list`, and receives all 11 tools.
  Noninteractive Codex runs cancel MCP tool calls, so successful provider tool
  execution is not claimed. Required Gradle and Go verification passes.
# 2026-07-26 — SYN-014E Slice 5C.6 signed Codex closure

- Resolved actual source lineage: `HEAD=8d5447b35772a99f7097faa10f401c6c6156a714`; both reported freshness fixes are ancestors and the source tree is clean.
- Classified prior successful tool calls as `SYNTHETIC_MCP_HARNESS`; no evidence attributes them to a real Codex process.
- Audited the unchanged 11-tool MCP surface: the content-hash precondition is an explicit backward-compatible optional/clarifying contract, with `patch_precondition_required` for omission.
- Built candidate `0.1.0-dev-local-5c10` from the actual HEAD with deterministic payload hash metadata. The established signer requires `SYNESIS_MANIFEST_PRIVATE_KEY_B64`; it is absent, and the unsigned release-mode manifest was rejected before update planning.
- Required Gradle checks, Go tests, and Go vet passed. Existing active `0.1.0-dev-local-5c9`, fixture control checkout, and preserved worker evidence were not altered. No processes were terminated and no worktrees were force-removed.
# 2026-07-26 — SYN-014E Slice 5C.7 signing provisioning and real Codex retry

- Audited the trust model: Ed25519 detached signatures, embedded release public key, signer private key supplied only through `SYNESIS_MANIFEST_PRIVATE_KEY_B64`, and no pre-existing matching local credential or registration path.
- Added explicit acceptance-only trust validation and `provision-acceptance-key`; generated the user-scoped private key outside Git with restrictive ACLs. Acceptance mode requires a signed `developmentOnly` manifest, matching public-key identifier, and explicit `--acceptance`; release verification remains isolated.
- Verified matching-key acceptance, tampered-manifest rejection, wrong-key rejection, unsigned acceptance rejection, and release isolation. Built and activated signed `0.1.0-dev-local-5c11` from `b5f264a`; previous `5c10` remains retained.
- Corrected the Codex MCP launcher entry through supported `codex mcp remove/add`, preserving `other-server` and `notify`; the real Codex process loaded the global config, discovered 11 tools, and executed ensure/read/apply/run/get. A complete final mutation/test closure was not claimed because repeated refreshes caused genuine workspace-generation rejections on later test patches.
- Gradle checks, Go tests, and Go vet passed. Fixture control checkout stayed clean; no process termination or forced worktree removal was used.

# 2026-07-26 — SYN-014E Slice 5C.8 multi-file freshness

- Resolved lineage from reported `b5f264a`/`35cc7bd` to actual implementation base `49da341ae33123cd040ce96f9d393ef8f7aa12ac`.
- Added per-file revisions and explicit reason codes for file revision staleness, patch-context mismatch, and workspace-generation change; exact connection-instance binding is preserved.
- Workspace tests (154), full Gradle checks, Go tests, and Go vet passed. Sequential multi-file and same-file old/new revision tests pass.
- Built, verified, prepared, and activated signed `0.1.0-dev-local-5c12`; active pointer retains `0.1.0-dev-local-5c11` as previous.
- Real noninteractive Codex loaded the preserved TOML, discovered 11 Synesis tools, executed ensure/read/read/apply/apply/run/get, passed `run-tests.cmd`, and returned no pending action. Fixture control checkout remained clean; no process termination or forced worktree removal was used.

# 2026-07-26 — SYN-014E Slice 5C.9 Antigravity discovery preflight

- Resolved the installed Antigravity surface: version 2.3.1 at the local Windows application path; production MCP configuration is `%USERPROFILE%\\.gemini\\antigravity\\mcp_config.json`, with the matching `%USERPROFILE%\\.gemini\\config\\mcp_config.json` mirror.
- Verified the sole Synesis entry uses the stable `synesis.cmd` launcher and exact `mcp --provider antigravity` arguments, with no worker path, fixture path, credentials, or model override. No migration was needed and no unrelated settings were changed.
- Gradle checks and Go tests/vet passed. Fixture control checkout remains clean; signed `0.1.0-dev-local-5c12` remains active with `5c11` previous. Read-only cleanup/reconciliation found no executable actions or ambiguous active leases.
- Real Antigravity GUI discovery and tool execution remain unproven pending the operator's required close/reopen/open-workspace action. No production Synesis defect has been demonstrated.

# 2026-07-26 — SYN-014E Claude Code MCP provider support

- Renamed the canonical provider ID to `claude` and retained `claude-code` as a registry input alias. Claude Code hook configuration remains `.claude/settings.json`.
- Added project-scoped `.mcp.json` management with append/update-only `mcpServers.synesis`, launcher command preservation, `mcp --provider claude` arguments, idempotent reinstall, and unrelated-entry-preserving uninstall. Claude Desktop configuration was not added.
- Focused `ProviderApplicationServiceTest`, provider/module checks, root Gradle `check`, and bootstrap `go test ./...`/`go vet ./...` pass. Existing 11-tool MCP surface is unchanged.

# 2026-07-26 — SYN-015 STRUCT-1A foundational package completion

- Paused `SYN-014E` before production edits and promoted `SYN-015` as the sole validator-recognized ACTIVE primary task for the staged package-structure refactor.
- Completed `STRUCT-1A` only: reorganized packages within `:project-record`, `:coordination`, and `:link`, moved `DemoCli` to `org.synesis.link.cli`, and updated the `link` Gradle main-class reference.
- Required validation passed: `.\gradlew.bat :project-record:check --no-daemon`, `.\gradlew.bat :coordination:check --no-daemon`, `.\gradlew.bat :link:check --no-daemon`, `.\gradlew.bat check --no-daemon`, `go test -count=1 ./...`, and `go vet ./...`.
- Committed the slice as `Reorganize Synesis foundational packages` at `376f2d2ce6003b32d28994b19b6728926ab0af6e`.
- Stopped before `STRUCT-1B`; the only remaining work in this session is durable-state reconciliation and checkpoint creation for the completed slice.

# 2026-07-26 — SYN-015 STRUCT-1B activation

- Sealed the STRUCT-1A durable-state reconciliation in `95f696cc6442b426b28d7e2f2d7b7dd54a43b541`.
- Confirmed a clean worktree and passing `:workspace:check`, `:cli:check`, and `:mcp:check` preflight from that commit.
- Activated `STRUCT-1B — Workspace packages` as the sole active subtask under `SYN-015`; `STRUCT-1C` and `STRUCT-1D` remain inactive.
- No workspace production edits have started. The next action is the required workspace FQN reference inventory.

# 2026-07-26 — SYN-015 STRUCT-1B workspace package completion

- Reorganized only the `:workspace` module: project/bootstrap ownership, provider-specific adapters, JSON/configuration, lifecycle cleanup/reconciliation/repair/lease, and responsibility-specific infrastructure packages.
- Removed production references to the old `workspace.integration`, flat lifecycle, guardrail, provider JSON, and migration Codex TOML package names; aligned moved tests with production packages.
- Added narrow workspace architecture tests for removed integration ownership, delivery isolation, provider-neutral contracts, and production/test separation.
- Required validation passed: `:workspace:check`, `:coordination:check`, `:cli:check`, `:mcp:check`, root `check`, `go test -count=1 ./...`, `go vet ./...`, and focused `McpTool11Test`.
- Committed as `Reorganize Synesis workspace packages` at `b67ac1c`; stopped before `STRUCT-1C`.

# 2026-07-26 — SYN-015 STRUCT-1B ownership correction and final checkpoint

- The initial workspace move exposed an accidental `application -> provider -> project -> application` package cycle.
- Restored `ProjectApplicationService` to `workspace.application` and moved application-facing agent services and `TranslatedOutcome` into `workspace.application`; retained agent response/value records in `workspace.agent`.
- Updated the required CLI FQNs and architecture/test package references. No new interface or durable/public format was introduced.
- Verified `:workspace:check`, `:cli:check`, `:mcp:check`, root `check`, Go tests, Go vet, focused `McpTool11Test`, stale FQN scan, and an acyclic production workspace package graph.
- Committed the correction as `248889a`; created checkpoint `CP-0214`; stopped before `STRUCT-1C`.

# 2026-07-26 — SYN-015 STRUCT-1C MCP package completion

- Activated STRUCT-1C after baseline Gradle and Go validation passed.
- Moved `McpProtocolHandler` to `org.synesis.mcp.application` and `McpStdioServer` to `org.synesis.mcp.transport.stdio`; retained `SynesisMcpServer` at the stable root package.
- Added narrow MCP package architecture tests and protocol/transport package markers; moved MCP tests to mirror the application package.
- Verified `:mcp:check`, focused persistent MCP tests, `:cli:check`, root `check`, and stale production FQN scans.
- Committed as `5cb0656`; STRUCT-1D is now the sole active subtask.

# 2026-07-26 — SYN-015 STRUCT-1D CLI package completion

- Moved command implementations into identity, sync, provider, hook, project, workspace, coordination, prediction, ownership, task, speculation, and lifecycle packages; retained cross-cutting root command wiring.
- Moved representative CLI tests to mirror production command families and added CLI package architecture checks.
- Verified CLI/MCP/root Gradle checks, launcher help/version/init/doctor/provider/workspace/lifecycle acceptance, and stale command-FQN scanning.
- Committed as `958a039`; QUALITY-DEDUP is now the sole active subtask.

# 2026-07-26 — SYN-015 deduplication completion

- Audited repeated infrastructure and lifecycle logic. One strong identical group was confirmed: SHA-256 UTF-8 plan hashing duplicated in cleanup, reconciliation, and repair plan stores.
- Extracted lifecycle-owned `PlanIntegrity` with direct coverage; no generic utility or behavior rewrite introduced.
- Verified `:workspace:check`; committed as `98755b3`; QUALITY-WARNINGS is now the sole active subtask.

# 2026-07-26 — SYN-015 warning cleanup completion

- Warning-mode Gradle audit identified four execution-time `project.file(...)` deprecations in verification tasks.
- Replaced those lookups with `layout` providers without changing the files scanned or runtime behavior.
- Verified root `check --warning-mode all` with zero warning lines; committed as `98cda05`; QUALITY-GOD is now the sole active subtask.

# 2026-07-26 — SYN-015 god-class split completion

- Ranked oversized classes by size and responsibility concentration; selected `ProviderApplicationService` as the strongest safe candidate.
- Extracted MCP configuration persistence into `ProviderMcpConfigurationService` while preserving the stable facade and provider result strings.
- Verified `:workspace:check` and focused provider tests; committed as `04977b9`; final repository validation is next.

# 2026-07-26 — SYN-015 final validation completion

- Full Gradle module matrix and root `check` passed; Go tests and vet passed.
- Focused MCP server/tool tests passed with exactly 11 tools; stale production FQN and production-to-test scans were clean; no module dependency changes were found.
- CLI help/version and command-family help passed; the disposable CLI MCP launcher retains its pre-existing missing MCP runtime classpath limitation.
- Working tree was clean before this final durable checkpoint; `SYN-015` is complete and `SYN-014E` remains paused.
# 2026-07-26 — SYN-016 coordination domain organization

- Reorganized the flat coordination domain into `capability`, `task`, `ownership`, `prediction`, `integration`, `speculation`, and `command` packages.
- Updated production/test references and mirrored coordination tests; added a package ownership architecture test without adding package-info files.
- Verified `:coordination:check`, root `check`, Go tests/vet, stale-FQN scans, and no-flat-package scans.
- Committed as `195fc95`; SYN-016 is complete and `SYN-014E` remains paused.
# 2026-07-27 — SYN-018 repository hygiene

- Promoted `SYN-018` as the sole active task; `SYN-014E` remains paused.
- Inventoried 353 Markdown files (228 checkpoints, 32 ADRs, 16 evidence files)
  and 12 scripts/launchers. Preserved all historical evidence and found no safe
  duplicate script entrypoint to consolidate.
- Updated the README, maintained navigation, provider commands/paths, current
  state, generated `AGENTS.md` instructions, and the durable inventory. The
  generated template source is `ProjectApplicationService`.
- Added root `repositoryHygieneCheck` for maintained links, script references,
  machine paths, canonical provider commands, duplicate script names, and MCP
  tool-count claims. It passes on 39 maintained Markdown files.
- Focused MCP tests and exact 11-tool confirmation, Go test/vet, CLI help/version,
  provider list, and generated-init instructions pass. Sequential root check
  still fails only at the pre-existing workspace architecture-test assertion.
- Commits: `4b7f530` documentation; `59f7c63` hygiene checks. No script cleanup
  commit was needed. Exact continuation: preserve the final checkpoint and
  leave the architecture-test mismatch for a separately authorized task.
# 2026-07-27 — Root AGENTS.md current-boundary correction

- Corrected the handwritten repository `AGENTS.md`, which still described only
  the Link-stage repository. It now names the current CLI, workspace,
  coordination, MCP, and provider boundaries, the exact 11-tool surface,
  revision-bearing MCP reads/patches, canonical provider IDs, and deferred
  remote-networking claims.
- Corrected startup references to the durable files under `docs/agent/`.
- The initialized-project generated template remains owned by
  `ProjectApplicationService` and was not conflated with the repository
  contract.
# 2026-07-27 — SYN-019 workspace architecture-test closure

- Reproduced `WorkspaceApplicationPackageArchitectureTest` failure.
- Inspected the production root and callers: only `ProjectApplicationService`
  remains directly under `org.synesis.workspace.application`; the other names
  in the test allowlist already live in `application.constraint` or
  `application.provider` and have updated callers.
- Narrowed the architecture-test allowlist to `ProjectApplicationService` only;
  no production type moved and no behavior or public surface changed.
- `:workspace:check`, `:cli:check`, `:mcp:check`, root `check`, Go test/vet,
  focused MCP 11-tool tests, CLI help/version, provider list, init, and hygiene
  checks pass before the unrelated README edit appeared. That edit triggers a
  false positive in the existing hygiene count regex (`%20tools-11`) and was
  preserved outside this task. Commit: `a87d3d8`.
## 2026-07-27 — Bootstrap versioned activation portability correction

- Checkpoint: CP-0235
- Active task: SYN-019, with explicit user authorization for one narrow Go
  bootstrap portability correction.
- Completed work: Versioned activation now moves the validated payload into its
  final version directory before applying immutable permissions. If hardening
  fails, the partially hardened payload is made removable and deleted.
- Verification: `go test -count=1 ./...`, `go vet ./...`, `git diff --check`, and
  all six CI target cross-builds pass. The original failure is not reproducible
  on the local Windows host.
- Failed attempts: None.
- Remaining work: Review and commit disposition; do not modify the unrelated
  README edit.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
## 2026-07-27 — Deferred register refinement

- Active task: SYN-019; documentation-only deferred-register cleanup authorized
  by the user.
- Completed work: Reduced the active register to nine realistic capabilities,
  added contract invalidation, out-of-band mutation enforcement, and wait-for
  deadlock detection, merged connectivity capabilities, archived historical
  IDs SL-D-001 through SL-D-030, and created the network validation matrix.
- Product boundary: no Synesis-hosted server, third-party rendezvous, STUN,
  TURN, relay infrastructure, or production peer discovery dependency; no
  harness intelligence or automatic harness-code rewriting claim.
- Verification: deferred validator PASS (9 entries); `./gradlew.bat check
  --no-daemon --dependency-verification=strict` PASS; bootstrap Go test and vet
  PASS; repository hygiene and maintained Markdown link checks PASS.
- Remaining work: Review, checkpoint, and commit this documentation slice.

## 2026-07-27 — Deferred coordination correctness features

- Active task: SYN-019; documentation-only deferred-feature addition.
- Completed work: Reused and expanded `SL-D-037`–`SL-D-039` with explicit
  contract revision/dependency invalidation, out-of-band mutation detection and
  integration enforcement, and wait-for dependency detection without claiming
  implementation. No duplicate IDs or separate parking-lot documents were
  created.
- Product boundary: Synesis communicates invalidation and enforcement evidence
  but does not rewrite, regenerate, or repair harness code; no portable
  filesystem prevention claim is made; lease expiry is not treated as deadlock
  resolution.
- Verification: deferred validator PASS; Gradle strict check PASS; bootstrap
  Go test and vet PASS; no production, CLI, or MCP changes.
- Remaining work: Create CP-0241 and commit this documentation slice.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`

## 2026-07-29 — SYN-025 provider collaboration roadmap completion

- Active task: SYN-025 provider collaboration acceptance and evidence.
- Completed work: Reconciled the final MCP parity slice, direct Codex/Claude/
  Antigravity process evidence, handoff, release, deleted-chat recovery, and
  JSON-safe discovery projections. Marked SYN-025 DONE at CP-0292.
- Provider boundary: direct MCP configuration, connections, mutations, claims,
  handoff, release, and recovery are evidenced for all three providers where
  available. Antigravity model-driven prompting and native-hook maturity remain
  explicitly unclaimed after the recorded harness limitation.
- Verification: `./gradlew.bat check :cli:installDist --no-daemon
  --dependency-verification=strict` BUILD SUCCESSFUL (50 actionable tasks);
  bootstrap `go test -count=1 ./...`; `go vet ./...`; task-tracker `python -m
  pytest -q` (45 passed); deferred validator; and `git diff --check` PASS.
- Remaining work: No collaboration roadmap task remains active. Do not publish,
  push, or claim native provider enforcement without new evidence.
## 2026-08-02 — SYN-036 promotion

- Active task: SYN-036 — Canonical baselines and lineage-aware integration.
- Reconciled SYN-035 as DONE at CP-0399 after the ten-tool lifecycle surface,
  strict schemas, managed manual, Codex ordinary completion evidence, full
  sequential Gradle verification, bootstrap Go tests/vet, and provider
  limitation evidence were recorded.
- Promoted SYN-036 as the sole active task. No production changes were made
  during promotion.
- Exact continuation: inspect the current MCP catalog and administrative-state
  abstractions, then implement task 1.
## 2026-08-02 — SYN-036 task 1 complete

- Completed the authoritative ten-tool MCP catalog foundation in `mcp-contract`.
- Removed the duplicate tools/list schema builder from `McpProtocolHandler`;
  exact schemas now come from one descriptor source.
- Added non-circular wire compatibility, catalog-content, and guidance-artifact
  identities, managed-manual/provider freshness diagnostics, and canonical
  Git-common-directory administrative-state resolution.
- Verification: `:mcp-contract:check`, `:workspace:check`, and `:mcp:check`
  passed sequentially; commit `71b33c5`.
- Next slice: managed baseline classification and transaction-owned ignored
  managed paths.

## 2026-08-03 — SYN-036 task 8 complete

- Active task: SYN-036 — Canonical baselines and lineage-aware integration.
- Completed work: Repair joining now requires an immutable snapshot with the
  expected Synesis snapshot ref and `REPAIR_REQUIRED` completion state. The
  authenticated target lane is materialized from the current control HEAD and
  the original immutable conflict, while dirty target content fails closed.
  `ProjectAppendLock` covers current-head capture, Git materialization, and the
  signed source-release/target-announcement event, so the reserved scope has no
  unowned interval. Repair payloads carry snapshot, control-head, and source/
  target epoch metadata; replay validates the same lineage, and repeated joins
  are idempotent.
- Verification: focused coordination/workspace repair and integration-queue
  tests pass; full `:coordination:test` passes; strict
  `:coordination:javadoc :workspace:javadoc` passes; `git diff --check` passes.
  A broad `:workspace:test` run previously timed out without a completed
  failure report and remains recorded as a runner limitation, not product
  evidence.
- Remaining work: SYN-036 task 9 prerelease migration and legacy cleanup, then
  task 10 real Codex plus Antigravity acceptance. Do not claim provider
  autonomy beyond recorded MCP evidence.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`

## 2026-08-03 — SYN-036 task 9 complete

- Active task: SYN-036 — Canonical baselines and lineage-aware integration.
- Completed work: Audited the first-release boundary after the atomic repair
  slice. The current MCP catalog and handler expose exactly ten raw tools and
  reject decorated/retired lifecycle names; canonical provider IDs are the only
  accepted runtime IDs. Provider/project migration and rollback tests remain
  green, unrelated provider configuration is preserved, and stale current-
  facing ten/eleven-tool and compatibility wording was corrected. Historical
  signed-event decoders and singleton-work-group replay remain intentionally
  because they are required to read existing durable state, not to admit old
  runtime calls.
- Verification: `repositoryHygieneCheck`, focused ten-tool MCP contract tests,
  provider/project migration tests, `git diff --check`, and the affected
  workspace compile pass.
- Remaining work: SYN-036 task 10 real Codex plus Antigravity acceptance.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`

## 2026-08-03 — SYN-036 complete with provider-boundary exception

- Active task: SYN-036 — Canonical baselines and lineage-aware integration.
- Completed tasks 1 through 10. Final fixes included exact requester
  capability filtering for same-node lanes, explicit `INTEGRATION_BLOCKED`
  responses when no eligible candidate remains, hermetic Git setup in the
  bundle smoke test, and removal of generated checkpoint trailing whitespace.
- Real Codex CLI evidence established the exact `src/task_tracker.py` claim,
  negotiated the capability contract, published revision 1 on the authorized
  lineage, and remained fenced while requester validation was pending. A clean
  Antigravity noninteractive process did not complete the MCP workflow and
  produced no claim or mutation; direct Antigravity MCP transport remains
  separately evidenced. No provider-autonomy claim is made.
- Evidence: `docs/evidence/syn036-real-provider-acceptance-2026-08-03.md`.
- Verification: sequential Gradle `check` with strict dependency verification
  PASS; `:mcp:check`, targeted SYN-036 workspace suites, strict Javadocs and
  static checks, deferred validator, bootstrap Go tests/vet, and
  `git diff --check` PASS.
- Disposition: SYN-036 DONE at CP-0407. The only uncompleted requested step is
  Antigravity's external noninteractive model-driven execution, which is
  documented as unsupported without weakening Synesis safety guarantees.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`; review CP-0407 before promoting another task.

## 2026-08-03 — SYN-037 complete

- Active task: SYN-037 — Generic command execution, private runtime state, and
  real Codex completion.
- Completed work: replaced the prerelease command intent/adapter route with
  direct argv execution; added the shared bounded process/evidence primitive;
  added exact common-directory Git exclusions; implemented fail-closed Codex
  hook ownership and conflict diagnostics; added schema-v2 project validation;
  and routed completion/integration validation through the same executor.
- Acceptance: a real Codex CLI participant acquired the exact
  `src/task_tracker.txt` claim, made the change through MCP, produced three
  structured command results including `command_executable_not_found`, called
  the permitted `finish_lane`, published snapshot
  `snap_76f58a1737e488a2a248413dd95d3a3e` with only the tracker path, integrated
  it, and left control HEAD `0237b31e1d1423861c6ce7e4fbe99b1027315189` with
  empty `git status --short`. Evidence is in
  `docs/evidence/syn037-real-codex-acceptance-2026-08-03.md`.
- Verification: focused workspace suites, MCP contract/server suites, strict
  `:mcp-contract:javadoc :mcp:javadoc :workspace:javadoc`, deferred validator,
  bootstrap Go tests/vet, and `git diff --check` PASS. A bounded 300-second
  sequential root `check` attempt timed out in the existing Gradle test
  runner without a completed report; no root-check pass is claimed.
- Decisions: ADR-0042 records the breaking direct-argv schema, shared executor,
  project validation metadata, hook ownership, and raw-byte evidence semantics.
- Disposition: SYN-037 DONE at CP-0410. No new MCP tool or out-of-scope
  provider/coordination architecture was added.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`; review CP-0410 and promote the next authorized
  task.

## 2026-08-03 — SYN-037 root verification repair

- Root cause 1: `Syn037CompletionValidationTest` compared raw file text to an
  LF-only string, while Windows Git checkout semantics legitimately returned
  CRLF. The assertion now compares the exact logical line list, preserving
  process-output bytes and all command evidence semantics.
- Root cause 2: `IntegrationOrchestrationServiceTest` initialized Synesis after
  its initial commit, then snapshotted the repository root against `HEAD^`.
  That made the managed-baseline commit look like a feature delta and caused
  `.synesis/project.json` and `AGENTS.md` to hit `SnapshotArtifactPolicy`.
  The fixture now creates a detached lane from the post-init baseline commit,
  applies only `src/claimed.py`, asserts the lane base equals that baseline,
  and observes exactly `src/claimed.py` as the prepared changed path.
- Focused repaired tests: PASS in 21.6s. `:workspace:check`: PASS in 7m39s.
  Complete profiled root `check`: PASS in 15s after workspace outputs were
  current; profile `build/reports/profile/profile-2026-08-03-09-53-49.html`.
  Deferred validation, bootstrap Go tests/vet, `git diff --check`, and doctor
  all pass (doctor retains its existing personal-path warning).
- Disposition: SYN-037 final verification complete at CP-0415. No production
  command executor or snapshot artifact policy behavior was weakened or
  redesigned.

## 2026-08-03 — SYN-038 lifecycle implementation

- Implemented the Codex-only lifecycle attachment under the existing
  `synesis coordination serve` owner. `ProjectRuntimeHost` now retains the
  signed loopback route, per-binding locks, state/checkpoint store, durable
  idempotency ledger, protocol attachment, evidence journal, and shutdown /
  passive-reconciliation path.
- `ProviderSessionCommand` now runs the existing Synesis session and
  collaboration authority workflow before freezing binding, participant,
  WorkIntent/claim, lane/epoch, control root, worktree, binding version, and
  MCP connection identity into the immutable START envelope. START-side
  verification is read-only and cannot create or repair Synesis authority.
- Added bounded generated-local Codex schema projection and protocol client:
  raw 8 MiB frames, strict UTF-8/JSON handling, `initialized` notification,
  late-response tombstones, correlation failure handling, exact thread/turn
  transitions, event-driven WAIT, bounded HTTP bodies, 512-entry evidence
  queue, 8 MiB journals, bounded retention, and repeated-discovery hard stop.
- Added deterministic lifecycle fixtures for framing, correlation, evidence,
  WAIT control, idempotency, and process-tree termination. Focused lifecycle
  suite: PASS (7 classes, 20 tests). Workspace/CLI/MCP compilation and strict
  Javadocs: PASS. Deferred validation and `git diff --check`: PASS.
- Full `:workspace:test` and `:mcp:test` attempts remain incomplete because
  existing Git-heavy initialization fixtures block in
  `TaskIntegrationServiceTest` / `McpServerTest.setUp`; no broad pass is
  claimed. Installed Codex schema generation and initialize smoke passed, but
  the full real-Codex owner acceptance remains pending.
- Evidence: ADR-0043,
  `docs/evidence/syn038-codex-schema-2026-08-03.md`, and
  `docs/evidence/syn038-real-codex-acceptance-2026-08-03.md`.

## 2026-08-03 — SYN-038 final deterministic hardening

- Durable lifecycle-control results now retain a bounded encoded response (or
  a SHA-256 result reference) so exact duplicate requests replay the prior
  result rather than inventing a new mutation. WAIT HTTP failures and expired
  deadlines return bounded failure responses while still cancelling the owner
  waiter.
- STEER, INTERRUPT, NOTIFY, and continuation-producing RESUME now fail closed
  for a non-active or already-active turn instead of issuing a duplicate Codex
  mutation. The lifecycle route retains bounded request framing and the owner
  remains the existing coordination server process.
- Evidence accounting no longer holds its lock across journal filesystem I/O;
  protocol draining remains non-blocking even when persistence stalls. The
  generated-schema server-request subset handles `currentTime/read` and marks
  approval/elicitation requests as interaction-required. `process/exited` is
  retained as MCP child-process evidence, while root exit remains owned by the
  verified ProcessHandle monitor.
- Final focused lifecycle verification: 24 tests pass across eight lifecycle
  fixture classes. Workspace/coordination/
  CLI/MCP compilation and strict Javadocs pass; deferred validation and
  `git diff --check` pass. Full Git-heavy workspace/MCP initialization suites
  remain incomplete and are not claimed as passing. Real-Codex evidence remains
  the partial owner smoke documented in the evidence report.

## 2026-08-03 — SYN-038 real-owner acceptance and repository gate

- The disposable production owner was exercised through the installed
  `synesis coordination serve` process. A second owner for the same project
  was rejected with `lifecycle_owner_already_running`.
- Real evidence now covers exact authority establishment before START,
  durable START and matching `thread/started`, event-driven WAIT that did not
  starve STEER or INTERRUPT, exact interrupted completion, passive exact-thread
  resume, explicit continuation on the same thread, and duplicate STATUS/WAIT
  result replay. The full record is
  `docs/evidence/syn038-real-codex-app-server-acceptance-2026-08-03.md`.
- Codex 0.145.0 emitted MCP `mcpServer/elicitation/request` for
  `ensure_session` even under an acceptance-only `approval_policy="never"`
  override. The production client correctly recorded `INTERACTION_REQUIRED`
  and did not guess approval. Therefore MCP command cancellation, barrier
  process cleanup, and normal `finish_lane` validation/snapshot/integration
  remain unclaimed.
- Focused lifecycle tests PASS; coordination and CLI checks PASS; workspace/
  MCP compile, format, static, and strict Javadoc gates PASS; deferred and
  fixture validators PASS; bootstrap `go test -count=1 ./...` and `go vet ./...`
  PASS; `git diff --check` PASS. The full root check was attempted after
  fixing report whitespace but exceeded the bounded five-minute window in
  existing long-running test fixtures. No full-root PASS is claimed.
- A real Codex server request exposed that generated `RequestId` accepts
  numeric IDs. The schema projection now validates the string/int64 union and
  the protocol client echoes a numeric server-request ID without changing its
  JSON type; deterministic coverage was added and the focused suite remains
  green.
- An acceptance-only `--disable tool_call_mcp_elicitation` probe moved Codex's
  consent request from MCP elicitation to `item/tool/requestUserInput` but did
  not remove the explicit Allow/Cancel boundary. A concurrent HARD_STOP also
  exposed and fixed an `active` attachment snapshot race during journal
  persistence. The installed distribution was rebuilt after the fix.

## 2026-08-03 — SYN-038 durability verification and full repository gate

- Tightened `LifecycleIdempotencyLedger.verifyCommitted` so a durable-store
  success callback without a readable committed ledger is rejected before any
  lifecycle mutation. Added deterministic coverage for that verification
  failure; the focused Codex lifecycle suite now passes with 25 tests.
- The sequential root command
  `./gradlew.bat check --no-daemon --max-workers=1 --console=plain` completed
  with `BUILD SUCCESSFUL` in 13m24s after the existing Git-heavy fixtures.
  Reports contain zero failures/errors across all module tests. Strict
  Javadocs/static/format gates, deferred and fixture validators, bootstrap Go
  tests/vet, and `git diff --check` pass.
- Real-Codex status remains partial only at the installed MCP elicitation
  boundary: no approval operation was added, and MCP command cancellation and
  normal lane completion remain unclaimed without direct evidence.

## 2026-08-03 — SYN-038 final hard-stop ownership guard

- Repeated-discovery hard stop now returns `ROOT_ALREADY_EXITED` immediately
  when the root was not observable during the initial ownership check; it does
  not enumerate or target descendants whose ownership cannot be proven. A
  deterministic fixture covers that no-target behavior.
- The final sequential root command completed with `BUILD SUCCESSFUL` in
  13m58s after this fix; 26 focused lifecycle tests and all module reports remain
  green with zero failures/errors. Deferred validation, doctor, and diff checks
  pass.

## 2026-08-03 — SYN-038 post-restart direct MCP smoke

- After the Codex harness restart, a fresh initialized disposable project was
  exercised through the installed Codex CLI and the existing Synesis MCP.
  `ensure_session` returned `ready`; after refreshing the managed Codex
  Synesis Manual through the existing provider-install workflow, a supported
  `run_command`/`git_log` intent completed with exit code 0 and output
  `SYN038_MCP_RETRY_OK`.
- The first attempted command shape was rejected as `invalid_path`; no files
  were edited. This proves direct MCP reachability and execution only. It does
  not replace the Codex App Server-owner acceptance or prove MCP cancellation,
  barrier cleanup, or normal lane completion.

## 2026-08-03 — SYN-038 second post-restart production-owner run

- A fresh disposable fixture was exercised through the current installed
  `synesis coordination serve` owner. The first `ProviderSessionCommand`
  authority attempt failed before Codex launch with
  `lifecycle_claim_not_acquired`; the exact intent was released through the
  existing workflow and a fresh connection instance established a new binding
  and claim. No lifecycle service mutation occurred for the failed attempt.
- The successful production START recorded exact binding, participant,
  WorkIntent/lane, epoch, claim, worktree, durable ledger commit, thread
  `019fc837-d48e-7fc2-95ff-c677f06e5135`, and initial turn
  `019fc837-e903-7813-a086-475a76aab5fa`. A second owner was rejected with
  `lifecycle_owner_already_running`.
- Codex 0.145.0 again stopped at the explicit MCP elicitation for
  `ensure_session`; the lifecycle client recorded `INTERACTION_REQUIRED` and
  did not fabricate approval. Exact-turn interruption, a long no-tool turn,
  STEER, terminal interrupted WAIT, duplicate STATUS/WAIT replay, passive
  exact-thread resume, explicit continuation, and bounded hard stop were
  independently exercised. Codex-driven command cancellation remains
  unclaimed.
- After attachment stop, the same ten-tool Synesis MCP surface independently
  returned `ready`, changed the assigned fixture to `implemented`, returned
  `validation_ok`, and completed `finish_lane` with snapshot
  `snap_95f37ea353a1b13a62aea3b32ecc41d1`. This is separate MCP/session
  evidence, not Codex command-cancellation evidence.
- The owner was restarted for a short clean duration to reconcile the stale
  owner record and exited with `STOPPED`. Exact fixture processes were absent
  at cleanup and the control checkout remained clean.

## 2026-08-03 — SYN-038 final3 harness-restart lifecycle probe

- The global installed MCP executable was retried after the harness restart;
  MCP `initialize` and `tools/list` succeeded and returned exactly the ten raw
  tools. The disposable production owner remained the existing
  `synesis coordination serve` topology on loopback.
- A real App Server START reached the exact thread and a foreground barrier.
  Holding WAIT while another client sent STEER proved control capacity was not
  starved. Exact-turn INTERRUPT was exercised both with no observable command
  and after a barrier PID was observed; both results were honestly classified
  `turn_interrupted_command_state_unconfirmed` because Codex/MCP exposed no
  cancellation or `ProjectProcessExecutor` evidence.
- Owner restart was passive: startup recorded the old root as exited/stopped,
  launched no turn, and a later explicit RESUME restored the exact stored
  thread via replacement App Server `thread/resume`/`thread/read`.
- Codex 0.145.0 then emitted MCP elicitation for `get_next_action`. The local
  protocol client preserved `INTERACTION_REQUIRED`; no approval operation was
  added. Normal Codex-driven finish/integration remains unclaimed. Evidence is
  appended to `docs/evidence/syn038-real-codex-app-server-acceptance-2026-08-03.md`.

## 2026-08-03 — SYN-038 harness MCP retry and passive-owner cleanup

- A newly spawned current-bundle `synesis-mcp.exe` process returned protocol
  `2025-06-18`, the exact ten-tool catalog, and `ensure_session=ready` for the
  disposable fixture. The desktop-callable MCP child was an older long-lived
  process and returned an internal session-construction error; it was not
  treated as evidence or terminated.
- The current `synesis.cmd coordination serve` owner acquired a new host,
  passively reconciled the stored attachment to `STOPPED` at revision 46, and
  created no turn. The exact old MCP connection reactivated its participant
  heartbeat, but the old claim/WorkIntent had already been released. A
  lifecycle STATUS request failed closed with `lifecycle_claim_not_acquired`.
- The owner then ran a three-second clean shutdown, leaving owner status
  `STOPPED` and no fixture-owned helper or App Server process. No continuation
  was attempted under stale authority.
- Restarting only the stale desktop MCP Java child caused the app-side
  transport to report `Transport closed`; the Codex app stayed alive. A fresh
  standalone current-bundle MCP invocation immediately succeeded with
  protocol `2025-06-18`, ten tools, and `ensure_session=ready`. The desktop
  connector now needs its own process restart; no Synesis code was changed.

## 2026-08-03 — SYN-038 direct MCP retry after user harness restart

- The callable MCP route was retried twice and continued to return
  `Transport closed`.
- An independent current-bundle `synesis.cmd mcp --provider codex` wire run
  completed `initialize` with protocol `2025-06-18`, `tools/list` with exactly
  ten tools, and `ensure_session=ready` in an isolated worktree. This is direct
  evidence that the bundle is healthy; the desktop connector remains closed.
- No production code, approval route, lifecycle operation, or architecture was
  changed. The remaining Codex elicitation boundary is recorded as an external
  blocker.

## 2026-08-03 — SYN-038 fresh 0.146.0 Codex completion run

- Timestamp: 2026-08-03 Europe/Athens
- Checkpoint: CP-0446 (next checkpoint records this entry)
- Active task: SYN-038
- Completed work: Established a fresh exact provider binding through the
  existing `ProviderSessionCommand` workflow and ran the production
  `synesis coordination serve` owner on loopback port `64337`. The installed
  Codex `0.146.0-alpha.9.2` executable reached the existing ten-tool Synesis
  MCP surface, updated `src/task_tracker.txt`, passed `validation_ok`,
  published snapshot `snap_f3bd879455900258bb77ca6cea8fac22`, and completed
  `finish_lane` integration on the exact resumed thread. Control HEAD ended
  at `9e30f4183434a5f282a6e42fb55e4339c0879578` with clean control status.
- Files changed: `docs/evidence/syn038-real-codex-app-server-acceptance-2026-08-03.md`,
  `docs/agent/CURRENT.md`, `docs/agent/GOAL.md`, `docs/agent/NEXT_SESSION.md`,
  `docs/agent/STATE.md`, `docs/agent/TEST_MATRIX.md`, and this log. No
  production source changed during the acceptance run.
- Commands/run evidence: production START for binding
  `session-8bcc6cc7-1bbf-423a-a28c-ef10a0960b06`; passive exact-thread
  `thread/resume`/`thread/read`; explicit continuation turns
  `019fc947-5a61-7910-aa04-d9bad01b811a` and
  `019fc94e-07f8-7471-a586-8f722486b3ee`; MCP `get_next_action` integration
  check; Codex MCP `finish_lane`; final Git/status and projection inspection.
- Results: lifecycle record completed at revision 21 with exact thread
  `019fc943-ecc7-79f1-af19-930044fc2155`; snapshot is integrated and
  validation exit code is 0. Three stale disposable-fixture coordination
  requests were rejected through the existing coordination service before the
  Codex retry; no authority, claim, or control-checkout bypass was used.
- Decisions: The first fresh turn exposed an older unqualified `codex`
  executable/model mismatch; the owner was restarted with the installed
  `d7e8094cfb76a267\codex.exe`, preserving the same binding and exact thread.
  Preserve the independent interrupted-command classification
  `turn_interrupted_command_remained_active`; do not infer MCP cancellation or
  command-tree cleanup.
- Failed attempts: START against the stale prior connection fingerprint was
  rejected fail-closed; a new exact binding was used. The first fresh Codex
  turn failed only because the owner resolved Codex `0.130.0-alpha.5` with an
  unsupported configured model; no duplicate thread or mutation resulted.
- Remaining work: rerun/checkpoint the final repository verification gate and
  keep the independent command-cleanup outcome explicitly unproven.
- Exact continuation: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`
# 2026-08-05 — SYN-038 durable command admission and Git runner verification

- Reproduced `McpServerTest.testMcpReadFileAndApplyPatchEndToEnd` alone. The
  expected response was terminal `completed`; the actual response was blocked
  with `command_admission_stale` and `LEASE_RENEWAL_FAILED`. The underlying
  failure was `IOException:PARTICIPANT_NOT_FOUND` from the collaboration
  heartbeat after one successful lease renewal. The durable namespace held the
  verified scope and process anchor at object revision 1, namespace revision
  12 in the captured run, an ACTIVE lease, and no STARTING record.
- Traced `McpProtocolHandler → ProjectCommandService → ProjectCommandAdmissionService`.
  The admission protocol already performed one renewal, exact post-renew
  snapshot, authority comparison, protection reacquisition, revalidation, and
  fail-closed no-launch behavior. The production defect was treating a valid
  no-claims session with no participant as a failed heartbeat.
- Added `WorkspaceCollaborationService.heartbeatIfPresent` and used it only in
  durable command admission. It ignores only `PARTICIPANT_NOT_FOUND`; all other
  errors remain blocking. Added a direct no-claims MCP regression and retained
  the original end-to-end assertion.
- Fixed the remaining raw integration-orchestration Git subprocess paths to use
  the shared bounded runner. Added wall-clock timeout protection and Git
  optional-lock/fsmonitor suppression. Captured two bounded full-gate stalls
  with exact worker stacks before this fix; no process tree was left running.
- Updated the doctor read-only test to permit only valid host-wide command
  namespace reconciliation/retention warnings caused by prior test evidence.
- Verification: full `check` PASS in 8m53s; focused MCP class PASS (32 tests),
  SYN-038 focused suite PASS, validators PASS, doctor PASS, bootstrap
  `go test`/`go vet` PASS, and `git diff --check` PASS with existing CRLF
  normalization warnings only. No commit, tag, or SYN-039 created.

# 2026-08-22 — SYN-039 unattended Todo baseline reproduction

- Ran `scripts/agent-resume.ps1`; repository was clean on `master`, active
  task was SYN-039, and CP-0461 was current. No production code was changed.
- Created disposable fixture
  `C:\Users\LIPARA~1\AppData\Local\Temp\syn039-unattended-todo-baseline-20260822`
  with baseline commit `f0c0f99`, then initialized Synesis. Raw Codex JSONL
  captures are retained under the fixture's `baseline-logs` directory.
- Launched two ordinary concurrent Codex sessions without manual relay. Agent
  A created WorkGroup `7c5ab815-5f05-365b-a78b-3478440036af`, lane/intent
  `e167026d-3340-3892-b66c-0cbcb5a1c7ee`, and task
  `39168b91-9fc9-37d5-9703-6d06d251620e`; it implemented Todo completion and
  passed `pytest` with 3 tests.
- Agent B discovered A's WorkGroup and claims but no actionable review grant.
  Its status request failed with `COORDINATION_FIELD_NOT_ALLOWED:body`, its
  join failed with `COORDINATION_FIELD_REQUIRED:grantId`, and status returned
  no requests. No validation item, snapshot projection, or handoff was
  exposed.
- Agent A published snapshot `snap_6162f6fd4ff4d51aadb5484609270ab3`, but
  `finish_lane` returned `integration_pending` / `integration_failed`; retry
  returned `integration_conflict` / `TESTS_FAILED` and requested human help.
  The control checkout stayed at baseline `7a5925f`.
- Post-run coordination status reported zero tasks and zero ownerships, but
  three managed worktrees remained. Doctor was `DEGRADED` with two
  high-confidence `stale_session_lease` warnings, reconciliation recommended,
  and no repair available. Formal evidence is
  `docs/evidence/syn039-unattended-todo-baseline-2026-08-22.md`.
- Result: baseline acceptance FAIL. Exact continuation: inspect existing
  LaneGrant, snapshot projection, validation, completion, and integration
  transitions, then add the smallest deterministic regression fixture for the
  missing reviewer grant and contradictory `pytest`-passing / `TESTS_FAILED`
  completion result.

# 2026-08-22 — SYN-039 reviewer validation and snapshot-publication blocker

- Preserved CP-0467's reviewer-admission and integration work. Added the
  existing-model typed review-validation payload/event, projection state,
  reviewer next-action projection, single-use grant consumption path, and
  structured ACCEPT/REJECT handling. No new MCP tool or orchestration layer
  was added.
- Focused coordination, workspace, and MCP tests passed, including valid
  consumption, wrong reviewer, wrong snapshot, replay, ACCEPT, REJECT, and
  reviewer next-action fixtures. `git diff --check` passed.
- Rebuilt `:cli:installDist` successfully and launched two ordinary Codex
  sessions without manual relay in disposable fixture
  `C:\Users\LIPARA~1\AppData\Local\Temp\syn039-unattended-review-20260822-4`.
  Agent B discovered `REVIEW_ADMISSION_REQUIRED`, submitted the projected
  join request, received grant
  `2f248cda-272e-3a3f-bf9c-92d871198670`, and consumed it. The next action was
  `SNAPSHOT_PENDING` / `wait`; Agent A never published a snapshot. The
  WorkGroup `ed61f1d9-02d8-350b-8188-e27854dc9a21` remained ACTIVE, and the
  control checkout stayed at baseline `4794183`.
- Doctor for the fixture was `DEGRADED` with five warnings. No cleanup or
  repair was performed. The full root `check` reached `:mcp:test` but did not
  complete: `McpServerTest.setUp` remained blocked in
  `ManagedBaselineTransactionService.prepare` / `ProcessCommandRunner` while
  starting a Git subprocess. This is not recorded as a green full check.
- Result: this slice's reviewer admission and grant consumption are verified;
  the next bounded blocker is producer snapshot publication after reviewer
  admission. Exact evidence is
  `docs/evidence/syn039-unattended-todo-review-validation-2026-08-22.md`.

# 2026-08-22 — SYN-039 producer snapshot-publication slice

- Preserved CP-0469 and local commits. Added `SNAPSHOT_PUBLICATION_REQUIRED`
  and the existing `finish_lane` next action. After a consumed targeted REVIEW
  grant, the owner projection now exposes the exact WorkGroup, intent, claim
  epoch, and immutable-publication action. A matching published snapshot
  suppresses the action; ownership, grant, epoch, and fail-closed checks remain
  intact. No new MCP tool or orchestration layer was added.
- Added deterministic MCP/workspace regression coverage and recorded the
  slice in `docs/evidence/syn039-unattended-todo-snapshot-publication-2026-08-22.md`.
  Focused coordination/workspace/MCP tests, `:cli:installDist`, validators,
  bootstrap Go tests/vet, strict Javadocs, and `git diff --check` pass.
- Three fresh ordinary two-session Todo reruns were launched with no manual
  relay or protocol intervention. They all stopped before grant consumption at
  the existing owner REVIEW request. The final fixture
  `C:\Users\LIPARA~1\AppData\Local\Temp\syn039-unattended-todo-publication-20260822-112743-lf3`
  exposed `owner_request_pending`, `respond_coordination`, and request
  `4998d76b-fe4b-4d08-b627-103ed21d4122`; the owner did not accept it. This is
  the current provider-side blocker, not evidence against the new post-grant
  publication projection.
- The serialized root `check` remains incomplete. Focused
  `:mcp:test --tests org.synesis.mcp.application.McpServerTest` reproduced the
  Git subprocess startup stall at `McpServerTest.java:181`; worker PID `24912`
  was blocked through `AgentNextActionService`,
  `RepositoryPrivateStateService`, `GitProcessRunner`, and
  `ProcessCommandRunner`. Existing bounded subprocess hardening was preserved;
  no larger timeout was introduced.
- Implementation commit: `8d4d854 Make SYN-039 snapshot publication
  actionable`. Exact next action: run resume, rerun the two-agent Todo flow
  with projected owner acceptance, verify `finish_lane` after grant
  consumption, and preserve the next lifecycle blocker. No push and no
  SYN-040.

# 2026-08-22 — SYN-039 owner REVIEW-acceptance slice

- Fixed the smallest projection defect after tracing `get_next_action` through
  `AgentWorkflowReducer`: `RESPOND_COORDINATION` previously emitted empty
  arguments even though the owner had a pending REVIEW request. The owner now
  receives the exact strict response payload plus request/WorkGroup/intent/epoch
  context. No new tool, role, orchestrator, side channel, or ownership bypass.
- Added deterministic MCP coverage for exact request projection, correct
  payload, valid advancement, wrong-request fail-closed behavior, and existing
  review-grant progression. Focused tests, Javadocs, validators, Go tests/vet,
  and diff check passed.
- A fresh app-managed unattended run stopped at initial `overlapping_claim`
  admission because another participant already owned both Todo paths. It did
  not reach this slice; no end-to-end success is claimed. Evidence:
  `docs/evidence/syn039-unattended-todo-owner-acceptance-2026-08-22.md`.
- Root `check` reproduced the Git subprocess stall at
  `WorkspaceCliTest.setUp:74` / `ProcessCommandRunner.execute:81` with worker
  `29372` and child `git.exe` `22824`; existing hardening was preserved.
- Next action: rerun the exact fresh Todo acceptance with isolated initial
ownership. No push and no SYN-040.

# 2026-08-23 — SYN-039 CP-0472 unattended Todo acceptance

- Created a fresh disposable Git + Synesis fixture with project
  `6148fa85-90b7-4cbc-8400-51d0d43d2541` and baseline `7c8d341`.
- Launched two independent GPT-5.6 Luna High agents: Agent A was the sole
  initial Todo implementer; Agent B was review-only discovery and could not
  claim Todo paths or create an implementation intent before discovery.
- Both agents independently stopped at typed `workspace_not_ready`, with
  `get_next_action` projecting only `RECOVER → ensure_session`. No WorkGroup,
  claims, requests, grants, snapshots, validation, integration, or closure was
  created. Coordination status remained PASS at sequence zero; Doctor was
  DEGRADED with three warnings.
- No production code changed. The first blocker is recorded as per-project
  MCP/session readiness pending deterministic reproduction. No push and no
  SYN-040.

# 2026-08-23 — SYN-039 CP-0473 workspace-readiness slice

- Reproduced the project-boundary failure path: Codex's managed MCP entry had
  no explicit project root, so a provider session without MCP roots fell back
  to its process directory and failed closed at `workspace_not_ready` →
  `ensure_session`.
- Added the smallest fix in `CodexTomlConfiguration` and
  `ProviderMcpConfigurationService`: project installation now writes the
  existing `--project <root>` MCP argument. Added fresh provider install,
  repeated ensure, and two-independent-session regression coverage.
- Commit `bea47c4 Pin Codex MCP sessions to initialized projects`; evidence:
  `docs/evidence/syn039-workspace-readiness-cp0473-2026-08-23.md`.
- Focused tests, Javadocs, validators, Go vet, and diff check passed. Root
  `check` progressed to the recurring `McpServerTest.setUp:45` Git subprocess
  stall; exact thread evidence was captured. Bootstrap Go tests separately
  failed three migration tests with `update migrations not prepared`.
- Direct MCP with the generated project-pinned entry returned `ready/isolated`.
  The fresh unattended two-agent rerun still stopped before coordination due
  to an incompatible/stale MCP distribution used by the agent harness. No
  WorkGroup or lifecycle state was created. No push and no SYN-040.

# 2026-08-24 — SYN-039 CP-0474 current-bundle unattended acceptance

- Rebuilt `:cli:platformBundle` and configured Codex against the current
  Windows bundle's `bin/synesis-mcp.exe`, SHA-256
  `FAECFCB1B9ED43E9786C922BA880841FCD950FE612B1C359DCD61CD9807FB1BA`.
  Direct preflight reported `0.1.0-SNAPSHOT`, protocol `2025-06-18`, and the
  exact ten-tool catalog.
- Launched two independent GPT-5.6 Luna High agents with no relay,
  assignment, or manual lifecycle transition. Both current-bundle MCP
  processes were observed with the exact project root and both sessions became
  `ready / isolated`.
- Agent A implemented the Todo completion operation and passed four pytest
  tests. Agent B autonomously discovered WorkGroup
  `33e8329c-fd66-3174-9e3f-f115f6dae550`, consumed REVIEW grant
  `496f1893-ca32-3939-82a1-24f860dea86a`, and kept review ownership
  non-overlapping.
- The first post-readiness blocker is producer publication: Agent A's durable
  next action was `PUBLISH` / `snapshot_publication_required`, while repeated
  `finish_lane` calls returned `task_not_ready` / `retry`. No snapshot,
  validation, integration, or closure occurred. Evidence:
  `docs/evidence/syn039-unattended-todo-cp0474-2026-08-24.md`.
- Focused SYN-039/provider/session/coordination/MCP tests passed; deferred and
  fixture validators, Go vet, and `git diff --check` passed. Doctor remains
  `DEGRADED` with five warnings. The recurring root Git stall and bootstrap
  migration-test failures remain separate.

# 2026-08-24 — SYN-039 CP-0486 exact-rule diagnostic

- Completed the CP-0485 stale-recovery trace. The reviewer worktree was dirty
  and based on the pre-integration control revision while the owner had
  advanced control through an unprojected `finish_lane`; the existing binding
  path correctly failed closed with `WORKSPACE_STALE_DIRTY`. Lease/process
  evidence did not prove a Synesis process-loss defect.
- Implemented the smallest agent-facing contract clarification in
  `ProviderManualService` and generated `AGENTS.md`: ordinary `IMPLEMENT`
  without a concrete projected tool is not permission to call `finish_lane` or
  another lifecycle action. Added deterministic manual/generated-contract
  assertions.
- Ran fresh external-harness project
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0486-001` with two
  current-bundle GPT-5.6 Luna sessions. Both reached `ready / isolated`, held
  disjoint claims, and converged on WorkGroup
  `9527b8ec-0971-3f33-995c-ac0833d506c7`.
- Agent A executed the exact projected
  `request_coordination(work_group_join)` action and implemented `todo.py`
  without calling unprojected `finish_lane`. Agent B made an unprojected
  `integrationCheck` request while its isolated worktree correctly lacked A's
  unintegrated implementation; Synesis returned `integration_conflict` /
  `TESTS_FAILED` and `request_human_help`. No exact projected lifecycle action
  failed, and no grant, snapshot, validation, integration, or closure was
  reached. This is agent-compliance evidence, not a new production defect.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0486-exact-rule-diagnostic-2026-08-24.md`.
- Focused workspace/MCP tests, `:workspace:javadoc`, fixture/deferred
  validators, Doctor structural checks, and `git diff --check` passed. The
  root Git subprocess stall, bootstrap migration failures, and fixture Doctor
  warnings remain separate. Next action: run a fresh bounded diagnostic that
  forbids unprojected integration checks, then run the ordinary acceptance only
  if the diagnostic completes. No push and no SYN-040.

# 2026-08-24 — SYN-039 CP-0487 role-order diagnostic

- Created fresh project `94e7b869-4b26-4669-bbd2-5de2e344d018` with current
  bundled MCP and two independent GPT-5.6 Luna sessions. Both reached
  `ready / isolated` with exact disjoint claims.
- Agent B established WorkGroup `a273e5df-a157-3ec7-ae93-211828d0acc2` first,
  so B's test intent was the producer and A's implementation intent was the
  reviewer. Exact REVIEW admission, two exact owner responses, grants
  `5ba56aa7-3887-3ee1-8973-919669144888` and
  `7907440e-cc5d-39a2-a4b6-b228290ff381`, and consumption of the first grant
  all succeeded.
- A correctly received `SNAPSHOT_PENDING` → `wait`. B's last
  `get_next_action` occurred before grant consumption and returned ordinary
  `IMPLEMENT`; B did not poll after the later reviewer action. No exact
  projected producer action failed and no snapshot, validation, integration,
  or closure occurred. This is role-order/agent-compliance evidence, not a
  new production defect.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0487-role-order-diagnostic-2026-08-24.md`.
- Duplicate retry-safe requests/grants are recorded for later idempotency and
  cleanup review. Exact next action: launch the implementation agent first in
  a fresh bounded diagnostic and capture producer publication through reviewer
  validation without relay or manual transition. No push and no SYN-040.

# 2026-08-24 — SYN-039 CP-0497 reviewer-continuity diagnostic

- Committed the previously implemented CP-0495 review-validation catalog fix
  as `706b743` and the CP-0496 reviewer recovery-continuity fix as `d578223`.
  The reviewer now keeps its participant/session identity when only the
  control checkout advances and receives a fresh isolated recovery worktree.
- Ran fresh project
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0497-001` with two
  independent GPT-5.6 Luna sessions and the repository-built MCP. Both
  reached ready/isolated preflight with the same project and ten tools.
- The run reached WorkGroup
  `7c5ac4f7-c538-39c2-8e5d-ed9fadbdc771`, exact REVIEW admission, owner
  acceptance, grant consumption, exact projected `finish_lane`, immutable
  snapshot `snap_3eb0df616deb0c00e78540f63877b1c2`, integration, exact stale
  reviewer recovery, and two exact projected ACCEPT decisions. No exact
  projected action failed. Evidence:
  `docs/evidence/syn039-unattended-todo-cp0497-review-continuity-diagnostic-2026-08-24.md`.
- The first concrete blocker after validation is status inconsistency:
  `respond_coordination` reports `workGroupStatus=COMPLETED`, while final
  collaboration status reports the same group `ACTIVE` with Agent A's active
  second intent and two duplicate grants. The run did not qualify for the
  ordinary second acceptance. The next narrow code action is a deterministic
  response-status regression/fix; do not broaden cleanup or push.
- Focused MCP/workspace tests and Javadocs passed; validators, Go vet, and
  `git diff --check` passed. Bootstrap Go tests retain three migration
  failures. The full root check reproduced the known `McpServerTest` Git
  subprocess failure/stall and was bounded/interrupted after process evidence
  capture. Doctor remains DEGRADED with six fixture warnings.
