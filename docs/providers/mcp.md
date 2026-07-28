# MCP behavior

The stdio server exposes exactly 11 tools. The stable tool names are:

`ensure_session`, `read_file`, `apply_patch`, `run_command`, `get_next_action`,
`describe_required_capability`, `respond_to_owner_request`,
`publish_implementation`, `validate_available_implementation`, `complete_task`,
and `cancel_task`.

The server/configuration namespace is `synesis`. Legacy `synesis.*` names are
accepted for one compatibility period but are not advertised by `tools/list`.

One persistent MCP process owns one provider binding and one worker/session
context. Reads return logical UTF-8 text plus a revision derived from exact raw
bytes. `apply_patch` requires the matching revision for modifications;
stale reads are rejected. Worker worktrees and session state are isolated.
