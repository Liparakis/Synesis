# Goal

- Product: Synesis, with Synesis Link as the first implemented transport/session module
- Repository type: modular-monolith Gradle project
- Current phase: unified collaboration CLI/MCP surface and raw names under SYN-024; SYN-023 is complete at CP-0269, SYN-022 is complete at CP-0268, SYN-021 is complete at CP-0266, and SYN-020 is complete at CP-0260; repository hygiene and workspace package architecture are complete; SYN-014E remains paused; SYN-012 is complete at CP-0144; SYN-011 remains VERIFYING, SYN-010B remains VERIFYING, and SYN-010A remains VERIFYING
- Networking implementation: authenticated QUIC sessions, bounded control path, application liveness, bounded direct candidate selection, demo-only application request/result exchange, and signed single-use terminal invitations
- Wider Synesis capabilities: out of scope until explicitly tasked
- Goal revision: 6
- Status: unified collaboration surface active; `Unified collaboration CLI/MCP surface and raw MCP names` is the sole ACTIVE primary task under `SYN-024`. `SYN-023` is DONE at CP-0269, `SYN-022` is DONE at CP-0268, `SYN-021` is DONE at CP-0266, and `SYN-020` is DONE at CP-0260. `SYN-014E` remains paused. SYN-012 is DONE from the real CLI evidence. SYN-011 remains VERIFYING from the supplied real-integration failure. SYN-010A's
  required license decision is recorded as AGPL-3.0-only; publication remains
  unperformed pending explicit push authorization and remaining review gates.
- Completion target: Synesis Link v1 criteria in `docs/agent/CONTRACT.md`
- Evidence: SYN-009C release evidence is complete at CP-0110; SYN-010A
  publication audit, current/reachable-history scan, documentation preparation,
  strict Java verification, and repository validators are recorded at CP-0111.
- Exact continuation: finish shared CLI/MCP readiness parity and document the raw-name migration; keep `SYN-014E` paused.
