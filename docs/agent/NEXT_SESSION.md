# Next Session

- Active task: SYN-010A (public GitHub developer-preview preparation)
- Repository branch: master
- Last checkpoint: CP-0123
- Last passing command: `gradlew.bat check --dependency-verification=strict`
- Last failing command: GitHub Windows bundle command parsed the unquoted version property as task `.1.0-dev.10`; fixed by quoting the full `-PsynesisVersion` argument.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: Rerun the publication audit and obtain explicit push authorization only after reviewing author metadata and the target repository.
- Unresolved blockers: Explicit push authorization, author-metadata review, and target-repository confirmation remain; production signing is a separate gate.
- Facts that must not be forgotten: ActionGuardrail and ProjectPathResolver are shared boundaries; Codex is project-local `.codex/hooks.json`, requires trusted project configuration, and must remain `REVIEW_REQUIRED`/`DEGRADED` until real validation; Codex input is PreToolUse with `tool_name=apply_patch`, `tool_input.command`, and `cwd`; the adapter never applies patches; allowed and unsupported Codex events produce empty stdout and exit 0; blocked/invalid events produce bounded denial JSON and exit 0. The abrupt process-loss integration test uses a short test-only liveness policy; production defaults remain unchanged. The workflow uses Node 24-compatible actions and Microsoft OpenJDK 25 for all native bundle runners, and quotes the full Windows Gradle version property.
