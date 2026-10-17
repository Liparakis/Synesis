# ADR-0098 — One project across many computers

## Premise (USER-STATED, 2026-10-17)

Synesis is a collaboration tool between people who work with AI agents: glue that sets a few rules for the agents so
they do not trample each other's work. It is not an always-on, high-availability system. People must be able to work
together when their computers are connected and on their own when they are not, and use the tool effectively either
way. Like Git, Synesis does not care when or where someone works; what matters is that everyone's work eventually
merges into one stable shared line (`main`), checked by review and tests.

Every design here is judged against this premise. Anything whose purpose is uptime, failover or agreement in real
time (majorities, elections, leader leases, takeover after minutes of silence, topology for hundreds of members) is
out of scope unless the premise needs it. This ADR was rewritten against the premise on 2026-10-17. What the rewrite
removed is listed, one line each, under "Not needed under the premise".

The owner also said, on 2026-10-17 (USER-STATED):

- When some computers disconnect, the others "just continue our work normally". No majority, and no waiting for
  computers that are away.
- "we could make it work like Git essentially ... at some point everyone needs to get merged, and that's the point we
  care about".
- "I'm not running a data center".

## Status

### For the owner to confirm

Each item gives the recommended answer. **Item 0 is moot:** nothing is decided by a majority any more, so a connected
group without one simply keeps working together.

1. **Claims are hints.** Example: Ana's PC and Bob's laptop are connected, and Ana's agent holds `web/app.js`. Bob's
   agent cannot claim it, as today. On the train, Bob's agent claims `web/app.js` too, because the laptop cannot see
   Ana's claim. Both lanes stand. When the two computers meet, both agents are told, and whichever reaches `main`
   second gets the other's change merged in. **Yes.**
2. **Each computer merges its own lanes onto `main`.** Example: Bob's agent finishes and gets a review on the train.
   Bob's laptop merges it, and pushes it to `main` when it is online again. Ana's PC does not merge Bob's work for
   him. **Yes.**
3. **When `main` moved, Synesis merges the new `main` in, as `git pull` does, and does not rewrite this computer's
   merges.** Example: Bob merged two lanes on the train. At home `main` has moved. Synesis adds one merge commit per
   lane on top of the new `main`, runs the tests, and pushes. A lane Bob started from his second merge still has its
   starting point in the history. **Yes.**
4. **A re-merge that Git does cleanly and that passes the tests is not reviewed again.** Example: Ana changed
   `server/api.py`, Bob changed `web/app.js`, and both were reviewed. Bob's re-merge needs no new review. The cost:
   if Ana renamed a function and Bob's new code still calls the old name somewhere the tests do not cover, that
   mistake reaches `main` unreviewed. Only a result an agent had to combine is reviewed again. **Yes.**
5. **Two different review verdicts on one snapshot are a dispute.** Example: before the train, Bob's laptop handed in a
   snapshot. On the train Bob's agent accepts it; at home Ana's agent rejects it. Whichever computer hears of both
   first, the snapshot is disputed and stays off `main`. A third agent reviews it again; if it cannot decide, an
   operator request asks a person. **Yes.**
6. **A computer that is away keeps its lanes.** Example: Bob's laptop holds `docs/` and Bob is away for two weeks.
   Nobody else continues that lane. If a goal then waits only on Bob's lanes, each other computer, after one day by its
   own clock, raises an operator request: "goal X waits only on lanes of Bob's laptop, last seen Monday; release
   them?". Only the owner releases them. **Yes, with one day.**
7. **Removing a computer ends its lanes and reservations.** Example: the owner removes Carol's old laptop. Its lanes and
   reservations end at once. Anything that laptop writes later is still copied, so every copy stays complete, but it
   counts for nothing. **Yes.**
8. **Work that arrives for a goal already finished becomes an operator request.** Example: the goal "add a login page"
   is done on `main`, then Bob's laptop comes back with one more lane for it. A person is asked: start a new goal for
   it, or set it aside. **Yes.**
9. **Reservations are optional and come late in the build order.** Example: before a trip Bob reserves `web/`; the
   computers that have seen it do not claim inside it. A two-minute Wi-Fi drop does not end it; Bob is asked to hand it
   in when he is back. It belongs to the laptop, not to Bob, so Bob's desktop cannot claim inside it either. **Yes.**
10. **Without a Git host, `main` lives on the owner's computer.** Example: there is no GitHub. While Ana's PC is off,
    Bob keeps working and merging on his laptop, and his merges reach `main` once Ana's PC is on again. **Yes.**
11. **Moving from step 2.** Example: after updating, Ana makes a new invitation and Bob joins again. The old log stays
    on disk as an archive, and no conversion is written (prerelease formats, ADR-0088). **Yes.**

