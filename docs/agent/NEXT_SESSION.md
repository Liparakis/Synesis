# Next Session

- Active task: SYN-019 ACTIVE under Close workspace application package architecture rule
- Repository branch: master
- Last checkpoint: CP-0240; current HEAD is `c9b509b` before the current documentation-only commit.
- Last passing command: `./gradlew.bat check --no-daemon --dependency-verification=strict`; bootstrap `go test -count=1 ./...`; bootstrap `go vet ./...`; deferred validator.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: Review and commit the deferred coordination-feature wording update and CP-0241; no production capability work.
- Unresolved limitations: an unrelated README edit remains outside the task and
  currently triggers a false positive in the existing hygiene count regex.
- Facts that must not be forgotten: Exactly 11 MCP tools must remain registered in `tools/list`. No production code, CLI surface, or MCP surface changed in the deferred-register cleanup. `SL-D-037`–`SL-D-039` are the canonical entries for these coordination features; do not create duplicate parking-lot or research documents. `docs/agent/DEFERRED.md` has 9 active entries; historical IDs `SL-D-001`–`SL-D-030` are in `docs/archive/DEFERRED_FUNCTIONALITY_HISTORY.md`.
