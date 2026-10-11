# ADR-0092 — One public contract per owned file, edited only by the lane that holds it

## Status

Accepted on 2026-10-12 by the repository owner. Not yet built.

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

Decided by the repository owner in discussion on 2026-10-11.

### The contract

- A contract is attached to files that its publishing lane claims. It may
  cover several files or a subtree, all held by that one lane. A claimed file
  is covered by at most one active contract. A lane may publish several
  contracts over disjoint files.
- A lane that releases one of a contract's files keeps the contract, and the
  contract still covers the released file. Releasing a file never shrinks a
  contract; only a new revision removes a file from it. A consumer does not
  lose a promise it relied on because the owner let go of a file.
- A contract is one text, and Synesis cannot tell which sentence concerns which
  file. So the part about a released file is not frozen separately: it is
  protected by the rule that every revision is accepted again by every bound
  consumer.
- When another lane claims a released file and publishes its own contract for
  it, the file moves to that contract. Consumers bound to the earlier contract
  are told, as for any revision. A contract never has two editors.
- A lane that claims more files adds them to a contract only by publishing a
  new revision. That is a change like any other: every bound consumer is told
  and binds again. No file joins a contract silently.
- Only the lane holding the claim on those files publishes a contract or a new
  revision of it. A lane that continues a stopped lane inherits its contracts
  with its claims.
- Every agent in the project can read every contract and its history.
- When the files are no longer claimed, the contract stays readable and can no
  longer be edited. Whoever next claims the files is its editor.
- There is one contract store. The per-requester contract is removed.
- A contract keeps the eleven fields of today's capability contract. The owner
  now writes them once, and they are what let two validators state the
  contradiction precisely.

### The consumer

- A consumer names the file it builds on, as in ADR-0090, and **binds** to the
  contract's current revision. Binding is the consumer's acceptance of that
  revision.
- A consumer whose need the contract does not cover files a **change
  request**: a typed record against the contract, saying what it needs. It is
  visible to every agent. Where no contract exists yet for a claimed file, the
  change request is how a consumer asks for one.
- The owner answers every change request: it accepts by publishing a revision
  that covers it, or declines with a reason. The owner cannot finish its lane
  with a change request unanswered.
- The owner is shown all open change requests on a contract together, with the
  contract's current text. This is what the failed run lacked.

### When the contract changes

- A new revision makes every binding to the previous one stale. Each bound
  consumer is told, is resumed if it has stopped, and must bind to the new
  revision before the owner's work can be integrated.
- A consumer that will not accept the new revision files a change request or
  cancels its lane. A declined consumer may bind anyway, cancel, or ask for an
  operator (ADR-0091). A disagreement is therefore always visible, and it ends
  at the owner's decision, a cancellation, or the operator.

### Before the owner's work is integrated

- Every consumer bound to the contract checks the owner's published
  implementation against the contract, as today. The owner's snapshot is
  integrated when every binding is at the current revision and has validated
  the published implementation.
- A new implementation commit returns every binding to validation, as ADR-0089
  already decides.

### The tool surface

Removed from `request_coordination`: `capability_request`, `contract_proposal`,
`contract_request`. The last shares only a name with contracts; in 859
recorded agent sessions neither it nor `contract_proposal` was ever called.
Removed from `respond_coordination`: `capability_response`.

Added to `request_coordination`: publish a contract or a revision, bind to a
revision, file a change request. Added to `respond_coordination`: answer a
change request.

Kept: `implementation_validation`, and the tool
`publish_capability_implementation`, which names a contract and revision where
it named a request. `task.knownDependencies` keeps its place and names the
files a lane builds on.

The tool count stays at ten in this step.

## What is removed, kept and corrected

- **Removed:** the negotiation half of the capability request. Its states for
  awaiting the owner, a requested revision, a counter-proposal and a rejection
  go, with their events and the two services' negotiation paths.
- **Kept:** the publication and validation half. The record that today is "an
  accepted request" becomes "a consumer's binding": the same implementation
  revisions, the same validation workspace, the same gates on finish,
  integration, cancellation and release, read through the binding.
- **Corrected:** contract ownership and the tie to claims are enforced where
  events are validated and replayed, so a log holding a revision from a lane
  that did not hold the files is rejected. Today both systems check authority
  only in the calling service.
