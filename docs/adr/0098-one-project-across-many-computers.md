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

### Decided by the designer

The owner's answers below leave these gaps. Each is the designer's choice, open to the owner:

1. **A lock between connected computers.** A claim's answer waits until every connected computer has seen it, a few
   seconds at most; one that does not answer in time counts as not connected. If two connected computers claim
   overlapping paths at the same moment, the claim of the lower node ID keeps the paths, and the other agent is told
   `OVERLAPPING_CLAIM` before it may write.
2. **The original reviewer cannot be reached for a re-review.** If its own computer judges its session gone
   (`SessionLeasePolicy`), any other agent that is not the author reviews the re-merge at once. If its computer is not
   connected, another non-author reviews it once work has stalled (item 4).
3. **Agents in a dispute do not agree.** Until one of them records a new verdict, the snapshot counts as rejected. The
   author revises it, or, once the goal is met without it, it goes to the trash.
4. **"Work has stalled"** means: no agent on this computer has other work, and the goal waits on a lane or a re-review
   whose computer is not connected now. No timer. A redial within Link's retained-session time (30 s) is still
   "connected". The cost: a short blip while this computer's agents are idle can trigger a takeover, and the away
   version then goes to the trash, recoverable. That is the owner's choice.
5. **Two diverged copies of `main`.** Of two connected computers whose copies have diverged, the one with the higher
   node ID re-merges its own work on top of the other's `main` (re-merge, a last merge of its old head if needed,
   re-review, tests), and the other then fast-forwards to the result. The lower node ID never has to merge, so the two
   never chase each other. A mirror remote has no node ID: after a refused push to it, the pusher re-merges only if it
   is the higher node ID or the other computer is not connected; otherwise it waits.
6. **The trash** is `refs/synesis/trash/<lane>/<epoch>` plus a log entry naming it. The UI lists it. Emptying it is a
   person's action, and the only way work is ever deleted.

### The owner's answers (USER-STATED, 2026-10-17 evening)

"A collaborative framework, not Stalin administration." Agents "act as engineers having a discussion", and do not
"behave like juniors always asking for babysitting". So no path here needs an administrator.

0. **A connected group without a majority:** moot; nothing is voted on.
1. **Claims stay locks wherever they can be checked:** on one computer (which keeps different harnesses, such as Claude
   and Codex, from colliding) and between connected computers. While computers are apart a lock cannot be checked, so
   both may edit, and an agent merges the two when they meet (a combine lane).
2. **Agents work in their own worktrees and branches** and merge into the project's default branch (`main`, `master`
   or whatever it is). This ADR calls it `main`.
3. **When `main` moved,** pull and re-merge, as `git pull` does.
4. **A re-merge is reviewed again, by the same reviewer** that reviewed the original work.
5. **Two different verdicts:** no third agent. The agents involved settle it themselves through messages (ADR-0093),
   like engineers discussing. The dispute and how it ended are recorded and shown in the UI. An agent also writes it to
   a file of its own only if the user tells it to.
6. **Work that arrives for a goal already met goes to the trash,** recoverable until a person empties it.
7. **An away computer's work is taken over only when work stalls:** no other work is left and something waits on it.
   When that computer's agent reconnects, it is told: stop, release your locks and anything you hold, close your lane.
   Its unfinished version goes to the trash, recoverable.
8. **Removing a computer ends its open work.**
9. **Projects shared with a step-2 build are invited again once** after the update.
10. **No reservations at all:** "you cannot reserve files because you might go offline; if you never come back we need
    administrative action to take over."
11. **Every computer keeps a full copy of `main`.**
12. **A Git host is never mandatory.** The project works peer to peer over Link with no remote. A remote, if there is
    one (GitHub, GitLab, Gitea, a bare repository over SSH, any Git remote), may co-exist as an extra place to exchange
    code and as a mirror, but nothing may depend on it.

### The owner's answers (USER-STATED, 2026-10-18)

13. **A takeover starts from the last snapshot the away computer handed in** that reached the taker's computer, or from
    the lane's base if there is none. An agent's unsaved work stays on its own computer and goes to the trash when that
    computer comes back. No checkpoints are shared.
14. **The step 6 third computer runs real agents:** the VM's Claude and Codex sign-ins may be copied into it.
15. **Real runs with live agents** are allowed for every step's done-when, unattended.
16. **A step whose real run fails and is not fixed in about two hours** is recorded as PARTIAL, with what failed and
    why, and the build goes on with the next step's code and tests.

Accepted on 2026-10-16 by the repository owner. Rewritten on 2026-10-17 against the premise, as a design, and settled
by the owner's answers that evening: no code exists for steps 3 to 7 below. ADR-0100 is folded into this model; it
now describes how work from separate copies meets on `main`. ADR-0099 (agents are woken) is unchanged in substance.

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
- Code moves only through a Git remote, which a shared project must have (VERIFIED: `CodeRemote.java:57-69`).
- The earlier designs for steps 3 to 6 fixed this with voting, elections and leader leases. The premise rules those
  out.

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
   as today. A computer that is away keeps its lanes until work stalls (answer 7).
6. **Code** travels through a Git remote, or over Link when none is configured. *Superseded on 2026-10-17 evening:*
   code always travels over Link; a remote is an optional mirror that nothing depends on (answer 12).
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
| `main` | the default branch, a full copy on every computer; a Git remote, if any, is one more mirror |
| fetch and fast-forward | a computer moving its `main` to a newer copy it got over Link |
| pull, merge, then share | re-merging when two copies of `main` diverged |
| a merge conflict | a combine lane for an agent |

```text
 PC (owner)   own log ──●──●──●───────●──●──▶      exchanged when connected, both ways
                         \        \  /
 VM (friend)  own log ──●──●──●──●──●──●──▶       (the VM keeps writing while cut off)

 main (every copy)  ──M1──────M2──M3──▶            moves only forward; each Mi reviewed and tested
```

### The rules that stay hard

Only rules that prevent harm that cannot be undone:

1. **No work is silently lost.** No entry is ever removed or replaced. No snapshot commit becomes unreachable. A
   combine keeps both versions until an agent has made one change from them.
2. **Every entry is signed and attributable.** A computer writes only its own log, and acts only for participants it
   introduced. No computer acts for another's agents, the owner's computer included (from step 5; until then the
   owner's computer merges every lane and keeps today's exemption).
3. **No self-approval** where review is required (`REVIEW_SELF_NOT_ALLOWED`: `ReviewAdmissionRules.java:103`,
   `AppendAdmission.java:168`, `:351`).
4. **History is tamper-evident.** Each log is a hash chain signed by its writer, and every computer holds a copy of
   every log, so an edited or dropped entry shows as a mismatch against the other copies.
5. **`main` only fast-forwards,** and only with work that was reviewed where review is required, re-merges included,
   and that passed the project's validation command.
6. **A claim is a lock wherever it can be checked:** on one computer, and between connected computers (owner's answer
   1).

Messages and review requests are how agents talk, as engineers do. No `main` waits for a computer that is away, and no
path needs an administrator.

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
  and the copies would never agree. So in step 4 every throw an arriving entry can reach becomes one of two outcomes:
  - **both stand**, recorded so agents are told (two overlapping claims);
  - **counts for nothing, reported loudly** (an entry acting for another computer's participant, a self-review).

  ADR-0100's table lists the cases. Where two computers write about one thing, the projection keeps the set of what
  was written, so the order does not matter (below).
- **Where two entries meet on one thing** (VERIFIED order-dependent today, fixed in step 4):
  - **Review verdicts.** A second verdict throws `REVIEW_DECISION_ALREADY_RECORDED`
    (`TaskCompletionProjection.java:628-630`), so a computer that saw ACCEPTED first and one that saw REJECTED first
    would end in opposite states. A snapshot's review state becomes the latest verdict of each reviewer (a reviewer
    may record a new verdict, which replaces its own earlier one). Two different verdicts are `REVIEW_DISPUTED` in
    either order. A disputed snapshot counts as rejected, so the merge pass skips it, until one reviewer records a new
    verdict (designer item 3).
  - **Operator requests.** A second raise for one condition throws `OPERATOR_REQUEST_ALREADY_OPEN`
    (`OperatorRequestProjection.java:95`). Two raises for the same condition count as one request; two different
    answers keep it open, and a new answer replaces its answerer's own.
  - **WorkGroup completion.** `LaneCompletionService` writes `WORK_GROUP_STATUS_CHANGED` COMPLETED
    (`LaneCompletionService.java:245-251`), and from step 5 two computers may both write it. A second COMPLETED for the
    same group counts as the first. Completion itself is read from Git ("Getting onto `main`").

### Claims are locks wherever they can be checked

