# Goal

- Product: Synesis, with Synesis Link as the first implemented transport/session module
- Repository type: modular-monolith Gradle project
- Current phase: SYN-039 is ACTIVE. SYN-038 is DONE at CP-0458, with its Codex App Server lifecycle phase preserved at CP-0447/CP-0448 and its durable project-command extension completed in implementation commit `ad9fdd8`. SYN-037 completed at CP-0415. SYN-036 remains DONE at CP-0407; SYN-014E remains paused; older verification tasks retain their recorded status.
- Networking implementation: authenticated QUIC sessions, bounded control path, application liveness, bounded direct candidate selection, demo-only application request/result exchange, and signed single-use terminal invitations
- Wider Synesis capabilities: out of scope; SYN-038 is the explicitly tasked Codex-only lifecycle slice
- Goal revision: 26
- Status: active
- Goal status: Make two ordinary Synesis-aware coding agents complete one shared repository task unattended through the existing workgroup, review, handoff, validation, integration, cleanup, and diagnostics boundaries. SYN-039 must preserve independent Codex/Claude Code sessions underneath Synesis and must not add a central orchestrator, UI, daemon, Fleet system, or centralized launcher. CP-0532 exact-projection evidence proves the existing protocol can complete one shared coding WorkGroup end to end with both grants, immutable snapshots, structured validation, integration, passing control tests, and terminal WorkGroup completion. The required ordinary follow-up still stops at provider/session engagement after fail-closed rejection of wrong review arguments; no unchanged projected action failed. SYN-038 evidence and its `turn_interrupted_command_remained_active` limitation remain unchanged. SYN-010A's required license decision is recorded as AGPL-3.0-only; publication remains unperformed pending explicit push authorization and remaining review gates.
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
- Exact continuation: run `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`, then run one fresh ordinary unattended two-agent
  Todo acceptance with only complementary coding prompts and the current
  bundled MCP. Preserve the first unchanged projected-action failure or
  missing usable action; classify wrong agent arguments and provider-turn
  termination separately. Keep `SYN-014E` paused and do not create SYN-040,
  push, or add prerelease compatibility aliases.
