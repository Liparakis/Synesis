# ADR-0100 — How work from separate copies meets on `main`

## Status

Accepted on 2026-10-17 as a design, under the title "A computer that is not connected keeps working, and its work
merges back". Revised the same day against ADR-0098's premise (USER-STATED, 2026-10-17): Synesis works like Git, every
computer always works on its own copy, and what matters is that everyone's work eventually merges onto one stable
`main`, checked by review and tests.

No code exists for this ADR.

What changed in the revision:

- **Superseded:** the trunk with branches that return, and everything that served it (listed under "Superseded"). There
  is no separate offline mode any more. ADR-0098 "Each computer writes only its own log" is the one mechanism.
- **Kept:** the owner's eight answers, the conflict table (now applied whenever entries from two computers meet, not at
  a return), the combine lane through the repair path, re-review of disputes then an operator request, reservations as
  an option, and holding an away computer's lanes.

The owner's choices this ADR still needs are in ADR-0098, "For the owner to confirm" (items 1 to 9). The lab below is
the set of real runs ADR-0098's build order uses.

Claims are marked VERIFIED (file:line on this branch), USER-STATED, DERIVED or ASSUMED.

## In plain words

Bob takes his laptop on a train. The owner's desktop is at home.

- **On the train:** nothing changes for Bob's agents. They claim, write, review and merge on the laptop, as they do at
  home. They cannot see claims made at home since the train left, so they may overlap.
- **At home:** the laptop and the desktop exchange their logs within seconds. Each now sees what the other did.
- **Getting onto `main`:** the laptop pushes its merges. `main` has moved, so the push is refused, as Git refuses it.
  Synesis merges the new `main` in, runs the tests and pushes again. Where both sides changed the same lines, an agent
  gets a "combine these" lane, and its result is reviewed like any other work.
- **What is kept:** nothing Bob did is lost or rewritten. Every entry stays in his laptop's log, and every commit stays
  in the history.

Before leaving, Bob can **reserve** `web/`, so that computers which have seen the reservation do not claim inside it.

## The owner's answers (USER-STATED, 2026-10-17)

1. **Overlap.** An agent merges both. Both versions are kept. Synesis opens a "combine these" lane, and the result is
   reviewed. A person steps in only if the agent cannot combine them.
2. **Merging while apart.** Allowed, exactly as on a single computer. The merges are later made again on top of the
   shared `main` and checked again.
3. **Reservations.** A reservation lasts until the person is back and hands it in or releases it. Everyone sees it. The
   owner can break one.
4. **Online,** claims still prevent overlaps up front. *Under the premise:* a computer refuses a claim that overlaps one
   it knows about, and connected computers know each other's claims within seconds.
5. **Voting.** *Superseded by the premise:* nothing is decided by voting.
6. **Disputes Synesis cannot settle,** such as two different review verdicts. An agent reviews again on the merged
   result. If it cannot settle it, an operator request any member can answer.
7. **Parts** are folders the owner declares. *Deferred* with parts (ADR-0098).
8. **Code over Link** without a Git host: at most 200 MB per transfer, resumable, larger history in several pieces.

## What the code does today (VERIFIED)

- **Projections throw while applying, not only at append.** Each projection's `validate` and `apply` run in one fold
  (`PredictionEventStore.java:139-151`), and `apply` throws too: for example `OVERLAPPING_CLAIM`
  (`CollaborationProjection.java:555-557`) and `REVIEW_DECISION_ALREADY_RECORDED`
  (`TaskCompletionProjection.java:628-630`). Only `AppendAdmission` is append-time only (`AppendAdmission.java:28-29`).
  An entry from another computer that hit one of these throws would stop that log's copy for good.
