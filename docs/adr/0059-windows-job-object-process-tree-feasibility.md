# ADR-0059: Windows Job Object process-tree feasibility for SYN-051

## Status

Accepted as a partial feasibility result; production integration deferred.

## Context

The preceding SYN-051 broker spike proved exact stock-Codex thread pinning but
could not prove that App Server-only death also ended its MCP child. This
spike tested Windows Job Objects outside production code using a suspended
launch, assignment before resume, disabled breakaway, and kill-on-close.

## Decision

Treat a Windows Job Object as a viable candidate for the provider-neutral
`ManagedProcessTree` primitive. The generic tree and real Codex App Server/MCP
trees were contained, explicit teardown proved Job emptiness, and an
App-Server-only kill left the MCP child observable and still owned until Job
teardown. A separate Job B survived Job A teardown.

Classify the overall spike **PARTIAL**, not PASS-A: ambiguous Job-query
failure, real two-successor loser cleanup, and real Codex recovery after a
supervisor crash were not exercised. Any production implementation must fail
closed for those cases, assign before resume, avoid inherited duplicate Job
handles, and require a definitive empty predicate before successor authority.

`UNSAFE_FILE_AUTH` and the existing shared-home/thread-ownership conclusions
are unchanged. No production continuity code, Codex source, auth policy,
`.synesis` state, fixture, or Synesis MCP path was changed.

## Consequences

- Windows Job Objects are sufficient in principle to contain ordinary Codex
  App Server/MCP descendants and deterministically tear them down.
- `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` must be paired with a correct handle
  ownership model; an inherited or duplicated Job handle could defeat
  kill-on-close.
- `CREATE_SUSPENDED` plus assignment before `ResumeThread` closes the launch
  window tested here; normal descendant inheritance remained contained.
- The process container solves process death, not provider-thread ownership,
  Synesis authority, or provider authentication.
- Cross-platform implementations remain future work and must not be implied
  by this Windows result.

## Evidence

See [`SYN-051-job-object-process-tree-feasibility-2026-09-03.md`](../evidence/SYN-051-job-object-process-tree-feasibility-2026-09-03.md).
