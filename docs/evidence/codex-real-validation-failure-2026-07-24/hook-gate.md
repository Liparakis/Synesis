# Installed hook gate

After reinstalling `0.1.0-dev.local`, the external project provider status was:

```text
PROVIDER_STATUS=DEGRADED
SESSION_TRUST=WORKSPACE_UNVERIFIED
SESSION_WORKSPACE=UNASSIGNED
SESSION_INTERCEPTION=UNPROVEN
REAL_AGENT_VALIDATION=NOT_COMPLETED
```

An installed Synesis Codex hook invocation from the original checkout returned:

```json
{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"WORKSPACE_TRANSITION_REQUIRED"}}
```

The requested proof files remained absent and the external checkout status was
unchanged apart from its pre-existing untracked `.agents/`, `.codex/`,
`.synesis/`, and `AGENTS.md` files.
