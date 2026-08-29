# SYN-041 native observability design

Date: 2026-08-28  
Starting HEAD: `f5622eba03c7631a7e3c8620a5598e8037ded001`  
Branch: `master`  
Task: SYN-041, measurement-design slice only  
Latest native checkpoint: CP-0555

## Boundary and current evidence

No real-provider run was performed for this slice. SYN-039 remains
`DONE / ACCEPTED`; SYN-041 remains `ACTIVE`; no lease, Doctor, provider,
identity, migration, cleanup, launcher, proxy, relay, or transport behavior
was changed.

CP-0555 observed the native topology without an interposed process:

```text
Codex 13992 -> synesis-mcp.exe 21136 -> Java 14024
```

The MCP and Java processes terminated at approximately
`2026-08-28T17:22:41.346Z` and `2026-08-28T17:22:41.348Z`; Codex exited at
`2026-08-28T17:23:05.8487876Z` with code `0`. The approximately 24.5-second
ordering is valid process timing, but CP-0555 had no Codex transcript, no
native EOF observation, and no usable MCP or Java exit code. The lease stayed
`ACTIVE` and Doctor reported `stale_session_lease`. The causal classification
therefore remains RESULT E.

The current exact blocker is:

> Need native, non-interposing MCP/Java exit-code and transport-lifetime
> telemetry sufficient to classify the child termination observed ~24.5
> seconds before Codex exit.

## Existing diagnostics inventory

### Codex 0.145.0

The installed executable is
`C:\Users\Liparakis\AppData\Local\Microsoft\WinGet\Links\codex.exe`,
resolving to the installed Windows binary. `codex --version` reports
`codex-cli 0.145.0`.

The supported noninteractive surfaces found in the installed help are:

- `codex exec --json`, which emits the Codex event stream as JSONL;
- `codex exec -o <file>`, which writes the final assistant message;
- `codex doctor --json`, a redacted installation/configuration/runtime report;
- `codex debug app-server`, `codex features`, and the ordinary `--config`
  overrides.

The help exposes no MCP lifecycle trace switch, child-process exit-code
switch, MCP stderr capture switch, verbose provider-process switch, or
documented MCP idle/shutdown timeout. `codex mcp list` is configuration
inventory only; it is not lifecycle telemetry. The earlier native harness
failed while draining inherited output, so the next design must use durable
Codex output files rather than a blocking inherited reader.

### Synesis MCP

The official MCP bundle is the `0.1.0-dev.local` Windows bundle. Its current
executable hash is:

```text
07F23EF1E1C9C6D344CA31A640CAA92BD483345C6F8260DE82A84C69F9E4A53B
```

Existing diagnostics are limited to Java stderr and the opt-in
`SYNESIS_MCP_TRACE_FILE` file. `SynesisMcpServer` writes a startup line with
PID, version, commit, connection, provider, and working directory.
`McpStdioServer` traces only `stdin_first_byte_received`,
`initialize_parsed`, `tools_list_received`, and
`initialize_response_written`. It does not trace EOF, read-loop exit,
`handler.close()`, clean-close persistence, or process exit. Stdio failures
are written to stderr and return process code `1`; no shutdown hook supplies
an additional clean-close record.

The source chain remains `readFrame() == null` -> return `0` ->
`McpProtocolHandler.close()` -> non-supervised
`SessionLeaseService.markClosedCleanly()` -> `CLOSED_CLEANLY`. An abnormal
process end bypasses that branch.

## Windows capability matrix

`Y` means the mechanism can provide the observation directly; `P` means
partial or conditional evidence; `N` means it cannot establish the item.
“Topology” means whether it inserts itself into Codex-to-MCP stdio.

