# MCP behavior

The stdio server exposes exactly 10 tools. The stable raw tool names are:

`ensure_session`, `read_file`, `apply_patch`, `run_command`, `get_next_action`,
`request_coordination`, `respond_coordination`,
`publish_capability_implementation`, `finish_lane`, and `cancel_lane`.

The server/configuration namespace is `synesis`; the wire contract advertises
raw names only. Decorated `synesis.*` calls are rejected.
migration.

Session establishment verifies the provider-managed Synesis Manual manifest,
version, and content hash. Invalid attestation blocks mutation and other
authority-increasing operations, while inspection, inbox reads, own-lane
cancellation/closure, claim relinquishment, diagnostics, and operator recovery
remain available.

One persistent MCP process owns one provider binding and one worker/session
context. Reads return logical UTF-8 text plus a revision derived from exact raw
bytes. `apply_patch` requires the matching revision for modifications;
stale reads are rejected. Worker worktrees and session state are isolated.

Before mutating visible task files, the agent must call `ensure_session` with
`task.goal`, `task.acceptance`, and `task.claims`. Each claim is an exact
repository-relative `path_exact` or `path_subtree` selector and is the existing
intent/ownership announcement. Claims must be disjoint; Synesis rejects
overlapping ownership. `likelyScopes` is descriptive planning information only
and does not announce intent or acquire claims. There is no separate announce
tool in the ten-tool MCP surface.
