# Next Session

- Active task: SL-014
- Repository branch: master
- Last checkpoint: CP-0059
- Last passing command: `gradlew.bat clean check --dependency-verification=strict`
- Last failing command: none; the first full run had a transient test class-loading failure and the immediate rerun passed
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: Implement the bounded transport-neutral Link application-stream binding and focused SL-014 tests; do not add record storage, sync, project terminology, or `:cli` changes.
- Unresolved blockers: physical generated-launcher onboarding remains pending;
  generated early-kill did not reach a bounded terminal status, while Link-level
  abrupt-loss and wrong-identity tests pass; no physical claim is permitted.
- Facts that must not be forgotten: two-process evidence is not two-machine evidence; only authenticated control-ready sessions send demo work; deferred capabilities remain in `docs/agent/DEFERRED.md`; no public release/tag is authorized.
