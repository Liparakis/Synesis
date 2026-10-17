# ADR-0100 — A computer that is not connected keeps working, and its work merges back

## Status

Accepted on 2026-10-17 as a design. The owner set the premise and answered the eight design questions that day. The
answers reached this session through the owner's main session while the owner slept (USER-STATED, relayed). Items
marked **DECIDED BY DESIGNER, owner to confirm** are choices the answers did not settle. They are listed again at the
end.

No code exists for this ADR yet.

It amends ADR-0098, "One project across many computers":

- It reverses ADR-0098's rejection of "each member decides alone and conflicts are fixed later". The reversal covers
  work done while apart and nothing else.
- It reverses ADR-0098's consequence that a member which cannot reach the leader cannot claim, finish or merge.
- It narrows ADR-0098's invariant 1 and its claim invariant.
- It reshapes ADR-0098's paused step 3, the design on branch `claude/youthful-stonebraker-7f907c` at commit `43eaaba3`.
- It changes ADR-0098's decision 5 for computers that are away.

ADR-0099 is the hook work, `docs/adr/0099-agents-are-woken-not-waiting.md`.

Mode: evolution of the existing system. Claims are marked VERIFIED (file:line on `claude/bold-borg-51ee1c` at
`11b4aa5c`), USER-STATED, DERIVED, ASSUMED, UNKNOWN or REQUIRES-MEASUREMENT.

## In plain words

Picture this. Bob takes his laptop on a train. The owner's desktop, which coordinates the project, is at home.

- **Today:** Bob's agents can keep typing inside lanes they already hold. They cannot claim a new file, hand in work
  or merge anything until the laptop reaches the desktop again (VERIFIED: `PredictionEventStore.java:402-406` turns a
  missing leader into `LEADER_UNREACHABLE` or `LEADER_OUTCOME_UNKNOWN`).
- **With this ADR:** after a short time without the desktop, the laptop starts a private continuation of the
  project's log, called a **branch**. On the branch the laptop is ordinary single-computer Synesis. Its agents claim,
  write, review and merge into the laptop's own copy of the main branch.
- **When Bob is home again:** the laptop hands the branch in.
  - Its entries are proposed again, one by one, on top of the shared log, and the desktop judges each one with the
    same rules as online work.
  - Its merges are made again on top of the shared main branch, and the project's tests run again. This is a Git
    rebase.
  - If both sides changed the same file, both versions are kept, and an agent gets a "combine these" lane. Its result
    is reviewed like any other work.
- **What is kept:** nothing Bob did is lost or rewritten. The branch is sealed and kept, and its commits stay under a
  ref.

Before leaving, Bob can **reserve** `web/`. Nobody else can claim it until Bob hands it back or the owner breaks the
reservation.

## Context

### What ADR-0098 decided, and why it was drift

ADR-0098 put every write through one decider, the leader, and kept one history: every member's log is a prefix of the
committed log, and a member never appends on its own (invariant 1). It rejected "each member decides alone and
conflicts are fixed later", because two agents could then hold the same file. Its Consequences say a member that cannot
reach the leader "cannot claim, finish or merge until it reaches a leader again". Step 3 (voters, elections) was
designed to shrink that gap by letting a majority keep deciding without the owner. Even so, a minority, or a lone friend
while the owner sleeps, still stops.

The owner (USER-STATED, 2026-10-17): "when someone is not connected to us with Synesis they should be able to work on
the project. Otherwise this is pure drift from the core premise of building." The owner chose both of these:

1. A computer that is not connected works fully on its own, like single-computer Synesis today: claim, write, review,
   merge locally. On reconnect its work merges back. Where both sides touched the same thing, the result is a conflict
   that an agent or a person resolves, like a review.
2. Optionally, before going offline, a person reserves files or parts, so nobody else can claim them while they are
   away.

Step 3 was paused until this was designed.

### The owner's answers (USER-STATED, 2026-10-17, relayed)

1. **Overlap on reconnect.** An agent merges both. Both versions are kept. Synesis opens a "combine these" lane for an
   agent, and the result is reviewed like any other work. A person steps in only if the agent cannot combine them.
2. **Offline merging.** Allowed. An offline computer works exactly like single-computer Synesis, merges included. On
   reconnect its merges are replayed on top of the shared main branch, like a Git rebase, and checked again.
3. **Reservations.** A reservation lasts until the person is back (reconnects and hands in, or releases). Everyone
   sees it. The owner can break one if someone never comes back.
4. **Online.** Claims still prevent overlaps up front, as today. Merge-on-return applies only to work done while apart.
5. **Voting (step 3).** Keep it, simpler. Voting only picks who coordinates the connected computers. A computer that
   drops off just works offline and merges back, and nobody waits on it. Elections matter only among the computers
   still connected.
6. **Disputes Synesis cannot settle**, such as a review accepted on one side and rejected on the other. An agent tries
   first, by reviewing again on the merged result. If it cannot settle it, it becomes an operator request any member
   can answer.
7. **Step 5 parts.** The owner declares parts as folders (`web/`, `server/`, `docs/`) and can change them later.
8. **Step 6, code over Link without a Git host.** At most 200 MB per transfer, resumable. Larger history goes in
   several pieces.

### What the code does today (VERIFIED)

