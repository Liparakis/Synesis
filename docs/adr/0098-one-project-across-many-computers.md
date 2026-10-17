# ADR-0098 — One project across many computers

## Status

Accepted on 2026-10-16 by the repository owner. The decisions marked USER-STATED were made by the
owner on 2026-10-15. Each step under "Build order" is built and evidenced on its own.

Built so far (`docs/agent/CURRENT.md`, 2026-10-15 and 2026-10-16):

- **Step 1**, the compatibility number: `LinkCompatibility.CURRENT` = 4 (6 after step 2).
- **From step 2's "First"**, heartbeats out of the log: `ParticipantActivity`.
- **Found and fixed while writing this ADR:** one coordination folder and incremental log reads.
- **Step 2, DONE 2026-10-16 (run 7).** Writers and reviewers on both computers. The WorkGroup COMPLETED, and both
  computers' runs COMPLETED. Not reached: two networks, a real drop and redial during a run. See
  `docs/agent/CURRENT.md`, "run 7". The history before it:
- **Step 2, built 2026-10-16, PARTIAL at first:**
  - The audit, member proposals, Link transport, code through a Git remote, merge outside the lock, joining, and
    redial are built.
  - In real runs between two computers, cross-computer claims, review and the leader merging a member's lane were
    shown.
  - A WorkGroup did not complete, because of review routing that is not specific to two computers.
  - See `docs/agent/CURRENT.md`, "four real runs".
  - **Run 6, same day:** after the review-guidance and session fixes, the whole goal integrated.
    - Each module was written on one computer, reviewed on the other, and merged by the leader.
    - All three heads ended equal, and the tests pass.
    - The WorkGroup stayed ACTIVE only because an agent opened an extra lane the goal does not name.
    - See `docs/agent/CURRENT.md`, "run 6".

It builds on ADR-0066 (overlay), ADR-0076 (adjacency reconciliation), ADR-0077 (multi-peer QUIC),
ADR-0080 (project identity), ADR-0093 (conversations) and ADR-0097 (answer link). It changes
ADR-0066's membership authority and ADR-0077's topology policy; see "What this changes".

Mode: evolution of the existing system. Claims are marked VERIFIED, USER-STATED, DERIVED, ASSUMED,
UNKNOWN or REQUIRES-MEASUREMENT.

## Context

Agents coordinate today because they share one disk. Every claim, lane, review, message and
integration is an event in one log under the project's `.synesis` folder. Each event is a file
named by a dense sequence number, chained by SHA-256 and signed with Ed25519 by the local node
(`PredictionEventStore`, `PredictionEvent`). Writers on that one computer are serialized by an OS
file lock held across read, validate and append (`ProjectAppendLock`, `ProjectAppends`). (VERIFIED.)

Link connects computers but carries no coordination:

- Frames delivered over the overlay are discarded (`LinkRuntimeOwner`).
- The production launcher passes no membership, so the overlay is off (`CoordinationServerLauncher`).
- `synesis sync` copies decision and constraint records once and stops (`SyncApplicationService`).

(VERIFIED.)

Four other things assume one computer (VERIFIED):

- **Integration holds the lock throughout.** One pass prepares a worktree, cherry-picks snapshots,
  runs the project's test gate and fast-forwards the control branch, all under the append lock
  (`AgentNextActionService`, `IntegrationOrchestrationService`).
- **Liveness uses local process checks.** Session liveness combines wall-clock heartbeats with
  process IDs on the same machine (`SessionLeaseService`).
- **Event times come from the writer's clock** (`createdAtEpochMillis`).
- **The held wait counts event files on the local disk** every 400 ms (`ProjectChangeWait`).

The repository owner wants agents on many computers to work on one project, with nothing depending
on one person staying online. (USER-STATED.)

## Decisions made by the owner (USER-STATED, 2026-10-15)

1. **Links.**
   - Links are balanced: not everyone connects to everyone.
   - Peers connect directly when their routers allow it, and through a third member when not.
   - A lost link is replaced from a candidate list that changes as members join and leave.
   - Two members never paste links to each other; members pass each other's answer links along.
2. **Membership.**
   - A member is a computer, not a person. A person's several computers are several members.
   - Any member may invite. Only the owner may remove.
   - The owner holds a recovery key that can make any computer the owner's computer again.
   - A removed member keeps what it already copied. No part of a project is hidden from some members.
3. **Deciding.**
   - A small group of voters decides claims and merges. Every member is a voter while there are five
     or fewer.
   - Voters are picked automatically from the most reliable members. The owner may pin or exclude any.
   - More than half the voters decide. On an exact tie, the side with the owner's computer decides.
   - An absent voter counts until it is removed or replaced.
4. **Scale.** Hundreds of members per project.
5. **A member lost in the middle of a lane.** Its lane is held for a grace period, then another
   agent may continue it from the last snapshot it handed in. Edits it never handed in are kept on a
   side branch for a person to look at, never merged automatically.
6. **Code.** Code travels through a Git remote, or over Link when none is configured.
7. **Merging.**
   - The owner declares the project's parts. Each part has its own merge queue.
   - A lane that touches two parts waits for both queues and merges into both at once.
   - The voters give each part's merging to a reliable member.
8. **Signals.** Integration and other coordination signals are entries in the log, not chat
   messages. A message still decides nothing (ADR-0093).
9. **Operator requests.** Any member may answer one.
10. **Versions.** Builds whose compatibility number differs refuse to connect, clearly and loudly.
    The number changes only when a format sent between computers changes.

## Decision

### The shape

Each member keeps a full copy of the project log and serves reads from it. Writes are decided by
one voter at a time, the **leader**, and become final once more than half the voters hold them.
This is the replicated-log model of Raft, with the non-voting members Raft calls learners. The
existing log already has the property it needs: a dense sequence and a hash chain, so a member can
prove it holds exactly the leader's prefix. (DERIVED.)

```text
member  ──request──▶  leader ──append──▶  voters (majority acknowledges) ──▶ committed
   ▲                                                                        │
   └────────────── log entries, spread along the overlay ◀──────────────────┘
```

### Invariants

1. **One history.** Every member's log is a prefix of the committed log. A member never appends on
   its own; it applies committed entries in sequence order.
2. **One leader per term.** A leader is elected by more than half the voters, or by exactly half
   including the owner's computer. Two sides of a split can never both reach that. (DERIVED: two
   disjoint sets cannot both hold more than half, and only one can hold the owner's computer.)
3. **The voter set changes only by an entry the current voters commit.** Automatic picking proposes;
   it never installs. While a change is pending, both the old and the new set must agree ("Step 3 design").
4. **With an even number of voters, the owner's computer is one of them**, so the tie rule always
   has someone to apply to.
5. **Removal needs the owner's key.** A removal entry is valid only when signed by the owner key or
   by a key the recovery key has made the owner key. Invitations need any member's key and a
   committed entry.
6. **Decisions are made against the leader's committed state.** A member runs the services on an
   exact copy of the leader's log and proposes the entry it would append, naming the position it
   expects. The leader commits it only if its log is still at that position and its projections
   accept the entry. Every rule that guards authority lives in a projection, so the leader enforces
   it on every entry, wherever the entry came from. A rejected proposal returns the existing
   refusal, such as `OVERLAPPING_CLAIM`. (Reworded on 2026-10-16: the leader originally re-ran
   every service rule. See "Step 2 design".)
