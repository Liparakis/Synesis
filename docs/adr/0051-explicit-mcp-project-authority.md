# ADR-0051: Preserve Explicit MCP Project Authority Across Linked Worktrees

Status: Accepted

Date: 2026-09-02

Task: SYN-045

## Context

The Codex desktop app creates ordinary linked Git worktrees for independent
tasks. Those worktrees contain tracked `.synesis/project.json` metadata but do
not contain repository-private `.synesis/local` provider/profile state or the
untracked `.codex/hooks.json` integration. MCP initialization currently accepts
the provider-reported root before the launcher-pinned `--project` root. A Codex
task can therefore replace a valid installed control checkout with its linked
worktree and be rejected as `provider_integration_required` even though both
paths identify the same repository and Synesis project.

## Decision

When the MCP launcher starts with an initialized, non-worktree control
checkout, that checkout is the authority root. Provider-reported initialized
roots may confirm this authority only when both of the following match the
configured checkout:

- the Synesis project ID; and
- the canonical Git common directory.

An equivalent ordinary linked worktree resolves back to the configured control
checkout. Synesis does not copy provider metadata, profiles, hooks, identities,
or coordination state into that worktree.

An initialized root with a different project ID or Git common directory fails
closed instead of replacing the configured project. A Synesis-assigned
worktree, identified by its workspace binding or protected legacy location,
also fails closed even when it shares the same project and Git repository.

When the launcher root is not itself an initialized control checkout, existing
MCP root discovery remains available so clients can locate one unambiguous
initialized project.

## Consequences

- Codex app worktrees can join through the provider integration installed in
  their canonical control checkout.
- Provider admission remains project-local and unchanged; a genuinely missing
  integration at the control checkout is still rejected.
- A client cannot redirect an explicitly project-pinned MCP process to another
  initialized repository.
- Synesis continues to allocate and verify its own isolated worker worktree;
  the Codex app worktree does not become a Synesis mutation workspace.
- `ProjectApplicationService` discovery semantics remain unchanged for CLI and
  non-MCP callers.
