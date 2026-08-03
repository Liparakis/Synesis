# Goal

- Product: Synesis, with Synesis Link as the first implemented transport/session module
- Repository type: modular-monolith Gradle project
- Current phase: SYN-037 is DONE at CP-0415 after completing generic direct-argv execution, private runtime visibility, Codex hook ownership safety, real Codex completion/integration, and full root verification. SYN-036 is DONE at CP-0407; SYN-029 through SYN-035 are recorded complete; SYN-014E remains paused; SYN-012 is complete at CP-0144; SYN-011 remains VERIFYING, SYN-010B remains VERIFYING, and SYN-010A remains VERIFYING
- Networking implementation: authenticated QUIC sessions, bounded control path, application liveness, bounded direct candidate selection, demo-only application request/result exchange, and signed single-use terminal invitations
- Wider Synesis capabilities: out of scope until explicitly tasked
- Goal revision: 14
- Status: roadmap complete for the authorized SYN-037 slice. SYN-037 is DONE at CP-0415. The exact ten-tool surface is preserved; the command intent/adapter route is removed; command evidence exposes raw bytes read, retained bytes, and explicit truncation; private Git exclusions do not establish provider ownership; the real Codex acceptance completed an uncontaminated snapshot through integration with an empty final control status; and the two root verification fixture defects were corrected without weakening process output or snapshot artifact policy. The complete profiled root `check` now passes. SYN-036 remains DONE at CP-0407. The requested Antigravity model-driven noninteractive acceptance remains an external limitation and is not claimed. SYN-035 is DONE with the ten-tool MCP lifecycle, strict schemas, managed manual, Codex ordinary completion evidence, and honest Claude authentication limitation recorded at CP-0399. `SYN-014E` remains paused. SYN-012 is DONE from the real CLI evidence. SYN-011 remains VERIFYING from the supplied real-integration failure. SYN-010A's
  required license decision is recorded as AGPL-3.0-only; publication remains
  unperformed pending explicit push authorization and remaining review gates.
- Completion target: Synesis Link v1 criteria in `docs/agent/CONTRACT.md`
- Evidence: SYN-009C release evidence is complete at CP-0110; SYN-010A
  publication audit, current/reachable-history scan, documentation preparation,
  strict Java verification, and repository validators are recorded at CP-0111.
- Evidence: `docs/evidence/syn037-real-codex-acceptance-2026-08-03.md` and
  ADR-0042.
- Exact continuation: run `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`, review CP-0415, and promote the next explicitly
  authorized task. Keep `SYN-014E` paused and do not add prerelease
  compatibility aliases.
