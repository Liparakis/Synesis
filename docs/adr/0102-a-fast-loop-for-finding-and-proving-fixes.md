# ADR-0102 — A fast loop for finding and proving fixes

## Status

Proposed on 2026-10-19, at the owner's request ("make an adr ... and note down" the fixes discussed that night). Nothing
here is built yet. ADR-0101 stays reserved for the simplification the owner planned after ADR-0098, ADR-0099 and
ADR-0100 (2026-10-17).

Claims are marked MEASURED (timed on 2026-10-18/19, on this PC and the VM), DERIVED or ASSUMED.

## In plain words

On 2026-10-18 the real PC + VM runs of ADR-0098 found 18 defects, about one every two hours. Most of that time was
waiting, not thinking: a run stops at its first defect, a 20-minute split is mostly idle, logs were decoded by hand, and
every fix ran whole test suites on Windows. This ADR makes one "find a defect, fix it, prove it" cycle take about 5 to
15 minutes instead of 1 h 45 to 3 h, and keeps real agents for the final proof.

## Context (MEASURED, 2026-10-18)

| Stage of one cycle | Time | Where it went |
| --- | --- | --- |
| Build both computers | ~6 min | PC `installDist`, then a Podman container build for the VM |
| Set up a real run | ~8 min | about eight commands by hand (create, share, invite, join, answer, accept, provider install, start agents) |
| Run until the defect shows | 35 to 55 min | agents ~5 min, a 20-minute Link split, agents restarted by hand after the heal |
| Find the cause | 30 to 60 min | `.sce` entries decoded by hand from evidence copied off both computers |
| Fix and test | 30 to 60 min | the fix 5 to 15 min; the touched module suites on Windows 10 to 25 min (one run took 21 min 57 s); gates; waiting on other sessions' Gradle runs |
| **One cycle** | **~1 h 45 to 3 h** | |

Two facts drive most of it:

- **A real run stops at its first defect.** One run finds one defect, so cycles are serial.
- **Windows runs `git` about thirty times slower.** About 35 ms per process against about 1 ms on Linux (MEASURED
  2026-10-13); the workspace module's ~1,000 tests start many `git` processes.

Agents finished the four-package goal in 2 to 4 minutes per side, so most of a 20-minute split was idle (MEASURED).

## Decision

### Fixes to the lab and the loop

1. **Scripted scenarios** (the largest saving). A test harness drives the MCP tools the way an agent does (claim,
   write, publish, request and record reviews, take a combine or a continuation), in two or three real Synesis
   processes joined by a real Link on loopback, with each scenario's steps written out: S1 apart without overlap, S2
   overlap and a combine lane, S3 dispute, S4 takeover, S5 a crash during a re-merge, a reconnect after a reboot, three
   computers with the owner down, removal of a member. Each scenario runs in 1 to 2 minutes, deterministically, with no
   model cost, and each one runs independently, so one round can show several defects. Real agents are kept for the
   final confirmation of a step. (DERIVED: every defect of 2026-10-18 except the Windows stderr pipe and the Podman lab
   ones was a protocol defect a scripted scenario would have shown.)
2. **A decoded log dump and an evidence script.** `synesis log dump` prints every log this computer holds (its own and
   each copy) in local applied order, one entry per line with type, writer, sequence and the fields that matter, and
   with the state each projection derives (lanes, snapshots, grants, combines, membership). One evidence script copies
   both computers' coordination folders, agent transcripts, `git log --all`, refs, worktrees and leases into one folder
   and runs the dump on each. Investigators read text, not bytes.
3. **Shorter splits.** A Link split lasts long enough for the work to happen apart, about 5 minutes, not 20. The split
   stays a firewall partition that drops only traffic between the two computers (ADR-0100, "The lab"), never a pulled
   cable, so agents keep their model provider.
4. **Build once, copy to the VM.** The CLI distribution is plain Java; the PC's `installDist` output is copied to the VM
   (which has its own JDK), with no Podman container build. This also removes the risk that starting or stopping the
   Podman machine breaks the VM's bridged network (MEASURED 2026-10-18: a Podman stop cut the VM off until a reset).
5. **One script per run.** Create the project, share it, invite, join `--into`, answer, accept, install providers on each
   computer, start the watchers, start agents, split, heal, collect evidence and validate (copies of `main` equal, logs
   byte-identical, tests pass), in one call with a scenario name.
6. **The run controller keeps waiting while another computer of the project is known but away**, bounded by the run's
   own timeout, instead of ending `STALLED`; the same run continues after the heal, with no agents restarted by hand. This
   is a product rule as well as a lab saving: under ADR-0098's premise the other computer comes back.
7. **A fast test rule for fixers.** While fixing, a fixer runs only the affected test classes, on Linux in Podman (about
   15 to 30 seconds), and never a whole module suite in the loop. The Windows run is the touched classes plus one final
   Windows gate before a push.
8. **One test-state folder and one Gradle home per session.** Parallel sessions on one PC do not share
   `-PsynesisTestState`, build caches or Gradle daemons, so they stop colliding (two shared-state flakes and one deleted
   cache folder on 2026-10-18).
9. **A background gate.** A script keeps re-running the whole Linux gate on the newest commit of the working branch and
   writes red or green, with the failures, to a file. Fixes are committed as they pass their own tests; the gate checks
   them in batches while the next defect is worked on, and a red gate is the next defect.

### A related product fix decided the same night

10. **A reviewer lane is told how to end.** Once a reviewer has recorded its verdict, its next action says to write its
    review notes to `reviews/<snapshot>.md` and finish the lane, and that a one-line note is enough when it has nothing
    to add. The rule that a reviewer lane ends by publishing its notes stays; agents had refused to invent a file nobody
    asked for, which left work groups open (s1-run5, s5-run3, s5-run5). (USER-STATED, 2026-10-19: "we can still write a
    review saying that nothing existed for the agent to review and it's okay".)

## Expected result (DERIVED)

| Stage | Before | After |
| --- | --- | --- |
| Reproduce | real run, 35 to 55 min | scripted scenario, 1 to 2 min |
| Find the cause | 30 to 60 min | log dump and the failing step, 2 to 5 min |
| Prove the fix | 10 to 25 min of suites on Windows | affected classes on Linux, 15 to 30 s; gates in the background |
| Build both computers | ~6 min | ~2 min |
| **One cycle** | **~1 h 45 to 3 h** | **~5 to 10 min for a simple defect, 15 to 20 min for a deep one** |

The floor is the fixer's own reasoning: about 3 to 10 minutes for a simple defect and 10 to 20 for a deep one.

## Build order

1. Items 3, 4, 7, 8 and 10 (instructions, scripts and one guidance change; about an hour).
2. Items 2, 5 and 9 (dump, run script, background gate; about an hour).
3. Item 6 (controller rule; about 30 minutes, with tests).
4. Item 1 (scripted scenarios; about 2 to 3 hours), then the scenarios of ADR-0098 steps 4 to 7 and ADR-0100's lab.

## Consequences

- Defects show in minutes and several per round; real agent runs confirm instead of discover.
- The scripted scenarios become the regression suite for multi-computer behaviour, run by the Linux gate.
- A real run is still needed for what a script cannot show: real models' behaviour, real networks, real operating
  systems (the Windows stderr pipe of 2026-10-18 was found only by a real run).

## Not decided

- Whether `synesis log dump` is a published command or a test-only tool (ASSUMED: published, under `synesis log`, since
  people debugging a shared project need it too).
- How many real-agent runs confirm a step once its scenarios pass (ASSUMED: one per step).
