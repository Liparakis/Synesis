# Codex worktree provisioning

The final installed bundle was reinitialized against the external project.

```text
PROJECT_ID=851293a6-bc7c-4492-8ae6-76aec2c17354
NODE_ID=sl1-1cc434f1ca9a52f34a445fcf44bb0d3f6b565fb282f41bfad027650342c3257a
GIT_HEAD=37eaa5aad4bf2f192c76a8a3e001120eeeb603e4
GIT_HEAD_VALID=true
CODEX_SESSION_ID=session-7a7b35ae-e6e2-4f45-a302-6d338d311a4f
CODEX_ASSIGNED_WORKTREE=C:\Users\Liparakis\AppData\Local\Synesis\workspaces\851293a6-bc7c-4492-8ae6-76aec2c17354\worktrees\session-7a7b35ae-e6e2-4f45-a302-6d338d311a4f
CODEX_BRANCH=synesis/codex/session-7a7b35ae-e6e2-4f45-a302-6d338d311a4f
CODEX_BASE_COMMIT=37eaa5aad4bf2f192c76a8a3e001120eeeb603e4
CODEX_WORKTREE_REGISTERED=true
CODEX_WORKSPACE_TRUST=VERIFIED
CODEX_PROVIDER_STATUS=DEGRADED
ANTIGRAVITY_PROVIDER_STATUS=DEGRADED
```

The assigned worktree is outside the control checkout and has a distinct
session branch. Re-running provider install resumed the same fallback Codex
session and path. A Synesis hook invocation from the assigned worktree emitted
no deny response, and no proof file was created; this proves Synesis routing
and policy evaluation, not real Codex interception. The provider therefore
remains degraded until an actual Codex harness action proves interception.
