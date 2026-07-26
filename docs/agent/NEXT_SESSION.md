# Next Session

- Active task: none; SYN-016 DONE under Organize coordination domain packages
- Repository branch: master
- Last checkpoint: CP-0224; coordination commit `195fc95`
- Last passing command: `:coordination:check :workspace:check :cli:check :mcp:check check`, `go test ./...`, and `go vet ./...`.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: Do not make production edits; keep `SYN-014E` paused unless a new task is explicitly activated.
- Unresolved limitations: `STRUCT-1B` is complete at `b67ac1c` plus corrective commit `248889a`; stop after its checkpoint and do not activate `STRUCT-1C` automatically. Existing application-to-provider-specific orchestration remains for a later structural slice.
- Facts that must not be forgotten: Exactly 11 MCP tools must remain registered in `tools/list`. `DemoCli` moves only to `org.synesis.link.cli` and its Gradle main-class string must be updated. No type may move across Gradle modules. No Go bootstrap edits are permitted in this structural phase.
