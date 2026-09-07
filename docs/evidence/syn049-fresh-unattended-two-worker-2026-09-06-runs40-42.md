# SYN-049 fresh unattended two-worker acceptance — runs #40–#42 — 2026-09-06

## Result

**PARTIAL / SYN-049 remains ACTIVE / PARTIAL.** The bounded fresh runs
passed the host and artifact gates, lawful A/B admission, generation-1
same-process managed preparation and START, same-AppServer provider-thread
creation, managed ownership, ACTIVE promotion, provider-native B wake, real
A implementation and publication, and capability validation. Run #42 also
left both exact provider lifecycle checkpoints completed and both provider
threads durably recorded.

The acceptance did not reach lawful B downstream implementation and
completion, final review/integration, or WorkGroup terminalization. After A's
published immutable snapshot, B's managed worktree still contained only its
original skeleton and `.keep` files; the producer types were not visible in
B's isolated worktree. B preserved its authority and attempted the supported
refresh path, which correctly returned `overlapping_claim` / self-overlap.
No claim, state, worktree, proof, credential, or provider-storage bypass was
used.

This is an incomplete collaboration acceptance, not a provider durability
failure. The precise remaining boundary is a lawful production path for a
live managed dependent worker to consume an integrated producer snapshot in
its own isolated worktree without re-announcing its existing claim.

## Host, provenance, and source

