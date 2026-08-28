# Goal

- Product: Synesis, with Synesis Link as the first implemented transport/session module
- Repository type: modular-monolith Gradle project
- Current phase: SYN-039 is DONE / ACCEPTED at CP-0547. SYN-038 is DONE at CP-0458, with its Codex App Server lifecycle phase preserved at CP-0447/CP-0448 and its durable project-command extension completed in implementation commit `ad9fdd8`. SYN-037 completed at CP-0415. SYN-036 remains DONE at CP-0407; SYN-014E remains paused; older verification tasks retain their recorded status.
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
- Exact continuation: select and explicitly promote the next authorized task
  before beginning implementation; do not reopen SYN-039 for its separately
  recorded Doctor/provider-session hygiene warnings, and keep `SYN-014E`
  paused. No new SYN milestone is created by this closure.
