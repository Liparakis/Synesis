# Next Session

- Active task: SYN-012 (acceptance evidence complete; state closure pending)
- Repository branch: master
- Last checkpoint: CP-0144
- Last passing command: `.\gradlew.bat check --no-daemon`
- Last failing command: strict coordination Javadocs before CP-0136; fixed and reverified
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: Run the repository resume and inspect CP-0144 and the preserved real CLI evidence report.
- Unresolved limitations: native Linux/macOS PATH behavior is not executable
  on this Windows host; cross-platform Go tests and the documented reversible
  user-local profile policy are the available evidence.
- Facts that must not be forgotten: preserve signed manifest and SHA-256
  checks, archive traversal/symlink/size defenses, legacy root until staged
  validation succeeds, external projects, the exact Antigravity matcher, and
  SYN-011's degraded real-agent status/evidence. Keep coordination loopback-only
  until remote enrollment evidence exists; coordinator events retain the signed
  requester command envelope as payload.
