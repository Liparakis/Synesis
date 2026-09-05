# SYN-051 fresh two-worker/restart acceptance — 2026-09-05

## Classification

**PASS-A for the bounded SYN-051 two-worker/restart acceptance.** This fresh
run proved independent managed Worker A and Worker B identity, authority,
provider-thread ownership, real claim-aligned work, trusted generation-1
death, exact generation-2 resume, and post-resume real turns. It does not
close the broader SYN-049 integration/workgroup acceptance.

## Gates and provenance

- Starting Synesis HEAD: `d6ab7e0`; the worktree contained only the pending
  narrow stream-ordering fix that was built for this run.
- JDK: Temurin `25+36-LTS`,
  `C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe`.
- Process-local property only:
  `-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`.
- `Selector.open()` passed; minimal `HttpServer` create/start/stop passed.
- Produced and installed hashes matched exactly:
  - workspace: `a7049e8b0a9c7de792038c254f1c546515de1c02757ec97834ac67be5e6863a1`
  - MCP: `dddc6df0decc0530dc30d6809ded118b9cf0e801ad7238b73aa53ab90bae0bfd`
  - CLI: `07f6770592f923a78f3071be39e27d8dea4dfd13b99f99acbd5a8937d98cd0bb`
  - native MCP: `37a2db02909d434a6a8135a6326e2592ecf5f2e83c873e146e589b95a27f97ea`
- Target baseline: branch `main`, tracked HEAD
  `8cf929c4def2a5d900f654c5b99d9ebef8bc972e`; only pre-existing untracked
  `probe-runtime/` existed in the control checkout.
- Global Java/Codex settings were not changed. The workaround remained
  process-local. No credentials were read, copied, logged, or modified.
- No historical binding, proof, thread, or worktree was reused.

## Fresh workers and generation 1

| | Worker A | Worker B |
|---|---|---|
| Connection | `syn051-final-ab-a-99b9f163-3e17-456b-ad86-4293bc677925` | `syn051-final-ab-b-4ac9d1a8-a34d-4f40-9cae-1a8f1107b2dc` |
| Binding | `session-61a3ec50-0fe7-4106-9ac2-cceda3a96615` | `session-84b268af-b448-4059-9c97-cb8c73f5615f` |
| Participant | `agt_971db454-30b8-346f-a91d-91570a54bfa3` | `agt_f68d651b-150c-35e3-a2e1-8026e2ce69fc` |
| WorkIntent | `54bfa3ae-46ab-382d-907f-4db7f82394e5` | `8fa013b7-c415-3cf4-865b-7390b2ffeb0d` |
| Claim | `probe-runtime/persistence-ab-a-8c5eafea-90cd-46d4-b29d-ec93acca78d8.txt` | `probe-runtime/persistence-ab-b-01a3cc44-b042-452a-af9f-88f5e73f3337.txt` |
| Generation-1 proof digest | `35f5426f5784d00340bea2ae0b3941d8e43abc2a29f5fbd2d9c0f7f1d3bfefe7` | `564a5662b41e4f846146633ba7e5a016ffc98d70a6b6142d9c41c52b151e0cee` |

Both fresh preparations returned generation 1 `PENDING_ACTIVATION`, unresolved
provider thread, absent ownership, and raw proof retained only in volatile
launcher state. Cross-proof probes in both directions returned
`managed_attachment_rejected`.

Worker A launched AppServer PID `15076`, MCP PID `14984`; Worker B launched
AppServer PID `18512`, MCP PID `22452`. Both used
`CREATE_SUSPENDED -> AssignProcessToJobObject -> ResumeThread`; both roots and
MCP children were in their exact production Windows Job. Pending transport
survived while pending authority remained denied. Each worker then used the
same AppServer connection for `thread/start`, acquired unique ownership, was
promoted to `ACTIVE`, and retained the same MCP connection through promotion.

Generation-1 provider threads were distinct:

- A: `01a071f2-2e42-7f23-8bf7-4f4753fb4e63`
- B: `01a071f2-2e83-7c52-ad9e-9fc148b5e87d`

Immediately after ownership, each ownership record was revision 1 with
`persistenceReady=false`. The first real turns completed through trusted
`turn/completed` events:

- A turn `01a071f2-3032-7a70-8a44-174134ae89ce`;
- B turn `01a071f2-303a-74b2-843b-fc47eb8f54d1`.

For each exact `(CODEX, Thread) -> Binding`, readiness changed
`false -> true`. The managed agent performed the mutations through Synesis
MCP only. A changed only its exact claim file and wrote marker
`SYN051-AB-A-A1-382672cf-7fe4-4a13-b1ba-8402ed24960f`; B changed only its
exact claim file and wrote marker
`SYN051-AB-B-A1-2b02bdc5-efc2-4023-9594-d8c85b26f46c`.

## Independent restart and generation 2

Worker A was stopped while B remained live. Trusted generation-1 death
evidence recorded root PID `15076`; fresh generation-2 proof digest was
`11e22b9c7fa5cbf59bb9267704369294d03dba2b3f071333f94e0fc7931c1c76`.
Generation 2 launched AppServer PID `22228` and MCP PID `14588` and resumed
exact Thread A. Its trusted turn was
`01a071f2-deb5-7d12-aee1-7bfbcadd3377`; the other worker remained live.

Worker B was then stopped while A's successor remained live. Trusted
generation-1 death evidence recorded root PID `18512`; fresh generation-2
proof digest was
`1b5800fa25637037a6380405cd8f33c7b775a36af0b2e192f77f82d962ef1178`.
Generation 2 launched AppServer PID `8052` and MCP PID `24584` and resumed
exact Thread B. Its trusted turn was
`01a071f3-890e-7823-808b-3e20e88e0451`; A's successor remained live.

Post-resume verification remained exact and contained: each worktree had
only its own claim file, with both continuation markers present. The provider
thread database contained a row and rollout path for each exact Thread A/B;
the history database contained two `completed` turns per thread; all four
rollout files existed and contained the corresponding real MCP activity and
completion evidence.

Final lifecycle records were generation 2, `DISCONNECTED` attachment,
`persistenceReady=true`, ownership revision 2, exact original binding,
participant, WorkIntent, claim, and provider thread, with checkpoint state
`STOPPED` and `evidenceComplete=true` after normal host cleanup.

## Scope, cleanup, and hygiene

- No bootstrap AppServer was used; each generation-1 thread was created by
  its own AppServer launched by the production START path.
- No A1 -> A2 thread substitution, proof recovery, stale-generation
  authority, cross-worker ownership, or latest-session fallback occurred.
- No dependency/handoff or WorkGroup terminalization was required by this
  bounded SYN-051 acceptance. The broader SYN-049 integration/workgroup
  acceptance remains separate and was not run.
- The validation host exited normally after the evidence endpoint. No Java or
  Synesis MCP child remained. Cleanup produced no additional replacement
  generation; trusted death receipts were the explicit generation-1 restart
  evidence.
- Raw proofs were never logged or persisted; only digests appear here.
  Provider credentials were not inspected. No raw proof was placed in the
  target or global Codex configuration.
- No push, tag, release, or remote mutation occurred.

## Defect and verification record

The first corrected A/B attempt exposed two production-boundary issues: a
co-running teardown race and native pipe closure while protocol readers were
blocked. The narrow fix serializes lifecycle teardown under the existing
state lock and closes protocol/stdout/stderr streams before managed-supervisor
handle release. Focused lifecycle regression and the complete workspace
`lifecycle.codex` suite passed. The runtime was rebuilt and installed after
the fix, with exact produced/installed hashes recorded above.

Final classification: **PASS-A** for SYN-051. SYN-051 may advance through
normal repository status documentation; SYN-049 remains **PARTIAL**.
