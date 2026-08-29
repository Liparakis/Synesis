# SYN-041 final real Codex terminal-seal acceptance

Date: 2026-08-28  
Branch: `master`  
Starting HEAD: `f5622eba03c7631a7e3c8620a5598e8037ded001`  
Primary result: **RESULT C — terminal history rewrite after a correctly fenced rebind**

## Scope and provenance

This was the one authorized actual real-provider lifecycle. Two earlier
Codex launches in the same controller were preflight-only failures caused by
Windows argument quoting; Codex rejected them before MCP startup, and they
created no Synesis process, session, or event. They are recorded as excluded
controller failures, not provider lifecycle measurements.

The actual run used the normal authenticated Codex environment:

- Codex: `codex-cli 0.145.0`, `Logged in using ChatGPT`;
- direct topology: Codex -> official `synesis-mcp.exe` -> bundled Java;
- bundle: `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64`;
- bundle rebuilt with `TEMP=C:\t` and `TMP=C:\t`, build time
  `2026-08-28T20:43:33.732344700Z`, `BUILD_COMMIT=UNKNOWN` because the
  checkout was intentionally dirty;
- official MCP SHA-256:
  `33A75B968C97AA90DC3106776797B0291AA0C6CA9A3F3713095FE029E31A1924`;
- packaged JAR SHA-256: `synesis-cli.jar`
  `A411F4353FC37BAAC228FFE0DE892F5FF20DACD6E1A683916116AFC0A9FD20CA`,
  `coordination-0.1.0-SNAPSHOT.jar`
  `AE652114171BAB6D48C07A968618295FE5FD02E3FA6DEC908BFEA64A0FDECFB6`,
  `mcp-0.1.0-SNAPSHOT.jar`
  `9738BAEB07D0C97214DC5A43E425B7308359603BAF3292962354D808AE4C8252`,
  `mcp-contract-0.1.0-SNAPSHOT.jar`
  `2346FE309273244DD8CBAFE9EBD4042F3D6D9C02DBF48BFED182DED8A27BB1F4`,
  `workspace-0.1.0-SNAPSHOT.jar`
  `7EA6A2C499D3DE5B2C03E917BDEB259AE79E776C9183109DDA883AF4A8BE6F1E`.

The five unrelated lifecycle files were preserved. No provider migration,
generalized identity architecture, SYN-039 behavior, new MCP tool, push,
tag, release, or publication was performed.

## Fixture and identifiers

Fresh fixture: `C:\t\syn041-final-terminal-seal-20260828-001`  
Project ID: `97722e6c-811e-4ad5-b6ec-80eb802f0b28`  
Baseline commit: `3c70142797e43710cb090e355343d0d2bcf4fb9e`  
Connection: `conn-instance-4ae3fa70-50e7-41f4-8108-8b8831bb9c9b`  
Session/binding: `session-9ea1e61e-ff51-49ab-81f5-cba6f8fd1db1`  
Participant: `agt_863a2e00-c667-3751-8e48-0cf16f25c17d`  
WorkIntent: `7d90ff91-922d-3bc1-9a26-921e5c8fe95e`  
WorkGroup: `014d55f4-e84c-3616-80bf-f6be38ba4eab`  
Claim: `path_exact:verification.txt`, epoch 1.

## Real provider protocol and completion

The Codex prompt required this exact order:

1. task-bearing `ensure_session` with goal, acceptance, exact claim,
   `completionMode=no_change_allowed`, and producer role;
2. `read_file` of `verification.txt`;
3. `get_next_action({})`;
4. execute only its projected `finish_lane` arguments, adding only the
   explicit `terminalSession=true` opt-in;
5. stop after successful terminal completion and allow natural transport end.

The transcript at `C:\t\syn041-handle-observer-20260828-001\run-013\codex.jsonl`
proves all five steps. The file content was exactly `ok`; no fixture mutation
occurred. The exact finish request was:

```text
finish_lane({
  outcome: "no_change",
  intentId: "7d90ff91-922d-3bc1-9a26-921e5c8fe95e",
  workGroupId: "014d55f4-e84c-3616-80bf-f6be38ba4eab",
  claimEpoch: 1,
  workGroupVersion: 1,
  expectedRevision: 4,
  participant: "agt_863a2e00-c667-3751-8e48-0cf16f25c17d",
  summary: "Verification completed successfully; no repository mutation was required",
  terminalSession: true
})
```

Synesis returned `NO_CHANGE`, `claimsReleased=true`,
`workGroupState=COMPLETED`, and `sessionTermination=SESSION_TERMINATED` with
`terminalFenceSequence=7`. The decoded durable event sequence is:

```text
1 WORK_GROUP_CREATED
2 WORK_INTENT_ANNOUNCED
3 PARTICIPANT_HEARTBEAT
4 PARTICIPANT_HEARTBEAT
5 WORK_INTENT_RELEASED
6 WORK_GROUP_STATUS_CHANGED
7 PROVIDER_SESSION_TERMINALIZED
```

