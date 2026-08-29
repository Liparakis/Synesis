# SYN-039 CP-0506 — Premature Finish Projection

Date: 2026-08-24  
Task: SYN-039 — Autonomous Workgroup Completion  
Diagnostic: bounded exact-projection two-agent Todo run  
Result: FAILED at the first unexecutable projected lifecycle action

## Fixture and preflight

- Fresh disposable project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0506-001`
- Harness logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0506-001`
- Seed commit: `e82e942`
- Managed baseline: `245d9924d8e90b310f6a517fe9f098c625c0e060`
- Project ID: `8788e95b-f158-4f38-86af-e23fd64f7ce9`
- MCP executable for both agents:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256: `D27A9F4D3C833C3C5581DD012254E7AE767D96FC71F53DC2718461CBC6822CD1`
- MCP startup: version `0.1.0-SNAPSHOT`, protocol `2025-06-18`, commit `bc334ac`, exactly 10 tools
- Agent A MCP connection: `conn-instance-f4bee313-c118-4a7d-962d-b0d47dfd3d6c`
- Agent B MCP connection: `conn-instance-9e75fa35-cab7-46ff-9722-8af13cf979bc`
- Both agents reached the current bundled MCP, the same project, distinct isolated worktrees, and `ready / isolated`
  sessions.

## Durable coordination state

- WorkGroup: `cc2e1711-a898-3a56-84b1-458afaa45bdc` — `ACTIVE`
- Agent A participant: `agt_87130706-4cfe-3ee9-8466-2530ca6d9023`; claim `PATH_EXACT:todo.py`; epoch `1`; implementation
  intent `31e6db39-4d4c-3902-bffd-e58b62c3c1e9`
- Agent B participant: `agt_91993120-8c58-3c9e-a441-3201146926a4`; claim `PATH_EXACT:test_todo.py`; epoch `1`; test
  intent `4520f5f6-64b7-32a8-ba16-f524f0331a31`
- First REVIEW request: `6039b52e-f583-4d5c-a495-b7df3220cde8`
- First REVIEW grant: `c8e4ad1e-7169-3541-95a4-2741e7b299d0`; single-use; epoch `1`; consumed by Agent A; validation
  accepted
- Reverse REVIEW request: `f86b3b97-5189-458f-8477-fa96a52c55a8`
- Reverse REVIEW grant: `0b0e4809-6964-34e2-9702-ab39586bb3e7`; single-use; epoch `1`; consumed by Agent B

Agent B published and integrated its test snapshot:

- Snapshot: `snap_a1a3492bca65ba892bafa08cc714a9c8`
- Commit: `082aad8f2a0ac786ec95f9a66e85fd8b91397916`
- Base: `245d9924d8e90b310f6a517fe9f098c625c0e060`
- Changed path: `test_todo.py`
- Integration result: success

## First concrete failure

Agent A's exact sequence was:

`get_next_action` projection

```text
status=ready
reason=snapshot_publication_required
nextAction=finish_lane
workGroupId=cc2e1711-a898-3a56-84b1-458afaa45bdc
intentId=31e6db39-4d4c-3902-bffd-e58b62c3c1e9
claimEpoch=1
participant=agt_87130706-4cfe-3ee9-8466-2530ca6d9023
recommendedTool=finish_lane
arguments={"summary":"Publish the completed immutable snapshot"}
actionId=720d5daf-91a2-33fe-ac7e-7571e6191467
```

Immediately following action:

```text
finish_lane({"summary":"Publish the completed immutable snapshot"})
=> status=blocked, reason=task_not_ready, nextAction=retry
```

The same exact projected action was replayed once and returned the same result. Agent A's assigned worktree remained at
the managed baseline; `todo.py` still contained `raise NotImplementedError`, and no Agent A snapshot, validation, or
integration record was created. The agent did not ignore a concrete projection.

## Classification

This is a production projection defect, not a grant or ownership failure.
`AgentNextActionService.snapshotPublicationAction` treated a consumed peer REVIEW grant as sufficient to project
`finish_lane`, while `AgentTaskCompletionService` correctly rejects an empty source snapshot (`NO_CHANGES_TO_PUBLISH` /
`task_not_ready`). The projection was therefore not executable for the authenticated lane state.

The required narrow correction is to apply the snapshot service's read-only changed-path and artifact-policy
precondition before projecting `finish_lane`. Review admission, grants, claims, epochs, ownership, and fail-closed
completion behavior are out of scope.

## Final diagnostics

- WorkGroup terminal state: still `ACTIVE`
- Agent A: `ACTIVE`, no snapshot, no integration
- Agent B: published its test snapshot and reached the completed side of the run
- Doctor: `DEGRADED`, six warnings, zero critical/errors
- Doctor warnings: two `stale_session_lease`, two `provider_migration_required`,
  `command_namespace_reconciliation_required`, and `command_capacity_or_retention`
- Those warnings did not prevent ready/isolated MCP sessions or cause the first lifecycle failure; they remain
  separately classified.
- The recurring root Git subprocess startup stall remains separate infrastructure evidence.

## Next action

Run focused regression and bundle verification for the guarded projection, rebuild the bundled MCP, then rerun a fresh
exact-projection two-agent diagnostic. Stop at the first later lifecycle failure and preserve it as the next SYN-039
blocker.
