# Goal

- Product: Synesis, with Synesis Link as the first implemented transport/session module
- Repository type: modular-monolith Gradle project
- Current phase: SYN-009D stable flat installation layout; SYN-011 remains VERIFYING, SYN-010B remains VERIFYING, SYN-010A remains VERIFYING, and SYN-009C is DONE
- Networking implementation: authenticated QUIC sessions, bounded control path, application liveness, bounded direct candidate selection, demo-only application request/result exchange, and signed single-use terminal invitations
- Wider Synesis capabilities: out of scope until explicitly tasked
- Goal revision: 4
- Status: contract active; SYN-009D is ACTIVE from the supplied stable-layout request. SYN-011 remains VERIFYING from the supplied real-integration failure. SYN-010A's
  required license decision is recorded as AGPL-3.0-only; publication remains
  unperformed pending explicit push authorization and remaining review gates.
- Completion target: Synesis Link v1 criteria in `docs/agent/CONTRACT.md`
- Evidence: SYN-009C release evidence is complete at CP-0110; SYN-010A
  publication audit, current/reachable-history scan, documentation preparation,
  strict Java verification, and repository validators are recorded at CP-0111.
- Exact continuation: run `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`, inspect the final checkpoint, and mark SYN-009D complete if the durable state remains consistent.
