# SYN-039 CP-0480 — WorkGroup convergence projection evidence

Date: 2026-08-24  
Task: SYN-039 — Autonomous Workgroup Completion

## Classification

The backend did not reproduce a WorkGroup split when both claim-bearing
sessions used the same project and complementary exact claims. Both sessions
converged on the deterministic active WorkGroup selected by
`WorkspaceCollaborationService.announce`.

The confirmed defect was narrower: REVIEW admission was present in the nested
`reviewActions` projection, but the executable workflow advertised
`recommendedTool=request_coordination` with empty `arguments`. This forced an
agent to infer the nested payload instead of receiving the exact action.

## Diagnostic run

Fixture: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0480-003`  
Project: `62e195d5-c21f-4d4c-a959-5b688aeb9270`  
WorkGroup: `82223437-5c6b-38a3-bf18-c0216df46d5e`

Both GPT-5.6 Luna agents used the current bundled MCP and independently called
`ensure_session` with the same goal/acceptance and disjoint exact claims:
`todo.py` and `test_todo.py`. The second agent's immediate `get_next_action`
contained both participants, both intents, the same WorkGroup, and no pending
conflict. Both proceeded with ordinary visible implementation work. No split
occurred. They stopped before returning to the lifecycle after implementation;
that remains agent-compliance evidence, not a WorkGroup discovery defect.

## Ordinary run

Fixture: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0480-004`  
Project: `9af18337-dfc9-46b0-adad-384b0cfb0bc8`  
WorkGroup: `019b6e0a-b1da-3e8f-9e44-bbd5496f031b`

With only complementary coding prompts, the agents also converged on one
WorkGroup. Agent A first overclaimed `test_todo.py`; Synesis correctly returned
`overlapping_claim`. Its next projection exposed
`REVIEW_ADMISSION_REQUIRED`, `request_coordination`, `work_group_join`, and
the exact WorkGroup/intent payload. The agent executed that request
successfully. An owner response initially included an unsupported `reason`
field and failed closed; the corrected exact response succeeded. No separate
WorkGroup was created.

## Narrow fix

`AgentNextActionService` now promotes the selected review action's protocol
kind and payload to the top-level response. `AgentWorkflowReducer` now copies
that payload into executable `request_coordination` arguments. The existing
MCP regression asserts the exact `work_group_join` kind, WorkGroup ID, and
intent ID. Ownership, path protection, and lifecycle semantics are unchanged.

Focused MCP, workspace reducer, and next-action tests passed. The bundled MCP
was rebuilt successfully after terminating only the stale acceptance process
that held its files open.

Remaining blockers are later lifecycle completion/cleanup, the known Git
subprocess stall, bootstrap migration test failures, and existing Doctor
warnings.