- Synesis source checkout before the run sequence: `7e79faa4257e276fc053343cbc84f53b9aa34d24`, branch `master`.
- Starting worktree had the three narrow managed-continuation source/test edits later recorded with the evidence.
- Runtime source provenance: `a7697bbb5de83ced8b61b275056f9204e4467fbc`.
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe`, version `25+36-LTS`.
- Process-local property: `-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`.
- `Selector.open()`: PASS.
- Minimal `HttpServer` create/start/stop: PASS.
- No global Java, network, or Synesis production setting was changed.
- Produced and installed artifacts were hash-equal for the disposable run:

| artifact      | SHA-256                                                            |
|---------------|--------------------------------------------------------------------|
| workspace JAR | `25045AD6AAEC648584C06BC7D4BA733E04BF239BCCCE82CE60A7E167FAFDB9CD` |
| MCP JAR       | `029CCA350D77A9D362B37F5AE74BB7FA32EB1E20C30138C96D948900556AEB5F` |
| CLI JAR       | `09C148533300A5D60BFA3925BF3DD534511040CBBFB144E5F38A86062D4C658`  |
| native MCP    | `B49E39D87EEE01FB21821C05A4EAD8BCB96DF58209E5558391A270BF7FC6C48E` |

The supported provider-install flow temporarily registered the fresh target
in the user Codex configuration. Supported provider uninstall then reported
`PROVIDER_UNINSTALL_RESULT=SUCCESS`, `MANAGED_HOOK_REMOVED=true`, and
`UNRELATED_CONFIGURATION_PRESERVED=true`; the target-specific MCP registration
was absent afterward. The cleanup-point direct configuration hash was
`42C2D2B98D69EE6BA70C35FB0650CBE651DA9869D693D7F329B550600182762E`; a later
final read-only check observed
`3046E0568F20781B9FB1C147011E20B3E943E8B8E97DE751CD7B8FE0CBA3B6C7`.
No provider credentials were read, copied, or logged. Exact byte-for-byte
restoration to the older pre-run configuration hash was not established.

## Fresh target and identities — run #42

Target: `C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260906-42`.

- fixture baseline before Synesis initialization: branch `main`, commit `2215a11dddd3cfd084232a3fd542ae8a6e934789`;
- initialized Synesis baseline: `8a5516af6855a2659d9aec347ffc1d71db8b07ab`;
- project: `cde93ec3-3872-46d9-9bc9-e0f45b4d8e1e`;
- WorkGroup: `3f6f6f6a-0c5c-3abc-ae58-85c89cdf29cd`;
- Binding A: `session-b67997b9-d570-456f-a4ab-ce63a9e08590`;
- Participant A: `agt_dc574825-0267-3fcf-94b3-b8b67af19c6d`;
- WorkIntent A: `60521848-edba-3342-bae2-8fe129fc8bf6`;
- Binding B: `session-16b5acff-91db-4624-92c0-d42034708449`;
- Participant B: `agt_8f99c493-f774-3ced-ba98-f681c659179d`;
- WorkIntent B: `7525d6bf-8614-3e58-a806-4e6e627ff795`;
- exact B dependency: `tasktracker.domain.persistence`;
- capability handle: `req_E2F393K6D3S6000527Y4C5C682A2V1N2`;
- A claims: `src/main/java/tasktracker/domain`, `src/main/java/tasktracker/persistence`,
  `src/test/java/tasktracker/domain`, `src/test/java/tasktracker/persistence`;
- B claims: `src/main/java/tasktracker/application`, `src/main/java/tasktracker/api`,
  `src/test/java/tasktracker/application`, `src/test/java/tasktracker/api`.

No historical lane was reused. The target was new, no `.synesis` state was
copied or manually edited, and no substitute worker was created.

## Managed runtime and collaboration evidence

The disposable harness used the production `ManagedCodexProcessLauncher`,
`ProjectRuntimeHost`, and `CodexAppServerLifecycleService` path. Each worker's
`prepareFirst` and START ran in the same live validation JVM. Both workers
used generation 1, retained a volatile preparation proof through START, and
had no provider thread or ownership before START. Both reached ACTIVE through
the pending-attachment quarantine and same-MCP promotion path. The exact raw
proof was never printed; only digests were retained by the runtime evidence.

Run #40 and run #41 reached the same generation-1 startup and B wake path.
Run #40 exposed a harness continuation sent before B's durable participant
completion. Run #41 exposed provider-model parallel MCP calls; the harness
was then tightened to require sequential MCP calls. These were not used as
successful acceptance evidence.

Run #42 reached:

- capability lifecycle `AWAITING_OWNER -> ACCEPTED -> IMPLEMENTATION_AVAILABLE -> VALIDATED`;
- B provider-native wake on the original Thread B;
- A's real claimed domain/persistence implementation and publication;
- trusted completed lifecycle state for both exact provider threads;
- provider ownership records with `persistenceReady=true` after completion.

Read-only continuity inspection for run #42 recorded:

- Thread A: `01a074d7-47b7-7340-99b5-e8b2b055b18a`, generation 1, lifecycle `COMPLETED`, ownership revision 2,
  `persistenceReady=true`, provider row/rollout/history present;
- Thread B: `01a074d6-a0a9-7f13-b705-1212c8aa9475`, generation 1, lifecycle `COMPLETED`, ownership revision 2,
  `persistenceReady=true`, provider row/rollout/history present;
- A checkpoint revision 12 and B checkpoint revision 16, both `evidenceComplete=true`;
- A and B managed attachments remained generation-1 `ACTIVE` records after normal cleanup.

The harness observed production suspended launch, Job assignment, and resume
for the App Server/MCP process trees. The retained durable checkpoint records
preserve Codex root PIDs A=`17628` and B=`18332`; exact App Server/MCP child
PID pairs were not retained in the final run-42 evidence, so exact child PID
membership is not claimed here as a separately auditable artifact.

## Worktree result

A's assigned worktree retained these seven legitimate claimed files:

- `src/main/java/tasktracker/domain/Task.java`
- `src/main/java/tasktracker/domain/TaskStatus.java`
- `src/main/java/tasktracker/persistence/FileTaskRepository.java`
- `src/main/java/tasktracker/persistence/InMemoryTaskRepository.java`
- `src/main/java/tasktracker/persistence/TaskRepository.java`
- `src/test/java/tasktracker/domain/TaskTest.java`
- `src/test/java/tasktracker/persistence/TaskRepositoryTest.java`

B's assigned worktree retained only the skeleton and `.keep` files. The
target control checkout was clean at `8a5516af6855a2659d9aec347ffc1d71db8b07ab`;
there was no out-of-claim control mutation. Because B never lawfully
consumed the capability and completed its downstream implementation, no
accepted final integration or WorkGroup terminalization was recorded.

## Safety, cleanup, and classification

- No A1 -> A2 replacement, `replaceAfterTrustedDeath`, cross-process resume,
  state surgery, manual merge/cherry-pick, credential access, or push occurred.
- The validation host closed through normal cleanup. A deliberate death
  experiment was not run; no intentional `DeathEvidence` was produced.
- Raw managed proofs were not persisted, logged, placed in evidence, placed
  in the target, or placed in global Codex configuration. Provider credentials
  were not inspected.
- SYN-049 remains `ACTIVE / PARTIAL`.
- SYN-051 remains `COMPLETE / PASS-A` from its separately bounded evidence.
- Classification: **PARTIAL**, not PASS-A. The provider persistence boundary
  passed for both exact threads, but lawful dependent-worker consumption,
  B completion, final review/integration, and WorkGroup terminalization did
  not.

Exact next action: perform a separate read-only source/projection diagnosis of
managed dependent-worktree snapshot consumption and the self-overlap recovery
boundary before another fresh SYN-049 acceptance lane. Do not reuse run #42,
repair its state, start a substitute worker, invoke replacement, or patch
production speculatively.
