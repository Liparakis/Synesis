# SYN-039 CP-0532 ordinary post-fix acceptance

Date: 2026-08-24
Status: PARTIAL; first post-fix stop is agent engagement/compliance, not a new production failure

## Fresh harness

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0532-001`
- Harness/logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0532-001`
- Seed commit: `4c81d3f`
- Project ID: `a15ba0fb-1a43-4516-a063-c0ad815db8d7`
- Current bundled MCP executable: `cli/build/platform-bundle/synesis-0.1.0-dev.local-windows-x64/bin/synesis-mcp.exe`
- MCP SHA-256: `3076EF03BCE4EE3088165296720B64241127549327E47589ACD12EDE57CCEA9C`
- MCP version: `0.1.0-SNAPSHOT`; exactly 10 tools; explicit project pin and distinct connection IDs `syn039-cp0532-agent-a` / `syn039-cp0532-agent-b`.
- Both agents were independent GPT-5.6 Luna High Codex sessions with only complementary visible coding prompts. No relay, manual transition, grant consumption, snapshot publication, conflict repair, or integration intervention occurred.

## Participants and ownership

- Agent A: participant `agt_16d8d907-3cda-3c99-b59f-190466196787`, intent `3bb03515-4cde-3db9-9f56-c43063820a98`, claim `PATH_EXACT todo.py`, epoch 1, worktree `session-a642fff8-9e8e-45d3-abc7-942e53a344e9`.
- Agent B: participant `agt_bfa21b05-002a-30de-935b-255dd64d732a`, intent `cb9ef56e-d892-3c58-9388-6675018e1a66`, claim `PATH_EXACT test_todo.py`, epoch 1, worktree `session-3a3b7b6f-5110-4624-9f1e-045bd184ed21` and later recovery worktree `session-3a3b7b6f-5110-4624-9f1e-045bd184ed21-recovery-2ed8eb81-3b11-463b-9aaa-4a8cca15ca37`.
- WorkGroup: `f81adf5d-1845-31b6-9eda-199dbcf8cb6f`, status `ACTIVE`.
- REVIEW requests: `c34853b9-ca1a-48d9-8acb-b2f37e5eb1b9` (B → A) and `34426d8b-0e8b-4dba-b57f-4fe10eacb9d8` (A → B), both `ACCEPTED`.
- Grants: `555bdaca-076d-31f7-a01e-f148eb668b39` targeted B and was consumed; `a1fa1459-1e77-358c-9969-df4fc8809469` targeted A to review B and remained unconsumed when A ended.

## Progress and exact first stop

1. A implemented `todo.py` and ran `python -m pytest test_todo.py` — `2 passed`; the lane contained only `todo.py` plus generated cache files.
2. B discovered the existing WorkGroup and executed the exact projected REVIEW admission request. B then consumed grant `555bdaca-076d-31f7-a01e-f148eb668b39` with the projected WorkGroup, intent, epoch, and target participant.
3. A received `snapshot_publication_required` with:

   ```text
   finish_lane({"summary":"Publish the completed immutable snapshot"})
   ```

   A executed that exact call. Synesis returned `snapshotState=PUBLISHED` and `integrationState=integrated`, with snapshot `snap_fe293a7bd698ba24def510ab25c6a6a0`, snapshot commit `b4d33bba6d6cdcaac0e85870244d31992da451f3`, and control checkout commit `20ab964`.
4. The CP-0531 generated-bytecode `integration_conflict` did not recur. The integrated snapshot changed only `todo.py`.
5. A then requested reciprocal review and received the unchanged executable `request_coordination` projection again. A's Codex session ended instead of continuing to the targeted grant. B correctly followed the projected `WAIT -> get_next_action({})` continuation while waiting for A to consume grant `a1fa1459-1e77-358c-9969-df4fc8809469`.
6. B's first malformed review decision changed the projected intent ID and failed closed with `policy_denied / UUID string too large`; B then submitted the exact projected identifiers and received structured `ACCEPTED`. This is agent-compliance evidence, not a backend failure.
7. B never published `test_todo.py`; no second snapshot, final integration, or WorkGroup closure was reached.

## Final observed state

- Control checkout: `20ab964` (`Synesis immutable lane snapshot`), containing the accepted `todo.py` implementation; B's test change is absent.
- Coordination: `PROJECT_SEQUENCE=0`, `TASKS=0`, `OWNERSHIPS=0`; WorkGroup remains `ACTIVE`.
- Doctor: `DEGRADED`, six warnings, zero errors/critical findings, reconciliation recommended. The warnings are the known stale-session-lease, command-namespace/capacity, and provider-migration findings.
- The root MCP verification reproduced the known bounded Git subprocess launch stall in `McpSyn039SliceTest` and ultimately completed green. Bootstrap Go tests still have the three known migration failures. Neither issue was shown causal to this acceptance stop.

## Classification and continuation

The first post-fix integration action succeeded, so the snapshot artifact defect is closed by `b249790`. The remaining CP-0532 stop is agent engagement/compliance: an agent ended after a repeated concrete projection, while the other remained in a valid projected wait state. No production lifecycle change is justified from this run alone.

Next diagnostic: keep both independent agents alive through reciprocal REVIEW request and WAIT projections and capture whether the active reviewer later receives a usable implementation/publication action after the sibling snapshot is accepted.