- **The log** is one immutable file per entry in `events/`, named by a dense sequence
  (`PredictionEventStore.java:373-375`).
  - An entry's signed bytes hold its sequence and the previous entry's digest. Its position is therefore part of what
    its writer signed.
  - Every append checks the durable head and runs every projection's `validate`, then `AppendAdmission`
    (`PredictionEventStore.java:229-272`, `139-151`).
  - A taken sequence is never replaced: placement is a hard link (`321-348`).
- **Every local process reads `events/` directly:** agents' MCP servers, the run controller, the held wait, the hooks.
  - A long-lived store replays only entries above the ones it already applied (`PredictionEventStore.java:627-633`).
    A store whose files changed under it would keep stale state.
  - `recordedEvents` counts the folder's files for change triggers (`791-801`).
- **Admission runs at append time only, never at replay** (`AppendAdmission.java`, class comment). Anything that
  brings work in by replay skips every authority rule.
- **Claims:** `CollaborationProjection.announce` refuses `OVERLAPPING_CLAIM` (`CollaborationProjection.java:548-557`).
- **Attribution:** `ParticipantAttribution` refuses an entry acting for another computer's participant, except when
  `view.leaderEntry()` (`ParticipantAttribution.java:60`). A computer deciding alone counts as a leader today
  (`PredictionEventStore.java:698-701`), so it could release another computer's lanes.
- **Leader-only types:** the integration records, `REPAIR_REQUIRED`, `CONTROL_BRANCH_ADVANCED` and `TASK_INTEGRATED`
  (`PredictionEventType.java:396-403`).
- **The member path** has no state for working alone:
  - A member's processes propose over loopback to their server. `MemberReplication.relay` forwards to the leader or
    answers unreachable (`MemberReplication.java`, `relay`).
  - The leader judges the proposal (`LeaderEndpoint.accept`; `PredictionEventStore.admit`, `438-472`).
  - A member whose copy differs stops for good (`DIVERGED`).
- **Code** is merged only by the leader, which cherry-picks each snapshot onto the control head
  (`IntegrationWorkspaceService.java:142`).
  - A snapshot whose base is not an ancestor of the control head is refused `STALE_BASE`
    (`IntegrationOrchestrationService.java:346-347`).
  - A cherry-pick conflict records `INTEGRATION_CONFLICTED` and `REPAIR_REQUIRED`
    (`IntegrationOrchestrationService.java:920-925`), and a repair lane lets an agent fix it.
  - A repair lane has exactly one source lane with the same claim (`RepairLaneTransfer.java:58-61`). That fact decides
    how overlaps are combined below.
- **Liveness does not cross computers** (`43eaaba3`, "What the code showed"). Decision 5's takeover of a lost member's
  lane is not built across computers.

### Constraints

- Every rule that guards authority lives in one place, the projections and admission (ADR-0098 invariant 6). A second
  copy of the rules for returned work is not acceptable.
- Nothing a local reader saw in `events/` is taken back (`43eaaba3`, "Shape").
- Durable history (AGENTS.md):
  - Lineage is preserved.
  - History is never rewritten to look original.
  - A replay is deterministic.
  - A stale action fails safely.
- A decision never reads a writer's clock (ADR-0098 invariant 7).
- Trust model (USER-STATED, 2026-10-16): members are trusted. The guards stop accidents, not malice, and every entry
  is signed by the computer that made it.

## Decision

### The shape: one trunk, and branches that return

```text
              trunk (events/, committed only, the same on every connected computer)
 ... 410 ─ 411 ─ 412 ─ 413 ─ 414 ─ ... ─ 455 BRANCH_RETURNED ─ 456 ...
                  │                           ▲
                  │ fork                      │ entries proposed again, judged by the trunk's rules
                  └─ 413' ─ 414' ─ ... ─ 449' ┘ (branch of B: branches/<id>/events/, sealed at return, kept)
```

- **The trunk** is ADR-0098's committed log in `events/`, decided by the coordinator (step 2's fixed leader, or step
  3's elected leader). Nothing in `events/` is ever removed or replaced.
- **A branch** is one computer's continuation of the trunk while it cannot reach the coordinator.
  - It lives beside the trunk in `branches/<id>/events/`. Its numbering and hash chain continue from the trunk entry
    it forked from, so the existing chain check verifies it unchanged.
  - The computer's own view while it works apart is `trunk[1..fork]` followed by the branch.
  - The view ignores trunk entries above the fork until the branch has returned. The server may keep pulling into
    `events/` during that time.
- **On a branch the computer is single-computer Synesis.** Its server judges its agents' proposals itself (the loopback
  route already exists). Its merge pass merges into its own checkout's control branch. Nothing is pushed or fetched.
- **Returning is a rebase.**
  - The branch's entries are proposed again in order, as ordinary member proposals at the trunk head, with the same
    payload and the same entry identity, signed again by the same computer.
  - The trunk's own admission judges each one. A refusal is the conflict.
  - Integration records are not proposed again. The trunk's merge pass makes those merges itself, with its tests.
  - One `BRANCH_RETURNED` entry records the branch exactly, by fork, head digest and archive ref, plus the exceptions.
  - The computer's view then switches to the trunk, once. The branch folder is sealed and kept.

(Design by the Opus runner of the architect exploration, taken as the base. The Fable runner's design is credited and
weighed in "Alternatives rejected". DERIVED.)

### Invariants (replacing ADR-0098's invariant 1 and the claim invariant)

