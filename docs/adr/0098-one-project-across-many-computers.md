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
     `synesis log problems`. These do not depend on order: a participant's introduction is in its own computer's log,
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
    logs go on. A held-back entry is listed, with the code it waits on, in `synesis log problems`.
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
    every verdict but an integration, so the final state does not depend on which arrived first.
18. **Known order dependence left for later** (rare, and each needs a design of its own): a participant's lifecycle
    when its own computer and a taker both write about it (suspended against detached, ADR-0100 doubt on lifecycle
    states); two prediction steps that are siblings (`ACCEPTED_EXACT` against `CONTRACT_REVISED`) or two terminal
    steps; two revisions of one contract number; a capability request whose requester was continued on another
    computer before the request arrived. Each applies without throwing; which of the two stands depends on the
    order.

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
3. **Code over Link.** Link becomes the path code always takes.
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

- **Every throw an arriving entry can reach.** Projections throw while applying, not only at append. `throw new`
  sites per projection (VERIFIED by count on this branch): `CollaborationProjection` 49, `WorkGroupProjection` 15,
  `CapabilityRequestProjection` 13, `TaskCompletionProjection` 11, `ContractProjection` 10, `CoordinationProjection`
  5, `PredictionProjection` 4, `OperatorRequestProjection` 3, `ConversationProjection` 2; plus `AppendAdmission`,
  which runs as the last stage. Step 4 classifies each as "both stand", "counts for nothing, reported", or "cannot be
  reached by an entry from another log" (malformed payloads, which the chain and signature checks already stop). Also
  open from the 2026-10-16 audit: `CLAIM_EPOCH_STALE` is thrown in `LaneClaimService` and `WorkGroupService` too, and
  whether the projections cover every case is not verified.
- **The diverged-copy rule with three or more copies** is reasoned, not proven. The property test of step 5 checks it.
- **A third computer for step 6:** whether QUIC from the VM reaches a Podman container on this PC.
  (REQUIRES-MEASUREMENT.)
- **Home routers:** the UDP mapping lifetime, against the 15 s pull. (REQUIRES-MEASUREMENT.)
- **How often overlaps, re-merges and combine lanes happen** in real runs, and how much re-review they cost.
  (REQUIRES-MEASUREMENT, steps 4 and 5.)
- **Log and repository growth** over months, with every computer holding everything. (UNKNOWN.)
- **Code over Link sizes:** the ratio of disk usage to bundle size, and the time to write a 200 MB bundle on a laptop.
  (REQUIRES-MEASUREMENT, step 3.)