7. **Time used in a decision is the leader's.** A writer's clock appears only as information.
   Liveness of a remote member is the leader's observation, committed only when it changes. It
   never comes from a process ID on another computer.
8. **A merge never holds the log.** A part's merger prepares, tests and proposes a merge entry
   naming the part head it started from. The leader commits it only if that head is still current;
   otherwise the merger starts again.
9. **Parts are locked in a fixed order.** A lane touching several parts takes their queues in the
   order of the parts' names, so two such lanes cannot wait on each other.
10. **Different compatibility numbers never form a session.** The check runs in the Link handshake,
    before anything about the project is exchanged.

### Membership authority

The signed membership becomes part of the replicated log. Joining, removal, voter changes, owner key
changes and part declarations are all log entries, committed like any other.

ADR-0066 and ADR-0080 made the membership snapshot the authority and let only its single signing
node issue it (`OverlayMembershipView` rejects any other authority). That cannot hold the owner's
decisions: members could not admit anyone while the owner was away, and there could be no recovery.
Here the overlay receives each membership view as a snapshot *derived from* the committed log, and
admits only members of that view, as before. (DERIVED.)

The owner key is separate from the owner computer's node key. Creating a project makes both and
shows the recovery key once.

### Links at hundreds of members

The current topology is a ring sorted by node ID: each member links to the members one and two
places away, or to everyone at three or fewer (`OverlayTopologyPolicy`, VERIFIED). A message between
two members of a 300-member ring can cross about 75 links. (DERIVED.)

The policy keeps the ring and adds two more rings, each ordering the members by a hash:

- Sort the member node IDs.
- With three or fewer members, link everyone.
- Otherwise link each member to the members one and two places away, as today. Then, for
  m = 1 and 2, sort the members by `SHA-256(nodeId ‖ "#" ‖ m)` and link each to the next in that
  order, wrapping at the end.

A member has at most eight links, and every member computes the same graph from the membership
alone. A simulation on random IDs, with five seeds per size, measured the following.
(VERIFIED by simulation. Failures were random and independent, and every link was assumed
possible.)

| Members | Average hops, ring | Average hops, ring + 2 rings | Still connected after losing 10% / 30%, without recomputing |
| --- | --- | --- | --- |
| 100 | 12.9 | 2.51 | |
| 300 | 37.9 | 3.11 | ring: 2% / 0% |
| 1000 | 125.4 | 3.75 | ring: 0% / 0%; ring + 2 rings: 100% / 92% |

A join or leave changes four links between existing members. Each changed link may mean a new hole
punch.

Rejected:
- **Chord fingers by list position** change about 2N links on every join.
- **Chord fingers by ID** let one member reach 73 links.
- **Hashed long links without rings** either leave the degree unbounded or fall apart more easily
  at equal degree.

A third extra ring, up to ten links, is the setting to raise if a project must survive losing 30% of
its members at once before membership is recomputed.

Two members that cannot reach each other directly use the answer-link exchange of ADR-0097 with the
overlay as the courier: one member sends its endpoints, the other sends toward them. No person
pastes anything. Members whose routers block every direct path keep a route through a third member.

### Code

Lane snapshots and part heads are Git commits.

- **With a configured remote:** each member pushes its snapshot refs there, and fetches whatever a
  committed entry names.
- **Without one:** a member pulls them as a thin Git bundle from a member that holds them:
  1. The receiver names the commit it needs, taken from a committed entry, and the tips it already
     has.
  2. The sender bundles only refs under `refs/synesis/snapshots/`, and sends no more than a set
     size.
  3. The receiver checks the bundle (`git bundle verify`) and fetches it into a fixed ref with
     object checking on.
  4. The receiver keeps the ref only if its commit and tree match the entry.

  A peer never runs a Git command of its choosing on another member, and nothing is ever pushed
  into a member. Git today runs only through `GitProcessRunner`, which closes standard input, keeps
  64 KiB of output and stops after ten seconds (VERIFIED), so writing a bundle file needs a longer
  bound. Streaming `git upload-pack` and a Git remote helper were rejected: each needs a new
  streaming runner or a separate executable, and neither can resume an interrupted transfer.

A merge entry names commits, never file contents. A member that cannot fetch a named commit reports
it, and works on nothing that depends on it.

### Timing

Starting values, to be confirmed by measurement (step 3):

| Timer | Value | Why |
| --- | --- | --- |
| Voter heartbeat | 1 s | 7 to 30 times an idle home round trip of 7 to 34 ms (FCC, *Measuring Broadband America* 2023). It equals Link's heartbeat. |
| Election timeout | random 10–20 s | At least ten heartbeats (etcd, TiKV). Well above Link's 5 s `EXPIRED`, so one Link reconnect starts no election. |
| Pre-vote, check-quorum | on | A computer back from sleep or a split cannot depose a working leader, and a leader without a majority for 10 s steps down (Raft thesis §9.6; etcd/raft). |
| Lease reads | none | Home computers sleep, which breaks the clock bound a lease needs. A read that grants authority confirms leadership first (ReadIndex). |
| Member suspected / lane grace | 2 min / 5 min | Today's `SessionLeasePolicy` values, now measured by the leader. Five minutes is about fifteen worst-case elections, so a leader change alone never causes a takeover. |
| Keepalive on every NAT path | at most 15 s | Routers may drop a UDP mapping after 30 s idle (RFC 9308 §3.2), and 15 s is the floor RFC 8085 allows. Voters already send more often; members do not send every second, which would load the leader. |
| QUIC idle timeout | about 60 s, set explicitly | Link sets none today. (VERIFIED: `NettyQuicTransport`.) |

Link's own `SUSPECT` (3 s) and `EXPIRED` (5 s) are hints. No decision keys off them. (VERIFIED
values: `LivenessConfiguration`, which calls them a conservative local default.)

### The log's size and replay cost

The five largest logs from real five-agent runs hold 341 to 720 events, of about 500 bytes each.
That is roughly 1 MB per hour of five lanes (estimate). (VERIFIED from
`Documents\SynesisRemasterAcceptance`.)

Two things must change before the log is copied to hundreds of members:

1. **Heartbeats leave the log.** `PARTICIPANT_HEARTBEAT` makes up 68% of events and half of the bytes
   in those logs; one is appended on every tool call. Liveness becomes the leader's observation, and
   only its changes become entries: suspected, lost, back.
2. **The log is read incrementally.** Done on 2026-10-15 (`VerifiedEventPrefixes`).
   - **Before:** every store that opened re-read and re-verified every event, and so did every
     append: at least three full passes per heartbeat. On a 720-event log, one open plus one append
     took 1.0 to 1.2 s.
   - **Now:** each process verifies an event once. The same open plus append takes 14 to 19 ms.
     (VERIFIED: `docs/agent/CURRENT.md`, 2026-10-15.)
   - **For replication:** a member keeps a verified head and applies only new entries.

A snapshot that lets old entries be dropped is still left open. The two changes above remove most
of the growth.

### What each member keeps local

Lane worktrees, integration worktrees, the MCP server agents talk to, hooks and the held wait stay
on each computer. They read the local copy of the log.

The held wait wakes when a committed entry arrives, instead of when a file count changes.

### Versions

Each build carries a **compatibility number**, raised only when a format sent between computers
changes. It is in the invitation and in the Link handshake. On a difference, both sides end with a
new failure that names both numbers and which side is older. It is never reported as an invalid or
expired invitation, which is what a version mismatch looks like today (`Onboarding`, VERIFIED).

