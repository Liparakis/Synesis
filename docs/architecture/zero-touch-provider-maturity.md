# Zero-touch provider maturity matrix

| Provider | Project detection | Session bootstrap | Worktree proof | Mutation interception | Safe-boundary delivery | Status |
|---|---|---|---|---|---|---|
| Codex | synthetic/project hook | missing | missing | synthetic only; real trust incomplete | missing | EXPERIMENTAL / READ-ONLY GATE |
| Antigravity | synthetic/project hook | missing | missing | synthetic; real hook bypass observed | missing | BETA / UNVALIDATED |
| Claude Code | existing hook adapter | not planned in SYN-013 | not planned | existing adapter only | not planned | DEFERRED |

Promotion requires a trusted real run proving the complete column set, not only
the parser or a generated wrapper. A provider that fails a column remains
installed for diagnostics but cannot enter zero-touch mutation mode.

