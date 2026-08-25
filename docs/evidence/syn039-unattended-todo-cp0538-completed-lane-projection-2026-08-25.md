# SYN-039 CP-0538 completed-lane pending REVIEW projection

Date: 2026-08-25

## First post-fix acceptance

The fresh ordinary run used the rebuilt bundled MCP in
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0538-2026-08-25-001`.
It reached one shared WorkGroup, reciprocal REVIEW requests, accepted grants,
snapshot publication, integration, and structured validation progress.

The first post-fix lifecycle defect was narrower than the CP-0537 defect:
after a lane published its snapshot and became `COMPLETED`, it still had an
unresolved outgoing REVIEW request while the WorkGroup remained `ACTIVE`.
The completed-binding branch returned terminal `COMPLETED` before checking
that pending request. This exposed no usable continuation to the provider,
even though the owner still had to answer and the review lifecycle was not
finished.

## Narrow fix and regression

`completedReviewAction` now checks the same durable pending outgoing REVIEW
projection before returning terminal `COMPLETED`. A completed lane with an
unresolved request receives `WAIT`, `OWNER_RESPONSE_PENDING`, and the exact
`get_next_action({})` continuation. The regression extends
`AgentNextActionServiceTest.completedParticipantProjectsReviewOnlyAdmissionForActiveSiblingGroup`.

The fix does not reopen the lane, create write ownership, bypass grant or
epoch checks, or alter terminal cleanup behavior.

## Acceptance identifiers

- Project ID: `b360c71e-24fc-4353-9c06-9c5ac6f937ce`
- WorkGroup: `fe469b0c-fec9-3e92-b1e0-11c545a9b1b3`
- Participants: `agt_f802b5e8-5afc-3077-86c8-ec1db08231cb`,
  `agt_ff0f0e42-4e7f-3261-a2bd-e7e3d3c8d752`, plus one additional reviewer
  participant created by the provider session
- Published snapshot: `snap_85bc226a2e730b4bb0ca811ff684849a`
- Final WorkGroup state: `ACTIVE`
- Final Doctor: `DEGRADED`, 6 warnings, 0 errors, 0 critical findings,
  `NEXT_ACTION=prepare_repair_plan`

## Verification

- `:workspace:test --tests org.synesis.workspace.AgentNextActionServiceTest`
  passed after the fix.
- `:cli:platformBundle --rerun-tasks --no-daemon` passed.
- The known MCP Git subprocess stall remained separate.

No unchanged projected action failed in this run. Provider/session
engagement remained the boundary after valid projections.
