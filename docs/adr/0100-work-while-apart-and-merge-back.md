# ADR-0100 — How work from separate copies meets on `main`

## Status

Accepted on 2026-10-17 as a design, under the title "A computer that is not connected keeps working, and its work
merges back". Revised the same day against ADR-0098's premise (USER-STATED, 2026-10-17): Synesis works like Git, every
computer always works on its own copy, and what matters is that everyone's work eventually merges onto one stable
`main`, checked by review and tests. Settled by the owner's answers of that evening (ADR-0098, "The owner's answers"):
claims are locks wherever they can be checked, every re-merge is reviewed again, agents settle disputes by talking,
stalled work is taken over, nothing needs an administrator, there are no reservations, and no Git host is needed.

No code exists for this ADR.

- **Superseded:** the trunk with branches that return, reservations, re-review by a third agent, operator requests for
  disputes, and holding an away computer's lanes until the owner releases them (listed under "Superseded").
- **Kept:** the conflict table, applied whenever entries from two computers meet, and the combine lane through the
  repair path.

The designer's choices this ADR relies on are ADR-0098's "Decided by the designer", items 1 to 6. The lab below is the
set of real runs ADR-0098's build order uses.

Claims are marked VERIFIED (file:line on this branch), USER-STATED, DERIVED or ASSUMED.

## In plain words

Bob takes his laptop on a train. Ana's desktop is at home.

- **At home, connected:** a claim is a lock across both computers. If Ana's agent holds `web/app.js`, Bob's agent cannot
  claim it.
- **On the train:** nothing changes for Bob's agents. They claim, write, review and merge on the laptop. The laptop
  cannot check Ana's claims made since the train left, so the two may overlap.
- **Back home:** the two computers exchange their logs within seconds, and each sees what the other did. Their copies
  of `main` have diverged, so one of them merges the other's in, as `git pull` does, the original reviewers review the
  re-merges, and the tests run. Where both changed the same lines, an agent gets a "combine these" lane, and its result
  is reviewed like any other work.
- **What is kept:** nothing Bob did is lost or rewritten. Every entry stays in his laptop's log, every commit stays in
  the history, and work that has to step aside goes to the trash, where a person can bring it back.

## The owner's answers (USER-STATED, 2026-10-17)

The morning answers, as revised by the evening ones:

1. **Overlap.** An agent merges both. Both versions are kept. Synesis opens a "combine these" lane, and the result is
   reviewed.
2. **Merging while apart.** Allowed, exactly as on a single computer. The merges are later made again on top of the
   other copy of `main` and reviewed and checked again.
3. **Reservations.** *Withdrawn in the evening:* no reservations at all.
4. **Online,** claims prevent overlaps up front: a claim is a lock on one computer and between connected computers.
5. **Voting.** *Superseded by the premise:* nothing is decided by voting.
6. **Disputes,** such as two different review verdicts, are settled by the agents involved, through messages, like
   engineers discussing. No third agent, and no person by default.
7. **Parts** are folders the owner declares. *Deferred* with parts (ADR-0098).
8. **Code over Link:** at most 200 MB per transfer, resumable, larger history in several pieces. In the evening: Link is
   the first-class path, and a Git host is never needed.

## What the code does today (VERIFIED)

- **Projections throw while applying, not only at append.** Each projection's `validate` and `apply` run in one fold
  (`PredictionEventStore.java:139-151`), and `apply` throws too: for example `OVERLAPPING_CLAIM`
  (`CollaborationProjection.java:555-557`) and `REVIEW_DECISION_ALREADY_RECORDED`
  (`TaskCompletionProjection.java:628-630`). Only `AppendAdmission` is append-time only (`AppendAdmission.java:28-29`).
  An entry from another computer that hit one of these throws would stop that log's copy for good.
- **Attribution:** an entry may act only for participants its signer introduced, except the leader's
  (`ParticipantAttribution.java:59-68`, exemption at `:60`).
- **Code conflicts:** a cherry-pick conflict records `INTEGRATION_CONFLICTED` and `REPAIR_REQUIRED`
  (`IntegrationOrchestrationService.java:920-925`). A repair lane has one source lane, with the same lineage and the
  same claimed paths, and must be another participant (`RepairLaneTransfer.java:53-61`).
- **Bases:** a snapshot whose base is not an ancestor of the control head is refused `STALE_BASE`
  (`IntegrationOrchestrationService.java:346-347`).

## Decision

### When entries from two computers meet

