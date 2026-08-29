# SYN-041 final handle-based native measurement

Date: 2026-08-28  
Branch: `master`  
Starting HEAD: `f5622eba03c7631a7e3c8620a5598e8037ded001`  
Valid provider run: one; fixture `C:\t\syn041-real-codex-native-20260828-011`

## Scope and controller preflight

The requested valid measurement used one native real Codex session with a
separate handle observer. Two earlier controller launches were preflight
failures before MCP startup: Codex rejected malformed command-line TOML array
overrides. They produced no MCP or Java process and are not lifecycle
measurements. The corrected controller was validated with `codex exec --help`
before the valid run.

The disposable helper was
`C:\t\syn041-handle-observer-20260828-001\observe.ps1`; it is outside the
repository and uncommitted. A non-provider smoke check first verified that a
retained handle can observe a signaled process and read exit code `0`.

The helper used `OpenProcess(PROCESS_QUERY_INFORMATION | SYNCHRONIZE,
inheritHandle=false)`, `WaitForSingleObject(..., 0)`,
`GetExitCodeProcess`, `GetProcessTimes`, and
`QueryFullProcessImageNameW`. It never opened MCP standard handles, forwarded
bytes, called MCP, mutated Synesis state, or terminated/suspended/injected into
any process.

## Direct topology proof

The controller PID was `4444`. The observer PID was `5460`, started as a
controller sibling to Codex. The observed native chain was:

```text
controller 4444
  ├─ observer 5460
  └─ Codex 1576
       └─ official synesis-mcp.exe 11416
            └─ packaged Java 10192
```

Parentage and executable identities were:

| Role  |   PID | Parent | Image                                                                                                                                            |
|-------|------:|-------:|--------------------------------------------------------------------------------------------------------------------------------------------------|
| Codex |  1576 |   4444 | `C:\Users\Liparakis\AppData\Local\Microsoft\WinGet\Packages\OpenAI.Codex_Microsoft.Winget.Source_8wekyb3d8bbwe\codex-x86_64-pc-windows-msvc.exe` |
| MCP   | 11416 |   1576 | `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`                           |
| Java  | 10192 |  11416 | `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\runtime\bin\java.exe`                          |

The official MCP SHA-256 was
`07F23EF1E1C9C6D344CA31A640CAA92BD483345C6F8260DE82A84C69F9E4A53B`.
The bundle was `0.1.0-dev.local`; its CLI JAR hash was
`E5D10201094A99925E975DC593A8DF606DE7308A080E48652186D07DAE313329` and
its MCP JAR hash was
`E7C2C7573EB1A04D29DA1C694394529DA59B78132720E56564287CE489E795FF`.
Codex was `codex-cli 0.145.0`, launched through
`C:\Users\Liparakis\AppData\Local\Microsoft\WinGet\Links\codex.exe`.

## Provider and Synesis evidence

Fresh project ID: `b0899bc1-5de4-474a-a493-ede60231df91`. The fixture Git
checkout remained clean at baseline commit `60f9c7cfb771e3c913eb8e575130605376614f4`.
The Codex JSONL proves the required sequence:

1. task-bearing `ensure_session` returned `ready` and isolated workspace;
2. `read_file({"path":"verification.txt"})` returned `ok\n`;
3. `get_next_action({})` projected the exact `finish_lane` arguments;
4. `finish_lane` returned `NO_CHANGE`, `claimsReleased=true`, and
   `workGroupState=COMPLETED`;
5. Codex reported completion and its final assistant output stated that no
   repository mutation occurred.

Identifiers:

- connection: `conn-instance-8eef0614-d069-4d0a-9d3f-5b93fa2fd060`;
- session/binding: `session-7dc63404-716d-4bd7-baf2-d74529535b77`;
- participant: `agt_973809df-2895-36f9-af71-b7d268290d72`;
- WorkIntent: `6c98d078-29ca-3005-bf36-036e35a9cd8f`;
- WorkGroup: `51268f45-8a52-3818-b6eb-3bbd639ecf8b`;
- claim: `path_exact:verification.txt`, epoch 1;
- lease:
  `C:\Users\Liparakis\AppData\Local\Synesis\workspaces\b0899bc1-5de4-474a-a493-ede60231df91\admin\session-leases\conn-instance-8eef0614-d069-4d0a-9d3f-5b93fa2fd060.json`.