## Step 2 design (2026-10-16)

The leader is fixed: the owner's computer, here **A**. Members, such as **B**, run their agents
locally.

### Trust model (USER-STATED, 2026-10-16)

Members are people the owner trusts. The design favours throughput over defence against a hostile
member. It must still show who did what: every entry is signed by the computer that made it, and
the hash chain makes a silent edit or deletion visible on every copy.

Two guards stay regardless of trust, because they stop accidents (a buggy build, an agent gone
wrong), not malice:

1. **Authority rules live in projections.** No two agents hold one file. Nobody reviews their own
   work. A grant is used once. A claim epoch must be current.
2. **A participant's entries come from the computer that introduced it.** An entry acting for a
   participant is accepted only from the node that signed that participant's
   `WORK_INTENT_ANNOUNCED`. B therefore cannot release or finish A's lanes.

### Shape: the member proposes, the leader verifies

The seam is the one place an entry is written: the file placement in `PredictionEventStore.append`.
(Design by the Opus runner of the architect exploration; parts from the other three runners are
named where used.)

1. **On B, each append becomes a proposal.** B's service code runs unchanged: the tool, its
   service, its rules, and its local Git and binding effects, against B's copy of A's log at head
   *N*. The entry it would write is signed with B's node key and names *N* as the previous digest
   and sequence. B sends it to A and waits.
2. **On A, the proposal is checked:**
   - its log is still at *N*;
   - the Link session's node is the entry's signer, and a member;
   - the event type is not one only the leader writes (a new flag on `PredictionEventType`, for
     example `INTEGRATION_*`, `CONTROL_BRANCH_ADVANCED` and `TASK_INTEGRATED`);
   - its projections accept the entry, as for a local append.

   A then places the entry unchanged, under its append lock.
3. **If A's head moved,** B gets the existing "lost its sequence" answer. B applies A's newer
   entries, and its service runs again on the new head: today's retry loop, now across computers.
4. **Members never append on their own** (invariant 1). On A, local appends are unchanged.

Nothing changes in the 11 tools, their schemas or the services' public methods. Provider-session
bindings stay on B and are checked there, as today. Because B signs its own entries, the services'
assumption that "the signer is this computer" still holds on the computer that runs them.

### Delivery of committed entries

From the Fable runner:

- A sends members a short notice when its head moves.
- B then pulls the entries after its head, in pages, over a Link application stream. No entry has
  to fit an overlay frame, which is bounded at about 4 KiB.
- B also pulls every 15 seconds. That doubles as the NAT keepalive (see "Timing").
- B writes the pulled entries into its own event folder, after verifying the chain. B's held wait
  and every reader keep working unchanged.
- A gap or a digest mismatch stops B loudly. B never repairs its copy by guessing.

### Liveness of B's agents on A

