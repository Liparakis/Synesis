# Next Session

- Active task: SYN-021 ACTIVE under Authenticated claim lifecycle, presence, and stale fencing
- Repository branch: master
- Last checkpoint: CP-0259; current HEAD remains unchanged because this work is intentionally uncommitted.
- Last passing command: `./gradlew.bat check --no-daemon --dependency-verification=strict`; bootstrap `go test -count=1 ./...`; bootstrap `go vet ./...`; deferred validator.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: add signed participant heartbeat and lifecycle events, wire owner-independent abandonment recovery, and test old-epoch mutation fencing.
- Unresolved limitations: an unrelated README edit remains outside the task and
  currently triggers a false positive in the existing hygiene count regex.
- Facts that must not be forgotten: Exactly 11 MCP tools must remain registered in `tools/list`. `SL-D-037`–`SL-D-039` remain deferred and are not evidence of implementation. Desktop agents must use Synesis MCP for mutations. `docs/agent/DEFERRED.md` has 9 active entries; historical IDs `SL-D-001`–`SL-D-030` are in `docs/archive/DEFERRED_FUNCTIONALITY_HISTORY.md`.