Accepted on 2026-10-16 by the repository owner. Rewritten on 2026-10-17 against the premise, as a design: no code
exists for steps 3 to 8 below. ADR-0100 is folded into this model; it now describes how work from separate copies meets
on `main`. ADR-0099 (agents are woken) is unchanged in substance.

Built so far (`docs/agent/CURRENT.md`, 2026-10-15 to 2026-10-17):

- **Step 1**, the compatibility number: `LinkCompatibility.CURRENT` is 6 (VERIFIED: `LinkCompatibility.java:30`).
- **Step 2**, one fixed leader: DONE on 2026-10-16 (run 7). Writers and reviewers on both computers, the WorkGroup
  COMPLETED, both runs COMPLETED.
- **Two homes**, 2026-10-17 (remaster56): four agents completed a WorkGroup across two home networks, joined by the one
  link and the owner's approval.
- **ADR-0099:** agents end their turn on WAIT and are woken by hooks or the run controller, across computers too.
- **Open:** the overnight run of 2026-10-17 stalled because waiting now ends the turn and the session lease reads that
  as absence (`SessionLeasePolicy.java:16-17`: stale after 2 min, grace 5 min). See "Build order", prerequisite.

Mode: evolution of the existing system. Claims are marked VERIFIED (file:line on this branch), USER-STATED, DERIVED,
ASSUMED or REQUIRES-MEASUREMENT.

## Context

### What step 2 built (VERIFIED)

- **One decider.** A member's process builds its entry against its copy of the leader's log, then sends it to the
  leader and places it only once the leader accepted it (`PredictionEventStore.java:250-259`). The leader judges it with
  the same projections (`PredictionEventStore.java:438-472`).
- **Who may send.** The leader refuses an entry not signed by the session's node, from a non-member, or of a type only
  the leader writes (`LeaderEndpoint.java:80-88`).
- **A member never merges.** The merge pass returns at once on a member
  (`IntegrationOrchestrationService.java:480-484`). The leader merges every computer's lanes and pushes the control
  branch, with no force (`CodeRemote.java:238-255`, push at `:248`).
- **A merge does not hold the log.** The cherry-picks and tests run outside the lock, and the result is recorded only if
  the control head is still the one the pass started from (`IntegrationOrchestrationService.java:867-869`).
- **The leader may act for anyone.** Attribution is skipped for the leader's own entries
  (`ParticipantAttribution.java:60`, `PredictionEventStore.java:699-701`).
- **The role is a local file**, `replication.properties`, with `role=leader|member`, the leader or the members, and the
  code remote (`ReplicationSettings.java:27-31`). No file means "leader" (`ReplicationRole.java:109-125`).

### What it cannot do under the premise

- Without the leader, a member's agents cannot claim, hand in or merge: an append fails with `LEADER_UNREACHABLE` or
  `LEADER_OUTCOME_UNKNOWN` (VERIFIED: `PredictionEventStore.java:402-406`).
- Two friends connected to each other while the owner's computer is off do not coordinate at all.
- The designs for steps 3 to 6 fixed this with voting, elections and leader leases. The premise rules those out.

### What the real runs taught

- Coordination across computers works when both are up: claims, review in both directions, merges, two home networks
  (run 7; "Two homes, one project").
- The stalls were never about agreement between computers. They were about agents waiting: sleeping instead of ending
  the turn ("Two homes, bigger goal"), and then being judged absent while they waited (overnight run). Both are about a
  computer judging an agent that is simply waiting.

## Decisions made by the owner (USER-STATED, 2026-10-15 and 2026-10-16)

Kept as stated unless marked.

1. **Links.** Not everyone needs to connect to everyone; a third member can carry traffic; members never paste links to
   each other. *Under the premise:* a handful of computers, each connecting to the others it can reach ("Links").
2. **Membership.** A member is a computer. Any member may invite; the owner approves each join; only the owner removes.
   The owner holds a recovery key. A removed member keeps what it already copied. Nothing is hidden from members.
3. **Deciding** by a voter majority with a tie rule. *Superseded by the premise:* nothing is decided by voting.
4. **Scale**, hundreds of members. *Deferred:* the premise is a handful of people. Scale is later, if ever.
5. **A member lost in the middle of a lane.** *Narrowed:* a computer's own agent that died is handled by that computer,
   as today. A computer that is away keeps its lanes (item 6).
6. **Code** travels through a Git remote, or over Link when none is configured.
7. **Merging by declared parts**, each with its own queue. *Deferred:* `main` orders merges by itself. Parts are a
   scale feature.
8. **Signals** are log entries, not chat messages. A message decides nothing (ADR-0093).
9. **Operator requests:** any member may answer one.
10. **Versions:** builds whose compatibility number differs refuse to connect, loudly.
11. **Trust** (2026-10-16): members are people the owner trusts. Guards stop accidents, not malice. Every entry shows
    who made it, and a silent edit is visible on every copy.
