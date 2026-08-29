# SYN-039 — CP-0529 continuity probe and ordinary acceptance

Date: 2026-08-25

## Scope

This checkpoint records one supported non-ephemeral session-continuity probe
and the required fresh ordinary two-agent acceptance. No Synesis production
code changed and no manual coordination transition, message relay, ownership
repair, merge, or control-checkout mutation was performed by the harness.

The agents used the current repository-bundled MCP executable:

```text
C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe
version: synesis 0.1.0-SNAPSHOT
SHA-256: 776B1AA22D4EEBE566941FCCDB0F15F544555BAC8C62DB4F1128BAC03A0D9359
tools: 10
```

The ordinary harness pinned both MCP processes to the same initialized
project root and used distinct connection IDs:

```text
syn039-cp0529-002-agent-a
syn039-cp0529-002-agent-b
```

## Continuity probe

Project:

```text
C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-continuity-cp0529-001
projectId: ec8a50d0-1f75-4490-9366-3a578533d87f
```

Participants, intents, claims, and epoch:

| participant                                | intent                                 | claim          | epoch |
|--------------------------------------------|----------------------------------------|----------------|------:|
| `agt_d69273ae-cc06-3ac4-b4b0-9bc760b6a8e8` | `9cb018f1-b745-321d-85c7-4fe1aa04e700` | `todo.py`      |     1 |
| `agt_fce37679-5462-3a41-a8d0-479877de3c70` | `7f3e3c24-a659-3a5e-871a-5ef9a07e6756` | `test_todo.py` |     1 |

Shared WorkGroup: `3d7f36ad-26fa-3099-b4c8-433b24224261`.

Requests and grants:

- request `ff963f7f-ce71-4fcd-bc11-ef86e2a88e67` was accepted as REVIEW;
- grant `887293a2-981e-3010-a6eb-e4f000b14975` targeted the test participant;
- reciprocal request `7179a7b6-a529-474e-9856-71b47a73fbe8` was accepted;
- reciprocal grant `1f446a38-059f-3afd-bcc2-26161ae3fb01` targeted the
  implementation participant.

Agent A implemented `TodoList.complete`, ran `pytest -q test_todo.py` with
3 passing tests, and accepted the owner-side REVIEW request. Agent B first
attempted to consume grant `887293a2-981e-3010-a6eb-e4f000b14975` without the
projected `targetParticipant`; Synesis correctly returned
`policy_denied / COORDINATION_FIELD_REQUIRED:targetParticipant`. B then used
the unchanged projected arguments and consumed the single-use grant.

After resuming the existing A session, Synesis projected exactly:

```json
{"summary":"Publish the completed immutable snapshot"}
```

for `finish_lane`. A executed that exact action successfully. The immutable
implementation snapshot was `snap_3e21542358dd37d57cb6963d6f128557`, commit
`61ff24108f67188ec271393019091d2d2a101e57`, changed path `todo.py`, and the
control checkout advanced to integration commit `e34ee5b`.

B then received `workspace_stale -> ensure_session`. The exact recovery call
`ensure_session({})` returned `failed / internal_failure /
request_human_help`; the same result occurred for the refresh and repair
forms. Inspection of B's bound worktree showed no source edits, but normal
pytest output had left:

```text
?? __pycache__/test_todo.cpython-313-pytest-9.1.1.pyc
?? __pycache__/todo.cpython-313.pyc
```

The existing binding code treats non-managed untracked files as dirty and the
existing dirty-worktree path fails closed. B had also already selected
non-projected reads after the stale state. Therefore this probe does not
prove a new production defect: it reproduces the existing safe dirty-worktree
refusal at an agent/session continuation boundary. No production change was
made.

Final continuity state:

- WorkGroup `3d7f36ad-26fa-3099-b4c8-433b24224261`: `ACTIVE`;
- A: `COMPLETED`; B: `ACTIVE`;
- A's implementation is integrated; B's test lane was not published;
- Doctor: `DEGRADED`, six warnings, zero errors/critical findings;
- final control checkout is clean and contains the implementation; its
  visible Todo tests pass 3/3.

