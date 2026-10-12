# ADR-0092 — One public contract per owned file, edited only by the lane that holds it

## Status

Accepted on 2026-10-12 by the repository owner, and revised the same day after
an independent design review whose four decisions the owner took. This is the
revised record. Built on 2026-10-12; "What the real runs changed" at the end
says what building it added.

Step 2 of three, after ADR-0091 (operator requests) and before ADR-0093 (agent
conversations). It removes kinds from the MCP tool catalog, which is a breaking
change to a published contract. The repository owner approved that break with
this record.

Mode: evolution of the existing system. Claims are marked VERIFIED (read in
this repository or in a recorded run), USER-STATED, DERIVED, ASSUMED or UNKNOWN.

## Context

### The failure

In a real five-agent run (`u1-five-lllll`, build `remaster22`) the lane that
built the storage module agreed a contract with each of three requesters. Two
of them contradicted each other: one required an unknown task id to raise
`FileNotFoundError` and listing to create a missing store; the other reserved
`FileNotFoundError` for filesystem failures and required listing to have no
side effects. One implementation is published to every requester (ADR-0089),
so each revision failed one validator. The owner described the conflict
exactly, had nothing it could do about it, and stopped. (VERIFIED.)

The owner wrote both contradictory texts itself. It is shown one request at a
time and never sees what it has already agreed. (VERIFIED.)

### What exists

The repository holds two contract systems. (VERIFIED by inventory, 2026-10-11.)

**The capability request**, about 5,100 lines of main code and 38 test files,
is the live path. Each requester writes its own contract of eleven fields, the
owner accepts or revises it, and from then on the request carries the owner's
implementation revisions and the requester's validation. Every gate on lane
finish, integration, cancellation and release reads it. Its first half,
negotiation, is per requester. Its second half, publication and validation, is
already shared: one publication reaches every accepted request.

**The published contract**, about 1,000 lines, is a revisioned text with an
owner and a list of selectors, to which a consumer binds at an exact revision.
It is close to what is wanted in shape and is not in use:

- Nothing checks that a new revision comes from the owner of the previous one.
  Any bound session can publish the next revision of any contract and become
  its owner.
- Nothing ties a contract to a claim. Its selectors are strings that are never
  parsed.
- When a contract is replaced, bindings to the old revision are marked for
  replanning, and nothing reads that mark except status output. No agent is
  told.
- Its one gate on integration cannot be reached: the field it reads is filled
  only with capability request handles.
- No MCP kind binds to it, and no next action, finish or cancellation reads it.

### What the owner asked for

Contracts are public. Any agent can read them. Only the agent that holds the
file can edit them. (USER-STATED.)

## Decision

Decided by the repository owner in discussion on 2026-10-11 and 2026-10-12. The
points marked (review) were settled on 2026-10-12 after the design review
described at the end.

### The contract

- A contract is published by a lane over files that lane claims. It may cover
  several files or a subtree. It holds the eleven fields of today's capability
  contract. A claimed file is covered by at most one active contract, and a lane
  may publish several contracts over disjoint files.
- The owner of a contract is the authority lineage of the lane that published
  it. Only that lineage publishes a revision. A lane that continues a stopped
  lane has the same lineage and so the same contracts.
- A lane that claims more files adds them to a contract only by publishing a
  revision.
- Every agent in the project can read every contract and its history.
- (review) A lane cannot give up one file: claims only grow, and a lane keeps
  its files until it ends. When the owner's lane ends, its contracts are
  **frozen**: readable, never edited again. A lane that later claims those
  files publishes a new contract. Nothing bound to a frozen contract goes
  stale.

### The consumer

- A consumer names the file it builds on, as in ADR-0090, and **binds** to the
  current revision of the contract that covers it. Binding is the consumer's
  acceptance of that revision. A binding belongs to the consumer's lane and
  follows it to a successor.
- A consumer whose need the contract does not cover files a **change
  request**: a typed record against the contract, or against the claimed file
  when no contract exists yet, saying what it needs. Every agent can read it.
- The owner answers each change request by its identifier: accepted by
  revision R, or declined with a reason. (review) A change request that a new
  revision does not answer stays open. The owner cannot finish its lane with
  one open.
- The owner is shown all open change requests on a contract together, with the
  contract's current text.

### When the contract changes

- A new revision makes every binding to the previous one stale. Each bound
  consumer is told and must bind to the new revision before the owner's work
  can be integrated.
