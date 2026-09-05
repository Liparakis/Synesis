# SYN-049 fresh unattended two-worker acceptance — run #25 — 2026-09-06

## Result

**PARTIAL.** This fresh run crossed the corrected provider-native wake,
capability publication/consumption, exact generation-1 ownership, real
provider turns, and provider durability boundaries. It did not reach durable
WorkGroup terminalization: Worker A remained `ACTIVE` with its WorkIntent
`ANNOUNCED` after the bounded completion-followup allowance. No replacement,
second generation, Worker B, or state surgery was used.

## Provenance and host

- Synesis repository HEAD before the run: `6371240c88517cde7f0110d55b8e5142bafca3e9`
- Runtime source provenance: `a7697bbb5de83ced8b61b275056f9204e4467fbc`
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe`, Temurin `25+36-LTS`
- Process-local property: `-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`
- `Selector.open()`: PASS
- Minimal `HttpServer` create/start/stop: PASS
- Global Codex configuration was snapshotted, changed transiently by supported
  provider setup, then restored byte-for-byte. Final SHA-256:
  `D5596D80D12536BBCB7512373735FD4EBE2536A26AEEF21D495A691AF43DC240`.
- No global Java, network, Synesis production configuration, or credential
  setting was changed.

Current installed artifacts were hash-checked before launch and matched the
rebuilt provenance-locked distribution:

| artifact | SHA-256 |
|---|---|
| workspace JAR | `0224A99661EB961CB9F1803BFC6C7945E1C085874E0B929CD93DBA070DB9FF80` |
| MCP JAR | `029CCA350D77A9D362B37F5AE74BB7FA32EB1E20C30138C96D948900556AEB5F` |
| CLI JAR | `2F76646F7794A12724ABF90D86C3667A8F6F88C3AC070F222632B4F67F1276DF` |
| native MCP and installed MCP | `58C31651CC3BE0B7DE71204EA12AEA690D987E8403C31B96D9013B9EEB5360C8` |

## Fresh target and identities

Target: `C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260906-25`

- branch: `main`
- baseline/control checkout HEAD: `cc7eb7c9d5c51d42fbc8ed9b44eae4959b177b2e`
- project ID: `8f3bd406-0e96-4c60-9333-12b588c1d670`
- WorkGroup: `0d0d8761-739c-3b29-9591-223e3e04baea`
- Binding A: `session-32e7c026-9587-4c77-b270-3420b428488a`
- Participant A: `agt_3322f7cd-8e88-3678-bd2b-1b6ef159ae0d`
- WorkIntent A: `2e937450-6d10-39af-8b94-c41bcb0e9ed0`
- Binding B: `session-acfca446-0621-4c9a-b127-f56c9a7328d5`
- Participant B: `agt_009a00eb-e71b-368d-aa36-572deafce9e9`
- WorkIntent B: `37396329-9365-35f4-b491-ab752e84b2c1`
- dependency: `tasktracker.domain.persistence`

The target was initialized fresh through supported Synesis flows. No
historical `.synesis` state or historical validation lane was reused.

Claims were the normal disjoint task-tracker paths. The exact durable claim
for A was the four domain/persistence path subtrees; B's exact durable claim
was the four application/API path subtrees. The final coordination projection
preserved both claim sets on A and B's capability request was `VALIDATED`.

## Managed runtime evidence

One live launcher and one live `ProjectRuntimeHost` were retained across
`prepareFirst` and START for both bindings.

- A prepare: generation 1, `PENDING_ACTIVATION`, proof digest only
  `861d370c12f558516d55b7dfccc8e95a2065a878da00f87a2c3ec4b1b5d40ad3c`
- B prepare: generation 1, `PENDING_ACTIVATION`, proof digest only
  `bea1afca4994f5385bb537ff277c9e34480d30e5dbcc875b2c53d3b3a89631aab`
- provider thread before START: null for both
- ownership before START: absent for both
- A START: succeeded, generation 1, Thread A
  `01a073b9-7708-76a1-843d-f69a89fe7b3a`
- B START: succeeded, generation 1, Thread B
  `01a073b8-a8cd-7c02-9924-d87a5d820de9`
- second proof/generation: none observed
- bootstrap App Server: none observed
- provider-native B wake: `sameThread=true`; initial and wake turns were on
  exact Thread B
- A and B lifecycle checkpoints after cleanup: `COMPLETED`, generation 1,
  evidence complete
- final attachments: `ACTIVE`, generation 1, exact Thread A/Thread B
- final ownership: `ACTIVE`, exact binding/thread pairs, revision 2,
  `persistenceReady=true`

The App Server roots were launched through the production Windows Job
supervisor with assignment-before-resume behavior. The persisted lifecycle
checkpoints record the owned Codex root PIDs (A `1704`, B `10924`), exact root
executable, start epoch, generation 1, and complete evidence. The run did not
persist a separate MCP PID snapshot; the native MCP was launched by each
managed App Server through the production connection, and no bootstrap MCP or
alternate connection was introduced.

The harness did not separately preserve a standalone pending-authority denial
line for this SYN-049 run. No pending MCP authority acquisition was observed,
and the generation-1 activation/ownership sequence remained fail-closed.

## Capability, work, and durability

The public coordination projection reached `HEAD=120` and contained:

- `CAPABILITY_REQUEST_CREATED` at sequence 6
- `CAPABILITY_REQUEST_ACCEPTED` at sequence 10
- `CAPABILITY_IMPLEMENTATION_PUBLISHED` at sequence 30
- `CAPABILITY_VALIDATION_STARTED` at sequence 38
- `CAPABILITY_IMPLEMENTATION_VALIDATED` at sequence 39
- final capability state: `tasktracker.domain.persistence/VALIDATED`

Provider-owned Codex state was inspected read-only, without credentials:

- exact Thread A row exists in `state_5.sqlite`; rollout path exists;
- exact Thread A has 8 completed turns, 127 history items;
- exact Thread B row exists; exact Thread B has 3 completed turns, 155 history
  items;
- the corresponding rollout files exist for both exact provider threads.

Worker B produced the real isolated source/test mutation within its claims:

`src/main/java/tasktracker/api/TaskApi.java`,
`src/main/java/tasktracker/application/TaskService.java`,
`src/main/java/tasktracker/application/TaskNotFoundException.java`,
`src/test/java/tasktracker/api/TaskApiTest.java`, and
`src/test/java/tasktracker/application/TaskServiceTest.java`.

Worker A's source tree had no source diff after its owner/review/publication
workflow; only provider-generated untracked `build/classes` output remained.
This is why the run proves A's managed lifecycle and capability ownership but
does not claim an additional A source mutation.

The control checkout remained clean. No out-of-claim mutation was observed.

## Stop and classification

The host was stopped normally after the bounded A completion-followup allowance
was exhausted. No replacement was invoked, no A2 was created, no Worker B
replacement was started, and no full SYN-049 retry or SYN-051 acceptance was
run. No trusted death evidence was intentionally generated; cleanup may have
closed still-live managed connections, but no successor lifecycle was started.

Raw proofs were never printed, persisted, or placed in the evidence document;
only SHA-256 digests were recorded. Provider credentials were not read,
copied, or logged. The process-local AF_UNIX property was not persisted or
added to production configuration.

Classification: **PARTIAL**. Exact remaining blocker: the durable collaboration
projection leaves Participant A `ACTIVE` and WorkIntent A `ANNOUNCED` after the
provider has completed its bounded completion-followup turns, so WorkGroup
terminalization is not proven. SYN-049 remains `ACTIVE / PARTIAL`; SYN-051
remains complete/pass-A from its separately bounded evidence. A1-to-A2 or
Worker-B replacement validation is not authorized by this result.
