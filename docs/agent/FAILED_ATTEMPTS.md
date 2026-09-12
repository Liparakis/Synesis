## 2026-09-11 — SYN-057 protocol-gap discovery

The first SYN-057 slice intentionally made no implementation attempt. Source
reconstruction found no trustworthy destination project/runtime identity in
current SLO1/SLA2 or the daemon `OPEN_URI` request. The safe result is
classification E; no resolver, Link parser duplication, unsigned project
field, runtime broadcast, or protocol edit was introduced.

Next action: obtain an explicit signed recipient-targeting contract or a
separately authorized protocol task before resuming production work.

## 2026-09-11 — SYN-057 activation and discovery boundary

No failed implementation attempt existed at activation. The operator explicitly
authorized SYN-057 and moved the sole active marker from completed-current-
scope SYN-056. Production routing remains untouched until the identity/trust,
daemon discovery, and pre-dispatch deep-link target model is reconstructed.

## 2026-09-11 — SYN-056 closure and next-task discovery

The completed SYN-056 scope was re-audited without reopening implementation.
No authoritative `SYN-057`/`SYN-058` successor exists, and `SL-D-040` is
already complete for its current scope rather than an unactivated follow-on.
The remaining membership-authority capability requires a separate explicit
activation trigger and remains outside SYN-057. No production change was made
in that continuation; SYN-057 is now explicitly active.

## 2026-09-11 — SYN-056 r4/r5 browser portability slice

The first fallback launch attempt used Java `ProcessBuilder.Redirect.DISCARD`
for child stdin, which is invalid (`Redirect invalid for reading: WRITE`). It
was corrected to a valid pipe; no shell or URI concatenation was introduced.
The next browser probe initially depended on the image's broken Selenium
Manager and then on Chrome's rejected WebSocket origin; the acceptance probe
was moved to the already-local Chrome DevTools endpoint with no production
impact. The final r4 gate passed.

The first r5 container attempt omitted `-Duser.home=/synesis-home`, placing
daemon state in the disposable container layer; no protocol result from that
attempt was counted. Both peers were restarted with Java user-home pinned to
their D: mounts before the accepted r5 run.

The accepted r5 run reached physical live peer transport but left overlay
membership unconfigured and produced no durable signed-membership mutation.
This is a product acceptance boundary, not a reason to weaken Link authority
or add daemon CONNECT IPC.

## 2026-09-11 — SYN-056 retry-3 browser-opener boundary

The D:-backed retry-3 harness reached positive distinct-peer installed Link
transport: peer verification, control readiness, liveness, successful work,
and clean close. The daemon B `OPEN_URI` operation reached
`WAITING_FOR_CONNECT`. Java AWT in the disposable image reported
`Desktop.isDesktopSupported=false`, so the browser Complete-Join/ANSWER path
was not exercised. This is an image capability limitation, not permission to
add daemon CONNECT IPC, expose bootstrap material, copy operation state, or
weaken identity checks.

## 2026-09-11 — SYN-056 browser-mediated answer boundary

The shipped browser UI consumed the existing fragment bootstrap, exchanged a
session, and created a real signed SLO1 invitation through the Network/Create
Invitation action. An installed daemon-managed join runtime accepted that
invitation and produced a real signed SLA2 answer. Sending that exact answer
through the same daemon correctly returned `URI_REJECTED` with authority code
`OPERATION_NOT_FOUND`, because the pending host operation belonged to the
separate browser runtime rather than the daemon-owned join runtime.

This narrows the remaining evidence gap to creating the host operation through
the browser bootstrap of the daemon-managed host runtime. Do not add bootstrap
disclosure through IPC or daemon-owned invitation semantics merely to force a
positive result.

## 2026-09-10 — SYN-055 visual verification setup, resolved

Default Java UI startup hit the known loopback error; the documented temporary
directory/JVM property workaround passed. Gradle npmCi hit a running Vite
esbuild file lock; stopping the owned preview server let the locked install
and gates pass. An expired initial UI bootstrap was replaced by a fresh
runtime/session. Accessible markup findings were fixed and rescanned.
These are resolved; do not weaken authentication or change global JVM settings.
Evidence: `docs/evidence/SYN-055-art-direction-2026-09-10.md`.

## 2026-09-05 — SYN-051 focused Gradle test stalls

- The command-local build-JVM workaround passed Gradle startup and clean
  `:cli:installDist`.
- Focused workspace and MCP test selections compiled but stalled at their test
  tasks without assertion output; both were stopped after bounded waits.
- These are incomplete host/test evidence, not production or architecture
  failures. The clean build/install and exact artifact provenance passed.

Evidence: `docs/evidence/SYN-051-build-jvm-provenance-2026-09-05.md`.

## 2026-09-05 — SYN-051 clean-build loopback stop

- JDK25 process-local selector and minimal IPv4/IPv6 HTTP preflight passed with
  `-Djdk.net.unixdomain.tmpdir=temporary workspace`.
- The required clean `:cli:installDist` attempt independently failed in the
  Gradle/JDK process before task execution with `Unable to establish loopback
  connection`.
- The diagnostic property was not propagated to Gradle or global settings.
  Artifact provenance was not freshly re-established, so no Worker-A lane was
  created and no managed runtime was launched.

Evidence: `docs/evidence/SYN-051-process-local-preflight-build-blocker-2026-09-05.md`.

## SYN-051 standalone host compatibility — 2026-09-05

The bounded investigation is COMPLETE; SYN-051 remains ACTIVE / PARTIAL.
Both Temurin 25+36 and 21.0.11+10 pass Pipe.open() and IPv4/IPv6 TCP, but
fail Selector.open() and HttpServer creation with an AF_UNIX connect error.
The smaller direct UNIX socket test fails in inherited/expanded user TEMP
and passes in temporary workspace. With only the diagnostic JVM's
jdk.net.unixdomain.tmpdir set to that directory, the unchanged full probe
passes 13/13 on BOTH JDKs. Classification: temporary-path-dependent Windows
AF_UNIX boundary, not JDK25-specific, not IPv4/IPv6, not host-wide TCP failure.
The underlying OS/filesystem/environment cause is unproven. User-TEMP socket
cleanup failed; exact files and limitations are recorded in the evidence.
No managed runtime, target project, production source, .synesis, credentials,
global settings, or remote state was touched.

Evidence: `docs/evidence/SYN-051-standalone-loopback-compatibility-2026-09-05.md`
and its `-raw.txt` companion. Checkpoint: CP-0683.md.

Next action: Review the standalone compatibility evidence and obtain separate authorization for one
new Worker-A run on
existing JDK25 with a process-local UNIX socket directory and matching primitive preflight; do not
launch or reuse a
lane in this slice.

# Failed Attempts

## 2026-09-11 — SYN-056 same-runtime positive ANSWER completion

This was not a runtime-ownership failure. A fresh installed `synesis ui`
run proved that the browser bootstrap, invitation creation, daemon JOIN, and
daemon ANSWER all targeted one runtime. JOIN returned `WAITING_FOR_CONNECT`
and a real answer URI.

The exact answer reached the authoritative pending Link operation but could
not finish because the existing `ControlPlaneLinkOperations` answer flow
waits for a join-side `connect` command. The daemon exposes only bounded
`OPEN_URI` handoff and intentionally has no CONNECT operation; the shipped
browser UI has no control for a daemon-created pending join. A repeated exact
answer was rejected as `OPERATION_BUSY`, and public `headSequence` stayed zero.

Do not repeat this by adding CONNECT IPC, disclosing bootstrap/session tokens,
copying pending operations, scanning runtimes, or weakening Link checks. The
focused owner test remains the positive answer/replay evidence; the installed
positive transition is PARTIAL pending a separately authorized completion
seam.

