# Next Session

- Active task: SYN-019 ACTIVE under Close workspace application package architecture rule
- Repository branch: master
- Last checkpoint: CP-0230; current HEAD is `a87d3d8`.
- Last passing command: `:workspace:check`, `:cli:check`, `:mcp:check`, root `check`, Go test/vet, focused MCP tests, CLI help/version, provider list, init, and hygiene check.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: Create the closure checkpoint for `SYN-019`; preserve the unrelated README edit and keep `SYN-014E` paused.
- Unresolved limitations: none for this architecture closure; an unrelated README edit remains outside the task.
- Facts that must not be forgotten: Exactly 11 MCP tools must remain registered in `tools/list`. `DemoCli` moves only to `org.synesis.link.cli` and its Gradle main-class string must be updated. No type may move across Gradle modules. No Go bootstrap edits are permitted in this structural phase.
