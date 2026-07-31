# Goal

- Product: Synesis, with Synesis Link as the first implemented transport/session module
- Repository type: modular-monolith Gradle project
- Current phase: SYN-032 completion and incremental-integration hardening is DONE at CP-0389 after repair-lane revalidation; SYN-034 handoff is complete and SYN-035 is the sole active verification task for the ten-tool MCP lifecycle surface. SYN-029 through SYN-031 and SYN-033 remain recorded complete; SYN-014E remains paused; SYN-012 is complete at CP-0144; SYN-011 remains VERIFYING, SYN-010B remains VERIFYING, and SYN-010A remains VERIFYING
- Networking implementation: authenticated QUIC sessions, bounded control path, application liveness, bounded direct candidate selection, demo-only application request/result exchange, and signed single-use terminal invitations
- Wider Synesis capabilities: out of scope until explicitly tasked
- Goal revision: 10
- Status: SYN-032 is DONE for completion-transaction and integration hardening; SYN-034 handoff is complete and SYN-035 is active only for the externally blocked Claude acceptance. Existing provider evidence remains classified separately: Antigravity native transport is verified, but model-driven noninteractive MCP invocation is not claimed as provider autonomy. `SYN-014E` remains paused. SYN-012 is DONE from the real CLI evidence. SYN-011 remains VERIFYING from the supplied real-integration failure. SYN-010A's
  required license decision is recorded as AGPL-3.0-only; publication remains
  unperformed pending explicit push authorization and remaining review gates.
- Completion target: Synesis Link v1 criteria in `docs/agent/CONTRACT.md`
- Evidence: SYN-009C release evidence is complete at CP-0110; SYN-010A
  publication audit, current/reachable-history scan, documentation preparation,
  strict Java verification, and repository validators are recorded at CP-0111.
- Exact continuation: run `claude auth status`; run the real Claude ordinary
  acceptance only if it reports authenticated, otherwise preserve the explicit
  OAuth blocker and keep SYN-035 active for that external prerequisite. Keep
  `SYN-014E` paused and do not add prerelease compatibility aliases.