## 2026-09-11 — SYN-056 positive installed ANSWER setup boundary

The installed daemon was started successfully and an answer-shaped
`OPEN_URI` reached the runtime-owned route, returning the existing
`ANSWER_INVALID` authority code for a malformed answer. A positive answer could
not be produced in the current headless acceptance surface because creating the
required host operation requires the runtime's one-time browser bootstrap.
`OPEN_PROJECT` intentionally returns only the runtime endpoint, so the daemon
does not leak the bootstrap or create a second invite authority. Classification:
environmental acceptance-evidence gap, not a daemon/Link implementation
failure. Do not add a bootstrap-disclosure IPC field or daemon-owned invite
operation merely to make this harness green.

## 2026-09-05 — SYN-051 real-runtime loopback host creation

- The fresh single-worker validation stopped at the first material boundary.
  A new lawful Worker-A lane was created through supported application flows,
  and a disposable caller compiled and constructed one live production
  launcher and runtime host.
- JDK 25 failed at `HttpServer.create(...)` before `prepareFirst` with
  `Unable to establish loopback connection`, caused by
  `SocketException: Invalid argument: connect` in `PipeImpl`/
  `WEPollSelectorProvider`.
- Therefore no START, attachment, proof, generation, App Server, MCP, Job,
  provider thread, turn, or persistence evidence exists. Normal host cleanup
  ran; replacement, A2, and Worker B were not invoked.
- Classification: harness/host compatibility blocker, not provider-runtime
  failure. Do not retry this pass or reuse its lane. Resolve the loopback
  compatibility issue in a separately authorized slice before a new fresh
  Worker-A run.

## 2026-09-04 — SYN-051 package-wide test tasks incomplete

- After the implementation slice, `:workspace:test :mcp:test --no-daemon
  --max-workers=1` reached `:workspace:test` and produced no progress for
  two bounded waits; it was stopped.
- A subsequent explicit MCP test selection reached `:mcp:test` and likewise
  produced no progress for two bounded waits; it was stopped.
- These are incomplete host/test-run evidence, not passing or failing
  implementation results. The changed workspace classes passed explicitly,
  MCP test sources compiled, and clean installation/provenance passed.
- Do not respond by weakening lifecycle or proof gates; retain the focused
  evidence and leave package-wide runtime testing for a separately bounded
  follow-up.

## 2026-09-04 — SYN-051 bootstrap thread was not a durable handoff

- The read-only provider investigation reproduced the managed failure with
  disposable provider-only probes. A `thread/start`-only T1 was readable while
  live in its originating process but had no provider `threads` row, history
  items, or rollout and failed fresh `thread/read`/`thread/resume`.
- A T2 with one completed turn, using the same Codex 0.153.0 binary, normal
  home, state DB, and history DB, produced durable records and passed fresh
  `thread/read` and `thread/resume`.
- This disproves the bootstrap assumption that the returned live ID was a
  successor-loadable provider resource. It does not indicate a state-root
  mismatch, require a `thread/load` call, or weaken Synesis proof/ownership
  controls. Graceful-versus-abrupt termination was not separately compared.
- Classification: PASS-B architecture simplification found; overall managed
  compatibility remains partial. No production source or protected state was
  changed.

Immediate next action: implement managed generation-1 creation and broker
pinning with one persisted turn before successor/replacement acceptance, then
run focused verification only.

## 2026-09-04 — SYN-051 real-runtime validation stopped before A1

- Current source `088cb239f2e2109c4dddd40cbaee36c158337e82` built and installed
  successfully with matching workspace/MCP/CLI/native-launcher hashes. Focused
  tests and strict quality checks passed.
- A fresh lawful binding and active exact provider-thread owner were created
  for `SkibidiToilert`. The managed launcher issued generation 1 and launched
  App Server A inside the Windows Job supervisor.
- Codex `0.153.0` rejected the broker-pinned bootstrap-created thread with
  `thread not loaded` on `thread/resume` in the new App Server process. This is
  the first material runtime failure; A1 activation and the real turn did not
  occur. The lifecycle failed closed and startup cleanup wrote a trusted
  generation-1 receipt. That receipt is not evidence of the requested
  controlled App Server-root crash.
- The one-shot harness did not fail-fast after the start exception and later
  reached `prepareReplacement`, creating generation 2 `PENDING_ACTIVATION`
  from the startup-cleanup receipt. This was not accepted as A1-to-A2
  evidence; the aborted fresh attachment was terminalized at generation 2
  through the public service. The historical generation 1 remains `ACTIVE`
  without a trusted receipt. No controlled replacement acceptance, A2, Worker
  B, production edit, or push was performed.
- Read-only provider confirmation returned the same error:
  `thread not loaded: 01a069b4-1028-7472-8a77-21e847c51cc7`.

## 2026-09-04 — SYN-051 historical generation had no death receipt

- The existing real fixture still reports generation 1 as `ACTIVE`, while its
  managed process tree is gone. Public lifecycle/reconciliation evidence does
  not include a receipt produced by the new trusted supervisor seam.
- The old raw proof is not recoverable and no caller-supplied death assertion
  is accepted. The generation was therefore left untouched; no durable IDs,
  `.synesis` files, or Worker B state were edited.
- Gradle also remains blocked before build execution by
  `java.io.IOException: Unable to establish loopback connection`, so the
  current source has not been installed for a real-runtime rerun.

## 2026-09-04 — SYN-051 compatibility retry input mismatch

- The first compatibility retry used a stale installed distribution whose
  native MCP launcher was absent because the `nativeMcpLauncher` output
  directory was incorrectly considered up-to-date. Forcing that build artifact
  regenerated the exact executable; no production source explanation was
  inferred from the missing artifact.
- The patched real A run then started Synesis MCP successfully and reported
  `mcpServer/startupStatus=ready`. The real prompt requested `compat-a5.txt`,
  but the durable participant/WorkIntent claim was `compat-a4.txt`; Synesis
  returned `internal_failure` and made no unauthorized mutation. This is a
  harness-input mismatch, not evidence of a pending-transport or authority
  bypass defect. Do not broaden acceptance from this run.

## 2026-09-04 — SYN-051 real-runtime validation first failure

- Exact committed source `dc9231fd48971d72e0cf3a810f68f3e503f5398b` built and
  installed with matching workspace/MCP/CLI hashes.
- Fresh `SkibidiToilert` was initialized through ordinary Git and supported
  Synesis flows. Real Codex 0.145.0 launched managed App Server A; the root
  was observed inside a Windows Job.
- Stock Codex started the configured Synesis MCP child during App Server
  initialization, before the lifecycle verified/resumed the exact thread
  and activated the pending managed attachment. The child failed closed on
  MCP initialize because `PENDING_ACTIVATION` is not authority.
- This is the first concrete runtime incompatibility. Do not relax proof
  gating, move activation earlier speculatively, or continue with Worker B
  and A2. Evidence is under the fixture `probe-runtime` directory and the
  current SYN-051 evidence record.
- The unfiltered workspace test suite was also stopped after no progress;
  focused tests passed, but broad tests remain incomplete.

## 2026-08-29 — SYN-041 CP-0567 bounded verification boundary

- The read-first trace proved there is no surviving Synesis observer for an
  externally launched MCP Java process after hard termination. The old
  connection-ID-only clean close also allowed a rejected probe to rewrite the
  original terminal transport history.
- The bounded fix wires Java-local stdio failure to the existing abnormal
  finalizer and makes foreign PID clean close perform conservative persisted
  runtime liveness classification before refusing clean close.
- Fresh packaged acceptance passed: terminal seal, tracked Java termination,
  `SESSION_TERMINAL` rebind rejection, probe clean EOF, and final durable
  `TERMINAL_DISCONNECTED`. No Codex run was performed after the fix.
