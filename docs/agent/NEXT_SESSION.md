# Next Session

- Active task: SYN-036 — Canonical baselines and lineage-aware integration.
  SYN-035 is DONE at CP-0399; SYN-029 through SYN-034 are complete.
- Repository branch: master
- Last checkpoint: CP-0402. SYN-035 ten-tool lifecycle surface, strict schemas,
  managed manual guidance, ADR-0041, and the full sequential check passed.
  SYN-036 tasks 1 through 8 are complete at commits `71b33c5`, `14ff54f`,
  `9e62c9f`, `b3b260f`, `23dea7c`, `192f839`, and the current integration-queue
  slice, with the authority-lineage, dependency-ordering, and atomic repair
  transfer tests verified.
- Last passing commands: `./gradlew.bat :workspace:javadoc --no-daemon --max-workers=1 --no-parallel --console plain`; focused `:workspace:test --tests org.synesis.workspace.lifecycle.ManagedBaselineTransactionServiceTest --tests org.synesis.workspace.lifecycle.ManagedPathPolicyTest --tests org.synesis.workspace.lifecycle.SemanticIndexFingerprintTest --tests org.synesis.workspace.application.ProjectApplicationServiceTest --no-daemon --max-workers=1 --no-parallel --console plain`. A full `:workspace:check` attempt timed out without a completed report.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: inspect the prerelease migration and legacy-cleanup
  implementation against SYN-036 task 9, then implement only the missing
  fail-closed migration/replay safeguards while preserving active-writer
  exclusion and the ten-tool surface.
- Unresolved limitations: Antigravity model-driven autonomy remains unclaimed;
  SYN-036 must preserve this provider-boundary statement.
- Facts that must not be forgotten: SYN-035 changes the prerelease MCP count
  to exactly ten raw tools and removes legacy aliases. `SL-D-037` is implemented
  at CP-0268; `SL-D-038` is promoted under SYN-023; `SL-D-039` remains deferred.
  Desktop agents must use Synesis MCP for mutations. `docs/agent/DEFERRED.md`
  has 9 active entries; historical IDs `SL-D-001`–`SL-D-030` are in
  `docs/archive/DEFERRED_FUNCTIONALITY_HISTORY.md`.
