# Claude Code hook integration

Install the project-local integration with:

```powershell
synesis provider install claude-code
```

The managed `PreToolUse` entry is written to
`<project>/.claude/settings.json` and invokes the installed `synesis` launcher
with the project-local profile. It matches the existing structured `Edit|Write`
contract and delegates evaluation to the shared `ActionGuardrail` through
`ClaudeCodeHookAdapter`.

Installation and status run synthetic contract checks only. The support level
is `EXPERIMENTAL` until a real authenticated Claude Code agent run is recorded.
Uninstall removes only the Synesis-managed entry.
