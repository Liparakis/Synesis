# Next Session

- Active task: SYN-003 CP-W1
- Repository branch: master
- Last checkpoint: CP-0077
- Last passing command: `gradlew.bat clean check --dependency-verification=strict`
- Last failing command: none
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: review CP-W1 evidence and approve/promote
  CP-W2 separately; do not implement host/join or sync in this slice.
- Unresolved blockers: none for CP-W1; physical launcher and project-record
  transfer claims remain explicitly deferred.
- Facts that must not be forgotten: Link, `:cli`, and `:project-record`
  production code are frozen; CP-R5 is deferred; two-process evidence is not
  two-machine evidence; no public release or tag is authorized.
