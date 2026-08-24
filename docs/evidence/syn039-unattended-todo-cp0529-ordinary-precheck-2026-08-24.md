# SYN-039 ordinary acceptance precheck — CP-0529

This was a separate ordinary two-agent run captured before the corrected
CP-0530 exact-rule diagnostic. It is retained for completeness and is not
counted as the conditional second acceptance, because it did not follow a
completed diagnostic.

- Project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0529-001`
- Harness:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0529-001`
- Project ID: `69e9428a-51cd-46e5-95a3-8978ef6d8c2f`
- Both sessions used the current bundled MCP, the same project, distinct
  ready/isolated bindings, and one shared WorkGroup.

Agent B added regression coverage and reached `1 failed, 2 passed` in its
isolated worktree while Agent A independently implemented its claimed file.
After A's review request was accepted, A's projection remained
`SNAPSHOT_PENDING` / `wait` until B produced a snapshot. B's Codex turn ended
after accepting the request and did not continue the projected coordination
poll. No snapshot, validation, integration, or WorkGroup closure was reached.

Final read-only status showed the WorkGroup `beb5190d-a9d7-3d2c-8f39-decf40ef7e57`
`ACTIVE`, coordination sequence `0`, no durable request/grant/snapshot
completion, and Doctor `DEGRADED` with six warnings. The control checkout
remained clean. This is ordinary agent engagement evidence, not a proven
production protocol defect. Raw logs remain under the harness path above.
