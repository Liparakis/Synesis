# SYN-051 shared-normal-home implementation slice — 2026-09-03

## Classification

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