1. **One trunk.** Every computer's `events/` is a prefix of the trunk. Nothing in `events/` is ever removed or
   replaced. A computer never appends to `events/` on its own. ADR-0098 said "never appends"; this says "never
   appends to the trunk".
2. **Branches are owned, sealed and named.**
   - A branch is one computer's chain from one trunk entry.
   - A computer has at most one open branch per project.
   - A branch is sealed once and never changed after, and it is never deleted.
   - Once returned, it is named by its head digest in exactly one `BRANCH_RETURNED`.
3. **A reader's view moves forward only, and switches once per branch:** from `trunk[1..fork] + branch` to the trunk.
   The switch happens only after the trunk holds that branch's `BRANCH_RETURNED`. Every open store sees the switch and
   rebuilds.
4. **Claims.**
   - Within any one view, no two active lanes overlap, and no active lane overlaps another computer's reservation.
   - Overlap can arise only between work done apart. On the trunk it is never two overlapping active lanes. The
     returning lane is deferred, and its paths held for it, until the trunk lane ends (answers 1 and 4).
5. **A branch acts only for its own computer.**
   - It acts only for participants its own computer introduced.
   - It integrates only snapshots created on the branch.
   - It continues only its own lanes.
   - It changes no membership.
6. **Every effect of a branch reaches the trunk through the trunk's admission** (a proposal) or **through the trunk's
   merge pass**. Nothing enters by replay, so no rule exists twice.
7. **Time.** No decision reads a writer's clock. Reservations have no timer. The only local clock is a computer's own
   choice to start working apart, which no other computer has to accept.
8. **Code.**
   - The trunk's control branch advances only through the trunk's merge pass and its tests.
   - Branch commits stay reachable under `refs/synesis/branches/<id>/control`.
   - A snapshot's base must be an ancestor of the control head, or a branch commit that the trunk has absorbed (see
     "Code").
9. **A return is idempotent.** Interrupted anywhere, it resumes and reaches the same trunk.

ADR-0098's invariants 2 to 10 are unchanged.

### When a computer starts working apart

- **By itself:** the coordinator has been unreachable for 90 s and an agent needs to append.
  - Until then appends get `LEADER_UNREACHABLE` as today, so a short drop makes no branch.
  - The real runs saw drops redial within about 90 s (`docs/agent/CURRENT.md`, 2026-10-16 and 2026-10-17).
  - REQUIRES-MEASUREMENT. **DECIDED BY DESIGNER, owner to confirm:** the 90 s figure, and starting by itself at all.
- **On request:** `synesis offline [--reserve <path>...] [--note "..."]`. With `--reserve` it reserves first, while
  still connected, and then opens the branch.
- **Proposals in doubt** (`LEADER_OUTCOME_UNKNOWN`) are kept as bytes by the server. They become the first entries of
  the branch, with the same entry identity. At return, a check by identity finds them on the trunk if they had
  committed, and proposes them if not. A service run again can therefore never duplicate them. (DERIVED; this also
  closes a gap step 2 has today.)
- **The opening** happens under `ProjectAppendLock` and is done only by the server. It writes `branches/<id>/fork`
  (`<sequence> <digest>`) and `branch.active`. Agents see `WORKING_APART` as information on every answer.

### Rules on a branch (VERIFIED gaps, DERIVED rules, all in `AppendAdmission`)

- **B1. Attribution stays on.** `leaderEntry()` is false while a branch is open. `ParticipantAttribution` then refuses
  any entry acting for a participant another computer introduced. A branch cannot release, finish, answer for or take
  over another computer's lane.
- **B2. A branch integrates only snapshots created on it** by its own participants, filtered in
  `IntegrationCandidatePolicy`. A snapshot handed in before the fork belongs to the trunk. Nothing is integrated twice.
- **B3. A branch continues only lanes whose participant its own computer introduced.**
- **B4. A branch changes no membership:** `BRANCH_CANNOT_CHANGE_MEMBERSHIP`. This applies to step 3's membership
  entries.
- **B5. Reservations that existed at the fork are respected on the branch,** because they are in its projection.

### Returning

When the coordinator is reachable again and a branch is open, the server runs the return. It holds this computer's
`ProjectAppendLock` throughout. Local appends meanwhile get `RETURNING_TO_SHARED_LOG`, which is retryable.

1. **Keep the lineage.** Push through `CodeRemote.pushRefs`, which pushes only `refs/synesis/*`:
   - `refs/synesis/branches/<id>/control`, the branch's control head;
   - `refs/synesis/branches/<id>/log`, a commit whose tree holds the branch's entry files, so any computer can check
     them against the digest the trunk records;
   - the snapshot and prepared refs made on the branch.
2. **Pull the trunk** to its head.
3. **Plan the return**, as a pure function of the fork state and the branch. Each entry type has one treatment, given
   by an exhaustive method on `PredictionEventType` with no `default`, so a new type cannot be added without deciding
   its fate:

   | Treatment | Types |
   | --- | --- |
   | `REDONE_BY_TRUNK`: the trunk's merge pass writes its own | every `leaderOnly()` type; plus the entries the branch's merge pass wrote for a lane it had just integrated (release, contract end, group close; `LaneCompletionService`) |
   | `BRANCH_ONLY`: kept in the archive, never proposed | `WORK_GROUP_STATUS_CHANGED` (the trunk recomputes it), legacy `PARTICIPANT_HEARTBEAT` |
   | `REPROPOSE`: the trunk judges it again | every other type: lanes, scope, snapshots, reviews, grants, continuations, completions, cancellations, operator requests, contracts, capability requests, messages |

