# Next Session

- Active task: SYN-009C (cross-platform distribution and bootstrap installation)
- Repository branch: master
- Last checkpoint: CP-0107
- Last passing command: `.\gradlew.bat clean check --dependency-verification=strict`
- Last failing command: none
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: Create the phase-2 checkpoint, commit the verified SYN-009C.2 slice, then promote SYN-009C.3.
- Unresolved blockers: Public production signing-key replacement, OS vendor signing/notarization, and real Codex `/hooks` validation remain intentionally outside this local release gate.
- Facts that must not be forgotten: ActionGuardrail and ProjectPathResolver are shared boundaries; Codex is project-local `.codex/hooks.json`, requires trusted project configuration, and must remain `REVIEW_REQUIRED`/`DEGRADED` until real validation; Codex input is PreToolUse with `tool_name=apply_patch`, `tool_input.command`, and `cwd`; the adapter never applies patches; allowed and unsupported Codex events produce empty stdout and exit 0; blocked/invalid events produce bounded denial JSON and exit 0.
