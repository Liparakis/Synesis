# ADR-0013: Minimal searchable project view

## Status

PROPOSED FOR REVIEW - 2026-07-21. SYN-001 is DONE at CP-R4; no
implementation is authorized by this ADR.

## Context

CP-R4 proves that two isolated profiles can exchange and persist one
authenticated, signed `decision` record with deterministic duplicate, stale,
conflict, rejection, and unknown outcomes. The next product question is not
more transport; it is whether a human can find and compare the signed truth
already present locally.

The CAF phase map also names a second record type such as a failed experiment.
That would add another schema, lifecycle semantics, canonical bytes, sync
cases, inspection output, and compatibility surface before a second consumer
exists. A view over existing decisions exercises the user-facing value with
less irreversible surface.

## Decision for review

Select the smallest next slice as a read-only searchable project view over
existing verified `decision` heads. It is a derived query, not a new record
type and not an authority layer.

The initial view should support only bounded queries over stable decision
fields: record ID, title/rationale text, status, owner/author node ID, and
revision/digest display. Results are limited, deterministic, and ordered by
`recordId` then revision; no wall clock is used for freshness or authority.
The view returns only verified current heads by default and fails closed on
corrupt or ambiguous storage rather than silently skipping it. It performs no
mutation and persists no search index in the first slice.

## Ownership and boundaries

- `:project-record` remains the owner of canonical bytes, signature
  verification, revision storage, heads, quarantine, and project identity.
- A future view adapter owns query parsing, bounded matching, deterministic
  ordering, and safe human-readable projection.
- Link and the frozen CLI are unchanged. No network, sync, retry, worker,
  lease, background, Obsidian, or federation behavior is introduced.
- The adapter may consume a read-only enumeration API only if a proven blocker
  shows that the existing inspection surface cannot enumerate verified heads.
  It may not scan private storage paths or duplicate store validation.

## Invariants

1. Every returned row is backed by a canonical, signature-verified decision
   head from the requested project.
2. Query parsing, UTF-8, term count, result count, and rendered fields are
   strictly bounded and deterministic.
3. Equal local storage produces equal result bytes and order across restart.
4. A query cannot mutate decisions, heads, quarantine, configuration, or Link
   state.
5. Corrupt, missing, unauthorized, or ambiguous storage fails closed with a
   safe typed result; it is never omitted as if absent.

## Storage and protocol impact

No new record files, head format, wire message, signature rule, or sync result
is required. The first implementation scans verified current heads on demand;
an index is deferred until measured query cost justifies one. If enumeration
is impossible through existing public APIs, the only allowed blocker is a
minimal read-only `:project-record` accessor with no format or ownership
change.

## Alternatives

| Candidate | Result | Reason |
|---|---|---|
| Add `failed-experiment` record v1 | Deferred | Duplicates envelope/signature/storage/sync machinery and adds semantics without a current consumer. |
| Build a minimal searchable decision view | Selected | Reuses verified signed decisions, adds no protocol or durable authority, and tests explainability directly. |
| Add Obsidian/shared Markdown projection | Rejected | Reintroduces mutable prose as an authority surface and adds an integration before need. |
| Add workers, leases, autonomy, background sync, or federation | Rejected | No current invariant requires them; they multiply ownership and failure modes. |

## Checkpoints and acceptance gates

- **CP-R6 planning gate:** review this ADR, the bounded query contract, and
  the proof that existing storage APIs suffice or identify one blocker.
- **CP-R7 implementation gate:** only if CP-R6 is approved, implement the
  read-only view and its deterministic unit/fixture tests; no production
  networking or record-schema changes.
- **CP-R8 evidence gate:** strict verification, corruption/no-mutation tests,
  restart-equivalence evidence, and safe inspection review; then decide
  whether a second record type is still needed.

## Non-claims and invalidation

This slice does not provide full-text indexing, semantic search, event-log
history, cross-project search, remote search, offline merge, reviewer
workflow, membership, task state, worker coordination, leases, autonomy,
background behavior, federation, physical-machine evidence, or Obsidian
integration. Reopen the decision only when a measured query limit, a second
record consumer, or a new authority requirement makes the derived local view
insufficient.
