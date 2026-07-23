# Speculation state machines

## Prediction state

```mermaid
stateDiagram-v2
  [*] --> PROPOSED
  PROPOSED --> ROUTED
  ROUTED --> RECEIVED
  RECEIVED --> ACCEPTED_EXACT
  RECEIVED --> ACCEPTED_EQUIVALENT
  RECEIVED --> REVISED
  ACCEPTED_EXACT --> IMPLEMENTING
  ACCEPTED_EQUIVALENT --> IMPLEMENTING
  REVISED --> IMPLEMENTING
  IMPLEMENTING --> PATCH_READY
  PATCH_READY --> AVAILABLE
  AVAILABLE --> VALIDATING
  VALIDATING --> RETIRED
  PROPOSED --> REJECTED
  ROUTED --> REJECTED
  RECEIVED --> REJECTED
  ACCEPTED_EXACT --> INVALIDATED
  IMPLEMENTING --> INVALIDATED
  PATCH_READY --> INVALIDATED
  AVAILABLE --> INVALIDATED
  VALIDATING --> INVALIDATED
  PROPOSED --> EXPIRED
  ROUTED --> EXPIRED
  RECEIVED --> EXPIRED
```

Terminal states are `RETIRED`, `INVALIDATED`, `REJECTED`, and `EXPIRED`.
Creation is the only event that can establish a prediction. Every later event
must be valid for the current state; the projection rejects impossible jumps.

## Provider decision precedence

```text
verified policy BLOCKED -> DENY_POLICY
missing/stale context    -> STALE_CONTEXT
owned by this node       -> ALLOW
owned by another node    -> REQUEST_OWNER
accepted prediction      -> SPECULATIVE_ALLOWED
patch/lease failure      -> structured retry or human escalation
```

The precedence prevents speculative coordination from bypassing existing
policy constraints.
