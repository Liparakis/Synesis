# Goal

- SYN-051 provider-thread provenance review (2026-09-04): **PASS-B —
  architecture simplification found**, with overall managed compatibility
  still **PARTIAL**. Codex 0.153.0 does not make a `thread/start`-only ID
  successor-loadable across App Server processes; one persisted turn/history
  is required in the observed lifecycle. State roots matched, and the exact
  failure was not caused by Synesis proof/ownership state. No production code
  changed. See the provider evidence and ADR-0064.
- Exact next action: implement the bounded managed-generation-1 creation and
  broker pinning change, then require one persisted turn before successor or
  replacement acceptance. Rebuild/install and run focused checks only; do not
  start Worker B or full acceptance.

- SYN-051 real-runtime validation (2026-09-04): **PARTIAL / STOPPED ON FIRST
  MATERIAL FAILURE**. Current source and installed artifacts were verified;
  a fresh managed Worker A binding and provider-thread owner were created.
  Production lifecycle launch reached a Windows Job-contained App Server, but
  Codex `0.153.0` rejected the exact bootstrap-created thread with
  `thread not loaded` before A1 activation. The lifecycle failed closed and
  produced only startup-cleanup death evidence. The non-fail-fast harness
  later created generation 2 pending through the production replacement path,
  then terminalized that aborted record; no controlled crash, valid replacement
  acceptance, A2, Worker B, or broad acceptance was run.
- Exact next action: perform a read-only provider-thread provenance review;
  do not retry managed runtime or modify production code until the loadability
  cause is understood.

- SYN-051 lifecycle replacement slice (2026-09-04): **PARTIAL**. Trusted
  supervisor death evidence is persisted per binding and generation, and
  proofless replacement is race-safe, fresh-proof, and ownership-preserving.
  Missing, mismatched, stale, terminal, live, or ambiguous evidence remains a
  hard rejection. The historical real generation has no trusted receipt and
  was left untouched.
- Exact next action: resolve Gradle loopback, rebuild/install matching
  artifacts, and rerun focused lifecycle/MCP verification. Do not start Worker
  B or perform full acceptance.

- Real-runtime compatibility result (2026-09-04): **PARTIAL / boundary PASS**.
  Pending proof-bearing MCP transport now survives stock Codex early startup
  without authority, and the same connection promotes after exact thread
  verification and activation. Fresh `SkibidiToilert` A evidence reached MCP
  `ready`, exact thread resume, activation, and real Synesis `ensure_session`
  inside one Job-contained process tree. The disposable turn itself failed
  closed on a mismatched claim path (`compat-a5` versus durable `compat-a4`);
  broader acceptance remains unrun.
- Exact next action: checkpoint and commit this compatibility slice, then
  stop before Worker B or full acceptance.

- Current bounded runtime-boundary slice (2026-09-03): **PARTIAL**. A
  Windows-only Java 25 FFM Job Object supervisor is integrated through the
  managed Codex lifecycle with suspended creation, assignment before resume,
  kill-on-close, native waits, and Job accounting. Managed proofs use
  launch-local Codex `env_vars` from the App Server environment and remain
  pending until the broker-pinned thread is verified. Focused source/build and
  real root-plus-descendant containment evidence passes; real Codex child
  proof delivery and A/B restart acceptance remain outstanding.
- Exact continuation: rebuild/install with matching hashes, then run the
  focused real Codex proof-carrier and A1-to-A2/B-isolation probe only. Full
  task-tracker acceptance is not authorized.

- Selected implementation direction (2026-09-03): PASS-B shared normal
  provider-home managed continuity is authorized. Synesis, not Codex, enforces
  worker authority through unique durable `(provider, providerThreadId)`
  ownership, trusted broker-derived immutable pinning, proof-gated admission,
  generation fencing, and owned process-tree lifecycle. Historical feasibility
  evidence remains preserved; no provider-native ownership is assumed.
- Exact continuation: implement the ownership/admission/pinning slice in
  SYN-051. Preserve ordinary `SESSION_BOUND`, `UNSAFE_FILE_AUTH` for isolated
  homes, exact authority resolution, ten MCP tools, SYN-049, and all fixtures.

- Current bounded implementation (2026-09-03): SYN-051 is the single ACTIVE
  task for stock-Codex `MANAGED_CONTINUITY`. Implement one dedicated retained
  `CODEX_HOME` and App Server per logical worker, exact thread resume, selected
  `env_vars` proof delivery, hash-only durable attachment state, proof
  rotation, and monotonic generation fencing through existing strict authority
  seams. First map insertion points, then implement and verify safe isolated
  authentication, an authenticated model turn, restart/reattachment, and
  stale-generation rejection. Preserve SYN-049, SYN-050 evidence, ordinary
  `SESSION_BOUND` providers, exactly ten MCP tools, and all historical state.
  Do not weaken authority, copy worktrees, edit `.synesis`, invent IDs, push,
  tag, release, or redesign Review/Doctor.

