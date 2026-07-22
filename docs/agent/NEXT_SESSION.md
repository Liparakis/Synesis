# Next Session

- Active task: none; SYN-009C is DONE (cross-platform distribution and bootstrap installation)
- Repository branch: master
- Last checkpoint: CP-0110
- Last passing command: isolated real Java archive bootstrap trial on D:
- Last failing command: none
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: Run the startup resume command before any separately authorized follow-up; the next scoped work is a controlled hosted HTTPS installation trial.
- Unresolved blockers: Public production signing-key replacement, OS vendor signing/notarization, and real Codex `/hooks` validation remain intentionally deferred and are not completion claims.
- Facts that must not be forgotten: ActionGuardrail and ProjectPathResolver are shared boundaries; Codex is project-local `.codex/hooks.json`, requires trusted project configuration, and must remain `REVIEW_REQUIRED`/`DEGRADED` until real validation; Codex input is PreToolUse with `tool_name=apply_patch`, `tool_input.command`, and `cwd`; the adapter never applies patches; allowed and unsupported Codex events produce empty stdout and exit 0; blocked/invalid events produce bounded denial JSON and exit 0.
