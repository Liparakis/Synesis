# Next Session

- Active task: SYN-019 ACTIVE under Close workspace application package architecture rule
- Repository branch: master
- Last checkpoint: CP-0232; current HEAD is `516506f`.
- Last passing command: `:workspace:check`, `:cli:check`, `:mcp:check`, root `check`, Go test/vet, focused MCP tests, CLI help/version, provider list, init, and hygiene check.
- Immediate next command: `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`
- Exact next documentation action: Record review/commit disposition for the authorized bootstrap portability correction; leave the unrelated README edit untouched and keep `SYN-014E` paused.
- Unresolved limitations: an unrelated README edit remains outside the task and
  currently triggers a false positive in the existing hygiene count regex.
- Facts that must not be forgotten: Exactly 11 MCP tools must remain registered in `tools/list`. `DemoCli` moves only to `org.synesis.link.cli` and its Gradle main-class string must be updated. No type may move across Gradle modules. The authorized Go change is limited to versioned activation ordering and payload cleanup.
