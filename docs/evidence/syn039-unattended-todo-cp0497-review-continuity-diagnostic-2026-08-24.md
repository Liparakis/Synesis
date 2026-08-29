# SYN-039 CP-0497 reviewer-continuity diagnostic

## Scope and classification

This was a fresh bounded diagnostic of the exact projected-action contract
after the reviewer recovery fix. It was not a clean product acceptance: both
agents were given independent coding responsibilities, so Agent A retained a
second active implementation lane after reviewing Agent B's lane. No manual
relay, request acceptance, snapshot publication, validation, integration, or
cleanup transition was performed by the harness operator.

The diagnostic did not produce an exact projected-action failure. It did,
however, prove a durable state/reporting inconsistency: an ACCEPT response
reported `workGroupStatus=COMPLETED` while the durable collaboration projection
later reported the same WorkGroup `ACTIVE` with a live intent and review
grants. This is the next SYN-039 lifecycle blocker; it is not classified as
agent non-compliance.

## Harness and preflight

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0497-001`
- Harness: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0497-001`
- Project ID: `4d0fa215-d2e4-4a72-9a1c-0e7b858a3b1e`
- MCP executable: the repository-built
  `cli/build/platform-bundle/synesis-0.1.0-dev.local-windows-x64/bin/synesis-mcp.exe`
- MCP SHA-256 observed after the rebuild:
  `0A30C6DE6B032B6305AF4A8142705BB91CB39DD5BBC77C1BD007BFD1EDCC7ACE`
- MCP protocol: `2025-06-18`
- MCP version: `0.1.0-SNAPSHOT`
- Startup commit: `bc334ac`
- Catalog: exactly ten tools
- Both agents used the same project root, distinct connection/session
  identities, and reached `ensure_session=ready` with `workspace=isolated`.

Participants and lanes:

| Agent | Participant                                | Intent                                 | Claim          | Epoch |
|-------|--------------------------------------------|----------------------------------------|----------------|------:|
| A     | `agt_4ccf5981-ec68-3fb4-ba44-fdd686d0a1a2` | `25ab2ea7-3bcc-3411-9289-438184a7f38e` | `todo.py`      |     1 |
| B     | `agt_5baa7728-3cda-3d80-9fab-89c82a3c041b` | `758178e9-688a-35bf-b081-9e104211405d` | `test_todo.py` |     1 |

Shared WorkGroup: `7c5ac4f7-c538-39c2-8e5d-ed9fadbdc771`. B's test intent
established the group and became its producer. A became the reviewer for B,
while A's own implementation intent remained active.

## Exact projection/action trace

| Projection                                                                                                                                                                                            | Exact following action                                                                                           | Result                                                                                                              |
|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| A: `REVIEW_ADMISSION_REQUIRED`, `request_coordination`, `kind=work_group_join`, WorkGroup `7c5ac4f7-c538-39c2-8e5d-ed9fadbdc771`, intent `758178e9-688a-35bf-b081-9e104211405d`                       | `request_coordination` with the projected kind/payload                                                           | Request `cec841ea-98d7-414e-9721-0d92266c1b03`, `REVIEW`, `ACCEPTED`                                                |
| A: repeated identical REVIEW admission projection                                                                                                                                                     | exact repeated `request_coordination`                                                                            | Request `6ae2e8ce-0d4d-4b3c-92f9-7f5e16dd9120`, `REVIEW`, `ACCEPTED`                                                |
| B: owner `respond_coordination` for request `cec841ea-98d7-414e-9721-0d92266c1b03`                                                                                                                    | exact `{kind:coordination_response,payload:{coordinationRequest,coordinationStatus:ACCEPTED,proposal:admitted}}` | REVIEW grant `01455883-e2b3-35bc-aea1-e787b5c16329` issued to A                                                     |
| B: owner `respond_coordination` for request `6ae2e8ce-0d4d-4b3c-92f9-7f5e16dd9120`                                                                                                                    | exact projected acceptance                                                                                       | REVIEW grant `81abb964-a6c9-3436-a8d1-5dbd4c9c492f` issued to A                                                     |
| A: grant available for `01455883-e2b3-35bc-aea1-e787b5c16329`                                                                                                                                         | exact projected grant consumption payload, target A, intent `758178e9-688a-35bf-b081-9e104211405d`, epoch 1      | `CONSUMED`; no overlapping write ownership                                                                          |
| B: `snapshot_publication_required`, `finish_lane`, summary `Publish the completed immutable snapshot`                                                                                                 | exact `finish_lane({summary:...})`                                                                               | `task=integrated`; snapshot `snap_3eb0df616deb0c00e78540f63877b1c2`; snapshot `PUBLISHED`; integration `integrated` |
| A: `workspace_stale` → `ensure_session` after control advanced                                                                                                                                        | exact `ensure_session({})`                                                                                       | `ready/isolated`; same A session identity preserved on a new recovery worktree                                      |
| A: `validation_required`, `review_validation`, grant `01455883-e2b3-35bc-aea1-e787b5c16329`, snapshot `snap_3eb0df616deb0c00e78540f63877b1c2`, intent `758178e9-688a-35bf-b081-9e104211405d`, epoch 1 | exact `respond_coordination` with `result=accepted`                                                              | `ACCEPTED`; response reported `workGroupStatus=COMPLETED`                                                           |
| A: second exact grant-consumption projection for `81abb964-a6c9-3436-a8d1-5dbd4c9c492f`                                                                                                               | exact projected consumption                                                                                      | `CONSUMED`                                                                                                          |
| A: second exact `review_validation` projection for the same snapshot                                                                                                                                  | exact `respond_coordination` with `result=accepted`                                                              | `ACCEPTED`; response again reported `workGroupStatus=COMPLETED`                                                     |

