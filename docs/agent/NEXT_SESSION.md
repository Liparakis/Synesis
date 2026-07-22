# Next Session

- Active task: SYN-010A (public GitHub developer-preview preparation)
- Repository branch: master
- Last checkpoint: CP-0111
- Last passing command: `gradlew.bat check --dependency-verification=strict`
- Last failing command: none
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: After an intentional license decision, replace the unfinished `LICENSE`, rerun the publication audit, and re-review the target repository before any push.
- Unresolved blockers: An intentional license decision is required before public visibility; ownership/publication authorization and production signing remain separate gates.
- Facts that must not be forgotten: ActionGuardrail and ProjectPathResolver are shared boundaries; Codex is project-local `.codex/hooks.json`, requires trusted project configuration, and must remain `REVIEW_REQUIRED`/`DEGRADED` until real validation; Codex input is PreToolUse with `tool_name=apply_patch`, `tool_input.command`, and `cwd`; the adapter never applies patches; allowed and unsupported Codex events produce empty stdout and exit 0; blocked/invalid events produce bounded denial JSON and exit 0.
