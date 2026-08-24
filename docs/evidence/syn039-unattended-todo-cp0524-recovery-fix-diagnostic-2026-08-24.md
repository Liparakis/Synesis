# SYN-039 CP-0524 clean-recovery identity fix diagnostic

Date: 2026-08-24

## Purpose

This was a fresh bounded two-agent diagnostic after the clean-recovery binding
fix. Both agents used the rebuilt bundled MCP and the exact-action rule. No
human relay, manual lifecycle transition, ownership repair, or control
checkout mutation was performed.

## Harness and MCP

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0524-001`
- Harness/logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0524-001`
- Project ID: `f87b1444-16a7-41f4-97ec-6f4a737c1914`
- Seed/control HEAD: `ac465a0cac0c294223aa652013dfc19f0fe39b1e`
- Final control checkout: clean at the seed HEAD; no snapshot or integration commit
- MCP executable: `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256: `50CE82BA0207C846C19237BAFAA3B7ED364EA39D0DE86F8EB39600B5FAE78D7A`
- MCP startup version/protocol identity: `0.1.0-SNAPSHOT`, startup commit `bc334ac`
- Agent A connection: `conn-instance-8152a65f-a8db-4b0d-a5d5-552db067319c`
- Agent B connection: `conn-instance-fe1a6d3c-f516-40f7-9ebf-eefbb57fc3ca`

## Durable coordination state

- WorkGroup: `e8a79ccc-6731-3881-aa99-922517a45497`, `ACTIVE`, version 1
- Agent A: `agt_1fcf92e4-36f8-3834-bedb-7778fb6d1e1f`, intent `7926a4ac-5844-379d-bb37-20b51d01f8bc`, epoch 1, `PATH_EXACT todo.py`
- Agent B: `agt_20fb2541-1874-3258-b67b-e88c11cfc200`, intent `95c7d25e-fc7a-3a41-bdf7-b5f4318557a7`, epoch 1, `PATH_EXACT test_todo.py`
- REVIEW request: `8b83d2f9-8008-4228-bf70-b8eec13bd29e`, A → B, `ACCEPTED`
- REVIEW grant: `0af27869-aa5a-3ea2-afd5-a7eb1b17ed30`, target A, target intent B, epoch 1, single-use, available
- Snapshots: none
- Validation decisions: none
- Integration: none
- WorkGroup closure: none

## Exact projection/action trace

1. Both sessions established `ready / isolated` with disjoint claims.
2. Agent A received `get_next_action` with a concrete `request_coordination`
   projection for `work_group_join` targeting B's intent. A executed the exact
   projected arguments; the REVIEW request was created successfully.
3. Agent B independently performed its visible `test_todo.py` work and ran
   `pytest test_todo.py`: exit code 0, `2 passed in 0.01s`, empty stderr.
4. Agent B received the exact owner projection:
   `respond_coordination({kind: coordination_response, payload:
   {coordinationRequest: 8b83d2f9-8008-4228-bf70-b8eec13bd29e,
   coordinationStatus: ACCEPTED, proposal: admitted}})`. B executed it
   exactly; the response returned `ACCEPTED`.
5. B then polled `get_next_action` and received the exact single-use grant
   wait projection: `get_next_action({})`, with grant
   `0af27869-aa5a-3ea2-afd5-a7eb1b17ed30` targeted to A and
   `snapshotRequired: true`. No projected action failed.
6. Both agents stopped before the next poll/consumption and before A's visible
   implementation, snapshot publication, validation, integration, or closure.

The first blocker in this diagnostic is agent engagement after a valid WAIT
projection, not a Synesis protocol failure. The run did not reach the repaired
clean-recovery path in the live acceptance, so no additional production change
is justified from this run.

## Production fix verified separately

The CP-0523 recovery trace showed a clean worker already at the advanced control
HEAD being rebound to a new session, stranding its active intent and grant on
the old participant. Commit `dd9f0eb` extends the existing safe session-preserving
rebind predicate to that exact no-unintegrated-worker-work state. The new
deterministic `ProviderSessionBindingServiceTest` regression proves the session
ID is preserved, the isolated worktree is refreshed, and workspace trust still
verifies. Uncommitted and divergent worker state remains outside this change
and continues to fail closed under the existing checks.

## Doctor

Final direct Doctor result: `DEGRADED`, 6 warnings, 0 errors, 0 critical. The
warnings were two `ambiguous_session_liveness`,
`command_namespace_reconciliation_required`, `command_capacity_or_retention`,
and two `provider_migration_required`. No warning was shown to cause the
ready/isolated or coordination state in this diagnostic.

## Verification

- `:mcp:test --tests org.synesis.mcp.application.McpSyn039SliceTest`: PASS (11 tests)
- `:workspace:test --tests org.synesis.workspace.ProviderSessionBindingServiceTest`: PASS (12 tests)
- `:coordination:test --tests org.synesis.coordination.collaboration.WorkIntentServiceTest`: PASS/from cache
- coordination/workspace/MCP Javadocs: PASS
- deferred and fixture validators: PASS
- bootstrap `go vet ./...`: PASS
- `git diff --check`: PASS
- `:cli:platformBundle --rerun-tasks --no-daemon`: PASS after the diagnostic MCP children exited; the first attempt was blocked by those still-open external MCP JAR handles

Raw action logs remain under the harness directory above.
