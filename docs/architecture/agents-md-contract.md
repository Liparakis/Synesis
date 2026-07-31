# AGENTS.md contract for initialized projects

`synesis init` adds one marked, replaceable section and preserves all user text:

```text
<!-- SYNESIS-BEGIN -->
- Use Synesis tools for project reads, file changes, and commands.
- One persistent MCP connection owns one provider binding and one isolated
  worker context.
- Reads carry revisions; provide the matching revision when applying a patch.
- Do not modify the control checkout or another worker's files directly.
- If Synesis reports identity, ownership, freshness, or workspace failure, stop
  mutation and inspect only read-only state.
- The current MCP surface contains exactly 10 tools.
<!-- SYNESIS-END -->
```

The exact generated text is owned by `ProjectApplicationService`; this document
describes its contract rather than being a second template. The section is
guidance, not an authorization mechanism. Authorization remains coordinator-side
and provider enforcement remains a release gate.
