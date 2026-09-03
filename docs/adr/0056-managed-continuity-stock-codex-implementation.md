# ADR-0056: Bounded stock-Codex managed continuity implementation

Status: Accepted for SYN-051 implementation — 2026-09-03

## Decision

Implement only the approved `MANAGED_CONTINUITY` profile for stock Codex App
Server. Each logical worker receives one retained, dedicated `CODEX_HOME`, one
dedicated App Server process, one exact Codex thread, and one Synesis-managed
attachment proof delivered through selected `env_vars`. The provider-neutral
core reuses the existing runtime-authentication and exact authority seams;
Codex-specific launch and home handling remains behind a Codex adapter.

The durable managed attachment is adapter-private and versioned. It records
provider, mode, binding/session, exact thread, generation, proof hash, managed
runtime-home identity, status, and revision. It does not create a second
participant, WorkIntent, claim epoch, WorkGroup, or identity graph. Raw proof
exists only in trusted process environment/memory; durable state stores only
its hash and scoped metadata. Proofs are cryptographically random, rotated on
reattachment, single-use, and fenced by a monotonic generation.

Reattachment authenticates the exact existing binding, provider, thread, proof,
generation, authority, liveness, and revision before one atomic replacement.
The old attachment is fenced; races, replay, wrong worker/thread, ambiguous
liveness, and terminal state fail closed. `SessionAuthorityResolver` remains
strict and does not gain latest-session, participant, project, provider,
worktree, or thread fallbacks. First attachment and authenticated
`ensure_session` continuation reuse the existing logical participant,
WorkIntent, claims, and WorkGroup without re-admission.

The Codex home is retained while active or disconnected and cleaned only after
terminal retention. v1 uses one App Server process per managed worker. The
ordinary `SESSION_BOUND` Codex/Claude paths, the ten-tool MCP surface,
`mcp-contract`, provider authentication boundaries, and Review/Doctor behavior
remain unchanged.

## Evidence and gates

ADR-0055 and its evidence establish the accepted provider capability model and
the stock-Codex PASS-A dedicated-home feasibility. Before real acceptance,
source HEAD, built artifacts, installed artifacts, and hashes must be recorded
and matched. Production acceptance must include two real managed workers,
separate homes/processes/threads/proofs, one authenticated model turn, exact
thread restart/resume, proof rotation, stale-generation rejection, and no
model-visible or durable raw proof. Failure to establish safe isolated Codex
authentication is a stop condition.

This decision does not implement Claude continuity, anonymous continuity,
shared App Servers, arbitrary fleet launch, upstream Codex changes, or a
Review/Doctor redesign. Historical fixtures, including SYN-049, are preserved
and are not manually repaired or re-admitted.
