# Goal

- Product: Synesis, with Synesis Link as the first implemented transport/session module
- Repository type: modular-monolith Gradle project
- Current phase: staged package-structure refactor Phase 1 with `STRUCT-1B` complete and `STRUCT-1C` not activated; SYN-012 is complete at CP-0144; SYN-011 remains VERIFYING, SYN-010B remains VERIFYING, and SYN-010A remains VERIFYING
- Networking implementation: authenticated QUIC sessions, bounded control path, application liveness, bounded direct candidate selection, demo-only application request/result exchange, and signed single-use terminal invitations
- Wider Synesis capabilities: out of scope until explicitly tasked
- Goal revision: 5
- Status: contract active; `Reorganize Synesis package structure` is the sole ACTIVE primary task under `SYN-015`. `STRUCT-1A — Foundational packages` is complete at commit `376f2d2ce6003b32d28994b19b6728926ab0af6e`; `STRUCT-1B` is complete at `b67ac1c` plus corrective commit `248889a`; `STRUCT-1C` and `STRUCT-1D` remain inactive until explicitly resumed. `SYN-014E` is paused pending completion of the staged structural work. SYN-012 is DONE from the real CLI evidence. SYN-011 remains VERIFYING from the supplied real-integration failure. SYN-010A's
  required license decision is recorded as AGPL-3.0-only; publication remains
  unperformed pending explicit push authorization and remaining review gates.
- Completion target: Synesis Link v1 criteria in `docs/agent/CONTRACT.md`
- Evidence: SYN-009C release evidence is complete at CP-0110; SYN-010A
  publication audit, current/reachable-history scan, documentation preparation,
  strict Java verification, and repository validators are recorded at CP-0111.
- Exact continuation: checkpoint the completed `STRUCT-1B` slice and stop; activate `STRUCT-1C` only after explicit continuation.
