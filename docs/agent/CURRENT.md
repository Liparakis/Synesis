# Current Task

## Identity

- Task ID: SYN-013D
- Status: READY
- Priority: P0
- Started checkpoint: (not started)
- Latest checkpoint: CP-0165
- Responsible agent: primary implementation engineer
- Related decisions: ADR-0027, ADR-0028, ADR-0029

## Objective

Implement Stage 2A: a minimal `stdio` MCP server exposing exactly 5 safe
workspace tools (`synesis.ensure_session`, `synesis.read_file`,
`synesis.apply_patch`, `synesis.run_command`, `synesis.get_next_action`).

## Immediate slice

None yet started. Stage 1 (SYN-013C) is complete and committed at `198f3e9`.
SYN-013D has not been promoted to ACTIVE.

## Evidence ledger

- VERIFIED: `AgentResponse`, `AgentOutcomeTranslator`, `AgentStatus`,
  `AgentReason`, `AgentNextAction`, `AgentMutationResult`,
  `AgentCapabilityResult`, `AgentStatusResult`, `TranslatedOutcome` exist in
  `org.synesis.workspace.agent`. All 65 `:workspace:check` tests pass.
- VERIFIED: CLI `--output agent` flag wired to `AgentOutcomeTranslator`.
- VERIFIED: `AGENTS.md` managed section uses the concise 4-bullet contract.
- VERIFIED: Full root `./gradlew.bat check --no-daemon` passes (42 tasks).
- VERIFIED: Commit `198f3e9` ("Simplify Synesis agent-facing responses") is on
  branch `master`. Working tree is clean.
- NOT STARTED: `:mcp` module, ambient session resolver, MCP provider
  configuration, MCP stdio transport.

## Work completed (Stage 1 — SYN-013C)

- Created `org.synesis.workspace.agent` package with full agent response envelope:
  `AgentStatus`, `AgentReason`, `AgentNextAction`, `AgentMutationResult`,
  `AgentCapabilityResult`, `AgentStatusResult`, `AgentResponse`.
- Created `AgentOutcomeTranslator` and `TranslatedOutcome` translating all 10
  internal `Decision` outcomes plus exceptions into safe public `AgentResponse`.
- Updated `AGENTS_BODY` in `ProjectApplicationService` to concise 4-bullet
  contract bounded by `<!-- SYNESIS-BEGIN -->` / `<!-- SYNESIS-END -->`.
- Added `--output agent` flag to `WorkspaceMutateCommand` (`:cli`).
- Added `AgentResponseTest` and `AgentOutcomeTranslatorTest` (15 + 13 tests).
- Updated existing `ProjectApplicationServiceTest` and
  `WorkspaceMutationBrokerTest` assertions to match new managed section text.
- All checks pass: `:workspace:check`, `:cli:check`, root `check` (42 tasks).

## Current limitations

- Remote HTTPS, signed remote event-read authorization, and production
  supervisor lifecycle management remain intentionally deferred.
- Codex CLI 0.140.0 does not support native pre-apply_patch hooks
  (`REAL_CODEX_PRE_MUTATION_HOOK_SUPPORTED=false`); workspace mutations are
  enforced via `WorkspaceMutationBroker` (Strategy B).
- No MCP server exists yet. Stage 2A has not started.

## Verification target

`:workspace:check` (65 tests), `:cli:check`, root `check` (42 tasks). All
pass at commit `198f3e9`.

## Immediate next action

Run `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1` then
read CONTRACT.md, GOAL.md, STATE.md, TASKS.md, and DEFERRED.md. Promote
SYN-013D to ACTIVE and begin Stage 2A by creating the `:mcp` Gradle subproject
and ambient session resolver (`AgentSessionService`) in `:workspace`.
