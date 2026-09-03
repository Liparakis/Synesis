# ADR-0063: SYN-051 generation-scoped managed-runtime death receipts

**Status:** Accepted for the bounded replacement lifecycle slice

**Date:** 2026-09-04

## Context

Managed attachment proofs are intentionally process-local and disappear when
their runtime generation ends. The existing replacement path required the old
raw proof plus a caller-supplied stopped assertion, so an `ACTIVE` attachment
whose owned Job had already been proven dead could not be restarted safely.
That would either require proof recovery or unsafe durable-state surgery.

## Decision

The trusted managed process-tree supervisor may return non-secret death
evidence only after its existing ownership and empty-tree predicate succeeds.
The lifecycle launcher persists that evidence as an immutable receipt scoped to
the exact project, provider binding, generation, root identity, and supervisor
provenance. The receipt is stored separately from attachment records and never
contains a raw proof or provider credential.

Replacement reads the receipt through the trusted attachment service. It
requires the current attachment generation and provider-thread owner to match,
rejects terminal or missing-evidence states, and uses the attachment record's
generation/proof hash as the atomic compare-and-replace fence. It mints a fresh
proof and advances exactly one generation while preserving the binding,
provider thread, participant, WorkIntent, and claims. The old generation and
proof therefore remain permanently stale.

Normal managed teardown, startup failure cleanup, and observed process exit
produce the receipt through the same supervisor boundary. Host-crash recovery
without a durable receipt remains conservative and is not inferred from PID
absence.

## Consequences

- A dead managed generation can be replaced without recovering its raw proof.
- Two concurrent replacements have one compare-and-replace winner.
- A receipt cannot authorize another generation or another binding.
- The pending-transport quarantine and exact ten-tool MCP catalog are unchanged.
- Historical generations without a trusted receipt remain unrecoverable under
  this model; they are not retroactively declared dead.

## Evidence

The focused managed-attachment suite passes 14/14 tests, including missing,
wrong-scope, terminal, stale-generation, old-proof replay, and concurrent
proofless replacement cases. Gradle execution remains blocked by the host's
loopback connection failure; no real runtime was launched against stale
installed artifacts.
