# Next Session

- Active task: SYN-015 ACTIVE under Reorganize Synesis package structure following CP-0210
- Repository branch: master
- Last checkpoint: CP-0210
- Last passing command: `:coordination:check :workspace:check :cli:check :mcp:check check`, `go test ./...`, and `go vet ./...`.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: If continuation is explicitly requested, mark `STRUCT-1B` active in `docs/agent/TASKS.md`, `CURRENT.md`, and `NEXT_SESSION.md`, confirm the working tree is clean, and inventory `:workspace` package FQNs before any production move.
- Unresolved limitations: `STRUCT-1A` must remain intra-module only, preserve all CLI/MCP surfaces, and stop after the foundational-package commit/checkpoint without activating `STRUCT-1B`.
- Facts that must not be forgotten: Exactly 11 MCP tools must remain registered in `tools/list`. `DemoCli` moves only to `org.synesis.link.cli` and its Gradle main-class string must be updated. No type may move across Gradle modules. No Go bootstrap edits are permitted in this structural phase.
