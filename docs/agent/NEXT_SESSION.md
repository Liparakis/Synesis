# Next Session

- Active task: SYN-017 ACTIVE under Organize workspace application packages
- Repository branch: master
- Last checkpoint: CP-0227; coordination commit `195fc95`; workspace application commit `27595c1`.
- Last passing command: root `check --no-daemon`, `:workspace:check`, `:cli:check`, `:mcp:check`, `go test -count=1 ./...`, and `go vet ./...`.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: Verify `SYN-017` durable state and review the committed package map; do not activate later structural or quality tasks automatically.
- Unresolved limitations: `STRUCT-1B` is complete at `b67ac1c` plus corrective commit `248889a`; stop after its checkpoint and do not activate `STRUCT-1C` automatically. Existing application-to-provider-specific orchestration remains for a later structural slice.
- Facts that must not be forgotten: Exactly 11 MCP tools must remain registered in `tools/list`. `DemoCli` moves only to `org.synesis.link.cli` and its Gradle main-class string must be updated. No type may move across Gradle modules. No Go bootstrap edits are permitted in this structural phase.
