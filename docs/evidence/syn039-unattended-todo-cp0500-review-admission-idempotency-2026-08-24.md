# SYN-039 CP-0500 post-fix REVIEW admission diagnostic

## Result

The fresh bounded diagnostic verified the narrow REVIEW admission fix: replaying
the same projected `request_coordination(work_group_join)` returned the same
request ID and did not mint duplicate requests or grants. The run then reached
snapshot publication, integration, grant consumption, and structured ACCEPT.

The run did not reach clean WorkGroup completion because Agent A stopped after
ignoring two repeated concrete `request_coordination` projections. That is
agent-compliance evidence under the diagnostic rule, not a new production
defect. Agent B later accepted A's request, but A was no longer polling to
consume the resulting grant. No production lifecycle change is justified by
that stop.

## Harness and preflight

- Project root: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0500-002`
- Project ID: `5c4700bd-9765-4886-9aea-261bfb65be4a`
- Seed commit: `8238770`
- Harness: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0500-002`
- Model: two independent `gpt-5.6-luna` Codex sessions
- MCP executable: `cli/build/platform-bundle/synesis-0.1.0-dev.local-windows-x64/bin/synesis-mcp.exe`
- MCP SHA-256: `DCB0A0747D63B2566AC900161707CC37AE359FFFD074D322DAD6A45EA9B7A876`
- MCP startup: protocol `2025-06-18`, version `0.1.0-SNAPSHOT`, startup
  commit `bc334ac`, exactly ten tools
- MCP connections: A `conn-instance-ca8db827-11de-43c1-aaa6-7bdd5c313a20`;
  B `conn-instance-28af37e3-78f1-4803-8961-577ed046e8be`
- `ensure_session`: both `ready / isolated`; worktrees were
  `session-df00b5ac-8b8f-4ae6-9708-1605657045f5` and
  `session-ab705442-097a-4729-bab4-c2ec3fbb85ab`

Participants converged on one WorkGroup:

- A: `agt_97ddfa74-ef36-3e41-86c8-978b16a6dffb`, intent
  `af6c7b6f-158d-3c2c-9851-4bc061eaa8aa`, claim `PATH_EXACT todo.py`, epoch 1
- B: `agt_39c0aced-4254-33d4-a14a-e70edd9f8144`, intent
  `dfa409d1-4392-3081-9b6e-bdd14056e118`, claim `PATH_EXACT test_todo.py`,
  epoch 1
- WorkGroup: `4c0005dc-4358-32b5-922a-3cf554cfb54d`

## Projected actions and results

1. Both agents received ordinary `IMPLEMENT` with no executable lifecycle
   action and performed only their assigned visible repository work.
2. B received `REVIEW_ADMISSION_REQUIRED` with exact
   `request_coordination` arguments for A's intent. Repeated execution
   returned the same request `90ab5c3b-e663-4230-94df-5f0077015508`; it did
   not create additional requests.
3. A accepted request `90ab5c3b-e663-4230-94df-5f0077015508` with the exact
   projected `respond_coordination` payload.
4. B consumed the single-use REVIEW grant
   `d531fe00-b103-3c2b-8480-3010cd59fce0` for A's intent and epoch 1.
5. A received the exact `finish_lane` projection and succeeded:
    - snapshot `snap_6b8ee8837a67aca57c5c28baed57a8a2`
    - snapshot commit `ac349cbf5984033e4f308448c85337562daf8339`
    - changed path `todo.py`
    - integration `integrated`
6. B received exact `review_validation` arguments for that snapshot and
   returned structured `ACCEPTED`; the server returned
   `workGroupStatus=ACTIVE`.
7. A then received a concrete review-admission projection for B's intent and
   executed it once, creating request
   `d9d89b66-c0bf-46ac-958f-926c411564e7`. The next two `get_next_action`
   calls projected that same executable request again. A did not execute the
   repeated projections and stopped. This is the first observed deviation
   from the diagnostic rule.
8. B later accepted request `d9d89b66-c0bf-46ac-958f-926c411564e7`, issuing
   grant `b1b5b243-b6a5-308d-af57-bce3d3fc63d4` for B's intent. A was no
   longer active to consume it.

The repeated B admission calls all returned the original request ID, including
after acceptance. Final durable grants were exactly two: the consumed grant
for A's snapshot and the unconsumed grant for B's lane. The idempotency
regression is covered by `WorkIntentServiceTest`.

## Final state

- WorkGroup `4c0005dc-4358-32b5-922a-3cf554cfb54d`: `ACTIVE`
- A: `COMPLETED`, claim released, one published/integrated snapshot
- B: `ACTIVE`, intent `ANNOUNCED`, claim `PATH_EXACT test_todo.py`, no
  published snapshot
- Visible B tests passed `4`; B's snapshot was not published
- No projected lifecycle action failed
- First blocker: Agent A ignored a repeated executable REVIEW admission
  projection and stopped polling; this run therefore cannot distinguish a
  later completion defect safely.

## Doctor and independent warnings

Final read-only Doctor JSON:

- `DEGRADED`, 6 warnings, 0 errors, 0 critical, no mutations
- two `stale_session_lease`
- `command_namespace_reconciliation_required`
- `command_capacity_or_retention`
- two `provider_migration_required`

These findings remain separately classified from the REVIEW admission fix.
The Codex harness also emitted its known model-cache/plugin warnings; they did
not prevent MCP readiness or the exercised lifecycle transitions.

## Durable raw evidence

Per-agent JSONL and MCP startup logs are retained at:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0500-002\logs`.
