# Current Task

## Identity

- Task ID: SYN-037
- Status: DONE
- Priority: P0
- Started checkpoint: CP-0408
- Completed checkpoint: CP-0415 (final post-commit checkpoint)
- Responsible agent: primary SYN-037 implementation engineer
- Related decisions: ADR-0042

## Objective

Replace the prerelease command intent/adapter path with direct argv execution,
maintain exact repository-private runtime exclusions without claiming provider
configuration ownership, and prove real Codex completion through an
uncontaminated snapshot, generic validation, integration, and a clean control
checkout.

## Work completed

- Added `ProjectProcessExecutor` and `ProjectCommandSpec` as the single direct
  argv primitive for agent commands, pre-publication validation, and integration
  validation. It enforces lane-relative working directories, bounded
  head/tail evidence, concurrent draining, raw byte read/retained counts,
  explicit truncation, timeouts, cancellation, and process-tree termination.
- Replaced the legacy command-intent and build-system adapter route. The MCP
  surface remains exactly ten raw tools and `run_command` accepts only the
  canonical `argv`, `workingDirectory`, and `timeoutSeconds` request.
- Added project metadata schema v2 optional validation; schema v1 remains
  readable as no configured gate and is not rewritten automatically.
- Added exact common-directory exclusions for `/.synesis/local/`,
  `/.synesis/coordination/`, and `/.codex/hooks.json`. Exclusion is visibility
  only and never proves provider ownership.
- Added fail-closed Codex hook ownership classification, tracked/symlink/
  malformed/concurrent conflict handling, preservation of unrelated provider
  entries, and stable `PROVIDER_CONFIGURATION_CONFLICT` diagnostics.
- Completion and integration return the same structured command evidence and
  run the project-owned validation argv through the generic executor.
- Corrected the two root-verification fixture defects without changing
  production behavior: the Codex completion assertion compares logical lines
  across CRLF/LF platforms, and integration snapshots use a lane worktree
  based on the committed managed baseline before adding only the claimed
  source path.

## Verification

- Real Codex acceptance: PASS —
  `docs/evidence/syn037-real-codex-acceptance-2026-08-03.md`.
- Focused workspace suites (executor/evidence, project metadata, hook
  ownership, private exclusions, command service, completion/integration):
  PASS, including the two repaired tests.
- `:workspace:check`: PASS in 7m39s (architecture, formatting, Javadocs,
  static analysis, and all workspace tests).
- `:mcp-contract:test`, `:mcp:test --tests
  org.synesis.mcp.application.McpServerTest`: PASS.
- `:mcp-contract:javadoc`, `:mcp:javadoc`, `:workspace:javadoc`: PASS.
- Complete profiled root `check`: PASS in 15s after the workspace outputs were
  current; 57 actionable tasks and profile
  `build/reports/profile/profile-2026-08-03-09-53-49.html`.
- Deferred register validator, bootstrap Go tests/vet, and `git diff --check`:
  PASS.
- The earlier 13m32s root failure was reproduced and fixed as two test-fixture
  defects; no production command or snapshot policy behavior was changed.

## Current failures

- None. The two previously reproducible root failures now pass independently,
  in the workspace check, and in the complete root check.

## Completion state

SYN-037 final verification is complete. The real Codex participant established an exact claim,
changed only `src/task_tracker.txt`, produced three direct-argv results
(including `command_executable_not_found`), completed server-owned
pre-publication and integration validation through the same executor, published
an uncontaminated one-file snapshot, integrated it, and left the control
checkout clean. No new MCP tool, adapter, provider lifecycle, coordination
topology, or unrelated architecture was added.

## Immediate next action

Run `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`, review
CP-0415, and promote the next explicitly authorized task. Do not reopen SYN-037
without new contradictory evidence.
