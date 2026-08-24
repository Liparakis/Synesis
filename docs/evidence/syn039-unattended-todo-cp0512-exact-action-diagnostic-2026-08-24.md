# SYN-039 exact-action diagnostic — CP-0512

## Classification

Fresh diagnostic after CP-0510, using the current bundled MCP and the explicit
rule that every concrete projected action must be executed with unchanged
arguments before another lifecycle action. No harness operator relay,
manual transition, claim repair, snapshot publication, or validation was
performed.

The protocol executed successfully through both reciprocal publication paths
and one structured ACCEPT. The first remaining failure was agent continuation:
Agent A stopped after repeated `WAIT -> get_next_action` projections for the
second review before polling again after Agent B published its snapshot. No
exact projected lifecycle call failed and no production change is justified
by CP-0512.

## Harness and preflight

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0512-001`
- Harness/logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0512-001`
- Seed commit: `32bd6ba2fd20ce7e1e512183b564a0d0c034926b`
- Project ID: `0c4a2434-d423-403d-9ba1-e344ffd94e33`
- Managed baseline: `f754a9941bcdff2163d3272344d7dae7f2acd619`
- MCP executable: repository bundle
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256: `74D36D7A3D4EBFBF5539FAD2E42B9F3976446C10350F5BF6B784B5300C4C8386`
- MCP startup evidence: protocol `2025-06-18`, version `0.1.0-SNAPSHOT`,
  commit `bc334ac`, exactly ten tools.

| Agent | Session/worktree | Participant | Intent / epoch / claim |
|---|---|---|---|
| A | `session-e18b0e9c-2d89-410d-b9a8-b2afb332635c` | `agt_83c01454-48aa-3291-92f1-7651c8f81790` | `a335b9dc-4289-3dcc-bbc0-def759571ae2`, epoch 1, `todo.py` |
| B | `session-35f90f9c-62b0-493e-af62-396b5fe98378` | `agt_22ef75bd-f109-3a05-9d83-3759fcd3f528` | `fb3a7edb-f4a0-3d2f-861f-890e1a33f532`, epoch 1, `test_todo.py` |

Both independently reached `ensure_session=ready`, `workspace=isolated`.

## Exact projection/action trace

### First review: B reviews A's `todo.py`

1. B received `request_coordination` with exact arguments:

   ```json
   {"kind":"work_group_join","payload":{"intentId":"a335b9dc-4289-3dcc-bbc0-def759571ae2","proposal":"Review the immutable snapshot for this work group","workGroupId":"1bc03f52-15e9-332e-ab08-1d4ffb8c88ab"}}
   ```

   B executed those arguments unchanged; request
   `44a2df31-c55f-4a54-a00c-b4185a1728ad` was created.
2. A received the exact owner `respond_coordination` projection for that
   request and executed it unchanged; result `ACCEPTED`.
3. B received the single-use REVIEW grant
   `bd9902a7-a00a-3d76-9d4c-c459bd749a41`, epoch 1, target B, target intent
   A's `a335b9dc...`. B consumed it with the exact projected coordination
   payload.
4. After the expected `workspace_stale -> ensure_session` recovery, B
   received `nextAction=review_decision` with exact payload:

   ```json
   {"grantId":"bd9902a7-a00a-3d76-9d4c-c459bd749a41","intentId":"a335b9dc-4289-3dcc-bbc0-def759571ae2","claimEpoch":1,"snapshotId":"snap_9fb0ad6fe51badb1a872e5586514376a"}
   ```

   B executed `respond_coordination(kind=review_validation, result=accepted)`
   with that exact context; Synesis returned structured `ACCEPTED`.
5. A's `finish_lane` projection then executed exactly and published snapshot
   `snap_9fb0ad6fe51badb1a872e5586514376a` for `todo.py`; response reported
   `snapshotState=PUBLISHED`, `integrationState=integrated`, snapshot commit
   `251d7788934a92e6e09c6d7461a4f6f8d51a33d4`.

### Reciprocal review: A reviews B's `test_todo.py`

1. A received and executed the exact `request_coordination` admission for
   B's `fb3a7edb...` intent. B received and executed the exact owner response
   for request `42367d8e-04ea-4b10-b329-fe490ccd3bdf`.
2. A consumed the exact single-use REVIEW grant
   `1456006f-0572-3867-bfa6-e40edae3d21f`, epoch 1, target A, target intent
   B's `fb3a7edb...`.
3. A then received repeated `WAIT` projections with
   `nextProtocolKind=review_validation`, `recommendedTool=get_next_action`,
   and payload containing that grant and `snapshotRequired=true`. A did not
   continue polling after the peer's later publication.
4. B subsequently executed its exact projected `finish_lane` with
   `{"summary":"Publish the completed immutable snapshot"}`. The response
   reported snapshot `snap_8f1f118e368dfd5211850a636214461e`,
   `snapshotState=PUBLISHED`, and `integrationState=integrated`; control
   history contained the corresponding second immutable snapshot commit
   `3fc805c21c49e38d14bf78bca7f72d7994c88230` and integrated control commit
   `981c1eb456bb59b13e29c209795701f75deec509`.
5. No second `review_decision`, second validation result, or terminal
   WorkGroup closure was observed before the agents stopped.

## Terminal state

- WorkGroup: `1bc03f52-15e9-332e-ab08-1d4ffb8c88ab`, `ACTIVE`.
- Participants: both reported `COMPLETED` in final collaboration status.
- REVIEW requests: `44a2df31...` and `42367d8e...`, both `ACCEPTED`.
- Claims: disjoint epoch-1 `todo.py` and `test_todo.py`; no overlapping write
  ownership was created for review.
- Snapshots: A's `snap_9fb0ad...` and B's `snap_8f1f118...` published and
  integrated.
- Validation: first ACCEPT completed; reciprocal validation was not reached.
- Control checkout: integrated snapshot history present, but generated
  `AGENTS.md` remained modified after the run; WorkGroup was not cleanly
  closed.
- Doctor: `DEGRADED`, six warnings, zero errors and zero critical findings:
  two stale session leases, command namespace reconciliation, command
  capacity/retention, and two provider migration warnings.

No second ordinary acceptance was started because this diagnostic did not
reach end-to-end WorkGroup closure. The CP-0511 ordinary result is recorded
separately in
`docs/evidence/syn039-unattended-todo-cp0511-ordinary-2026-08-24.md`.

