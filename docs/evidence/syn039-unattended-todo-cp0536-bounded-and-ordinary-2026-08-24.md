# SYN-039 CP-0536 bounded and ordinary acceptance

Date: 2026-08-24
Status: PARTIAL; the bounded exact-action diagnostic completed the existing
review/snapshot/validation/integration path and closed its WorkGroup. The
second ordinary acceptance stopped at agent compliance before the reciprocal
grant was consumed. No additional production defect was proven by the
ordinary run.

## Confirmed production defect and narrow fix

The pre-fix CP-0535 diagnostic created one WorkGroup, integrated the first
lane, and then allowed a late disjoint intent to be announced into that
already terminal WorkGroup `112f0406-946f-3385-94d7-d4ec768f9c5b`. The late
participant remained active, but `get_next_action` could only return ordinary
`IMPLEMENT`; no valid publication or completion action existed for the
terminal group. This was the first concrete post-acceptance defect in that
run.

The fix is limited to two existing-model guards:

- `coordination/src/main/java/org/synesis/coordination/application/WorkIntentService.java`
  now rejects an explicit intent whose existing WorkGroup is not `ACTIVE`,
  with `WORK_GROUP_NOT_ACTIVE`, before appending the intent event.
- `workspace/src/main/java/org/synesis/workspace/application/collaboration/WorkspaceCollaborationService.java`
  continues to converge on an existing active WorkGroup, but allocates a new
  group when the canonical default group is terminal and no active group is
  available. Explicit requests for a terminal group still fail closed.

Deterministic coverage is in
`coordination/src/test/java/org/synesis/coordination/collaboration/WorkIntentServiceTest.java`
(`lateIntentCannotBeAnnouncedIntoCompletedWorkGroup`) and
`workspace/src/test/java/org/synesis/workspace/MultiChatLogicalWorkspaceTest.java`
(`newDefaultLaneUsesFreshWorkGroupAfterDefaultGroupCompletes`). Both tests
were red before the corresponding fix and green afterward.

## Bounded exact-action diagnostic

Fresh project and harness:

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0536-001`
- Project ID: `1c0fb0d1-1b4f-45c4-bf31-1c3d7a85505c`
- Harness: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-diagnostic-cp0536-001`
- MCP: repository-built current bundle, protocol `2025-06-18`, version
  `0.1.0-SNAPSHOT`, commit `bc334ac`, exactly ten tools
- Both agents used explicit project and per-agent connection pins and
  reached distinct `ready / isolated` sessions before visible work.

Participants and claims:

| participant                                | intent                                 | session                                        | claim / epoch                                           |
|--------------------------------------------|----------------------------------------|------------------------------------------------|---------------------------------------------------------|
| `agt_d3a9bdbe-24d9-3c7c-8941-9357de4e2436` | `44303e56-da3c-3c5c-ab6e-c535848e737e` | `session-a95656c7-8646-452d-8315-f5c0abc25fb1` | `todo.py`, epoch 1                                      |
| `agt_42e52e67-c217-3c19-8c63-47f04df45f29` | `41907e80-441f-3192-b461-9d8ac7c0afd5` | `session-8aa0c3b9-f646-49ec-8682-a9f50b9c6eaf` | `test_todo.py`, epoch 1                                 |
| `agt_972ebe4e-6adf-36e3-ae24-4741487fe0c1` | `403f62a4-3481-3038-b0cd-92994726dfb`  | `session-5cac0f45-75f3-494e-a13a-91f25a58bfe0` | `test_todo.py`, epoch 1; later verification participant |

All three intents converged on WorkGroup
`62241cb0-1e0d-3030-a945-e7f2dc5c37fb`.

The relevant projection/action/result trace was:

1. `REVIEW_ADMISSION_REQUIRED` projected
   `request_coordination({"kind":"work_group_join","payload":{"intentId":<target intent>,"workGroupId":"62241cb0-1e0d-3030-a945-e7f2dc5c37fb","proposal":"Review the immutable snapshot for this work group"}})`.
   The request was executed with the projected intent and group and was
   accepted by the owner.
2. The owner executed the projected
   `respond_coordination` acceptance. REVIEW grants were issued without
   overlapping write ownership:
   `684ccac9-5a39-3190-ba36-5baa1edefeb3`,
   `0877c982-0842-3bb6-80b0-0b092bcb1296`, and
   `1281f9bf-e9a0-348c-bb56-4aa39b502c95`.
3. The targeted reviewers consumed the single-use grants with the exact
   participant, intent, WorkGroup, and epoch context. No replay or wrong
   participant was accepted.
4. Each producer received
   `snapshot_publication_required` and executed the exact projected
   `finish_lane({"summary":"Publish the completed immutable snapshot"})`.
   The immutable snapshots were visible through the existing authorized
   review path.
5. Snapshot `snap_e81ef54442a8dba8b2d5bcb783bd5d55` (commit
   `e0dec8b47e17853e8a062e0772a1ab8ad7dc65fd`, `todo.py`) passed its own
   pytest 4/4 and integrated.
6. Snapshot `snap_ff087342059643da45a8d0d09d86b565` (`test_todo.py`) produced
   `3 failed, 2 passed` against the intentionally incomplete implementation.
   The reviewer inspected the immutable snapshot and submitted a structured
   REJECT with the exact grant, snapshot, intent, and epoch. The rejection
   routed actionable work back to the test implementer.
