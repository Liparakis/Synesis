# Next Session

- Active task: SYN-004
- Repository branch: master
- Last checkpoint: CP-0082
- Last passing command: `gradlew.bat clean check --dependency-verification=strict`
- Last failing command: none
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: Await next task description and user instructions.
- Unresolved blockers: none
- Facts that must not be forgotten: No changes to Link wire protocols (SDR1, SRP1); the single invitation URI format query parameters are project, record, host; the host Node ID is extracted from the URI and cryptographically pinned; failure exit codes must match 10 and print next-action hints; decision records must never be treated as executable tasks.
