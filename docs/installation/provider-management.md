# Provider management

From an initialized project, the unified launcher supports:

```powershell
synesis provider list
synesis provider install antigravity
synesis provider status antigravity
synesis provider uninstall antigravity
synesis provider install claude-code
synesis provider status claude-code
synesis provider uninstall claude-code
```

Use `--project <path>` for an explicit project directory. Provider metadata is
local-only under `.synesis/local/providers/`. Antigravity writes its managed
hook to `<project>/.agents/hooks.json`; Claude Code writes to
`<project>/.claude/settings.json`. Unrelated JSON fields and hooks are kept.

Installation performs isolated synthetic block/allow checks. It does not run a
real provider agent. Antigravity remains `BETA` until a real agent denial and
successful re-plan are recorded; Claude Code remains `EXPERIMENTAL`.

Malformed configuration is never repaired or overwritten. Review it manually,
then rerun installation. Uninstall removes only the stable Synesis-managed
entry and its local metadata.