Harness logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-continuity-cp0529-001`.

## Fresh ordinary acceptance

Project:

```text
C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0529-002
projectId: e8058b79-caae-46af-86a8-b77c193ff145
seed commit: 22d59cb
managed baseline: 85584a5
```

The only agent prompts were the two visible coding responsibilities:

- Agent A: implement `TodoList.complete` in `todo.py`, do not modify
  `test_todo.py`, run the relevant tests;
- Agent B: add one focused regression test in `test_todo.py`, do not modify
  `todo.py`, run the relevant tests.

Participants and claims:

| participant                                | intent                                 | claim          | epoch | session                                        |
|--------------------------------------------|----------------------------------------|----------------|------:|------------------------------------------------|
| `agt_c3bad58a-fec1-33da-8dec-78d550d38891` | `d5d3af94-d014-33f6-9065-aa295befa1ef` | `todo.py`      |     1 | `session-9607b55f-4e4b-462f-89bb-bac1b2f8c13a` |
| `agt_d1b338fd-6429-3eb5-9e49-0e9748ee67ed` | `9336286f-6a0f-3b5d-9fe2-caae6d223c6e` | `test_todo.py` |     1 | `session-3f635041-f502-4dd1-9a2a-c126cef5b493` |

Both sessions reached `ready / isolated` and converged on WorkGroup
`c8834a58-fe9d-3a75-8b56-bbf7a86f7a6a`.

Requests and grants:

- request `806f28fb-ffb1-4b30-b183-e2113a718bf6` was accepted from the
  implementation participant to the test participant;
- request `7ac7154a-e620-41ec-b32c-102f6cd14cdb` was accepted from the test
  participant to the implementation participant;
- grant `76b0f7d0-9c6f-35f3-afac-43dc843500f7` targeted the implementation
  participant;
- grant `fadac0ca-cdec-3a26-9e3c-b7a6e2a545ca` targeted the test participant.

Progression:

1. B added `test_complete_ignores_unknown_title`; its own visible run was
   `2 passed, 2 failed` against the still-incomplete `todo.py`.
2. B followed the projected `finish_lane` action with exact arguments
   `{"summary":"Publish the completed immutable snapshot"}`. Snapshot
   `snap_d678f31fc5591c897c7a648c41d4322d`, commit
   `0cde4e0404e9d07158b8f0f76839cf01ed6b4831`, changed only `test_todo.py` and
   integrated into control commit `d19fac9`.
3. A received and executed the projected review admission, inspected the
   test-only snapshot, and submitted a structured ACCEPT for grant
   `76b0f7d0-9c6f-35f3-afac-43dc843500f7`, intent
   `9336286f-6a0f-3b5d-9fe2-caae6d223c6e`, claim epoch 1, and snapshot
   `snap_d678f31fc5591c897c7a648c41d4322d`.
4. B then received this exact executable projection:

   ```json
   {
     "kind":"work_group_join",
     "payload":{
       "intentId":"d5d3af94-d014-33f6-9065-aa295befa1ef",
       "proposal":"Review the immutable snapshot for this work group",
       "workGroupId":"c8834a58-fe9d-3a75-8b56-bbf7a86f7a6a"
     }
   }
   ```

   Instead of executing the projected `request_coordination`, B called
   `get_next_action` again and its Codex turn ended. This is the first genuine
   ordinary-run stop and is agent-compliance/session-engagement evidence, not
   an unchanged projected Synesis action failure.

Final ordinary state:

- WorkGroup `c8834a58-fe9d-3a75-8b56-bbf7a86f7a6a`: `ACTIVE`;
- test participant: `COMPLETED`; implementation participant: `ACTIVE`;
- one test-only snapshot integrated; no implementation snapshot exists;
- reciprocal grant `fadac0ca-cdec-3a26-9e3c-b7a6e2a545ca` remains targeted at
  the test participant;
- control checkout is clean at `d19fac9`, but `python -m pytest -q
  test_todo.py` reports `2 failed, 2 passed` because `todo.py` still contains
  `complete: pass`;
- Doctor: `DEGRADED`, six warnings, zero errors/critical findings,
  reconciliation recommended, no cleanup recommended.

The six Doctor warnings are the known separate state:

- two stale session leases;
- durable command namespace reconciliation required;
- command capacity/retention review required;
- two provider migration warnings.

Harness logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0529-002`.

## Classification and next action

The current blocker is ordinary agent compliance/turn engagement: an exact
`request_coordination` projection was available and usable, but the reviewer
selected another inbox read and then stopped. The continuity probe separately
reproduced the existing fail-closed dirty-worktree recovery boundary after
pytest-generated untracked artifacts; it does not authorize speculative
production changes.

## Verification after the acceptance runs

- `:coordination:test --tests
  org.synesis.coordination.collaboration.WorkIntentServiceTest` and
  `:workspace:test --tests
  org.synesis.workspace.ProviderSessionBindingServiceTest`: PASS.
- `:coordination:javadoc :mcp:javadoc`: PASS.
- `bootstrap\go vet ./...`: PASS.
- `scripts/agent-resume.ps1`, `scripts/agent-validate-deferred.ps1`,
  `scripts/agent-validate-fixtures.ps1`, and `scripts/agent-doctor.ps1`:
  PASS for repository/state validation; Doctor remains DEGRADED with the six
  warnings listed above.
- `git diff --check`: PASS.
- Focused `:mcp:test --tests
  org.synesis.mcp.application.McpSyn039SliceTest` did not complete. At the
  captured boundary, Gradle test worker PID 14504 had test thread 19516 in
  `TIMED_WAITING` at `ProcessCommandRunner.startProcess:144`, through
  `GitProcessRunner.runInternal:129` and
  `SemanticIndexFingerprint.flagPaths:122`; the process-launch watchdog was
  also active. This reproduces the known Git subprocess stall and was stopped
  after evidence capture. It remains separate infrastructure work.

No production code changed, nothing was pushed, and no SYN-040 was created.

Next action after CP-0529: run one fresh ordinary unattended two-agent Todo
acceptance with only complementary coding prompts and the current bundled
MCP. Do not change production coordination, cleanup, review, snapshot,
validation, or integration semantics unless an unchanged projected action
fails or required progress has no usable projection.