- Current bounded follow-up (2026-09-03): SYN-049 remains PARTIAL with Defect A
  and Defect B preserved separately; its scoped implementation and direct
  acceptance are complete, but the original whole-WorkGroup criterion was not
  met. SYN-050's provider-neutral runtime-authentication design selects Result A
  for the core, and formally approves `MANAGED_CONTINUITY` with
  Synesis-supervised Codex App Server as its first target. The isolated
  inherited-pipe prototype passed. The stock isolated-runtime experiment is
  now `PASS-A` at feasibility level for isolated dedicated Codex homes; its
  dynamic child-launch boundary remains `FAIL`. Ordinary Codex/Claude MCP remain
  `SESSION_BOUND`. Do not reopen SYN-049, weaken exact authority, rewrite
  durable state, copy worktrees, or implement production continuity in this
  prototype-only pass. Evidence:
  `docs/evidence/SYN-050-protected-carrier-prototype-2026-09-03.md`,
  `docs/evidence/SYN-050-codex-app-server-child-launch-boundary-2026-09-03.md`,
  and `docs/evidence/SYN-050-stock-codex-isolated-runtime-feasibility-2026-09-03.md`.
- Provider-boundary feasibility (2026-09-03): ordinary Codex and Claude stdio
  remain `SESSION_BOUND`; static wrappers, anonymous brokers, process
  lineage, and model-visible bearers do not establish same-conversation
  continuity. The managed Codex target is approved and its isolated-runtime
  feasibility evidence is complete. Full evidence:
  `docs/evidence/SYN-050-provider-boundary-feasibility-2026-09-03.md`.
- Exact continuation: review and authorize a bounded dedicated-home managed
  implementation slice using selected `env_vars`, retained thread state, and
  proof rotation. Preserve SYN-049 and all historical fixtures.

- SYN-041 final real Codex closure acceptance (2026-08-29): one authenticated
  Codex lifecycle used the official packaged bundle through direct native MCP
  and Java, committed `PROVIDER_SESSION_TERMINALIZED` at fence sequence 7,
  and completed lawful no-change work. The original runtime was dead before
  the sole rejected same-session probe; it returned `SESSION_TERMINAL` and
  the final durable lease was `TERMINAL_DISCONNECTED` with original process
  metadata preserved. Primary result RESULT A. SYN-041 is DONE / ACCEPTED.
  Evidence: `docs/evidence/syn041-final-real-codex-closure-2026-08-29.md`.
- Exact continuation: preserve the accepted SYN-041 closure; do not create
  SYN-042 or broaden terminal-session semantics without a separately activated
  task.

- SYN-041 final real Codex terminal-seal acceptance (2026-08-28): one actual
  authenticated Codex lifecycle completed lawful no-change work and committed
  `PROVIDER_SESSION_TERMINALIZED` before native Java/MCP exit 1 and Codex exit
    0. Exact-session rebind returned `SESSION_TERMINAL`, but its clean close
       rewrote the same lease to `CLOSED_CLEANLY` while the durable terminal event
       remained. Primary result RESULT C; SYN-041 remains ACTIVE. Evidence:
       `docs/evidence/syn041-final-real-codex-terminal-seal-acceptance-2026-08-28.md`.
- Exact continuation: inspect `SessionLeaseService.markClosedCleanly` and add
  a focused regression test for terminal-history preservation. Do not run
  another provider experiment or close SYN-041 until this defect is resolved.

- SYN-041 bounded implementation slice (2026-08-28): explicit exact-session
  terminal intent and server-validated authority proof are implemented on the
  existing `finish_lane` surface. The monotonic terminal event fences
  rebind/heartbeat/wake/review/continuation/coordination authority; clean EOF
  remains `CLOSED_CLEANLY`; unsealed abnormal transport remains stale/recovery;
  sealed abnormal transport is `TERMINAL_DISCONNECTED` history. Evidence:
  `docs/evidence/syn041-terminal-session-seal-2026-08-28.md`.
- Exact continuation: inspect the implementation/evidence diff and preserve
  the clean worktree boundary; do not run another real provider experiment,
  commit, push, tag, release, migrate providers, reopen SYN-039, or add
  generalized identity architecture.

- SYN-041 terminal-disconnect semantics (2026-08-28): design-only source
  tracing proves current lane/group/binding completion is not an irreversible
  provider-session terminal state. Completed bindings can retain bounded
  review authority and the same connection evidence can be rebound. Primary
  result is RESULT C: future explicit exact-session terminal intent plus an
  atomic no-authority proof is required; clean EOF and abnormal transport must
  remain diagnostically distinct. Evidence:
  `docs/evidence/syn041-terminal-disconnect-semantics-2026-08-28.md`.
- Exact continuation: stop SYN-041; do not implement, rerun Codex, modify
  leases/Doctor, reopen SYN-039, or create SYN-042.

