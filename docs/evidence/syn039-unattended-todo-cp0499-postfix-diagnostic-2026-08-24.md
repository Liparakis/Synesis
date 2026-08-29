# SYN-039 CP-0499 Post-Fix Bounded Diagnostic

Date: 2026-08-24

Status: BLOCKED — the diagnostic reached snapshot publication, integration,
and structured ACCEPT, but did not reach clean WorkGroup closure. No ordinary
second acceptance was started because the bounded diagnostic did not complete.

## Scope and harness

This was a fresh disposable Git + Synesis project with two independent
GPT-5.6 Luna Codex agents. No coordination action was relayed or triggered
manually.

- Project root:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0499-003`
- Project ID: `ac5d791a-9f5f-419c-8252-5261c090931b`
- Seed commit: `e27c40e Seed Todo acceptance fixture`
- Harness:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0499-003`
- MCP executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP executable SHA-256:
  `BD8AAD11D6ABCDE946684AD4E1F0EC150A5489592E82B30F32580514E680CF6E`
- Launch route for both agents:
  `synesis-mcp.exe mcp --provider codex --project C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0499-003`
- MCP control preflight for the same bundled executable: protocol
  `2025-06-18`, server `synesis`, version `0.1.0-SNAPSHOT`, startup commit
  `bc334ac`, exactly 10 tools.
- Agent MCP catalog preflight: both agents reported exactly 10 tools and
  `ensure_session` returned `status=ready`, `workspace=isolated`.

The agents used disjoint visible claims:

| Agent | Participant                                | Session / provider binding                     | Claim                     | Epoch |
|-------|--------------------------------------------|------------------------------------------------|---------------------------|-------|
| A     | `agt_308bf35e-34be-32ee-b6c0-08b3c04a6bbf` | `session-2d1dfc31-836e-4a1b-ac32-50bb2efed2c7` | `PATH_EXACT todo.py`      | 1     |
| B     | `agt_93d9b57e-8c9c-380d-8577-429c3ff6b503` | `session-fa4e2094-b8f3-4c72-809d-1604457f7ff1` | `PATH_EXACT test_todo.py` | 1     |

Both bindings were verified against the same project. Agent A's worktree
was `...\worktrees\session-2d1dfc31-836e-4a1b-ac32-50bb2efed2c7`; Agent B
recovered to
`...\worktrees\session-fa4e2094-b8f3-4c72-809d-1604457f7ff1-recovery-12d6ca3f-baaa-4c31-8fc5-f4eec78135b5`
after a correctly handled `workspace_stale` recovery.

## Coordination identifiers

- Shared WorkGroup:
  `3621a4f6-6b2b-3379-9174-9cdcb45b8186`
- Agent A intent:
  `ac5f0d7a-87d1-3400-baa8-c6c7fd636fd1`
- Agent B intent:
  `c07b437f-d113-3fb4-a6f2-03d12e19c587`
- Agent A claim epoch: `1`
- Agent B claim epoch: `1`

Initial intent matching converged both agents on the single WorkGroup. Agent
A completed the visible `todo.py` implementation and passed the initial three
tests. Agent B added a fourth test to `test_todo.py` and passed `4 passed`.

## Projection-to-action trace

The agents were instructed to execute a concrete projected action exactly
before choosing another Synesis lifecycle action. The relevant transitions
were executed as follows.

1. `IMPLEMENT` with no executable lifecycle action → each agent performed its
   assigned visible repository work normally.
2. Agent B received the exact projected
   `request_coordination(kind=work_group_join, payload={intentId:
   ac5f0d7a-87d1-3400-baa8-c6c7fd636fd1, proposal: "Review the immutable
   snapshot for this work group", workGroupId:
   3621a4f6-6b2b-3379-9174-9cdcb45b8186})` and executed it successfully.
3. Agent A received and executed exact projected
   `respond_coordination` ACCEPT actions for the pending REVIEW requests.
4. Agent A received exact `snapshot_publication_required` → `finish_lane`
   with `{summary: "Publish the completed immutable snapshot"}` and executed
   it successfully.
5. Agent A's result was:
   `task=integrated`, `snapshotState=PUBLISHED`,
   `integrationState=integrated`, snapshot
   `snap_d03b1424511d73cbf6d1e13ed23937de`.
