# ADR-0043: Codex App Server lifecycle owner and authority ordering

- Status: accepted for SYN-038 implementation
- Date: 2026-08-03
- Decision owners: Synesis workspace/coordination maintainers

## Context

SYN-038 needs a long-lived owner for one Codex App Server attachment per
provider binding. The repository already has `synesis coordination serve`,
which owns the loopback `CoordinationHttpServer` process. No separate daemon,
listener, provider framework, or lifecycle owner exists.

The existing Synesis session workflow already establishes provider binding,
participant, WorkIntent, claim, lane/worktree, and workspace authority. A
lifecycle START that tried to create those identities after its durable
idempotency gate would be circular and could launch Codex without exact
authority.

## Decision

`CoordinationServeCommand` remains the production process entry point.
`ProjectRuntimeHost` is constructed and retained for the duration of that
process and owns Codex lifecycle attachments, the Codex-only loopback route,
durable lifecycle idempotency, protocol state, evidence, and attachment locks.

`ProviderSessionCommand` first calls the existing
`AgentSessionService.resolveSessionContext` and
`WorkspaceCollaborationService.announce` workflows. It freezes the exact
binding, participant, WorkIntent/claim, lane/epoch, worktree, and MCP
prerequisites into an immutable START envelope. Lifecycle START only verifies
those identities, persists its durable idempotency entry, and then launches and
controls Codex.

The existing coordination HTTP listener is reused. The route is Codex-only and
loopback-only. The MCP surface remains exactly ten tools.

## Consequences

- There is exactly one identifiable long-lived owner for each attachment.
- A stale or missing Synesis authority context fails before lifecycle mutation,
  process launch, or App Server protocol output.
- Owner restart can reconcile an exact stored thread passively without starting
  model work.
- Durable lifecycle request identity is separate from App Server, MCP,
  lifecycle-revision, waiter, and evidence identifiers.
- HTTP, protocol, queue, journal, tombstone, and process-tree bounds remain
  enforced as specified by SYN-038.

## Rejected alternatives

- A new daemon or IPC layer: no repository evidence requires another
  production process, and it would duplicate ownership and shutdown paths.
- Lifecycle START creating claims or worktrees: this creates the circular
  dependency and violates the exact-authority invariant.
- A generic provider lifecycle framework: SYN-038 is intentionally Codex-only.

