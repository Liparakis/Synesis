# SYN-041 terminal transport-history preservation — 2026-08-29

## Scope and result

This is one narrowly scoped implementation slice for CP-0562. It fixes the
proven overwrite in which a later clean close from a rejected same-session
rebind changed a durably abnormal terminal lease to `CLOSED_CLEANLY`.

SYN-041 remains ACTIVE. No new provider experiment was run. SYN-039 and
SYN-040 semantics were not reopened, no generalized identity or provider
migration was introduced, and the MCP catalog remains exactly 10 tools.

## Causal source trace

Before this slice, `McpProtocolHandler.close()` called the no-argument
`SessionLeaseService.markClosedCleanly(Path, String)` for every connection
close. That method loaded the lease and unconditionally wrote
`CLOSED_CLEANLY`; it did not distinguish the original process owner from a
later rejected connection using the same connection/session evidence.

The durable lease is keyed by the persisted project/provider/connection
binding and contains the session and process metadata in one
`SessionLeaseRecord`. `SessionLeaseStore.save()` atomically replaces the JSON
file, but it is not a compare-and-swap revision protocol. The existing
project append lock is therefore the smallest available serialization point.

The fix uses that existing `ProjectAppendLock` for clean and abnormal terminal
finalization. Clean close reloads the durable lease under the lock and refuses
to write either finalized transport state. The abnormal supervisor callback
records
`TERMINAL_DISCONNECTED` only when the expected PID still owns a
`TERMINAL_AUTHORITY_CONFIRMED` lease. Terminal authority confirmation remains
inside the terminalization service's existing append-locked transaction, so
the new method does not nest the lock.

## Transition matrix

| Persisted state                | Clean close by original owner                   | Clean close by rejected/different owner | Abnormal finalizer                                                 |
|--------------------------------|-------------------------------------------------|-----------------------------------------|--------------------------------------------------------------------|
| `ACTIVE`                       | `CLOSED_CLEANLY`                                | no-op                                   | no terminal transition; existing stale/recovery evaluation remains |
| `TERMINAL_AUTHORITY_CONFIRMED` | `CLOSED_CLEANLY` (lawful clean first finalizer) | no-op                                   | `TERMINAL_DISCONNECTED` when expected PID matches                  |
| `TERMINAL_DISCONNECTED`        | unchanged                                       | unchanged                               | unchanged, idempotent                                              |
| `CLOSED_CLEANLY`               | unchanged                                       | unchanged                               | no-op                                                              |

The terminal-disconnected state is now history-preserving and monotonic. The
first serialized lawful finalizer wins: a clean close may legitimately close
an otherwise confirmed terminal lease, while a durable abnormal finalization
cannot subsequently be rewritten by cleanup. A rejected same-session probe
can still close its own handler resources, but its cleanup observes the
already-finalized durable state and cannot mutate it.

## Preserved invariants

- Session-vs-connection semantics remain separate: the persisted lease still
  belongs to the exact provider binding/connection record, while terminal
  authority and the terminal event remain exact-session fences.
- Terminal abnormal finalization requires the existing terminal-authority
  proof and the supervised process PID; active leases are not relabeled
  terminal merely because a process exits.
- `TERMINAL_DISCONNECTED` replay is idempotent, and repeated rejected clean
  closes are no-ops.
- All existing lease metadata is copied unchanged for abnormal finalization:
  project, provider, connection, worker, session, process identity,
  Synesis version, creation time, and heartbeat. The abnormal transition does
  not refresh the heartbeat. Clean-close timestamp behavior remains as before.
- Doctor does not call a new cleanup mutation and does not suppress stale or
  ambiguous findings. The focused terminal case remains free of stale,
  ambiguous, and durable-state-ambiguous findings without mutation.
- Existing unsealed liveness behavior remains unchanged: missing or mismatched
  active processes still use stale/recovery classification, while a confirmed
  terminal session cannot be recovered.

## Implementation and regression coverage

Changed production files:

- `workspace/src/main/java/org/synesis/workspace/lifecycle/lease/SessionLeaseService.java`
- `workspace/src/main/java/org/synesis/workspace/application/provider/ProviderSupervisionService.java`
- `mcp/src/main/java/org/synesis/mcp/application/McpProtocolHandler.java`

Changed tests:

- `workspace/src/test/java/org/synesis/workspace/lifecycle/lease/TerminalLeaseStateTest.java`
- `mcp/src/test/java/org/synesis/mcp/application/McpSyn039NoChangeCompletionTest.java`

The focused lease suite covers terminal-authority clean close, abnormal
terminalization, metadata preservation, replay, active-state refusal,
durable-state rejection, concurrent clean/abnormal first-finalizer
serialization, and non-recoverable terminal liveness. The MCP regression
seals a terminal session, records abnormal transport history, performs a
same-connection rejected `ensure_session`, closes the rejected handler, and
asserts exact durable-record equality afterward.

## Verification evidence

Successful serialized checks used `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\\tmp`
to avoid the host's default loopback/Unix-domain temporary-directory failure:

- `:workspace:test --tests TerminalLeaseStateTest --tests LeaseTest --tests ProviderSessionTerminalizationServiceTest`
- `:workspace:test --tests ProviderProcessSupervisorTest`
- `:mcp:test --tests McpSyn039NoChangeCompletionTest`
- `:mcp:test --tests McpSyn039RejectedContinuationTest`
- `:mcp-contract:test --tests McpToolCatalogTest`
- `:workspace:test --tests CodexLifecycleWaitControlTest --tests AgentNextActionServiceTest`
- `agent-validate-deferred.ps1`
- `git diff --check`
- `:workspace:javadoc :mcp:javadoc :cli:platformBundle :cli:bundleSmokeTest`
- fresh `:cli:platformBundle :cli:bundleSmokeTest --rerun-tasks`

All listed checks passed. A broader combined MCP selection and individual
`McpSyn039SliceTest`/`McpServerTest` attempts were stopped after process-heavy
test timeouts with no assertion failure output; they are incomplete, not
passes. The initial Gradle attempts also failed before test execution because
the host could not establish its default loopback connection; the explicit
temporary-directory setting resolved that environment issue.

## Packaged provider-independent acceptance

The fresh official platform bundle was exercised against disposable fixture
project `4a84c9a9-a87d-40b1-a102-e301a2d9d7ad` using the bundled CLI/MCP
processes, without Codex. Both MCP processes reported `TOOLS=10`. The first
process completed lawful no-change terminal finish with
`SESSION_TERMINATED`, fence sequence 6, and was forcibly ended without stdin
close. A disposable Java helper called the packaged lease service and changed
the exact lease to `TERMINAL_DISCONNECTED`, preserving its raw metadata. A
second same-connection packaged MCP process returned
`{"state":"SESSION_TERMINAL"}` from `ensure_session`; its probe exited 0.
Doctor returned `DEGRADED` with 2 warnings, 0 errors, 0 critical findings,
0 mutations, and no cleanup or reconciliation recommendation. The final raw
lease remained exactly `TERMINAL_DISCONNECTED` after rejected-probe cleanup.
The helper and both disposable fixtures were removed afterward.

This is packaged lifecycle and lease-fencing evidence only. No real Codex
provider experiment was run for this slice.

## Boundary and repository state

The five pre-existing unrelated lifecycle files remain untouched:

1. `workspace/src/main/java/org/synesis/workspace/lifecycle/GitProcessRunner.java`
2. `workspace/src/main/java/org/synesis/workspace/lifecycle/ProcessCommandRunner.java`
3. `workspace/src/main/java/org/synesis/workspace/lifecycle/RepositoryPortabilityService.java`
4. `workspace/src/test/java/org/synesis/workspace/lifecycle/ProcessCommandRunnerTest.java`
5. `workspace/src/test/java/org/synesis/workspace/lifecycle/RepositoryPortabilityServiceTest.java`

No commit, push, tag, release, or publish was performed. The existing dirty
SYN-041 worktree is preserved for the user; this slice adds only the files
listed above plus its evidence/checkpoint documentation.