12. **Joining** (2026-10-16): one link; the owner accepts each join. Built ("Joining, revised").
13. **Joins while the owner is away** (2026-10-17): a project setting the owner controls. By default a join waits for
    the owner. The other choice lets any member approve.

## Step 2 design (built, 2026-10-16)

Kept as the record of what is built. Source and protocol documents cite its headings. "What happens to step 2" below
says what of it changes.

The leader is fixed: the owner's computer, **A**. Members, such as **B**, run their agents locally. B's services run
unchanged against B's copy of A's log; the entry they would write is signed by B and sent to A, which places it only if
its log is still at the position B named and its projections accept it. Otherwise B pulls and its service runs again.
An entry acting for a participant is accepted only from the node that introduced that participant.

### Merging runs on A, outside the log lock

Only A merges. A short-delayed pass also runs after any entry from a member, so A needs no agent of its own to merge
B's work (`MergeDriver`). The cherry-pick and the test gate run without the append lock; the lock is taken only to
record the result, and only if the control head and the lanes' entries are still what the pass started from. Otherwise
the pass starts again (invariant 8). B pushes `refs/synesis/*` before proposing an entry that names a commit; A fetches
before deciding anything that reads it, and pushes the control branch after each advance.

### Joining, revised (owner, 2026-10-16)

1. **The owner creates a project invitation:** one link (`synesis link invite`, or the browser UI). It carries the
   signed, single-use connection invitation (`SLB3`), the project identity, and the owner's code remote URL if there is
   one.
