# SYN-039 ordinary unattended Todo acceptance — CP-0511

## Classification

This was the completely ordinary acceptance following the CP-0510
diagnostic. The two agents received only complementary visible coding
responsibilities and the current bundled MCP; no lifecycle coaching, relay,
manual request acceptance, snapshot publication, validation, or integration
was performed by the harness.

The run reached a real shared WorkGroup and a successful exact REVIEW
admission, but it stopped before a snapshot. This is not a new integration
or coordination-state defect:

- Agent A completed `todo.py`, passed `3 passed`, then called the optional
  `get_next_action({integrationCheck:{...}})` overload instead of returning to
  the durable empty-argument inbox. The server returned
  `status=completed`, `accepted=true`, `actions=[]`, and A stopped.
- Agent B correctly added a test to `test_todo.py`, but its isolated worktree
  did not contain A's unintegrated implementation, so its local run was
  `2 passed, 2 failed`. This was not validation of an accepted snapshot and
  did not reproduce the historical false `TESTS_FAILED` integration result.
- B did execute the exact owner `respond_coordination` projection for request
  `cf09bea9-b782-49bf-a9c2-37073350cbee`; Synesis issued REVIEW grant
  `7b0b27d1-9596-3387-9c8a-18afe0cedd79`, after which B stopped at the exact
  `WAIT -> get_next_action` continuation.

The `integrationCheck` overload is an agent-facing contract ambiguity to
inspect separately. No production lifecycle behavior was changed for this
run.

## Harness and preflight

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0511-001`
- Harness/logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0511-001`
- Project ID: `154cf0eb-d78c-42be-a168-c6a96b12a755`
- Control baseline: `fa6234635d2c1b9a06c651bcdcdbd22142d04b39`
- MCP executable: repository bundle
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256: `74D36D7A3D4EBFBF5539FAD2E42B9F3976446C10350F5BF6B784B5300C4C8386`
- MCP startup evidence: protocol `2025-06-18`, version `0.1.0-SNAPSHOT`,
  commit `bc334ac`, exactly ten tools.

| Agent | Session/worktree | Participant | Intent / epoch / claim |
|---|---|---|---|
| A | `session-5cd788f3-0c57-4f25-be3a-0d26362c4618` | `agt_6191d861-3afc-3c6f-848b-7288c83a9118` | `974ac9d8-ca24-36ab-bba7-f50340c3eba9`, epoch 1, `todo.py` |
| B | `session-ddb4af11-2855-4f2e-9e6b-6b6a658b48b3` | `agt_fd202887-0b58-3cf4-9eb3-9c44de2f8a68` | `e95aae8f-6412-3770-897f-9500c96c79b4`, epoch 1, `test_todo.py` |

Both sessions independently reached `ready / isolated` using the same
initialized project and distinct worktrees.

## Durable coordination trace

- WorkGroup: `b57bf0fd-c262-3335-b45d-84e57eaf02ae`
- A's exact first projection: `request_coordination`,
  `kind=work_group_join`, target intent
  `e95aae8f-6412-3770-897f-9500c96c79b4`.
- Exact request result: pending request
  `cf09bea9-b782-49bf-a9c2-37073350cbee` from A to B.
- Exact owner response by B: `respond_coordination` with the projected
  `coordinationRequest`, `coordinationStatus=ACCEPTED`, and
  `proposal=admitted`; result `ACCEPTED`.
- Grant: `7b0b27d1-9596-3387-9c8a-18afe0cedd79`, single-use, epoch 1,
  target participant A, target intent B's `e95aae8f...`.
- No snapshot, review decision, integration, or closure was reached.

The final fixture Doctor run with the current bundle was `DEGRADED`, six
warnings, zero errors and zero critical findings: two stale session leases,
command namespace reconciliation, command capacity/retention, and two
provider migration warnings. The control checkout remained at the managed
baseline and clean. These warnings were not shown to cause the stop.