7. The returned work produced snapshot
   `snap_fa0ac02810cfbdf0aa98bf54a6c3588e`, which the reviewer inspected and
   ACCEPTed after the integrated control tests passed 5/5.

Final bounded state:

- WorkGroup: `62241cb0-1e0d-3030-a945-e7f2dc5c37fb`, `COMPLETED`, version 2
- Control commits included `492352f` and `1cac098`; control was clean
- Control `python -m pytest -q test_todo.py`: 5/5
- Coordination status: `PASS`, zero tasks and zero ownerships
- Doctor: `DEGRADED`, six warnings, zero errors/critical findings; warnings
  remain separately classified (session leases, command namespace/capacity,
  and provider migration)
- No exact projected lifecycle action failed in this diagnostic.

## Ordinary unattended acceptance

The ordinary run used fresh project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0536-001`
(project ID `1326e55c-be3c-48d1-bf37-597bdf9eaa9a`), the same current bundled
MCP, and only complementary visible coding prompts. Both agents reached the
same project, ten tools, distinct `ready / isolated` sessions, disjoint
claims, and WorkGroup
`1c9fd0e2-eda4-3505-a20e-db86de14ec8a`.

The run reached the first lane's exact projected `finish_lane`; snapshot
`snap_06e2a0ee5d4647a73f815205e2519457` (commit
`12f8205a94503715f5b2b10b3d38a5c92bb6b328`, `test_todo.py`) published and
integrated. Its own test result was `2 passed, 3 failed`, which was expected
because `todo.py` was still a no-op in that lane.

The first agent-compliance deviation was visible in the JSONL trace: the
projection supplied WorkGroup
`1c9fd0e2-eda4-3505-a20e-db86de14ec8a`, while the first attempted
`request_coordination` changed it to `1c9fd4e2-eda4-3505-a20e-db86de14ec8a`.
The later retry used the exact projected arguments and succeeded. The more
important terminal deviation was Agent B's item 26:

```text
get_next_action -> request_coordination
{
  "kind": "work_group_join",
  "payload": {
    "intentId": "ea7efeda-8893-32ea-ae3a-4a600d4f992d",
    "workGroupId": "1c9fd0e2-eda4-3505-a20e-db86de14ec8a",
    "proposal": "Review the immutable snapshot for this work group"
  }
}
```

Agent B did not execute that projected action and called `get_next_action`
again (item 28), then its Codex turn ended. The reciprocal REVIEW grant
`4ba34d35-976a-3d55-bc40-0d7c9656f46b` remained unresolved, the implementation
lane did not publish, and the WorkGroup remained `ACTIVE`. This is agent
choice/turn-engagement evidence, not an unchanged projected action failure or
a reason to alter production lifecycle semantics.

## Separate verification issues

- The known root `check` Git subprocess startup stall remains isolated at
  `McpServerTest`/`WorkspaceCliTest.setUp` through the Git process runner; no
  timeout was enlarged.
- Bootstrap `go test ./...` retains the three known migration failures.
- Doctor remains `DEGRADED` with the warnings listed above. No evidence from
  either CP-0536 run makes those warnings causal to the WorkGroup transition.
- No remote push was performed and no SYN-040 was created.

## Evidence sources

- Bounded JSONL: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-diagnostic-cp0536-001\logs`
- Ordinary JSONL: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0536-001\logs`
- Focused regression tests listed above
- Final control `collaboration status`, Doctor output, and control pytest

## Verification after the run

- `:coordination:test --tests org.synesis.coordination.collaboration.WorkIntentServiceTest`: PASS
-
`:workspace:test --tests org.synesis.workspace.MultiChatLogicalWorkspaceTest --tests org.synesis.workspace.AgentNextActionServiceTest`:
PASS
- `:mcp:test --tests org.synesis.mcp.application.McpSyn039SliceTest`: PASS, 15 tests; the known Git child-process wait
  was observed during fixture setup but the suite completed
- `:coordination:javadoc :workspace:javadoc :mcp:javadoc`: PASS
- `:cli:platformBundle --rerun-tasks`: PASS; current bundle rebuilt
- `scripts/agent-validate-fixtures.ps1`: PASS
- `scripts/agent-resume.ps1`: PASS; exactly one active SYN-039 task
- `scripts/agent-doctor.ps1`: structural checks PASS; one warning for documented external absolute paths
- `go vet ./...` in `bootstrap`: PASS
- `go test ./...` in `bootstrap`: FAIL with the three known migration failures in `main_test.go` lines 132, 201, and
  288 (`update migrations not prepared`)
- `git diff --check`: PASS for the tracked working-tree diff
- Full `.\gradlew.bat check --no-daemon`: FAIL at the pre-existing `:link:formatCheck` trailing-whitespace list. After
  removing this evidence file's Markdown hard-break spaces, `:link:formatCheck` still fails only on the previously
  recorded checkpoint/evidence files; CP-0536 is no longer in the list. The run also reproduced the known Git subprocess
  wait in `McpServerTest.setUp` through `ManagedBaselineTransactionService.synchronizeRealIndex` and `GitProcessRunner`.
