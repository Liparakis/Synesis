# SYN-081 invisible canonical project metadata boundary

Date: 2026-09-18<br>
Starting/final `HEAD`: `813b1f1c5c3572d895444850b1e22ad1f4cc114b`<br>
Branch: `master`<br>
Task: `SYN-081`<br>
ADR: `docs/adr/0081-invisible-canonical-project-metadata-boundary.md`

## Result

The bounded filesystem and UX-hygiene milestone is implemented without changing
project identity, replica/fork semantics, Git ownership, Link, the MCP catalog,
daemon authority, or installer behavior. `.synesis` is the fixed project-local
Synesis metadata root on every platform. Windows receives the ordinary DOS
Hidden attribute as best-effort presentation state; Unix-like systems use the
ordinary leading-dot convention.

The new `ProjectMetadataStorage` seam owns canonical-root normalization,
no-follow validation, root creation for non-Git and replica paths, and the
single platform-specific visibility operation. `ProjectApplicationService`
re-ensures it on discovery, explicit open, initialization, replica creation,
fork, and copied-project reopen. Visibility failures return a bounded
diagnostic and are logged at `FINE`; unsafe or unavailable metadata roots are
real storage failures. Metadata bytes are never changed by visibility
normalization.

## Complete project-write inventory

The audit covered initialization, identity, membership/authority, lifecycle,
commands, claims, tasks, capabilities, WorkGroups, ownership, recovery,
runtime, provider attachment, Link/project context, daemon observation,
`KnownProjectRegistry`, diagnostics, migrations, locks, journals, caches,
temporary files, checkpoints, and generated project-side files.

| Current write surface | Owner and purpose | Scope / durability | Canonical location and disposition |
| --- | --- | --- | --- |
| `.synesis/project.json` | `ProjectApplicationService` and `ManagedBaselineTransactionService`; portable logical project identity | portable, durable, Synesis-internal | Remains the sole canonical project metadata file under `.synesis`; atomic temporary is a sibling inside `.synesis` |
| `.synesis/local/profile/link/**` | Link `IdentityBootstrap`; node key and identity material | node-local, durable, private | Already canonical under `.synesis/local`; no migration |
| `.synesis/local/profile/project.conf` and `records/**` | project membership/provider profile and local records | node-local, durable | Already canonical under `.synesis/local`; no migration |
| `.synesis/local/providers/**` | provider installation/attachment metadata | node-local, durable | Already canonical under `.synesis/local/providers`; no migration |
| `.synesis/local/runtime/**`, workspace binding, repair/speculation markers | runtime, assigned-workspace, and recovery materialization state | runtime or node-local, bounded durable markers | Already canonical under `.synesis/local`; no migration |
| `.synesis/shared/records/**` | shared project records initialized by workspace application | project-local, durable | Already canonical under `.synesis/shared`; no migration |
| `.synesis/coordination/**`, including event storage and coordination locks | `PredictionEventStore` and higher-level coordination services; claims, tasks, capabilities, WorkGroups, ownership, lifecycle projections | project-local, durable | Already canonical under `.synesis/coordination`; no migration |
| Project metadata atomic temps such as `project.json.tmp-*` | `ProjectApplicationService` metadata replacement | ephemeral | Sibling of the metadata file inside `.synesis`; cleanup remains bounded |
| Baseline/index and transaction journals | `ManagedBaselineTransactionService`, Git private index, transaction locks/journals | host/process or repository administration; durable only for recovery | External administrative state under the host Synesis state/workspace roots; the system-temp `synesis-baseline-*.index` is outside the checkout and is deleted after use |
| Session leases, lifecycle locks, recovery/migration/provider journals | lease, migration, cleanup, repair, and provider lifecycle services | host-level administration, durable recovery state | External `%LOCALAPPDATA%\\Synesis\\...` on Windows or the existing Unix state equivalent; not project-internal metadata and not moved |
| Integration, validation, and dedicated worktrees | integration/validation workspace services | ephemeral or durable until cleanup; external | External `%LOCALAPPDATA%\\Synesis\\workspaces\\<project-id>\\...` or the existing Unix equivalent; path containment checks remain in force |
| `.git/info/exclude` | Git-owned private exclusions, including `/.synesis/local/` and `/.synesis/coordination/` | Git administration | Explicit Git exception; Git semantics are unchanged and `.synesis` was not blindly added to `.gitignore` |
| `AGENTS.md` managed section | visible user-facing Synesis integration contract | user-facing, durable | Explicit visible exception; unrelated user text is preserved. It is not bookkeeping to hide |
| `.codex/hooks.json`, `.claude/settings.json`, `.mcp.json`, and provider-global configuration | provider-owned integration/configuration contracts used by Claude/Codex | provider-facing, visible or user-home state | Explicit provider integration exception. These paths are not Synesis project identity/authority storage and were not relocated speculatively |
| User files, agent output, build/test artifacts, diagnostics | user/project/tool ownership | ordinary project content | Remain in their existing visible locations; no process-based blanket hiding was added |

