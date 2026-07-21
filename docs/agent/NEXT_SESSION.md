# Next Session

- Active task: None (SYN-008 is DONE at CP-0095)
- Repository branch: master
- Last checkpoint: CP-0095
- Last passing command: `.\gradlew.bat clean check --dependency-verification=strict`
- Last failing command: none
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: Prepare external builder trial or product packaging proposal for SYN-009.
- Unresolved blockers: none
- Facts that must not be forgotten: ActionGuardrail is the shared evaluator used by both ClaudeCodeHookAdapter and AntigravityHookAdapter; AntigravityHookAdapter uses toolCall.name and toolCall.args.TargetFile, NOT tool_name or tool_input; decision mapping is deny/force_ask/ask; exit code is always 0 for both adapters; Claude Code hook contract uses hookSpecificOutput.permissionDecision; Antigravity hook contract uses decision.
