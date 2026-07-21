# Next Session

- Active task: SYN-009B (implementation complete; checkpointed, do not start SYN-009C)
- Repository branch: master
- Last checkpoint: CP-0101
- Last passing command: `.\gradlew.bat clean check --dependency-verification=strict`
- Last failing command: none
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: Review the SYN-009B completion checkpoint and commit state without starting SYN-009C.
- Unresolved blockers: none; strict clean verification and disposable provider lifecycle checks passed.
- Facts that must not be forgotten: ActionGuardrail is the shared evaluator used by both ClaudeCodeHookAdapter and AntigravityHookAdapter; AntigravityHookAdapter uses toolCall.name and toolCall.args.TargetFile, NOT tool_name or tool_input; decision mapping is deny/force_ask/ask; exit code is always 0 for both adapters; Claude Code hook contract uses hookSpecificOutput.permissionDecision; Antigravity hook contract uses decision.
