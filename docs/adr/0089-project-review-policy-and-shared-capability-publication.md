# ADR-0089 — A project can require review, and one publication serves every requester

## Status

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
  never integrate; that is the policy the project stated.
- The owner asserts the contract of the request it names. Its other requesters
  receive the same commit on the strength of their own accepted contracts, and
  each still validates it. No requester's validation is taken from another's.
- A requester that validated and stopped is needed again when the owner
  revises. Its lane is still open, so the controller resumes it.
- The tool catalog and the CLI surface are unchanged.