The terminal payload records the exact session, provider `codex`, participant,
reason `finish_lane_terminal_session`, and validated preceding revision 6.
There were no active intents, claims, pending review obligations, pending
commands/dependencies, or remaining WorkGroup authority at the seal.

## Native process evidence

The non-interposing observer retained only native query/synchronize handles.
It did not read or write MCP stdin/stdout and did not terminate any process.

| Role | PID | Parent | Executable | Exit code | Windows exit time |
| --- | ---: | ---: | --- | ---: | --- |
| Codex | 15152 | 18776 | `...\\codex-x86_64-pc-windows-msvc.exe` | 0 | `2026-08-28T20:48:21.6612618Z` |
| MCP | 11080 | 15152 | official bundled `synesis-mcp.exe` | 1 | `2026-08-28T20:48:21.2328312Z` |
| Java | 11564 | 11080 | bundled `runtime\\bin\\java.exe` | 1 | `2026-08-28T20:48:21.2327563Z` |

Java and MCP exited abnormally before Codex exited normally. The MCP trace
contains startup, initialize, and tools-list records, but no EOF or handler
close record. The native evidence proves the exit boundary; it does not prove
which side caused the child termination or that Codex emitted a partial frame.

Immediately after the real provider run, the lease marker was
`TERMINAL_AUTHORITY_CONFIRMED`; derived liveness against the absent Java
process is the implemented `TERMINAL_DISCONNECTED` classification. The
pre-probe Doctor result had no exact-session stale or ambiguous finding:
`DEGRADED`, two unrelated host-wide command-namespace warnings,
`reconciliationRecommended=false`, and zero mutations.

## Fence probe and proven defect

A single direct post-run probe reused the exact connection instance ID. It
advertised exactly 10 MCP tools and `ensure_session` returned:

```json
{"status":"completed","result":{"state":"SESSION_TERMINAL"}}
```

Thus the exact session did not regain authority. However, the probe then
closed its new stdio connection cleanly. The lease was rewritten to
`CLOSED_CLEANLY`, and the final lease file is:

`C:\Users\Liparakis\AppData\Local\Synesis\workspaces\97722e6c-811e-4ad5-b6ec-80eb802f0b28\admin\session-leases\conn-instance-4ae3fa70-50e7-41f4-8108-8b8831bb9c9b.json`.

The durable event sequence still ends at immutable
`PROVIDER_SESSION_TERMINALIZED`; no later terminal event or authority
reactivation occurred. Nevertheless, the normal clean-close path rewrote the
transport classification for the same sealed connection. This directly proves
that a rejected exact-session rebind/close can overwrite terminal transport
history. It violates the acceptance requirement that terminal history remain
immutable and distinct from `CLOSED_CLEANLY`.

Heartbeat and wake/next-action were not sent after the successful seal because
the real prompt was required to stop after terminal completion. Existing
serialized automated coverage already proves those fences; the exact-session
rebind probe proves the live terminal admission fence. A new independent
session was not created so the fixture would not be broadened after the
defect was found.

## Doctor and comparison

After the probe, Doctor remained `DEGRADED` with only the two unrelated
command-namespace warnings. It emitted no `stale_session_lease` and no
`durable_state_ambiguous` for this fixture, and performed zero mutations.

| Measure | CP-0557 | This acceptance |
| --- | --- | --- |
| Terminal seal | absent | present, event 7 |
| Lawful lane completion | yes | yes, `NO_CHANGE` |
| WorkGroup | `COMPLETED` | `COMPLETED` |
| Codex exit | 0 | 0 |
| MCP exit | 1 | 1 |
| Java exit | 1 | 1 |
| Lease after provider exit | `ACTIVE` | terminal authority confirmed; derived terminal-disconnected |
| Exact rebind | not tested | fenced as `SESSION_TERMINAL` |
| Final lease after probe | `ACTIVE` | incorrectly rewritten `CLOSED_CLEANLY` |
| Doctor stale warning | yes | absent |

## Classification and stopping decision

Primary result: **RESULT C**. The terminal seal itself succeeds, the real
Codex lifecycle completes, child abnormal teardown remains forensically
visible, and the exact session is fenced. The subsequent clean-close path
rewrites the same sealed lease to `CLOSED_CLEANLY`, so the final classification
and history-preservation criterion are not satisfied.

SYN-041 remains `ACTIVE`. No fix was committed in this run. The narrow next
causal question is whether `markClosedCleanly` must refuse to overwrite either
`TERMINAL_AUTHORITY_CONFIRMED` or a derived `TERMINAL_DISCONNECTED` state while
preserving the durable terminal event. Do not infer a broader identity or
provider architecture from this finding.

## Artifacts

All external run artifacts are under:
`C:\t\syn041-handle-observer-20260828-001\run-013\`.

Important files: `codex.jsonl`, `codex.final.txt`, `codex.stderr.log`,
`mcp.trace.log`, `observer.jsonl`, `controller.jsonl`,
`doctor-final.json`, `doctor-after-fence-probe.json`,
`collaboration-status-final.txt`, `fence-probe.jsonl`,
`lease-after-fence-probe.json`, `reconcile-dry-run.json`, and
`lease-evaluation.txt`.
