# Next Session

- Active task: SYN-009B.1 (Codex PreToolUse adapter and provider lifecycle)
- Repository branch: master
- Last checkpoint: CP-0105
- Last passing command: `.\gradlew.bat clean check --dependency-verification=strict`
- Last failing command: none
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: Review/trust the exact project-local Codex hook in the interactive `/hooks` UI, then rerun the protected-file experiment in `docs/validation/codex-real-agent-experiment.md`.
- Unresolved blockers: real Codex payload capture and authenticated `/hooks` validation are pending; do not promote Codex beyond EXPERIMENTAL without their evidence.
- Facts that must not be forgotten: ActionGuardrail and ProjectPathResolver are shared boundaries; Codex is project-local `.codex/hooks.json`, requires trusted project configuration, and must remain `REVIEW_REQUIRED`/`DEGRADED` until real validation; Codex input is PreToolUse with `tool_name=apply_patch`, `tool_input.command`, and `cwd`; the adapter never applies patches; allowed and unsupported Codex events produce empty stdout and exit 0; blocked/invalid events produce bounded denial JSON and exit 0.