- SYN-041 read-only exit-code causal analysis (2026-08-28): source tracing
  proves the native launcher waits for Java and mirrors Java exit failures.
  Clean EOF returns 0 and `CLOSED_CLEANLY`; partial EOF returns 1 with
  `MCP_PARTIAL_FRAME_EOF` and `ACTIVE`; closed stdout returns 0. Classify
  RESULT B primary / RESULT D secondary. The exact CP-0557 lower-level
  exception is not uniquely proven. Evidence:
  `docs/evidence/syn041-exit-code-causal-analysis-2026-08-28.md`.
- Exact continuation: stop SYN-041 at this bounded result; do not rerun a
  provider or modify transport, leases, Doctor, migration, identity, or
  SYN-039.

- SYN-041 final handle-based native measurement (2026-08-28): exactly one
  valid real Codex run preserved direct Codex -> official MCP -> Java
  parentage. Native retained handles recorded MCP and Java exit code 1 before
  Codex exit code 0, so the run is RESULT C / MCP-Java failure. The provider
  completion path succeeded, but the internal-versus-external cause and clean
  EOF remain unresolved. Evidence:
  `docs/evidence/syn041-final-handle-native-measurement-2026-08-28.md`.
- Exact continuation: stop SYN-041 at RESULT C; do not run another provider
  measurement or modify leases, Doctor, production code, migrations,
  generalized identity, or SYN-039.

- SYN-041 design outcome (2026-08-28): no equivalent real-provider run was
  performed. The bounded next measurement is native process-handle exit
  capture plus regular-file Codex JSONL/stderr capture, with no MCP stdio
  interposer. Ordinary Windows telemetry cannot by itself attribute a
  zero-code external termination to Codex or prove literal anonymous-pipe
  EOF. Evidence: `docs/evidence/syn041-native-observability-design-2026-08-28.md`.
- SYN-041 exact blocker: Need native, non-interposing MCP/Java exit-code and
  transport-lifetime telemetry sufficient to classify the child termination
  observed ~24.5 seconds before Codex exit.

- Product: Synesis, with Synesis Link as the first implemented transport/session module
- Repository type: modular-monolith Gradle project
- Current phase: SYN-041 is DONE / ACCEPTED at the final real Codex closure acceptance. SYN-039 remains DONE / ACCEPTED
  at CP-0547 and SYN-040 remains DONE / VERIFIED; neither is reopened. SYN-038 is DONE at CP-0458, with its Codex App
  Server lifecycle phase preserved at CP-0447/CP-0448 and its durable project-command extension completed in
  implementation commit `ad9fdd8`. SYN-037 completed at CP-0415. SYN-036 remains DONE at CP-0407; SYN-014E remains
  paused; older verification tasks retain their recorded status.
- Networking implementation: authenticated QUIC sessions, bounded control path, application liveness, bounded direct
  candidate selection, demo-only application request/result exchange, and signed single-use terminal invitations
- Wider Synesis capabilities: out of scope; SYN-038 is the explicitly tasked Codex-only lifecycle slice
- Goal revision: 26
- Status: active
- Goal status: Make two ordinary Synesis-aware coding agents complete one shared repository task unattended through the
  existing workgroup, review, handoff, validation, integration, cleanup, and diagnostics boundaries. SYN-039 is now
  accepted at CP-0547: independent Codex sessions preserved disjoint roles, rejected and corrected immutable snapshots,
  integrated only accepted work, explicitly completed the no-change reviewer lane, and left no active collaboration
  state. No central orchestrator, UI, daemon, Fleet system, or centralized launcher was added. SYN-038 evidence and its
  `turn_interrupted_command_remained_active` limitation remain unchanged. SYN-010A's required license decision is
  recorded as AGPL-3.0-only; publication remains unperformed pending explicit push authorization and remaining review
  gates.
- Completion target: Synesis Link v1 criteria in `docs/agent/CONTRACT.md`
- Evidence: SYN-009C release evidence is complete at CP-0110; SYN-010A
  publication audit, current/reachable-history scan, documentation preparation,
  strict Java verification, and repository validators are recorded at CP-0111.
- Evidence: `docs/architecture/zero-touch-agent-collaboration.md`,
  `docs/validation/multi-chat-provider-acceptance.md`,
  `docs/evidence/syn037-real-codex-acceptance-2026-08-03.md`,
  `docs/evidence/syn038-codex-schema-2026-08-03.md`,
  `docs/evidence/syn038-real-codex-acceptance-2026-08-03.md`,
  `docs/evidence/syn038-real-codex-app-server-acceptance-2026-08-03.md`, and
  ADR-0043.
- Exact continuation: preserve the SYN-041 RESULT E native-topology
  measurement; the polling harness lost the Codex transcript and native EOF/
  child exit evidence. Keep SYN-039/SYN-040 closed and `SYN-014E` paused; no
  lease or Doctor change is authorized.