- Full process-heavy MCP selections timed out without assertion output and
  remain incomplete; they are not reported as passes.
- Evidence: `docs/evidence/syn041-terminal-disconnect-trigger-cp0567-2026-08-29.md`.

## 2026-08-29 — SYN-041 final real CP-0565 acceptance RESULT C

- One real native Codex run completed the exact no-change terminal flow and
  committed `PROVIDER_SESSION_TERMINALIZED` at sequence 7. The exact
  same-session rebind returned `SESSION_TERMINAL`.
- Native MCP/Java teardown occurred before Codex completed, but exact child
  exit codes were not captured. The persisted lease remained
  `TERMINAL_AUTHORITY_CONFIRMED`; `TERMINAL_DISCONNECTED` was only a derived
  liveness result.
- The rejected probe closed cleanly and rewrote the durable lease to
  `CLOSED_CLEANLY`. This remains RESULT C and proves the remaining narrow
  abnormal-transport persistence boundary; do not infer a partial MCP frame or
  exact Codex caller.
- No second provider run, speculative fix, Doctor change, SYN-039 change,
  provider migration, generalized identity work, commit, or push occurred.
- Evidence: `docs/evidence/syn041-final-real-codex-cp0565-2026-08-29.md`.

## 2026-08-29 — SYN-041 CP-0563 bounded verification limits

- The first Gradle attempts failed before test execution because the host could
  not establish its default loopback/Unix-domain temporary connection. Setting
  `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\\tmp` allowed the bounded
  suites to run.
- The broader combined MCP selection, and individual process-heavy
  `McpSyn039SliceTest` and `McpServerTest` attempts, produced no assertion
  failure but exceeded the practical host timeout and were stopped. They are
  incomplete, not passes; targeted replacements passed serialized.
- A first disposable packaged fixture was discarded because its writer closed
  cleanly and therefore did not model abnormal transport. The second fixture
  forcibly ended the MCP process, recorded `TERMINAL_DISCONNECTED`, rejected
  same-session rebind, and preserved the exact lease through probe cleanup.
- Disposable fixtures initially contained read-only Git object files; after
  stopping the stale test MCP process and clearing attributes on the exact
  fixture paths, both fixtures and their workspace records were removed.

## 2026-08-28 — SYN-041 final real Codex terminal-seal acceptance

- Two controller launches were excluded preflight failures: Windows argument
  quoting stopped Codex before MCP startup, with no Synesis process or state.
- The one actual real Codex lifecycle used the rebuilt official bundle and
  completed `ensure_session`, `read_file`, projected `get_next_action`, and
  exact no-change `finish_lane` with `terminalSession=true`.
- Durable `PROVIDER_SESSION_TERMINALIZED` committed at sequence 7 before
  native Java/MCP exit 1; Codex exited 0. The exact-session rebind probe was
  correctly fenced as `SESSION_TERMINAL`.
- The probe then ended cleanly and `markClosedCleanly` rewrote the same lease
  to `CLOSED_CLEANLY`, while the terminal event remained present. This proves
  the narrow terminal-history rewrite defect. It is RESULT C, not evidence to
  broaden identity or provider lifecycle architecture.
- Evidence:
  `docs/evidence/syn041-final-real-codex-terminal-seal-acceptance-2026-08-28.md`.
- Stop rule: do not run another provider lifecycle. The next narrow causal
  question is whether clean-close must refuse terminal-authority leases.

## 2026-08-28 — SYN-041 first bounded terminal-session implementation

- The first concurrent full-module verification attempt produced process-heavy
  test timeouts. The affected cases passed individually and the authoritative
  targeted suites passed serialized with `--max-workers=1`.
- Strict Javadocs initially failed because the new record components lacked
  `@param` tags; the documentation was corrected and relevant Javadocs passed.
- A clean-EOF regression initially returned `workspace_generation_changed`.
  The narrow fix treats only an existing `DETACHED` participant as a clean
  rebind trigger; abnormal/stale states remain fail-closed.
- Evidence: `docs/evidence/syn041-terminal-session-seal-2026-08-28.md`.

## 2026-08-28 — SYN-041 terminal-disconnect semantics boundary

- Design-only review completed; no provider run or production mutation was
  performed.
- Derived terminal classification from WorkGroup completion, participant or
  binding completion, or zero active claims/intents is unsafe. Review-only
  authority, pending non-claim obligations, wake/recovery, and same-connection
  rebinding remain possible.
- Primary result is RESULT C: explicit terminal intent and an atomic
  exact-session authority seal are required before abnormal transport can be
  treated as non-recoverable. Do not map it to `CLOSED_CLEANLY`.
- Evidence: `docs/evidence/syn041-terminal-disconnect-semantics-2026-08-28.md`.

## 2026-08-28 — SYN-041 exit-code causal analysis

- Source tracing proves the native launcher waits for Java and mirrors Java’s
  `ExitError.ExitCode()`; launcher-side failure is not the source of the
  observed child code 1.
- Clean EOF against the official bundle returned Java/MCP 0 and wrote
  `CLOSED_CLEANLY`.
- Partial EOF returned Java/MCP 1, logged `MCP_PARTIAL_FRAME_EOF`, skipped
  `handler.close()`, and left the lease `ACTIVE`.
- Closing the parent stdout reader did not reproduce code 1; the process
  returned 0 and the lease closed cleanly, consistent with unchecked
  `PrintStream` write errors.
- Classification: RESULT B primary and RESULT D secondary. The exact
  CP-0557 input condition remains unresolved because its stderr lacked the
  reproduced fatal trace. Do not implement a workaround or run another real
  provider experiment.

## 2026-08-28 — SYN-041 final handle measurement result

- Two controller preflights rejected malformed TOML array overrides before
  MCP startup; they produced no provider lifecycle evidence and are excluded.
- The one corrected valid run preserved direct Codex -> official MCP -> Java
  topology and retained native handles for all three processes.
- MCP and Java each returned exit code 1 and exited while Codex was still
  alive; Codex later returned 0. This establishes the RESULT C child-failure
  boundary but does not identify a crash, caller, or `TerminateProcess` path.
- Doctor afterward was `UNHEALTHY` with one ambiguous durable-state error and
  one stale-lease warning, with zero mutations. No production defect or lease
  defect is proven.
- Retry prohibition: do not run another equivalent provider measurement or
  change lifecycle behavior from this evidence. Preserve the exact artifacts
  in `docs/evidence/syn041-final-handle-native-measurement-2026-08-28.md`.

## 2026-08-28 — SYN-041 observability design boundary

- Completed a read-only design slice; no provider experiment was run.
- The installed Codex help exposes JSONL (`codex exec --json`), final-message
  output, and redacted Doctor JSON, but no documented MCP child lifecycle or
  exit-code telemetry. Synesis traces startup and initial protocol events but
  not EOF, handler close, or process exit.
- Retained native process handles are the smallest reliable exit-code method.
  Ordinary Windows telemetry still cannot prove the caller of a zero-code
  termination or literal anonymous-pipe EOF.
- Classification remains RESULT E. No production fix or broader architecture
  is justified. Evidence:
  `docs/evidence/syn041-native-observability-design-2026-08-28.md`.

## 2026-08-28 — SYN-041 native teardown capture remains inconclusive

- Direct Codex -> official `synesis-mcp.exe` -> packaged Java parentage was
  captured with no wrapper or proxy; child termination timing was observed.
- The polling harness failed to persist the Codex JSONL transcript and did not
  capture usable native EOF or child exit-code evidence. The fresh lease stayed
  ACTIVE and Doctor reported `stale_session_lease`.
- Classification: RESULT E. Do not infer native teardown causality and do not
  run another equivalent probe in SYN-041.
