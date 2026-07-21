# 21. Provider lifecycle and diagnostics

## Decision

Keep provider lifecycle management in `:workspace` application services behind
the unified `:cli` composition root. Use a static registry for the two real
integrations: Antigravity (`BETA`) and Claude Code (`EXPERIMENTAL`). Provider
metadata is local-only below `.synesis/local/providers/`.

Provider configuration is merged in place with a stable Synesis-managed hook
ID, written through a temporary sibling and atomic replacement where supported.
Malformed JSON is rejected without overwrite. Synthetic checks use temporary
projects and typed SDR2 constraints through the existing provider adapters and
`ActionGuardrail`; they never modify a user project store.

## Consequences

Install/status/uninstall are deterministic and idempotent, unrelated provider
configuration remains owned by the user, and real-agent validation is reported
separately from synthetic health. Codex, portable packaging, dynamic loading,
and automatic repair remain deferred.
