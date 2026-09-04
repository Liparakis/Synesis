# SYN-051 generation-1 persistence boundary implementation — 2026-09-04

This is the bounded production implementation record for the provider
persistence boundary selected by ADR-0064. It does not claim fresh managed
runtime acceptance, Worker B isolation, A1/A2 restart acceptance, or full
task-tracker acceptance.

## Scope and outcome

The separate first-generation bootstrap handoff was removed from the managed
launcher. `prepareFirst` now creates a pending proof scope with no thread ID.
The managed App Server itself calls `thread/start`; the lifecycle then
acquires unique `(provider, thread)` ownership for the exact returned ID,
broker-verifies it, binds it to the pending attachment, and activates the
attachment. The existing early MCP transport remains quarantined and is
promoted only after this exact sequence.

Provider-thread ownership now records `persistenceReady` durably. Acquisition
starts provisional. Same-process read, graceful App Server exit, and
`thread/start` do not set the flag. The managed lifecycle sets it only after
the trusted exact `turn/completed` notification for the active matching
thread, binding, and generation. The transition is idempotent and remains
owned by the provider/thread record rather than an attachment generation.

Successor resume and trusted-death replacement are blocked while the owner is
provisional. They are eligible only after `persistenceReady=true` and the
existing trusted death receipt/generation fences pass. Already-bound successor
finalization is idempotent for the exact pinned thread; mismatches fail
closed. Legacy ownership records without the new field load as provisional.

## Changed production surface

- `ProviderThreadOwnershipRecord` and `ProviderThreadOwnershipStore`: schema
  2 persistence readiness, exact scoped update, legacy-provisional read, and
  preserved readiness on release.
- `ManagedAttachmentRecord`, `ManagedAttachmentStore`, and
  `RuntimeAuthenticator`: pending first-generation records can carry no
  thread selector until trusted binding.
- `ManagedAttachmentService`: pending issue/bind, exact pending admission,
  idempotent binding, and provisional replacement rejection.
- `ManagedCodexProcessLauncher`: managed first-generation `thread/start`,
  broker acquisition, finalization, activation refresh, and completion-boundary
  persistence update.
- `CodexAppServerLifecycleService`: finalize-before-activate ordering and
  trusted completed-turn callback; ordinary launchers retain no-op defaults.

## Verification

Starting repository state was HEAD
`4a2df6480bec9b2d0d34995214b7ef4686e6cf82` on `master`, clean and 45 commits
ahead of `origin/master`. No `.synesis` state, credentials, historical
fixture, Worker B, or remote repository was changed.

Passed focused command:

```text
TEMP=C:\\t TMP=C:\\t .\\gradlew.bat :workspace:test
  --tests ProviderThreadOwnershipStoreTest
  --tests ManagedAttachmentServiceTest
  --tests CodexLifecycleWaitControlTest
  --tests ManagedCodexThreadBrokerTest
  --tests CodexAppServerProtocolClientTest
  --tests CodexAppServerProtocolSchemaTest
  --no-daemon --max-workers=1
BUILD SUCCESSFUL
```

The lifecycle regression proves `verify → finalize → activate` ordering and
that only a completed event reaches the persistence callback. Ownership,
attachment, replacement, broker, protocol, and lifecycle focused tests pass.
MCP test sources compile. The combined workspace/MCP test task and a selected
MCP package test task both stalled after entering their test task and were
stopped; they are incomplete evidence, not passes.

Clean build/install command:

```text
TEMP=C:\\t TMP=C:\\t .\\gradlew.bat clean :cli:installDist
  --no-daemon --max-workers=1 --console=plain
BUILD SUCCESSFUL
```

The installed launcher reports `SYNESIS_VERSION=0.1.0-dev.local`,
`RECORD_FORMAT=SDR2`, `BUILD_PLATFORM=windows-x64`, and
`JAVA_RUNTIME=25+36-LTS`. The build was run before the final source commit,
so its generated `BUILD_COMMIT` field was `UNKNOWN`; the hashes below are the
matching produced/installed artifacts from that clean source tree.

| Artifact | SHA-256 |
|---|---|
| `workspace/build/libs/workspace-0.1.0-SNAPSHOT.jar` | `e66e85afda2883da6926c3ed2cf5fd43de18db20e1c83b92840ef2a3c8b2528e` |
| `mcp/build/libs/mcp-0.1.0-SNAPSHOT.jar` | `dddc6df0decc0530dc30d6809ded118b9cf0e801ad7238b73aa53ab90bae0bfd` |
| `cli/build/libs/cli-0.1.0-SNAPSHOT.jar` | `aa9067c9f10df121b05e4d9751512668cf1bdd5bd93a45cf020e0e3b1fba33e2` |
| `cli/build/install/synesis/lib/workspace-0.1.0-SNAPSHOT.jar` | `e66e85afda2883da6926c3ed2cf5fd43de18db20e1c83b92840ef2a3c8b2528e` |
| `cli/build/install/synesis/lib/mcp-0.1.0-SNAPSHOT.jar` | `dddc6df0decc0530dc30d6809ded118b9cf0e801ad7238b73aa53ab90bae0bfd` |
| `cli/build/install/synesis/lib/cli-0.1.0-SNAPSHOT.jar` | `aa9067c9f10df121b05e4d9751512668cf1bdd5bd93a45cf020e0e3b1fba33e2` |
| `cli/build/install/synesis/bin/synesis.bat` | `b8bcb137eb83659360f332c6ca6c17b328300844f83f28f82977cdec56c0670e` |

## Acceptance boundary

This slice is **PARTIAL**. The implementation and focused verification are
complete for the bounded source boundary, and build/install artifact
provenance passed. Full package-wide test completion and fresh managed
runtime validation remain unrun. No credentials were read, copied, logged,
or modified; no raw proof is persisted or placed in global Codex config; no
new MCP tool was added; the catalog remains exactly 10 tools.

Exact next action: checkpoint and commit this slice, then stop. Fresh
single-worker managed runtime validation may be separately authorized.