| Mechanism                            | Codex/MCP/Java exit                               | Termination caller | Pipe/EOF                                     | Crash vs self-exit   | Handle timing | Topology  | Decision value                                                   |
|--------------------------------------|---------------------------------------------------|--------------------|----------------------------------------------|----------------------|---------------|-----------|------------------------------------------------------------------|
| Retained native process handles      | Y, exact status after wait                        | N                  | N                                            | P                    | Y             | unchanged | **Primary** for child exit and ordering                          |
| `Win32_ProcessStopTrace`             | P/Y, includes `ExitStatus`, PID, parent, UTC time | N                  | N                                            | P                    | Y             | unchanged | Useful fallback/cross-check; prior host attempt was not reliable |
| Kernel ETW process provider          | Y/P, start/stop and process identity              | N                  | N                                            | P                    | Y             | unchanged | Supplemental process timeline; events can be lost                |
| Kernel ETW object/file/I/O providers | P                                                 | N                  | P, not a dependable anonymous-pipe EOF proof | N/P                  | P             | unchanged | Investigative only; no stable pipe-object conclusion             |
| WMI/CIM process snapshots            | N after disappearance; Y only while live          | N                  | N                                            | N                    | P/polling     | unchanged | Parent/path/command-line inventory only                          |
| Process Monitor                      | P                                                 | N                  | P, tool-dependent and not authoritative      | P                    | P             | unchanged | Heavy supplemental observation, not primary proof                |
| WER/Application Error                | N/P, crash records only                           | N                  | N                                            | Y for recorded crash | Y             | unchanged | Distinguishes some crashes; absence proves nothing               |
| Job-object inspection                | P if a named/accessible job is known              | N/P                | N                                            | N                    | P             | unchanged | Can show job membership/accounting, not caller origin            |
| Sysinternals handle/process tools    | P snapshot                                        | N                  | P snapshot only                              | N                    | P             | unchanged | Manual relationship check; insufficient timing guarantee         |

The matrix is intentionally conservative. Ordinary ETW/WMI process events
show what ended and when; they do not prove that Codex called
`TerminateProcess`. Anonymous-pipe handle enumeration via undocumented or
privilege-sensitive handle-table techniques is not a reliable supported
EOF oracle, and Process Monitor does not turn it into one.

## Exact exit-code method

The next separately authorized measurement should discover each target PID as
soon as the direct topology appears, then immediately retain a process handle
without touching its standard handles:

1. Identify the direct MCP child of Codex by PID, executable path, command line,
   parent PID, and start time. Identify Java as the direct MCP child using the
   same identity tuple.
2. Call `OpenProcess` with `PROCESS_QUERY_INFORMATION | SYNCHRONIZE` and
   `bInheritHandle = FALSE`. `PROCESS_QUERY_LIMITED_INFORMATION` is a valid
   fallback for `GetExitCodeProcess` on supported Windows versions when full
   query access is denied; `SYNCHRONIZE` is required for waiting.
3. Retain both handles. Wait with `WaitForSingleObject` or
   `WaitForMultipleObjects`; on `WAIT_OBJECT_0`, record a monotonic timestamp,
   UTC timestamp, `GetExitCodeProcess`, and `GetProcessTimes`.
4. Record `QueryFullProcessImageNameW`, the executable hash, PID, parent PID,
   and creation-time identity before closing the handle. Never treat PID
   absence or `STILL_ACTIVE` as an exit result.

The process object and retained handle remain queryable after target
termination until the observer closes its handle. This is read-only
observation: it does not become the MCP parent, inherit or close MCP stdio,
inject code, send protocol bytes, or alter lease state. The same method works
for Java. `Win32_ProcessStopTrace` may be recorded as an independent
cross-check, but it is not a replacement for retained handles.

## Pipe and termination-origin limits

The observer cannot reliably observe the exact `McpStdioServer.readFrame()`
return from outside the process. It can observe the child exit and can try to
correlate anonymous-pipe handles, but a third-party handle-table snapshot does
not robustly identify the final writer-close instant or distinguish a normal
close from handles being closed as a consequence of process termination.

Therefore:

- a clean MCP exit code `0` does not prove whether Java returned normally,
  called `ExitProcess(0)`, or was externally terminated with code `0`;
- a nonzero exit code plus WER/Application Error evidence can establish a
  crash-like failure, but absence of WER does not prove self-exit;
- ordinary ETW, WMI, parentage, job accounting, and exit status do not identify
  the caller of `TerminateProcess`;
- exact Codex caller attribution would require provider instrumentation,
  kernel-level call-stack capture/debugging, or an interposing transport, all
  outside this measurement slice.

This is the critical limitation: the proposed design materially resolves
child status, timing, parentage, and crash evidence, but may still return a
bounded “origin not attributable” result rather than claim exact Codex
termination causality.

## Recommended final measurement topology

```text
external measurement controller
  ├─ Codex (directly started; regular-file stdout/stderr capture)
  │    └─ official synesis-mcp.exe (direct child; unchanged stdio)
  │         └─ packaged Java (direct child)
  └─ retained query/synchronize handles + optional OS event consumer
```

