# SYN-049 fresh unattended two-worker acceptance — run #31 — 2026-09-06

## Result

**FAIL / SYN-049 remains ACTIVE / PARTIAL.** This was a completely fresh
target and a fresh lawful A/B setup. The run passed the host/provenance gates,
generation-1 managed preparation and START for both workers, real A work and
publication, and provider-native wake of the original B Thread B. It stopped
at the first material coordination failure: the original B lane repeatedly
hit the server-projected recovery sequence
`workspace_stale -> ensure_session({}) -> workspace_not_ready`.

B therefore never lawfully requested completion after capability consumption.
A completed its own snapshot and entered review-only `SNAPSHOT_PENDING`, while
B remained active and the WorkGroup remained active. No substitute worker,
replacement, A2, state surgery, manual rebind, copied worktree, or push was
used.

## Host and provenance

- Synesis source checkout before run: `defe04eb1267136c8a145fc096cffd7a0a8ea40b`;
  starting worktree clean on `master`, ahead of `origin/master` by 91 commits.
- Runtime source provenance: `a7697bbb5de83ced8b61b275056f9204e4467fbc`.
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe`,
  `25+36-LTS`.
- Process-local property: `-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`.
- `Selector.open()`: PASS.
- Minimal `HttpServer` create/start/stop: PASS.
- Global Java, network, and Synesis production configuration: unchanged.

The already installed disposable runtime was used; no rebuild was performed.
Produced and installed artifacts matched exactly:

| artifact | SHA-256 |
|---|---|
| workspace JAR | `563D5C1ACC99EA314CA5F1190886C3A86B193C91F026530271D59A75DF6F9A7C` |
| MCP JAR | `029CCA350D77A9D362B37F5AE74BB7FA32EB1E20C30138C96D948900556AEB5F` |
| CLI JAR | `2F76646F7794A12724ABF90D86C3667A8F6F88C3AC070F222632B4F67F1276DF` |
| native MCP | `58C31651CC3BE0B7DE71204EA12AEA690D987E8403C31B96D9013B9EEB5360C8` |

The supported provider-install flow transiently changed the user Codex
configuration. Cleanup reverted the run-31 Synesis registration from target
`...-31` to the preceding target `...-30` and removed the run-31 trust entry;
no credentials were read or changed. The final direct `config.toml` hash was
`85BB12E3D01A8520BF63FCD5FED2D188205308BA452EFA1DE95A15F049D244DE`.
The run's recorded non-secret install digests were
`01B64D0B5ADE3CA5F762D5793A45051B977727A340430181FD3A81D26651AA07`
before setup and
`77ABFECA394294F993493B0580E42DAAF924A59C91D8E9E047322B117702E7AC`
after setup; these were not used to overwrite unrelated configuration.

## Fresh target and identities

Target: `C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260906-31`.

- fixture baseline: branch `main`, commit `2215a11dddd3cfd084232a3fd542ae8a6e934789`;
- initialized Synesis baseline: `649a07b9a6dafb34954bf2c61be2b455214b6ac3`;
- project: `e56d0e5e-fcc5-4d1e-8ed2-51acca8d8e58`;
- WorkGroup: `66aef4ff-5ad6-3690-a3f8-d40cc8fa1ba9`;
- Binding A: `session-29a34667-ec92-4924-9cba-dd32d50aacbb`;
- Participant A: `agt_22434198-197d-3878-ae5d-3fa4d38299bb`;
- WorkIntent A: `017c723b-66eb-31c8-9c30-028b4e2d29c3`;
- Binding B: `session-8af434f4-bcda-48eb-a1ac-a9b9e76e61b8`;
- Participant B: `agt_9e9da759-b56d-300c-b10d-52fd7561f19b`;
- WorkIntent B: `8188a548-5f9a-33c3-8a78-cce413fe8f0e`;
- dependency: `tasktracker.domain.persistence`.

No historical lane was reused. The original B provider connection remained
the same provider task through the dependency wait and wake; no new B was
created.

## Runtime and collaboration evidence

The disposable harness kept one live validation JVM across both
`prepareFirst` and START for each worker and used the production launcher and
`ProjectRuntimeHost` path.

- A preparation: generation 1, `PENDING_ACTIVATION`, proof digest
  `dd13fc462283a69307311dd3ef04cff77a4acb4d9080572d83663abeeda74621`;
  provider thread and ownership were unresolved before START.
- B preparation: generation 1, `PENDING_ACTIVATION`, proof digest
  `138c6ce314e72f3b5dc9b7a8eaff9be498591a63756a991ee47d591e8795c075`;
  provider thread and ownership were unresolved before START.
- A START produced exact Thread A
  `01a07447-5b17-7b02-823a-5c51e9cb558e`.
- B START produced exact Thread B
  `01a07446-a013-7391-a429-3916d9909c5c`.
- Both starts were generation 1 on the same live launcher path; no second
  preparation, proof, generation, or bootstrap App Server was used.
- App Server A PID `22916`; MCP A PID `19024`.
- App Server B PID `19492`; MCP B PID `14000`.
- Both App Servers and MCP children were launched through the production
  suspended -> Job assignment -> resume path. Normal cleanup left no managed
  processes and tore down the Jobs; no intentional trusted-death experiment
  was performed.
- Both workers reached ACTIVE with exact provider ownership and initial
  `persistenceReady=false`; the final read-only continuity inspection showed
  both generation-1 checkpoints completed and exact ownership
  `persistenceReady=true` after trusted completions.
- B's initial dependency turn entered `NEEDS_CAPABILITY`; the capability
  request was `req_N465Q4002785N4C52537A2K6E136V775`.
- The provider-native B wake used the same Thread B:
  initial turn `01a07446-a1a8-7d42-b84f-a2cff91e020b`, wake turn
  `01a0744b-2998-74f2-935a-270e0f5ba230`.
- A performed the exact claimed domain/persistence implementation and
  published an immutable snapshot. The target control checkout contains the
  resulting seven claimed source/test paths; no out-of-claim path was found.
- The final public projection was HEAD 78: A `COMPLETED` with claims cleared,
  B `ACTIVE` with its four exact application/API claim subtrees, capability
  `VALIDATED`, and WorkGroup `ACTIVE`.
- A's snapshot review state was `SNAPSHOT_PENDING` because B never completed
  its lawful recovery and completion path.

The original B rollout shows the repeated server-directed sequence after
capability validation and review admission. The final B provider turn made
only `ensure_session({})` and `get_next_action` calls; no completion request
or `finish_lane` was made after the repeated `workspace_not_ready` result.

Provider read-only state for both exact Thread A and Thread B contained the
provider thread row, rollout path, and trusted completed turns. This does not
repair the missing collaboration terminalization and is not claimed as full
SYN-049 acceptance.

## Target state and safety

- Main target checkout is clean after the Synesis immutable snapshot commits;
  it is `ahead 2, behind 1` relative to its configured `origin/main`.
- A's assigned worktree retains the seven legitimate claimed files as
  untracked evidence; they were not manually cleaned.
- B's assigned worktree remained clean.
- Raw proof was not printed, persisted, or included in evidence; only proof
  digests are recorded. Provider credentials were not read, copied, or
  logged. No raw proof was placed in the target or global Codex configuration.
- No A1 -> A2 replacement, cross-process resume, Worker B substitute, state
  surgery, or full SYN-049 retry was performed.
- Cleanup was normal after the bounded failure. No intentional DeathEvidence
  was produced; no replacement action was invoked.

## Classification and next action

Classification: **FAIL for run #31; SYN-049 remains ACTIVE / PARTIAL**.
SYN-051 remains **COMPLETE / PASS-A** from its separate bounded evidence.

Exact remaining blocker: preserve the original B provider task while its
durable Synesis worktree is stale after A's immutable snapshot integration;
the server-projected `ensure_session({})` recovery still returns
`workspace_not_ready`, preventing B from consuming the validated capability,
requesting completion, and allowing WorkGroup terminalization.

Exact next action: perform a separate bounded read-only diagnosis of this
`workspace_stale -> workspace_not_ready` recovery boundary before any new
acceptance lane. Do not reuse run #31, repair its state, invoke replacement,
start a Worker B substitute, or patch production in this evidence slice.
