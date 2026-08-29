# SYN-041 read-only exit-code causal analysis

Date: 2026-08-28
Branch: `master`
HEAD: `f5622eba03c7631a7e3c8620a5598e8037ded001`
Latest prior checkpoint: `CP-0557`

## Scope

This was a read-only source analysis plus disposable non-Codex controls. No
provider session was started, no production source was changed, no lease or
Doctor behavior was changed, and the Synesis source checkout was never used as
the control project. The five pre-existing unrelated lifecycle-file changes
were preserved untouched.

## Source trace

The native launcher source is
`bootstrap/cmd/synesis-mcp/main.go`. Gradle builds it from
`bootstrap/cmd/synesis-mcp` with `go build -trimpath`, copies the Windows
artifact into the platform bundle, and emits the tested executable at
`cli/build/platform-bundle/synesis-0.1.0-dev.local-windows-x64/bin/synesis-mcp.exe`.

The launcher:

1. resolves the bundled `runtime/bin/java.exe` and the bundle classpath;
2. invokes `org.synesis.cli.SynesisCli` with inherited stdin/stdout/stderr and
   the forwarded `mcp --provider ... --project ...` arguments;
3. waits synchronously with `command.Wait()`;
4. calls `os.Exit(exitError.ExitCode())` when Java returns an
   `exec.ExitError`;
5. uses launcher-generated exit `1` only for launcher resolution/start/wait
   errors that are not Java `ExitError`s.

There is no shutdown timeout and no launcher logic that terminates Java when
stdio changes. Signal forwarding handles only interrupt and SIGTERM. Thus the
tested Java exit `1` is propagated directly by the launcher; it is not
launcher-generated.

The Java path is
`cli/src/main/java/org/synesis/cli/SynesisCli.java` -> `McpCommand.call()` ->
reflective `SynesisMcpServer.execute()` ->
`mcp/src/main/java/org/synesis/mcp/transport/stdio/McpStdioServer.java`.
`SynesisCli.main()` calls `System.exit(execute(...))`.

`McpStdioServer.run()` has these decisive paths:

| Java path               | Trigger                                                     |                                        Java result | `handler.close()`      |
|-------------------------|-------------------------------------------------------------|---------------------------------------------------:|------------------------|
| Normal EOF              | `readFrame()` sees `-1` with an empty frame buffer          |                                                  0 | yes                    |
| Partial EOF             | `readFrame()` sees `-1` after buffered bytes                |                                                  1 | no                     |
| Invalid UTF-8           | strict decoder throws `MCP_INVALID_UTF8`                    |                                                  1 | no                     |
| Oversized frame         | frame limit throws `MCP_FRAME_LIMIT_EXCEEDED`               |                                                  1 | no                     |
| Malformed JSON          | handler creates a JSON-RPC parse error response             |            normally 0 if stream later ends cleanly | yes on later clean EOF |
| Handler runtime failure | handler catches most failures and returns an error response |            normally 0 if stream later ends cleanly | yes on later clean EOF |
| Loop `Throwable`        | any uncaught read/dispatch failure                          |                                                  1 | no                     |
| `handler.close()`       | executed only after clean EOF                               | normally no change; `close()` swallows `Exception` | conditional            |

The loop catches `Throwable`, logs the message and stack trace to stderr, and
returns `1`. The `finally` block invokes `handler.close()` only when the loop
set `cleanClose=true`. `McpProtocolHandler.close()` attempts
`markClosedCleanly()` and collaboration detach, swallowing ordinary
`Exception`s.

The response writer is a `PrintStream`. `println()` and `flush()` are called,
but `checkError()` is never tested. The local closed-stdout control therefore
confirmed that a write-side pipe closure alone does not select Java exit `1`.

## Disposable controls

All controls used the official packaged MCP and separate external fixtures
under `C:\t\syn041-exit-controls-*`. The official bundle was
`0.1.0-dev.local`; the MCP executable hash was
`07F23EF1E1C9C6D344CA31A640CAA92BD483345C6F8260DE82A84C69F9E4A53B`.

| Control              | Setup                                                                                    | Java/MCP exit | Lease            | Fatal diagnostic                    |
|----------------------|------------------------------------------------------------------------------------------|--------------:|------------------|-------------------------------------|
| Clean EOF            | initialize, `ensure_session`, close stdin after complete frames                          |         0 / 0 | `CLOSED_CLEANLY` | none                                |
| Partial EOF          | initialize, `ensure_session`, write an unterminated frame, close stdin                   |         1 / 1 | `ACTIVE`         | `MCP_PARTIAL_FRAME_EOF` stack trace |
| Closed stdout reader | initialize, `ensure_session`, close parent stdout reader, send `tools/list`, close stdin |         0 / 0 | `CLOSED_CLEANLY` | none                                |

The clean control used project ID `469069b6-6789-4ded-9656-e4b30d749949` and
connection `syn041-control-clean-eof`. The partial control used project ID
`cd54ac8f-1036-4471-8d87-d88098cb598f` and connection
`syn041-control-partial-eof`. The stdout control used project ID
`5507488a-ad63-421b-ac69-2541df6cd0f0` and connection
`syn041-control-stdout-close`.

The partial control’s stderr localized the Java failure to:

```text
McpFrameReader.readFrame(McpFrameReader.java:38)
McpStdioServer.run(McpStdioServer.java:67)
SynesisMcpServer.execute(SynesisMcpServer.java:56)
McpCommand.call(McpCommand.java:64)
SynesisCli.main(SynesisCli.java:263)
```

This is the exact locally reproduced input-side exceptional path. The clean
control proves normal parent stdin closure is not this path. The stdout
control proves that a closed parent reader does not reproduce it with the
current `PrintStream` handling.

## Comparison with CP-0557

CP-0557 recorded successful provider completion, MCP exit `1`, Java exit `1`,
Codex exit `0`, an `ACTIVE` lease, and no crash event. The partial-EOF control
matches the child exit and lease signature. The clean-EOF and closed-stdout
controls do not. Therefore the strongest supported interpretation is an
abnormal input-side transport/read condition or equivalent exceptional loop
failure after completion, not ordinary stdin EOF and not stdout closure alone.

The exact CP-0557 low-level exception is not proven: its captured stderr did
not contain `MCP_PARTIAL_FRAME_EOF` or another MCP fatal stack trace. The
evidence localizes Java exit `1` to the exceptional `McpStdioServer` return
path only as far as the available provider transcript permits.

## Classification

Primary: **RESULT B — clean EOF works; an abrupt/partial transport condition
reproduces Java/MCP exit `1` with an `ACTIVE` lease.**

Secondary: **RESULT D — the native launcher propagates Java’s nonzero exit
code directly.**

This strongly supports a provider/MCP teardown incompatibility boundary, but it
does not prove that Codex specifically emitted a partial frame, identify an
external terminator, or prove a Synesis shutdown-path defect. No policy or
lease change is authorized by this evidence.

## Artifacts

Control JSON results are under:

- `C:\t\syn041-exit-controls-clean-eof\results\clean-eof.json`
- `C:\t\syn041-exit-controls-partial-eof\results\partial-eof.json`
- `C:\t\syn041-exit-controls-stdout-close\results\stdout-close.json`

No control processes remain. No production files, lease semantics, Doctor
classification, migration, generalized identity, recovery, launcher, daemon,
relay, orchestrator, SYN-039 state, milestone, or remote repository state was
changed.
