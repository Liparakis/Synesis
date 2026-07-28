# Next Session

- Active task: none; collaboration roadmap complete at SYN-025 / CP-0292
- Repository branch: master
- Last checkpoint: CP-0293; roadmap-completion validator fix and final clean-state checkpoint pass. Direct MCP evidence is complete for Codex, Claude, and Antigravity; model-driven Antigravity prompting and native hooks remain separately unverified.
- Last passing command: `./gradlew.bat check --no-daemon --dependency-verification=strict`; bootstrap `go test -count=1 ./...`; bootstrap `go vet ./...`; deferred validator.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: no collaboration roadmap task remains; run `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1` before any separately authorized future task.
- Unresolved limitations: an unrelated README edit remains outside the task and
  currently triggers a false positive in the existing hygiene count regex.
- Facts that must not be forgotten: Exactly 11 MCP tools must remain registered in `tools/list`. `SL-D-037` is implemented at CP-0268; `SL-D-038` is promoted under SYN-023; `SL-D-039` remains deferred. Desktop agents must use Synesis MCP for mutations. `docs/agent/DEFERRED.md` has 9 active entries; historical IDs `SL-D-001`–`SL-D-030` are in `docs/archive/DEFERRED_FUNCTIONALITY_HISTORY.md`.
