# Codex PreToolUse hook integration

Install the project-local integration from an initialized project:

```powershell
synesis provider install codex
synesis provider status codex
synesis doctor
synesis provider uninstall codex
```

Synesis writes one managed `PreToolUse` matcher for `^apply_patch$` to
`<project>/.codex/hooks.json`. On Windows the entry includes a
`commandWindows` `cmd.exe` form for the generated `.bat` launcher. Unknown
configuration, unrelated event groups, and unrelated handlers are preserved;
the `.codex` directory is never removed by uninstall.

The command is `synesis hook codex`. It reads one Codex hook JSON object from
stdin, discovers the Synesis project from payload `cwd`, and inspects only
`tool_name=apply_patch` and `tool_input.command`. The bounded parser recognizes
the `*** Begin Patch`/`*** End Patch` envelope and Add, Update, Delete, and Move
file directives. It never applies the patch.

Every source and destination path is normalized through the shared
`ProjectPathResolver` and checked through the shared `ActionGuardrail`:

- Any blocked or invalid path denies the entire patch with
  `hookSpecificOutput.permissionDecision=deny` and exit 0.
- A warning emits `hookSpecificOutput.additionalContext`; it does not ask.
- An allowed patch emits no stdout decision and exits 0.
- A non-`apply_patch` tool emits no stdout and a bounded stderr diagnostic.

Project-local Codex hooks run only after the project hook definition is
reviewed and trusted in Codex’s `/hooks` UI. Synesis never edits Codex trust
state. Consequently install/status/doctor report `TRUST_STATUS=REVIEW_REQUIRED`
and keep Codex `DEGRADED` until a real authenticated run proves denial
recognition, reason display, replanning, and unchanged protected-file hash.

Official contract references:

- https://developers.openai.com/codex/config-advanced/
- https://developers.openai.com/codex/config-reference/
- https://learn.chatgpt.com/docs/hooks
