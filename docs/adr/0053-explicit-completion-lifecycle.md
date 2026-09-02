# ADR-0053: Explicit call-local completion requests

Status: Accepted for SYN-049.

## Context

The pre-release completion path currently carries a caller-selected
`WorkIntent.CompletionMode`. A publishable mutation can therefore project the
completion path before a worker has finished its implementation, tests, or
coordination obligations. `snapshot` and `no_change` are terminal outcomes of
completion, not policies that a worker should select at admission time.

## Decision

Remove `WorkIntent.CompletionMode`, `snapshot_required`, `no_change_allowed`,
`completionMode`, and their public/wire/provider aliases. Replace the durable
intent format without those fields; removed formats fail loudly and require
fresh initialization because Synesis has no public consumers.

`get_next_action` accepts the optional boolean `completionRequested`. Missing
or false means ordinary implementation continues. True is evaluated only for
that invocation and is not sticky or durably treated as a caller-controlled
policy. A `finish_lane` action is projected only after the existing authority,
claim epoch, WorkGroup version, event revision, session/participant, worktree,
review, capability, and higher-priority coordination predicates are rechecked.
The execution-time stale-state fences remain authoritative. Existing snapshot
and clean no-change validation remain resulting completion artifacts.

## Consequences

Workers can make any number of substantive mutations and continue polling as
`IMPLEMENT`; a completion request is an explicit finalization attempt. Existing
review, capability, snapshot, integration, and ownership safety gates remain
in force. Historical fixtures are preserved rather than reinterpreted.

## Verification

Focused tests must prove multiple mutations remain `IMPLEMENT`, explicit
completion alone can project the exact `finish_lane`, stale authority and all
existing blocking obligations fail closed, the new codec round-trips, removed
fields/formats are rejected, and the MCP catalog remains exactly ten tools.
