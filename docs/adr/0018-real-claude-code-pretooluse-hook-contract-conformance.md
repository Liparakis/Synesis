# 18. Real Claude Code PreToolUse Contract Conformance and Absolute Path Resolution

- Status: Accepted
- Date: 2026-07-22
- Deciders: Core Architecture Team

## Context

SYN-007 introduced the initial `ClaudeCodeHookAdapter` using a prototype response frame (`{"decision": "deny"}`) and exit code `10`. To align with official Claude Code (v2.1+) `PreToolUse` command hook specifications before external trials:
1. Blocked operations must return structured JSON `{"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny", "permissionDecisionReason": "..."}}`.
2. The hook binary must exit with code `0` so Claude Code's engine parses standard output JSON without failing the command process.
3. Claude Code passes absolute target paths (`cwd` + `tool_input.file_path`). The adapter must normalize absolute paths relative to the project root before delegating to `ScopeMatcher`.

## Decision

1. **Official `PreToolUse` Response Framing**:
   - `BLOCKED`: Exits with code `0` and outputs standard JSON:
     ```json
     {
       "hookSpecificOutput": {
         "hookEventName": "PreToolUse",
         "permissionDecision": "deny",
         "permissionDecisionReason": "Synesis blocked this edit..."
       }
     }
     ```
   - `ALLOWED` / `UNSUPPORTED`: Exits with code `0` and outputs `{}` to preserve Claude Code's native permission flow.
   - `WARNING`: Exits with code `0` and outputs `{"hookSpecificOutput": {"hookEventName": "PreToolUse", "additionalContext": "Synesis Warning: ..."}}`.

2. **Boundary-Verified Path Resolution (`ClaudeCodeHookAdapter.resolveRelativePath`)**:
   - Accepts absolute paths supplied by Claude Code.
   - Relativizes paths against project root (`cwd`).
   - Rejects targets outside the project boundary or across Windows drive letters with `INVALID_INPUT` denial.

3. **Effective Constraint Supersession (`ProjectConstraint.filterEffectiveActive`)**:
   - Explicitly filters out active constraints that are superseded by other active constraints via `supersedes` record IDs prior to evaluation.

## Consequences

- **Pros**: Complete conformance with Claude Code v2.1+ command hook contract; zero breaking exit codes; robust absolute path handling.
- **Cons**: Real authenticated model runs require a logged-in Claude Code CLI session (`claude auth login`).
