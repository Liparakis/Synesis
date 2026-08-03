# Next Session

- Active task: none. SYN-036 — Canonical baselines and lineage-aware
  integration — is DONE at CP-0407; SYN-035 and SYN-029 through SYN-034 are
  complete.
- Repository branch: master
- Last checkpoint: CP-0407. SYN-036 tasks 1 through 10, the ten-tool
  contract, provider evidence, and the full sequential repository gates are
  recorded.
- Last passing commands: `./gradlew.bat check --no-daemon --max-workers=1
  --no-parallel --dependency-verification=strict --console plain`;
  `powershell -ExecutionPolicy Bypass -File
  scripts/agent-validate-deferred.ps1`; `bootstrap\go test -count=1 ./...`;
  `bootstrap\go vet ./...`; and `git diff --check`.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: run `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`, review CP-0407, and promote the next explicitly
  authorized task. No SYN-036 implementation work remains.
- Unresolved limitation: Antigravity model-driven noninteractive MCP execution
  did not complete the final provider workflow. Direct MCP transport remains
  evidenced; native/provider-supervisor autonomy is not claimed.
- Facts that must not be forgotten: SYN-035 changes the prerelease MCP count
  to exactly ten raw tools and removes legacy aliases. `SL-D-037` is implemented
  at CP-0268; `SL-D-038` is promoted under SYN-023; `SL-D-039` remains deferred.
  Desktop agents must use Synesis MCP for mutations. `docs/agent/DEFERRED.md`
  has 9 active entries; historical IDs `SL-D-001`–`SL-D-030` are in
  `docs/archive/DEFERRED_FUNCTIONALITY_HISTORY.md`.
