# Goal

- Product: Synesis, with Synesis Link as the first implemented transport/session module
- Repository type: modular-monolith Gradle project
- Current phase: SYN-036 is DONE at CP-0407 after completing the canonical-baseline and lineage-aware integration implementation. SYN-029 through SYN-035 are recorded complete; SYN-014E remains paused; SYN-012 is complete at CP-0144; SYN-011 remains VERIFYING, SYN-010B remains VERIFYING, and SYN-010A remains VERIFYING
- Networking implementation: authenticated QUIC sessions, bounded control path, application liveness, bounded direct candidate selection, demo-only application request/result exchange, and signed single-use terminal invitations
- Wider Synesis capabilities: out of scope until explicitly tasked
- Goal revision: 12
- Status: roadmap complete; SYN-036 is DONE with all ten implementation tasks, the canonical baseline/index/reset/portability/lineage/integration/repair invariants, the exact ten-tool contract, and the final repository gates verified at CP-0407. The requested Antigravity model-driven noninteractive acceptance could not complete; direct MCP transport remains separately evidenced and no provider-autonomy claim is made. This is the only remaining external provider limitation. SYN-035 is DONE with the ten-tool MCP lifecycle, strict schemas, managed manual, Codex ordinary completion evidence, and honest Claude authentication limitation recorded at CP-0399. `SYN-014E` remains paused. SYN-012 is DONE from the real CLI evidence. SYN-011 remains VERIFYING from the supplied real-integration failure. SYN-010A's
  required license decision is recorded as AGPL-3.0-only; publication remains
  unperformed pending explicit push authorization and remaining review gates.
- Completion target: Synesis Link v1 criteria in `docs/agent/CONTRACT.md`
- Evidence: SYN-009C release evidence is complete at CP-0110; SYN-010A
  publication audit, current/reachable-history scan, documentation preparation,
  strict Java verification, and repository validators are recorded at CP-0111.
- Exact continuation: run `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`, review CP-0407 and promote the next explicitly
  authorized task. Keep `SYN-014E` paused and do not add prerelease
  compatibility aliases.
