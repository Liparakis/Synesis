# Next Session

- Active task: SYN-009C (planning only; SYN-009B is DONE and no implementation work is active)
- Repository branch: master
- Last checkpoint: CP-0102
- Last passing command: `.\gradlew.bat clean check --dependency-verification=strict`
- Last failing command: none
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: Review SYN-009C's portable ZIP and clean-machine smoke-test acceptance criteria before implementation.
- Unresolved blockers: none; SYN-009B is closed with strict clean verification, five unified-launcher process scenarios, and disposable provider lifecycle checks passed.
- Facts that must not be forgotten: ActionGuardrail is the shared evaluator used by both ClaudeCodeHookAdapter and AntigravityHookAdapter; AntigravityHookAdapter uses toolCall.name and toolCall.args.TargetFile, NOT tool_name or tool_input; decision mapping is deny/force_ask/ask; exit code is always 0 for both adapters; Claude Code hook contract uses hookSpecificOutput.permissionDecision; Antigravity hook contract uses decision.