4. **Propose each `REPROPOSE` entry in branch order.** The entry keeps its payload and identity and gets the trunk
   head's position. An entry the trunk already holds (same computer, same identity and type after the fork) is skipped.
   A refusal is classified as one of these, then handled as the conflict table says:
   - **defer**;
   - **agreed**, when both sides reached the same verdict;
   - **dispute**;
   - **combine**;
   - **dependent**, which follows its root;
   - **unexpected**, which becomes an operator request.
5. **Propose `BRANCH_RETURNED`.** A projection rule accepts it once per branch id. It holds:
   - the branch id and the computer;
   - the fork's sequence and digest;
   - the branch head's sequence and digest;
   - the archive and control refs;
   - counts;
   - the exceptions only: deferred lanes, disputes, snapshots to combine, and absorbed commits (see "Code").

   It stays small whatever the branch's length.
6. **Seal and switch.**
   - Write `branches/<id>/sealed` (the trunk position of `BRANCH_RETURNED` and the branch head digest), then delete
     `branch.active`. A crash between the two is finished on the next open.
   - Move the checkout to the trunk's control head with `git reset --keep`, only when
     `ControlBranchAdvancementService.foreignChanges` is empty. Otherwise refuse loudly, as `followLeader` does.

**Readers across the switch** (the hardest part, DERIVED):

- Each store compares the view's generation (the branch id, or `trunk`) on every `durableHead()`. On a change it resets
  every projection and replays from entry 1. The reset is a third member of each `Stage`, so the stage list stays the
  one registry (`PredictionEventStore.java:139-151`).
- The verified-prefix cache is keyed by generation too.
- `recordedEvents` counts the local view. `ProjectChangeWait` and the hook watchers also wake on a generation change.
- An optimistic envelope whose revision is above the fork, issued in a branch view, is stale after the switch. A
  revision at or below the fork names the same prefix in both views and stays valid.
- After the switch, the lanes, snapshots and reviews an agent knew keep their identities and claim epochs, because the
  payloads were proposed unchanged. Only sequence numbers differ, and only refused entries are missing.

### Code: merges are made again on top, and lanes keep their base

- **On the branch** the merge pass, its cherry-picks and its test gate run as on a single computer. The integration
  guard reads "this computer decides locally", which is true for a leader and for an open branch.
  `CodeRemote.followLeaderIfMember` is skipped while a branch is open.
- **On return** the lanes the branch had merged reach the trunk in the state they had just before the branch merged
  them (handed in, reviewed, prepared), because their integration records are `REDONE_BY_TRUNK`.
  - The trunk's merge pass picks them up as ordinary candidates. It cherry-picks each snapshot onto the shared head
    and runs the project's tests. That is answer 2's "replayed on top, like a Git rebase, and checked again", with no
    new Git path.
  - The branch's own merge commits are never replayed. They stay under the control ref for lineage.
- **A review made on the branch stands** when it is the only review of that snapshot. Only the merge and the tests
  are redone. **DECIDED BY DESIGNER, owner to confirm.**
- **Lanes based on a branch merge.** A lane claimed on the branch after a branch merge has a base `Cb_i` that the trunk
  never holds, so today's rule would refuse it `STALE_BASE`. One rule is added in `validateSnapshotMetadata`:
  - A base is accepted when it is an ancestor of the control head, or when it is a branch control commit that a
    committed `BRANCH_RETURNED` lists as absorbed and every snapshot the branch had integrated up to it is
    `INTEGRATED` on the trunk.
  - Until then the snapshot waits (`WAITING_FOR_RETURNED_BASE`). That is not a failure.
  - This keeps the branch's merge order. Nobody rebases an agent's live worktree.
- **A code conflict** at any of these cherry-picks records `INTEGRATION_CONFLICTED` and `REPAIR_REQUIRED`, as today.
  The repair lane is answer 1's "combine these" lane:
  - an agent other than the author gets the trunk's version (in the control head) and the returned version (the
    snapshot), and makes one change holding both;
  - the result is reviewed and merged like any other work;
  - if the agent cannot combine them, its repair ends in an operator request any member can answer.

### Conflicts

