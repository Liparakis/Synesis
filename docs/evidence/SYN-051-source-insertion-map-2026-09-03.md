# SYN-051 source insertion map — 2026-09-03

## Scope

This is the pre-edit source map for the bounded stock-Codex
`MANAGED_CONTINUITY` implementation. It confirms the smallest existing seams
and records what is not present. No production behavior is changed by this
record.

## Verified current seams

| Concern                  | Existing source boundary                                                                                 | Implementation implication                                                                                                                                                                                                           |
|--------------------------|----------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Exact Synesis authority  | `workspace.application.provider.SessionAuthorityResolver` and `ProviderSessionBindingService`            | Keep exact connection matching and `BOUND`/terminal checks. No latest-session or thread fallback.                                                                                                                                    |
| Durable logical identity | `ProviderSessionBindingService.Binding` plus collaboration `Participant`/`WorkIntent` projections        | Managed attachment must reference the existing binding/session and must not create a second identity graph.                                                                                                                          |
| Codex process owner      | `workspace.lifecycle.codex.ProjectRuntimeHost`                                                           | Extend the existing long-lived project host rather than add a second owner. It already owns binding-scoped runtime instances, locks, checkpoints, and reconciliation.                                                                |
| Codex process protocol   | `CodexAppServerLifecycleService`                                                                         | Reuse its exact-thread start/resume/read path and execution-time fencing. Its `ProcessLauncher` is the provider-specific managed launch seam.                                                                                        |
| Durable Codex lifecycle  | `CodexLifecycleStateStore.Checkpoint`                                                                    | Existing `attachmentGeneration`, `connectionGeneration`, thread, process, revision, and state fields remain lifecycle state. Managed attachment metadata should be adapter-private and separate from participant/intent/claim state. |
| Provider configuration   | `workspace.provider.codex.CodexTomlConfiguration` and `ProviderMcpConfigurationService`                  | Managed mode must explicitly generate the Codex App Server configuration and selected `env_vars`; ordinary provider configuration remains unchanged.                                                                                 |
| Provider status/manuals  | `ProviderApplicationService`, `ProviderManualService`, `ProviderRegistry`, and `docs/providers/codex.md` | Add an explicit managed mode/reporting path without relabeling ordinary `SESSION_BOUND` behavior.                                                                                                                                    |
| CLI lifecycle exposure   | `cli` provider commands and `CoordinationServeCommand`                                                   | Use existing CLI/runtime wiring for managed launch and status; do not add an MCP tool or a second daemon.                                                                                                                            |

## Verified absence

There is no current `RuntimeAuthenticator`, `CodexManagedAdapter`,
`MANAGED_CONTINUITY` mode, managed runtime-home abstraction, managed attachment
proof store, or managed reattachment command. `ProjectRuntimeHost` currently
uses project-local `.synesis/local/runtime/codex-lifecycle` and its default
launcher injects fixed binding/project/provider values into the App Server
command. The current lifecycle checkpoint is not a managed attachment record:
it contains process/thread lifecycle state but no proof hash, runtime-home
identity, provider mode, or adapter-private attachment status.

The current Codex TOML writer emits a static `[mcp_servers.synesis]` entry with
command and args only. The stock-Codex feasibility evidence proves that a
dedicated runtime home and an allow-listed `env_vars` entry can carry a
worker-specific value, but the production source does not yet create or retain
such homes, generate proofs, or authenticate a replacement attachment.

## Bounded insertion order

1. Add provider-neutral, versioned managed attachment state and a generic
   authentication/reattachment service beside the existing binding authority.
2. Add Codex-specific runtime-home, proof-delivery, authentication, and
   `ProcessLauncher` adapter behavior behind that service.
3. Thread managed mode through the existing project runtime host and CLI/status
   surfaces without changing ordinary provider operation.
4. Add focused tests for proof hygiene, generation fencing, exact thread
   identity, restart, race, terminal, and ordinary-mode regression before real
   Codex acceptance.

## Non-goals confirmed by the map

No Claude implementation, anonymous continuity, upstream Codex change, shared
App Server, arbitrary fleet launch, latest-session inference, Review/Doctor
redesign, new MCP tool, historical fixture repair, `.synesis` metadata edit, or
worktree copying is justified by this map.
