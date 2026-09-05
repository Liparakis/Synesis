# SYN-051 fresh Worker-A A1/A2 runtime validation — 2026-09-05

## Classification

**PARTIAL.** This fresh lane passed the managed generation-1 lifecycle and
provider persistence boundary, but the one real provider turn did not perform
the required exact-claim mutation. The stop rule therefore ended the lane
before controlled A1 HARD_STOP, replacement preparation, or A2 resume.

This is not PASS-A or PASS-B. The persistence observation itself was resolved;
the unresolved requirement is the claim-aligned provider mutation.

## Preconditions

- Synesis source start HEAD: `c49283e8fad87fb7f3d84e73d3c8d373e9558f61`.
- Runtime source baseline: `a7697bbb5de83ced8b61b275056f9204e4467fbc`.
- Worktree was clean before this evidence-only slice; no production source
  changed.
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe`,
  Temurin `25+36-LTS`.
- Process-local property only:
  `-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`.
- `Selector.open()` and minimal `HttpServer create/start/stop` passed.
- Workspace, MCP, CLI, and native MCP artifact hashes matched the locked
  values. No rebuild was performed.
- Target control checkout: `SkibidiToilert`, branch `main`, tracked HEAD
  `8cf929c4def2a5d900f654c5b99d9ebef8bc972e`. Existing untracked historical
  `probe-runtime/` evidence remained untouched.

## Fresh lane

The final lane was new and did not reuse an earlier binding, proof, thread, or
claim:

- connection: `syn051-final-a-9c429e8e-a16a-4b52-b0bb-cae5a07b3c18`
- Binding A: `session-111525f8-c7f3-41d5-895e-87670d01a587`
- Participant A: `agt_422ed006-c2ec-3beb-8d11-4e17077f291f`
- WorkIntent A: `696a0907-0a93-3f3a-8f0d-d5fb944b75a9`
- exact Claim A: `probe-runtime/persistence-final-a-79466089-4e74-4a35-8d0e-a73743de37b6.txt`

The target assigned worktree was fresh at base commit
`8cf929c4def2a5d900f654c5b99d9ebef8bc972e` and had no initial changes.

## Generation 1 evidence

One live `ManagedCodexProcessLauncher` instance retained the volatile
preparation across the call boundary. `prepareFirst` returned generation 1,
`PENDING_ACTIVATION`, `thread=null`, no provider ownership, and proof digest
`fefdf080eefa2a6ccbdbbc5c0ad97ba71ffae201d5a5c72f05be3ee5fb43ee51`.
The raw proof was not printed, persisted, or placed in evidence.

The production MCP handler received an authority-bearing `read_file` request
while the exact attachment was pending and returned
`managed_attachment_pending` without mutation. The same signed START path then
launched AppServer PID `17420`; MCP PID `15248` was observed as its descendant.
The production Windows Job supervisor independently proved the root and MCP
child were members of the same exact Job. The runtime launch contract is
`CREATE_SUSPENDED -> AssignProcessToJobObject -> ResumeThread`. No bootstrap
AppServer or second generation/proof occurred.

The same AppServer performed `thread/start` and returned Thread A
`01a07142-4851-7ca2-abb1-f8091d5fd8ce`. Ownership was acquired at revision 1,
the broker pin and thread finalization matched Binding A, and the attachment
then became ACTIVE. Immediately before the turn,
`persistenceReady=false`.

The real Codex turn completed with turn ID
`01a07142-49c7-7c63-9d3f-ba831dcfcefb`; the trusted lifecycle evidence recorded
`turn/completed`. Readiness changed from `false` at ownership revision 1 to
`true` at revision 2 for the exact `(CODEX, Thread A) -> Binding A` owner.
Binding, participant, WorkIntent, claim identity, and generation remained
unchanged.

## Claim-alignment failure

The provider rollout was complete and read-only inspection showed the exact
Thread A provider row, one completed history turn, and its rollout. During the
turn, however, the Synesis MCP interaction attempted to establish the exact
claim and reported that the exact path was already owned by active participant
`agt_422ed006-c2ec-3beb-8d11-4e17077f291f`. The agent then performed read-only
checks and ended with “no files were modified.” The exact claimed file was
absent in the assigned worktree; Git status and diff were empty. Thus the
required claim-aligned provider mutation was not proven.

Provider durability evidence, read-only:

- `C:\Users\Liparakis\.codex\state_5.sqlite`: exact Thread A row with rollout
  `C:\Users\Liparakis\.codex\sessions\2026\09\05\rollout-2026-09-05T14-09-24-01a07142-4851-7ca2-abb1-f8091d5fd8ce.jsonl`.
- `C:\Users\Liparakis\.codex\thread_history_1.sqlite`: one `completed`
  history row for the exact Thread A, ordinal 1, duration 82,736 ms.
- Rollout: 673,353 bytes, 84 lines, with the completed turn record.

## Stop and cleanup

The harness stopped at the first material claim-alignment failure. Its normal
host cleanup left the attachment `DISCONNECTED` and lifecycle checkpoint
`STOPPED`; it incidentally produced generation-1 death receipt evidence for
root PID `17420`. That was normal cleanup, not controlled A1 death evidence.

`replaceAfterTrustedDeath`, generation-2 preparation, A2, cross-process
`thread/resume`, Worker B, and full SYN-049 acceptance were not invoked. No
credentials were read, no global Java/Codex setting was changed, no Synesis
production source was changed, and nothing was pushed.

## Evidence commands

- `scripts/agent-resume.ps1`
- JDK25 preflight with the process-local UNIX temporary-directory property
- exact artifact SHA-256 verification
- disposable `C:\t\Syn051FreshWorkerA1A2.java` harness, compiled against the
  already-installed distribution
- supported-store inspection with `C:\t\Syn051PostInspect.java`
- read-only SQLite provider inspection and Git status/diff inspection