- Evidence:
  `docs/evidence/syn041-real-codex-native-teardown-2026-08-28.md`.

## 2026-08-28 — SYN-041 instrumented provider/MCP teardown boundary

- A temporary byte-forwarding observer verified and launched the official MCP
  executable by exact SHA-256, and the real Codex task completed through exact
  no-change `finish_lane`; Codex exited 0.
- The observer directly saw no stdin EOF and no clean official MCP child exit
  event. The lease remained ACTIVE and Doctor reported the fresh
  `stale_session_lease` warning.
- Classification: RESULT C, provider/MCP transport teardown boundary. No
  Synesis lease persistence defect is proven; stop before implementation.
- Evidence:
  `docs/evidence/syn041-real-codex-teardown-measurement-2026-08-28.md`.

## 2026-08-28 — SYN-041 final lease measurement remains inconclusive

- Fresh authenticated real Codex used the official packaged MCP and completed
  task-bearing `ensure_session`, `read_file`, exact `get_next_action({})`, and
  exact no-change `finish_lane`; the WorkGroup reached `COMPLETED` and Codex
  exited 0.
- The official MCP and packaged Java processes disappeared, but no direct MCP
  EOF or child exit-code evidence was captured. The final lease remained
  `ACTIVE`, and Doctor reported `stale_session_lease`.
- Classification: RESULT D. This does not prove a lease defect; keep SYN-041
  ACTIVE and do not change lease or Doctor semantics.
- Evidence:
  `docs/evidence/syn041-real-codex-lease-measurement-2026-08-28.md`.

## 2026-08-28 — SYN-041 prior engagement stall explained

- The prior real JSONL showed task-bearing `ensure_session`, then no
  `read_file`, followed by an outstanding `get_next_action({})`.
- Current source and the packaged synthetic control return the expected
  task-bearing participant/intent/claim/WorkGroup projection; zero-work
  `get_next_action` has no server long-poll implementation.
- A fresh authenticated real Codex run with an explicit numbered sequence
  completed task-bearing `ensure_session`, `read_file`, `get_next_action`, and
  exact `finish_lane`; the WorkGroup completed and Codex exited 0.
- Classification: RESULT A; prior harness/provider invocation sequencing,
  not a Synesis projection or lease defect. Do not add orchestration or
  alter lifecycle semantics.
- Evidence:
  `docs/evidence/syn041-real-codex-engagement-2026-08-28.md`.

## 2026-08-28 — SYN-041 real Codex lifecycle stall

- Default ChatGPT authentication succeeded after reproducing the 401 as an
  empty isolated `CODEX_HOME` with no credentials.
- Fresh real Codex reached the official packaged MCP and completed
  `ensure_session`, but the first `get_next_action({})` remained in progress;
  no WorkGroup or explicit completion was created.
- The verified disposable tree was then ended as the permitted active-session
  crash control. The lease remained ACTIVE and Doctor reported the expected
  fresh stale-lease warning.
- Classification: RESULT D / provider-runtime inconclusive. No lease or
  Doctor fix is justified.
- Evidence:
  `docs/evidence/syn041-real-codex-clean-exit-2026-08-28.md`.

## 2026-08-25 — SYN-039 CP-0532 exact diagnostic and ordinary follow-up

- The exact-projection diagnostic used fresh project
  `e9cff886-feee-496c-933d-fbe939402ae9` and WorkGroup
  `35931e39-9eb1-3693-b03e-b89fc7088b72`. Both independent agents executed
  every concrete projected lifecycle action, including reciprocal REVIEW
  admission, grant consumption, `ensure_session({})` recovery, snapshot
  publication, immutable validation, and integration. Synesis returned
  `workGroupStatus=COMPLETED`; control pytest was 5/5.
- The required ordinary follow-up used fresh project
  `9af5f848-6bc9-45be-b48b-2e26d3d128bb` and WorkGroup
  `4646b6ba-66bc-3760-8fda-fc04b9db1b66`. The reviewer first added unsupported
  `failedAcceptanceTests` and received fail-closed
  `COORDINATION_RESPONSE_FIELD_NOT_ALLOWED`, then used a wrong intent and
  received `REVIEW_GRANT_BINDING_MISMATCH`; the later correctly bound REJECT
  succeeded. The provider sessions ended before the reciprocal grant was
  consumed and the implementation snapshot was published; control pytest was
  1 failed, 4 passed.
- Classification: exact protocol correctness is proven; the ordinary failure
  is agent argument compliance and provider/session engagement. Do not add
  argument repair, retry orchestration, cleanup, or a daemon from this run.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0532-exact-diagnostic-and-ordinary-2026-08-25.md`.

## 2026-08-25 — SYN-039 CP-0531 reciprocal-review session stop

- Attempted approach: run a fresh exact-projection two-agent diagnostic after
  fixing the reciprocal-review grant gate.
- Confirmed protocol result: one shared WorkGroup reached exact admission,
  owner acceptance, single-use grant consumption, immutable snapshot access,
  structured validation response, and snapshot integration. Replayed exact
  request/grant actions remained idempotent and fail-closed authorization was
  preserved.
- Observed stop: the sessions ended while reciprocal request
  `016aa0ca-48a3-4d78-91de-e48b10e33969` and grant
  `42bf4474-67a1-3efa-984c-0a571be83c49` remained unresolved. No unchanged
  projected action failed; the provider sessions did not remain engaged long
  enough to observe the next projected continuation.
- Classification: provider/session engagement evidence, not a Synesis
  projection defect. Do not add retry orchestration, argument repair, or
  cleanup machinery from this run.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0531-exact-rule-diagnostic-2026-08-25.md`.

## 2026-08-25 — SYN-039 CP-0530 post-fix exact-argument/session stop

- Attempted approach: Run a fresh bounded exact-projection diagnostic after
  fixing the pytest-generated bytecode recovery defect.
- Expected result: Both independent agents would execute every projected
  REVIEW, grant, snapshot, validation, and continuation action exactly and
  close the WorkGroup without relay or manual transitions.
- Confirmed production result: B executed the exact projected
  `ensure_session({})` after `workspace_stale` and recovered to a new isolated
  worktree while preserving its session. This proves the narrow
  `__pycache__/` classifier fix.
- Observed stop: A changed the projected reciprocal REVIEW intent from
  `242ab48e-bd24-3481-9e15-ac7cb3dcf4d5` to malformed
  `242ab48e-bd24-3481-9e15-acb7-4535-bc8d-bf3065206772`; Synesis rejected it
  with `UUID string too large`. A later used the exact projection, but its
  provider turn ended while the reciprocal review continuation remained
  unresolved. Both snapshots were ultimately published/integrated and
  control pytest passed 4/4, but WorkGroup closure was not observed.
- Root cause/classification: agent-selected wrong lifecycle arguments and
  provider/session engagement, not a Synesis protocol defect. The fail-closed
  rejection is correct. Do not add argument repair, retry orchestration,
  cleanup, or lifecycle machinery from this run.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0530-bytecode-recovery-2026-08-25.md`.

## 2026-08-25 — SYN-039 CP-0530 pre-fix pytest artifact recovery

- Attempted approach: Continue a supported reviewer session after sibling
  integration using the exact projected `ensure_session({})` action.
- Observed result: Standard pytest-generated `__pycache__/` files were the
  only untracked content, but stale recovery raised `WORKSPACE_STALE_DIRTY`
  and returned `failed / internal_failure / request_human_help`.
- Root cause/classification: concrete production inconsistency between
  `SnapshotArtifactPolicy` (which already allows Python bytecode caches) and
  provider stale-workspace cleanliness classification. Fixed narrowly in
  `ProviderSessionBindingService`; unknown untracked content remains blocking.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0530-bytecode-recovery-2026-08-25.md`.

