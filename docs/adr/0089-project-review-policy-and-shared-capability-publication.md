# ADR-0089 — A project can require review, and one publication serves every requester

## Status

**Amended by ADR-0104 (built 2026-10-26/27):** the project's review policy is read from the owner's signed decision in the
project's log (`synesis project review set mandatory|none`), not from `review` in `.synesis/project.json`, once the
project has a signed baseline; the work group still keeps its own copy as announced.

Accepted on 2026-10-11. Decided by the repository owner after the real runs
recorded in `docs/agent/CURRENT.md` for that date. Made under ADR-0088: the
persisted project metadata and the capability event sequence change, every
producer and reader in this repository changes with them, and durable state
written by an older prerelease build is not migrated.

## Context

Three causes kept failing real multi-agent runs after the coordination defects
of the day were fixed.

**Review could not be required by a project.** Only a work group created with
a mandatory review policy made review a condition of integration, and only an
agent could create such a group. A project whose goal said "this is reviewed
before it is integrated" had no way to state it durably. A producer that
finished before any reviewer had announced was integrated unreviewed, and the
reviewer that arrived later had nothing it could lawfully decide.

**An owner with several requesters published to one at a time.** Each request
for a capability carries its own implementation revisions and validation. The
owner published its implementation for one request and was then shown a wait
for that requester's verdict, ahead of the publication the other requesters
were waiting for. One requester that stopped held all the others.

**Requesters validated different commits of the same files.** When one
requester asked for a revision after another had validated, the owner changed
its files and published again for the first. The other request stayed
validated at the older commit, could not be published to again, and the owner
could not finish: its files no longer matched what that requester validated.

**An owner could finish past a request it had never answered.** A request
filed moments before the owner finished stayed `AWAITING_OWNER` after the
owner's lane closed, and its requester waited for an answer nobody could give.

## Decision

- `.synesis/project.json` may carry `"review": {"mandatory": true}`. Every
  work group that an announcement creates in such a project is created with the
  mandatory independent review policy. A group that already exists keeps its
  own policy, and a group created explicitly through `work_group_create` keeps
  the policy it was given. The field is optional; absent or false, groups are
  created without a review policy as before. It is set like the project's
  validation command, by whoever establishes the project's policy, not by an
  agent.
- A capability implementation is published to every request of the owner's
  lane that has an accepted contract, not only to the request the owner named.
  A request whose latest implementation is already that commit is left as it
  is. A request that was published to, being validated, or validated at an
  older commit receives the new revision and returns to
  `IMPLEMENTATION_AVAILABLE`, so its requester validates what will actually be
  integrated.
- A request still waiting for the owner's implementation outranks the owner's
  wait for another requester's verdict.
- An owner that finishes supersedes the requests addressed to its lane that it
  never answered, as it already superseded its own unanswered proposals. The
  requester is told the owner went ahead and builds against the integrated
  work.

## Consequences

- A project with mandatory review does not integrate a lane until a reviewer
  has accepted its snapshot. Two producers in such a project and no reviewer
  never integrate; that is the policy the project stated. (Amended 2026-10-17
  below: the producers review one another.)
- The owner asserts the contract of the request it names. Its other requesters
  receive the same commit on the strength of their own accepted contracts, and
  each still validates it. No requester's validation is taken from another's.
- A requester that validated and stopped is needed again when the owner
  revises. Its lane is still open, so the controller resumes it.
- The tool catalog and the CLI surface are unchanged.

## Amendment 2026-10-17: a producer waiting for review may review the others

Decided by the repository owner after the run recorded in
`docs/agent/CURRENT.md` as "Two homes, bigger goal". Four writers in a project
with mandatory review each published and then held a `WAIT` for its own
review. A producer waiting on its review was offered no other work, and with no
dedicated reviewer no review ever started.

- A participant that holds a lane in a work group and has published a snapshot
  in it may be admitted to review another lane's snapshot in that group,
  without a reviewer lane. Before, admission required a reviewer lane of the
  requester and refused anyone else with `REVIEWER_INTENT_REQUIRED`; a
  participant that published nothing in the group, or holds no lane in it any
  more, still is.