- **On one computer** a claim is a lock, as today: the append lock serializes every process, whatever harness runs it,
  and an overlapping claim is refused `OVERLAPPING_CLAIM` (`CollaborationProjection.java:555-557`). This is what keeps
  Claude and Codex agents on one computer from colliding (owner's answer 1).
- **Between connected computers** a claim is a lock too. The claim is written locally, then its answer waits until
  every connected computer has applied it, a few seconds at most. A computer that already holds an overlapping active
  claim answers so, and the claim is refused before the agent may write. Two claims made at the same moment on two
  connected computers: the lower node ID keeps the paths, the other is refused (designer item 1). A computer that does
  not answer in time counts as not connected.
- **Between computers that are apart** a lock cannot be checked. Both claims stand, and both agents are told as soon as
  their computers meet. The overlap is settled at `main`: the second lane to arrive is re-merged, and if Git cannot
  combine it, an agent does, in a combine lane (ADR-0100).
- The refused or losing claim is an ordinary entry of its computer's log, so the copies stay complete.

### Getting onto `main`

`main` is the project's default branch, whatever its name (owner's answer 2). **Every computer keeps a full copy of
it, and no Git host is ever needed** (owner's answers 11 and 12). Agents work in their own worktrees and branches. Each
computer's merge pass integrates its own reviewed lanes into its own copy of `main`, as the leader's pass does today:
cherry-pick, the validation command, record.

**Sharing `main` over Link, the normal path.** A computer whose `main` moved tells the computers it is connected to.
Each fetches the new head over Link ("Code over Link") and fast-forwards its own copy if it can. Nothing is ever pushed
into a computer: each one fetches and moves its own `main` itself.

**When two copies have diverged** (each holds merges the other lacks, usually because their computers were apart):

1. **Who re-merges.** Of the two, the computer with the higher node ID takes the other's `main` and re-merges its own
   integrations on top of it, as `git pull` does. The other then fast-forwards to the result. The lower node ID never
   has to merge, so the two never chase each other (designer item 5). With three or more copies the same rule applies
   pair by pair, and every step only adds commits, so the copies converge. (DERIVED; checked by a property test.)
2. **How.** One merge commit per own integration not yet in the other copy, oldest first. Each merge has that
   integration as a parent, so a lane based on it keeps its base (today's rule, `STALE_BASE`,
   `IntegrationOrchestrationService.java:346-347`, accepts it unchanged). Then one more merge commit brings in this
   copy's old head if it is not yet an ancestor of the result. Once a head is itself a re-merge commit, rebuilding
   "the other's `main` plus own integrations" need not contain it, and without that last merge the result would not
   fast-forward this copy. Then the validation command runs on the result.
3. **Review again.** Each re-merge is reviewed again by the reviewer of the original work (owner's answer 4), who sees
   what the re-merge changed against what it reviewed. If that reviewer cannot be reached, the re-merge waits. A
   reviewer also counts as unreachable when its own computer judges its session gone (`SessionLeasePolicy`), even
   though that computer is connected; any other agent that is not the author then reviews at once. Otherwise, once
   work has stalled, any other agent that is not the author reviews it (designer item 2). Only then does the result
   become this computer's `main`, and the other computers fast-forward to it.
4. **If Git cannot merge,** the path a cherry-pick conflict takes today runs: `INTEGRATION_CONFLICTED` and
   `REPAIR_REQUIRED` (`IntegrationOrchestrationService.java:920-925`). Its repair lane is the combine lane: an agent
   other than the author combines the two versions, and the result is reviewed (ADR-0100). Merging one integration at
   a time keeps each conflict inside one lane, which the existing one-source repair lane needs
   (`RepairLaneTransfer.java:53-61`).
5. **If the validation command fails** on the merged result, today's validation-failure path runs.
6. **Agents settle what they disagree on** by messages (ADR-0093). Synesis does not route the decision to a person.

**A Git remote, when there is one,** is an extra place to exchange code and a mirror: GitHub, GitLab, Gitea, a bare
repository over SSH, any Git remote. Nothing depends on it, and a project works the same with it unreachable or
removed.

- A computer fetches from it as from one more peer, and pushes its `main` there with no force, as
  `publishControlBranch` already does (`CodeRemote.java:248`).
- A refused push means the mirror holds a copy that diverged from this one. The pusher re-merges onto it (fetch,
  re-merge, re-review, test, push) only if it is the higher node ID of the two computers whose copies diverged, or the
  other computer is not connected. Otherwise it waits: the higher node ID is already re-merging over Link, and the
  mirror catches up from the result. Without this, two computers could re-merge onto each other's copy, one over Link
  and one through the mirror, and stay diverged for ever. The remote decides nothing that Link does not also decide.
- Snapshot refs may also go there, so a reviewer can fetch from it when the author's computer is off.

Before a lane starts, the computer updates its `main` from the newest copy it can reach, as `followLeader` does today
(`CodeRemote.java:209-228`).

A lane is on `main` when its integration commit is an ancestor of `main`. Every computer reads that from its own copy,
so no entry has to announce it. A goal is complete when every lane it names is on `main`.

The rule is the same whether two copies meet a second after the merge or a week later.

**Two re-mergers in turn.** If the validation command and the re-review take ten minutes, a higher node ID can keep
re-merging while new merges keep arriving. Every round still ends with a copy that holds more than before, and with a
handful of computers the rounds are few. (DERIVED.) If it shows up in practice, a computer gathers what arrived during
a round into the next one instead of starting over. (REQUIRES-MEASUREMENT.)

### Computers that are away

- No computer judges another computer's agents present or absent. A computer judges only its own agents, with its own
  clock, as `SessionLeasePolicy` does today.
- **A lane of a computer that is away is left alone while there is other work.** It is a lock that cannot be checked,
  and the computer is probably still working on it.
- **When work stalls, another agent takes it over** (owner's answer 7). "Stalled" means no agent on this computer has
  other work, and a goal waits on a lane or a re-review whose computer is not connected now (designer item 4). An
  agent then continues the lane from its last snapshot that reached this computer, through today's lane continuation. The continuation is
  the taker's own entry, so attribution holds: no computer acts for another's agents.
- **When the away computer reconnects,** its agent is told the lane was taken over: stop, release your locks and
  anything you hold, close your lane. It does that with its own entries. Its unfinished version goes to the trash
  (designer item 6), so it is not lost. If its agent had already integrated the lane onto its own `main` before
  reconnecting, nothing can be trashed, because `main` only moves forward: the two integrations meet as diverged
  copies, and the re-merge (and, where Git cannot merge, a combine lane) settles them.
- **A short drop can trigger a takeover.** With no timer, a PC whose agents are idle can take over a lane during a
  blip of a few seconds, and the away computer's version then goes to the trash. That is what the owner chose; the
  trash keeps it recoverable.
- If two computers that are apart from each other both take over the same lane, both continuations stand and meet like
  any overlap: a combine lane at `main`.

### The trash

Work that must leave the way without being lost goes to the trash: late work for a goal already met (owner's answer
6), a taken-over computer's unfinished version (answer 7), a disputed snapshot the author gave up on (designer item 3).

- Its commits are kept under `refs/synesis/trash/<lane>/<epoch>`, and an entry of the computer that put it there names
  them, with the reason.
- The UI lists the trash. A person can bring an item back as a new lane, or empty it. Emptying is the only way work is
  ever deleted, and only a person does it.

### Membership and keys

Membership moves into the owner's log (step 6). The owner key lives on one computer, so the owner's entries form one
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
- Removal also ends the removed computer's open work (owner's answer 8).

### Links

- A handful of computers. Each keeps a session to every other member it can reach, dialing the other's `MEMBER_CARD`.
  Today replication runs on control-plane sessions, one per computer, to its inviter. The overlay, which links everyone
  up to three members (`OverlayTopologyPolicy.java:28`), is off in production and is not needed for this.
- Two members that cannot dial each other still get each other's entries and commits through any computer both reach.
- Topology for more members is later, if ever ("Not needed under the premise").

### Code over Link (step 3; the first-class path)

Today code moves only through a Git remote: `CodeRemote.of` refuses a shared project without `code.remote`
(VERIFIED: `CodeRemote.java:57-69`). Step 3 makes Link the path that always works.

- A second `CodeMover` beside `CodeRemote` does the same four things over Link (VERIFIED operations: `pushRefs`
  `CodeRemote.java:131`, `ensureCommit` `:176`, `followLeader` `:209`, `publishControlBranch` `:238`). "Push before
  naming" becomes "hold the refs until the lane ends". "Ensure a commit" fetches it from any connected computer that
  holds it. "Publish" only tells peers the new head.
- A computer serves only commits that an entry in its copy of the logs names, or its `main`, from `refs/synesis/` and
  `main`. A peer never chooses a Git command, and nothing is pushed into a computer.
- Pieces of at most 200 MB per transfer, resumable (USER-STATED, ADR-0100 answer 8): planned at 150 MB, 256 KiB chunks
  each with a SHA-256, resumed from the first missing chunk. Prepared pieces are kept 24 h for resuming. These numbers
  are engineering starting values, not owner decisions (REQUIRES-MEASUREMENT).
- Joining fetches the repository over Link once the owner has accepted the join. When the invitation carries a remote
  URL, cloning from it first is a faster start, never a requirement.
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

1. One history; a member never appends on its own. *Replaced in step 4* by "a computer appends only to its own log".
2. One leader per term. *Not needed.*
3. The voter set changes only by an entry the voters commit. *Not needed.*
4. An even voter set holds the owner's computer. *Not needed.*
5. Removal needs the owner's key. *Stays* ("Membership and keys").
6. Decisions are made against the leader's committed state, and every authority rule lives in a projection. *The
   projection half stays.* The leader half is replaced in step 4 by local judgement plus the rules for arriving entries.
7. Time used in a decision is the decider's. *Stays as:* no decision reads another computer's clock.
8. A merge never holds the log. *Stays.*
9. Parts are locked in a fixed order. *Deferred* with parts.
10. Different compatibility numbers never form a session. *Stays.*

### What happens to step 2

Step 2 is the working baseline until the steps below replace its parts, and most of it stays.

- **Stays:** Link sessions, redial and the replay guard; the one-link join and the owner's approval; `PULL` and `NOTICE`
  with chain checks and paging; the 15 s pull; the loopback route from agents' processes to their computer's server;
  the merge outside the lock with its head check; the projection rules of the 2026-10-16 audit; the compatibility
  number. Code through a Git remote stays as the optional mirror.
- **Changed in step 3:** a shared project no longer needs `code.remote` (`CodeRemote.java:57-69`); code moves over Link.
- **Removed in step 4:** the member's proposal path (`PredictionEventStore.java:250-259`, `LeaderEndpoint.accept`).
  Every computer appends as the leader does today.
- **Removed in step 5:** the member's skip of the merge pass (`IntegrationOrchestrationService.java:480-484`), and the
  leader's exemption from attribution (`ParticipantAttribution.java:60`). Leader-only types
  (`PredictionEventType.java:396`) become "written only by the computer whose lane they are about". Until then the
  owner's computer merges every lane, and its merge pass writes entries for other computers' lanes (the release after
  an integration), so it keeps both.
- **Simplified in step 6:** `replication.properties` keeps only `role=shared` and an optional `code.remote=`. Members
  come from the owner's log.
- **The owner's computer** keeps two duties: the owner key and approving joins. It is otherwise a member like the
  others. It is not a coordinator, and it does not hold `main` for anyone.
- **Migration** (owner's answer 9): a project shared with a step-2 build is invited again once after the update, and its
  old log is kept as an archive. A project on one computer changes nothing: its `events/` is already its own log.

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
- **Taking over a lane after minutes of silence:** a takeover happens only when work stalls, never on a timer.
- **Cross-computer liveness judged by a leader:** no computer needs another's agents judged absent.
- **A coordinator or merger for other computers' lanes:** two computers could then merge one lane. Its own computer
  merges it, or another agent takes the lane over when work stalls.
- **Reservations** (owner's answer 10): "you cannot reserve files because you might go offline; if you never come back
  we need administrative action to take over."
- **A third agent for disputes, operator requests for disputes or away computers, the owner releasing an away
  computer's lanes** (owner's answers 5 and 7): agents settle disputes by talking, and take over stalled work
  themselves. No path needs an administrator.
- **`main` held by the owner's computer, or by a Git host** (owner's answers 11 and 12): every computer keeps a full
  copy.
- **1,024 members, hashed rings, degree 8, snapshot version 2, signed voter messages through a third member:** a
  handful of people. Later, if a project ever needs it.
- **Courier hole punching between members:** the answer link already makes the first connection across two homes, and
  entries flow through any computer both reach. Later, if pairs that cannot connect turn out common.
- **Parts, their queues and per-part mergers:** a scale feature, later.

## Alternatives rejected

- **Keep step 2's leader and let a computer that cannot reach it branch and return** (ADR-0100's first form). Two modes,
  and friends connected without the owner still do not coordinate.
- **Step 2 plus voting** (the step 3 design). Needs majorities, elections and leases, and a connected group without a
  majority still stops coordinating. The premise rules it out.
- **One shared log with entries taken back on conflict.** Every local reader would see entries disappear.
- **The Git remote's refused push as the order of `main`.** The simplest ordering, but it makes the project depend on
  the remote (owner's answer 12).
- **A server run by Synesis.** Out of scope (ADR-0097).
- **Chat messages as signals.** A model can ignore or misread a message (ADR-0093). Messages carry the discussion;
  entries carry what was decided.

## Consequences

- No computer ever stops for lack of a connection, and no write waits on another computer.
- A project needs no Git host. With one, it works the same, and losing it changes nothing.
- Connected agents never overlap. Overlap between work done apart costs a re-merge, a re-review, and sometimes a
  combine lane.
- Every re-merge is reviewed again, so reviewers see more work when copies diverge often.
- Every computer's copy of `main` moves only forward. Two copies that diverged converge when their computers meet.
- Nobody blocks `main` by being away: when work stalls, another agent takes the lane over.
- Without a central gate, a buggy build could put work onto a copy of `main` that was not reviewed. Every computer
  audits `main` when it fetches it (a fitness function) and reports such a commit loudly.
- Each computer stores every log and the whole repository.
- Agents can still get around the operator gate with `run_command` (recorded, deferred).
- A removed member keeps its copy. Removal ends future access, not past access.

## Build design (designer, 2026-10-18)

How each step is built, from a survey of the source on 2026-10-18. Each item is the designer's choice, open to the
owner. "Today" facts are VERIFIED on `master` at `972435f2`.

### Step 3: Code over Link

Today: `CodeRemote` is a concrete class whose static `of` refuses a shared project without `code.remote`
(`CodeRemote.java:57-69`). A Link exchange is one request and one reply on its own QUIC stream, at most 512 KiB, within
5 s (`ApplicationStreamCodec.java:17`, `StreamExchange.java:37-38`). Only the coordination server holds Link sessions;
agents' processes reach it by HTTP on loopback (`LoopbackExchange`). No Java code uses `git bundle`.

1. **One code exchange, used by every caller.** `CodeRemote`'s callers (snapshot hand-in, publication, the reviewer's
   and validator's `ensureCommit`, the merge pass's fetch, `followLeader`, `publishControlBranch`) call one
   `CodeExchange`. It always uses Link in a shared project, and also the Git remote when one is configured, as a mirror:
   fetch from Link first, then the remote; publish to the remote as today. A single-computer project does nothing, as
   today.
2. **Agents' processes ask their own server.** A new loopback path `/code` beside `/replication` takes "make commit C
   present" or "bring `main` up to date". The server does the Link transfer and the `git fetch` from the bundle, then
   answers. The process waits at most 10 minutes, the bundle bound.
3. **A new Link protocol, `SCX1`, beside `SRL1`,** routed by `ReplicationSide` by its magic:
   - `Want(wanted, have)` names refs or commit IDs; the server answers `Piece(pieceId, bytes, chunkCount, tip)` or
     `Unavailable(code)`.
   - `Chunk(pieceId, index)` answers `ChunkData(index, bytes, sha256)`, 256 KiB at most, so one chunk fits one exchange.
   - The server names what it serves itself: `refs/synesis/snapshots/*`, `refs/synesis/prepared/*`, and the head of the
     default branch, and only commits an entry in its copy of the log names (or that branch). It builds the bundle with
     `git bundle create` from refs it resolved. A peer never chooses a Git command, and nothing is pushed into a
     computer.
4. **Pieces of at most 150 MB.** The server walks `git rev-list --reverse` from `have` to `wanted` and cuts at the last
   commit whose `--disk-usage` total stays under 150 MB; the piece's `tip` is that commit. The client asks again with
   the new `have` until the tip is the wanted commit. A prepared piece is kept 24 h under
   `<coordination>/code-pieces/`, keyed by (wanted, have), so a resumed request reuses it, and a client resumes from its
   first missing chunk. Disk usage is an estimate of bundle size (REQUIRES-MEASUREMENT, "Unresolved").
5. **Each piece is checked** on arrival: each chunk's SHA-256, `git bundle verify`, `git fetch` with
   `fetch.fsckObjects=true` into `refs/synesis/incoming/<pieceId>`, and the fetched tip must equal the piece's tip. Only
   then does the client move a snapshot ref (which must equal what the log names) or fast-forward `main` by today's
   `followLeader` rule. The incoming ref is then deleted: it is a transfer buffer, not work.
6. **Who holds what in step 3.** The leader is still fixed. A member no longer pushes: its snapshot refs stay where
   they are ("hold the refs until the lane ends"). The leader's merge pass fetches each snapshot an entry names from the
   computer that wrote the entry. A member's reviewer fetches from the leader, which serves every commit its log names
   once it has it. `main` stays the leader's to move.
7. **Members follow `main` without waiting for a lane.** Today a member fast-forwards only when it allocates a lane
   (`ProviderWorktreeAllocation.java:55`). In step 3 its server also follows whenever an applied `CONTROL_BRANCH_ADVANCED`
   names a head its `main` lacks, so `main` ends equal on every computer.
8. **`code.remote` becomes optional:** `CodeRemote.of` returns empty without it, `ProjectSharing.admit` and `follow` no
   longer require it, and an invitation without a URL is complete.
9. **Joining clones over Link.** When the project is not on the joining computer, `synesis link join <link> --into
   <empty folder>` (and a folder field in the UI) waits for the owner's acceptance, then fetches `main` in pieces, checks
   it out, and follows. A URL in the invitation is offered as a faster start, never needed.
10. **Bundle commands** run with a 10-minute bound, outside every lock, through `GitProcessRunner` with its output cap;
    Git writes and reads the bundle file itself, so nothing streams through the runner.
11. **Compatibility 7.**
12. **Following `main` before a lane is best effort** (designer, 2026-10-18, after real run s5-run5). A computer's own
    copy of `main` is always reachable, so when no computer or mirror that may hold a newer one answers (not connected,
    no answer, timed out: `CODE_REMOTE_UNAVAILABLE`), the lane starts from this computer's own `main` and its agent is
    not held up. The lane's workspace marker records it (`baseFrom: LOCAL_MAIN` and the reason), so a later re-merge
    of that lane is expected, not a fault. Only a local failure still stops the lane (role settings that cannot be
    read, a checkout Git cannot use). Before this rule, a partitioned member's first `ensure_session` failed with
    `workspace_not_ready` and `request_human_help`, and the member did no work for the whole partition. The same rule
    holds wherever an agent waits for code another computer holds (a reviewer's snapshot, a validator's
    implementation, a repair's snapshot): the wait is a retry, never a request for a person.

Calls made while building it (designer, 2026-10-18):

- **A `Want` names one thing** (a snapshot or prepared ref, `HEAD`, or a commit ID), and a `Piece` also carries the
  commit the name resolved to and the holder's branch name, so a client asking for `HEAD` knows when it is done and a
  joiner names its branch the same.
- **Pieces are cut along first parents by binary search** on `rev-list --objects --disk-usage`, which only shrinks as
  the cut moves back along first parents; a cut over merges in plain `--reverse` order is not monotonic.
- **Every Link failure reaches callers as `CODE_REMOTE_UNAVAILABLE`,** so their "try again" handling is unchanged.
- **A snapshot is asked for by its ref,** which its author serves before its own log copy has the entry; the result
  must equal the commit the log names.
- **The merge pass fetches outside the integration lock, and only the snapshots it may merge now** (reviewed and in
  order). One computer that is away therefore holds back only its own snapshots, not every integration.
- **A member fetches `main` only when the advanced head is missing,** and moves its checkout by `followLeader`'s rule
  whenever the commit is held, so a checkout that cannot fast-forward does not download again on every pull.
- **The first checkout goes only into a folder that holds nothing but Synesis's own files;** otherwise
  `CODE_FOLDER_NOT_EMPTY`, and nothing is touched.
- **Removed, not deprecated:** `CodeRemote.of(Path)`, `ensureCommit`, `followLeaderIfMember`, `fetchIfMissing`,
  `NOT_CONFIGURED` and `MemberAdmission.CODE_REMOTE_NOT_CONFIGURED`. Every caller is in this repository and was
  migrated, and the workspace module ships only inside the CLI, so these are treated as internal. A refusal code that
  can no longer happen is dropped: a reader that knew it never sees it. Open to the owner.
- **Not built yet:** a leader passing a commit from one member to another (a member's reviewer gets another member's
  snapshot once the leader holds it, which with two computers is always), and the invitation's mirror for a joiner
  that used `--into`.

### Step 4: Each computer writes its own log

Today: one log, `events/%020d.sce`, chained by a global `sequence`; each projection's `validate` is a copy of `apply`, so
all 112 `throw new` sites fire on replay and on arriving entries too; `applyCommitted` places the file before applying,
so a throw makes the log unopenable. Many readers use the global sequence as a cursor (`ControlPlaneEventStream`,
`EventLogObserver`, `unchangedSince`, `CoordinationService.replayAfter`).

1. **Layout.** `events/` is this computer's own log, unchanged. `peers/<node>/events/` holds a byte-identical copy of
   each other computer's log. `applied.order` is an append-only list of `<node> <sequence>` in the order this computer
   applied entries.
2. **The local revision.** A line's position in `applied.order` is the entry's local revision. Every reader that uses
   "the sequence" as a cursor or a revision uses the local revision, so the dashboard, the CLI, agent runs and
   optimistic envelopes keep working unchanged. A log's own `sequence` stays its chain position.
3. **Checking is separate from applying.** Each projection keeps today's rules as its check, run only on this computer's
   own appends (with `AppendAdmission`). Applying never throws on a verified entry. An arriving entry that today's rules
   would refuse is treated by kind:
   - **Missing reference** (`*_NOT_FOUND`, `CONVERSATION_DELIVERY_AHEAD_OF_LOG`, an unknown snapshot): parked, and
     applied when what it names arrives. An entry's own earlier entries are always present, because a log is copied in
     order.
   - **Revision and epoch checks** (`*_STALE`, `CLAIM_EPOCH_STALE`, `expectedRevision`, `validatedRevision`): checked on
     the writer's own computer against its own view; not checked again on arrival.
   - **Two facts about one thing:** both stand, and the projection keeps the set (overlapping claims, review verdicts per
     reviewer with `REVIEW_DISPUTED`, operator raises and answers, duplicate COMPLETED).
   - **Acting for another computer's participant, or a self-review:** counts for nothing, reported loudly in the UI and
     `synesis coordination status` (`LOG_PROBLEM=`). These do not depend on order: a participant's introduction is in its own computer's log,
     before any entry acting for it.
   - Every other site is decided one by one into one of these four, in a table kept beside ADR-0100's, each with a test
     that applies the two entries in both orders.
4. **The claim lock between connected computers.** The claim is appended locally. Before its answer, the server asks
   every connected computer `ClaimSeen(entry)`; each pulls if needed and answers with any overlapping active claim and
   whether that claim's own check has finished. The claim is refused if an overlapping claim's check has finished, or if
   both are still being checked and the other computer's node ID is lower. A computer that does not answer in 5 s counts
   as not connected. The refused claim's lane is closed by its own computer's entry, and its agent is told
   `OVERLAPPING_CLAIM` before it may write.
5. **The proposal path goes.** Every computer appends locally; `LEADER_UNREACHABLE` and `LEADER_OUTCOME_UNKNOWN` go
   with it. The merge pass still runs only on the owner's computer, with its attribution exemption, until step 5.
6. **Migration** (owner's answer 9): a shared step-2 project keeps its log in `events-step2/` on every computer and
   starts a fresh one; the project is invited again once. A project on one computer changes nothing.
7. **Property test:** random logs from three computers, applied in random arrival orders, each log in its own order,
   answers before requests included; every decision listed under "Order does not decide" is the same.
8. **Compatibility 8.**

Decided while building step 4 (designer, 2026-10-18; each open to the owner):

9. **Three ways to apply an entry** (`ApplyMode`): `CHECK` for this computer's own new entry (every rule, as
   today), `ARRIVAL` for an entry of another log applied here for the first time (on a copy, so a held-back entry
   changes nothing), and `REPLAY` for an entry already in `applied.order`. Applying never throws in `ARRIVAL` or
   `REPLAY` except to hold an entry back (`MissingReferenceException`, same code as the refusal). Anything else an
   arriving entry still hits counts for nothing, is reported, and its line records why; so one faulty entry never
   stops a copy.
10. **A held-back entry holds back the rest of its own log,** because its later entries may build on it. The other
    logs go on. A held-back entry is listed, with the code it waits on, in `synesis coordination status` (`LOG_PROBLEM=`).
11. **Ended is not missing.** A lane that was released, completed or continued is remembered by its id, so a late
    entry of the away computer about it applies with no effect instead of waiting for ever.
12. **`applied.order` lines** are `self <sequence>` for this computer's own log (every process that opens a store
    can write it, and not every process knows the computer's node ID), and `<node> <sequence>` for a copy. A third
    field, the code, marks an entry that counted for nothing; such an entry takes no local revision. Entries of the
    own log that have no line yet (a log written before step 4, or an append cut short) apply after every line, in
    their own order, and the next writer gives them their lines; so a project on one computer changes nothing.
13. **Marks of delivery are local.** `CONVERSATION_MESSAGES_DELIVERED` names a position in its writer's local order,
    which means nothing on another computer. Its bound is judged only by its writer, and on another computer it is
    read only for the writer's own lanes, which nothing there delivers to. Each computer delivers to its own agents.
14. **Attribution on arrival** is today's rule, judged again: an arriving entry acting for a participant that
    another node introduced counts for nothing (`PARTICIPANT_NOT_OWNED_BY_SIGNER`), except an entry signed by the
    owner's computer, which keeps its exemption until step 5. One acting for a participant not seen at all applies,
    as it did on its writer: a participant's introduction comes before its own computer's entries for it, in the
    same log, so only a faulty build can act first. Leader-only types from another computer count for nothing
    (`EVENT_TYPE_LEADER_ONLY`). A self-review counts for nothing once the snapshot it names has arrived.
15. **Two answers to one coordination request** (a review request answered by its first target and by the lane that
    took it over): both are kept, and the answer of the lower node ID is the request's status.
16. **Work-group status:** a newer version replaces an older one, and a second change of the same version counts as
    the first; at one version COMPLETED wins over any other status, then the later status in declaration order. Only
    the version and the status move: two creations of one group (two computers apart claiming the first lane of one
    goal) give the group the goal and review policy of the lower node's creation, in either order. **Operator
    requests:** every condition Synesis raises is about the computer that raises it (its control checkout, its
    integration pass), so "one open request per condition" is judged on that computer's own raise, and raises from
    two computers are two requests. This narrows ADR-0100 row 6, whose "two raises for one condition count as one"
    assumed one shared condition. Answers are kept per writing node; a node's new answer replaces its own, and two
    different answers keep the request open.
17. **Review verdicts** are kept per reviewing participant. Each computer still refuses a second verdict on its own
    append, as today; on arrival both stand. Every reviewer accepted gives `REVIEW_ACCEPTED`, every one rejected
    `REVIEW_REJECTED`, and different verdicts `REVIEW_DISPUTED` (a new state, counted as refused, so it is never
    merged). An integration written by the owner's computer stands over any verdict, and a validation refusal over
    every verdict but an integration, so the final state does not depend on which arrived first. Ending a dispute by
    a reviewer's new verdict comes with step 7; until then the author publishes a new revision.
18. **Known order dependence left for later** (rare, and each needs a design of its own): a participant's lifecycle
    when its own computer and a taker both write about it (suspended against detached, ADR-0100 doubt on lifecycle
    states); two prediction steps that are siblings (`ACCEPTED_EXACT` against `CONTRACT_REVISED`) or two terminal
    steps; two revisions of one contract number; a capability request whose requester was continued on another
    computer before the request arrived; two takers of one lane on computers apart (both successors stand, but which
    one the old lane's review requests follow depends on the order; step 7). Each applies without throwing; which of the two stands depends on the
    order.
19. **The claim lock, made exact.** Item 4 alone lets two claims both stand: B asks A before A has claimed, A then
    claims and asks B while B's check still runs, and B's node is the higher, so A is not refused, and neither is B.
    So a computer that is asked about an overlapping claim while its own is being checked, by a computer with a lower
    node ID, gives its own claim way when its check ends. The question is `CLAIM_SEEN` (the signed claim entry) and
    the answer `CLAIM_ANSWER` (empty, or `OVERLAPPING_CLAIM`), on Link between computers and on the loopback route
    from an agent's process to its computer's server. Before it appends a claim, the process registers it with its
    own server (`CLAIM_INTENT`, loopback only), so a question that arrives between the append and the check finds
    the claim being checked; a registration whose check never starts is dropped after 15 seconds. The check runs
    after the append lock is released, since it waits on other computers, and a claim that gives way is released by its own computer's entry before the agent
    is answered. A computer with no connected computer, or whose server is not running, asks nobody: the claim
    stands. An overlap with a lane of the claimant's own computer is not counted again, since its own append checked
    it; an overlap with a third computer's lane that the asked computer holds is.
20. **Readers of the local revision.** `headSequence()`, `events()`, `eventsAfter`, `unchangedSince`, the dashboard's
    event stream and the conversation cursors count local revisions. The legacy `synesis events follow` stream
    still names each entry by its own log's sequence, so in a shared project its cursor is per log; it is left for
    later with that limit.
21. **Whose logs are copied.** A signature proves who wrote an entry, not that the writer belongs to the project. The
    owner's computer copies only the logs of the members its settings name; a member copies every log the owner's
    computer serves, until membership is in the owner's log (step 6). An arriving entry that would have opened a lane
    and counted for nothing ends that lane here, so the rest of its log does not wait for it for ever.

Built on 2026-10-18 (branch `claude/adr-0098-step4-own-log-68db50`, source-level evidence only: no PC and VM run
yet, so the done-when below is NOT REACHED): items 1 to 21, with the order-free rules, the property test
over three computers, and the claim lock. `synesis log problems` is `synesis coordination status`, which prints a
`LOG_COPY=<node> <head>` line per copy and a `LOG_PROBLEM=` line per entry that counted for nothing or waits. Not
built: the UI showing overlaps, disputes and problems; the order dependence of item 18. Item 8 is built: this branch sets
compatibility 8 directly, and 7 comes with the merge of step 3.

Found in the first PC and VM run of step 4 with the cable cut (project `s1-run4`, build `e384abe7` on both computers,
2026-10-18; the VM cut off 07:58:13 to 08:18:13 UTC). Both copies of both logs ended byte-identical and no log file was
changed, so the copying held. The run stalled for two other reasons (designer, 2026-10-18; each open to the owner):

22. **A finish argument copied wrong is named, not called stale.** The VM's primes lane never published. Decoded from
    the VM's copy: at its last projection the head was 41 and stayed 41, the lane was at epoch 1 and `ANNOUNCED`, its
    group at version 1, and every argument of the three last `finish_lane` calls equalled the projection except
    `participant`, which the Codex agent typed `agt_57611847-333e-3bd7-b28c-04bba9c6f9dc` for
    `...-b28d-04bba9c6f9dc` (five of its seven finishes carried such a typo). `validateSnapshotEvidence` refused it as
    `SNAPSHOT_COMPLETION_EVIDENCE_STALE`, whose recovery projects the same action again, so the agent retyped the same
    value until its unchanged-poll limit and stopped. The local revision was not the cause: `headSequence()` and the
    fence both count local revisions. The caller's participant and its lane's work group cannot change while the lane
    is open, so a different value can only be a wrong copy: the finish is now refused
    `SNAPSHOT_COMPLETION_EVIDENCE_MISMATCH` (blocked, retry) with `field` and `expected`, and a no-change finish keeps
    `NO_CHANGE_COMPLETION_EVIDENCE_MISMATCH` but gains the same two keys and `retry` instead of asking a human. Every
    other argument still reads as stale. Seen too, and left as designed: the agent's two finishes with every argument
    right, before the cut (revisions 15 and 27), were stale because review entries of the PC's lanes (a grant issued
    and consumed, a coordination answer, a verdict) arrived between the projection and the call; `finishEnvelopeStillHolds`
    counts any of them, from any computer. The agent re-projected each time.
23. **A computer coming back resumes a stopped agent that has something to come back to.** The PC's reviewer of the
    VM's fib snapshot consumed its grant ten seconds after the cut, read nothing (`CODE_REMOTE_UNAVAILABLE`), recorded no
    verdict and stopped. The VM's six entries had all reached the PC before the cut, so after the VM came back nothing
    new arrived on the PC: its log stayed at 41 from 07:58:22 on, `progressedSince` stayed false, and the controller
    waited (`FOREIGN_LANE_OPEN`) for ever. Only `replication.log`'s `CONNECTED` line says that the VM is back. The run
    controller now reads, from that journal, when the session with a computer of the project that is connected now
    began (`ReplicationJournal.connectedSince`, `Observation.otherComputerConnectedSince`), and resumes once per such
    session a stopped agent whose lane may move or whose session holds a request or a grant, when the session began
    after the agent last looked (its process's start, or 30 seconds before its end, as for log progress). An agent with
    neither is not resumed. A link that drops and comes back often costs one idle resume each time, bounded by
    `--max-resumes`. ADR-0099 records the rule as fix 11.

### Step 5: Each computer merges its own lanes

Today the merge pass cherry-picks reviewed snapshots onto the checked-out branch and fast-forwards it
(`IntegrationWorkspaceService.java:142`, `ControlBranchAdvancementService.java:118`); a member skips it.

1. **The merge pass runs on every computer, for its own lanes** (lanes introduced by its node). Integration entries are
   written only by the lane's computer, and attribution has no exemption.
2. **`main` over Link:** a peer's `CONTROL_BRANCH_ADVANCED` makes this computer fetch that head (step 3's exchange) and
   fast-forward when its own `main` is an ancestor.
3. **Audit before moving.** Every commit a fetched head adds must be an integration or re-merge named by an entry, with
   an accepted review where required and a passed validation. A commit no entry names yet waits for the next pull; one
   still unnamed once the writer's log covers that head is reported loudly and not taken.
4. **Diverged copies:** the higher node ID re-merges (designer item 5): one merge commit per own integration not in the
   other copy, oldest first, then its old head if needed, then the validation command. The result is a new snapshot of
   each lane, `REMERGE`, reviewed through today's review flow, with the grant offered first to the original reviewer
   (designer item 2 when it cannot be reached). Only when every re-merge is accepted does the result become this
   computer's `main`. A conflict opens today's repair lane as the combine lane.
5. **A lane is on `main`** when its integration commit is an ancestor of this computer's `main`; a goal is complete when
   every lane it names is. A second COMPLETED counts as the first.
6. **The mirror:** pushed with no force after each advance; a refused push follows "Getting onto `main`".
7. **Property test:** random merges, cuts and meetings among three copies end with every copy of `main` equal and only
   ever moved forward.
8. **Compatibility 9** (new entry types), so steps 6 and 7 are 10.

Decided while building step 5 (designer, 2026-10-18; each open to the owner):

9. **One merge pass per computer.** Each computer's pass takes only the snapshots of lanes its node introduced, and its
   integration attempts and last advanced head are its own (`activeIntegrationAttempt(node)`,
   `lastControlHeadAdvanced(node)`): another computer's attempt in progress never holds this one's pass. Attribution
   has no exemption: the owner's computer no longer acts for another computer's participant, and the types that were
   the leader's only (the integration entries, `TASK_INTEGRATED`, `INTEGRATION_VALIDATION_REFUSED`) act for the
   lanes whose snapshots they name, so one written by any other computer is refused on its writer and counts for
   nothing on arrival (`PARTICIPANT_NOT_OWNED_BY_SIGNER`). `EVENT_TYPE_LEADER_ONLY` is gone.
10. **Taking another computer's `main`.** After each pull that brought entries, on every computer, each head another
    computer's pass advanced to (its last `CONTROL_BRANCH_ADVANCED`) that this copy lacks is fetched over Link from
    the computer that wrote it first. When this copy is its ancestor, the head is audited and, if the audit passes,
    taken by fast-forward while this computer's own pass is not running (the integration lock); a person's change in
    the checkout stops it, as it stopped a member's follow in step 3. Nothing announces the move: each copy reads
    from Git what it holds.
11. **The audit** follows the head back advance by advance, each named by an entry (the head it moved from and to),
    to a commit this copy holds. Every advance on the way must name snapshots this computer has seen, made on the
    advancing computer's own lanes, each accepted by a reviewer other than its author where review is required. A
    commit no entry names yet is not taken and waits for the next pull, with a warning in the server's log; a
    separate loud report for a head still unnamed once its writer's log covers it is left for later.
12. **Every computer runs a merge driver,** a member's too, nudged after each pull that brought entries, so work
    that became mergeable on another computer (a review accepted there) and a copy of `main` to re-merge onto are
    acted on with no agent of this computer calling in.
13. **The re-merge** runs in this computer's merge pass, under its integration lock, when another computer's head is
    held here, has diverged from this copy, and this computer's node ID is the higher. In a worktree at the other
    head it makes one merge commit per own advance (`CONTROL_BRANCH_ADVANCED` of this node) whose head the other copy
    lacks, oldest first, then one more with this copy's old head if the result does not contain it yet, then runs
    the validation command on the result. A merge Git cannot make, or a failing validation, stops the re-merge and is
    reported; the combine lane (today's repair lane needs a live source lane, and these lanes have ended) is NOT
    BUILT.
14. **A re-merge is reviewed again through today's review tools.** For each lane of each re-merged advance the pass
    writes `REMERGE_SNAPSHOT_CREATED` (new, compatibility 9): a snapshot of the lane's re-merge commit, with the
    original snapshot's provenance (lane, epoch, participant, group), the other head and this copy's old head. It is
    a snapshot for review only: it never integrates by itself and changes no task's state. The pass then issues a
    grant on it to the reviewer who accepted the original snapshot (`LANE_GRANT_ISSUED` with the snapshot ID and no
    request, as a continuation's grants have none), and that reviewer consumes it and records its verdict with the
    tools it already has (`work_group_join` with the grant, `review_validation`). Every check those tools run holds
    for a lane that has ended. A reviewer whose lane ended is told of the grant by its next action and woken by it.
15. **The re-merge lands** when every one of its snapshots is accepted by a reviewer who is not its author: the pass
    moves this copy's branch to the result (which holds this copy's old head, so `main` moves forward), and records
    it as an ordinary advance, `CONTROL_BRANCH_ADVANCED` from the other head to the result naming the re-merge
    snapshots, so the other computer's audit follows it to its own head and fast-forwards. A re-merge whose basis
    moved (this copy advanced meanwhile, or the other head is not the other computer's last) is left, and the next
    pass builds a new one. A rejected re-merge is left as it is and reported. Another reviewer when the original one
    cannot be reached (designer item 2) is item 24's rule.
16. **How the re-merge is built and found again.** Its ID follows from the two heads (`rm-` and a hash of the other
    head and this copy's head), so every pass on the same basis finds the same re-merge in the log and goes on to land
    it; a pass on a new basis builds a new one. Each re-merge snapshot is `refs/synesis/snapshots/rms-…` at its lane's
    merge commit, and the result is kept reachable under `refs/synesis/remerge/<id>` until it lands. A lane whose
    original snapshot needed no review gets no re-merge snapshot; a re-merge with none lands as soon as it is built
    and validated. A re-merge that Git cannot make, whose validation fails, or that a review rejects is reported once
    in the server's log and not tried again by that server process until the heads change; the combine lane that
    would resolve it is NOT BUILT. Every step can be repeated after a crash: the result is kept under its ref before
    anything is written, each pass reads the lanes back from the result's merge commits and writes only the entries
    that are missing (their IDs follow from the re-merge), and the advance is written before the branch moves, so a
    pass that finds it written only moves the branch.
17. **The reviewer is told and woken.** A re-merge snapshot is in no task's history, so no one is offered it as an open
    review; only its grant offers it. `get_next_action` shows the grant to its reviewer whether or not that reviewer
    still holds a lane, and the run controller resumes a stopped reviewer once for each such grant, as for a review it
    is offered.
18. **The audit follows every merged history.** A re-merge's advance starts from the other head, and its result also
    holds the re-merging computer's own advances and old head. For every advance it walks, the audit also follows the
    other parents of each merge commit the advance adds, back to commits this copy holds, whether or not the re-merge
    needed a review; a commit no advance names stops it. An advance must move to a descendant of where it started, so
    the walk always ends; an entry naming anything else is an invalid chain. A snapshot passes only when every verdict
    on it is ACCEPTED and one of them is not its author's, so a disputed snapshot does not; a lane whose introduction
    has not arrived is not proven to be the writer's, and its head waits. The same holds for the merge pass: a lane
    whose introducer is unknown is not this computer's, and an integration entry naming a snapshot that has not
    arrived waits for it instead of acting for no one.
19. **"On `main`" is read from each copy.** A run on a computer is complete only when no lane is open and this copy of
    `main` holds every head another computer's pass advanced to (`MainMeeting.holdsEveryHead`). So the run that waits
    for a re-merge's review, or for the other copy's result to arrive, is not ended because every lane has closed.
    `WORK_GROUP_STATUS_CHANGED` COMPLETED is unchanged and may be written by both computers (step 4 counts it once).
20. **The mirror.** The push stays unforced, as before. A push the mirror refuses because it holds another computer's
    diverged copy is tried again after every pass, and succeeds by fast-forward once the re-merge over Link has
    landed. Re-merging onto the mirror's copy when the other computer is not connected (designer item 5's last clause)
    is NOT BUILT: that copy's commits are named by entries this computer cannot have without Link, so the audit could
    not pass.
21. **Who re-merges among more than two copies.** Designer item 5 names two computers. With three, letting the higher
    of every diverged pair re-merge never settled in the convergence property test: the middle computer re-merged onto
    the lowest one's copy while the highest re-merged onto the middle one's, and every new merge commit was a new
    divergence. So a computer first takes every copy it is behind, by fast-forward. Of the copies still diverged from
    it, those another diverged copy already holds are dropped; the rest are "in the way". Only when this computer's
    node ID is higher than every writer in the way does it re-merge, onto the lowest of them, one at a time. Every
    other computer waits and fast-forwards to the result. A computer that cannot see a higher writer's copy yet may
    re-merge too; the higher one then re-merges onto that result, and the lower never does, so the rounds still end.
22. **The combine lane** (item 4, ADR-0100 "The combine lane"). Today's repair lane cannot be its vehicle as it is:
    it needs a live source lane at a `REPAIR_REQUIRED` task and seeds from this copy's head, while a re-merge conflict
    is between lanes that ended long ago and the other copy of `main`. So the combine lane keeps the repair lane's
    shape (one source, an agent other than the author, the same `ensure_session` call with `repairIntentId` and
    `repairSnapshotId`) and changes what it is seeded from and where its work goes:
    - **Opening.** When Git cannot merge one of its advances, the re-merge keeps what it built so far under
      `refs/synesis/remerge/<id>/base` and writes `COMBINE_REQUIRED` (new, compatibility 9 not yet released): the
      re-merge, the base it was merging onto, the advance's head, and the advance's snapshots. It acts for those
      lanes, so only their computer writes it. One advance at a time, so each combine has one source.
    - **Who combines.** Any agent of the re-merging computer that holds no lane and authored none of the advance's
      snapshots; its next action offers the combine, and a stopped one is woken for it. Agents of other computers are
      not offered it: the re-merging computer must close the combine lane when the re-merge lands, and a computer
      writes only for its own agents (NOT BUILT: a combine lane on another computer).
    - **The lane.** Joining opens an ordinary producer lane in a work group of its own (`combine-group:<id>`, created
      with the combine, so a closed original group does not refuse it), on a task of its own, based at the re-merge's
      base, claiming the advance's lanes' paths. The agent's worktree is moved to that base and given both versions:
      the advance's changes merged in with Git's conflict markers left in the files, the index at the base. The agent
      makes one change that holds both and finishes the lane as usual. If it cannot, it messages the authors of both
      sides (ADR-0093) and keeps the lane open; nothing reaches `main` until one change holds both.
    - **Its result.** The combine lane's snapshot is never merged by an ordinary pass. The next re-merge pass makes
      the advance's merge commit from it, with the snapshot's tree and both parents (the base and the advance's head),
      goes on with the remaining advances, and runs the validation command. The merged lane's re-merge snapshot then
      carries the combined version, so the original reviewer reviews the result, as for any re-merge (item 14); the
      combine snapshot is not reviewed a second time on its own.
    - **Closing.** When the re-merge lands, the re-merging computer records the combine snapshot integrated and
      releases the combine lane, as the merge pass does for its own lanes; its work group then closes. Until then a
      merge pass answers the combining agent WAIT with the reason, not RETRY, so it does not poll.
    - **Not covered yet:** a second advance of the same re-merge that conflicts after the first was combined (built
      on the combine's base, untested), a combine lane on another computer, and a combine the agent cannot make:
      its messages go to the authors, and the lane stays open until someone changes it.
23. **One agent, one build** (second real step 5 run, s5-run2, 2026-10-18). The PC ran the acceptance build from
    `PATH` while an older build was still the installed one. `provider install` pointed the hooks at the `PATH` build
    and the agents' MCP entry at the installed `synesis-mcp.exe`, because the MCP launcher was looked for in the install
    location first (`ProviderLauncherResolution.mcpLauncherPath`), and the installed launcher starts whichever version
    is activated. That older MCP server could not read `REMERGE_SNAPSHOT_CREATED` (wire code 81, new in this step). Once
    the re-merge wrote one, every store it opened failed with "invalid event type", and every call of the PC's
    reviewer of gcd was answered `SESSION_TERMINAL_STATE_UNAVAILABLE` (`durable_state_ambiguous`, not retry-safe). The
    reviewer read gcd but could not record its verdict, and both controllers kept waiting for it. The log was sound:
    the run's own build answers that session `review_decision` from the same files. **Rule:** an MCP entry runs the
    build its hooks run, through that build's native MCP launcher or else its CLI launcher; an explicitly configured
    MCP launcher still wins.
    - **The re-merge was lawful.** No lane was merged while the link was down. Within a second of the reconnect each
      computer merged its own reviewed lane onto the same head (primes on the PC, fib on the VM). The PC, the higher
      node ID, re-merged onto the VM's copy, the original reviewer of primes (on the VM, not its author) accepted the
      re-merge snapshot, and only then did the PC advance; the VM's audit took the result, so both copies are equal.
    - **Not done:** an older build that meets an entry type it does not know still reports `durable_state_ambiguous`
      and asks for a person; naming it "this Synesis is older than the project's log" would have shown the cause at
      once. `agents run` does not check that its agents' MCP server is its own build.
24. **A combine nobody was shown** (s5-run6, 2026-10-18: PC and VM partitioned for 20 minutes right after the join,
    agents started only once apart). Apart, the PC merged primes, fib and digits onto its `main` and the VM merged its
    own primes. After the heal the PC, the higher node ID, re-merged onto the VM's copy; its oldest advance (primes,
    `snap_19da…`) met the VM's primes, so it wrote `COMBINE_REQUIRED` and the combine's work group, and stopped, as
    item 22 says. Of the four fresh agents, one PC agent continued the held gcd lane (offered and taken, lawfully). The
    other PC agent held no lane and wrote none of the combined work, so the run controller resumed it for the combine,
    but its next action said to wait: first because another call's pass held the integration lock, then because gcd's
    snapshot waited for its review. The combine is offered only by the claim gate (`claimRequiredResponse`), which a
    lane-less caller reached only when the merge pass had nothing to say; and a combine's group never had a lane, so
    with every other group closed the project would have read as finished. **Rule:** a caller that holds no lane is
    offered a combine it may take before the merge pass's wait and before "no open work", right after a continuation
    (`AgentNextActionService.stagedAction`), so the next action shows what the run controller woke the agent for.
    The VM's agents are rightly not offered it (a combine lane on another computer, NOT BUILT).
    - **The held fib lane on the VM was never offered** to the VM's fresh agents. Both computers, apart, opened their
      first lane in "the" default work group, whose ID was derived from the project alone; the step 4 table merged the
      two creations into one group, the PC completed it after its own two lanes, and a held lane in a completed group
      is offered to nobody (`RecoveryContinuationProjection`). **Rule:** the default group is the computer's own,
      derived from the project and the node ID; a group open when a computer starts a lane is still joined as before.
      Not covered: a group opened while connected that one computer completes while apart and the other still works
      in (the same symptom, rarer; a group should not count as complete while a lane in it is open). NOT BUILT.
    - **Why both computers stalled before the heal.** On each computer a reviewer consumed its grant (gcd on the PC,
      fib on the VM), then the merge pass integrated the reviewer's own lane, and from then on every `read_file` and
      `run_command` of its review answered `SESSION_RECOVERY_STATE_UNAVAILABLE` (`McpProtocolHandler.refuseFencedRecovery`:
      a completed binding could record a verdict but not read what it reviewed). The reviewers stopped without a
      verdict, the grants stayed consumed by participants that were gone, and no other agent was offered those reviews.
      The re-merge's own reviews would have met the same wall: the original reviewers of primes and fib are sessions
      that ended. **Rules (2026-10-18):**
      - *A completed lane finishes the review it holds.* `read_file` and `run_command` admit a completed binding at
        the fence, as `get_next_action`, `request_coordination` and `respond_coordination` already did (ADR-0099,
        amendment), on a managed connection too. Downstream only the review snapshot access accepts a completed
        binding (`ReviewSnapshotAccessService`, through `SessionAuthorityResolver.resolveReview`), and only while its
        grant is consumed and has no verdict; it reads and runs in the disposable review worktree, never the lane's.
        A completed lane that holds no review, or whose verdict is recorded, is told to start a fresh session
        (`SESSION_NOT_ACTIVE`, `ensure_session`); `apply_patch`, `finish_lane` and every other tool still fail closed.
      - *A reviewer that cannot be reached is replaced (designer item 2).* The merge pass releases (`LANE_REVOKED`) a
        review grant on a snapshot still under review, with no verdict, whose reviewer holds no lane, when the
        reviewer's own computer judges its session gone: no lease of that computer names the session as live
        (`AwayLanes.goneHere`: closed cleanly, terminal, or unseen past the grace period, or the connection went on to
        a fresh session). This needs no stall and runs at once, since only that computer can judge the session; a
        quiet agent keeps a live lease and its review (ADR-0099). The away-computer release of step 7 (item 17) is the
        same rule for a reviewer whose computer is away. A released ordinary review is a candidate again: an accepted
        review request holds the review only until its grant is issued, then the grant does, in the candidates agents
        are shown as at admission (`ReviewAdmissionRules.validateReviewSnapshotUnassigned`). A released grant holds
        nothing for its former reviewer: its next action no longer asks for the verdict, its reads no longer reach the
        snapshot, and it no longer holds its session open (`ProviderSessionTerminalizationService.openAuthority`), so
        a session that comes back may start a fresh one. A released re-merge review
        is offered by the next pass to another agent of the re-merging computer: an active participant whose session
        it judges live, neither the author nor in its lineage, not released from it before, the lowest handle first
        (`RemergeService.offerReview`); with none, the re-merge waits. Only this computer's agents are chosen, because
        only their sessions can be judged here.
      - *Not covered:* a reviewer that holds a lane is never released here; continuing its lane carries its grants. While
        any lease of the computer cannot be read, or it has no leases folder, nobody of it is judged gone: the
        unreadable lease may be the live one.
25. **A combine taken by ordinary work** (s5-run7, 2026-10-18: PC and VM partitioned for 20 minutes right after the
    join; apart, each computer finished the goal, PC `main` 7491f26 with 5 snapshots, VM `main` b276a5f with 2). After
    the heal the VM, the higher node ID, re-merged onto the PC's copy; its first advance (primes, `snap_55baa9c2…`) met
    the PC's primes, so it wrote `COMBINE_REQUIRED` and the combine's work group `5e4684e3` at 19:28:59 UTC, as item 22
    says. Both fresh VM agents were offered the combine (`combineCandidates`). Before either took it, a fresh PC agent
    announced ordinary work (digits) with no group named; every other group was completed, so the announcement joined
    the first active group, the combine's (`WorkIntentAnnouncement`'s default group). From then on the combine read as
    taken (`CombineLaneJoin.waiting`: its group holds a lane), so the VM agent's `ensure_session` with the offered
    arguments fell through to the repair path and was refused `REPAIR_INTENT_NOT_FOUND` (the combined lane ended long
    ago). Both VM agents then announced ordinary lanes, which joined the same group; the NOTES lane's snapshot
    (`snap_844271e2…`) counted as the combine's result (`combinePending`), was never offered for review (a combine's
    group requires none) and waited for a re-merge that never came, because the re-merge was still waiting for its
    combine. No re-merge snapshot or re-merge review grant was ever written, so the release of a gone reviewer (item
    24) had nothing to release; the "original reviewer" in the agents' wait was the combine-pending text. The PC
    agents were rightly shown no open work: the combine is the VM's (a combine lane on another computer, NOT BUILT).
    **Rule:** a combine's group holds only its combine lane. An announcement that names no group never joins one,
    and one that names it is refused (`COMBINE_GROUP_HOLDS_ONLY_ITS_COMBINE_LANE`). A repair join that names a combined
    snapshot whose combine is not open to the caller is refused `COMBINE_TAKEN_OR_CLOSED`, not sent down the repair
    path.
    - *Not covered:* a log already written with ordinary lanes in a combine's group (this run's) stays stuck; and an
      ordinary lane merged onto this computer's `main` while its re-merge waits for a combine moves the head the
      re-merge started from (untested).
26. **A combine lane judged against the wrong `main`** (s5-run8, 2026-10-18: PC and VM partitioned for 20 minutes right
    after the join; apart, each computer finished the goal, PC `main` 90570ed, VM `main` fefbe78). After the heal the
    PC, the higher node ID, re-merged onto the VM's copy; its primes advance (`snap_7e60ae27…`) met the VM's primes,
    so it wrote `COMBINE_REQUIRED` with base fefbe78. A fresh PC agent took the combine (item 22): the join moved its
    worktree to fefbe78 with HEAD detached and left the markers in, but its binding still named the session's branch
    and the PC's `main` (90570ed) as its base. Every later call was answered `workspace_stale` (no branch, a HEAD that
    is not the binding's base, a base that is not this computer's `main`), and `ensure_session` tried to refresh a
    dirty worktree onto 90570ed and refused `WORKSPACE_STALE_DIRTY`; the agent asked for a person after 68 s. The
    s5-run7 combine never reached this: its join was refused (item 25) and the agent worked in an ordinary lane at the
    VM's own `main`, so no worktree had been moved yet in a real run. **Rule:** a combine lane's worktree is judged
    against the combine's base, not this computer's `main`. The join moves the session's own branch to that base
    instead of detaching HEAD, and only a worktree still on its branch at its recorded base is moved
    (`COMBINE_WORKTREE_HAS_COMMITS`); then it records that base in the session's binding, so a refused move leaves the
    binding as it was, and the branch, ancestry and generation checks hold as for any lane. Where every other lane
    must be based at this computer's control head, a session's binding may instead be based at the base of the
    combine lane that session holds open (`ProviderSessionCoordinationFacts.atJudgedBase`); unreadable coordination
    state answers with the control head alone. Its markers are then the lane's own uncommitted work, not a stale
    worktree to refresh, and the agent reads, edits, runs commands and finishes there. Once the lane ends, the
    session's next `ensure_session` gives it a fresh worktree at this computer's `main`.
    - *Not covered:* a crash after the worktree is prepared and before its binding and lane are written leaves a
      moved, dirty worktree with no lane, which `ensure_session` cannot refresh (as a crash before the announcement
      did before this rule); a combine lane continued or taken over by another session; and the re-merge's use of a
      combine snapshot whose base is the other copy's `main` is still untested in a real run.
27. **A re-merge review owed to no one who can take it** (scripted scenarios S1 and S2, ADR-0102 item 1, 2026-10-19,
    about a third of the runs; and s5-run13, where the original reviewer was a Claude agent on the PC whose run had
    ended before the heal). Three ways the same invariant failed: *a re-merge review owed on this computer is
    offered to an agent that is live, may review it, and can see it in its next action.* (a) A reviewer that
    refreshed its stale workspace with `ensure_session` got a new session; the merge pass released the old session's
    grant (item 24) and offered it to no one, since the only other agent wrote the snapshot and the refreshed agent
    held no lane, so it was no participant on the log. (b) The released review went to the agent that had taken the
    combine, whose lane waits for this very re-merge, so its next action said "wait" and the grant stayed available for
    ever. (c) A reviewer on a connected computer whose agents had all ended is never judged gone by the computer that
    re-merges, and its own computer ran no pass to release it. **Rule:** `RemergeService.anotherReviewer` chooses among
    the live sessions of this computer (`AwayLanes.Agents`), whether or not they hold a lane; first the session that
    replaced a released reviewer's on the same connection, then the lowest handle. It never chooses the author or any
    session of the author's agent, a session that authored in the lane's lineage or in an earlier session of its own
    agent, a participant that is not active, or the holder of an open lane of a combine of this re-merge. What makes two
    sessions one agent's is `SessionSuccessions`: the binding service records, before it puts a fresh session at a
    connection's path in place of another (`ensure_session` after a lane ended, or a stale refresh), which session
    it replaced. It is local to this computer, and unreadable leases or successions choose no one (fail closed). A
    review whose original reviewer another computer's log introduced is offered first to such a live session here and
    to that reviewer only when there is none. The run controller already wakes the chosen session
    (`EventLogObserver.reviewsOffered` takes the re-merge grants of the participant behind each connection).
    - *Not covered:* an `ensure_session` refresh still replaces the session of a lane-less agent that holds an open
      review grant (the grant is then released and offered again, to the same agent first); and an agent that is live
      by its lease but never calls `get_next_action` holds the grant until its lease ends.

28. **The lower computer takes the re-merged `main` on every pull, not only the pull that brought entries** (ADR-0102
    scripted scenarios S1 and S2, defect 4: about 3 runs in 90, the lower computer held the new commit and every
    entry, its checkout clean and behind, for good). Taking another computer's head (step 5's fast-forward) was
    started only by a pull that placed entries. The attempt can end without a move for a reason that passes by itself:
    the commit is not fetchable yet, or the computer's own pass holds the integration lock. The next pull places
    nothing, so nothing tried again. The hook that starts it (`LeaderReplication.whenPulled`,
    `MemberReplication.whenPulled`) now runs after every pull that reached its peer (`LogExchange`'s `afterPull`),
    so at least every `PULL_INTERVAL_SECONDS`, and `CodeOverLink.mainMayHaveMoved` is idempotent: a head already
    met costs a comparison. The merge pass's hook (`afterMemberEntry`) stays on pulls that brought entries. The
    rules of item 5 are unchanged (only a fast-forward of a head the log names, after the audit). A refusal that waits
    for the next pull (busy integration lock, detached checkout, head not fetched) is logged with its reason.

29. **A reviewer of another computer's snapshot is asked again while that computer is connected** (s5-run14, PC and
    VM, build remaster81 = `32d430a4`, 2026-10-19: both copies of `main` equal and the logs byte-identical, but the goal's
    `gcd/` package never landed and neither run ended). *Proven from the evidence:* the PC's Claude agent consumed the
    VM's grant `f3bdcc2f` for the VM's `gcd` snapshot, was refused `read_file` and `run_command` with
    `CODE_PEER_UNREACHABLE` in four resumes between 01:01 and 01:11:36, and stopped without a verdict; its last resume,
    at the heal, was refused again. After 01:12:25 no process of the PC's run started for 48 minutes, the log stayed at
    50 entries, the grant stayed unresolved (it was revoked only when the run's end ended the connections), and the VM's
    producer and its second Codex agent waited behind that grant, as the PC's second Claude agent waited behind it
    ("another reviewer was admitted"). Both runs printed `AGENT_RUN_WAITING=FOREIGN_LANE_OPEN` until their time ran out.
    The replication journals of both computers hold no line after the first `CONNECTED` of 00:59:51: no `DISCONNECT`
    and no later `CONNECTED`, so the wake of item 23 (a session that began after the agent last looked) had nothing to
    fire on, and the heal woke the reviewer only through log entries that arrived in two batches, the second while the
    code was still unreachable. *Hypothesis, not proven:* the code became readable some seconds after the last
    resume. *Rule:* a stopped agent whose session holds an unfinished review grant on a snapshot another computer's
    agent published (`ProviderSessionTerminalizationService.holdsReviewOfAnotherComputer`, the same `grantUnresolved`
    rule as the other grant checks) is resumed again while that computer is connected, 30 seconds after it stopped, and
    after 60, 120 and so on for each resume in a row that recorded nothing (`AgentRunController.wakeFor`). The resumes
    that record nothing count against `--max-resumes` as any others; once it is spent the connection is ended, the
    reviewer is judged gone, and its review is released and offered to a live non-author by the rules of items 24 and
    27. Nothing about the authority of the review changed: the reviewer still reads the snapshot over Link and gives
    its own verdict. Scripted scenario S3 (`CrossComputerReviewScenarioTest`) fails without the rule (the controller
    prints `FOREIGN_LANE_OPEN` and resumes nobody) and passes with it. *Not covered:* a run whose journal says the
    other computer is away does not retry (it would spend the agent's resumes while nothing can be read), and the
    wake keeps no memory of an earlier refusal, so a reviewer that stops for another reason is asked again too.

30. **A re-merge review owed to agents the run controller had already closed** (s5-run16, PC owner with 2 Claude agents,
    VM member with 2 Codex agents, build remaster86 = `3dbbbf92`, 2026-10-20; a real 5-minute cut, both journals
    `LIVENESS_EXPIRED` after 60 s; FAIL: `main` PC `e523d3d5`, VM `ffd76e6e`; the fix is built, see below).
    *Proven from the evidence* (`D:\SynesisAcceptance\evidence\s5-run16`; the VM's own log has timestamps):
    - The PC's run ended `COMPLETED` after 128 s, while cut. Its log named no head of the VM's, so
      `MainMeeting.holdsEveryHead` held for the one head it knew, and no agent had a lane or a grant to come back to, so
      `progressElsewhere` had nothing to wait for (`OTHER_COMPUTER_AWAY` needs an agent that `mayComeBack`). That is the
      rule working as written: a run cannot wait for a head it cannot learn of. The PC's agents were gone from then on,
      which is lawful, since only the re-merging computer's agents review a re-merge (item 13).
    - The VM, the higher node ID, re-merged after the heal (09:54:53): `COMBINE_REQUIRED` for primes and `NOTES.md`. The
      run controller resumed codex#2 for it 25 s later and it took and finished the combine lane (09:56:13) as a new
      session, `agt_20677c3f`, on a new connection (the old one had been closed, so `ensure_session` gave a fresh binding
      and `SessionSuccessions` recorded nothing; it records only a replacement at one connection's path).
    - The pass wrote the two re-merge snapshots and their grants (09:56:14.25 and .28) to the two original reviewers
      `agt_723913f6` (codex#2's first session) and `agt_e4f2adeb` (codex#1), and **released both within 190 ms**
      (`LANE_REVOKED`, .35 and .43). Both reviewers' leases were `CLOSED_CLEANLY` since 09:52:49 and 09:53:03, before the
      heal: the controller ends the connection of an agent with no lane, request or grant to come back to
      (`AgentRunController.keepOrEnd`), so `AwayLanes.goneHere` judged them gone at once, as item 24 says it should.
    - `RemergeService.anotherReviewer` then chose among the live sessions of the VM, and the only one was `agt_20677c3f`,
      the holder of the combine lane, which it never chooses (item 27). So no grant was written again ("waits for another
      reviewer"), the combine lane stayed open for ever, and nothing was offered to either agent, so the controller had
      nothing to resume them for: `wakeFor` resumes on grants, open reviews and combines that exist for a session,
      and codex#1 had no session. The run printed `FOREIGN_LANE_OPEN`, `COPIES_MEETING`, `OTHER_COMPUTER_CONNECTED` and
      timed out at 3000 s; the grants and the participants were closed by the run's end, not by any rule.
    - With two agents a computer has no independent reviewer for both re-merge snapshots at all: the snapshot of the
      combined lane (author codex#1) can only be reviewed by codex#2, who holds the combine lane, and the other (author
      codex#2) only by codex#1, who has no session. Item 27's exclusion of the combine's holder and item 24's release of
      closed sessions together leave no one, which scenarios with four or six agents per computer never meet.
    *Hypothesis, not proven:* that the PC's stopped agents would have been resumed if the PC's run had still been running;
    its run ended 4 minutes before the heal, so nothing tested it.
    **Built 2026-10-20 as ADR-0099 fix 16** (a), (b) and (c) below, as named, plus two defects the scenario found: a run
    whose own work is closed ends no sooner than 30 s after another computer connected (`COPIES_MEETING`; it ended within
    a second of the heal, before the combine was written), and `AwayLanes.liveHere` reads the session a connection has now
    from its binding, because a lease still names the session before the one a refresh or a combine join put at the same path
    and a review offered to that name was never taken and, its lease live, never released (a re-merge grant to a lane-less
    session of this computer that no entry introduced is now released when it is gone). The combine is also refused to every
    session of its authors' agents (`CombineLaneJoin.sessionsOfAgent`), not only to the author's own handle: the fresh
    session of a resumed author was offered it. `CombineUnderRunControllerScenarioTest` is enabled and passed 4 of 4 times
    (Linux gate); the combine lands and both mains end equal with every re-merge grant consumed. Not proved in a real run.
    *What the fix holds* (decided with the invariant of item 27, *a re-merge review owed on this computer is offered to
    an agent that is live, may review it, and can see it in its next action*): (a) a stopped agent whose session was
    closed, with a re-merge snapshot waiting for a reviewer that none of its sessions authored, is resumed for it (the
    pass can only choose a session that exists; `reviewsOffered` names today only what exists for a session); (b) the
    controller records, when it opens a new connection for an agent that had an earlier one, that the new session is the
    old one's successor (`SessionSuccessions`), or the pass would offer a fresh session the review of its own earlier
    work; (c) the holder of a combine lane whose snapshot is published is shown a re-merge grant addressed to it
    (`AgentReviewActionProjection.reviewActions` shows it today only to a lane-less caller or an admitted reviewer) and the
    pass may choose it, because the original reviewer can be the combiner and the combine snapshot is, by item 22, not
    reviewed on its own. Scripted scenario `CombineUnderRunControllerScenarioTest` (two agents per computer started by a real
    `AgentRunController`) reproduces the state: both original reviewers closed, their grants released within a second, the
    combine holder the only live agent, and the other agent never woken ("waits for another reviewer").

31. **A combine opened after every agent has stopped waits for the next agents run** (designer, 2026-10-20). Under a
    real cut each computer can finish its own work and end its run before the heal (s5-run17: the VM's run ended
    COMPLETED after 244 s, during the cut); the re-merge after the heal then opens a combine with no agent running on
    either computer. It waits, as a merge conflict in Git waits for whoever works next; a run does not wait for a
    computer that may stay away for days (the premise). The lab models later work with `run.sh --restart-after-heal`,
    which now starts any ended run again after the heal. Still open (PARTIAL): with agents started again, s5-run18
    ended with two combines for one snapshot and a reviewer lane open, and the copies did not meet within the run's
    50 minutes.

32. **One combine per conflicting snapshot, whatever this copy of `main` does meanwhile** (s5-run18, VM = the
    re-merging computer). *Proven from the VM's log dump* (`log-dump.txt`, applied
    order): after the heal the pass opened `cb-943defc8…` (re-merge `rm-db2b…`, own head `969da54`, `COMBINE_REQUIRED`
    at pos 146). In the same moment an ordinary lane of the VM's codex agent (snapshot `snap_9a6d55d4…`, based on
    `969da54`, reviewed and accepted, pos 144 to 153) landed on the VM's `main`, which moved to `e262142`. A re-merge's
    ID is the digest of the other head and this copy's head (`RemergeService.remergeId`), so the move named a new
    re-merge `rm-5b58…`; it was built again, met the same conflict on the same advance, and `openCombine` derived the
    combine ID from the re-merge ID, so it wrote a second combine `cb-22126432…` (pos 159) for `snap_3e0a5e49…`. Both
    combines hold the same other head (`339011f`), base and advance head. Neither was ever taken.
    *Rule:* a combine is about the other copy's head and the advance, not about this copy's head. Its ID is derived from
    those (`other`, advance head, base), and the pass, the build, and the choice of another reviewer look combines up by
    the other head: a new re-merge caused by an ordinary advance finds the combine again, waits for it, and uses its
    result. A combine that logs written before this rule hold under another ID is found the same way. Tests:
    `RemergeServiceTest.anOrdinaryAdvanceOfThisCopyWhileACombineWaitsOpensNoSecondCombineAndTheNewReMergeUsesTheFirst`
    fails without the rule (a second `COMBINE_REQUIRED`).
    *Not changed (found, other responsibilities):* the reviewer lane `2bbb8861…` (agent `agt_3797…`, a REVIEWER lane of
    the work group `3197bbd1…`) stayed `ACTIVE` because the PC, cut off, had completed that group (its pos 143) without
    knowing a lane the VM had announced in it (pos 62); the lane's owner then wrote its verdict and review notes, but
    every finish path refuses a group that is not `ACTIVE` (`NoChangeCompletionEligibility`,
    `LaneCompletionRules.validateNoChangeGroup`: `WORK_GROUP_NOT_ACTIVE`), the transcript shows `IMPLEMENT` without a tool
    after the completion request, and a caller with an open lane is never offered a combine
    (`AgentNextActionService`, `callerIntent.isEmpty()`). Hypothesis: that holds codex#1 out of the combine and, with
    `--max-resumes` spent, nobody took it. A group closed by one computer during a cut while another computer holds a
    lane in it is a separate rule to decide. The scenario `CombineUnderRunControllerScenarioTest` also shared one leases
    folder between its two computers (leases are per project ID under the JVM's home), so the higher computer's pass
    offered a re-merge review to the lower computer's test agents; their leases are removed once they are done.
    `CombineUnderRunControllerScenarioTest` is enabled again and passed 6 of 6 times in a row (Linux gate); one earlier
    attempt failed only because the gate host was overloaded by parallel runs (the lab's invite expired). Not proved in a
    real run.

33. **A lane whose work group is no longer `ACTIVE` is told to end** (designer, 2026-10-26; the case item 32 recorded as
    "found, not changed", s5-run18: reviewer lane `2bbb8861…` of group `3197bbd1…`, which the cut-off PC completed
    without knowing the lane). A lane cannot complete into a group that is `COMPLETED` or `CANCELLED`, whichever computer
    closed it and whichever role the lane has, and nothing is integrated into a closed group: every finish path already
    refuses it with `WORK_GROUP_NOT_ACTIVE`. *Rule:* the lane's next action says so in plain words (state
    `LANE_GROUP_CLOSED`, reason `WORK_GROUP_NOT_ACTIVE`, `BLOCKED`) and projects ending the lane with the existing
    `cancel_lane`. Ending a lane keeps everything it wrote (verdicts, review notes, snapshots) as history and never
    reopens the group, and the agent then holds no lane, so it is offered a combine like any other. It is one check at the
    head of the review-lane stage (`ReviewLaneProjection.reviewLaneAction`), which the ordinary path and the
    stale-workspace path both pass first, and it answers the same for an ordinary and a finish-asked call, so no role has
    its own copy. The agent is told, never ended behind its back: the lane stays `ACTIVE` until it calls `cancel_lane`.
    Tests: `ClosedGroupLaneTest` (a lane in a `COMPLETED` or `CANCELLED` group is told to end, then, ended, is offered the
    open combine and the group stays closed) and the scenario `LaneInGroupClosedWhileApartScenarioTest` (the cut, one side
    completes the group, the other's reviewer lane is ended after the heal). Source semantics proven; not proved in a
    real run.

34. **One combine per conflicting advance, whatever either copy's head does meanwhile** (s5-run19, 2026-10-26, build
    remaster91; the VM is the re-merging computer, the PC the other copy). Result `TIME_BOUND`, `main` apart (PC
    `a9accc1e`, VM `a44cfc92`). *Proven from both log dumps* (`D:\SynesisAcceptance\evidence\s5-run19`, applied order):
    the VM wrote three combines for the one snapshot `snap_3e7f6f09…`: `cb-2eb0fbbb…` (other head `223fc5a2`, pos 134),
    `cb-c6fe733e…` (`a0235c09`, pos 148), `cb-da5d997b…` (`a9accc1e`, pos 160). Each is the PC's next head after an ordinary
    lane of the PC (pos 140 to 153): item 32 made the combine independent of *this* copy's head but still looked combines
    up by the *other* head (`combinesOnto(store, other)`), and a copy that keeps integrating moves it each time. The VM's
    own head `a44cfc92` was the same in all three, and so was the advance head they all combine.
    *Why the lane and the snapshot stayed open, and why the copies never met:* the first combine was taken (lane
    `7b166133…`, work group `832cb455…`) and its result `snap_b3864df2…` was written (pos 162, on base `223fc5a2`). The
    build then looked only for the combines of the newest other head (`a9accc1e`), found the third, which nobody had
    taken, and waited for it: the result of the first was never used, so the re-merge never landed, the snapshot stayed
    `INTEGRATION_PENDING` (a combine lane's result is merged by the re-merge, nothing else integrates it) and its lane
    stayed `ACTIVE` as a producer waiting for that landing. The other two groups (`1cfd28b2…`, `31814a29…`) stayed `ACTIVE`
    with no lane. So the same missing invariant: one combine per conflicting advance. (`snap_a0e72df4…`
    `REVIEW_REJECTED` is an ordinary rejected snapshot of the group `8c7dfc15…`, whose lane was re-done as `snap_a3ec2f8e…`;
    it is not part of this.)
    *Identity chosen:* the combine is about the advance of this computer that Git cannot merge, named by its head: its ID is
    `cb-` + digest of the advance head (`RemergeService.openCombine`), and every lookup is by `CombinePayload.theirs`, the
    same field, so combines logged under the earlier IDs are found as they are (no format change; `COMBINE_REQUIRED`
    is as before). `CombinePayload.combineId` still names the combine's work group and task.
    *Rule:* `openCombine` writes nothing when any combine names the advance; the build starts from the base of the last
    combine that has a result (`combinesWithResult`) and, when that base lacks the other copy's newest head, merges that
    head into the result last, so the result holds the combine lane's work and what the other copy took since; the
    reviewer choice excludes the holders of the combines that name the snapshots the re-merge carries
    (`anotherReviewer`). An advance whose combine has no result yet is merged by Git against the newest heads, so a
    conflict the other copy resolved itself (an equivalent change) is gone and no lane is needed; once no other copy
    diverges from this one, `closeCombines` cancels the work group of such a combine (`cancelWhenResolved`, this
    computer's lanes only, a head not here yet stops it), and a lane in it is told to end by item 33. Where one advance
    has several combines already (run 19), the one with a result is used and the others are cancelled the same way.
    *Limit:* if the other copy's newest head conflicts with the combined result (it changed the same lines again),
    the re-merge stops and is tried again when a head moves, and no second combine is opened for it. A combine lane that
    finishes after the conflict went away is recorded integrated without its tree being used (both versions are on
    `main` through Git's own merge).
    Tests: `RemergeServiceTest.theOtherCopyMovingWhileACombineWaitsOpensNoSecondCombineAndTheResultIsMergedWithItsNewestHead`
    and `aCombineWhoseConflictTheOtherCopyResolvedItselfIsMergedByGitAndItsGroupIsCancelled`, and the scenario
    `OverlapAndCombineScenarioTest.theOtherComputerGoesOnIntegratingWhileTheCombineWaitsAndOneCombineIsOpenedAndMainEndsEqual`
    (after the heal the lower computer integrates four more lanes while the combine waits: one `COMBINE_REQUIRED`, mains
    equal, the later files on both). All three fail without the change. Source semantics proven; not proved in a real
    run.
35. **A combine lane that has published nothing is not shown another combine's wait** (s5-run20, 2026-10-26, build
    remaster92; the PC re-merges, the VM is the other copy). Result `TIME_BOUND`, `main` apart (PC `b1ecfcd4`, VM
    `75d3fad4`). *Proven from the log dumps and the PC agent transcripts* (`D:\SynesisAcceptance\evidence\s5-run20`): one
    re-merge `rm-09be0f9f…` opened two combines, `cb-45df29d6…` for `snap_15bf4812…` (digits, base `5096b431`, the other
    copy's head) and `cb-6d86a1c6…` for `snap_8af36a6d…` (primes and `NOTES.md`, base `a2647ff0`, the commit the build made
    from the first combine's result). Both lanes were taken. The first agent published its result `snap_60707315…`
    (`INTEGRATION_PENDING`, as designed: a combine lane's result is merged by the re-merge). The second agent
    (`claude#2`, lane `6e34e895…`) resolved its files, ran the suite and asked to finish (`get_next_action` with
    `completionRequested`), and was answered with the first lane's wait: `combinePending`, `snapshotIds` naming only
    `snap_60707315…`, "nothing more is needed from this lane; wait". It stopped. No second combine result was ever
    written (the log has one `TASK_SNAPSHOT_CREATED` after the combines), so the re-merge, which lands only when every
    combine has a result, could not build, the first result stayed `INTEGRATION_PENDING`, and the two work groups stayed
    `ACTIVE` until the run's time ran out (the log is flat from 12:31 to 13:19; the last four entries are the end-of-run
    suspensions). Both agents were told the truth about the first lane; the second was told it about the wrong lane.
    *The missing invariant:* the integration pump has no caller, and its combine wait lists the unlanded snapshot of every
    combine lane of this computer (`IntegrationOrchestrationService`), so `AgentNextActionService.stagedAction` handed it
    to whoever fell through the earlier stages, including a producer that had published nothing. The same stage already
    withholds the pump's validation wait from such a producer (`NO_PUMP_WAIT`); the combine wait is the same kind of
    answer and was missed.
    *Rule:* `pumpShownTo` withholds both waits from a producer lane that has no snapshot yet, so its next action is its own
    (publish, or keep resolving), not another lane's wait. A lane that has published still sees the combine wait.
    *Not proved:* why the second lane's completion request offered no `finish_lane`. Every store-level condition
    (`reviewRequired`, capability obligations, `owedOwnerReply`, `awaitedVerdict`, the group, the claims) was read back from
    the PC's log dump and allows publication; what remains is the inspection of that lane's Windows worktree
    (`hasScopedPublishableChanges`), which the log does not hold. The scripted scenario with two combines, both taken and
    finished, the other computer integrating meanwhile, lands and ends with equal `main` on the unchanged code: the base of
    the second combine is not unstable (a later build starts from the newest combine that has a result, so it meets that
    base again). So this item fixes what the agent was told, not the unknown reason it was not offered publication; a
    rerun will show it (the next transcript of a second combine lane will carry the answer to `completionRequested`).
    *Wake:* unchanged: the run controller resumed `claude#2` for the second combine, and the lane was shown the right action
    as soon as `pumpShownTo` stops hiding it.
    Tests: `OverlapAndCombineScenarioTest.aCombineLaneThatHasPublishedNothingIsNotToldToWaitForAnotherCombinesSnapshot`
    (fails without the change with the answer the agent got in the run), and
    `twoCombinesOfOneReMergeAreBothTakenAndFinishedAndTheReMergeLandsAndMainEndsEqual` (passes with and without it; it holds
    the two-combine landing). Source semantics proven for what the agent is told; not proved in a real run.
36. **A lane that has published nothing is shown no wait of the integration pump** (s5-run21, 2026-10-26, build remaster93).
    The second combine lane (`6d3580a9…`, group `67b1ec4e…`) never published. *Proven from the transcripts and the VM*
    (`D:\SynesisAcceptance\evidence\s5-run21b`): the first run ended before the combines existed (`COMBINE_REQUIRED` and both
    lanes are the last log entries before the end-of-run suspensions); the restart took both. `codex#1` took the first and
    published `snap_c1cad1e3…` (`get_next_action` with `completionRequested` answered `snapshot_publication_required`,
    `finish_lane` offered, so the Windows-worktree hypothesis of item 35 is not the cause). `codex#2` (`codex-2-3.log`) joined
    the second (`ensure_session` answered `repairJoined`, `intentId 6d3580a9…`) and its only following `get_next_action` was
    answered `{"status":"waiting","reason":"integration_pending","nextAction":"wait","result":{"pending":1,"integrationRunning":true}}`.
    That answer exists only where another pass holds the integration lock (`IntegrationOrchestrationService.integrate`). The
    agent said it would wait and stopped; it never read a file or asked to finish. The lane's worktree on the VM still holds
    the unresolved `gcd/gcd.py` and `gcd/test_gcd.py`. Nothing recorded in the log ends that wait (the lock release is not an
    event), so `LaneWake` had nothing to wake it on, and the log stayed flat. Item 35 hid only the combine wait
    (`combinePending`) and the review wait; this wait of the same pump was missed.
    *Rule:* `pumpShownTo` withholds from a producer that has published nothing the waits that are for other lanes or
    for the pass (review, combine, `integrationRunning`); its own publication is its next step. (First written as every wait of
    the pump; that hid a dependency wait of the lane itself and failed `AgentNextActionServiceTest`, found in item 37.)
    *`snap_48308bf3…` waits for review with no reviewer:* it is the late snapshot of a primes continuation lane
    (`9d015423…`, task `a0519681…`), created at log position 122 after that task was already integrated through
    `snap_3631375…` and `snap_9b8e50c7…`; the task and its participant were cancelled at 123 to 128, the WorkGroup
    `3a53d01c…` is `COMPLETED`, so no reviewer can be admitted. A snapshot of a cancelled task is not closed by the
    cancellation, so `pendingReviewSnapshots()` lists it for ever and the pump tells every lane without a snapshot of its
    own `validation_required`. Not fixed here: it does not stop a re-merge landing, and closing it is a decision about what
    cancelling a lane does to its published snapshot (recorded, open).
    Test: `OverlapAndCombineScenarioTest.aCombineLaneThatHasPublishedNothingIsNotToldThatAnotherPassIsRunning` (the lane
    joins, another pass holds the lock; fails without the change with the s5-run21 answer). Source semantics proven; not
    proved in a real run.
37. **Only a combine lane is told "nothing more is needed from this lane"** (s5-run22, 2026-10-26, build remaster94). Result
    `TIME_BOUND`, copies apart (PC `d2124194`, VM `d865d1e8`). *Proven from the log dumps and the PC transcripts*
    (`D:\SynesisAcceptance\evidence\s5-run22`): one re-merge `rm-7b4f2e98…` opened two combines. Combine 1 (`cb-9c4d3e4a…`,
    `snap_f1efaa7f…`) was taken by `claude#2`'s restart session `agt_acf3f761…` and published `snap_13f2fe22…`
    (`INTEGRATION_PENDING`). Combine 2 (`cb-bb561676…`, `snap_57c895ea…`, its group `7ea467e8…` active) was opened at log
    position 114 and **no lane was ever announced in it**, so the re-merge, which lands only when every combine has a result,
    could not land. The only agent that may take it, `claude#1` (it wrote neither combine), was told otherwise: its restart
    session `agt_7f54afd0…` had been resumed as a reviewer (`codex` snapshot `snap_64e59d17…`, lane `bb8d9ff2…`), recorded its
    verdict (`respond_coordination` answered `ACCEPTED`), and its next `get_next_action` (`claude-1-3.log`, again in
    `claude-1-6.log`) was answered `{"status":"waiting","reason":"integration_pending","nextAction":"wait","result":{"combinePending":true,"snapshotIds":["snap_13f2fe22…"],"instruction":"The combined version is merged with the re-merge of the two copies of main, which lands once the original reviewer accepts it again. Nothing more is needed from this lane; wait."}}`.
    It ended its turn without finishing the reviewer lane, so the lane stayed `ACTIVE`; a session that holds a lane is never
    offered a combine (`combineCandidates` is for lane-less callers), and nothing in the log changed afterwards. The other
    agent, `claude#2`, wrote both combines' snapshots and may take neither.
    *The missing invariant:* the combine wait (item 22) is for the owner of a combine lane that has published; the pump names
    it to every caller, and item 35 withheld it only from a producer that had published nothing.
    *Rule:* `pumpShownTo` shows the combine wait only to a caller whose lane is in a combine's work group. Any other lane is
    shown its own next step (a reviewer after its verdict: finish the lane), which frees the agent to be offered the combine.
    *Not fixed:* `claude#2`'s restart session took a combine whose snapshot its earlier session wrote (the agent link of
    item 30 did not join `agt_acf3f761…` to `agt_67fb74a1…`); harmless here, noted. The `snap_13f2fe22…` wait for the
    original reviewer is by design.
    Test: `CombineWaitShownToTest.onlyACombineLaneIsToldToWaitForTheCombinesSnapshot` (fails without the change: a lane of
    another group is shown the wait). Source semantics proven; not proved in a real run.
38. **A combine lane that keeps the other copy publishes that as its result** (s5-run23, 2026-10-26, build remaster95). Result
    `TIME_BOUND`, copies apart (PC `074035dd`, VM `fb3e9adf`). *Proven from the log dump and the PC transcripts*
    (`D:\SynesisAcceptance\evidence\s5-run23`): one re-merge `rm-367bdd49…` opened two combines. Combine 1 (`cb-ec769f2b…`) was
    resolved and published `snap_2f070a82…` (`INTEGRATION_PENDING`). Combine 2 (`cb-66941cf2…`, `snap_9c6e0fd0…`, the fib
    snapshot) was taken by `claude#1`'s session `agt_96aedb10…` (`claude-2-3.log`), which resolved the conflict by keeping the
    other copy's files; its worktree then equalled the combine's base. Its `get_next_action` with `completionRequested` was
    answered `{"status":"ready",…,"nothingToPublish":true,"instruction":"This lane has changed none of its claimed files, so
    there is nothing to publish. Change them, or if nothing needs to change call cancel_lane with the reason."}`; it called
    `cancel_lane` (`{"status":"completed","result":{"task":"cancelled"}}`). The combine was left with no lane, no result and
    a `CANCELLED` work group (log positions 134 to 140), and `RemergeService.openCombine` does not open a second lane for an
    advance that has a combine, so nothing could ever take it again; the re-merge, which lands only when every combine has a
    result, never landed. Items 35 to 37 were not involved: the agent was told something false by the product itself.
    *The missing invariant:* for an ordinary lane "nothing changed" means nothing to publish; for a combine lane the
    worktree equal to the other copy is a resolution, and the re-merge needs it recorded as the combine's result (the tree
    of the combine's base).
    *Rule:* a lane in a combine's work group is always publishable (`LaneCompletionProjection.isCombineLane`): it is offered
    `finish_lane` and not `cancel_lane`, the scoped-change check of `finish_lane` does not apply, and a snapshot with no
    changed paths is accepted for it. Source: `LaneCompletionProjection.nothingToPublish`, `snapshotPublicationAction`,
    `AgentTaskCompletionService` (scope check and the `NO_CHANGES_TO_PUBLISH` refusal).
    *General rule asked for: an agent is never told to wait on an event no log entry or wake will announce.* Every
    blocker found in items 35 to 38 was an answer that ended an agent's turn on something that could not change (a wait for a
    lock release, for another lane's snapshot, a cancel instruction that closed the only way forward). It cannot be enforced in
    one place today: WAIT answers are built at about twenty sites (`IntegrationOrchestrationService`, `CapabilityActionProjection`,
    `ContractConsumerService`, `AgentNextActionService`), and whether an event "will be announced" depends on the site's
    own state. What one place can enforce is the weaker half: `LaneWake.noteWait` already records the lane view the agent
    saw when told WAIT, so a wait whose view cannot change (the caller holds no open lane, or no entry of its view is
    something the wait names) is detectable there, and could answer "no wait" instead of holding the agent. That is a design
    change to the wake contract and is not made here.
    Tests: `OverlapAndCombineScenarioTest.aCombineLaneThatKeepsTheOtherCopyPublishesThatAsItsResultAndTheCopiesMeet` (fails
    without the change at the `cancel_lane` instruction, then at `NO_CHANGES_TO_PUBLISH`; with it both copies of `main`
    meet). Source semantics proven; not proved in a real run.
39. **A combine lane that has published its result is released, so its agent can take the other combine** (s5-run24,
    2026-10-26, build remaster96). Result `TIME_BOUND`, copies apart (PC `ace60554`, VM `34c60197`). *Proven from the log
    dumps and the transcripts of both computers* (`D:\SynesisAcceptance\evidence\s5-run24`; this time the VM is the
    re-merging computer, its agents are `codex`): one re-merge `rm-ee9b2880…` opened two combines. Combine 1 (`cb-98823ac8…`,
    `snap_87650305…` and `snap_3e1c56d3…`, written by `agt_74251eeb…` and `agt_a4f32e8d…`) was taken by `codex#1`'s restart
    session `agt_4f7c327f…` (`codex-1-1.log`), which published `snap_aa6f846d…` (`{"status":"waiting","reason":"integration_pending",…,"combinePending":true,…"Nothing more is needed from this lane; wait."}`).
    Combine 2 (`cb-338fb618…`, group `44a82d5a…`, `snap_fd280e2b…`) has no lane and appears in no transcript: it was never
    offered. It was written by `agt_c63f9324…`, a session of `codex#2`, so `codex#2` may not take it; its restart session
    `agt_650124df…` (`codex-2-4.log`) was answered, lane-less, `{"status":"waiting","nextAction":"wait",…"workflow":{"type":"WAIT","blockers":["none"],…}}`
    and stopped. The only agent that may take combine 2, `codex#1`, held lane `815c3651…`, which stays active until the
    re-merge lands, and the re-merge lands only when combine 2 has a result: `codex-1-3.log` and `codex-1-5.log` are the
    same combine wait, three times. The two waits depend on each other and no log entry can end either. Nothing in items
    35 to 38 caused it; `codex#2`'s session also looked like a stranger to the author check of combine 1 (the agent link of
    item 30 did not join `agt_4f7c327f…` to `agt_a4f32e8d…`), which is why `codex#1` could take combine 1 at all.
    *The missing invariant:* a combine lane exists to produce its result. Once that is published, holding the lane only
    keeps the agent from the next combine (the re-merge, not the agent, lands it).
    *Rule:* the pass that runs the re-merge (`RemergeService.closeCombines`) releases the lane of a combine whose result is
    published and not yet landed, and completes its session (`WorkIntentService.releaseCombineResult`, one
    `WORK_INTENT_RELEASED`). The snapshot stays unintegrated and the work group open; `releaseAfterIntegration` closes the
    group when the re-merge lands, also when the lane is gone. `CombineLaneJoin.waiting` is false for a combine whose result
    stands, so it is not offered again (a refused result is offered again). The run controller already offers a waiting
    combine to the sessions of the stopped agent and resumes it on a new connection, which is then lane-less and is offered
    it. The combine wait of the pump (item 22) is shown to no one who holds a combine lane any more.
    *Not changed:* the author check stays; two agents that both wrote a combine's snapshots still cannot take it, and a
    third agent is needed (the run controller starts agents; the product does not).
    Tests: `OverlapAndCombineScenarioTest.theAgentThatPublishedTheFirstCombineIsOfferedTheSecondAndTheCopiesMeet` (one
    combining agent takes both combines; fails without the change: the second combine is never offered) and
    `theWholeTwoCombinePathNeverAnswersAnAgentWithSomethingNothingCanEnd` (the whole path with one combining agent, the
    second combine resolved by keeping the other copy, the copies meet, and every `get_next_action` answer of the combining
    agent is read back: no instruction to cancel a combine lane, no wait that names no blocker to a session that is not
    complete). The second test is the regression net for the next dead end in this path: it records every answer
    (`ScenarioAgent.answers`) and the list of dead-end shapes in it is where a new one is added. Source semantics proven; not
    proved in a real run.

### Step 6: Membership in the owner's log

Today membership is `replication.properties` on each computer; join requests live in memory
(`LinkRuntimeOwner.java:225`); node keys are Ed25519 in `identity.bin`; no owner or recovery key exists.

1. **The owner key is the owner computer's node key.** It already never leaves that computer, so no second key is
   kept. `OWNER_CHANGED` moves ownership to another node's key and is signed by the current owner or the recovery key.
2. **The recovery key:** 256 random bits shown once as 24 BIP-39 English words at `project share`, never stored; the
   words derive an Ed25519 key whose public half goes in `PROJECT_SHARED`.
3. **Entries** as in "Membership and keys". `JOIN_REQUESTED` is written by the inviting member. The owner's acceptance
   becomes `JOIN_ANSWERED`, and the join waits for it however long the owner is away, unless the owner set
   `JOIN_POLICY_SET{ANY_MEMBER}`.
4. **Links:** each member publishes a `MEMBER_CARD` (a retained invitation of its listener, as `replication.peers` keeps
   today), and dials every other member's card it can reach.
5. **`replication.properties`** keeps only `role=shared` and an optional `code.remote`.
6. **The third computer:** a Podman container with the Linux build and the VM's Claude and Codex sign-ins (owner's
   answer 14), with host networking. If the VM cannot reach it over QUIC, a clone of the VM instead.

Decided while building step 6 (designer, 2026-10-18; each open to the owner):

7. **Membership is a projection like the others.** A new stage, `MembershipProjection`, applies the membership
   entries in each computer's applied order. Whether an entry counts is judged by the same rules on the writer's own
   append and on arrival (`AppendAdmission`), and one that fails counts for nothing, as in step 4. A project with no
   `PROJECT_SHARED` is not shared: membership checks nothing there. The owner's statements come from one log, but
   they refer to entries of other logs (a request in the inviting member's log, a member's acceptance), so an arriving
   entry that refers to what this computer has not applied yet waits for it, as a missing reference does in step 4:
   an answer before its request, an entry of a computer whose acceptance has not arrived, an owner statement of a
   later revision. Every computer then ends with the same membership whatever the order. Only the old owner's line and
   a recovery change can still cross (item 12). (Corrected after the code review of step 6.)
8. **The entries** (wire codes 83 to 89): `PROJECT_SHARED` {owner public key, recovery public key or none}, written
   once by the owner's computer, whose node key it names; `JOIN_REQUESTED` {request, joiner node}, written by
   the member that holds the joiner's session (the session was authenticated with the joiner's key, and nothing needs
   the key itself, so a step-5 migration can write one for a member whose key it never saw); `JOIN_ANSWERED` {request, joiner, accepted or declined,
   revision}; `JOIN_POLICY_SET` {OWNER_ONLY or ANY_MEMBER, revision}; `MEMBER_REMOVED` {node, the head of its log the
   owner had seen, revision}; `MEMBER_CARD` {the member's retained invitation}; `OWNER_CHANGED` {owner public key,
   recovery public key or none, revision, the recovery key's signature or none}. The revision is the number of owner
   statements applied before it; a statement is written at the revision its writer holds, so an old one cannot be
   replayed later.
9. **Who may write what.** `JOIN_REQUESTED` and `MEMBER_CARD`: any member. `JOIN_ANSWERED`: the owner; under
   `ANY_MEMBER` also any member, accepting only (a decline is the owner's), judged by the policy in force at the
   revision the answer carries. `JOIN_POLICY_SET` and `MEMBER_REMOVED`: the owner. `OWNER_CHANGED`: the owner, or any
   member carrying a valid recovery signature over the project, the action, the new keys and the revision. The owner's
   answer decides and always takes its revision, even over a member's acceptance applied before it (a decline then
   removes the joiner again); a member's acceptance counts only while no answer is applied. A computer that joined
   another's project (it dialed an inviter, or holds another computer's log) never shares one itself.
10. **Members and logs.** The members are the owner and every accepted joiner not removed. A computer serves and pulls
    only members; it copies every log of anyone who was ever a member, so a removed computer's log stays complete. An
    entry of a removed computer's log after the head its removal names counts for nothing. One the computer had
    already applied before the removal reached it stays applied there (an applied entry is a fact, as in step 4); the
    removal also ends the removed computer's lanes everywhere, so its claims are freed the same on every computer.
11. **The recovery key.** 256 random bits, shown once as 24 BIP-39 English words (the standard list, 2048 words with a
    checksum), stored nowhere. The words give back the bits; SHA-256 over a fixed domain and the bits gives the 32-byte
    Ed25519 seed. `project share` prints the words. A project shared before step 6 gets no recovery key from its
    migration (nothing is shown to a person there); running `project share` again on the owner's computer then writes
    an `OWNER_CHANGED` with the same owner and a new recovery key, and prints its words. The same command rotates it.
12. **Recovery.** `project recover` on any member asks for the 24 words and writes `OWNER_CHANGED` naming that
    computer's node key as the owner's. The old owner's statements written after that change, at its revision or
    later, count for nothing wherever they arrive after it; any applied earlier stay (NOT BUILT: undoing them).
13. **Everyone pulls from everyone.** The leader and member sides become one shared side: it serves the members and
    pulls each member it is connected to. Each member writes a `MEMBER_CARD` with its retained invitation, and dials
    every other member's card it does not hold a session with. Two members that cannot reach each other still get each
    other's entries through any member both reach, because every member serves every log it holds.
14. **`replication.properties`** is written as `role=shared` and an optional `code.remote`. A step-5 file is migrated
    at the server's start: an owner's (`role=leader`) writes `PROJECT_SHARED` (no recovery key) and accepts each listed
    member; a member's (`role=member`) keeps its inviter as the first computer it dials and waits for the owner's
    entries. Compatibility 10.
15. **Before the owner's entries arrive.** A computer that holds no `PROJECT_SHARED` has no members to go by. It
    trusts the computers it dialed with an invitation (the `dial` lines of `replication.peers`): it pulls from them and
    keeps a copy of every log they serve, as a step-5 member did. Once `PROJECT_SHARED` and its acceptance arrive,
    membership decides. A computer that has neither shared nor dialed serves no one.
16. **One side, one gateway.** `LeaderReplication` is now every computer's side (its member list read from a
    `MembershipView` of the log); `MemberReplication` is no longer run, and `becomeMember` changes nothing, because the
    joiner's inviter is recorded as dialed before it is called. The class keeps its name for now; renaming it is left
    to the simplification after step 7 (ADR-0101).
17. **Joining and the commands.** A computer that holds a joiner writes `JOIN_REQUESTED` at once (sharing its project
    first if it never was), so the request outlives the process and every member sees it; `link accept` writes the
    acceptance, `link decline` the owner's decline. Every computer's invitation is a project link. `project share`
    writes `PROJECT_SHARED` and prints the recovery words once; `project add-member` lets a node in by name (a request
    and its acceptance at once) and `project remove-member` writes `MEMBER_REMOVED`. `project follow` still writes a
    step-5 member file, which stays trusted until the computer joins by a link. `coordination status` prints the owner,
    whether a recovery key is set, each member's connection and the waiting requests.
18. **Before a lane, a joined computer follows `main`.** What a step-5 member did from its settings file, a computer
    that is not the owner (as the log says) now does: it brings its checkout to the newest copy it can reach.
19. **Member cards.** A member's card is a retained invitation of its own listener whose invitation counts as used
    from the start: the listener admits any node the membership names, without a capability, so every member may dial
    the same card again and again, expired or not, and nobody else gets in with it (a joiner comes in by an
    invitation made for it). A card whose link names another computer than its writer is not dialed, and a newer card
    of a member replaces the dial of its older one. Each computer hosts
    one card once it belongs, publishes it (`MEMBER_CARD`, written again only when it changes), keeps its port in
    `replication.card`, and listens on that port again after a restart. Every 15 s it dials the card of each member
    it holds no session with; of two members the lower node ID dials first, and the other dials too after 60 s
    without a session, so a pair reachable in one direction only is still linked. A dial then redials on its own for
    as long as the member is one.

Decided after the first real run of step 6 (designer, 2026-10-19; s6-run1, owner PC and two member computers, one Codex
agent on each member, mandatory review; open to the owner):

20. **A producer whose own lane only waits for review reviews the others', whatever work group they are in.** Both
    members' agents announced a producer lane, published, and were told to wait. Neither could review: the only two
    agents each held an open producer lane, and a session holds one lane, so neither could take a reviewer lane (the
    runs printed `FOREIGN_LANE_OPEN` until their time ran out). Two computers that announce at the same moment each open
    a work group of their own (`WorkIntentAnnouncement.defaultWorkGroupId`, step 5), so even the review a producer may
    take without a reviewer lane (ADR-0089, amended 2026-10-17) did not reach across: both the offer
    (`ReviewLaneProjection.reviewPendingAction`) and the admission (`ReviewAdmissionRules`, `REVIEW_WORK_GROUP_MISMATCH`)
    were bound to the producer's own group. The rule now is: a participant that holds a producer lane and has published
    a snapshot may be admitted to review a snapshot of any work group of the project. Independence is unchanged and is
    not the group: never its own work, nor its authority lineage (`REVIEW_SELF_NOT_ALLOWED`), and a participant that
    published nothing, or holds no producer lane, is still refused (`REVIEWER_INTENT_REQUIRED`). The review is a grant
    for that one snapshot; the reviewer keeps its one lane, in its own group, and its own snapshot's wait is
    untouched. A reviewer lane stays bound to its group. The answer says: "While your snapshot waits for review, review
    snap_… for <participant>: …". The run controller wakes such an idle producer for a review it is offered
    (ADR-0099 fix 13). Proved by `ProducersReviewEachOtherScenarioTest` (two computers, one agent each, both mains
    equal), `McpProducersReviewEachOtherTest` (one computer, two groups) and `WorkIntentServiceTest`. Source semantics
    only; no real run has shown it.
21. **A session that predates the accept is used and recorded like one made after it** (scripted scenarios
    `ThreeComputersMainMeetingScenarioTest` and `TakeoverScenarioTest`, 2026-10-19). A joiner dials, and its session is
    attached before the owner accepts it; likewise a member's session with a computer that is admitted later. The
    exchange kept such a session and used it for logs from the accept on, but `LeaderReplication` kept its own session
    map and recorded only the sessions of nodes admitted at attach time. So the owner's code-over-Link peers
    (`liveSessions`) lacked every session that predated its admission, the owner never fetched the others' newer `main`
    (restarting it re-attached the sessions and cured it), and `replication.members` never named the joiner, so
    `AwayLanes.away` judged nobody away and no lane could be taken over. The rule now is: `LeaderReplication` keeps and
    records every attached session whatever the node's standing; admission is read afresh when the sessions are used
    (`liveSessions`, the exchange's serve and pull, and the members file, which names only current members and is
    re-evaluated every 2 seconds, written only when its lines change). A session of a computer that is not admitted
    still carries nothing: it is served and pulled nothing, and is not offered to Code over Link. Source semantics
    proven by the two scenario tests above (previously disabled); no real run has shown it.
22. **A peer is recorded as not connected when its exchange goes unanswered, not only when its session ends**
    (s7-run3, 2026-10-20, remaster84: PC owner, VM member; the VM cut the Link with a firewall that dropped every packet
    in both directions at 10:08:37). Ten minutes later the PC's `replication.members` still read the VM `CONNECTED`
    from before the cut, so the PC never judged the VM away and its idle agent was never offered the VM's stalled lane
    (step 7 item 9). s5-run14 showed the same blind spot: no `DISCONNECT` in either computer's `replication.log` during
    a five-minute split. `LeaderReplication` recorded a member only from the attach of its session until the end of that
    session; the exchange's own verdict (a pull that gets no answer sets its peer `DISCONNECTED`) was kept in
    `LogExchange` and never reached the record, and a session that Link still holds open is never judged by its end.
    Whether Link ever ends such a session on the dialling side or the hosting side was not proven by this change (the
    real runs show `LIVENESS_EXPIRED` after about 60 s in one run and no end in another); the record no longer depends on
    it. The rule, the same on the owner and on every member, whichever side dialled: **a computer records a peer as not
    connected within the liveness bound after traffic to it stops, and as connected again when a session to it works
    again.** The bound is one pull interval plus the wait for its answer (`LogExchange.PULL_INTERVAL_SECONDS`, 15 s, then
    Link's 5 s limit on an exchange, 8 s at the outside): at most about 25 s, with the file's own 2 s recheck, and it is
    the same traffic that already keeps a path through a home router open. `replication.members` is therefore a function
    of two things: a live attached session, and what the last pull over it said. A session's first pull is always
    reported (the exchange forgets what the last session's pulls said when a new one is attached, except a diverged log,
    which stays diverged until a person looks), so a session that is attached and silent from the start is recorded
    `DISCONNECTED` after its first pull fails and a replaced zombie does not hide the new session. A node that is not
    admitted is not pulled, so nothing is known of it and nothing is recorded as lost: a joiner accepted later keeps the
    optimistic `CONNECTED` of its attach until its first pull says otherwise (item 21), instead of looking away for up
    to a pull interval after the accept. The record reads the exchange's state afresh instead of trusting the order of
    its notices. Nothing else changes: admission, serving and the claim lock still use the live-session test, so a
    claim still waits at most five seconds for a peer that does not answer (item 4 of step 4). Proved by
    `LinkReplicationTest` (a silent path on a session Link keeps open, both sides, and a new session while the silent
    one is held open) and by `TakeoverScenarioTest` with the owner as the taker of a member's lane over a silent split
    (`ScenarioLab.splitSilently`). The same scenario with the joiner as the taker of the owner's lane judges the owner
    away and offers the lane, but then the joiner's agents fail to bind a session (`SESSION_BINDING_FAILED`, then
    `SESSION_RECOVERY_STATE_UNAVAILABLE`) while its session to the owner is silent and open; that test is disabled,
    OPEN, undiagnosed. Source semantics proven for the record; no real run has shown it. The run controller's wait (`EventLogObserver.otherComputerConnectedSince`) now reads the same record, not `replication.log`, so a run does not
    keep waiting for a computer that the takeover already counts as away.
    **s7-run4 (2026-10-20, remaster85, the build with this rule): the rule did not fire, and the evidence says why not here.** The
    PC's record still read the VM `CONNECTED` from the join, and neither computer's `replication.log` shows a `DISCONNECT`, in a 10-minute
    "cut" during which both computers' own checks (Link's 30 s expiry on each side, and the pulls every 15 s that the record now follows)
    reported a healthy path. Both records had in fact moved once, at the join (the `since` of each is the first answered pull after
    the accept, later than the attach), so the rule appears to be live in the product. A path that really dropped every datagram fails both checks in
    under a minute (s5-run11, run12 and run13, 2026-10-18: `DISCONNECT ... LIVENESS_EXPIRED` on both sides after 60 s). On this evidence the
    cut of s5-run14, run15, s7-run1, run3 and run4 did not reach the path Link used; that is a lab fault to check on the VM (the rule set
    that `partition.sh` leaves, and the peer address of the QUIC flow during the cut), not proven here. The scripted scenarios cannot
    drop datagrams, and the Linux gate's container has no `iptables`, so the silent path is simulated at the exchange (no answer on a
    session that stays open); a pull has its own deadline whichever way the silence comes, so the verdict does not wait on Link. One
    hardening came out of reading the exchange for a way it could stop reporting: the two periodic rounds (pull and notice) are now
    kept alive after a round that throws, which `scheduleWithFixedDelay` would otherwise never run again.

### Step 7: Disputes, takeovers and the trash

1. **Disputes:** both reviewers are told in their next answer and woken (ADR-0099); a reviewer's new verdict replaces
   its own. The UI shows the dispute, the messages and the end, in the Coordination view.
2. **"Stalled"** is computed by each computer for itself (designer item 4). A stalled lane of an away computer is
   offered to this computer's agents as a continuation candidate, as recovery candidates are today; no agent is
   assigned.
3. **The takeover** is the taker's own `LANE_CONTINUATION_ACCEPTED`, from the last handed-in snapshot that reached this
   computer, or the lane's base (owner's answer 13). Today's continuation needs a `SUSPENDED` or `RECOVERY_HELD`
   source (`CollaborationProjection.java:861`); a new source state, away and stalled, is judged by the taker's
   computer.
4. **The returning agent** is told `LANE_TAKEN_OVER`: stop, release, close, with its own entries. Its computer commits
   the lane's worktree to `refs/synesis/trash/<lane>/<epoch>` and writes `WORK_TRASHED{lane, epoch, commit, reason}`.
5. **The trash** also takes late work for a met goal and a disputed snapshot its author gave up on. A Trash tab lists
   it; "Restore" opens a new lane from it (`TRASH_RESTORED`); "Empty" is a person's action, confirmed in the UI or with
   `synesis trash empty`, and is the only place a ref is deleted (`TRASH_EMPTIED`).

Decided while building step 7 (designer, 2026-10-18; each open to the owner):

6. **Telling the parties of a dispute.** Each reviewer with a verdict on a disputed snapshot, and its author, finds a
   `reviewDispute` in its next answer: the snapshot, each reviewer's verdict, and what to do: message the others
   (`send_message`, ADR-0093) and, on changing one's mind, record a new verdict on the same grant. A stopped reviewer
   or author is resumed once for each new state of the dispute (ADR-0099). Messages need the sender to hold a lane;
   a reviewer without one is told so, and its verdict still counts (NOT BUILT: messages from a computer's agent with no
   lane).
7. **A new verdict replaces the reviewer's own.** While a snapshot is disputed, the reviewer of a grant may record
   another verdict on that grant; it replaces its own, and the projection judges the snapshot again (agreed accepted,
   agreed rejected, or still disputed). Once the verdicts agree, or the snapshot is integrated, a verdict is final as
   before. The replacement is bound to the grant and snapshot it was given for, not to the author lane's current
   epoch, which a rejection had moved on. The review workspace is not opened again for it.
8. **The record of a dispute.** The projection remembers every snapshot that was ever disputed. The Coordination view
   gets a Reviews tab listing them: the snapshot, its author, each reviewer's latest verdict, how it stands (disputed,
   agreed accepted, agreed rejected). The messages between the reviewers and the author stay in the Conversations
   tab, under the author's lane.
