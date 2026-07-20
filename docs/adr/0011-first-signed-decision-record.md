# ADR-0011: First signed shared decision record

## Status

ACCEPTED FOR PREREQUISITE ONLY - 2026-07-21. The record task remains blocked
until SL-014 is verified.

## Context

The CAF concept calls for versioned, attributable project truth before
supervisors, workers, ownership leases, or autonomous coordination. Synesis
has verified authenticated Link sessions but no project record store or
application-stream API usable by a higher-level module. `:link` and `:cli`
are frozen by the current planning request.

## Proposed decision

When explicitly activated, add exactly one application record type,
`decision`, in a new `:project-record` module. A decision has a stable UUID,
monotonic revision, immutable owner, provenance, status, evidence digest
references, predecessor digest, and Ed25519 signature. It persists as an
immutable per-profile revision chain with an atomically updated local head.

The module accepts records only from a configured authenticated Link peer whose
node identity equals the record owner/author and whose signature verifies.
Version-and-digest comparison detects duplicates, stale state, gaps, and
divergence; conflict input is quarantined rather than overwriting a head.

The existing CLI stays unchanged. The new module may own a fixed JDK-only
development launcher for record creation, one-shot sync, and readable
inspection.

## Required predecessor

The user approved a separate SL-014 Link task to expose the smallest
transport-neutral, bounded authenticated application-stream seam. It must
contain no project or decision vocabulary and retain Link's identity,
readiness, liveness, framing, limits, deadlines, and cleanup ownership. SL-014
does not authorize record storage or sync.

## Rejected alternatives

- Store authoritative state in Markdown or Obsidian: no canonical encoding,
  signature, version, or deterministic conflict behavior.
- Send a decision through `synesis-demo-work/1`: that protocol accepts only
  `describe-session` and expanding it would leak project semantics into Link.
- Add a second network stack or a central service: unnecessary, duplicates
  Link trust, and violates the local-first proof scope.
- Add failures, tasks, membership, workers, leases, or federation alongside
  decisions: no evidence yet requires them.

## Consequences and invalidation

The first shared record proof remains deliberately pairwise and owner-writes
only. It establishes no global order, consensus, remote availability, or
general multi-writer merge policy. Reopen this ADR when a second record type,
multi-writer authority, record-size pressure, continuous sync, or independent
release/ownership evidence makes the chosen module and file store insufficient.

## Fitness functions

- Link contains no `project`, `decision`, `record`, `owner`, or `sync` package
  or wire terminology.
- Every accepted remote record is bounded, session-authenticated,
  signature-verified, project-scoped, and persisted before its head advances.
- Equal version with unequal digest never overwrites a local head.
- Existing Link and CLI strict tests remain unchanged and pass.