| # | What happened while apart | Prevented on the branch? | What the trunk does at return | Who resolves, and how it is written |
| --- | --- | --- | --- | --- |
| 1 | **Overlapping claims.** Both sides claimed overlapping paths while apart. Lanes and reservations from before the fork were seen by both sides, so only two lanes both claimed apart can overlap. (DERIVED) | No | Proposed `WORK_INTENT_ANNOUNCED` refused `OVERLAPPING_CLAIM` only if the trunk lane is still active. If the trunk lane has ended, the claim is accepted and any code overlap is a combine lane at merge. If it is active: the returning lane and its later entries are **deferred** and listed in `BRANCH_RETURNED`, and its paths are held as a reservation for it | When the trunk lane ends, the returning computer proposes the deferred entries again, and the reservation ends (`LANE_REJOINED`). The lane merges after the trunk lane. A code conflict is a combine lane (answer 1). The trunk never holds two overlapping active lanes (answer 4) |
| 2 | **Double integration** of one snapshot | Yes, by B2 | none | none |
| 3 | **Acting for another computer's participant** | Yes, by B1 | none | none |
| 4 | **A review decided on both sides** (a snapshot handed in before the fork) | No | Proposed review refused as already reviewed. Same verdict: agreed. Different verdict: a review dispute, and the snapshot does not integrate while it is open | Answer 6: a review request to a reviewer who was neither earlier reviewer, on the merged result. If that agent says it cannot decide, `OPERATOR_REQUEST_RAISED`, answerable by any member. If the trunk had already integrated the snapshot, the re-review is of the integrated code, and a rejection opens a repair lane |
| 5 | **A lane continued twice** | By B3 on the branch. On the trunk, a lane of an away computer is not taken over (see "Away computers") | Only reachable if a person broke that hold. The proposed continuation is refused | Both kept: the branch's last snapshot of that lane goes to "combine" (row 8) |
| 6 | **An operator request answered on both sides** | No (any member may answer, ADR-0098 decision 9) | Proposed answer refused. Same answer: agreed. Different answer: a dispute | A new operator request naming both answers, for any member (answer 6) |
| 7 | **A WorkGroup completed on one side** while the other added work to it | The branch never completes a group for the trunk (`BRANCH_ONLY`) | Proposed claim refused `WORK_GROUP_NOT_ACTIVE`. A group cannot be made active again (`AppendAdmission.java:247-249`) | An operator request: "the returned lane X belongs to the finished goal G; open a new goal for it, or set it aside?" **DECIDED BY DESIGNER, owner to confirm** |
| 8 | **The branch kept working in a lane the trunk ended** (the trunk integrated a snapshot handed in before the fork, and released the lane) | No | Proposed snapshot refused `SNAPSHOT_COMPLETION_EVIDENCE_STALE` | Listed in `BRANCH_RETURNED` to combine. The trunk's merge pass treats it as a conflicting snapshot and writes `REPAIR_REQUIRED`, so the repair lane combines it (answer 1). ASSUMED: the repair path needs only the snapshot record that `BRANCH_RETURNED` carries; checked in slice 4 |
| 9 | **The same task taken on both sides** with disjoint paths | No | UNKNOWN whether any rule limits a task to one lane. If none: both are accepted and both merge, and overlapping code is a combine lane | Checked in slice 1. If a rule exists, it is row 1 |
| 10 | **Contracts and capability requests** changed on both sides | No | The contract projection's own refusal | An operator request naming both. ASSUMED rare; counted in the lab |
| 11 | **Messages and deliveries** | Nothing to conflict | Proposed, always admitted | none |
| 12 | **Two computers returning** with overlapping work | n/a | Their proposals interleave on the coordinator. The first decided wins a contested path; the second is deferred (row 1) | as row 1 |

The resolver ladder is the same in every row:

1. the trunk's own rules;
2. an agent (a combine lane through the repair path, or a re-review);
3. an operator request any member answers.

A person is needed only when an agent says it cannot settle it (answers 1 and 6).

### Reservations

USER-STATED (answer 3): a reservation lasts until the person is back. Everyone sees it, and the owner can break it.

```text
RESERVATION_MADE   {id, node, selectors, note, origin: PERSON | DEFERRED_RETURN}   proposed by its node, while connected
RESERVATION_ENDED  {id, reason: HANDED_IN | RELEASED | OWNER_BROKE | LANE_REJOINED, ownerStatement?}
```

- **Made** with `synesis reserve <path>...` or `synesis offline --reserve <path>...`, on a computer that can reach the
  coordinator.
  - A reservation names paths or folders. A part is a folder (answer 7), so reserving a part means reserving its folder.
  - A reservation asked for while apart is refused `RESERVATION_NEEDS_SHARED_LOG`, because nobody could see it.
- **What it guarantees.** On the trunk, and on every branch forked after it, a claim that overlaps another computer's
  reservation is refused `RESERVED_BY_OTHER_COMPUTER`. It is one more case in `CollaborationProjection`'s overlap check
  for claims and scope extensions. The holder's own computer may claim inside it.
- **What it cannot guarantee.** A computer that was already apart when the reservation was made never saw it.
  - Its claim in the reserved paths arrives at return as row 1, with the reservation in the trunk lane's place: the
    lane is deferred until the reservation ends.
  - `synesis reserve` says so when it is used: "computers working apart right now will not see this until they are
    back".
- **Ends:**
  - when its computer hands it in or releases it (`synesis reserve end <id>`);
  - when the owner breaks it (`synesis reserve break <id>`). In step 2 this is an entry of the owner's computer, which
    is the coordinator. In step 3 it carries the owner-key statement of `43eaaba3`, "Membership in the log";
  - for `DEFERRED_RETURN`, when its lane rejoins.
- **A return does not end a person's reservation by itself.** After a return, the status line and the UI ask "you are
  back; hand in your reservation of `web/`?", and handing in is one command. **DECIDED BY DESIGNER, owner to confirm.**
  Example: Bob reserves `web/` for a week-long trip, and his hotel Wi-Fi drops for two minutes on day one, so his
  laptop works apart and returns. If returning ended the reservation, `web/` would be open again on day one.