6. Agent B received exact grant-consumption projections and executed them,
   then executed exact structured ACCEPT validation responses for that
   immutable snapshot.

No exact projected action returned a protocol failure.

## Requests, grants, snapshot, and validation

The same REVIEW admission projection remained executable after the first
successful request. Agent B followed the projection each time, producing
three distinct requests for the same target intent and WorkGroup:

- `0884ddcf-a8c2-4a2d-b876-bf1536f25e9f`
- `c6bb62a2-401b-47c3-9ee1-0f80a7525717`
- `71014c1b-a133-445d-b877-982b04aefd6b`

Agent A accepted all three. The resulting single-use grants were:

- `1e931fe2-0019-3c0b-81c2-5fdc1c66b98e`
- `91710581-d12e-3f6f-97ac-4579d05f4804`
- `58bfb735-0bbc-3e33-a5aa-0f4b8cbd362b`

Agent B consumed each exact grant with the same target intent, WorkGroup,
participant, and epoch, and submitted three structured ACCEPT decisions for
snapshot `snap_d03b1424511d73cbf6d1e13ed23937de`. Every response reported
`result=ACCEPTED` and `workGroupStatus=ACTIVE`.

Snapshot details from the final projection:

- Snapshot: `snap_d03b1424511d73cbf6d1e13ed23937de`
- Snapshot commit: `752fadabde2513b7317d231f6c6df844253fdc34`
- Base commit: `c49a9d3bdb587e9b486e46bf67aec694b838096c`
- Changed paths: `todo.py`
- Snapshot state: `PUBLISHED`
- Integration result: `integrated`

The control checkout contained the implemented `todo.py` at commit
`9938f68 Synesis immutable lane snapshot`. Agent B's fourth test was not
integrated because Agent B never received a snapshot-publication action for
its own active lane.

## First unresolved lifecycle state

After the final ACCEPT, Agent B's exact `get_next_action` result was:

- top-level `status=ready`;
- no `nextAction`, `nextProtocolAction`, `recommendedTool`, or executable
  arguments;
- `currentIntent` remained Agent B's `ANNOUNCED` intent with its
  `PATH_EXACT test_todo.py` claim still active;
- WorkGroup status remained `ACTIVE`;
- no pending coordination requests;
- no pending review action;
- blockers were empty;
- permitted operations were only `read_file`, `apply_patch`, and
  `run_command`.

Thus visible work had passed, the reviewed snapshot had integrated, and the
review had been accepted, but Synesis did not project a usable action for the
still-active reviewer lane to publish its own snapshot, finish, or close the
WorkGroup. This is the first concrete protocol/product blocker after the
completed review transition.

The repeated identical REVIEW request projection is related evidence: the
same successful admission request remained actionable and created three
distinct grants. Single-use grant replay protection was not bypassed, but the
admission path was not idempotent under the observed exact-at-least-once
projection sequence.

## Final diagnostics and separate issues

The final control checkout was clean and contained the Todo implementation.
The final WorkGroup remained `ACTIVE`; no clean WorkGroup terminal state was
recorded. The final Doctor result was `DEGRADED` with six warnings:

- two `stale_session_lease` findings;
- `command_namespace_reconciliation_required`;
- `command_capacity_or_retention`;
- two `provider_migration_required` findings.

The known root `McpServerTest.setUp` Git subprocess stall, bootstrap Go
migration failures, and Doctor warnings were not used to explain this
protocol stall. They remain separately classified.

## Classification and next slice

This run is not agent-compliance evidence: the concrete projected actions
that were available were executed with the projected arguments. It is also
not a REVIEW admission, snapshot, or validation argument defect; those paths
worked.

The smallest next implementation slice is to trace the post-ACCEPT projection
for an active reviewer intent, add a deterministic regression for the
`status=ready`/no-action/ACTIVE-WorkGroup state, and make the existing model
project the correct finish/publication or terminal closure action. In the same
focused trace, make repeated identical REVIEW admission projection
idempotent or project a wait after the first pending request. Do not broaden
into cleanup, detached-agent retention, ownership redesign, or a new
orchestrator.

Raw harness logs are retained outside the repository under
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0499-003\logs`.
