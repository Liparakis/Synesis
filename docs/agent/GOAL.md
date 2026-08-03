# Goal

- Product: Synesis, with Synesis Link as the first implemented transport/session module
- Repository type: modular-monolith Gradle project
- Current phase: SYN-038 remains the sole ACTIVE task after completion evidence was recorded at CP-0447; it is ready for handoff with no implementation action remaining. SYN-037 completed at CP-0415. SYN-038 adds reliable Codex App Server lifecycle ownership and control while preserving the existing ten-tool, provider-binding, and coordination boundaries. SYN-036 remains DONE at CP-0407; SYN-014E remains paused; older verification tasks retain their recorded status.
- Networking implementation: authenticated QUIC sessions, bounded control path, application liveness, bounded direct candidate selection, demo-only application request/result exchange, and signed single-use terminal invitations
- Wider Synesis capabilities: out of scope; SYN-038 is the explicitly tasked Codex-only lifecycle slice
- Goal revision: 19
- Status: SYN-038 remains the sole ACTIVE task for contract bookkeeping; completion evidence is recorded at CP-0447 and no implementation action remains. Deterministic implementation and real-owner acceptance are complete: a fresh exact binding on fixture `syn038-final3-20260803-1900` used installed Codex `0.146.0-alpha.9.2` to resume the exact thread, mutate through the ten-tool MCP surface, pass validation, publish snapshot `snap_f3bd879455900258bb77ca6cea8fac22`, and complete normal `finish_lane` integration at clean control HEAD `9e30f4183434a5f282a6e42fb55e4339c0879578`. The interrupted-turn fixture independently remains `turn_interrupted_command_remained_active`; Codex-to-MCP cancellation/tree cleanup is not claimed and is recorded as a separate acceptance outcome. The existing `synesis coordination serve` process remains the production owner; lifecycle START consumes exact authority established by existing Synesis session/collaboration services; no new daemon, listener, provider abstraction, or MCP tool is permitted. SYN-037 remains DONE at CP-0415 and its verification is the baseline. `SYN-014E` remains paused. The requested Antigravity model-driven noninteractive acceptance remains an external limitation and is not claimed. Historical Codex 0.145.0 elicitation evidence remains valid and SYN-038 remains fail-closed. Direct ten-tool MCP validation is not substituted for Codex cancellation evidence. The focused and full Gradle checks, strict/static/format gates, deferred and fixture validators, bootstrap Go tests/vet, and `git diff --check` pass. SYN-010A's
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
- Exact continuation: no implementation action remains; on a future session
  inspect CP-0447 and preserve the independent command-cleanup
  classification. Do not add an approval operation, weaken fail-closed
  interaction handling, or claim command cleanup without direct evidence. Keep
  `SYN-014E` paused and do not add prerelease compatibility aliases.
