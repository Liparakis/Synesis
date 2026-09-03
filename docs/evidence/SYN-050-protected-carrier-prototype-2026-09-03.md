# SYN-050 protected-carrier prototype evidence — 2026-09-03

## Disposition

**Classification: PARTIAL.** The disposable carrier model is sound in an
isolated Windows process harness, and the installed Codex App Server can start
and resume exact threads across an App Server process restart. The tested
Codex App Server boundary did not provide a path for a Synesis-created
per-worker proof to reach the exact managed MCP bridge without becoming a
static configuration value or otherwise being forwarded by an unimplemented
adapter. SYN-050 production implementation therefore remains blocked.

This is a runtime experiment only. It does not authorize or contain a
production `RuntimeAuthenticator`, managed attachment record, CLI command,
provider configuration change, durable Synesis change, authority change, or
MCP tool change.

## Scope and preservation

- Source HEAD before the experiment: `a2c0783e357f0a3382dcfbb8133cc020cf4284c2`.
- Branch: `master`.
- The source checkout was clean before the experiment and remains clean apart
  from this evidence/planning update.
- Production Java, provider configuration, `.synesis` state, the unrelated
  stash, SYN-049, `GOONSQUAD`, and the preserved task-tracker fixture were not
  touched, restarted, re-admitted, copied, or repaired.
- The Synesis source checkout was not initialized or managed as a Synesis
  customer project. No Synesis MCP calls, manual Synesis identifiers, manual
  durable-state edits, forced lifecycle actions, or worktree copies were used.

## Disposable material and provenance

The disposable harness root is:

```text
C:\Users\Liparakis\AppData\Local\Temp\syn050-protected-carrier-20260903-01
```

The isolated carrier source is
`src\ProtectedCarrierPrototype.java`. The real-boundary observer consists of
`src\McpProbe.java` and `src\AppServerBoundaryProbe.java`. The compiled
classes, temporary Codex home, temporary App Server workspace, redacted
reports, and hash-only prototype state are all below that temp root; none are
repository files.

The provider executable used by the real-boundary probe was:

```text
C:\Users\Liparakis\AppData\Local\Microsoft\WinGet\Links\codex.exe
codex-cli 0.145.0
SHA-256 83751F15CB6A0A7B97DF67752C001E3FE1C20E18FFBFEC3FF63567296205EB6C
```

The Java runtime used to compile and launch the disposable helper was:

```text
C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe
SHA-256 6E260AF20C51A039588329374038DDF7A8C1D6362A9B8531B1B71B9764D49604
```

Disposable source hashes from the final run:

| File | SHA-256 |
| --- | --- |
| `ProtectedCarrierPrototype.java` | `877571E48FF5035E96AC4C6979073B8161F251BF002339E85DA76EC8D80B5F20` |
| `McpProbe.java` | `63420567914A638E924C2C7DF7C0FC2733EB967571FA1CE93973F7CA7440BD7F` |
| `AppServerBoundaryProbe.java` | `D10FAFBD7F4757D4128A412FDE58997F8D5FC1CD2B59A87B63664E3F200F06F3` |

## Carrier experiment

The first harness uses an anonymous inherited child-stdin pipe created by
`ProcessBuilder`. A cryptographically secure random 256-bit proof is written
after launch over that private pipe. The child never receives the proof in its
command line. The host keeps only a SHA-256 proof hash and non-secret scope
metadata in the disposable state file; the raw proof stays in process memory
and the private handshake pipe.

The handshake uses a domain-separated HMAC challenge response over worker,
thread, generation, and bridge nonce. Verification compares the expected
scope, generation, proof hash, and response. The harness exercises actual
cross-use rejection; it does not merely compare random values.

The real Codex probe uses a temporary `CODEX_HOME` and a disposable configured
MCP server. The static configuration marker is a non-secret control only. It
demonstrates that ordinary App Server configuration reaches an MCP child; it
is deliberately not treated as an authentication carrier. No proof was put
in the App Server environment, command line, config, project, MCP payload, or
Codex input.

## Isolated harness results

Command:

```powershell
javac -d C:\Users\Liparakis\AppData\Local\Temp\syn050-protected-carrier-20260903-01\classes C:\Users\Liparakis\AppData\Local\Temp\syn050-protected-carrier-20260903-01\src\ProtectedCarrierPrototype.java
java -cp C:\Users\Liparakis\AppData\Local\Temp\syn050-protected-carrier-20260903-01\classes ProtectedCarrierPrototype C:\Users\Liparakis\AppData\Local\Temp\syn050-protected-carrier-20260903-01
```