- **Claims:** a local claim that overlaps an active one is refused `OVERLAPPING_CLAIM`
  (`CollaborationProjection.java:557`).
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
write about one thing, the projection keeps the set of what was written, so the result is the same whichever entry a
computer sees first, and an answer that arrives before what it answers is kept until that arrives (ADR-0098, "Order
does not decide"). (DERIVED.)

| # | What happened | How it counts | Who settles it |
| --- | --- | --- | --- |
| 1 | **Overlapping claims**, made while the two computers could not see each other | Both lanes stand. Each agent's next answer names the other lane and its computer | Nobody, until `main`. The second lane to reach `main` is re-merged; a code conflict is a combine lane (row 12) |
| 2 | **One snapshot integrated twice** | Cannot happen: only the computer whose lane it is merges it (ADR-0098) | none |
| 3 | **An entry acting for another computer's participant** | Counts for nothing, reported loudly. Every computer runs attribution with no exemption | A person, if it ever appears: it means a faulty build |
| 4 | **Two review verdicts on one snapshot** | The snapshot's review state is the set of verdicts. Same verdict: agreed. Different verdicts: `REVIEW_DISPUTED`, in either order, and the merge pass skips the snapshot while the dispute is open. Today a second verdict throws `REVIEW_DECISION_ALREADY_RECORDED` (`TaskCompletionProjection.java:628-630`), so two computers would end in opposite states | Answer 6: a reviewer who gave neither verdict reviews the merged result. If it cannot decide, or no such reviewer exists, `OPERATOR_REQUEST_RAISED`. If the snapshot is already on `main`, the re-review is of that code, and a rejection opens a repair lane |
| 5 | **A self-review**, made by a faulty build | Counts for nothing, reported loudly | The snapshot waits for a lawful review |
| 6 | **Two answers to one operator request**, or two raises for one condition | Two raises for the same condition count as one request (today the second throws `OPERATOR_REQUEST_ALREADY_OPEN`, `OperatorRequestProjection.java:95`). Same answer: agreed. Different answers: a dispute | A new operator request naming both answers, for any member |
| 6a | **Two computers mark one WorkGroup COMPLETED** (`LaneCompletionService.java:245-251`, from ADR-0098 step 4) | A second COMPLETED counts as the first. Completion itself is read from Git | none |
| 7 | **Work added to a goal already finished on `main`** | The lane stands, outside the finished goal | An operator request: "open a new goal for it, or set it aside?" (ADR-0098 item 8) |
| 8 | **A lane continued on two computers**, possible only after the owner released an away computer's lanes | Both lanes stand; the returning computer's later snapshots go to a combine lane | An agent, in the combine lane |
| 9 | **The same task taken on both sides** with different paths | Both stand. No rule limits a task to one lane: `TASK_ALREADY_CLAIMED` (`CoordinationProjection.java:174`) guards only the legacy `TASK_CLAIMED` of `synesis task claim` (VERIFIED) | none |
| 10 | **Contracts and capability requests** changed on both sides | The contract projection's own rule decides; a conflict is a dispute | An operator request naming both (ASSUMED rare; counted in the lab) |
| 11 | **Messages and deliveries** | Always count | none |
| 12 | **A code conflict at `main`** | The push is refused; Git cannot merge one local integration onto the new `main` | The combine lane (below) |

The ladder is the same in every row: the rules first, then an agent (a combine lane or a re-review), then an operator
request any member answers. A person is needed only when an agent says it cannot settle it (answers 1 and 6).

### The combine lane

It is today's repair lane, opened by today's conflict path when a local integration does not merge onto the new `main`
(ADR-0098, "Getting onto `main`", step 3).

- Re-merging one local integration at a time keeps each conflict inside one lane, so the repair lane's single source is
  enough (`RepairLaneTransfer.java:53-61`).
- An agent other than the author gets both versions: the shared `main` and the lane's snapshot. It makes one change
  that holds both.
- The result is reviewed and merged like any other work.
- If the agent cannot combine them, its repair ends in an operator request any member can answer.

### Reservations (optional; ADR-0098 step 7)

```text
RESERVATION_MADE   {id, node, selectors, note}
RESERVATION_ENDED  {id, reason: HANDED_IN | RELEASED | OWNER_BROKE | MEMBER_REMOVED, ownerStatement?}
```

- **Made** with `synesis reserve <path>...` on the computer that will hold it. A part is a folder, so reserving a part
  means reserving its folder.
- **What it does.** A computer that has seen the reservation refuses another computer's claim inside it,
  `RESERVED_BY_OTHER_COMPUTER`, as one more case of the overlap check. The holder's own computer may claim inside it.
- **What it cannot do.** A computer that did not see it in time may claim inside it. That claim then meets the
  reservation like row 1. `synesis reserve` says so: "computers not connected right now will not see this until they
  are".
- **Ends** when its computer hands it in or releases it (`synesis reserve end <id>`), when the owner breaks it
  (`synesis reserve break <id>`, owner-signed), or when its computer is removed.
- **Coming back does not end it.** The person is asked "hand in your reservation of `web/`?". Example: Bob reserves
  `web/` for a week, and his hotel Wi-Fi drops for two minutes on day one. The reservation must not end then.
- **No timer,** so no decision reads a clock.
- **Held by a computer, not a person** (a member is a computer, ADR-0098 decision 2). Bob's desktop cannot claim
  inside a reservation his laptop made.

### Away computers: their lanes are held

- A lane of a computer that cannot be reached is held until the computer is back. Nobody continues it automatically,
  because the computer is probably still working on it, and a takeover would make two versions every time.
- The owner can release such lanes: `synesis reserve break --lanes-of <node>`. After that an agent may continue a lane
  from its last snapshot that reached the other computers, and row 8 applies if the away computer kept working.
- A goal that waits only on lanes of computers not heard from for one day, by this computer's own clock, raises an
  operator request: "goal X waits only on lanes of node Y, last seen T; release them?" (ADR-0098 item 6).
- Removing a computer ends its reservations and releases its lanes, under the same owner signature. Entries it writes
  afterwards are still copied, so no copy is left incomplete, and count for nothing.
- A computer's own agent that died is still handled by that computer, as today: the lane is held for the grace period,
  then continued from its last snapshot.

### Superseded

Each was part of the first form of this ADR. One line each on why it is no longer needed:

- **The trunk, branches in `branches/<id>/events/`, `BRANCH_RETURNED`:** every computer always writes its own log, so
  there is nothing to branch from or return to.
- **Starting to work apart after 90 s, and `synesis offline`:** there is no apart mode. `synesis reserve` is the part
  that stays.
- **Proposals in doubt:** appends are local, so no append's outcome is ever unknown.
- **The return plan, its treatment table and re-proposing entries:** entries are never proposed again. They arrive, and
  the table above says how they count.
- **The view switch, the generation reset and stale envelopes after a return:** a computer's view only grows.
- **Deferring an overlapping claim until the other lane ends, and `DEFERRED_RETURN` reservations:** claims are hints;
  both lanes stand and meet at `main`.
- **The absorbed-base rule and `WAITING_FOR_RETURNED_BASE`:** re-merging with merge commits keeps every local base in
  the history, so `STALE_BASE` accepts it unchanged.
- **The rules B1 to B5 on a branch:** B1 (attribution) and B5 (reservations seen) now hold everywhere; B2 and B3 follow
  from "only a lane's own computer merges or continues it"; B4 follows from membership being owner-signed.
- **"What happens to step 3":** step 3 (voting) is not needed (ADR-0098, "Not needed under the premise").

## The lab: this PC and the VM

The scripts go in `D:\SynesisAcceptance\offline\`, beside `link-vm`. The project is disposable, and Synesis is used
normally in it. The code remote is a bare repository, except where a scenario says otherwise. Agents are Sonnet 5.5 at
medium effort, two on each side. The link is cut with `VBoxManage controlvm synesis-peer-vm setlinkstate1 off` and
restored with `on`.

| Scenario | Steps | Expected |
| --- | --- | --- |
| S1, apart without overlap | A goal of four modules in four folders. Cut after the first claims, for 20 minutes | Both sides keep working with no `LEADER_UNREACHABLE`. After reconnecting, both logs are exchanged, every module reaches `main` with the tests passing, and the WorkGroup completes once |
| S2, overlap | As S1, and the goal names one file both sides touch | Both lanes stand during the cut and each agent is told of the other. A combine lane opens on the second pusher, is reviewed and merges |
| S3, review dispute | Before the cut, the VM hands in a snapshot. Both sides review it during the cut, with different verdicts | A re-review by a third agent. If it cannot decide, an operator request |
| S4, reservation | `synesis reserve web/` on the VM while connected, then cut | The PC's claim in `web/` is refused `RESERVED_BY_OTHER_COMPUTER`. The reservation stays after reconnecting, until handed in |
| S5, crash during a re-merge | Reconnect, then kill the VM's server during its re-merge, and restart it | The re-merge resumes, and the result equals S1's |

Checks after every scenario:

- each computer's copy of each log is byte-identical to the writer's;
- no file in `events/` or `peers/` was ever removed or replaced, which a watcher on both computers records;
- `main` is equal on the PC, the VM and the remote, and only ever fast-forwarded;
- every integration on `main` was reviewed by a non-author and passed the validation command;
- the project's tests pass on the final `main`.

Also recorded: the time from reconnecting to `main` being equal, and the number of re-merges (REQUIRES-MEASUREMENT).

## Fitness functions

ADR-0098's list covers this ADR. Two belong here:

- **Every arriving case is decided:** a test applies each row of the table in both orders and gets the same result.
- **A dispute blocks `main`:** no snapshot with an open review dispute is merged onto `main`.

## Alternatives rejected

- **The first form of this ADR: a trunk decided by one computer, and branches that return.** Two modes, and two friends
  connected while the owner is away still do not coordinate.
- **Each computer owns its own log** (the Fable runner of the first design) was rejected then because it admits
  overlapping lanes side by side. The premise accepts that: claims are hints, and the overlap meets at `main`. Taken.
- **Rebasing a computer's merges onto the new `main`.** It rewrites commits that later lanes started from, which needs
  the absorbed-base rule. A merge commit keeps them.
- **A three-way merge of projected state.** It needs a merge function per projection, and it produces state with no
  signed entry behind it.
- **Reservations that expire on a timer.** That puts a clock in a decision (answer 3).

## Consequences

- No computer ever stops for lack of a connection.
- Connected agents avoid overlap up front. Work done apart costs a re-merge, and sometimes a combine lane.
- A dispute holds its snapshot off `main` until an agent or a person settles it.
- A computer away for months comes back with a long log and many re-merges. Nothing bounds that yet. (UNKNOWN)

## Unresolved

- How often overlaps, combine lanes and disputes happen in real runs. (REQUIRES-MEASUREMENT, the lab.)
- ASSUMED, checked in ADR-0098 step 6: a re-review of a disputed snapshot needs only entries both computers hold.
