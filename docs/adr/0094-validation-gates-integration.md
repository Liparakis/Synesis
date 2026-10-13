# ADR-0094 — The project's validation command gates integration, and a failure goes back for repair

## Status

Accepted on 2026-10-13 by the repository owner. Built on 2026-10-13; see "As built".

It extends ADR-0042 (the project-owned command), ADR-0045 (the road a rejected
snapshot takes back to its producer) and ADR-0091 (operator requests).

Mode: evolution of the existing system. Claims are marked VERIFIED, USER-STATED,
DERIVED, ASSUMED or UNKNOWN.

## Context

A real run integrated a failing test. One lane wrote a command-line program and
asserted the wording of an error that the storage lane words differently; the
wording was in no contract. Each lane's work was sound alone, the work group
completed, and the project's own suite has one failure. (VERIFIED, run
`w1-three-sls`, 2026-10-13.)

Synesis already runs a project-owned command before the control branch moves
(ADR-0042): `validation` in `.synesis/project.json` is run in the lane before a
finish is published, and again on the integration worktree. Three things made it
unusable. (VERIFIED by reading; none was reproduced.)

- Nothing sets it. No command writes the field, and an agent may not edit
  `.synesis`. The acceptance projects never declared one, which is why the
  failing test was integrated.
- A failure at integration is a dead end. The attempt ends `pending`, the
  snapshots return to eligible, and every later pass runs the command again, for
  ever. The lane is fenced from changing its files and cannot take its finish
  back. Nobody is told what the command printed.
- The same command gates each lane alone and the combined tree. A lane whose
  tests need another lane's code cannot pass it alone.

The one complete road for "refused, corrected, published again" is a review
rejection (ADR-0045). Real runs have used it. (VERIFIED.)

## Decision

Decided by the repository owner on 2026-10-13.

1. **An operator sets the command.** `synesis project validation set -- ARGV...`,
   `show` and `clear` write the field in the project file. (USER-STATED.)
2. **A failure on the combined work returns to the last contributor.** The lane
   whose snapshot was added last to the attempt is given the output and repairs
   inside its own ownership. Two repairs; after them the operator is asked.
   (USER-STATED.)
3. **The command runs on the combined work by default.** Running it in each lane
   alone is opted into per project. (USER-STATED.)

And, from the owner's conditions: the command runs on a stable candidate tree; a
failure is never silently accepted; existing lifecycle mechanisms are reused and
published contracts grow additively.

### A refusal is its own fact

A refusal by the command is recorded as `INTEGRATION_VALIDATION_REFUSED`, with
the attempt, the snapshot, the lane it returns to, the commit the command ran
on, the outcome and the end of what it printed. The snapshot's state becomes
`validation_refused`. It never integrates and takes no review decision.

It is not recorded as a review rejection, though it travels the same road. A
review decision needs a consumed grant of a real reviewer, and a lineage with a
rejected snapshot is review-required from then on: a project without reviewers
would be left with a lane nobody could accept. (VERIFIED.)

The lane is reopened by the same code a review rejection uses: the intent moves
to its next version with its claims, the fences on its files lift, and the
producer is shown that a revision is required, with the refused snapshot. The
newer snapshot supersedes the refused one. (DERIVED from ADR-0045; one
implementation for both.)

### Who answers for a failure

- A command that exits non-zero has given a verdict on the combined tree. The
  snapshot added last is refused. The other snapshots of the attempt are tried
  again without it.
- A command that timed out or could not be started has given no verdict on
  anybody's work. Nothing is refused.
- A lane that has had two refusals in its task and lineage is not asked again.

In the last two cases an operator request `integration_validation_failing` is
raised with the end of the output. While it is open no attempt is started,
except by `synesis operator recheck`: an attempt would only run the same command
again on every poll. It closes by observation: when a recheck's command passes
or refuses a snapshot, when the project declares no command, or when no snapshot
is waiting.

The last contributor is not always the cause. It is the lane that can act: what
was integrated before it is in the tree the command ran on and is not its to
change. When the fault is in another lane's file it can say so to that lane.
(ASSUMED to be good enough; the runs will show.)

### The candidate tree

