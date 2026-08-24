# SYN-039 CP-0502 owner polling diagnostic

## Result

This fresh bounded diagnostic used the current bundled Synesis MCP and two
independent GPT-5.6 Luna Codex sessions against a new Git + Synesis project.
Both sessions reached ten tools and `ready / isolated` bindings, held
disjoint claims, and converged on one WorkGroup.

The run reproduced the owner-side gap that CP-0501 suggested but did not
establish. After the owner accepted a REVIEW request, the durable state held
an active WorkGroup and an issued, unconsumed, single-use REVIEW grant for the
peer. The owner then received ordinary `IMPLEMENT` with no executable
projection and stopped. The peer later consumed the grant and correctly
received `SNAPSHOT_PENDING` → `WAIT` → `get_next_action`, but the owner was no
longer alive to receive the now-legal `finish_lane` projection.

This repeated with the diagnostic continuation rule explicitly requiring both
agents to remain active after ordinary no-action and WAIT states. The owner
was not authorized to mutate at the pre-consumption point, but progress was
still required for unattended completion. The smallest confirmed defect was
therefore a missing owner-side non-terminal projection, not an ownership or
grant-authority failure.

## Harness and preflight

- Project root:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0502-001`
- Project ID: `3495456a-396c-4082-98e6-91853c5d886e`
- Seed commit: `fdd6d14`
- Final control commit: `0bb340d` (`master`, clean)
- Harness:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0502-001`
- MCP executable: current bundled `synesis-mcp.exe`
- MCP startup: protocol `2025-06-18`, version `0.1.0-SNAPSHOT`, commit
  `bc334ac`, exactly ten tools
- Connections:
  - A: `conn-instance-48857101-ef07-40a9-8f30-0a52c482cbc0`
  - B: `conn-instance-c9355bb5-0d91-4ad4-b90b-e2a56379ef1f`
- Both `ensure_session` calls returned `ready / isolated`.

Participants were distinct and claims were disjoint. The run converged on
WorkGroup `aa623b7d-1f0a-3b61-bdd9-6c66a8d8ea63`. The owner-side participant
held its own exact path claim; the peer held the complementary exact path
claim.

## Exact transition

1. The owner accepted REVIEW request
   `005396db-065b-405e-930e-cc03263ce6fd`.
2. Synesis issued single-use grant
   `861c6047-0de1-3832-992f-0ab03fcb7706`, targeting the peer participant,
   for the owner's intent at claim epoch 1.
3. Owner `get_next_action({})` returned ordinary `IMPLEMENT`, with no
   `recommendedTool` or executable lifecycle arguments. The response still
   contained the active WorkGroup, participants, intents, and unconsumed
   grant in collaboration state, but no state saying that the owner must
   remain active and wait for peer grant consumption.
4. The peer executed the exact projected grant-consumption request. The tool
   returned `status=CONSUMED`.
5. The peer then received `reason=validation_required`,
   `nextAction=wait`, `nextProtocolAction=wait`, and
   `recommendedTool=get_next_action` twice. No snapshot was yet published.

No exact projected action failed. No snapshot, validation, integration, or
WorkGroup closure occurred. Raw JSONL and MCP startup logs remain under the
fixture harness `logs` directory.

## Narrow resolution

`AgentNextActionService` now projects an owner-side `WAIT` when all of the
following are true: the caller owns an active intent; its WorkGroup is active;
the grant targets that exact intent and current claim epoch; the grant is
currently available and single-use; the grant targets another participant;
and the owner's immutable snapshot is not yet published. The projection
exposes the grant, WorkGroup, target participant, epoch, and
`review_grant_consumption` payload, while the workflow reducer exposes only
`get_next_action({})`. It grants no write, review, or grant-consumption
authority to the owner.
