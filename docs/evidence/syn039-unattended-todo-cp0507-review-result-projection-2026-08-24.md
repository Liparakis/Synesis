# SYN-039 CP-0507 — Invalid Review Result Projection

Date: 2026-08-24  
Task: SYN-039 — Autonomous Workgroup Completion  
Diagnostic: fresh exact-projection two-agent Todo run after CP-0506 publication guard  
Result: the false `finish_lane` blocker is fixed; the first later lifecycle blocker is invalid review-result projection

## Fixture and preflight

- Fresh disposable project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0507-001`
- Harness logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0507-001`
- Seed commit: `97af0f7`
- Managed baseline: `7225414fb03dea1994c49bb0e0318f105f9a1b6d`
- Project ID: `7497b6fc-efe5-4920-b57d-f4213fd81944`
- MCP executable for both agents:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256: `D27A9F4D3C833C3C5581DD012254E7AE767D96FC71F53DC2718461CBC6822CD1`
- MCP startup: version `0.1.0-SNAPSHOT`, protocol `2025-06-18`, commit `bc334ac`, exactly 10 tools
- Both independent GPT-5.6 Luna agents reached `ready / isolated` sessions against the same project and distinct
  worktrees.

## Durable coordination state

- WorkGroup: `9b605c00-d45c-34e6-a9dd-f0ad4d31be3b` — `ACTIVE`
- Agent A participant: `agt_6825bdfe-181c-33ee-abcf-889acdf994f3`; implementation intent
  `8aae7ec1-d731-3c8e-a91e-d951f586dabd`; claim `PATH_EXACT:todo.py`; epoch `1`
- Agent B participant: `agt_51e1500b-a4c8-3023-bf5e-d45a633687cd`; test intent `ebf836fa-b8f0-3ff5-8824-ca54cd40a13b`;
  claim `PATH_EXACT:test_todo.py`; epoch `1`
- REVIEW request from B: `9d1462ed-aef1-427e-b048-31499aa36a82`
- Reverse REVIEW request from A: `f0f2ccba-80c5-47ee-9ced-48eac0abf8ce`
- REVIEW grant consumed by B: `4c3eae33-35d4-3015-bdcf-bf84895f6aad`; single-use; epoch `1`

## Progress before the blocker

Agent A followed ordinary `IMPLEMENT`, changed only `todo.py`, ran `pytest` successfully, executed the exact projected
`finish_lane`, and integrated its snapshot:

- Snapshot: `snap_760b1bf37251e2c2f64e92e73ece42a9`
- Snapshot commit: `804fe64f18b3f261d6f25750aef9f64ab4333b33`
- Base commit: `7225414fb03dea1994c49bb0e0318f105f9a1b6d`
- Changed path: `todo.py`
- `finish_lane` result: `snapshotState=PUBLISHED`, `integrationState=integrated`
- Control checkout commit after integration: `c6af41d Synesis immutable lane snapshot`

This confirms the CP-0506 guard: no unexecutable `finish_lane` was projected for an empty lane, and the exact projected
publication action succeeded once source changes existed.

## First later failure

After B consumed the single-use grant, `get_next_action` projected:

```text
status=ready
reason=validation_required
nextAction=respond_coordination
nextProtocolKind=review_validation
grantId=4c3eae33-35d4-3015-bdcf-bf84895f6aad
intentId=8aae7ec1-d731-3c8e-a91e-d951f586dabd
claimEpoch=1
snapshotId=snap_760b1bf37251e2c2f64e92e73ece42a9
result=accepted|rejected
recommendedTool=respond_coordination
arguments={"kind":"review_validation","payload":{"grantId":"4c3eae33-35d4-3015-bdcf-bf84895f6aad","intentId":"8aae7ec1-d731-3c8e-a91e-d951f586dabd","claimEpoch":1,"snapshotId":"snap_760b1bf37251e2c2f64e92e73ece42a9","result":"accepted|rejected"}}
```

B executed those exact projected arguments. Synesis returned:

```text
status=blocked
reason=policy_denied
nextAction=request_human_help
result.error=COORDINATION_RESPONSE_INVALID_RESULT
```

The literal `accepted|rejected` is documentation-style alternatives, not a valid structured validation decision. No
ACCEPT/REJECT decision was recorded, no validation completed, and the WorkGroup remained `ACTIVE`. B did not ignore or
alter the projected action.

## Classification

This is a concrete agent-facing protocol projection defect. The review-validation projection must expose a valid,
executable decision contract while preserving the existing structured `accepted`/`rejected` validation model and
fail-closed rejection of invalid values. This slice does not change grant authorization, snapshot visibility,
integration, ownership, or cleanup behavior.

## Final diagnostics

- WorkGroup: `ACTIVE`
- Agent A: `COMPLETED` after snapshot publication and integration
- Agent B: `ACTIVE` with the review request unresolved
- Snapshot: visible and integrated; validation decision absent
- Doctor: `DEGRADED`, six warnings, zero critical/errors
- Warnings: two `stale_session_lease`, two `provider_migration_required`, `command_namespace_reconciliation_required`,
  and `command_capacity_or_retention`
- The warnings did not prevent MCP readiness or cause this validation projection failure; they remain separately
  classified.
- The recurring root Git subprocess startup stall remains separate infrastructure evidence.

## Next action

Trace the review-validation projection from `reviewActions` through `AgentWorkflowReducer` and the MCP response
contract. Make the smallest change that projects a valid structured decision choice without auto-selecting ACCEPT or
REJECT, then add deterministic coverage for valid ACCEPT, valid REJECT, and invalid/replayed/stale validation inputs.
