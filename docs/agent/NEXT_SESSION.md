# Next Session

- Active task: SYN-003 CP-W2 complete; CP-W3 requires separate approval
- Repository branch: master
- Last checkpoint: CP-0078
- Last passing command: `gradlew.bat clean check --dependency-verification=strict`
- Last failing command: none
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: review CP-W2 evidence and approve/promote
  CP-W3 separately; do not implement broader CAF behavior in this slice.
- Unresolved blockers: none for CP-W2; physical operation, retries, reconnect,
  discovery, membership, background sync, and project-record transfer claims
  remain explicitly deferred.
- Facts that must not be forgotten: Link, `:cli`, and `:project-record`
  production code are frozen; CP-R5 is deferred; CP-W2 is one-shot only;
  invitation values are redacted from evidence; two-process evidence is not
  two-machine evidence; no public release or tag is authorized.
