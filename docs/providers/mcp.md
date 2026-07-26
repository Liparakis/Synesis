# MCP behavior

The stdio server exposes exactly 11 tools. The stable tool names are:

`synesis.ensure_session`, `synesis.read_file`, `synesis.apply_patch`,
`synesis.run_command`, `synesis.get_next_action`,
`synesis.describe_required_capability`, `synesis.respond_to_owner_request`,
`synesis.publish_implementation`, `synesis.validate_available_implementation`,
`synesis.complete_task`, and `synesis.cancel_task`.

One persistent MCP process owns one provider binding and one worker/session
context. Reads return logical UTF-8 text plus a revision derived from exact raw
bytes. `synesis.apply_patch` requires the matching revision for modifications;
stale reads are rejected. Worker worktrees and session state are isolated.