- A participant is never admitted to review work of an authority lineage it
  published a snapshot in, so it cannot review a continuation of its own work
  either. The request is refused with `REVIEW_SELF_NOT_ALLOWED`, as a request
  to review its own lane already was. Every other independence check is kept.
- A lane held for recovery is not continued by a participant that holds an
  undecided review grant on it (`REVIEW_SELF_NOT_ALLOWED`). Role isolation
  refused only a successor holding a reviewer lane; a producer reviewing through
  a grant could otherwise take over the lane it was reviewing and accept that
  lane's snapshot as its owner.
- A continuation carries the reviews the source owner held, grants and the
  review requests it made, to the successor, whatever the lane's role. Only a
  reviewer lane's were carried before, and the reviews held by a producer that
  died stayed addressed to it, the snapshots they named reviewed by nobody.
- `get_next_action` offers a producer whose current snapshot awaits review the
  open review candidates of its work group that it did not author, before its
  wait. With one candidate the request is executable; with several, the
  producer chooses, as every review candidate is chosen. With none it waits as
  before.
- A review admission asked of a lane is shown before anything else about
  review: before the lane's own wait for an answer to a review request it made,
  and before a review or a correction it owes. Producers that ask to review
  each other are each requester and owner, and each waiting for its own answer,
  none would answer another's. Answering is one call, and the review or
  correction is shown on the next.
- The answer offered is the one the acceptance rules allow. When accepting
  would be refused, for instance because another producer was admitted to the
  same snapshot first, the owner is offered `REJECTED` with that refusal as the
  proposal. Offered the accept, the owner was shown the same refused action
  again ahead of its own work, and the requester waited for an answer that
  never came.

**Representation.** The review is taken by a request bound to the exact
snapshot, and its authority is the grant the snapshot's owner issues to the
reviewer's participant. The reviewer keeps its one lane, claim epoch and claims;
it announces no reviewer lane. Two alternatives were rejected. A second,
reviewer, lane for the same participant breaks "a session holds one lane": the
lane identity is derived from the session, and every lookup of a participant's
lane would have to choose between two, so lane ownership and the epoch a
mutation is fenced on would become ambiguous. A separate session for the review
is something an agent cannot open: one provider connection is bound to one
session.

**Why a held lane and authorship, not the wait.** The owner's decision names a
producer whose snapshot is waiting. Admission is checked when the request is
made, when the owner accepts it and when the grant is issued. The lane is what
recovery works on: a reviewer whose agent dies is continued through its lane,
and its reviews with it. A reviewer with no lane would have nothing to be
continued from, and its grant would hold the snapshot from every other reviewer
for good. Requiring the wait itself would refuse more often in the window
between request and acceptance without adding independence. When the
requester's lane ends in that window, accepting is refused and the owner is
offered the rejection instead. A participant that reviewed a snapshot and later
continues that lineage, after deciding, is not refused the continuation; from
then on it is an author of the lineage and cannot review it again.

**Consequences.**

- A project with mandatory review and only producers completes once each
  snapshot has been reviewed by another producer.
- While a producer holds a consumed review grant, its `read_file` and
  `run_command` read the immutable review snapshot, as for any reviewer, and
  its review action comes before a correction of its own rejected snapshot.
- A producer whose own snapshot is accepted and integrated while it holds a
  review grant keeps the grant with no lane. If its agent then dies, nothing
  continues that review; this is left to the separate work on reviews whose
  agent is gone.
- A lineage is a participant's own work only once it published in it. A
  participant that held a lineage's lane and handed it off before publishing is
  not excluded from reviewing it.
- The tool catalog, the MCP schemas, the CLI surface and the persisted formats
  are unchanged. The next-action answer for a waiting producer may now carry
  `nextAction: request_coordination` and `actionableReviewCandidates` where it
  carried only `wait`. Replaying an older log can now carry a producer's
  reviews to its successor where it did not before.
