# ADR-0031: Provider mutations require a distinct verified worktree

## Status

Accepted for SYN-013B. Real Codex interception and workspace routing remain
unproven until an actual provider event succeeds in the assigned worktree.

## Decision

When a provider binding is created for a project with a committed Git `HEAD`,
Synesis allocates a durable session worktree below
`.synesis/local/worktrees/<worktree-id>` on a session branch. Existing bindings
resume that path when it is still a valid Git worktree. Projects without a
committed `HEAD` remain bound for diagnostics but have no assigned workspace.

Before policy evaluation, Codex and Antigravity hooks require the event cwd (or
workspace path) to equal the assigned worktree and require Git to report that
same directory as the worktree root. The control checkout, missing worktree,
and mismatched paths return stable deny codes rather than attempting a rewrite.
Provider status reports workspace assignment and interception as unproven; no
synthetic check promotes readiness.

## Consequences

The original checkout cannot be treated as a provider mutation target. A
provider session can be created without claiming that it is safe to mutate,
which preserves diagnostics for uncommitted or non-Git projects while failing
closed at the trust boundary. Codex sessions that cannot transition their cwd
remain degraded and require no unsafe fallback.

## Revisit when

The provider exposes a trusted session-start/workspace-routing contract that
can be exercised by an installed hook and recorded as real evidence.
