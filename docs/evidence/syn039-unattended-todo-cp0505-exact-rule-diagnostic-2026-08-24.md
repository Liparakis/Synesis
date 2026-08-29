# SYN-039 CP-0505 exact-projection diagnostic

## Result

This fresh bounded diagnostic used a new Git + Synesis project and two
independent GPT-5.6 Luna Codex sessions. Both sessions used the current
bundled Synesis MCP, reached `ready / isolated`, converged on one WorkGroup,
and executed every concrete lifecycle projection that they observed.

No exact projected mutation failed. The run stopped after grant consumption
while both agents still had the executable `WAIT` → `get_next_action({})`
continuation available. The producer did not poll again after the reviewer
consumed the grant, so snapshot publication, validation, integration, and
closure were not reached. This is agent-compliance evidence, not a proven
Synesis lifecycle defect. No production code changed for this run.

The ordinary second acceptance was not run because this diagnostic did not
reach end-to-end completion.

## Harness and preflight

- Project root:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0505-001`
- Harness:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0505-001`
- Project ID: `796f6155-3a8f-47c1-8cb9-5aab2ef35138`
- Seed commit: `540c195`
- Synesis managed baseline: `df7c03779d167f00655709ca3b22255623d599ee`
- MCP executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- MCP SHA-256:
  `D27A9F4D3C833C3C5581DD012254E7AE767D96FC71F53DC2718461CBC6822CD1`
- MCP startup: protocol `2025-06-18`, version `0.1.0-SNAPSHOT`, commit
  `bc334ac`, exactly ten tools, provider `codex`
- MCP connection A:
  `conn-instance-e28448b4-9637-491c-9742-a467c618a94c`
- MCP connection B:
  `conn-instance-400cb235-1a6e-4760-a976-5e56f55f47f8`
- Agent A session/worktree:
  `session-af8046f3-cdc3-44ee-b132-93340a4cfd51`
  at
  `C:\Users\Liparakis\AppData\Local\Synesis\workspaces\796f6155-3a8f-47c1-8cb9-5aab2ef35138\worktrees\session-af8046f3-cdc3-44ee-b132-93340a4cfd51`
- Agent B session/worktree:
  `session-f95fb73e-fed2-49bf-b3b7-aae8c05fcf70`
  at
  `C:\Users\Liparakis\AppData\Local\Synesis\workspaces\796f6155-3a8f-47c1-8cb9-5aab2ef35138\worktrees\session-f95fb73e-fed2-49bf-b3b7-aae8c05fcf70`
- Both `ensure_session` calls returned `status=ready`,
  `workspace=isolated`.

The prompts gave only complementary visible coding responsibilities plus the
requested exact-projection rule. They prohibited hidden `.synesis/**` access,
manual relay, manual transitions, ownership repair, and invented lifecycle
calls.

## Participants and ownership

- WorkGroup:
  `35aa138a-a6bf-389a-a4b5-e7bbe66024ec`, `ACTIVE`
- Agent A participant:
  `agt_077c18ef-8b67-345f-83c7-1dd9ddab1e16`
    - intent: `28e6aae4-6127-3007-a54e-917371722eda`
    - claim: `PATH_EXACT todo.py`, claim epoch `1`
    - responsibility: implement `TodoList.complete`
- Agent B participant:
  `agt_b3eccbab-43da-3086-bb3c-73b15b10114e`
    - intent: `07430344-4f1d-3201-9e7a-3c28532ea888`
    - claim: `PATH_EXACT test_todo.py`, claim epoch `1`
    - responsibility: add one regression test
- Claims were disjoint and both intents remained `ANNOUNCED`/active at the
  last observed projections.

## Projection/action trace

The trace below records each coordination projection and the immediately
following action. Visible `read_file`, `apply_patch`, and `run_command` calls
are included where they explain the lifecycle position.

### Agent A

1. `get_next_action({})` projected:

   ```json
   {
     "nextAction": "request_coordination",
     "nextProtocolKind": "work_group_join",
     "recommendedTool": "request_coordination",
     "arguments": {
       "kind": "work_group_join",
       "payload": {
         "proposal": "Review the immutable snapshot for this work group",
         "workGroupId": "35aa138a-a6bf-389a-a4b5-e7bbe66024ec",
         "intentId": "07430344-4f1d-3201-9e7a-3c28532ea888"
       }
     }
   }
   ```

   A executed the exact projection. The result was one pending REVIEW
   request, `917a964a-960d-4359-b68b-835dc2047cd0`, targeting B.

2. The same projection was delivered again. A executed the exact same
   `request_coordination` arguments again; the result returned the same
   request ID and did not create a duplicate request.

