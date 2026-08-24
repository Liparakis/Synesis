# Failed Attempts

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
- Attempted approach: Let the existing responder stream handler claim control before classifying the first frame on every new stream.
- Expected result: A second authenticated stream would reach the demo handler.
- Observed result: The application stream was rejected as `DUPLICATE_CONTROL_STREAM` and the client received `SLF1` instead of a demo result.
- Command or evidence: `NettyQuicLoopbackTest.establishesIdentityBoundSessionOnLocalQuicControlStream` failed with demo-frame header `534c46310105`.
- Root cause: The responder claimed the shared control flag before checking whether the frame was an application frame.
- Retry prohibition: Do not restore claim-before-classification ordering.
- Evidence required before retry: N/A; fixed by reading the bounded frame first and routing non-control frames only after an authenticated session exists.
- Next hypothesis: Keep control ownership and application-stream routing separate at the first-frame classification boundary.

## 2026-07-20 — local interface candidate scan

- Date: 2026-07-20
- Task ID: SL-DEMO-001
- Attempted approach: Enumerate local interfaces for the physical demo using the existing provider.
- Expected result: Reach the live Ethernet adapter and advertise `192.168.1.100`.
- Observed result: `CANDIDATES=0` even though Windows reported Ethernet up with `192.168.1.100`.
- Command or evidence: Java interface enumeration showed many up/down virtual adapters; the provider stopped at the first down adapter.
- Root cause: `LocalInterfaceCandidateProvider` used `break` for a down interface, abandoning all later interfaces.
- Retry prohibition: Do not stop the whole scan on one down adapter; skip it and continue.
- Evidence required before retry: Targeted candidate tests and a direct gather check must return candidates on a host with live Ethernet.
- Next hypothesis: The corrected scan will produce usable same-family candidates; network reachability still requires a shared/direct topology.

## 2026-07-20 — transient full-suite test class loading

- Date: 2026-07-20
- Task ID: SL-DEMO-001
- Attempted approach: Run the full strict clean check after restoring package metadata.
- Expected result: All checks and tests pass.
- Observed result: One full run failed in `NettyQuicLoopbackTest` with `NoClassDefFoundError` for its anonymous `$2` class; the focused test rerun and immediate full rerun passed.
- Command or evidence: `gradlew.bat clean check --dependency-verification=strict`; test XML under `link/build/test-results/test`.
- Root cause: Undetermined transient test-worker/class-loading failure; no reproduction on rerun.
- Retry prohibition: Do not change production or test code based on this single non-reproducible failure.
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
