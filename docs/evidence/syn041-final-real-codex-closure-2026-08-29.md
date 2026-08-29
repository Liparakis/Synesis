# SYN-041 final real Codex closure acceptance — 2026-08-29

## Verdict

Primary result: **RESULT A — full real abnormal-path acceptance**.

The one authorized real Codex lifecycle completed lawful no-change work and
committed the terminal fence before the provider transport disappeared. The
original Java runtime was observed under the native direct parent chain and
was absent before the rejected same-session probe. That probe returned
`SESSION_TERMINAL` and exited cleanly, while the production PID-gated foreign
close path durably changed the original lease from
`TERMINAL_AUTHORITY_CONFIRMED` to `TERMINAL_DISCONNECTED` without changing
any original process metadata.

SYN-041 is therefore formally **DONE / ACCEPTED**. SYN-039 remains
**CLOSED / DONE / ACCEPTED**. No SYN-042 was created.

## Provenance

- Starting branch: `master`.
- Starting HEAD: `f5622eba03c7631a7e3c8620a5598e8037ded001`.
- The starting worktree was intentionally dirty with the recorded SYN-041
  implementation/evidence and five preserved unrelated lifecycle files.
- Official bundle: `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64`.
- Launcher: `bin\synesis-mcp.exe`; SHA-256 at launch:
  `33A75B968C97AA90DC3106776797B0291AA0C6CA9A3F3713095FE029E31A1924`.
- Codex: `codex-cli 0.145.0`; authentication: normal `Logged in using
  ChatGPT`.
- The bundle was rebuilt from this checkout with `:cli:platformBundle
  --rerun-tasks --max-workers=1 --no-daemon` before the run. A later required
  Javadoc/smoke rebuild regenerated timestamped packaged JARs; the current
  post-validation hashes are recorded in the final report and must not be
  retroactively presented as the launch-time JAR hashes. The launch-time
  `synesis-cli.jar` hash captured before the run was
  `9B5E631F2A37E0468C6733593ABE1FE4B91BD9607328E3E6C756F9C83DFFFAB7`.
- Current post-validation packaged JAR hashes are: `synesis-cli.jar`
  `BF1202C81E21892AD4D55BFCFB7435C836AA27A5E78977DC52AB062D4040FDA3`,
  `coordination-0.1.0-SNAPSHOT.jar`
  `AE652114171BAB6D48C07A968618295FE5FD02E3FA6DEC908BFEA64A0FDECFB6`,
  `link-0.1.0-SNAPSHOT.jar`
  `92575DD208C6CFBB7BCF6DD46BAD678E9753594AD2F606E66A452F4F4496B1E9`,
  `mcp-0.1.0-SNAPSHOT.jar`
  `D3A772DC0D0233592F57AC4902BBC9019C45ABA038307276B3DAC8346AC67285`,
  `mcp-contract-0.1.0-SNAPSHOT.jar`
  `2346FE309273244DD8CBAFE9EBD4042F3D6D9C02DBF48BFED182DED8A27BB1F4`,
  `project-record-0.1.0-SNAPSHOT.jar`
  `1709E59556CF82253F0722B42B47E5C21C65101A8BD25B0002BD78188351922D`,
  and `workspace-0.1.0-SNAPSHOT.jar`
  `8473C0BAB76C2BD93D2FA8486EBB1C09D4258D5DC69F6C34B697CEE48C5A86B9`.

## Fresh fixture and identifiers

- Fixture: `C:\t\syn041-final-20260829-001`.
- Baseline commit: `1df2edee1c6a8f04a0ad00ac967d92b0cd3889ff`.
- Project: `d6ddf087-1ee4-4ac8-b289-8a073ab316f0`.
- Connection: `conn-instance-54462f0b-9828-42d1-a9ea-d501b9f88e5c`.
- Session: `session-f649cf83-2026-4636-ad01-054dcd57f875`.
- Participant: `agt_3090a676-a397-39e6-a996-39b9b2f66936`.
- WorkIntent/lane: `ff33cb5c-1e5d-344e-b394-62fb659938f9`.
- WorkGroup: `d79d31fe-0e24-3fe0-b0dd-4da6306d7301`.
- `verification.txt` was exactly `ok`; the fixture remained clean.

## Real Codex lifecycle

The only real Codex run used direct native topology:

`Codex -> official synesis-mcp.exe -> packaged Java MCP`.

The observed action order was:

1. task-bearing `ensure_session` with `completionMode=no_change_allowed`,
   `role=producer`, and a read claim for `verification.txt`;
2. `read_file("verification.txt")`, returning `ok`;
3. `get_next_action({})`;
4. no mutation decision;
5. the exact projected `finish_lane` payload, adding only
   `terminalSession=true` to the projected arguments;
6. stop after successful terminal completion.

Relevant `finish_lane` fields were `outcome=no_change`, the exact intent and
work-group IDs, `claimEpoch=1`, `workGroupVersion=1`, `expectedRevision=4`,
the exact participant, the projected summary, and `terminalSession=true`.

The result was `NO_CHANGE`, `claimsReleased=true`, WorkGroup `COMPLETED`,
and `SESSION_TERMINATED`. Event files 1 through 7 are present; event 7 is
`finish_lane_terminal_session`, with validated prior revision 6 and terminal
fence sequence 7. The terminal response and durable event were committed
before the provider turn completed.

Final projections before probing were WorkIntent released, claim released,
WorkGroup `COMPLETED`, participant `COMPLETED`, and provider session
`TERMINAL`.

## Native process evidence