No loose Synesis-owned root-level state file, lock, node identity, recovery
record, journal, or metadata cache was found in the source checkout. The only
root-level Synesis-created content retained by design is the visible
`AGENTS.md` integration section and provider integration surfaces described
above. No legacy root-file migration was therefore required, and there are no
two independently writable authoritative copies to reconcile.

## Storage and lifecycle contract

`ProjectMetadataStorage.ensure` validates the project root and `.synesis` with
`LinkOption.NOFOLLOW_LINKS`, rejects a metadata-root symlink/reparse-like
unsafe object rather than following an external target, validates an existing
`project.json` as a regular file, and applies only the standard DOS Hidden
attribute when a DOS view exists. The implementation does not use shell
`attrib`, System attributes, ACLs, encryption, or Explorer changes.

| Operation | Visibility behavior | Identity/private-state behavior |
| --- | --- | --- |
| New non-Git fixture / initialization | Seam creates and normalizes `.synesis` before metadata write; final ensure rechecks it | Existing metadata writer and fresh local state semantics remain unchanged |
| New Git initialization | Existing managed baseline transaction creates/stages the exact `.synesis/project.json`; seam normalizes the resulting root after the transaction | Baseline safety and Git tracking remain unchanged |
| Existing project open, `locate`, `require`, attach | Seam re-ensures Hidden or returns a diagnostic-only visibility result | ProjectId, creation time, node identity, membership, claims, tasks, and authority are not rewritten |
| Replica creation | Fresh target goes through the seam before portable metadata and fresh private state | Same ProjectId, new node/private state; visibility is local presentation state |
| Explicit fork | Existing target is normalized before replacement and again after fresh metadata | New ProjectId by explicit fork only; no coupling to Hidden state |
| Copy/relocation/reopen | Opening the new path re-ensures the local platform policy | Copied portable metadata preserves identity; missing Hidden is not corruption |
| Migration/recovery/restore | Supported migration resolves through `ProjectApplicationService.require`; metadata replacement stays below `.synesis`, whose parent policy is re-ensured on reopen | Existing migration authority and recovery checks remain authoritative |
| Unix-like host | DOS view is absent; no extra mutation is performed | `.synesis` remains an ordinary dot-directory |

The service does not set Hidden on children. It does not serialize or copy the
attribute as project state. A visibility failure is diagnostic-only, while
metadata-root creation, validation, symlink, and metadata-file failures remain
fail-closed storage errors.

## Visibility, security, and content boundaries

Hidden state is not read by authentication, authorization, identity parsing,
membership validation, integrity checks, `KnownProjectRegistry`, daemon
selection, MCP attachment, Git policy, or Link project-context logic. The
storage class has no identity, authority, membership, or security dependency;
the architecture test asserts that `DosFileAttributeView` has one production
owner in the filesystem infrastructure package.

The root-hygiene test creates `src/CreatedByAgent.java` and confirms that the
source file, `src`, and visible `AGENTS.md` remain visible while no loose
Synesis temp/lock artifact is emitted at the project root. Ownership and
purpose, not the process that created a file, determine whether it belongs in
`.synesis`.

## Verification

All JVM checks used the bounded host workaround
`-Djdk.net.unixdomain.tmpdir=C:\\t\\syn081-java`; this is an environment
workaround, not a product setting.

- Focused storage, initialization/open, replica, architecture, format, static
  analysis, and Javadoc checks — PASS:
  `:workspace:formatCheck :workspace:staticAnalysis :workspace:architectureCheck :workspace:javadoc :workspace:test --tests org.synesis.workspace.application.ProjectApplicationServiceTest --tests org.synesis.workspace.infrastructure.filesystem.ProjectMetadataStorageTest --tests org.synesis.workspace.application.ProjectReplicaProcessTest`.
