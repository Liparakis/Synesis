# SYN-041 terminal-disconnect trigger — CP-0567

Date: 2026-08-29
Result: RESULT C — both missing abnormal finalization and insufficient
clean-close ownership were causal

## Boundary proven

CP-0565 fixed the persisted-terminal-history overwrite, but the real provider
teardown never durably entered `TERMINAL_DISCONNECTED`. The remaining causal
boundary was the production abnormal-runtime finalization trigger.

The current topology is:

```text
Codex -> synesis-mcp.exe -> packaged Java MCP runtime
```

The lease is written by `SessionLeaseService.createOrRenewLease` in the Java
MCP process and records the packaged Java PID and process identity. There is no
long-lived Synesis observer for an MCP process launched externally by Codex.
`ProviderSupervisionService` observes only processes launched through its own
`ProviderProcessSupervisor`; it is not on the Codex MCP path. Therefore an
external hard termination can leave the durable lease at
`TERMINAL_AUTHORITY_CONFIRMED` without any callback running in Java.

## Writer call graph

The sole production writer is:

```text
SessionLeaseService.markTerminalDisconnected(controlRoot, connectionId, pid)
  -> ProjectAppendLock.acquire(.synesis/coordination)
  -> SessionLeaseStore.load
  -> require matching persisted PID
  -> require TERMINAL_AUTHORITY_CONFIRMED
  -> SessionLeaseStore.save(TERMINAL_DISCONNECTED, preserving metadata/heartbeat)
```

Its production callers are now:

```text
McpStdioServer.run catch(Throwable)
  -> McpProtocolHandler.closeAbnormally()
  -> markTerminalDisconnected(current MCP Java PID)

ProviderSupervisionService process-exit callback
  -> markTerminalDisconnected(supervised provider PID)

McpProtocolHandler.close() from a later process
  -> SessionLeaseService.markClosedCleanly(..., current MCP Java PID)
  -> if PID differs from persisted runtime PID:
       inspect persisted runtime with ProcessInspector
       if NOT_OBSERVED, PID_REUSED_OR_MISMATCHED, or
          PROCESS_EVIDENCE_UNAVAILABLE:
         save TERMINAL_DISCONNECTED under the same append lock
       refuse clean close
```

The first two paths are synchronous push-style finalization when the owning
process or existing Synesis supervisor receives an observable failure. The
last path is the bounded pull-style fallback at the first later close when no
external observer survived the MCP process. Doctor and ordinary liveness reads
remain derived/read-only; they are not the durable writer.

## Close-authority finding

Before this slice, `McpProtocolHandler.close()` called the connection-ID-only
`markClosedCleanly(Path, String)`. A rejected probe reused the original
connection ID, so its EOF could rewrite the original lease even though its
process was different. Session ID alone was not a sufficient transport
authority proof. The existing persisted connection ID, PID, executable, start
time, and nonce provide the available narrow identity evidence; this slice uses
the existing PID-gated overload and does not add generalized identity
architecture.

The owning process's clean close still wins when its PID matches and the lease
is not already terminally finalized. A different process cannot clean-close a
live tracked terminal runtime. If that tracked runtime is absent or mismatched,
the close path records the historical abnormal outcome before refusing the
foreign clean close. Active nonterminal leases retain their stale/recovery
semantics.

## Implementation

Changed production paths:

- `mcp/src/main/java/org/synesis/mcp/application/McpProtocolHandler.java` —
  pass the current MCP PID to clean close and expose the bounded abnormal
  finalizer used by the stdio transport.
- `mcp/src/main/java/org/synesis/mcp/transport/stdio/McpStdioServer.java` —
  invoke abnormal finalization on a transport-loop failure.
- `workspace/src/main/java/org/synesis/workspace/lifecycle/lease/SessionLeaseService.java` —
  classify a dead persisted terminal runtime before rejecting a foreign clean
  close, using the existing process inspector and append lock.

Regression coverage:

- `workspace/src/test/java/org/synesis/workspace/lifecycle/lease/TerminalLeaseStateTest.java`
  covers dead/live tracked runtimes, wrong PID refusal, metadata preservation,
  wrong connection refusal, replay, and concurrent finalization behavior.
- `mcp/src/test/java/org/synesis/mcp/application/McpServerTest.java` covers a
  stdio transport failure durably writing `TERMINAL_DISCONNECTED`.

No MCP tool, Doctor rule, terminal-seal rule, SYN-039 behavior, SYN-040 wake
behavior, provider migration, or generalized runtime identity model changed.

## Packaged provider-independent acceptance

Fresh disposable fixture:

- fixture: `C:\t\syn041-packaged-cp0567-20260829-002`
- project: `a8558f1a-e561-4e2d-b099-278fd8a25637`
- connection: `cp0567-original-connection-4`
- official bundle: `cli/build/platform-bundle/synesis-0.1.0-dev.local-windows-x64`

The official packaged MCP completed terminal no-change with
`SESSION_TERMINATED` and terminal fence sequence 14. The persisted Java PID
was 2572. That exact Java process was forcibly terminated; the launcher later
exited with code -1, and the PID was absent. A separate packaged probe used the
same connection ID, received `SESSION_TERMINAL`, then closed its input cleanly
and exited 0. Its Java process was different (20200). The final raw lease was
`TERMINAL_DISCONNECTED`; the original Java PID, executable, command line,
start time, nonce, timestamps, and session identity were preserved.

This acceptance used the production packaged MCP path and did not manually
write the terminal-disconnect state. The disposable harness and output are
outside the repository under the fixture directory.

## Verification

- workspace `TerminalLeaseStateTest`: PASS.
- MCP stdio abnormal-finalization test: PASS.
- MCP no-change, terminal-seal, and packaged boundary tests: PASS.
- SYN-039 rejected-continuation regression: PASS.
- provider supervision and Codex wait-control regressions: PASS.
- MCP package architecture and exact tool catalog: PASS; tool count 10.
- `:workspace:javadoc :mcp:javadoc`: PASS.
- `:cli:platformBundle :cli:bundleSmokeTest --rerun-tasks`: PASS.
- packaged provider-independent production-path acceptance: PASS.
- Doctor after acceptance: `DEGRADED`, 0 critical, 0 errors, 2 unrelated
  command-namespace warnings, 0 mutations; no `STALE_SESSION_LEASE` and no
  `DURABLE_STATE_AMBIGUOUS`.
- `git diff --check`: PASS with only Git line-ending normalization warnings.
- deferred-register validator: PASS (9 entries).

The full `McpServerTest` selection and combined SYN-039 selection were also
attempted, but the host timed out before producing a result; they remain
incomplete, not passed. No real Codex run was performed after this fix. The
single CP-0566 real Codex run occurred before this implementation slice and is
documented separately.

SYN-041 remains ACTIVE. Nothing was committed, pushed, tagged, released, or
published.