2. **The friend opens it** (a click, the UI's paste box, or `synesis link join`). Synesis finds the project by its
   identity; a chosen folder must carry the same identity; if the project is not on this computer, the friend is told
   the URL to clone.
3. **The friend's computer follows the inviter** with no node IDs typed.
4. **The owner approves each join** (USER-STATED). The session is authenticated and the identity checked, but nothing
   is served until the owner accepts (`synesis link requests`, `synesis link accept <node>`, or the UI).
5. **Across two networks,** the answer link of ADR-0097 goes from the friend back to the owner.

`project share`, `add-member`, `remove-member` and `follow` stay for scripts and removal.

## Decision

### The shape: Git

Every computer always works on its own copy. Its agents claim, write, review and merge locally, connected or not.
Connected computers exchange their work every few seconds. Computers that are not connected exchange it later. There is
one mechanism, not an online mode and an offline mode. (DERIVED from the premise.)

| Git | Synesis |
| --- | --- |
| a clone | a computer's copy of the project: its checkout and its coordination log |
| a commit | a log entry, signed by the computer that wrote it and chained to its previous entry |
| fetch | pulling the other computers' logs |
| `main` on the remote | `main` on the Git remote, or on the owner's computer when there is none |
| push, refused unless it fast-forwards | merging onto `main` |
| pull, merge, push again | re-merging when `main` moved |
| a merge conflict | a combine lane for an agent |

```text
 PC (owner)   own log ──●──●──●───────●──●──▶      exchanged when connected, both ways
                         \        \  /
 VM (friend)  own log ──●──●──●──●──●──●──▶       (the VM keeps writing while cut off)

 main (Git remote)  ──M1──────M2──M3──▶            only fast-forwards; each Mi reviewed and tested
```

### The rules that stay hard

Only rules that prevent harm that cannot be undone:

1. **No work is silently lost.** No entry is ever removed or replaced. No snapshot commit becomes unreachable. A
   combine keeps both versions until an agent has made one change from them.
2. **Every entry is signed and attributable.** A computer writes only its own log, and acts only for participants it
   introduced. No computer acts for another's agents, the owner's computer included (from step 4; until then the
   owner's computer merges every lane and keeps today's exemption).
3. **No self-approval** where review is required (`REVIEW_SELF_NOT_ALLOWED`: `ReviewAdmissionRules.java:103`,
   `AppendAdmission.java:168`, `:351`).
4. **History is tamper-evident.** Each log is a hash chain signed by its writer, and every computer holds a copy of
   every log, so an edited or dropped entry shows as a mismatch against the other copies.
5. **`main` only fast-forwards,** and only with work that was reviewed where review is required and that passed the
   project's validation command.

Everything else is guidance that helps agents avoid each other: claims, reservations, messages, review requests. A
computer enforces it against what it knows. Nothing waits for a computer that is away.

### Each computer writes only its own log

- **Where.** A computer appends its own entries to `events/`, as a single computer does today. The copies of the other
  computers' logs go in `peers/<node>/events/`, in the same file format, verified by the same chain check. (DERIVED)
- **No new field.** Entries keep today's format. A computer applies arriving entries in the order they arrive, each log
  in its own order. Nothing waits for another log to catch up.
- **Order.** A computer keeps the order in which it applied entries in a local file, so a store opened later replays the
  same order. Different computers may apply entries from different logs in different orders, and an answer may arrive
  before what it answers (a grant before its request, through a third computer). Every rule must give the same final
  answer in any such order: an entry that names something not yet seen is kept, and takes effect when that arrives (a
  fitness function).
- **Considered and dropped: each entry naming the heads of the other logs it had seen** (like a Git commit's parents),
  so that entries could be applied in causal order. With rules that tolerate any order it decides nothing, and holding
  back entries until their heads arrive can stall a copy. The per-log hash chain already gives tamper evidence.
- **Appends are local.** No append waits on another computer. `LEADER_UNREACHABLE` and `LEADER_OUTCOME_UNKNOWN`
  disappear, and with them every "proposal in doubt".
- **Exchange.** A computer whose head moved sends a notice to the computers it is connected to. They pull what follows
  their copy, in pages, and also pull every 15 s (today's `MemberReplication.PULL_INTERVAL_SECONDS`,
  `MemberReplication.java:49`). Any computer serves every log it holds, because each entry is signed by its writer. A
  friend's entries reach you through a third computer with no relay protocol.
- **Local rules, and what arrives.** A local append is still judged by the projections and `AppendAdmission`, against
  everything this computer has seen, as today. An entry from another computer is a signed fact and must always apply.
  Today it would not: each projection's `validate` and `apply` run in one fold (`PredictionEventStore.java:139-151`),
  and the projections throw while applying, for example `OVERLAPPING_CLAIM` in `CollaborationProjection.announce`
  (`CollaborationProjection.java:555-557`). One such throw on an arriving entry would stop that log's copy for good,
  and the copies would never agree. So in step 3 every throw an arriving entry can reach becomes one of two outcomes:
  - **both stand**, recorded so agents are told (two overlapping claims);
  - **counts for nothing, reported loudly** (an entry acting for another computer's participant, a self-review).

  ADR-0100's table lists the cases. Where two computers write about one thing, the projection keeps the set of what
  was written, so the order does not matter (below).
- **Where two entries meet on one thing** (VERIFIED order-dependent today, fixed in step 3):
  - **Review verdicts.** A second verdict throws `REVIEW_DECISION_ALREADY_RECORDED`
    (`TaskCompletionProjection.java:628-630`), so a computer that saw ACCEPTED first and one that saw REJECTED first
    would end in opposite states. A snapshot's review state becomes the set of verdicts: two different verdicts are
    `REVIEW_DISPUTED` in either order, and the merge pass skips a disputed snapshot.
  - **Operator requests.** A second raise for one condition throws `OPERATOR_REQUEST_ALREADY_OPEN`
    (`OperatorRequestProjection.java:95`). Two raises for the same condition count as one request; two different
    answers are a dispute.
  - **WorkGroup completion.** `LaneCompletionService` writes `WORK_GROUP_STATUS_CHANGED` COMPLETED
    (`LaneCompletionService.java:245-251`), and from step 4 two computers may both write it. A second COMPLETED for the
    same group counts as the first. Completion itself is read from Git ("Getting onto `main`").

### Claims are hints

- A computer refuses a claim that overlaps an active claim it knows about (`OVERLAPPING_CLAIM`,
  `CollaborationProjection.java:557`). Connected computers know each other's claims within seconds, so connected agents
  avoid overlap up front, as the owner asked (ADR-0100, answer 4).
- Overlap is possible only between claims made while their computers could not see each other. Both lanes stand. Each
  agent's next answer says which lane on which computer overlaps it, so they can message each other (ADR-0093).
- The overlap is settled where it matters, at `main`: the second lane to arrive is re-merged, and if Git cannot combine
  it, an agent does, in a combine lane (ADR-0100).

### Getting onto `main`

Each computer's merge pass integrates its own reviewed lanes into its own `main`, as the leader's pass does today:
cherry-pick, the validation command, record. Then:

1. **Push** `main` with no force, as `publishControlBranch` already does (`CodeRemote.java:248`). The remote refuses it
   unless it fast-forwards. That refusal is the only ordering `main` needs. No computer decides it.
2. **If refused:** fetch `main`, and merge it in with one merge commit per local integration, oldest first. Each merge
   has that integration as a parent, so a lane based on it keeps its base (today's rule, `STALE_BASE`,
   `IntegrationOrchestrationService.java:346-347`, accepts it unchanged). Run the validation command on the result,
   then push again.
3. **If Git cannot merge,** the path a cherry-pick conflict takes today runs: `INTEGRATION_CONFLICTED` and
   `REPAIR_REQUIRED` (`IntegrationOrchestrationService.java:920-925`). Its repair lane is the combine lane: an agent
   other than the author combines the two versions, and the result is reviewed (ADR-0100). Merging one integration at a time keeps each
   conflict inside one lane, which the existing one-source repair lane needs (`RepairLaneTransfer.java:53-61`).
4. **If the validation command fails** on the merged result, today's validation-failure path runs.
5. **A person** is needed only when an agent says it cannot combine or cannot decide (an operator request).

Before a lane starts, the computer updates its `main` from the shared one, as `followLeader` does today
(`CodeRemote.java:209-228`).

A lane is on `main` when its integration commit is an ancestor of `main`. Every computer can read that from Git, so no
entry has to announce it. A goal is complete when every lane it names is on `main`.

The rule is the same whether the push happens a second after the merge or a week later.

**Two pushers in turn.** If the validation command takes ten minutes, two computers can keep refusing each other: each
re-merges and tests while the other pushes. Every refusal means another computer's push succeeded, so `main` always
moves forward, and one computer waits at most one round per other computer pushing at the same moment. With a handful
of computers that is bounded. (DERIVED.) If it shows up in practice, a computer waits a random few seconds before
retrying. (REQUIRES-MEASUREMENT.)

### Computers that are away

- A computer that cannot be reached keeps its lanes, held, until it is back. Nobody continues them automatically. The
  owner can release them (`synesis reserve break --lanes-of <node>`, ADR-0100).
- No computer judges another computer's agents present or absent. A computer judges only its own agents, with its own
  clock, as `SessionLeasePolicy` does today.
- What "away" means is only what a person sees: the time of the last entry from that computer.
- **A goal must not wait in silence.** When a goal waits only on lanes held by computers this computer has not heard
  from for one day by its own clock, it raises an operator request: "goal X waits only on lanes of node Y, last seen
  T; release them?" (item 6). The owner answers by releasing them or not. No decision reads another computer's clock:
  "last seen" is when this computer last received an entry from Y.

### Membership and keys

Membership moves into the owner's log (step 5). The owner key lives on one computer, so the owner's entries form one
line and never conflict.

| Entry | Valid when |
| --- | --- |
| `PROJECT_SHARED` | first membership entry; carries the owner public key and the recovery public key |
| `JOIN_REQUESTED` | written by the inviting member while it holds the joiner's session; durable, unlike today's in-memory requests |
| `JOIN_ANSWERED` | signed by the owner key, or by any member under `JOIN_POLICY_SET{ANY_MEMBER}` (decision 13) |
| `MEMBER_REMOVED` | signed by the owner key; names the head of the removed computer's log that the owner had seen. Later entries of that log are still copied, so no copy is ever left incomplete, and count for nothing |
| `MEMBER_CARD` | a retained invitation of the member's own listener, so other members can dial it |
| `OWNER_CHANGED` | signed by the owner key or the recovery key; a recovery-signed change wins over an old-key statement made at the same point |

- The owner key is stored on the owner's computer only, never copied (USER-STATED, 2026-10-17). From another
  computer the owner uses the recovery key once, which makes that computer the owner's computer.
- An owner statement signs the project, the action, the subject and the membership revision, so it cannot be replayed
  later.
- The recovery key is shown once as 24 words and stored nowhere.
- Removal also ends the removed computer's lanes and reservations (item 7).

### Links

- A handful of computers. Each keeps a session to every other member it can reach, dialing the other's `MEMBER_CARD`.
  Today replication runs on control-plane sessions, one per computer, to its inviter. The overlay, which links everyone
  up to three members (`OverlayTopologyPolicy.java:28`), is off in production and is not needed for this.
- Two members that cannot dial each other still get each other's entries through any computer both reach.
- Topology for more members is later, if ever ("Not needed under the premise").

### Code

- **With a Git remote,** as step 2 built: a computer pushes its snapshot and prepared refs before it writes an entry
  that names them, and a reviewer fetches before it reviews.
- **Without one,** `main` lives on the owner's computer, and commits move over Link as thin Git bundles ("Code over
  Link", step 8). A computer that wants `main` advanced asks the owner's computer, which fetches the commit from it,
  checks that it fast-forwards and that its tree matches, and moves `main`. Nothing is ever pushed into a member.

### Code over Link (step 8, condensed from the 2026-10-17 design)

- A second `CodeMover` next to `CodeRemote` (VERIFIED operations: `pushRefs` `CodeRemote.java:131`, `ensureCommit`
  `:176`, `followLeader` `:209`, `publishControlBranch` `:238`).
- A member serves only commits that an entry in its copy names, from `refs/synesis/` and `main`. A peer never chooses a
  Git command.
- Pieces of at most 200 MB per transfer, resumable (USER-STATED, ADR-0100 answer 8): planned at 150 MB, 256 KiB chunks
  each with a SHA-256, resumed from the first missing chunk. Prepared pieces are kept 24 h for resuming. These numbers
  are engineering starting values, not owner decisions (REQUIRES-MEASUREMENT).
- Joining clones from the invitation's URL when it can, otherwise fetches over Link once the owner has accepted the
  join.
- Each piece is checked: its hash, `git bundle verify`, then a fetch with object checking into a fixed ref. The final
  commit and tree must equal the ones wanted.
- Today Git runs with 10 s locally, 30 s against a remote and 64 KiB of output (`GitProcessRunner.java:21`, `:28`,
  `:32`). Bundle commands get a 10-minute bound and never run under a lock.
- Rejected: streaming `git upload-pack`, a Git remote helper, one hash per piece.

### Timing

| Timer | Value | Why |
| --- | --- | --- |
| Pull | 15 s | catch-up, and the keepalive of a home router's UDP mapping (RFC 9308 §3.2 allows a 30 s drop) |
| Link retained sessions | 5 / 15 / 30 s | set by the real runs of 2026-10-16 |
| A computer's own agents | stale 2 min, grace 5 min | `SessionLeasePolicy.java:16-17`; judged only by their own computer |

No timer decides anything about another computer.

### Versions

Each build carries a compatibility number, raised only when a format sent between computers changes. It is in the
invitation and the Link handshake. On a difference both sides fail with a message naming both numbers and which side is
older. Built in step 1.

### The log's size

Heartbeats left the log on 2026-10-16 (`ParticipantActivity`), and logs are read incrementally since 2026-10-15
(`VerifiedEventPrefixes`; one open plus one append went from about 1 s to 14-19 ms on a 720-entry log). Every computer
holds every log. A snapshot that lets old entries go is left for later (UNKNOWN until measured over months).

### Step 2's invariants, as built

Source comments cite these numbers, so they keep them.

1. One history; a member never appends on its own. *Replaced in step 3* by "a computer appends only to its own log".
2. One leader per term. *Not needed.*
3. The voter set changes only by an entry the voters commit. *Not needed.*
4. An even voter set holds the owner's computer. *Not needed.*
5. Removal needs the owner's key. *Stays* ("Membership and keys").
6. Decisions are made against the leader's committed state, and every authority rule lives in a projection. *The
   projection half stays.* The leader half is replaced in step 3 by local judgement plus the rules for arriving entries.
7. Time used in a decision is the decider's. *Stays as:* no decision reads another computer's clock.
8. A merge never holds the log. *Stays.*
9. Parts are locked in a fixed order. *Deferred* with parts.
10. Different compatibility numbers never form a session. *Stays.*

### What happens to step 2

Step 2 is the working baseline until step 3 ships, and most of it stays after.

- **Stays:** Link sessions, redial and the replay guard; the one-link join and the owner's approval; `PULL` and `NOTICE`
  with chain checks and paging; the 15 s pull; the loopback route from agents' processes to their computer's server;
  code through a Git remote with push before naming; the merge outside the lock with its head check; the projection
  rules of the 2026-10-16 audit; the compatibility number.
- **Removed in step 3:** the member's proposal path (`PredictionEventStore.java:250-259`, `LeaderEndpoint.accept`).
  Every computer appends as the leader does today.
- **Removed in step 4:** the member's skip of the merge pass (`IntegrationOrchestrationService.java:480-484`), and the
  leader's exemption from attribution (`ParticipantAttribution.java:60`). Leader-only types
  (`PredictionEventType.java:396`) become "written only by the computer whose lane they are about". Until then the
  owner's computer merges every lane, and its merge pass writes entries for other computers' lanes (the release after
  an integration), so it keeps both.
- **Simplified in step 5:** `replication.properties` keeps only `role=shared` and `code.remote=`. Members come from the
  owner's log.
- **The owner's computer** keeps three duties: the owner key, approving joins, and holding `main` when there is no Git
  host. It is otherwise a member like the others. It is not a coordinator.
- **Migration** (item 11): a project shared with a step-2 build is shared again after the update, and its old log is
  kept as an archive. A project on one computer changes nothing: its `events/` is already its own log.

### Not needed under the premise

Each was designed on 2026-10-16 or 2026-10-17 and is dropped, with the reason.

- **Majority voting and voter sets:** no write waits on agreement; each computer writes its own log.
- **Elections and terms, `TERM_STARTED`:** nobody leads, so nobody is elected.
- **The tie rule** (the owner's computer counts one and a half): nothing is counted.
- **Joint agreement during voter changes**, and its counterexample: there are no voters.
- **Reliability-based voter picking**, `CONNECTION_CHANGED`, the 7-day score and the swap rule: no voters to pick.
- **Pre-vote, check-quorum, election timeouts, leader leases:** no leader.
- **`replication.tail/`, `replication.vote`, SRL version 2, a Raft library:** no consensus.
- **`VOTERS_RESET` and `synesis log rejoin`:** no voter set can be lost.
- **Working apart after 90 s, `synesis offline`, branches that return** (ADR-0100's first form): every computer works
  on its own copy all the time.
- **Proposals in doubt** and `LEADER_OUTCOME_UNKNOWN`: appends are local.
- **Taking over a lost computer's lane after minutes of silence:** an away computer is probably still working on it.
- **Cross-computer liveness judged by a leader:** no computer needs another's agents judged absent.
- **A coordinator or merger for other computers' lanes:** two computers could then merge one lane. Its own computer
  merges it when back, which the premise accepts.
- **1,024 members, hashed rings, degree 8, snapshot version 2, signed voter messages through a third member:** a
  handful of people. Later, if a project ever needs it.
- **Courier hole punching between members:** the answer link already makes the first connection across two homes, and
  entries flow through any computer both reach. Later, if pairs that cannot connect turn out common.
- **Parts, their queues and per-part mergers:** `main` orders merges by itself. A scale feature, later.

## Alternatives rejected

- **Keep step 2's leader and let a computer that cannot reach it branch and return** (ADR-0100's first form). Two modes,
  and friends connected without the owner still do not coordinate.
- **Step 2 plus voting** (the step 3 design). Needs majorities, elections and leases, and a connected group without a
  majority still stops coordinating. The premise rules it out.
- **One shared log with entries taken back on conflict.** Every local reader would see entries disappear.
- **A server run by Synesis.** Out of scope (ADR-0097). A Git host already gives `main` a home.
- **Chat messages as signals.** A model can ignore or misread a message (ADR-0093).

## Consequences

- No computer ever stops for lack of a connection, and no write waits on another computer.
- Connected agents still avoid overlap up front. Overlap between work done apart costs a re-merge, and sometimes a
  combine lane.
- `main` is the one place that must be single. With a Git host it is always reachable. Without one, merges onto `main`
  wait for the owner's computer.
- Nobody can block `main` by being away. Nobody can take over an away computer's lanes either, except the owner.
- Without a central gate, a buggy build could push work onto `main` that was not reviewed. Every computer audits `main`
  when it fetches it (a fitness function) and reports such a commit loudly. A Git host's branch protection can add
  prevention; Synesis does not configure it.
- Each computer stores every log.
- Agents can still get around the operator gate with `run_command` (recorded, deferred).
- A removed member keeps its copy. Removal ends future access, not past access.

## Build order

Each step is small, works on its own, and ends with a real run on this PC and `synesis-peer-vm` (scripts in
`D:\SynesisAcceptance\`, disposable projects, Synesis used normally in them). ADR-0100's lab scenarios S1 to S5 are
the shared runs. Compatibility goes up by one in each step that changes a format sent between computers.

**Prerequisite, not a step of this ADR: waiting is not absence.** The overnight stall (`docs/agent/CURRENT.md`,
2026-10-17, causes 1 to 3) is fixed first, under ADR-0099. Every run below needs agents that wait for a review without
losing their lanes.

1. **The compatibility number.** DONE.
2. **One fixed leader.** DONE 2026-10-16 (run 7). The baseline.
3. **Each computer writes its own log.** Two computers.
   - **Built:** own `events/` plus `peers/<node>/events/`; the local applied order; notice and pull per log; the
     proposal path removed. Every projection throw an arriving entry can reach becomes "both stand" or "counts for
     nothing, reported" (ADR-0100's table; the sites are listed under "Unresolved"). Review verdicts kept as a set, with
     `REVIEW_DISPUTED` in either order and disputed snapshots skipped by the merge pass; duplicate operator raises and
     duplicate COMPLETED counted once; an answer that arrives before what it answers kept until that arrives. Merging
     still happens on the owner's computer, reading every log, with its exemption from attribution. Members still come
     from the role file.
   - **Done when:** S1 with the VM's cable cut for 20 minutes mid-run, plus one overlapping claim and one review given
     on both sides during the cut. Both sides keep claiming, publishing and reviewing, and no agent ever sees
     `LEADER_UNREACHABLE`. Within one pull after reconnecting, both computers hold byte-identical copies of both logs,
     and both show the overlap and the disputed review the same way. The owner's computer merges the VM's other
     reviewed lanes, and the disputed snapshot stays off `main`. Compatibility 7.
4. **Each computer merges its own lanes onto `main`.**
   - **Built:** the merge pass on every computer; push with no force; on refusal fetch, one merge commit per local
     integration, the validation command, push again; the combine lane through the repair path; "on `main`" read from
     Git; the update from `main` before a lane starts; attribution with no exemption, and integration entries written
     only by the lane's own computer.
   - **Done when:** S1 again, now with both sides merging locally during the cut. After reconnecting, the second pusher
     re-merges with no person, the tests pass, and `main` is equal on the remote, the PC and the VM. S2 (one file both
     sides change) ends with a combine lane opened for an agent, reviewed and merged. S5 (the VM's server killed during
     a re-merge) ends the same as S1.
5. **Membership in the owner's log, and a third computer.**
   - **Built:** the membership entries above, durable join requests, the owner and recovery keys, `MEMBER_CARD` and
     dialing every member, `role=shared`.
   - **Done when:** a third computer (a Podman container on this PC if the VM reaches it, otherwise a clone of the VM)
     joins by the one link and the owner accepts on the PC. Then Synesis on the PC is stopped; the PC itself stays on,
     because the container and the Git remote may run on it. The VM and the third computer keep working together: each
     sees the other's claims, and an overlapping claim is refused. With Synesis on the PC started again, all of it
     reaches `main`. The owner removes the third computer: its lanes end, and an entry it writes afterwards is copied
     and counts for nothing. Compatibility 8.
6. **Disputes and away computers.**
   - **Built:** settling a dispute: re-review by a third agent, then an operator request; two answers to one operator
     request; work for a finished goal; held lanes of an away computer and the owner's release; the one-day operator
     request for a goal that waits only on an away computer.
   - **Done when:** S3 (a review dispute) ends with a re-review or an operator request. A lane of the VM held through a
     cut raises the operator request (with the interval shortened for the run), is released by the owner, and is
     continued on the PC.
7. **Reservations** (optional).
   - **Built:** `RESERVATION_MADE` and `RESERVATION_ENDED`, `synesis reserve`, `reserve end`, `reserve break`.
   - **Done when:** S4: a reservation on the VM refuses the PC's claim in `web/` while both are connected, and stays
     until handed in.
8. **Code over Link without a Git host.**
   - **Built:** "Code over Link" above, `main` on the owner's computer, joining over Link.
   - **Done when:** S1 with no Git host ends with `main` equal on both computers, and a 450 MB history joins over Link
     in three or more pieces through an interruption.

The two-homes run with the friend's PC is repeated after step 4 as a check, not a done criterion.

## Fitness functions

- **Nothing taken back:** no code path deletes or renames a file in `events/`, in `peers/`, or under `refs/synesis/`.
- **Every copy is the writer's:** a log copied through any computer is byte-identical to the writer's, and every entry
  in `peers/<node>/events/` is signed by that node.
- **Arriving entries always apply:** for every entry type, applying an entry from another computer's log never throws.
- **Order does not decide:** a property test applies the same logs in random arrival orders, each log in its own order,
  answers before requests included. Every decision is the same: which claims overlap, which reviews count, disputes,
  operator requests, WorkGroup status, membership.
- **Own participants only:** no entry in one computer's log acts for a participant another computer introduced.
- **`main` only fast-forwards:** an architecture test that no push of `main` uses force or a `+` refspec.
- **`main` is audited:** every integration on `main` names a snapshot with an accepted review by a non-author, where
  review is required, and a passed validation. Checked on every fetch of `main`.
- **No other computer's clock:** no decision reads the clock of an entry another computer wrote, and no liveness of
  another computer's agents is judged.
- **Short appends:** no lock is held across a Git or process call, the bundle commands of step 8 included.
- **Nothing pushed into a member** (step 8): the Link code mover runs no `git push` and no command a peer chose.

## Unresolved

- **Every throw an arriving entry can reach.** Projections throw while applying, not only at append. `throw new`
  sites per projection (VERIFIED by count on this branch): `CollaborationProjection` 49, `WorkGroupProjection` 15,
  `CapabilityRequestProjection` 13, `TaskCompletionProjection` 11, `ContractProjection` 10, `CoordinationProjection`
  5, `PredictionProjection` 4, `OperatorRequestProjection` 3, `ConversationProjection` 2; plus `AppendAdmission`,
  which runs as the last stage. Step 3 classifies each as "both stand", "counts for nothing, reported", or "cannot be
  reached by an entry from another log" (malformed payloads, which the chain and signature checks already stop). Also
  open from the 2026-10-16 audit: `CLAIM_EPOCH_STALE` is thrown in `LaneClaimService` and `WorkGroupService` too, and
  whether the projections cover every case is not verified.
- **A third computer for step 5:** whether QUIC from the VM reaches a Podman container on this PC.
  (REQUIRES-MEASUREMENT.)
- **Home routers:** the UDP mapping lifetime, against the 15 s pull. (REQUIRES-MEASUREMENT.)
- **How often overlaps and combine lanes happen** in real runs. (REQUIRES-MEASUREMENT, steps 3 and 4.)
- **Log growth** over months, with every computer holding every log. (UNKNOWN.)
- **Code over Link sizes:** the ratio of disk usage to bundle size, and the time to write a 200 MB bundle on a laptop.
  (REQUIRES-MEASUREMENT, step 8.)
