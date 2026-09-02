# ADR-0049: Provider integration is required before agent work

## Status

Accepted for SYN-044.

## Decision

Project initialization establishes the Synesis project boundary but does not
silently create provider sessions or treat a partial MCP configuration as a
provider integration. Before session binding, Synesis must verify the selected
provider's project-local installation metadata and existing provider status.

Only a provider with an installation record and a non-broken status may enter
the session/workspace path. Codex's existing `DEGRADED` status remains
admissible because it represents the documented real-agent trust-review state,
not absence of installation. `NOT_INSTALLED`, `BROKEN`, obsolete, unknown, or
malformed provider state is denied before worktree allocation. The same
predicate is used by shared workspace readiness so an integration removed or
broken after binding cannot continue to authorize work.

## Consequences

`synesis init` remains the project bootstrap ceremony, while provider
installation remains an explicit prerequisite supplied by the platform
integration boundary. A fresh project now fails closed instead of creating a
stale or misleading session. Automatic provider-session creation during init
is deliberately out of scope because it would create provider-independent
bindings before a real connection exists.

The external Codex desktop task/thread launcher remains outside Synesis's
control plane; this gate prevents it from receiving work through Synesis until
the provider integration is actually installed.