## 2026-08-25 — SYN-039 CP-0529 ordinary reciprocal-review projection stop

- Attempted approach: Run a fresh ordinary two-agent Todo acceptance with
  only complementary coding prompts after the CP-0528 continuity probe.
- Expected result: the agents would execute the projected reciprocal REVIEW
  admission, publish the implementation snapshot, validate it, integrate it,
  and close the WorkGroup without relay or lifecycle coaching.
- Observed result: the agents reached shared WorkGroup
  `c8834a58-fe9d-3a75-8b56-bbf7a86f7a6a`, B published and integrated
  test-only snapshot `snap_d678f31fc5591c897c7a648c41d4322d`, and A recorded
  ACCEPT. B then received the exact executable `request_coordination`
  projection for the reciprocal REVIEW admission but chose another
  `get_next_action` call and its turn ended. The implementation snapshot was
  not published; control pytest reported `2 failed, 2 passed`; WorkGroup
  remains ACTIVE.
- Root cause/classification: ordinary provider/session engagement and
  projection compliance. No unchanged projected action failed and no usable
  projected action was absent. Do not change production lifecycle, cleanup,
  integration, or orchestration behavior from this run.
- The continuity companion reached projected `finish_lane` successfully, but
  reviewer `ensure_session` failed closed on pytest-generated `__pycache__`
  files considered untrusted by the existing cleanliness rule. This is the
  existing dirty-worktree boundary, not a proven SYN-039 production defect.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0529-continuity-and-ordinary-2026-08-25.md`.

## 2026-08-24 — SYN-039 CP-0525 ordinary completion boundary

- Attempted approach: Run the required second fresh ordinary two-agent Todo
  acceptance after a bounded exact-projection diagnostic completed end to end.
- Expected result: ordinary agents would continue from visible coding through
  reciprocal review, validation, integration, and WorkGroup closure without
  lifecycle coaching.
- Observed result: both agents reached one shared WorkGroup. A integrated
  `snap_de38379e858662f72b2a5de69db6d983`; B accepted it, published and
  integrated `snap_b78e80fc552f8df1a890812d587b2e72`, and control pytest passed
  3/3. A's session stopped while the valid reviewer continuation remained
  projected; WorkGroup `5e0a82d7-635d-3e47-9e3e-5a4c37d83822` remained ACTIVE.
- Root cause/classification: ordinary Codex session engagement/projection
  compliance. No unchanged projected Synesis action failed and no valid
  lifecycle action was absent. Do not change production lifecycle, cleanup,
  integration, or orchestration behavior from this run.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0525-002-bounded-and-ordinary-2026-08-24.md`.
- The bounded companion diagnostic completed WorkGroup
  `52ceb172-4e63-332b-ac6a-a5d932acd03d`; its invalid payload attempts were
  fail-closed and its exact retries succeeded.

## 2026-08-24 — SYN-039 CP-0522 third ordinary projection-compliance stop

- Attempted approach: Run a third fresh ordinary two-agent Todo acceptance
  with only the two complementary coding prompts and current bundled MCP.
- Expected result: Both agents would remain engaged through reciprocal review,
  implementation snapshot publication, validation, integration, and closure.
- Observed result: Both sessions reached one WorkGroup. A implemented
  `todo.py`, followed exact REVIEW admission and grant consumption, then
  received durable `WAIT → get_next_action({})` and instead performed
  unprojected reads/recovery. Those responses returned `workspace_stale` and
  later `internal_failure`. B executed exact `finish_lane`, publishing and
  integrating `snap_012bbfe1bc5f22b8e69d51e9638b4c05`; A rejected it after its
  test failed against the incomplete base. WorkGroup
  `e769b143-f9b0-337f-b06a-9eb1603c8cc9` remained ACTIVE.
- Root cause/classification: agent/session compliance with the durable
  projection contract. Exact projected lifecycle calls succeeded; invalid or
  unprojected calls failed closed. No production defect is proven.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0522-third-ordinary-2026-08-24.md`.
- Retry prohibition: Do not add cleanup, lifecycle, test-gating, or
  orchestration behavior from this run. Run focused verification and create
  CP-0523; a production change requires an unchanged projected action failure
  or a missing usable action.

## 2026-08-24 — SYN-039 CP-0522 ordinary session-engagement stop

- Attempted approach: Run a fresh ordinary two-agent Todo acceptance using
  only the complementary coding prompts after a bounded diagnostic had
  completed the protocol.
- Expected result: Ordinary agents would continue through reciprocal REVIEW,
  snapshot publication, validation, integration, and WorkGroup closure.
- Observed result: The ordinary run reached one shared WorkGroup. A executed
  exact `finish_lane`, integrated `snap_d0a18b8641e2054682eb15f95d3a772c`,
  and executed the projected REVIEW admission request. B consumed its grant,
  accepted A's snapshot, and then correctly followed projected WAIT. A's
  turn ended while a repeated concrete `request_coordination` projection
  remained pending for A; grant `051f07ff-e0c0-3f10-8422-705d066afc57`
  remained unconsumed and WorkGroup
  `0f999cd8-e9b2-38cc-a382-ab6722b76139` remained ACTIVE.
- Root cause/classification: deterministic ordinary Codex session/projection
  compliance boundary. No unchanged projected action failed and no valid
  active lane lacked a usable action. The bounded diagnostic closed with
  explicit post-completion session engagement, so this is not evidence of a
  backend lifecycle defect.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0522-valid-diagnostic-and-ordinary-2026-08-24.md`.
- Retry prohibition: Do not add lifecycle machinery or weaken fail-closed
  behavior for this run. One third ordinary acceptance may test repeatability;
  a production change requires a concrete protocol failure.

## 2026-08-24 — SYN-039 CP-0521 invalid continuation seed

- Attempted approach: Run the bounded completed-lane continuation diagnostic
  with a fresh Git + Synesis Todo project and two independent agents.
- Observed result: The seed already implemented `TodoList.complete`; A
  correctly made no edit and stayed in `IMPLEMENT`. B waited at
  `SNAPSHOT_PENDING`, later added its test and passed 4/4. No snapshot or
  closure occurred in WorkGroup
  `bb378922-3385-3c36-b8ac-98760163e56a`.
- Root cause/classification: invalid acceptance fixture, not a Synesis
  protocol defect. No unchanged projected action failed.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0521-invalid-seed-continuation-2026-08-24.md`.
- Retry prohibition: Do not change production code. Rerun only with a
  genuinely missing no-op `complete()` implementation so lifecycle evidence
  is meaningful.

## 2026-08-24 — SYN-039 CP-0520 ordinary completed-lane engagement stop

- Attempted approach: Run a fresh ordinary two-agent Todo acceptance with
  only complementary coding prompts; retain unfinished sessions, but do not
  resume a completed coding lane as a new intent.
- Expected result: Both agents would remain engaged through reciprocal
  REVIEW, second snapshot publication, validation, integration, and terminal
  WorkGroup closure.
- Observed result: A's exact `finish_lane` published and integrated
  `snap_41f8664537c23fe67293f8e08f740fa6`. B consumed that REVIEW grant,
  inspected the immutable snapshot, and submitted ACCEPT. A then ended before
  polling the reciprocal grant
  `22bc7d10-0337-31c9-9155-6de7f0130b73`; B correctly remained in exact WAIT.
  The WorkGroup `5c1609bd-f88d-36e5-845b-0f07677e9ffe` remained `ACTIVE`.
- Root cause/classification: ordinary agent/session engagement and
  projection compliance. The corrected harness did not create a new intent,
  and no unchanged projected action failed. No production change is
  justified.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0520-ordinary-completed-lane-2026-08-24.md`.