- **No timer exists**, so no decision reads a clock (invariant 7).
- **A reservation is held by a computer, not a person.** A member is a computer (ADR-0098 decision 2). Bob's desktop
  cannot claim inside a reservation made on his laptop.

### Away computers: their lanes are held, not taken over

ADR-0098's decision 5 holds a lost member's lane for a grace period, then lets another agent continue it. A computer
that is away is now probably still working on that lane, so a takeover would make two versions every time.

- A lane of a computer that cannot be reached is held until the computer returns, like a reservation.
- The owner can release it, the same way as breaking a reservation: `synesis reserve break --lanes-of <node>`.
- Decision 5 still applies to a computer's own agent that died, judged by that computer: a lane held, then continued
  from the last snapshot it handed in.

**DECIDED BY DESIGNER, owner to confirm.** This narrows a USER-STATED decision of 2026-10-15. It also makes step 5's
cross-computer liveness and takeover unnecessary.

### How this fits step 2 as built

- **Kept:**
  - the Link transport;
  - `PROPOSE` / `PULL` / `NOTICE` and the 15 s pull;
  - the loopback route;
  - push before naming a commit, and the leader fetching before it decides;
  - the merge outside the lock, and only the coordinator merging into the shared control branch;
  - joining through one link;
  - `LEADER_LOG_DIVERGED`, which keeps its meaning. A trunk copy that differs from the coordinator's is corruption. A
    branch is not divergence, because it lives outside `events/`.
- **Changed:**
  - `MemberReplication` gains the states `APART` and `RETURNING`. It opens the branch, runs the return, and proposes
    deferred lanes again when their blocker ends.
  - The member's server judges loopback proposals itself while a branch is open.
  - `PredictionEventStore` reads its mode (leader, member, branched) per append instead of fixing it at open. It
    places a branch entry in the branch folder and rebuilds on a generation change.
  - `IntegrationOrchestrationService` gets the "decides locally" guard and the absorbed-base rule.
  - `CodeRemote` gets the branch refs and the checkout move.
- **New:**
  - `coordination/domain/branch`: the return treatment, the return plan, the conflict classifier, `BRANCH_RETURNED`
    and its projection;
  - `LogBranch` in `coordination/persistence`;
  - `BranchReturn` in `coordination/replication`, with a small `CodeMover` interface that `CodeRemote` implements, so
    `coordination` stays free of Git;
  - the commands `synesis offline`, `synesis reserve`, `synesis reserve end|break` and `synesis log branches`;
  - in `coordination status`: the branch, its fork, its entry count, and the last return's report.
- **Not changed:** the 11 MCP tools and their schemas, and the services' public methods. Agents see four new codes:
  `WORKING_APART`, `RETURNING_TO_SHARED_LOG`, `RESERVED_BY_OTHER_COMPUTER` and `LANE_WAITING_FOR_RETURN`.

### What happens to step 3 (commit `43eaaba3`)

Answer 5: voting stays, and only picks who coordinates the connected computers. Under this ADR, losing the quorum no
longer stops anyone, so step 3 becomes less urgent, not unnecessary. Its remaining value: three or more connected
computers keep preventing overlap up front while the owner's computer is off. (DERIVED)

**Stays as designed:**

- the small Raft:
  - one entry in flight;
  - committed entries only in `events/`, with `replication.tail/` and `replication.vote`;
  - pre-vote and check-quorum;
- the derived term from `TERM_STARTED`;
- the weighted tie rule, joint agreement during voter changes, and the named counterexample;
- membership, the owner key and the recovery key in the log;
- `MEMBER_CARD` and the voter mesh;
- for merges across a leader change: push before naming, `alignWithLog`, `INTERRUPTED_BY_LEADER_CHANGE`;
- Lab A and Lab B, with the changed expectations below.

**Changes:**

1. **Owner decision 4's last bullet is reversed.** "Whatever it decided alone after losing contact is set aside for a
   person, never merged" becomes "it is a branch and returns like any other" (USER-STATED premise).
2. **A leader that steps down by check-quorum,** or a follower that reaches no leader, works apart: if its agents need
   to append, its server opens a branch from its committed `events/` head. The uncommitted tail is not part of the
   branch, because it was never in `events/`.
3. **`VOTERS_RESET` stays, its fork does not.** The owner key or the recovery key may still force a new voter set when
   more than half the voters are gone for good. A computer of the old set that comes back with entries after
   `forkAfter` no longer stops for a person. Those entries become a branch forked at `forkAfter`, and it returns.
4. **Two voters.** The owner's side still decides by the tie rule, so while the owner sleeps the friend works apart and
   returns. Nobody waits.
5. **A minority side of a split** works apart, each computer on its own branch.
   - Computers cut off together do not form a small coordinator of their own in the first version. Their overlaps are
     resolved at return (row 12).
   - **DECIDED BY DESIGNER, owner to confirm.** The same branch mechanism allows a shared minority branch later.
6. **Lab A's expectations:**
   - "the minority commits nothing" becomes "the minority's computers branch, keep working, and return";
   - "Same history" becomes "every `events/` byte-identical after healing, every branch sealed and named by exactly one
     `BRANCH_RETURNED`";
   - "No double claims" becomes "no two active overlapping lanes at any point of the trunk; every overlap made apart
     has a deferral and, if the code overlapped, a combine lane".

**Dropped:**