- (review) A new revision ends every validation in progress and every verdict
  given under the previous revision. A verdict names the contract revision and
  the implementation commit it was given for. The owner may publish an
  unchanged commit again for a new revision.
- A consumer that will not accept a revision files a change request or cancels
  its lane. A declined consumer may bind anyway or cancel.

### Before the owner's work is integrated

- Every consumer bound to the contract checks the owner's published
  implementation against the contract. The owner's snapshot is integrated when
  every binding that still counts is at the current revision and has validated
  the published implementation.
- (review) A verdict that asks for a revision names the contract field the
  implementation breaks. A point of the consumer's own declined change request
  is not grounds. Synesis can require the field and show the consumer its
  declined requests; it cannot judge the text. A consumer and an owner that
  still disagree end at the operator.
- A new implementation commit returns every binding to validation, as ADR-0089
  decides.

### When an agent is gone (review)

- A binding of a lane that was cancelled or has ended no longer counts.
  Cancelling a lane withdraws its bindings and its open change requests in any
  state; no gate may refuse a cancellation because of them.
- When the owner's lane is released after integration, every change request
  still open on its contracts is declined with a fixed reason under the same
  lock, and every binding not yet validated is closed as "the owner finished".
  A consumer that binds after that is satisfied by the integrated work and
  waits for no validation.
- When the owner's lane is cancelled, its contracts are frozen and its
  bindings are closed as "the owner's lane ended". The dependency is open again.
- When a bound consumer's lane is still open and its agent does not return
  within a stated time after the owner published, Synesis raises an operator
  request (ADR-0091) naming that lane. It does not skip the consumer.
- A consumer that declared a dependency and never bound does not hold the
  owner. It is satisfied by the integrated work, unchecked, as an unrequested
  dependency is today.

### The tool surface

Removed from `request_coordination`: `capability_request`, `contract_proposal`,
`contract_request`. In 859 recorded agent sessions the last two were never
called. Removed from `respond_coordination`: `capability_response`.

Added to `request_coordination`: `contract_publish` (the owner publishes a
contract or a revision), `contract_bind` (a consumer binds to a revision, or
binds again after one), `contract_change_request`. Added to
`respond_coordination`: `contract_change_response`.

Kept: `implementation_validation`, which gains the contract field when it asks
for a revision, and the tool `publish_capability_implementation`.
`task.knownDependencies` keeps its meaning: the consumer's own name for what it
depends on, which its binding carries.

The tool count stays at ten in this step.

## How it is built (review)

The review showed that building the new flow beside the old one would give
agents two ways to agree a contract and two record types behind one set of
gates. It is built in place instead.

- **The shared contract** replaces the unused contract store: its records, its
  projection and its three event types are rewritten for the contract described
  above, with the change requests. Nothing at run time read the old store.
- **A binding is today's request record.** It is created by `contract_bind`
  already agreed, carrying the contract's identifier and revision. A contract
  revision moves each binding to the state that today means "the owner revised,
  accept or counter", and binding again is today's requester acceptance. From
  there the record is what it was: implementation revisions, the validation
  workspace, continuation to a successor, and every gate on finish,
  integration, cancellation and release.
- **Removed:** a request created with the requester's own contract, the owner's
  accept, reject and revise of it, and the requester's counter-proposal, with
  the states only they used.

Authority is checked where every other authority in this log is checked: by the
service that appends, under the project append lock, against the claims and
lanes of that moment. The projection additionally refuses what it can judge
from its own state: a revision from another lineage than the contract's owner,
a revision number that does not follow, a second active contract over a covered
file, a binding to a revision that is not current. (review) The record first
said these would all be enforced on replay. They cannot be: a projection does
not see claims, and every local session signs with one node identity.

Order: the contract store and its tests; the binding in the request
projection; the services and what an owner and a consumer are shown; the MCP
kinds, the catalog and the manual; the tests that exercised negotiation; the
full repository check; real runs against the recorded baseline.

## Alternatives considered

- **Show the owner what it has already agreed.** Cheap and adds no rule.
  Proposed first. It relies on the owner's judgment across separate
  negotiations. The owner chose the structural change.
- **The first accepted contract binds later requesters.** Rejected. Contracts
  are compared as exact text, so every later requester would be refused,
  compatible differences with real ones.
- **Keep the tool names and change what is behind them.** Proposed. Rejected
  by the owner in favour of removing and replacing.
- **Keep both systems.** Rejected: the contradiction stays possible in the one
  agents use.
