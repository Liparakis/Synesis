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

## Amendment 2026-10-11: a reviewer lane needs a producer lane to review

Decided by the project owner after real two-agent runs in which both agents
read the same goal and both announced the reviewer role. A session holds one
lane for its life, so neither could give the role up for the producer role, and
each waited for a producer that was not coming.

- A reviewer intent is admitted only when its work group has an open producer
  intent of another participant, covered by the reviewer's review targets when
  it declares any. Otherwise the announcement is refused with
  `REVIEWER_REQUIRES_PRODUCER`, nothing is recorded, and the session is still
  free to announce the implementation, or to announce the reviewer intent again
  once a producer lane exists.
- An active reviewer intent declared for exactly one producer intent, by the
  matching rule above, makes that producer's work subject to review from the
  moment the reviewer is admitted, before any request or grant exists. A
  reviewer whose session is suspended or held for recovery does not.

This reverses one consequence of the original decision: a reviewer can no
longer be admitted before its producer and wait. The purpose of the decision
is kept. Arrival order still cannot change who reviews whom, because roles and
targets stay explicit; the order that produced a reviewer with nothing to
review is now refused instead of admitted. Recovery continuation of an existing
reviewer lane does not pass through announcement and is unaffected.
