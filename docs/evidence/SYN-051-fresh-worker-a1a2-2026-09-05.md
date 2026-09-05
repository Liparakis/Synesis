# SYN-051 fresh Worker-A A1/A2 runtime validation — 2026-09-05

## Classification

**PASS-A.** One fresh lawful Worker A completed the corrected generation-1
managed lifecycle, one real exact-claim Codex turn, trusted persistence, a
controlled A1 hard stop with a trusted death receipt, exact generation-2
resume, and a second exact-claim turn on the same provider Thread A. No
Worker B or full SYN-049 acceptance was run.

## Preconditions and production fix

- Synesis source start HEAD: `ed5349245d7536c5f29e78a6fa25028d006cce9c`;
  starting worktree clean.
- Runtime source baseline: `a7697bbb5de83ced8b61b275056f9204e4467fbc`.
- A genuine managed hard-stop race was found: the process-exit observer could
  advance the lifecycle checkpoint while `HARD_STOP` held a stale revision,
  producing `lifecycle revision regression` after valid
  `FORCED/managed_job_empty` evidence. The minimal fix serialized hard-stop
  evidence persistence and final transition against a fresh checkpoint. It is
  committed as `072581d`.
- Focused tests passed:
  `CodexLifecycleWaitControlTest` and `CodexEvidenceJournalTest`.