Agent A then completed its separate visible `todo.py` implementation and
reported `3 passed`. Its subsequent `get_next_action` responses remained
ordinary `IMPLEMENT` with no concrete lifecycle tool or arguments. It did not
invent `finish_lane`, which was correct under the fail-closed contract.

## Durable terminal observation

The final control checkout was clean. The snapshot projection identified
immutable commit `e3112d21283e8096b7e6e5d36e08cabeddb931b1` with changed
`test_todo.py` plus Python bytecode artifacts. The final control history also
contained `278dd67`, another `Synesis immutable lane snapshot` with the same
tree. This duplicate snapshot history is recorded for later idempotency and
artifact cleanup review, not changed in this diagnostic.

Final `synesis collaboration status` reported:

- B: `COMPLETED`
- A: `ACTIVE`, claim `todo.py`
- both REVIEW requests: `ACCEPTED`
- both REVIEW grants: still listed for A
- WorkGroup `7c5ac4f7-c538-39c2-8e5d-ed9fadbdc771`: `ACTIVE`

Thus the exact service response claiming `COMPLETED` is not the durable
WorkGroup state. The response is produced on every accepted decision, while
the status-change event is only appended when there are no active intents and
no available grants. This run had A's active second intent and duplicate
review grants, so the durable group correctly remained active under the
current closure condition. The response nevertheless exposed a false
terminal status to the agent.

The run therefore did not qualify for the second ordinary unattended
acceptance. The next narrow implementation slice should first make the
review response reflect the durable WorkGroup status, then rerun a fresh
diagnostic with the intended single producer/reviewer lifecycle before
attempting broader closure or cleanup changes.

## Verification

- Focused MCP catalog and SYN-039 tests: PASS
- Focused workspace session-binding/recovery tests: PASS
- Affected Javadocs (`:mcp-contract:javadoc :mcp:javadoc :workspace:javadoc`): PASS
- Deferred and fixture validators: PASS
- Go vet: PASS
- `git diff --check`: PASS
- Bootstrap Go tests: FAIL only at the three known migration cases reporting
  `update migrations not prepared`
- Full root `check`: not completed; it reproduced the known infrastructure
  issue. `McpServerTest.java:48` reported `AccessDeniedException`, then
  `workspace:test` left child Git processes waiting. Observed child processes
  included `git.exe` PIDs `23604`, `20660`, `16456`, `26744`, and `10492`, and
  Gradle test workers `18324`, `11552`, `17872`, and `25936`. The bounded
  check was interrupted after preserving this evidence.
- Fixture Doctor: `DEGRADED`, six warnings, zero critical findings/errors,
  `NEXT_ACTION=prepare_repair_plan`; these warnings were not shown to cause
  this lifecycle result.

No production code was changed by the CP-0497 acceptance run itself. No push
was performed and no SYN-040 was created.
