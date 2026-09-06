# SYN-049 fresh unattended two-worker acceptance — runs #70–#74

Date: 2026-09-06

## Scope and provenance

The Synesis source checkout began at `c9733fd9e35a97332465f7b5326fe4bb96aba075`,
clean, on `master`, with runtime source unchanged since
`a7697bbb5de83ced8b61b275056f9204e4467fbc`. No production source was changed
for these runs. Installed artifact hashes were rechecked before the final run:

```text
workspace/build/libs/workspace-0.1.0-SNAPSHOT.jar  0b741052a309281361105a49397f0b52e4fddc144d1b74cd93e9c4f688e97ec3
mcp/build/libs/mcp-0.1.0-SNAPSHOT.jar              029cca350d77a9d362b37f5ae74bb7fa32eb1e20c30138c96d948900556aeb5f
cli/build/libs/cli-0.1.0-SNAPSHOT.jar              7da82f6ed68dcdbc9672b313a3a3f1add36f1fc2b302cc4d6afce49891c57994
cli/build/native-mcp/windows-x64/synesis-mcp.exe  4719257fccd82513df464e0e35f6ddb7f1fd25931ae24347a2237a2d71ce5a17
```

JDK: Temurin 25+36-LTS at
`C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot`. The only host
workaround was process-local:
`-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`.
The previously established `Selector.open()` and minimal `HttpServer`
create/start/stop preflight passed with this property. No global Java, network,
or Codex setting was changed. Supported provider uninstall returned success for
each completed target.

## Fresh runs

Runs #70 and #71 used fresh targets and a single live validation JVM carrying
both production launchers through generation-1 prepare/START. Both reached
same-process managed startup, real claim-aligned turns, capability publication
and validation, provider-native wake on the original B thread, and WorkGroup
`COMPLETED` with both participant intents terminal.

Run #70 integrated both immutable snapshots, but the target’s independent
compile failed because A had produced JavaBean accessors and `void` mutation/
repository methods instead of the published contract’s record-style accessors
and `Task` return types.

Run #71 corrected those shapes but the independent compile still failed because
A returned `String status()` where the consumer required `TaskStatus status()`.

Run #72 reached A snapshot/terminalization but B’s snapshot was absent. The
runner had marked provider-native wake observation with the same flag used to
suppress the explicit B capability continuation, so it did not send the exact
consumer continuation prompt. The run was stopped after this harness failure;
no state was edited.

Run #73 exposed a second runner-only ordering bug: the explicit B continuation
was gated on an unrelated A continuation flag. It was stopped after the runner
was shown to be waiting without sending B’s exact continuation.

Run #74 used the corrected disposable runner and a fresh target. It passed
fresh lawful A/B setup, generation-1 `prepareFirst`/START, and B initial
completion. A’s real owner turn then failed with lifecycle state `FAILED`,
`revision=8`, generation `1`, exact thread
`01a0776f-970e-7663-ac39-1ebbe09f37fa`, and
`terminalDiagnostic=process_exit`. The runner failed closed immediately; no
replacement, A2, cross-process resume, or substitute B was invoked.

## Durable boundaries

Run #70 is the strongest positive boundary: WorkGroup
`9b417c8f-88d7-3081-b7f6-701f5727ddf3` completed with capability
`tasktracker.domain.persistence=VALIDATED`; B wake evidence recorded
`sameThread=true`, and both final lifecycle checkpoints remained generation 1.
Run #71 independently repeated the same lifecycle endpoint with WorkGroup
`540867b7-f0e6-32a0-85f1-a8ee673631ca` completed. Neither run is a full
acceptance because the integrated compile failed.

The final run’s exact material blocker is the unexpected A AppServer process
exit during the owner turn. This is not evidence that the provider durable
state boundary is correct, nor authorization to patch production or proceed to
replacement validation.

## Classification

**PARTIAL / ACTIVE.** Generation-1 managed startup, same-thread provider-native
wake, capability publication/validation, claim-scoped coding, and WorkGroup
terminalization are proven in bounded fresh runs. Full SYN-049 acceptance is
not proven because the integrated compile contract failed in #70/#71 and the
final corrected-run attempt (#74) stopped on `process_exit` before completion.

SYN-049 remains `PARTIAL / ACTIVE`. SYN-051 remains `COMPLETE / PASS-A`; it was
not reopened. No push occurred.

## Cleanup and security

The validation-host processes and provider processes were closed or absent after
the stopped runs. Supported provider uninstall succeeded for #70, #71, #72,
#73, and #74. No raw proof, provider credential, global Codex credential, or
provider storage was read or copied. No `.synesis` surgery, manual snapshot
copy, historical lane reuse, replacement, A2, Worker C, or push was performed.

Exact next action: investigate the run-#74 AppServer `process_exit` evidence
read-only before another fresh target. Do not patch production or claim full
acceptance without a successful fresh run whose integrated target compiles and
tests.
