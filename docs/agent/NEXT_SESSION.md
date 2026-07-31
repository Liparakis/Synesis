# Next Session

- Active task: SYN-035 — Clear MCP lifecycle surface and autonomous action
  guidance. SYN-029 through SYN-034 are recorded complete or reconciled;
  SYN-032 remains DONE at CP-0389.
- Repository branch: master
- Last checkpoint: CP-0397. SYN-035 ten-tool lifecycle surface, strict schemas, managed manual guidance, ADR-0041, and the full sequential check passed. Focused coordination, workspace, MCP, and CLI suites pass; strict Javadocs, repository hygiene, deferred validation, full Gradle check, and bootstrap Go tests/vet pass.
- Last passing command: `./gradlew check --no-daemon --max-workers=1 --no-parallel`; bootstrap `go test ./...`; bootstrap `go vet ./...`.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: run `claude auth status`; run the real Claude ordinary
  acceptance only if authenticated, otherwise preserve the explicit OAuth
  blocker and await external authentication.
- Unresolved limitations: an unrelated README edit remains outside the task and
  currently triggers a false positive in the existing hygiene count regex.
- Facts that must not be forgotten: SYN-035 changes the prerelease MCP count
  to exactly ten raw tools and removes legacy aliases. `SL-D-037` is implemented
  at CP-0268; `SL-D-038` is promoted under SYN-023; `SL-D-039` remains deferred.
  Desktop agents must use Synesis MCP for mutations. `docs/agent/DEFERRED.md`
  has 9 active entries; historical IDs `SL-D-001`–`SL-D-030` are in
  `docs/archive/DEFERRED_FUNCTIONALITY_HISTORY.md`.