3. A then performed only its assigned visible `todo.py` work. The patch
   changed `TodoList.complete` to mark the selected item complete, and
   `pytest` passed `3` tests.

4. After B accepted the request, A received a concrete grant-available
   projection with `recommendedTool=request_coordination` and exact arguments
   containing:

   ```json
   {
     "kind": "work_group_join",
     "payload": {
       "grantId": "a92067d7-7d0f-365b-b514-7b3efb314428",
       "intentId": "07430344-4f1d-3201-9e7a-3c28532ea888",
       "claimEpoch": 1,
       "workGroupId": "35aa138a-a6bf-389a-a4b5-e7bbe66024ec",
       "targetParticipant": "agt_077c18ef-8b67-345f-83c7-1dd9ddab1e16"
     }
   }
   ```

   A executed those exact arguments. The result was
   `grantId=a92067d7-7d0f-365b-b514-7b3efb314428`, `status=CONSUMED`.

5. A then received the exact continuation three times:

   ```text
   nextAction=wait
   recommendedTool=get_next_action
   arguments={}
   nextProtocolKind=review_validation
   ```

   A executed `get_next_action({})` after each delivery. No snapshot was
   visible and no validation action was projected before A ended its session.

### Agent B

1. B's first `get_next_action({})` projected ordinary `IMPLEMENT` with no
   concrete lifecycle tool. B read visible files, added the focused
   regression test to `test_todo.py`, and ran pytest. The isolated worktree
   reported `2 passed, 2 failed` because A's sibling `todo.py` implementation
   was not present in B's worktree. B did not edit A's file.

2. B then received and executed the exact owner response projection:

   ```json
   {
     "kind": "coordination_response",
     "payload": {
       "coordinationRequest": "917a964a-960d-4359-b68b-835dc2047cd0",
       "coordinationStatus": "ACCEPTED",
       "proposal": "admitted"
     }
   }
   ```

   The result was `coordinationRequest=917a964a-960d-4359-b68b-835dc2047cd0`,
   `status=ACCEPTED`, and Synesis issued the single-use REVIEW grant
   `a92067d7-7d0f-365b-b514-7b3efb314428` to A.

3. Before A's grant consumption was observed, B received the exact
   `REVIEW_GRANT_PENDING` continuation:

   ```text
   nextAction=wait
   recommendedTool=get_next_action
   arguments={}
   nextProtocolKind=review_grant_consumption
   grantId=a92067d7-7d0f-365b-b514-7b3efb314428
   ```

   B executed `get_next_action({})` twice. It then ended its session instead
   of polling again after A consumed the grant. No projected mutation was
   ignored or failed.

## Lifecycle state at the stop

- REVIEW request:
  `917a964a-960d-4359-b68b-835dc2047cd0`, accepted
- REVIEW grant:
  `a92067d7-7d0f-365b-b514-7b3efb314428`, single-use, target A, claim epoch 1,
  consumed exactly once
- Snapshots: none
- Validation decisions: none
- Integration: none
- WorkGroup: `ACTIVE`
- Control checkout: clean at managed baseline `df7c037`
- Agent A worktree: `todo.py` modified; pytest passed 3 tests
- Agent B worktree: `test_todo.py` modified; pytest reported 2 passed and 2
  expected dependency failures
- No closure, cleanup, or final integrated application state was reached.

The direct CLI event-plane status was `COORDINATION_STATUS=PASS` with
`PROJECT_SEQUENCE=0`, `TASKS=0`, and `OWNERSHIPS=0`; the WorkGroup state above
comes from the MCP coordination projections, which are the relevant
WorkGroup/LaneGrant source for this diagnostic.

## Doctor and independent verification

Fixture Doctor was `DEGRADED` with six warnings, zero errors, and zero
critical findings:

- two `stale_session_lease` warnings;
- `command_namespace_reconciliation_required`;
- `command_capacity_or_retention`;
- two `provider_migration_required` warnings.

The warnings did not prevent ready/isolated preflight, WorkGroup convergence,
REVIEW admission, owner response, or grant consumption. They remain separate
from this agent-compliance stop. The recurring root Git subprocess stall,
bootstrap migration failures, and repository format findings remain separate
verification issues as previously recorded.

## Durable evidence

Raw agent JSONL and MCP startup logs are retained at:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0505-001\logs`.

Conclusion: the current protocol projections were executable and the executed
mutations succeeded. The next implementation decision must not be based on a
backend failure from CP-0505. The next diagnostic should keep both ordinary
agents alive through the post-consumption owner projection and peer snapshot
publication, then preserve the first later projected failure or no-action
state.