Result: `PROTOTYPE=PASS`, `CARRIER=anonymous inherited child-stdin pipe`,
`ASSERTIONS=48`.

The 48 assertions prove:

- Worker A and Worker B receive distinct proofs and distinct logical scopes.
- A accepts A and rejects B; B accepts B and rejects A.
- A's proof is rejected for the wrong thread.
- Bridge generation 1 accepts, a rotated generation 2 accepts, and the old
  generation 1 proof is rejected after replacement.
- Two synchronized replacement attempts produce exactly one winner and one
  generation advance.
- A live old runtime blocks takeover.
- Ambiguous liveness blocks takeover.
- A terminal binding blocks revival.
- Hash-only durable state contains no raw proof.
- The raw proof does not appear in child command-line material.
- Host-restart behavior is explicitly only a model result: fresh proof is
  required after restart; exact provider resume still needs the real boundary.

## Real Codex App Server boundary

The real-boundary command was:

```powershell
javac -d C:\Users\Liparakis\AppData\Local\Temp\syn050-protected-carrier-20260903-01\classes C:\Users\Liparakis\AppData\Local\Temp\syn050-protected-carrier-20260903-01\src\McpProbe.java C:\Users\Liparakis\AppData\Local\Temp\syn050-protected-carrier-20260903-01\src\AppServerBoundaryProbe.java
java -cp C:\Users\Liparakis\AppData\Local\Temp\syn050-protected-carrier-20260903-01\classes AppServerBoundaryProbe C:\Users\Liparakis\AppData\Local\Temp\syn050-protected-carrier-20260903-01 C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe codex
```

Result: `APP_SERVER_PROBE=PASS` for the exercised provider operations.

The probe used a temporary Codex home and verified the following real
operations:

- App Server JSONL initialization completed.
- One App Server process created two durable threads:
  `01a0671b-432b-7012-9013-cc0a63ae34fc` and
  `01a0671b-43c1-7d51-9a55-ded174395e7a`.
- `mcpServerStatus/list` exposed the configured disposable MCP server.
- `mcpServer/tool/call` succeeded for each exact thread and returned only
  `probe-ok`.
- A no-op `turn/start` was accepted and interrupted before model work; it was
  used only to materialize a restartable durable rollout.
- The first App Server process (PID 22316) stopped cleanly.
- A second App Server process (PID 16856) resumed the exact first thread with
  `thread/resume`; the follow-up `thread/read` used the same exact thread.
- The replacement App Server started new MCP child processes. The redacted
  MCP log records each child as a child of the corresponding App Server and
  records `rawProofPresent=false` for every observed message.
- The static configuration marker was visible to the MCP child. This is a
  negative control, not an acceptable protected credential path.

The report and MCP log are:

```text
C:\Users\Liparakis\AppData\Local\Temp\syn050-protected-carrier-20260903-01\app-server-boundary-report.txt
C:\Users\Liparakis\AppData\Local\Temp\syn050-protected-carrier-20260903-01\mcp-events.log
```

The real App Server probe therefore proves process launch, MCP child
communication, two-thread protocol correlation, and exact thread resume. It
does **not** prove that a Synesis host can pass its private inherited handle
or raw proof through Codex App Server to the exact MCP bridge. The installed
boundary exposes static MCP configuration and App Server JSON-RPC thread
correlation, but the probe found no per-thread protected-carrier input. No
provider guarantee is inferred from the successful `probe-ok` calls.

## Security and lifecycle findings

| Property | Result | Evidence boundary |
| --- | --- | --- |
| Per-worker random proof | PASS | 48-assertion isolated harness |
| A/B isolation and cross-use rejection | PASS | Actual handshake attempts |
| Generation rotation and stale rejection | PASS | Bridge replacement/replay assertions |
| Replay rejection | PASS | Consumed old proof and wrong worker/thread cases |
| Race single-winner fencing | PASS | Deterministic concurrent replacement assertions |
| Live-old and ambiguous liveness | PASS | Fail-closed isolated cases |
| Terminal-state protection | PASS | Revival attempt rejected |
| Raw proof in durable prototype state | PASS | State files contain only hashes/scope |
| Raw proof in real App Server evidence | PASS for exercised probe | No proof was supplied; redacted logs contain no raw proof |
| Model-visible leakage | PARTIAL | No proof entered the probe input or direct tool result; a full model turn was not run |
| App Server start and exact thread identity | PASS | Real `thread/start` response |
| App Server process restart and exact resume | PASS | Real `thread/resume` and `thread/read` |
| Protected attachment across App Server boundary | NOT PROVEN | No provider-controlled per-thread carrier input was available |
| Two threads in one App Server | PROTOCOL PASS / AUTH PARTIAL | Both exact thread calls work; no thread-scoped proof was tested |
| Full Synesis-host restart | PARTIAL/model-only | Fresh proof and safe old-runtime classification remain required |

