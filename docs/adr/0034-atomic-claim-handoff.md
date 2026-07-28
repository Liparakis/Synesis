# ADR-0034: Atomic two-party claim handoff

## Status

Accepted for SYN-021.

## Decision

Handoff begins as a signed `HANDOFF` coordination request. The current owner
retains the claim while the request is pending. Only the addressed active target
may accept it. The response and `CLAIM_HANDOFF_ACCEPTED` event are appended
under the same project append lock; the projection then replaces the owner and
increments the intent version, fencing the source epoch without an unowned
interval.

Rejection, duplicate response, stale version, wrong target, inactive target,
and unauthorized response leave ownership unchanged. The first slice records a
bounded proposal; dirty-worktree artifact validation remains an integration
checkpoint concern.
