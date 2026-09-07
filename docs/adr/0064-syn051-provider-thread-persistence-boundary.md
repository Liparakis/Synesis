# ADR-0064: SYN-051 provider-thread persistence boundary

- Status: Accepted for the next bounded implementation slice
- Date: 2026-09-04
- Scope: SYN-051 managed Codex lifecycle only

## Decision

`thread/start` is a process-local creation result, not a sufficient
cross-process handoff contract. Generation 1 must be created by the managed
App Server (inside the existing proof/quarantine boundary), after which the
broker acquires and immutably pins the exact returned thread under unique
`(provider, thread)` ownership. A successor or replacement may rely on
`thread/resume` only after at least one provider-persisted turn/history event,
or after a specifically verified provider persistence operation.

The existing proof-gated admission, immutable broker-derived pinning,
generation fencing, and owned process-tree lifecycle remain unchanged. A raw
provider thread ID never grants Synesis authority.

## Evidence

The 2026-09-04 provider-only T1/T2 investigation found that a 0.153.0
start-only thread was visible to `thread/read` in its originating process but
had no provider `threads` row, history items, or rollout and could not be read
or resumed by a fresh process. A thread with one completed turn produced all
three durable artifacts and was successfully read and resumed by a fresh
process using the same normal provider home and executable. The detailed
record is [
`SYN-051-provider-thread-provenance-2026-09-04.md`](../evidence/SYN-051-provider-thread-provenance-2026-09-04.md).

The finding is a PASS-B architecture simplification, not a claim that full
managed A1/A2 acceptance has passed. The current bootstrap-created handoff
must not be retried as if it were durable.

## Consequences

- The separate pre-managed bootstrap process is no longer required for
  generation-1 thread creation.
- The managed lifecycle must expose a bounded creation/pin transition before
  it can use a provider thread as a successor identity.
- A first persisted turn is part of the next real-runtime acceptance setup.
- Missing persistence remains a fail-closed compatibility error.
- No provider authentication isolation is claimed; the selected security
  model continues to use the shared normal provider home with Synesis-side
  authority controls.
