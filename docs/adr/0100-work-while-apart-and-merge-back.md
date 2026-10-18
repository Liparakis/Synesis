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
| 8 | **A lane taken over while its computer was away** (ADR-0098, "Computers that are away") | The successor's lane stands. When the away computer reconnects, its agent is told to stop, release its locks and anything it holds, and close its lane; its unfinished version goes to the trash. If it had already integrated the lane onto its own `main`, nothing can be trashed (`main` only moves forward): the two integrations meet as diverged copies, and the re-merge or a combine lane settles them | The away computer's own agent, with its own entries |
| 9 | **The same task taken on both sides** with different paths | Both stand. No rule limits a task to one lane: `TASK_ALREADY_CLAIMED` (`CoordinationProjection.java:174`) guards only the legacy `TASK_CLAIMED` of `synesis task claim` (VERIFIED) | none |
| 10 | **Contracts and capability requests** changed on both sides | The contract projection's own rule decides; a conflict keeps both and marks it | The agents that own the two changes, by messages (ASSUMED rare; counted in the lab) |
| 11 | **Messages and deliveries** | Always count | none |
| 12 | **A code conflict** when two copies of `main` meet | Git cannot merge one integration onto the other copy | The combine lane (below) |

The ladder is the same in every row: the rules first, then the agents involved, by messages and through a combine
lane. Synesis never routes a decision to a person by default. A person acts only on the trash, on membership, or when
the user asks.

### Every rule an arriving entry can reach (ADR-0098 step 4)

Each `throw new` that runs while an entry is applied, classified on 2026-10-18 (VERIFIED by reading the source at
`6e75ceeb`; line numbers are of that commit, under `coordination/src/main/java/org/synesis/coordination/domain/`). On
this computer's own append every site refuses as it does today. On an entry that arrives from another computer's log,
each site counts as its class says:

- **PARK**, a missing reference: the entry names something written in another log that has not arrived. It is held
  back, with the later entries of its own log, and applied when that arrives. Something the writer's own log names
  earlier is never missing, because a log is copied in order.
- **WRITER**, a revision, epoch or state check: judged by the writer against its own view; not judged on arrival.
- **BOTH**, two facts about one thing: both stand, and the projection keeps the set (rows 1, 4, 6, 6a, 9, 10).
- **NOTHING**, counts for nothing, reported loudly (rows 3 and 5): only a faulty build writes it.
- **UNREACHABLE** from a verified entry: a payload rule the writer's build ran, or a state the writer's own log alone
  decides. If one is ever reached on arrival, the entry counts for nothing and is reported, so applying never throws.

Decoders and payload constructors (`CollaborationCodec`, `*Payload.decode`, `WorkIntent`, `ContractCodec`, ...) throw
only on malformed bytes: UNREACHABLE for a verified entry of the same compatibility number.

**Collaboration** (`collaboration/CollaborationProjection.java`, `ProviderSessionFences.java`,
`CoordinationRequests.java`)

