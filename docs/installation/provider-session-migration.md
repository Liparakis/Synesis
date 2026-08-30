# Provider-session migration

An initialized project may predate provider-session bindings. Migration is
automatic on the next provider install or first Codex hook:

1. Synesis validates the existing project ID and project-local node identity.
2. It preserves the profile, provider metadata, and unrelated hook entries.
3. It creates a provider-specific binding beneath
   `.synesis/local/sessions/` and a fallback nonce only when provider evidence
   is unavailable.
4. It reports `READY_FOR_REAL_VALIDATION` while real provider validation stays
   a separate gate.

Bindings are versioned and atomically replaced. A malformed, stale, or
identity-mismatched record fails closed; Synesis does not reinitialize the
project or copy the global identity into it. The global Link identity created
during diagnosis is preserved as machine-level onboarding/diagnostic state and
is not provider-session authority.