- Affected workspace/CLI/MCP regression set — PASS (`BUILD SUCCESSFUL`, 2m 9s):
  `ProjectApplicationServiceTest`, `ProjectMetadataStorageTest`,
  `ProjectReplicaProcessTest`, `KnownProjectRegistryTest`,
  `ProjectContextExchangeTest`, `LinkRuntimeOwnerControlPlaneTest`,
  `UnifiedCliSyncProcessTest`, `DaemonServerTest`,
  `LocalProjectSelectionStoreTest`, `mcp-contract.McpToolCatalogTest`, and
  `mcp.application.McpToolCatalogTest`.
- CLI gates — PASS: `:cli:formatCheck :cli:staticAnalysis :cli:javadoc`.
- `git diff --check` — PASS; only normal Windows LF/CRLF conversion warnings
  were emitted while inspecting the intentionally dirty worktree.
- Graphify refreshed the workspace graph after the final production change:
  3,990 nodes, 13,598 edges, 180 communities, and no import cycles. The
  graph report notes 36 thin communities and seven weakly connected nodes as
  navigation observations, not build failures.
- Windows attribute behavior is exercised conditionally through the JDK DOS
  attribute view. On this host the DOS view is available; tests verify create,
  clear-and-reensure, copied metadata, and diagnostic-only failure behavior.
  Unix behavior is covered by the no-DOS-view seam test.
- Symlink safety is covered by a test that refuses a `.synesis` symlink without
  creating metadata in its external target; platforms that deny test symlink
  creation skip that fixture rather than weakening the production check.
- The earlier SYN-080 Link/overlay, project-record, process-isolated identity,
  and MCP ten-tool catalog evidence remains valid; SYN-081 did not change
  those modules or contracts. The repository-wide aggregate remains subject
  to the previously recorded `WORKTREE_NOT_REGISTERED` readiness fixtures and
  JDK 25 null-`Path` environment failures, so it is not called fully green.

## Explicit answers

1. **Is every Synesis-owned project-internal artifact now under `.synesis`?**
   Yes for project-internal Synesis metadata found by the audit. The exact
   exceptions are host-level administrative/workspace/recovery state, Git's
   `.git/info/exclude`, visible `AGENTS.md`, and provider-owned integration
   configuration (`.codex`, `.claude`, `.mcp.json`, and user-home provider
   files). They have separate ownership or user-facing/provider semantics and
   were not moved into the project metadata boundary.

2. **How is Windows Hidden applied or re-applied?** The JDK
   `DosFileAttributeView` is called by the one storage seam after root creation
   and on every supported open/discovery, init, replica, fork, and copied-path
   reopen. Recreated roots are normalized again; cross-platform copies are
   normalized when opened locally. Unsupported/denied attribute operations
   return `HIDDEN_APPLY_FAILED` diagnostics without changing valid metadata.

3. **What guarantees visibility is not authentication/authorization/identity/integrity?**
   Hidden is derived presentation state, never serialized or copied as authority
   state. The storage seam has no security or identity dependency, the existing
   identity/membership/integrity paths remain unchanged, and the architecture
   test enforces one isolated platform-attribute owner.

4. **How are bookkeeping and real project files distinguished?** The storage
   boundary is selected by ownership and path contract: Synesis records and
   runtime state use `.synesis`; provider integration and visible `AGENTS.md`
   retain their explicit contracts; files such as `src/CreatedByAgent.java`,
   docs, builds, tests, and diagnostics remain ordinary project content. The
   root-hygiene test verifies this distinction.

5. **Can visibility, relocation, copying, fork, daemon reopen, or MCP attach change ProjectId?**
   No. Clearing or reapplying Hidden changes no metadata bytes; copy/relocation
   reopen preserves the portable identity; replica and fork retain their
   established SYN-080 semantics; daemon and MCP use normal path/metadata
   discovery and do not consult visibility. A new ProjectId occurs only through
   the explicit fork operation.

## Checkpoint and repository state

- No local commit was created for SYN-081; `HEAD` remains the starting SHA.
- No push, publish, tag, release, or remote mutation was performed.
- Durable checkpoint: `docs/agent/checkpoints/CP-0973.md`.
- The worktree intentionally remains dirty with the prior SYN-080 slice and
  the SYN-081 implementation/evidence/docs; unrelated existing changes were
  preserved.
- The JVM workaround directory `C:\\t\\syn081-java` remains an external
  test-environment artifact because the host would not permit cleanup while
  installed processes were active; it is outside this repository and is not
  project metadata.
