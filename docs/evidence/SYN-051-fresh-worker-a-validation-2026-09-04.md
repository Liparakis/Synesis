# SYN-051 fresh Worker A managed-runtime validation — 2026-09-04

## Classification

**PARTIAL — stopped before managed App Server launch.** This pass did not
produce a managed generation, proof, provider thread, or model turn. The
failure was in the disposable caller harness: it submitted START without
first invoking the managed launcher's required `prepareFirst` step. It is not
evidence of a Codex/provider failure and was not retried in this pass.

## Source and build

- Source HEAD: `a7697bbb5de83ced8b61b275056f9204e4467fbc`, `master`, clean at
  start and stop.
- Java: Temurin OpenJDK 25+36-LTS. Windows: Windows 11 Pro 10.0.26200 x64.
- Codex: `codex-cli 0.153.0`,
  `C:\Users\Liparakis\AppData\Local\Programs\OpenAI\Codex\bin\codex.exe`,
  SHA-256 `0f8ed9678bca539aa6517adb0c8d50ad9a94ff5df4d21e149ae700d219fff69d`.
- Build: `TEMP=C:\t TMP=C:\t .\gradlew.bat clean :cli:installDist
  --no-daemon --max-workers=1 --console=plain`; BUILD SUCCESSFUL.
- Produced and installed workspace, MCP, and CLI JAR hashes matched. The
  local launcher reported `BUILD_COMMIT=UNKNOWN`; source HEAD is the
  authoritative provenance identity.
- Focused workspace/lifecycle run: 35 tests, 0 failures, 0 errors, 0 skips.
  A selected MCP server/catalog/frame test task stalled after test classes
  compiled and emitted no assertion or failure output; it was stopped and is
  incomplete evidence, not a pass.
- Deferred register validation passed. The MCP catalog remains exactly 10
  tools. No `git diff --check` finding was observed.

## Target and fresh authority

Target `C:\Users\Liparakis\Desktop\SkibidiToilert` remained on branch `main`
at `8cf929c4def2a5d900f654c5b99d9ebef8bc972e`; tracked status remained clean
and the pre-existing untracked `probe-runtime/` evidence was not edited.
Historical participants, attachments, and generations were not repaired or
terminalized. The fresh supported authority path created:

- binding: `session-8b82fd05-6c97-4f8f-bc1a-2a1ce0c56c2a`;
- participant: `agt_9db6eb10-fff3-3711-8f97-1d0b75ed40de`;
- WorkIntent: `45cb6507-0dd0-397d-aecb-51e8754b069e`;
- exact claim: `probe-runtime/persistence-a.txt`.

The claim was announced through the application flow. No claim file was
created because the lifecycle stopped before a worker process existed.

## First material boundary

A disposable Java caller created a custom production `ProjectRuntimeHost`
with `ManagedCodexProcessLauncher` and a loopback coordination server, then
submitted the immutable START request through `ProviderSessionCommand`. The
owner returned `managed_attachment_not_prepared`.

The caller had not invoked `ManagedCodexProcessLauncher.prepareFirst` after
creating the fresh authority and before START. The error occurred at the
launcher boundary, before `launch`, so these gates were not reached:

- no Proof P_A1 was minted and no proof digest exists;
- no Attachment Generation 1 or pending attachment record was prepared;
- no App Server PID, MCP PID, Job, suspended launch, assignment, or resume;
- no early MCP transport, `thread/start`, returned Thread A, ownership,
  broker pin, finalization, activation, turn, or `turn/completed` event;
- no `persistenceReady` transition and no provider durable-thread evidence.

No App Server was killed, no death evidence was generated, no generation 2
was created, and Worker B was not executed. No provider credential was read,
copied, logged, or modified. The global Codex config scan found no attachment
assignment or raw proof-like value. No production source or ADR changed; this
evidence record is the only source-repository documentation change for the
pass.

## Exact continuation

Do not retry this stopped acceptance pass. First decide or separately
authorize the supported managed caller sequence that invokes `prepareFirst`
before START; then run a new fresh single-worker validation if authorized.
Preserve the fail-closed launcher behavior and do not start Worker B, perform
A1-to-A2 replacement, or alter historical target state.

## Caller-contract-only preparation pass — 2026-09-05

### Classification

**PASS-A for the bounded preparation boundary; SYN-051 remains ACTIVE / PARTIAL
overall.** This pass invoked the actual production `prepareFirst` operation for
one lawful Worker-A lane and stopped immediately afterward. It did not call
START, launch Codex, start an App Server, start MCP, or perform provider-thread
work.

### Starting state and lane selection

- Starting source HEAD: `a7697bbb5de83ced8b61b275056f9204e4467fbc` on `master`.
- Starting target: `C:\Users\Liparakis\Desktop\SkibidiToilert`, `main`,
  `8cf929c4def2a5d900f654c5b99d9ebef8bc972e`; tracked Git status remained
  clean and the pre-existing untracked `probe-runtime/` directory was not
  edited.
- The requested prior candidate binding
  `session-8b82fd05-6c97-4f8f-bc1a-2a1ce0c56c2a` was not reusable: supported
  state inspection showed lifecycle `FAILED`, diagnostic
  `managed_attachment_not_prepared`, no managed attachment, and no provider
  ownership. It was not reset or mutated.
