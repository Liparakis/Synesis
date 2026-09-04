# SYN-051 bounded real-runtime validation — 2026-09-04

## Result

**PARTIAL / STOPPED ON FIRST MATERIAL FAILURE.** This pass used the rebuilt
installation from source HEAD `088cb239f2e2109c4dddd40cbaee36c158337e82`.
Workspace, MCP, CLI, and native launcher built/installed hashes matched:

- workspace `01D7C4CB4015B417FFADBC30A6B64C1C84EEC1F07B1306A49FED859FCCCFBE40`
- MCP `DDDC6DF0DECC0530DC30D6809DED118B9CF0E801AD7238B73AA53AB90BAE0BFD`
- CLI `78432D8DDF988E3D25933456EC9841024ED8065D4200E6ED8EBF28F030A071B4`
- launcher `A39FF82BE3355AE0EA0BA66D522D7688E2CC44BDF3FF07DB2C6C83266430BF23`

A fresh lawful Worker A binding was created for `SkibidiToilert`, with active
participant `agt_b20c250c-6c97-359b-af7b-0beda8f49440`, exact claim
`probe-runtime/replacement-a.txt`, active provider-thread ownership for
`01a069b4-1028-7472-8a77-21e847c51cc7`, and a generation-1 pending attachment.
The managed production launcher created App Server A inside the Windows Job
supervisor. Codex `0.153.0` then returned `thread not loaded` for
`thread/resume`; the lifecycle recorded `FAILED` and did not activate the
attachment or start the A1 turn.

The lifecycle's bounded startup cleanup produced a trusted generation-1 death
receipt for root PID `4952`. This is startup-failure cleanup evidence only, not
the requested controlled App Server-root crash/teardown evidence. The aborted
fresh attachment was terminalized at generation 2 through the public
application service. The historical binding `session-72ed7536-ff66-431d-ae97-fcdf27f678c3`
remains generation 1 `ACTIVE` with no receipt. No Worker B, A/B test,
replacement, A2, production source change, or push occurred.

Read-only provider confirmation in a new App Server process returned:
`thread not loaded: 01a069b4-1028-7472-8a77-21e847c51cc7`.

## Runtime matrix

| Gate | Result |
|---|---|
| Build/install provenance | PASS |
| Focused lifecycle/MCP and strict quality checks | PASS |
| Fresh binding/participant/claim/ownership | PASS |
| Job-contained managed App Server launch | PARTIAL |
| Exact pinned thread join | FAIL: `thread not loaded` |
| A1 managed turn and mutation | NOT RUN |
| Controlled root failure and trusted teardown | NOT RUN |
| Proofless replacement and A2 | NOT RUN |
| Worker B/A-B/full acceptance | NOT RUN |

## Next action

Perform a separately authorized, read-only provider-thread provenance review
for cross-process thread loadability. Preserve fail-closed proof activation;
do not retry managed A1/A2 or broaden SYN-051 until the cause is understood.

# SYN-051 shared-normal-home implementation slice — 2026-09-03

## 2026-09-04 — bounded production lifecycle replacement fix

**Classification: PARTIAL.** The trusted managed-process supervisor now
returns death evidence only after its existing root-exit, Job teardown, and
`ActiveProcesses == 0` proof succeeds. The managed launcher persists that
evidence as a project-local binding/generation receipt containing process
identity and supervisor provenance, but no raw proof or credential. The
replacement path reads the receipt itself, accepts no old proof or caller
boolean, preserves the exact binding/thread/ownership context, mints a fresh
proof and generation, and uses atomic compare-and-replace so one concurrent
replacement wins.

Focused direct execution passed 14/14 `ManagedAttachmentServiceTest` methods,
including missing evidence, exact scope, terminal rejection, old-proof replay,
and concurrent replacement. Direct changed-source compilation passed, and
the pending MCP quarantine/promotion regression passed 2/2. Gradle remains
blocked before build execution by `Unable to establish loopback connection`,
so no current-source install provenance or real-runtime rerun is claimed.

The existing real fixture's generation 1 has no trusted receipt. Its process
tree is gone, but the generation remains unrecoverable under the new protocol;
no receipt was manufactured and no `.synesis` state was edited. Real A1/A2,
Worker B, and full acceptance were not run.

