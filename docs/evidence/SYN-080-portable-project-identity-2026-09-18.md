# SYN-080 portable project identity and replica model

Date: 2026-09-18<br>
Starting/final `HEAD`: `813b1f1c5c3572d895444850b1e22ad1f4cc114b`<br>
Task: `SYN-080`<br>
ADR: `docs/adr/0080-portable-project-identity-and-replica-model.md`

## Result

The bounded identity milestone is implemented in the existing Synesis source
checkout. The canonical logical identity remains the existing UUID in
`.synesis/project.json`, represented by
`org.synesis.workspace.application.project.ProjectIdentity` as UUID plus the
immutable metadata creation timestamp. Paths, names, Git state, remotes,
runtimes, providers, credentials, keys, and Link sessions are not identity.

`ProjectApplicationService` now has explicit replica and fork seams. A replica
accepts only portable metadata, creates fresh local node/profile/runtime state,
and rejects mismatched or already-present private state. `fork` is explicit and
generates a new UUID; it is intentionally bounded to a target containing only
portable metadata so private authority state is never reused. A copied ProjectId
alone does not create membership or authority.

`KnownProjectRegistry` remains discovery-only and accepts multiple validated
paths for one logical ID. Path-aware status projection prevents two local
replicas from both being reported live merely because their IDs match. The
daemon deduplicates same-ID choices for its existing ID-only selection protocol
while retaining all registry paths.

Project-facing Link consumers exchange a bounded `SPX1` context after Link
authentication. A mismatch or malformed frame is rejected before application
delegation; `sync join` now creates first-time `project.conf` only after that
context succeeds. Signed `OverlayMembershipSnapshot` remains the authority
boundary, and a membership snapshot for another project is rejected before
session admission. Core Link and the ten-tool MCP catalog were not expanded.

## Verification

All focused checks below used the bounded Windows JVM workaround
`-Djdk.net.unixdomain.tmpdir=C:\\t\\syn080-java` because the default JDK
loopback setup is unavailable on this host.

- Workspace identity, registry, context, Link-owner, and process-isolated tests:
  `:workspace:test --tests org.synesis.workspace.application.ProjectApplicationServiceTest --tests org.synesis.workspace.discovery.KnownProjectRegistryTest --tests org.synesis.workspace.application.sync.ProjectContextExchangeTest --tests org.synesis.workspace.transport.control.LinkRuntimeOwnerControlPlaneTest --tests org.synesis.workspace.application.ProjectReplicaProcessTest` — PASS.
- CLI daemon and sync process tests, including the first-time config/context
  negative case: `:cli:test --tests org.synesis.cli.UnifiedCliSyncProcessTest --tests org.synesis.cli.daemon.DaemonServerTest --tests org.synesis.cli.daemon.LocalProjectSelectionStoreTest` — PASS; the final sync class ran 6 tests.
- Same Git history/different identity: `ProjectApplicationServiceTest` clones
  one committed content history, initializes both paths independently, and
  proves equal Git parent commits with distinct ProjectIds — PASS.
- Link/authority regressions:
  `:link:test --tests org.synesis.link.overlay.OverlayProtocolTest --tests org.synesis.link.protocol.TraversalProtocolV2Test --tests org.synesis.link.session.ApplicationStreamBindingTest` — PASS.
- Project-record process regressions:
  `:project-record:test --tests org.synesis.projectrecord.sync.ProjectRecordSyncProcessTest --tests org.synesis.projectrecord.sync.ProjectReconciliationSyncProcessTest` — PASS.
- MCP contract and application catalog checks, including exactly ten tools:
  `:mcp-contract:test --tests org.synesis.mcp.contract.McpToolCatalogTest :mcp:test --tests org.synesis.mcp.application.McpToolCatalogTest` — PASS.
- Workspace gates: `formatCheck`, `staticAnalysis`, `architectureCheck`, and
  `javadoc` — PASS.
- CLI gates: `formatCheck`, `staticAnalysis`, and `javadoc` — PASS.
- `git diff --check` — PASS; Git emitted only existing LF/CRLF conversion
  warnings while inspecting the dirty Windows worktree.
- Graph refresh: Graphify re-extracted 294 workspace files, then reported
  3,959 nodes, 13,521 edges, 161 communities, and no import cycles. It also
  reports seven weakly connected nodes and 28 thin communities as graph
  navigation gaps, not build or architecture failures.

## Regression boundary and limitations

A pre-final broad invocation of workspace/CLI/MCP plus Link, project-record,
and coordination tests was red in the repository's existing environment. The
material failures were repeated `WORKTREE_NOT_REGISTERED` readiness fixtures
and JDK 25 `Path` null-environment failures; the early CLI sync failures were
old independent-project fixtures and were corrected to use lawful replicas.
The final focused CLI sync suite is green. The broad aggregate is therefore
not claimed as a clean full-suite acceptance result.

This milestone does not claim Git synchronization, full distributed
coordination, cloud/federation, Internet/NAT reachability, a clone UI, or a
session-unique project-context token beyond the current Link application-stream
handler boundary. The source checkout remains uncommitted and nothing was
published or pushed.