- Exactly one new lane was created through the production session-resolution
  and collaboration services. No project was created and no `.synesis` file
  was manually edited.

Selected Worker-A authority:

- Binding A: `session-444ee24b-1ff8-4111-b818-787f900088a0`.
- Participant A: `agt_c74b5d08-5a0b-3017-bce9-652498da186c`.
- WorkIntent A: `befca654-af37-31cb-a605-f6bfbec7a8f0`.
- Claim A: exact selector
  `probe-runtime/caller-contract-preparation-62178e4d-e95a-4529-845a-22ae1d8fbfff.txt`,
  acquired at epoch 1, producer role.

Before preparation, Binding A was `BOUND` with `VERIFIED` workspace
verification and `VERIFIED` provider trust. Participant A was `ACTIVE` and
WorkIntent A was `ANNOUNCED`. The managed attachment and provider-thread
ownership were absent; there was no lifecycle binding record because START had
not been called. Binding A was neither terminal nor revoked.

### Production caller contract

The declaring class is
`org.synesis.workspace.lifecycle.codex.ManagedCodexProcessLauncher`.
The exact method is:

```text
public ManagedAttachmentRecord prepareFirst(
    LifecycleControlRequestEnvelope.AuthorityContext authority) throws Exception
```

Source location:
`workspace/src/main/java/org/synesis/workspace/lifecycle/codex/ManagedCodexProcessLauncher.java:99-122`
(`prepareFirst` declaration at line 107). It requires non-null Codex authority;
the caller must already have a project location, exact binding, verified
assigned worktree, active participant, announced WorkIntent, and acquired
claim. The attachment store must not already contain a record for that binding.
In normal-provider-home managed mode it uses the provider-owned home without
copying or inspecting credential contents, issues a first pending attachment,
stores the raw proof only in the launcher's private volatile preparation, and
durably writes only the proof hash.

The production START caller is
`workspace/src/main/java/org/synesis/workspace/application/provider/ProviderSessionCommand.java:67-129`.
Its existing sequence resolves the session, announces the claim, resolves the
exact binding, builds `AuthorityContext`, and submits START, but it does not
call `prepareFirst`. No production caller or test invoking
`ManagedCodexProcessLauncher.prepareFirst` was found. The nearest lower-level
test is `workspace/src/test/java/org/synesis/workspace/application/provider/continuity/ManagedAttachmentServiceTest.java:312-330`,
which tests `issuePending`, not the launcher caller boundary. A disposable
caller therefore constructed the smallest supported sequence from
`AgentSessionService.resolveSessionContext` →
`WorkspaceCollaborationService.announce` → exact authority construction →
`ManagedCodexProcessLauncher.prepareFirst`.

### Preparation result

- Invocation result: successful; no exception or fallback occurred.
- Generation: exactly one `Generation 1`; the attachment directory contained
  exactly one record for Binding A.
- Attachment: `PENDING_ACTIVATION`.
- Provider thread: unresolved (`threadId=null`).
- Proof digest: `897b305ba8aa01fc162a5466a4c0de49c35250a73161717a2c362b2d2e118591`.
- Raw proof persistence: not persisted; the durable JSON had no raw-proof field,
  and the in-memory prepared record was present during the invocation. The raw
  proof was not printed, recorded, passed in arguments, or written to Codex
  configuration.
- `expectedInitialThreadId(authority, 1)` returned `null`, confirming the
  prepared first generation selects the later `thread/start` path rather than
  a provider-thread resume.
- Provider-thread ownership after preparation: absent.
- Binding, participant, WorkIntent, and claim preservation: all four exact
  before/after comparisons passed.

The source START guard at
`workspace/src/main/java/org/synesis/workspace/lifecycle/codex/ManagedCodexProcessLauncher.java:161-169`
requires an in-memory prepared record with status `ACTIVE` or
`PENDING_ACTIVATION` and the matching generation. The created record satisfies
that guard for generation 1; no `launch` call was made to test it.

The process snapshot was unchanged: no target App Server or MCP process was
launched. The only observed App Server-shaped PID, 13772, was the pre-existing
Codex desktop app-tools server and was unchanged; no Synesis MCP PID was
created. No process was killed.

### Boundary and continuation

This is PASS-A for the caller-contract/preparation objective, not runtime
acceptance. START, Job supervision, App Server, MCP, `thread/start`, provider
ownership, activation, turns, and persistence readiness remain deferred. The
disposable caller exited after inspection, so its volatile raw proof is no
longer available to a new process; a later runtime pass must keep preparation
and START in the same trusted live caller or perform a new supported
preparation. START was not called in this pass.

SYN-049 remains `PARTIAL`. SYN-051 remains `ACTIVE / PARTIAL` overall, with
PASS-A limited to this narrow preparation boundary. No production source or
ADR changed, no historical target generation was edited, and no push occurred.

Exact next action: if separately authorized, use a live caller that invokes
`prepareFirst` and then STARTs this same prepared lane in one bounded pass;
otherwise do not call START or retry the runtime probe.
