# ADR-0040: Automated lane recovery and server-enforced manual attestation

- Status: Accepted for SYN-028
- Date: 2026-07-29

## Decision

SYN-028 extends the isolated mutation-lane model with a durable, crash-
recoverable lifecycle. Process loss or quota exhaustion fences the old
authority and enters `SUSPENDED`; it never infers abandonment. A verified
immutable recovery snapshot transitions the lane to `RECOVERY_HELD` and keeps
its scope reserved until continuation, cancellation, revocation, or operator
action. Continuation always receives a new binding, epoch, lane, and worktree.

Completion is an idempotent protocol transaction: prepare and verify the
immutable snapshot, durably commit completion, then close the lane and release
claims. Recovery resumes or rolls back incomplete phases without duplicate
snapshots or premature claim release.

The durable inbox is non-destructive and at-least-once. Retrieval never
acknowledges an item; an exact-caller-authorized response variant performs
idempotent acknowledgement or resolution.

Provider installation deploys one managed Synesis Manual skill globally for
each provider. Session establishment attests its manifest, version, and hash.
Failed attestation blocks mutation and authority-increasing operations but
permits state inspection, inbox reads, own-lane closure/cancellation, claim
relinquishment, diagnostics, and operator-authorized recovery or revocation.

Before migration, an exclusive project migration lock and durable phase marker
prevent races with incompatible old-format writers. Migration resumes or rolls
back idempotently from the recorded phase.

## Consequences

Synesis does not share private chat context or resurrect closed provider chats.
Agents use bounded inbox queries when provider wake-up is unavailable. The
cancelled lane is permanently fenced; preserved evidence can return to active
development only through explicit recovery into a new authorized lane.