The controller may be the Codex parent; it must not be the MCP parent. Codex
must launch the official MCP directly. Codex JSONL and stderr should be
redirected to regular files at Codex creation time so no reader pipe can block
the harness. This changes only Codex output capture, not the Codex-to-MCP
stdio chain. The observer must never forward bytes, own MCP stdin/stdout,
close target handles, terminate a target, call MCP, write project state, or
touch leases.

Collect the following records in one UTC/monotonic timeline:

```text
T0 observer ready
T1 Codex start and identity
T2 MCP start, direct parentage, path/hash
T3 Java start, direct parentage, path/hash
T4 finish_lane response in Codex JSONL
T5 final Codex MCP/tool event and turn completion
T6 MCP handle signaled, exit code, process times
T7 Java handle signaled, exit code, process times
T8 optional WMI/ETW stop event and WER/Application Error event
T9 Codex exit, exit code, process times
T10 final lease JSON and Doctor JSON
```

The existing `SYNESIS_MCP_TRACE_FILE` may be enabled as a startup/protocol
correlation file, but it cannot supply T6’s EOF/close fact. A successful run
must preserve the complete Codex JSONL file, not stream-drain it through a
blocking inherited pipe.

## Classification rules

| Evidence                                                                                              | Classification                                                                                 |
|-------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| MCP exit `0`, no crash evidence, process end while Codex remains alive, and corroborated writer close | graceful transport shutdown is supported; literal EOF remains inferred unless MCP logs it      |
| MCP nonzero or exception/WER evidence while Codex remains alive                                       | MCP/Java failure or crash boundary                                                             |
| MCP/Java exit while Codex remains alive, with evidence of a job/process kill but no caller identity   | externally caused teardown is supported; Codex attribution remains unproven                    |
| Pipe closes, MCP exits `0`, and lease becomes `CLOSED_CLEANLY`                                        | healthy clean-close path supported                                                             |
| Pipe closes, MCP exits `0`, and lease remains `ACTIVE`                                                | Synesis shutdown defect candidate, pending proof that the pipe close reached the reader as EOF |
| Child exit status and timing captured but pipe/caller origin unavailable                              | bounded inconclusive result; do not label provider teardown or lease defect                    |
| No retained-handle result or incomplete Codex output                                                  | RESULT E; measurement failed again                                                             |

The ~24.5-second gap currently matches no documented Codex or Synesis timeout,
shutdown setting, or source-level constant found in the installed help or
checkout. It is a measured ordering, not evidence of a 25-second timeout.

## Disposable helper decision

A throwaway native helper is warranted for a future run because the prior
PowerShell polling path did not reliably retain/query child exit results or
preserve Codex output. It should be test-only and do only these operations:

- start Codex if needed, with stdout/stderr directed to regular files;
- discover and validate direct descendants;
- call `OpenProcess`, wait, query exit status/times, and close handles;
- optionally consume process ETW/WMI events and record WER evidence;
- emit append-only measurement records outside Synesis project state.

It must not be committed as production code, become the MCP parent, proxy or
forward stdio, inject, kill, call MCP, mutate leases, or perform cleanup. No
helper was implemented in this slice.

## Decision

No additional real-provider run is authorized by this design slice. If the
task is later explicitly continued, the retained-handle/file-capture method
is materially stronger than CP-0555 for MCP/Java exit status, process timing,
and crash evidence. It still cannot, by ordinary user-mode telemetry alone,
prove the caller of a zero-code external termination; that residual limit
must remain explicit in the resulting classification.

## References

- Installed command help: `codex exec --help`, `codex doctor --help`,
  `codex debug --help`, `codex mcp --help`.
- Synesis source: `mcp/src/main/java/org/synesis/mcp/transport/stdio/McpStdioServer.java`,
  `mcp/src/main/java/org/synesis/mcp/application/McpProtocolHandler.java`,
  `workspace/src/main/java/org/synesis/workspace/lifecycle/lease/SessionLeaseService.java`.
- Microsoft
  Learn: [GetExitCodeProcess](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-getexitcodeprocess),
  [process handles and identifiers](https://learn.microsoft.com/en-us/windows/win32/procthread/process-handles-and-identifiers),
  [WaitForSingleObject](https://learn.microsoft.com/en-us/windows/win32/api/synchapi/nf-synchapi-waitforsingleobject),
  [Win32_ProcessStopTrace](https://learn.microsoft.com/en-us/previous-versions/windows/desktop/krnlprov/win32-processstoptrace),
  and [ETW system providers](https://learn.microsoft.com/en-us/windows/win32/etw/system-providers).
