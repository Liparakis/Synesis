# SYN-049 fresh unattended two-worker acceptance — run #44 — 2026-09-06

## Result

**PARTIAL / SYN-049 remains ACTIVE / PARTIAL.** This fresh run passed the
current artifact, JDK25 process-local compatibility, fresh target creation,
lawful A/B binding and claim setup, same-process generation-1 preparation and
START, same-AppServer provider-thread creation, ownership, ACTIVE promotion,
and trusted provider turn completion for the initial turns. It stopped at the
first material harness/provider boundary: both App Servers reported that the
configured `synesis` MCP child closed during the MCP `initialize` handshake.
Consequently neither worker had a callable Synesis MCP namespace, so no
`get_next_action`, capability request, publication, wake/consume, coding,
integration, or terminalization could lawfully proceed.

This is not evidence of a Synesis coordination-state or provider-persistence
failure. It is an incomplete acceptance with the exact remaining boundary at
the fresh managed App-Server-to-Synesis-MCP child startup/handshake. No
filesystem, CLI, or coordination bypass was used.

## Host, provenance, and source

- Source checkout at run start: branch `master`, HEAD
  `a3c2c6c13f26f6acfe972b3207794b417c898c08`, clean before evidence edits.
- Runtime source provenance: `a7697bbb5de83ced8b61b275056f9204e4467fbc`.
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe`,
  `25+36-LTS`.
- Process-local property: `-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`.
- `Selector.open()`: PASS.
- Minimal `HttpServer` create/start/stop: PASS.
- No global Java, network, or Synesis production setting was changed.
- Runtime artifacts used by the harness were the current build outputs:

| artifact | SHA-256 |
|---|---|
| workspace JAR | `25045AD6AAEC648584C06BC7D4BA733E04BF239BCCCE82CE60A7E167FAFDB9CD` |
| MCP JAR | `029CCA350D77A9D362B37F5AE74BB7FA32EB1E20C30138C96D948900556AEB5F` |
| CLI JAR | `1DC305D844A5F309FFF9B7692F8838C27552C2EDAC45C3A13AC7C95A115E335F` |
| native MCP passed to launcher | `275A143B8B1D911B3E04E5CB5FC02B0DE28FC4E5C0CB903A1E859C5700A6EB72` |

The supported provider-install flow temporarily changed the user Codex
configuration for this fresh target. Supported uninstall returned
`SUCCESS`, `MANAGED_HOOK_REMOVED=true`, and
`UNRELATED_CONFIGURATION_PRESERVED=true`; the target-specific MCP server
entry is absent afterward. The remaining target project trust entry is an
unrelated preserved setting. Final configuration hash:
`9697DF33908C88AF2B1EC39D0973F21DAD30FDA0A81ADEB9440F89D1E2B6F49A`.
Provider credentials were not inspected.

## Fresh target and exact identities

- Target: `C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260906-44`.
- Fixture baseline: branch `main`, commit
  `2215a11dddd3cfd084232a3fd542ae8a6e934789`.
- Initialized Synesis baseline: `aba49f9ada8f37eb2c3bf94a07f015987753106e`.
- Project: `5dbc4dc1-be11-4da5-aa5d-6d75bde34aa2`.
- WorkGroup: `bbd8344b-1166-359b-9738-cc61bd671f94`.
- Binding A: `session-9f9b08eb-3b59-480a-8acb-26db7186e4df`.
- Participant A: `agt_88c2660b-fd58-3509-923f-c26356d19072`.
- WorkIntent A: `cb9d41b2-15de-3b59-bf0c-2eac3d03e211`.
- Binding B: `session-fef2f3ce-ed94-4560-9274-fda2e1fe5862`.
- Participant B: `agt_19f58758-73af-3122-926c-c3e54f825f25`.
- WorkIntent B: `7182855b-7cb5-33b7-b4ba-57d220c4528a`.
- B dependency: `tasktracker.domain.persistence`.
- A claims: the four exact `src/main/java/tasktracker/domain`,
  `src/main/java/tasktracker/persistence`,
  `src/test/java/tasktracker/domain`, and
  `src/test/java/tasktracker/persistence` subtrees.
- B claims: the four exact `src/main/java/tasktracker/application`,
  `src/main/java/tasktracker/api`,
  `src/test/java/tasktracker/application`, and
  `src/test/java/tasktracker/api` subtrees.

No historical lane was reused. The target was freshly initialized, no
`.synesis` state was copied or manually modified, and no substitute worker
was created.

## Managed lifecycle evidence

The production `ManagedCodexProcessLauncher`, `ProjectRuntimeHost`, and
`CodexAppServerLifecycleService` path ran in one live validation JVM. Both
workers were prepared before START and used generation 1:

- A: `PENDING_ACTIVATION`, proof digest
  `2708a46b264ff08f892344de63f29112c120f1114d0c939979ca8c2b89322df0`;
- B: `PENDING_ACTIVATION`, proof digest
  `76c07e227400d0f24bbdaa4428be8525ec68fc18336cdd0b77f39424de15163`;
- both provider threads were unresolved and ownership was absent before
  START;
- B START succeeded with Thread B
  `01a074f0-9707-7743-b941-2b99f1234d66`;
- A START succeeded with Thread A
  `01a074f1-8ca4-7a40-935a-e520c8c8e51d`;
- both used the same binding and generation 1 through START, with no second
  proof or generation;
- App Server PIDs were B=`24288` and A=`15028`, both child processes of the
  validation JVM PID `17232` during the run;
- the production suspended-launch/Job-assignment/resume path was used, with
  the managed App Server and MCP children launched under the same process-tree
  supervisor. The run was stopped before a separately auditable final Job
  membership capture could be retained.

Both attachments reached `ACTIVE`, and the initial real provider turns
completed on their exact threads. The managed ownership records ended at
revision 2 with `persistenceReady=true` after those provider completions.
This run did not reach the required pre-turn persistence invariant for a
claim-aligned acceptance turn because the Synesis MCP child was unavailable.

## First material failure

For both exact App Servers the trusted lifecycle evidence recorded:

`MCP client for synesis failed to start: MCP startup failed: handshaking with MCP server failed: connection closed: initialize response`

The Codex workers then correctly stopped without mutation, reporting that no
callable `mcp__synesis__*` tools existed and that
`mcp__synesis__get_next_action` could not be issued. The coordination event
projection remained at the initial three setup events: both participants
remained `ACTIVE`, both intents remained `ANNOUNCED`, and no capability
request was created. No provider-native B wake was observed in this run.

No claim-aligned coding turn, capability publication/consumption, trusted
completion for a claim mutation, final review/integration, or WorkGroup
terminalization was proven. The A/B worktrees remained clean at their
initialized baseline; the target control checkout remained clean at
`aba49f9ada8f37eb2c3bf94a07f015987753106e`.

## Safety and cleanup

- The validation host was interrupted only after the hard MCP startup
  boundary was captured; all App Server processes were gone afterward.
- Supported provider uninstall completed successfully. No replacement,
  A2, cross-process resume, Worker B substitute, state surgery, manual merge,
  or push occurred.
- Raw attachment proofs were retained only in trusted volatile/runtime
  handling and digests were the only proof values recorded in this evidence.
  No raw proof was printed or copied into the target or global Codex config.
- Provider credentials were not read, copied, logged, or modified.
- No intentional DeathEvidence was produced.

SYN-049 remains `ACTIVE / PARTIAL`. SYN-051 remains `COMPLETE / PASS-A` from
its separately bounded evidence. Classification is **PARTIAL**, not PASS-A:
the fresh managed lifecycle reached generation-1 ACTIVE and initial provider
completion, but the required Synesis MCP coordination boundary failed before
the real unattended A/B acceptance could begin.

Exact next action: perform a bounded read-only child-startup compatibility
diagnosis for the configured native Synesis MCP handshake, then—only if the
diagnosis identifies a safe, supported correction—rebuild/install/hash-verify
and run one new fresh target. Do not reuse run #44, repair its state, copy
worktrees, bypass MCP, or patch production speculatively.
