# Next Session

- Active task: SYN-012 (public CLI implementation; final verification pending)
- Repository branch: master
- Last checkpoint: CP-0144
- Last passing command: `powershell -ExecutionPolicy Bypass -File scripts/run-speculative-coordination-real.ps1`
- Last failing command: root `check` before the final Javadoc and public CLI fixes; rerun after checkpoint
- Immediate next command: `.\gradlew.bat check --no-daemon`
- Exact next documentation action: Record strict check, bundle reinstall, and installed external-project acceptance evidence, then close SYN-012 if all pass.
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
