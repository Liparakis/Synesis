# SYN-049 fresh unattended two-worker acceptance — run #29 — 2026-09-06

## Result

**FAIL / SYN-049 remains ACTIVE / PARTIAL.** This fresh target passed the
provenance, JDK25 host, lawful A/B admission, generation-1 `prepareFirst`,
and B START gates. B's App Server then exited before its first real turn
completed. The trusted lifecycle checkpoint recorded `process_exit`; no
`turn/completed`, capability wake, A START, integration, or workgroup
terminalization evidence exists.

The run was stopped at that first material failure. No historical lane was
reused, no state surgery or proof recovery was performed, no replacement or
A2 was created, and no additional acceptance retry was started.

## Source correction and verification

The preceding run's `lifecycle_worktree_mismatch` was traced to
`ProviderSessionBindingService.ensure`: an active managed binding could be
reallocated when the control checkout advanced. Commit `4426b03` adds a
narrow exact-binding guard for active `MANAGED_CONTINUITY` attachments and a
regression test. Ordinary non-managed stale, detached, dirty, and bytecode
cache recovery behavior remains covered by the existing test class.

Focused verification passed:

- `ProviderSessionBindingServiceTest`: 14/14;
- `AgentNextActionServiceTest`: 13/13;
- `CodexLifecycleWaitControlTest`: 4/4;
- `CodexWakeAdmissionServiceTest`: 6/6;
- `CodexWakeCoordinatorTest`: 1/1;
- `ProjectRuntimeHostWakeTest`: 2/2;
- clean `:cli:installDist`;
- `:cli:platformZip`.

## Host and provenance

- Synesis repository before source correction: `0f0726fc691dee8e3528d755010a078bf16602ab`;
- final Synesis repository: `4426b03be3ae9a35914be17b0b603dfc37c34f03`;
- final worktree: clean;
- runtime source provenance: `a7697bbb5de83ced8b61b275056f9204e4467fbc`;
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe`, `25+36-LTS`;
- process-local property: `-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`;
- `Selector.open()`: PASS;
- minimal `HttpServer` create/start/stop: PASS;
- no global Java or network setting was changed.

The rebuilt validation installation was disposable:
`C:\t\synesis049-install-run29`. Its payload and stable MCP launcher matched
the rebuilt artifacts exactly:

| artifact | SHA-256 |
|---|---|
| workspace JAR | `563D5C1ACC99EA314CA5F1190886C3A86B193C91F026530271D59A75DF6F9A7C` |
| MCP JAR | `029CCA350D77A9D362B37F5AE74BB7FA32EB1E20C30138C96D948900556AEB5F` |
| CLI JAR | `2F76646F7794A12724ABF90D86C3667A8F6F88C3AC070F222632B4F67F1276DF` |
| native MCP | `58C31651CC3BE0B7DE71204EA12AEA690D987E8403C31B96D9013B9EEB5360C8` |

The global Codex config was restored byte-for-byte after the transient
supported provider-install flow: before/after
`8F3A95C1AD08EC5727CF20B1651BE17AA92B15A45408DF4B46F40E0727EA87E7`.
The transient run-29 install hash was
`02BB33D65BA2A0D4F685D8E0488F50C4DE4F8BC8ED4FDA8EC39B5EEA2108A81B`.

## Fresh target and identities

Target:
`C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260906-29`.
It was created from the pristine task-tracker fixture, initialized through
the supported CLI flow on branch `main`, and had no pre-existing Synesis
runtime lane. Initial fixture commit was
`2215a11dddd3cfd084232a3fd542ae8a6e934789`; the lawful Synesis baseline was
`a6cee7cfbf99c4fd2baceee935920d42d65f8979`.

- project: `16b9ed46-5138-442f-9dee-339ff4dae4c5`;
- WorkGroup: `bc282bf7-6d08-3755-a079-b87845c89ad0`;
- A binding: `session-ca9a5e72-4bfe-4010-9913-f5bc40c69f45`;
- A participant: `agt_8e2d4882-2368-3262-a935-100e24fa3223`;
- A intent: `78f80097-5ccc-359d-8df5-55339064a9ed`;
- B binding: `session-8e6f09a8-8ef7-415a-a30a-1e44167ad96b`;
- B participant: `agt_290f86ee-c7a0-3112-a755-17692942c690`;
- B intent: `d5bc4583-2f05-3712-b476-e0d29e62e7c8`;
- dependency: `tasktracker.domain.persistence`;
- A claim: `src/main/java/tasktracker/domain`,
  `src/main/java/tasktracker/persistence`,
  `src/test/java/tasktracker/domain`,
  `src/test/java/tasktracker/persistence`;
- B claim: `src/main/java/tasktracker/application`,
  `src/main/java/tasktracker/api`,
  `src/test/java/tasktracker/application`,
  `src/test/java/tasktracker/api`.

The provider-install setup binding was not used as A or B. The validation
host held one `ManagedCodexProcessLauncher` and one `ProjectRuntimeHost` for
the A/B preparation and START sequence. Both preparations returned
generation 1 `PENDING_ACTIVATION`, retained volatile proof only in the live
launcher, and persisted digest-only records:

- A proof digest: `f4661d84542e4096f72919d9cfbd9cbbcaa0ee79048cdaff25ee3c5e38993c41`;
- B proof digest: `37f41ea6de009ebaeb443a397790c2cb4914bed2a1493c8d8fd888e01df09046`;
- provider thread before START: unresolved for both;
- provider ownership before START: absent for both.

## Failure evidence

B START returned success with generation 1 and Thread B
`01a0740f-8aee-7041-a4b9-0428911abafe`. Its App Server root executable was
the provider's Codex binary, but its root PID was no longer alive when the
host awaited the first completion checkpoint. The public lifecycle
checkpoint recorded:

- state: `FAILED`;
- revision: `11`;
- terminal diagnostic: `process_exit`;
- attachment generation: `1`;
- Thread B: `01a0740f-8aee-7041-a4b9-0428911abafe`;
- turn: `01a0740f-8cad-7571-a8d6-afe7cc20f1dd`;
- attachment: `DISCONNECTED`, revision `4`;
- ownership: exact Thread B to exact B binding, `ACTIVE`, revision `1`,
  `persistenceReady=false`.

The provider state database contains the exact Thread B row and rollout path;
the corresponding `thread_turns` row remains `inProgress`. Therefore the
provider durable boundary was not reached and no completion signal may be
claimed. No A App Server was started, no A turn ran, and no target file was
mutated. The target's final tracked state is clean with no diff.

The public coordination projection remained at HEAD 4: both A and B
participants were `ACTIVE`, both intents were `ANNOUNCED`, and no capability
request had been created. No pending MCP authority-bearing operation was
performed.

Raw proof was never printed, persisted, or included here; only digests are
recorded. Provider credentials were not read, copied, or logged. The global
Codex configuration was restored. Normal host shutdown left no run-29
managed processes; no trusted death evidence was intentionally produced.

## Classification and next action

Classification: **FAIL** for run #29; SYN-049 remains **ACTIVE / PARTIAL**.
SYN-051 remains **COMPLETE / PASS-A** from its separately bounded evidence.

The exact remaining blocker is the new managed B App Server process-exit
before its first real turn. The next action is a new bounded investigation of
that launch failure, preserving this target and run as evidence; do not retry
the acceptance, create A2, or start another worker until the cause is
isolated and separately authorized.
