# Next Session

- Active task: None (SYN-007.1 is DONE at CP-0094)
- Repository branch: master
- Last checkpoint: CP-0094
- Last passing command: `.\gradlew.bat clean check --dependency-verification=strict`
- Last failing command: none
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: Prepare external builder trial or real-agent repeated trial proposal.
- Unresolved blockers: none
- Facts that must not be forgotten: ClaudeCodeHookAdapter exits code 0 for JSON denials; response format uses hookSpecificOutput with permissionDecision: "deny"; absolute paths are relativized via resolveRelativePath against project CWD; ProjectConstraint.filterEffectiveActive filters out superseded constraints; claude CLI requires interactive authentication (claude auth login).
