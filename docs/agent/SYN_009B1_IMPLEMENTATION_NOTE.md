# SYN-009B.1 Implementation Note

## Boundary

SYN-009B.1 evolves the existing provider lifecycle and hook adapter seam. It
adds Codex under `:workspace` and one unified CLI subcommand, while reusing
`ActionGuardrail`, `ProjectPathResolver`, `ProjectApplicationService`, and the
existing atomic provider configuration writer. It does not create a module,
apply patches, inspect transcripts, or alter the Link protocol.

The installed local baseline is `codex-cli 0.140.0` (`codex --version`). The
actual hook payload still must be captured from a real Codex `/hooks` run and
sanitized before the integration can claim real-agent compatibility.

## Existing contracts to preserve

- `ActionGuardrail.evaluate(profile, request)` remains the only constraint
  evaluator.
- `ProjectPathResolver.resolve(projectRoot, rawPath)` remains the only
  project-containment/path normalization boundary.
- Provider lifecycle writes are atomic, idempotent, project-local, and preserve
  unknown JSON fields and unrelated hook entries.
- Hook commands return process exit 0 for provider decisions; the CLI controls
  only the provider-specific stdout/stderr contract.

## Codex contract

Codex project hooks live at `<repo>/.codex/hooks.json`, under the top-level
`hooks.PreToolUse` matcher-group structure. Project-local hooks load only when
the project `.codex/` layer is trusted; Synesis must not edit Codex trust
state. The official configuration references reviewed for this boundary are:

- https://developers.openai.com/codex/config-advanced/
- https://developers.openai.com/codex/config-reference/
- https://learn.chatgpt.com/docs/hooks

The B.1 payload contract is `PreToolUse` JSON with `cwd`, `tool_name`, and
`tool_input.command`. Only `tool_name=apply_patch` is interpreted. Other tools
are unsupported, produce no decision JSON on stdout, and exit 0.

Blocked and invalid input produce a bounded JSON object with
`hookSpecificOutput.permissionDecision=deny` and a concise reason. Warnings
produce `hookSpecificOutput.additionalContext` and never ask. Allowed input
produces empty stdout and no permission decision. The adapter never writes or
applies a file.

## Parser design

`CodexApplyPatchParser` accepts only bounded patch text. It recognizes
`*** Begin Patch`/`*** End Patch` and `*** Add File:`, `*** Update File:`,
`*** Delete File:`, and `*** Move to:` directives. It returns deterministic
normalized source/destination path strings, removes duplicates, rejects
missing markers, unsupported/malformed directives, excessive input, and
traversal candidates. Move operations require both source and destination.
No patch hunk or file content is interpreted beyond the bounded directive
grammar.

The adapter resolves every returned path against payload `cwd` through
`ProjectPathResolver`, evaluates every path with `ActionGuardrail`, and folds
results deterministically: invalid/traversal or any BLOCK wins; otherwise a
warning is emitted if any path warns; all allowed paths are allowed. The
whole patch is denied when any path is blocked.

## Provider lifecycle and trust

The Codex registry entry follows Antigravity (`BETA`), Claude Code
(`EXPERIMENTAL`), then Codex (`EXPERIMENTAL`). Installation owns only the
managed `PreToolUse` command in project-local `.codex/hooks.json`; on Windows
it also writes the documented `commandWindows` launcher override. Unknown
top-level fields, unrelated event groups, and unrelated handlers survive
install/uninstall. The managed command is `synesis hook codex` and relies on
`cwd` project discovery rather than a transcript or profile argument.

Status and doctor report `TRUST_STATUS=REVIEW_REQUIRED` (or `UNKNOWN` when no
trust signal is observable), `REAL_AGENT_VALIDATION=NOT_COMPLETED`, and a
degraded state until a real run proves: block, recognized denial, reason shown,
replanning, and unchanged protected-file hash. No trust database is touched.

## Expected implementation files

- `workspace/src/main/java/org/synesis/workspace/integration/codex/CodexApplyPatchParser.java`
- `workspace/src/main/java/org/synesis/workspace/integration/codex/CodexHookAdapter.java`
- `workspace/src/main/java/org/synesis/workspace/provider/codex/CodexProviderIntegration.java`
- `workspace/src/main/java/org/synesis/workspace/application/HookApplicationService.java`
- `workspace/src/main/java/org/synesis/workspace/application/ProviderApplicationService.java`
- `workspace/src/main/java/org/synesis/workspace/provider/ProviderRegistry.java`
- unified CLI hook wiring and focused workspace/CLI tests
- Codex integration and validation documentation plus ADR-0022

This list is a boundary, not permission to add compatibility layers or
unrelated provider abstractions.

## Evidence gates

Synthetic parser/adapter/lifecycle tests and generated-launcher process
coverage are implementation gates. A sanitized real payload, exact Codex
version, and authenticated `/hooks` experiment are separate evidence gates.
Failure to complete the real experiment keeps Codex `EXPERIMENTAL` and does
not justify a stronger support claim.
