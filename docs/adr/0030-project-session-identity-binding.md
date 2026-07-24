# ADR-0030: Automatic project-scoped provider session binding

## Status

Accepted for SYN-013B. Codex and Antigravity remain `READY_FOR_REAL_VALIDATION`,
not real-validated.

## Decision

Keep session binding inside the existing workspace modular-monolith boundary.
Before a provider hook evaluates a meaningful action, it loads the initialized
project-local node identity and atomically creates or resumes a binding under
`.synesis/local/sessions/`. The binding records project, node, provider,
provider-instance fingerprint, session, supervisor, worker, trust state, and
Git base evidence. Provider credentials, private keys, and conversation content
are excluded.

Provider evidence is explicit when Codex supplies `session_id` or Antigravity
supplies `conversationId`. When a provider exposes no stable key, Synesis uses a
project/provider bootstrap nonce and reports `SESSION_EVIDENCE=FALLBACK`; this
does not claim independent chat identity.

The existing machine/global Link identity remains a diagnostic and onboarding
identity. It is not substituted for the project node identity and is preserved
by migration. Provider installation and the first hook action perform the same
idempotent bootstrap, so `synesis init` remains the only human ceremony.

## Consequences

Project and node IDs remain stable while independent provider instances receive
distinct session, supervisor, and worker IDs. Repeated events resume active
bindings; revoked, completed, or abandoned records are never silently reused.
Malformed or mismatched records fail closed. Provider maturity is unchanged:
synthetic checks do not promote a provider beyond the real-validation gate.

## Revisit when

A provider exposes a stronger authenticated session contract, or coordinator
worktree/runtime allocation becomes available and can replace the current
explicit `UNKNOWN` worktree fields without weakening the gate.
