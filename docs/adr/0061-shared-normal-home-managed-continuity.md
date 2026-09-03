# ADR-0061: Shared normal provider-home managed continuity

**Status:** Accepted for the bounded SYN-051 implementation slice

**Date:** 2026-09-03

## Context

The Codex provider does not supply a supported worker-ownership or
thread-scoped managed-authentication primitive. A shared normal Codex home can
therefore expose provider threads across processes. That exposure is not by
itself Synesis authority, but it invalidates any design that treats a thread ID,
process lineage, or process-private proof as sufficient ownership.

The existing Synesis implementation already has exact provider bindings,
hash-only rotating managed attachment proofs, monotonic generations, strict
connection resolution, and a narrow App Server lifecycle seam. The bounded
broker and Windows Job Object feasibility records demonstrate the remaining
provider-thread and process-containment mechanisms independently, but neither
is yet the complete production integration.

## Decision

Implement shared-normal-home managed continuity as an explicit Synesis profile.
Synesis will own a durable atomic provider-resource record keyed by
`(provider, providerThreadId)`. An active managed binding is the sole lawful
owner of that key. The broker derives the thread from that record and pins it
before provider operations; callers and models cannot select a different
thread. Managed MCP admission requires the exact process-private proof,
binding, thread, and current generation. Missing or invalid proof fails closed
and never downgrades to ordinary session-bound admission.

Provider-thread ownership and attachment generation remain separate. Ownership
survives runtime replacement; generation identifies the one current runtime
that may speak for the binding. Terminal release is explicit and lawful only.
Owned process-tree supervision is required before managed authority activates;
unsupported or ambiguous containment fails closed. The normal provider home is
used by Codex itself. Synesis never reads, copies, parses, moves, logs, or
injects provider credentials.

## Consequences

- Ordinary Codex and Claude MCP remain `SESSION_BOUND`.
- No MCP tool, raw App Server passthrough, second identity graph, provider-auth
  proxy, or provider source patch is added.
- `UNSAFE_FILE_AUTH` remains correct for isolated-home mode; the explicit
  normal-home managed strategy is a separate policy path.
- Raw access to another provider thread remains outside Synesis's confidentiality
  promise, but cannot recover the owning Synesis binding without its proof and
  exact managed admission.
- A production acceptance claim requires atomic ownership-race, mismatch,
  proof/generation, process-containment, restart, and fresh two-worker evidence.

## Invalidation

Reopen this decision if Codex adds a verified provider-native worker/thread
ownership assertion, if atomic local ownership cannot be made durable, or if
the Windows process supervisor cannot establish assignment-before-resume and
an unambiguous empty/dead predicate.

## Fitness functions

Focused tests must prove one durable winner for a cross-binding thread race,
reject proof-less managed admission and B-to-A mismatch, preserve ordinary
session-bound admission, prevent broker thread switching, and fail closed on
ambiguous process liveness. A fresh hash-matched managed two-worker
acceptance is required before SYN-051 can be marked complete.
