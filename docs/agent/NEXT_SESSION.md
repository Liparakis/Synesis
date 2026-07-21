# Next Session

- Active task: SYN-003 CP-W1
- Repository branch: master
- Last checkpoint: CP-0076
- Last passing command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Last failing command: none
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next code action: create the `:workspace` application and bounded launcher;
  stop before CP-W2 host/join or sync.
- Unresolved blockers: none for CP-W1; physical launcher and project-record
  transfer claims remain explicitly deferred.
- Facts that must not be forgotten: Link, `:cli`, and `:project-record`
  production code are frozen; CP-R5 is deferred; two-process evidence is not
  two-machine evidence; no public release or tag is authorized.