## 2026-09-04 — production compatibility fix and real A boundary

**Classification: PARTIAL / compatibility boundary PASS.** The bounded fix
separates managed MCP transport admission from Synesis authority activation.
`PENDING_ACTIVATION` is accepted only after exact proof, provider, binding,
thread, generation, and `BOUND` checks; an exclusive generation-scoped OS lock
prevents a duplicate transport. The handler initializes and exposes the same
ten tools, but re-reads exact active binding/generation state before every
managed tool call. Pending `ensure_session` and mutation calls perform only
the strict read-only binding check in `SessionAuthorityResolver`, then return
a deterministic blocked response without creating session authority or
dispatching application work; after trusted lifecycle activation, the same
handler promotes in place.

The managed launcher also adds the exact non-secret
`SYNESIS_MCP_CONNECTION_INSTANCE_ID` through Codex's explicit per-server
environment override. The proof remains selected through `env_vars` and is
never written to configuration, arguments, logs, or durable records.

Focused JUnit tests passed for pending/active/invalid/stale/terminal
attachment admission, duplicate transport locking, quarantine, promotion,
catalog size, and server startup. Strict compilation and `:cli:installDist`
passed; built and installed hashes matched:

```text
workspace 69A381E347653AE84156E5F6084BF2FC5B45BD362918FF429FF23565F54F6AAC
mcp       DDDC6DF0DECC0530DC30D6809DED118B9CF0E801AD7238B73AA53AB90BAE0BFD
cli       623FA0D04265D2F9020EAF35F0437B194A6DB78896212C790C6DE20620511EA8
mcp exe   5AFDAE178EB947663E0DF274F320EB2196DDDC56C62B585EEC451CC1DB6533F9
```

Fresh real A used `C:\Users\Liparakis\Desktop\SkibidiToilert`, binding
`session-72ed7536-ff66-431d-ae97-fcdf27f678c3`, provider thread
`01a06949-dcb7-79f2-a489-167ad1d1640d`, generation 1, and proof digest
`438d99902939ae1c656654d8f923bd24d711a7495859d6c27c121cfa4ae17793`.
The harness/App Server/MCP PIDs were 6124/20412/15464 and all three were
observed in the same Windows Job. Startup reached MCP `ready`; attachment
became `ACTIVE`; a real Codex turn invoked Synesis `ensure_session`. The turn
then failed closed because the prompt requested `compat-a5.txt` while the
durable lane claimed `compat-a4.txt`; no unauthorized mutation occurred.
The exact disposable harness was stopped and its child tree disappeared.
Worker B, A/B isolation, A1/A2 restart, and full acceptance remain unrun.

The prior first failure remains preserved below: stock Codex startup ordering
was correctly identified, but the former strict startup rejection was at the
wrong layer. The compatibility fix did not weaken proof validation or grant
pending authority.

## 2026-09-04 real-runtime validation — first failure

**Classification: FAIL — stopped on the first material runtime failure.**

The exact committed source `dc9231fd48971d72e0cf3a810f68f3e503f5398b` was
clean-built and installed into the local development distribution. The
workspace, MCP, and CLI JAR hashes matched their installed copies. A fresh
`C:\Users\Liparakis\Desktop\SkibidiToilert` repository was created through
ordinary Git and supported Synesis initialization; its baseline commit was
`8cf929c4def2a5d900f654c5b99d9ebef8bc972e`.

Real Codex `0.145.0` established distinct provider sessions for the focused
workers. Managed Worker A claimed exact Thread A and received generation 1
with a pending attachment. `ManagedCodexProcessLauncher` launched the real
App Server through the Windows Job supervisor; App Server A was observed as
Job-contained and the lifecycle issued `thread/resume` for exact Thread A.

The first material failure followed: stock Codex started the configured MCP
servers during App Server initialization, before the lifecycle's exact
thread verification and managed attachment activation. The Synesis MCP
child consequently failed its initialize handshake while the attachment
was `PENDING_ACTIVATION`. The fail-closed result is correct for the security
invariant, but it means the current production ordering cannot complete a
real managed Codex turn on stock Codex 0.145.0.

