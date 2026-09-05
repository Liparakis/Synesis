# SYN-051 fresh Worker-A persistence validation — 2026-09-05

## Classification

**PARTIAL.** The successful fresh lane proved the corrected generation-1
same-process lifecycle through a real Codex turn and durable provider state.
It is not PASS-A because the live window did not independently exercise a
pending-state authority rejection and did not independently query the MCP
child's membership in the exact Job handle. Those are evidence gaps, not
failures of the observed lifecycle. SYN-051 remains **ACTIVE / PARTIAL**.

A preliminary fresh lane was abandoned by the disposable harness after
`turn/started`, before trusted completion could be observed. It was normally
closed, left no claim mutation, and was never reused. The successful run below
used a different fresh binding, claim, launcher, and generation.

## Starting gates

- Synesis starting HEAD: `7ae7b9129ecb575d47acd6d7620ab2a8edce63f1`.
- Starting Synesis status: clean.
- Runtime source baseline: `a7697bbb5de83ced8b61b275056f9204e4467fbc`;
  production/runtime paths were unchanged after that baseline.
- Target: `C:\Users\Liparakis\Desktop\SkibidiToilert`, branch `main`,
  tracked HEAD `8cf929c4def2a5d900f654c5b99d9ebef8bc972e`.
- Target starting status had only pre-existing untracked `probe-runtime/`
  evidence. No historical lane was reused and no `.synesis` state was edited.
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe`,
  Temurin `25+36-LTS`.
- Process-local property: `-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`.
  No global Java, network, TEMP, Codex, or Synesis setting was changed.
- Selector preflight: PASS.
- Minimal IPv4 `HttpServer` create/start/stop preflight: PASS.
- Artifact hashes were recomputed before target state creation and matched:

| artifact | SHA-256 |
| --- | --- |
| `workspace/build/libs/workspace-0.1.0-SNAPSHOT.jar` and installed copy | `af41102bb20b03d87c76c3dd8547840905d16f0338443ec039253e6c56248f6a` |
| `mcp/build/libs/mcp-0.1.0-SNAPSHOT.jar` and installed copy | `dddc6df0decc0530dc30d6809ded118b9cf0e801ad7238b73aa53ab90bae0bfd` |
| `cli/build/libs/cli-0.1.0-SNAPSHOT.jar` and installed copy | `438fbea4ffbf739a530233814db44b53f928f626b3b48b10c263ac6312ed2467` |
| `cli/build/native-mcp/windows-x64/synesis-mcp.exe` and installed copy | `21c07b3ee2653fc262e150ed92144adceed7f67dd48e46f78edd24918ed3d8eb` |

No rebuild was performed during this validation because provenance matched.

## Successful fresh lane

The supported application flows generated the binding, participant, intent,
and claim. The exact values were:

- Binding A: `session-e3783a86-78c4-41c5-965c-6fb10e854781`.
- Participant A: `agt_e2d789e8-0b92-378f-9995-452c5afcaee2`.
- WorkIntent A: `c9feac30-0ac9-32e2-9954-30e51a9a3d5d`, version 1,
  status `ANNOUNCED`.
- Exact Claim A: `probe-runtime/persistence-live-a-cfb5d260-1ebc-4b1e-a186-51c4bc996da4.txt`.
- Claim selector: one `PATH_EXACT` selector; no out-of-claim selector.
- Claim marker written by the managed worker: one line,
  `SYN051-LIVE-A-8674a4fd-0e13-44c9-8175-2f366fee50bb`.

One `ManagedCodexProcessLauncher` instance was retained in one live JVM from
`prepareFirst` through the real production `ProjectRuntimeHost` START route.
Preparation returned generation `1`, attachment `PENDING_ACTIVATION`,
provider thread `null`, and proof digest
`22026ac3fdd07e86187fe5671b2beb71f66598c9fc616bed79688f0860e862c2`.
The raw proof remained only in the launcher’s volatile state; it was not
printed or persisted. Ownership before START was absent.

The real START succeeded through the production host and loopback HTTP route.
The same launcher, binding, generation, and pending proof scope were used;
no second preparation, proof, binding, or generation occurred. App Server A1
was PID `25220`; MCP A1 was PID `19252`, a descendant of that App Server.
The production launcher returned only after the Windows supervisor’s
assignment-before-resume and ownership gate passed. The supervisor path is
`CREATE_SUSPENDED → AssignProcessToJobObject → ResumeThread`, with kill-on-close
configured. Direct child-to-exact-Job membership was not separately queried in
this run, so that subclaim remains unresolved.

The generation-1 journal showed App Server initialization, `thread/start`,
MCP startup, and the same-App-Server `thread/started` event. No bootstrap App
Server was used. The pending MCP transport was present while the attachment
was pending, and no managed authority was observed before activation; a
separate harmless pending-state authority rejection was not issued, so the
canonical rejection string is not independently recorded here.

Thread A returned by that same App Server was
`01a06f47-0e24-7ce2-9e9f-bf4fdb36c18f`. Production acquired the unique owner
`(CODEX, Thread A) → Binding A` at ownership revision 1, broker-pinned the
same thread, finalized the attachment, and promoted generation 1 to `ACTIVE`.
The MCP PID was `19252` before and after activation, demonstrating same-
connection promotion. A post-activation managed operation succeeded.

Immediately before the initial turn, the observed state was `IDLE` with
attachment `ACTIVE`, Thread A present, and `persistenceReady=false` at
ownership revision 1. The exact Claim A was used unchanged in the single
initial `turn/start` prompt. The managed worker created only the claimed file.

The trusted production evidence journal is:

`C:\Users\Liparakis\Desktop\SkibidiToilert\.synesis\local\runtime\codex-lifecycle\evidence\session-e3783a86-78c4-41c5-965c-6fb10e854781\generation-1.jsonl`

It contains the actual `turn_completed` evidence category for Thread A’s
turn `01a06f47-0f2d-7a71-aa81-91a0ea83d707`, status `completed`, and the
journal manifest reports `evidenceComplete=true`, zero dropped events.
After that trusted event, Synesis recorded:

- readiness before: `false`, ownership revision `1`;
- readiness after: `true`, ownership revision `2`;
- lifecycle checkpoint: `COMPLETED`, revision `8` at the transition;
- attachment, binding, participant, WorkIntent, claim, thread, and generation
  identities unchanged.

## Provider durability and target result

Read-only inspection of the normal provider-owned state found:

- `C:\Users\Liparakis\.codex\state_5.sqlite`: exactly one `threads` row for
  Thread A, with rollout path
  `C:\Users\Liparakis\.codex\sessions\2026\09\05\rollout-2026-09-05T04-55-22-01a06f47-0e24-7ce2-9e9f-bf4fdb36c18f.jsonl`.
- `C:\Users\Liparakis\.codex\thread_history_1.sqlite`: one completed
  `thread_turns` row for Thread A and 29 materialized history items.
- The referenced rollout exists, is 509,262 bytes, and contains 100 lines
  including the completed turn.

The assigned worktree was
`C:\Users\Liparakis\AppData\Local\Synesis\workspaces\2449acd9-6b8f-450e-9287-3f6a6032e489\worktrees\session-e3783a86-78c4-41c5-965c-6fb10e854781`.
Its only new status entry was the exact untracked Claim A file. It contains
one line, and the out-of-claim result was empty. The control checkout retained
only its pre-existing untracked historical `probe-runtime/` evidence.

No provider credentials were read, copied, logged, or modified. No raw proof
appears in the durable attachment record, journal, provider database, rollout,
target, or global Codex configuration. Only the proof digest is recorded.

## Stop and repository result

The host was closed normally after the evidence was captured. This incidentally
left the generation-1 attachment `DISCONNECTED`, the lifecycle checkpoint
`STOPPED`, and root PID `-1`; ownership remained durably `ACTIVE` and
`persistenceReady=true`. No deliberate death test, replacement, A2, cross-
process resume, Worker B, or full SYN-049 acceptance was run. No trusted death
evidence was intentionally produced. Normal close did incidentally create the
generation-1 receipt through the production
`WindowsJobObjectProcessTreeSupervisor` (`rootPid=25220`, diagnostic
`managed_job_empty`); it was not used for replacement authorization and no
death test was performed.

Only this evidence record and the corresponding agent checkpoint/state updates
are allowed changes. No production source or build configuration changed. No
push occurred. Final Synesis HEAD and status are recorded by the checkpoint
commit that contains this document.

## Remaining blocker and next action

SYN-049 remains `PARTIAL`; SYN-051 remains `ACTIVE / PARTIAL`. The remaining
blocker for PASS-A is narrow and exact: independently capture a harmless
pending-state authority rejection and independently prove the MCP child is in
the exact App Server Job handle in a future bounded evidence run. A separate
bounded A1 trusted-death → A2 exact-resume/replacement validation is **not yet
authorized by this PASS classification**.

Immediate next action: checkpoint and stop; do not start Worker B, invoke
replacement, or broaden acceptance until the two evidence gaps are separately
authorized and scoped.
