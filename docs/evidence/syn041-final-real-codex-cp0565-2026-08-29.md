# SYN-041 final real Codex CP-0565 acceptance — 2026-08-29

## Classification

Primary result: **RESULT C — real-provider terminal history rewrite remains**.

The single native Codex lifecycle reached the terminal seal and the exact
same-session rebind was rejected, but the provider/MCP teardown left the
persisted lease at `TERMINAL_AUTHORITY_CONFIRMED`. Its read-side liveness
classification was `TERMINAL_DISCONNECTED`, but that abnormal result was not
durably written before the rejected probe closed. The probe then changed the
persisted lease to `CLOSED_CLEANLY`.

Therefore CP-0565's durable-state guard works when
`TERMINAL_DISCONNECTED` is already persisted, but this real run proves a
narrow remaining transport-boundary defect: abnormal MCP transport teardown
does not persist that state before later clean cleanup. No further fix was
attempted in this acceptance slice.

SYN-041 remains ACTIVE. SYN-039 remains CLOSED / DONE / ACCEPTED. No SYN-042
was created.

## Provenance

- Branch: `master`
- HEAD: `f5622eba03c7631a7e3c8620a5598e8037ded001`
- Codex: `codex-cli 0.145.0`; authentication: `Logged in using ChatGPT`
- Official bundle:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64`
- Official launcher:
  `bin\synesis-mcp.exe`
- Launcher SHA-256:
  `33A75B968C97AA90DC3106776797B0291AA0C6CA9A3F3713095FE029E31A1924`
- Packaged `synesis-cli.jar` SHA-256:
  `F029371BA4E07A917058310B555271F632B8E01174CE081451B0DB1C3003F8A2`
- Packaged `coordination-0.1.0-SNAPSHOT.jar` SHA-256:
  `AE652114171BAB6D48C07A968618295FE5FD02E3FA6DEC908BFEA64A0FDECFB6`
- Packaged `mcp-0.1.0-SNAPSHOT.jar` SHA-256:
  `9738BAEB07D0C97214DC5A43E425B7308359603BAF3292962354D808AE4C8252`
- Packaged `mcp-contract-0.1.0-SNAPSHOT.jar` SHA-256:
  `2346FE309273244DD8CBAFE9EBD4042F3D6D9C02DBF48BFED182DED8A27BB1F4`
- Packaged `workspace-0.1.0-SNAPSHOT.jar` SHA-256:
  `D7671ABB8CE9D62A9579B9C5895347C3F2034E451602E8EF0FCD310687CD175D`

The worktree was intentionally dirty before the run. The five unrelated
lifecycle files remained excluded from this acceptance's changes.

## Fresh fixture and durable identifiers

- Fixture: `C:\t\syn041-final-real-cp0565-20260829-001`
- Baseline fixture commit: `3356f79b00a1cf8c7b1e14a61384fedb780466d4`
- Project: `59fa2019-28e2-4d5d-9ad3-b4c972a368a9`
- Connection: `conn-instance-8fcb87fe-7c7b-41e1-8d1d-06f6062a53f1`
- Session/binding: `session-e65fe876-4c5f-4b8c-8be6-e7b2eb5effb6`
- Participant: `agt_76e0bd16-33cb-38e5-985e-f318460b5c4b`
- WorkIntent/lane: `f4cde735-d540-30ce-99d4-90b0c2a1dc84`
- WorkGroup: `19ea47dc-0258-3ddf-8188-325478eb1335`
- Claim: `path_exact:verification.txt`, claim epoch 1

The file was exactly `ok` plus its normal trailing newline. No fixture
mutation was requested or performed by Codex.

## Exact provider lifecycle

The native topology was direct and non-interposed:

`Codex → official synesis-mcp.exe → packaged Java MCP runtime`

The Codex transcript at
`C:\t\syn041-final-real-cp0565-20260829-001\acceptance-artifacts\codex.jsonl`
proves this exact tool order:

1. task-bearing `ensure_session` with `completionMode=no_change_allowed`,
   producer role, and the exact file claim;
2. `read_file("verification.txt")`, returning `ok\n`;
3. `get_next_action({})`;
4. no mutation decision;
5. the projected `finish_lane` arguments with only `terminalSession=true`
   added;
6. stop after successful terminal completion.

The exact finish result was `NO_CHANGE`, claims released, WorkGroup
`COMPLETED`, and `SESSION_TERMINATED`. The terminal fence sequence was 7.
The terminal event was `PROVIDER_SESSION_TERMINALIZED`; event files 1 through
7 are present in the fixture's coordination event directory, with event 7
recording `finish_lane_terminal_session` and validated preceding revision 6.

Final durable projection before the rebind probe was: WorkIntent released,
claim released, WorkGroup `COMPLETED`, participant `COMPLETED`, and the local
provider session record `TERMINAL`.

The terminal response was delivered before the Codex turn completed. Native
process inspection observed Codex PID 7588, official MCP PID 19016, and
packaged Java PID 22952; MCP was the direct child of Codex and Java the direct
child of MCP. The child processes were gone by post-run inspection before the
outer Codex process completed, but exact MCP/Java exit codes and exit
timestamps were not captured by a retained-handle observer in this run.
Codex's outer command returned exit 0.

The native child teardown is abnormal and behaviorally consistent with the
previously reproduced exceptional MCP read path, but its exact caller or
low-level Codex transport event has not been directly observed. Do not infer
that Codex emitted a partial MCP frame.

## Lease before the rejected probe

The raw pre-probe lease was copied to
`acceptance-artifacts\lease-before-probe.json` and had SHA-256
`C310EEA7F2A26F1D15F179D0D34945BF2CFE36D431B7F0043311B0A5794C2B56`.

Its state was `TERMINAL_AUTHORITY_CONFIRMED`. Evaluating liveness against the
absent Java process produced the derived state `TERMINAL_DISCONNECTED`; this
derived value was not persisted into the lease file. All persisted process
identity and timestamps were present.

This is the remaining causal gap. The real MCP transport path did not invoke
the abnormal durable finalizer before the clean-close path became reachable.

## Rejected same-session probe and history result

A direct official-bundle MCP probe reused the exact connection ID and called
`ensure_session`. It returned:

```json
{"state":"SESSION_TERMINAL"}
```

The probe then closed its connection cleanly. Its framing harness emitted
three parser-error responses before the valid request because concatenated
frames were passed through PowerShell's pipeline as separate strings; the
valid `ensure_session` response was received and the process exited 0. No
second real Codex provider lifecycle was run.

After probe close, the durable lease had SHA-256
`74BD7F78EA7DE8CF28ABFFAE70335E172F2A26FF2057411CDD9E14B3C4DBBAEF` and
state `CLOSED_CLEANLY`.

Compared with the pre-probe record, schema, project, provider, connection,
worker, session, Synesis version, creation time, PID, executable, command
line, process start time, and connection nonce were unchanged. The lease
state changed from `TERMINAL_AUTHORITY_CONFIRMED` to `CLOSED_CLEANLY`, and
the clean-close path refreshed the heartbeat. The authoritative terminal
transport history therefore changed and the CP-0562-shaped defect remains.

## Doctor

Post-probe official-bundle Doctor returned `DEGRADED`, with 2 warnings, 0
errors, 0 critical findings, 0 mutations, and no cleanup or reconciliation
recommendation. The warnings were unrelated command-namespace warnings:
`command_namespace_reconciliation_required` and
`command_capacity_or_retention`.

No `stale_session_lease` or `durable_state_ambiguous` finding was present in
the JSON result. The final rewritten lease was not recovery-eligible, but the
required abnormal terminal history was lost.

## CP-0562 comparison

| Measure | CP-0562 | Final CP-0565 run |
|---|---|---|
| Terminal seal | yes, sequence 7 | yes, sequence 7 |
| Provider completion | `NO_CHANGE`, `SESSION_TERMINATED` | `NO_CHANGE`, `SESSION_TERMINATED` |
| Codex exit | 0 | 0 |
| MCP/Java exit | 1/1 observed previously | exact codes not captured; child teardown abnormal/inconclusive at low level |
| First lease outcome | derived terminal disconnect | persisted authority-confirmed, derived terminal disconnect |
| Rebind rejected | yes | yes, `SESSION_TERMINAL` |
| After probe cleanup | `CLOSED_CLEANLY` | `CLOSED_CLEANLY` |
| History preserved | no | no |
| Stale warning | absent | absent |
| Durable ambiguous warning | absent | absent |

## Closure decision

This run is **RESULT C**, not RESULT A or B. The terminal seal and live fence
remain green, but the abnormal outcome was only derived and the subsequent
rejected clean close rewrote the durable lease. SYN-041 was not formally
closed. No additional provider run or speculative fix was performed.

Existing CP-0565 bounded serialized validations remain authoritative:
focused lease/terminalization/MCP regressions, wake/continuation, tool count,
strict Javadocs, deferred validation, platform bundle, and bundle smoke all
passed. Broader process-heavy MCP suites remain incomplete because of host
timeouts, not assertion failures.

No commit, push, tag, release, or publication occurred. The real-run
artifacts remain under the disposable fixture path for review.
