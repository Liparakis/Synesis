# Goal

- Product: Synesis, with Synesis Link as the first implemented transport/session module
- Repository type: modular-monolith Gradle project
- Current phase: SYN-027 multi-chat logical workgroups and isolated mutation lanes is ACTIVE; SYN-020 through SYN-026 are complete; repository hygiene and workspace package architecture are complete; SYN-014E remains paused; SYN-012 is complete at CP-0144; SYN-011 remains VERIFYING, SYN-010B remains VERIFYING, and SYN-010A remains VERIFYING
- Networking implementation: authenticated QUIC sessions, bounded control path, application liveness, bounded direct candidate selection, demo-only application request/result exchange, and signed single-use terminal invitations
- Wider Synesis capabilities: out of scope until explicitly tasked
- Goal revision: 8
- Status: collaboration roadmap complete at CP-0292 and SYN-026 is DONE. The final provider evidence distinguishes direct MCP transport from model-driven behavior and native hooks; Antigravity's recorded harness limitation is not claimed as provider enforcement. `SYN-014E` remains paused. SYN-012 is DONE from the real CLI evidence. SYN-011 remains VERIFYING from the supplied real-integration failure. SYN-010A's
  required license decision is recorded as AGPL-3.0-only; publication remains
  unperformed pending explicit push authorization and remaining review gates.
- Completion target: Synesis Link v1 criteria in `docs/agent/CONTRACT.md`
- Evidence: SYN-009C release evidence is complete at CP-0110; SYN-010A
  publication audit, current/reachable-history scan, documentation preparation,
  strict Java verification, and repository validators are recorded at CP-0111.
- Exact continuation: implement SYN-027 Phase 1 exact-caller authority hardening;
  use `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1` before
  continuing and keep `SYN-014E` paused.
