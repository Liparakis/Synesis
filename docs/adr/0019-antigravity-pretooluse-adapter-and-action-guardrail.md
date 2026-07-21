# 19. Antigravity PreToolUse Adapter and Shared ActionGuardrail Evaluator

- Status: Accepted
- Date: 2026-07-22
- Deciders: Core Architecture Team

## Context

SYN-007.1 delivered the Claude Code `PreToolUse` adapter but could not be verified with a real agent due to missing authentication. The developer uses Google Antigravity as their primary coding agent. SYN-008 adds the Antigravity adapter while eliminating duplicated constraint evaluation logic.

## Decision

1. **Extract `ActionGuardrail`**: A harness-neutral constraint evaluator encapsulating profile-bound `DecisionStore` load, `ProjectConstraint` filtering, `filterEffectiveActive` supersession, and per-path `appliesTo` evaluation. Both `ClaudeCodeHookAdapter` and `AntigravityHookAdapter` delegate to this shared component.

2. **`AntigravityHookAdapter`**: Translates official Antigravity `PreToolUse` JSON fields (`toolCall.name`, `toolCall.args.TargetFile`, `workspacePaths`) to `ActionGuardrail.Request`. Decision mapping:
   - `BLOCK` → `{"decision": "deny", "reason": "..."}`
   - `WARN` → `{"decision": "force_ask", "reason": "..."}`
   - `ALLOWED` → `{"decision": "ask", "reason": "..."}`
   - `UNSUPPORTED` → `{"decision": "ask"}` + stderr diagnostic
   - `INVALID_INPUT` → `{"decision": "deny", "reason": "Synesis could not safely validate the target path."}`

3. **`selectProjectRoot`**: Selects the most specific `workspacePaths` workspace root containing the target, enabling multi-workspace environments. Falls back to the profile directory. Rejects targets outside all workspace roots.

4. **Exit Code**: Always `0` for both adapters. Structured JSON decision in stdout.

## Consequences

- **Pros**: Shared constraint logic with zero duplication; harness-specific translation is minimal and explicit; each adapter remains independently testable.
- **Cons**: `run_command` shell side-effects remain unintercepted; real Antigravity GUI agent run pending user's own experimental verification (automated experiment validates adapter mechanics; real interactive agent validation validates actual harness behavior).
