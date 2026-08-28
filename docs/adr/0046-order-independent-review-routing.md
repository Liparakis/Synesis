# ADR-0046: Order-independent review routing

Status: Accepted
Date: 2026-08-28

## Context

The SYN-039 reviewer-first runtime reproduced an order-sensitive defect in
`AgentNextActionService.reviewActions`. The projection selected the first
active peer, or a fallback peer, as the review target. When the reviewer
intent arrived first, the producer was later assigned review semantics and
could not receive a valid producer publication action. Arrival order therefore
changed the meaning of otherwise equivalent work-group participation.

## Decision

- Add a bounded semantic role to `WorkIntent`: `PRODUCER` or `REVIEWER`.
- Permit reviewer intents to declare bounded, non-owning resource selectors
  identifying the producer work they may review.
- Automatic review admission considers only active reviewer intents and active
  producer intents in the same work group. An empty reviewer target is usable
  only when exactly one producer candidate exists; explicit selectors must
  identify exactly one producer candidate. Ambiguous or unrelated candidates
  produce no admission action.
- Keep the existing request and grant records. Their canonical direction is
  explicit: the request requester and grant target participant are the
  reviewer; the request conflicting intent and grant target intent are the
  reviewed producer lane. MCP projections expose descriptive aliases and
  reject conflicting alias values.
- Resolve review access to the immutable snapshot by the existing work-group,
  lane-intent, and claim-epoch binding. Do not make a snapshot identifier part
  of the pre-publication grant.

## Consequences and non-goals

This closes the proven selection defect without introducing a scheduler,
arrival-order protocol, generalized identity machinery, provider-specific
behavior, new MCP tools, or manual repair/orchestration. Existing explicit
review requests and durable grants remain consumable for compatibility, while
automatic admission no longer infers a reviewer from peer order.
