# Next Session

- Active task: SL-013
- Repository branch: master
- Last checkpoint: CP-0056
- Last passing command: `gradlew.bat clean check --dependency-verification=strict`
- Last failing command: none; the first full run had a transient test class-loading failure and the immediate rerun passed
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: Run the documented physical generated-launcher onboarding scenario on two computers and record sanitized results in `docs/evidence/PHYSICAL-CLI-ONBOARDING.md`; do not claim it from process tests.
- Unresolved blockers: physical generated-launcher onboarding remains pending;
  generated early-kill did not reach a bounded terminal status, while Link-level
  abrupt-loss and wrong-identity tests pass; no physical claim is permitted.
- Facts that must not be forgotten: two-process evidence is not two-machine evidence; only authenticated control-ready sessions send demo work; deferred capabilities remain in `docs/agent/DEFERRED.md`; no public release/tag is authorized.
