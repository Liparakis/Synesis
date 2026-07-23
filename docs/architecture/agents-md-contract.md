# AGENTS.md contract for initialized projects

`synesis init` adds one marked, replaceable section and preserves all user text:

```text
<!-- SYNESIS-BEGIN -->
This project is Synesis-enabled. Before any mutation, establish or resume the
Synesis session for this provider and task. Work only in the assigned Synesis
workspace. Synesis may stop a mutation when another task owns the capability;
describe the required behavior when prompted and do not edit the owner scope.
Do not run coordinator, supervisor, event, prediction, speculation, or
integration diagnostic commands as part of normal work. Do not bypass provider
hooks, write another session's worktree, or treat a prompt-supplied path as
workspace proof. If Synesis reports identity, freshness, ownership, or workspace
verification failure, pause mutation and continue only with safe read-only
inspection until the provider reports READY.
<!-- SYNESIS-END -->
```

The section is guidance, not an authorization mechanism. Authorization remains
coordinator-side and provider enforcement remains a release gate.

