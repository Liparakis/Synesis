# SYN-039 CP-0525 ordinary unattended Todo acceptance

## Result and classification

This was a fresh ordinary two-agent acceptance using only the agents'
complementary visible coding prompts. The harness supplied no lifecycle
instructions, identifier relay, manual request/grant transition, snapshot
publication, validation, integration, or ownership repair.

The run reached one shared WorkGroup, exact REVIEW admission, owner
acceptance, single-use grant consumption, immutable snapshot publication,
snapshot integration, and structured ACCEPT validation. It then stopped at the
first concrete lifecycle gap after ACCEPT: Agent B's own active intent and
`test_todo.py` claim remained live, but repeated `get_next_action` calls
returned ordinary `IMPLEMENT` with no executable lifecycle action. B therefore
had no protocol-projected path to publish/finish its own accepted lane, and the
WorkGroup remained ACTIVE.

This is a product/projection defect, not an agent-compliance deviation. B
performed the visible coding work, followed the concrete admission, grant,
recovery, and validation projections, and did not invent `finish_lane` after
the final bare `IMPLEMENT` projection. The reviewer decision itself was a
choice point: Synesis exposed the exact grant/snapshot/intent/epoch context and
allowed ACCEPT/REJECT values without guessing the review result; B submitted
the structured ACCEPT successfully.