Worker B, A/B proof isolation, raw unmanaged Thread-A recovery, App Server A
failure, Job teardown acceptance, A2 fresh proof/generation, and full
task-tracker acceptance were not run after this failure. No production code
changed in this validation pass. The disposable A harness and Job tree were
terminated; the fixture's baseline source remained unchanged, with only the
untracked probe evidence directory present.

Evidence: `SkibidiToilert/probe-runtime/.../generation-1.jsonl` records
`thread/resume` success followed by `synesis` MCP startup failure and no
raw proof value.

### Validation provenance and hygiene

- Codex: `codex-cli 0.145.0`; executable path
  `C:\Users\Liparakis\AppData\Local\Microsoft\WinGet\Links\codex.exe`;
  SHA-256
  `83751F15CB6A0A7B97DF67752C001E3FE1C20E18FFBFEC3FF63567296205EB6C`.
- Java: Temurin OpenJDK 25+36-LTS. Windows: Windows 11 Pro 10.0.26200,
  x64.
- Built and installed hashes matched: workspace
  `B0BF809DC59658E5F493FF42E5B82F2BC29EA005EC235DBE7E6144768ED54557`, MCP
  `FACD4ED19B9B43381AAD51142C0F7861AE6B3A93DB9359146EB10F16701FB4CC`,
  CLI `312AC4099954EB2BC14004983679CB8F268D5EE3F4BC2D3D378EABF9F1DFC0DD`.
- Built paths were `workspace/build/libs/workspace-0.1.0-SNAPSHOT.jar`,
  `mcp/build/libs/mcp-0.1.0-SNAPSHOT.jar`, and
  `cli/build/libs/cli-0.1.0-SNAPSHOT.jar`. Installed copies were under
  `cli/build/install/synesis/lib/`; launchers were under
  `cli/build/install/synesis/bin/`.
- Normal `CODEX_HOME` was used. Synesis did not read, copy, parse, or modify
  provider credentials. No raw proof appeared in the fixture evidence or
  non-secret Codex configuration scan.
- The non-secret `config.toml` baseline hash was
  `9A516E0838556D7977D285D09B8D3A57B43FDF31284CA5752EEA737D5BB7291D`;
  after the probe it was
  `C76EE5E24BA12B502B1E717F92300ED5B0375A0DFFB74FD7E9B139EFF65B94DD`.
  The change was not attributed conclusively and was not overwritten.
- Focused tests and the exact ten-tool catalog passed. The unfiltered
  workspace suite was stopped after no progress and remains incomplete.

## Prior implementation-slice classification

**PARTIAL — durable authority controls implemented; managed runtime acceptance
remains blocked at the process-container and provider-private-carrier gates.**

This record covers the bounded production slice authorized after the PASS-B
security-model decision. It does not claim that shared normal-home Codex
continuity is generally production-ready.

## Implemented controls

- Added `ProviderThreadOwnershipRecord` and `ProviderThreadOwnershipStore`.
- Active ownership is keyed by `(provider, providerThreadId)` and persisted
  below `.synesis/local/runtime/provider-thread-ownership`.
- Acquire uses one project-wide file lock plus atomic replacement; a second
  binding receives `provider_thread_owned_by_another_binding`.
- Released ownership remains a durable tombstone and is not casually reusable.
- Added `ManagedCodexThreadBroker`, which accepts only active Codex ownership,
  exposes the pinned selector, and rejects a changed provider result.
- Managed attachment issuance and replacement can derive the thread only from
  the durable owner. Provider-thread ownership is separate from attachment
  generation.
- Added explicit `NORMAL_PROVIDER_HOME_MANAGED` runtime mode and an auth policy
  classification that does not inspect provider credential files in that mode.
- The exact managed MCP binding rejects proof-less startup instead of
  downgrading to ordinary `SESSION_BOUND`; managed session resolution also
  requires an active managed attachment record.
- `UNSAFE_FILE_AUTH` remains unchanged for isolated dedicated-home mode.
- Managed launcher default is fail-closed until an owned process-tree
  supervisor is injected. Ordinary `ProcessBuilder` is not treated as
  assignment-before-resume containment.
- No MCP tool, raw App Server passthrough, provider credential read/copy,
  provider source patch, second identity graph, or Review/Doctor change was
  added.

## Verification