The real-boundary probe did not expose the random proof at all, so its
no-leak result is bounded: it proves that the tested App Server/MCP protocol
path did not receive a proof, not that an unimplemented protected channel
would be secure. The isolated harness performs the actual secret-hygiene scan
over reports, state files, command-line material, and temporary text
evidence.

## Trust-chain classification

Proven today:

```text
Synesis-side disposable host model
→ random per-worker proof
→ anonymous inherited private pipe
→ bridge challenge response
→ hash/scope/generation verification
→ replay/race/live/ambiguous/terminal fencing
```

Separately proven at the provider boundary:

```text
Codex App Server client
→ JSONL initialize
→ thread/start
→ configured MCP child
→ thread-scoped mcpServer/tool/call
→ process restart
→ exact thread/resume
→ exact thread/read
```

Not proven as one chain:

```text
Synesis host proof
→ Codex App Server managed process
→ exact MCP bridge
→ exact Codex thread
```

The host and isolated bridge model are authority-bearing in the prototype.
The App Server thread ID is exact provider correlation and restart scope, not
an authentication secret. The static MCP marker, PID, parent PID, process
lineage, same user, repository, worktree, and same-human context are
diagnostic/contextual evidence only. No one of those values can replace the
protected proof.

Because one App Server process successfully hosted two exact threads in the
probe and no thread-scoped protected assertion was available, v1 should use
one Synesis-managed App Server process and one exact thread per logical worker.
That is a recommended containment model, not proof that the current provider
can deliver the carrier. A shared process with independently authenticated
thread attachments remains unsupported until Codex exposes a suitable
provider-controlled mechanism.

## Final disposition and next action

- SYN-050 remains `ACTIVE / DESIGN_APPROVED / PROTOTYPE_PARTIAL /
  IMPLEMENTATION_BLOCKED`.
- Production continuity implementation is not unblocked.
- Ordinary Codex and Claude stdio remain `SESSION_BOUND`.
- No production behavior, authority rule, MCP tool count, provider config,
  historical fixture, or Review/Doctor behavior was changed.
- The temporary prototype source is retained only under the disposable temp
  root for reproducibility during this investigation; it is not committed to
  Synesis. The redacted reports and commands are the durable evidence.
- The exact next action is to obtain a documented Codex-managed launch/IPC
  contract that can deliver and verify a non-model-visible per-attachment
  proof at the exact MCP bridge/thread boundary, then reopen ADR-0055 and
  create a separately authorized bounded implementation task. Do not implement
  a static secret, model-visible bearer, latest-session fallback, or PID-based
  workaround.

## Validation

- Isolated carrier harness: PASS, 48 assertions.
- Real Codex App Server boundary: PASS for start, two-thread MCP calls, and
  exact process-restart/thread-resume probe; protected-carrier delivery:
  PARTIAL/not proven.
- Raw-proof scan of real-boundary report and MCP log: PASS; no raw-proof marker
  or proof value was observed. The probe intentionally supplied no proof.
- `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`: PASS;
  active task SYN-050, checkpoint CP-0644 before this evidence update.
- `powershell -ExecutionPolicy Bypass -File scripts/agent-validate-deferred.ps1`:
  PASS.
- `powershell -ExecutionPolicy Bypass -File scripts/agent-validate-fixtures.ps1`:
  PASS.
- `git diff --check`: PASS.
- Exact MCP catalog inspection remains 10 tools; no catalog file was changed.
- `scripts/agent-doctor.ps1`: known pre-existing vague-continuation error and
  personal-absolute-path warning remain; no new Doctor behavior was opened.

## No-bypass attestation

No manual Synesis ID, durable Synesis state edit, worktree copy, worker
restart/re-admission, forced integration, latest-session fallback, authority
weakening, unsupported MCP tool, Antigravity integration, or protocol bypass
was used. The only process IDs and Codex thread IDs in this record were
returned naturally by the disposable provider probe and are diagnostic
evidence, not caller-supplied Synesis identifiers.