- **Removed:** the integration gate that cannot be reached, and whichever of
  the two contract records is not kept.

Which existing record carries the contract is left to the implementation. The
constraint is that one remains.

## Alternatives considered

- **Show the owner what it has already agreed.** Cheap and adds no rule.
  Proposed first. It relies on the owner's judgment across separate
  negotiations; one earlier soft instruction on this branch failed in a real
  run before a rule fixed it. The owner chose the structural change.
- **The first accepted contract binds later requesters.** Rejected. Contracts
  are compared as exact text, so every later requester would be refused;
  compatible differences would be refused with real ones; the first requester
  would set terms it did not know others needed; and two names for one file
  would escape it.
- **Keep the tool names and change what is behind them.** Proposed. Rejected
  by the owner in favour of removing and replacing: the old kinds describe a
  negotiation that no longer exists.
- **Keep both systems.** Rejected: the contradiction stays possible in the one
  agents actually use.
- **Consumers may edit, the owner approves.** Not chosen: two agents write one
  document.

## Consequences

- Two contracts for one file cannot exist, so they cannot contradict.
- The owner decides. A consumer that needs something the owner declines cannot
  get it by negotiation. It can adapt, cancel or ask the operator.
- Who writes first is reversed. A consumer that arrives before the owner has
  published files a change request and waits for revision one.
- Every revision costs each bound consumer one more step, and resumes each one
  that has stopped. With N consumers and R revisions that is up to N times R
  wake-ups. Not measured.
- This is the largest change on the branch. It touches about 5,100 lines of
  main code and 38 test files, in the part of the system where most of this
  branch's defects were found.
- The MCP catalog breaks: four kinds removed, four added, one tool's
  arguments changed, one field's meaning changed. Provider manuals, the golden
  projection, its digests and the managed agent instructions change with it.
- Durable state written by an earlier prerelease build is not migrated
  (ADR-0088). A project started on an older build is started again.
- ADR-0090 stands: the owner is still named by a file. ADR-0089 stands for
  publication and validation. ADR-0035 is completed, not replaced. ADR-0041 and
  ADR-0054 are superseded where they describe per-requester negotiation.

## How it will be built

In order, each stage with its tests before the next:

1. The contract's invariants, bindings and change requests in the coordination
   module, beside the existing flow.
2. The workspace services and the next-action projections: what an owner and a
   consumer are shown.
3. The MCP kinds, the catalog, the manual.
4. Real runs on the new flow against the recorded baseline, with the old flow
   still present.
5. Removal of the negotiation half, the old kinds, the unused contract record
   and the unreachable gate, and the rewrite of the tests that exercised them.
6. The full repository check and the real runs again.

Nothing is merged between stage 3 and stage 6 that leaves agents with two ways
to agree a contract.

## How it will be checked

- Tests of each invariant at the projection: a revision from a lane that does
  not hold the files is rejected on append and on replay; a second active
  contract over a covered file is rejected; a binding to a revision that is not
  current is rejected.
- A test of the failed run's shape: two consumers with opposite needs. Both
  change requests are shown to the owner together, the owner declines one with
  a reason, and the declined consumer's next action says so.
- Tests that a new revision makes each binding stale, that the owner's snapshot
  is not integrated until each is current and validated, and that a stopped
  consumer is resumed for it.
- A test that the owner cannot finish with a change request unanswered.
- A test that a successor lane edits its predecessor's contract and that no
  other lane can.
- A test that a contract over two files keeps both after its lane releases
  one, that a lane which then claims the released file and publishes a contract
  for it takes the file over, and that each consumer bound to the earlier
  contract is told.
- A test that a newly claimed file is covered only after a new revision, and
  that the revision makes each binding stale.
- Real runs with the goals and model mixes of `docs/agent/CURRENT.md`. The
  baseline is 13 of 15 on `remaster21` and `remaster22`. The step is accepted when completion
  does not fall and the five-agent single-provider project completes
  repeatedly.

## Open questions

- Settled on 2026-10-12: `contract_request` is removed, and
  `publish_capability_implementation` keeps its name.
- Whether a consumer must bind before it may write code, or only before it may
  finish. Proposed: before it may finish, as today.
