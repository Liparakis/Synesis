# Synesis — Google Antigravity PreToolUse Hook Integration Guide

**Module**: `:workspace` / `AntigravityHookAdapter`
**Command**: `synesis-workspace --profile <profile> hook antigravity`
**Configuration File**: `<workspace>/.agents/hooks.json`

---

## 1. Overview

Synesis provides pre-action guardrail enforcement for Google Antigravity. When Antigravity attempts a file mutation tool (`write_to_file`, `replace_file_content`, `multi_replace_file_content`), the `PreToolUse` hook intercepts the operation, evaluates target file paths against reconciled Synesis project constraints, and returns a decision JSON to Antigravity before disk modifications occur.

---

## 2. Configuration Setup

Add the following to `<workspace>/.agents/hooks.json`:

```json
{
  "synesis-guardrail": {
    "PreToolUse": [
      {
        "matcher": "write_to_file|replace_file_content|multi_replace_file_content",
        "hooks": [
          {
            "type": "command",
            "command": "C:\\path\\to\\synesis\\workspace\\build\\install\\synesis-workspace\\bin\\synesis-workspace.bat --profile C:\\path\\to\\synesis\\profile hook antigravity",
            "timeout": 10
          }
        ]
      }
    ]
  }
}
```

---

## 3. Decision Mappings

- **`BLOCK` Constraint**: Returns `{"decision": "deny", "reason": "..."}`. The edit is denied before disk mutation occurs, and the agent receives the constraint title, scope, and rationale to re-plan.
- **`WARN` Constraint**: Returns `{"decision": "force_ask", "reason": "..."}`. Operation triggers explicit user review before execution.
- **`ALLOWED` / Unconstrained**: Returns `{"decision": "ask", "reason": "..."}`. Preserves Antigravity's standard permission flow.
- **`UNSUPPORTED` Tool**: Non-file tools (e.g. `run_command`) return `{"decision": "ask"}` and emit a `SYNESIS_HOOK_RESULT=UNSUPPORTED` diagnostic to stderr.

---

## 4. Manual Testing via Stdin

You can manually verify hook execution by piping an official Antigravity `PreToolUse` payload into the launcher:

```powershell
Get-Content payload.json | .\workspace\build\install\synesis-workspace\bin\synesis-workspace.bat --profile C:\path\to\profile hook antigravity
```
