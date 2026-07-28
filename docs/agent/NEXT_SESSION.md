# Next Session

- Active task: SYN-023 ACTIVE under Contract-aware pre-merge compatibility checks
- Repository branch: master
- Last checkpoint: CP-0268; contract revision parity is committed and SYN-023 is promoted.
- Last passing command: `./gradlew.bat check --no-daemon --dependency-verification=strict`; bootstrap `go test -count=1 ./...`; bootstrap `go vet ./...`; deferred validator.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: add shared prepare/check integration validation and deterministic snapshot compatibility tests.
- Unresolved limitations: an unrelated README edit remains outside the task and
  currently triggers a false positive in the existing hygiene count regex.
- Facts that must not be forgotten: Exactly 11 MCP tools must remain registered in `tools/list`. `SL-D-037` is implemented at CP-0268; `SL-D-038` is promoted under SYN-023; `SL-D-039` remains deferred. Desktop agents must use Synesis MCP for mutations. `docs/agent/DEFERRED.md` has 9 active entries; historical IDs `SL-D-001`–`SL-D-030` are in `docs/archive/DEFERRED_FUNCTIONALITY_HISTORY.md`.
