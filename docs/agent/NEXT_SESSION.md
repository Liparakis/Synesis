# Next Session

- Active task: SYN-002 (review/closure)
- Repository branch: master
- Last checkpoint: CP-0075
- Last passing command: `gradlew.bat clean check --dependency-verification=strict`
- Last failing command: none; the first full run had a transient test class-loading failure and the immediate rerun passed
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: review SYN-002 evidence and close the task;
  do not begin a new CAF slice.
- Unresolved blockers: physical generated-launcher onboarding remains pending
  as an explicitly unclaimed frozen SL-013 scenario;
  generated early-kill did not reach a bounded terminal status, while Link-level
  abrupt-loss and wrong-identity tests pass; no physical claim is permitted.
- Facts that must not be forgotten: SYN-001 is DONE at CP-R4; CP-R5 is
  deferred; two-process evidence is not two-machine evidence; Link and
  `:project-record` are frozen except for proven blockers; deferred
  capabilities remain in `docs/agent/DEFERRED.md`; no public release/tag is
  authorized.
