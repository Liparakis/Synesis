# MCP behavior

The stdio server exposes exactly 11 tools. The stable raw tool names are:

`ensure_session`, `read_file`, `apply_patch`, `run_command`, `get_next_action`,
`request_coordination`, `respond_coordination`,
`publish_capability_implementation`, `finish_lane`, `cancel_lane`, and
`send_message`.

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

A contract publication, a binding, a change request and its answer are decided
and appended under the project event-log lock against a freshly replayed
projection, so concurrent MCP processes cannot record two bindings for the same
consumer and dependency, or bind to a revision a peer has just replaced. If the
lock cannot be acquired within its bounded wait, the call returns `blocked` /
`task_not_ready` with `reason: EVENT_APPEND_LOCK_UNAVAILABLE` and `retry`;
nothing was appended. Only the lane that claims a file publishes its contract and
answers the change requests against it; another lane receives `policy_denied`.

When only someone with authority over the project can clear what blocks a step,
the answer is `blocked` / `operator_required` with `operatorRequest` (`id`,
`condition`, `summary`, and `evidence` as the refusing tool reported it) and an
`instruction` (ADR-0091). Its workflow type is `OPERATOR_REQUIRED`, it permits
only `get_next_action`, and `maxUnchangedPolls` is 1: polling cannot change it.
It is shown to a lane that waits on integration, including the `finish_lane`
that published its snapshot, and not to a lane that still has work of its own.
The published work is kept and is integrated when the request closes.

A project may declare a validation command (`synesis project validation`). It
runs on the combined work of an integration attempt before the project's branch
moves. When it exits non-zero, the snapshot added last to the attempt is refused
and never integrates: its state is `VALIDATION_REFUSED`, its lane is reopened at
the next claim epoch with its claims (a lane that had already continued it
stands past that epoch and is left as it is; a holder that is suspended or held
for recovery stays so), and the call that ran the attempt is
answered `blocked` / `integration_failed` / `retry` with `phase: integration`,
`validation` and `refusedSnapshotId`. The lane's next `get_next_action` is
`REVISION_REQUIRED`, as after a rejected review, and every answer carries
`rejectedSnapshot` until the lane publishes again. A refused snapshot has
`validationFailure` wherever it is shown: `outcome`, `exitCode`, `command`
(the argument vector that was run, as the text of a JSON array),
`workingDirectory` (relative to the project root), `attemptId`,
`candidateCommit` and `repairsLeft`. `command` and `workingDirectory` are cut
to 8 KiB. `repairsLeft` counts the repair now owed: 2 after the first refusal,
1 after the second. `output` (the end of what the command printed, standard
output then standard error, at most 8 KiB) is added only in the
`rejectedSnapshot` shown to the lane that owes the correction, not in the
`snapshots` list of the collaboration details or any other view of the
snapshot. The lane
corrects its own claimed files; another lane's files are asked for with
`send_message` or a contract change request. A lane that asks to finish again
(`completionRequested`) is not held at `REVISION_REQUIRED` for having changed
nothing: the command may have failed for what was never the lane's, and it
publishes its files as they are. A lane that can do nothing about the failure
tells the operator with `send_message` to `["operator"]`. When the project's
command is no longer the one that failed, the `instruction` says so and names
the command it is now. A validation refusal makes no later revision
review-required; a project with mandatory review reviews the repaired snapshot
by its ordinary rules.

When the command fails while a lane with a snapshot in the attempt has used its
two repairs, when it timed out or could not be started, or when the pass that
ran it died twice in a row, nothing is refused and Synesis raises an operator
request with condition `integration_validation_failing`. Its `evidence` is the
end of what the command printed. While it is open no integration attempt is
started by an agent's call, so the command is not run again on every poll, and
a call that would start one is answered `blocked` / `operator_required`.
It closes when `synesis operator recheck` runs the command and it passes or
refuses a snapshot, when the project declares no command, or when no snapshot
is waiting to be integrated. A recheck that fails with another summary or
output closes it and raises a new request, so the open one shows the latest
output.

An internal failure while binding a session, announcing, finishing or
cancelling a lane, publishing or validating an implementation, or integrating
is answered `failed` with `failurePoint`: the identifier of an entry in
`.synesis/local/diagnostics/failure-points.jsonl` that holds the cause and its
stack. The answer itself never carries them. A `failed` answer from another
path does not yet carry one.

One public contract covers each claimed file (ADR-0092). Every agent can read
it, in `collaboration_status` and wherever `get_next_action` lists `contracts`.

- `contract_publish` (`paths`, `contract`, optional `answers`) publishes the
  contract over files the caller claims, or its next revision. A path ending in
  `/` names a subtree. A path outside the caller's claims is refused with
  `CONTRACT_PATH_NOT_CLAIMED`. A revision can add files and never drops one. The
  answer carries the revision, `bindingsToMakeAgain`, and the change requests
  it answered and left open.
