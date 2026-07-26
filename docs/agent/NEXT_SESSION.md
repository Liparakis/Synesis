# Next Session

- Active task: SYN-018 ACTIVE under Repository documentation and script hygiene
- Repository branch: master
- Last checkpoint: CP-0228; current HEAD is `a67dd00`.
- Last passing command: prior package work passed focused and root checks; the current preflight root check exposed a stale architecture assertion and parallel test-result race.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: Finish the maintained-file inventory and use it to drive the documentation cleanup commit; do not reactivate `SYN-014E`.
- Unresolved limitations: the current architecture-test failure and test-result race are pre-existing verification blockers; do not fix them under repository hygiene.
- Facts that must not be forgotten: Exactly 11 MCP tools must remain registered in `tools/list`. `DemoCli` moves only to `org.synesis.link.cli` and its Gradle main-class string must be updated. No type may move across Gradle modules. No Go bootstrap edits are permitted in this structural phase.