B's agents' calls are reads on B and never reach A. Every 15 seconds B sends, with its pull, the
participants that made a call since the last one. A records them in `ParticipantActivity` at the
time it **received** them (the leader's clock, invariant 7). A's run controller and absence guard
then see B's agents as active.

### Code

The Git remote is configured on both computers.

- **Before proposing an entry that names a commit,** B pushes `refs/synesis/snapshots/*` and
  `refs/synesis/prepared/*`. That covers a snapshot, a prepared completion and a published
  implementation.
- **A fetches before deciding anything that reads those commits.** That means the merge, review
  access and validation. A computes changed paths and tree hashes itself, rather than reading B's
  claim (from the Fable runner). A missing or mismatched commit refuses with the existing
  snapshot errors.
- **After each control-branch advance,** A pushes the control branch. B fast-forwards before
  giving out a new lane worktree.
- A path written into an entry, such as the validation worktree path, is a path on the signer's
  computer, and only that computer reads it.

### Merging runs on A, outside the log lock

The merge runs only on A. A guard in `orchestrateIntegration` covers its three triggers. A
short-delayed pass also runs after any entry from a member, so A needs no agent of its own to merge
B's work (from the Sonnet and Opus runners).

Today the merge holds the append lock through the project's test gate, often minutes. Every writer
gives up after 2 seconds, so B's proposals would fail through every merge. The Opus runner found
this. Invariant 8 is therefore brought forward from step 5:

1. The cherry-pick and the test gate run without the lock.
2. The lock is taken only to record the result, and only if the control head and the lanes'
   entries are still what the pass started from. Otherwise the pass starts again.

This also stops one computer's merge from blocking its own agents.

### Failure and retry

- A resent proposal is the same bytes. A recognises an entry it already holds and answers with its
  position.
- A unreachable: `LEADER_UNREACHABLE`. No answer after resends: `LEADER_OUTCOME_UNKNOWN`. B's agent
  keeps working inside its lane, and the next pull settles what happened.
- B's own append lock is held across the round trip, so B's agents never race each other for a
  position.
- `LinkCompatibility.CURRENT` becomes 5, because the member protocol is new.

### Smallest first slice

1. **Two project roots on one computer, in one test.** One root is the leader and one a member,
   joined by an in-process transport. Proposal, verification, pull, the attribution guard, and the
   two projection rules moved out of the services.
2. **Link as the transport.** Leader and member coordination servers, notices, pulls and
   proposals.
3. **Code through a bare Git remote,** and the merge moved outside the lock.
4. **Joining.** A member is added through the existing invitation. In step 2 the member list is
   local state on A; step 3 moves it into the log.
5. **A real run:** A, and the virtual machine as B.
   - B's claim of a file A holds is refused.
   - Each side reviews the other's work.
   - A merges B's lane with no agent of its own, and B fast-forwards.

**Deferred:**
- elections, terms, and leader time on entries (step 3);
- membership in the log (step 3);
- reconnecting a lost session (step 4);
- capability contracts across computers;
- batching proposals.

### Settled before code (owner, 2026-10-16)

- **The audit comes first, as its own change.**
  - List every rule enforced only inside a service. Seen so far: `REVIEWER_REQUIRES_PRODUCER`,
    `WORK_GROUP_NOT_ACTIVE` and `CLAIM_EPOCH_STALE`.
  - Move the rules that guard authority into their projections. Leave purely helpful checks where
    they are.
  - Make an append validate against projections caught up to the durable head, not against what
    the store held when it opened.
  - On one computer this closes the gap where `finish_lane` and `cancel_lane` append without the
    lock.
- **Session lifetime.**
  - Step 2's first real run accepts that both computers stay up.
  - A drop is reported loudly on both. B's agents keep working inside their lanes and get
    `LEADER_UNREACHABLE` for anything that needs A.
  - Redialing the same peer from the endpoints already known follows straight after. It comes
    before real use, because laptops sleep.
- **Pre-push hooks.**
  - Synesis pushes its own `refs/synesis/*` refs with local hooks off, as `GitProcessRunner`
    already runs every Git command.
  - The project's tests still run at the merge on A. Server-side protection still applies.
  - A push to a real GitHub repository is tested early in step 2. If a host refuses custom refs,
    the push reports `CODE_REMOTE_UNAVAILABLE` with the reason.

### Joining, revised (owner, 2026-10-16)

The first build exposed the plumbing:
- node IDs copied both ways;
- `project share`, `add-member` and `follow`;
- a code-remote name typed on each computer.

The owner wants one link instead.

1. **The owner creates a project invitation.** In the browser UI or with `synesis link invite`, the owner gets one
   link. It carries the connection invitation (the existing `SLB3` payload: signed, single use, expiring), the
   project identity (UUID and creation time from `project.json`), and the owner's code remote URL if the project has
   one. Making the invitation makes the owner's computer the leader if it is not one yet.
2. **The friend opens it.** They click it (the installer's `synesis://` handler opens the browser UI) or paste it
   into the UI's invitation box. The command line keeps working. Synesis then looks up the project identity in the
   friend's known-project registry.
   - **Known:** that folder is used.
   - **Not known:** the friend is asked where the project is. A chosen folder is accepted only if its
     `.synesis/project.json` carries the same identity. It is then registered as a copy of the project (ADR-0080,
     `initReplica`) and becomes the folder used.
   - **Not on this computer:** the friend is told so, with the code remote URL to clone from. They clone it
     themselves and choose the folder. Synesis cloning it, or receiving it over Link when there is no Git host, is
     deferred until after build step 6 (USER-STATED).
3. **The friend's computer follows the inviter automatically.** It sets the member role and the code remote from the
   invitation, with the log still empty, then connects. No node IDs are typed.
4. **The owner approves each join** (USER-STATED). The connection is authenticated as today and project identity is
   checked (`ProjectContextExchange`), but the session is not served until the owner accepts the request. The owner
   sees it in the UI ("<node> wants to join <project>", Accept or Decline) or with `synesis link requests` and
   `synesis link accept <node>`. Accepting adds the member. Declining closes the session and the invitation.
5. **Across two networks,** the answer link of ADR-0097 still goes from the friend back to the owner, or the owner
   starts with the router-port option.

The commands `project share`, `add-member`, `remove-member` and `follow` stay for scripts and removal. They are no
longer the documented way in.

### Rejected for step 2

- **The leader re-runs every service** (the Haiku, Sonnet and Fable runners). B sends typed
  commands, and A runs the full service code and signs every entry.
  - It is the strongest against a hostile member.
  - It needs about 25 operations reshaped into one type, three services split into local and
    leader halves, and A learning about B's provider sessions.
  - A would sign entries it did not author, so attribution becomes a field inside the entry instead
    of a signature.
  - Under the owner's trust model it costs more and shows less.
- **Forwarding whole tool calls.** A would have to reach into B's disk, which is where agents
  edit.

## Step 3 design (2026-10-17)

Step 2 has one fixed leader, chosen by a local file. Step 3 lets the leader move: a project keeps working after losing
any minority of its voters, including the leader and the owner's computer. Membership, the owner key and removal move
into the log. (Design by the Opus and Fable runners of the architect exploration, merged; the runner each part comes
from is named where it matters.)

Not built yet: no code exists for this section.

### Settled before code (owner, 2026-10-17)

The owner answered four questions:

1. **Joins while the owner is away.** This is a project setting the owner controls. The default is that a member's
   invitation waits for the owner. The other choice is that any member may approve. Changing the setting needs the
   owner key. (USER-STATED)
   - Example: Bob invites Carol on Saturday and the owner's computer is off until Monday. With the default, Carol's
     computer connects and shows "waiting for the owner". The request is a log entry, so it survives restarts, and the
     owner accepts or declines on Monday.
2. **What "reliable" means for picking voters.** The share of the last 7 days a computer was connected, counting only
   computers that can reach the code remote now. At most one voter changes per day. (USER-STATED)
3. **Where the owner key lives.** Only on the owner's computer, stored next to that computer's node key and never
   copied. To act as owner from another computer, the owner uses the recovery key there once, which makes that
   computer the owner's computer. (USER-STATED)
4. **Losing more than half the voters for good.** Yes: the owner key or the recovery key may force a new voter set.
   (USER-STATED)
   - Example: three voters, two laptops stolen. The owner names the desktop and a friend's PC as the voters and work
     continues.
   - If a "lost" computer was only asleep and comes back, whatever it decided alone after losing contact is set aside
     for a person to look at. It is never merged.

### What the code showed (VERIFIED, 2026-10-17)

- **Every process reads `events/` directly**: agents' MCP servers, the run controller (`EventLogObserver`), the held
  wait (`ProjectChangeWait`), and the hooks work in flight. Each verifies entries once (`VerifiedEventPrefixes`).
  Anything placed there must never be taken back.
- **A member's entry names its own position.** The sequence and the previous digest are inside the bytes the member
  signs (`PredictionEvent`). The entry has no term field.
- **Each `PredictionEventStore` fixes its role when it is opened.** A store opened while its computer led would keep
  writing `events/` after it stopped leading (Fable runner).
- **Leader services call `store.append` while holding `ProjectAppendLock`** (`ConsumerAbsenceGuard.reconcile`, the
  merge pass). If those appends went to a decider that takes the same lock, as `LeaderEndpoint.accept` does now, they
  would deadlock (Opus runner).
- **A new leader can wait forever on an old leader's merge.** The merge commit is pushed only with the control branch,
  after the pass. `IntegrationOrchestrationService.branchCannotHold` treats Git's "unknown commit" as "the branch
  could hold it", so the started attempt is waited on with no end (Opus runner).
- **No liveness crosses computers today.** `PULL` carries no participants; `ParticipantActivity` is local, and
  `ConsumerAbsenceGuard` judges only participants with a provider binding on its own computer. The step-2 section
  "Liveness of B's agents on A" describes behaviour that was never built. A leader change therefore changes no
  liveness judgement today.
- **Join requests live in memory.** `LinkRuntimeOwner` holds them, and they end with the invitation (10 minutes at
  most).
- `LinkCompatibility.CURRENT` is 6. Step 3 makes it 7.

### Shape: a small Raft, one entry in flight, committed entries only in `events/`

Raft with pre-vote and check-quorum, as "Timing" says, written in the `coordination` module and carried over Link
sessions. Load is about 0.2 entries per second for five lanes ("The log's size"), so the design takes these
simplifications (DERIVED):

1. **One entry in flight per project.** The leader stages one entry, waits until a quorum stores it, places it, and
   only then stages the next. At home round trips of 7 to 34 ms that allows about ten commits a second.
2. **`events/` holds committed entries only, on every computer.** The uncommitted tail lives beside it in
   `replication.tail/`, and the vote in `replication.vote`. Only the coordination server writes either, and it forces
   both to disk before answering. Agents, hooks, the run controller and the UI never read them. Nothing a local reader
   saw is ever taken back. The tail is normally empty or one entry. At a term start it holds an earlier entry and the
   new `TERM_STARTED`.
3. **The term is derived, not stored in entries.** A leader's first entry in its term is `TERM_STARTED{term}`, which it
   writes and signs itself. An entry's term is the term of the last `TERM_STARTED` at or before it. That is exactly
   Raft's per-entry term, because a leader appends `TERM_STARTED` before any entry of its term. A member's signed
   proposal therefore stays valid across a leader change.
4. **The hash chain replaces Raft's previous-index and previous-term check.** A previous digest pins the whole prefix,
   which is stronger.
5. **On a shared project, every append goes through the computer's coordination server, the leader's own agents
   included.** Agents' processes propose over the loopback route members already use. The server judges the entry
   when it leads and forwards it when it does not.
   - This gives one write path per computer. The fixed-role store problem goes away, because every process of a shared
     project is just a proposer.
   - The server's commit path takes a lock inside its own process, never `ProjectAppendLock`, which removes the
     deadlock. `ProjectAppendLock` keeps its member meaning: it lines up this computer's proposers.
   - An unshared project, one with no `replication.properties`, writes directly as today.

```text
agent process ─append─▶ loopback /replication ─▶ this computer's server
                                                   │ leading?  judge on committed state (projections + AppendAdmission)
                                                   │           stage in replication.tail, send REPLICATE to voters
                                                   │           quorum stored it ─▶ place in events/, ACCEPTED(seq)
                                                   └ not leading: forward to the leader's session, or UNREACHABLE
```

### The tie rule, and why voter changes need both quorums

The owner's rule is "more than half, or exactly half including the owner's computer". It is written once, as a
weighted majority: each voter counts 1, and the owner's computer counts one and a half. A set wins with more than half
the total weight. With an odd number of voters the extra half changes nothing. Every place that counts agreement calls
the same function: pre-votes, votes, commits and check-quorum. The denominator is the committed voter set, never the
reachable one: an absent voter counts (USER-STATED). (DERIVED)

**Changing one voter at a time is not safe with this rule** (Opus runner, DERIVED by counterexample).

- Example: the voters are the owner's computer A, B and C, and C is being removed. In the new set {A, B}, A alone is
  exactly half and holds the owner's computer, so A alone decides.
- A commits the removal with B's help. Then A is cut off, and keeps committing alone.
- B holds the removal but never heard that it was committed, so B still counts {A, B, C}. B and C are a majority of
  that set: B is elected and commits different entries. Two histories.
- The usual argument fails because {A}, a quorum of the new set, and {B, C}, a quorum of the old set, share no voter.

The fix is **joint agreement while a voter change is pending.** A computer whose log holds an uncommitted voter change
needs a quorum of the old set and of the new set, for votes and commits alike. Once the change is committed, the new
set alone counts. It still takes one entry per change. In the example, B needs A to win the new set, so B cannot be
elected. Two Raft rules stay with it: one voter change pending at a time, and a new leader commits its own
`TERM_STARTED` before it proposes any voter change (Ongaro, 2015).

This is reasoned, not model-checked. The first slice's simulation tests it, and the counterexample is a named
regression test.

**Two voters.** The other computer alone is never more than half. A two-computer project therefore cannot decide while
the owner's computer sleeps. This is the arithmetic of the tie rule, and step 2 behaves the same way. A third computer
that is always on removes it. (DERIVED)

### Who stands for election

All voters run the same election. The timeouts favour two kinds of computer:

- **The owner's computer** draws its election timeout from the low end of the range, so it usually wins while it is
  up (Fable runner).
- **A voter whose last code-remote check failed** uses a timeout twice as long. It still votes, and it leads only when
  nobody else stands (both runners). It checks with `CodeRemote.check` at start and every 10 minutes.
- **Leadership transfer is deferred.** A leader that cannot reach the code remote takes claims and reviews, and its
  merge pass answers `CODE_REMOTE_UNAVAILABLE`, as it does today.

### A proposal in flight across a leader change

A proposal's bytes name its position N+1 and the digest of N, and never change. The member resends the same bytes, as
in step 2. (DERIVED)

| What happened before the leader changed | What the resend gets |
| --- | --- |
| The old leader committed the entry | Every later leader holds it (Raft's leader completeness), so the answer is `ACCEPTED(N+1)` once the new leader's `TERM_STARTED` has committed. |
| The entry was staged and some voters stored it, but it was not committed | If the new leader holds it, the new leader commits it before its `TERM_STARTED`, and the answer is `ACCEPTED`. If not, position N+1 is the new `TERM_STARTED` and the answer is `HEAD_MOVED`. The old entry can never commit, so re-running the service cannot duplicate it. |
| Nobody stored the entry | The new leader treats it as a fresh proposal: `ACCEPTED` or `HEAD_MOVED`. |
| No leader yet (an election takes 10 to 20 s) | The relay answers `UNREACHABLE` at once. After the resends the agent gets `LEADER_OUTCOME_UNKNOWN` and keeps working inside its lane. The next pull settles what happened. |

A voter that is not the leader answers a proposal with `REFUSED NOT_LEADER`. The relay then tries once more on the
session of the leader it knows.

### Leader time: a derived term, and the leader's clock only in the leader's entries

"What this changes" said that entries gain a term and the leader's time. This design adds neither field. (DERIVED)

- The term is derived (see above).
- Invariant 7 holds as long as no rule decides anything from the clock of an entry a member wrote. Entries the leader
  writes (`TERM_STARTED`, `INTEGRATION_*`, and liveness changes later) already carry the leader's clock, because the
  leader is their writer.
- The one rule that read a writer's clock is the replay of old `PARTICIPANT_HEARTBEAT` entries. A shared project's
  leader refuses that type from a proposal. `ParticipantActivity.overlay` prefers the time recorded on its own
  computer whenever one exists, so a remote clock that runs fast cannot delay suspicion (Fable runner).
- A field on every entry would have changed the bytes members sign, voided every proposal in flight at a leader
  change, and changed every reader of the format, with no rule needing it.
- When member liveness becomes leader-observed (step 5), a new leader counts every participant as seen at its own
  `TERM_STARTED`. A leader change can then only lengthen a grace period, never shorten it.

### Membership in the log

New entry types, all decoded in a new package `coordination/domain/membership`:

| Entry | Payload | Valid when (checked by `MembershipProjection`) |
| --- | --- | --- |
| `PROJECT_SHARED` | owner computer and its key; owner public key; recovery public key; join policy `OWNER` | it is the first membership entry, and the owner key signs its statement |
| `TERM_STARTED` | term | the signer leads that term, and the term is higher than the last one (leader-only) |
| `JOIN_REQUESTED` | joiner node and key; the member that invited it | it is proposed by the inviting member's server while it holds the joiner's session |
| `JOIN_ANSWERED` | node; accepted or declined; approval | the request is open; the owner key signs it, or, under policy `ANY_MEMBER`, any member proposes it |
| `MEMBER_REMOVED` | node; owner signature | the owner key signs it (invariant 5) |
| `MEMBER_CARD` | a retained invitation link of the member's own listener | the signer is that member |
| `JOIN_POLICY_SET` | `OWNER` or `ANY_MEMBER`; owner signature | the owner key signs it |
| `OWNER_CHANGED` | new owner computer and its key; new owner public key; new recovery public key; signature | the owner key or the recovery key signs it. If the new owner's computer is not yet a member, this entry also admits it (see "Recovery") |

Deferred to the slices that need them: `VOTER_SET_CHANGED` (automatic picking, pin and exclude) and `VOTERS_RESET`.

Rules (DERIVED):

- **Owner authority is a signature inside the payload, never the entry's signer.** Every entry stays signed by the
  node that proposed it, as today. The owner key signs `SHA-256("SYNESIS-OWNER-1" ‖ project ‖ action ‖ subject ‖
  membership revision)`. Because the revision is in the statement, an old signature cannot be replayed after
  membership has moved.
- **Every member of a project of five or fewer is a voter** (USER-STATED). A sixth member joins without a vote: it
  pulls like any member and is simply not in the voter set. Automatic picking (below) is what later gives it a vote.
- **Invariant 4 is a projection rule** on every voter change: an even voter set contains the owner's computer.
- **`MembershipProjection` is the only authority.** It is one more stage of `PredictionEventStore` and appears on
  `ProjectionView`. It decides who may propose and pull (`LeaderEndpoint`), whom Link admits and redials
  (`returningPeers`), who votes, what the run controller counts as another computer of the project, and later the
  overlay.
- **`replication.properties` shrinks to `role=shared` and an optional `code.remote=`.** The `leader=` and `member=`
  lines go away. A step-2 file refuses with `REPLICATION_ROLE_RESHARE`, so a project shared with a step-2 build is
  invited again (prerelease formats, ADR-0088).
- **`ProjectionView.leaderEntry()` becomes "the signer leads now".** On the leader, that means the entry's signer is
  this node and this node leads. Leader-only types are accepted only from the current leader, so a deposed leader's
  merge pass cannot record anything.
- **The overlay view derives from the committed log** (ADR-0066/0080 change). Each computer builds its own
  `OverlayMembershipSnapshot` from the projection at each membership revision, signs it with its own node key, and
  uses the revision as the snapshot revision. Every computer derives the same set from the same log, so propagating
  snapshots between computers is retired, and `OverlayMembershipView`'s single-authority rule holds per computer. The
  overlay stays off in step 3. The wiring, and raising the 32-member limit, come with step 4.

### Sessions between voters

Elections need every voter to reach every other voter, but today each computer has one session, to its inviter. Each
member publishes a `MEMBER_CARD`, a retained invitation of its own listener. For every pair of members with no session,
the lower node ID dials the other's card with `resumeRetainedJoin`. The host admits the dial because its
`returningPeers` now names committed members. Five voters make ten sessions. (DERIVED; both runners)

- ASSUMED: `resumeRetainedJoin` accepts a card past its invitation's expiry. If it does not, cards need a form that
  does not expire.
- REQUIRES-MEASUREMENT: whether cards dial between two home networks. A pair that cannot connect directly is step 4,
  through a third member.
- `PULL` is answered by any member, since committed entries are the same everywhere.

### Owner key, node key and recovery key

| Key | Made | Kept | Signs |
| --- | --- | --- | --- |
| Node key | at install, unchanged | identity folder | every entry its computer proposes |
| Owner key | at the first project invitation | `<identity>/owner/<project>.key` on the owner's computer only (USER-STATED) | statements inside `PROJECT_SHARED`, `JOIN_ANSWERED` under the default policy, `MEMBER_REMOVED`, `JOIN_POLICY_SET` and `OWNER_CHANGED`; later voter pins and part declarations |
| Recovery key | with the owner key | nowhere: shown once as 24 words, to be written down | only `OWNER_CHANGED` and `VOTERS_RESET` |

- **Removing a member** is done on the owner's computer, which signs and proposes the entry. Elsewhere, `remove-member`
  refuses with `OWNER_KEY_NOT_HERE`. The owner's way to act from another computer is recovery. Deferred: a one-line
  removal token made on the owner's computer and used on another (Fable runner). It fits the same payload signature.
- **Recovery with a quorum alive.** On the new computer, `synesis project recover` takes the recovery words and a
  project link from any member. It proposes `OWNER_CHANGED`, signed by the recovery key. The entry replaces the owner
  key, the owner's computer and the recovery key, and admits the new computer as a voter. A join request that carries
  a valid recovery signature needs no other approval, since the recovery key is the owner's authority. Without this,
  an owner whose only computer is gone could never be approved back in.
- **Recovery or reset without a quorum** (owner decision 4) is a declared fork, because no normal entry can commit:
  - Example: a two-voter project {owner A, B} loses A. B alone is never a quorum, so not even a recovery entry can
    commit.
  - `VOTERS_RESET{voters, forkAfter}` is signed by the owner key or the recovery key, proposed on a surviving computer,
    and committed by a quorum of the **new** set alone.
  - Before proposing it, the command refuses unless the voters it drops have been unreachable for at least the lane
    grace (5 minutes). The person must type their names to confirm.
  - A computer of the old set that returns with entries after `forkAfter` stops with `LEADER_LOG_DIVERGED`, as today.
    `synesis log set-aside` then moves those entries to `events-set-aside/<term>/` and pulls again. It refuses while
    an agent session on that computer is active, because its readers would see entries disappear.
  - This is the only way invariant 1 can break, and it needs the owner's signature.

### Joining, with approval when the owner is away

```text
inviting member M                     joiner J                        whoever may approve
  link invite ─SLP1 link────────────▶ open-project-link
                                      follow: role=shared, empty log
  holds J's session ◀───────── dial ─
  proposes JOIN_REQUESTED{J, by M}    (committed: survives restarts and the 10-minute hold)
                                                                       sees it (UI, synesis link requests)
                                                                       JOIN_ANSWERED{J, accepted}, owner-signed
                                                                       by default
  J is now a member: M serves the held session, or admits J's redial
  J pulls the log, proposes MEMBER_CARD; the voters dial each other's cards
```

- **Default policy `OWNER`:** only the owner's computer can answer. The request waits in the log, any member's
  `synesis link requests` lists it, and J's computer redials M until it is a member.
- **`ANY_MEMBER`:** after the owner commits `JOIN_POLICY_SET{ANY_MEMBER}`, a `JOIN_ANSWERED` from any member is valid.
  It is the same path and one projection branch.
- The owner still sees who approved whom, because each answer is an entry signed by the computer that made it.

### Automatic voter picking

Picking matters only above five members. It is designed here and built after step 3's done criterion.

- **Reliability** (owner decision 2) is the share of the last 7 days a computer was connected to whoever led, counted
  in whole 10-minute windows.
  - It is read from committed connection changes that the leaders write with their own clock. Those entries come
    with step 5's leader-observed liveness, which picking therefore waits for.
  - Only computers whose latest code-remote check passed count. Each member reports a change in that result as an
    entry.
  - Ties go to the longer unbroken connection, then the node ID.
- **The swap rule.** When a non-voter has been more reliable than the least reliable unpinned voter, by at least 20
  percentage points, for 24 hours, the leader proposes one swap. There is at most one swap a day, and the joint
  agreement rule applies.
- **Owner control.** Pinned voters are never swapped out. Excluded members are never picked. The owner's computer is
  kept in while the count is even. Picking only proposes; the voters commit (invariant 3).

### Merges when the leader moves

- **Only the leader merges.** `MergeDriver` starts when its computer starts leading and stops when it stops. The pass
  guard reads the replica's state instead of `isReplicationMember()`.
- **Push before naming** (both runners). The merge commit is pushed to `refs/synesis/integration/<attemptId>` before
  `INTEGRATION_COMMIT_CREATED` is proposed. This extends the step-2 rule that a member pushes before it proposes an
  entry naming a commit.
- **A new leader aligns first.** Before its first pass, `CodeRemote.alignWithLog`:
  1. fetches `refs/synesis/*` and the control branch;
  2. fast-forwards the checkout to the commit named by the last committed `CONTROL_BRANCH_ADVANCED`;
  3. publishes the branch, with no force, so two leaders cannot rewrite it.
- **A started attempt from an earlier term** is completed by the existing `recoverAdvancedControlBranch` when its
  commit is held or can be fetched. Otherwise it ends as `INTERRUPTED_BY_LEADER_CHANGE`, and a later pass merges and
  tests again.
  - That reason does not count toward the "died twice" operator request.
  - `branchCannotHold` treats "unknown commit after a successful fetch" as "cannot hold", which removes the endless
    wait.

### Wire and files

- **SRL version 2** (`ReplicationCodec`), with the sender always the authenticated session's node, never a field:
  - `VOTE_REQUEST{project, term, preVote, lastSequence, lastDigest, lastTerm}`;
  - `VOTE{term, granted}`;
  - `REPLICATE{project, term, committedSequence, committedDigest, tail}`, which is also the 1-second heartbeat;
  - `REPLICATED{term, stored, committedSequence}`.

  `PROPOSE`, `PULL` and `NOTICE` keep their shape. `LinkCompatibility.CURRENT` goes to 7.
- **Local files in the coordination folder, never replicated:**
  - `replication.tail/` and `replication.vote`, both forced to disk;
  - `replication.state`, which is advisory (`term`, `leader`, own role, code-remote check), for status, the merge
    guard and the relay. It is never authority.
  - Journal lines `LEADER term= node=`, `ELECTION_STARTED`, `STEPPED_DOWN reason=`, `COMMITTED seq= term=`. The
    parser of `ReplicationJournal.connectedPeers` already skips unknown kinds.
- **Classes that change:**
  - `PredictionEventStore`: `admit` splits into `judge` and the existing place path; it gains the membership stage.
  - `ReplicationRole`: `memberOf` becomes `shared`.
  - `ReplicationSettings`: the new format.
  - `LeaderEndpoint`: members come from the projection, and the commit replaces `admit`.
  - `LeaderReplication`: serves only while leading.
  - `MemberReplication`: relays to whoever leads.
  - `ReplicationSide` (cli) is replaced by a `Replica` in `coordination/replication`, a shell around a pure
    `Consensus` core that a deterministic simulation drives.
  - `ProjectSharing`: proposes entries instead of writing member lines.
  - `LinkRuntimeOwner`: durable requests, and `returningPeers` from the projection.
  - `IntegrationOrchestrationService`, `CodeRemote` and `EventLogObserver`, as described above.

### The lab

Scripts go in `D:\SynesisAcceptance\step3\`, beside `link-vm`. Only disposable projects are used, and Synesis is used
normally in them.

**Lab A, the done criterion.** Five Podman containers from the Linux-gate image, on one Podman network with
`NET_ADMIN`:

- Each container is a computer with its own identity and coordination server. A sixth container serves the bare Git
  remote. Container 1 is the owner's computer.
- `lab-up.ps1 -Voters 3|4|5` joins every computer through the one link and the owner's approval.
- A load driver in the test jar appends a steady mix on every computer: overlapping claims, releases and snapshots, 2
  to 5 appends a second in total. It records every `ACCEPTED(seq, eventId)` it saw.
- `fault.ps1` provides `split` (iptables both ways), `heal`, `kill`, `pause`/`resume` (a sleeping laptop), `restart`,
  and `netem` (home delay and loss).
- `check.ps1` runs these checks:
  1. **Same history:** after healing and 60 seconds, every `events/` is byte-identical. Before that, each is a prefix
     of the longest.
  2. **No acknowledged entry lost.**
  3. **No double claims.**
  4. **At most one `TERM_STARTED` per term.**
  5. **No file in `events/` removed or replaced:** a watcher in each container records every change.

| Scenario | Fault | Expected |
| --- | --- | --- |
| Split, odd | 5 voters; {1,2} against {3,4,5} for 60 s | {3,4,5} keeps committing and {1,2} commits nothing; one history |
| Split, tie | 4 voters; {1,2} against {3,4}, owner in 1 | {1,2} decides by the tie rule; one history |
| Leader loss | kill the leader under load; restart it after 2 min | a new `TERM_STARTED` within 30 s; the old leader rejoins as follower; one history |
| Leader sleep | pause the leader 60 s | check-quorum deposes it; on waking, pre-vote stops it from deposing the new leader |
| Voter loss | kill a follower for 5 min | no election; it catches up by `PULL` |
| Voter change during a split | remove a voter while splitting the old and new majorities | at most one side commits (the counterexample as a regression) |
| Merge across a leader change | kill the leader between `INTEGRATION_COMMIT_CREATED` and `CONTROL_BRANCH_ADVANCED`, and after | the new leader aligns and completes or merges again; the remote's branch equals the log's last advanced commit |

Each scenario runs 20 times with random fault timing. A last run uses real agents (Sonnet 5.5, medium) on three
computers, with the leader killed mid-run, to show agents get through a leader change.

**Lab B, timing on real networks.** The Windows host, `synesis-peer-vm` and a third voter:

- The third voter is a Podman container if QUIC from the VM reaches it (REQUIRES-MEASUREMENT); otherwise a clone of
  the VM.
- Faults: unplugging the VM's virtual cable (`VBoxManage ... setlinkstate1 off`), Windows sleep, VM pause.
- The two-homes pair is used for the measurements across homes.

| Timer (from "Timing") | Measured by | Starting value holds if |
| --- | --- | --- |
| Heartbeat 1 s | Link round trip every second for 24 h, with an upload saturating the link 10 minutes an hour; p50 and p99 | p99 under 500 ms |
| Election timeout 10–20 s | elections in 72 idle hours, three voters, two homes | none a day; more than one raises the floor |
| Pre-vote, check-quorum | the leader-sleep scenario, 20 runs | no term change caused by the computer that woke |
| Recovery after leader loss | time from kill to the new `TERM_STARTED`, 50 kills | p95 under 30 s |
| Keepalive 15 s | each home router's UDP mapping lifetime, by halving the keepalive interval | lifetime above 30 s |
| Claim latency | proposal to `ACCEPTED`, LAN and two homes | p99 under 2 s at home |

Link's retained sessions now expire after 30 s, not 5 s (step 2). An election can therefore start before Link
notices a dead session. With pre-vote this is harmless: a voter that lost only its own session to a working leader
cannot win a pre-vote. (DERIVED; the 72-hour count confirms it.)

### Smallest first slice

1. **The consensus core and its simulation, with no IO.** `Consensus`, the quorum function, and the membership rules
   for voter changes. A seeded simulation of 3 to 5 voters covers loss, delay, reordering, splits, and crash and
   restart with the vote and tail kept on disk. It checks same history, no acknowledged entry lost, and one leader per
   term. The counterexample is a named regression.
2. **Membership in the log.** The entry types, `MembershipProjection`, owner and recovery keys made at the first
   invitation, `role=shared`, and every reader of the member list moved to the projection. This slice still runs with
   one leader.
3. **The replica over an in-process transport.**
   - `Replica`, the tail and vote files, SRL version 2, and the `judge`/place split.
   - Every shared append goes through loopback, and `ReplicationSide` is deleted.
   - Test: three project roots in one JVM, as in step 2's `LinkReplicationTest`.
4. **The voter mesh over Link.** `MEMBER_CARD`, card dialing, `returningPeers` from the projection, and
   compatibility 7. Test: three servers on loopback QUIC.
5. **Merges across a leader change.** Push before naming, `alignWithLog`, and `INTERRUPTED_BY_LEADER_CHANGE`.
6. **Lab A to the done criterion, then Lab B,** and the Timing table updated from the measurements.
7. **Owner overrides.** `OWNER_CHANGED` by recovery, `VOTERS_RESET`, `synesis log set-aside`, and
   `JOIN_POLICY_SET{ANY_MEMBER}`.

**Deferred:**
- automatic voter picking, which waits for step 5's leader-observed liveness;
- voter pin and exclude;
- removal tokens;
- leadership transfer;
- wiring the overlay snapshot (step 4);
- routes through a third member (step 4);
- members' activity reports and lane takeover (step 5);
- proposal batching;
- a log snapshot.

### Work in flight this must fit

- **"Keep an agent run alive while the other computer works."** `EventLogObserver.otherComputerConnected` reads the
  role file's members and leader, which slice 2 removes. It reads the projection's other members instead.
  `ReplicationJournal.connectedPeers` and its line format stay. The meaning widens from "my leader or my members" to
  "any other member", which is what a project with several voters means. Slice 2 lands after that work merges.
- **"Wake agents with hooks instead of polling."** It reacts to `events/`, which stays committed-only, so no agent is
  woken by an entry that is later dropped. `TERM_STARTED` and membership entries do land in `events/`. They are marked
  as recording no lane state, so a leader change does not wake every agent. Both changes add to
  `PredictionEventType`, a small merge.

### Rejected for step 3

- **A Raft library (Apache Ratis, SOFAJRaft).**
  - It brings its own log, so `events/` would become a second copy written after it.
  - It brings its own transport, which would need an adapter onto Link application streams.
  - It counts plain majorities, so the owner's tie rule cannot be expressed; giving the owner's computer a second
    voter identity would break "a member is a computer".
  - Ratis adds about 15 MB to the installer.
- **A term and the leader's time as fields of every entry.** The bytes members sign would change, so every proposal
  in flight at a leader change would be refused. Every reader of the format would change too. No rule needs either
  field.
- **Uncommitted entries in `events/`, removed on conflict.** Every local reader would have to cope with entries
  disappearing.
- **The owner's computer leads; a deputy takes over on an owner-signed handover.** The least code, but the owner's
  absence is exactly the case the owner wants covered.
- **Leases with the owner as preferred primary.** Home computers sleep and wake with a jumped clock, the reason
  "Timing" already rejects lease reads.
- **Single-voter changes without joint agreement.** Unsafe with the tie rule, as the counterexample shows.
- **Always an odd number of voters, dropping the tie rule.** It avoids the counterexample, but with two computers one
  could not vote, and the tie rule is the owner's.

## Alternatives rejected

- **The host is the server, for good.** Simple, but everything stops when that person leaves, and
  the owner rejected it.
- **Every member votes.** At hundreds of members, a majority of all members is rarely reachable, so
  no claim could be made most of the time. (DERIVED.)
- **Each member decides alone and conflicts are fixed later.** Two agents could hold the same file.
  That breaks the claim invariant every rule after it depends on.
- **Chat messages as signals.** A model can ignore or misread a message, and ADR-0093 keeps messages
  out of every decision.
- **One vote per person.** A person's computers do not share state, and Synesis has no notion of who
  owns a computer. (USER-STATED.)
- **A server run by Synesis.** Out of scope by the owner's decision of 2026-10-15 (ADR-0097). A
  member that is always on fills the same role without one.

## What this changes

- **ADR-0066 / ADR-0080:** the membership authority moves from one signing node to the committed
  log, with owner-only removal.
- **ADR-0077:** the topology policy gains long-range links above a size threshold.
- **ADR-0093:** unchanged. Messages travel in the log and still decide nothing.
- **Formats:** the handshake gains the compatibility number. Log entries keep their format: the term is derived
  from each leader's `TERM_STARTED` entry, and the leader's clock is in the entries the leader writes ("Step 3
  design", which replaced "entries gain a term and the leader's time"). Under ADR-0088, prerelease formats may change
  without migration.
- **`OverlayMembershipSnapshot` holds at most 32 members.** The limit rises with step 3.

## Consequences

- A project can lose any minority of its voters, and the owner, and keep working.
- A member that cannot reach the leader can still read, and work inside lanes it already holds.
  It cannot claim, finish or merge until it reaches a leader again.
- A claim takes a round trip to the leader and to a majority of voters.
  (REQUIRES-MEASUREMENT: claim latency across home networks.)
- Every member stores the whole log. (UNKNOWN: log size after months at hundreds of members; a
  snapshot that lets old entries be dropped may be needed.)
- Any member answering operator requests widens a gap already recorded: agents can get around the
  operator gate with `run_command`. With many members, that gap is open to all of them.
- A removed member keeps its copy. Removal ends future access, not past access.

## Build order

Each step works and is tested on its own. Later steps build on earlier ones.

1. **The compatibility number.**
   - **Built:** the number in the invitation and handshake, and the loud failure.
   - **Done when:** two builds with different numbers fail with both numbers shown, in the CLI and
     the browser.
2. **One fixed leader, one voter.** See "Step 2 design".
   - **First:** done. One coordination folder and incremental reads on 2026-10-15; heartbeats out
     of the log on 2026-10-16.
   - **Built:**
     - The owner's computer decides.
     - Members forward their requests to it over the overlay and receive committed entries.
     - The overlay delivers frames to the log instead of discarding them.
     - Code moves through a Git remote.
   - **Done when:** an agent on one computer and an agent on another claim, review and integrate one
     WorkGroup, with no two agents ever holding the same file.
3. **Voters, elections and membership in the log.** See "Step 3 design".
   - **Built:** terms, voter-set entries, the owner key and the recovery key, owner-only removal,
     and the tie rule.
   - **Done when:** a split, a leader's loss and a voter's loss in a three- to five-member lab each
     end with one history.
4. **Topology at scale.**
   - **Built:** long-range links, automatic answer-link exchange between members, and routes through
     a third member.
   - **Done when:** a 100+-member simulation converges after members join and leave, and hops and
     degree are measured.
5. **Parts and their merge queues.**
   - **Built:** part declarations, merge assignment, multi-part locking in order, and lane takeover
     with the side branch for late edits.
   - **Done when:** parallel merges in two parts, a lane touching both, and a takeover after a lost
     member all end with every member's tests passing on the merged result.
6. **Code over Link without a remote.**

## Fitness functions

- **Same history:** a test that compares every member's log prefix after each scenario. Any
  divergence fails it.
- **No double claims:** a property test over random splits and losses: no two uncancelled lanes ever
  hold overlapping paths in the committed log.
- **Leader time only:** an architecture test that no decision rule reads the system clock outside
  the leader path.
- **No cross-machine process checks:** liveness of a remote member reads no process ID.
- **Short appends:** the append path holds no lock across a Git or process call.

## Unresolved

Investigated on 2026-10-15. What is left:

- **Timing:**
  - The starting values above need home-to-home round trips, direct and relayed, idle and under
    upload.
  - They also need each router's mapping lifetime, spurious elections per day, and how long sleep
    pauses last. (REQUIRES-MEASUREMENT, step 3.)
- **Links:**
  - The simulation assumed random, independent failures and that every link is possible. Failures
    of a whole network, and pairs that cannot punch through, are not modelled.
  - Leaders should be placed on different networks. (REQUIRES-MEASUREMENT, step 4.)
- **Log:** a snapshot for projects that live for months. (UNKNOWN until heartbeats are out of the
  log and growth is measured again.)
- **Code without a remote:** the bundle size bound and how an interrupted transfer resumes. (Step 6.)
Fixed on 2026-10-15, found while investigating this ADR: **two coordination folders on one
computer.**
- Agents wrote `.synesis/coordination`. The coordination server, and with it the browser dashboard
  and the CLI's `task`, `ownership`, `prediction` and `coordination status` commands, used
  `.synesis/shared/coordination`.
- The server now defaults to the agents' folder.
- The dashboard's live stream now also reports events that other processes append.
- See `docs/agent/CURRENT.md`, 2026-10-15.
