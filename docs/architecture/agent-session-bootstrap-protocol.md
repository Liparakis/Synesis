# Agent session bootstrap protocol

The provider-neutral operations are `ensureSession`, `announceTask`,
`updateIntent`, `evaluateAction`, `confirmCapabilityContract`,
`respondToOwnerRequest`, `reportImplementationReady`,
`validateIntegratedCapability`, and `completeTask`.

Every request carries project ID, session ID, node identity, provider ID,
idempotency key, observed event sequence, and base commit. Responses use stable
codes: `BOOTSTRAP_REQUIRED`, `RUNTIME_UNAVAILABLE`, `WORKSPACE_UNVERIFIED`,
`STALE_CONTEXT`, `DENY_POLICY`, `REQUEST_OWNER`,
`HUMAN_APPROVAL_REQUIRED`, `CONTRACT_REQUIRED`, `PENDING`, `READY`, and
`INTEGRATION_BLOCKED`.

## Bootstrap sequence

```mermaid
sequenceDiagram
  participant A as Harness
  participant P as Provider adapter
  participant S as Session manager
  participant R as Hidden runtime
  participant G as Git/worktree broker
  A->>P: natural-language task
  P->>S: ensureSession(project, provider)
  S->>R: start or resume authenticated runtime
  S->>G: allocate and verify isolated worktree
  G-->>S: worktree, branch, base commit
  S-->>P: workspace proof + session token
  P->>S: announceTask(intent)
  S-->>P: provisional scopes and constraints
  P-->>A: begin only after mutation gate passes
```

The provider must prove the harness is operating in the returned worktree. A
message telling an agent to `cd` is not proof. If the provider cannot switch or
cannot intercept its mutation tools, Synesis returns `WORKSPACE_UNVERIFIED` and
the agent remains read-only.

## Task intent and ownership

Intent includes purpose, semantic capabilities, expected scopes, base commit,
observed project sequence, acceptance commands, compatibility, concurrency, and
expiry. Ownership is capability/module/interface/task based. Concrete scopes
are enforcement evidence, not the ownership identity. Provisional claims become
active only after coordinator authorization and conflict evaluation.

## REQUEST_OWNER

The pre-mutation adapter stops the operation before any write and returns the
owner capability, node, supervisor, and intent version. The session manager
derives target, task, base evidence, scope hashes, and proposed behavior. The
agent confirms only fields that cannot be derived; the manager validates and
submits the existing bounded `PredictionContract`.

