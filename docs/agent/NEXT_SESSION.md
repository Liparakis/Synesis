# Next Session

- Active task: SYN-036 — Canonical baselines and lineage-aware integration.
  SYN-035 is DONE at CP-0399; SYN-029 through SYN-034 are complete.
- Repository branch: master
- Last checkpoint: CP-0399. SYN-035 ten-tool lifecycle surface, strict schemas, managed manual guidance, ADR-0041, and the full sequential check passed. The repository is clean at commit `5d8a943`.
- Last passing command: `./gradlew check --no-daemon --max-workers=1 --no-parallel`; bootstrap `go test ./...`; bootstrap `go vet ./...`.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: inspect the current MCP catalog and administrative
  state, then implement SYN-036 task 1 (catalog/rendering identities and
  administrative-state foundation).
- Unresolved limitations: Antigravity model-driven autonomy remains unclaimed;
  SYN-036 must preserve this provider-boundary statement.
- Facts that must not be forgotten: SYN-035 changes the prerelease MCP count
  to exactly ten raw tools and removes legacy aliases. `SL-D-037` is implemented
  at CP-0268; `SL-D-038` is promoted under SYN-023; `SL-D-039` remains deferred.
  Desktop agents must use Synesis MCP for mutations. `docs/agent/DEFERRED.md`
  has 9 active entries; historical IDs `SL-D-001`–`SL-D-030` are in
  `docs/archive/DEFERRED_FUNCTIONALITY_HISTORY.md`.