9. **"Stalled", for this computer.** None of this computer's agents holds an open lane in an active work group,
   while such a group still has an open lane whose computer is not connected to this one now. "Connected" is this
   computer's record of its member sessions (`replication.members`, written whole by its replication side; not the
   rotating journal, which keeps 100 lines and can lose one): another computer is away when that record says its
   session ended, its exchange gets no answer (step 6 item 22), or it names no session of it. With no readable record nobody is away, so a takeover of a computer that
   is in fact connected cannot follow from a missing file. A redial within Link's retained time changes nothing. No
   timer. The offer, the takeover and the release of reviews use the one test (`AwayLanes.stalled`). The taker's
   computer judges it again when the takeover is asked for, outside the append lock; a computer that connects in that
   moment meets the takeover as two takers of one lane meet (ADR-0100 row 8). A review the caller may take still comes before any continuation, as for recovery
   candidates (`continuationsFirst`); a re-review or a combine is a lane or grant of this computer, which keeps it
   from being stalled only once taken.
10. **The offer.** When this computer is stalled, the lanes of computers that are not connected are offered to its
    lane-less agents as continuation candidates, beside the recovery candidates (`formerOwnerState` `AWAY`), with the
    same request. No agent is assigned.
11. **The takeover.** Taking one writes the taker's own `LANE_CONTINUATION_ACCEPTED`. Its recovery reference names
    the lane's last snapshot that reached this computer (`snapshot:<id>`), or the lane's base (`base:<commit>`); the
    actor scopes are empty, since no session of the away computer is here. The new lane keeps the task, claims,
    group and lineage at the next epoch; its worktree, made from this computer's `main` as every lane's is, gets
    everything the snapshot changed against the lane's base, uncommitted, with Git's markers where it conflicts (as
    a combine lane's does). Any provider may take a lane over: no session is continued. Another computer's lane held
    for recovery is not offered here; its recovery snapshot is in that computer's folders. A
    source owner that is not held for recovery is lawful for such a continuation when another computer introduced it
    and the reference names that lane's snapshot or base; whether that computer was away and stalled is judged by the
    taker's computer when it writes the entry, and is not judged again where the entry arrives.
12. **The returning agent.** An agent whose lane another computer took over finds `laneTakenOver` in every answer
    (the successor and its owner): stop; its claims ended with the lane; close the session. Its own computer, seeing
    the takeover, commits everything in the lane's worktree, committed or not, to
    `refs/synesis/trash/<lane>/<epoch>` and writes `WORK_TRASHED` (reason `TAKEN_OVER`), once, whether or not the agent
    calls in: the sweep runs in every integration pass (the merge driver runs one at an interval on every computer),
    the ref first and then the entry, so a crash between them leaves a ref the next pass names. With no worktree left,
    the lane's newest snapshot is kept. A lane it had already integrated on its own `main` meets the taker's at the
    re-merge, as the ADR says. The takeover is found whether or not the taker's lane is still open: a taker that
    finished before this computer came back still leaves the lane taken over. (Corrected, scripted scenario S4, DEFECT 5,
    2026-10-19: "every answer" includes the stale-workspace answer a returning agent gets first, because `main` moved under
    its worktree, and the first answers of the session that replaced the one that held the lane (`ensure_session`
    gives the agent a new session): both notices, `laneTakenOver` with `trashRef` and `trashedWork`, are added in one
    place, `AnswerNotices`, over the agent's whole session lineage (`SessionSuccessions`).)
13. **The trash entries** (codes 90 to 92): `WORK_TRASHED` {lane, epoch, task, group, commit, reason},
    `TRASH_RESTORED` {lane, epoch, the new work group}, `TRASH_EMPTIED` {lane, epoch}. Each is written by the computer
    whose person or agent acted; `WORK_TRASHED` only by the computer that put the lane's owner on the log. Work is
    trashed once per lane and epoch; restoring or emptying names work in the trash, and an arriving entry that names
    work not arrived yet waits. A trashed commit is named by its entry and served under its trash ref, so every
    computer can fetch it over Link; an emptied one is no longer served. An agent finds its own trashed work in
    `trashedWork` in every answer.
14. **What else goes to the trash.** The same sweep takes, for each lane of this computer whose work group is no
    longer active, its newest snapshot when it was not integrated: one still disputed or rejected was given up
    (`DISPUTE_GIVEN_UP`); any other came after the goal was met (`LATE_FOR_MET_GOAL`). The snapshot's commit is kept
    under the trash ref, and its agent is told.
15. **Restore** is a person's action (the Trash tab, or `synesis trash restore`): it writes a work group of its own,
    with the original's review policy and goal, and `TRASH_RESTORED` naming it. The group's ID follows from the work
    and how often it was restored, so a repeat after a crash finds it. A producer lane announced in it starts with
    everything the trashed commit holds against the trashed lane's base applied, uncommitted, to its worktree, as a
    takeover's does; a commit trashed on another computer is fetched over Link first.
16. **Empty** is a person's action, confirmed in the UI (and by the endpoint, which needs `confirmed`) or with
    `synesis trash empty --yes`: it writes `TRASH_EMPTIED`, then deletes the trash ref on this computer; every
    computer's sweep deletes its own copy of an emptied ref, this one's too after a crash between the two. It is the
    one deletion of work the "nothing taken back" fitness function allows (`NothingTakenBackArchitectureTest`: only
    `WorkTrash` deletes a trash ref).
17. **Stalled reviews.** Taking a lane over carries its owner's review grants to the taker, as every continuation
    does. A review held by a reviewer of an away computer that holds no lane (since ADR-0089 a producer reviews
    without one) is released instead: while this computer is stalled, its integration pass revokes that grant
    (`LANE_REVOKED`, judged again under the append lock) when no verdict is recorded and the snapshot is still under
    review, and a reviewer here may then be admitted. A reviewer of this computer is released by its own session,
    stalled or not (step 5, item 24). A revoked grant holds no review. A verdict the away reviewer writes before the release reaches it
    stands beside the new reviewer's, and the two may meet as a dispute; one written after is refused
    (`REVIEW_GRANT_REVOKED`). NOT BUILT: a re-review offered to an away original reviewer alone, and a review request
    accepted on the away computer whose grant never reached this one.
18. **Reviewer lanes and the report rule.** A reviewer lane that cannot close without publishing a report (no-change
    is refused) keeps its work group open; step 7 does not change that rule (the owner decides). It touches it twice:
    such a lane is an open lane for the stall test of the computers that are not its own, and a report published after
    the group completed goes to the trash.

19. **An agent whose only lane waits for its review cannot take a lane over** (s7-run5, 2026-10-20, remaster86: PC owner
    with one `claude` agent, VM member with one `codex` agent; the VM cut the Link for 600 s the moment its agent announced a
    lane). VERIFIED from the PC's evidence: Link ended the session at 09:12:57 (`DISCONNECT ... LIVENESS_EXPIRED`), the cut
    was real; the PC's agent had published `primes` at 09:12:25 and held that lane, `REVIEW_PENDING`, for the whole cut;
    the transcript shows no resume between 09:12:30 and the heal at 09:22:25, and the first answer after it was the
    unchanged `waiting / validation_required`. So the PC was not stalled by item 9 (its one agent held an open lane, and
    `AwayLanes.stalled` counts a lane that only waits for review as held), and its agent could not have taken a lane over
    if it had been (a session holds one lane for its life; `RecoveryContinuationProjection.continuationBlock`
    `CURRENT_PARTICIPANT_HOLDS_A_LANE`, and a takeover materialises the snapshot into the taker's one worktree). The
    `STALLED` end of the PC's run followed the heal, not the cut. This is the design as written, not a missing offer: the
    expected "owner's idle agent" did not exist. It is also the right outcome for one agent per computer: the taker's
    own `fib` lane would need a reviewer that is not the taker, and none is on the PC. A takeover during a cut needs a
    lane-less agent on the taker's computer, and a second one to review for the lane to land. The lab for step 7 therefore
    runs two agents on the PC (the one that is lane-less reviews the other's lane; both are then lane-less and offered the
    lane), or starts the PC's agent only after the cut has begun. NOT DECIDED, for the owner: letting an agent whose lane only waits for review end
    that lane's session to take another (it would give up admitting its reviewer and any rework of the snapshot, and a
    taker's lane would then wait on the same missing reviewer). The one defect found while reading the run controller for
    this, and fixed, is ADR-0099 fix 15: a stopped lane-less agent was never woken for a takeover offer.

## Build order

Each step is small, works on its own, and ends with a real run on this PC and `synesis-peer-vm` (scripts in
`D:\SynesisAcceptance\`, disposable projects, Synesis used normally in them). **Every done-when runs with no Git remote
at all.** A remote is added only where a step says so, as a mirror. ADR-0100's lab scenarios S1 to S5 are the shared
runs. Compatibility goes up by one in each step that changes a format sent between computers.

**Prerequisite, not a step of this ADR: waiting is not absence.** The overnight stall (`docs/agent/CURRENT.md`,
2026-10-17, causes 1 to 3) is fixed first, under ADR-0099. Every run below needs agents that wait for a review without
losing their lanes.

1. **The compatibility number.** DONE.
2. **One fixed leader.** DONE 2026-10-16 (run 7), with a Git remote. The baseline.
3. **Code over Link.** Link becomes the path code always takes. DONE 2026-10-18 (remaster66, `1ebeb275`): run 7's goal
   COMPLETED on the PC and the VM with no Git remote, `main` equal on both (`6ff0c11`); a 450 MB history joined in four
   pieces, cut in the middle of the first and resumed from chunk 151 (`docs/agent/CURRENT.md`, 2026-10-18).
   - **Built:** the Link code mover ("Code over Link"); `code.remote` optional; a reviewer fetches a snapshot from its
     author's computer, the leader fetches before it merges, and members follow `main`, all over Link; joining fetches
     the repository over Link.
   - **Done when:** run 7 again with no Git remote: the WorkGroup completes on both computers and `main` is equal on
     both. A 450 MB history joins over Link in three or more pieces through an interruption. Compatibility 7.
4. **Each computer writes its own log, and claims lock between connected computers.** DONE 2026-10-20: under real Link cuts (s5-run16 to s5-run18, s7-run5, s7-run6; both computers logged `LIVENESS_EXPIRED` about 60 s in) each kept claiming, publishing and reviewing apart, and after the heal both held byte-identical logs. Correction: the cuts of 2026-10-19 (s5-run14, s5-run15, s7-run1) and s7-run3/s7-run4 did not reach the Link (the PC had rotated to an IPv6 address `partition.sh` did not block; fixed 2026-10-20 by blocking the LAN prefix), so they were connected runs.
   - **Built:** own `events/` plus `peers/<node>/events/`; the local applied order; notice and pull per log; the
     proposal path removed; a claim's answer waiting for connected computers (designer item 1). Every projection throw
     an arriving entry can reach becomes "both stand" or "counts for nothing, reported" (ADR-0100's table; the sites
     are under "Unresolved"). Review verdicts kept per reviewer, with `REVIEW_DISPUTED` in either order and a disputed
     snapshot counted as rejected; duplicate operator raises and duplicate COMPLETED counted once; an answer that
     arrives before what it answers kept until that arrives. Merging still happens on the owner's computer, with its
     exemption from attribution. Members still come from the role file.
   - **Done when:** while connected, a claim on the VM that overlaps a PC claim is refused. Then S1 with the VM's cable
     cut for 20 minutes, plus one overlapping claim and one review given on both sides during the cut. Both sides keep
     claiming, publishing and reviewing, and no agent sees `LEADER_UNREACHABLE`. Within one pull after reconnecting,
     both computers hold byte-identical copies of both logs and show the overlap and the dispute the same way. The
     disputed snapshot stays off `main`. Compatibility 8.
5. **Each computer merges its own lanes, and the copies of `main` meet peer to peer.** PARTIAL 2026-10-20 (owner's rule 16): connected, the copies meet and goals complete (s7-run1, s7-run6); under a real 5-minute cut the copies re-merge and a combine is opened, but in s5-run16, s5-run17 and s5-run18 the two copies of `main` did not end equal (designer items 30 and 31). The scripted scenarios S1, S2, S3 pass; `CombineUnderRunControllerScenarioTest` is disabled (1 in 3). Update 2026-10-26: after items 32 to 34, s5-run20 (remaster92, real 5-minute cut) opened exactly one combine per conflicting change and both copies held the whole goal, but one combine's result stayed `INTEGRATION_PENDING`, two combine lanes stayed open, and the copies of `main` did not end equal (TIME_BOUND); still PARTIAL. s5-run21 (remaster93, after item 35): stopped by hand after 10 minutes with nothing moving — one combine open and not taken, one snapshot REVIEW_PENDING, the copies of `main` different; evidence in `D:\SynesisAcceptance\evidence\s5-run21`. The owner set a stop on further fix-and-rerun rounds (2026-10-26): step 5 stays PARTIAL until it is taken up again, likely after ADR-0101.
   - **Built:** the merge pass on every computer; sharing `main` over Link; the diverged-copy rule (designer item 5);
     one merge commit per integration; re-review of each re-merge by the original reviewer; the combine lane through the
     repair path; "on `main`" read from each copy; attribution with no exemption, and integration entries written only
     by the lane's own computer; a Git remote as an optional mirror.
   - **Done when:** S1 with both sides merging during the cut. After reconnecting, the higher node ID re-merges, the
     original reviewers review again, the tests pass, and `main` is equal on the PC and the VM. S2 (one file both sides
     change) ends with a combine lane, reviewed and merged. S5 (the VM's server killed during a re-merge) ends the same
     as S1. Then S1 once more with a bare repository as a mirror, made unreachable halfway: the result is the same, and
     the mirror catches up when it is back. Compatibility 9.
6. **Membership in the owner's log, and a third computer.** DONE 2026-10-19: s6-run2 (remaster83) with the owner's Synesis stopped 10 minutes: the two members worked and reviewed each other through member cards, all three mains and log sets equal after the owner came back; the owner removed the third computer and `MEMBER_REMOVED` reached both members. Overlap refusal while the owner is down, a removed member's late entry and a reboot reconnect are proven by scripted scenarios only.
   - **Built:** the membership entries above, durable join requests, the owner and recovery keys, `MEMBER_CARD` and
     dialing every member, `role=shared`.
   - **Done when:** a third computer (a Podman container on this PC if the VM reaches it, otherwise a clone of the VM)
     joins by the one link and the owner accepts on the PC. Then Synesis on the PC is stopped; the PC itself stays on,
     because the container runs on it. The VM and the third computer keep working together: each sees the other's
     claims, an overlapping claim is refused, and their copies of `main` meet. With Synesis on the PC started again,
     all three copies of `main` become equal. The owner removes the third computer: its open work ends, and an entry it
     writes afterwards is copied and counts for nothing. Compatibility 10.
7. **Disputes, taking over stalled work, and the trash.** DONE 2026-10-20: s7-run6 (remaster87) RESULT=PASS under a real 10-minute cut: the VM's agent stalled on an announced lane, the PC's idle agent took it over (`LANE_CONTINUATION_ACCEPTED`, written by the PC during the cut), after the heal the VM wrote `WORK_TRASHED` for its version, all four packages and NOTES landed, `main` equal, logs identical. The returning-agent notice is proven by `TakeoverScenarioTest` (in the run the stalled agent was replaced by a new one, which has no notice to receive). A dispute stays source-level.
   - **Built:** agents of a dispute told to settle it by messages, and a new verdict replacing a reviewer's own;
     the dispute and its end shown in the UI; the stall test (designer item 4) and takeover through lane continuation;
     the reconnecting agent told to stop, release and close; the trash (designer item 6) for late work and for a
     taken-over version.
   - **Done when:** S3 (a review dispute) ends with the two reviewers agreeing by messages, or with the snapshot counted
     as rejected, and the UI shows either. A lane of the VM is taken over on the PC while the VM is cut off and nothing
     else is left to do. When the VM reconnects, its agent stops, releases its locks and closes its lane, and its
     version is listed in the trash. Late work for a met goal lands in the trash.

The two-homes run with the friend's PC is repeated after step 5 as a check, not a done criterion.

## Fitness functions

- **Nothing taken back:** no code path deletes or renames a file in `events/`, in `peers/`, or under `refs/synesis/`,
  except a person emptying the trash.
- **Every copy is the writer's:** a log copied through any computer is byte-identical to the writer's, and every entry
  in `peers/<node>/events/` is signed by that node.
- **Arriving entries always apply:** for every entry type, applying an entry from another computer's log never throws.
- **Order does not decide:** a property test applies the same logs in random arrival orders, each log in its own order,
  answers before requests included. Every decision is the same: which claims overlap, which reviews count, disputes,
  operator requests, WorkGroup status, membership.
- **Connected claims lock:** no two active overlapping lanes exist whose computers were connected when the second
  claim was made.
- **Own participants only:** no entry in one computer's log acts for a participant another computer introduced.
- **`main` moves only forward:** no copy of `main` is ever moved to a commit that does not contain its old head, and no
  push to a mirror uses force or a `+` refspec.
- **Copies converge:** a property test over random merges, cuts and meetings ends with every copy of `main` equal.
- **`main` is audited:** every integration and re-merge on `main` names a snapshot with an accepted review by a
  non-author, where review is required, and a passed validation. Checked on every fetch of `main`.
- **No remote needed:** the whole build order's tests pass with no Git remote configured.
- **No other computer's clock:** no decision reads the clock of an entry another computer wrote, and no liveness of
  another computer's agents is judged.
- **Short appends:** no lock is held across a Git or process call, the bundle commands included.
- **Nothing pushed into a computer:** the Link code mover runs no `git push` toward a peer and no command a peer chose.

## Unresolved

- **Left by the code review of step 6** (2026-10-18), each recorded rather than built: before `PROJECT_SHARED`
  arrives a joiner trusts its inviter about who the owner is (the project link does not name the owner); a
  re-admitted computer's removal head is forgotten; an old recovery statement stays usable until the recovery key is
  rotated; a member that runs `link accept` before the owner answers gets `MEMBER_NOT_SAVED` rather than "waiting for
  the owner"; `MembershipView` replays the log in a store of its own and serves a removed member for up to half a
  second; `MemberReplication`, the settings-based `LeaderReplication` constructors and `becomeMember` are dead in
  production; `LinkRuntimeOwner` has grown past 1,200 lines and its card code could be a class of its own; the
  `MemberAdmission` callbacks other than `admit` are defaults, so another implementation could skip the durable
  request. For the simplification after step 7 (ADR-0101).

- **Left by the code review of step 7** (2026-10-18). For the owner: what the main audit (step 5) does with a
  verdict that arrives after its snapshot was integrated on another computer. It can happen when an away reviewer's
  verdict, written before its review was released (designer item 17), arrives after the new reviewer's acceptance
  was integrated; the audit then finds a verdict that is not an acceptance and refuses that copy of `main`. One rule
  that does not depend on order: each reviewer on a grant not revoked has accepted. Not changed here, since it changes
  the audit. Also recorded rather than built: the takeover's reference is told apart by its `snapshot:` / `base:`
  prefix rather than a typed source; three read paths still build the collaboration view without this computer's
  node ID (so they list another computer's recovery-held lanes, which only that computer can continue); the Trash view
  lives in `App.tsx`, which was past 1,000 lines already; `NothingTakenBackArchitectureTest` covers the trash refs,
  not yet the logs and the other `refs/synesis/` refs. For the simplification after step 7 (ADR-0101).

- **Every throw an arriving entry can reach.** Classified on 2026-10-18 in ADR-0100, "Every rule an arriving entry
  can reach", and built in step 4; what still depends on order is designer item 18.
- **The diverged-copy rule with three or more copies** is reasoned, not proven. The property test of step 5 checks it.
- **A claim-lock test that failed once under load.** `ClaimLockTest.ofTwoOverlappingClaimsMadeAtTheSameMomentExactly
  TheLowerNodesStands`, race 3, failed in the whole Linux gate on 2026-10-18 (31ac93cc): both overlapping claims
  stood (the member's claim got no `OVERLAPPING_CLAIM`). It passed alone five times in a row. Looked at on
  2026-10-18: not a race in the product. The test announces each claim in a scratch store of its own, so neither
  computer's log holds either claim, and the only thing that can make one give way is the other's check being under
  way at the same moment (`LogExchange.checking`). On a loaded machine one thread's whole check can finish before the
  other's starts; then neither sees the other and both stand. In the product a claim is in its computer's log before
  it is checked, so a check that comes second finds it (`judgeClaim` reads the log) and exactly one stands, which
  `ofTwoAgentsClaimingAtTheSameMomentThroughTheServicesOnlyOneClaimStandsAndTheOtherIsReleased` covers through the
  services. Left as it is: the test could count a race in which both stood as one where the two checks did not
  overlap, and require enough overlapping ones. (FLAKY, test only, step 4.) Fixed on 2026-10-19 (293947ad): each
  side registers its claim before the race, as `LaneClaimService` does through `ClaimLock.announce`; 200 races under
  load passed.
- **Both claims giving way, once, under load.** In one 200-race run on 2026-10-19 (several gate containers at once),
  `ofTwoAgentsClaimingAtTheSameMomentThroughTheServicesOnlyOneClaimStandsAndTheOtherIsReleased` failed at race 140 with
  neither claim standing, both refused `OVERLAPPING_CLAIM` by the lock. Safe (no overlap stands) but not live (both
  agents are refused). Hypothesis, not proven: `ClaimLock.announce` swallows an `IOException` (2 s connect, 12 s request
  on loopback), so a claim sits in the log unregistered until its check starts, and `judgeClaim` answers a question
  arriving in that gap `OVERLAPPING_CLAIM` even on the higher node. Pinning it needs the order of register, append and
  check per side logged, or a failing `announce` injected. (OPEN, step 4.)
- **Two mcp tests that failed once in the Windows gate** on 85c85b82 (2026-10-18), while other checkouts ran tests:
  `McpSyn039SliceTest.dirtyReviewerReceivesDurableReviewDecisionAfterControlCheckoutAdvances`
  (`MANUAL_ATTESTATION_REQUIRED` in its own setup) and
  `SyntheticTwoProcessCollaborationTest.anUnchangedCommitPublishedAgainForANewContractRevisionReachesEveryReboundConsumer`
  (a worktree under the shared test-state folder was not a Git repository). Both passed on Linux at the same commit, and alone on Windows with a state folder of their own
  (`-PsynesisTestState`, 23 and 24 tests). Read as shared-state flakes: every checkout's Windows tests use
  `Temp\synesis-test-state` unless told otherwise. (FLAKY, environment.)
- **A third computer for step 6:** whether QUIC from the VM reaches a Podman container on this PC.
  (REQUIRES-MEASUREMENT.)
- **Home routers:** the UDP mapping lifetime, against the 15 s pull. (REQUIRES-MEASUREMENT.)
- **How often overlaps, re-merges and combine lanes happen** in real runs, and how much re-review they cost.
  (REQUIRES-MEASUREMENT, steps 4 and 5.)
- **Log and repository growth** over months, with every computer holding everything. (UNKNOWN.)
- **Code over Link sizes:** the ratio of disk usage to bundle size, and the time to write a 200 MB bundle on a laptop.
  (REQUIRES-MEASUREMENT, step 3.)
