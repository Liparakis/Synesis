# SYN-041 real Codex teardown measurement

Date: 2026-08-28
Starting HEAD: `f5622eba03c7631a7e3c8620a5598e8037ded001`
Branch: `master`

## Scope and source call chain

This was the narrowly authorized teardown measurement. No product source,
accepted SYN-039 semantics, provider migration, or Doctor behavior changed.

The current call chain is:

```text
McpStdioServer.run()
  reader.readFrame() returns null at stdin EOF
  -> cleanClose = true
  -> return 0
  -> finally handler.close()
  -> McpProtocolHandler.close()
  -> SessionLeaseService.markClosedCleanly(...)
  -> leaseState=CLOSED_CLEANLY
```

`McpProtocolHandler.close()` persists the clean lease before detaching the
collaboration lane and suppresses shutdown exceptions. `markClosedCleanly`
rewrites the existing record with a current heartbeat and
`CLOSED_CLEANLY`; an I/O failure is suppressed. Provider completion alone does
not call this path. A provider/process termination without EOF can therefore
leave `ACTIVE`, which Doctor evaluates as stale/recovery-relevant after missed
heartbeats. Doctor does not warn for `CLOSED_CLEANLY`.

## Measurement mechanism

A temporary external PowerShell 7 observer was configured between Codex and
the unchanged official `synesis-mcp.exe`. It copied stdin/stdout/stderr bytes
without parsing, altering, delaying, reordering, or synthesizing protocol
frames. It recorded only observer timestamps, the official child PID and hash,
pipe-copy completion, stream closure, and child exit code. It also recorded
lease-file state snapshots externally. The observer was temporary and was
removed after the run; it remains uncommitted.

Two observer bring-up controls (fixtures 004 and 005) failed before MCP
engagement due observer-launch/forwarding issues and are not lifecycle
evidence. Fixture 006 is the successful instrumented run.

## Fresh run

- Fixture: `C:\t\syn041-real-codex-teardown-20260828-006`
- Project ID: `8770e9e5-9ada-493f-b789-0cb0bd19861a`
- Baseline commit: `bb422ff501c2dff8f3f6963c591a4516d887677e`
- Connection: `syn041-real-codex-teardown-20260828-006`
- Session/binding: `session-306204ec-1ed8-472f-8e82-b43fabe86f30`
- Participant: `agt_1bb0684e-67bb-3b74-b678-21ee8667d66e`
- WorkIntent/lane: `7c38c1d8-0ab8-31d8-8240-b3d8c169771c`
- WorkGroup: `24ed22b3-2855-3675-8b27-f2a59bf5b1d6`
- Claim: `path_exact:verification.txt`, claim epoch 1
- Lease file:
  `C:\Users\Liparakis\AppData\Local\Synesis\workspaces\8770e9e5-9ada-493f-b789-0cb0bd19861a\admin\session-leases\syn041-real-codex-teardown-20260828-006.json`

Codex was `codex-cli 0.145.0` at
`C:\Users\Liparakis\AppData\Local\Microsoft\WinGet\Links\codex.exe`,
PID `2340`. It started at `2026-08-28T17:05:08.0683112Z` and exited at
`2026-08-28T17:05:36.0276785Z` with code `0`.

The observer verified the official MCP path
`C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
and hash
`07F23EF1E1C9C6D344CA31A640CAA92BD483345C6F8260DE82A84C69F9E4A53B`.
It launched MCP PID `10976` from observer PID `14984` at
`2026-08-28T17:05:09.0999504Z`; the packaged Java process was recorded by
the lease as PID `10100`.

## Lawful completion

The real Codex JSONL recorded:

1. task-bearing `ensure_session` returned `ready`;
2. `read_file({"path":"verification.txt"})` returned exact content `ok`;
3. `get_next_action({})` projected the exact no-change `finish_lane` action;
4. `finish_lane` returned `NO_CHANGE`, `claimsReleased=true`, and
   `workGroupState=COMPLETED`;
5. Codex reported lifecycle completion and exited 0.

The fixture repository remained at its baseline commit with no mutation.

## Direct teardown observations

The observer log proves the official hash and child launch. The official MCP
trace recorded initialization and `tools/list`, proving that the observer
forwarded the real protocol far enough for the complete Synesis task to run.
It did **not** record `mcp_stdin_eof_forwarded`, stdout/stderr closure, or
`official_child_exit`. The Codex process exited 0, while the observer/MCP
chain subsequently ceased to be present; no MCP exit code can be claimed.

Therefore:

- MCP stdin EOF: **not observed**;
- `McpProtocolHandler.close()` invocation: **not observed**;
- `SessionLeaseService` `CLOSED_CLEANLY` write: **not observed**;
- MCP exit timestamp/code: **not captured**;
- packaged Java exit result: **not captured**.

Lease snapshots showed `ACTIVE` at `2026-08-28T17:05:35.9302406Z` and again
after teardown at `2026-08-28T17:06:02.4020899Z`, with heartbeat unchanged at
`1787936724798`. The final durable lease remained `ACTIVE`.

Doctor after teardown returned `DEGRADED`, with zero errors, zero critical
findings, zero mutations, and three warnings:

- `stale_session_lease` — fresh-run lease;
- `command_namespace_reconciliation_required` — host-wide administrative;
- `command_capacity_or_retention` — host-wide administrative.

## Classification

Primary result: **RESULT C — provider/MCP transport teardown defect**.

The required transport evidence is direct: Codex exited 0, but the observer
did not receive parent-side stdin EOF and did not observe a clean official MCP
child exit. The lease warning is a consequence of the missing clean teardown
evidence, not proof of a `SessionLeaseService` persistence defect. The causal
boundary is Codex-to-MCP process/pipe teardown, upstream of Synesis lease
finalization.

SYN-041 remains `ACTIVE`. Stop before implementation. The narrow next action,
if separately authorized, is to investigate why Codex terminates the MCP
observer/child chain without closing the observer stdin pipe or yielding a
clean child exit; do not compensate by closing leases or suppressing Doctor.
SYN-039 remains `DONE / ACCEPTED` at CP-0547, and SYN-040 remains
`DONE / VERIFIED`.
