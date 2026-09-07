# ADR-0060: Shared Codex home security-model audit for SYN-051

## Status

Accepted as a bounded security-model audit — 2026-09-03. No production
implementation or authentication-policy change is authorized by this ADR.

## Decision

Codex's ability to see and resume a persisted provider thread is not, by itself,
a violation of Synesis's required managed-authority property. The property is
that a runtime which is not the current authenticated managed attachment for
Worker A must not acquire or exercise Worker A's Synesis authority.

The shared normal provider home can therefore be safe for Synesis authority
without provider-enforced per-thread ownership, provided Synesis adds and
enforces one minimal provider-resource ownership invariant:

```text
one active managed Synesis binding owns one exact (provider, providerThread)
pair, established by a durable atomic unique ownership CAS/lease
```

The trusted lifecycle broker must derive its immutable thread pin from that
ownership state, control the App Server protocol, and mediate process-private
proof delivery. Existing managed proof validation, attachment generation
fencing, exact authority resolution, and owned process-tree teardown remain
required. Proof-less raw provider resumability must never upgrade a binding into
managed continuity.

This is `PASS-B` for the security model. It does not declare the current
production implementation complete. The current source has no
`ProviderThreadLease` or cross-binding unique thread index, the managed launcher
still accepts a thread argument, and the owned process-tree/broker integration
and safe authentication gates remain incomplete.

## Consequences

- ADR-0057 remains valid as a provider experiment: the normal Codex store does
  not enforce Synesis worker thread ownership. Its `FAIL` classification is not
  sufficient to prove that Codex must enforce Synesis authority.
- Raw Codex may still resume another provider conversation under the same
  provider account/home. This is not claimed to be provider conversation
  confidentiality.
- Thread IDs remain selectors/correlation values, never credentials. The
  managed proof is the runtime credential; the generation is its freshness
  fence; the Synesis binding is logical identity.
- `ensure_session` and managed startup must remain fail closed. A raw runtime
  with no valid managed proof must not recover a managed binding by matching a
  thread, latest state, or provider-wide session.
- A future shared-home implementation may reuse the existing managed
  attachment/binding identity graph. It must not add a second worker identity
  model or weaken `SessionAuthorityResolver`.
- `UNSAFE_FILE_AUTH` remains unchanged. A future policy distinction for a
  normal provider-owned home is a separate authorized implementation decision.

## Required future acceptance

Before any shared-home policy change, prove with fresh disposable state that:

1. a raw Codex process can resume Thread A but cannot obtain A's Synesis
   authority without its managed proof;
2. proof-less `ensure_session` cannot downgrade/recover a managed binding;
3. the unique provider-thread CAS rejects duplicate active ownership;
4. managed B cannot select or authenticate against Thread A;
5. only one successor wins generation and thread ownership;
6. stale and losing successors fail closed and their owned trees are torn down;
7. process-tree liveness ambiguity blocks successor authority;
8. provider credentials remain provider-owned and no raw proof is persisted or
   exposed to the model; and
9. two independent managed workers complete authenticated turns and exact
   restart/resume without duplicate logical coordination state.

The current dedicated-home `UNSAFE_FILE_AUTH` gate, no-push boundary, preserved
fixtures, ten-tool MCP contract, and SYN-049 `PARTIAL` status remain unchanged.

## Evidence

See [
`SYN-051-shared-home-security-model-audit-2026-09-03.md`](../evidence/SYN-051-shared-home-security-model-audit-2026-09-03.md).
