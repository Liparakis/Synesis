# Next Session

- Active task: SYN-015 ACTIVE under Reorganize Synesis package structure; final validation following god-class commit `04977b9`
- Repository branch: master
- Last checkpoint: CP-0219; god-class commit `04977b9` awaits final validation checkpoint
- Last passing command: `:coordination:check :workspace:check :cli:check :mcp:check check`, `go test ./...`, and `go vet ./...`.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: Run complete Gradle/Go validation, MCP 11-tool handshake, stale-reference scans, dependency/cycle checks, and inspect the working tree.
- Unresolved limitations: `STRUCT-1B` is complete at `b67ac1c` plus corrective commit `248889a`; stop after its checkpoint and do not activate `STRUCT-1C` automatically. Existing application-to-provider-specific orchestration remains for a later structural slice.
- Facts that must not be forgotten: Exactly 11 MCP tools must remain registered in `tools/list`. `DemoCli` moves only to `org.synesis.link.cli` and its Gradle main-class string must be updated. No type may move across Gradle modules. No Go bootstrap edits are permitted in this structural phase.
