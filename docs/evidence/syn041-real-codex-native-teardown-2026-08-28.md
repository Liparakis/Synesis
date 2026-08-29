# SYN-041 native Codex-to-MCP teardown validation

Date: 2026-08-28
Starting HEAD: `f5622eba03c7631a7e3c8620a5598e8037ded001`
Branch: `master`

## Scope and topology

This was the final authorized native transport probe. The configured MCP
command was the official bundled executable directly; no wrapper, proxy,
forwarding process, custom stdio bridge, or observer-owned handle was between
Codex and MCP. A separate polling harness observed process state and lease
files only. It did not send protocol traffic, close handles, inject EOF, or
terminate provider processes.

The direct topology observed was:

```text
Codex PID 13992
  -> synesis-mcp.exe PID 21136
       -> packaged Java PID 14024
```

Codex parent PID was `10052`; official MCP parent PID was exactly Codex PID
`13992`; packaged Java parent PID was exactly MCP PID `21136`. The official
MCP path was
`C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
with SHA-256
`07F23EF1E1C9C6D344CA31A640CAA92BD483345C6F8260DE82A84C69F9E4A53B`.

## Source semantics reconfirmed

`McpStdioServer.run()` marks clean close only when `readFrame()` returns null,
returns exit code 0, and invokes `handler.close()` from its `finally` block.
`McpProtocolHandler.close()` calls `SessionLeaseService.markClosedCleanly()`
before detaching collaboration state. The lease writer persists
`CLOSED_CLEANLY`; shutdown exceptions are suppressed. Abnormal process
termination bypasses the clean EOF branch. No JVM shutdown hook provides an
alternate clean-close path, and the current trace does not log EOF or handler
close.

## Fresh fixture and lifecycle identity

- Fixture: `C:\t\syn041-real-codex-native-20260828-009`
- Project ID: `e1ff51ca-9612-41dd-b210-1a5991986db7`
- Baseline commit: `d32b64f63c0b290214041a0a66b9642569b6c7db`
- Connection: `syn041-real-codex-native-20260828-009`
- Session/binding: `session-6f15c51b-2d7d-415c-bca3-2f50906110b6`
- Participant, WorkIntent, and WorkGroup: not recovered because the Codex
  JSONL transcript was lost when the polling harness itself failed during
  output handling; no completion claim is made from this run.
- Lease file:
  `C:\Users\Liparakis\AppData\Local\Synesis\workspaces\e1ff51ca-9612-41dd-b210-1a5991986db7\admin\session-leases\syn041-real-codex-native-20260828-009.json`
- Initial/final observed lease state: `ACTIVE`
- Initial heartbeat: `1787937723882`; final heartbeat unchanged

## Native process observations

The external poller recorded the direct MCP and Java processes at
`2026-08-28T17:21:49.6594840Z` and `2026-08-28T17:21:49.6610535Z`.
It recorded both native child processes terminating at approximately
`2026-08-28T17:22:41.346Z`, while the Codex process exit was observed at
`2026-08-28T17:23:05.8487876Z` with code `0`. The external process handle did
not expose a usable child exit code in this harness. No MCP EOF or clean-close
event was available from the native MCP trace; the configured trace file was
not produced.

The Codex completion transcript was not persisted because the polling harness
failed while draining an inherited output handle. Consequently this run does
not prove lawful `finish_lane` completion, participant terminality, claims
release, or WorkGroup completion. The direct topology and termination timing
are valid observations, but they cannot establish RESULT A, B, C, or D.

Doctor after the run returned `DEGRADED`, with zero errors, zero critical
findings, zero mutations, and:

- `stale_session_lease` — the fresh lease;
- `command_namespace_reconciliation_required` — host-wide administrative;
- `command_capacity_or_retention` — host-wide administrative.

## Classification

Primary result: **RESULT E — still inconclusive**.

The native topology is proven and the prior wrapper topology is not present.
However, the missing Codex transcript and missing native EOF/child exit-code
evidence prevent a lawful-completion-to-teardown classification. The native
run therefore neither proves that the wrapper caused CP-0554 nor proves a
native provider/MCP incompatibility. No Synesis shutdown or lease-persistence
defect is proven.

SYN-041 remains `ACTIVE`. No further equivalent probe is authorized by this
task. Any future work would need a different, reliable OS-level capture path
that records Codex output without interposing on MCP stdio. No production code,
lease semantics, Doctor behavior, provider migration, identity architecture,
or SYN-039 semantics changed.
