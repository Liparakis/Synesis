# SYN-039 CP-0537 pending REVIEW projection defect

Date: 2026-08-25

## Proven defect

The fresh ordinary acceptance in
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0537-2026-08-25-001`
created one shared WorkGroup and a valid REVIEW admission request. After the
reviewer executed the exact projected
`request_coordination({kind: work_group_join, payload: ...})`, the next
`get_next_action({})` projected the same mutating request again. Repeated
requests were idempotent and returned the same request ID, but the reviewer
had no usable pending/wait state and could not progress autonomously.

The source trace identified the mismatch in
`workspace/src/main/java/org/synesis/workspace/application/agent/AgentNextActionService.java`:
the fallback `reviewActions` branch considered active sibling intents but did
not exclude a pending outgoing REVIEW request from the current participant.
Inbound pending requests were projected separately for the owner, so the
requester was re-admitted instead of waiting for the owner response.

## Narrow fix

`AgentNextActionService` now:

- projects a pending outgoing REVIEW request as `WAIT` with
  `OWNER_RESPONSE_PENDING`;
- exposes the request ID, target, intent, WorkGroup, claim epoch, and the
  exact `get_next_action({})` continuation;
- excludes only the matching pending outgoing REVIEW intent from fallback
  `REVIEW_ADMISSION_REQUIRED` discovery;
- leaves ownership, claims, grants, epochs, snapshot access, and fail-closed
  mutation checks unchanged.

The deterministic regression is
`AgentNextActionServiceTest.pendingReviewAdmissionProjectsWaitInsteadOfReplayingRequest`.
The focused workspace suite passes.

## Baseline identifiers

- Project ID: `b360c71e-24fc-4353-9c06-9c5ac6f937ce`
- WorkGroup: `5bcb8076-33cf-338f-8c7b-19f8f9501a1a`
- Reviewer participant: `agt_bf76867c-aa72-3b85-b00d-8515f9986884`
- Owner participant: `agt_a83288e9-14aa-3f8c-9eb7-c76b79fd117e`
- Pending REVIEW request: `f9b5c8d4-9921-4018-866a-a12ce11807ed`
- Final state: WorkGroup `ACTIVE`, request `PENDING`, no grant, snapshot,
  validation, integration, or closure.

## Separate verification issue

The MCP SYN-039 test run was stopped after reproducing the known Git startup
stall. Test worker PID `8480` was blocked by child PID `20404` running:

```text
git -c core.hooksPath=... -c core.fsmonitor=false ... check-ignore --no-index --quiet -- AGENTS.md
```

This remains infrastructure evidence and was not hidden with a larger
timeout.
