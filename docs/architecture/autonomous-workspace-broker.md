# Autonomous workspace broker

`WorktreeBroker` allocates one Git worktree and branch per authenticated agent
session under `.synesis/local/worktrees/<session-id>`. It records repository
root, base commit, branch, worktree path, owner session, and lifecycle state in
a durable registry. Allocation is idempotent; an existing registration is
revalidated before reuse.

The broker verifies repository identity, worktree containment, branch
provenance, clean status, and that no other session owns the path. It never
changes a user-created worktree or branch. Cleanup removes only registered
orphaned worktrees after the session is terminal and Git confirms provenance.

## Provider transition contract

An adapter must expose `discoverProject`, `bootstrap`, `proveWorkspace`,
`interceptMutation`, `injectAtSafeBoundary`, `captureResponse`, and
`captureCompletion`. `proveWorkspace` must be observable (provider-reported
working directory or an equivalent authenticated tool context); a generated
path in a prompt is insufficient.

Codex remains experimental pending trusted real `/hooks` evidence. Antigravity
remains unvalidated because the recorded real run bypassed the project hook.
Claude is deferred. Unsupported providers are explicitly read-only.

## Dirty and failure behavior

Dirty source roots, missing Git, path escape, branch deletion, duplicate active
session, or transition uncertainty return `WORKSPACE_UNVERIFIED`; no mutation
is permitted. A hidden runtime crash can be restarted with the same session
token after revalidating the worktree and cursor.

## MCP connection and text contract

One persistent MCP subprocess represents one provider connection. Calls within
that process share one authoritative session and worker binding; a newly
started subprocess receives a distinct connection and must not inherit another
process's ephemeral worker. File reads expose normalized logical UTF-8 text
with LF separators while revisions remain hashes of exact raw bytes. Patches
match that logical representation and persist using the original BOM and line
ending policy, so providers can copy read content directly without platform
newline conversion.