- `synesis log set-aside`;
- `events-set-aside/<term>/`;
- the rule that set-aside refuses while an agent session is active;
- automatic voter picking's dependence on step 5's cross-computer liveness. Picking can read connection changes the
  coordinator records; it is still deferred until a project passes five members.

**Considered and not taken: dropping per-entry quorum commit** (Fable runner). Without it, voting would only elect, and
a deposed coordinator's entries would be taken back from `events/` and judged again. That deletes the tail and the
replicate round trip, but it takes entries back from readers, the one thing the trunk promises never to do. Correctness
comes before code size (AGENTS.md priority 1). **DECIDED BY DESIGNER, owner to confirm.**

### Steps 5 and 6 (answers 7 and 8)

- **Step 5:** the owner declares parts as folders and can change them later (answer 7). A part's merge queue is the
  coordinator's. A returned lane that touches two parts waits for both queues, as ADR-0098 invariant 9 says.
  Cross-computer takeover leaves step 5 ("Away computers").
- **Step 6:** code over Link moves at most 200 MB per transfer, resumably, with larger history in several pieces
  (answer 8). A returning branch's refs travel the same way when no Git host is configured. This settles ADR-0098's
  unresolved bundle bound.

### Migration

- Three new entry types (`BRANCH_RETURNED`, `RESERVATION_MADE`, `RESERVATION_ENDED`). An older build cannot decode
  them, so `LinkCompatibility.CURRENT` goes from 6 (VERIFIED `LinkCompatibility.java:30`) to 7. Step 3 then takes 8.
- Existing shared projects keep working unchanged until a computer first works apart. Nothing in `events/` is
  rewritten, and nothing is migrated. Prerelease formats may change without migration (ADR-0088), and this change needs
  none.
- A single-computer project (no `replication.properties`) never branches. Its role is the leader
  (`ReplicationRole.java:109-125`), and nothing in this ADR runs on it.
- `branch.active` and `branches/` are local files, like `replication.properties`. They are never sent and never in Git,
  except the archive refs a return pushes.

## Build order

Each slice works and is tested on its own.

1. **Branch and return in one JVM** (no Git, no Link). Two project roots, a leader and a member, joined by an
   in-process channel that can be cut. This slice holds:
   - `LogBranch`, the composite view, the generation reset, and `recordedEvents` over the view;
   - the return treatment and the return plan;
   - `BranchReturn`, the rules B1 to B4, and the `BRANCH_RETURNED` rule;
   - a check for a one-lane-per-task rule (row 9).

   Tests:
   - `branchThenReturnLeavesOneTrunk`: after the return, `events/` is byte-identical on both roots, the branch folder
     is sealed and unchanged, and the lanes the member's agent knew keep their identities and epochs;
   - `longLivedStoreSeesTheSwitch`: a store opened before the cut answers, after the return, what a fresh store
     answers;
   - `envelopeFromBranchIsStaleAfterReturn`;
   - `overlapIsDeferredNotDoubled`, with a property check over the whole trunk;
   - `returnResumesAfterCrash`, killing the return after k proposals, for every k;
   - `branchCannotActForAnotherComputer`;
   - `everyEventTypeHasAReturnTreatment`, which the exhaustive switch enforces at compile time.
2. **Code:**
   - the branch's merge pass (B2);
   - the absorbed-base rule;
   - the branch refs through a bare remote;
   - the checkout move;
   - combine through the repair path for a code conflict.
3. **Reservations:** the entries, the overlap rule, the commands, and the owner's break.
4. **Deferral and disputes:**
   - proposing deferred lanes again;
   - review and operator disputes through re-review and operator requests;
   - "combine" for row 8;
   - holding away computers' lanes.
5. **The lab** (below), then the step 3 lab with the changed expectations.

### The lab: this PC and the VM

