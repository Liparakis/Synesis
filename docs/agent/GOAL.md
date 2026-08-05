# Goal

- Product: Synesis, with Synesis Link as the first implemented transport/session module
- Repository type: modular-monolith Gradle project
- Current phase: SYN-038 is DONE at CP-0458. Its Codex App Server lifecycle phase remains complete at CP-0447/CP-0448, and its durable project-command extension is complete in implementation commit `ad9fdd8`. The extension preserves the existing ten-tool, provider-binding, coordination, and App Server boundaries. SYN-037 completed at CP-0415. SYN-036 remains DONE at CP-0407; SYN-014E remains paused; older verification tasks retain their recorded status. No successor task was promoted.
- Networking implementation: authenticated QUIC sessions, bounded control path, application liveness, bounded direct candidate selection, demo-only application request/result exchange, and signed single-use terminal invitations
- Wider Synesis capabilities: out of scope; SYN-038 is the explicitly tasked Codex-only lifecycle slice
- Goal revision: 21
- Status: roadmap complete
- Goal status: Preserve the completed App Server evidence described above and close the SYN-038 durable project-command extension. The existing `synesis coordination serve` process remains the production owner; no new daemon, listener, provider abstraction, or MCP tool was added. The extension includes the bounded namespace/lock/format/process-anchor spike, durable typed request replay/conflict, release/reacquire admission, phase persistence, fail-closed cleanup, diagnostics, deterministic fixtures, and limited real-Codex acceptance. The prior interrupted-turn classification `turn_interrupted_command_remained_active` remains unchanged. Do not create SYN-039 or reinterpret prior evidence. SYN-010A's
  required license decision is recorded as AGPL-3.0-only; publication remains
  unperformed pending explicit push authorization and remaining review gates.
- Completion target: Synesis Link v1 criteria in `docs/agent/CONTRACT.md`
- Evidence: SYN-009C release evidence is complete at CP-0110; SYN-010A
  publication audit, current/reachable-history scan, documentation preparation,
  strict Java verification, and repository validators are recorded at CP-0111.
- Evidence: `docs/evidence/syn037-real-codex-acceptance-2026-08-03.md`,
  `docs/evidence/syn038-codex-schema-2026-08-03.md`,
  `docs/evidence/syn038-real-codex-acceptance-2026-08-03.md`,
  `docs/evidence/syn038-real-codex-app-server-acceptance-2026-08-03.md`, and
  ADR-0043.
- Exact continuation: no SYN-038 implementation action remains. If the
  repository is resumed, verify the pushed closure commit and tag before any
  new task is promoted. Do not add an approval operation, weaken fail-closed
  interaction handling, create SYN-039, or claim command cleanup without
  direct evidence. Keep `SYN-014E` paused and do not add prerelease
  compatibility aliases.
