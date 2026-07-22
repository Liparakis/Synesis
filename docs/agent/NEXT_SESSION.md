# Next Session

- Active task: SYN-009D (stable flat installation layout)
- Repository branch: master
- Last checkpoint: CP-0131
- Last passing command: `go test ./...; go vet ./...` in `bootstrap`
- Last failing command: none for SYN-009D
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: Run the final repository resume and inspect
  the new checkpoint before marking SYN-009D complete.
- Unresolved limitations: native Linux/macOS PATH behavior is not executable
  on this Windows host; cross-platform Go tests and the documented reversible
  user-local profile policy are the available evidence.
- Facts that must not be forgotten: preserve signed manifest and SHA-256
  checks, archive traversal/symlink/size defenses, legacy root until staged
  validation succeeds, external projects, and SYN-011's degraded Antigravity
  status/evidence.
