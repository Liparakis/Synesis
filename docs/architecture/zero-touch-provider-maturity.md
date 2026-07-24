# Zero-touch provider maturity matrix

| Provider | Project detection | Session bootstrap | Worktree proof | Mutation interception | Safe-boundary delivery | Status |
|---|---|---|---|---|---|---|
| Codex | synthetic/project hook | automatic local binding; real trust incomplete | missing | synthetic only; real trust incomplete | missing | EXPERIMENTAL / READY_FOR_REAL_VALIDATION |
| Antigravity | synthetic/project hook | automatic local binding; real trust incomplete | missing | synthetic; real hook bypass observed | missing | BETA / READY_FOR_REAL_VALIDATION |
| Claude Code | existing hook adapter | not planned in SYN-013 | not planned | existing adapter only | not planned | DEFERRED |

Promotion requires a trusted real run proving the complete column set, not only
the parser or a generated wrapper. A provider that fails a column remains
installed for diagnostics but cannot enter zero-touch mutation mode.

`READY_FOR_REAL_VALIDATION` means the project/node/provider session binding,
actor separation, and exact installed hook path are present. It is not evidence
that a real provider denied or intercepted a mutation.
