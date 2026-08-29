# Goal

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
- Current phase: SYN-041 is DONE / ACCEPTED at the final real Codex closure acceptance. SYN-039 remains DONE / ACCEPTED at CP-0547 and SYN-040 remains DONE / VERIFIED; neither is reopened. SYN-038 is DONE at CP-0458, with its Codex App Server lifecycle phase preserved at CP-0447/CP-0448 and its durable project-command extension completed in implementation commit `ad9fdd8`. SYN-037 completed at CP-0415. SYN-036 remains DONE at CP-0407; SYN-014E remains paused; older verification tasks retain their recorded status.
- Networking implementation: authenticated QUIC sessions, bounded control path, application liveness, bounded direct candidate selection, demo-only application request/result exchange, and signed single-use terminal invitations
- Wider Synesis capabilities: out of scope; SYN-038 is the explicitly tasked Codex-only lifecycle slice
- Goal revision: 26
- Status: active
- Goal status: Make two ordinary Synesis-aware coding agents complete one shared repository task unattended through the existing workgroup, review, handoff, validation, integration, cleanup, and diagnostics boundaries. SYN-039 is now accepted at CP-0547: independent Codex sessions preserved disjoint roles, rejected and corrected immutable snapshots, integrated only accepted work, explicitly completed the no-change reviewer lane, and left no active collaboration state. No central orchestrator, UI, daemon, Fleet system, or centralized launcher was added. SYN-038 evidence and its `turn_interrupted_command_remained_active` limitation remain unchanged. SYN-010A's required license decision is recorded as AGPL-3.0-only; publication remains unperformed pending explicit push authorization and remaining review gates.
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