- `contract_bind` (`capability`, `ownerPath`, `revision`) is the consumer's
  acceptance of one exact revision. `capability` is the consumer's own name for
  the dependency, as in its `task.knownDependencies`. The lane of another
  participant that claims `ownerPath` is the owner (ADR-0090). The answer is
  `ready` with `contractAccepted: true`, the contract and
  `capabilityRequestHandle`. A revision that is not the current one returns
  `stale_request` with the current contract; a file with no contract returns
  `needs_capability` / `owner_required` with `reason: CONTRACT_NOT_PUBLISHED`;
  a file no other lane claims returns the same with `lanes`.
- `contract_change_request` (`ownerPath`, `need`) tells the lane claiming the
  file what the caller needs, and asks for a contract where none exists yet. It
  returns `waiting` / `owner_response_pending` with `changeRequest`.
- `contract_change_response` (`changeRequest`, `decision`, `reason`) is the
  owner's answer: `accepted` when the current revision covers the need, or
  `declined` with a reason.

An owner with open change requests is shown all of them together in
`get_next_action`, as `changeRequests` beside `yourContracts`, and cannot finish
its lane until each is answered (`CONTRACT_CHANGE_REQUEST_OPEN`). A consumer
whose contract was revised is shown `revise_capability_request` with the new
contract and `bindAgain`, the exact `contract_bind` call; what it validated
under the earlier revision no longer counts, and the owner may publish the same
commit again for the new revision.

A verdict of `revision_required` in `implementation_validation` needs `reason`
and `contractField`, the field of the contract the implementation breaks.

A consumer that binds to a file whose owner has finished and been integrated is
answered `ready` with `reason: CAPABILITY_OWNER_INTEGRATED` and the frozen
contract: nothing is left to wait for or to validate, and the dependency is
satisfied from then on. `get_next_action` lists frozen contracts beside active
ones for that reason. A `contract_change_request` against such a file is
refused with `CONTRACT_OWNER_FINISHED`.

An owner waits while a lane bound to its contract is open. When a bound lane
owes an action (binding again, or checking a published implementation) and has
made no verified call for the stated time (two minutes by default,
`SYNESIS_REQUESTER_ANSWER_GRACE_SECONDS`), Synesis raises an operator request
with condition `bound_consumer_absent`, and the waiting owner is answered
`blocked` / `operator_required`. It closes when that lane acts or ends.

Cancelling a lane withdraws its bindings in every state. When an owner's lane
ends, the change requests still open against its contracts are declined and the
bindings it can no longer serve are closed. Its contracts become `FROZEN` when
its work was integrated, and `WITHDRAWN` when the lane was cancelled or finished
without a change: nothing was built to a withdrawn contract, it cannot be bound
to, and a lane that depended on it is shown the dependency as unbound again. A
lane that has published its work can no longer revise its contract
(`CONTRACT_OWNER_COMPLETING`). `collaboration_status` lists every change
request beside the contracts.

## Waiting

A `get_next_action` whose answer is a wait with nothing to hand over is held by
the server until the project log has something new for the caller or twenty
seconds pass, and when something is new the call is made again from the start
and its answer returned. An agent can therefore sit through a peer's work
without a model turn every few seconds. The hold changes when an answer is
given, never which. `SYNESIS_WAIT_HOLD_SECONDS` sets it for a server process
(zero to thirty; zero turns it off).

## Messages

`send_message` sends text from the caller's lane (ADR-0093). `to` names each
other lane by a file it claims or by its participant handle; `conversation`
continues a conversation the lane already belongs to. A conversation is its set
of member lanes: the same set is always the same conversation, a different set
is a different one, and Synesis shows a conversation only to its members.
Messages are kept in the project's coordination log and are not encrypted.

A message changes no state. No lane state, gate or next action is derived from
one, and what agents agree takes effect only when it is recorded through the
typed protocol. The note delivered with each batch asks for one short
answer to a direct question about files the reader's lane claims, and for
nothing else; the answer to a send tells the asker to carry on and not to wait. Messages addressed to the caller's lane arrive under `messages`
in a `get_next_action` result (`notice`, `unread`, `moreUnread`), beside the
next action and never in place of it, at most ten at a time and at least once:
a batch counts as arrived when the same connection calls again. The caller's
conversations, with the newest twenty messages of each, are listed under
`conversations` by `collaboration_status`. An agent that continues a lane has
that lane's conversations.

A lane may send fifty messages of at most 2,000 characters; the next is refused
with `MESSAGE_LIMIT_REACHED`. `to` `["operator"]` alone records an operator
request with condition `lane_asks_operator` instead, one open per lane. It
blocks nothing, and it is closed by the operator.

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
with a recommended fresh completion request. A `participant` or `workGroupId`
that differs from the lane's cannot be stale, only copied wrong: the call
returns `SNAPSHOT_COMPLETION_EVIDENCE_MISMATCH` (or
`NO_CHANGE_COMPLETION_EVIDENCE_MISMATCH`) with `field` and `expected`, and the
agent corrects that argument and calls again.

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