Each computer judges its own appends with today's rules. An entry that arrives from another computer is a signed fact
and must always apply: every projection throw it could reach becomes one of the outcomes below. Where two computers
write about one thing, the projection keeps what each wrote, so the result is the same whichever entry a computer sees
first, and an answer that arrives before what it answers is kept until that arrives (ADR-0098, "Order does not
decide"). (DERIVED.)

| # | What happened | How it counts | Who settles it |
| --- | --- | --- | --- |
| 1 | **Overlapping claims**, made while the two computers could not check each other's locks | Both lanes stand. Each agent's next answer names the other lane and its computer | The two agents may talk (ADR-0093). The second lane to reach a copy of `main` is re-merged; a code conflict is a combine lane (row 12) |
| 1a | **Overlapping claims on two connected computers at the same moment** | The lower node ID keeps the paths; the other claim is refused before its agent may write (ADR-0098 designer item 1) | none |
| 2 | **One snapshot integrated twice** | Cannot happen: only the computer whose lane it is merges it (ADR-0098) | none |
| 3 | **An entry acting for another computer's participant** | Counts for nothing, reported loudly. Every computer runs attribution with no exemption | It means a faulty build; the report says which |
| 4 | **Two review verdicts on one snapshot** | Each reviewer's latest verdict counts; a reviewer may record a new one. Same verdict: agreed. Different verdicts: `REVIEW_DISPUTED`, in either order, and the snapshot counts as rejected until one reviewer changes its verdict (ADR-0098 designer item 3). Today a second verdict throws `REVIEW_DECISION_ALREADY_RECORDED` (`TaskCompletionProjection.java:628-630`), so two computers would end in opposite states | The two reviewers, by messages (owner's answer 6). The dispute, the messages and how it ended are shown in the UI. If the snapshot is already on `main`, the reviewers discuss that code, and a rejection opens a repair lane |
| 5 | **A self-review**, made by a faulty build | Counts for nothing, reported loudly | The snapshot waits for a lawful review |
| 6 | **Two answers to one operator request**, or two raises for one condition | Two raises for the same condition count as one request (today the second throws `OPERATOR_REQUEST_ALREADY_OPEN`, `OperatorRequestProjection.java:95`). Two different answers keep the request open; a new answer replaces its answerer's own | The two answerers |
| 6a | **Two computers mark one WorkGroup COMPLETED** (`LaneCompletionService.java:245-251`, from ADR-0098 step 5) | A second COMPLETED counts as the first. Completion itself is read from `main` | none |
| 7 | **Work added to a goal already met on `main`** | Goes to the trash (owner's answer 6 of the evening), recoverable | A person may bring it back from the UI |
| 8 | **A lane taken over while its computer was away** (ADR-0098, "Computers that are away") | The successor's lane stands. When the away computer reconnects, its agent is told to stop, release its locks and anything it holds, and close its lane; its unfinished version goes to the trash | The away computer's own agent, with its own entries |
| 9 | **The same task taken on both sides** with different paths | Both stand. No rule limits a task to one lane: `TASK_ALREADY_CLAIMED` (`CoordinationProjection.java:174`) guards only the legacy `TASK_CLAIMED` of `synesis task claim` (VERIFIED) | none |
| 10 | **Contracts and capability requests** changed on both sides | The contract projection's own rule decides; a conflict keeps both and marks it | The agents that own the two changes, by messages (ASSUMED rare; counted in the lab) |
| 11 | **Messages and deliveries** | Always count | none |
| 12 | **A code conflict** when two copies of `main` meet | Git cannot merge one integration onto the other copy | The combine lane (below) |

The ladder is the same in every row: the rules first, then the agents involved, by messages and through a combine
lane. Synesis never routes a decision to a person by default. A person acts only on the trash, on membership, or when
the user asks.

### The combine lane

It is today's repair lane, opened by today's conflict path when an integration does not merge onto the other copy of
`main` (ADR-0098, "Getting onto `main`", step 4).

- Re-merging one integration at a time keeps each conflict inside one lane, so the repair lane's single source is
  enough (`RepairLaneTransfer.java:53-61`).
- An agent other than the author gets both versions: the other copy of `main` and the lane's snapshot. It makes one
  change that holds both, and may message the authors of both sides.
- The result is reviewed and merged like any other work.
- If the agent cannot combine them, it says so in a message to the authors, and the lane stays open for them. Nothing
  reaches `main` until one change holds both.

### Recording a dispute

- The entries are the record: the verdicts, any new verdict, and the messages between the reviewers (ADR-0093). The UI
  shows the dispute, the discussion and how it ended.
- An agent also writes the dispute to a file of its own only if the user tells it to (owner's answer 5 of the evening).

### Superseded

Each was part of an earlier form of this ADR. One line each on why it is no longer needed:

- **The trunk, branches in `branches/<id>/events/`, `BRANCH_RETURNED`:** every computer always writes its own log, so
  there is nothing to branch from or return to.
- **Starting to work apart after 90 s, and `synesis offline`:** there is no apart mode.
- **Proposals in doubt:** appends are local, so no append's outcome is ever unknown.
- **The return plan, its treatment table and re-proposing entries:** entries are never proposed again. They arrive, and
  the table above says how they count.
- **The view switch, the generation reset and stale envelopes after a return:** a computer's view only grows.
- **Deferring an overlapping claim until the other lane ends:** both lanes stand and meet at `main`.
- **The absorbed-base rule and `WAITING_FOR_RETURNED_BASE`:** re-merging with merge commits keeps every local base in
  the history, so `STALE_BASE` accepts it unchanged.
- **The rules B1 to B5 on a branch:** B1 (attribution) now holds everywhere; B2 and B3 follow from "only a lane's own
  computer merges it, and a takeover is the taker's own entry"; B4 follows from membership being owner-signed; B5 went
  with reservations.
- **Reservations, `synesis reserve`, `RESERVATION_MADE`, `RESERVATION_ENDED`** (owner, evening): "you cannot reserve
  files because you might go offline; if you never come back we need administrative action to take over."
- **Re-review by a third agent, and operator requests for disputes** (owner, evening): the agents involved settle it.
- **Holding an away computer's lanes until the owner releases them** (owner, evening): stalled work is taken over by
  other agents, and the away computer's version goes to the trash.
- **Main on the owner's computer without a Git host** (owner, evening): every computer keeps a full copy.
- **"What happens to step 3":** voting is not needed (ADR-0098, "Not needed under the premise").

## The lab: this PC and the VM

The scripts go in `D:\SynesisAcceptance\offline\`, beside `link-vm`. The project is disposable, and Synesis is used
normally in it. **There is no Git remote** unless a scenario adds one as a mirror. Agents are Sonnet 5.5 at medium
effort, two on each side. The link is cut with `VBoxManage controlvm synesis-peer-vm setlinkstate1 off` and restored
with `on`.

| Scenario | Steps | Expected |
| --- | --- | --- |
| S1, apart without overlap | A goal of four modules in four folders. Cut after the first claims, for 20 minutes | Both sides keep working with no `LEADER_UNREACHABLE`. After reconnecting, both logs are exchanged, the copies of `main` meet, the re-merges are reviewed by the original reviewers, the tests pass, and the WorkGroup completes once |
| S2, overlap | As S1, and the goal names one file both sides touch | Both lanes stand during the cut and each agent is told of the other. A combine lane opens, is reviewed and merges |
| S3, review dispute | Before the cut, the VM hands in a snapshot. Both sides review it during the cut, with different verdicts | The snapshot counts as rejected; the two reviewers discuss it by messages; either one changes its verdict or the rejection holds. The UI shows the dispute and its end |
| S4, takeover | During a cut, the VM holds the last open lane of the goal and the PC has nothing else to do | An agent on the PC takes the lane over. When the VM reconnects, its agent stops, releases its locks and closes its lane, and its version is in the trash |
| S5, crash during a re-merge | Reconnect, then kill the re-merging computer's server, and restart it | The re-merge resumes, and the result equals S1's |

Checks after every scenario:

- each computer's copy of each log is byte-identical to the writer's;
- no file in `events/` or `peers/` was ever removed or replaced, which a watcher on both computers records;
- `main` is equal on the PC and the VM (and on the mirror, when one is used), and each copy only ever moved forward;
- every integration and re-merge on `main` was reviewed by a non-author and passed the validation command;
- the project's tests pass on the final `main`.

Also recorded: the time from reconnecting to equal copies of `main`, the number of re-merges and re-reviews
(REQUIRES-MEASUREMENT).

## Fitness functions

ADR-0098's list covers this ADR. Two belong here:

- **Every arriving case is decided:** a test applies each row of the table in both orders and gets the same result.
- **A dispute blocks `main`:** no snapshot whose reviewers disagree is merged onto any copy of `main`.

## Alternatives rejected

- **The first form of this ADR: a trunk decided by one computer, and branches that return.** Two modes, and two friends
  connected while the owner is away still do not coordinate.
- **Each computer owns its own log** (the Fable runner of the first design) was rejected then because it admits
  overlapping lanes side by side. Under the premise that happens only while computers are apart, and the overlap meets
  at `main`. Taken.
- **Rebasing a computer's merges onto the other copy of `main`.** It rewrites commits that later lanes started from,
  which needs the absorbed-base rule. A merge commit keeps them.
- **A three-way merge of projected state.** It needs a merge function per projection, and it produces state with no
  signed entry behind it.

## Consequences

- No computer ever stops for lack of a connection.
- Connected agents never overlap. Work done apart costs a re-merge, a re-review, and sometimes a combine lane.
- A dispute holds its snapshot off `main` until its reviewers agree.
- Work that has to step aside is never lost: it waits in the trash until a person empties it.
- A computer away for months comes back with a long log and many re-merges. Nothing bounds that yet. (UNKNOWN)

## Unresolved

- How often overlaps, combine lanes, disputes and takeovers happen in real runs. (REQUIRES-MEASUREMENT, the lab.)
- ASSUMED, checked in ADR-0098 step 7: two reviewers can settle a dispute with the messaging that exists (ADR-0093),
  with no new tool.
