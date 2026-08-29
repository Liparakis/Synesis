# SYN-039 CP-0483 — Active reviewer projection diagnostic

Date: 2026-08-24
Task: SYN-039 — Autonomous Workgroup Completion

## Scope and fixture

This was a fresh bounded diagnostic acceptance after commit `9e6d971`
(`Expose active peer review admission`). It used two independent GPT-5.6 Luna
Codex agents, no message relay, no manual lifecycle transition, and the
current repository-bundled MCP:

- Fixture: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0483-001`
- Project ID: `da065c4f-a823-4792-9245-95a179766d9e`
- Effective baseline: `f5fba584e691383cf0197b36e8d98ee78ca8850c`
- MCP: `cli/build/platform-bundle/synesis-0.1.0-dev.local-windows-x64/bin/synesis-mcp.exe`
- MCP version: `0.1.0-SNAPSHOT`
- MCP SHA-256: `9064A9A96B0DD09595409E886214C5C2F35D4778B221ABB77DCE60D6161B576E`
- Raw traces: fixture `logs/agent-a.jsonl`, `logs/agent-b.jsonl`, and the
  corresponding stderr logs

Both sessions reached `ensure_session=ready` and `workspace=isolated` with
the same project and disjoint exact claims:

| Agent              | Session                                        | Participant                                | Intent                                 | Claim          | Epoch |
|--------------------|------------------------------------------------|--------------------------------------------|----------------------------------------|----------------|------:|
| A / implementation | `session-65bface0-dcd4-497a-bc4c-14fbc1708ff5` | `agt_5b4ddb4e-38dc-31df-84e1-b380d1c14864` | `fd1cdaba-9a70-310f-8dc1-83dc165a21c1` | `todo.py`      |     1 |
| B / reviewer       | `session-7a0ad8a2-7901-4faf-a40a-471c931e13d3` | `agt_5c94bb17-fb5c-3c85-acc4-59915643ed2d` | `0e184fab-d3d3-3b82-a24a-5febd5085e3c` | `test_todo.py` |     1 |

## Projection/action trace

The first `get_next_action` for each agent projected ordinary `IMPLEMENT`
with no `recommendedTool` and no typed lifecycle arguments. Both agents then
performed normal visible repository work in their own isolated worktrees.

Agent A implemented `TodoList.complete(title)` in `todo.py`, passed `py_compile`
and a focused behavior assertion, and repeatedly received:

```text
workflow.type=IMPLEMENT
workflow.recommendedTool=absent
workflow.arguments=absent
reviewActions=[]
pendingCoordination=[]
grants=[]
```

Agent A nevertheless selected unprojected `finish_lane`. It published
snapshot `snap_2ecbf452a75a69a8048168e6a1f177f2` with snapshot commit
`db741cdbb14af2e9aff337420d832cc873d5a503`, changed path `todo.py`, lane
`fd1cdaba-9a70-310f-8dc1-83dc165a21c1`, and claim epoch `1`. Integration
returned `integration_failed`; later unprojected retries returned the same
result. Because `finish_lane` was not projected, this is agent-compliance
evidence, not an exact projected-action protocol failure.

After the snapshot existed, Agent B repeatedly called `get_next_action`. The
projection still contained:

```text
workflow.type=IMPLEMENT
workflow.recommendedTool=absent
workflow.arguments=absent
reviewActions=[]
pendingCoordination=[]
grants=[]
snapshots=[snap_2ecbf452a75a69a8048168e6a1f177f2]
```

The active intent order exposed to B was B's `test_todo.py` intent first and
A's `todo.py` intent second. The current fallback selected the first active
intent in the WorkGroup as the review owner and therefore treated B as the
owner of its own review-discovery state. No `REVIEW_ADMISSION_REQUIRED`,
`request_coordination(work_group_join)`, grant, or snapshot-validation action
was projected to B. B later chose unprojected `finish_lane`, which correctly
returned `task_not_ready`; that result is also agent-compliance evidence, not
an exact projected-action failure.

## Durable final state

- WorkGroup: `af1807bc-ab46-3c98-8908-7073a807a7a6`, `ACTIVE`, version `1`
- Participants: both above, both `ACTIVE`
- Claims: disjoint `PATH_EXACT:todo.py` and `PATH_EXACT:test_todo.py`, epoch `1`
- Requests: none
- Grants: none
- Snapshot: `snap_2ecbf452a75a69a8048168e6a1f177f2`, published for A's lane
- Validation decision: none
- Integration: no accepted integration; A's unprojected completion reported
  `integration_failed`
- WorkGroup closure: not reached
- Control coordination CLI: `COORDINATION_STATUS=PASS`, sequence `0`,
  `TASKS=0`, `OWNERSHIPS=0`
- Control checkout: remained at the effective baseline; agent changes were
  isolated in Synesis worktrees

Doctor remained `DEGRADED` with six warnings and no errors or critical
findings: two `stale_session_lease` findings,
`command_namespace_reconciliation_required`, `command_capacity_or_retention`,
and two `provider_migration_required` findings. These remain separately
classified; the run did not show them causing the missing review projection.

## Classification and next action

The active-reviewer regression added in `9e6d971` passes when the existing
WorkGroup owner is observed first. This fresh run proves a remaining
order-dependent projection defect: when the reviewer intent is recorded first,
the later implementation snapshot does not cause the reviewer to receive a
usable admission action. The next narrow slice is to trace and correct
producer/reviewer selection using existing intent, claim-epoch, WorkGroup, and
snapshot provenance, with a reviewer-first deterministic fixture. Do not add a
role system, orchestrator, ownership bypass, or cleanup redesign.

No ordinary second acceptance was run because the diagnostic stopped at the
first state where Synesis required progress but projected no usable action.
