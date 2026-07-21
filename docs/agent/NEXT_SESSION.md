# Next Session

- Active task: SYN-005
- Repository branch: master
- Last checkpoint: CP-0087
- Last passing command: `.\gradlew.bat clean check --dependency-verification=strict`
- Last failing command: none
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: Review the updated task state files and prepare for CP-W6 integration.
- Unresolved blockers: none
- Facts that must not be forgotten: PRP1 uses magic prefix 0x50525031; chunk size is 50; local corrupt records count prevents SUCCESS convergence; divergent heads are exchanged and quarantined on both sides; stop before :workspace integration in CP-W5.