The initial and final lease reads were `ACTIVE`. The final lease still
contained Java PID `10192`, the exact executable identity, and the matching
process-start epoch. The post-completion CLI status query returned
`COLLABORATION_ERROR=PARTICIPANT_NOT_FOUND`; it was not used to contradict
the authoritative completed `finish_lane` response. No lease file was
mutated by the observer or controller.

## Handle-based teardown evidence

The observer’s monotonic timeline and Windows process times were:

| Role  | Process creation time          | Windows process exit time      | Observer signaled time         | Exit code |
|-------|--------------------------------|--------------------------------|--------------------------------|----------:|
| MCP   | `2026-08-28T18:14:27.5650560Z` | `2026-08-28T18:15:01.8764560Z` | `2026-08-28T18:15:02.1403304Z` |       `1` |
| Java  | `2026-08-28T18:14:27.6029938Z` | `2026-08-28T18:15:01.8764686Z` | `2026-08-28T18:15:02.1559163Z` |       `1` |
| Codex | `2026-08-28T18:14:25.9746902Z` | `2026-08-28T18:15:02.1470243Z` | `2026-08-28T18:15:02.3960967Z` |       `0` |

The retained handles returned successful exit queries for all three
processes. MCP’s Windows exit time preceded Java by approximately 12.6
microseconds; both preceded Codex’s Windows exit time by approximately 270.6
milliseconds. The observer’s polling signal records show the same order, with
MCP observed before Java and both before Codex. Codex was therefore alive when
both children ended.

The existing MCP trace contains only:

```text
stdin_first_byte_received
initialize_parsed
initialize_response_written
tools_list_received
```

There is still no literal EOF or `handler.close()` event. Codex stderr had
only a model-cache warning and the known PowerShell shell-snapshot warning;
no MCP exception was forwarded. A read-only Application log query for the
teardown interval found no matching Application Error, WER, .NET Runtime, or
Java event. Absence of such an event is supporting evidence only, not proof of
a graceful exit.

## Lease and Doctor result

Doctor was run after all three processes ended and returned:

```text
doctorResult=UNHEALTHY
findingsCount=2
errors=1
warnings=1
critical=0
mutationsPerformed=0
reconciliationRecommended=true
```

Fresh findings:

- `durable_state_ambiguous` / `ERROR` / `AMBIGUOUS`: failed to read the
  durable coordination event store;
- `stale_session_lease` / `WARNING` / `HIGH_CONFIDENCE`: the provider lease
  missed heartbeats beyond policy threshold.

The CLI collaboration-status query also returned `PARTICIPANT_NOT_FOUND`.
These diagnostic read failures do not authorize repair or reconciliation.

## Classification

Primary result: **RESULT C — MCP/Java failure**.

The decisive new evidence is nonzero exit status `1` for both official MCP and
Java while Codex remained alive. This establishes a child failure/abnormal
termination boundary and explains why clean-close behavior was not proven. It
does not establish a crash signature, identify a `TerminateProcess` caller,
or prove that Codex explicitly killed the child. The provider/MCP teardown
boundary remains the causal investigation boundary; a Synesis lease defect is
not proven.

SYN-041 remains `ACTIVE`. No RESULT A/B/D/E re-run is authorized. The exact
remaining origin question is whether the code-1 termination was an MCP/Java
self-failure or an externally induced termination carrying code `1`; ordinary
user-mode telemetry did not resolve that caller attribution.

## Scope confirmation

- No production source changed.
- The five pre-existing unrelated lifecycle files remain modified and were
  not touched by this run.
- Lease semantics, Doctor classification, provider migration, generalized
  identity, reattach, succession, recovery, cleanup, launcher, daemon, relay,
  orchestrator, and SYN-039 semantics were untouched.
- No new milestone/task was created. Nothing was pushed, tagged, released, or
  published.

## External artifacts

The complete disposable artifacts are under
`C:\t\syn041-handle-observer-20260828-001\run-012\`:

- `observer.jsonl` — retained-handle process discovery, signal, exit-code, and
  process-time records;
- `controller.jsonl` — controller/observer/Codex launch ordering;
- `codex.jsonl`, `codex.final.txt`, `codex.stderr.log` — Codex evidence;
- `mcp.trace.log` — existing Synesis startup/protocol trace;
- `doctor-final.json`, `lease-paths-final.txt`, and
  `collaboration-status-final.txt` — read-only post-run diagnostics.
