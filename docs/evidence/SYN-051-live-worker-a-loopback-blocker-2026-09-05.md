# SYN-051 fresh single-worker managed-runtime validation — loopback blocker

Date: 2026-09-05
Classification: **PARTIAL — STOPPED ON FIRST MATERIAL HARNESS/HOST FAILURE**

## Scope and safety boundary

This was a bounded Worker-A-only real-runtime validation. It used direct local
application calls in the target project and did not use Synesis MCP or Synesis
coordination tools against this source repository. Worker B, A1-to-A2
replacement/recovery, deliberate root termination, and full acceptance were
not authorized and were not invoked. The pass was stopped at the first
material failure; no retry was attempted.

The production source repository was clean at source commit
`bc55302dbecd5e744f9d91f0f23318db087d3071`. The runtime source baseline
remained `a7697bbb5de83ced8b61b275056f9204e4467fbc`; the intervening source
repository change was documentation-only. Installed artifacts were not
rebuilt during this pass and retained the previously matched hashes:

| Artifact                         | SHA-256                                                            |
|----------------------------------|--------------------------------------------------------------------|
| workspace JAR and installed copy | `af41102bb20b03d87c76c3dd8547840905d16f0338443ec039253e6c56248f6a` |
| MCP JAR and installed copy       | `dddc6df0decc0530dc30d6809ded118b9cf0e801ad7238b73aa53ab90bae0bfd` |
| CLI JAR and installed copy       | `770c7df632d07481365e6177fc1559eb2ae8568a9c44619319d447d9b29c5c58` |
| native MCP and installed copy    | `10d63a1c7f763ac44723c3c4ccde9f270c14a7fe0048f4719bc0ec5d32133dca` |
| installed `synesis.bat`          | `b8bcb137eb83659360f332c6ca6c17b328300844f83f28f82977cdec56c0670e` |

The target was `C:\Users\Liparakis\Desktop\SkibidiToilert`, on `main`, at
`8cf929c4def2a5d900f654c5b99d9ebef8bc972e`, with clean tracked state and only
the pre-existing untracked `probe-runtime/` directory. Codex was
`codex-cli 0.153.0` and Java was Temurin OpenJDK 25+36-LTS.

## Fresh lawful lane

The preparation-only lane from the preceding pass was not reused. A new lane
was created through the target application's supported session and
collaboration services:

- connection instance: `syn051-live-a-40ff76be-a20b-4148-99d1-d04a153152b7`
- participant: `agt_81dcd2a3-5d43-3cb5-9068-6a68b5f2f699`
- WorkIntent: `c340aab7-376f-3172-b43f-2580c3045603`
- claim: `probe-runtime/persistence-live-a-40ff76be-a20b-4148-99d1-d04a153152b7.txt`
- binding: `session-756b644a-bac0-479c-be24-577581f286c6`
- assigned worktree:
  `C:\Users\Liparakis\AppData\Local\Synesis\workspaces\2449acd9-6b8f-450e-9287-3f6a6032e489\worktrees\session-756b644a-bac0-479c-be24-577581f286c6`

The binding was `BOUND` with verified verification and trust. Its assigned
worktree was clean and the exact claim file did not exist before the probe.
No production authority record was manually edited.

## First material failure

The disposable caller compiled successfully against the installed CLI
distribution and constructed one live `ManagedCodexProcessLauncher`, the
production `ProjectRuntimeHost`, and its JDK loopback HTTP boundary. Before
`prepareFirst` could run, `HttpServer.create(...)` failed:

```text
java.io.IOException: Unable to establish loopback connection
caused by java.net.SocketException: Invalid argument: connect
    at java.net.PipeImpl / WEPollSelectorProvider
```

The exception occurred while the JDK 25 loopback server was being created.
Consequently, the production lifecycle did not receive START and no managed
runtime boundary was exercised. The caller's normal `finally` path closed the
constructed host; it recorded no attachment, no generation, and no death
receipt. The harness reported `REPLACEMENT_INVOKED=false`,
`WORKER_A2_INVOKED=false`, and `WORKER_B_INVOKED=false`.

This is a harness/host compatibility blocker, not evidence of a provider
thread, MCP, proof, lifecycle, or model-turn failure. It is nevertheless a
material failure for this validation because the requested real boundary could
not be reached, so the stop rule applies.

## Not reached

The following remain **not reached / no evidence** in this pass: the actual
START class/service and signature; same-launcher proof lifetime; generation,
attachment, proof digest, provider thread, ownership, or START result; App
Server/MCP PIDs and Job membership; assignment-before-resume; pending
transport quarantine; early authority rejection; bootstrap absence;
same-App-Server `thread/start`; Thread A ownership and persistence readiness;
attachment activation; pre-turn claim alignment; a real Worker-A turn;
trusted `turn/completed`; provider DB/history/rollout inspection; target claim
mutation; and any persistence-ready transition.

No credential contents, file-backed auth, global provider configuration, raw
proof, or secret was read, copied, logged, or modified. No Codex or MCP child
from this probe remained after the failure. Existing unrelated desktop Codex
and Synesis MCP processes were not touched.

## Final state and exact continuation

The target Git state remained at `8cf929c4def2a5d900f654c5b99d9ebef8bc972e`,
with no tracked changes and only the pre-existing `probe-runtime/` untracked
directory. The fresh authority lane remains preserved for audit; it has no
managed attachment or lifecycle checkpoint from this failed attempt. SYN-049
remains `PARTIAL`; SYN-051 remains `ACTIVE / PARTIAL`.

The exact next action is a separately authorized compatibility investigation or
minimal fix for the JDK 25 loopback `PipeImpl`/`WEPollSelectorProvider` invalid
argument failure, followed by a new fresh lawful Worker-A lane that keeps
`prepareFirst` and START in the same live caller. This lane must not be reused,
and no A2 or Worker B work is authorized by this evidence.
