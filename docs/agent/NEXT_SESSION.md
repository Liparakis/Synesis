# Next Session

- Active task: SYN-001 (CP-R2)
- Repository branch: master
- Last checkpoint: CP-0067
- Last passing command: `gradlew.bat clean check --dependency-verification=strict`
- Last failing command: none; the first full run had a transient test class-loading failure and the immediate rerun passed
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: retain CP-R2 evidence and await explicit
  CP-R4 activation; do not begin networking or sync.
- Unresolved blockers: physical generated-launcher onboarding remains pending
  as an explicitly unclaimed frozen SL-013 scenario;
  generated early-kill did not reach a bounded terminal status, while Link-level
  abrupt-loss and wrong-identity tests pass; no physical claim is permitted.
- Facts that must not be forgotten: two-process evidence is not two-machine evidence; only authenticated control-ready sessions send demo work; deferred capabilities remain in `docs/agent/DEFERRED.md`; no public release/tag is authorized.
