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
    pass builds a new one. A rejected re-merge is left as it is and reported; another reviewer when the original one
    cannot be reached (designer item 2) is NOT BUILT.
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
   session ended or names no session of it. With no readable record nobody is away, so a takeover of a computer that
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
    finished before this computer came back still leaves the lane taken over.
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
    review, and a reviewer here may then be admitted. A revoked grant holds no review. A verdict the away reviewer writes before the release reaches it
    stands beside the new reviewer's, and the two may meet as a dispute; one written after is refused
    (`REVIEW_GRANT_REVOKED`). NOT BUILT: a re-review offered to an away original reviewer alone, and a review request
    accepted on the away computer whose grant never reached this one.
18. **Reviewer lanes and the report rule.** A reviewer lane that cannot close without publishing a report (no-change
    is refused) keeps its work group open; step 7 does not change that rule (the owner decides). It touches it twice:
    such a lane is an open lane for the stall test of the computers that are not its own, and a report published after
    the group completed goes to the trash.

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
4. **Each computer writes its own log, and claims lock between connected computers.**
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
5. **Each computer merges its own lanes, and the copies of `main` meet peer to peer.**
   - **Built:** the merge pass on every computer; sharing `main` over Link; the diverged-copy rule (designer item 5);
     one merge commit per integration; re-review of each re-merge by the original reviewer; the combine lane through the
     repair path; "on `main`" read from each copy; attribution with no exemption, and integration entries written only
     by the lane's own computer; a Git remote as an optional mirror.
   - **Done when:** S1 with both sides merging during the cut. After reconnecting, the higher node ID re-merges, the
     original reviewers review again, the tests pass, and `main` is equal on the PC and the VM. S2 (one file both sides
     change) ends with a combine lane, reviewed and merged. S5 (the VM's server killed during a re-merge) ends the same
     as S1. Then S1 once more with a bare repository as a mirror, made unreachable halfway: the result is the same, and
     the mirror catches up when it is back. Compatibility 9.
6. **Membership in the owner's log, and a third computer.**
   - **Built:** the membership entries above, durable join requests, the owner and recovery keys, `MEMBER_CARD` and
     dialing every member, `role=shared`.
   - **Done when:** a third computer (a Podman container on this PC if the VM reaches it, otherwise a clone of the VM)
     joins by the one link and the owner accepts on the PC. Then Synesis on the PC is stopped; the PC itself stays on,
     because the container runs on it. The VM and the third computer keep working together: each sees the other's
     claims, an overlapping claim is refused, and their copies of `main` meet. With Synesis on the PC started again,
     all three copies of `main` become equal. The owner removes the third computer: its open work ends, and an entry it
     writes afterwards is copied and counts for nothing. Compatibility 10.
7. **Disputes, taking over stalled work, and the trash.**
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
  overlap, and require enough overlapping ones. (FLAKY, test only, step 4.)
- **A third computer for step 6:** whether QUIC from the VM reaches a Podman container on this PC.
  (REQUIRES-MEASUREMENT.)
- **Home routers:** the UDP mapping lifetime, against the 15 s pull. (REQUIRES-MEASUREMENT.)
- **How often overlaps, re-merges and combine lanes happen** in real runs, and how much re-review they cost.
  (REQUIRES-MEASUREMENT, steps 4 and 5.)
- **Log and repository growth** over months, with every computer holding everything. (UNKNOWN.)
- **Code over Link sizes:** the ratio of disk usage to bundle size, and the time to write a 200 MB bundle on a laptop.
  (REQUIRES-MEASUREMENT, step 3.)
