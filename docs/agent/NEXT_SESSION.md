# Next Session

- Active task: SYN-009A (promoted; implementation pending)
- Repository branch: master
- Last checkpoint: CP-0096
- Last passing command: `.\gradlew.bat clean check --dependency-verification=strict`
- Last failing command: none
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: Verify the SYN-009A commit state without starting SYN-009B or SYN-009C.
- Unresolved blockers: none after task promotion; baseline verification must be rerun.
- Facts that must not be forgotten: ActionGuardrail is the shared evaluator used by both ClaudeCodeHookAdapter and AntigravityHookAdapter; AntigravityHookAdapter uses toolCall.name and toolCall.args.TargetFile, NOT tool_name or tool_input; decision mapping is deny/force_ask/ask; exit code is always 0 for both adapters; Claude Code hook contract uses hookSpecificOutput.permissionDecision; Antigravity hook contract uses decision.