Live non-interposing observation recorded:

| Process |   PID | Executable                                                          | Parent | Start observation          |
|---------|------:|---------------------------------------------------------------------|-------:|----------------------------|
| Codex   | 24824 | `C:\Users\Liparakis\AppData\Local\Microsoft\WinGet\Links\codex.exe` |  23652 | 2026-08-29 03:16:02 +03:00 |
| MCP     | 22700 | official bundle `bin\synesis-mcp.exe`                               |  24824 | 2026-08-29 03:16:04 +03:00 |
| Java    | 25532 | official bundle `runtime\bin\java.exe`                              |  22700 | 2026-08-29 03:16:04 +03:00 |

The MCP and Java children were gone by post-run inspection; the original
Java PID was explicitly absent before the probe. Exact child exit codes and
exit timestamps were not retained by the observer, and the outer Codex exit
code was not separately retained. The complete Codex JSONL contains
`turn.completed`, and the native process outcome is behaviorally abnormal
because no clean-close lease transition occurred before child disappearance.
The exact caller or lower-level provider transport event remains unobserved;
no partial MCP frame is claimed.

## Lease and rejected probe

Before the probe, the raw lease snapshot was saved as
`C:\t\syn041-final-20260829-001-artifacts\lease-before-probe.json` with hash
`337950A4FC9465D9E5D1F41E3B7D70835E12CC42D086493517EE45595B73B1A9` and
state `TERMINAL_AUTHORITY_CONFIRMED`. Its recorded PID `25532` was absent.

The direct official-bundle probe reused the exact connection ID and returned
`{"state":"SESSION_TERMINAL"}`. Its MCP process PID was 21076 and its
packaged Java child was 20128. The probe closed stdin normally and exited 0.

The final raw lease hash is
`E38A2F6C5039DAD20E7175F520165FDC54465F08AE2980CDD40D50535EACECAF` and
state `TERMINAL_DISCONNECTED`. The foreign-close liveness fallback executed
using the persisted original PID evidence: the original process was absent,
its recorded PID differed from the probe runtime, and terminal authority was
already confirmed. The original PID, executable, command line, start time,
nonce, session ID, project, provider, creation time, and heartbeat metadata
were preserved; only `leaseState` changed.

This proves the CP-0567 causal correction. The rejected probe's clean EOF did
not redefine the original runtime's transport history as `CLOSED_CLEANLY`.

## Doctor and comparison

Official-bundle Doctor returned `DEGRADED`, 2 unrelated command-namespace
warnings, 0 errors, 0 critical findings, 0 mutations, and no cleanup or
reconciliation recommendation. `stale_session_lease` was absent,
`durable_state_ambiguous` was absent, and recovery eligibility was none.

| Measure                         | CP-0566                        | Final                                                                               |
|---------------------------------|--------------------------------|-------------------------------------------------------------------------------------|
| Terminal seal                   | pass                           | pass, sequence 7                                                                    |
| Original teardown               | abnormal                       | abnormal by durable outcome/process disappearance; exact low-level cause unobserved |
| Pre-probe lease                 | `TERMINAL_AUTHORITY_CONFIRMED` | `TERMINAL_AUTHORITY_CONFIRMED`                                                      |
| Same-session rebind             | `SESSION_TERMINAL`             | `SESSION_TERMINAL`                                                                  |
| Original tracked process dead   | yes                            | yes, PID 25532 absent                                                               |
| Foreign-close liveness fallback | insufficient                   | executed and persisted disconnect                                                   |
| Post-probe lease                | `CLOSED_CLEANLY`               | `TERMINAL_DISCONNECTED`                                                             |
| History preserved               | no                             | yes                                                                                 |
| stale/ambiguous findings        | absent                         | absent                                                                              |

## Closure validation

- Focused workspace lease, wrong-connection, wrong-PID/live/dead-runtime,
  terminalization, provider supervision, binding, and Codex wait-control
  tests: PASS.
- Dedicated `AgentNextActionServiceTest` wake/continuation regression: PASS.
- MCP abnormal-stdio, terminal seal/rebind, no-change completion, and
  SYN-039 rejected-snapshot tests: PASS.
- MCP package architecture and exact catalog: PASS; exactly 10 tools.
- MCP contract catalog tests: PASS.
- `:workspace:javadoc :mcp:javadoc`: PASS.
- `:cli:platformBundle :cli:bundleSmokeTest --rerun-tasks`: PASS.
- `scripts/agent-validate-deferred.ps1`: PASS, 9 entries.
- `git diff --check`: PASS; only line-ending normalization warnings.
- The broader process-heavy MCP selections remain
  `INCOMPLETE / HOST TIMEOUT`, not pass claims.

## Boundary confirmations

The five unrelated lifecycle files remained excluded from the SYN-041
closure evidence and were not staged or changed by this closure run:

- `workspace/src/main/java/org/synesis/workspace/lifecycle/GitProcessRunner.java`
- `workspace/src/main/java/org/synesis/workspace/lifecycle/ProcessCommandRunner.java`
- `workspace/src/main/java/org/synesis/workspace/lifecycle/RepositoryPortabilityService.java`
- `workspace/src/test/java/org/synesis/workspace/lifecycle/ProcessCommandRunnerTest.java`
- `workspace/src/test/java/org/synesis/workspace/lifecycle/RepositoryPortabilityServiceTest.java`

No generalized identity architecture, provider migration, MCP tool, Doctor
semantic change, SYN-039 change, SYN-040 redesign, SYN-042, push, tag,
release, or publication was performed.
