# ADR-0058: Bound broker-pinned stock Codex thread feasibility

## Status

Accepted as a feasibility result; production integration deferred.

## Context

The SYN-051 shared-normal-home experiment showed that process-private proofs
do not make Codex's persisted thread store worker-owned. A fresh App Server
could resume another worker's persisted thread after the original process
stopped. The next bounded hypothesis was a tiny trusted lifecycle broker that
owns one App Server stdio channel and permanently pins it to one durable
expected provider thread.

The spike had to remain outside the Synesis source boundary, avoid provider
credential handling, preserve `UNSAFE_FILE_AUTH`, and leave production
continuity policy unchanged.

## Decision

The broker concept is **PASS-B** for feasibility. A disposable broker using
the normal Codex home/auth successfully enforced one broker, one App Server,
and one exact persisted thread. It rejected wrong-thread operations before
Codex received them, rotated proofs across process generations, rejected a
live owner and ambiguous liveness, and serialized a concurrent successor
race. It remained lifecycle-only.

The result is not production-ready because App Server-only death orphaned the
disposable MCP child. A production broker therefore requires a verified owned
process-tree supervisor and an audited dead/ambiguous classification. The
broker lock is not a replacement for Synesis durable generation fencing.

## Consequences

- The provider-thread ownership gap is closable at a trusted stdio boundary.
- `UNSAFE_FILE_AUTH` remains unchanged; no auth-policy weakening follows.
- The current dedicated-home implementation and managed-acceptance hard stop
  remain in force.
- A future production slice must bind exact durable thread state before MCP
  admission, preserve strict authority resolution, and reuse generation
  fencing rather than create a second identity graph.
- The broker depends on Codex App Server protocol/config compatibility; a
  provider-native authenticated launch-context assertion would be stronger if
  available.

## Evidence

See [
`SYN-051-broker-pinned-thread-feasibility-2026-09-03.md`](../evidence/SYN-051-broker-pinned-thread-feasibility-2026-09-03.md).

No production source, `.synesis` state, historical fixture, provider
credential, or remote repository was changed by the spike.