| Site | Code | Class | On arrival |
| --- | --- | --- | --- |
| CP:77, :554, :791, :795, :799, :940 | `SESSION_EPOCH_FENCED`, `SESSION_TERMINAL`, `PARTICIPANT_NOT_FOUND` (heartbeat, revoke) | UNREACHABLE | no build appends `PARTICIPANT_REVOKED` or `PARTICIPANT_HEARTBEAT` any more; old single logs only |
| CP:107 | `CONTINUATION_TARGET_INVALID` | WRITER | the taker checked the target against its view of the source |
| CP:507 | `INTENT_NOT_FOUND` (scope extension) | BOTH | the lane is the writer's own, so missing means another log ended it: the ended lane wins, the late extension has no effect |
| CP:510, :514, :519, :524 | `CLAIM_EPOCH_STALE`, `INTENT_NOT_ACTIVE`, `SCOPE_EXTENSION_INVALID`, `SCOPE_EXTENSION_NO_CHANGE` | WRITER | applied against what this computer holds |
| CP:527, :557 | `OVERLAPPING_CLAIM` (extension, announcement) | BOTH | row 1: both lanes stand; the overlap is reported to both agents |
| CP:550, :688 | `SESSION_TERMINAL` (announcement, request) | WRITER | the fence is the participant's own computer's |
| CP:573, :1004 | `INTENT_NOT_FOUND` (release, no-change completion) | PARK | a lane already ended (released, continued) is not missing: the entry has no effect |
| CP:617 | `REPAIR_SOURCE_NOT_FOUND` | PARK | source lane of another log |
| CP:621, :624, :627, :631 | `REPAIR_TARGET_NOT_ANNOUNCED`, `REPAIR_WORK_GROUP_MISMATCH`, `REPAIR_AUTHORITY_LINEAGE_MISMATCH`, `REPAIR_TARGET_MUST_BE_DISTINCT` | UNREACHABLE | payload and immutable lane fields |
| CP:634, :641, :647 | `REPAIR_SCOPE_MISMATCH`, `REPAIR_EPOCH_MISMATCH`, `REPAIR_TARGET_ALREADY_ACTIVE` | WRITER | |
| CP:666, :670 | `UNWIND_SOURCE_NOT_ACTIVE`, `UNWIND_EPOCH_OR_SCOPE_MISMATCH` | WRITER | a replacement lane ended elsewhere is not brought back |
| CP:692 | `INTENT_NOT_FOUND` (request's conflicting lane) | PARK | |
| CP:711 (a `return`) | lane of a rejection not found | PARK | today it silently drops the reopening |
| CP:737 | `VALIDATION_REFUSAL_TARGET_STALE` (lane missing) | PARK | |
| CP:757 | `REVIEW_TARGET_STALE`, `VALIDATION_REFUSAL_TARGET_STALE` (lane moved) | WRITER | |
| CP:774 | `PARTICIPANT_NOT_FOUND` (reopen) | UNREACHABLE | every lane comes with its participant |
| CP:814 | `INTENT_NOT_FOUND` (handoff) | PARK | |
| CP:817, :821 | `CLAIM_EPOCH_STALE`, `HANDOFF_TARGET_NOT_ACTIVE` | WRITER | |
| CP:844, :859, :962, :977 | `PARTICIPANT_NOT_FOUND` (suspend, hold, cancel, detach) | PARK | reached only by the owner's computer's exempt entries (until ADR-0098 step 5) |
| CP:862 | `RECOVERY_NOT_SUSPENDED` | WRITER | a participant that ended (detached, cancelled, completed) stays ended whatever arrives later |
| CP:877, :884 | `SESSION_TERMINAL`, `CONTINUATION_TARGET_EXISTS` (continuation) | UNREACHABLE | the taker's own participant and id |
| CP:904 | `CONTINUATION_SOURCE_NOT_FOUND` | PARK | source lane of the away computer's log |
| CP:907, :911, :928 | `CLAIM_EPOCH_STALE`, `RECOVERY_NOT_HELD`, `OVERLAPPING_CLAIM` (continuation) | WRITER | the takeover stands (row 8) |
| CP:993 | `NO_CHANGE_COMPLETION_CONFLICT` | BOTH | the lane stays completed; the evidence kept is the first one this computer applied, which only the lane's own computer writes |
| CP:1000, :1011 | `COMPLETION_REVISION_STALE`, `NO_CHANGE_COMPLETION_BINDING_MISMATCH` | WRITER | revisions are local to each computer |
| PSF:41 | `TERMINAL_REVISION_STALE` | WRITER | |
| PSF:47, :52 | `TERMINAL_SESSION_CONFLICT`, `TERMINAL_PARTICIPANT_CONFLICT` | BOTH | the participant is terminal if any session says so |
| CR:35 | `REQUEST_EXISTS` | UNREACHABLE | the requester's own id |
| CR:51 | `REQUEST_NOT_FOUND` | PARK | request in the requester's log, answer in the target's |
| CR:57 | `REQUEST_ALREADY_RESOLVED` | BOTH | both answers are kept; the answer of the lower node ID is the request's status, in either order |

**Work groups and grants** (`collaboration/WorkGroupProjection.java`)

| Site | Code | Class | On arrival |
| --- | --- | --- | --- |
| WG:294 | `WORK_GROUP_EXISTS` | BOTH | one group per id; a real creation replaces the placeholder an announcement made, an identical one is the same group |
| WG:306 | `LANE_GRANT_EXISTS` | UNREACHABLE | grant ids are random or derived from the continuation |
| WG:309, :427 | `WORK_GROUP_NOT_FOUND` (grant, status change) | PARK | |
| WG:398, :414, :447 | `LANE_GRANT_NOT_FOUND` (consume, revoke, review) | PARK | |
| WG:401 | `LANE_GRANT_REPLAYED` | BOTH | consumed and revoked are both kept; a grant is available only if neither |
| WG:430 | `WORK_GROUP_VERSION_STALE` | WRITER | a newer version replaces an older; a second change of the same version counts as the first (row 6a) |
| WG:433 | `WORK_GROUP_REVIEW_POLICY_IMMUTABLE` | UNREACHABLE | the group's policy is kept |
| WG:450 | `REVIEW_SELF_APPROVAL_FORBIDDEN` | NOTHING | row 5 |
| WG:453, :462, :466 | `LANE_GRANT_NOT_CONSUMED`, `REVIEW_GRANT_BINDING_MISMATCH`, `REVIEW_DECISION_CONFLICT` | UNREACHABLE | one grant has one reviewer, whose log orders its own entries |
| WG:456 | `REVIEW_GRANT_SUPERSEDED` | WRITER | the verdict is still recorded |

**Snapshots, reviews and integration** (`task/TaskCompletionProjection.java`)

| Site | Code | Class | On arrival |
| --- | --- | --- | --- |
| TC:92, :95 | `REVIEW_ACCEPTANCE_REQUIRED`, `REJECTED_SNAPSHOT_CANNOT_INTEGRATE` | WRITER | an integration is a fact: `main` only moves forward |
| TC:553 | `COMPLETION_PREPARATION_MISMATCH` | PARK | |
| TC:556 | `PUBLISHED_COMPLETION_CANNOT_UNWIND` | WRITER | |
| TC:574, :582 | `SNAPSHOT_ID_EXISTS`, `SNAPSHOT_REVISION_EXISTS` | UNREACHABLE | only the publishing participant writes them |
| TC:614 (a `return`), the snapshot lookups of the integration entries | snapshot not found | PARK | today the verdict or the integration is silently dropped |
| TC:625, :716 | `REVIEW_SNAPSHOT_BINDING_MISMATCH`, `VALIDATION_REFUSAL_SNAPSHOT_BINDING_MISMATCH` | UNREACHABLE | immutable snapshot fields |
| TC:628-630 | `REVIEW_DECISION_ALREADY_RECORDED` | BOTH | row 4: each reviewer's verdict is kept; two different verdicts are `REVIEW_DISPUTED` in either order, counted as rejected |
| TC:711 | `VALIDATION_REFUSAL_SNAPSHOT_UNKNOWN` | PARK | |
| TC:719 | `VALIDATION_REFUSAL_SNAPSHOT_NOT_ELIGIBLE` | WRITER | |

**Operator requests** (`operator/OperatorRequestProjection.java`)

| Site | Code | Class | On arrival |
| --- | --- | --- | --- |
| OR:92 | `OPERATOR_REQUEST_EXISTS` | UNREACHABLE | random id |
| OR:95 | `OPERATOR_REQUEST_ALREADY_OPEN` | BOTH | row 6: two raises for one condition are one request |
| OR:103 | `OPERATOR_REQUEST_NOT_OPEN` | PARK when unknown, BOTH when answered | row 6: two different answers keep it open; a new answer replaces its answerer's own |

**Capabilities, contracts, tasks, predictions, conversations**

| Site | Code | Class | On arrival |
| --- | --- | --- | --- |
| capability/CapabilityRequestProjection:359, :394, :457 | `DUPLICATE_CAPABILITY_REQUEST_HANDLE`, `INVALID_CAPABILITY_CONTRACT_REVISION_STATE`, `CAPABILITY_LINEAGE_MISMATCH` | UNREACHABLE | |
| :466, :575, :598, :615 | `CAPABILITY_REQUEST_NOT_ACCEPTED`, `CAPABILITY_REQUEST_NOT_FOUND`, `CAPABILITY_CONTRACT_REVISION_STALE`, `CAPABILITY_VALIDATION_STALE` when what they name has not arrived | PARK | |
| :472, the same four when what they name is older | `CAPABILITY_IMPLEMENTATION_REVISION_STALE` and the above | WRITER | |
| :417, :489, :590 | `CAPABILITY_CONTRACT_REVISION_MISMATCH`, `CAPABILITY_PUBLISHER_STALE`, the state codes of `requireRequest` | BOTH | row 10: a superseded request stays superseded |
| :643, :649 | `CAPABILITY_CONTINUATION_SELF_OWNERSHIP_FORBIDDEN` | NOTHING | |
| contract/ContractProjection:186, :205, :220, :228 when what they name has not arrived | `CONTRACT_REVISION_STALE`, `CONTRACT_CHANGE_REQUEST_NOT_OPEN` | PARK | |
| :186, :192, :197, :220 otherwise | `CONTRACT_REVISION_STALE`, `CONTRACT_FROZEN`, `CONTRACT_PATH_ALREADY_COVERED`, `CONTRACT_CHANGE_REQUEST_NOT_OPEN` | BOTH | row 10 |
| :205 otherwise | `CONTRACT_REVISION_STALE` (binding) | WRITER | |
| :189 | `CONTRACT_OWNED_BY_ANOTHER_LANE` | NOTHING | |
| :212, :233, :236 | `CONTRACT_CHANGE_REQUEST_EXISTS`, `CONTRACT_CHANGE_DECLINED_WITHOUT_REASON`, `CONTRACT_CHANGE_ANSWER_INVALID` | UNREACHABLE | |
| task/CoordinationProjection:171, :187, :199 | `TASK_NOT_FOUND` | PARK | |
| :174, :208 | `TASK_ALREADY_CLAIMED`, `OWNERSHIP_CONFLICT` | BOTH | row 9 |
| :158, :203, :224 | `INVALID_TASK_STATE`, `OWNERSHIP_REQUIRES_TASK_CLAIM`, `OWNERSHIP_NOT_FOUND` | UNREACHABLE | |
| prediction/PredictionProjection:40, :81 (earlier step missing) | not created, invalid transition | PARK | |
| :81 (after a terminal), :92 | invalid transition, terminal cannot transition | BOTH | a terminal state absorbs later steps |
| :103 | duplicate creation | UNREACHABLE | |
| conversation/ConversationProjection:116 | `CONVERSATION_MESSAGE_EXISTS` | UNREACHABLE | |
| :124 | `CONVERSATION_DELIVERY_AHEAD_OF_LOG` | WRITER | a mark of delivery counts in its writer's local numbering; a mark from another computer's log has no effect here (ADR-0098, step 4 designer items) |

**Admission** (`collaboration/AppendAdmission.java`, `ReviewAdmissionRules.java`, `LaneCompletionRules.java`,
`ParticipantAttribution.java`): checked only on this computer's own appends (WRITER), except:

| Site | Code | Class | On arrival |
| --- | --- | --- | --- |
| ParticipantAttribution:66 | `PARTICIPANT_NOT_OWNED_BY_SIGNER` | NOTHING | row 3, with the owner computer's exemption until ADR-0098 step 5; an entry of the owner's computer for a participant not seen yet is parked |
| the leader-only types (`prediction/PredictionEventType.java:396-403`) from another computer | `EVENT_TYPE_LEADER_ONLY` | NOTHING | until step 5 |
| AppendAdmission:278, :281, :102 | `REVIEW_SELF_APPROVAL_FORBIDDEN`, `REVIEW_SNAPSHOT_MISMATCH` (reviewer is the producer) | NOTHING | row 5; parked until the snapshot arrives |
| AppendAdmission:168, :351, ReviewAdmissionRules:103, WorkIntent:384 | `REVIEW_SELF_NOT_ALLOWED`, `INTENT_OWNER_MISMATCH` | NOTHING | row 5 |
| ReviewAdmissionRules:305 | `REVIEW_CANDIDATE_ALREADY_ASSIGNED` | BOTH | two reviewers' grants stand; their verdicts follow row 4 |

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
