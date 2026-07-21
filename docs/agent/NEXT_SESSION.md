# Next Session

- Active task: None (SYN-005 is DONE at CP-0091)
- Repository branch: master
- Last checkpoint: CP-0091
- Last passing command: `.\gradlew.bat clean check --dependency-verification=strict`
- Last failing command: none
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: Review SYN-006 / next milestone proposals after SYN-005 closure.
- Unresolved blockers: none
- Facts that must not be forgotten: PRP1 uses magic prefix 0x50525031; workspace host automatically handles both SRP1 and PRP1 incoming streams; check-action evaluates action-time constraints against local decision store and exits 10 on BLOCKED with stderr HINT=.
