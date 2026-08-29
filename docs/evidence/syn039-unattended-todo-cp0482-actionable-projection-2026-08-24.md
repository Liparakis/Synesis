# SYN-039 CP-0482 — Actionable lifecycle projection diagnostic

Date: 2026-08-24
Task: SYN-039 — Autonomous Workgroup Completion

## Result

This bounded diagnostic proves a protocol projection defect after both agents
complete their assigned visible work. Both agents obeyed the required rule:
they performed ordinary repository work while `get_next_action` reported
`IMPLEMENT` without a concrete lifecycle tool, and they did not invent
`finish_lane`, validation, or coordination calls.

After the work was complete, repeated `get_next_action` calls still projected
ordinary `IMPLEMENT` with no executable lifecycle action. Synesis exposed no
REVIEW admission request, snapshot-publication action, grant, validation
action, or other usable transition. Progress was therefore required but no
protocol action was available. This is the first concrete CP-0482 blocker.

Raw traces are retained at:

`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0481-001\logs\agent-a.jsonl`
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0481-001\logs\agent-b.jsonl`

## Preflight and shared state

Fixture: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0481-001`
Project ID: `bca62af3-e1c9-482d-b69b-f9ba2197d4b1`
Baseline commit: `7444637d5f68ba3d5799117c6535b2bd5d01f6e9`
WorkGroup: `ffd58516-2313-3ccc-a402-b20c921d2f8f`

Both sessions used the current bundled MCP through explicit per-agent
wrappers, reached `ensure_session=ready` and `workspace=isolated`, and used
the same project root. The claims were disjoint:

| Agent | Session                                        | Participant                                | Intent                                 | Claim          | Epoch |
|-------|------------------------------------------------|--------------------------------------------|----------------------------------------|----------------|------:|
| A     | `session-09e72674-2077-4784-a1a9-bc1c45523519` | `agt_788f02fc-20db-3593-a6ea-06f648bfeb8a` | `b74e8441-8a6f-3076-bb67-ee99b40d1af2` | `todo.py`      |     1 |
| B     | `session-88fafd35-4bb3-40fa-8e0d-9688f0bf6aa7` | `agt_952b937a-8630-3a5e-9439-b599bcf3b18f` | `5e9d75aa-914b-3a25-9b1f-62fc6c29128f` | `test_todo.py` |     1 |

The shared `get_next_action` state showed both participants and both intents
in the same active WorkGroup. No claims conflict was reported.

## Projection/action trace

### Initial implementation projection

Both agents received the same protocol shape:

```text
workflow.type=IMPLEMENT
workflow.recommendedTool=<absent>
workflow.arguments=<absent>
permittedOperations=[read_file, apply_patch, run_command]
blockers=[]
```

The agents then performed only visible work:

- Agent A changed only `todo.py`, adding `TodoList.complete(title)`, and its
  targeted behavior checks passed.
- Agent B changed only `test_todo.py`, adding two focused tests. Its isolated
  test run initially failed because Agent A's unintegrated implementation was
  not present in Agent B's worktree; compilation and diff checks passed.

### Post-work projection

After each agent completed its visible work and called `get_next_action`
again, the projection still had:

```text
workflow.type=IMPLEMENT
workflow.actionId(A)=4da139d8-3399-3824-b606-2f931e9b1c8d
workflow.actionId(B)=e5d7ad32-e61b-3fde-b3d1-c5155d650556
workflow.recommendedTool=<absent>
workflow.arguments=<absent>
reviewActions=[]
pendingCoordination=[]
grants=[]
snapshots=[]
blockers=[]
```

Actual agent action after that projection:

- Agent A performed final read/status/hygiene checks and called
  `get_next_action` again. It did not call a lifecycle tool.
- Agent B performed final compile/artifact cleanup checks and called
  `get_next_action` again. It did not call a lifecycle tool.

Therefore no exact projected lifecycle action failed; the failure is that
the protocol emitted no usable action after implementation completion.

## Lifecycle state not reached

No request ID, REVIEW grant, snapshot ID, validation decision, integration
result, or terminal WorkGroup state was created. The CLI coordination status
reported:

```text
COORDINATION_STATUS=PASS
PROJECT_SEQUENCE=0
TASKS=0
OWNERSHIPS=0
```

The final Doctor result was `DEGRADED` with six warnings:

- `stale_session_lease`
- `ambiguous_session_liveness`
- `command_namespace_reconciliation_required`
- `command_capacity_or_retention`
- two `provider_migration_required` findings

These warnings were not shown to cause the missing projection and remain
separate infrastructure/diagnostic work. The known Git subprocess stall and
bootstrap migration failures likewise remain separate.

## Classification and next action

This is a confirmed SYN-039 production projection defect, not an agent-choice
failure. The next narrow implementation slice is to trace the completion-state
decision in `AgentNextActionService` and its coordination projections, then
make the existing REVIEW admission or snapshot-publication action appear with
exact executable arguments once the active WorkGroup has completed visible
implementation. Preserve fail-closed ownership, epoch, grant, and snapshot
rules; do not add orchestration or bypass lifecycle authorization.