The command runs in the integration worktree, at the commit made by applying the
attempt's snapshots to the control head, and the control branch is then moved to
that same commit. What the command writes in the worktree is not part of it.
The refusal records the commit. (VERIFIED: the branch is advanced to the
commit, never to the worktree's state.)

### An attempt that a dead process left

An attempt runs under the project lock from its start to its end. A pass that
holds the lock and finds an attempt still `started`, with the control head where
that attempt found it, ends it as interrupted and goes on. Before, it was waited
on for ever; with a test suite inside the attempt, a process dying there is not
rare. (DERIVED.)

## Alternatives considered

- **Record the failure as a review rejection.** No new event. Refused: it would
  need a forged reviewer, and it makes the lineage review-required for good.
- **Give the failure to every lane of the attempt.** Refused: lanes whose work
  the command did not fault would be reopened, and each would run it again.
- **Always ask the operator.** Refused: the common failure is one a lane can
  read and correct in a minute.
- **Leave the failing snapshot pending and retry.** That is the dead end above.
- **Run the command outside the project lock.** Wanted, and not done here: it
  needs a durable marker for the run and a recheck under the lock. Until then
  other writers are refused, and retry, while the suite runs.
- **Let the file be changed and leave the commit to the owner.** Built first and
  taken back: the file is tracked since `init`, and integration refuses a
  checkout with an uncommitted change, so every `set` stopped the project.
- **Commit it with the transaction `init` uses.** Built second and taken back:
  that transaction refuses a checkout holding any untracked file, and every
  project holds some once a provider is installed.

## Consequences

- A project that declares a command no longer has it run in each lane alone
  unless it says so with `alsoInLane`. This changes the meaning of an existing
  field. (ADR-0088: before a release, persisted formats may change.)
- `fork` keeps a project's validation command and review policy. It dropped
  both, so a forked project lost its gate without saying so.
- An older build cannot read a log that holds the new event, nor a project file
  that holds `alsoInLane`.
- While the command runs, every writer that needs the project lock is refused
  and retries. A suite of a minute is a minute of that.
- The operator has no verb to grant a further repair. The levers are the
  command, the project itself, and ending the lane.
- One request per condition: two lanes out of repairs share one request.
- A non-zero exit that is not a verdict on the work, a flaky test or a killed
  process, uses a repair all the same.
- The operator's commands are trusted by where they are run. An agent with a
  shell could run `synesis project validation clear`. That is so for every
  operator command; with this record it guards more.

## How it will be checked

- Tests of the projections, the pass with real Git, the configuration and the
  commands, with the case end to end through the protocol handler: a lane's
  snapshot is refused, the lane is shown the output and the command, corrects
  its file, publishes again and is integrated.
- A real run in which a command the agents cannot read fails the combined work
  on a rule the goal does not state, the lane repairs it from the output, and
  the work is integrated.
- A real run in which the command cannot pass: two repairs, the request, the
  operator changes the command, a recheck integrates.

What the runs showed is in `docs/agent/CURRENT.md`.

## As built

`synesis project validation set`, `show` and `clear`. `set` and `clear` hold the
project lock, change nothing when what is declared does not change, and in a Git
repository that tracks the project file commit that one path, by the rule
integration itself uses for a checkout it may advance: a checkout holding
changes Synesis did not make is refused. When the commit fails the file is put
back.

`INTEGRATION_VALIDATION_REFUSED` (wire code 80) with its own payload, which also
records the command and its working directory, so the lane is told what was run.
State `validation_refused`. `validationFailure` on the refused snapshot;
its output is shown only to the producer, not in every participant's view.

The operator condition `integration_validation_failing`, held as the checkout
condition is: nothing is integrated and a run ends as needing the operator.

Semantic changes beside the two under "Consequences":

- A finish that changes nothing is no longer validated anywhere unless the
  project opted into the lane run: it never reaches integration.
- A pass that finds an attempt left started looks at it before it looks for
  eligible snapshots. A project whose whole work was in such an attempt used to
  be answered that nothing was ready.
- A command that timed out used to answer "integration failed, retry"; it now
  raises the operator request.
- A snapshot in a refused state, by review or by validation, keeps it whatever
  event names it later, and is not chosen as the owner snapshot a consumer
  waits for.

## The quality review of the build

One reviewer read the uncommitted change and ran nothing. It found the core
sound: a refused snapshot cannot reach the control branch, a failed command is
never taken for a pass, the commit tested is the commit advanced, and the reopen
shared with review rejection behaves for reviews exactly as before. It found one
defect that could have ended a live attempt and eight that left the operator or
an agent without a lawful step. A test found one more. Each was checked against
the code and acted on.

- *The project lock could be dropped inside one process on Linux and macOS.*
  Closing any channel on a file releases every lock the process holds on it, and
  a second thread that failed to take the lock closed its own channel. The
  holder then believed it held a lock it no longer had. This was so before; the
  rule that ends an attempt as interrupted made it dangerous. A thread of the
  same process now never opens a second channel.
- *An attempt left started stayed for ever once the head moved,* and the
  operator's own levers move it. The record of the attempt's commit is now
  written or the branch is not advanced, so an attempt with no recorded commit,
  or one whose commit the branch does not hold, is ended as interrupted.
- *A pass that dies every time* ended and restarted the attempt for ever. After
  two in a row the operator is asked.
- *After a recheck the operator was shown the old output.* A different failure
  now closes the request and raises a new one.
- *A lane out of repairs made the next publisher answer for it* on a recheck.
  Nothing is refused while a snapshot of an exhausted lane is in the attempt.
- *A refusal made a suspended participant active.* It now keeps its state; a
  review rejection behaves as it did.
- *Ending the lane did not close the request* (found by a test): the pass
  answered before it classified the candidates, so the dead lane's snapshot was
  never blocked as stale. The hold is now applied where an attempt would start.
- *A consumer could wait for ever on a refused owner snapshot,* and *every
  refusal put its output into every participant's view for good.*

Not acted on: one shared raise and close for the three guards did not come out
smaller.

## Open questions

- Whether the last contributor is the right lane often enough.
- Whether two repairs is the right number.
- Whether a long suite under the lock makes waiting agents look absent.
