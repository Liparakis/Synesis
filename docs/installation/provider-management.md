# Provider management

From an initialized project, the unified launcher supports:

```powershell
synesis provider list
synesis provider install antigravity
synesis provider status antigravity
synesis provider uninstall antigravity
synesis provider install claude
synesis provider status claude
synesis provider uninstall claude
synesis provider install codex
synesis provider status codex
synesis provider uninstall codex
```

Use `--project <path>` for an explicit project directory. Provider metadata is
local-only under `.synesis/local/providers/`. Antigravity writes its managed
hook to `<project>/.agents/hooks.json`; Claude Code writes to
`<project>/.claude/settings.json`. Unrelated JSON fields and hooks are kept.
Codex writes its managed hook to `<project>/.codex/hooks.json` and includes a
Windows-specific launcher command.

Installation performs isolated synthetic block/allow checks. It does not run a
real provider agent. Antigravity remains `BETA` until a real agent denial and
successful re-plan are recorded; Claude Code and Codex remain `EXPERIMENTAL`.
Codex additionally requires project hook trust review in `/hooks`; installation
does not modify Codex trust state and status remains `DEGRADED` until real
validation.

Installation also creates the first project/provider session binding. The first
Codex hook (`session_id`) or Antigravity hook (`conversationId`) resumes that
binding or creates a distinct one automatically. `provider status` reports
`SESSION_BINDING`, `SESSION_ID`, `SUPERVISOR_ID`, `WORKER_ID`,
`SESSION_PROJECT_ID`, `SESSION_NODE_ID`, `SESSION_TRUST`, and
`SESSION_EVIDENCE`. `SESSION_EVIDENCE=FALLBACK` means the provider did not
expose a stable instance key; it is deliberately weaker than a chat identity.
No manual bind, actor registration, profile copy, or token exchange is required.

For migration, an initialized project keeps its existing project ID, node ID,
profile, and unrelated hook configuration. The next install or first hook
creates only the missing local binding. A malformed or identity-mismatched
record fails closed and is reported as broken rather than silently replaced.

Provider commands resolve Synesis from `synesis`/`synesis.cmd` on PATH first.
If PATH has not been refreshed in the current process, they use the stable
fallback at `%LOCALAPPDATA%\Synesis\bin\synesis.cmd` on Windows or the
platform stable-root `bin` launcher on Unix. Provider commands never contain a
`versions/<version>` path.

Malformed configuration is never repaired or overwritten. Review it manually,
then rerun installation. Uninstall removes only the stable Synesis-managed
entry and its local metadata.

`claude-code` is retained as a compatibility alias for the canonical `claude`
provider ID. It is not a second provider. Claude Code's hook command remains
`synesis hook claude-code` because that is the provider's hook event adapter
name.
