# SYN-039 CP-0501 producer-polling diagnostic

## Result

This fresh bounded diagnostic used two independent GPT-5.6 Luna Codex
sessions against a fresh Git + Synesis project and the current bundled MCP.
Both sessions reached the same project, ten tools, and distinct
`ready / isolated` bindings. They converged on one WorkGroup and every
concrete projected mutation that was executed succeeded.

The run reached REVIEW admission, exact owner acceptance, and single-use
REVIEW grant consumption. It stopped before snapshot publication because the
producer stopped after `get_next_action` returned ordinary `IMPLEMENT` with
no executable lifecycle action while the reviewer grant was still unconsumed.
The reviewer later consumed the grant and received the expected
`SNAPSHOT_PENDING` → `wait` projection, but the producer was no longer
polling to receive the now-available `finish_lane` action.

No exact projected action failed. The first deviation is agent-compliance
evidence, not a proven production defect. No production code changed in this
diagnostic.

## Harness and preflight

- Project root:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0501-002`
- Project ID: `1a67c646-9725-48ba-b6ec-63618ef2cd89`
- Seed commit: `194c41f`
- Final control commit: `3a470d8` (`master`, clean)
- Harness:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0501-002`
- Model: two independent `gpt-5.6-luna` Codex sessions
- MCP executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256:
  `DCB0A0747D63B2566AC900161707CC37AE359FFFD074D322DAD6A45EA9B7A876`
- MCP startup: protocol `2025-06-18`, version `0.1.0-SNAPSHOT`, commit
  `bc334ac`, exactly ten tools
- MCP connections:
  - A: `conn-instance-63eb7d52-d4c4-4d29-b2d3-cf7d7b9c6ab9`
  - B: `conn-instance-8208bf1a-4002-4d3b-9219-506e300fb14a`
- `ensure_session`: both `ready / isolated`
  - A worktree: `session-e2ed9671-3931-4401-8577-84c54b9d8a59`
  - B worktree: `session-c2ae0111-b607-4521-a6cb-af2fc39452c4`

Participants and ownership were disjoint and shared one WorkGroup:

- A: `agt_a390eccc-127b-34b7-8863-818a2da6583f`, intent
  `730fc571-5511-3982-9f6c-3ce73c89b560`, claim `PATH_EXACT todo.py`, epoch 1
- B: `agt_b907c21c-c1b2-3c80-9219-ff946e8bbab0`, intent
  `5940d00c-8109-361f-8748-a18fb73b9572`, claim `PATH_EXACT test_todo.py`,
  epoch 1
- WorkGroup: `1f8bc962-fbb5-376b-9f72-1e0b4135a495`

## Projection and action trace

1. A and B received ordinary `IMPLEMENT` projections with no executable
   Synesis lifecycle action and performed only their assigned visible work.
   A implemented `TodoList.complete` and passed `pytest` 3/3. B added one
   visible regression test; its local test run was blocked by the sibling
   implementation not being present in B's isolated worktree.

2. B received the exact REVIEW admission action and executed:

   ```text
   request_coordination(
     kind="work_group_join",
     payload={
       workGroupId="1f8bc962-fbb5-376b-9f72-1e0b4135a495",
       proposal="Review the immutable snapshot for this work group",
       intentId="730fc571-5511-3982-9f6c-3ce73c89b560"
     })
   ```

   The request was `e459644e-1496-4706-ba67-552e270c5cd5`, targeted to A.
   Repeating that exact request projection returned the same request ID.

3. A received the exact owner acceptance projection and executed:

   ```text
   respond_coordination(
     kind="coordination_response",
     payload={
       coordinationRequest="e459644e-1496-4706-ba67-552e270c5cd5",
       coordinationStatus="ACCEPTED",
       proposal="admitted"
     })
   ```

   The tool returned `status=ACCEPTED`.

4. A immediately called `get_next_action` again. The durable state contained
   one issued, unconsumed single-use grant targeted to B:

   - grant: `e6b09aa2-0cf8-35de-b80c-1e4180ccb6a7`
   - target participant: `agt_b907c21c-c1b2-3c80-9219-ff946e8bbab0`
   - target intent: `730fc571-5511-3982-9f6c-3ce73c89b560`
   - WorkGroup: `1f8bc962-fbb5-376b-9f72-1e0b4135a495`
   - claim epoch: 1

   Because the grant had not yet been consumed, A received ordinary
   `IMPLEMENT` with no `recommendedTool` or executable lifecycle arguments.
   A stopped instead of returning to `get_next_action` after the peer-side
   grant transition.

5. B then received the exact single-use grant-consumption projection and
   executed:

   ```text
   request_coordination(
     kind="work_group_join",
     payload={
       grantId="e6b09aa2-0cf8-35de-b80c-1e4180ccb6a7",
       intentId="730fc571-5511-3982-9f6c-3ce73c89b560",
       claimEpoch=1,
       workGroupId="1f8bc962-fbb5-376b-9f72-1e0b4135a495",
       targetParticipant="agt_b907c21c-c1b2-3c80-9219-ff946e8bbab0"
     })
   ```

   The tool returned `status=CONSUMED`. B then received two identical
   `WAIT` projections with `reason=validation_required`,
   `nextProtocolAction=wait`, `recommendedTool=get_next_action`, and
   `arguments={}`. There was no snapshot yet, so B correctly could not
   validate or integrate anything.

## Source trace and classification

`AgentNextActionService` evaluates review actions first, then
`snapshotPublicationAction`. The latter requires a same-WorkGroup, same-intent,
same-epoch REVIEW grant targeted to another participant whose durable grant is
already consumed. Only then does it project `finish_lane`. Before that
consumption the owner has no safe mutation to perform; the reviewer owns the
next authorized transition.

`AgentWorkflowReducer` maps an explicit `WAIT` to the executable
`get_next_action` with empty arguments. A response with no `nextAction` remains
ordinary `IMPLEMENT`, allowing visible repository work without inventing a
lifecycle mutation. These checks preserve ownership, grant, epoch, and
fail-closed behavior.

The observed stop therefore does not prove that Synesis emitted an unusable
action for a required owner mutation. It proves that the ordinary producer
agent treated a non-terminal no-action state as terminal and did not poll after
the reviewer consumed the grant. No lifecycle production change is justified
by this run.

## Final state

- WorkGroup `1f8bc962-fbb5-376b-9f72-1e0b4135a495`: `ACTIVE`
- A: active intent/claim in the final projection; no snapshot published
- B: active intent/claim; the REVIEW grant above was consumed
- Requests: REVIEW request `e459644e-1496-4706-ba67-552e270c5cd5` accepted
- Grants: `e6b09aa2-0cf8-35de-b80c-1e4180ccb6a7` consumed
- Snapshots: none
- Validation: none
- Integration: none
- Control checkout: clean at `3a470d8`; the Todo implementation was not
  integrated because no producer snapshot was published
- No exact projected action failed

## Doctor and independent verification issues

Final read-only project Doctor:

- `DEGRADED`, 6 warnings, 0 errors, 0 critical, 0 mutations
- two `stale_session_lease`
- `command_namespace_reconciliation_required`
- `command_capacity_or_retention`
- two `provider_migration_required`

The recurring root Git subprocess stall, bootstrap migration failures, and
these Doctor warnings remain separately classified; they were not shown to
cause this stop. Focused SYN-039 verification and repository validators remain
the next checkpoint gates.

## Durable raw evidence

Per-agent JSONL and MCP startup logs are retained at:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0501-002\logs`.