- The authorized rebuild/install passed with `--max-workers=1` and the
  process-local AF_UNIX workaround. Produced and installed hashes matched:
  - workspace:
    `7b3b5066f46140cae88a3357f0156e45cfb8b432ea1e07f6844983fe5141163a`
  - MCP:
    `dddc6df0decc0530dc30d6809ded118b9cf0e801ad7238b73aa53ab90bae0bfd`
  - CLI:
    `438fbea4ffbf739a530233814db44b53f928f626b3b48b10c263ac6312ed2467`
  - native MCP:
    `d24737530957fbe82ac05f3d5f43588d5357fdc8eff7377712e3151e0bc25c30`
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe`,
  Temurin `25+36-LTS`. The process-local property was
  `-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`.
  `Selector.open()` and minimal `HttpServer create/start/stop` passed.
  `JAVA_TOOL_OPTIONS`, `GRADLE_OPTS`, `JDK_JAVA_OPTIONS`, and
  `_JAVA_OPTIONS` were empty at process, user, and machine scope after the
  run.
- Target baseline was `SkibidiToilert`, control branch `main`, tracked HEAD
  `8cf929c4def2a5d900f654c5b99d9ebef8bc972e`; existing historical untracked
  `probe-runtime/` evidence was left untouched.

## Fresh Worker A and generation 1

This lane did not reuse any historical binding, participant, WorkIntent,
claim, proof, provider thread, or process. It was created through supported
application flows:

- connection: `syn051-final-a-e114cd42-afba-47b2-b82f-f0cd64f470a7`
- Binding A: `session-b57fcc4c-40f8-4df2-b846-99b34b132d02`
- Participant A: `agt_f0ff955a-b7cb-3bbf-b3a8-cc65661f0b00`
- WorkIntent A: `3be7082a-1357-3ce6-8c18-8f3dc7f5620f`
- exact Claim A:
  `probe-runtime/persistence-final-a-c033a0f0-bcd8-4673-9f4a-8e3a2e51de05.txt`
- assigned worktree:
  `C:\Users\Liparakis\AppData\Local\Synesis\workspaces\2449acd9-6b8f-450e-9287-3f6a6032e489\worktrees\session-b57fcc4c-40f8-4df2-b846-99b34b132d02`

One live `ManagedCodexProcessLauncher` instance retained the volatile raw
proof across `prepareFirst -> START`. `prepareFirst` returned generation 1,
`PENDING_ACTIVATION`, provider thread unresolved, and ownership absent. The
recorded proof digest was
`d252fb6ff074696f077d2bd0e335ffe8cf4e9f47807044711314bb60bfbb16b7`.
The raw proof was never printed, persisted, or placed in evidence.

The production MCP handler accepted the pending connection but rejected one
authority-bearing `read_file` operation with `managed_attachment_pending` and
made no mutation. START consumed the same launcher, Binding A, generation 1,
and preparation; no second proof or generation was created. AppServer A1 was
PID `20140`; MCP A1 was PID `21416`. The launch ordering was
`CREATE_SUSPENDED -> AssignProcessToJobObject -> ResumeThread`, with the
AppServer observed before its MCP child. The production
`WindowsJobObjectProcessTreeSupervisor` proved both root and MCP child in the
same Job. No bootstrap AppServer occurred.

The same AppServer performed `thread/start`, returning Thread A
`01a07150-405b-7931-b030-9af8e0dec788`. Provider ownership was
`(CODEX, Thread A) -> Binding A`, revision 1, with the broker pin and exact
thread finalization committed before activation. Generation 1 became ACTIVE;
`persistenceReady=false` immediately before the turn. The same MCP connection
was promoted from quarantined to authoritative; no replacement MCP was used
within generation 1.

The first real Codex turn was claim-aligned and returned trusted
`turn/completed` with turn ID
`01a07150-416f-7381-89c9-31381930e4be`. Readiness changed
`false/revision 1 -> true/revision 2` for the exact owner. Binding,
participant, WorkIntent, claim, Thread A, and generation 1 were unchanged.
The assigned worktree contained exactly one changed path: Claim A, with the
deterministic A1 marker and no out-of-claim change.

## Controlled A1 death and generation 2

Supported `HARD_STOP` returned `success=true`, `STOPPED`, and
`FORCED/managed_job_empty`. The trusted generation-1 death receipt recorded
root PID `20140` and supervisor
`WindowsJobObjectProcessTreeSupervisor`. Replacement preparation returned
generation 2, `PENDING_ACTIVATION`, a distinct proof digest
`5624d21de0252033bdc894efd9953340a36bbe34b95a40481d99681e2e42c0fd`, the
same Thread A, and the same Binding A. No proof recovery or manual state
editing occurred.

Exact `RESUME` launched AppServer A2 PID `5652` and MCP A2 PID `20648`, again
with root and MCP in the production Job and assignment before resume. It used
the exact prior Thread A; no cross-process thread substitution occurred. The
second real turn returned trusted `turn/completed` with turn ID
`01a07151-4211-7352-ad5d-e94e7270111e`; the generation-2 lifecycle checkpoint
reached `COMPLETED`. Readiness correctly remained `true/revision 2 -> true/revision 2`.
Claim A then contained exactly the A1 and A2 markers, with no out-of-claim
path.

## Provider durability and cleanup

Read-only provider inspection for exact Thread A found:

- `C:\Users\Liparakis\.codex\state_5.sqlite`: exact thread row and rollout
  path `C:\Users\Liparakis\.codex\sessions\2026\09\05\rollout-2026-09-05T14-24-39-01a07150-405b-7931-b030-9af8e0dec788.jsonl`.
- `C:\Users\Liparakis\.codex\thread_history_1.sqlite`: two exact Thread-A
  turns, both `completed`.
- Rollout file exists and contains the provider records for both turns.

Normal host cleanup stopped A2 and left the final lifecycle checkpoint
`STOPPED`, `evidenceComplete=true`, and no current Codex/MCP process. It
incidentally produced generation-2 death receipt evidence. Replacement was
not invoked after this cleanup. No Worker B or SYN-049 acceptance ran. No
credentials were read or copied. The process-local JVM property was not
persisted or added to Synesis configuration. The target control checkout
remained on `main` with its historical untracked evidence only; the legitimate
Claim-A mutation remains in the assigned worktree for evidence.

## Evidence commands

- `scripts/agent-resume.ps1`
- JDK25 selector/HTTP preflight with the process-local property
- bounded `:workspace:test` focused tests and `:cli:installDist`
- disposable `C:\t\Syn051FreshWorkerA1A2.java` harness
- supported-store inspection with `C:\t\Syn051PostInspect.java`
- read-only provider SQLite/history/rollout inspection
- read-only target Git status/content inspection
