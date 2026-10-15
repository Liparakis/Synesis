# ADR-0098 — One project across many computers

## Status

Accepted on 2026-10-16 by the repository owner. The decisions marked USER-STATED were made by the
owner on 2026-10-15. Each step under "Build order" is built and evidenced on its own.

Built so far (`docs/agent/CURRENT.md`, 2026-10-15 and 2026-10-16):

- **Step 1**, the compatibility number: `LinkCompatibility.CURRENT` = 4.
- **From step 2's "First"**, heartbeats out of the log: `ParticipantActivity`.
- **Found and fixed while writing this ADR:** one coordination folder and incremental log reads.

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
   it never installs.
4. **With an even number of voters, the owner's computer is one of them**, so the tie rule always
   has someone to apply to.
5. **Removal needs the owner's key.** A removal entry is valid only when signed by the owner key or
   by a key the recovery key has made the owner key. Invitations need any member's key and a
   committed entry.
6. **Decisions are made against the leader's committed state.** A member's validation is advisory;
   the leader runs the same rule again before it appends. A rejected request returns the existing
   refusal, such as `CLAIM_EPOCH_STALE`.
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
- **Formats:** log entries gain a term and the leader's time; the handshake gains the compatibility
  number. Under ADR-0088, prerelease formats may change without migration.
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
2. **One fixed leader, one voter.**
   - **First:** done. One coordination folder and incremental reads on 2026-10-15; heartbeats out
     of the log on 2026-10-16.
   - **Built:**
     - The owner's computer decides.
     - Members forward their requests to it over the overlay and receive committed entries.
     - The overlay delivers frames to the log instead of discarding them.
     - Code moves through a Git remote.
   - **Done when:** an agent on one computer and an agent on another claim, review and integrate one
     WorkGroup, with no two agents ever holding the same file.
3. **Voters, elections and membership in the log.**
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
