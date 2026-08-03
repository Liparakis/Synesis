# Next Session

- Active task: SYN-038 remains the sole ACTIVE bookkeeping task with completion
  evidence recorded at CP-0447. Deterministic gates and the fresh real-owner
  completion run are green; the only unproven independent outcome is MCP
  command cleanup, which remains explicitly classified.
  SYN-037 is DONE at CP-0415; SYN-036 is DONE
  at CP-0407.
- Repository branch: master.
- Last checkpoint: CP-0447; lifecycle implementation is present on the working
  tree, deterministic verification passes, and the fresh owner run recorded
  Codex validation, snapshot publication, and integrated `finish_lane` on the
  exact resumed thread. The next checkpoint must retain the exact
  interruption/command-cleanup classification.
- Evidence: `docs/evidence/syn038-codex-schema-2026-08-03.md`,
  `docs/evidence/syn038-real-codex-acceptance-2026-08-03.md`,
  `docs/evidence/syn038-real-codex-app-server-acceptance-2026-08-03.md`, and
  ADR-0043.
- Verification: focused lifecycle tests (26), the sequential root
  `./gradlew.bat check --no-daemon --max-workers=1 --console=plain`, strict
  Javadocs/static/format gates, deferred and fixture validation, bootstrap Go
  tests/vet, and `git diff --check` pass. The latest root gate ended
  `BUILD SUCCESSFUL` in 13m11s with zero module test failures/errors.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`.
- Exact next code action: none. Preserve the completed evidence and do not add
  an approval operation or claim command cleanup without direct evidence.
- Facts that must not be forgotten: the MCP surface is exactly ten raw tools;
  `run_command` is direct argv only; `/.synesis/local/`,
  `/.synesis/coordination/`, and `/.codex/hooks.json` are the only Synesis
  private exclusions; exclusion never proves provider ownership; and
  `SYN-014E` remains paused.
