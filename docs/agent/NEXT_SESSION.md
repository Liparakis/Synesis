# Next Session

- Active task: SYN-038 remains the sole ACTIVE bookkeeping task. The prior
  Codex App Server lifecycle phase is complete with evidence at CP-0447/CP-0448;
  the durable project-command extension is now active. The prior unproven
  MCP command cleanup outcome remains explicitly classified.
  SYN-037 is DONE at CP-0415; SYN-036 is DONE
  at CP-0407.
- Repository branch: master.
- Last checkpoint: CP-0457; prior lifecycle implementation and evidence remain
  preserved. The next checkpoint must record the durable-command namespace,
  permanent-lock, format, process-anchor, and two-process spike evidence while
  retaining the exact interruption/command-cleanup classification.
- Evidence: `docs/evidence/syn038-codex-schema-2026-08-03.md`,
  `docs/evidence/syn038-real-codex-acceptance-2026-08-03.md`,
  `docs/evidence/syn038-real-codex-app-server-acceptance-2026-08-03.md`, and
  ADR-0043.
- Verification: focused durable-command tests, full root `check`, strict
  Javadocs/static/format gates, deferred and fixture validation, bootstrap Go
  tests/vet, doctor, and `git diff --check` pass. The final MCP class has 32
  passing tests.
- Immediate next command: resume with
  `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`, then
  inspect CP-0457 before any completion-commit decision.
- Exact next code action: none required for this fix. Preserve the completed
  App Server history, do not overwrite prior evidence, add no approval
  operation, create no SYN-039, and do not commit or tag until the user
  explicitly authorizes completion packaging.
- Facts that must not be forgotten: the MCP surface is exactly ten raw tools;
  `run_command` is direct argv only; `/.synesis/local/`,
  `/.synesis/coordination/`, and `/.codex/hooks.json` are the only Synesis
  private exclusions; exclusion never proves provider ownership; and
  `SYN-014E` remains paused.
