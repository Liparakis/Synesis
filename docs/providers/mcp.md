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

Keep all task members inside `task`. If a `task` object is present, placing task
fields beside it is rejected instead of silently defaulting omitted fields.

Provider continuity is explicit. A normal MCP process is `SESSION_BOUND`; a
managed Codex process is `MANAGED_CONTINUITY` only after startup authenticates
the exact existing binding, thread, generation, and server-issued proof. The
proof is delivered through the allow-listed process environment and is never
written to configuration, repository state, prompts, output, or diagnostics.

Capability requests, requester revision replies, and owner replies are decided
and appended under the project event-log lock against a freshly replayed
projection, so concurrent MCP processes cannot record two active requests for
the same requester and capability or two different replies to one contract
revision. A reply that loses that race is answered from the recorded state: the
identical decision is idempotent, and any other reply is `stale_request`. If the
lock cannot be acquired within its bounded wait, the call returns `blocked` /
`task_not_ready` with `reason: EVENT_APPEND_LOCK_UNAVAILABLE` and `retry`;
nothing was appended. Only the session whose supervisor and worker scope is
stored as the request's owner may answer it or see it in its inbox. Local
sessions share one node identity, so a sibling session on the same node receives
`policy_denied`.

When only someone with authority over the project can clear what blocks a step,
the answer is `blocked` / `operator_required` with `operatorRequest` (`id`,
`condition`, `summary`, and `evidence` as the refusing tool reported it) and an
`instruction` (ADR-0091). Its workflow type is `OPERATOR_REQUIRED`, it permits
only `get_next_action`, and `maxUnchangedPolls` is 1: polling cannot change it.
It is shown to a lane that waits on integration, including the `finish_lane`
that published its snapshot, and not to a lane that still has work of its own.
The published work is kept and is integrated when the request closes.

An internal failure while binding a session, announcing, finishing or
cancelling a lane, publishing or validating an implementation, or integrating
is answered `failed` with `failurePoint`: the identifier of an entry in
`.synesis/local/diagnostics/failure-points.jsonl` that holds the cause and its
stack. The answer itself never carries them. A `failed` answer from another
path does not yet carry one.

A new capability request says whose it is by `ownerPath`, the repository file
that implements the capability. The open lane of another participant that
claims that file is the owner (ADR-0090). A lineage sent with it does not
overrule the claim, and a file no other lane claims leaves the request
unrecorded. Without `ownerPath`, `ownerAuthorityLineageId` is used, or the only
other open lane when there is exactly one. When the owner cannot be told the
answer is `needs_capability` / `owner_required` with `lanes`, the goal and
claims of each open lane, and an `instruction`. A recorded request answers with
`owner`, the goal and claims of the lane it went to.

When the owner asks for a contract revision, the requester's `get_next_action`
returns `revise_capability_request` with the capability name, the revised
contract, its `contractRevision`, and `acceptRevision`: the exact
`request_coordination` call that accepts it. Accepting, countering, or cancelling
remains the requester's decision, so that call is offered and not recommended. A
reply that does not match the recorded request returns `stale_request` with a
`reason` of `CAPABILITY_CONTRACT_REVISION_STALE`, `CAPABILITY_NAME_MISMATCH`,
`CAPABILITY_REVISION_NOT_PENDING`, or `CAPABILITY_CONTRACT_MISMATCH`.

After the requester validates a published implementation, the owner's ordinary
`get_next_action` returns `implementationValidated: true` and recommends
`get_next_action` with `completionRequested: true`. The dependent lane cannot
integrate until the owner's own lane snapshot exists, so this takes precedence
over the shared integration wait.

The `finish_lane` arguments projected by a completion request carry
`expectedRevision`, the event-log revision at projection time. They stay valid
while only participant heartbeats are appended after that revision, so another
agent's polling does not invalidate them. Any other event does, and the call
then returns `SNAPSHOT_COMPLETION_EVIDENCE_STALE` (or the no-change equivalent)
with a recommended fresh completion request.

If Synesis cannot load the durable terminal fence for the exact bound session,
`ensure_session` and `get_next_action` fail closed with
`SESSION_TERMINAL_STATE_UNAVAILABLE`. For diagnosis, the response reports the
cause's exception type (`causeType`) and a bounded `causeCode`:
`event_log_verification_failure` for coordination event-log integrity failures,
or `durable_state_ambiguous` when durable state cannot be classified more
precisely. It does not include paths or exception messages. The workflow marks
this as non-retryable recovery with no recommended MCP tool; it does not
authorize rewriting or restoring the event log. The symbolic
`request_human_help` action is not an MCP tool; stop and request operator
intervention before retrying session operations.