- Retry prohibition: Do not modify lifecycle semantics for this run. The
  next bounded diagnostic may retain the existing session only to execute an
  already projected REVIEW action; it must not announce a new coding intent
  or relay coordination.

## 2026-08-24 — SYN-039 CP-0519 ordinary continuation after command-scope fix

- Attempted approach: Run the required second ordinary two-agent acceptance
  after the command-scope fix, with only complementary coding prompts and
  retained Codex sessions.
- Expected result: Both ordinary lanes would remain engaged through reciprocal
  review, snapshots, validation, integration, and WorkGroup closure.
- Observed result: Both original lanes integrated and validated. The retained
  harness resumed A after its lane had completed, creating a new active
  `todo.py` participant. A ignored one projected admission argument, then
  exact recovery returned `internal_failure`; B remained in WAIT. WorkGroup
  `dfc93a1a-de2e-3db4-859e-c0eb7d60eaab` remained ACTIVE.
- Root cause/classification: contaminated agent/session continuation and
  projection compliance; no clean unchanged projected action failure is
  attributed to production by this run.
- Evidence:
  `docs/evidence/syn039-unattended-todo-cp0519-command-scope-recovery-2026-08-24.md`.
- Retry prohibition: Do not add lifecycle behavior for this run. Correct the
  disposable harness continuation policy before the next ordinary acceptance.

## 2026-08-24 — SYN-039 CP-0536 ordinary acceptance compliance stop

- Attempted approach: Run a fresh ordinary two-agent Todo acceptance after the
  CP-0535 terminal-WorkGroup guard, with only complementary visible coding
  prompts.
- Expected result: Agents would follow the existing projected coordination
  actions through reciprocal review, publication, validation, integration, and
  closure without relay.
- Observed result: The first test snapshot published and integrated. Agent A
  initially changed the projected WorkGroup ID in a REVIEW admission request,
  then corrected it. Agent B later received the exact projected
  `request_coordination(work_group_join)` action, called `get_next_action`
  again instead, and its turn ended. WorkGroup
  `1c9fd0e2-eda4-3505-a20e-db86de14ec8a` remained ACTIVE with reciprocal grant
  `4ba34d35-976a-3d55-bc40-0d7c9656f46b` unresolved.
- Root cause: Agent action/turn engagement deviated from the durable
  projection contract. No unchanged projected lifecycle action failed and no
  backend defect is proven.
- Evidence: `docs/evidence/syn039-unattended-todo-cp0536-bounded-and-ordinary-2026-08-24.md`.
- Retry prohibition: Do not change production lifecycle behavior for this
  compliance deviation. A future acceptance may be run only to test ordinary
  agent behavior after the verified local commit.

## 2026-07-20 — Java 25 ephemeral TLS certificate generation

- Date: 2026-07-20
- Task ID: SL-012
- Attempted approach: Netty's deprecated `SelfSignedCertificate` helper for the
  temporary QUIC transport certificate.
- Expected result: Compile and run on the repository's Java 25 toolchain.
- Observed result: javac failed inside the deprecated helper path with a Java
  25 compiler/runtime error.
- Command or evidence: `gradlew.bat :link:compileJava --dependency-verification=strict`.
- Root cause: the helper's deprecated certificate-generation path is not
  compatible with this Java 25 environment.
- Retry prohibition: Do not restore the helper without a reproducible Java 25
  compatibility fix.
- Evidence required before retry: a passing Java 25 compile and host/join run.
- Next hypothesis: keep the ephemeral transport certificate isolated behind a
  small keytool-backed implementation; Synesis identity remains separate.

## 2026-07-20 — demo application stream classification

- Date: 2026-07-20
- Task ID: SL-DEMO-001
- Attempted approach: Let the existing responder stream handler claim control before classifying the
  first frame on
  every new stream.
- Expected result: A second authenticated stream would reach the demo handler.
- Observed result: The application stream was rejected as `DUPLICATE_CONTROL_STREAM` and the client
  received `SLF1`
  instead of a demo result.
- Command or evidence:
  `NettyQuicLoopbackTest.establishesIdentityBoundSessionOnLocalQuicControlStream` failed with
  demo-frame header `534c46310105`.
- Root cause: The responder claimed the shared control flag before checking whether the frame was an
  application frame.
- Retry prohibition: Do not restore claim-before-classification ordering.
- Evidence required before retry: N/A; fixed by reading the bounded frame first and routing
  non-control frames only
  after an authenticated session exists.
- Next hypothesis: Keep control ownership and application-stream routing separate at the first-frame
  classification
  boundary.

## 2026-07-20 — local interface candidate scan

- Date: 2026-07-20
- Task ID: SL-DEMO-001
- Attempted approach: Enumerate local interfaces for the physical demo using the existing provider.
- Expected result: Reach the live Ethernet adapter and advertise `192.168.1.100`.
- Observed result: `CANDIDATES=0` even though Windows reported Ethernet up with `192.168.1.100`.
- Command or evidence: Java interface enumeration showed many up/down virtual adapters; the provider
  stopped at the
  first down adapter.
- Root cause: `LocalInterfaceCandidateProvider` used `break` for a down interface, abandoning all
  later interfaces.
- Retry prohibition: Do not stop the whole scan on one down adapter; skip it and continue.
- Evidence required before retry: Targeted candidate tests and a direct gather check must return
  candidates on a host
  with live Ethernet.
- Next hypothesis: The corrected scan will produce usable same-family candidates; network
  reachability still requires a
  shared/direct topology.

## 2026-07-20 — transient full-suite test class loading

- Date: 2026-07-20
- Task ID: SL-DEMO-001
- Attempted approach: Run the full strict clean check after restoring package metadata.
- Expected result: All checks and tests pass.
- Observed result: One full run failed in `NettyQuicLoopbackTest` with `NoClassDefFoundError` for
  its anonymous `$2`
  class; the focused test rerun and immediate full rerun passed.
- Command or evidence: `gradlew.bat clean check --dependency-verification=strict`; test XML under
  `link/build/test-results/test`.
- Root cause: Undetermined transient test-worker/class-loading failure; no reproduction on rerun.
- Retry prohibition: Do not change production or test code based on this single non-reproducible
  failure.
- Evidence required before retry: A reproducible failure or repeated test-worker logs.
- Next hypothesis: Treat as environmental/transient unless it recurs.

## Required entry format

## 2026-07-22 — Codex real-agent hook validation

- Date: 2026-07-22
- Task ID: SYN-009B.1
- Attempted approach: Run authenticated Codex CLI 0.140.0 against a disposable
  project with the project-local Codex hook installed; first without the trust
  bypass, then once with the documented one-shot bypass only for diagnostics.
- Expected result: The hook receives a real `apply_patch` payload, denies the
  protected path, and preserves its hash.
- Observed result: Without persisted project trust the hook was skipped and the
  protected disposable file changed. The bypassed diagnostic run also did not
  invoke the temporary capture wrapper in this Windows noninteractive path.
  No actual payload fixture or denial/re-plan claim is recorded.
- Command or evidence: `codex login status`; `codex -m gpt-5.4 -C <fixture>
  -s workspace-write -a never exec --ephemeral --json ...`; validation report
  `docs/validation/codex-real-agent-experiment.md`.
- Root cause: The required `/hooks` interactive trust review was not completed;
  the noninteractive path is not evidence of trusted project-hook execution.
- Retry prohibition: Do not promote Codex or claim real enforcement from a
  bypassed or untrusted run.
- Evidence required before retry: Review/trust the exact hook in Codex `/hooks`,
  capture and sanitize one payload, and prove denial recognition, reason,
  replanning, and unchanged protected hash.
