# SYN-049 fresh unattended two-worker acceptance — run #28 — 2026-09-06

## Result

**FAIL / SYN-049 remains ACTIVE / PARTIAL.** This fresh skeleton-based run
passed the host and provenance gates, lawful A/B admission, same-process
generation-1 preparation and START, real A implementation/publication, and the
provider-native B wake on the original Thread B. It stopped at the first
material production failure: the exact same-thread B continuation was rejected
with `lifecycle_worktree_mismatch`.

No replacement, second generation, Worker B substitute, state surgery, or
full acceptance retry was used.

## Provenance and host

- Synesis repository HEAD before the run: `892a99f293374ef823045717fec3db0345275862`
- Starting Synesis worktree: clean
- Runtime source provenance: `a7697bbb5de83ced8b61b275056f9204e4467fbc`
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe`, `25+36-LTS`
- Process-local property: `-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`
- `Selector.open()`: PASS
- Minimal `HttpServer` create/start/stop: PASS
- Global Codex config before provider setup: SHA-256
  `8F3A95C1AD08EC5727CF20B1651BE17AA92B15A45408DF4B46F40E0727EA87E7`
- Transient supported-install hash:
  `E9CDB993FAC28B485BBA25E11AF421E38FAFCB1E6F8D7A4E2C7E53F9473DBB8A`
- Global Codex config after restore:
  `8F3A95C1AD08EC5727CF20B1651BE17AA92B15A45408DF4B46F40E0727EA87E7`
- No global setting, Java/network setting, Synesis production configuration,
  or credential was changed.

Runtime artifacts and the validation-host install matched exactly:

| artifact | SHA-256 |
|---|---|
| workspace JAR | `0224A99661EB961CB9F1803BFC6C7945E1C085874E0B929CD93DBA070DB9FF80` |
| MCP JAR | `029CCA350D77A9D362B37F5AE74BB7FA32EB1E20C30138C96D948900556AEB5F` |
| CLI JAR | `2F76646F7794A12724ABF90D86C3667A8F6F88C3AC070F222632B4F67F1276DF` |
| native MCP | `58C31651CC3BE0B7DE71204EA12AEA690D987E8403C31B96D9013B9EEB5360C8` |

## Fresh target and exact identities

Target: `C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260906-28`

- baseline branch: `main`
- baseline fixture commit: `c86676c62387bd6c3d4b5818289cc5b4a00a5109`
- initialized Synesis base commit: `7b9b5125e5021a62016a7fadf59590e5d4ebe4e4`
- project ID: `3db6eefb-397a-4f4a-ac49-ab2897ec010c`
- WorkGroup: `3f10d4c6-832e-3818-856d-463ecde89d5f`
- Binding A: `session-a761f968-9ba6-4147-be08-611b21593812`
- Participant A: `agt_f00ca0a5-87ff-375f-9b80-ae4e839fb761`
- WorkIntent A: `488fc47c-69de-36ef-82d5-bd712576521c`
- Binding B: `session-4123e7f4-094c-4a0c-8480-ebe6d7e57ffb`
- Participant B: `agt_3106814a-0e4a-342d-881b-c6fb9aafff9a`
- WorkIntent B: `9152cce3-ec3d-358e-8c55-5508c15294f3`
- dependency: `tasktracker.domain.persistence`

The target was created through supported flows. No historical lane or
historical `.synesis` state was reused. A's exact durable claim was:

```text
src/main/java/tasktracker/domain
src/main/java/tasktracker/persistence
src/test/java/tasktracker/domain
src/test/java/tasktracker/persistence
```

B's exact durable claim was:

```text
src/main/java/tasktracker/application
src/main/java/tasktracker/api
src/test/java/tasktracker/application
src/test/java/tasktracker/api
```

## Managed generation-1 evidence

One live `ManagedCodexProcessLauncher` and one live `ProjectRuntimeHost` were
retained across `prepareFirst` and START.

- A prepare: `PENDING_ACTIVATION`, generation 1, proof digest
  `6bd074cae6b188e27d455f8f8fc42922e09d91eb96a63939b9b63607e5879fb2`
- B prepare: `PENDING_ACTIVATION`, generation 1, proof digest
  `fda23b1301e6ad60d5075de9dc5e3eaf00ffa082c6d91e84a9d092b36c368875`
- provider thread before START: null for both
- ownership before START: absent for both
- B START: succeeded, generation 1, Thread B
  `01a073e5-3a7c-7760-ae12-b02b3a427d38`
- A START: succeeded, generation 1, Thread A
  `01a073e6-067e-7332-bd70-60605f9a1fdb`
- second proof/generation: none
- bootstrap App Server: none
- launcher lifetime: same live launcher/host process across preparation and
  both START operations

The live process tree observed the production launch order and parentage:

- B App Server PID `15164`; B MCP child PID `21704`
- A App Server PID `6924`; A MCP child PID `25764`
- both App Servers were children of the validation JVM, and each MCP was a
  child of its corresponding App Server
- production START succeeded only after the Windows Job supervisor's
  ownership check; the native implementation is assignment-before-resume
  (`CREATE_SUSPENDED -> AssignProcessToJobObject -> ResumeThread`)
- a separate opaque Job handle identifier was not exposed by the production
  API; no claim beyond the production ownership check is made here

Pending MCP authority was not separately exercised with a harmless operation.
No pending authority acquisition was observed. The B wake was provider-native
and exact: `sameThread=true`, initial turn
`01a073e5-3ba1-76f3-bb19-db73b20dc138`, wake turn
`01a073ec-0887-7952-b721-692d4b70045d`, same Thread B and generation 1.

## Work, failure, and durability

A performed real implementation from the empty claim subtrees and published
the exact capability. Public capability events were:

- `CAPABILITY_REQUEST_CREATED` sequence 7
- `CAPABILITY_REQUEST_ACCEPTED` sequence 11
- `CAPABILITY_IMPLEMENTATION_PUBLISHED` sequence 67
- `CAPABILITY_VALIDATION_STARTED` sequence 72
- `CAPABILITY_IMPLEMENTATION_VALIDATED` sequence 73

A's integrated snapshot contained exactly these seven paths:

```text
src/main/java/tasktracker/domain/Task.java
src/main/java/tasktracker/domain/TaskStatus.java
src/main/java/tasktracker/persistence/FileTaskRepository.java
src/main/java/tasktracker/persistence/InMemoryTaskRepository.java
src/main/java/tasktracker/persistence/TaskRepository.java
src/test/java/tasktracker/domain/TaskTest.java
src/test/java/tasktracker/persistence/TaskRepositoryTest.java
```

After A publication, B's provider-native wake completed on the original Thread
B. The harness then attempted one normal same-thread continuation while B was
still durably ACTIVE. Production rejected it with:

```text
lifecycle_worktree_mismatch
```

Read-only public binding evidence explains the rejection. B's original
provider row still referenced worktree
`...\\worktrees\\session-4123e7f4-094c-4a0c-8480-ebe6d7e57ffb`, while the
durable B binding had been rewritten to recovery worktree
`...\\worktrees\\session-4123e7f4-094c-4a0c-8480-ebe6d7e57ffb-recovery-af6a4e26-3118-4666-98cd-f92affd7045d`
with base commit `88e820595d83a3b6a947f3e9453be4d0dee93f0f`. The exact
same-thread continuation therefore failed the production worktree fence. This
is a production boundary failure, not a missing provider wake or a harness
substitution.

At normal cleanup, A and B lifecycle checkpoints were generation 1 `STOPPED`
with complete evidence; attachments were `DISCONNECTED`; ownership records
retained the exact Thread A/Thread B pairs and `persistenceReady=true`, revision
2. The run did not reach B capability consumption, B implementation, final
integration, or WorkGroup terminalization.

Provider-owned state was inspected read-only, without credentials:

- Thread A row and rollout exist; 3 completed turns and 153 history items
- Thread B row and rollout exist; 2 completed turns and 127 history items
- no provider storage was modified

The control checkout ended clean at `88e820595d83a3b6a947f3e9453be4d0dee93f0f`.
A's isolated worktree retained only the seven claimed source/test files as
untracked implementation output. B's original worktree retained no mutation.
No out-of-claim mutation was observed.

Raw proofs were never printed, persisted, or placed in this evidence; only
digests are recorded. Provider credentials were not read, copied, or logged.
The AF_UNIX workaround remained process-local. Normal host cleanup stopped the
managed trees and produced no intentionally generated trusted-death test
evidence. Replacement was not invoked; no A2 and no Worker B replacement was
created. No push occurred.

## Classification and next action

Classification: **FAIL / SYN-049 ACTIVE / PARTIAL**.

Exact blocker: the original B provider thread and provider process remained
bound to the original worktree, but Synesis rewrote B's durable binding to a
recovery worktree during/after A integration. The next same-thread continuation
was correctly rejected by the production worktree fence. Diagnose and fix this
binding-recovery/reconciliation race read-only and with focused tests before a
new fresh acceptance lane. Do not reuse this target or invoke replacement.

SYN-051 remains complete/pass-A from its separately bounded evidence. A1-to-A2
validation is not authorized by this result.
