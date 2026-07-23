# Next Session

- Active task: SYN-009D (stable flat installation layout)
- Repository branch: master
- Last checkpoint: CP-0134
- Last passing command: `gradlew.bat clean check --dependency-verification=strict --no-daemon`
- Last failing command: none for SYN-009D
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: Run the repository resume and inspect
  CP-0134, including the clean reinstall and external Antigravity wrapper
  evidence.
- Unresolved limitations: native Linux/macOS PATH behavior is not executable
  on this Windows host; cross-platform Go tests and the documented reversible
  user-local profile policy are the available evidence.
- Facts that must not be forgotten: preserve signed manifest and SHA-256
  checks, archive traversal/symlink/size defenses, legacy root until staged
  validation succeeds, external projects, the exact Antigravity matcher, and
  SYN-011's degraded real-agent status/evidence.
