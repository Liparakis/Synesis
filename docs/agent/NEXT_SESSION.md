# Next Session

- Active task: none. SYN-037 is DONE at CP-0415; SYN-036 is DONE at CP-0407.
- Repository branch: master.
- Last checkpoint: CP-0415, with direct argv execution, exact private
  exclusions, fail-closed Codex hook ownership, server-owned generic
  validation, and real Codex completion evidence recorded.
- Evidence: `docs/evidence/syn037-real-codex-acceptance-2026-08-03.md` and
  `docs/adr/0042-generic-command-execution-private-runtime-and-codex-completion.md`.
- Verification: focused repaired tests, `:workspace:check`, complete profiled
  root `check`, strict module Javadocs, deferred validation, bootstrap Go
  tests/vet, and `git diff --check` pass. The two former root failures were
  cross-platform assertion and pre-baseline fixture defects; production
  behavior and SnapshotArtifactPolicy remain unchanged.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File
  scripts/agent-resume.ps1`.
- Exact next code action: review CP-0415 and promote the next explicitly
  authorized task. Do not reopen SYN-037 without contradictory evidence.
- Facts that must not be forgotten: the MCP surface is exactly ten raw tools;
  `run_command` is direct argv only; `/.synesis/local/`,
  `/.synesis/coordination/`, and `/.codex/hooks.json` are the only Synesis
  private exclusions; exclusion never proves provider ownership; and
  `SYN-014E` remains paused.