- Next hypothesis: An interactive trusted run will either exercise the
  `commandWindows` launcher and complete the gate or reveal a concrete
  Windows hook-command contract issue.

## Required entry format

## 2026-07-22 — Clean-room Java verification harness

- Date: 2026-07-22
- Task ID: SYN-009C.2
- Attempted approach: Run strict Java verification in a D: copied worktree.
- Expected result: Reproduce the prior clean Java gate independently.
- Observed result: Early runs reused copied Gradle caches or left Java test
  temp state on C:, causing false native-library and disk-space failures.
- Command or evidence: D: `gradlew.bat clean check --dependency-verification=strict`;
  final clean run with `--no-build-cache`, fresh project state, and D: TEMP/TMP.
- Root cause: Verification harness inherited absolute build-cache paths and
  the host C: temp directory.
- Retry prohibition: Do not treat the early failures as source regressions;
  rerun only with isolated project/cache/temp paths.
- Evidence required before retry: Fresh D: project, D: Gradle home, D: temp,
  and no build cache.
- Next hypothesis: The isolated run will match the existing Java clean gate.

- Date:
- Task ID:
- Attempted approach:
- Expected result:
- Observed result:
- Command or evidence:
- Root cause:
- Retry prohibition:
- Evidence required before retry:
- Next hypothesis:

## 2026-07-22 — Go bootstrap local toolchain

- Date: 2026-07-22
- Task ID: SYN-009C.2
- Attempted approach: Downloaded the official Go 1.26.5 Windows archive to a
  temporary directory and extracted only partial toolchain contents to run
  `gofmt` and `go test ./...` without installing Go system-wide.
- Expected result: Local bootstrap compile and tests pass.
- Observed result: The host has insufficient free disk space for the complete
  toolchain; the partial GOROOT lacked required standard-library sources and
  `go test` could not run.
- Command or evidence: `go test ./...`; Go 1.26.5 temporary toolchain attempt.
- Root cause: No installed Go toolchain and only a partial temporary extraction.
- Retry prohibition: Do not claim Go verification from the partial toolchain.
- Evidence required before retry: Complete Go 1.26.5 toolchain or CI run with
  `gofmt` and `go test ./...` plus native bootstrap fixture evidence.
- Next hypothesis: CI's setup-go job will provide a complete toolchain; fix any
  compiler or test issue there before marking SYN-009C.2 complete.

## 2026-07-22 — Post-CP-0109 distribution audit

- Date: 2026-07-22
- Task ID: SYN-009C
- Attempted approach: Treat CP-0109 as final and run the real Windows archive
  through the bootstrapper.
- Expected result: The external-style installation trial and CI release path
  should work without test-only environment injection.
- Observed result: Unix bundle smoke selected Windows `cmd.exe`; provider
  install failed because bundled launchers did not set `SYNESIS_LAUNCHER`; CI
  signed `bootstrap/manifest.json.sig` but verified `manifest.json.sig` before
  copying it.
- Root cause: Smoke task assumed the host platform and tested a directory;
  launcher self-identification was supplied only by the smoke harness; the CI
  verifier used the wrong sidecar path.
- Retry prohibition: Do not claim CP-0109 final until archive extraction,
  launcher self-identification, and sidecar verification are rerun together.
- Evidence required before retry: Clean D: source copy, real Java archive,
  native bootstrap subprocess, YAML parse, and final clean commit.
- Result after fix: Real Windows archive trial passes; Unix branch remains
  runner-dependent and is now selected by host platform rather than `cmd.exe`.

## 2026-07-22 — SYN-011 Antigravity real hook failure

- Date: 2026-07-22
- Task ID: SYN-011
- Attempted approach: Run the corrected generated `.agents/hooks.json`
  command manually, then run Antigravity 1.0.16 with the real structured file
  editor against the protected scope.
- Expected result: The project hook would deny the protected edit, preserve the
  hash, pass its reason to the agent, and permit a replan.
- Observed result: The manual exact command denied correctly, but the real
  Antigravity operation modified the protected file. Its current transcript and
  conversation database show `replace_file_content` but no `PreToolUse`, hook
  decision, or Synesis marker. A later `--add-dir` run allowed an unrelated edit
  and updated `proposals/tcp-fallback.md`.
- Command or evidence: `docs/evidence/antigravity-real-investigation-2026-07-22/report.md`;
  preserved before-state files; Antigravity transcript/database diagnostics.
- Root cause: The original generated Windows command was malformed; after that
  was corrected, the remaining failure was Antigravity project-hook
  discovery/loading in the tested CLI/IDE invocation path.
- Retry prohibition: Do not claim Antigravity real enforcement or restore
  `HEALTHY` from synthetic checks. Do not broaden matcher coverage without a
  verified structured mutation tool contract.
- Evidence required before retry: A trusted Antigravity run with observable
  hook invocation, denial, reason delivery, unchanged protected hash, and
  successful replan.
- Next hypothesis: Verify the supported Antigravity workspace/project loading
  mode or obtain a trusted diagnostic that proves `.agents/hooks.json` is
  loaded before changing the adapter again.

## 2026-09-05 — SYN-051 disposable observation harness closed before completion

The first fresh same-process harness reached generation-1 ACTIVE and observed
`persistenceReady=false` before `turn/start`, but its disposable caller closed
the production host 1.5 seconds after `turn/started` instead of waiting for
trusted completion. The lane was normally closed, produced no claim mutation,
and was not reused. The harness was corrected before a different fresh lane
was run. This was a harness observation error, not evidence of a production
failure; the successful lane is recorded separately.

## 2026-09-05 — SYN-051 broader package-test gate incomplete

After the fresh end-to-end runtime PASS-A, `:workspace:test :mcp:test` was
run with JDK25, `--no-daemon`, `--max-workers=1`, and the process-local AF_UNIX
property. The workspace lifecycle-codex suite passed. The MCP suite first
exposed a Windows 8.3 short-path versus long-path test comparison; a minimal
test-only `toRealPath()` correction made that regression pass. A subsequent
full MCP run remained incomplete: `McpServerTest` stalled in provider MCP
setup, and a linked-worktree setup later stalled while launching Git. The
stalls were stopped without being treated as passing. SYN-051 runtime evidence
remains PASS-A, but the repository task remains ACTIVE / PARTIAL until this
broader test gate is resolved or its environment blocker is formally accepted.

## 2026-09-05 — SYN-051 MCP per-method isolation follow-on

To distinguish assertion failures from cumulative fixture-host behavior, every
test method in `McpServerTest` (33 methods) and every test method in
`CodexLinkedWorktreeRootSelectionRegressionTest` (13 methods) was run in a
separate fresh Gradle test JVM with JDK25, `--no-daemon`, `--max-workers=1`, the
process-local AF_UNIX property, and the process-local native MCP launcher.
Every isolated invocation passed. Subsequent bounded aggregate runs passed for
the in-scope MCP classes after their long-running Git/provider fixtures
completed. Explicit two-process and older SYN-039 classes remain unrun by
scope.
## 2026-09-10 — SYN-056 daemon installed-smoke defects

- The first installed daemon attempt used `ProcessBuilder.Redirect.DISCARD` for
  child stdin; Windows rejected it with `Redirect_invalid_for_reading_WRITE`.
  The runtime now uses the platform null device for stdin.
- The first `--no-browser` response reported `SYNESIS_UI_OPENED` because the
  response conflated runtime readiness with browser-opened state. The response
  now reports those separately.
- Force-stopping the daemon initially left its project runtime alive, causing a
  replacement daemon to hit the existing project-host lock. The daemon-owned
  runtime now receives a parent PID and exits when that owner disappears; the
  relaunch smoke then observed zero orphan runtimes.
## 2026-09-10 — SYN-056 installed deep-link smoke cleanup and gate classification

