# Next Session

- Active task: SYN-013B (automatic provider session binding)
- Repository branch: master
- Last checkpoint: CP-0155
- Last passing command: `.\gradlew.bat check --no-daemon`
- Last failing command: root `check` before the final Javadoc fix; rerun passed after the fix
- Immediate next command: inspect `SpeculationWorkspace` reuse and implement the
  provider worktree/cwd gate for SYN-013B.
- Exact next documentation action: record the external proof cleanup hash/status
  and update the checkpoint after the focused gate tests; do not push.
- Unresolved limitations: native Linux/macOS PATH behavior is not executable
  on this Windows host; cross-platform Go tests and the documented reversible
  user-local profile policy are the available evidence.
- Facts that must not be forgotten: preserve signed manifest and SHA-256
  checks, archive traversal/symlink/size defenses, legacy root until staged
  validation succeeds, external projects, the exact Antigravity matcher, and
  SYN-011's degraded real-agent status/evidence. Keep coordination loopback-only
  until remote enrollment evidence exists; coordinator events retain the signed
  requester command envelope as payload; CLI profiles resolve identity below
  `<profile>/link`, and the external acceptance harness uses wildcard-safe
  scope tokens.
