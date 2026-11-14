# ADR-0029: Zero-touch autonomous agent collaboration

## Status

Accepted for SYN-013. Its decision was built through ADR-0030 and ADR-0031 (both accepted for SYN-013B); the status
line still said "Proposed" and was corrected on 2026-11-14.

## Decision

Extend the existing local coordination module with a hidden runtime/session
manager and provider-neutral bootstrap surface. Keep the file-backed signed
event protocol, Git as durable history, and per-session worktrees. Make provider
workspace transition and mutation interception explicit capability gates.

## Rationale

The current CLI acceptance proves coordination semantics, not ambient harness
control. Antigravity real-agent enforcement failed and Codex real trust is
unfinished. Treating either as ready would violate isolation and the stated
zero-touch invariant. A modular-monolith extension is reversible and preserves
the verified protocol.

## Revisit when

Measured local scale, remote deployment, or a provider security boundary makes
the topology insufficient; or both target providers pass trusted workspace and
mutation evidence.