Starting source HEAD was
`efafef9701500dc7953c6d612c0bd54c322ea236` on `master` with a clean tree.

The changed production sources and focused test sources compile with Java 25
using `javac -Xlint:all -Werror`. Direct runtime checks for durable ownership
and immutable broker pinning passed. `git diff --check` and the deferred
register validator passed. The MCP catalog still contains exactly 10 tool
descriptors.

The focused Gradle test command was attempted twice, including the approved
IPv4 preference environment workaround, but Gradle failed before configuration
with `java.io.IOException: Unable to establish loopback connection`. Therefore
the JUnit suite is **incomplete**, not passing.

## Deliberate blockers

The repository still lacks a production Windows Job Object implementation and
the lifecycle service's existing termination path is not wired to the new
supervisor seam. The available Codex App Server child-launch evidence also
does not provide a supported private proof carrier to the exact managed MCP
child under the shared normal home. No real A/B managed run, raw-thread probe,
B-to-A provider probe, forced restart, artifact provenance run, or full
task-tracker acceptance was started.

## Exact continuation

Implement and verify the Windows supervisor with assignment-before-resume,
kill-on-close, exact root identity, and an unambiguous empty/dead predicate;
then integrate it through `CodexAppServerLifecycleService` and prove a trusted
thread-scoped managed MCP proof carrier before attempting real managed
acceptance. Preserve `UNSAFE_FILE_AUTH` and ordinary `SESSION_BOUND` behavior
until those gates pass.

## Bounded runtime-boundary follow-on — current result

The preceding blocker section records the state before the authorized
follow-on implementation. It is superseded for the current source tree by
the following bounded result; the historical feasibility claims above remain
unchanged.

**Classification: PARTIAL.**

`WindowsJobObjectProcessTreeSupervisor` is now production source. On Windows,
Java 25 FFM binds `kernel32` and uses `CreateJobObjectW`,
`SetInformationJobObject`, `CreatePipe`, `CreateProcessW`,
`AssignProcessToJobObject`, `ResumeThread`, `TerminateJobObject`,
`WaitForSingleObject`, `QueryInformationJobObject`, `IsProcessInJob`,
`GetProcessId`, `GetExitCodeProcess`, `ReadFile`, `WriteFile`, and
`CloseHandle`. The Job uses only `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`; no
breakaway flag is enabled. The process creation flags are
`CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT`, and assignment occurs before
resume. The supervisor owns the Job, root process, primary-thread, and pipe
handles. Its definitive predicate is root wait signaled, Job wait signaled,
and `ActiveProcesses == 0`; native query/wait failure is ambiguous and does
not activate a successor. Job membership plus retained process handles, not
PID disappearance, is the ownership predicate.

`CodexAppServerLifecycleService` now carries the supervisor on managed
`AppServerProcess` attachments and delegates managed startup failure, process
exit, hard stop, owner shutdown, and failed-attachment cleanup to Job teardown.
The existing ordinary launcher and legacy terminator remain available for
ordinary/fake process paths. A real Windows focused test launched a Java root
that spawned a `cmd.exe` descendant, observed both while live, then proved
both dead after explicit Job teardown and Job accounting reached zero.

The managed Codex launcher now supplies a launch-local override for
`mcp_servers.synesis.env_vars=["SYNESIS_ATTACH_PROOF"]` while placing the raw
proof only in the App Server environment. The normal Codex config is not
rewritten and no proof is placed in arguments. Ownership-derived attachment
records use `PENDING_ACTIVATION`; the trusted lifecycle verifies exact thread
response/readback and only then activates `ACTIVE`. Early authentication is
therefore rejected by the existing managed admission predicate.

Focused Gradle tests passed for the real Windows supervisor, lifecycle
package, attachment service, and exact managed MCP proof gate. A broad MCP
selection was stopped after several minutes without progress; it is recorded
as incomplete, not passing. The installed Codex version is `codex-cli
0.145.0`, but no rebuilt distribution was installed and no real Codex A/B
proof/restart acceptance was run. SYN-051 remains PARTIAL; full acceptance is
not authorized. The exact next action is to rebuild/hash/install the current
source and run only the focused real Codex child-proof, A1-to-A2 recovery,
fresh-proof rotation, and Worker B-isolation probe.
