# Next Session

- Active task: SYN-013B (automatic provider session binding & mutation enforcement)
- Repository branch: master
- Last checkpoint: CP-0158
- Last passing command: `.\gradlew.bat check --no-daemon`
- Last failing command: `:link:formatCheck` (fixed by trimming whitespace)
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: checkpoint created at CP-0158; do not push.
- Unresolved limitations: Codex native `apply_patch` does not invoke `.codex/hooks.json` PreToolUse hooks (`REAL_CODEX_PRE_MUTATION_HOOK_SUPPORTED=false`); workspace mutations are enforced via `WorkspaceMutationBroker` (Strategy B). Unintercepted native Codex mutations report `MUTATION_WITHOUT_ALLOW_POSSIBLE=true` and `SESSION_INTERCEPTION=UNPROVEN` so fail-closed is not falsely claimed.
- Facts that must not be forgotten: preserve signed manifest and SHA-256 checks, archive defenses, legacy root until staged validation succeeds, external projects, exact Antigravity matcher, SYN-011 status, and all 5 workspace mutation invariants.

