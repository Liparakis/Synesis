# ADR-0086 — Recovery continuation admission before claim declaration

## Status

Accepted for SYN-082 continuation recovery on 2026-09-20.

## Context

The server already projects recovery-held lanes through `get_next_action` and
requires an explicit `request_coordination(kind=continuation)` before a fresh
participant receives successor authority. Ordinary MCP admission, however,
could try to announce the replacement's copied task claim first. A reviewer
replacement that copied the exact `review-report.md` claim therefore collided
with the recovery-held predecessor before it could observe the continuation
action. This made a lawful reviewer successor depend on provider behavior that
omitted the task claim, and could strand an otherwise recoverable review lane.

The fix must not release the predecessor claim, auto-select a continuation, or
grant mutation authority during session admission.

## Decision

After a verified session lease is established, the MCP admission path performs
a read-only recovery-continuation preflight. It recognizes an active,
recovery-held intent in an active WorkGroup when:

* the requested semantic role matches;
* a caller-provided WorkGroup restriction matches, when present;
* caller-provided ownership selectors exactly match the held intent's selectors
  (an empty selector list is allowed only as discovery input); and
* the caller is a different participant from the held predecessor and a
  non-empty recovery snapshot reference exists.

When the preflight matches, admission deliberately does not announce an intent.
The session receives no claim and no grant. The participant must poll
`get_next_action`, choose among any projected candidates, and submit the exact
server-projected continuation request. The existing append-locked continuation
validation remains the sole authority-transfer boundary.

If the preflight read fails, or no held lane matches, the existing claim path
remains authoritative and fails closed on overlap. A normal new reviewer with
no recoverable reviewer lane still must explicitly claim a disjoint report or
test path.

## Consequences

* Fresh replacements can reach the existing continuation projection even when
  their blind task declaration repeats the predecessor's exact claim.
* No claim, participant intent, grant, or successor is created by the preflight.
* Claim isolation remains unchanged for unrelated or non-recovery-held work.
* Multiple candidates remain a choice for the agent; the admission preflight
  never assigns one.
* The preflight reads the same project-local coordination state as the normal
  MCP path and adds no tool or protocol surface.

## Alternatives rejected

* Releasing the recovery-held claim during admission would create an unowned
  window and weaken crash recovery.
* Automatically continuing the only matching lane would turn a session
  admission read into assignment and violate the no-planner boundary.
* Allowing any overlapping claim without recovery-state, role, and exact-scope
  checks would bypass claim isolation.
* Adding a reviewer-specific MCP tool would expand the fixed ten-tool catalog
  and duplicate the generic continuation action.

## Verification

`McpAdmissionRecoveryTest` proves that a fresh reviewer with an exact
recovery-held report claim receives a ready session, sees the continuation
candidate, and has not transferred authority before the explicit continuation
request. Existing continuation, review, race, and catalog tests remain part of
the SYN-082 focused matrix.