- The first disposable smoke helper treated the batch launcher PID as the Java
  runtime PID; later inspection found only exact temporary-root Java children.
  Those validated PIDs were stopped and verified absent.
- Generated Git object files in the disposable roots were temporarily
  read-only, so exact-root cleanup required clearing attributes before deletion.
  No user project or repository data was targeted.
- The process-local `JAVA_TOOL_OPTIONS` workaround contaminated one generated
  child-process output with its diagnostic prefix. Rerunning the two broader CLI
  failures with `GRADLE_OPTS` and no `JAVA_TOOL_OPTIONS` reproduced both, so the
  failures remain outside the SYN-056 daemon/Link slice.

## 2026-09-11 — SYN-056 CONNECT seam installed completion remains open

## 2026-09-11 — SYN-056 operator-aborted Windows fixture / Docker unavailable

- The Windows two-identity setup was manually interrupted by the operator
  while creating a disposable fixture. It is not a product failure and was
  not resumed; the exact partial root was quarantined after process cleanup.
- Docker client 29.2.1 and Compose 5.0.2 were present, but the selected
  `desktop-linux` engine could not be opened. The required Docker acceptance
  was stopped before container creation, so no candidate or cross-container
  result is claimed.
- Do not fall back to same-identity testing or alter Link semantics. Resume
  only with a fresh D:-backed Docker root after the Linux engine is available.

- Source review found CONNECT already existed in Link, the authenticated
  runtime route, and the browser client. The minimal missing seam was added as
  pending-join snapshot metadata plus the existing Network `Complete Join`
  action; no daemon CONNECT IPC or secret disclosure was added.
- The handler's process-wide onboarding lock was incorrectly held across the
  blocking host-answer and join-connect calls. It was narrowed to handle
  creation so independent retained Link handles can overlap.
- A fresh installed browser run proved daemon JOIN, `WAITING_FOR_CONNECT`, the
  conditional UI action, and client CONNECT invocation. The subsequent daemon
  ANSWER request still timed out at the runtime handoff with `headSequence=0`;
  the positive durable completion and replay proof remain unverified.
- Do not repeat browser/UI work without new evidence. The direct trace now
  classifies the existing self-host/self-join result: both operations overlap,
  the same persisted identity is rejected as `IDENTITY_PROOF_INVALID`, and
  later candidate attempts time out. Do not weaken identity checks; a positive
installed completion requires two distinct durable peer runtimes.

## 2026-09-11 — SYN-056 Docker retry / engine storage failure

- The fresh Docker harness proved installed daemon startup, authenticated
  status, bridge candidate advertisement, and distinct project identity
  initialization. It did not complete the two-identity Link acceptance.
- The daemon runtime ready endpoint had a trailing slash; appending the health
  route without normalization produced a double slash and caused a healthy
  runtime to be discarded. This was fixed minimally and covered by a focused
  test.
- The disposable Linux image was missing Java AWT's libXi.so.6 and
  libXtst.so.6. The attempted image rebuild then failed with Docker Desktop
  storage I/O, and the restarted Linux engine remained unavailable. Do not
  classify this as a Link or browser-product failure, and do not bypass the
  daemon/browser boundary to obtain bootstrap material.
## 2026-09-11 — Docker retry-2 invitation mutation remains pending

The fresh two-container installed harness and real browser bootstrap path
worked through the A Network view, but the shipped Create Invitation action
did not return an SLO1 URI. The runtime stayed healthy and a bounded thread
diagnostic showed no active Link host operation. Do not treat this as
permission to add daemon CONNECT IPC, expose bootstrap/session material,
copy Link operation state, or weaken identity checks. The exact acceptance
remains ACTIVE / PARTIAL.
## 2026-09-11 — SYN-056 Docker invitation trace classification

The initial HOST-A browser hang was reproduced with the shipped UI and traced
without changing Link behavior. A Windows-built installDist mounted in Linux
contained only the Windows Netty QUIC native jar. `QuicSslContextBuilder.build`
raised `UnsatisfiedLinkError` (`FileNotFoundException` for the Linux native
resource), and the uncaught `Error` terminated the HTTP worker before a
response. A Linux-targeted rebuild initially still inherited the old daemon's
captured Windows classpath; restarting the disposable daemon corrected that
false-negative. The corrected run returned SLO1 and HTTP 201. Do not classify
this as a Docker candidate deadlock or inflate timeouts.
## 2026-09-11 — SYN-056 Docker invitation trace classification

The initial HOST-A browser hang was reproduced with the shipped UI and traced
without changing Link behavior. A Windows-built installDist mounted in Linux
contained only the Windows Netty QUIC native jar. `QuicSslContextBuilder.build`
raised `UnsatisfiedLinkError` (`FileNotFoundException` for the Linux native
resource), and the uncaught `Error` terminated the HTTP worker before a
response. A Linux-targeted rebuild initially still inherited the old daemon's
captured Windows classpath; restarting the disposable daemon corrected that
false-negative. The corrected run returned SLO1 and HTTP 201. Do not classify
this as a Docker candidate deadlock or inflate timeouts.
## 2026-09-11 — SYN-056 distinct-peer Docker engine I/O blocker

The fresh D:-backed two-identity setup succeeded through installed project
initialization and daemon/runtime readiness. A read-only `docker cp` probe then
returned `502 Bad Gateway` from the Linux engine, and bounded `docker version`
calls stopped completing. This prevents browser bootstrap, invitation, and
cleanup verification. It is an environment failure; no production or Link
change was made and no positive onboarding result is claimed.
## 2026-09-11 — SYN-056 r4 browser opener environment blocker

R4 installed Chrome 153, Xvfb, `xdg-open`, GIO, and Selenium under the
D:-backed acceptance root. Generic `xdg-open` launched Chrome, but the real
installed `synesis ui` smoke returned `SYNESIS_UI_OPEN_FAILED` because Java
reported `Desktop.Action.BROWSE=false`. The JDK Linux XDesktopPeer expects
legacy GNOME VFS symbols absent from the modern image. No Link operation or
production workaround was attempted. Do not add a Synesis browser bridge;
obtain a supported environment or explicit product-platform direction first.
## 2026-09-11 — SYN-056 durable-membership assumption rejected

The r5 `CONNECTED` result was initially recorded as partial because the
overlay remained `UNCONFIGURED` / `UNKNOWN`. A read-only audit of ADR-0066,
ADR-0068, the overlay protocol, `LinkRuntimeOwner`, `ControlPlaneHttpHandler`,
and the authoritative SYN-056 task criteria showed that this interpretation
was wrong: no signed membership authority is supplied by the CLI, and no
product action exists to exercise. Do not repair this by auto-mutating
membership on `CONNECTED` or by adding daemon/browser authority. The correct
classification is E, outside SYN-056 scope.

## 2026-09-11 — historical r4/r5 browser portability issues
## 2026-09-11 — SYN-056 closure and next-task discovery

The completed SYN-056 scope was re-audited without reopening implementation.
No authoritative `SYN-057`/`SYN-058` successor exists, and `SL-D-040` is
already complete for its current scope rather than an unactivated follow-on.
The remaining membership-authority capability requires a separate explicit
activation trigger. No production change or task activation was made.

## 2026-09-11 — SYN-056 r4/r5 browser portability slice
## 2026-09-11 — SYN-057 architecture-only routing design

The architecture slice made no production implementation attempt. It
reconstructed current SLO1/SLA2 wire/version/signature behavior and proposed
ephemeral local SLO1 selection plus a signed v2 SLA2 host return target. The
proposal is intentionally pending operator decisions; no protocol or daemon
behavior changed.

## 2026-09-11 — SYN-057 protocol-gap discovery