The scripts go in `D:\SynesisAcceptance\offline\`, beside `link-vm`.

- This PC (A) leads, and `synesis-peer-vm` (B) is the member.
- The project is disposable, and Synesis is used normally in it.
- The code remote is a bare repository.
- The agents are Sonnet 5.5 at medium effort, two on each side.
- The link is cut with `VBoxManage controlvm synesis-peer-vm setlinkstate1 off` and restored with `on`.

| Scenario | Steps | Expected |
| --- | --- | --- |
| S1, apart without overlap | A goal of four modules in four folders. Cut after the first claims, for 20 minutes | Both sides claim, review and merge locally. After the return, B's lanes merge on A, with A's tests passing, in B's merge order. The WorkGroup completes once |
| S2, overlap | As S1, and the goal names one file both sides touch | B's lane is deferred and rejoins after A's ends. A combine lane opens, is reviewed and merges |
| S3, review dispute | Before the cut, B hands in a snapshot. Both sides review it, with different verdicts | A re-review. If that reviewer cannot decide, an operator request |
| S4, reservation | `synesis offline --reserve web/` on B, then cut | A's claim in `web/` is refused `RESERVED_BY_OTHER_COMPUTER` during the cut. After the return the reservation stays until handed in |
| S5, crash during return | Restore the link, then kill B's server after a few proposals, and restart it | The return resumes, and the result is identical to S1 |
| S6, short drop | Cut for 40 s | No branch. Agents saw only `LEADER_UNREACHABLE` |

Checks after every scenario:

- `events/` is byte-identical on A and B;
- every sealed branch is unchanged since sealing, and equal to its archive ref and digest;
- no file in either `events/` was ever removed or replaced, which a watcher on both computers records;
- no two active overlapping lanes at any point of the trunk;
- the control heads are equal on A, B and the remote;
- the project's tests pass on the final head.

Also recorded: the return time and the number of merge passes (REQUIRES-MEASUREMENT).

## Fitness functions

- **Same trunk:** after every scenario and every simulated cut, every computer's `events/` is a byte-identical prefix of
  the longest.
- **No overlap on the trunk:** a property test over random cuts and returns. At no trunk position do two active lanes
  hold overlapping paths, and no active lane overlaps another computer's reservation.
- **Nothing taken back:** a test asserts that no code path deletes or renames a file in `events/` or in a sealed
  branch.
- **One rule set:** an architecture test shows that returned entries reach the projections only through `append`'s
  validation path, and that no replay applies a branch entry to the trunk.
- **Every type decided:** `PredictionEventType.onReturn()` has no `default`.
- **No writer's clock:** ADR-0098's "leader time only" test also covers reservations and returns.

## Alternatives rejected

- **Each computer always owns its own log, and the trunk holds the coordinator's judgments with the original bytes
  inside** (the Fable runner).
  - It is strong on lineage: nothing is ever signed again.
  - It makes a lost answer harmless, because entries are judged by their place in their own log.
  - It changes every append, including all online work. Every trunk entry becomes a wrapper around a copy of the
    member's entry.
  - It admits overlapping lanes onto the trunk, and their combine would need a repair lane with two sources.
    `RepairLaneTransfer` allows one (`RepairLaneTransfer.java:58-61`).
  - Its kept idea, that proposals in doubt are settled by identity, is taken here ("When a computer starts working
    apart").
- **One merge entry that every computer replays from the archived branch.** The trunk entry is small, but the branch
  comes in through replay, where no admission runs. It is safe only with a second copy of every authority rule.
- **A three-way merge of projected state.** It needs a merge function for each of nine projections, none of which
  exists, and it produces state with no signed entry behind it.
- **The branch written into `events/` and moved out at return.** Every reader, file count and watcher would see
  entries disappear and numbers reused. `43eaaba3` rejected that for uncommitted entries.
- **Per-computer logs merged by rules, with no decider.** Owner answer 4 keeps claims exclusive while connected, which
  needs a decider. Without one, the claim invariant becomes a repair at merge time everywhere.
- **Rebasing code on the returning computer and handing in one squashed snapshot.** It avoids the absorbed-base rule,
  but loses the trunk's per-lane task state and needs a Git path beside the merge pass.
- **Reservations that expire on a timer.** That puts a clock in a decision, and the owner wants none (answer 3).

## Consequences

- No computer ever stops working for lack of a connection. Work done apart costs a return, and sometimes a combine
  lane.
- Claims made while connected still prevent overlap up front. Overlap is possible only between work done apart, and
  reservations shrink it for the computers that see them.
- A return takes about one coordinator round trip per branch entry, during which local appends retry. That is about
  2 s for 50 entries and about 20 s for 700 at 30 ms. (REQUIRES-MEASUREMENT between two homes. Batching proposals,
  deferred since step 2, is the lever.)
- A returned merge appears on the trunk as the coordinator's merge, made later, with a new commit. The branch's own
  commits stay under a ref.
- Every store gains a full rebuild on a generation change. A caller that keeps a value worked out from the projections
  between calls is wrong after a switch. Slice 1 sweeps the long-lived holders.
- In step 2, two friends online together while the owner is away do not coordinate with each other. Their overlaps
  are settled at return. Step 3 removes that while three or more computers stay connected.
- A computer away for months returns a long branch. Nothing bounds its size yet. (UNKNOWN)

## Unresolved

- REQUIRES-MEASUREMENT:
  - the 90 s start;
  - the return time on long branches between two homes;
  - how often combine lanes and disputes occur in real runs.
- ASSUMED, checked in slice 1:
  - the positional `REDONE_BY_TRUNK` rule assumes the branch's merge pass writes only release, contract-end,
    group-close and session entries after it integrates a lane (`LaneCompletionService`,
    `IntegrationOrchestrationService.java:1039-1052`).
- ASSUMED, checked in slice 4:
  - the repair path needs only the snapshot record `BRANCH_RETURNED` carries (row 8).
- UNKNOWN: whether a task can be taken twice with disjoint paths (row 9).

## Decided by the designer, owner to confirm

1. A computer starts working apart by itself after 90 s without the coordinator, and only when an agent needs to
   append. It can also be started by hand with `synesis offline`.
2. A review made while apart stands. Only the merge and the tests are redone on return, unless both sides reviewed the
   same snapshot.
3. A return does not end a person's reservation. The person is asked to hand it in.
4. A reservation is held by the computer that made it, not by the person.
5. A lane of a computer that is away is held until it returns, never taken over automatically. The owner can release
   it. This narrows ADR-0098 decision 5.
6. Returned work for a goal already finished on the trunk becomes an operator request.
7. Computers cut off together each work on their own branch. A shared minority branch comes later.
8. Step 3 keeps per-entry quorum commit. "Voting only elects" is not taken, because it would take entries back from
   readers.
9. Overlapping claims made apart are deferred rather than admitted side by side, so the trunk never holds two
   overlapping active lanes, and the combine goes through the existing one-source repair lane.