## Fresh project and harness

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0525-001`
- Harness/logs: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0525-001`
- Project ID: `7eba15f8-47e1-4c00-890e-9d34d12bc8ce`
- Seed commit: `2891779` (`seed Todo ordinary acceptance`)
- Managed baseline: `49f62b5`
- Final control checkout: `09c0d52` (`Synesis immutable lane snapshot`)
- Control checkout: clean on `master`
- MCP executable: `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256: `3EFCCEA02349D5AD5C5E6D14A362BB58645078A7591564069792F7338CBCCA6E`
- MCP startup version/commit: `0.1.0-SNAPSHOT` / `bc334ac`
- Agent A connection: `conn-instance-ccb08a61-2332-4d15-b7aa-86f5db71834a`
- Agent B connection: `conn-instance-f723db66-3628-4951-84a0-1c1383b206de`
- Both launch wrappers passed the same project root and current bundled
  executable. A separate read-only wire probe returned protocol `2025-06-18`,
  server `synesis 0.1.0-SNAPSHOT`, and exactly ten tools:
  `ensure_session`, `read_file`, `apply_patch`, `run_command`,
  `get_next_action`, `request_coordination`, `respond_coordination`,
  `publish_capability_implementation`, `finish_lane`, `cancel_lane`.

## Participants, claims, and WorkGroup

| Agent | Participant / intent | Claim / epoch | Session worktree | Final state |
|---|---|---|---|---|
| A | `agt_cf3cd89f-9661-3bb3-b4c0-732a93b605f2` / `6dc6923d-e23d-300e-ba85-ff09ab3f6d51` | `PATH_EXACT todo.py`, epoch 1 | `session-15fd55ba-7513-466b-b69f-8460ea1aab98` | COMPLETED |
| B | `agt_dc811ea4-0ecf-37d5-890f-c01e7739ef83` / `f63dee3c-0822-3852-916b-767882eb3b64` | `PATH_EXACT test_todo.py`, epoch 1 | `session-de476ffb-0fb1-45c6-9224-d975b79c6b15-recovery-01bbd304-1843-4ad7-b345-8be1701bfa5a` | ACTIVE |

- Shared WorkGroup: `d34f88e2-83f2-34af-a84c-c2bb351f65cc`, `ACTIVE`
- REVIEW request: `90573dbf-da4b-40e7-bf42-9a1a79ae6b1a`, A target,
  `ACCEPTED`
- REVIEW grant: `a9f772ce-0722-39d0-890d-9c5cbd1cc6d6`, target B,
  target intent A, epoch 1, single-use, consumed
- A snapshot: `snap_1b7221059ac7a398520b7efdb220bfbb`, commit
  `5be5794851f1a5b0d2b0e7953f5aa114f9256047`, `PUBLISHED` and integrated
- Review validation: `ACCEPTED` for the A snapshot; no rejection occurred
- No terminal WorkGroup status was recorded.

## Projection-to-action trace

1. Both agents called claim-bearing `ensure_session`; both became
   `ready / isolated` with disjoint claims. Their initial projections were
   ordinary `IMPLEMENT` with no lifecycle tool, and both performed visible
   repository work normally.
2. B received and executed the exact projected
   `request_coordination` admission for A's intent:
   `work_group_join(workGroupId=d34f88e2-83f2-34af-a84c-c2bb351f65cc,
   intentId=6dc6923d-e23d-300e-ba85-ff09ab3f6d51,
   proposal="Review the immutable snapshot for this work group")`.
3. A received and executed the exact projected
   `respond_coordination` acceptance for request
   `90573dbf-da4b-40e7-bf42-9a1a79ae6b1a` with `ACCEPTED` / `admitted`.
4. B received and executed the exact projected single-use grant consumption
   for grant `a9f772ce-0722-39d0-890d-9c5cbd1cc6d6`, target participant
   `agt_dc811ea4-0ecf-37d5-890f-c01e7739ef83`, target intent
   `6dc6923d-e23d-300e-ba85-ff09ab3f6d51`, epoch 1, and WorkGroup
   `d34f88e2-83f2-34af-a84c-c2bb351f65cc`.
5. A received and executed the exact projected
   `finish_lane({"summary":"Publish the completed immutable snapshot"})`.
   The result was `snapshotState=PUBLISHED` and `integrationState=integrated`.
6. B's next projection reported `workspace_stale -> ensure_session({})`.
   B executed that exact recovery action, received a distinct recovery
   worktree while retaining the valid coordination identity, and then
   received the review decision context for grant `a9f772ce-0722-39d0-890d-9c5cbd1cc6d6`,
   snapshot `snap_1b7221059ac7a398520b7efdb220bfbb`, intent
   `6dc6923d-e23d-300e-ba85-ff09ab3f6d51`, and epoch 1.
7. B submitted the structured response
   `review_validation(result=accepted, grantId=a9f772ce-0722-39d0-890d-9c5cbd1cc6d6,
   intentId=6dc6923d-e23d-300e-ba85-ff09ab3f6d51, claimEpoch=1,
   snapshotId=snap_1b7221059ac7a398520b7efdb220bfbb)`.
   Synesis returned `ACCEPTED` and truthful `workGroupStatus=ACTIVE`.
8. After that ACCEPT, B ran four further empty-argument
   `get_next_action` polls. Each returned `status=ready`, `workflow.type=IMPLEMENT`,
   `recommendedTool` absent, and no executable lifecycle arguments. The
   projection still showed B's intent `ANNOUNCED`/ACTIVE, claim
   `PATH_EXACT test_todo.py`, the active WorkGroup, A's completed participant,
   and the integrated A snapshot.

## Visible work and integration state

- A changed only `todo.py`; its visible tests passed (`2 passed`) and its
  immutable snapshot integrated into control.
- B changed only `test_todo.py`; its visible tests passed (`3 passed`). The
  change remains uncommitted in the recovery worktree and is not present in
  the clean control checkout.
- Control checkout `master` is clean at `09c0d52` and `python -B -m pytest`
  passes `2/2` there. This proves the accepted A implementation is present,
  not that B's test contribution integrated.
- The recovery worktree has `M test_todo.py`; no manual cleanup or integration
  was performed.

## First concrete blocker and source trace

The active B intent has no publication/completion projection after the sibling
lane's ACCEPT. The existing code explains the mismatch:

- `workspace/src/main/java/org/synesis/workspace/application/agent/AgentNextActionService.java`
  projects `finish_lane` only from `snapshotPublicationAction`, which requires
  a consumed REVIEW grant targeted at the current intent. B's consumed grant
  targeted A's intent, so B's own changed lane is not publishable through that
  branch.
- `workspace/src/main/java/org/synesis/workspace/application/agent/AgentWorkflowReducer.java`
  reduces a null next action to `IMPLEMENT` with only visible repository
  operations. That is correct while coding remains, but leaves no executable
  handoff/publication action once B's visible work is complete.
- `workspace/src/main/java/org/synesis/workspace/application/collaboration/ReviewValidationService.java`
  only marks the WorkGroup `COMPLETED` when there are no active intents and no
  available grants. B's active intent therefore correctly prevents closure;
  the missing piece is the action that can complete that intent.

This slice does not justify changing ownership, grant fencing, stale lease
cleanup, detached-agent retention, Doctor behavior, or the integration engine.
The next narrow implementation action is to reproduce this active-reviewer
no-action state deterministically and project the existing-model completion /
review path for the active reviewer lane without bypassing claims, epochs,
snapshot validation, or fail-closed authorization.

## Final diagnostics and retained evidence

Fixture Doctor:

- `DOCTOR_RESULT=DEGRADED`, 6 warnings, 0 errors, 0 critical
- `CLEANUP_RECOMMENDED=false`, `RECONCILIATION_RECOMMENDED=true`,
  `REPAIR_AVAILABLE=true`, `NEXT_ACTION=prepare_repair_plan`
- warnings: two `stale_session_lease`,
  `command_namespace_reconciliation_required`,
  `command_capacity_or_retention`, and two `provider_migration_required`
- These warnings did not prevent the ready/isolated sessions or the lifecycle
  reached here and remain separately classified.

Raw JSONL, prompts, launch wrappers, startup traces, and stderr remain under:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0525-001`.

Focused repository verification after the run passed:

- `:mcp:test --tests org.synesis.mcp.application.McpSyn039SliceTest`
- `scripts/agent-validate-deferred.ps1`
- `scripts/agent-validate-fixtures.ps1`
- `git diff --check`

The Synesis worktree stayed clean and remains 68 commits ahead of origin;
nothing was pushed.