- **Build the new flow beside the old one behind a project setting.** Proposed
  by the review as the way to keep the two apart. Not chosen: it builds a second
  copy of the publication and validation half, which this decision does not
  change, and then deletes the first.
- **Skip a consumer that does not come back after a stated time.** Rejected by
  the owner: every consumer checks, and an absent one is the operator's to
  resolve.

## Consequences

- Two contracts for one file cannot exist, so they cannot contradict.
- The owner decides. A consumer that needs something the owner declines can
  adapt or cancel.
- Who writes first is reversed. A consumer that arrives before the owner has
  published files a change request and waits for revision one.
- Every revision costs each bound consumer one more step and a new validation.
  Every recorded event still resumes every stopped agent that holds a lane, so
  N consumers binding again cost up to N times N resumes against the resume
  limit. Not measured; if it ends runs, an agent must be resumed only for the
  events that concern it.
- The gate that blocks every lane's finish while any validation workspace
  exists in the project is held longer than today. Not changed here.
- The MCP catalog breaks: four kinds removed, four added, one kind gains a
  field. Provider manuals, the golden projection, its digests and the managed
  agent instructions change with it. The CLI's `collaboration contract`
  commands, which wrote to the unused store, are removed or pointed at the new
  one.
- Durable state written by an earlier prerelease build is not migrated
  (ADR-0088).
- ADR-0090 stands: the owner is named by a file. ADR-0089 stands for
  publication and validation. ADR-0035 is replaced. ADR-0041 and ADR-0054 are
  superseded where they describe per-requester negotiation.

## How it will be checked

- The projection refuses: a revision from another lineage, a second active
  contract over a covered file, a binding to a revision that is not current.
- The failed run's shape: two consumers with opposite needs. Both change
  requests are shown to the owner together, the owner declines one with a
  reason, and the declined consumer's next action says so.
- A new revision makes each binding stale, ends a validation in progress, and
  the owner's snapshot is not integrated until each binding is current and
  validated at that revision; an unchanged commit can be published for it.
- The owner cannot finish with a change request open; a change request filed
  between its finish and its release is declined at release.
- Cancelling a consumer's lane withdraws a binding in every state and releases
  the owner's integration.
- A consumer binding after the owner was integrated waits for nothing.
- A successor lane publishes its predecessor's contract and no other lane can;
  a consumer's successor holds its bindings.
- A verdict asking for a revision without a contract field is refused.
- Real runs with the goals and model mixes of `docs/agent/CURRENT.md`. The
  baseline is 13 of 15 on `remaster21` and `remaster22`. The step is accepted
  when completion does not fall and the five-agent single-provider project
  completes repeatedly.

## The design review of 2026-10-12

Four reviewers on four different models read the first version of this record
and the code it replaces, independently and read-only, with one brief: find
where the design as written gets stuck. At least three of the four found each
of these: a consumer that is gone holds the owner for ever; the exit "cancel
your lane" is refused by a gate the record kept; validation is tied to the
commit and not to the contract revision; a lane cannot give up one file, so the
rules for a released file described nothing and contradicted each other; a
request that arrives as the owner finishes has no exit; the invariants cannot be
enforced on replay as the log is built; nothing linked a revision to the change
requests it answers; the build order would have run two flows behind one set of
gates. The first version described the path on which every agent stays and
agrees. This version is what the review and the owner's four decisions made of
it.

## What the real runs changed

The first build of this record completed 13 of 14 real runs. The one that did
not showed something the record had described and the code had not done.

With a public contract the owner no longer waits for its consumers before it
can finish: it publishes the contract, builds, and is integrated. A consumer
then regularly arrives after the owner's work is in, with other lanes still
open. The existing rule that lets a dependency be satisfied by integrated work
asks that no peer be open, because a dependency never bound names no owner.
The late consumer was told that no lane claims the file yet, and looked for an
owner until the run ended.

So a consumer that binds to a file whose owner has finished and been integrated
binds to the frozen contract: a record is created already closed as "the owner
finished", the answer says the work is integrated, and from then on that
dependency is satisfied whatever other lanes are open. The record names the
owner, which is what the older rule could not know. A change request against
such a file is refused: nobody is left to change the contract.

The two-minute rule that let an owner finish past a consumer that had not
answered is gone. An owner waits while a bound consumer's lane is open. A
consumer that owes an action and has made no verified call for the stated time
is put to the operator (`bound_consumer_absent`), the owner is told so, and the
request closes when the consumer acts or its lane ends.
