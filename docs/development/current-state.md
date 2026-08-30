# Synesis current state

**Date**: August 30, 2026
**Repository branch**: `master`
**Current baseline**: the Antigravity integration has been removed, the
repository cleanup is pushed, and this pass is reconciling active
documentation with the implemented product.

Synesis is a local-first coordination and constraint-enforcement layer for
independently running AI coding agents. The stdio MCP server exposes exactly
10 raw tools. The supported provider IDs are `claude` and `codex`; both
integrations are currently `EXPERIMENTAL`, and MCP transport evidence is tracked
separately from real provider-hook enforcement.

One persistent MCP connection owns one provider binding and one isolated worker
context. Reads are revision-bearing, patches require the matching revision, and
workspace/session authority remains isolated per provider connection.

---

## 1. Executive Summary

- The unified `:cli` distribution owns command parsing and terminal output;
  `:workspace` supplies the shared application services.
- `:project-record` owns signed SDR2 records, typed constraints, scope
  matching, and local record storage.
- `:coordination` owns durable claims, lanes, work groups, inboxes, and
  completion semantics.
- `:mcp-contract` owns the exact ten-tool catalog and wire schemas; `:mcp`
  owns stdio JSON-RPC transport and dispatch.
- Project discovery walks upward through `.synesis/project.json`; private
  identity, provider metadata, and runtime state remain under
  `.synesis/local/`.
- `synesis provider` currently exposes the canonical IDs `claude` and `codex`.
  Both are experimental: installation and synthetic checks are not proof of
  real provider trust or universal hook enforcement.

---

## 2. Module and distribution breakdown

The root Gradle build includes seven Java modules: `:link`, `:project-record`,
`:coordination`, `:workspace`, `:mcp-contract`, `:mcp`, and `:cli`.

| Module | Responsibility |
|---|---|
| `:link` | Identity, authenticated QUIC sessions, control readiness, liveness, and application streams. |
| `:project-record` | Signed SDR2 records, constraints, scope matching, local storage, and PRP1 reconciliation. |
| `:coordination` | Durable coordination protocol and work-group/lane state. |
| `:workspace` | Project/provider/session lifecycle, worktrees, guardrails, snapshots, integration, and diagnostics. |
| `:mcp-contract` | Stable raw MCP tool catalog and schemas. |
| `:mcp` | MCP stdio JSON-RPC transport and handler dispatch. |
| `:cli` | Picocli command surface, terminal rendering, installation composition, and packaging. |

The release workflow builds six development-only Java bundles and six
cross-compiled Go bootstrappers, then aggregates them into an internal
`synesis-release-candidate` artifact. No public release is claimed.

The bootstrap installation model uses a stable OS user-data root with the
launcher in `bin/`. Install/update operations stage in a sibling directory and
retain one temporary rollback root during activation. Legacy pointer and
version-directory layouts are migration-only.

### Implemented boundaries

- `:link` provides authenticated transport and session primitives.
- `:project-record` provides signed durable project records and constraints.
- `:workspace` provides local project/provider lifecycle and guardrails.
- `:mcp-contract`, `:mcp`, and `:cli` expose bounded adapters over shared
  application services.

## 3. Documented limitations and enforcement boundaries

- **Real-agent validation**: the attempted noninteractive Codex run did not
  establish project-hook trust, so real-agent enforcement remains
  `NOT_COMPLETED`.
- **Harness Integration Scope**: Synesis enforces constraints at integration points that invoke its guardrail (
  `check-action` or `hook claude`).
- **Claude Code Adapter Scope**: Enforces supported structured file-edit tools (`Edit`, `Write`, `str_replace_editor`,
  `write_file`, `file_edit`, `file_write`, `NotebookEdit`). It emits `UNSUPPORTED` diagnostics on stderr for raw
  un-parsed shell commands (`Bash`).
