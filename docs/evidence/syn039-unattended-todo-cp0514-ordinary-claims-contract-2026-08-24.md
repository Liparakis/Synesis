# SYN-039 CP-0514 ordinary acceptance — claim-announcement contract

Date: 2026-08-24

## Purpose

This was a fresh ordinary two-agent acceptance after the agent-facing
`ensure_session` contract was clarified. The agents received only
complementary visible coding prompts. No lifecycle instructions, message
relay, manual request/grant transition, snapshot publication, validation, or
integration was performed by the harness.

## Harness and project

- Project root:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0514-001`
- Project ID: `5158c7dd-d7ef-419b-b792-25d49acb3cb6`
- Seed commit: `0cf2dd2` (`Seed ordinary unattended Todo fixture`)
- Control checkout at end: `102cd95f63cd4de2b61df2b1f53f0648919248a9`
  and clean
- MCP executable: repository bundled
  `cli/build/platform-bundle/synesis-0.1.0-dev.local-windows-x64/bin/synesis-mcp.exe`
- MCP SHA-256:
  `15967D97D566BE1F758098A32DDD54CFB3F2EB6B8A641EA9EA47A7BB1A05544F`
- MCP version: `0.1.0-dev.local`; protocol: `2025-06-18`; ten raw tools
- Provider: `codex`; project pin passed as `--project <project-root>`
- Provider manual version: `4`

Agent A used session `session-9a969ab7-24cb-4bf4-800a-2221b09194be`,
isolated in worktree
`...\\worktrees\\session-9a969ab7-24cb-4bf4-800a-2221b09194be`.
Agent B used session `session-e440d130-fdb7-4cc5-bd1c-6dc90f21c823`,
isolated in worktree
`...\\worktrees\\session-e440d130-fdb7-4cc5-bd1c-6dc90f21c823`.

## Agent responsibilities and initial claims

Both agents independently called `ensure_session` with the clarified
claim-bearing task shape and reached `ready / isolated`:

- A: implement `TodoList.complete` in `todo.py`; exact claim
  `path_exact:todo.py`.
- B: add one regression test in `test_todo.py`; exact claim
  `path_exact:test_todo.py`.

The server recorded distinct participants and intents:

- A participant `agt_d39273ea-2a69-3521-88b8-2b748892eb7a`, intent
  `48a7b714-8ad6-3bd1-8b06-f7a8ef4b9450`, epoch 1.
- B participant `agt_50f69a1c-fbc5-36c0-84f6-bee9d88c3df1`, intent
  `120d116d-2d43-33b6-93d2-95a207c03b51`, epoch 1.

Both intents converged on WorkGroup
`d15372f8-6d60-3db4-beaa-c78eaed2e62d`, which remained `ACTIVE`.

## Exact progression

1. A's initial `get_next_action({})` projected ordinary `IMPLEMENT` with
   permitted visible operations and no lifecycle tool. A implemented
   `todo.py` and ran `python -m pytest test_todo.py`: `3 passed`.
2. B's initial `get_next_action({})` projected
   `request_coordination` with `kind=work_group_join`, WorkGroup
   `d15372f8-6d60-3db4-beaa-c78eaed2e62d`, A's intent
   `48a7b714-8ad6-3bd1-8b06-f7a8ef4b9450`, and the review proposal. B executed
   those projected arguments; request
   `8fec3118-c1a1-407d-899e-f20f3a975563` was created.
3. A's `get_next_action({})` projected the exact owner response
   `respond_coordination({kind:coordination_response,payload:{coordinationRequest:
   8fec3118-c1a1-407d-899e-f20f3a975563,coordinationStatus:ACCEPTED,
   proposal:admitted}})`. A executed it successfully.
4. A then received the exact read-only continuation
   `WAIT -> get_next_action({})` with REVIEW grant
   `91988d4d-9f80-311d-860f-55d46a3a5eff`, target participant B, target intent
   A's intent, WorkGroup, epoch 1, and `snapshotRequired=true`. A executed
   three identical continuation polls but stopped while the grant was still
   pending, before observing B's later consumption.
5. B's next projection exposed the exact single-use grant-consumption
   `request_coordination` payload. B first omitted the projected
   `targetParticipant` and Synesis rejected the non-projected call closed with
   `policy_denied / COORDINATION_FIELD_REQUIRED:targetParticipant`. B then
   executed the complete projected payload including
   `targetParticipant=agt_50f69a1c-fbc5-36c0-84f6-bee9d88c3df1`; Synesis
   returned `status=CONSUMED` for grant
   `91988d4d-9f80-311d-860f-55d46a3a5eff`.
6. After grant consumption, B's `get_next_action({})` projected
   `WAIT -> get_next_action({})` with `nextProtocolKind=review_validation`,
   `state=SNAPSHOT_PENDING`, WorkGroup, grant, and `snapshotRequired=true`.
   B executed the exact continuation twice and stopped. No snapshot became
   visible, so no validation decision, integration, or WorkGroup closure was
   reached.

## Final state

Final collaboration status recorded the two active participants, disjoint
claims, the accepted REVIEW request, the single-use grant, and the active
WorkGroup. No snapshot, validation decision, integration attempt, or terminal
WorkGroup state was recorded. The control checkout remained at the seed
commit and clean.

Final `synesis doctor --verbose` was `DEGRADED` with six warnings, zero errors,
and zero critical findings:

- two `stale_session_lease` warnings;
- `command_namespace_reconciliation_required`;
- `command_capacity_or_retention`;
- two `provider_migration_required` warnings.

## Classification

The clarified claim-announcement contract fixed the prior ordinary-run
discoverability gap: both ordinary agents now independently establish
disjoint intent/ownership and converge on one WorkGroup. Review admission,
owner acceptance, and single-use grant consumption are reachable.

This run proves no new production lifecycle defect. The first stop after the
grant path is agent continuation/compliance: the producer stopped before
polling after grant consumption, and the reviewer stopped at the projected
`SNAPSHOT_PENDING` continuation. The omitted `targetParticipant` was rejected
fail-closed and then corrected by the agent. Keep it as diagnostic evidence;
do not weaken the protocol or add lifecycle machinery for this run.

Raw agent JSONL and harness logs are retained at:
`C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0514-001`.
