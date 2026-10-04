# ADR-0087 — Resolve project context per MCP connection

## Status

Accepted on 2026-10-04.

## Context

Codex uses one user-level Synesis MCP registration across project chats. A
project root in that shared registration makes the MCP server depend on which
project last installed or updated it. Codex creates a separate MCP connection
for each chat, and a connection can provide its own workspace roots. The
server needs to bind each connection to one initialized project without
letting it change projects after session binding.

Removing `--project` from the MCP CLI also affects project-local provider
entries. Those entries still need a trusted way to retain their configured
project root when a client supplies no roots, while preventing supplied roots
from redirecting an explicitly pinned connection.

## Decision

- The global Codex registration launches `mcp --provider codex` without a
  project argument. Each connection resolves one initialized project from its
  MCP workspace roots, falling back to the process working directory when
  roots do not identify one. The selected project is fixed after session
  binding; a connection does not switch between projects.
- The MCP CLI rejects the removed `--project` argument. Ordinary Synesis CLI
  commands that operate on an explicit project keep their own `--project`
  options.
- Claude's project-local `.mcp.json` passes its trusted project root through
  the server `env.SYNESIS_MCP_PROJECT` setting. Managed Codex launches use the
  same environment variable for their trusted project root.
- Provider hooks and project metadata remain project-local. Removing or
  reinstalling one project's Codex hook does not retarget the shared MCP
  registration. `provider uninstall codex --global` removes only that shared
  registration.

This narrows ADR-0051: its pinned-root checks continue to govern launches with
an explicit trusted project root. Ordinary user-level Codex MCP connections use
their own workspace context instead of inheriting the project that installed
the integration.

## Consequences

- One user-level MCP registration can serve concurrently open Codex chats in
  different initialized projects, subject to each project's own provider
  installation and session readiness.
- A project-local provider configuration can preserve its root without
  passing `--project` through the MCP CLI.
- MCP tools remain scoped to the project selected for that connection; a new
  chat or connection resolves its own project context.

## Verification

The MCP protocol tests cover root discovery, root immutability after session
binding, and connections that list tools before project resolution. Provider
configuration tests cover the unpinned Codex entry and the trusted project
environment written for Claude. `:cli:distributionCheck` exercises the
packaged Codex MCP launcher from a project working directory without
`--project`.
