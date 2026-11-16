# 2026-11-16 — Real runs of items 63–64 skipped

To reach items 63 (repair while the author's computer is away) and 64 (a late verdict under a revoked grant) lawfully,
the lab's `run.sh` (outside the repository) gained `--then-split-when TYPE`: after the first heal it cuts the Link again
the moment the PC holds an entry of that type copied from the VM (for 63, `INTEGRATION_CONFLICTED`). Not run: the lab
VM hung early in boot twice (VirtualBox under Hyper-V, about 5.6 GB free on the host), and the owner chose to skip the
runs. 63 and 64 stay source-proven. Item 64 would still need a reviewer to reject by its own judgement, which no cut
can arrange. Of the older "Open" items, "a repair is offered only on the author's computer" and "a re-merge stopped
after two rejections is not retried" were closed by items 63 and 62 the same day (the 2026-11-14 line predates them).

# 2026-11-16 — Keyless build provenance for release files

A release run now attests each raw installer and platform archive (`actions/attest-build-provenance@v2`, with
`id-token` and `attestations` write on the `bundles` job); `gh attestation verify <file> --repo Liparakis/Synesis` checks
one. No key exists. **Not run:** no release run has been made since (owner's rule), so the step is checked only as
parsing YAML. Windows signing: the owner chose SignPath Foundation; the `bundles` job signs the raw Windows installer
on a release run once `SIGNPATH_API_TOKEN` is set (skipped until then), and `CODE_SIGNING_POLICY.md` is the policy page
SignPath asks for. Not run; the owner applies and sets the secret and variables (`docs/release/signing.md`). The
repository's `README.md` is a leftover acceptance-project file and must link the policy before applying. Apple signing
is not wired. **Deferred by the owner (2026-11-16):** the SignPath application waits until just before a public
release; the commercial-license offer was withdrawn the same day so the project qualifies.

# 2026-11-16 — Installer check: fresh install shown, decline path not checked

**Shown.** The owner installed the `0.1.0-dev.installcheck-20261116` wizard build in a new local Windows account that
never had Synesis: the install completed, with no administrator prompt. **Not checked: the decline path.** This PC has
UAC off (`EnableLUA=0`), so Windows shows no approval prompt to anyone and "No" cannot be clicked; the `Synesis Link`
firewall rule still named the owner account's older build afterwards. The owner chose to leave it unchecked (security
settings are the owner's; nothing was changed). It rests on the wizard tests that replace the elevation.

# 2026-11-16 — The two rare flakes fixed

**`McpRecoveryContinuationTest` could not delete `.git`.** Git 2.29+ starts a detached `git maintenance run --auto
--detach` after every commit (seen with `GIT_TRACE` in the gate image, git 2.53), which can still be writing in `.git`
when the test, or Synesis, deletes or moves the checkout. `GitProcessRunner` now passes `maintenance.auto=false` and
`gc.auto=0` (`GitProcessRunnerMaintenanceTest`); the person's own git commands still do maintenance. The cause is
inferred, not caught in the act. **`OverlapAndCombineScenarioTest` "holds the entries … wrote".** The test waited until
both logs matched and then checked again while the computers kept writing; the second check is removed there and in
`ApartWithoutOverlapScenarioTest` (the wait already proves it). Full Linux gate: link 209, coordination 594,
project-record 28, relay 12, mcp-contract 21, workspace 1284 (11 skipped), mcp 215, cli 363 (1 skipped), 0 failures.

# 2026-11-15 — Operator-key recovery built

**Built.** ADR-0104's open requirement "Operator-key recovery" is resolved: a new operator key is made from 24 words
printed once (`OPERATOR_WORDS=`), and `synesis project policy restore-key` puts the registered key back from them under a
new passphrase (a lost file, a forgotten passphrase, or the new owner after `project recover`). The words must give the
registered key (`OPERATOR_WORDS_WRONG`); no log entry or wire format changed. Not the ADR-0098 recovery key as first
proposed: the owner's node key alone can name a new one, which would have been a node-key-only path. A key made before
today has no words and cannot be restored. A baseline write that fails after the key was made deletes the key unless the
log registered it (not covered by a test: no failure there could be injected cheaply).

**Evidence.** `ProjectPolicyServiceTest` (restore, wrong words, forgotten passphrase, new owner after `recover`) and
`ProjectPolicyCommandTest.theWordsPrintedOnceRestoreALostKeyUnderANewPassphrase`. Full Linux gate: link 209,
coordination 594, project-record 28, relay 12, mcp-contract 21, workspace 1283 (11 skipped), mcp 215, all 0 failures;
cli 363 (1 skipped) with 1 failure, `OverlapAndCombineScenarioTest.theOtherComputerGoesOnIntegratingWhileTheCombineWaits…`
("holds the entries … wrote": the two logs not yet equal), which passed 3 reruns of the class. A rare replication-timing
flake, not caused by this change (the scenario's policy step only signs once); cause not found.

# 2026-11-15 — CI flakes, sandbox gaps, item 18 designed

**Fixed.** The two tests that failed CI release run 43639210576 on `55735ed2`: `CodexLifecycleWaitControlTest` read the
thread ID its stand-in wrote after the latch (test race, order swapped); `OverlapAndCombineScenarioTest` could not delete
its folder because `MergeDriver.close` did not wait for a running pass, which also let a pass write into a project after
Synesis stopped it (it now waits up to 30 s; `MergeDriverTest.closeReturnsOnlyAfterAPassThatIsRunningHasEnded`, fails
before). Sandbox (ADR-0104, "Residual" and Part C): every known project root is hidden on Linux and macOS; re-merge
validation with no sandbox raises one blocking `remerge_sandbox_unavailable` request; a lane's own validation answers
`SANDBOX_UNAVAILABLE` instead of a retry. macOS stays unverified and refuses `run_command`.

**Found already handled.** A late snapshot of a cancelled lane is trashed by `WorkTrash` (step 7 item 14), which closes
its review; a stale reviewer lane with notes is told to publish and is shown `finish_lane` (now pinned by
`AgentSessionServiceTest.aStaleReviewerLaneWithUnpublishedNotesIsShownTheCompletionThatPublishesThem`, passes as is).

**Docs.** ADR-0029 status corrected to accepted (built through ADR-0030/0031); ADR-0104's status line no longer says only
stage 1 is built. ADR-0101 stays deferred (owner, 2026-11-15). Designer item 18's five order dependences are designed in
ADR-0098 and all five built the same day (each fails before). Case 3 adds `publishedContractRevision` to the capability
request payload (format 5; format 4 still read; an older build cannot read format 5) and makes the lower node's
publication of one contract revision stand, a stated change of `ContractArrivalOrderTest`.

**Evidence.** Linux gate after item 18: link 209, coordination 591, project-record 28, relay 12, mcp-contract 21,
workspace 1281 (11 skipped), cli 362 (1 skipped), all 0 failures, and mcp 215 with 1 failure:
`McpRecoveryContinuationTest.aReviewerWhoseModuleWasIntegratedElsewhereIsToldItMayCancel` could not delete its
project's `.git` at teardown (something still wrote there); 3 reruns of the class passed. Recorded as a rare flake, cause
not found; the test is unchanged since October.

**Not done.** A real run that hits items 62–64: a blind run cannot be steered into a second rejection, an away author or
a late verdict without telling the agents what to do, which the acceptance rules forbid; they stay source-proven.

# 2026-11-14 — ADR-0098 step 5 items 62–64: re-merge review rounds, away-author repair, revoked-grant verdicts

**Fixed** (ADR-0098 items 62–64, each pinned by a test): closing the operator request of a re-merge rejected twice starts a
new round of review whose accept lands it (62); while the author's computer is away, another computer offers its repair,
never to the author (63); the main audit ignores a verdict given under a revoked grant, so a late rejection from an away
reviewer no longer refuses a copy of `main` (64, owner decision).

**Evidence.** Linux gate at `--cpus 2` green after 62–63; Windows gate green on `95077b6a` (0 failures, 24m28s). Real apart
runs on sbx20 (`95077b6a`): 3/3 PASS (s20a-run1, s20a-run2b, s20a-run3). The runs did not hit the exact cases of 62–64;
those are source semantics proven only.

**Open.** Sandbox gaps (projects outside home, re-merge validation commands, macOS unverified), closed on 2026-11-15
except macOS. (This line first also listed a late snapshot of a cancelled lane and a stale, dirty reviewer lane; both
were already handled, see 2026-11-15.) Older-build compatibility and the 2-CPU re-measure were dropped by the owner.

# 2026-11-14 — ADR-0098 step 5: apart-and-rejoin converges in real runs (items 43–61)

**Fixed since 2026-10-27** (each with a fail-before test; ADR-0098 items 43–61): a mistyped grant ID names the session's
one grant (43); a run waits while a connected computer's log grows (44); a transient coordination request is not a lasting
`INTEGRATION_BLOCKED` (45); a computer waits for its own pass before taking the other copy's main (46); the provider manual
stops a tool-holding agent from running `provider install` (47); stranded reviewer lanes are released, never a reviewer
that was granted (48); an agent whose process failed with its lane open is resumed (50); a conflict between the combined
result and the other copy's newest head opens one more combine (51) whose lane claims every path its merge brings in (52);
stale sessions are led out of the `ensure_session`/`get_next_action` loop (53, 55, 56); an idle agent of the author's
computer is offered a repair lane (54); a combine join is refused before its worktree moves (57); a mismatched alias is
answered retry (58); `send_message` takes optional `blocking` (default true) and only blocking operator requests end a run
(59, owner decision); a re-merge reviewer judges the merge, not the whole goal (60); a rejected re-merge goes to one more
independent reviewer, and a second rejection is a blocking operator request (61, owner decision).

**Evidence.** Linux gate at `--cpus 2` green on every merged step through `f48af10b` (2,700+ tests, 0 failures).
Real apart runs (PC Windows + Ubuntu VM, 300 s firewall split, agents restarted after the heal; PC agents Claude Haiku 5.5
from sbx10, VM agents Codex GPT 6 Luna): sbx12–sbx18 13 PASS, 4 PARTIAL, 4 FAIL across 21 runs, each non-pass a distinct
cause now fixed; sbx13 3/3 PASS; **sbx19 (`f48af10b`) 3/3 PASS**. Source semantics proven for every item; real-run
convergence demonstrated on sbx13 and sbx19, not yet over a long series.

**Open.** A repair is offered only on the author's computer; a re-merge stopped after two rejections waits for a person
and is not retried after the request is closed; a computer on an older build reads an operator note as blocking; the
2-CPU `OverlapAndCombineScenarioTest` failures (1 in 11 rounds, 2026-10-27) were not re-measured on the new tip.

# 2026-10-27 — ADR-0104 built (sandboxed commands, signed decisions); step 5 stable under real cuts

**ADR-0104, all stages built.** Stage 1: the owner's gating decisions are `PROJECT_POLICY_SET` entries (wire code 93)
signed by a separate passphrase-protected operator key; gates read validation, review and the lane gate from the signed
log and ignore `project.json` once a baseline exists; `project validation|review|policy|sandbox` sign entries.
Stages 2–4: agent commands run through one `Sandbox` choke point — bubblewrap on Linux, a low-integrity process on
Windows, sandbox-exec on macOS (written UNVERIFIED, refuses until tested on a Mac); with no sandbox a command is refused
unless the owner signs `project sandbox allow-unsandboxed`. Stage 5: doctor findings, a `sandbox` field in the
`run_command` result, an operator request when a gate cannot run, a read guard for Read/Glob/Grep on Synesis state,
Link compatibility 12, provider manual 14. Deviation: `init` signs no baseline (it needs a person's passphrase), so a
project's file stays its policy until a person signs a first decision or runs `project policy adopt`.

**Real runs (sbx1–sbx3).** sbx-run2 PASS but every command was refused: the Windows probe ran Git Bash's `whoami` (fixed:
full paths and the Low SID). sbx-run3 PASS with the PC's agents running the tests inside the Windows sandbox. On the
Ubuntu 24.04 VM bubblewrap is blocked by `kernel.apparmor_restrict_unprivileged_userns=1`; commands there are refused and
doctor now names the narrow AppArmor profile as the fix. Step 5 under a real 5-minute cut: s5-run29, 30, 31 reported
PASS (and s5-run26 PARTIAL/27 TIME_BOUND/28 PASS on remaster98 before the fixes below). **Correction (2026-10-27
morning):** those passes do not show step 5 stable. Since stage 1, the VM member's agents were refused with
`internal_failure` / `policy_not_received` because no baseline was ever signed: the VM finished 0 lanes in s5-run31 and
in sbx-run2–4, and few in s5-run29/30, so the PC did the work. `run.sh` now fails a run in which any computer finishes
no lane; the step 5 runs are to be repeated after the member-policy fix.

**Fixed:** an author of a combined snapshot may take the combine (item 40); a re-merge granted to a session is a new
offer to the run controller (item 41); a completed session is offered an open review it may take (item 42); the provider
binding listing read another agent's half-written temporary file.

**Open:** the VM's AppArmor profile (owner's call); signing keys; Codex's own reads are unguarded; recovery of a lost
operator key; `RemergeService.validates` and the lane `alsoInLane` gate do not raise the sandbox request; a rare
`retry_required` from `ensure_session` in `OverlapAndCombineScenarioTest` under load.

# 2026-10-26 — ADR-0098 step 5 DONE under a real Link cut; ADR-0103 stage 4 built

**ADR-0098 step 5:** s5-run25 (remaster97 = b45415be) RESULT=PASS under a real 5-minute cut — diverged, re-merged
through two combines, `main` equal on both computers, logs identical, goal complete, tests pass. Designer items 32–39,
each found in a real run with the live stuck project and pinned by a scenario: one combine per conflicting change; a
lane that has published nothing is shown none of the pump's waits; the combine wait shown only to a combine lane; a
combine lane that keeps the other copy publishes that as its result; a lane in a closed group is told to end; a combine
lane is released once its result is published; and a trace test of the whole two-combine path that fails on any answer
an agent cannot act on. All seven ADR-0098 steps are now proven in real runs.

**ADR-0103 stage 4:** "ready without a task" (`agents run --wait-for-task`, `agents task`; a task message carries no
authority), UI start/stop of agents through one runner, OS package definitions and signing that turns on only with the
owner's key. Real runs of stages 2–3 passed with defects D1–D9 fixed (D9: Synesis's JVM crash files now go to its own
state folder, never the project). The unused project-record sync protocol was deleted (3,866 lines).

**Lab:** the Linux gate starts its container inside the Podman WSL distro; scenarios run on the Linux gate only;
the VM loses its network now and then and is reset.

**Open:** the installer's admin-prompt check (fresh install shown 2026-11-16; the decline path not checked, UAC is off on the only Windows PC); adapters for further harnesses (owner's choice);
ADR-0101 open; a late snapshot of a cancelled lane waits for review for ever (ADR-0098 step 5, noted).

# 2026-10-20 — ADR-0098 step 7 proven under a real cut; step 5 PARTIAL; yesterday's cuts did not reach the Link

**Correction to 2026-10-19.** The lab's Link cut (`~/accept/partition.sh` on the VM) blocked the PC's IPv6 addresses
by name, and the PC had rotated to a temporary address it did not list, so s5-run14, s5-run15, s7-run1 (the "PASS after
a 10-minute split"), s7-run3 and s7-run4 were connected runs, not split runs. The cut now blocks the LAN prefix
(internet hosts are outside it, so agents keep their providers); every cut since logs `LIVENESS_EXPIRED` on both
computers about 60 s in.

**Real runs with a real cut (remaster86–88; PC `claude:sonnet:medium`, VM `codex:gpt-6-luna:high`).**
- s7-run6: **RESULT=PASS**. The VM's agent stalled on an announced lane (`D:\SynesisAcceptance\lab\vm-stall.sh` cuts
  the Link and stops it), the PC's idle agent took the lane over during the cut, the VM trashed its version after the
  heal, the goal landed, `main` equal, logs identical. ADR-0098 step 7 DONE.
- s5-run16, s5-run17, s5-run18: logs identical after the heal, the re-merge opened a combine, but the copies of
  `main` did not end equal. ADR-0098 step 5 PARTIAL (owner's rule 16); designer items 30 and 31.

**Fixed today:** the owner recorded a member as connected after the Link went silent, and the run controller now
reads the same record (step 6 item 22); a stopped agent with no lane is woken for a takeover offer (ADR-0099 fix 15);
a stopped agent is woken for a re-merge review it may take, an agent the controller resumes is recorded as its earlier
session's successor, and a combine's holder may review as a last resort (fix 16). Lab: each Linux gate run has its own
Gradle home (no more journal-lock timeouts); `build-and-copy.ps1` refuses a PC copy without the Windows QUIC jar.

**ADR-0101** is open with its candidate rules and the owner's constraint (an agent's identity cannot be inferred from
a provider's session). **Open:** step 5 under a real cut; `CombineUnderRunControllerScenarioTest` disabled (1 in 3).

# 2026-10-19 — ADR-0098 steps 4 to 6 done, step 7 source-proven; ADR-0099 fixes 12 to 14; ADR-0102 built

On `adr-0098-step3-code-over-link`. ADR-0102 (a fast loop) was built and used the same day: scripted multi-computer
scenarios (`cli/src/test/.../coordination/*ScenarioTest`: S1 apart, S2 overlap and combine, S3 cross-computer review,
S4 takeover and trash, producers reviewing each other, three computers with the owner down, member removal, a reboot,
three-computer main meeting), `synesis log dump`, lab scripts in `D:\SynesisAcceptance\lab` (`build-and-copy.ps1`,
`run.sh`, `evidence.sh`), a parallel-safe Linux gate, and the reviewer-notes guidance (item 10).

**Defects found and fixed (each with a test that fails before it, unless noted).** A continuation grant held as
`PENDING_REVIEW`; a run ending before the post-heal combine (`COPIES_MEETING`); the combine loser sent to a person; a
re-merge review offered to no one after a reviewer's session was replaced (`SessionSuccessions`), given to the agent
holding the combine, or owed to an agent that was not running; the lower computer not taking the re-merged `main` (now
tried on every pull); a reviewer of another computer's snapshot never asked again after the heal (ADR-0099 fix 12); two
producers waiting on each other's review with no reviewer (reviews across work groups, ADR-0098 step 6 item 20, ADR-0099
fix 13); a lane with unpublished work shown another lane's review wait (fix 14; its test is a regression guard only);
the owner never using or recording a session that predates its accept (step 6 item 21: the owner could not fetch code
from, or judge away, a computer that joined); a returning agent never told its lane was taken over; the
`ClaimLockTest` flake. Lab: the VM needs its own Linux-QUIC build (ADR-0102 item 4).

**Real runs (PC: `claude:sonnet:medium`; VM users `synesis` and `synesis3`: `codex:gpt-6-luna:high`; no Git remote).**
- s7-run1 (remaster83, `3666a4f8`): 10-minute Link split. **RESULT=PASS**: four packages and NOTES landed, `main` equal,
  logs byte-identical, tests pass on both, both runs COMPLETED. Real current-build acceptance proven for: work apart
  through a Link failure, re-merge, cross-computer review after the heal, copies of `main` meeting.
- s6-run2 (remaster83): owner's Synesis stopped 10 minutes; the two members worked and reviewed each other through
  member cards; all three mains and log sets equal afterwards; the owner removed the third computer and `MEMBER_REMOVED`
  reached both members. The agents covered only part of the README goal (one package and NOTES) before ending
  COMPLETED: Synesis tracks lanes, not the README's list. PARTIAL by the script (the PC ran no agents by design).
- s5-run14, s5-run15 (remaster81, 82): PARTIAL, `main` equal and logs identical, one package missing each; both causes
  fixed above.
- Step 7 takeover in a real run: NOT REACHED (the VM finished its own lane). Source-proven by S4.

**Open:** both claims refused once under heavy load (ADR-0098 Unresolved); `link answer` prints `LINK_STATE=FAILED`
when the join already went direct; the Linux gate's shared Gradle cache causes journal-lock timeouts with parallel
agents; a dispute stays source-level; on Windows two scenario tests are skipped (a three-way main check not met in
180 s, and a @TempDir held open), so they run on the Linux gate only.

# 2026-10-18 — ADR-0098 step 3: code moves over Link, and the PC + VM goal completes with no Git remote

Built on `adr-0098-step3-code-over-link` (design in ADR-0098 "Build design", step 3, and the calls made while building
it). Code moves between computers as resumable `git bundle` pieces over Link (`SCX1`: at most 150 MB per piece, 256 KiB
chunks each with a SHA-256, `git bundle verify` and an fsck'd fetch); a computer serves only refs and commits its log
names. Agents' processes ask their own coordination server on the loopback `/code` route. A member's hand-in pushes
nothing; the leader's merge pass fetches the reviewed snapshots it may merge, outside the integration lock; members
follow `main` after pulls that name a head they lack; `code.remote` is an optional mirror; `link join --into <folder>`
copies the project into an empty folder over Link. Compatibility 7. Also: a join answered after its first dialing round
now connects (`LateAnswerTest`), and every `LINK_STATE=FAILED` names `LINK_FAILURE`.

**Defects found by the real runs and fixed (ADR-0099, 2026-10-18 fixes 9 and 10).** Run A1 (`mp17-link`) stopped at 6 of 7
modules: both PC agents had finished their lanes and stopped, and nothing resumed a stopped agent for a review it could
take (`AgentRunController`). Run A2 (`mp18-link`) stopped at 7 snapshots: an agent with no lane asked to review, stopped,
and was not resumed when the author accepted, and its session had already been ended. Each fix has a test that fails
before it.

**Real runs, this PC (leader, two `claude:sonnet:medium`) and the VM (member, two `codex:gpt-6-luna:high`), no Git
remote on either, joined by the one link with the answer link (this PC has no "Synesis Link" firewall rule).** The PC
ran the CLI from `installDist` with a `synesis.cmd` shim: Go is not installed on this OS, and the installer needs it.
- Run A3, `mp21-link`, remaster66: **COMPLETED** on both (166 s here, 159 s on the VM). WorkGroup COMPLETED; `master`
  equal on both (`6ff0c11`); `python -m unittest` 44 OK on both; `cli.py slug "Hello World!"` prints `hello-world`, an
  unknown command prints the usage line and returns 2. Every review by a non-author; PC Claude reviewed VM Codex's work.
  Real current-build acceptance proven for: joining over Link into an empty folder, code over Link in both directions,
  the leader merging member work fetched over Link, `main` following on the member, cross-computer review, WorkGroup
  completion, with no Git remote.
- Run B, `big450c`: a 450 MB history joined over Link in four pieces (141.6, 141.6, 141.6, 47.2 MB); the VM's cable was
  cut with 151 chunks of the first piece and none finished, restored 40 s later; the VM redialed (`REDIAL_OK`) and went
  on from chunk 151; `master` equal, `git fsck` clean. (The VM's ingress was policed to 20 Mbit/s for IPv4 and IPv6 so
  the cut landed inside a piece; two earlier tries at LAN speed cut between pieces and also ended equal.)
- Runs A1 and A2: PARTIAL, as above. Evidence: `D:\SynesisAcceptance\offline\STEP3-RUNS.md`.

**Gates.** Linux gate (Podman) on `1ebeb275`: 1,918 tests, 0 failures, 3 skipped. Windows: the touched classes of every
piece. The full Windows gate was not run on this branch.

**Open.** The leader does not pass a commit from one member to another (a member's reviewer gets another member's snapshot
once the leader holds it; always true with two computers). The invitation's mirror is not set for a joiner that used
`--into`. Public members of `CodeRemote` were removed with every caller migrated, not deprecated (ADR-0098, open to the
owner).

# 2026-10-18 — ADR-0099's known limits fixed, and Codex agents get a Stop hook

The owner asked on 2026-10-18 to fix every limit ADR-0099's amendment listed and to re-test Codex's hooks (USER-STATED);
the CLOSE answer for a lane its agent cancelled stays, by the owner's choice. Branch `claude/adr-0099-limits-3ceb11`,
eight commits (the last fixes the review's findings) and this docs commit; not pushed, not merged. Source-level
evidence only: no real run on this code yet (the main session re-checks it in its next PC + VM run after merging). The ADR stays Proposed. Design notes for each fix are in ADR-0099,
"Fixes of 2026-10-18".

**Root causes and changes.**
1. `keep` read only the lease, so a kept participant the log had suspended was resumed on the same connection and
   refused `SESSION_EPOCH_FENCED` until the run ended. The controller now sees the fence (`Observation.sessionFenced`),
   ends that connection and resumes the agent once on a new one, where its held lane is offered for continuation.
2. ADR-0093 made the operator closing a lane's request resume nobody, so a lane that asked and waited was never woken.
   The closing now wakes that lane alone: an `operator` entry in the Stop hook's lane view, and a once-only controller
   resume (`Observation.newestOperatorAnswerFor`). Still not progress.
3. `ControllerConnections.ended` gave up after 30 s on a busy lock, leaving the lease naming the controller. An end is
   now tried once per observation (`Connections.end`) until recorded; the run's end waits for the rest.
4. `resumeStale` reallocated a clean worker with private commits even when its session held an open lane. Such a
   session now keeps its binding and worktree.
5. A first process that opened and closed its lease within 2 s beat the controller's first `keep`. The controller now
   reserves itself as keeper before starting the process (`<connection>.keeper` note); the MCP server names the
   reserved keeper when it is alive and the server descends from it (the `keep` fence), so its own close is a no-op.
7. Codex 0.163.0 re-test (below). Agents the controller starts on Codex get Synesis's own Stop hook on the command line
   with its trust pinned to its hash, for that run only; `synesis hook codex` answers Stop as Claude's hook does.
   `provider install/status codex` report hook review as `TRUST_NOTE` beside a healthy install, not `DEGRADED`.
8. `LeaseKeeperTest` used `ProcessHandle.current().parent()`, empty on this fresh Windows 11 install; it now launches a
   foreign process.

**Codex verdict (VERIFIED on Windows and the Linux VM, 14 model runs, codex-rs source at `rust-v0.163.0`).** Hooks run
in `codex exec`, `exec resume` and `app-server`, but only trusted ones. The 0.162 tests saw nothing because an
untrusted project's hooks are never loaded (the bypass flag does not change that), `node` is not on this PC's `PATH`,
and pwsh breaks inner double quotes. A hook given with `-c hooks.Stop=[...]` plus `-c hooks.state={'<key>'={trusted_hash=...}}`
runs with no project trust, no bypass and nothing written to `~/.codex`. A blocking Stop continues the same turn with
the reason, `stop_hook_active` is set, the environment is inherited. Unlike Claude Code: no continuation cap (Synesis
caps at three), a timed-out hook is not killed on Windows (Synesis ends its own hold first), exit 2 does not work on
Windows (Synesis uses the JSON form). The trust key and hash recipe are internal to Codex; a Codex that changes them
skips the hook silently and the controller wakes the agent as before.

**Tests.** Each fix has a test that fails on the code before it (run on Windows with the old code
put back, unless marked compile-time): `ControllerConnectionsTest.aProcessThatOpensAndClosesItsLeaseBeforeTheControllerLooksLeavesTheSessionKept`
(5; a real child JVM opens and closes its lease), `AgentRunControllerTest.anAgentWhoseKeptSessionWasFencedIsResumedOnceInANewSessionToContinueItsLane`
(1), `LaneWakeTest.theOperatorClosingARequestTheLaneAskedWakesIt` (2), `ProviderSessionBindingServiceTest.aStaleCleanWorkerWithPrivateCommitsKeepsTheSessionOfItsOpenLane`
(4), `LeaseKeeperTest.aProcessCannotKeepTheSessionOfAProcessItDidNotStart` (8, failed every run before);
compile-time before: `AgentRunControllerTest.anEndTheProjectCouldNotRecordIsOfferedAgainUntilItIsRecorded` and
`ControllerConnectionsTest.anEndTriedWhileTheLockIsBusy...` (3), `...theOperatorClosingALanesRequestResumesThatLanesAgentOnceAndNoOther`
and `ParticipantActivityTest` 2 (1, 2), `HookCodexStopTest` 2, `CodexStopHookTest` 2 (hash pinned to the one Codex 0.163.0
reported), `HeadlessSessionContractTest` 2 (hook arguments; a real `.cmd` shim in a folder with a space receives them
intact), `LeaseKeeperTest.aReservationNamingAProcessTheOpenerDoesNotDescendFromOrADeadOneNamesNobody` (7, 5).
Full gate on the final code: **Linux** (Podman, `check`): BUILD SUCCESSFUL, link 199, coordination 324,
project-record 60, relay 12, mcp-contract 20, workspace 856 (3 skipped), mcp 201, cli 200, no failure. **Windows**
(`check --max-workers=4`, single-use daemon): BUILD SUCCESSFUL, the same counts with workspace 856 (1 skipped), no
failure. A first Windows attempt was stopped by another session's `gradlew --stop` (not a test result). Outside the
gate, `ControllerConnectionsTest`'s lock tests failed about one run in four on Windows, old tests included (Open).
Real check without a model call: Codex 0.163.0 `hooks/list` reports the hook Synesis builds for this PC's install path
`trusted`.

**Review.** Independent review (Sonnet 5.5, requested effort medium; effort metadata unavailable). Acted on:
a `.cmd` shim in a folder with a space would have lost its path (now `cmd /d /s /c` with one quoted line, real test);
a keeper reservation with an unknown start time failed open (now names nobody), with negative tests; a fenced lane's
resume before its end is recorded; the operator closings scanned per call; a backslash allowed in a Linux hook path.
Not changed, with reasons in ADR-0099: the fence rule is restated once (the coordination module keeps it private); the
660 s timeout is not derived from `LaneWake.HOLD` (the provider package does not depend on the run package); keeper
notes left by a killed controller are not swept; `AgentRunController` is near 900 lines.

**Open.** Real-run evidence is NOT REACHED for every fix: the main session re-checks them in its next PC +
VM run after merging, and should see Codex agents held by their Stop hook (`synesis hook codex` Stop entries in local
diagnostics, `WOKEN`) instead of exiting at every WAIT. The Codex hook rests on Codex internals (trust key, hash
recipe); a release that changes them skips it silently. Changed meanings that need the owner's approval with the ADR:
`provider install/status codex` no longer `DEGRADED` for hook review alone, and an operator closing a lane's request
now wakes that lane (ADR-0093 narrowed). A transient failure to read `.synesis/project.json` sends lease reads to a
`default-project` folder (`ProjectAdministrativeLayout.resolveProjectId`), the cause of the Windows lock-test flake;
recorded, not fixed. `link join` answering too early is the main session's (step 3).

# 2026-10-17 — A waiting agent keeps its session (ADR-0099 amendment) and the PC + VM rerun (COMPLETED)

Fixes for the five defects of the overnight entry below, with the owner's approval of the three rule changes
(2026-10-17, recorded in ADR-0099, "Amendment of 2026-10-17: a waiting agent keeps its session"). Four more defects
were found by the real reruns and fixed the same way; each fix has a test that fails on the previous code.

**Root cause of 1, 2 and 5 (VERIFIED in run `run-0e6c908a`).** The run controller gave every process of an agent a new
connection identity and recorded the old one ended; the MCP server also closed its own lease at end of input. A
Codex agent's process exits at every WAIT, so every wait became a departure: SUSPENDED and RECOVERY_HELD within seconds,
and each resume a new session with no lane. `claude#2` was resumed ten times and in resumes 6 to 8 claimed a new module
while `continuation_available` offered its own held lane: "four modules" were four sessions of one conversation.

**Changed.**
- Decision 1 (rule): one connection identity per controller agent, reused on every resume, so the same session and
  lane. `SessionLeaseService.keep` points the lease at the controller (only a lease naming the controller or a live
  descendant of it, never a closed or terminal one) and renews it; the MCP server's close then no longer applies (pid
  fence). `AgentRunController.keepOrEnd` keeps a session while the agent runs or may come back, and otherwise ends it
  and runs recovery at once (`ControllerConnections.ended`); a busy lock decides nothing. Interactive agents unchanged.
  The Stop hook does not renew: the process it holds is alive.
- Decision 2 (rule): `--max-resumes` bounds idle resumes in a row (nothing recorded while the process ran), any
  progress resets it. An agent whose lane ended while it was stopped is resumed once on a new connection.
- Decision 3 (guidance): review candidates only for `ACTIVE` authors.
- Decision 4 (rule): `ensure_session` on a connection whose lane completed starts a fresh session (the fence lets it
  through); `ProviderSessionBindingService.ensure` refuses `SESSION_AUTHORITY_OPEN` while the ended session still holds a
  grant, request or command.
- Decision 5: documented, one open lane per agent follows from one session per agent.
- Found by the reruns: the controller's observer cached a connection's participant and ended kept sessions that had
  just taken their next lane (run 1; `EventLogObserver.participantOf` now reads the binding each time); a completed lane
  could not record the review it held (run 3; the fence admits the tools whose policy admits completed bindings, and
  only grant consumption and the review decision accept one downstream); the leader merged accepted member work only on
  a member entry or a leader agent call (run 3; `MergeDriver.repeatEvery`, 30 s); a reviewer lane whose module was
  integrated through another reviewer waited for ever while other modules were open (run 4; it is now told it may cancel
  , though a cancelled lane still does not start a fresh session; see Open).

**Tests (fail before, pass after; Windows).** `McpRecoveryContinuationTest.freshSessionIsNotLedToReviewASuspendedAuthorWhileAContinuationWaits`,
`...aReviewerWhoseModuleWasIntegratedElsewhereIsToldItMayCancel`,
`McpSyn039NoChangeCompletionTest.aConnectionWhoseLaneCompletedStartsAFreshSessionForItsNextTask`,
`ProviderSessionBindingServiceTest.sessionWhoseLaneEndedKeepsItsSessionWhileItStillHoldsARequest`,
`ParticipantActivityTest.theRunControllerSeesTheLaneAConnectionTookInAFreshSession`,
`McpProducersReviewEachOtherTest.aProducerWhoseOwnModuleWasIntegratedStillFinishesTheReviewItHolds`; new classes or API
(compile-time "before"): `LeaseKeeperTest` 3, `ControllerConnectionsTest.aSessionIsNotGivenUpBecauseTheProjectLockWasBusy`,
`AgentRunControllerTest` (kept session, lane ended, idle budget; three budget tests rewritten for the new meaning),
`MergeDriverTest.aRepeatingDriverRunsPassesWithNoMemberEntry`. Windows gate (`check`) on the code before the last four
fixes: every module green (link 199, coordination 324, project-record 60, relay 12, mcp-contract 20, workspace 837 with
1 skipped, mcp 199) except one `cli` test, `ControlPlaneReplicationTest.aMemberAddedOrRemoved...` (LEADER_UNREACHABLE
for PROPOSER_NOT_A_MEMBER), which passed alone on rerun. After the last fixes only the affected classes were rerun.
Windows gate (`check`) on the final code before the last correction: two failures, both from late changes,
`SyntheticTwoProcessCollaborationTest.cancellingTheLastOpenLane...` (a cancelled lane's session is told CLOSE; the change
that gave it a fresh session was reverted) and `ReviewAdmissionOrderIndependenceTest.reviewerWhoseProducerLeft...`
(instruction wording, test updated); those classes and the changed ones pass on Windows. The whole gate was not rerun
after that correction. The Linux gate was not usable: the Podman machine died at every test start.

**Review.** Independent thermo-nuclear review (Sonnet 5.5, medium; effort metadata unavailable). Acted on: a transient
lock failure ended a kept session; the open-authority rule moved into the binding service so both routes to a fresh
session follow it, with a refusal test; duplicate process-identity code. Not changed, with reasons in the ADR: the
budget does not bound resumes for progress (counting them starves the waiting agent again); `keep` reads only the
lease; a keeper on Windows reads AMBIGUOUS, never recovered.

**Real runs, this PC (leader, two `claude:sonnet:medium`, MCP JVMs capped `-Xmx384m` from run 3) and the VM (member,
two `codex:gpt-6-luna:high`), README.twopc-big, one-link join each time, `AGENT_SESSION_KEPT` for all four agents in
every run, on Windows and Linux.**
- Run 1, `mp10-big`, remaster59: 4 of 7 modules (3 snapshots) in 6 min, five participants, none held; then the observer defect
  ended two kept sessions (count, cli held). Stopped. PARTIAL.
- Run 2, `mp11-big`, remaster60: environment failure, not a product result. Three MCP JVMs died at start with
  "insufficient memory" (free commit 2 GB: another session's Android emulator and Studio, the VM's 8 GB); their
  `hs_err` files in the checkout raised `control_checkout_foreign_changes` on the VM. VM reduced to 5 GB.
- Run 3, `mp13-big`, remaster60: 2 modules; then the review-grant fence and the merge-pass gap. Stopped. PARTIAL.
- Run 4, `mp14-big`, remaster62: 6 of 7 modules integrated in about 25 min (`python -m unittest`: 27 tests OK), `cli.py`
  published and waiting for review, which no agent could take (cancelled lane not ended; reviewer lane waiting on an
  integrated module). Stopped. PARTIAL.
- Run 5, `mp15-big`, remaster63: 6 of 7 in about 10 min, `cli.py` lane active; stopped by me for a PC restart.
  Interrupted, not a result.
- Run 6, `mp16-big`, remaster63, after the restart: **COMPLETED** on both computers (`AGENT_RUN_RESULT=COMPLETED`,
  791 s on the VM, 793 s here; `AGENT_RESUMES` 13 on the VM, 6 here). All seven modules on `master` in 7 snapshots;
  checked independently: `python -m unittest` 36 tests OK, `python cli.py slug "Hello World!"` prints `hello-world`,
  an unknown command prints the usage line and returns 2. Seven reviews, all ACCEPTED, none by the author (mandatory
  independent review): four across computers (Claude reviewing Codex twice, Codex reviewing Claude twice), three
  between agents of one computer. Nine participants in all, none SUSPENDED or RECOVERY_HELD. Real current-build
  acceptance proven for: blind discovery, concurrent claims, kept sessions across waits on Windows (Claude) and Linux
  (Codex), cross-computer review admission and decision, leader merge of member work, WorkGroup completion, durable
  goal completion. The build differed from the committed code in one reverted line (a cancelled lane counted as ended;
  no lane was cancelled in this run).

**Open.** An agent that cancels a lane (for instance a reviewer whose module was integrated through another review)
cannot take a next lane in the same process: its session is told CLOSE and a task is refused
(`workspace_generation_changed` in run 4); giving it a fresh session conflicts with the tested CLOSE behaviour and needs a
decision. The controller still starts it again on a new connection once its lane has ended. A first process that exits within 2 s of opening its lease closes it before the controller keeps it (old
behaviour then). `link join` that returns WAITING before the answer is pasted makes the answer fail (`LINK_STATE=FAILED`,
no reason printed); a join left waiting longer worked. `provider install codex` reports DEGRADED (hook trust
REVIEW_REQUIRED) as before.

# 2026-10-17 — Overnight real run, this PC and the VM: woken agents meet the liveness lease (stopped, NOT REACHED)

**Run.** remaster57 (master `597637a6` plus the design commits) on this PC (leader, two `claude:sonnet:medium`) and the
bridged VM (member, two `codex:gpt-6-luna:high`), project `mp9-big` (seven modules, `cli.py` depending on six,
mandatory review, no dedicated reviewers), code remote a bare repository on the VM over SSH. Joined with the one link:
`link invite` printed `LINK_FIREWALL=STALE` and its NOTE (the installer had too), the VM's `link join` printed
`CODE_REMOTE_CHECK=REACHABLE`, the answer link connected, `link accept`, and the leader's status printed
`REPLICATION_MEMBER=... STATE=CONNECTED`. 204 log entries, 114 proposed by the VM. Stopped at 07:10: one module
(`roman`) integrated, completion NOT REACHED.

**Proven on real current builds.** The firewall report (install and invite), the join-time fetch check, the leader's
member state, the Stop hook installed for Claude, producers reviewing each other on one computer (`roman`), and across
computers: a Codex agent on the VM was admitted to the review of `slug` written by Claude here, ran its tests and
accepted it; Codex agents ended their turn on WAIT and were resumed by the run (ADR-0099).

**Setup error, mine.** The first VM run ended `NO_WORK_ANNOUNCED` in 25 s: the VM's `PATH` put the raw launcher
`/opt/synesis/synesis/bin/synesis` first, `provider install codex` wrote it into `~/.codex/config.toml`, and Codex
starts MCP servers without `JAVA_HOME`, so no Synesis tools loaded. Reinstalled through the wrapper
`/usr/local/bin/synesis` (it exports `JAVA_HOME`). Not a product defect, but `provider install` could refuse a
launcher that cannot start without the caller's environment.

**Why it stalled (open, design-level).**

1. Waiting now ends the turn (ADR-0099), but the session lease still reads that as absence (stale 2 min, grace
   5 min, `SessionLeasePolicy`). A Codex agent has no process between resumes and a Claude agent may sit up to 10 min
   in its Stop hook, so ordinary waits turn producers into absent authors: their lanes go SUSPENDED and then
   RECOVERY_HELD, and review requests addressed to them wait on nobody.
2. `--max-resumes` (default 8) now counts waits: `claude#2` reached 8 and `codex#1` 7, after which a request addressed
   to them can no longer resume them. A retry with `--max-resumes 30` did not help, because of 3.
3. Fresh agents of a restarted run were offered reviews of snapshots whose authors were gone but not yet RECOVERY_HELD
   (`reviewCandidatesFor` excludes only RECOVERY_HELD), took reviewer lanes, and then could not continue the held
   authors (one lane per session). All four waited on authors; both controllers waited on each other
   (`FOREIGN_LANE_OPEN` / `OTHER_COMPUTER_CONNECTED`).
4. A Claude agent whose lane was integrated was refused a new reviewer lane: `ensure_session` with a task hits the
   recovery fence on its COMPLETED binding (`McpProtocolHandler.refuseFencedRecovery`, `SESSION_RECOVERY_STATE_UNAVAILABLE`),
   so it stopped while work remained.
5. One Claude agent claimed four modules in turn while others had none, then waited on four reviews at once.

# 2026-10-17 — Review findings on "a producer waiting for review may review the others" fixed

An independent review of the entry below found three defects, all coming from review authority no longer needing a
reviewer lane. ADR-0089's amendment is updated with them.

**Changed.**
- Self-approval by continuation, fixed fail closed: a lane held for recovery is not continued by a participant holding an
  undecided review grant on it (`REVIEW_SELF_NOT_ALLOWED`, `AppendAdmission.requireLawfulContinuation`). Role isolation
  had refused only a successor holding a reviewer lane, so a producer reviewing a lane through a grant could continue it
  and accept its snapshot as its owner.
- Stranded reviews: a continuation now carries the source owner's review grants and its own review requests to the
  successor whatever the lane's role (`WorkGroupProjection.migrateReviewGrants`, `CoordinationRequests.retargetReviewRequest`),
  and the successor is shown the carried grant (`AgentReviewActionProjection.hasExplicitReviewGrant`). Admission without
  a reviewer lane now also needs a lane of the requester in the group, so every such reviewer has a lane to be continued
  from. A session holding an open grant or request cannot be terminalized (`terminalBlockers`), so no terminal-participant
  rule was added.
- An owner asked twice for one snapshot: the admission offered to the owner is checked by the acceptance rules first, and
  an accept that would be refused is offered as `REJECTED` with the refusal (`AgentReviewActionProjection.reviewAcceptanceAction`).
  The owner is no longer shown the same refused accept, and the second requester gets its answer.
- One rule, `ReviewAdmissionRules.holdsReview`, now says whether a grant already holds a snapshot's review, for admission
  and for the candidates agents are shown.

**Proven at source level only** (Linux gate): `AppendAdmissionTest.aSuccessorCannotTakeOverALaneItHoldsAnUndecidedReviewGrantOn`,
`WorkIntentServiceTest.theReviewAProducerHeldMovesToTheParticipantThatContinuesItsLane` and
`anAuthorWhoseLaneHasEndedIsNotAdmittedToReview`, `McpProducersReviewEachOtherTest.anOwnerAskedTwiceForOneSnapshotAdmitsOneAndIsToldToRejectTheOther`.
All four failed on the previous code. No real run yet.

**Open.** A producer whose own snapshot integrates while it holds a review grant keeps the grant with no lane; if its
agent then dies, nothing continues that review (the separate task on reviews whose agent is gone). A participant that
handed a lineage's lane off before publishing in it is not excluded from reviewing it.

# 2026-10-17 — Agents are woken, they do not wait (ADR-0099, "Two homes, bigger goal" defect 2)

**Defect.** Told WAIT, agents slept: 16 `run_command` sleeps of 20 to 900 s in run `run-fb2eab33` (`python -c
"import time; time.sleep(590)"`), while the review request addressed to the sleeping author went unanswered.
Owner decisions (2026-10-17): agents never wait themselves; provider hooks wake them; any member may wake any lane on
any computer (per-member permission is a recorded future item).

**Provider capabilities, verified on this PC.** Claude Code 2.1.291 in `claude -p` stream-json mode: a `Stop` hook's
`{"decision":"block","reason":...}` continues the turn and the model acts on the reason; the next stop has
`stop_hook_active: true`; a hook past its `timeout` lets the turn end; hooks inherit the controller's connection
variable; a `PreToolUse` deny reason reaches the model. Codex 0.162.0 has the same `Stop` schema, but `codex exec` ran
none of a project's hooks (plain, `--enable hooks` with `--config` hooks, `--dangerously-bypass-hook-trust`; not even
`SessionStart`), so Codex agents are woken only by the run controller after they stop.

**Changed.**
- `run_command` refuses an argv that only sleeps (`POLICY_DENIED`, `reason: SLEEP_COMMAND_REFUSED`, instruction to end
  the turn), before anything runs. One rule, `SleepCommands`, also denies pure shell sleeps of run-controller agents in
  the Claude `PreToolUse` hook (matcher now `Edit|Write|Bash|PowerShell`); interactive sessions are not affected.
- A managed Claude `Stop` entry (`synesis-claude-code-stop`, `timeout` 660 s). When `get_next_action` answers WAIT to a
  run-controller connection, the MCP server writes the caller's lane view to `.synesis/local/waits/<sha256>.json`;
  any other answer removes it. On Stop, `LaneWake` holds the agent up to ten minutes, watching the local log's head
  every 400 ms, and blocks the stop with what arrived (a request for or answer to its lane, a grant, a review result,
  its task's state, a contract or capability request, a message, its WorkGroup, its lane ending) or when the time its
  wait names passes; at most three wakes without a new WAIT. Replicated events land in the local log, so another
  computer's event wakes the agent with no new transport, and `send_message` is the existing way to wake another lane.
- Guidance (managed AGENTS.md section, provider manual, `get_next_action` description): on WAIT, end your turn.
  Replaces commit `394a0d38`'s "wait within your turn", whose controller wait (keep a run waiting while another computer
  is connected) this builds on. The run controller's resume trigger already covers every event addressed to a stopped
  agent's lane (it resumes on any progress and on messages), so no second trigger was added.

**Tests** (fail before: the classes did not exist, or the old answer ran the sleep): `SleepCommandsTest` 4,
`LaneWakeTest` 11, `LaneWakeAcrossComputersTest` 1 (leader and member joined by a real Link session on loopback: a
request made on the leader wakes the member's waiting agent through replication, measured 209 ms on Linux and 211 ms
on Windows, so the leader's notice and not the 15 s pull carried it), `HookClaudeStopTest` 4,
`McpToolMalformedArgumentsTest.runCommandRefusesACommandThatOnlySleepsWithoutRunningIt`, and the managed-file golden
tests updated. Linux gate before the review fixes: every module green (workspace 828, mcp 194, cli 199, coordination
319, link 199) once the catalog golden file was updated (description change; wire digest unchanged, content digest
changed). After the review fixes: the affected classes pass on Windows, and the whole Linux gate (`check`) is green:
link 199, coordination 319, project-record 60, relay 12, mcp-contract 20, workspace 831 (2 skipped), mcp 194, cli 199.
The Windows gate was not run.

**Review** (thermo-nuclear, independent Opus reviewer). Fixed: a lane that ended (integrated, revoked, continued) let
the agent stop silently; the consumer side of capability requests was not watched; waits that end at a time had no
wake, and the recovery-deadline guidance still said to wait until an epoch; shell sleeps were refused to people too;
`sleep 600 && echo done` passed; notes were never removed. Recorded, not done: one list of managed hook entries per
provider; a fitness test over every WAIT reason. Not tested: the consumer-side capability wake (one filter line using
the projection's own `matchesRequester`).

**Real run, one computer** (`0.1.0-dev.remaster57-20261017` run from its extracted zip with `PATH` and
`SYNESIS_MCP_LAUNCHER` pointing at it, so the installed remaster56 and its daemon were not touched; project `wake-2`,
`README.review.md`, mandatory review, two `claude:sonnet:medium` agents, `run-7d3f2bac`). `AGENT_RUN_RESULT=COMPLETED`
in 68 s, `AGENT_RESUMES=0`, snapshot integrated, `python -m unittest` OK, no sleep in either log. The writer published,
was told WAIT, ended its turn (02:16:37 UTC) and was woken by its Stop hook 4 s later: "a review result; your task's
completion state changed"; told WAIT again during integration, it ended its turn and was woken with "your lane has
ended", then saw COMPLETED and stopped. Hook diagnostics: 2 `WOKEN`, 2 `STOP_ALLOWED`; the waits folder was empty at
the end. Real current-build acceptance proven on one computer: Stop-hook wake on a review result and on lane end.
NOT REACHED: a real two-computer run with this build (the wake across computers is proven at source level only), a
Codex agent, a wake on a review request to admit (in this run the 20 s held `get_next_action` caught it).
A first attempt (`wake-1`) is not a product result: its `.mcp.json` named the installed remaster56 MCP server
(the MCP launcher resolves `%LOCALAPPDATA%` before `PATH`), whose catalog digest did not match the new manual, so
claims were refused with `manual_attestation_required`. The user-wide manual was restored to remaster56 afterwards.
# 2026-10-17 — A producer waiting for review may review the others (ADR-0089 amended; defect 1 of "Two homes, bigger goal")

Owner decision of 2026-10-17: an agent whose own published snapshot waits for mandatory review may review other agents'
snapshots, never its own. In that run, four writers each held `WAIT` for their own review, were offered nothing
else, and with no dedicated reviewer no review started. Two things kept them there. Admission refused any requester
without a reviewer lane (`REVIEWER_INTENT_REQUIRED` in `ReviewAdmissionRules.validateReviewAdmission`), and a session
holds one lane, so a producer could not take one. And `get_next_action` (`ReviewLaneProjection.reviewPendingAction`)
answered the waiting producer with the wait alone.

**Changed.**
- Admission (`ReviewAdmissionRules`): a participant that published a snapshot in the work group may be admitted without
  a reviewer lane. A participant that published a snapshot in the target's authority lineage is refused
  `REVIEW_SELF_NOT_ALLOWED`, so a continuation of its own work is not reviewable by it either. The review is a grant to
  the participant; it keeps its one producer lane and claim epoch.
- `get_next_action`: a producer whose current snapshot awaits review is offered, before its wait, the open candidates
  of its work group outside its own lineages (`nextAction: request_coordination`, `actionableReviewCandidates`; an
  executable payload when there is one, the producer's choice when there are several).
- A review admission asked of a lane comes before the lane's own wait for an answer to its review request, so producers
  that ask to review one another answer one another.
- `reviewCandidatesFor` leaves out candidates in a lineage the caller published in, by the admission's own rule
  (`ReviewAdmissionRules.authoredInLineage`). The rule is tested at admission; the guidance for a continued lane has no
  test of its own.

**Proven at source level only** (Linux gate): `McpProducersReviewEachOtherTest` (four producers, no reviewer, every
snapshot accepted by another producer, self-review refused, no reviewer lane announced; an admission not hidden by the
producer's own request), and two `WorkIntentServiceTest` cases. All three failed on the previous code. No real run yet.

**Open.** Two producers asking for the same candidate at once: the owner can admit one and must reject the other
(`REVIEW_CANDIDATE_ALREADY_ASSIGNED` on accept). Defects 2 to 4 of that entry are separate tasks (defect 3 is the held-author fix just below).

# 2026-10-17 — A fresh agent continues a held author before it is offered that author's snapshot for review (defect 3)

**Defect** ("Two homes, one project", first attempt, defect 3; agent logs of run `run-6105f17e`). Two snapshots were
`REVIEW_PENDING` and both authors were `RECOVERY_HELD`. Each fresh agent's task-less `ensure_session` answer listed both
snapshots as `reviewCandidates` with a ready `reviewerIntent` and no `continuationCandidates`; each claimed a reviewer lane
and sent `work_group_join`. The request is addressed to the author, who could not answer, and a session that holds a lane
cannot continue another (`CURRENT_PARTICIPANT_HOLDS_A_LANE`), so both waited on `owner_response_pending` until their polls
ran out and the run ended `STALLED`.

**Rule (unchanged, ADR-0085 "Producer successor review requests").** A pending review request follows only the
author's lawful successor, who answers it; no review is accepted automatically, and presence is not consent (ADR-0083).
So admission does need a live author lane, and the gap was routing.

**Changed (guidance only; no authority or admission rule).**
- `reviewCandidatesFor` (claim-required instruction and `ensure_session`) leaves out a snapshot whose author lane is
  `RECOVERY_HELD`. It is offered again once that lane is continued.
- A lane-less caller is pointed at continuation or review in this order: a held lane that a pending review request names
  (the reviewer waits for its successor); else a review it can be admitted to; else any held lane
  (`RecoveryContinuationProjection.continuationsFirst`). An author awaiting review needs two lanes, a successor and a
  reviewer, so with two fresh agents and two held authors the second agent now reviews the continued author instead of
  continuing the other one (two successors would wait for reviewers that never come).
- `ensure_session` shows the same choice (`CollaborationResponseMapper.openLaneChoices`): either
  `continuationCandidates` with `continuationInstruction`, or `reviewCandidates`. Additive result fields.

**Tests** (`McpRecoveryContinuationTest`, all three fail before the change):
`freshSessionContinuesAHeldAuthorBeforeItsSnapshotIsOfferedForReview` (held author, fresh agent continues, second fresh
agent is offered the review, requests it, the successor admits it, the reviewer gets its grant);
`secondFreshSessionReviewsTheContinuedAuthorRatherThanContinuingAnother` (two held authors);
`freshSessionContinuesTheHeldAuthorAWaitingReviewerAskedFirst` (the stalled state itself: a reviewer already waits on a
held author; a fresh agent continues that author and is shown the admission).
Windows runs: the class 9/9 (3/3 failing without the change); `:workspace:test` 799/799 and `:mcp:test` 192/193 before the
review refactor, the one failure `McpSyn039NoChangeCompletionTest.staleWorkspaceGenerationIsRejectedWithoutRelease` with
`NoSuchFileException` on its own worktree (no review or continuation path; it passed 13/13 on rerun); after the refactor
`AgentNextActionServiceTest`, `AgentWorkflowReducerTest`, `McpRecoveryContinuationTest`, `McpSyn039NoChangeCompletionTest`,
`McpAdmissionRecoveryTest` 68/68, and `:workspace:check`/`:mcp:check` gates without tests plus `repositoryHygieneCheck` pass.
The Linux gate was not usable: the Podman WSL machine stopped mid-run three times.

**Not done.** No real run yet. Two fresh agents that both read the choice before either acts can still continue two
different held authors (each then waits for a reviewer); a later agent is pointed at reviewing. The public collaboration
projection's `reviewCandidates` still lists a held author's snapshot (it is project state), so a caller of another
provider, which cannot continue that lane, could still pick it from the raw list.
# 2026-10-17 — A member that cannot fetch says why, at join and to every agent (defect 1 of "Two homes, one project")

**Cause.** On a member, `ProviderWorktreeAllocation.withWorktree` runs `CodeRemote.followLeaderIfMember` (a `git fetch`
with no prompt). When Git had no stored sign-in, the binding was saved `FAILED` with `lastSeenState`
`CODE_REMOTE_UNAVAILABLE`, and Git's reason was dropped there. Readiness then answered `workspace_stale` /
`SESSION_NOT_ACTIVE`, `ensure_session` `workspace_not_ready` / `SESSION_NOT_ACTIVE`, both retryable, and
`provider install` only `WORKTREE_BINDING_STATUS=FAILED`. Nothing at join time asked the remote.

**Change (uncommitted).**
- `CodeRemote.Refused.detail()`: Git's first `fatal:`/`error:` line (it was the last line, which for a missing
  repository is generic advice), with `user:password@` removed from any URL, then "Run `git fetch <remote>` once in a
  terminal in this project's folder and sign in if Git asks ...; then try again." `CodeRemote.check(root, remote)` runs
  `git ls-remote <remote> HEAD` through the same runner (refuses a name starting with `-`).
- `Binding.lastSeenDetail` (new last record component; the old 24-argument constructor stays). Set only with the
  `lastSeenState` of a code-remote refusal and cleared whenever that state changes. Persisted as `lastSeenDetail` only
  when set, so other bindings are written as before; same schema version.
- `WorkspaceReadinessService`: a binding with no worktree and a detail answers `blocked`, `workspace_not_ready`,
  `request_human_help`, result `{diagnostic: <lastSeenState>, detail}`. `ensure_session` returns that same answer
  (`AgentSessionFailure` keeps a `blocked` readiness answer whole). The next ensure still retries the fetch, so the
  agent starts once the person has signed in.
- `provider install`/`status`: `WORKTREE_BINDING_DIAGNOSTIC` (when there is no worktree) and `WORKTREE_BINDING_DETAIL`.
- Join: warning, never refusal (the invitation is single use and the fix comes after). `open-project-link` adds
  `codeRemoteCheck` (`REACHABLE` or the code) and `codeRemoteDetail`; the check runs in the runtime that answers the UI,
  after the join, so it cannot time out the folder runtime's request. `synesis link join` prints `CODE_REMOTE_CHECK` and
  `CODE_REMOTE_DETAIL` itself (its request timeout is 15 s, the Git remote timeout 30 s). The UI join panel shows the
  detail as an alert under the connecting message. Docs: control-plane-v1 (`open-project-link`), TWO_MACHINE_TESTING.

**Changed on purpose.** The answer for this case was retryable (`retry_required`/`ensure_session`) and is now `blocked`/
`request_human_help`; readiness's reason for it moves from `workspace_stale` to `workspace_not_ready`. No new
`AgentReason` value, because `AgentReason.fromValue` throws on an unknown value and replayed command answers are parsed
with it. Every Git refusal message from `CodeRemote` now ends with the `git fetch` sentence.

**Not done.** Synesis's Git runs with `GIT_CONFIG_NOGLOBAL=1`, so a credential helper set only in `~/.gitconfig`
(`gh auth setup-git`) is not used, and `git fetch` in a terminal can succeed while Synesis still fails; the detail does
not say this (the friend guide does). Not run on two real computers.
# 2026-10-17 — A run keeps waiting while another computer of the project is connected (defect 2 of "Two homes")

**Why no resume.** The first two-home run's agent logs (`agent-runs/4ff01567-.../run-8b50f524-...`) show both owner
agents publishing (`REVIEW_PENDING`, `validation_required`, `maxUnchangedPolls` 12), polling five times, then ending
their turn on a 45 s background timer, which ends a headless process. Nothing was recorded after they stopped (events 8
to 11 at 02:13 are their exits), so `progressedSince` was false and no agent was resumed. No participant of the friend
held a lane (its bindings had failed, defect 1), so `foreignActiveLaneOpen` was false too, and the run ended `STALLED`
although `replication.log` shows the friend `CONNECTED` from 23:08 to 23:22 UTC. In the successful run the friend's
reviewers held lanes and recorded reviews: that foreign lane and that progress were the state missing the first time.

**Rule.** When every agent has stopped and the run would end `STALLED`, it also keeps waiting while another computer of
the project is connected (`Observation.otherComputerConnected`: for the leader a member, for a member its leader, whose
newest `CONNECTED`/`DISCONNECT` line in `replication.log` is a start; `ReplicationJournal.connectedPeers`) and one of its
agents can be resumed and holds an open lane. The bound is the run's own deadline, as for a foreign lane; progress from
the other computer resumes the agents by the existing path. Each wait is printed once when it begins:
`AGENT_RUN_WAITING=FOREIGN_LANE_OPEN`, `OTHER_COMPUTER_CONNECTED`, or `ANSWER_DEADLINE`. The managed `AGENTS.md`
section and the provider manual now say to wait within the turn, not on a background timer, since a headless run ends
with the turn.

**Limits.** The journal is what a computer last recorded: a runtime that died leaves `CONNECTED`, and a dropped link
that is redialing reads as disconnected, so the run then ends as before. Two computers whose stopped agents wait on
each other with nobody to serve them now both wait until `--timeout-minutes` and end `TIMED_OUT` instead of `STALLED`.
Source semantics proven (`AgentRunControllerTest`, `ConversationServiceTest`, `ReplicationJournalTest`); not yet run
across two real computers.
# 2026-10-17 — A firewall rule left naming an earlier version is reported (defect 4 below)

**Why.** The rule "Synesis Link" names one version's `runtime\bin\java.exe`. Only `install`/`repair --allow-link` and
the wizard replace it, so an update without the option left it naming remaster47 while remaster56 ran, and this
computer stopped accepting dials with no message.

**What changed.** Reported, not refreshed: the command line asks for elevation only when told to (ADR-0096 decision 2)
and an unattended update cannot answer a prompt; the wizard already refreshes the rule once it exists.

- Installer: a successful `install`, `repair`, `update --execute` or `update --rollback` without `--allow-link` reads
  the rule (`netsh ... show rule ... verbose`, no elevation) and, when no rule program ends with the active payload's
  `<version folder>\runtime\bin\java.exe`, prints `LINK_FIREWALL=STALE`, `LINK_FIREWALL_RULE_PROGRAM=`, a `WARNING=`
  line and `LINK_FIREWALL_FIX=` (`--allow-link`, or `netsh advfirewall firewall set rule name="Synesis Link" new
  program="..."`). No rule, or a rule without a program, prints nothing. With `--allow-link` the rule is replaced as
  before, now also on `update --execute` and `--rollback`.
- `synesis link invite` on Windows reads the same rule for its own `java.home` and prints `LINK_FIREWALL=MISSING` or
  `STALE` and a `NOTE=` with the command, and that the answer link still connects. Nothing is printed when the rules
  cannot be read.
- Paths are compared from the version folder on, and found by their shape, because netsh translates its labels, prints
  in the console code page, and may print 8.3 short names (the tests' temporary root came back as `LIPARA~1`).
- No stable program path: the runtime exists only inside version folders that rollback keeps, and the firewall matches
  the listening process (ADR-0096, "A rule for the stable launcher").

**Tests.** Source semantics only; no real update was run against the real rule (reading it here showed the regular
expression finds the remaster56 path, and a missing rule exits 1). Go: every bootstrap package passes on Windows,
with new tests for German netsh output, a stale rule after `update --execute` and `repair`, a current rule spelled
with a short name, and rollback. Windows `:cli:check repositoryHygieneCheck`: 197 CLI tests, 0 failures, Javadoc
passes; `LinkFirewallRuleTest` (4) and `LinkCommandsTest` (18) included. The Linux gate did not run: the Podman
machine dropped its connection 15 s into both attempts.
# 2026-10-17 — The leader says whether each member is connected (defect 5 below)

**Change.** `LeaderReplication` writes `replication.members` beside the events folder: one line per member the role file
names, `<node> CONNECTED|DISCONNECTED <instant>`. A member is `CONNECTED` from the attach of its session (only sessions
`ReplicationSide` attaches, so a held joiner never counts) until that session ends; a session replaced by a newer one
does not mark it disconnected. A member with no session yet is `DISCONNECTED` since the server started. Writes run on
the notice thread, whole through a temporary file; the file is removed on close and absent with no members.
`synesis coordination status` on a leader prints `REPLICATION_MEMBER=<node> STATE=CONNECTED|DISCONNECTED since=<time>`
per role-file member, sorted, or `STATE=NO_COORDINATION_SERVER` with no file. Additive: existing keys and the member's
`replication.status` are unchanged. Docs: TWO_MACHINE_TESTING, WIRE_FORMAT, the CLI command reference.

**Evidence.** Source semantics proven: `LinkReplicationTest` (connect, end, close removes the file; unreadable lines
skipped, removed members dropped), `CoordinationLogLocationTest` (a real `coordination serve` of a leader, before,
during, after), `ControlPlaneReplicationTest` (connected again after the redial, through the launcher's wiring). Not run:
real two-computer acceptance. Known limit, shared with `replication.status`: a server that crashed leaves its last
record behind.
# 2026-10-17 — Two homes, bigger goal: every module written, none reviewed (stopped, NOT REACHED)

**Run.** Same two computers and build as the entry below, project reset (new identity `177b07d3`, repository
force-pushed, old copies archived and removed from the registry). Goal: six independent modules plus `cli.py` that
depends on them, mandatory review by a non-author, no fixed roles (`README.twopc-big.md`). Joining by one link and an
answer link, accepted 03:08; two `claude:sonnet:medium` agents on each computer from about 03:10; one reviewer-only
agent added on the owner at 03:26 and replaced at 03:38. Stopped by the owner at about 03:52: WorkGroup completion NOT
REACHED, nothing integrated, 33 log entries, the link stayed up throughout. Proven again on real current builds:
one-link admission, concurrent claims from both computers (owner: `slug`+`wrap`, `count`; friend: `roman`,
`csvline`), publication on both sides, a cross-computer review request admitted by its author (`roman`).

**Why it stalled.**

1. Every writer published and then held a `WAIT` for its own review. A producer waiting on its review is offered no
   other work, so with no fixed reviewer nobody was free to review (the README design, by ADR-0089 rules).
2. Review admission needs the author to answer, and the authors were asleep: agents on both computers waited with
   shell `sleep 240`/`sleep 1200`, or through Synesis itself with `run_command python -c "import time;
   time.sleep(590)"` (owner `claude#2`, repeatedly), while the action said `retryAfterMs=5000`. Long sleeps also stretch
   `maxUnchangedPolls=12` into hours. The owner's `claude#2` admitted the `count` review only after its reviewer had
   stopped at 12 unchanged polls of about 20 s.
3. A reviewer whose process exited left its lane `RECOVERY_HELD`; the replacement reviewer reported those snapshots
   (`roman`, `csvline`) as no longer open for review and did not continue the held lanes (see the open task on
   reviews whose author is gone).
4. Agent logs are written only when the provider process exits, so neither person could see a running agent's steps.

# 2026-10-17 — Two homes, one project: four agents completed a WorkGroup across two networks (remaster56)

**Run.** Owner (this PC, leader) and a friend's Windows PC (member) on two home networks, build
`0.1.0-dev.remaster56-20261017`, private GitHub repository as code remote, project `SynesisTest2` (four-agent goal:
`slug.py` and `wrap.py`, two writers and two reviewers, mandatory review, `python -m unittest`). Joining used the one
link: `synesis link invite` on the owner; on the friend, `git clone` then `synesis link join <link> --project`; the
friend's `ANSWER_LINK` pasted on the owner (`link answer`, `LINK_STATE=CONNECTED`); `link accept` on the owner. Then
`synesis agents run` with two `claude:sonnet:medium` agents on each computer, started within about a minute.
Owner: `AGENT_RUN_RESULT=COMPLETED`, 204 s, `AGENT_RESUMES=2`; friend: `COMPLETED`, about 235 s, each agent resumed
once. Both modules integrated on the leader (`cded733`, `f115c98`), 15 tests pass, 21 of 58 log entries name the
friend's node, no disconnect during the run. Real current-build acceptance proven across two networks: one-link
admission, answer-link connection, owner approval, concurrent claims from both computers, lane continuation, review,
integration, run completion on both sides. Not established: which computer wrote or reviewed which module.

**First attempt (`SynesisTest`, abandoned).** An earlier join (02:08) connected and was accepted, and a drop
(`LIVENESS_EXPIRED` after 840 s) redialed by itself within 90 s. It failed for these reasons, recorded as open defects:

1. The member could not fetch from GitHub without a prompt (the clone's sign-in had not been stored). Every provider
   binding on the member was `FAILED` with `CODE_REMOTE_UNAVAILABLE` (`followLeaderIfMember` before the worktree), but
   agents only saw `workspace_not_ready`/`SESSION_NOT_ACTIVE`, and `provider install` only `WORKTREE_BINDING_STATUS=FAILED`.
   Neither the join nor the install checks or names this.
2. The owner's agents started first, wrote both modules, waited for review, exited (a turn ended on a background
   timer), and the run ended `STALLED` with `AGENT_RESUMES=0` although a connected member could still review.
3. A second owner run started fresh agents that claimed both reviewer lanes; each review then waited for author
   admission from authors that had exited (`RECOVERY_HELD`), and that run also ended `STALLED`.
4. The Windows Firewall rule "Synesis Link" named remaster47's `java.exe` after updates installed without
   `--allow-link`, so inbound Link was blocked on the LAN until the owner reinstalled with it.
5. On the leader, `coordination status` prints no `REPLICATION_STATE`; only `replication.log` shows the connection.

The old project was retired: `remove-member` for the friend's node and its Git remote removed; the repository was
force-pushed with the new project's history and its `refs/synesis/*` deleted.

# 2026-10-17 — The one link joined a friend's computer for real (remaster56, this PC and the VM)

**Run.** Build `0.1.0-dev.remaster56-20261017` (`LINK_COMPATIBILITY=6`) on this PC (owner) and the bridged VM (friend).
Disposable project `mp8-one-link`, code remote a bare repository on the VM over SSH (the only remote, so the link named
it). `synesis link invite` printed `INVITE_LINK=synesis://project/SLP1-...` and `CODE_REMOTE_URL=ssh://...` and wrote no
`replication.properties`. On the VM, `git clone` of that URL, then `synesis link join <link> --project ~/mp8`, printed
`PROJECT_FOLDER`, `NEXT=the owner ... accepts` and an `ANSWER_LINK`. `synesis link answer` on the owner connected
(`LINK_STATE=CONNECTED`); `synesis link requests` listed the VM's node; `synesis link accept` wrote `role=leader`,
the member and `code.remote=shared`. The VM then showed `role=member`, `code.remote=origin` (its clone's matching remote)
and `REPLICATION_STATE=CONNECTED`. Real current-build acceptance proven: owner invite, friend locate-by-remote, hold
before accept, accept, follow. Not run here: the browser UI panels (unit-tested only), agents on this project (entry
replication was proven by run 7), two separate networks.

**Found.** The LAN dial from the VM did not get in: the Windows Firewall rule "Synesis Link" still named remaster47's
`java.exe`. An update moves the runtime to a new version folder, and only `install --allow-link` replaces the rule, so an
update without it silently stops this computer from hosting (the answer link still works, since the host then dials
out). Recorded in the friend guide; not fixed.

# 2026-10-16 — One link takes a friend into a project; the owner accepts each join (ADR-0098, "Joining, revised")

**Spec.** The ADR section "Joining, revised (owner, 2026-10-16)" (uncommitted in the main checkout; not edited here).

**Link.** `synesis://project/SLP1-...` (`ProjectInvitation`, link module): compatibility number, project UUID and creation
time, the inviter's code remote URL (never credentials: `publicUrl` strips `user:password@` and the web user; a parser
refuses a URL that carries them, starts with `-`, or is a Git remote helper like `ext::`), the unchanged `SLB3` payload,
and a second Ed25519 signature by the inviter's node key over a domain tag plus everything before it. The SLB3 signature
covers only its own fields, and widening it would have changed what the joiner hands to Link, so the project link signs
the whole payload instead: changing the identity or URL, or moving a signed SLB3 onto another project, fails
`verifyAt`. `LinkCompatibility.CURRENT` 5 to 6; the pinned `SLB3`/`SLA3`/envelope vectors were regenerated (SLB3 now
uses a fixed key of its own, `INVITER_ID` in `ProtocolWireCompatibilityTest`; SLA3 only changes its first two bytes) and
SLP1 vectors added. Docs: WIRE_FORMAT, SYNESIS_LINK_V1, THREAT_MODEL, control-plane-v1, TWO_MACHINE_TESTING.

**Owner.** `invite` with `project: true` (UI **Create Invitation**, `synesis link invite`) writes nothing: `ProjectSharing.invitationRemote`
reads the explicit `code.remote` or the only Git remote, and `LinkRuntimeOwner.projectInvitationLink` signs the link. A
runtime with no role file is an unshared leader (`LeaderReplication.serving`, `ReplicationSide`), so no restart is needed;
`noticeAll` skips its head count while no member is attached. Only the first accept writes the leader role (see review fixes).

**Friend.** `open-project-link {link, folder?}` (UI: paste box on the Projects page and Network view; the daemon opens the
UI with `&projectLink=` for a clicked `synesis://project/` link; `synesis link join <link>` with or without `--project`):
registry lookup by identity (several copies: the one this runtime serves, else the first by path); else `PROJECT_NOT_FOUND`
with the code remote URL (no clone); a chosen folder must carry the same identity (`PROJECT_IDENTITY_MISMATCH`), a clone
holding only `project.json` gets `initReplica`, a folder with no `.synesis` is accepted only if a remote has the link's URL.
The folder's own runtime (reached with its runtime command token if it is not the serving one) then runs
`ProjectSharing.follow` (member role, code remote = matching remote or a new `synesis` one, empty-log check, peers
retained to the leader) and switches its `ReplicationSide` to a member's live, then joins the SLB3 inside.

**Approval.** `LinkRuntimeOwner(... , MemberAdmission)`: a node that authenticates through a hosted invitation and is not
in `returningPeers` is held (session, identity checked, nothing served) and listed in `snapshot.onboarding.joinRequests`;
`accept-join` writes the member list, attaches the live session and records `hosted` in `replication.peers`;
`decline-join` closes it. A request ends with its invitation; a session that ended first leaves the request, and a later
accept makes the node a member who is served when it redials. Requests are not persisted: a restart drops them.
CLI: `synesis link requests|accept <node>|decline <node>`.

**Tests (all in the Linux gate).** `ProjectInvitationTest` (9) and the SLP1 wire vectors; `ProjectSharingTest` (16);
`LinkRuntimeOwnerJoinRequestsTest` (5); `ProjectLinkFlowTest` (6: invite, open from another project's runtime, held before
accept, member proposal lands after accept, decline, unknown project then wrong folder then clone, tampered link, own log
and ALREADY_A_MEMBER, restart drops requests); `LinkCommandsTest` (+7); web-ui App tests (+6). Without the hold the join
request and flow tests fail (4 of 5 and 3 of 6); without the live switch to member the end-to-end flow fails.
Changed on purpose: `link invite` sends `project: true` and prints `CODE_REMOTE_URL`; the two compatibility vectors;
`HolePunchTest` uses an `SLA3-AAY` stub for the new number.

**Review fixes (2026-10-17, same uncommitted change).**
- Creating an invitation no longer writes a role file (it could make `CodeRemote.of` throw `CODE_REMOTE_NOT_CONFIGURED` for
  the owner's own completions and publications). The response and CLI show `codeRemoteName`, `codeRemoteUrl` or
  `codeRemoteReason`. `accept-join` writes the leader role, the member and `code.remote` (the remote whose URL the link
  that brought the node carried, remembered by `LinkRuntimeOwner` per connection invitation); no URL means
  `409 CODE_REMOTE_NOT_CONFIGURED` and the request stays. `MemberAdmission.admit` now takes the URL.
- `invite` on a member makes the plain connection invitation again (the documented member-hosts topology).
- `ProjectSharing.follow` and `admit` run under `ProjectAppendLock`, which also fences agents' appends, so the empty-log
  check, the role file and the live switch (`LiveSwitch`) are one step; a failed switch restores the file and removes the
  remote this call added. The CLI scripts `project share|follow|add-member` still write the role file without that lock.
- `open-project-link` prepares the connection first (`startJoin(link, BeforeConnect)`), so capacity or transport refusals
  write nothing; a failure after the live switch cannot un-switch the running side (restart or a new open reconciles).
- Code remote URLs are `https://`, `ssh://` or `user@host:path` only (`ProjectInvitation.isFetchableUrl`), when making
  and when parsing a link; the owner's invitation otherwise carries none and says why.
- One directory: the control plane uses `ProjectLinkGateway.coordinationDirectory()` (the `--data` directory) for the role
  file; the launcher's admission uses it too.
- New `describe-project-link` command; the Join panel shows inviter, project, code remote URL and expiry before Join.
- A clicked `synesis://project/` link on an installation with no project already opened the UI on the home workspace
  (`knownProjectRuntime`); `DaemonServerRoutingTest` now pins it (finding 7 was not reproducible).
- Tests that failed before: lock tests in `ProjectSharingTest` (lock removed: 2 fail), capacity test in
  `ProjectLinkFlowTest` (old order: fails); API-shape tests fail to compile against the old signatures.

**Not done.** Cloning (or receiving over Link) the repository for the friend; a way to leave a leader after a declined
join (the friend's computer stays a member of it and redials a refused host every 30 s at most); the real two-computer run.

# 2026-10-16 — Drop and restart tested on two real computers

Windows leader (dials out) and Linux VM member (hosts), project `mp7-two-pc`, after the run 7 WorkGroup completed.
VERIFIED from both computers' `replication.log` and `coordination status`:

- **Drop** (build remaster54): the VM's virtual cable was unplugged for 60 s.
  - Both sides logged `DISCONNECT reason=LIVENESS_EXPIRED` 31 s in.
  - The leader's first redial failed while unplugged.
  - It reconnected 6 s after the cable was back (`REDIAL_OK attempts=3`).
  - The member showed `REPLICATION_LAST_DISCONNECT=LIVENESS_EXPIRED ...` and returned to `CONNECTED`.
- **Restart, before 1091dca8:** after restarting the leader's runtime nothing reconnected. Neither side kept the other.
- **Restart, after 1091dca8** (build remaster55, a fresh `link invite`/`link join` so the peers were recorded):
  - Leader runtime restarted: `DIAL_RESUMED`, then `CONNECTED` and `REDIAL_OK attempts=1` within about a second. The
    member closed the stale session as `replaced=true`.
  - Member runtime restarted: `HOST_RESUMED port=58885 peers=1`. The leader saw the old session expire after 30 s and
    redialed (`REDIAL_OK attempts=1`). The replay guard held 3 handshakes.

Not tested: a restart of a whole computer (the address may change), and two networks.
# 2026-10-16 — The retained host's replay guard survives a restart (closes the residual risk below)

**Gap.** The host's `ReplayGuard` was in memory while the hosting UDP port and the returning-peer admission survived a
restart, so a returning handshake recorded before the restart could be replayed once against the restarted host.

**Change.** `replication.replay` (new, `ReplicationReplay`, beside `replication.peers`; local, never replicated): one
transcript fingerprint per line, at most 8,192, strict format, atomic whole-file replace. `ReplayGuard` takes an
optional `Store`: a newly accepted fingerprint is saved before `accept` returns (the session exists only after
`SessionAuthenticator.establish` returned), and a failed save refuses the handshake. `Onboarding.useRetainedReplayGuard`
gives every retained host endpoint the same guard; `CoordinationServerLauncher.linkOwner` loads the file before any
endpoint listens. Missing file with a hosting record, or an unreadable one: no hosting resumes, the hosting records are
dropped, `REPLAY_GUARD_UNREADABLE` is journaled. One-shot `host/join` and non-retained paths are unchanged (fresh guard).
Capacity is per project now, not per endpoint.

**Proof (Linux gate).** `ReplayGuardTest` (4: a real handshake accepted, guard rebuilt from what the store saved, the
same transcript refused; save-before-return; failed save refuses; capacity across reload), `ReplicationReplayTest` (4),
three new `ControlPlaneReplicationTest` cases (file keeps and extends across a host restart; malformed file; missing
file). With the load disabled the three cli cases fail (3 of 13).

**Limits.** Power loss before the disk flush can lose the last fingerprint; an attacker who can write the coordination
folder is outside the model. Not re-run on two real computers; Windows gate not run.

# 2026-10-16 — A project session survives a restart of either coordination server (ADR-0098 step 2)

**Real evidence that motivated it** (two computers on one LAN; leader = Windows, which only dials out because it has no
inbound firewall rule; member = a Linux VM, which hosted the invitation). Drop test PASSED: the VM's virtual cable
unplugged for 60 s, both sides journaled `DISCONNECT reason=LIVENESS_EXPIRED` at +31 s, the leader's redial failed while
unplugged and reconnected 6 s after the replug (`REDIAL_OK attempts=3`), the member went back to CONNECTED. Restart test
FAILED as c16f19bd documented: stopping and restarting the LEADER's project runtime reconnected nothing (no new leader journal
line; the member stayed DISCONNECTED after `LIVENESS_EXPIRED`), because nothing was persisted.

**Change.** `replication.peers` (new, `ReplicationPeers`, beside `replication.properties`; local, excluded from version
control, never replicated; strict line format, atomic write): `dial=<host node ID> <invitation link>` for a host this
computer dialed, `host=<port> <node ID>` for each node admitted through an invitation it hosted. `LinkRuntimeOwner` writes
them through a `PeerMemory` (only for peers its returning rule names) and has `resumeDialing(link)` and `resumeHosting(port,
peers)`; `CoordinationServerLauncher.linkOwner` reads the file at start and resumes both. A dialer restores its `PreparedJoin`
from the link (`Onboarding.resumeRetainedJoin`: signature checked as of the issue time, handle counts as connected) and the
existing `Redialer` starts at once with the same backoff. A host rebinds the SAME UDP port (`Onboarding.resumeRetainedHost`,
`OnboardingEndpoint` takes a port) with `InvitationAdmission.markConsumed()` and a random, unshared capability, and admits
only nodes both recorded for that port and still named by the role. `remove-member` drops a node's records, `follow` keeps
only the leader's. Journal lines: `DIAL_RESUMED`, `HOST_RESUMED`, `DIAL_RESUME_SKIPPED`, `HOST_RESUME_SKIPPED`,
`HOST_RESUME_FAILED`, `PEERS_IGNORED`, `PEERS_NOT_SAVED`. Docs: THREAT_MODEL ("Across a restart"), SYNESIS_LINK_V1
("Returning peers"), TWO_MACHINE_TESTING.

**Rule change.** "Only after the invitation was used" is now "only after an invitation was used, now or before a restart", and
a restarted host is stricter than a first-run one: recorded nodes only. No wire frame changes.

**Proof (Linux gate, Podman, Sonnet-5.5 work).** Six new `ControlPlaneReplicationTest` cases fail without the resume step
(6 of 10 failed with the read of the file disabled) and pass with it: a restarted leader (the joiner) redials without an
invitation and a member proposal is accepted; a restarted member (the host) rebinds its port and the leader's redial
reconnects; a node the role names but the host never admitted is refused by the restarted host; a member removed while the
leader was down is neither redialed nor admitted; a malformed peers file resumes nothing and the server starts; a taken port
is logged and the server starts. Plus `ReplicationPeersTest` (6), two `ReturningPeerHandshakeTest` cases, and
`ProjectRoleCommandsTest` assertions for `remove-member` and `follow`. Full `check repositoryHygieneCheck --continue
--no-build-cache` on Linux: BUILD SUCCESSFUL (link 184, coordination 312, project-record 60, relay 12, mcp-contract 20,
workspace 771 with 2 skipped, mcp 190, cli 170; 0 failures).

**NOT REACHED / limits.** Not re-run on the two real computers; the Windows gate was not run. A host whose port was taken
hosts nothing (`HOST_RESUME_FAILED`; only a new invitation reconnects), and a dialer whose host changed address cannot reach
the invitation's old endpoints. The replay-guard residual risk recorded here is closed by the entry above.

# 2026-10-16 — Two computers, one project: the WorkGroup COMPLETED (run 7, ADR-0098 step 2 done)

Same setup as run 6 (two Claude Code agents, Sonnet 5.5 medium, on the Windows leader; two Codex agents, GPT-6
Luna high, on the Linux member; README with two writers and two reviewers, mandatory review, `python -m unittest`
validation), on build remaster54 with a34d0188 (the validation command is not a part to claim).

**Shown (VERIFIED from both logs and Git):**

| Module | Written on | Reviewed on | Merged by the leader |
| --- | --- | --- | --- |
| `slug.py` | member (Codex) | leader (Claude), REVIEW request ACCEPTED | `e2fda35` |
| `wrap.py` | leader (Claude) | member (Codex), REVIEW request ACCEPTED | `460c35d`, review note `272eb04` |

- WorkGroup `STATUS=COMPLETED`; both computers' `synesis agents run` returned `AGENT_RUN_RESULT=COMPLETED` (the
  member's after 182 s).
- No extra lane was opened.
- Both logs held the same 50 entries, and the session stayed connected throughout.
- The leader's head and the shared remote's `master` are `272eb04`. `python -m unittest` on the leader: 13 tests, OK.
- The member's own checkout stayed on `e2fda35`: it fast-forwards only before giving out a new lane, and none was
  needed after the work ended.

This meets step 2's "done when" in ADR-0098: an agent on one computer and an agent on another claim, review and
integrate one WorkGroup, with no two agents ever holding the same file.

**NOT REACHED:** two networks (both computers are on one LAN); a real Link drop and redial during a run; a
coordination-server restart mid-run (it would still need a new invitation).
# 2026-10-16 — The project's validation command is not a part to claim (run 6 follow-up)

Run 6 (`mp6-two-pc`, `python -m unittest` in `.synesis/project.json` `validation`, mandatory review) never completed its
WorkGroup. A Codex agent whose lane had ended started a fresh session and said: "The README also names a whole
repository acceptance check (`python -m unittest`), and neither active lane claims that check, so I'm taking that
unclaimed integration verification". It announced a producer lane for `integration_report.md`, which waited for a
reviewer nobody was left to be. The check was never unclaimed: `IntegrationOrchestrationService` runs the project's
validation command on every integration.

Guidance only, no authority change. `AgentNextActionService.claimRequiredResponse` (the lane-less caller's instruction)
now ends with "Synesis runs this project's validation command (`<argv, space-joined>`) on every integration, so running
or reporting it is not a part to claim." when `ProjectLocation.validation()` is set; without one the instruction is
byte-identical. The managed `AGENTS.md` section and the provider manual carry the same rule in their "a lane exists to
produce something the goal names" paragraph. Installed manuals and sections written earlier read as outdated until
`synesis provider install` or `init` refreshes them. NOT REACHED: a second real run showing the agent no longer
announces that lane.

# 2026-10-16 — Two computers, one project: the goal integrated end to end (run 6)

ADR-0098 step 2, after the review-guidance and session fixes (5c10bb54, 8b5f01e9). Build remaster53 on the Windows
leader, the same tree on the Linux member (a VM on the LAN). Project `mp6-two-pc`: `slug.py` and `wrap.py`,
mandatory review, `python -m unittest` validation; README: two writers, two reviewers. Two Claude Code agents (Sonnet
5.5, medium) on the leader, two Codex agents (GPT-6 Luna, high) on the member, the same prompt for all four.

**Shown (VERIFIED from both logs and Git):**

| Module | Written on | Reviewed on | Merged by the leader |
| --- | --- | --- | --- |
| `slug.py` | leader (Claude) | member (Codex), REVIEW request ACCEPTED | `0735e02` |
| `wrap.py` | member (Codex) | leader (Claude), REVIEW request ACCEPTED | `9df5414` |

The member reviewer's `review_slug.md` was merged too (`50b59a0`). The leader's head, the shared remote's `master` and
the member's own checkout all ended at `9df5414`. `python -m unittest` on the leader: 12 tests, OK. The Link session
stayed connected for the whole run (about 9 minutes of work, 51 entries, both logs identical).

**NOT REACHED: the WorkGroup did not reach COMPLETED.** After its review, the member's reviewer started a fresh
session and announced an extra lane the goal does not name (`integration_report.md`); under mandatory review it
waited for a reviewer nobody was left to be, and the member's run ended STALLED. The goal's acceptance criteria were
all met; the extra lane is agent behaviour that the provider manual already discourages (a lane exists to produce
something the goal names). Not reached either: two networks, a real drop and redial during a run, the Windows gate
before this entry.
# 2026-10-16 — A finished producer on another computer can now start a reviewer lane (real run 5)

Real run 5 of ADR-0098 step 2: leader A (Windows), member B (Linux VM). B's Codex #1 wrote `slug.py` in a producer lane;
the leader integrated it. Codex #1 was then offered the open `wrap.py` review and called `ensure_session` with a reviewer
task. Both calls (and the one after `ensure_session {refresh:true}`) were refused with `blocked / workspace_generation_changed
/ retry`; `get_next_action` kept answering `coordination_intent_required`. No reviewer ever started, `wrap.py` stayed
`REVIEW_PENDING`, the run stalled.

**Root cause (the first hypothesis, a stale workspace generation, was wrong).** The refusal was not a generation check.
`WorkIntentAnnouncement.announce` throws `SESSION_EPOCH_FENCED` for a participant that is not `ACTIVE`, and
`McpSessionEstablishment.announceClaims` (`SESSION_EPOCH_FENCED` branch) reports that as `workspace_generation_changed`.
The leader's integration released Codex #1's lane in the replicated log, so its participant became `COMPLETED`. On one
computer the same integration then completes the local binding file (`completeBySessionId`), and the next `ensure` makes
a new session. On B only the leader's binding files were touched; B's binding stayed `BOUND` with the same session id, so
`ensure` resumed it (even with `refresh`) and every announce hit the completed participant. Single-PC is not affected by
this path, but a session whose binding was left `BOUND` after its lane was released any other way would be.

**Changed.** `ProviderSessionBindingService.ensure` now starts a fresh session (new binding on the current control head)
when the durable log says the participant's lane has ended: `DETACHED` (as before) or `COMPLETED`
(`ProviderSessionCoordinationFacts.laneEnded`, replaces `cleanlyDetached`). A participant is `COMPLETED` only when it owns
no intent, and an active managed attachment still blocks the rebind. A caller with an open lane is unchanged; the stale
workspace refusal (`WORKSPACE_STALE_DIRTY`, generation check) is not weakened.

**Left as is.** The `SESSION_EPOCH_FENCED` to `workspace_generation_changed` mapping is still misleading for a revoked or
cancelled participant; it is no longer reached by a finished producer.

**Evidence.** `ProviderSessionBindingServiceTest.sessionWhoseLaneWasReleasedElsewhereStartsAFreshSessionButAnOpenLaneKeepsItsSession`
failed before the change (same session id after the lane was released) and passes after. No real run of the fix yet.

# 2026-10-16 — A new agent is now told it may review a snapshot awaiting review (real run 4 follow-up)

Real run 4 of ADR-0098 step 2 (see the entry below) left `wrap.py` `REVIEW_PENDING` under mandatory review: a fresh agent
started after the dedicated reviewer's lane ended claimed an already-integrated part, cancelled, and said it "wasn't
assigned to review" `wrap.py`. The design already lets any eligible caller become the reviewer (ADR-0046, ADR-0089):
every caller's collaboration view lists `reviewCandidates`, each `AVAILABLE` with a ready `reviewerIntent`. The agent
was never pointed at them: its `COORDINATION_INTENT_REQUIRED` instruction said only to "choose a part that no listed
participant claims", and the bare `ensure_session` result carried no candidates, so an agent that announced right after
`ensure_session` never saw them. Once it held a producer lane it could not become a reviewer.

**Changed (guidance and payload only; no authority rule).**
- `get_next_action`, claim-required answer: when the lane-less caller has open review candidates it did not produce,
  the instruction offers reviewing one as an equal alternative to claiming an unclaimed part and names
  `reviewCandidates[i].reviewerIntent`. With none, the instruction is unchanged.
- `ensure_session`: a session that holds no lane gets `result.reviewCandidates` (additive; `snapshotId`, `files`,
  `reviewerIntent`) when there are open candidates. A session that holds a lane gets none.
- Independence (`REVIEW_SELF_*`) and `REVIEW_CANDIDATE_ALREADY_ASSIGNED` still decide at admission.

**Not reached.** No real run of this yet; source and Linux-gate tests only. The exclusion of a candidate whose snapshot
the caller authored before handing its lane on (continuation) is in the code but has no test of its own.

# 2026-10-16 — Two computers, one project: four real runs of ADR-0098 step 2 (PARTIAL)

A = the owner's Windows computer, leader, real Claude Code (Sonnet 5.5, medium); it only dials out (no inbound
firewall rule). B = a Linux VM on the same LAN, member, real Codex (GPT-6 Luna, high), hosting the invitation. Code
remote: a bare repository on B, reached from A over SSH with a repository-local `core.sshCommand`. Each run was a fresh
disposable project, joined with `project share` / `follow` / `add-member` and `link invite` / `link join`. Goal:
`slug.py` and `wrap.py`, mandatory review, `python -m unittest` as validation. Details and timings:
`D:\SynesisAcceptance\overnight-2026-10-16\TRAIL.md`.

**Shown (VERIFIED from both logs and Git):**

- Claims from both computers land in one log; the two logs stay identical entry for entry.
- A member's lane goes through the shared remote: B pushed `refs/synesis/{prepared,snapshots}` before proposing, A
  fetched them.
- Review across computers: in run 4 a Codex reviewer on B reviewed Claude's `slug.py` from A (REVIEW request
  ACCEPTED), and A merged it (`8b58d46`).
- A merged a member's lane with no agent of its own acting: the reviewer's lane written on B (`review_slug.md`) was
  merged by A (`ff1aa6e`) and A pushed the control branch (the remote's `master` = `ff1aa6e`). `python -m unittest` on
  A: 6 tests OK.
- The session held for 25+ minutes once the stream fix was in (runs 3 and 4); a member's controller waited for the
  other computer's lane instead of ending (runs 2-4).

**Defects the runs found, fixed the same night:** a controller ended when its own agents stopped although another
computer's agent still held a lane (7e3d4bf8); the session could not come back after a drop and nothing recorded why
(c16f19bd, which also found a hosting endpoint that accepted only one QUIC connection ever); every exchange leaked one
of a connection's 64 QUIC streams, which stopped replication 12-16 minutes in, in runs 1 and 2 (e16638a1).

**NOT REACHED.** The WorkGroup did not complete: `wrap.py` (written on B, 6 tests) stayed `REVIEW_PENDING`. Two causes,
neither specific to two computers:
- Under mandatory review a producer's lane stays open until reviewed, and an agent holding a producer lane cannot take
  a reviewer lane (`SCOPE_EXTENSION_ROLE_CHANGE_REQUIRES_LIFECYCLE`), so two producers reviewing each other deadlock
  (run 3; a test-design error, fixed in run 4 by a dedicated reviewer).
- A reviewer's lane ends after the one candidate it took, a finished lane is not resumed, and a later agent was not
  offered the open review ("I wasn't assigned to review it"), so the second module had nobody to review it (run 4).
Also not reached: B fast-forwarding onto A's merged head (no new lane was allocated on B after the merge); redial after
a real drop (no drop happened after the stream fix); two networks; the Windows gate on these commits.
# 2026-10-16 — A Link connection ran out of streams after 64 application exchanges (ADR-0098 step 2, slice 6)

Found in the same two-computer run as the redial work, and again in a second run: 12 to 16 minutes after connecting, the
member's pulls stopped being answered (member marked the leader silent, `REPLICATION_STATE=DISCONNECTED`) while neither
side's `replication.log` recorded a session end. The session was live (the control stream and its heartbeats were fine);
only application requests hung. 64 pulls at 15 s is 16 minutes, and `NettyQuicTransport` gives each connection
`initialMaxStreamsBidirectional(64)`.

**Reproduction (before the fix).** `NettyQuicLoopbackTest.establishesIdentityBoundSessionOnLocalQuicControlStream` now
makes 200 sequential application requests in each direction over one real loopback QUIC session (it used 8). On the old
code request 60 of the client-initiated loop failed with `QuicTransportError{code=4, name='STREAM_LIMIT_ERROR'}`
(`QUICHE_ERR_STREAM_LIMIT`): the control stream, one demo-work, one small and one 300 kB application exchange had used
4 of the client's 64 bidirectional streams, and 60 more used the rest. Nothing was returned. After the fix all 400 requests
are answered.

**Root cause.** `StreamExchange` (`link/.../transport/control`) wrote the request and never ended the client's half of
the stream (no FIN), then closed the channel once the reply was read. QUIC gives a stream back to the opener's limit only
when both halves have ended; the abandoned request half kept every exchange counted for the life of the connection. The
same code serves application and demo-work streams, and the leader-to-member NOTICE direction (the peer's stream limit is
separate, so it hits at the same count on whichever side opens streams).

**Fix.** The client calls `SHUTDOWN_OUTPUT` after the request write, and both sides set `ALLOW_HALF_CLOSURE` on the
exchange stream (Netty otherwise closes the server's channel when the FIN arrives, before it replies). The limit stays 64.
Wire format and published API are unchanged; a peer that does not send the FIN (older build) still works because the
server replies and closes as before.

**Defence in depth, not added.** Ending a session after repeated request timeouts is not reachable from the evidence
once the leak is gone: a stream slot (`MAX_STREAMS`, 4 active) is released on every path through `channelInactive`
(reply, 5 s timeout, error), so timeouts no longer accumulate, and a hung peer handler on a live control stream is
already answered by the 5 s per-request timeout and the replication layer's silent-leader state. A rule that closes a
session on N timeouts would also end sessions over a slow handler that liveness correctly considers alive. Not tested:
a long run of handler timeouts (64 of them is over five minutes).

Tests: link 182, coordination 306, project-record 60, relay 12, mcp-contract 20, workspace 767 (2 skipped), mcp 189, cli 164,
all passing on the Linux gate with `repositoryHygieneCheck`.

# 2026-10-16 — A dropped project session comes back by itself, and the reason it ended is written down (ADR-0098 step 2, slice 5)

Found in the first real two-computer run today. Leader A was Windows with no inbound firewall rule (it can only dial
out); member B was a Linux VM. B hosted the invitation (`link invite --expect A`), A joined, both reported CONNECTED
and replicated entries. About fifteen minutes later B's replication state became DISCONNECTED while both coordination
servers still ran, and B's agents' proposals got `LEADER_OUTCOME_UNKNOWN`. Nothing logged why. Recovery needed a person
to create a new invitation on B and join it from A.

**Diagnosis (what the code showed, not a reproduction of the fifteen minutes).** Three things in the code explain a session
that ended and stayed ended: (1) retained sessions ran under `LivenessConfiguration.DEFAULT` (heartbeat 1 s, `EXPIRED`
after 5 s of silence), which a stalled process or a busy event loop crosses; (2) nothing dialed again, and the host
could not have accepted a second connection anyway: its QUIC server codec was given a plain `ChannelInboundHandlerAdapter`
as connection handler, which Netty refuses to add to a second connection, so a host accepted one connection and dropped
the rest (found when the first redial test timed out); (3) when a leader holds the session open but stops answering,
each proposal waited 8 s per send, three sends, with the agent's append lock held, and ended `LEADER_OUTCOME_UNKNOWN`.
The actual cause of the drop at minute fifteen is still not known: nothing was recorded. It is now.

**Recording.** `LinkRuntimeOwner` writes one line to a journal for each session start and end, with the end's
`SessionCloseReason`, the liveness state it ended in, how long it lasted, and whether a newer session had replaced it,
and for a redial's first failure and its outcome. `ReplicationJournal` (coordination, `replication.log` beside
`replication.status`) keeps the last 100 lines, rewritten whole through a temporary file. `synesis coordination status`
prints `REPLICATION_LAST_DISCONNECT=<reason> <time> <details>` for both roles.

**Timing.** Sessions retained by the owner (host `createRetainedInvitation`, join `importRetainedInvitation`) run under
`LivenessConfiguration.RETAINED`: heartbeat 5 s (inside the 15 s a home router's UDP mapping needs, RFC 9308 allows 30 s),
`SUSPECT` 15 s, `EXPIRED` 30 s (six missed heartbeats; it tolerates a pause of half a minute and is still well under
the 2 min a member is suspected), and an explicit QUIC idle timeout of 60 s (twice the expiry, so liveness decides
first and the timeout only reclaims a connection whose control stream already ended; before, an ended session's QUIC
connection stayed open forever). One-shot `synesis host`/`join` and the onboarding handshake keep `DEFAULT` and no idle
timeout. `LivenessConfiguration`'s invariants are unchanged.

**Redial without an invitation.** Admission is a narrow second mode in `InvitationAdmission` (Link): once the invitation
is consumed, a handshake with an empty invitation capability is admitted if the owner's rule names the claimed node,
and again after both proofs verified. The rule is the replication role: a member names its configured leader; a leader
names its current members (`LeaderReplication.isMember`, read from `replication.properties` per call, fail closed). A
host with no rule, or an unused invitation, admits nobody without the capability. Replay protection is the existing
transcript fingerprint in the host's `ReplayGuard`, sized 8,192 for a retained host because each redial adds one; the
dialer picks a fresh session ID and nonces per attempt. Both identities are authenticated as before. The invitation
path is untouched. The dialer is fixed to the original joiner (the firewalled leader in this run), unlike the
lexicographic rule of ADR-0076's `OverlayReconciler`, because only it can reach the other. The joiner's `Redialer`
(workspace) starts when its session ends, waits 1 s and doubles to 30 s forever while the rule still names the host and
no session to it is held, and stops when it does not; a success ends the loop and the new session starts the next.
`PreparedJoin.redial()` races the invitation's endpoints (the one the last session was made over first) from the same
UDP socket. The host's listening socket stays bound for the life of its handle, so the port the redial targets is the
one in the invitation (verified by the redial tests: the redial reaches the invitation's endpoint). A returning session
is handed to the owner on another thread and closed if the owner rejects it.

**Proposals while silent.** `MemberReplication` marks the leader silent when a request over a live-looking session gets
no answer, asks for a pull at once, and answers later proposals `Unreachable` immediately (so the agent sees
`LEADER_UNREACHABLE`, not three 8 s waits) until any answer arrives or a new session is attached. Pulls still go out.

**Limits.** Neither side remembers the other across a restart of its coordination server: a restart still needs a new
`link invite` and `link join` (persisting the endpoints is cheap, but a dialer that restarted would redial a host whose
port changed). A host behind a NAT that only the joiner can cross is not helped: the joiner's outbound dial opens its
own mapping, the host's side needs the port reachable, as for the first connection. A graceful `GOODBYE` from the host
(its server shut down) is redialed too; a joiner dials a dead host every 30 s until the rule stops naming it. Only the
joiner redials: a host whose joiner went away waits. The cause of the minute-fifteen drop is unproven.

Tests: `ReturningPeerHandshakeTest` (link; a named node is admitted again; an unnamed node, a known ID signed by another
key, a replayed handshake, an unused invitation, a host with no rule, and a removed node are refused),
`RetainedOnboardingTest` (retained timing and idle, the one-shot path unchanged, the joiner's `redial`, a refusing
host), `RedialerTest` (backoff doubles to the ceiling and stays; stops when unwanted; one loop per request;
close), `LinkRuntimeOwnerRedialTest` (two owners over real QUIC on loopback: a closed session returns without an
invitation, the journal names the reason on both sides; no redial once the rule says no; the used invitation admits
no stranger), `ControlPlaneReplicationTest` (the real shape: member hosts, leader joins, the session is closed, the
leader redials, and the member's next proposal is accepted; the journal and `lastDisconnect`), `ReplicationJournalTest`,
`SilentLeaderTest`. Without the admission mode, the silent flag, or the retained liveness, the tests above fail (checked
by turning each off in turn).

Published contracts: no wire field or code added. A new public `Onboarding.createRetainedInvitation`,
`importRetainedInvitation`, `PreparedJoin.redial`, `InvitationAdmission.admitReturning`/`admitsReturning`/`returned`,
`LivenessConfiguration.RETAINED`, `PeerSession.livenessConfiguration`, `NettyQuicTransport` overloads with an idle
timeout and `sharedConnectionHandler`, `LinkRuntimeOwner` constructor with a rule and a journal; the old signatures stay.
`CoordinationServerLauncher.linkOwner` (package-private) took a journal argument.

# 2026-10-16 — `synesis agents run` no longer ends while another computer's agent still holds a lane (ADR-0098 step 2)

Found in a real two-computer run today: computer B's only agent (Codex) finished its lane, waited for a review that
only the agent on computer A could give, and stopped. B's `agents run` then returned STALLED after 394 s while A's
agent was still working, so nobody on B was left to review A's work, and A's controller would have done the same
when its agent stopped to wait for B. The same held on one computer whenever a lane belonged to a participant the
controller did not start (an interactive agent in another terminal).

**Rule.** When every agent this controller started has stopped and the run would otherwise end STALLED,
`Observation.foreignActiveLaneOpen(managed)` is asked: is an open lane held by a participant whose replicated state is
ACTIVE and who is not behind one of the controller's own connections? If so the controller keeps polling. Its agents
are resumed by the existing path (progress after they stopped, for an agent whose own lane is still open); otherwise
the run ends TIMED_OUT at `--timeout-minutes`. Suspended, recovery-held, revoked, cancelled, completed and detached
holders do not count. NEEDS_OPERATOR, RESUME_LIMIT_REACHED, NO_WORK_ANNOUNCED and a complete project end the run as
before; `foreignActiveLaneOpen` defaults to false, so other observations are unchanged. Limit: liveness is the
participant state in the log, not a process check, so a crashed foreign agent whose participant is still ACTIVE keeps
the run waiting until the deadline.

Tests: `AgentRunControllerTest` (keeps polling and times out; resumed when the foreign lane progresses; STALLED as
before with none) and `ConversationServiceTest` (the observer counts an active foreign lane and ignores suspended
holders).

# 2026-10-16 — Two computers, one project: roles, members, and the replication on control-plane sessions (ADR-0098 step 2, slice 4)

Until now nothing in the product gave a computer a role or connected replication to a Link session. A person can now
turn a project into a leader on one computer and a member on another and connect them; the sequence is in
`docs/operations/TWO_MACHINE_TESTING.md`, "Two computers, one project". Not yet run on two real computers.

**Replication frames reach the sessions the control plane establishes.** `LinkRuntimeOwner` gained a constructor
taking an application-stream delegate and a session listener (the old constructor is unchanged, so an owner without
a delegate still refuses every project frame: "no project application handler is installed"). The delegate answers the
frames that follow the project-context exchange, so a peer of another project never reaches it; the listener runs at
the end of `acceptSession`. `CoordinationServerLauncher.linkOwner` wires them from the role in
`replication.properties`: a member passes `MemberReplication.handler()` and attaches the session whose peer is its
leader; a leader passes `LeaderReplication.handler()` and attaches the session of a current member. A peer that is
neither is admitted as before and gets nothing: the replication side refuses it. The replication objects are created
before the owner and closed first (listed last in the launcher's `try`).

**The leader's member list is read per request.** `LeaderEndpoint` and `LeaderReplication` take a
`Supplier<Set<String>>` (the old `Set` constructors remain); `LeaderReplication.open` reads `replication.properties`
each time, and a file that is gone, unreadable, or now a member's names nobody (fail closed). So `add-member` and
`remove-member` take effect on the next proposal or pull with no restart. A member added after its session was admitted
is served at once but is told of new entries only by its 15 second pull, because the leader attaches sessions when they
are admitted.

**Commands.** `synesis project share --code-remote <remote>`, `add-member <nodeId>`, `remove-member <nodeId>`,
`follow --leader <nodeId> --code-remote <remote>` (cli, `command/project`; shared base `ProjectRoleCommand`), printing
`KEY=VALUE` and refusing with `ERROR=<code>` (exit 10, nothing written): `ALREADY_A_MEMBER`, `NOT_A_LEADER`,
`CODE_REMOTE_UNKNOWN` (the clone has no such remote: checked with `git remote get-url`, so a typo surfaces here and not
at the first push), `INVALID_NODE_ID` (`NodeIdentity.isNodeId`; `ReplicationSettings` itself keeps its looser check
because existing tests use short names), `MEMBER_IS_THIS_COMPUTER`, `LEADER_IS_THIS_COMPUTER`, `NOT_A_MEMBER`,
`FOLLOWING_ANOTHER_LEADER`, `ALREADY_A_LEADER`, `LOG_NOT_EMPTY`. The follow rule: a member's log is a copy of the
leader's, so a computer with entries of its own would never merge (they are not on the leader's log) and would stop at
`LEADER_LOG_DIVERGED` at the first pull; `follow` refuses it up front unless this computer already follows the same
leader (changing the remote). The leader is the owner in step 2, so `remove-member` is leader-only.
`share` and `follow` stop the project's runtime when the installation daemon holds one (`RUNTIME_STOPPED=true`),
because the server reads the role once at start; it starts again with the new role on next use. `add-member` and
`remove-member` do not. A runtime started by `synesis ui` belongs to that process and is not stopped.

**`synesis link invite [--expect <nodeId>] | join <uri> | answer <operation> <uri>`** (cli, `command/link`) run the
operation on the project's running runtime, the process that owns the Link sessions, print `OPERATION_ID`,
`INVITE_LINK` / `ANSWER_LINK`, `EXPIRES_AT`, and wait (`--wait-seconds`, default 300, 0 returns at once) for a session
that did not exist before: `LINK_STATE=CONNECTED` with `PEER_NODE_ID`, or `WAITING` (timed out), `FAILED` (the
operation ended without a session), exit 1. The bare `synesis host` and `join` use a different per-installation key and
are one-shot, so they cannot join a project. Reaching the runtime: two new daemon operations, `PROJECT_RUNTIME`
(starts the project's runtime if needed and returns its endpoint and runtime command token) and `STOP_PROJECT_RUNTIME`
(never starts a daemon or a runtime); `HttpRuntimeControl` then calls the control plane with the runtime command token.
The control plane's session-authenticated routes now also accept that token (`ControlPlaneSessions.authenticate`):
it already mints a bootstrap token and with it a session, so it is the same authority, and a command line client no
longer replaces the session of an open browser tab to use it. The token reaches a local process through the daemon's
own authenticated IPC; the daemon's request line still carries no project authority.

**Found, not changed.** `synesis init` on a clone takes the `ALREADY_INITIALIZED` branch and does not create the
`.synesis/shared`, `local/providers`, `local/runtime` and `local/profile/records` folders that `init` and `initReplica`
create; nothing read in this slice needs them (the stores create their folders on write), but it is not shown for every
reader. Any session that holds the same project identity is admitted by the control plane, member or not (as before);
only replication refuses it. The ADR says a member is "added through the existing invitation": the invitation only
connects; membership is the leader's list, which is why `add-member` exists.

Tests: `LinkRuntimeOwnerProjectHandlerTest` (delegate answers, listener sees the authenticated session; no delegate still
refuses), `ControlPlaneReplicationTest` (two owners as the launcher wires them, driven through the control plane with
the runtime token: a member's entry reaches the leader and the leader's reaches the member by notice; add and remove take
effect with no restart; the token is the only credential besides a session), `ProjectRoleCommandsTest`,
`LinkCommandsTest` (fake runtime), `HttpRuntimeControlTest`, `DaemonProjectRuntimeAccessTest`. Each fails with the
delegate and listener (removed together), the live list, the token, the follow log rule or the runtime stop removed in
turn. Whole Linux gate: link 171, coordination 302, project-record 60, relay 12, mcp-contract 20, workspace 756 (2
skipped), mcp 189, cli 163, no failures.

# 2026-10-16 — Code moves through a shared Git remote; the leader fetches before it decides (ADR-0098 step 2, slice 3b)

The log names commits (`refs/synesis/prepared/<completionId>`, `refs/synesis/snapshots/<id>`); the objects now travel
between the members' and the leader's clones through one Git remote. Nothing is pushed or fetched on a computer with no
`replication.properties` (every project today).

**The setting.** `code.remote=<remote name>` in `replication.properties`, for a member or a leader
(`ReplicationSettings.codeRemote()`, `withCodeRemote`; `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`, so never a URL or an option;
the record gained a component and keeps its old two-argument constructor). A computer with settings and no remote
refuses what needs code exchange with `CODE_REMOTE_NOT_CONFIGURED`; `origin` is never assumed. `CodeRemote`
(workspace, `application/control`) is the one place that reads it and runs the Git commands: `CodeRemote.of` answers
empty for a single computer.

**Push, before the proposal.** In `AgentTaskCompletionService`: the prepared ref after it is pinned (before
`COMPLETION_PREPARED`), the snapshot ref after it is promoted (before `TASK_SNAPSHOT_CREATED`). In
`ImplementationPublicationService`: the snapshot ref after `createSnapshot` (before `CAPABILITY_IMPLEMENTATION_PUBLISHED`).
There is no single place where refs are written (several places update refs), so the push sits at the three points
where a ref becomes something an entry names, and never for a provisional ref that is removed again. Only
`refs/synesis/...` can be passed (anything else is `IllegalArgumentException`), unforced. A failed push is
`CODE_REMOTE_UNAVAILABLE` with Git's last line; nothing is proposed. A ref pushed for a proposal that then fails stays on
the remote (immutable, harmless).

**Fetch, before anything reads those commits.** The leader's pass fetches `refs/synesis/*` (unforced) after it holds the
integration lock and before it takes the append lock, only when the log has snapshots waiting. A failed fetch answers
`WAITING` / `INTEGRATION_PENDING` with `failure: CODE_REMOTE_UNAVAILABLE` and starts no attempt, because the pass's checks
would otherwise record the work as missing; no remote setting answers `BLOCKED` with `CODE_REMOTE_NOT_CONFIGURED`.
Review access, the capability validation worktree and the repair-lane materialization call `CodeRemote.ensureCommit`,
which fetches only when the commit is not in the clone; a commit still missing afterwards reaches the existing snapshot
errors.

**The leader reads what a snapshot changes from the commit.** It already took the tree from the objects (prepared tree
hash against `rev-parse commit^{tree}`, and the cherry-pick applies the commit). It did not take the changed paths: the
claim-coverage and overlap checks ran on the list in the entry. On a computer with replication settings,
`validateSnapshotMetadata` now compares `diff-tree parent commit` with the declared list and refuses a commit that
changes an undeclared path (`UNDECLARED_PATH:<path>`, or `UNVERIFIABLE_PATHS` when it cannot be read). Not on a single
computer (the same process wrote both).

**The leader publishes the control branch.** After every pass, outside the locks, `publishControlBranch` pushes the
checkout's head to the same branch name when `refs/remotes/<remote>/<branch>` is not already that head. A failed push is
logged (WARNING) and leaves the tracking ref behind, so the next pass tries again; it never changes a pass's answer.
Only that branch and `refs/synesis/*` are pushed.

**A member's new lane is based on the leader's head: the member's own control checkout follows it.** When a worktree is
about to be created (`ProviderWorktreeAllocation`), a member fetches the control branch into
`refs/remotes/<remote>/<branch>` and fast-forwards its control checkout when it holds nothing Synesis did not put there
(`ControlBranchAdvancementService.foreignChanges`) and can fast-forward. The binding's `baseCommit`, the intent's base,
`CONTROL_BASE_ADVANCED`, `workspaceGenerationFresh` and the worktree itself all start from the control checkout's `HEAD`,
so this is one change at one place; basing lanes on the remote-tracking ref instead would have changed all of them. It is
the operation the leader already performs on its own checkout at each integration. Checkout dirty or not a fast-forward:
left untouched, the lane is based on the older head, and the leader's `STALE_BASE` (`isAncestor(base, controlHead)`)
still accepts it while that head is in the leader's history. A fetch that fails fails the allocation
(`CODE_REMOTE_UNAVAILABLE`); a member without a remote setting gets `CODE_REMOTE_NOT_CONFIGURED`.

**Timeouts.** `GitProcessRunner.runRemote`: 30 s (local commands keep 10 s), same environment, hooks off, no signing, same
output cap. A push or fetch over a network does not fit in 10 s; 30 s bounds the time a member's append lock is held
during a capability publication. `GIT_CONFIG_NOGLOBAL` stays, so a remote that needs a credential helper from global
configuration cannot be used; an SSH agent or credentials in the clone's own configuration can.

**Found on the way.**

- Fixed: after the leader integrated a member's lane, `finalizeIntegratedLanes` appended `WORK_INTENT_RELEASED` signed
  by the leader for a participant the member introduced, and the participant guard (slice 1) refused it with
  `PARTICIPANT_NOT_OWNED_BY_SIGNER`, so the pass answered `FAILED` / `INTEGRATION_TERMINALIZATION_FAILED` after the
  branch advanced. The leader decides for the project, so its own entries may act for any participant: the guard now
  applies to a member's entries only, on the member and when the leader judges a proposal
  (`ProjectionView.leaderEntry`, set off while `PredictionEventStore.admit` runs). On one computer nothing changes.
  `MemberReplicationTest.theLeaderMayReleaseAMembersLaneItDecidedToMerge` is new; the attribution test now checks
  the member's own store instead of a leader store appending with a member's key, which no real path does.
  `CodeExchangeMemberTest` now asserts the pass `COMPLETED`; its second lane starts a new work group, because the
  merged lane was its group's only one and the group completed.
- Refs of a completion that is unwound are removed locally and stay on the remote. Nothing prunes `refs/synesis/*`.
- ADR "Pre-push hooks": the tests use a local bare repository; a push to a real GitHub repository is still untested.

Tests: `CodeExchangeLeaderTest` (fetch and integrate from a bare remote, control branch published; undeclared path
refused; fetch failure; not configured; failed control push does not block and is retried; single computer pushes
nothing; `ensureCommit`), `CodeExchangeMemberTest` (real sessions, completion and integration: refs on the remote when
each entry is proposed, the leader integrates from fetched objects, the member's next lane is based on the integrated
head; push failure proposes nothing; no remote setting; no settings, no push), `ReplicationCodeRemoteSettingTest`.
Each fails with the push, the fetch, the control publish, the follow, the path check or the role gate removed in turn.

# 2026-10-16 — A merge no longer holds the log; only the leader merges, and a member's entry triggers a pass (ADR-0098 step 2, slice 3a)

`IntegrationOrchestrationService` held the project append lock for a whole integration attempt, through the worktree,
the cherry-picks and the project's test gate. Every other writer on the computer gave up after two seconds, and so
would every member proposal the leader places under that lock. Invariant 8 ("a merge never holds the log") is built
here for the one computer; Git and Link are unchanged.

**What changed.**

- **A pass is three parts.** (a) Under the append lock, on a log replayed after it was taken: everything the pass did
  before (guards, recovery of an attempt found started, choosing the oldest ready work group) and
  `INTEGRATION_ATTEMPT_STARTED`. (b) With no append lock: the integration worktree, the cherry-picks and the test gate.
  (c) Under the append lock again, on a fresh replay: the outcome is recorded only if the attempt's basis still holds.
- **The basis (`staleBasis`).** The attempt is still the active started one; the control head is still the head it
  started from; every snapshot is still `INTEGRATING`, has no snapshot of a later claim epoch in its task and lineage,
  and passes the same per-snapshot metadata checks that chose it (stale base, claim epoch, claim coverage, prepared
  and snapshot refs, and no live coordination request open). Per snapshot, as when it was chosen: the group was never
  checked against its own paths, and checking it so would retry for ever. If any fails, nothing is advanced and the
  attempt ends as `INTEGRATION_ATTEMPT_FAILED`, status `pending`, reason `STALE_ATTEMPT_BASIS:<reasons>`; the snapshots
  return to the state they had and a later pass chooses again. The response is `RETRY_REQUIRED` /
  `INTEGRATION_STALE`. No new event type. The pass does not loop inside itself: the next pump retries.
- **One pass at a time, without the append lock.** `ProjectIntegrationLock` (`integration.lock` beside
  `append.lock`, same file-lock and in-process guard) is tried once and never waited for, and is held across (a) to (c).
  A pass that finds it taken answers `WAITING` / `INTEGRATION_PENDING` / `WAIT` with `integrationRunning: true`. The
  JVM-static monitor is gone. Nothing that appends takes this lock.
- **Recording waits for the append lock.** Part (c) waits up to five times two seconds. If it still cannot, the
  worktree is removed and the attempt is left started; the next pass ends it as interrupted (the recovery that
  already existed).
- **Leader only.** `PredictionEventStore.isReplicationMember()`; a member's pump (also a log whose role file cannot be
  read) answers the existing nothing-to-integrate result, `READY` / `RETRY`, and touches neither the log nor Git.
- **The leader's merge driver.** `LeaderReplication` takes a hook that runs after each accepted member proposal (a
  throwing hook cannot lose the answer). `MergeDriver` (cli, `command/coordination`, because cli is where the
  workspace and coordination modules meet) turns calls into one pass 3 s later, coalesced, on one thread; an entry
  during a pass causes one more after it. `CoordinationServerLauncher` starts both when `replication.properties` says
  leader. NOT YET REACHED in the product: nothing attaches a Link session to `LeaderReplication` (slice 4), so the hook
  fires only in tests.

**Crash reasoning.** Between (a) and (b)/(c) the log shows an attempt started, no commit, snapshots `INTEGRATING`, and
the worktree on disk. That is exactly what a pass that died in the test gate left before, and the recovery is
unchanged: the next pass holds the integration lock (a dead holder released it), finds no commit on record, ends the
attempt as `INTERRUPTED`, removes the worktree, and makes a new one. Two interrupted attempts in a row still raise the
operator request. A death inside (c) is as before: `INTEGRATION_COMMIT_CREATED` is recorded before the fast-forward, and
a head that equals the recorded commit is completed by `recoverAdvancedControlBranch`.

**Semantic changes (deliberate).**

- A caller that meets a running pass is told to wait at once (`WAITING` / `INTEGRATION_PENDING`); before it waited two
  seconds and got `BLOCKED` / `INTEGRATION_CONFLICT` / `INTEGRATION_LOCK_UNAVAILABLE`.
- While a pass runs, other writers append. A lane that changes (new claim epoch, a later snapshot, a new request) or a
  moved head during the pass now ends the attempt as stale instead of being impossible.
- A pass on a member's log does nothing.

**Found on the way.** `INTEGRATION_ATTEMPT_STARTED` is recorded before the slow part and is visible to members, which
the ADR's text does not mention (it describes preparing, testing and then proposing). The ADR also says a stale pass
"starts again"; here it ends the attempt and the next pump starts a new one.

Tests: `IntegrationOutsideAppendLockTest` (another writer appends while the gate is held open; a second pass is busy
and runs no second gate; a member's log integrates nothing, the leader's does; fail on the old service),
`IntegrationStaleOutcomeTest` (head moved; claim epoch moved and a later snapshot; death after the slow part),
`LeaderReplicationHookTest`, `MergeDriverTest`. The existing integration, validation-refusal, interrupted-attempt and
queue tests pass unchanged except that the nested test helpers `Project` and `Lane` of
`IntegrationValidationRefusalTest` are package-private for reuse.

# 2026-10-16 — A member's proposals and pulls travel to the leader over Link (ADR-0098 step 2, slice 2)

Two computers, each with its own project root, node identity and coordination server, joined by a real
authenticated QUIC session. The slice-1 `LeaderChannel`, `LeaderEndpoint` and `PredictionEventStore` API are
used unchanged. Joining a project (invitation to member list) and redialing a lost session are not here.

**What changed.**

- **The role is on disk.** `replication.properties` in the coordination folder, beside `events/` and never in it,
  holds `role=leader` and `member=<node>` lines, or `role=member` and `leader=<node>`. `ReplicationRole.of` reads
  it each time a log is opened, so every agent's MCP process on a member computer is a member without being told.
  No file is a leader (every log today). A file that cannot be read or parsed gives a role that refuses every
  append and pull with `REPLICATION_ROLE_INVALID`; reads still work, and it is never taken for a leader.
  `synesis coordination status` prints `REPLICATION_ROLE`, and for a member `REPLICATION_LEADER` and
  `REPLICATION_STATE`; it fails with `COORDINATION_ERROR=REPLICATION_ROLE_INVALID` for a bad file. The in-memory
  registration of slice 1 stays and wins, for tests.
- **A process reaches Link through its computer's coordination server.** Only that server holds the session. It
  writes `replication.endpoint` (`127.0.0.1:<port>`) when it starts and deletes it on exit; a member's `LeaderChannel`
  is `ExchangeLeaderChannel` over `LoopbackExchange`, a POST to `/replication` on that address, which the server
  relays over the session (`MemberReplication.relay`). No file, a stale port, a non-loopback host or a server with no
  session is "not sent": `LEADER_UNREACHABLE` at once, nothing appended. A request that may have left and got no
  answer is sent again as the same bytes, then `LEADER_OUTCOME_UNKNOWN`. `CoordinationServerLauncher` starts the
  member side when the file says member; a malformed file stops the server.
- **Protocol `SRL1`** (`ReplicationMessage`, `ReplicationCodec`, documented in `WIRE_FORMAT.md`): PROPOSE, PULL and
  NOTICE with the outcomes ACCEPTED, HEAD_MOVED, REFUSED, ENTRIES, NOTICED, plus UNREACHABLE and NO_ANSWER that a relay
  makes. Version 1, strict, no byte left over, at most 512 KiB. The sender is never a field: `LeaderReplication`
  passes the Link session's remote node to `LeaderEndpoint.accept`, and refuses a pull from a non-member.
- **Notice and pull.** `LeaderReplication` counts event files every 200 ms (agents' processes append too) and sends
  NOTICE to attached members when the count changed. `MemberReplication` pulls on a notice and every 15 seconds and
  applies after checking the chain. `LEADER_LOG_DIVERGED` stops pulling and proposing for good, logs at SEVERE and
  writes `DIVERGED` to `replication.status`.
- **Link.** The application payload bound is 512 KiB, no longer tied to the 4 KiB control frame
  (`ApplicationStreamCodec.MAX_PAYLOAD_BYTES`). A responder also bounded every inbound stream at the handshake
  envelope's 8 KiB, so a larger request to it could never arrive; a stream opened after authentication is now
  allowed a full application frame, one opened before it is not. `LinkCompatibility.CURRENT` is 5, and the SLB3, SLA3
  and envelope vectors were regenerated.
- **The seam for slice 4:** `LeaderReplication.attach(session)` and `MemberReplication.attach(session)` take an
  authenticated, control-ready session whose remote node is a member (leader side) or the leader (member side), and
  `handler()` is what the session must be created with. Tests use `Onboarding` for that; nothing in the product does yet.

**Found on the way.**

- `PeerSession.isUsable()` stays true after a session ended, because the control stream never clears its ready flag.
  `requestApplication` on such a session fails later instead of at once. `Sessions.live` also checks
  `terminalCompletion`.
- Slice 1 reported `LEADER_UNREACHABLE` for a proposal whose first send had been lost and whose resend found no
  session, although the first send may have arrived. It is now `LEADER_OUTCOME_UNKNOWN`.
- A member holds its append lock across the round trip. A relay waits up to 8 s and a proposal is sent up to three
  times, so other writers on that computer (2 s lock wait) can fail while the leader is slow or lost.

Tests: new `LinkReplicationTest` (two roots, two identities, two servers, one QUIC session on loopback),
`ReplicationProtocolTest` (exact bytes and strict rejection), `ReplicationRoleFileTest`, three cases in
`CoordinationLogLocationTest`, a 300 KB echo in `NettyQuicLoopbackTest`. Mutations that each fail tests: ignoring the
file, taking a bad file for a leader, `isUsable` alone as liveness, `UNREACHABLE` after a lost send, the old 4 KiB bound,
and the old 8 KiB bound on a responder's streams. VERIFIED on Linux only.

NOT REACHED: the Windows gate; a second computer; joining; redialing; leader-side wiring in the server (nothing
creates `LeaderReplication` outside tests); the 15-second activity report of a member's agents (the `PULL` message
carries no participants; it needs its own message); Git and the merge.

# 2026-10-16 — A member's log proposes entries to a leader's log (ADR-0098 step 2, slice 1)

Two project roots on one computer, in one test: a leader log and a member log joined by an in-process
channel. No Link, Git or MCP change.

**What changed.**

- `ReplicationRole` says whether a log decides its own entries (leader, the default and every log
  today) or proposes them to a leader (member, with a `LeaderChannel`). It is looked up by
  coordination folder and kept in memory, because the services open a fresh `PredictionEventStore`
  for every decision and know only the folder, and a channel is a live connection. A persisted member
  list and leader identity wait for the Link slice that has a connection to restore.
- `PredictionEventStore.append` on a member builds, signs and validates the entry exactly as before
  (same projections, same `AppendAdmission`, against its own copy), then sends the encoded entry to
  the leader. Only an entry the leader accepted is placed on the member, as the same bytes. A moved
  head makes the member pull and retry through the existing loop and its `MAX_APPEND_ATTEMPTS`; a
  leader refusal is thrown as the same `CoordinationException` code a local refusal has; no leader is
  `LEADER_UNREACHABLE`; no answer after three sends of the same bytes is `LEADER_OUTCOME_UNKNOWN`
  and nothing is placed. A leader log behaves as before.
- `LeaderEndpoint.accept(bytes, authenticatedSender)` is the leader's side: the entry must verify, be
  signed by the authenticated sender, who must be in the member set; its type must not be leader-only
  (`PredictionEventType.leaderOnly()`: the `INTEGRATION_*` entries the integration pass writes,
  `REPAIR_REQUIRED`, `CONTROL_BRANCH_ADVANCED`, `TASK_INTEGRATED`); it must follow the leader's head;
  then the leader's projections and `AppendAdmission` decide, under `ProjectAppendLock` on a store
  opened after the lock. The member's bytes are placed unchanged and never re-signed. The same bytes
  sent again are answered `Accepted`.
- Pull: `entriesAfter(sequence, maxEntries, maxBytes)` on the leader, `applyCommitted` and
  `pullFromLeader` on the member. The whole batch is chained and verified before anything is placed;
  a gap, a digest mismatch, a bad signature or a differing entry is `LEADER_LOG_DIVERGED`.
- Attribution (ADR guard 2): an entry acting for a participant is admitted only from the node that
  signed the entry that introduced it (announcement, repair lane or continuation), else
  `PARTICIPANT_NOT_OWNED_BY_SIGNER`. It is in `AppendAdmission`, so it holds for every append. The
  event types it covers and the payload field for each are in the table in `ParticipantAttribution`.

**Semantic notes.** The rule also covers the participant lifecycle entries (suspend, abandon,
revoke, recovery hold), so a leader that later fences a member's participant needs an explicit
exemption, which belongs with leader-observed liveness in step 3. Types keyed by task, prediction,
contract, capability, work group or operator request carry no participant and are not covered. A
leader's refusal of a member-built entry arises from leader-only rules (membership, type) or a race;
at the same head the member's own check already gives the same answer.

Tests: the Linux gate, link 170, coordination 267 (was 255), project-record 60, relay 12, mcp-contract 20, workspace 736 (2 skipped), MCP 189, CLI 132, browser UI (passed), 0 failures. New: `MemberReplicationTest` (12). Removing the attribution rule, the
leader-only check or the resend recognition each fails 1 to 2 of them. VERIFIED on Linux only.

NOT REACHED: the Windows gate; Link as the transport; a persisted member list; Git and the merge
moved outside the lock; a real second computer.

# 2026-10-16 — Every rule that guards authority is checked by the log, not only by a service

The audit ADR-0098 "Settled before code" asks for, done as its own change before step 2.

**Why.** In step 2 a member computer runs the services on a copy of the leader's log and proposes the
entry; the leader accepts it only if its projections accept it. A rule that lives only in a service
is then enforced by the proposer, which the leader does not trust. On one computer `finish_lane` and
`cancel_lane` append without the project append lock, so their checks could also run on state that
had moved.

**What changed.**

- New `AppendAdmission` (in `domain/collaboration`, with the small `ProjectionView` it reads) runs as
  the last stage of `PredictionEventStore`'s validation, so a refusal has the same code and message
  the service gave. The services call the same functions as an early answer, so there is one rule.
- Moved from services, now checked on the event: `REVIEWER_REQUIRES_PRODUCER` and
  `WORK_GROUP_NOT_ACTIVE` (lane announce); review request rules, reviewer admission, own-lane review
  and handoff target (`ReviewAdmissionRules`); grant issued only to the lane's owner for a lawful
  review; grant consumed once and still pointing at a live lane or a waiting review snapshot;
  continuation source, owner, `CLAIM_EPOCH_STALE`, recovery hold and snapshot, work group, grant
  match and the successor not taking the other side of a review; review decision self-approval,
  producer match and stale lane; no-change completion on a stale group version or with a review
  obligation (`LaneCompletionRules`); snapshot and completion preparation need the lane still the
  caller's at the claimed epoch; `task_not_cancellable` for an integrated task; a capability is
  published only by a lane that is still active; a closed work group cannot be reopened.
- `append` now brings its projections up to the durable head (applying the verified events it had not
  seen) before validating, so it judges the event against the log it is placed on. Hard-link
  placement and the retry on a taken sequence are unchanged.
- Left in the services on purpose, because the log cannot say them: who the caller is (session
  binding), whether a commit, tree or worktree is what the caller claims, the shape of an input, and
  local blocks such as the append lock being unavailable. This is the trust left in a member.
- Rules are checked at append only. Replay does not ask whether an old entry would be admitted today.

**Tests changed on purpose.** Tests that wrote an unlawful event straight to the store (a reviewer
with no other producer, a snapshot for a task the lane did not own, an owner released before it
published) now put the log in a lawful order first. `AgentNextActionServiceTest` self-review cases
add another producer, then release it, so the reviewer lane is admitted and the test still checks
that a self grant is not offered as a review.

Tests: the Linux gate, link 170, coordination 255, project-record 60, relay 12, mcp-contract 20,
workspace 736 (2 skipped), MCP 189, CLI 132, browser UI (passed), 0 failures. New: `AppendAdmissionTest` (15,
each rule appended straight to the store; all 15 fail with the stage off, and the ones that read
state fail with catch-up off), including the stale-store case (two store instances). VERIFIED on Linux only.

NOT REACHED: the Windows gate; the second-computer path (step 2 itself); rules for `ImplementationPublicationService`
that depend on a local binding or tree (`CAPABILITY_PUBLICATION_TREE_STALE`, owner session checks).

# 2026-10-16 — Builds that cannot understand each other refuse to connect, loudly

Step 1 of ADR-0098 (invariant 10).

**Why.** A build whose invitation or handshake format differs from ours looked, to the user, like a
corrupt or expired invitation (`Onboarding` reported every parse problem as `INVITE_INVALID`), and
nothing was exchanged that could tell the two sides apart. The owner: mismatching versions should
not connect and should fail clearly and loudly.

**What changed.**

- One owner for the number: `LinkCompatibility.CURRENT` in the `link` module, value `4` (the
  invitation and answer layouts before it were the third generation, `SLB3` and `SLA3`). The rule is
  in its Javadoc: raise it whenever any format sent between computers changes, and for nothing else.
  It is not the build string.
- Where it sits: the first two payload bytes of an invitation (inside the host's signature) and of an
  answer, and two bytes right after the magic and format byte of the `SLH1` handshake envelope. The
  link prefixes `SLB3-` and `SLA3-` are now frozen; they name the kind of link only.
- A link with another number fails with `IncompatibleVersionException` before the rest is parsed, so
  joining fails before dialing and importing an answer fails on the host. The new
  `OnboardingFailureCode.INCOMPATIBLE_VERSION` carries both numbers; `Onboarding.failure` upgrades any
  failure whose cause chain shows a mismatch, so no path can report one as invalid or lost.
- The handshake reads the number before parsing the envelope. On a difference the side that finds it
  fails with the new `HandshakeFailureCode.INCOMPATIBLE_VERSION` (ordinal 7, appended) and sends the
  existing failure frame carrying its own number (8 bytes instead of 6 for this code only), so the
  peer fails with both numbers too. This is before the transcript, admission and any project data.
- Words: `This invitation comes from a different Synesis version (compatibility 3; this computer has
  4). This computer is older: update Synesis on this computer, then ask for a new invitation.`, or
  `The other computer is older: ask its owner to update Synesis, ...`. The CLI exits 15
  (`ExitCodes.INCOMPATIBLE_VERSION`) with `FAILURE=INCOMPATIBLE_VERSION`; the control plane returns
  `400` `INCOMPATIBLE_VERSION` with the same message; the browser shows it as an alert in the red
  error style; `synesis version` prints `LINK_COMPATIBILITY=4`.
- Wire vectors, `WIRE_FORMAT.md`, `SYNESIS_LINK_V1.md`, `THREAT_MODEL.md` and `control-plane-v1.md`
  updated (ADR-0088: prerelease formats may change).

**Limits.** Builds from before this change cannot read the number, so they still call a new link
invalid or expired; unavoidable. A link whose first two bytes are damaged reads as a different
version. A host waiting on an invitation keeps waiting after a mismatched attempt, since an
unauthenticated connection must not end the wait for the real joiner; it prints the mismatch once as
`INCOMPATIBLE_PEER=<the same sentence>` (`InvitationAdmission.onIncompatiblePeer`, tested in
`NettyQuicLoopbackTest.aHostWaitingOnAnInvitationIsToldOfAnotherNumberAndKeepsWaiting`). The browser
does not show that host-side line yet.

Tests: the Linux gate, link 169, coordination 236, project-record 60, relay 12, mcp-contract 20,
workspace 729 (2 skipped), MCP 189, CLI 129, browser UI 30, 0 failures. New: compatibility number
position and refusal in `ProtocolWireCompatibilityTest`, invitation and answer in
`CompactOnboardingTest` (no candidates gathered, no answer link made), both sides failing in
`NettyQuicLoopbackTest`, the CLI message in `CommandAdapterTest`, `400 INCOMPATIBLE_VERSION` in
`ControlPlaneHttpHandlerTest`, the alert in `App.test.tsx`.

**NOT REACHED.** Two real builds with different numbers. The browser clicked through by a person. The
Windows gate.

# 2026-10-16 — A verified call no longer appends a heartbeat to the log

ADR-0098 ("The log's size and replay cost") holds the reason.

**Why.** In real five-agent runs `PARTICIPANT_HEARTBEAT` was 68% of the events and half the bytes
of the coordination log. What it records, that a participant made a verified call at a time, is
current state, not history.

**What changed.**

- A verified MCP call now checks that the participant exists and is active (a fresh replay, no
  append lock) and then writes the call's time to `admin/participant-activity/<participant>`, one
  small file replaced atomically, in `ParticipantActivity`. Nothing is appended. An unknown
  participant still gives `PARTICIPANT_NOT_FOUND`, a suspended, recovery-held or terminal one
  `SESSION_EPOCH_FENCED`, as before.
- `lastVerifiedActivity` is the later of the time replayed from the log and the recorded time. It
  is applied where the readers get their participants: `collaboration_status` (so the MCP field and
  the control plane keep their meaning), the run controller's `EventLogObserver`, and
  `ConsumerAbsenceGuard`. Logs that already hold heartbeats replay unchanged; the event type, its
  projection case and the places that ignore it as bookkeeping stay for them.
- The session lease was not used: its `lastHeartbeatEpochMillis` is also set when a lease is closed
  or confirmed terminal, which would make a dead consumer look recently active.
- A heartbeat event used to reactivate a suspended participant in the projection. The writer never
  appended one for a non-active participant, so only logs from other writers could do that; the
  replay is unchanged.

**Shown (Linux gate, Podman).** Tests that pin the behaviour passed on the old code and on the new:
`collaboration_status` reports the call's time, an agent that called after the latest event has
seen it all and one that called before has not, and the consumer's absence moment restarts on a
call and stays fixed when calls stop. The new test that an MCP-path heartbeat leaves the log head
unchanged failed on the old code (expected 2, was 5) and passes on the new. Whole gate: link 165,
coordination 236, project-record 60, relay 12, mcp-contract 20, workspace 735 (2 skipped), MCP 189,
CLI 128, 0 failures. Two existing tests that relied on polls appending heartbeats now append them
the way old logs hold them, or assert that a poll leaves the log head alone.

**NOT REACHED.** No real multi-agent run, so the log-size saving is not measured; the Windows gate
was not run.

# 2026-10-15 — The coordination server and the agents now share one event log

**Defect.** Agents wrote `.synesis/coordination`. The coordination server, which the daemon starts
with no data directory, read and wrote `.synesis/shared/coordination`. So the dashboard, the
`coordination status` command and the task, ownership, prediction and speculation commands that go
through the server's HTTP API worked on a log no agent writes. VERIFIED on the 236 acceptance
projects under `Documents\SynesisRemasterAcceptance`: every agent event is in
`.synesis/coordination/events`; one project has a `shared/coordination/events` folder, and it is
empty. The control-plane tests did not show it because they built the service on the agents' folder
by hand.

**Cause.** `CoordinationCliSupport.data()` spelled its own default path. Commit 33433022 moved the
agent call sites to `ProjectLocation.coordinationDirectory()` and did not reach it (the shared
default is older than that commit).

**What changed.**

- `CoordinationCliSupport.data()` defaults to `ProjectLocation.coordinationDirectory()`. An explicit
  `--data` still wins.
- `.synesis/shared/coordination` is neither read, merged nor deleted. If it holds an `events` folder
  when the server starts without `--data`, the server prints `COORDINATION_LEGACY_LOG_IGNORED=<path>`.
- Five other places that spelled `.synesis/coordination` and already held a `ProjectLocation` now ask
  it: `ProviderSessionCoordinationFacts`, `ProviderSessionTerminalizationService`,
  `CodexWakeCoordinator`, `PostMigrationReplayVerifier`, `ProjectMigrationRestorationService`.
  `DoctorService` and `SessionLeaseService` take a bare root, not a location, and still spell it.
- Live dashboard updates. The server's append path was already safe: `CoordinationService.submit`
  takes the same `ProjectAppendLock` as the agent services and decides on a log replayed under it.
  Its live subscription was not: it only saw the server's own appends, so once the logs were one,
  agent events would have reached the dashboard only on a refresh. `ControlPlaneEventStream` now
  reads the durable log once a second, and at once after a server append, and sends every event after
  the last one it sent.

**Tests.** `CoordinationLogLocationTest` (cli, 3 tests) failed 2 of 3 before the fix: the server's
default log was empty after an agent appended, and `coordination status` did not report the event.
The control-plane tests now build the service on `location.coordinationDirectory()`, and a new
`ControlPlaneHttpHandlerTest` case appends straight to the log, as another process would, and expects
the stream to report it; with `ControlPlaneEventStream` put back to its previous code it fails.

**NOT REACHED.** A real daemon, server and agent processes running together. Nothing here was run
outside the test JVM.

# 2026-10-15 — The event log is verified once per process, not on every open and every append

**Why.** Opening a `PredictionEventStore` and every `append` read, decoded, chain-checked and
Ed25519-verified every event file again. A heartbeat is appended on every MCP call and each append
goes through `ProjectAppends.call`, which opens another store, so one call cost at least three full
passes over a log that only grows.

**Measured (VERIFIED, one run, Windows, a copy of the 720-event `k30-five-lllll` log, JUnit JVM with
`-Xmx512m`).** One full pass took about 500 ms: 45-55 ms reading files, about 1 ms decoding, 440-520 ms
verifying signatures (0.65 ms each). Before: open 490-640 ms, append on an open store 490-600 ms,
open plus append (what `ProjectAppends.call` does) 1.0-1.2 s. After the first open: open 10-15 ms,
append on an open store 9-18 ms, open plus append 14-19 ms. ESTIMATE: a get_next_action with several
store opens dropped from seconds to tens of milliseconds at this size; not measured end to end.

**What changed.** `VerifiedEventPrefixes` remembers, per process and per (real events directory,
project), the events already verified. `PredictionEventStore.scan` replays those from memory and reads
and verifies only the files after them. The prefix is trusted only while the directory still has
files `1..K` by name and the file for sequence `K` has the bytes that were verified; otherwise the
entry is dropped and the whole log is verified again, so a missing or changed head file, a gap, or a
fork still fails exactly as before. At most eight logs are kept. No caller changed.

**Not re-checked.** A file inside the remembered prefix that is rewritten in place, other than the
head, is not noticed until the process restarts. Whoever can do that has the user's rights on the
same disk and could equally write a new, correctly signed log. Every new event is still fully
verified.

**Tests.** `EventLogScanTest` +5: reopen on top of a remembered log, two writers on separate stores
lose no event they reported appended (without the project append lock one of them may give up after
eight lost retries, which it did once in the first combined run, so the test counts what each
reported), a fork after the cache is warm is rejected by open and append, and a changed head
file, a removed middle file and a removed head file are not served from memory. Together with the shared-log fix
committed after this, the whole Linux gate (Podman, `check`) is green: link 165, coordination 240, project-record 60,
relay 12, mcp-contract 20, workspace 730 (2 skipped), mcp 189, cli 131; `EventLogScanTest` then passed
five more uncached runs. The Windows gate was not run.

**NOT REACHED.** No real acceptance run; no measurement on the installed product.


# 2026-10-15 — Two networks: answer link, hole punching, router port forward; old link formats removed

ADR-0097 holds the decisions. Not committed.

**Why.** Two computers on different home networks could not connect: each home router drops what
its own side did not start, the invitation named only the host's interface addresses, and nothing
made the host's router let the joiner in. The owner chose: no meeting-point server, both users paste
the other's link, relay out of scope, STUN through Cloudflare by default.

**What changed.**

- `SYN1`, `SLO1`, `SLA2`, the signed traversal offer and answer, the traversal coordinator and its
  UDP exchange are removed, with the control plane's `connect` command, the browser's "Complete
  Join", and the daemon's routing of answers by return target. Only tests produced them.
- STUN: `stun.cloudflare.com:3478`, or `SYNESIS_STUN_SERVER` (`host:port` or `off`). Tests run with
  it off. Project sync does not use it.
- Joining shows `synesis://answer/SLA3-...`. The host takes it from its input (`synesis host`), the
  browser's new answer field, or a `synesis://answer/` link, and sends one-byte datagrams toward the
  joiner's endpoints for thirty seconds. The joiner dials until the invitation expires and says
  `WAITING_FOR_HOST` after its first race fails. Project sync makes one race.
- `synesis host --open-router-port`, or the browser's checkbox, asks the router over UPnP to forward
  the listening port and puts the forward in the invitation; it is removed when the host closes.
- The control plane's `join` returns at once with the answer link and `CONNECTING` and dials in the
  background.

**Shown in a Podman lab on the WSL machine.** Two routers, each translating its LAN and dropping
traffic its LAN did not start, one peer behind each, a STUN responder between them. Scripts in
`D:\SynesisAcceptance\link-vm\natlab`.

| Case | Result |
| --- | --- |
| IPv4, answer pasted | No session |
| IPv6 behind both firewalls, answer pasted | Session, `IPV6/IPV6`, `WORK_RESULT=OK` |
| IPv6 behind both firewalls, no answer | No session |
| IPv4, `miniupnpd` 2.3.9 on the host's router, `--open-router-port`, no answer | Session on the first race, `WORK_RESULT=OK` |

The IPv4 failure is the router, not the code: the host's router recorded the joiner's early packets
as a connection to its own address and port (`[UNREPLIED]` in its connection table), and when the
host sent toward the joiner it translated the host's packets to another port. Three lab artifacts
were found and removed before these results: Podman's own NAT rewrote packets forwarded into the
LANs, `miniupnpd` refused a documentation-range address as reserved, and its setup script found no
WAN interface without a default route.

Tests: the Linux gate on the final code, link 165, coordination 236, project-record 60, relay 12,
mcp-contract 20, workspace 729 (2 skipped), MCP 189, CLI 130, 0 failures; new tests include
`RouterPortMappingTest` against a fake router, `HolePunchTest`, `CompactAnswerTest` and the `SLA3`
vector. On Windows `check repositoryHygieneCheck` ran every module's tests with 0 failures but
failed the workspace test task without a failing test; Gradle then could not recreate the
workspace test-state folder, which deleted cleanly a minute later, and the workspace suite alone
passed, 729 (1 skipped), 0 failures; the cause was not established. `repositoryHygieneCheck` passes.
Browser UI: typecheck, lint, 29 tests.

**Shown between two physical computers on two home networks**, both on the installer
`0.1.0-dev.remaster46-20261015` built from this tree. The friend's PC hosted with
`synesis host --open-router-port` and reported `no UPnP router answered`; the friend installed it
through the setup wizard with the firewall option ticked. The repository owner's PC joined: its first
race failed and it reported `WAITING_FOR_HOST`; the answer link was pasted into the host, which
reported `ANSWER_VERIFIED`; the next race connected over IPv4 (`LAN/MANUAL`), both sides reported the
other's node ID, `CONTROL_READY=true`, `LIVENESS=LIVE`, and the join `Work: OK`. These two routers
did not keep the joiner's early packets against the host's port, unlike the lab's Linux routers, so
the IPv4 failure in the lab holds for some routers and not for all. Which routers, and whether the
firewall rule was present on the friend's PC, were not inspected.

**Also changed after the two-PC run.** The terminal no longer draws a QR code for links: the
renderer, the `zxing` library and the terminal width and Unicode detection only it used are removed,
and no `QR_RENDERED` or `QR_SKIPPED` line is printed. The join's hint and the messages for
`CONNECTION_FAILED`, `HOST_TIMEOUT` and `NO_USABLE_CANDIDATE` now say what to try next. Linux gate
after this: CLI 128 (the two QR tests are gone), every other module unchanged, 0 failures; the
installer builds with strict dependency verification.

**NOT REACHED.** A real router's UPnP service. NAT-PMP and PCP. The browser flows clicked through by a
person. A session longer than the forward's one-hour lease. The reverse direction between the two
PCs.

# 2026-10-14 — Two machines connect over Link in both directions

ADR-0095 and ADR-0096 hold the decisions.

**Why.** `synesis host` on one computer and `synesis join` on another had never connected with the
installed product. Run between Windows and Linux virtual machines, it failed four ways.

**Root causes.**

- `LocalInterfaceCandidateProvider` skipped loopback addresses but not the loopback interface. WSL
  binds `10.255.255.254` there, and a host on a WSL machine invited its peer to it.
- It offered every IPv6 address of an interface. The listener is bound to the wildcard address, so
  the operating system chooses the source of each reply; with a stable and a temporary address in
  one prefix, the Windows default, it chooses the temporary one. The invitation carried the stable
  one. A capture on the joiner showed each 1200-byte initial packet answered by 100 bytes from the
  other address, which the joiner dropped until it gave up.
- The compact invitation carried one endpoint, and the order ranks a global IPv6 address above
  every LAN address. A joiner without IPv6 on the same private network had nothing to dial.
- Windows Firewall drops the session when Windows hosts. No rule named the installed runtime and
  Windows showed no prompt.

**What changed.**

- The provider skips the loopback interface, and offers an IPv6 address only when the machine sends
  from it to a neighbour in the same prefix, found by connecting a datagram socket, which sends
  nothing. An address that cannot be probed is kept.
- The invitation carries one to eight endpoints, one of every kind of candidate before a second of
  any. This is a format change: the revision is `SLB3`, and `SLB2` is no longer read or written.
  The joiner dials every endpoint of a family it has at once and keeps the first authenticated
  session. `PATH_SELECTED` reports the pair that won; it reported the first pair tried.
- The Windows installer can add the firewall rule: a checkbox in the wizard, `--allow-link` on
  `install` and `repair`, removal on uninstall. It starts itself elevated for that one command, and
  a declined prompt is a warning. It is never chosen by default on a first install.

**Shown with the installed build `0.1.0-dev.remaster45-20261014` on Windows.** It was installed with
`install --allow-link`; the repository owner approved the prompt, the command printed
`LINK_FIREWALL=ADDED`, and the rule named that version's `runtime\bin\java.exe` in place of the rule
made by hand for the previous version. The other machines ran the Linux CLI built from the same
tree. In each arrangement both sides reported the other's node ID, `CONTROL_READY=true` and
`LIVENESS=LIVE`, and the join `WORK_RESULT=OK`:

| Host | Joiner | Path |
| --- | --- | --- |
| VirtualBox machine bridged to the home network | Windows | `IPV6/IPV6` |
| Windows | that machine | `IPV6/IPV6` |
| Windows | a machine without IPv6 (Podman container with host networking on the WSL machine) | `LAN/MANUAL` |
| that machine | Windows | `LAN/MANUAL` |

Before the changes the first worked, the second and the fourth ended `CONNECTION_FAILED`, and the
third `NO_USABLE_CANDIDATE`.

Tests: `LocalInterfaceCandidateProviderTest` has one test that fails on the WSL machine and one that
fails on this Windows machine without the provider changes. The Linux gate on the tree before the
review fixes: link 180, coordination 236, project-record 60, relay 12, mcp-contract 20, workspace
729 (2 skipped), MCP 189, CLI 130, 0 failures. The installer's Go tests pass. The Windows gate
(`check repositoryHygieneCheck`) on the final code: link 180, coordination 236, project-record 60,
relay 12, mcp-contract 20, workspace 729 (1 skipped), MCP 189, CLI 130, 0 failures.
Scripts and output: `D:\SynesisAcceptance\link-vm`.

**Still wrong.**

- The firewall rule names a path that holds the version. An unattended `update` cannot refresh it;
  a repair with the option, or the wizard, is needed before the machine can host again.
- A build from before this change and one from after it cannot join each other.
- One multi-process Link test failed once on each platform on an earlier run and passed on the next
  (`NettyQuicLoopbackTest.reportsAbruptProcessLossWithDocumentedTerminalCategory` on Windows,
  `OverlayGraphQuicTest.sixMemberOverlayConvergesRoutesTransitRecoversAndRejoins` on Linux), with
  two virtual machines running. The cause was not established.

**NOT REACHED.** Two physical computers. Two networks: every run was on one router or one virtual
switch. The wizard checkbox with a real prompt, a declined prompt, and removal of the rule on
uninstall; those rest on tests that replace the elevation. The Linux installer; the Linux side ran
the portable launcher, and `:cli:nativeMcpLauncher` failed in the container for want of Go.

# 2026-10-13 — The UI opens on an installation that knows no project

ADR-0073's 2026-10-13 follow-up holds the decision.

**Why.** After every project could be removed from the registry, the repository owner removed
them all and `synesis ui`, `synesis://ui` and the desktop shortcut stopped opening anything
(`NO_KNOWN_PROJECT`; the link and the shortcut said nothing at all).

**What changed.** With no project known, the daemon serves the page from the installation's own
workspace (`InstallationHome`, `ui-home` under the application state root). The registry refuses
to record it, the snapshot reports `project: null` when served from it, and a project runtime is
preferred whenever one is up or can start. The Windows activation helper shows a message box when
a link could not be opened.

**Shown with the source-built CLI and an empty isolated installation.** `synesis ui --no-browser`
exits 0 and `synesis open synesis://ui` answers `READY`; the home workspace is initialized and no
registry file is written. In a browser against the home runtime: the Projects page with no rows,
the Add project button, no project navigation, the name `ui-home` nowhere, no console error; a
folder registered through the control plane is listed after a reload. The registry, control-plane
and daemon tests pass.

**Not shown.** The message box of the activation helper. An installed build. A project added from
the home page is listed as inactive there: the page still opens only the project whose runtime
serves it, so it becomes openable when the UI is next opened and that project serves it.

# 2026-10-13 — Removing projects works reliably, any project can be removed, and a reload keeps the page

**Root cause of the unreliable removal.** `KnownProjectRegistry` guarded its file with a file lock
only. A file lock belongs to the process, so a second thread of one runtime asking for it was
refused (`OverlappingFileLockException`, reported as `REGISTRY_LOCK_FAILED`, "could not access
known project registry") instead of made to wait. The runtime reads the registry for every
snapshot it sends the page while a removal writes it, so some removals were refused and a
snapshot built at that moment listed no known project. A new test runs three reading threads
against one removing thread and failed with exactly that message before the change. Threads of
one process now queue on a per-file lock before asking for the file lock.

**What else changed.**

- Any project can be removed from the registry, the one serving the page included
  (`CURRENT_PROJECT_PROTECTED` is gone). Only the entry goes; the runtime keeps running and the
  project is remembered again when a command next runs in it. The confirmation says so.
- The page keeps its session in the tab's session storage, so a reload carries on instead of
  asking for `synesis ui`. A session the runtime refuses is dropped. Sessions still end after
  thirty minutes and are not renewed.
- An empty registry is shown as empty; the page no longer puts the current project back into the
  list when the registry lists nothing.

- A selection is removed in one request and one registry write (`projectIds` on the existing
  `remove-project` command; `projectId` still names exactly one). The page used to send one
  request per project. The repository owner asked for a persistent removal queue in the daemon;
  it was not built, because the single write already gives what the queue was for: the whole
  selection is durable before the request answers, so there is nothing left to work off in the
  background and a reload agrees at once. Measured on a real runtime with thirty throwaway
  projects: one request, 10 ms, rows gone from the page 7 ms after the click, none listed after a
  reload.

**Shown.** Against a real runtime from the source build, isolated application state and eight
throwaway projects, in a browser: the page loaded from its bootstrap link, loaded again by address
alone, removed all eight projects (seven inactive and the live one) after one confirmation with no
error, and still listed none after another reload. Registry and control-plane tests and the 29
web UI tests pass.

**Not shown.** The same against an installed build. A project removed while agents work in it
returns as soon as one of their commands runs, which can look like a failed removal.

# 2026-10-13 — `synesis://ui` opens the UI, the installer stops the daemon, and can add a desktop shortcut

ADR-0073 and ADR-0080 hold the decisions under their 2026-10-13
follow-ups.

**What changed.**

- The daemon answers `synesis://ui`, `synesis://open` and the bare `synesis://` by opening the UI
  on a known project, the way `synesis ui` does outside a project. They are matched as text and
  never reach a runtime as a deep link. Any other address is validated as before.
- The daemon protocol has `SHUTDOWN`: the daemon answers, then closes itself and the runtimes it
  started. The installer sends it after an install, repair or update has activated the new build,
  and before an uninstall removes files (`bootstrap/internal/install/daemonstop`). A daemon too old
  to know the request is ended directly, only when its program file lies inside the installation
  root. Uninstall fails when the daemon cannot be stopped; an update reports it and still succeeds.
- The wizard's install page has a checkbox, "Add a Synesis UI shortcut to the desktop". It writes
  an internet shortcut to `synesis://ui` with the installation's icon
  (`bootstrap/internal/install/uishortcut`). It is ticked on a first install and afterwards
  follows whether the shortcut exists. Unticking it does not remove an existing shortcut;
  uninstall does, and only a file that points at `synesis://ui`.
- Uninstall also ends every other process whose program file lies inside the installation root
  (project runtimes still exiting, MCP servers) and retries a refused file removal for about three
  seconds. The first real uninstall with build `remaster38`, over an installed `remaster37`,
  failed with "being used by another process" on a jar: the old daemon had been ended directly and
  its runtimes were still exiting. No process ran from the installation when it was inspected
  afterwards and the file was no longer held.
- The eight daemon tests that opened a real browser no longer do.

**Shown.** `go test ./...` in `bootstrap` and `:cli:test` (130 tests) pass, with new tests for each
part, including one that ends a stand-in older daemon only when it runs from the installation.
With the source-built CLI and isolated application state: `synesis open synesis://ui` and
`synesis open synesis://` answered `READY`, `synesis open synesis://nonsense` was refused, and
the installer's stop request ended a live daemon, its two runtime processes and its endpoint file.

**Not shown.** No install, update or uninstall was run through the new installer, so the stop was
not seen from the wizard, nor against a daemon of an installed older build. The address was not
typed into a browser, and the desktop shortcut was not created by a real run or double-clicked.
MCP server processes started from the installation are not stopped by the installer.

# 2026-10-13 — `synesis ui` opens again on a running project, and opens outside a project

ADR-0073 holds the decisions under its 2026-10-13 follow-up.

**Root causes.** The daemon built every UI link from the one bootstrap token a runtime issued at
start, so a second opening carried a spent token ("bootstrap token is invalid or expired"). And
`synesis ui` sent no project unless `--project` was given, which the daemon refused
(`REQUEST_REJECTED`), inside a project as well as outside one.

**What changed.** The runtime issues a fresh one-time bootstrap token to the holder of its command
token (`POST /api/v1/commands/bootstrap`), and the daemon asks for one at every opening. The command
sends the working directory when it is a project; without one the daemon serves the UI from a
runtime already up, or from the first known project that starts, and answers `NO_KNOWN_PROJECT`
when there is none.

**Changed meaning, stated.** An unredeemed bootstrap token stops working when a newer one is
issued. A second opening replaces the first tab's session, as a second redemption always would
have. `OPEN_UI` no longer requires a project on the daemon protocol; `OPEN_PROJECT` still does.

**Shown with the source-built CLI, isolated application state, throwaway projects.** Outside a
project with nothing known: exit 10 and the sentence naming `synesis init`. After `synesis init`
in a folder: `synesis ui --no-browser` exits 0 there and from an unrelated folder. Against a real
runtime over HTTP: the start-up token opens a session (201), the same token again is refused (401,
the reported message), a fresh token is refused without the command token (401) and issued with it
(201), and it opens a session that reads the snapshot (200). `synesis ui` twice in a row with a
browser answered `SYNESIS_UI_OPENED` both times.

**Not shown.** What the two browser tabs displayed was not read back. The installed build was not
replaced, so the installed `synesis ui` still has both defects until a new build is installed.
With no known project at all the UI still cannot open; that needs a control plane no project
owns, which ADR-0073 rejected.

# 2026-10-13 — The browser UI is redrawn around a navigation rail

Only `web-ui/src/app/App.tsx`, its test and `web-ui/src/styles.css`
changed: no endpoint, DTO, route path or client call did. The repository owner asked for a free
redesign, which supersedes the SYN-055 brief where they differ (that brief ruled out extra
navigation and fixed the content widths).

**What changed.**

- A rail on the left, like the setup wizard's, holds the brand, the Projects link, the five
  project views with a count each, and the connection state with the runtime version. The
  project views were tabs under the project header. The connection message after the first
  snapshot was shown nowhere; a degraded stream is now visible in the rail.
- Projects is a dense list with aligned columns in place of cards. Overview, Agents,
  Coordination, Network and Diagnostics keep their content and are restyled.
- The stylesheet was rewritten: 3473 lines to about 1560, same palette as the wizard.
- Projects can be removed from the registry several at a time: a checkbox on each removable row,
  "Select all" over the filtered list, and one confirmation that names them. The registry still
  removes one entry per request, so the page sends them in turn; a refused one comes back into the
  list with its reason and does not stop the others. The project serving the page has no checkbox.
- Removed controls that did nothing or were doubled: the Agents "Filter" button, and the second
  "Create Invitation" and "Join" buttons on Network.

**Checked.** Typecheck, lint, 27 tests and the production build pass. Every view and the finding
dialog were looked at in a browser against the development fixture at about 1376 px wide; the
Projects columns align and no page scrolls sideways there or at 375 px. Not checked: the packaged
UI behind the real control plane, a browser other than Chromium, and an accessibility scan.

# 2026-10-13 — The Windows setup wizard is redrawn, opens at once, and no longer flashes console windows

Build `0.1.0-dev.remaster36-20261013`. ADR-0080 holds the decisions under "Redesign follow-up
(2026-10-13)".

**What changed.**

- The wizard has a new look: the browser UI's neutral dark palette, a rail with the mark and the
  steps, pages drawn in code. The PNG artwork under `bootstrap/assets/setup/` is deleted. The
  process is DPI aware.
- Console helpers the windowless installer starts no longer open console windows
  (`bootstrap/internal/childproc`): the launcher checks, `reg.exe`, the migration command, and
  the Java runtime behind a `synesis://` link.
- The single-file installer opens the wizard itself and unpacks its bundle in the background;
  install waits for it as a first step, "Unpack the installer". The wizard no longer starts the
  bundled runtime before it shows a window.
- Browse runs the folder picker inside the wizard (`bootstrap/internal/folderpicker`, shared with
  `synesis-folder-picker.exe`).

**Changed meaning, stated.** The wizard's start checks the bundle's shape only; the launcher run
that used to precede the window now happens only where it already happened a second time, in
install and repair before activation. The progress log cuts long lines instead of scrolling
sideways. The first page's button reads "Next" for both actions. Enter and Escape now act.

**Measured on this machine, 96 DPI, build `remaster35` bundle.** Old single-file installer: window
after about 2.5 s, 4 console windows on opening (two runs; a third opened no window and exited 1,
cause not investigated). New: window after about 150 ms, no console window (three runs). A
command-line install into a throwaway folder with `--skip-path-update`: 6 console windows with
the old installer, none with the new, both exit 0. The Gradle-built `remaster36` installer: window
after 89 to 141 ms, no console window (three runs); Tab reaches Cancel and Enter there closes the
wizard.

**The review.** One reviewer read the uncommitted change and ran nothing. It found one defect:
Enter with the focus on Back, Cancel or Browse moved forward, because owner-drawn buttons are not
push buttons to the dialog keyboard interface. It was reproduced on the first build and fixed.
Also acted on: the uninstall page opens with the focus on Back, the shown version is read only
from the bundle root, and the panel painter lost its unused second colour. Left as they were: the
bundled launcher check has no timeout, so a hung launcher would keep a closed installer alive; and
a single-file install validates the bundle once after unpacking and again before activation.

**Not shown.** No install, repair or uninstall was run through the new wizard against a real
installation, so the PATH step and link registration were not exercised from it. No display with
a scale above 100% was available, so the scaled layout is covered only by the layout test at 96
DPI. `go test ./...` passes in `bootstrap`; `GOOS=linux go build ./...` and `go vet ./...` pass.


Branch `remaster/2026-10-10`, builds `0.1.0-dev.remaster34-20261013` and `remaster35`. ADR-0094
is accepted and built. The repository owner decided the three questions the entry below left
open: an operator sets the command with a CLI command; a failure on the combined work returns to
the lane that contributed last, two repairs, then the operator; the command runs on the combined
work by default and in a lane alone only when the project says so.

**What is built.**

- `synesis project validation set -- ARGV...`, `show`, `clear`. `set` and `clear` hold the project
  lock and, in a Git repository that tracks the project file, commit that one file; a checkout
  that holds changes Synesis did not make is refused, by the rule integration uses.
- A non-zero exit of the command on the combined work refuses the snapshot added last to the
  attempt, as its own recorded fact (`INTEGRATION_VALIDATION_REFUSED`, state
  `validation_refused`), and reopens its lane by the code a review rejection uses. The lane is
  shown what the command printed and what was run, corrects its files or not, and publishes
  again. A refused snapshot never integrates.
- After two refusals of a lane, or when the command timed out, could not be started, or the pass
  that ran it died twice, nothing is refused: the operator request
  `integration_validation_failing` is raised and no attempt is started until
  `synesis operator recheck`.
- An attempt that a dead process left started is ended as interrupted by the next pass that
  holds the project lock, unless the branch already holds its commit.
- The project lock is no longer dropped when a second thread of the same process fails to take
  it (Linux and macOS; a defect that was there before).

**Changed meaning, stated.** A project that declares `validation` no longer has it run in each
lane alone unless it also says `alsoInLane`. `fork` keeps a project's validation command and
review policy. A finish that changes nothing is validated nowhere unless the lane run is on. A
command that timed out raises the operator request where it used to answer "retry". An older
build cannot read a log holding the new event nor a project file holding `alsoInLane`.

**The review.** One reviewer read the uncommitted change and ran nothing. It found the core
sound and found one defect that could have ended a live attempt, with eight that left an
operator or an agent without a lawful step; ADR-0094 lists them under "The quality review of the
build". All were acted on except one structural suggestion that did not come out smaller. The
tests found two more defects in the fixes themselves: ending the lane did not close the
operator's request, and the sentence that tells a lane the command has changed did not reach a
lane whose workspace had become stale, which an operator's change of the command always causes.

**Real runs.** The gate is a script outside the repository, declared by the operator, which the
agents cannot read: it runs the project's tests and one rule the goal does not state, the form
in which `list` prints a task. Every result of the earlier runs that was tried against it fails
that rule.

- *A gate that fails on a rule the goal does not state,* twice. On `remaster34`, Sonnet then
  Luna: the lane of the command-line program was refused (it printed `[1] [ ] Buy milk`), was
  shown what the gate printed, corrected its files and was integrated; 168 seconds, no resume.
  On `remaster35`, Luna then Sonnet: refused (`1 [ ] Buy milk`), corrected, integrated; 124
  seconds, no resume. The gate passes on both results. Demonstrated: a refusal by the command,
  the lane reopened with the output, a repair, a second attempt that integrates, work groups
  completed.
- *A gate that cannot pass,* on `remaster34`: the storage lane was refused once and its agent
  would not publish again work it knew to be right. No request was raised and the run ended
  `STALLED`. After the operator had declared a gate that can pass, the lane was shown only the
  old failure and told the operator the old command still failed. Both are answered in ADR-0094,
  "What the real runs showed": a lane refused by validation that asks to finish is let through
  with its files as they are, and the instruction says when the command has changed and tells
  a lane that can do nothing to say so to the operator.
- *The same, on `remaster35`.* The lane was refused, told the operator why
  (`lane_asks_operator`), and on a resume published the same work and was refused a second
  time; `NEEDS_OPERATOR`, 313 seconds, 3 resumes. The operator declared the gate that can pass
  and started the agents again: `COMPLETED` in 122 seconds, no resume, both lanes integrated,
  the gate passes on the result. Demonstrated: an operator request raised by a refused lane, the
  operator's change of the command, and integration after it without the lane changing a file.
  NOT REACHED in a real run: the request Synesis raises itself after two repairs, and its
  closing by a recheck; the lane told the operator first. Tests walk that path.
- *Goals without a command,* on `remaster35`, to see that nothing else moved: SL 109 seconds
  and no resume, review with the policy LS 175 and none, cancel SL 52 and none, SLS 126 and
  none, SLSLS 174 and two. All completed with their tests passing.

One pass over each. A first pair of runs on `remaster35` is not counted: the machine was out of
memory, the Codex process ended at start in both, and a Java crash log was left in one project's
checkout. That log was raised to the operator as a foreign change of the checkout, and
`project validation set` refused the checkout that held it. No agent ran
`synesis project validation` itself. The request a lane raises stays open after the run
completes, until the operator closes it.

**Limits.** The command runs while the project lock is held, so other writers are refused and
retry while it runs. The lane asked to repair is the last of the attempt, which is not always
the lane at fault. A non-zero exit that is no verdict on the work, a flaky test, uses a repair.
The operator has no verb to grant a further repair. The operator's commands are trusted by
where they are run: an agent with a shell could run `synesis project validation clear`; none
did in these runs. That the branch is not advanced when the record of the attempt's commit
cannot be written is read from the code, not tested.

Verification: on the last commit, link 172, coordination 236, project-record 60, relay 12,
mcp-contract 20, workspace 725, MCP 189, CLI 126, 1,540 tests, 0 failures, with the
documentation, format, architecture, dashboard and hygiene gates: on Windows from PowerShell with
four workers in 16 minutes 31 seconds (1 skipped), and on Linux in the container in 2 minutes
24 seconds (2 skipped). The main code was written by two implementation subagents from a design
the lead wrote and was read by the lead; the tests were written by test subagents, Sonnet 5.5 at
medium effort pinned at launch, which reported their model and could not show their effort from
runtime metadata.

# 2026-10-13 — An agent is woken for its own lane only, a wait is held inside Synesis, and the review of both

Branch `remaster/2026-10-10`, builds `0.1.0-dev.remaster32-20261013` and `remaster33`. No new
record. Three changes answer what the runs of `remaster30` showed, and the strict quality review of
them and of the two changes to Git lookups was run and acted on.

**What changed.**

- *A stopped agent is resumed only for a lane of its own.* Progress used to resume every stopped
  agent of a provider that held any open lane, also one whose own lane had ended; such an agent
  looked for work, announced lanes to verify what was integrated, and each announcement woke the
  others. The run controller now asks whether a lane taken through one of the agent's own
  connections is still open. The provider manual and the managed `AGENTS.md` section say that a
  lane exists to produce something the goal names, and that an agent with nothing left unclaimed
  stops.
- *A caller told to wait is held by the server.* An agent told to wait asked again every few
  seconds and gave up after about a minute, was stopped, and was started again as a new session
  that had to take its lane back; a reviewer was once left waiting for good on that path. A
  `get_next_action` whose answer is a wait with no message to hand over is now held until the
  project log has something new for the caller or twenty seconds pass
  (`SYNESIS_WAIT_HOLD_SECONDS`, zero to thirty, zero turns it off), and when something is new the
  whole call is made again, through every gate, and that answer returned. The hold changes when
  an answer is given, never which.
- *The note beside a message asks for an answer.* It asks the reader for one short answer to a
  direct question about its own files, and tells a sender to carry on and not wait.

**The eleven real runs, repeated on `remaster32`, and again on `remaster33` with the review fixes.**

| Run | remaster28 | remaster30 | remaster32 | remaster33 |
| --- | --- | --- | --- | --- |
| Two agents, SL | 170 s, 0 resumes | 66 s, 0 | 93 s, 0 | 126 s, 0 |
| Two agents, LL | 194 s, 0 | 137 s, 0 | 183 s, 0 | 227 s, 0 |
| Three agents, SLS | 147 s, 1 | 239 s, 1, one test of the result fails | 137 s, 0 | 151 s, 0 |
| Three agents, LLL | 577 s, 3 | 509 s, 1 | 203 s, 0 | 485 s, 1 |
| Five agents, SSSSS | 157 s, 11 | 211 s, 7 | 105 s, 1 | 122 s, 1 |
| Five agents, LLLLL | 422 s, 2 | TIMED_OUT, 1,801 s, 38 | 282 s, 0 | 343 s, 0 |
| Five agents, SLSLS | 316 s, 10 | 227 s, 5 | 320 s, 0 | 475 s, 0 |
| Five agents, SLSLS again | 271 s, 13 | 248 s, 2 | 271 s, 0 | 222 s, 0 |
| Five agents, LSLSL | 249 s, 0 | 384 s, 7 | 378 s, 0 | 178 s, 0 |
| Review with the policy, LS | 412 s, 0 | STALLED, 510 s, 3 | 165 s, 0 | 185 s, 0 |
| Cancel, SL | 82 s, 0 | 80 s, 0 | 44 s, 0 | 64 s, 0 |
| All eleven | 2,997 s, 40 | 4,412 s, 64, nine completed | 2,181 s, 1 | 2,578 s, 2 |

Eleven of eleven completed on both builds, and the tests of every integrated result pass. One
resume in eleven runs, and two, against 64 and 40. One pass over each goal on each build: it shows
the restarts are gone, not how often a run of each kind completes, and the times of one goal
differ between two passes on near-identical code by more than they differ between builds. The
last two runs on `remaster32` overlapped with a build on the same machine. The power of the
machine failed during the runs on `remaster33`: the five-Luna run was cut off with its five goals
integrated and its tests passing but no result recorded, and it and the run after it were made
again; the table holds those.

The cycle with a file Synesis did not put in the project's checkout was repeated: the request to
the operator was raised with the file as evidence, closed when the operator removed the file and
asked for a recheck, and the run started again completed in 109 seconds.

**Messages were hardly used.** Three messages in all the runs on the two builds. On `remaster32`,
one: the usage document's lane asking the command-line lane to say when its work was ready,
unanswered. On `remaster33`, in the run the power cut off, the usage document's lane said which
command syntax it had assumed and asked to be told if the program differed, and the command-line
lane answered eleven seconds later that it had not written the program yet and was waiting for
the storage contract; the document was left as assumed and matches the program that was then
written. One question answered of two: too few to say the new wording makes an answer more
likely. These goals need no conversation: the public contract carries what one lane needs from
another. A message waking a stopped agent, a successor inheriting a conversation and an agent
addressing the operator remain NOT REACHED in a real run.

**The review.** One reviewer read the three commits (`2c39c551`, `696069a3`, `a73237c1`) and ran
nothing. It found the two changes to Git lookups sound for honest layouts and found one defect
that mattered in the third, with eleven smaller findings. Each was checked against the code; all
were real and all are acted on or stated below.

- *An agent resumed once could be left for good.* Each start of an agent's process has a
  connection of its own, and only the last was asked whether it held an open lane. A resumed
  process that ended before it took its lane back held nothing; the lane stayed open under the
  first connection, and the agent was never resumed again. Every connection an agent has had is
  now asked.
- *A message already waiting was handed over only after the hold.* It is now handed over at once;
  a caller with a message is not held.
- *The answer after a hold was projected outside the gates of a call.* The root, the admission,
  the lease and the recovery fence were checked once, before up to fifty seconds of hold. The
  hold now sits in the protocol handler around the whole gated call, and the upper bound is
  thirty seconds.
- *The Git layout was resolved textually where Git resolves it physically.* A `..` in a pointer
  file read from behind a symbolic link named a different directory than the one Git uses.
  Real paths are used throughout.
- *Smaller.* A directory that is itself a Git directory counts as possibly inside a repository.
  The last-resort guess of a repository's identity from a bare look at `.git` is removed: where
  neither the layout nor Git names a common directory there is none, and the caller is refused.
  What ends a hold is now stated once, from the event type's own "records no state change". A
  lookup of a connection's session that failed is no longer remembered as "no session".

**Semantic changes, stated.** Reading a session's branch and commit in one Git question differs
from the two questions before in three cases: a branch that shares its name with a tag is now
accepted (the short name used to come back as `heads/x` and fail); a branch that is a symbolic
reference is resolved to its target; and a reference named `HEAD` makes the question ambiguous
and fails every worktree of that repository, closed. A directory with a hollow `.git` no longer
has a repository identity.

**Corrected.** The entry below says Synesis does not run a project's combined tests before it
integrates. That is wrong. A project that declares `validation` in `.synesis/project.json` has
that command run in the lane before a finish is published and again on the integration worktree
before the control branch moves (ADR-0042). The acceptance projects never declared one, which is
why a failing test was integrated. What is missing is a way for an operator to set it, and a
lawful next action when the combined run fails; neither is built.

Not done: the resume gate asks per connection while the answer deadline and the operator's hold
are still asked per provider; whether a participant with no active intent can be offered a review
it is then never woken for (a suspicion of the review, not reproduced); the combined test run
above.

Verification: on the commit that holds the review fixes, link 172, coordination 211,
project-record 60, relay 12, mcp-contract 20, workspace 691, MCP 183, CLI 120, 1,469 tests,
0 failures, with the documentation, format, architecture, dashboard and hygiene gates: on Windows
from PowerShell with four workers in 15 minutes 30 seconds (1 skipped), and on Linux in the
container in 1 minute 53 seconds (2 skipped). The two tests of the agent left for good fail with
the old rule put back, both together and the message one alone. The Linux runner now stops when
a Git step of its preparation fails: a stale lock once left the patch of an earlier run in place
and the gate ran on that tree without saying so. The test subagent: Sonnet 5.5 at medium effort
pinned at launch; it reported its model and could not show its effort from runtime metadata.

# 2026-10-13 — The real runs repeated after the reviews, and a reviewer left waiting

Branch `remaster/2026-10-10`, builds `0.1.0-dev.remaster30-20261013` and `remaster31`. No new
record. The consumer half of ADR-0092 was renamed and what nothing produces any more was removed
(ADR-0092, "The quality review of the build"), and the eleven real runs were repeated on the build
that holds the review fixes of step 3, which the runs of the day before had not.

| Run | remaster28 | remaster30 |
| --- | --- | --- |
| Two agents, SL | 170 s, 0 resumes | 66 s, 0 |
| Two agents, LL | 194 s, 0 | 137 s, 0 |
| Three agents, SLS | 147 s, 1 | 239 s, 1, one test of the result fails |
| Three agents, LLL | 577 s, 3 | 509 s, 1 |
| Five agents, SSSSS | 157 s, 11 | 211 s, 7 |
| Five agents, LLLLL | 422 s, 2 | TIMED_OUT, 1,801 s, 38 |
| Five agents, SLSLS | 316 s, 10 | 227 s, 5 |
| Five agents, SLSLS again | 271 s, 13 | 248 s, 2 |
| Five agents, LSLSL | 249 s, 0 | 384 s, 7 |
| Review with the policy, LS | 412 s, 0 | STALLED, 510 s, 3 |
| Cancel, SL | 82 s, 0 | 80 s, 0 |

Nine of eleven completed. The three that did not end cleanly:

**The review run stalled, and the cause is found and fixed.** The reviewer rejected the first
snapshot and accepted the corrected one, and it was integrated with its tests passing. The
reviewer's own lane was then told to wait for a producer, for good, and the group never closed. The
producer's agent had stopped and been resumed three times while it waited for review; a resumed
agent continues its lane as a successor, and the continuation after the reviewer's admission left
the reviewer's decided grant addressed to a superseded lane. The rule that asks whether a reviewer
still owes a review drops such a grant, saw none, and answered that the reviewer was waiting for
work to appear. A reviewer that has recorded a decision and has no producer left now owes nothing.
The rule was not changed by any step of this branch since `remaster27`; the same goal completed on
the two builds before. On `remaster31` the agents were started again on the stalled project and it
completed in 132 seconds; two fresh runs of the goal completed (382 s and 279 s, no resumes, so
neither took the path that had broken). The regression test builds the state through the real
services and fails with the fix taken out.

**Five Luna agents timed out with their work done.** All five goals were integrated and the
tests pass. The agents then announced more than thirty lanes to verify what was already integrated,
most of them cancelled again, and each announcement resumed the other stopped agents, 38 resumes in
thirty minutes. Progress resumes every stopped agent of a provider that holds any open lane, also
one whose own lane has ended, and such an agent looks for work. That weakness was recorded before
(a Luna run of 17 resumes on `remaster26`). The run was repeated on `remaster31` and completed in
519 seconds with 3 resumes. One of two runs on this code against none of two on the builds before:
too few to say whether this branch made it more likely. Not fixed.

**One completed run integrated a failing test.** In the three-agent run the lane that wrote the
command-line interface asserted the wording of an error that the storage lane words differently,
and that wording was in no contract. The work group completed; the project's own suite has one
failure. Synesis does not run a project's combined tests before it integrates. The run repeated on
`remaster31` completed with its tests passing.

**Where test time went, and the gate on Linux.** One test class run alone started 6,187 `git`
processes for 24 tests; starting one costs about 30 to 40 ms on Windows. 2,654 of them asked only
which repository a directory belongs to. That is now read from the two files Git itself reads
(`GitRepositoryLayout`), afresh each time and only for the plain layouts, and Git is not asked about a
directory that is in no repository. A session's branch and commit are asked in one process, and the
ancestor check is skipped when the worktree is still at its base commit. The class went from 531
seconds to 257 and from 6,187 processes to 2,836; the Windows gate went from about 32 minutes to
about 18 with four workers. Twelve workers had not helped (25.5 minutes for the two slow modules):
the cost is starting processes.

On Linux a `git` process starts in about 1.2 ms. The whole gate, run in a container from a clone on
the container's own filesystem, passed in 2 minutes 25 seconds with nothing reused: link 172,
coordination 210, project-record 60, relay 12, mcp-contract 20, workspace 667 (2 skipped), MCP 176,
CLI 120, with the dashboard build and every static gate. This is the first time the suite has run
on Linux. `scripts/linux-gate/run.ps1` does it for the current branch with uncommitted work
(`docs/development/build-and-test.md`). It does not test Windows behaviour, so the Windows gate is
still run before a push. Agents with real models on Linux remain NOT REACHED.

Messages: three in two of these runs, each again the usage document's lane asking for the
command-line syntax; one answered. A Synesis-raised `bound_consumer_absent` request was raised and
closed by itself in the three-Luna run.

Not done: nothing limits an agent without a lane from being resumed for another lane's progress, or
from announcing work the goal did not ask for; the combined tests of a project are not run before
integration; no real run has yet exercised a message waking a stopped agent, a successor
inheriting a conversation, or an agent addressing the operator.

Verification: link 172, coordination 210, project-record 60, relay 12, mcp-contract 20, workspace 658
(1 skipped), MCP 176, CLI 120, 0 failures, with the documentation, format, architecture, dashboard
and hygiene gates, run from PowerShell with four workers; after the two changes to Git lookups the
same gate passed again with workspace 667 (1 skipped), 1,437 tests in all, in 18 minutes 14 seconds.
The test subagent that wrote the regression test: Sonnet 5.5 at
medium effort pinned at launch; it reported its model and could not show its effort from runtime
metadata.

# 2026-10-12 — Step 3 of three: agents can talk to each other, and the review of step 2

Branch `remaster/2026-10-10`, builds `0.1.0-dev.remaster28-20261012` and `remaster29`. ADR-0093 is built. The strict
quality review of step 2 was run and acted on first.

**The review of step 2 (ADR-0092).** One reviewer read the two commits that built the public contract
and ran nothing. It found two paths that left a lane with no lawful next action and six further
defects; each was checked against the code and each was real. ADR-0092 lists them under "The quality
review of the build". The two that mattered most: a binding closed while its consumer was validating
left the validation standing, after which no lane of the project could finish; and a lane with one
dependency already integrated was shown only that note, never a second live binding or its own
consumers. A contract whose owner ended with nothing integrated is now `WITHDRAWN`, not `FROZEN`.

**Conversations (ADR-0093).** One new tool, the eleventh: `send_message`.

- A lane sends text to other lanes, named by a file they claim or by participant handle, or to a
  conversation it belongs to. A conversation is its set of member lanes; Synesis shows it only to
  them. Members are authority lineages, so an agent that continues a lane has its conversations.
- A message binds nothing and is never an instruction. No projection of lane state, no gate and no
  next action reads one. It is not progress either: it resumes the stopped agent it is addressed to,
  once, and nobody else.
- Unread messages arrive beside the next action of `get_next_action`, at least once, ten at a time.
  `collaboration_status` lists the caller's conversations.
- A lane may send fifty messages of 2,000 characters.
- A lane can address the operator. That records an operator request raised by the lane, which blocks
  nothing and is closed by the operator with `synesis operator close`.
- The operator reads every conversation with `synesis conversations` and in two read-only dashboard
  tabs, and cannot post.

Published contract, additive: the MCP catalog gains `send_message` (golden projection and digests
regenerated; provider manuals are reinstalled), `get_next_action` results may carry `messages`,
`collaboration_status` gains `conversations` and `changeRequests`, the CLI gains `conversations` and
`operator close`, the log gains two event types. Changed by the review fixes, on a branch whose
durable state is not migrated (ADR-0088): `CONTRACT_LANE_ENDED` carries whether the lane's work was
integrated, and a contract has a third state.

Real runs on `remaster28`, the same goals and model mixes as on `remaster27`. The bootstrapper said
nothing about messages; agents knew the tool from the manual only.

| Run | remaster27 | remaster28 | Messages sent |
| --- | --- | --- | --- |
| Two agents, SL | 184 s, 1 resume | 170 s, 0 | 0 |
| Two agents, LL | 189 s, 0 | 194 s, 0 | 0 |
| Three agents, SLS | 165 s, 2 | 147 s, 1 | 2 |
| Three agents, LLL | 261 s, 1 | 577 s, 3 | 1 |
| Five agents, SSSSS | 212 s, 9 | 157 s, 11 | 0 |
| Five agents, LLLLL | 623 s, 4 | 422 s, 2 | 1 |
| Five agents, SLSLS | 322 s, 6 | 316 s, 10 | 1 |
| Five agents, SLSLS again | 319 s, 7 | 271 s, 13 | 0 |
| Five agents, LSLSL | 343 s, 3 | 249 s, 0 | 0 |
| Review with the policy, LS | 262 s, 5 | 412 s, 0 | 0 |
| Cancel, SL | 54 s, 0 | 82 s, 0 | 0 |

Eleven of eleven completed on both builds. Summed over the eleven, elapsed time went from 2,934 to
2,997 seconds and resumes from 38 to 40: no difference that one run each can show. The stray-file
cycle ran once more and behaved as before: `NEEDS_OPERATOR` naming the file, `operator recheck`
closing the request once the file was removed, and the run started again completing.

What agents did with the channel: five messages in four of the eleven runs, every first message from
the lane that owned the usage document asking the lane that owned the command-line interface for
its exact syntax. One was answered, with the syntax, twenty-one seconds later. The other three were
not answered and their runs completed. No agent addressed the operator. Reading these back showed
that a finished run named every speaker only as an ended lane; a lane that has ended now keeps the
participant and claims it ended with.

A strict quality review of step 3 followed, as for steps 1 and 2, and was acted on before the
branch was pushed. Authority held. The defects it found are listed in ADR-0093 under "The quality
review of the build"; the two that mattered most were that a message made a finish without changes
stale, the finishing lane's own included, and that a lane's text was printed as written into the
operator's command line, where a line break let it forge a record.

Not done. Tokens per run were not measured, so the cost of the channel is stated in time and
resumes only. With five messages in eleven runs the bounds were never approached, so whether they
hold is not shown. No real run exercised a message waking a stopped agent, a successor inheriting a
conversation, or an agent addressing the operator; those are proven by source-level tests only. The
real runs were made on the build before the review of step 3 was acted on, and were not repeated
after it. From
the review of step 2, both leftovers were dealt with afterwards: the consumer half of the public
contract, which still carried the name of the flow it replaced, is `ContractConsumerService`, and
what only served a request that waits for its owner, is rejected or is cancelled is removed, with
the two event types marked retired.

Verification: link 172, coordination 210, project-record 60, relay 12, mcp-contract 20, workspace 656
(1 skipped), MCP 176, CLI 120, 0 failures, with the documentation, format, architecture, dashboard
and hygiene gates. Run from Git Bash, two CLI help tests fail because the CLI colours its output
there; the gate is run from PowerShell. One gate run died of memory exhaustion in the Java process
and was run again with four workers. Not run on Linux. Test subagents: Sonnet 5.5 at medium effort
pinned at launch; each reported its model, and none could show its effort from runtime metadata.
Link between two machines, macOS, and Linux agents with real models remain NOT REACHED.

# 2026-10-12 — Step 2 of three: one public contract per owned file, and the Codex guard

Branch `remaster/2026-10-10`, builds `0.1.0-dev.remaster25-20261012` to `remaster27`. ADR-0092 was
revised after an independent design review and four decisions of the repository owner, and is built.
ADR-0091's second guard layer is built. ADR-0093 (agent conversations) followed as step 3, above.

**The Codex guard (ADR-0091).** Synesis starts and resumes Codex sessions with
`sandbox_mode=read-only` and `mcp_servers.synesis.default_tools_approval_mode=approve`. A headless
Codex session does not run the project's hooks, so nothing had kept its own file tool or a shell
command out of the project checkout. This replaces a rule the repository stated on purpose, that
Synesis passes no sandbox argument. On `remaster25` a probe was refused its native write through the
real launcher, and five Luna collaborations under the guard completed (LL, LLL, SL, review LL, LLLLL).

**The public contract (ADR-0092).** Built in place: a binding is the request record, created already
agreed, so publication, validation, continuation and every gate are what they were.

- A lane publishes one contract over files it claims; every agent can read it; only the owner's
  lineage revises it. A consumer binds to an exact revision or files a change request that the owner
  must answer, and the owner is shown all open change requests together.
- A new revision makes every binding stale; each consumer binds again and what it validated before
  no longer counts. The owner may publish an unchanged commit again for the new revision.
- A verdict that asks for a revision names the contract field it rests on.
- Cancelling a lane withdraws its bindings in every state. A lane that ends freezes its contracts,
  declines the change requests open against them and closes the bindings it can no longer serve.
- A consumer that arrives after the owner was integrated binds to the frozen contract and is let
  through, whatever other lanes are open.
- An owner waits while a bound consumer's lane is open. A consumer that owes an action and has made
  no verified call for the stated time is put to the operator (`bound_consumer_absent`). The
  two-minute rule that let an owner finish past such a consumer is gone.

Published contract broken, with the owner's approval: `request_coordination` loses
`capability_request`, `contract_proposal`, `contract_request` and gains `contract_publish`,
`contract_bind`, `contract_change_request`; `respond_coordination` loses `capability_response` and
gains `contract_change_response`; `implementation_validation` gains `contractField`. The golden
projection and its digests changed. The CLI loses `collaboration contract publish` and `bind`, which
wrote to a store nothing read. Durable state of an earlier build is not migrated (ADR-0088). Three
event types are added and one is retired.

Real runs. The projects' coordination policy was reworded for the new flow ("bind to the public
contract its owner publishes", where it said "capability request"); the bootstrapper still assigned
and relayed nothing.

| Goal | remaster22 (before) | remaster26 | remaster27 |
| --- | --- | --- | --- |
| Two agents, negotiated (SL, LL, LS, SS) | 3 of 4 | 4 of 4 | 2 of 2 |
| Three agents (LLL, SLS) | 2 of 2 | 2 of 2 | 2 of 2 |
| Five agents, one provider | 3 of 4 | 3 of 3 | 2 of 2 |
| Five agents, mixed | 2 of 2 | 1 of 2 | 3 of 3 |
| Review with the policy | 2 of 2 | 2 of 2 | 1 of 1 |
| Cancel | 1 of 1 | 1 of 1 | 1 of 1 |
| Stray file: `NEEDS_OPERATOR`, recheck, run again completes | n/a | not run | 1 of 1 |

In every completed run with a dependency one contract was published, and no run raised an operator
request it should not have. Five agents of one provider took 179 to 305 seconds on `remaster26`
where they had taken 269 to 710 or stalled; one Luna run took 659 seconds with 17 resumes.

The one failure on `remaster26` was the code not doing what the record said. With a public contract
the owner no longer waits for consumers before it finishes, so a consumer arrives after the owner's
work is in; it was told that no lane claims the file yet. Fixed in `remaster27`, together with the
absent-consumer rule.

What the reviews found. Four reviewers on four models read the first version of ADR-0092 and agreed
on the same gaps (a gone consumer holds the owner, the exit "cancel" was refused by a gate, validation
tied to the commit and not the contract revision, rules for a released file that cannot occur,
enforcement on replay that the log cannot do); the record was rewritten before anything was built. A
strict quality review of step 1 found three forms of one rule and an error path that did not block;
both are fixed. The test subagents found two defects in the new code: a re-bound consumer was skipped
when an unchanged commit was published again, and an owner was told of the operator request one poll
late.

Not done: a strict quality review of step 2; operator requests in the web UI; a unit test of
`PeerDependencyPolicy.satisfiedByIntegratedOwner` (covered through the service and MCP flows); removal
of code the change left without a producer (the states for a request awaiting its owner and for a
rejected one, the finish-time supersede of an unanswered proposal). Every recorded event still
resumes every stopped agent that holds a lane; with several consumers binding again that cost is
not measured.

Verification: link 172, coordination 200, project-record 60, relay 12, mcp-contract 20, workspace 615
(1 skipped), MCP 171, CLI 114, 0 failures, with the documentation and hygiene gates. `:cli:test` twice
failed to start with "Unable to create isolated test state" and passed with an explicit test-state
directory; it is an environment fault of the test task, not a test failure. Not run on Linux. Test
subagents: Sonnet 5.5, medium effort pinned at launch; each reported its model, none could report its
effort. Link between two machines, macOS, and Linux agents with real models remain NOT REACHED.

# 2026-10-12 — Step 1 of three: operator requests, failure points, and what the Codex guard really was

Branch `remaster/2026-10-10`, builds `0.1.0-dev.remaster23-20261012` and `remaster24`. Decisions of
the repository owner recorded as ADR-0091 (this step, built), ADR-0092 (one public contract per owned
file) and ADR-0093 (agent conversations), both accepted and not built.

Built, ADR-0091:

- A project checkout holding a file Synesis did not make raises an **operator request** that names
  the files as `git status` reports them. It closes when the checkout is observed clean. Synesis does
  not touch the files.
- A refusal by the checkout, or by a control head that moved under an attempt, no longer fails the
  work. Before, the snapshots became `INTEGRATION_FAILED` and were never tried again, even after a
  person had cleaned the checkout.
- Only a lane that waits on integration is answered `blocked` / `operator_required` and told to stop.
  A lane with work of its own keeps working and publishing.
- A run in which every agent has stopped with a request open ends `NEEDS_OPERATOR`. An agent whose
  published work waits for an operator is not resumed.
- `synesis operator list` and `synesis operator recheck`. The second looks again and integrates what
  was waiting, with no agent running. No command declares a request resolved.
- An internal failure of session binding, lane finish, cancel or announcement, capability publication
  or validation, or integration records its cause and stack in `.synesis/local/diagnostics`, and the
  answer names the entry. Every Codex hook invocation is recorded.

Semantic changes: `finish_lane` on a dirty control checkout answers `blocked` / `operator_required`
where it answered `waiting` / `integration_pending` (`Slice4FailureScenariosTest` updated); an attempt
refused for a moved control head is recorded `pending`, not `failed`; `AGENT_RUN_RESULT` has one more
value; two event types and one projection are added to the coordination log. The MCP tool catalog is
unchanged.

Real runs. In the two marked "stray" the bootstrapper put a file into the project checkout 25
seconds after the agents started, as an injected failure, later removed it and ran
`synesis operator recheck`, and then started the same agents again. It assigned and relayed nothing.

| Goal | remaster23 | remaster24 |
| --- | --- | --- |
| Stray file, two agents (SL, LS): ends `NEEDS_OPERATOR` naming the file | 1 of 1, after 11 resumes and 472 s | 2 of 2, after 1 resume and 88 to 115 s |
| Recheck after the file is removed: request closes, waiting snapshot integrated | 1 of 1 | 2 of 2 |
| Run started again: `COMPLETED`, tests pass | not run | 2 of 2 |
| Five agents, mixed | not run | 1 of 1 |
| Negotiated, two agents (SL) | not run | 1 of 1 |
| Review with the policy (LS) | not run | 1 of 1 |
| Three agents (LLL) | not run | 1 of 1 |

On `remaster23` the blocked owner was resumed eleven times: each resumed process is a new session,
was offered its own lane to continue, published the same work again and was refused again. Fixed in
`remaster24` in the run controller. A resumed session is still offered that continuation if something
else resumes it; with two agents of one provider, one blocked and one not, both are resumed.

What the guard was. With hook invocations recorded, the stall that started this was reproduced on
Codex CLI 0.162.0: a headless Codex session does not run the project's hooks at all. They need a
person's review, and `provider status` had reported `NATIVE_MUTATION_INTERCEPTION=UNPROVEN` all along.
No Codex session in any recorded run was guarded. Measured: the read-only sandbox blocks the native
write and a writing shell command; alone it also blocks the Synesis tools; with the Synesis server's
tools approved they work. That launch policy is recorded in ADR-0091 and NOT built: a test and the
command reference state on purpose that Synesis passes no sandbox argument. Not tried on macOS or
Linux.

Design review of ADR-0092. Four reviewers on four models, read-only, one brief. At least three of the
four independently found the same gaps: a consumer that is gone holds the owner for ever; the exit
"cancel your lane" is refused by a gate the record keeps; validation is tied to the commit and not
to the contract revision; a lane cannot give up one file; a request that arrives as the owner
finishes has no exit; the invariants cannot be enforced on replay as the log is built. The record
now says it is not to be built as written and lists what must be settled, three points of which are
the owner's to decide. Step 2 has not started.

Not done in step 1: operator requests are raised for one condition, the foreign file. An integration
conflict was to be the second; agents already repair conflicts themselves, so raising a request for
each would end runs that can continue, and it was left out. A `failed` answer from a path not listed
above carries no failure point. The web UI does not show operator requests.

Verification: `check repositoryHygieneCheck` on Windows on the final tree, link 172, coordination 196,
project-record 60, relay 12, mcp-contract 17, workspace 604 (1 skipped), MCP 165, CLI 114, 0
failures. Not run on Linux. No test subagent was used; the design reviewers followed the pstack model
sheet. Link between two machines, macOS, and Linux agents with real models remain NOT REACHED.

# 2026-10-11 — A capability request names a file: the lane claiming it is the owner

Branch `remaster/2026-10-10`, builds `0.1.0-dev.remaster21-20261011` and `remaster22`. Real runs as in
the entries below, Claude Code `claude-sonnet-5-5` medium (S) and Codex `gpt-6-luna` high (L).

Decision made by the repository owner and recorded: ADR-0090. A declaration by the owner lane
("provides") was what had been proposed; it was not built, because the claim on a file already says
whose it is and needs no new state. The MCP tool catalog changes additively: `capability_request`
gains the optional `ownerPath`, and the pinned golden projection and digests change with it.

- A capability request names the repository file that implements the capability. The open lane of
  another participant that claims that file is the owner. A lineage sent with it does not overrule
  the claim, and a file no other lane claims leaves the request unrecorded.
- A request whose owner cannot be told, and a declared dependency not yet requested, are answered
  with the open lanes, their goals and claims, and what to send.
- A recorded request answers with the goal and claims of the lane that received it.
- A refused claim no longer tells the caller to request a capability from the lane it collided with.
  The manual said so, and that sentence was the cause of the misaddressed requests.

| Goal | remaster20 | remaster21 and 22 |
| --- | --- | --- |
| Five agents, one provider (SSSSS, LLLLL) | 0 of 2 | 3 of 4 |
| Five agents, mixed | 2 of 2 | 2 of 2 |
| Three agents (LLL, SLS) | 0 of 1 | 2 of 2 |
| Negotiated, two agents (SL, LS, SS, LL) | 4 of 4 | 3 of 4 |
| Review with the policy (LS, SL) | 4 of 4 | 2 of 2 |
| Cancel | 1 of 1 | 1 of 1 |

In every run of both builds each capability request named `ownerPath` on the first attempt and was
recorded against the lane claiming that file. The two runs that did not complete, both Luna only,
have other causes:

- **Two accepted contracts for one capability contradicted each other.** In the five-agent run the
  storage owner agreed with one requester that an unknown task id raises `FileNotFoundError` and
  that listing creates a missing store, and with another that `FileNotFoundError` is for filesystem
  failures only and listing has no side effects. One implementation is published to both (ADR-0089),
  so each revision failed one validator. The owner said so and stopped at the polling limit. Nothing
  stops an owner from accepting a second, different contract for a capability it already agreed.
- **An agent wrote into the control checkout with its provider's own file tool.** In the two-agent
  run a Codex `file_change` added the two command-line files to the project checkout, although the
  project's Codex `PreToolUse` hook for `apply_patch` was installed. Integration refuses a control
  checkout with changes Synesis did not make, which is intended. Neither agent was told why: the
  owner's snapshot showed `INTEGRATION_FAILED` and its next action was an ordinary `IMPLEMENT`. One
  occurrence in 361 Codex sessions; why the hook did not route or deny the patch is not established.

Also seen once, not diagnosed: `ensure_session` answered `internal_failure` with
`SESSION_BINDING_FAILED` while a workspace was being refreshed, and succeeded when the agent called
again. The cause is not logged.

The Luna agents are not fully blind. Each read `~/.codex/memories/MEMORY.md`, where Codex keeps notes
from this user's earlier Synesis runs, including a required lifecycle. The bootstrapper did not put
them there and did not remove them.

Still open from the entry below: a reviewer with a stale workspace is told `ensure_session` against
the manual, and a requester that validated and stopped is needed again after an owner revision.

Verification: `check repositoryHygieneCheck` on Windows, link 172, coordination 192, project-record
60, relay 12, mcp-contract 17, workspace 600 (1 skipped), MCP 163, CLI 113, 0 failures; after it one
conditional was rewritten and one assertion added, and the workspace and MCP suites were run again.
Not run on Linux. No test subagent was used. A single-reviewer quality pass was made in place of the
multi-agent review. Link between two machines, macOS, and Linux agents with real models remain NOT
REACHED.

# 2026-10-11 — Reviewer admission, project review policy, shared publication: the four remaining causes closed

Branch `remaster/2026-10-10`, builds `0.1.0-dev.remaster17-20261011` to `remaster19`. Real runs as in
the entry below, with Claude Code `claude-sonnet-5-5` medium (S) and Codex `gpt-6-luna` high (L). In
the review runs marked "policy" the bootstrapper also set `"review": {"mandatory": true}` in
`.synesis/project.json`, as it sets a validation command; it still assigned nothing and relayed
nothing.

Decisions made by the repository owner and recorded: ADR-0046 amended, ADR-0089 new.

- A reviewer lane is admitted only when its work group has a producer lane to review. Otherwise the
  announcement is refused with `REVIEWER_REQUIRES_PRODUCER`, nothing is recorded, and the session can
  still announce the implementation. Six tests that admitted a reviewer first now assert the refusal
  and then the same review direction.
- A project can require review: every work group an announcement creates in a project with
  `review.mandatory` gets the mandatory independent review policy. Persisted-format change under
  ADR-0088.
- A capability implementation is published to every request of the owner's lane with an accepted
  contract. A request validated or published at an older commit receives the new revision and is
  validated again. A request still waiting for its implementation outranks the owner's wait for
  another requester's verdict.
- Requests never answered are superseded where their owner's lane is released, under the same lock.
  This was the cause of the three stalls left undiagnosed in the entry below: the request was filed
  about a second before a fast owner finished.
- A caller that holds no lane is offered the continuation of its lane ahead of any project-wide wait.
  Found by the first policy run: the resumed implementer was shown "review pending" and never took
  its lane back, so it never admitted the reviewer. No dedicated test.
- A run in which every lane was cancelled ends `ALL_WORK_CANCELLED`, not `COMPLETED`.
- A refused capability contract names the missing, unknown or wrongly typed field, and one sentence
  where a list is expected is read as a list of one. An agent had sent nine contracts in a row with
  two fields as strings and the run ended at the resume limit.

| Goal | remaster17 | remaster18 | remaster20 |
| --- | --- | --- | --- |
| Negotiated, two agents (SS, LL, SL, LS) | 3 of 7 | 7 of 7 | 4 of 4 |
| Cancel | 1 of 1 | 1 of 1 | 1 of 1 |
| Three agents | 2 of 2 mixed | 2 of 2 mixed | 0 of 1, Luna only, not diagnosed |
| Five agents, mixed | 2 of 2 | 1 of 2 | 2 of 2 |
| Five agents, one provider | not run | not run | 0 of 2 |
| Review with the policy (SL, LS, SS, LL) | 4 of 5 | 5 of 5 | 4 of 4 |
| Review without the policy | 1 of 1 | 1 of 1, integrated unreviewed | not run |

`remaster19` was run while the repository check used the machine: 5 of 11, and its stalls showed
two more defects, both fixed in `remaster20`. A projected finish was refused as stale whenever
another agent recorded anything but a heartbeat; an owner was refused twelve times in a row. And a
lane continued by its resumed agent published a second snapshot while its first still waited on a
dependency; the first was integrated, the second then conflicted with its own lane, and the work
group stayed open with all work integrated. Only a lane's latest revision is now integrated.

The four negotiated stalls and the review timeout on `remaster17` are the request-at-release and the
continuation-behind-a-wait defects, fixed in `remaster18`. The five-agent run that failed on
`remaster18` is the contract defect, fixed in `remaster19`. In two policy runs the agents' logs show
both a `REJECTED` and an `ACCEPTED` review decision and one integrated snapshot, the corrected one.
Without the policy a fast producer is still integrated before a reviewer arrives; the late reviewer
is refused at admission and the run ends `COMPLETED` unreviewed. That is what the policy is for.

Still open, in order of how often it fails a run:

- A capability has no recorded owner. A requester names the lane it asks, and after an
  `overlapping_claim` refusal agents reuse the lineage of the lane they collided with. Both
  five-agent runs with one provider ended this way: the storage lane had already been integrated,
  the requests for `task_store.api` went to a documentation lane, which accepted or never answered,
  and the requesters waited on it. Letting a lane declare what it provides is a tool-schema change
  and was not made.
- A reviewer whose workspace went stale when the corrected snapshot was integrated is answered
  `workspace_stale` with `ensure_session`, and the manual forbids rebinding a stale review lane. One
  reviewer stopped there with the review complete and its own lane open.
- A requester that validated and stopped is needed again when the owner revises for another
  requester, and comes back only if the controller resumes it.

Verification: `check repositoryHygieneCheck` on Windows on the final tree, link 172, coordination 192,
project-record 60, relay 12, mcp-contract 17, workspace 599 (1 skipped), MCP 163, CLI 113, 0 failures.
Not run on Linux. Test subagents: Sonnet 5.5 at medium effort, pinned at launch; the harness exposed no
effective runtime metadata. Link between two machines,
macOS, and Linux agents with real models remain NOT REACHED.

# 2026-10-11 — Sonnet 5.5 medium with Luna 6 high: refusals that explain themselves, and what still stalls

Branch `remaster/2026-10-10`, builds `0.1.0-dev.remaster12-20261011` to `remaster16`. Real runs in new
disposable projects under `Documents\SynesisRemasterAcceptance`, started by `synesis agents run`. The
bootstrapper created each repository and goal, ran `synesis init` and `synesis provider install`,
started the controller and validated; it assigned nothing and relayed nothing. From `remaster14` on,
Claude Code `claude-sonnet-5-5` medium and Codex `gpt-6-luna` high; before that, Claude Code
`claude-opus-5-5` high and Codex `gpt-6-luna` medium.

Defects found by these runs and fixed, each with a test unless stated:

- An installed Synesis on Linux could start no child process: the installer restored the executable
  bit on four files and the runtime needs `lib/jspawnhelper` and `bin/keytool`. `synesis init` answered
  `GIT_REQUIRED` and `synesis host` `CONNECTION_FAILED`. On master too.
- The controller's record that an agent's process ended was dropped when the project lock stayed busy
  for two seconds. The lane looked held by a live session for eleven minutes and the agent resumed to
  continue it was offered nothing.
- A patch edit without `expectedOccurrences` was dropped and the patch refused as an invalid path.
- A session holding a lane by continuation could not extend it, name it again, or release it: the lane
  was looked up only by the identifier derived from the session.
- What a stopped agent is taken to have seen is now the earlier of its last recorded call and the log
  as it stood 30 seconds before its process ended. Either bound alone left an agent unresumed.
- A session with no lane, while others worked, was answered with `ensure_session` and empty arguments
  as the only permitted operation, and another participant's lane as its context.
- Every refusal now carries an instruction. A malformed argument keeps the parser's finding as
  `detail`; a refused payload names the accepted kinds, fields, contract fields or status values; a
  patch whose text did not match names the edit and both counts. Input that could not be read (an edit
  that was not an object, a claim without a path, a work group identifier that was not a UUID) was
  dropped and the call answered as a success; each is refused by name. Refusal codes and next actions
  are unchanged.
- An answer that depends on a peer allowed three unchanged polls, fifteen seconds, and a resumed agent
  takes about half a minute to reach Synesis. Agents woke each other in turn; a review ran sixteen
  resumes. Such answers now allow twelve. A recovery action keeps three.
- One lane's snapshot waiting on a dependency made the integration pump answer `integration_pending`
  to every caller ahead of that caller's own capability step. No dedicated test.
- The reason a reviewer gives with a rejection was recorded and never shown to the implementer, and an
  implementer that had admitted the next review request was answered as if implementing from the start.
- An active reviewer lane declared for exactly one producer lane makes that producer subject to review
  before any request or grant exists. A reviewer suspended or held for recovery does not.
- A waiting reviewer is told when its work group has no producer lane and may cancel.
- `synesis join` of an invitation created by the same identity is refused with `OWN_INVITATION`.
- Replacing a launcher within a moment of its process starting failed on Windows; the move is retried.

Runs with Sonnet 5.5 medium (S) and Luna 6 high (L), per build. A run is counted complete when
the controller reported `COMPLETED` and the project's tests passed; two runs that reported it without
meeting the goal are counted as failures. For the review goal only some logs were read, so a
completed review run is not proof that the rejection and correction both took place.

| Goal | remaster14 | remaster15 | remaster16 |
| --- | --- | --- | --- |
| Negotiated, two agents (SS, LL, SL, LS) | 8 of 8 | 5 of 6 | 5 of 6 |
| Cancel | 2 of 2 | 2 of 2 | 2 of 2 |
| Three agents | 2 of 2 | 2 of 2 | 0 of 1 |
| Five agents | 0 of 1 | 1 of 2 | 2 of 3, one of them five Sonnet agents |
| Review that rejects the first snapshot | 2 of 4 | 1 of 5 | 4 of 6 |

What still fails, by cause:

- A producer that finishes before any reviewer has announced is integrated unreviewed, and the late
  reviewer has nothing to review. A work group requires review only when it was created with a
  mandatory review policy; a project cannot state it. `.synesis/project.json` is read strictly, so a
  project-level policy is a persisted-format decision and was not made here.
- Two agents that both announce the reviewer role wait on each other. Told there is no producer, both
  cancelled, and neither could then announce the implementation: a session holds one lane for its
  life and a cancelled session is fenced (`workspace_generation_changed`). The run ended `COMPLETED`
  with every lane cancelled and nothing built. Refusing a reviewer lane with no producer at admission
  would leave the session free; it contradicts ADR-0046, whose tests admit a reviewer first, and was
  prepared and withdrawn pending a decision.
- With several requesters of one capability the owner publishes per request, each gated on the
  previous requester's validation. One requester that stops holds the rest.
- A run in which every lane was cancelled reports `COMPLETED`.

Link: `synesis host` on the Linux install creates its listener and invitation. A join between Windows
and the Linux container was NOT REACHED: plain UDP did not pass between them in either direction.
macOS: NOT REACHED. Linux agents with real models: NOT REACHED.

Verification: `check repositoryHygieneCheck` on Windows, link 172, coordination 190, project-record
60, relay 12, mcp-contract 17, workspace 596 (1 skipped), MCP 158, CLI 113, 0 failures. Linux container
`check` on commit `743df498`, 0 failures; the commits after it were not run on Linux.

# 2026-10-11 — Everything recorded as wrong or unproven on the remaster branch, fixed or run

Branch `remaster/2026-10-10`, builds `0.1.0-dev.remaster9-20261011` to `remaster11`. Real runs in new
disposable projects under `Documents\SynesisRemasterAcceptance`, started by `synesis agents run`. The
bootstrapper created each repository and goal, ran `synesis init` and `synesis provider install`,
started the controller, injected process failures where stated, set a validation command as project
policy in one run, and validated; it assigned nothing and relayed nothing. Claude Code
`claude-opus-5-5` high, Codex `gpt-6-luna` medium.

Defects found by these runs and fixed, each with a test that fails without the fix:

- Two processes that read the same log head both appended, and the later durable move replaced the
  earlier writer's event; the hash chain stayed valid. A lane announcement was lost this way:
  `ensure_session` answered `ready` and the lane was never in the log. On master too. The durable name
  is now given by a hard link, which refuses an existing name, and the append is made again on the
  new head. This is the `ensure_session` anomaly of the entry below.
- An owner that changed a validated file and then finished left its requester's snapshot
  `INTEGRATION_PENDING` for good. The finish is refused with `VALIDATED_IMPLEMENTATION_CHANGED`,
  naming the files, and a validated owner is told beforehand not to change them.
- An owner that committed after publishing could not finish with identical content
  (`SNAPSHOT_REF_CONTENT_MISMATCH`): reuse of a snapshot ref compared its parent commit as well as its
  tree.
- An owner waited for the requester's tests before publishing while the requester waited for the
  publication. The owner's answer now says its requester is waiting and to publish without the
  requester's files (blocker `requester_waiting_for_implementation`).
- A work group created ahead of its lanes that never received one stayed `ACTIVE`, and no agent can
  close a group. A group that never had a lane is not open work (`WorkIntentService.hasOpenWork`),
  for the controller and for agents.
- A session with no lane, in a project with no open work, was told `coordination_intent_required`. It
  is told `completed`, state `NO_OPEN_WORK`, and may still announce.
- A coordination request refused for a field its kind does not take now lists the fields it takes.

| Run | Agents | Result |
| --- | --- | --- |
| Three-part goal | Codex, Claude Code, Codex | `COMPLETED`, 297 s, 4 resumes, 13 tests pass; three lanes integrated and a fourth, self-announced, cancelled |
| Negotiated, three times | Codex pair | one `STALLED` on the validated-file defect before its fix; two `COMPLETED` after |
| Negotiated | Claude Code then Codex | `COMPLETED` |
| Cancel | Codex pair | `COMPLETED` |
| Negotiated with `python -m unittest` as the project's validation command | Claude Code, Codex | `COMPLETED`; both finishes carry the validation evidence |
| Review: one agent implements, one reviews | Claude Code pair, twice | the lane stayed `REVIEW_PENDING` until the reviewer decided, then integrated; first run `STALLED` on the empty-group defect, second `COMPLETED` |
| Codex process tree killed 80 s in | Codex, Claude Code | exit code 1 recorded, Codex resumed and continued its lane, `COMPLETED`, 16 tests pass |
| Controller and both agents killed 85 s in, fresh agents 335 s later | Codex, Claude Code | both abandoned lanes continued by the new sessions, `COMPLETED`, 17 tests pass |
| Owner's bounded wait | Codex, Claude Code | the owner proposed its contract, was answered `requester_response_pending` with a deadline, finished after it; Codex returned, was told `ownerProceededWithoutAnswer`, `COMPLETED`, 13 tests pass |
| `synesis host` and `synesis join`, two identities on one machine | none | descriptors exchanged, path selected, both identities verified, `CONTROL_READY`, `LIVENESS=LIVE`, both exit 0 |

The controller's 30-second rule of the entry below has now been observed: in 13 runs since it was
added an agent was resumed for work recorded 3 to 30 seconds before its process ended.

A project can gate what a lane publishes on tests: `validation.argv` in `.synesis/project.json`
(ADR-0042). The earlier statement that nothing gates integration on tests described projects that
had not set it. A failing validation command refusing a finish is now covered by a test.

Not changed: `synesis join` of an invitation created under the same identity fails with
`CONNECTION_FAILED`, as it does on the previous build.

NOT REACHED: more than three agents, review with a rejected lane, a join between two machines, and
macOS or Linux agents.

Verification: `check`, link 171, coordination 190, project-record 60, relay 12, mcp-contract 17,
workspace 593 (1 skipped), MCP 155, CLI 113, 0 failures.

# 2026-10-11 — Lane cancellation made to work for real agents: five defects closed

Branch `remaster/2026-10-10`, builds `0.1.0-dev.remaster4-20261010` to `remaster8`. Fourteen further
runs on the cancel goal of the entry below, almost all two Codex agents (`gpt-6-luna`, medium), each
in a new disposable project under `Documents\SynesisRemasterAcceptance`. Each defect was found by a
run, fixed with a test that fails without the fix, and run again.

1. Cancelling a lane did not re-evaluate its work group. A group whose last open lane was cancelled
   stayed `ACTIVE`, so the project could not complete, and another agent announced the cancelled
   lane again. Cancellation now closes a settled group: `COMPLETED` when a lane of it produced a
   snapshot or a no-change completion, `CANCELLED` when every lane was cancelled. The cancelled
   lane's session is answered `completed`, state `CANCELLED`, workflow `CLOSE`.
2. A lane that asked to finish with nothing changed was told only `IMPLEMENT`. The answer now adds
   `nothingToPublish`, the blocker `nothing_to_publish`, `cancel_lane` among the permitted
   operations, and an instruction.
3. `cancel_lane` failed with `internal_failure` for a lane that had filed a capability request: the
   request's cancellation was recorded as bare handle bytes the log cannot decode, after the task
   had been recorded as cancelled. On master too. Requests no owner accepted are now cancelled in
   the readable form; a lane whose request an owner accepted is refused with
   `capability_request_open` and records nothing.
4. Every open of the event store deleted all temporary event files without a lock, so a reader could
   delete or trip over the file another process was appending through. One run ended
   `AGENT_RUN_FAILED` on it. On master too. Only temporary files older than a minute are removed now.
5. `cancel_lane` could report success with the lane still open: it closed the lane through a second
   session lookup and discarded the failure. It now closes the lane of the session it admitted and
   a failure fails the call. Why that lookup rejected a session holding its lane by continuation was
   not established; the lookup is gone, not repaired.

| Build | Runs | Result |
| --- | --- | --- |
| remaster4 (fix 1) | Codex pair twice, Claude Code pair (`sonnet`, low) once | 2 `COMPLETED`; 1 `STALLED` on defect 2 |
| remaster5 (fixes 1, 2) | Codex pair three times | 3 `COMPLETED` |
| remaster6 (fix 2 reshaped) | Codex pair twice; Codex with Claude Code, negotiated goal, once | negotiated `COMPLETED`; 1 `AGENT_RUN_FAILED` on defect 4; 1 `RESUME_LIMIT_REACHED` after 16 resumes on defect 3 |
| remaster7 (fixes 1 to 4) | Codex pair three times | 2 `COMPLETED`; 1 `STALLED` on defect 5 |
| remaster8 (fixes 1 to 5) | Codex pair four times | 3 `COMPLETED`, two of them cancelling a lane held by continuation; 1 `STALLED` without any cancellation |

The last stall is the agents' own: they split the goal into the storage module and its tests, the
owner would not publish until the tests existed, and the tests could not run until it published.
Synesis answered the owner `implementationPending` with no blocker. Not changed.

Still as recorded below and not changed: a resumed agent whose own lane had finished is told
`coordination_intent_required` in its new session; and one `ensure_session` carrying a task answered
`ready` without establishing the intent, where the agent's next attempt succeeded.

Verification: `:coordination:check :workspace:check :mcp:check :cli:check`, coordination 188,
workspace 592 (1 skipped), MCP 152, CLI 113, 0 failures.

# 2026-10-10 — Remaster branch run by real agents: one regression fixed, one controller race closed

Branch `remaster/2026-10-10`. Builds `0.1.0-dev.remaster-20261010`, `remaster2` (command record
fix), and `remaster3` (controller change), each installed with the reorganised bootstrap installer.
Every run used a new disposable project under `Documents\SynesisRemasterAcceptance`, started by
`synesis agents run` with the prompt and goals of the entries below. The bootstrapper created the
repository and goal, ran `synesis init` and `synesis provider install`, started the controller, and
validated; it assigned nothing and relayed nothing. Claude Code `claude-opus-5-5` high and Codex
`gpt-6-luna` medium unless stated.

Regression found by run 1 and fixed: the remaster changed the canonical encoding of durable command
records and left `canonicalizationVersion` at 1. Records live in the user's Synesis directory and
the namespace reads all of them before running a command, so the 94 process anchors of the earlier
build blocked `run_command` for every agent with `COMMAND_INTEGRITY_MISMATCH`. The encoding is now
version 2 and version 1 records are verified by the version 1 rule.

Controller race found by runs 5 and 8 and closed: an agent that stopped was taken to have seen the
log up to the moment its process ended. Codex's last poll came 7 and 13 seconds before its process
ended, the owner's progress landed in between, and Codex was never resumed. An agent is now taken to
have last looked 30 seconds before its process ended, and never earlier than when that process
started. An agent that stops within 30 seconds of any progress, its own included, gets one extra
turn. The earlier build was not run in this start order, so whether it stalls there is not known;
the controller's rule was the same.

| Run | Build | Agents | Goal | Result | Control branch |
| --- | --- | --- | --- | --- | --- |
| 1 | remaster | Claude Code, Claude Code | negotiated | `COMPLETED`, 390 s, 2 resumes | 16 tests pass; no command ran |
| 2 | remaster2 | Claude Code, Claude Code | negotiated | `COMPLETED`, 209 s | 21 tests pass |
| 3 | remaster2 | Codex, Codex | negotiated | `COMPLETED`, 199 s | 6 tests pass |
| 4 | remaster2 | Codex, Claude Code | negotiated | `COMPLETED`, 236 s, 2 resumes | 13 tests pass |
| 5 | remaster2 | Claude Code, Codex | negotiated | `STALLED` | owner's lane only, 7 tests pass |
| 6 | remaster2 | Claude Code, Claude Code (`sonnet`, low) | cancel | `COMPLETED`, 42 s, 1 resume | 4 tests pass |
| 7 | remaster2 | Codex, Codex | cancel | `STALLED` | 5 tests pass |
| 8 | remaster2 | Claude Code, Codex | negotiated | `STALLED` | nothing integrated |
| 9 | remaster3 | Claude Code, Codex | negotiated | `COMPLETED`, 203 s | 11 tests pass |
| 10 | remaster3 | Claude Code, Codex | negotiated | `COMPLETED`, 305 s | 18 tests pass |
| 11 | remaster3 | Claude Code, Codex | negotiated | `COMPLETED`, 210 s | 12 tests pass |

Reached through the MCP surface by both providers on these builds: `ensure_session`, an
`overlapping_claim` refusal, `needs_capability` / `owner_required`, a capability request, the
owner's answer, a revision, `publish_capability_implementation`, validation through
`respond_coordination`, stale-workspace refresh, `finish_lane`, integration, and work group
completion. Also reached: controller resumes and lane continuation by each provider (runs 1 and 4),
and `cancel_lane` by each provider (runs 6 and 7).

NOT REACHED: the controller change in a real run. Runs 9 to 11 completed without any agent stopping
early, so the new rule never applied; it rests on `AgentRunControllerTest`. Also not reached:
participant loss by a killed process, review, the owner's bounded wait, and a join over Link.

Observed and not changed:

- Run 7: after `cancel_lane` the Codex agent announced the same lane again and left it open, which
  ended the run `STALLED` with the work itself integrated. `get_next_action` answers a cancelled lane
  with `coordination_intent_required`, as recorded below.
- Run 5, continued by one new Codex agent: the pending snapshot was integrated when that agent
  connected and the work group completed, but the control branch then had 1 failing test of 11, a
  defect in the Codex agent's own code. Nothing gates integration on tests. The new agent then
  announced a lane of its own to repair it, sent `scope_revision` with fields that kind does not
  take, was refused `COORDINATION_FIELD_NOT_ALLOWED` without being told which fields are allowed,
  and stopped with that lane open.
- `synesis provider install codex` reports `DEGRADED` for `TRUST_STATUS=REVIEW_REQUIRED`.
- `SynesisCliParsingTest.rootAndVersionHelpSucceed` and
  `DistributionLauncherTest.generatedLauncherSupportsHelpVersionAndIdentity` fail when Gradle is
  started from Git Bash, where the help output carries colour codes; they pass from PowerShell.

Verification: `:workspace:check :mcp:check :cli:check`, workspace 592 (1 skipped), MCP 148, CLI 113,
0 failures.

# 2026-10-10 — Owner timeout, same-provider agents, and cancel_lane proven by real runs

Uncommitted. Build `0.1.0-dev.owner-went-ahead-20261010`: the builds below plus one fix found here.
Disposable project `Documents\TestingSynesisOnClaude`, reset before each test. The bootstrapper wrote
the goal, started and stopped controller runs, set one environment variable, and observed; it
assigned nothing and relayed nothing.

Fix: a request its owner superseded by finishing now follows the requester's lane to the session that
continues it (`CapabilityRequestProjection.processLaneContinuation`). Before, it stayed with the
predecessor, so a requester that came back after the owner had finished was told only that its
dependency was integrated. Found by the first pass of test 1.

Verification: `:coordination:check :link:check :workspace:check :mcp:check :cli:check`, coordination
116, link 111, workspace 508 (1 skipped), MCP 125, CLI 85, 0 failures.

Test 1, the owner's bounded wait. The goal carried one extra policy line: the owner answers a
`task_store.api` request with its own contract as a revision. The requester was kept away by running
phase A with `--max-resumes 0` and then starting only the owner's provider (phase B), then only the
requester's (phase C). Codex `gpt-6-luna` medium, Claude Code `claude-opus-5-5` high.

- Pass 1, wait of 420 s, previous build. Claude Code, the owner, proposed its contract at 03:23:17,
  asked to finish, and was told `waiting` / `requester_response_pending` with the deadline. In phase B
  a new Claude Code process continued the owner's lane, was told the same, and stopped at 03:26:36.
  Nothing was recorded after that. The controller kept the run open and resumed the agent at
  03:30:17, the deadline to the second. It continued the lane, finished at 03:30:46, the request was
  recorded `SUPERSEDED` / `OWNER_FINISHED_WITHOUT_REQUESTER_ANSWER`, and the lane was integrated. In
  phase C Codex continued the requester's lane, built on the integrated module, and the work group
  completed (34 tests pass). It was NOT told the owner had gone ahead: the defect fixed above.
- Pass 2, wait of 300 s, this build. The owner proposed at 03:39:59. In phase B the owner's agent
  waited inside its turn, finished at 03:45:12 after the 03:44:59 deadline, and the request was
  superseded. In phase C Codex continued the requester's lane and its inbox carried
  `ownerProceededWithoutAnswer` with the owner's proposed contract and the guidance text. Work group
  `COMPLETED`, 28 tests pass.

Test 2a, two agents of one provider: two Claude Code agents (`claude-opus-5-5`, high) on the unchanged
negotiated goal. `COMPLETED` in 519 s with one resume. Request, acceptance, publication, validation;
the owner stopped after publishing and, resumed, continued its own lane, not its peer's, which was
never suspended. Both lanes integrated, 56 tests pass.

Test 2b, `cancel_lane`: two Claude Code agents (`sonnet`, low) on a goal with two parts, one of which
(`docs/USAGE.md`) already met its acceptance; the goal said to cancel such a lane with a reason. One
agent announced a lane on that file, read it, and called `cancel_lane` with a reason:
`TASK_CANCELLATION_REQUESTED`, `TASK_CANCELLED`, `DEPENDENCY_INVALIDATED`, `SESSION_FINALIZED`,
`PARTICIPANT_CANCELLED`. The other lane integrated and the work group `COMPLETED` in 74 s.

Observed and not changed:

- After `cancel_lane`, `get_next_action` answers `blocked` / `coordination_intent_required` rather
  than saying the lane is closed, as it does after a finished lane.
- With two agents of one provider the controller cannot tell whose lane is open. The agent that had
  cancelled was resumed once for its sibling's progress and had nothing to do.
- In test 1 the returning Codex agent mistyped the lane identifier in its first continuation call and
  was answered `CONTINUATION_SOURCE_NOT_FOUND`; it retried with the projected value.

NOT REACHED: the timeout with Codex as the owner. Its MCP server does not inherit the controller's
environment, so a non-default wait set that way would not reach it; the default applies.

# 2026-10-10 — Codex with Claude Code on Opus 5.5: negotiated run COMPLETED through two resumes

Uncommitted. Build `0.1.0-dev.successor-refresh-20261010`: the changes of the entry below plus one fix.

Fix: a session that holds its lane by continuation is now moved onto the new control head when it
has uncommitted claimed work and the control branch advanced, by the same refresh every other
session gets (same session, same claims, work carried over, old worktree left untouched). When that
refresh declines, for instance because the control branch changed a file the session claims, the
session keeps its recovered worktree as before. `McpRecoveryContinuationTest` asserted the old
behaviour (same worktree path after the control branch advanced); that assertion now states what
matters (same session, recovered work present, control-branch content visible), and a new test pins
the fallback. Approved by the owner of this project.

Verification: `:coordination:check :link:check :workspace:check :mcp:check :cli:check`, coordination
115, link 111, workspace 508 (1 skipped), MCP 125, CLI 85, 0 failures.

Real run, same disposable project, prompt, and agents (Codex `gpt-6-luna` medium first, Claude Code
`claude-opus-5-5` high), started by `synesis agents run`: `COMPLETED` in 406 s, two resumes, both of
Codex.

1. Both lanes announced in one work group. Codex requested `task_store.api` and stopped.
2. Claude Code accepted the contract as requested. Codex was resumed, continued its own lane, and
   wrote the CLI and its tests against the accepted contract, then stopped.
3. Claude Code published its implementation. Codex was resumed again, continued its lane again,
   inspected the implementation in the validation workspace, ran the owner's tests, and accepted it.
4. Claude Code finished and was integrated. Codex was told `workspace_stale`; `ensure_session` now
   answered `ready` on a refreshed worktree, its tests passed against the integrated module, and it
   finished and was integrated.
5. Work group `COMPLETED`. `python -m unittest` passes 32 tests on the control branch.

Reached by real providers in one run: request, acceptance, publication, validation, two controller
resumes each regaining the lane by continuation, a successor's workspace refresh, integration of both
lanes, and work-group completion.

NOT REACHED in any real run: the owner's bounded wait expiring. In this run the owner accepted the
request as it stood; in the previous one the requester answered the owner's proposal in time. That
rule, the supersession it records, and the requester's "owner went ahead" path rest on tests alone.
Also not reached: the deadline wake-up of the controller, `cancel_lane`, and a run with a second
agent of the same provider.

# 2026-10-10 — Resumed agents regain their lanes and a full negotiation runs; STALLED on a successor's stale workspace

Uncommitted. Build `0.1.0-dev.owner-grace-20261010`. Two changes, made after the run recorded in the
entry below, and one real run on them.

Changes:

- A capability owner waits a bounded time for the answer to a contract it proposed
  (`RequesterAnswerPolicy`: two minutes; `SYNESIS_REQUESTER_ANSWER_GRACE_SECONDS` or the system property
  `synesis.requesterAnswerGraceSeconds`). Inside that time a completion request answers `waiting` /
  `requester_response_pending` with `requesterAnswerDeadlineEpochMillis`, and `finish_lane` refuses for
  the same reason. After it the owner may finish; finishing appends `CAPABILITY_REQUEST_SUPERSEDED`
  with reason `OWNER_FINISHED_WITHOUT_REQUESTER_ANSWER`. The requester is then told the owner went
  ahead, shown the owner's last proposal, and told the owner's files are released. A superseded
  request is no longer a dependency of the requester's snapshot and no longer blocks its completion.
  Decided by the owner of this project: an owner must not be held for a late requester, and rework on
  a released file costs less than a reserved agent.
- `synesis agents run` gives every process it starts its own connection identity and records the
  connection closed when the process exits. Codex kills its MCP server, so its lease stayed `ACTIVE`
  and Synesis waited its five-minute abrupt-loss grace before a resumed process could continue the
  lane; that is why the first real resume failed. The controller also resumes a stopped agent once
  when an answer deadline of its provider has passed, and does not end the run while one is pending,
  because the log does not move at that moment. Not done: one stable identity per agent. A lease keeps
  its first process's identity, and a session a peer has already suspended would be fenced.

Not closed: the supersede event and the completion-prepared event are two appends. A requester that
polls and files a new request between them gets a request against a lane about to close. The window
is milliseconds and was not observed.

Verification: `:coordination:check :link:check :workspace:check :mcp:check :cli:check`, coordination
115, link 111, workspace 508 (1 skipped), MCP 124, CLI 85, 0 failures. New tests cover the wait, the
refusal, supersession, the late answer, the requester's finish, the controller's connection handling,
and its deadline wake-up.

Real run, same project, prompt, and agents as below (Codex `gpt-6-luna` medium first, Claude Code
`claude-opus-5-5` high): `STALLED` after 239 s with two resumes. PARTIAL, and much further than before.

1. Codex requested `task_store.api` and stopped. The controller closed its connection.
2. Claude Code answered with a revised contract, and its following poll suspended the stopped session
   and held its recovery snapshot. The controller resumed Codex, which was offered the continuation
   of its own lane, took it, and accepted the revised contract.
3. Claude Code published the implementation and stopped. Codex inspected it in the validation
   workspace, ran the owner's 28 tests there, and accepted it: `VALIDATED`.
4. The controller resumed Claude Code, which continued its lane, finished, and was integrated.
5. Codex, holding uncommitted `task_cli.py` and `test_task_cli.py`, was told `workspace_stale`.
   `ensure_session` answered `workspace_not_ready` / `CONTROL_BASE_ADVANCED` and every read was
   refused. It stopped. Nothing progressed after it stopped, so the controller ended the run.

Reached for the first time by real providers: capability request, owner counter-proposal, requester
acceptance, `publish_capability_implementation`, validation, a controller resume that regained a lane,
and lane continuation for both providers.

Defect that ended it: a session that holds its lane by continuation never has its workspace refreshed.
`ProviderSessionBindingService.ensure` moves any other session with uncommitted claimed work onto the
new control head and carries that work over; for a continuation successor it keeps the old worktree,
and `McpRecoveryContinuationTest` asserts exactly that. Such a session can still finish from its stale
base, but it cannot read or test against what was just integrated, which is what a requester needs.
Every controller resume makes the agent a successor, so this is now the common case. Not fixed in
this build because it changes behaviour an existing recovery test pins; fixed in the entry above.

NOT REACHED: integration of the requester's lane and work-group completion in a negotiated run, and
the owner's bounded wait expiring in a real run (the requester answered in time).

# 2026-10-10 — Codex with Claude Code on Opus 5.5: negotiation reached, then STALLED on the first real resume

Uncommitted. Build `0.1.0-dev.class-splits-20261010`, same disposable project and prompt as the entry
below, started by `synesis agents run` with Codex (`gpt-6-luna`, medium) first and Claude Code
(`claude-opus-5-5`, high effort) second. Result: `STALLED` after 209 s with one resume. PARTIAL.

What happened, from the event log and both agent logs:

1. Both lanes were announced in one work group. Codex declared `task_store.api`, was told
   `needs_capability`, and created a capability request (`CAPABILITY_REQUEST_CREATED`).
2. Codex polled `owner_response_pending` four times and its process ended, as a headless agent does.
3. Claude Code, the owner, wrote and tested the storage module, read the request, and answered with a
   revised contract (`CAPABILITY_REQUEST_CONTRACT_REVISED`, request state `REVISION_REQUESTED`).
4. The controller resumed Codex: the first resume any real run has needed.
5. The resumed Codex process could not reach its lane. Its new MCP server was given a new Synesis
   session (`session-786da7a9…`, the lane belongs to `session-cb07510d…`). `get_next_action` answered
   `coordination_intent_required` and listed its own lane as another participant's active work;
   `ensure_session` with that lane's `intentId` and `claimEpoch` answered `policy_denied` /
   `request_human_help` / `SCOPE_EXTENSION_INTENT_NOT_FOUND`. It gave up and exited.
6. Claude Code asked to finish while its revised contract was unanswered, was allowed to, and its lane
   integrated. It then waited on the open work group and exited.
7. Nothing progressed further. The Codex lane is still `ACTIVE`, the request is still
   `REVISION_REQUESTED`, the work group is `ACTIVE`. The control branch holds the storage module only;
   its 30 tests pass.

Defects this exposes. None is in code the class splits moved differently; the mechanisms are the same
on the previous build, which never resumed an agent.

- A resumed agent does not get its lane back. `SynesisMcpServer` takes its connection identity from
  `SYNESIS_MCP_CONNECTION_INSTANCE_ID` and otherwise generates a random one per process. The managed
  Codex launcher passes that variable; `synesis agents run` does not, so every resumed process is a
  stranger to its own lane. The only route back is recovery continuation, which needs the old session
  to be suspended first, and the controller does not count a suspension as a reason to resume.
- An owner may finish while a contract it proposed is unanswered. The earlier fix covers a request the
  owner has not answered and an implementation awaiting a verdict, not this state. What the requester
  is then told was NOT REACHED, because the requester never got back to its lane.
- `SCOPE_EXTENSION_INTENT_NOT_FOUND` with `request_human_help` is a dead end for an agent that is
  trying to resume its own work.

NOT REACHED: contract acceptance, `publish_capability_implementation`, validation, integration of the
requester's lane, work-group completion.

# 2026-10-10 — Refactored build installed and run with Codex and Claude Code; two installer defects fixed

Uncommitted. Build `0.1.0-dev.class-splits-20261010`, from `master` at `14e8fdde` plus the two fixes
below.

Defects found while installing, both fixed in source:

- The installer failed with `Access is denied` whenever a provider session was open. A provider keeps
  its MCP server running, which holds `bin\synesis-mcp.exe` open, and Windows will not write over an
  open image. `installStableLauncher` in `bootstrap/main.go` now leaves an identical launcher alone and
  otherwise moves the open one aside (`synesis-mcp.exe.replaced-*`) so the new one can take its name;
  running servers keep their image and a later installation removes the copy. Proven by
  `TestLauncherHeldOpenByARunningProcessIsReplaced`, which runs a process from the launcher and
  replaces it, and on this machine with three Codex desktop sessions connected throughout.
- `:cli:platformBundle` declared outputs and no inputs, so Gradle reused an existing bundle: rebuilding
  under an unchanged version packaged the previous launchers and jars. It and `:cli:runnableInstaller`
  now declare what they package. Earlier builds in this work were unaffected only because every one
  used a new version name.

Real current-build acceptance, disposable project `Documents\TestingSynesisOnClaude`, one Claude Code
agent (`claude-sonnet-5-5`, low effort) and one Codex agent (`gpt-6-luna`, medium effort), one prompt,
started by `synesis agents run`. The bootstrapper initialized the project, installed both providers,
started the controller, and validated; it assigned nothing and relayed nothing.

- Run 1, Claude Code started first: `COMPLETED` in 93 s, no resumes. The Claude Code lane finished and
  integrated before Codex announced, so the lanes did not overlap and each formed its own work group.
- Run 2, Codex started first: `COMPLETED` in 87 s, no resumes, one work group. The lanes overlapped.
  Codex declared `task_store.api`, was told `needs_capability` / `owner_required`, and sent a
  capability request with a full contract. The owner's lane had integrated by then, so Synesis answered
  `CAPABILITY_OWNER_INTEGRATED` and recorded no request. Codex's workspace was then reported stale,
  `ensure_session` moved it to a fresh worktree on the new control head, and it built against the
  integrated module.
- Both runs: two immutable lane snapshots integrated, both agents `COMPLETED`, `python -m unittest`
  passes 8 tests on the control branch.

Reached through the split MCP handler by real providers: `ensure_session`, `get_next_action`,
`read_file`, `apply_patch`, `run_command`, `request_coordination`, `finish_lane`, stale-workspace
refresh, integration, and lane close. NOT REACHED in either run: an owner accepting a capability
request, `publish_capability_implementation`, validation through `respond_coordination`,
`cancel_lane`, recovery and lane continuation, and a controller resume. Those rest on the test suites
alone for this build. A low-effort Claude Code agent finishes the storage lane in about half a minute,
which is why the requester keeps arriving after the owner is done.

# 2026-10-10 — Largest classes split by responsibility; behaviour and tests unchanged

Uncommitted. Structural moves only: method bodies, comments, and checks were moved verbatim into
package-private classes in the same package, and every public signature stayed where it was. No test
file was added, changed, or removed. Each move was compared line by line against the original before
it was applied.

| Class | Lines before | Lines after | Moved into |
| --- | ---: | ---: | --- |
| `AgentNextActionService` | 3,129 | 740 | `ReviewLaneProjection`, `CapabilityActionProjection`, `LaneCompletionProjection`, `RecoveryContinuationProjection`, `LocalCoordinationFallback`, `CollaborationView`, `NextActionSupport`, `NextActionKeys`, `NextActionProtocol`, `LaneScope`, `CapabilityActor`, `Publishability` |
| `McpProtocolHandler` | 2,743 | 668 | Stateless: `McpToolArguments`, `McpTaskArguments`, `McpCoordinationPayload`, `McpIntegrationCheckEvidence`, `McpJsonRpcResponses`, `McpRootCandidates`. State-owning: `McpProjectRootSelection`, `McpManagedAdmission`, `McpSessionLease`, `McpSessionEstablishment`, `McpDurableCommand`, `McpRecoveryReconciliation`, `McpWorkspaceTools`, `McpCoordinationTools` |
| `ControlPlaneHttpHandler` | 1,888 | 434 | `ControlPlaneRequestBoundary`, `ControlPlaneSessions`, `ControlPlaneProjectEndpoints`, `ControlPlaneOnboardingEndpoints`, `ControlPlaneEventStream`, `ControlPlaneHttpJson`, `ApiFailure` |
| `ProviderSessionBindingService` | 1,662 | 897 | `ProviderBindingFile`, `ProviderWorktreeGitFacts`, `ProviderWorktreeAllocation`, `ProviderSessionCoordinationFacts`, `ProviderWorkspaceRefresh`, `ProviderWorkspaceVerification` |
| `ProviderApplicationService` | 1,157 | 529 | `ProviderLauncherResolution`, `ProviderHookConfiguration`, `ProviderConfigurationFiles`, `ProviderResults`, `ProviderProjectInstructions`, `ProviderHealthProbes` |
| `WorkIntentService` | 1,197 | 722 | `LaneCompletionRules`, `LaneClaimService`, `LaneCompletionService`, `RepairLaneTransfer` |
| `WorkspaceCollaborationService` | 1,118 | 786 | `WorkIntentAnnouncement`, `NoChangeLaneCompletion`, `RecoveryLaneContinuation`, `MutationAdmission` |
| `TaskSnapshotService` | 1,240 | 938 | `SnapshotGitCommands`, `WorktreeChangeInspection`, `PublishableChangeDetection`, `SnapshotTreeMaterialization`, `SnapshotRefLifecycle` |
| `CodexAppServerLifecycleService` | 1,737 | 1,491 | `CodexAppServerDirectLaunch`, `CodexAppServerPayloadReader`, `CodexAppServerProcessStreams`, `CodexAppServerProcessTermination` |
| `Onboarding` | 1,472 | 1,307 | `LocalCandidateGathering`, `EndpointCleanup`, `ClientQuicConnectionAttempt`, `CompactDescriptorExchange` |

`McpProtocolHandler.handleToolsCall` is now the shared admission checks followed by one `switch` over
the ten tools. Each MCP state field has exactly one class that writes it; the selected project root is
passed to a tool call as an argument instead of being re-read from the handler. The stdio server calls
the handler from one thread, so the two are equivalent.

Left as they are, deliberately:

- `CollaborationProjection` (1,101 lines) is one replay state machine: a single `apply` switch whose
  handlers nearly all read and write the same `intents` and `participantHistory` maps. The sibling
  projections have the same shape. Splitting it would need a shared mutable state object.
- `CodexAppServerLifecycleService` keeps its lifecycle state machine and its event listener. Every
  listener case interleaves a store read, identity checks, and a transition that takes the state lock,
  writes the store, and notifies waiters; separating translation from mutation would reorder them.
- `Onboarding` is still large because its host, join, and import paths each repeat the same endpoint
  setup sequence in a long linear body. Reducing it means merging those copies, which changes
  acquisition and cleanup order in security-sensitive code. That is a behaviour change and needs its
  own task and tests.
- `ParticipantRecoveryService` carries private copies of `validateContinuationTarget` and
  `selectorsOverlap` that also exist in `CollaborationProjection`. Not merged, for the same reason.

Build change: the isolated test-state root in `build.gradle.kts` is now canonicalized. A Gradle daemon
started with the default Windows temporary directory reports it with an 8.3 short name
(`C:\Users\LIPARA~1\...`); worktree containment checks resolve real paths and rejected every mutation as
a symlink escape. One suite run failed 24 tests that way before the cause was found; none of the
failures came from the moved code.

Verification on the final tree, `:coordination:check :link:check :workspace:check :mcp:check
:cli:check` with `-Xlint:all -Werror`, Javadoc, format, architecture, and static-analysis gates:
coordination 112, link 111, workspace 502 (1 skipped), MCP 123, CLI 85 tests, 0 failures, `BUILD
SUCCESSFUL`. Not run: `project-record`, `relay`, `web-ui` tests (untouched modules), the installer
build, and any real-provider acceptance run on this tree.

# 2026-10-09 — `synesis agents run`: a controller that resumes stopped agents

Synesis has no working way to wake a provider session. The Codex wake path needs the coordination
server and a thread Synesis launched through the App Server, which nothing in production starts; Claude
Code has none. A headless agent therefore ends its process when it has nothing to do in one turn, and
nothing tells it when another lane has moved on.

Added, uncommitted, build `0.1.0-dev.agents-run3-20261009`:

- `synesis agents run --agent <provider[:model[:effort]]>... (--prompt | --prompt-file)` starts one
  headless session per agent with the same prompt, then watches the durable log. An agent that has
  stopped is started again, on its own provider session, with one sentence telling it to continue, when
  a lane of its provider is still open and work has progressed since it stopped. Heartbeats,
  suspensions, recovery holds, and accepted continuations are not progress, so idle agents cannot wake
  each other. The controller never reads or relays agent output, assigns nothing, selects no
  continuation, and writes no coordination state. It ends `COMPLETED`, `STALLED`, `NO_WORK_ANNOUNCED`,
  `RESUME_LIMIT_REACHED`, or `TIMED_OUT`.
- `ProviderIntegration.headlessSession()` replaces the unused `autonomousCommand`: how a provider is
  started, how its session identifier is read from its output, and how it is resumed. The prompt goes
  on standard input. Claude Code runs in print mode against the project's `.mcp.json` with only the
  Synesis tools and its read-only tools allowed; Codex runs `codex exec` under its own approval and
  sandbox policy.
- `PeerDependencyPolicy` now looks across the project instead of one work group. A lane announced after
  the others finished starts a work group of its own; under the earlier scope its declared dependency
  stayed `owner_required` forever. Found by the second real run below.

Real current-build acceptance, disposable project `Documents\TestingSynesisOnClaude`, one Claude Code
agent (`claude-sonnet-5-5`, low effort) and one Codex agent (`gpt-6-luna`, medium effort), one prompt:

- Run 1, started together by the controller: `COMPLETED` in 118 s. Both lanes integrated, WorkGroup
  `COMPLETED`, `python -m unittest` in the control checkout ran 8 tests, all passing. The lane with the
  declared dependency built against the other's integrated work without a capability request. The
  controller resumed the Claude agent once, on its own session, after that agent's lane had already
  completed; that resume achieved nothing, and the rule was then tightened to require an open lane of
  the agent's provider.
- Run 2, same command on a fresh project: `STALLED`, correctly. The Codex agent announced after the
  Claude lane's work group had completed, landed in a second work group, was told `owner_required` on
  every poll, and stopped with its lane open. Nothing had progressed since, so nothing was resumed.
- Run 3, the controller with a single new Codex agent on that same stalled project, after the policy
  change: `COMPLETED` in 71 s with no resume. The agent took over the abandoned lane by continuation,
  was told its dependency was integrated, and finished. Both work groups `COMPLETED`; 9 tests pass in
  the control checkout.

Not demonstrated: a real run in which the controller's resume was needed and made the difference, for
example an owner waiting for a requester's verdict. That path is covered by the controller's tests with
scripted processes only. Capability negotiation did not occur in these three runs.

Evidence: coordination 112, workspace 502 (1 skipped), mcp 123, cli 85 pass with their `check` gates
and `repositoryHygieneCheck`. New tests: the controller against scripted processes and project states
(resume on progress, no resume without it, no resume without an open lane of the provider, per-agent
limit, time limit, no session identifier); each provider's start and resume commands and session
identifier parsing; executable resolution; agent specifications; and a two-process test in which a lane
announced after the other work group completed has its dependency satisfied.

# 2026-10-09 — Codex with Claude Code: WorkGroup COMPLETED through loss, recovery, and continuation

Real current-build acceptance on the project mixed attempt 2 (below) left stuck, never reset. One Codex
thread (`gpt-6-luna`, medium effort) and one Claude Code session (`claude-sonnet-5-5`, low effort), each
resumed with the single sentence "Continue following the coordination protocol from where you stopped."
Every resume was a new process and therefore a new session. The bootstrapper relayed nothing, selected
no continuation, and did not touch `.synesis`.

Demonstrated across attempt 2 and these resumes, from a replayed copy of the 97-event log: admission of
both providers; disjoint claims; capability request; the owner shown the unanswered request when it
asked to finish, and its acceptance; implementation publication; requester inspection and validation;
loss of both participants and later of two successors; reconciliation (`PARTICIPANT_SUSPENDED` and
`RECOVERY_SNAPSHOT_HELD` four times each); continuation of the storage lane by a Codex successor, twice, and of the CLI lane by a Claude
successor (`LANE_CONTINUATION_ACCEPTED` at 61, 67, 78); the successor owner's completion and
integration (`TASK_INTEGRATED` at 75); integration of the CLI lane (92, 94); every session finalized and
intent released; `WORK_GROUP_STATUS_CHANGED` to `COMPLETED` (97). No intent remains active.
Independently: the control branch holds three snapshot commits, `python -m unittest` there runs 14
tests, all passing, and the CLI adds, completes, lists, and removes tasks.

Not demonstrated: a mixed run that completes without any agent process exiting; review; a requester
sending an implementation back. Both of the CLI lane's snapshots were integrated, its predecessor's and
its successor's.

Changes made to get there, uncommitted, build `0.1.0-dev.recovered-owner-20261009`:

- A validated capability request now follows a lane to its successor. The signed continuation event
  already transferred the owner and requester of requests with an open obligation; a validated one was
  left with the predecessor, so a successor owner was never shown that its completion was due and a
  successor requester would have been told to request a dependency it already held. Requests that
  ended without agreement still do not move. This changes how existing logs replay: a validated
  request is attributed to the successor after a continuation.
- A continuation is offered only to a caller that can take it. A caller that already holds a lane
  (`CURRENT_PARTICIPANT_HOLDS_A_LANE`) or belongs to another provider
  (`CONTINUATION_REQUIRES_SAME_PROVIDER`) sees the candidate as unavailable instead of being handed a
  request that fails with `RECOVERY_SOURCE_BINDING_NOT_FOUND`. A lane is still continued only from its
  own provider's binding; taking over another provider's lane remains unsupported.
- The integration pump accepts an owner-lane snapshot as carrying a validated implementation when it
  is the validated commit or when every path that implementation changed has identical content in it,
  including the same absences. It previously required the validated commit itself, which a lane
  rebuilt from recovery evidence can never produce, so the consumer waited forever. A snapshot in
  which a validated path differs still does not qualify.

Evidence: coordination 112, workspace 489 (1 skipped), mcp 122, cli 83 pass with their `check` gates
and `repositoryHygieneCheck`. New tests cover the validated-request transfer (owner and requester), the
two unavailable continuation reasons, and the content rule against a real repository (identical,
edited, restored, earlier, unknown).

# 2026-10-09 — Dependencies on finished lanes, owners awaiting a verdict, and abandoned recovery locks

Three changes decided after the mixed runs below, uncommitted.

- Dependency on finished work. `PeerDependencyPolicy` (coordination) is the one statement of the rule,
  used by the inbox projection, the completion gate, and the capability request service. A capability
  request is no longer recorded against a lane whose completion is prepared; the asker is told to wait
  (`CAPABILITY_OWNER_COMPLETING`), and its inbox says the same instead of `owner_required` while every
  remaining producer lane is closing. Once no producer lane of the work group is open and at least one
  peer lane has been integrated, a declared dependency the lane never obtained a request for counts as
  satisfied: the inbox lists it under `dependenciesIntegrated`, a request for it answers
  `CAPABILITY_OWNER_INTEGRATED` without recording anything, and it no longer blocks the lane's
  completion. A dependency with an open producer peer, or in a work group where nothing was integrated,
  is unresolved exactly as before. This lets a lane complete without a negotiated contract in that one
  situation.
- Owner awaiting a verdict. While an implementation this lane published is `IMPLEMENTATION_AVAILABLE` or
  `VALIDATING`, an owner asking to finish is told to wait (`validation_required`,
  `IMPLEMENTATION_AWAITING_VALIDATION`), and `finish_lane` refuses for the same stated reason. Before,
  the inbox offered `finish_lane` until the requester opened its validation, after which the action
  refused it.
- Abandoned recovery lock. Taking `reconciliation-execution.lock` now reclaims a lock whose recorded
  process no longer exists, or whose identifier belongs to a process that started after the lock was
  written. The decision and the new lock are made under an operating-system lock so two recoveries
  cannot discard each other's. An unreadable lock is still treated as held. Why the recovery that left
  the lock never finished is not known; the lock recorded a process that had exited, with no execution
  journal.

Evidence: coordination 111, workspace 488 (1 skipped), mcp 122, cli 83 pass with their `check` gates.
New tests: the lock (reclaim, exclusion, identifier reuse, unreadable lock); the two-process negotiation
test now also asserts the owner's wait and the refused late request; a new two-process test takes a
declared dependency through an integrated peer to work-group completion.

Real current-build acceptance, build `0.1.0-dev.recovery-fixes-20261009`, on the project left stuck by
mixed attempt 2 below, with its stale lock still in place. Resuming the Codex thread: the stale lock
was reclaimed; both exited participants were suspended and their recovery snapshots held (events 57
to 60); the inbox offered the storage lane's continuation and Codex accepted it
(`LANE_CONTINUATION_ACCEPTED`, event 61), keeping that lane's authority lineage. Demonstrated:
participant loss, reconciliation, continuation. NOT REACHED: the owner's completion, integration,
WorkGroup completion.

It stopped on two defects in what follows a continuation, neither changed:

- The successor is not recognised as the owner of the capability its lane had published. Capability
  ownership is matched on the supervisor and worker of the session that was addressed, and a successor
  session has its own. The validated-completion prompt therefore never appears for it.
- With its own lane in hand, the successor was next offered the continuation of the other, exited lane
  (the Claude CLI lane, whose completion is already prepared and snapshot created). Executing that
  projected call failed with `RECOVERY_SOURCE_BINDING_NOT_FOUND`, and the same call was projected again.

# 2026-10-09 — Claude project rules, and Codex with Claude: negotiation through validation, then stalled

Changes, uncommitted:

- `synesis provider install claude` writes `.claude/rules/synesis.md`, a Synesis-owned file that imports
  the managed `AGENTS.md` block with `@../../AGENTS.md`. Checked against Claude Code 2.1.291 first: it
  loads Markdown under `.claude/rules` as project instructions and resolves the import relative to the
  rule file. The install result reports `PROJECT_INSTRUCTIONS` as `INSTALLED`, `UNCHANGED`, `CONFLICT`
  (a file without the ownership marker is at that path and was left alone), or `NOT_APPLICABLE` for
  providers that read `AGENTS.md` themselves. Uninstall removes only a file that carries the marker.
- `synesis init` registered the Codex server with the command launcher while `provider install codex`
  used the server launcher, so whichever ran last decided how every Codex session started its server.
  Both now use the server launcher.

Mixed runs, build `0.1.0-dev.mixed-run-20261009`, disposable project `Documents\TestingSynesisOnClaude`,
one Claude Code session (`claude-sonnet-5-5`, low effort) and one Codex session (`gpt-6-luna`, medium
effort), one identical prompt that named neither roles nor files and did not mention `AGENTS.md`. The
project README required the CLI author to obtain the storage API through a capability request.

Attempt 1, both launched together: PARTIAL. Claude claimed the store and completed and integrated its
lane. Codex's capability request was recorded at event 13, between Claude's `COMPLETION_PREPARED` (12)
and `TASK_SNAPSHOT_CREATED` (14). Claude's lane closed with the request unanswered and Codex waited on
an owner that no longer existed. The owner-finish precedence added earlier cannot see a request that
arrives after completion has been prepared.

Attempt 2, Codex launched first and Claude once Codex had announced its claim: PARTIAL. Demonstrated,
from a replayed copy of the log: admission of both providers; disjoint claims; Claude's capability
request; Codex asking to finish, being shown the unanswered request instead, and accepting it at
contract revision 17; Codex's implementation publication; Claude's inspection and validation in the
owner's snapshot (`CAPABILITY_IMPLEMENTATION_VALIDATED`); Claude's `COMPLETION_PREPARED` and
`TASK_SNAPSHOT_CREATED`. NOT REACHED: the owner's lane completion, either integration, WorkGroup
completion. The control branch still ends at the managed baseline.

Why attempt 2 stopped, in order:

- Codex asked to finish while Claude's validation was still open. `get_next_action` projected
  `finish_lane`; `finish_lane` then refused with `task_not_ready` and `pending: 1`. The projection and
  the action disagree for an owner whose published implementation awaits the requester's verdict.
- Both headless agents then reached the unchanged-poll limit and their processes exited. A resumed
  headless agent is a new connection and a new session; it was shown only the shared
  `integration_pending` wait, not the owner's validated-completion prompt, which belongs to the exited
  session's lane.
- The exited sessions were not recovered. The first resumed Codex connection began a reconciliation
  execution at startup and took `reconciliation-execution.lock`; the lock was still there after that
  process had gone, with no execution journal written. Every later attempt, the second resumed
  connection's and an operator `synesis reconcile --execute`, failed with "execution is busy". `synesis
  reconcile --dry-run` reported three recovery-eligible sessions and one suspected stale. Why the first
  execution never finished is not known. `doctor` and `repair` are the documented way to clear a stale
  lock and were not run; the project was left as it stopped.

Open, not changed:

- A capability request can be recorded against an owner whose completion is already prepared.
  Refusing it there is not enough on its own: a lane with a declared dependency and no active owner is
  told `owner_required` on every poll and cannot complete, so the requester also needs a lawful way
  forward when the work it depends on has been integrated.
- Whether an owner may be projected `finish_lane` while its implementation awaits validation.
- Recovery of sessions whose processes exited cleanly, and a stale reconciliation lock that blocks it.

Evidence: workspace 484 (1 skipped), mcp 121, cli 83 pass with their `check` gates after all changes
above. The installed build, `0.1.0-dev.mixed-run-20261009`, has every change in this entry.

# 2026-10-09 — Two Claude Code agents: WorkGroup COMPLETED; four defects found by the runs

Real current-build acceptance in a disposable project (`Documents\TestingSynesisOnClaude`), two headless
Claude Code 2.1.291 sessions (`claude-sonnet-5-5`, low effort), launched together with one identical
prompt that named no role, file, or peer and told each to read `README.md` and `AGENTS.md`. The
bootstrapper created the repository and goal, ran `synesis init` and `synesis provider install claude`,
and validated the result; it relayed nothing between agents. Claude Code does not load `AGENTS.md` on
its own, so the prompt pointed at it; Synesis does not yet write a `CLAUDE.md`.

Run 1, build `0.1.0-dev.native-launcher-20261009`: PARTIAL. Demonstrated: admission of both sessions,
concurrent claims (the second session's claim on `task_store.py` was refused with `overlapping_claim`
and it re-claimed `task_cli.py`), a capability request, and completion and integration of the owner's
lane. NOT REACHED: the owner's reply, validation, the requester's completion, WorkGroup completion.
The owner asked to finish after the request had been recorded, was projected `finish_lane`, and
closed its lane; the requester then waited on a reply nobody could give.

Run 2, build `0.1.0-dev.claude-run2-20261009`, same prompt, project re-created: both lanes completed,
two snapshot commits on the control branch, both participants `COMPLETED` and the WorkGroup
`COMPLETED` in `collaboration status`. The event log itself was not replayed. Independently: `python -m unittest` in the control
checkout ran 9 tests, all passing, and the CLI added, completed, listed, and removed tasks. NOT REACHED
in run 2: capability negotiation. The second session's first announcement with a declared dependency
returned `internal_failure`; it retried without the dependency, built against the integrated store
after its workspace refreshed, and never needed the request.

Changes made from these runs, uncommitted:

- An owner asking to finish is shown a request it has not answered instead of `finish_lane`. Accepted
  requests and requester-requested revisions are unchanged: for those the completion request is how
  the owner declares the next implementation ready. Negative control: the new test received
  `finish_lane` before the change. A first version that also covered validation revisions broke
  `Slice4FailureScenariosTest` and was narrowed.
- A session whose lane has completed can read its inbox once more and is told the lane is completed.
  Before, the recovery-fence check failed on the missing active binding and returned
  `SESSION_RECOVERY_STATE_UNAVAILABLE` with `request_human_help`. Only `get_next_action` is let through;
  a mutating tool on a completed binding is still refused.
- That closing response is described as workflow `CLOSE`. It had been described as `IMPLEMENT` with
  write operations permitted.
- An announcement that cannot obtain the project append lock now returns a retryable
  `EVENT_APPEND_LOCK_UNAVAILABLE`, and any other announcement failure writes
  `SYNESIS_MCP_ANNOUNCE_FAILURE=<type>:<message>` to the server diagnostic stream. The cause of the
  run 2 `internal_failure` is not known: it did not reproduce with two scripted sessions in sequence,
  and nothing was logged at the time.
- Provider hooks are installed against `synesis.exe` when the installation has it, falling back to
  `synesis.cmd`. Existing projects keep their hook until the provider is installed again; for Codex
  that changes the hook text it asks the user to re-trust.

Also observed: `request_coordination` rejects an unexpected payload field as `policy_denied` with
`request_human_help` (the agent removed the field and succeeded); a project directory that is deleted
and re-created keeps failing discovery with `IDENTITY_MISMATCH` until the old registry entry is removed,
which is the registry's documented rule; `provider install claude` leaves `.claude/` and `.mcp.json`
untracked in the control checkout, which the integration and snapshot paths already tolerate.

The native launcher went through three real installs: `synesis --version` takes about 410 ms through
`bin\synesis.exe` against about 640 ms through `synesis.cmd`.

Evidence after all changes: workspace 481 (1 skipped), mcp 121, cli 83 with their `check` gates, the
one mcp failure above included and then fixed; after the narrowing, mcp 121 and a focused workspace
selection of 50 pass, and the full workspace suite was not run again. Installed build:
`0.1.0-dev.claude-fixes-20261009`, which no agent run has exercised.

# 2026-10-09 — Dead code removed, native command launcher, cross-process manual installation

Uncommitted, on top of the entry below.

- Removed production code unreachable from every entry point, with its dedicated tests:
  `ProviderSupervisionService`, `ProviderProcessSupervisor`, `ResetRecoveryService`, `DecisionSearch`,
  `ProviderSessionCommand`, `ImplementationSnapshotService`, `ProjectCommandFormatMigrationService`,
  `ManualCandidateProvider`, fourteen methods and five constructors with no callers. About 2,750
  lines. `CandidateGathererTest` now uses its existing stub in place of the removed provider;
  `ProjectCommandNamespaceSpikeTest` lost the one method that exercised the removed migration
  service. Kept deliberately: the managed-Codex launcher chain and the overlay reconciliation, key
  agreement, and relay client, which are unwired but correspond to accepted ADRs. ADR-0013 and
  ADR-0043 still name `DecisionSearch` and `ProviderSessionCommand`.
- Native command launcher: the launcher binary already used for MCP is now also installed on Windows
  as `bin\synesis.exe`, which the shell resolves ahead of `synesis.cmd`. When run under that name it
  performs the script launcher's maximum-release doctor check, failing closed, and sets
  `SYNESIS_LAUNCHER` as the versioned script does. `synesis.cmd` remains for hooks and registrations
  that name it. An existing launcher that cannot be replaced because it is running does not fail
  activation. Measured with the installed build before this change: about 350 ms less per command
  through the native launcher. The new installation path was not exercised by a real install.
- Provider manual: installation and the re-check behind `requireAttested` now hold a file lock beside
  the manual directory. Each agent connection is a separate process that may install the manual at
  startup, and a replacement briefly leaves no directory in place; two workspace tests failed once
  with `MANUAL_ATTESTATION_REQUIRED:MANUAL_MISSING` under the new per-run isolated home. No test
  drives two processes through this path; the evidence is the full suite passing again.
- `collaboration status` tells a person when there is nothing to show; its machine output is unchanged.
- The Claude provider binds a worktree and serves `ensure_session` and `get_next_action` over MCP from
  the current source (driven by a script, not by Claude Code). Responses carried no
  `workflow.payload`.
- The developer profile was cleared of test residue: 2,299 workspace directories, 2,420 repository
  records, and 256 registry entries, none of which belonged to a project that still exists.

Evidence: `check` across all modules: coordination 111, mcp 120, mcp-contract 15, cli 83, link 111,
project-record 24, relay 4 pass; workspace 476 with the two failures above, then 476 (1 skipped)
passing after the manual lock, with `:workspace:check`. `go test ./...` passes in `bootstrap`.
Installer `0.1.0-dev.cli-speed2-20261009` built, not installed.

# 2026-10-09 — CLI speed, readable output, smaller workflow envelope, test isolation

Measured on the installed `0.1.0-dev.installer-ui-20261006` build first: `synesis init` took 4.3–5.8 s
and started 66 Git processes in sequence; every command paid about 640 ms before doing work; in
SynesisTest73 `get_next_action` produced 53% of all tool-response characters, with each response
repeating its whole result under `workflow.payload`.

Changes, uncommitted:

- Process runner: `ProcessCommandRunner` blocks on process launch and exit instead of polling every
  25 ms. Timeout, interruption, and output-collection behaviour are unchanged.
- Managed baseline: `init` now starts 40 Git processes. `ManagedPathPolicy.inspect` asks Git once for
  tracked and once for ignored managed paths instead of once per path, and fails closed when either
  query errors (previously an error read as "not tracked" / "not ignored").
  `SemanticIndexFingerprint.capture` reads entries and flags from one `ls-files -v --stage -z` listing
  and both index-mode settings from one `config --get-regexp` query. The post-reset check and recovery's
  synchronized-state check call the new `indexTreeId` instead of a full capture, because they only
  compared the tree. One of two back-to-back identical ownership inspections before the administrative
  commit was removed. Managed worktree blobs are verified with one `ls-tree` and one `hash-object`.
  Measured from the staged distribution: `init` 2.5 s (from 3.8 s on the same harness).
- Known-project registry: a full registry now drops entries whose directory no longer exists before
  refusing a new project. The bound itself is unchanged, and a registry full of present directories
  still returns `REGISTRY_FULL` without rewriting the file.
- Workflow envelope: `AgentWorkflowReducer` no longer copies the result into `workflow.payload`. The
  facts remain once in the enclosing result. `workflow.recoveryDeadlineEpochMillis` is now written when
  the result carries one; the provider manual already told agents to read it there, and it was
  previously reachable only inside the removed copy. `finish_lane` responses shrink with it because
  they embed the next-action result.
- CLI output: on an interactive terminal the `KEY=VALUE` contract is shown as aligned labelled lines
  with an outcome marker on `*_RESULT` facts. Pipes, files, hooks, and scripts keep the machine
  contract by default; `--porcelain` or `SYNESIS_OUTPUT=porcelain` forces it and
  `SYNESIS_OUTPUT=human` forces the readable form. Only lines passed through `Terminal.stdout` and
  `Terminal.stderr` are presented; raw writers and `stdoutRaw` are untouched. Usage errors in the
  readable form carry the parser's message and suggestions.
- CLI help: root help groups commands by purpose and names agent-facing commands on one line; all 178
  options that lacked a description have one; seven group commands gained `-h/--help`.
- Tests: every `Test` task now runs with `LOCALAPPDATA`, the home directory variables, and `user.home`
  pointed at `<java.io.tmpdir>/synesis-test-state/<module>` (override with `-PsynesisTestState`). Before
  this, suites had filled the real registry with 256 dead paths and left about 2,300 workspaces and
  2,400 repository records under the developer's profile; that existing residue was not removed.

Evidence: full `test` across all modules under isolation, before the envelope and CLI changes:
coordination 111, workspace 486 (1 skipped, 1 failure: `CodexManagedRuntimeHomeTest`, caused by the
first isolation location being inside the project tree; relocated and re-run green), mcp 120,
mcp-contract 15, cli 76, link 111, project-record 27, relay 4. After all changes: workspace 486 (1 skipped), mcp 120, and cli 83
pass, as do `:workspace:check :cli:check :mcp:check -x test` and `repositoryHygieneCheck`. The real
profile gained no workspaces across these runs. Source semantics only: installer
`0.1.0-dev.cli-speed-20261009` was built but not installed, and no agent run exercised the smaller
envelope.

Found, not changed:

- `GitProcessRunner` disables system and global Git configuration, so a checkout relying on
  `core.autocrlf=true` from the Git for Windows system config can be reported as
  `CONTROL_CHECKOUT_DIRTY` when Git's stat cache does not short-circuit the comparison. Reproduced once.
- `SemanticIndexFingerprint` records paths tagged `H` (ordinary cached entries) as assume-unchanged;
  the behaviour was preserved.
- `provider install` reports `PROVIDER_INSTALL_RESULT=SUCCESS` even when worktree allocation fails
  (`WORKTREE_BINDING_STATUS=FAILED`). Seen with a state root deep enough to exceed the Windows path
  limit; with ordinary paths the Claude provider binds a worktree exactly as Codex does.
- The script launcher (`cmd` to PowerShell to `cmd` to Java) costs about 350 ms more per command than
  the existing native launcher used for MCP.
- About 5,200 lines in 21 production files are unreachable from any entry point (provider process
  supervision, the managed-Codex launcher chain, overlay reconciliation and key agreement, decision
  search, reset recovery). Several correspond to accepted ADRs, so nothing was deleted.

# 2026-10-06 — Windows installer: outcome, versions, and pre-install disclosure

The native setup wizard keeps its 912x666 footprint, dark theme, and page flow. Changes:

- Result page: a status badge beside the title (success, warning, failure), the installed version in
  the description, and "Next steps" after a successful install (new terminal, `synesis --version`,
  `synesis init`). Open details replace the next steps; closing them restores it.
- Install-location page: a "Setup will" list naming the three changes made to the machine (runtime in
  the chosen folder, `synesis://` handler, user PATH entry), and a sentence-case title.
- Main page: the install card states the transition when both versions are known ("Update X to Y",
  "Reinstall X", or "Install Synesis X"). The bundle version is read from the bundle's `VERSION` file
  and the installed version from the active pointer; both are display text only and are dropped when
  unreadable or not a plain version.
- Progress page: step status lines are smaller and muted, pending steps are dimmed, and the log
  heading is no longer clipped by the log.
- Every page: an accent mark starts the rule under the header.

`go test ./...` passes in `bootstrap`, including new tests for the version copy, the outcome badge
selection, badge and dot rendering, and the install and result layouts. The six preview pages were
inspected through the existing no-operation harness. Off-screen `PrintWindow` captures of that harness
intermittently omit parent-painted chrome or a label; on-screen captures of the same pages were
complete. The real install, repair, and uninstall operations were not exercised through the new UI.
Installer rebuilt as `0.1.0-dev.installer-ui-20261006`; not installed.

# 2026-10-06 — SynesisTest73 on `0.1.0-dev.lifecycle-fixes-20261006`: WorkGroup COMPLETED

Real current-build acceptance, user-run with two Codex agents from an empty project
(`Desktop\SynesisTest73`), build activated 2026-10-06T04:30:47Z from the uncommitted working tree. The
68-event log was copied and replayed read-only; every event passed chain, signature, and projection
checks.

Demonstrated: admission; concurrent claims (`task_store.py` granted to one lane, the other denied and
re-claimed `task_cli.py`); capability request and owner acceptance at contract revision 10; publication
of implementation revision 1; requester inspection and `CAPABILITY_IMPLEMENTATION_VALIDATED`; completion
of both lanes; two integration attempts, each ending in `CONTROL_BRANCH_ADVANCED` and `TASK_INTEGRATED`;
both sessions finalized and intents released; `WORK_GROUP_STATUS_CHANGED` to `COMPLETED`. Exactly two
participants appear in the log, both `COMPLETED`. The control checkout is clean at the second snapshot
commit and contains `task_store.py` and `task_cli.py`.

NOT REACHED in this run: an owner contract revision and the requester's accept of it (the owner accepted
the request as proposed; the requester's extra accept returned `CAPABILITY_REVISION_NOT_PENDING`),
participant loss, recovery continuation, and review. Whether the heartbeat-tolerant finish envelope or
the owner completion prompt was what let the lanes finish is not established from the log.

The agents did not run CLI behavior checks. A copy of the two integrated files, exercised outside the
project, added, listed, completed, and deleted tasks and returned exit 1 for an unknown id and a blank
title; new tasks are stored with status `queued`.

# 2026-10-06 — Three lifecycle gaps from the SynesisTest72 run closed in source

The gaps are described in the next entry. Source changes:

1. Completion envelopes tolerate heartbeats. `PredictionEventStore.unchangedSince(revision)` reports
   whether only `PARTICIPANT_HEARTBEAT` events follow a revision. The snapshot finish check
   (`AgentTaskCompletionService.validateSnapshotEvidence`) and the three no-change checks
   (`AgentTaskCompletionService`, `WorkspaceCollaborationService`, `WorkIntentService.completeNoChange`)
   use it in place of head equality. `CollaborationProjection` tracks the newest non-heartbeat sequence
   and validates a recorded no-change completion against it on replay. Any event other than a heartbeat
   still stales the envelope, and intent, claim epoch, WorkGroup version, and participant are checked as
   before. Existing logs replay unchanged because they satisfy the rule with equality. A no-change
   completion written with heartbeats between projection and finish is rejected on replay by builds
   older than this change; snapshot completions are not affected because that check was never part of
   replay.
2. `AgentNextActionService` projects `implementationValidated` with the completion request to a producer
   whose owned capability requests in its lineage are all settled, at least one `VALIDATED`, and whose
   lane has no snapshot yet. It runs after the explicit completion stage and before the integration pump
   result, which otherwise answers the shared `INTEGRATION_PENDING` wait to the owner too. The reducer
   makes that completion request the recommended tool.
3. The requester's `REVISE_CAPABILITY_REQUEST` projection carries the capability name and
   `acceptRevision`, the exact accept call. `CapabilityRequestService` names which part of a stale reply
   differs.

`SyntheticTwoProcessCollaborationTest` gained an end-to-end case in the order the real run took: owner
revision, requester accept using only the projected call, publication, inspection and validation,
requester lane finished first, owner prompted, requester poll between the owner's finish projection and
its `finish_lane`, both task snapshots integrated, WorkGroup `COMPLETED`. `NoChangeCompletionTest`
covers heartbeats versus a state change after projection, including replay. The "before" evidence for
these gaps is the real runs below, not a controlled revert of each change.

Full source suites after these changes: `:coordination:test` 111, `:workspace:test` 484 (1 skip),
`:mcp:test` 120, `:mcp-contract:test` 15, all with 0 failures and 0 errors. Javadoc for the four
modules, `formatCheck`, and `architectureCheck` pass; `:coordination:javadoc` first failed on missing
tags in `LaneContinuation` and `CapabilityActorScope` from the earlier patch, which were added after
the suite run as comment-only edits.

# 2026-10-06 — Fresh-project runs on `0.1.0-dev.capability-lock-20261006`: PARTIAL

Evidence is from user-run Codex sessions against the installed build; each project's event log was
copied and replayed read-only, and every log passed chain, signature, and projection checks.

`Desktop\SynesisTest72` (61 events, two participants, no extra lanes): concurrent claims, capability
request, owner revision, requester acceptance at contract revision 18, publication of implementation
revision 1, requester inspection, and `CAPABILITY_IMPLEMENTATION_VALIDATED` were all reached. The
requester lane then finished and its snapshot is `INTEGRATION_PENDING` with the capability dependency.
NOT REACHED: owner lane completion, integration, terminal WorkGroup. The owner's last activity before
validation was at publication; it polled once after validation and recorded no completion. In source,
`finish_lane` is projected only for `get_next_action({completionRequested:true})`, and the owner inbox
carries that `completionRequest` hint only while the request is `ACCEPTED` or `IMPLEMENTING`; after
publication and validation nothing tells the owner to request completion.

A follow-up resume of SynesisTest72 had the owner call `get_next_action({completionRequested:true})` and
then the projected `finish_lane`, which returned `SNAPSHOT_COMPLETION_EVIDENCE_STALE` until the
unchanged-action limit. The log grew only by heartbeats (events 62-67): owner at 04:12:47Z, requester at
04:12:50Z, then owner at 04:12:53Z, 04:12:59Z, 04:13:09Z, and 04:13:27Z; no completion event. The
projected finish payload carries `expectedRevision = store.headSequence()`, and
`AgentTaskCompletionService.validateSnapshotEvidence` requires it to equal the head at completion. Every
non-finish, non-command MCP call appends a `PARTICIPANT_HEARTBEAT`, so any poll by any participant
between projection and finish invalidates the envelope. The requester's poll three seconds after the
owner's projection explains the first rejection. The later rejections are consistent with the owner's
own intervening polls but cannot be established, because rejected calls leave no durable record. The
earlier successful requester finish had no event between its projection (event 54) and completion (55).
Owner lane completion, integration, and terminal WorkGroup remain NOT REACHED.

`Desktop\SynesisTest71` (41 events): stopped at `REVISION_REQUESTED`, contract revision 19. The same
controller prompt was sent twice in one project; the second pair of agents connected as new sessions and
could not act on the first pair's lanes or request (`policy_denied`). A `stale_request` on accept was
also reported there, and an initial denied accept in SynesisTest72. The requester's
`REVISE_CAPABILITY_REQUEST` projection carries handle, revision, and contract but neither the capability
name nor a ready payload, while the accept requires all of them to match; whether that caused the
rejections is not established because rejected calls are not recorded.

`Desktop\SynesisTest70` (54 events, older build) is a separate target from
`C:\t\SynesisTest70-RecoveryFix-20261005-1`: two agents became four participants with four lanes and
three requests for one capability, none published.

# 2026-10-06 — Capability negotiation serialized under the project append lock

`CapabilityRequestService` and `CapabilityResponseService` previously resolved the binding, read the
capability projection, and appended without holding `ProjectAppendLock`. `PredictionEventStore.append`
refreshes the durable head but validates against that instance's in-memory projection, so two MCP
processes could each record an active request for the same requester and capability, or each append a
reply decided from the same contract revision. A conflicting pair either replaced one event file at
the same sequence while both callers were told they succeeded, or left an accept/reject pair that no
longer replays.

Both services now acquire the lock before opening the store, resolve the exact active binding and
replay a fresh projection under it, and hold it through authorization, the revision and idempotency
checks, and the append. A bounded lock timeout returns `blocked` / `task_not_ready` with
`EVENT_APPEND_LOCK_UNAVAILABLE` and `retry` instead of `internal_failure`. The owner reply is now
authorized against the stored owner supervisor and worker scope, as publication already was, and the
owner inbox projects pending replies and validation revisions with the same scope, so a sibling session
on the shared node identity is neither offered nor allowed the reply.

`CapabilityNegotiationTest` gained concurrent duplicate-request, concurrent same-revision
accept/reject, and sibling-owner-scope cases; all three fail when the lock is made per-call and the
owner check is reduced to the node identity. Its legacy semantic-ownership fixture, and the one in
`TwoProcessCapabilityNegotiationProcessTest`, now name the bound codex session's own supervisor and
worker instead of synthetic identifiers.

That fixture change reflects a behavior change on the legacy path. A request routed through a
semantic-ownership claim stores the claim's supervisor as its owner scope. When that supervisor is not
the supervisor of any provider binding, no MCP session is offered or allowed the owner reply.
Previously any session on the node could accept such a request and was then refused at publication,
which already required the stored scope. Requests routed through an active owner intent are unaffected:
they store the exact owner binding's supervisor and worker.

These are in-process thread tests over separate service instances and a shared project directory; no
multi-process run and no real current-build acceptance was performed for this change.

Full source suites after the change: `:workspace:test` 484 tests (0 failures, 0 errors, 1 skip),
`:coordination:test` 109, `:mcp-contract:test` 15, and `:mcp:test` 119, all with 0 failures and 0
errors. The first `:mcp:test` run had one failure, the synthetic-owner fixture described above; the
rerun after the fixture change passed 119/119. `:workspace:javadoc` initially failed on missing tags for
`AgentTaskIntent`'s seven-argument constructor and `WorkspaceCollaborationService.recoveryFenced`, both
from the earlier uncommitted patch; the tags were added and `javadoc`, `formatCheck`,
`architectureCheck`, and `staticAnalysis` pass. The workspace suite was not rerun after those
comment-only edits.

# 2026-10-06 — SynesisTest70 lockout cause: installed build cannot read V2 continuations

A copy of the target's `.synesis/coordination/events` (91 events) was replayed outside the target. With
the current source every event passes project, sequence, hash-chain, and signature checks and all seven
projections accept it. The event log is intact; no backup or restore is needed.

With the installed build's own `coordination` jar
(`0.1.0-dev.capability-validation-20261005-83ff8461212a`, activated 2026-10-05 18:53 local) replay stops
at event 30, `LANE_CONTINUATION_ACCEPTED`, with `unsupported continuation format`. Events 30, 44, 61,
75, and 83 carry the `SCN2` continuation magic that the uncommitted source introduced; the installed
codec only knows `SCN1`. The run that wrote those events therefore used a build of the working tree,
and the two sessions bound at 01:56 and 01:58 on 2026-10-06 ran the older installed build through the
global Codex MCP entry. Their terminal-fence check opens the event store, fails at event 30, and
surfaces as `SESSION_TERMINAL_STATE_UNAVAILABLE`. Which launcher started the earlier sessions was not
determined. The capability-negotiation race fixed above is not the cause for this target.

Replayed with the current source, the log ends with the `task-store-api` request `VALIDATED` at contract
revision 15 and both participants suspended with recovery snapshots held (events 88-91). The target
holds 14 codex session bindings for two agent threads. Whether the lanes can continue to integration
under a build that reads V2 has not been tried; the target itself was only read and copied.

Installer rebuilt from the uncommitted working tree as `0.1.0-dev.capability-lock-20261006` and
installed with `synesis-windows-x64.exe install`; the previous `capability-validation-20261005` payload
is retained as the rollback version. The installed build's `coordination` jar replays the copied
SynesisTest70 log with no rejected events, and `synesis --version` reports the new version. Two
`mcp --provider codex` processes started from the previous payload before the install were left
running. No resume of SynesisTest70 and no fresh-project run has been made against this build.

# 2026-10-06 — SynesisTest70 resumed acceptance status

The existing disposable target
`C:\t\SynesisTest70-RecoveryFix-20261005-1` was resumed with its original two Codex agent threads. The
overlapping `task_store.py` claim led to the accepted `task-store-api` contract at revision 15:
`create_task(title, *, status="queued")` plus `list_tasks()`. The owner published implementation
revision 1, and the requester inspected and accepted that exact snapshot. The requester CLI check could
not import `task_store` because the owner snapshot had not yet integrated.

After the recovery continuations, the requester lane was restored, but its inbox remained
`needs_capability / owner_required` without a request handle or contract revision. The owner’s projected
`ensure_session` failed with `internal_failure`; the next inbox read returned
`SESSION_TERMINAL_STATE_UNAVAILABLE`, `retrySafe=false`, and no permitted operation. The requester’s
projected `ensure_session` failed the same way. No integration or terminal WorkGroup state was reached.
The acceptance remains PARTIAL.

Terminal-state failures now include a bounded `causeCode` shared with Doctor: verified event-log
integrity failures map to `event_log_verification_failure`, while other unreadable or rejected state maps
to `durable_state_ambiguous`. The fail-closed response still offers no mutation action and remains
non-retryable. `SessionTerminalStateUnavailableTest` passed 2/2 after this change.

Identity investigation confirmed that each agent shell sees a distinct, stable `CODEX_THREAD_ID`, but the
ordinary desktop `codex.exe` parent and its Synesis MCP children have neither `CODEX_THREAD_ID` nor
`CODEX_SESSION_ID`; the configured server has `env_vars=[]`. A disposable fake-MCP probe confirmed
`env_vars` forwards values present in the launching process, but does not provide the desktop with a
per-thread value. No Synesis-side identity fallback or global Codex configuration change was made;
stable reconnect identity still needs a host-provided per-thread MCP value or equivalent supported
metadata.

Managed-default source tracing found that production `ProjectRuntimeHost` construction still uses the
ordinary App Server launcher; `ManagedCodexProcessLauncher.prepareFirst`/`prepareReplacement` have no
production caller. The isolated managed-home path requires keyring authentication, unavailable with the
current file-backed login, while the shared-home path failed worker/thread isolation. No managed-default
switch or credential change was made. A verified backup for the existing event log is not known, and the
current repair workflow does not restore coordination logs; if Doctor confirms log corruption and no
verified backup exists, the original target cannot be safely restored and must remain evidence.

The follow-up full source suites passed: `:workspace:test` 481 tests (0 failures, 0 errors, 1
skip), `:coordination:test` 109 tests, `:mcp-contract:test` 15 tests, and `:mcp:test` 119 tests;
all reported 0 failures and 0 errors. The broad MCP rerun took 12m33s through Git-backed fixtures.
Its first run exposed two stale lifecycle expectations and a completed no-change replay blocked by
the recovery precheck. The tests now assert clean-EOF recovery preservation and dirty-worktree
refresh; only an exact nonterminal completed binding can reach the existing evidence-checked
no-change replay path. `git diff --check` passes, with only existing CRLF conversion notices.

These are source-level results, not real current-build acceptance. SynesisTest70 still has no
verified terminal WorkGroup, integration, or same-target recovery evidence. The current task did not
mutate the disposable target. The source checkout remains without `.synesis`; no commit or push was
made.

# 2026-10-05 — Recovery-preserving transport closure and capability succession

Clean MCP EOF no longer detaches an unfinished lane. The exact session lease is
marked closed, and reconciliation fences the participant and captures an
immutable recovery snapshot immediately. Abrupt process loss still requires
verified process absence and the existing five-minute grace; ordinary
`get_next_action` polling re-evaluates eligibility so recovery is not limited
to one admission attempt. Ambiguous or live process evidence remains
non-recoverable. Suspended and recovery-held participants cannot be revived by
old heartbeats, and MCP dispatch blocks the exact fenced binding before a
mutation can run. The lease is checked only after collaboration heartbeat
authority succeeds, preventing stale sessions from refreshing a closed lease.

Workflow responses expose `recoveryDeadlineEpochMillis` only when the exact
process is verified absent and the grace deadline is still in the future. The
deadline participates in action identity, and provider guidance instructs an
intentional wait until that deadline without consuming the unchanged-action
poll limit.

The versioned continuation payload now carries verified source and successor
capability actor scopes while retaining v1 event decoding. The same signed
`LANE_CONTINUATION_ACCEPTED` event transfers only exact matching owner or
requester scopes. Request handles, accepted contract revisions, immutable
implementation revisions, and completed validation verdicts remain intact. A
requester recovered from `VALIDATING` loses its old inspection context and the
same immutable implementation is projected for a fresh successor inspection.

Focused verification passed: coordination 38/38, workspace 34/34, MCP recovery
4/4, and MCP contract catalog 15/15, with no reported failures or skips.
`git diff --check` passed with existing line-ending notices. The versioned
installer `0.1.0-recovery-continuation-20261005-1` built, its bundle smoke test
passed, installation under `C:\t\SynesisRecoveryInstall-20261005-1` completed,
and the installed CLI reported that exact version.

After a host interruption, the existing external target
`C:\t\SynesisTest70-RecoveryFix-20261005-1` was inspected through its installed
MCP process. `ensure_session` returned ready and `get_next_action` returned
`IMPLEMENT` with action `22800316-f6ae-3b77-a346-16e7f4ba4726`; there was no
WorkGroup or participant state. No owner/requester acceptance lanes or source
files had been created before the interruption, and no acceptance request,
publication, inspection, validation, integration, or terminal WorkGroup result
exists. The live MCP process found on the machine belongs to the Synesis source
checkout and was not reused. Ordinary Codex-agent acceptance therefore remains
NOT REACHED; the installed-build observer check is the only external-runtime
evidence. No commit or push was made in the Synesis source checkout, which
remains without `.synesis`.

# 2026-10-05 — Capability publication continuation and bounded polling

A fresh two-agent task-tracker run accepted the API contract and allowed both
lanes to implement, but remained ACTIVE because the owner could not obtain a
publication action. Ordinary inbox polling kept returning implementationPending;
completion gates already required capability publication. Publication also used
HEAD instead of the visible dirty/untracked implementation.

The source now projects publish_capability_implementation after the owner
explicitly requests completion. Exact contract revision, intent, claim epoch,
and source-tree evidence fence publication. TaskSnapshotService captures claimed
source without altering HEAD/index/worktree and reuses the immutable commit at
later lane completion. The service validates current binding incarnation, trust,
workspace, and owner authority under the project append lock; exact retries do
not create another implementation revision.

Workflow action IDs include contract/implementation revisions and canonical
continuation payloads. Runtime retry hints and generated provider instructions
bound unchanged polling to three follow-ups with a five-second default delay.
This is provider guidance; it does not revoke authority or count calls server-side.

Requester validation now has an explicit inspect stage. get_next_action
projects result=inspect; the requester opens a detached worktree at the exact
published commit, then uses the existing read_file and run_command tools with
the request handle and implementation revision. Those calls require the exact
active requester session and a matching durable validation context. A verdict is
rejected before inspection or when workspace setup fails. Validation worktree
creation checks out the immutable commit directly rather than patching it onto a
different working tree. Implementation event format v3 now correctly round-trips
both owner lineage and requester session identity.

The fresh selected test runs passed 45 tests across coordination (3), workspace
(20), MCP (7), and the MCP contract catalog (15), with no failures or skips. They
cover exact requester/owner separation, dirty and committed publication, stable
snapshot reuse, inspection read and command access, unauthorized-session denial,
failed verdict before inspect, revision loops, and two independent MCP processes.
Strict Javadocs, compiler diagnostics, formatting, and architecture gates passed.
git diff --check and the deferred-register check pass.

Installer rebuilt as 0.1.0-dev.capability-validation-20261005. The Windows
installer and versioned bundle are under cli/build/distributions. A fresh
disposable-project run against the rebuilt installer remains unverified. No
commit or push was made; this source checkout remains without .synesis.

# 2026-10-05 — Capability negotiation after overlapping claims

The source now routes a denied overlapping claim into the existing MCP
capability-request lifecycle. Conflict results expose the exact owner lineage;
the requester must create a disjoint consumer claim and submit a complete API
contract. Owner revisions carry event-sequenced contract revisions, and the
requester must accept the exact revision before implementation work is exposed.
Both lanes can implement after acceptance. Owner completion waits for
capability publication, dependent completion waits for requester validation,
and dependent integration waits for the matching owner snapshot.

This is a source implementation only. `git diff --check` passes, and Java 25
compiled the changed production sources plus adjusted test sources against the
existing module outputs. Gradle could not start its single-use JVM because it
failed to establish a loopback connection; no tests or real disposable-project
acceptance were run. No commit or push was made, and the Synesis source
checkout remains without `.synesis`.

# 2026-09-29 — SYN-082 accepted-review artifact continuation

Source policy distinguishes mandatory independent review of an exact candidate
from reviewer report work. ReviewPolicy has no structured report obligation;
WorkIntent acceptance is free text and is not an integration gate. Therefore an
accepted exact candidate may integrate before a claimed reviewer report is
published. The active reviewer intent still prevents WorkGroup terminality.

After sibling integration advanced control HEAD, the exact BOUND reviewer could
not create its claimed report through MCP apply_patch; strict readiness returned
workspace_stale. Mutation readiness now permits that continuation only for the
same bound session, original registered worktree/generation, and exact active
claim. Unclaimed paths and ordinary stale mutations remain fail-closed. Once the
reviewer creates the claimed artifact, get_next_action(completionRequested=true)
projects finish_lane; the focused MCP flow publishes/integrates that exact
reviewer snapshot and closes the WorkGroup. No filename-specific gate or new
MCP tool was added.

Selected verification passed: McpSyn039SliceTest (22), McpRecoveryContinuationTest
(3), McpMandatoryReviewPolicyTest (1); selected workspace review/integration
classes (55); WorkGroupReviewPolicyTest and WorkIntentServiceTest (34); and
McpToolCatalogTest (11, exactly ten tools). Formatting, static analysis, and
architecture gates passed. Workspace Javadocs retain the two known warnings in
ProviderManualService.java:26 and ProviderSessionBindingService.java:576.
git diff --check passed. Full workspace and MCP module suites were not run.

Fresh bundle 0.1.0-syn082-review-deliverable-20260929-1:

- ZIP SHA-256: 7724C3975E1FBCFE324E428955A7D580224AD886C3C5D4A1A96BBFF48ACCFD17
- Workspace JAR SHA-256: F8EE58E1326DE8EF43709C88F6560BFD7A1DF78E0ED3F7638626517DB9F73E85
- Native MCP launcher SHA-256: EC4407FDD92E3469723A698828DBD5A5523076D217D8D0C966B0CC72B9207048

Fresh target C:\t\SYN082-ReviewDeliverable-Stage3-20260929-build1
(ProjectId f7f4127d-ddcf-4c75-8a8e-f6a3c3c2ce0e) was initialized from the
versioned bundle and its Codex provider integration was installed. Provider
setup reports TRUST_STATUS=REVIEW_REQUIRED and REAL_AGENT_VALIDATION=NOT_COMPLETED.
Its generated hook uses the stable global launcher, which still points to
0.1.0-syn082-accepted-review-fix-20260929-1. The versioned installer staged the
new immutable payload but failed replacing the stable launcher with Access is
denied because two live MCP processes hold it; the active pointer is unchanged.
The current source task exposes no Synesis MCP tools. Stage 3 and Stage 4 remain
NOT REACHED; no real acceptance or reviewer-loss claim is made.

## Exact next action

Gracefully release the three old MCP connections using
C:\t\SYN082-Stage3-20260929-acceptance-2, retry the versioned bundle install,
then open the fresh target as a Codex project/task and verify its MCP binds to
that ProjectId before starting Stage 3. Do not use this source task for Synesis
operations.

# 2026-09-23 — SYN-082 real process-crash proof and reviewer wait repair

`RecoveryContinuationCrashSafetyTest.abruptProcessLossAtDurableBoundariesReplaysOneOwnershipOutcome`
now launches a separate JVM and calls `Runtime.halt(86)` at three actual
continuation append boundaries. After `BEFORE_DURABLE_MOVE`, a fresh store
removes the abandoned temporary event, leaves the source `RECOVERY_HELD` with
no successor, and permits one retry. After `AFTER_DURABLE_MOVE` and
`AFTER_PROJECTION`, replay reconstructs exactly one successor; retry returns
the same current successor and does not append another event. This proves the
event-store/service crash prefix. It is not a full MCP-server process crash.

The full serial coordination suite passed in the combined run. The full
workspace run reached 432 tests and exposed one real state regression:
reviewer `get_next_action` returned generic `IMPLEMENT` when the reviewer had
no producer and no grants. The empty grant list must remain `REVIEWER_PENDING`;
a durable continuation grant, separately identified by
`WorkGroupProjection.continuationGrant`, must not create a review obligation.
That distinction is now restored. The previously failing test, all four
`ReviewAdmissionOrderIndependenceTest` cases, and three focused MCP continuation
classes pass. The full workspace suite must be rerun after the repair. The
earlier full MCP suite was interrupted and is not counted.

## Exact next action

Run the full serial workspace command recorded in `docs/agent/NEXT_SESSION.md`.

# 2026-09-20 — SYN-082 review-recovery admission and hostile boundary

The source-backed admission repair lets a fresh reviewer declare the exact
claim still held by a recovery-held reviewer and reach the existing projected
continuation action. Admission is read-only: it creates no participant,
intent, claim, grant, successor, or continuation record. The focused MCP
regression passes, and the current distribution still exposes exactly ten MCP
tools.

The fresh disposable target
`C:\Users\Liparakis\Desktop\SynesisReviewRecoveryFixed-20260920` supplied
the latest live evidence. Blind A/B/R lanes were concurrent; producer B's
process tree was killed and ordinary reconciliation completed 6/6 actions.
Two later producer successors recovered disjoint storage/core lanes. A
reviewer successor after a hostile loss reached the server-projected
`SNAPSHOT_PENDING` / `review_validation` gate for the core intent, without
reading the report or submitting a decision. The target never reached an
independent decision, reviewer publication, review integration, WorkGroup
completion, or goal closure. A later blind launch produced mixed role/session
evidence, so no terminal claim is inferred from it.

Phase verdict: crash-safe successor authority PASS; hostile blind A/B/C
recovery PARTIAL; mandatory review/reviewer-loss convergence
PARTIAL/NOT REACHED. Source `.synesis` remained absent and no source
Synesis coordination, manual target mutation, commit, or push was used.

## Exact next action

Run the full serial MCP regression and strict quality gates, then run
`powershell -ExecutionPolicy Bypass -File scripts/agent-checkpoint.ps1`.

# 2026-09-20 — SYN-082 fresh single-successor continuation

The fresh target `C:\Users\Liparakis\Desktop\SynesisSuccessorTarget-20260920`
passed the real bounded crash-safe successor path. Blind producer A claimed
intent `2e2a2cb8-8fc9-359a-982a-8953c52a99f7`, then its process tree was killed
before completion. Normal reconciliation plan
`recplan-f560f1bdcfcd4807a2ec85be0ee9b0f9` / execution
`recexec-ffaaa4dd2e5544e9af446dac2289f09c` completed 2/2 actions with zero
failures. Blind producer B discovered `continuation_available`, received the
server-authorized `CONTINUED` successor
`631eb250-3f3e-38cd-87a7-95c964826481` under participant
`agt_906c59c8-53d0-3050-badf-01f4240e471b`, passed 3 tests, published
`snap_20806a156de6ae572262e2cab27967ef`, and integrated the WorkGroup to
`COMPLETED` at version 2. A later fresh participant created a new unrelated
intent instead of reclaiming A's historical identity.

This is a PASS for the bounded no-review successor path. It does not close the
separate review-enabled acceptance gap. The post-run dry run's one derived
terminal `recoveryEligible` lease had zero executable actions and did not
represent active authority.

The focused coordination slice passed in 5s, the workspace slice passed in
5m04s, the MCP continuation/admission/review slice passed in 3m10s, and
`git diff --check` passed. The source `.synesis` guard remains absent.

The clean review target `SynesisMandatoryReviewFresh7-20260920` added a real
independent rejection and correction request. A producer successor published
`snap_ab10a4cb847c8f682e9713eaecbf52b7`; reviewer successor
`agt_7bfb62c1-d878-3a5e-a18b-55a0cd8e99ca` rejected it for a forward-only
status-transition defect, and producer successor
`agt_a18f2614-2000-3816-ab31-a281048a87c4` stopped at
`REVIEW_GRANT_PENDING` without self-approval. After that reviewer process
exited, reconciliation fenced it as `RECOVERY_HELD`, but the unchanged fresh
reviewer prompt was blocked by the still-held `review-report.md` claim. This
is a concrete real-target front-door gap, not review closure.

## Exact next action

Run `powershell -ExecutionPolicy Bypass -File
scripts/agent-checkpoint.ps1`, then review the recovery-held reviewer admission
seam before any further blind target.

# 2026-09-20 — SYN-082 lawful continuation final verification

The fresh review-gated target
`C:\Users\Liparakis\Desktop\SynesisMandatoryReviewFresh6-20260920`
provided the strongest live acceptance boundary in this slice. Through the
normal ten-tool surface it showed concurrent blind reviewer/producer claims,
review admission and exact grants, reviewer participant loss/recovery,
successor-scoped reviewer grants reaching `SNAPSHOT_PENDING`, and
`REVIEW_SELF_NOT_ALLOWED` for a same-participant reviewer takeover. A source
producer snapshot integrated correctly with `reviewRequired:false` because no
authoritative review request applied to that exact intent. The target ended
with no review decision, reviewer publication, review integration, WorkGroup
completion, or goal closure. Phase 1 is PASS; Phase 2 is PARTIAL; Phase 3 is
PARTIAL/NOT REACHED. Do not convert this into a terminal acceptance claim.

The source-side process identity repair and action-priority repair are now
covered by focused tests. The rebuilt distribution, targeted continuation /
recovery / integration tests, and strict workspace quality gates pass. The
exact named disposable project `C:\Users\Liparakis\Desktop\SomethingVeryFuckingRandom`
was exercised with the unchanged blind harness: a fresh participant discovered
the recovery-held lane, executed the projected generic continuation request,
and received `CONTINUED` with a new participant and intent. It then stopped at
an existing `REVIEW_GRANT_PENDING` state addressed to a previously completed
reviewer. A separate noisy reviewer-successor probe reached fresh
`SNAPSHOT_PENDING`, but not publication or closure. Full hostile
review-enabled A/B/C convergence remains PARTIAL/NOT REACHED; no bypass or
manual target mutation was used.

Evidence: `docs/evidence/SYN-082-lawful-recovery-continuation-2026-09-20.md`.

Follow-up verification on 2026-09-20 also passed the focused coordination
suite (`BUILD SUCCESSFUL in 5s`), the workspace continuation/recovery/
integration suite (`BUILD SUCCESSFUL in 4m 13s`), the MCP continuation and
admission suite (`BUILD SUCCESSFUL in 28s`), workspace format/static/
architecture/Javadoc plus the MCP-contract tests (`BUILD SUCCESSFUL in 10s`),
and a fresh `:cli:installDist --rerun-tasks` rebuild (`BUILD SUCCESSFUL in
28s`). The Gradle commands used only the process-local Windows JDK loopback
directory workaround `C:\t\synesis-loopback-probe`; no source or target
authority state was changed by verification.

The unchanged blind review-fixture retry then exercised reviewer and producer
successor chains through the normal ten-tool surface. C and E acquired fresh
reviewer intents; D and G acquired fresh producer intents. D implemented the
fixture and G repaired its assigned `src`-layout pytest import configuration;
the resulting four tests and wheel build passed. The target remained without a
published snapshot because its accumulated historical reviewer grants were
still waiting on older obligations. Independent review, integration, and
WorkGroup closure therefore remain NOT REACHED; no grant was revoked or
manually consumed.

Checkpoint CP-1005 was created after startup resume passed. Immediate next
action: inspect CP-1005 and the fresh review-gated evidence, then preserve the
explicit review-enabled closure gap and do not launch another target without a
separately authorized review-required fixture.

# 2026-09-19 — SYN-082 reviewer admission and evidence publication repair

The focused review fixture exposed and repaired the last source-side
publication omission: a reviewer with an independently accepted decision and a
claimed `review-report.md` had no projected `finish_lane` because publication
was filtered to producer roles. Snapshot publication and completion now use all
active claims owned by the authenticated participant, while the selected lane
selectors and foreign-claim fence remain authoritative. A reviewer can publish
only its own claimed evidence artifact; it cannot publish the producer's
source paths.

The public MCP admission path now returns `REVIEW_CLAIM_REQUIRED` when a
reviewer task supplies no ownership claim, making the required retry explicit
without inventing a report path or creating an intent. The regression then
admits the reviewer through `ensure_session` with an explicit report claim.

`McpSyn039SliceTest.reviewerOwnedArtifactCompletesThroughPublishIntegrationAndGroupClosure`
passes and asserts the reviewer snapshot contains only `review-report.md`,
both snapshots integrate, active intents are empty, and the WorkGroup is
`COMPLETED`. The focused workspace review predicates pass, and the rebuilt
local distribution passes. The full MCP test class was started but stopped at a
bounded unrelated fixture Git-initialization stall; it is incomplete, not a
pass. Real blind acceptance remains PARTIAL: no current-build run has yet
proved abrupt reviewer loss, successor independent review, post-review
publication/integration, and terminal goal completion together.

Fresh11 exposed why that action did not occur: its WorkGroup was created from a
producer slice with no authoritative review request, while README review prose
is intentionally non-authoritative. A fresh targeted acceptance must exercise
the existing review-request path against one uniquely selected producer
revision, then kill and replace the reviewer; it must not fabricate a claim,
infer policy from README text, or auto-assign a reviewer.

Immediate next action: run `powershell -ExecutionPolicy Bypass -File
scripts/agent-resume.ps1`, then use a fresh disposable target and the current
distribution to prove that targeted reviewer-loss sequence. Keep the source
checkout free of Synesis state and do not count Fresh11 as convergence.

The fresh blind negative probe `SynesisReviewerLossBlindFresh10-20260919`
confirmed that normal agents can discover an existing published snapshot, but
one reviewer attempt with an empty claim set remained correctly unannounced and
projected `coordination_intent_required`. No reviewer role was forced and no
review claim was fabricated. This is not reviewer-loss evidence; the focused
source fixture remains the only current source-level publication closure.

The follow-up public-protocol regression now converts that same empty-claim
boundary into the actionable `REVIEW_CLAIM_REQUIRED` response before retrying
with a lawful explicit report claim.

Fresh current-build target `SynesisReviewerLossBlindFresh11-20260919` admitted
four unchanged-prompt disjoint producer/test lanes. A fresh G acquired the
server-issued epoch-2 continuation for the detached model-test lane under a
new participant and ran 9 tests, but no authorized finish action appeared
while other continuation candidates remained. Final target state was
`active=0`, `recoveryEligible=5`, `executableActions=0`, WorkGroup `ACTIVE`,
and all snapshots `reviewRequired:false`; this strengthens Phase 2 evidence but
does not prove convergence or Phase 3.

A targeted reviewer then admitted a distinct reviewer intent with
`review-report.md`, but the normal projection remained `REVIEWER_PENDING`.
The unchanged-prompt follow-up selected `tests/test_storage.py` continuation
and remained separate from the review lane. No review decision or reviewer-loss
evidence was created.

# 2026-09-19 — SYN-082 fresh successor probes

The current source implementation remains verified for the bounded successor
path and crash prefixes. Fresh real probes added evidence for concurrent blind
disjoint admission, normal `continuation_available` projection, epoch-2
successor acquisition, predecessor detachment, and restoration of an
unpublished `issue_tracker.py` artifact. Both probes stopped at legitimate
review gates. A clean unresolved reviewer participant is not counted as an
abrupt reviewer loss; mandatory-review/reviewer-loss closure remains open.

The additional unchanged-prompt target
`C:\Users\Liparakis\Desktop\SynesisBlindReviewLoss4-20260919` admitted three
disjoint producer lanes. A and B exited normally, C published the `tests/`
snapshot (`7 passed`), and the final projection contained no continuation or
review action because the exited participants were still projected `ACTIVE`.
This is not process-tree-loss evidence and does not weaken the existing
successor result.

Evidence: `docs/evidence/SYN-082-three-lane-successor-probe-2026-09-19.md`.
Exact next action: run `powershell -ExecutionPolicy Bypass -File
scripts/agent-checkpoint.ps1`.

# Current Task

## SYN-082 Ordinary Codex workspace admission under Windows path aliases

- Task ID: SYN-082
- Status: ACTIVE; source repair, focused continuation checks, crash-boundary continuation checks, artifact-restore repair, targeted real lawful continuation/completion, serial workspace suite, quality-only gates, deferred validation, and diff hygiene PASS. The user-authorized overnight extension now requires three connected milestones: crash-safe successor prefixes, hostile concurrent A/B/C multi-lane recovery, and mandatory independent-review/reviewer-loss recovery. The source repair now includes exact-intent scoped publication, replayable reviewer-grant succession, pending-review retargeting for producer successors, and an explicit self-review rejection at the review-validation boundary. The current-build real hostile run proved process-tree loss and three exact successor continuations but remains PARTIAL because integration never converged and no mandatory-review action was reached; a narrower current-build A/B/C target proved recovery through integrated completion but still created no review obligation.
- Boundary: preserve exact `ConnectionInstanceId` binding, `RuntimeAuthenticator` and `SessionAuthorityResolver` strictness, ProjectIdentity/replica semantics, hidden `.synesis` handling, provider DEGRADED admission policy, and exactly 10 MCP tools. The lifecycle repair may invoke the existing bounded reconciliation plan after ordinary MCP admission; the explicitly activated ADR-0083 slice may expose and execute lawful recovery-held continuation through the existing generic coordination action. The overnight extension may add only crash-prefix/replay proof, concurrent multi-lane recovery support, and reviewer-loss continuation using existing review semantics. It must not weaken grace periods or claims, auto-assign successors, auto-review, add tools, bypass review/integration, or fabricate continuation authority. Do not use Synesis coordination/MCP for this source checkout; do not modify the source checkout's `.synesis`; do not publish.
- Root-cause evidence: Git worktree porcelain reports the package-local Windows Codex path while bindings can persist the normal `%LOCALAPPDATA%` spelling; both paths have the same NTFS file identity, but the old source compared `toRealPath()` strings. Real disposable projects therefore persisted `WORKTREE_NOT_REGISTERED` and could not reach readiness.
- Implementation evidence: `Files.isSameFile` now owns physical identity checks at the existing provider-binding/readiness seam; bounded diagnostics expose the real stable readiness code; compact stale-reallocation names avoid the Windows path-length limit. No authority fallback was added.
- Lifecycle evidence: the rebuilt launcher admitted two blind participants with disjoint claims, an outer-process kill removed the MCP/Java children while leaving the old lease projected `ACTIVE`, five-minute grace classified the dead sessions as recovery-eligible, and replacement admission fenced/held both interrupted fresh lanes exactly once. Terminal stale-plan recovery is also fenced. See `docs/evidence/SYN-082-lifecycle-recovery-2026-09-18.md`.
- Continuation evidence: `docs/evidence/SYN-082-lawful-recovery-continuation-2026-09-19.md` and `docs/evidence/SYN-082-overnight-successor-recovery-2026-09-19.md`; the first real continuation attempt exposed a root `.git` snapshot contamination bug, then the fixed target completed successor acquisition, recovered-file validation, snapshot publication, integration, WorkGroup closure, and `COMPLETED` projection. The overnight sequence then proved blind disjoint decomposition, a real current-build process-tree kill after product edits, five-minute recovery grace, and three server-authorized successor continuations. The narrower target `SynesisReviewContinuationBlind-20260919` was extended through CLI, storage, filtering, CRUD, public API, README, and persisted-CLI slices. Its later producer slices projected `reviewRequired:false`; the separate current-build target `SynesisMandatoryReviewContinuation6-20260919` produced real `reviewRequired:true` snapshots and reached reviewer `SNAPSHOT_PENDING`, but no current-build mandatory-review decision or terminal closure is claimed.

Latest current-build target `SynesisMandatoryReviewContinuation6-20260919` exposed the successor reviewer path after the stale-grant fencing repair. A fresh reviewer continued a prior reviewer intent, consumed the current successor grant, and reached the exact current-intent `SNAPSHOT_PENDING` action; a producer process-tree interruption was recovered through reconciliation, including a clean retry after transient lock contention. The run also exercised late predecessor-grant fencing and its focused regression. It did not reach an independent review decision, successor publication, integration, WorkGroup closure, or durable goal completion; after the final cleanup execution, the dry run still reported historical recovery-eligible sessions but zero executable actions.

## Acceptance criteria

1. The ordinary fresh-project admission path reaches `ready/isolated` through the existing lifecycle on this Windows host.
2. A new Codex/MCP process can admit the same ProjectId with a new exact connection binding; no latest-session, PID, thread, or filesystem-only fallback is introduced.
3. Same-file Windows path aliases pass only when filesystem identity and Git branch/common-directory checks agree; distinct worktrees still fail closed.
4. True missing, dirty, wrong-project, and unsupported-layout states remain rejected with bounded actionable diagnostics.
5. Provider `DEGRADED/REVIEW_REQUIRED` is documented as informational or admission-critical from source evidence; it is not suppressed.
6. Focused tests, affected regressions, strict workspace gates, `git diff --check`, evidence, and checkpoint are complete; no commit or push is made.
7. A fresh exact participant can discover and explicitly acquire one recovery-held unfinished lane under a new participant/connection/intent authority while preserving task/work lineage; stale, replayed, and racing actions fail closed and the existing review/integration gates remain authoritative.
8. Continuation has deterministic process-crash prefix semantics: restart/retry yields no successor or exactly one successor, never split ownership, and never requires internal state repair.
9. A participant with disjoint successor lanes can select and publish one exact lane at a time without absorbing sibling paths; a fresh real A/B/C run must still prove concurrent convergence.
10. Unresolved review responsibility can survive reviewer continuation through a fresh grant and independent decision; accepted review evidence is not migrated, self-approval remains forbidden, and a hostile mandatory-review run remains required.

## Work completed

Implemented the ADR-0083 recovery-held successor path at the existing generic
coordination boundary. `get_next_action` now exposes read-only continuation
candidates, makes a single candidate explicitly selectable, and withholds any
selection or payload when multiple candidates exist. The server validates the
source recovery state, immutable snapshot, task/goal/acceptance/scope/role/
dependencies, claim epoch, group, lineage, and conflicts under the project
append lock. It derives a fresh one-shot grant when needed, creates a distinct
successor participant/intent/connection/session/worktree authority, detaches
the predecessor, and preserves the task and durable lineage without resurrecting
the old lease.

The recovery snapshot seam now excludes root `.git`, `.git` descendants, and
`.synesis`; the regression prevents predecessor worktree metadata from being
restored into a successor. Focused source tests cover fresh acquisition,
replay/race/lineage, read-only action projection, protected snapshot restore,
MCP schema/catalog, crash prefixes, successor-scoped multi-lane publication,
reviewer-grant succession, and successor integration. Fresh
continuation now records one durable `LANE_CONTINUATION_ACCEPTED` event and
reconstructs the one-shot grant on replay; the concurrent-race test now counts
the returned `ALREADY_COMMITTED` loser correctly. The disposable blind
successor completed 6/6 recovered-application tests, CLI lifecycle and error
smoke, published its snapshot, integrated it, and closed the WorkGroup.

The overnight repair also migrates unresolved review responsibility lawfully:
reviewer continuation supersedes the dead reviewer's unresolved grants and
derives a fresh grant, while producer continuation retargets only matching
pending review requests to the successor producer. The request remains pending
and the ordinary independent response/grant path remains authoritative.
Continuation authorizations remain audit-visible but are not review grants; the
projection and review-validation service reject a review decision against one
with `REVIEW_SELF_APPROVAL_FORBIDDEN`.

## Current failures

No source or lifecycle failure remains for the bounded continuation slice. The
fresh public path is one durable continuation append and is crash-prefix tested
and replay-safe; the event store guarantee is process-crash recovery, not a
hardware power-loss directory-fsync guarantee. Exact finish evidence now selects
one intent, and scoped snapshots make disjoint same-participant lanes publishable
separately. Unresolved reviewer grants receive a deterministic successor grant,
and matching pending review requests follow an exact producer successor;
completed decisions are never migrated. The first overnight run used the
previous build and the fresh current-build run remains PARTIAL: it proved
interruption, grace-bound recovery, and three exact successor continuations,
but not terminal integration or mandatory review. The serial MCP aggregate
remains bounded/incomplete; focused MCP checks and the non-test quality gates
pass. The source checkout has no `.synesis` state, and no commit or push was
made. The latest focused MCP Syn-039 run passes after the self-review guard;
the extended blind target recovered an integration failure through a claim-epoch
2 successor and later integrated a 42-test correction; those later producer
slices projected `reviewRequired:false`. The separate current-build review
target did create `reviewRequired:true` snapshots and reviewer wait/grant
states, but no independent decision was recorded. Fresh no-change sessions
released claims without creating a reviewer intent. The real-target review
milestone remains NOT REACHED rather than inferred from README policy.

## Overnight continuation milestones

- Phase 1 — crash-safe successor authority: PASS for deterministic process
  crash-prefix/replay semantics and one-event fresh continuation; a dangerous
  real-process boundary injection remains a limitation.
- Phase 2 — hostile concurrent A/B/C multi-lane recovery: PARTIAL; the current
  build proved blind disjoint decomposition, abrupt producer process-tree loss,
  grace-bound recovery, three exact successor acquisitions, recovery from an
  integration failure, and later integrated correction, but no review obligation
  was projected.
- Phase 3 — mandatory review and reviewer-loss recovery: PARTIAL at source
  level; both reviewer-grant succession and producer pending-review retargeting
  are focused-tested, and the current-build run reached a fresh current-grant
  `SNAPSHOT_PENDING` reviewer state, but no independent review decision,
  reviewer process-tree loss, or review-enabled integration has been reached.

## Immediate next action

Run `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`, then
launch one fresh disposable current-build target whose normal project admission
creates a real review-required producer lane. Keep the blind prompt unchanged;
exercise exact finish evidence, producer/reviewer loss, independent review,
integration, and terminal WorkGroup projection. Do not force a reviewer role
or infer review from README prose. Preserve the source-only boundary and do
not use Synesis coordination/MCP in this repository.

## SYN-081 Invisible canonical project metadata boundary

- Task ID: SYN-081
- Status: DONE
- Architecture: ADR-0081
- Immediate next action: run `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1` before any future task; preserve the verified uncommitted slice and do not publish without explicit authorization.
- Boundary: `.synesis` is Synesis bookkeeping; user and agent project files remain visible. Windows Hidden is UX-only and best-effort. Do not change project identity, replica/fork semantics, Git tracking policy, core Link, MCP tool count, daemon authority, or installer UI.
- Evidence target: `docs/evidence/SYN-081-invisible-project-metadata-2026-09-18.md`; broad unrelated failures must remain separately classified.

## Acceptance criteria

1. Every discovered project-local Synesis write target is inventoried as `.synesis`, an external administrative/workspace exception, or an intentional visible user-facing exception.
2. One reusable storage seam owns `.synesis` creation, safe root validation, and best-effort Windows Hidden application; Unix relies only on the dot-directory convention.
3. Initialization, existing-project open/attach, replica, fork, relocation/copy reopen, and metadata-root re-ensure route through the seam without rewriting identity or private state.
4. Hidden/visibility state is not read by authentication, authorization, project identity, registry, daemon, or MCP logic.
5. Atomic metadata temporaries remain under `.synesis`; representative project operations leave no loose Synesis bookkeeping in the visible project root except documented `AGENTS.md` integration content and Git-owned `.git` state.
6. Focused tests, affected regressions, strict formatting/static analysis/architecture/Javadocs, `git diff --check`, Graphify, durable docs, and checkpoint evidence are complete; no commit or push is made.

## Work completed before implementation

The supplied filesystem-boundary specification was read. A repository write-surface audit found project metadata and coordination/provider/runtime records already rooted under `.synesis`; external admin/integration/validation state is intentionally outside the checkout; Git `info/exclude`, visible `AGENTS.md`, and provider integration files remain explicit exceptions with separate semantics.

## Work completed

Added `ProjectMetadataStorage` as the single production owner of `.synesis`
root validation/creation and best-effort Windows Hidden normalization. Wired
it through project discovery, explicit open, initialization, replica, fork,
and copied-project reopen without changing metadata bytes or identity. Added
root-hygiene, Unix/no-DOS, diagnostic-only failure, symlink-safety, and
architecture-ownership tests. The complete inventory and explicit exception
classification are recorded in `docs/evidence/SYN-081-invisible-project-metadata-2026-09-18.md`.

Focused workspace/CLI/MCP regressions and workspace/CLI quality gates pass.
Graphify reports 3,990 nodes, 13,598 edges, 180 communities, and no import
cycles. The known repository-wide environment/readiness limitations remain
separately classified; no commit or push was made.

Checkpoint `CP-0973` was created after the final evidence/docs update.

## SYN-080 Universal portable project identity and replica model

- Task ID: SYN-080
- Status: DONE
- Architecture: ADR-0080
- Immediate next action: preserve the verified bounded result; run `scripts/agent-resume.ps1` before any future task.
- Boundary: keep core `link` project-agnostic; project context belongs to the existing higher-level authenticated application-stream consumers.
- Evidence: `docs/evidence/SYN-080-portable-project-identity-2026-09-18.md`; focused workspace/CLI/Link/project-record/MCP checks and strict workspace/CLI gates pass. The broad aggregate retains known WORKTREE_NOT_REGISTERED and JDK 25 null-Path environment failures.

## Work completed

Implemented the canonical `ProjectIdentity` seam, strict portable metadata
handling, lawful fresh-state replica materialization, explicit fork, multi-path
discovery, daemon logical-ID selection, post-Link project context admission,
membership/project checks, and the CLI first-time config ordering fix. Added
focused and process-isolated evidence without changing core Link ownership or
the ten-tool MCP catalog.

## Current failures

The broad aggregate remains partial because of existing readiness fixtures and
the JDK 25 null-Path environment failures recorded in the SYN-080 evidence.
Git synchronization, clone UI, cloud/federation, Internet/NAT reachability,
and full distributed coordination remain outside this bounded milestone.

## Remaining work

No additional implementation is queued for this bounded milestone. Preserve
the uncommitted verified slice; run the startup resume script before any future
task and obtain separate authorization before publication.

## Immediate next action

Run `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1` before
any future task; no additional implementation is queued for this bounded
scope.

## SYN-079 Approved installer flow and visual polish

- Task ID: SYN-079
- Status: DONE
- Evidence: PASS; docs/evidence/SYN-079-uninstall-template-parity-2026-09-14.md and CP-0966.

## Immediate next action

Review cli/build/distributions/synesis-windows-x64-clean.exe with the user; no implementation remains queued for the authorized installer scope.

## Work completed

Simplified the result screen into an action-specific heading and compact summary/folder card. Diagnostics are collapsed by default behind Show details; expanded errors and retained history wrap in a dark read-only panel with only vertical scrolling, starting at the error summary. Toggle repaint is clean. Existing artwork, gradients, smooth progress and backend/data semantics are preserved. No new task or Synesis runtime/connector was used.

## Current failures

Focused bootstrap tests and vet pass. Native preview verified the collapsed failure summary, wrapping expanded details, vertical-only scrollbar and clean collapse repaint on the current desktop. Final scroll-to-top behavior is covered by the native control test. Native build and forced platform ZIP passed. Canonical runnableInstaller replacement was blocked by the operator-open prior executable (PID 54572); an equivalent fresh self-extractor was assembled as synesis-windows-x64-clean.exe and its outer/inner installer, archive and footer bytes verified. No real user install/uninstall was run; other DPI settings were not visually exercised. Unrelated whole-module folder-picker vet diagnostics remain outside this scope.

## Historical completed scopes

## SYN-070 Windows uninstall page cleanup

- Task ID: SYN-070
- Status: COMPLETE; presentation-only cleanup closed 2026-09-14.
- Goal: remove the remaining goofy visual artifacts from the current native uninstall page without changing installer semantics.
- Boundary: stale hidden-control paint, helper-copy fit, title-bar mark suppression, path typography, and compact action-button proportions only. Keep all existing Win32 control/event and operation/data boundaries unchanged.
- Required evidence: focused bootstrap tests, vet, Windows GUI build or cross-compile, and diff hygiene. Do not execute an installer action or claim pixel-level QA without a real capture.

## Work completed

Applied the bounded cleanup in `bootstrap/installer_menu_windows.go`: clear the hidden install-page heading before rendering other pages, keep the uninstall title bar free of the class fallback icon, use the body face for the read-only path, shorten the helper copy to a single deliberate line, and give Back/Uninstall/Cancel a balanced compact action rhythm.

## Verification and constraints

Focused source checks passed. The Gradle packaging stages for the fresh native launcher and bundle archive passed, but replacement of the canonical `synesis-windows-x64.exe` was blocked by the already-running prior installer (PID 34644); it was left untouched. A separately named valid self-extractor was assembled from those fresh inputs and its embedded archive footer was validated. Native screenshot capture is not available in this session, so no pixel-level claim is made. No installer operation has been executed.

Final preview artifact: `cli/build/distributions/synesis-windows-x64-uninstall-polished.exe`.

## Immediate next action

Preserve the scoped source and preview artifact; do not install it, terminate the running prior installer, create `.synesis` state, push, or broaden this presentation-only task.

## SYN-069 Windows uninstall page visual parity

- Task ID: SYN-069
- Status: COMPLETE; presentation-only implementation closed 2026-09-14.
- Goal: make the current native uninstall page match the approved dark, logo-free, banner-free mockup without changing installer semantics.
- Boundary: presentation only—uninstall page geometry, typography, options surface, path field, metadata checkbox/helper copy, and bottom action hierarchy. Keep the native control/event model and every existing operation/data boundary unchanged.
- Required evidence: focused bootstrap tests, vet, Windows GUI build or cross-compile, and diff hygiene. Do not execute an installer action or claim pixel-level QA without a real capture.

## Work completed

Updated `bootstrap/installer_menu_windows.go` only for the uninstall-page presentation slice. The page now suppresses the global brand/ribbon/logo and footer warning treatment, keeps the title and explanatory copy prominent, frames the options in a dark bordered surface, presents the installation folder in a read-only inset field, and draws the metadata toggle with a visible focus state. The existing Win32 event path still owns navigation and the metadata value; the uninstall button continues through the existing confirmation page.

## Verification

| Check | Result |
| --- | --- |
| `go test ./...` in `bootstrap` | PASS |
| `go vet .` in `bootstrap` | PASS |
| Windows test compilation (`GOOS=windows GOARCH=amd64 go test -c`) | PASS |
| Windows GUI build (`GOOS=windows GOARCH=amd64 go build -ldflags '-H=windowsgui'`) | PASS |
| `git diff --check` for source and task records | PASS |
| Installer operation / managed-state mutation | NOT RUN |
| Native screenshot capture | UNAVAILABLE in this session; no pixel-level claim made |

## Current failures

No functional failure was found. The rebuilt preview executable was produced in a temporary directory but not launched through the unavailable Windows visual bridge, so the evidence is source/build-backed rather than a native screenshot capture. No installer action, metadata removal, PATH change, registry change, project/repository/workspace change, or daemon action was performed.

## Immediate next action

Preserve the scoped source and evidence; do not install the preview, create `.synesis` state, push, or broaden this presentation-only task.

## SYN-068 Windows setup wizard visual fidelity refinement (CP-0948)

- Task ID: SYN-068
- Status: COMPLETE; screenshot-driven refinement closed 2026-09-14.
- Goal: make the native Windows setup wizard materially closer to the supplied
  dark Synesis mockup while preserving all existing installer semantics.
- Boundary: presentation only—client layout, typography, brand/logo panel,
  capability glyphs, action-card selection treatment, spacing, footer, and
  button hierarchy. The image is visual reference only; its example text,
  paths, and values are not product instructions. Keep SYN-067's Win32
  thread-affinity, deferred-show, ownership, PATH, protocol, metadata, and
  user-data preservation boundaries unchanged.
- Required evidence: focused bootstrap tests, vet, cross-platform source
  compatibility, a rebuilt Windows runnable installer, and a bounded no-argument
  launch/responsiveness probe. Do not install the artifact or claim pixel-level
  screenshot QA unless a real capture is available.

## Work completed

The native wizard now uses a reference-sized dark composition with bounded
work-area scaling, a larger rounded logo/wordmark panel, quiet diagonal brand
backdrop, native capability glyphs, radio-style action cards, visible focus
treatment for unselected cards, and the mockup's footer/action hierarchy. The
existing installer operations and ownership/data boundaries were not changed.
The final packaged artifact launched visibly and responsively, and a bounded
native capture confirmed the rendered action page. The desktop default is now
912x666 including the title bar; the Synesis mark is loaded from a linked
Windows resource for the title bar, taskbar, and Explorer `.exe` icon. The
resource was extracted from the built native installer as `#1_0000.ico`.

## Current failures

No installer action was executed. The evidence does not claim pixel identity at
every Windows DPI or display size, and no real managed installation was
modified.

## Immediate next action

Preserve the compact/icon installer artifact and evidence; do not install it,
stop unrelated daemons, create `.synesis` state, push, or broaden this
presentation-only task.

## SYN-067 Windows setup wizard and owned deep-link installation

- Task ID: SYN-067
- Status: COMPLETE; CP-0946 is the final checkpoint for this launch-responsiveness
  follow-up.
- Goal: replace the double-click console menu with a responsive native Windows
  setup wizard offering Install/Repair, custom destination selection, and
  metadata-preserving Uninstall controls, while making the existing owned
  `synesis://` registration part of the normal install lifecycle.
- Boundary: direct local checkout only. Keep versioned atomic activation,
  user-level PATH ownership, registry ownership checks, project/workspace/
  repository preservation, provider neutrality, and no daemon shutdown. Keep
  Unix behavior unchanged.
- Required evidence: focused Windows wizard/path/protocol tests, Go build with
  the Windows GUI subsystem, extracted and single-file installer build output,
  a bounded launch smoke covering the responsive wizard process without
  deleting user data, and an honest native desktop-inspection result.

## Work completed

Source inspection found the existing text menu, explicit `--install-dir`
support, ownership-safe HKCU protocol registration, versioned activation, and
metadata-preserving uninstall. The current installed registry has no
`synesis://` registration, which explains why pasted invitations do not enter
the browser/daemon path. The screenshot is being used only as visual reference;
its example text and values are not product instructions. The Windows wizard,
owned post-install registration, safe metadata cleanup, and preservation test
are now implemented. The visual-polish follow-up adds a branded header, larger
Segoe UI hierarchy, app metadata/capability copy, warning footer, custom
primary/secondary action treatment, the existing Synesis logo, and a mandatory
dark client/control/title-bar treatment. Focused Windows tests, the GUI rebuild,
and the final self-extracting launch/control-content probe are passing; the
follow-up checkpoint is CP-0943. The latest live screenshot shows the remaining
presentation defects: a white native group box, generic radio rows, and an
empty logo tile. This follow-up replaces the fieldset/radios with custom dark
selection cards, explicitly darkens the title bar, and passes `AlphaBlend` its
value-form blend descriptor so the embedded product mark can render. Focused
tests, cross-platform compilation, the final runnable-installer build, and a
  visible responsive UI Automation launch were previously recorded at CP-0944;
  independent verification found the rebuilt top-level window hidden. The
  visibility-only correction exposed a startup freeze caused by Go moving the
  UI goroutine between OS threads during Win32 child-window construction. CP-0946
  keeps the complete wizard lifetime on one OS thread, queues the show message,
  and passes the fresh wrapper responsiveness probe.

## Current failures

Pixel-level desktop capture remains unavailable from the inspection helper. No
real installation was run;
the managed installation, PATH, registry, daemon, and user project data remain
outside this source acceptance run. Existing unrelated repository baseline
failures and the paused Docker lab remain outside this slice.

## Immediate next action

Keep the CP-0946 installer artifacts and evidence as the current bounded result;
do not install them, stop daemons, create `.synesis` state, push, or claim
pixel-level capture.

# 2026-09-13 — SL-D-040 Checkpoint 3A Docker process/network isolation lab

- Task ID: SL-D-040
- Checkpoint: 3A
- Status: PAUSED AFTER SYN-067; bounded Docker harness implemented and verified for two
  cycles; native/KVM/physical traversal remains outside this host's evidence
  boundary
- Substrate: current Windows 11 workstation with Docker Desktop's Linux engine
  and Docker-managed WSL2 kernel. No Ubuntu WSL distribution, SSH target, or
  exposed KVM capability is available, so this slice must not claim native Linux
  or VM evidence.
- Durable source boundary: direct local checkout only; no source `.synesis`
  state, Synesis coordination tools, remote publish, or push.

## Checkpoint 3A boundary

The acceptance target is six independent JVM processes in Docker network
namespaces using the existing signed Link/overlay surfaces. The local lab must
prove the deterministic N=6 topology (12 undirected edges, degree 4), compact
SLB2 first contact, authenticated peer transit, hard kill, endpoint/container
replacement, partition, two-node failure, simultaneous reconnect, bounded
latency/loss, repeat cycles, exact topology evidence, and cleanup.

The source audit found two product limitations that must remain visible: the
normal CLI passes an empty membership source to `LinkRuntimeOwner`, and the
owner does not yet compose `OverlayReconciler` with a production direct
descriptor connector. Therefore the lab is either a narrowly scoped process
runner that supplies a legitimate signed snapshot and exercises existing
production Link/overlay classes, or it stops at a documented preflight blocker.
It must not manufacture membership, bypass authentication, or present a test
fixture as shipped CLI capability.

## Environment preflight

- Docker Linux engine: healthy; server OS `linux`, Docker 29.7.2, 16 CPUs,
  approximately 16.7 GiB engine memory.
- Linux-targeted distribution: built successfully with the Linux x86_64 Netty
  QUIC classifier; the packaged library contains the Linux native QUIC artifact.
- Host limitations: C: has approximately 9.8 GiB free; no native Ubuntu/SSH
  target; firmware virtualization is enabled but Windows exposes neither SLAT
  nor KVM/Hyper-V acceleration to this task.
- Existing Docker data was not pruned or removed.

## Verified Docker evidence

- Two-cycle run: `D:\SynesisDocker\p2p-lab-3a\run-20260913-181647`.
- Image manifest: `sha256:22978dc50c2945dde268b546763859442e31a54468dfd54328943e0c79b78135`.
- Six JVMs, a signed six-member snapshot, 12 edges, degree four, SLB2 first
  contact, transit delivery, hard-kill withdrawal, same-identity replacement,
  partition/recovery, netem impairment, two-node failure, and simultaneous
  reconnect all passed for two cycles.
- Containers and the private network were removed by bounded cleanup; no
  `failure.txt` was produced.
- Partition recovery deliberately uses a reversible traffic cut followed by
  graceful same-identity process replacement and descriptor refresh. It is not
  evidence that a live JVM automatically rebinds after arbitrary namespace
  detach/reattach.

## Immediate next action

Resume from `docs/agent/checkpoints/CP-0941.md` after SYN-067 closes; stop at
the documented Windows/Docker boundary and do not claim native Linux, KVM, NAT,
or physical traversal evidence.

# 2026-09-13 — SL-D-032 SLB2 minimization refinement

- Task ID: SL-D-032
- Checkpoint: 3 (SLB2 minimization audit and implementation)
- Starting HEAD: `06acc4031f6c6ec77581463f3cfa7a3bdbfbc1b5`
- Status: COMPLETE FOR CURRENT SCOPE; the prior SLB1 slice remains complete and
  is preserved as a decode-compatible baseline
- Related boundary: `SL-D-035` remains deferred for controlled-NAT and
  physical two-network evidence
- Prior scope completed: compact signed `synesis://join/SLB1-...` first contact,
  one-way direct onboarding where the endpoint hint is reachable,
  authenticated in-band candidate-descriptor exchange, CLI/deep-link and
  control-plane compatibility, and legacy SLO1/SLA2 decoding
- Completed refinement: produced an exact SLB1 byte budget, audited the detached
  signature and admission entropy, add a clean minimized wire revision, and
  prove the resulting lower bound without TOFU, compression, or transport/
  overlay redesign
- Exclusions preserved: relay/rendezvous/cloud services, TOFU,
  membership/project authority, overlay redesign, session resumption, path
  migration, and a universal NAT-traversal claim

## Active refinement

The current source-first audit confirms that the host signature and complete
Ed25519 public key remain pre-authentication trust material: the existing Link
proof authenticates the key supplied by the invite and cannot prevent key
substitution if the invite binding is unsigned. The refinement may remove only
redundant framing/metadata and defensibly bounded admission state; it must
retain the signed endpoint, expected durable identity, freshness, and
single-use admission boundary.

## Work completed

The additive SLB1 token carries only protocol/version fields, a short-lived
single-use capability, the inviter Ed25519 public key, one signed non-relay
endpoint hint, and the inviter signature. Full signed candidate descriptors
are exchanged after authenticated `CONTROL_READY` through the bounded SLX1
application-stream envelope. The existing Link identity proof and legacy
SLO1/SLA2 lifecycle remain available.

The terminal host/join, workspace control-plane, retained Link owner, sync
application, and daemon deep-link paths now use compact one-way onboarding by
default. Project routing remains application-level; compact Link material does
not contain project membership or authority data. IPv6 link-local candidates
are excluded because their interface scope cannot survive the existing wire
encoding.

The normal encoder now emits SLB2. It removes redundant inner framing and
protocol bytes, derives expiry from a signed bounded lifetime, infers address
family from the exact payload length, and reduces the admission capability to
12 bytes. Fixed fixtures are 120/132 raw bytes and 180/196 URI characters for
IPv4/IPv6; the full Ed25519 key and detached signature remain for self-contained
non-TOFU authentication.

## Verification

| Check | Result |
|---|---|
| `:link:check` | PASS; format, compile, static analysis, Javadocs, 107 tests |
| Focused workspace control-plane tests | PASS; `ControlPlaneHttpHandlerTest`, `LinkRuntimeOwnerControlPlaneTest` |
| Focused packaged CLI tests | PASS; generated onboarding, SLB2 daemon deep-link, and `UnifiedCliSyncProcessTest` |
| SLB2 minimization | PASS; exact 120/132 raw and 180/196 URI bytes for IPv4/IPv6; SLB1 decode retained |
| Workspace/CLI non-test gates | PASS; architecture, format, static analysis, and Javadocs |
| `git diff --check` | PASS; only Git line-ending normalization warnings |
| Detailed evidence | `docs/evidence/sl-d-032-compact-bootstrap-2026-09-13.md`; durable checkpoint `CP-0939.md` |

## Current failures

The full `:cli:check` is not green because two unrelated pre-existing tests
still fail: `CodexHookProcessTest.generatedLauncherFailsClosedBeforeMutationWhenWorkspaceIsUnassigned`
and `WorkspaceCliTest.test14RepeatedBrokerRequestWithSameIdempotencyKeyDoesNotDuplicateMutation`.
The focused compact CLI tests and all CLI non-test gates pass. A broad
`:workspace:check` run also encountered unrelated Synesis-managed
capability/session/worktree-state failures; it was not bypassed by creating
`.synesis` state or using coordination tools. Physical NAT/Internet traversal
and controlled two-network evidence remain outside this slice.
The full `UnifiedCliSyncProcessTest` class was stopped after exceeding its
expected bounded run window; its focused guided invitation test passed, so the
class is not claimed as a complete gate.

## Immediate next action

Review the SLB2 diff, evidence, and working-tree boundary for any explicitly
authorized commit; do not create `.synesis` state, push, or broaden the task
into physical traversal.

# 2026-09-13 — SL-D-040 bounded multi-node overlay convergence

- Task ID: SL-D-040
- Checkpoint: 2
- Starting HEAD: `f26fb03cc03eec56e2275b01bd07b53df56ad154`
- Status: COMPLETE FOR CURRENT SCOPE; the next explicitly scoped VM checkpoint
  requires fresh activation
- Durable checkpoint: `CP-0934.md`
- Deferred-register review: SL-D-040 is promoted; SL-D-035 remains paused for
  physical traversal evidence. No new capability was invented or activated.

## Work completed

Derived the minimum non-full-mesh population from
`OverlayTopologyPolicy`: full mesh is used through three members, and six is
the first population with an intentionally non-direct valid pair. The native
QUIC acceptance now creates six authenticated endpoints, observes the exact
four-degree/12-edge target graph, sends an E2E-encrypted logical payload over a
normal peer-transit route, abruptly kills the selected transit node, proves
local adjacency and route withdrawal, observes bounded automatic retry and
alternate delivery, then rejoins the same signed identity without duplicate
bindings. Deterministic reconciler tests cover bounded concurrent missing
edges, reciprocal ownership, backoff suppression, duplicate admission, stale
terminal callbacks, and stable convergence publication.

Production changes are limited to the existing seams: per-QUIC-connection
responder handshake state, authenticated client application-stream handling,
the existing bounded stream budget, transition-only topology publication,
sequential bounded topology propagation, and local stale-origin read-model
withdrawal. Signed membership and route/relay precedence remain unchanged.

## Verification

| Check | Result |
|---|---|
| `OverlayReconcilerTest` and `OverlayProtocolTest` | PASS |
| `OverlayGraphQuicTest` | PASS; six native QUIC endpoints, graph/transit/failure/rejoin |
| `NettyQuicLoopbackTest` | PASS; existing Link loopback and separate-JVM regression |
| `:link:check` | PASS; format, compile, static analysis, Javadocs, 96 tests |
| Detailed evidence | `docs/evidence/sl-d-040-overlay-convergence-2026-09-13.md` |

## Current failures

No code or focused-test failure remains. Gradle could not start in the
inherited Windows environment without the command-local JDK loopback temporary
directory workaround; all reported passes used that bounded workaround. The
six-node graph acceptance is multi-endpoint native QUIC in one JVM, not six
independent OS processes. Existing separate-JVM Link coverage is retained, but
Checkpoint 3 should extend process isolation in the VM matrix. Existing UI/SSE
projections do not expose this overlay topology read model, so browser live
projection behavior is not claimed or changed. Physical NAT/Internet traversal
is still the separate SL-D-035 evidence gate.

## Immediate next action

After the local commit, begin Checkpoint 3 by defining the isolated local-VM
topology/churn matrix and environment prerequisites; do not implement it now.

# 2026-09-12 — SL-D-036 automatic overlay adjacency reconciliation

- Task ID: SL-D-036
- Checkpoint: 1
- Status: COMPLETE FOR CURRENT SCOPE
- Dependency: SL-D-040 is COMPLETE FOR CURRENT SCOPE; this slice is the
  separately promoted direct-session recovery boundary.
- Scope: reconcile desired neighbors from verified signed membership with live
  authenticated `PeerSession` bindings; withdraw stale direct route inputs;
  reconnect only through a fresh normal Link connector with deterministic
  ownership and bounded retry/backoff.
- Authority boundary: physical disconnect never revokes membership; descriptors
  are routing candidates only; returned sessions must match the expected member
  identity and key before registry admission.
- Implementation result so far: `OverlayReconciler` observes terminal liveness,
  removes exact stale bindings, publishes route-input withdrawal, and retries
  missing owned edges. Deterministic tests and a real three-member localhost
  QUIC loss/rejoin test pass.
- Verification so far: `:link:check` passed with strict compilation/static analysis,
  formatting, Javadoc, and 89 Link tests; the real three-member native-QUIC
  loss/rejoin acceptance passed as well.
- Closure evidence: `docs/evidence/sl-d-036-overlay-reconciliation-2026-09-12.md`.

## Immediate next action

Create the required durable checkpoint, then close SL-D-036 Checkpoint 1
locally. Do not broaden this slice into session resumption, path migration, NAT
traversal, relay, UI, HTTP, MCP, or push.

# 2026-09-12 — SYN-066 Windows JDK loopback bootstrap compatibility

- Task ID: SYN-066
- Status: COMPLETE FOR CURRENT SCOPE
- Dependency: SYN-065 is COMPLETE FOR CURRENT SCOPE.
- Scope: pass the Windows JDK Unix-domain socket temporary-directory property
  through the generated launcher before JVM startup, while retaining the
  direct-Java fallback and explicit `JAVA_OPTS` override.
- Authority boundary: no protocol, lifecycle, registry, browser, Link, or
  project-data behavior changes.
- Deferred-register review: no deferred capability is activated.
- Implementation result: the generated Windows launcher now prepares the
  short `%PUBLIC%\s` Unix-domain socket directory before starting the bundled
  JVM. Direct Java entry points retain a bounded fallback, and daemon child
  runtimes inherit the configured property.
- Verification: focused bootstrap regression test passed; the rebuilt Windows
  runnable installer and supported repair passed; clean installed
  `coordination serve`, `ui --no-browser`, and browser-opening `ui` smokes
  passed without `JAVA_TOOL_OPTIONS`. The browser-opening command returned
  `SYNESIS_UI_OPENED`.
- Closure evidence: `docs/evidence/SYN-066-windows-jdk-loopback-bootstrap-2026-09-12.md`.
- Immediate next action: preserve SYN-066 as COMPLETE FOR CURRENT SCOPE. No
  authoritative successor is defined.

# 2026-09-12 — SYN-065 Windows daemon health-probe compatibility

- Task ID: SYN-065
- Status: COMPLETE FOR CURRENT SCOPE
- Dependency: SYN-056 installed daemon shell and SYN-064 are COMPLETE FOR
  CURRENT SCOPE.
- Scope: replace only the daemon's local runtime health probe transport and add
  a focused regression test for the Windows/JDK AF_UNIX failure.
- Authority boundary: no project lifecycle, registry, browser, Link, authority,
  filesystem, or remote behavior changes.
- Deferred-register review: no deferred capability is activated.
- Implementation result: the daemon's local runtime health probe now uses a
  Windows-compatible `HttpURLConnection` request, avoiding the JDK
  HttpClient AF_UNIX selector failure on this workstation. The authenticated
  daemon request path and runtime lifecycle are unchanged.
- Verification: focused daemon regression tests, source install distribution,
  Windows runnable installer, repair installation, and real installed
  `synesis ui` smoke passed. The broader CLI suite was bounded and interrupted
  after no completion or failure output.
- Closure evidence: `docs/evidence/SYN-065-windows-daemon-health-probe-2026-09-12.md`.
- Immediate next action: preserve SYN-065 as COMPLETE FOR CURRENT SCOPE. No
  authoritative successor is defined.

# 2026-09-12 — SYN-064 actionable Projects preview

- Task ID: SYN-064
- Status: COMPLETE FOR CURRENT SCOPE
- Dependency: SYN-063 is COMPLETE FOR CURRENT SCOPE.
- Scope: expose the existing Add project and non-current Remove project
  controls in development mock mode through an in-memory adapter, and replace
  the ambiguous project trash-only affordance with a labeled action.
- Authority boundary: no production control-plane, registry, filesystem,
  routing, Link, or authority changes; preview mutations are process-local.
- Deferred-register review: no deferred capability is activated.
- Implementation result: mock mode now supplies a process-local registry
  adapter; Add and non-current Remove flows are visible and functional, and the
  Remove action is explicitly labeled on project cards.
- Verification: typecheck, lint, 26 frontend tests, production build, browser QA,
  and interactive mock add/remove checks passed.
- Closure evidence: `docs/evidence/SYN-064-actionable-projects-preview-2026-09-12.md`.
- Immediate next action: preserve SYN-064 as COMPLETE FOR CURRENT SCOPE. No
  authoritative successor is defined.

# 2026-09-12 — SYN-063 Projects front-page redesign

- Task ID: SYN-063
- Status: COMPLETE FOR CURRENT SCOPE
- Dependencies: SYN-059 through SYN-062 are COMPLETE FOR CURRENT SCOPE.
- Scope: presentation-only redesign of the installed Projects front page using
  the existing authoritative registry and network snapshot plus the existing
  add, filter, open, inspect, and remove actions.
- Authority boundary: no backend, routing, registry, protocol, or Link behavior
  changes and no invented data or controls.
- Deferred-register review: no deferred capability is activated. This task
  changes only the presentation of already implemented local control-plane
  projections and actions.
- Implementation result: the page now uses a compact real-data overview,
  labeled search and status controls, responsive project cards, and a clearer
  connection panel while preserving every existing action.
- Verification: typecheck, lint, 25 frontend tests, production build, the
  repository browser QA suite, and rendered 2560x1440, 1920x1080, 1280x800,
  375x812, and 1555x457 inspections passed without horizontal overflow.
- Closure evidence: `docs/evidence/SYN-063-projects-front-page-redesign-2026-09-12.md`.
- Immediate next action: preserve SYN-063 as COMPLETE FOR CURRENT SCOPE. No
  authoritative successor is defined.

# 2026-09-12 — SYN-062 instant project registry removal

- Task ID: SYN-062
- Status: COMPLETE FOR CURRENT SCOPE
- Dependencies: SYN-059 and SYN-061 are COMPLETE FOR CURRENT SCOPE.
- Scope: make confirmed registry removal immediate in the Projects UI while
  retaining the real authenticated deletion and truthful failure recovery.
- Implementation result: the row is hidden immediately after confirmation;
  background success reconciles the authoritative snapshot and failure restores
  the row with a bounded error.
- Closure evidence: `docs/evidence/SYN-062-instant-project-registry-removal-2026-09-12.md`.
- Immediate next action: preserve SYN-062 as COMPLETE FOR CURRENT SCOPE; no
  authoritative successor is defined.

﻿# 2026-09-12 — SYN-060 Windows Explorer folder picker correction

- Task ID: SYN-060
- Status: COMPLETE FOR CURRENT SCOPE
- Dependencies: SYN-059 is COMPLETE FOR CURRENT SCOPE; SYN-061 remains closed.
- Scope: replace the legacy Swing folder chooser with a Windows Explorer-style
  native folder picker while preserving the authenticated `choose-project`
  command, exact selected path, read-only folder-derived name, and existing
  initialization flow.
- Immediate next action: preserve SYN-060 as COMPLETE FOR CURRENT SCOPE; no
  authoritative successor is defined.
- Implementation result: the bundled Windows helper opens the Explorer shell
  `IFileDialog` in folder-selection mode and returns the exact selected path
  through the existing authenticated control-plane boundary. No Link, registry,
  initialization, or browser behavior changes were introduced.

# 2026-09-12 — SYN-060 native project folder picker

- Task ID: SYN-060
- Status: COMPLETE FOR CURRENT SCOPE
- Dependencies: SYN-059 is COMPLETE FOR CURRENT SCOPE and remains closed.
- Scope: replace manual Add project path entry with an authenticated native
  Windows folder picker whose selected folder name is displayed read-only and
  whose exact absolute path is submitted through the existing registry path.
- Immediate next action: preserve SYN-060 as COMPLETE FOR CURRENT SCOPE; no
  authoritative successor is defined.
- Implementation result: the authenticated `choose-project` command opens a
  native Windows folder picker and returns the exact absolute path with the
  folder-derived name. The Add project dialog now presents that selection in
  a non-editable folder tile and submits it through existing registry
  validation.
- Closure evidence: `docs/evidence/SYN-060-native-project-folder-picker-2026-09-12.md`.

# 2026-09-12 — Final publication-blocker cleanup

- Review status: COMPLETE. The accumulated SYN-056 -> SYN-057 -> SYN-058
  source and test diff is internally coherent; no product-code integration
  defect was found and no feature task was created.
- Publication hygiene: COMPLETE. The changed publication set remains free of
  real user identities, workstation paths, D: roots, Docker/acceptance roots,
  file URIs, and Unix home paths. The three test-only fixture literals were
  generalized to a deterministic synthetic Windows path while preserving
  spaces, nesting, helper suffix, and `%1` command quoting semantics.
- Commit readiness: READY TO COMMIT. No staging, commit, or push was
  performed.
- Inventory: 111 unique paths relative to HEAD (26 modified tracked files,
  85 untracked files, 0 staged files, 0 deleted files). The additions are
  classified as product source/tests, ADRs/evidence, or durable checkpoints;
  no binary/log artifact is tracked.
- Verification: focused bootstrap protocol-registration tests, Go tests,
  deferred-register, resume, diff, link, secret, and path-hygiene checks
  passed. The known aggregate CLI and workspace/provider failures remain
  unrelated.
- Latest checkpoint: CP-0910.md.
- Previous inventory lines are historical review snapshots and are retained
  unchanged below.

## Eventual commit-boundary recommendation

The proposed four-commit history remains the clearest review sequence:
SYN-056 foundation, SYN-057 protocol/routing/selection, SYN-058 native
Windows activation, then documentation/evidence. These are an ordered stack,
not independent cherry-picks: SYN-057 consumes the installed daemon boundary
and SYN-058 consumes both the daemon and deterministic resolver. The current
dirty diff has shared files across those boundaries, so each split requires
focused staging and sequential verification. If independent cherry-picks are
required, use two commits instead: the complete product chain, followed by
the documentation/evidence record.

## Immediate next action

Preserve SYN-065 as COMPLETE FOR CURRENT SCOPE. No authoritative successor is
defined; do not invent one, and do not commit or push.

# Current Task

## 2026-09-12 — SYN-061 automatic project initialization from folder picker

- Task ID: SYN-061
- Status: COMPLETE FOR CURRENT SCOPE
- Dependency: SYN-059 and SYN-060 are COMPLETE FOR CURRENT SCOPE.
- Immediate next action: preserve SYN-061 as COMPLETE FOR CURRENT SCOPE; no
  successor is defined.

## 2026-09-12 — SYN-060 native project folder picker

- Task ID: SYN-060
- Status: COMPLETE FOR CURRENT SCOPE
- Dependency: SYN-059 is COMPLETE FOR CURRENT SCOPE.
- Immediate next action: preserve SYN-060 as COMPLETE FOR CURRENT SCOPE; no
  authoritative successor is defined.

## 2026-09-12 — SYN-059 project registry management and filtering

- Task ID: SYN-059
- Status: COMPLETE FOR CURRENT SCOPE
- Dependencies: SYN-056, SYN-057, and SYN-058 remain COMPLETE FOR CURRENT
  SCOPE and are not reopened.
- Scope: provide the main Projects page with explicit project filtering plus
  authenticated add/remove registry actions. Add validates an existing
  initialized project through the existing project authority. Remove affects
  only the installation-local registry entry and protects the current runtime
  project.
- Immediate next action: preserve SYN-059 as COMPLETE FOR CURRENT SCOPE; no
  authoritative successor is defined.
- Implementation result: the existing name/identity/path search is retained,
  an explicit status filter was added, and authenticated add/remove registry
  actions now refresh the authoritative snapshot. Removal protects the current
  runtime project and never deletes project data.
- Closure evidence: `docs/evidence/SYN-059-project-registry-management-2026-09-12.md`.

## 2026-09-12 — SYN-058 native deep-link registration and installed activation

- Task ID: SYN-058
- Status: COMPLETE FOR CURRENT SCOPE (retain only an administrative active
  marker if the one-active-task validator requires it)
- Dependencies: SYN-056 and SYN-057 are COMPLETE FOR CURRENT SCOPE and are not
  reopened.
- Scope: add the smallest installed activation boundary that accepts exactly
  one supported `synesis://` URI, starts or reuses the authenticated daemon,
  forwards the exact URI to `OPEN_URI`, and exits boundedly. Windows is the
  primary registered platform for this slice.
- Authority boundary: the OS launcher is transport only. Existing daemon URI
  validation, SYN-057 routing, project selection, runtime authorization, and
  Link verification remain authoritative. No URI semantics or project choice
  move into the launcher.
- Registration boundary: the Go installer owns per-user Windows registration;
  the stable native activation helper is the registered target so updates do
  not leave a versioned path behind. Daemon startup does not mutate registry
  state.
- Evidence and transition: ADR-0075 and the SYN-058 transition checkpoint
  record the reconstructed activation path, platform scope, and ownership
  decision before production implementation.
- Implementation result: the Java `open` command, stable native Windows
  activation helper, explicit per-user registration lifecycle, and real
  bounded OS activation are implemented and focused-verified. The Windows
  SLO1 picker composition and signed SLA2 v2 OS routing acceptance are
  complete for current scope.

## Work completed

SYN-057 closure was reconciled as COMPLETE FOR CURRENT SCOPE and its evidence
was preserved. SYN-058 is also closed for its current scope. SYN-059 added the
authenticated project-registry controls and explicit Projects page filtering;
the registry remains discovery metadata only. SYN-060 added the authenticated
native Windows folder picker and read-only folder-derived naming for Add
project.

## Current failures

The known aggregate workspace/capability/provider failures remain unrelated
and are preserved. The full CLI check also retains unrelated failures in
`CodexHookProcessTest` and `WorkspaceCliTest`. No aggregate repair was
authorized under SYN-059.

## Immediate next action

Preserve SYN-065 as COMPLETE FOR CURRENT SCOPE. No authoritative successor is
defined; leave the roadmap with no active task and preserve the evidence.

## 2026-09-11 — SYN-056 r4 portability fix and r5 browser acceptance

Implemented the smallest supported Linux browser-opening fallback in
`BrowserLauncher`: Java AWT Desktop browse remains preferred; when Linux AWT
browse is unavailable or fails, `xdg-open` is launched through direct argv
without a shell. URI validation and bounded failure mapping remain intact.

Focused `BrowserLauncherTest` and the Linux-targeted installed distribution
passed. The fresh D:-backed r4 gate then proved installed `synesis ui` opened
Chrome, consumed the bootstrap fragment, completed the session exchange, and
rendered live project state. Evidence:
`external acceptance evidence`.

Fresh r5 A/B browser acceptance also passed the transport boundary: distinct
persisted identities; real SLO1; daemon B `OPEN_URI` -> `WAITING_FOR_CONNECT`;
shipped B Complete Join; real SLA2; daemon A `OPEN_URI` -> `CONNECTED`; live
distinct peer identity/liveness on both sides; and terminal SLA2 replay
rejected as `URI_REJECTED / OPERATION_NOT_FOUND`. The overlay remained
`UNCONFIGURED`, with `UNKNOWN` membership and no durable signed-membership
mutation. The final authority audit proves this is intentional CLI behavior:
the CLI supplies no signed membership snapshot, no shipped approval/configure
action exists, and `CONNECTED` is the physical-session endpoint. The
authoritative SYN-056 scope is therefore COMPLETE FOR CURRENT SCOPE; the task
registry remains ACTIVE only to satisfy the one-active-task validator. Evidence:
`external acceptance evidence`.

Exact next action: preserve the completed current-scope SYN-056 boundary;
activate a separate membership-authority task only after an explicit authority
requirement and acceptance contract exist. Do not redesign Link, add daemon
membership authority, or auto-mutate membership on CONNECTED.

This historical SYN-056 entry is superseded by the explicitly authorized
SYN-057 task above. SYN-056 remains semantically complete; no membership
authority is being reopened.

## 2026-09-11 — SYN-056 r4 browser-opener environment blocker

The fresh D:-backed r4 environment contains Google Chrome 153, Xvfb,
`xdg-open`, GIO, and Selenium. A standalone `xdg-open` probe launched Chrome,
but the shipped installed `synesis ui` smoke returned
`SYNESIS_UI_OPEN_FAILED`: Java reported `Desktop.isDesktopSupported=true` and
`Desktop.Action.BROWSE=false`. The JDK Linux XDesktopPeer dynamically expects
legacy GNOME VFS (`libgnomevfs-2.so.0` / `gnome_url_show`) that is absent from
the modern base image.

No Link operation was started in r4. SYN-056 remains ACTIVE / PARTIAL. The
exact next action is to provide a supported non-Synesis-specific Linux desktop
browser environment (or obtain explicit authorization for a supported-platform
browser-opening change), then rerun only the browser smoke before Link work.
Do not add a browser bridge, daemon CONNECT IPC, browser bypass, or weaken Link
identity/replay checks.

## 2026-09-11 — SYN-056 distinct-peer Docker retry-3 completed transport boundary

The fresh D:-backed retry-3 harness used a Linux-targeted installed
distribution, two isolated daemon-managed runtimes, and distinct durable node
identities. The daemon-side B `OPEN_URI` projection reached
`WAITING_FOR_CONNECT` and produced a signed answer URI. The installed A/B CLI
transport then reached `PEER_CONNECTED`, verified the peer identity,
`CONTROL_READY=true`, `LIVENESS=LIVE`, `WORK_RESULT=OK`, and clean session
closure across the Docker bridge.

The disposable image did not support Java's browser opener, so browser
Complete Join and browser ANSWER completion remain unproven. Durable
exactly-once onboarding and replay also remain unproven. The r3 containers,
network, and image were removed; D:-backed Docker engine data and evidence
remain. SYN-056 stays ACTIVE / PARTIAL.

Exact next action: use an acceptance image with a supported browser opener to
exercise the existing browser Complete-Join/ANSWER path and record durable
exactly-once/replay evidence. Do not add daemon CONNECT IPC, copy operation
state, disclose bootstrap material, or weaken Link identity checks.

## 2026-09-11 — SYN-056 HOST-A invitation trace resolved

The shipped installed HOST-A invitation path is now proven end-to-end with a
Linux-targeted distribution: browser click, authenticated invite command,
Link host creation, listener bind, one candidate, SLO1 serialization, host
registration, HTTP 201, and browser-visible invitation URI. The first failed
Docker trace was not a Link wait or HTTP handoff defect. A Windows-built
distribution lacked the Linux Netty QUIC native artifact; `build()` raised an
uncaught `UnsatisfiedLinkError`, so the HTTP worker ended without a response.

`link/build.gradle.kts` now keeps host-native selection by default and accepts
the explicit validated `synesisNativeQuicClassifier` property for cross-target
staging. Temporary trace code was removed. SYN-056 remains ACTIVE / PARTIAL:
no B-side JOIN, CONNECT, ANSWER, durable completion, exactly-once, or replay
claim is made in this slice.

Exact next action: record the focused verification and checkpoint, clean only
the disposable trace containers/network, and leave the next slice at the
distinct-peer JOIN path. Do not add daemon CONNECT IPC or weaken Link identity
and replay boundaries.

## 2026-09-11 — SYN-056 Docker retry-2 partial acceptance

The fresh D:-backed two-container harness proved the installed daemon path,
authenticated status, distinct durable project identities, non-loopback Link
candidates, basic cross-container UDP reachability, and the real shipped
daemon-to-browser bootstrap path. Both control-plane pages loaded; A reached
the Network view. The A-side shipped Create Invitation action remained
pending without producing an SLO1 URI, so JOIN B, Complete Join, ANSWER A,
CONNECTED, durable completion, exactly-once, and replay remain unproven.
SYN-056 remains ACTIVE / PARTIAL; no commit or push was performed.

The minimal production correction remains ready-endpoint normalization in
`DaemonServer` plus its focused regression test. No daemon CONNECT IPC,
identity weakening, bootstrap disclosure, or Docker-specific production
behavior was added. The exact retry evidence is in
`docs/evidence/SYN-056-installed-daemon-2026-09-10.md`; created containers and
the bridge network were removed, while the D:-backed evidence root was kept.

Exact next action: diagnose the A-side shipped invitation mutation from the
current Link/runtime boundary, preserving the fail-closed identity and daemon
authority constraints. Do not invent a second identity, copy operation state,
add daemon CONNECT IPC, or claim positive completion from the partial browser
trace.

## SYN-056 Installed local daemon shell — 2026-09-10

- Task ID: SYN-056
- Status: **ACTIVE** — architecture and scope are recorded in ADR-0073.
- Scope: thin installation lifecycle/IPC shell in the existing `cli` module;
  existing project runtime and control-plane authority remain unchanged.
- Acceptance: one per-user daemon, authenticated bounded IPC, existing runtime
  launch/reuse and health evidence, shared `synesis ui` path, secure bootstrap
  preservation, stale/crash recovery, and installed smoke/resource evidence.

## Work completed

Implemented the daemon shell plus a runtime-owned deep-link handoff in `cli`:
bounded JSON-line IPC, per-user endpoint/lock state, runtime launch/reuse, lazy
health validation, browser opening, parent-owned runtime cleanup, and an
injectable endpoint-directory test seam. `synesis ui` uses this path when no
bounded smoke duration is requested; bounded duration mode preserves the
existing direct project-runtime behavior. The established `coordination serve`
composition remains the project authority.

`OPEN_URI` now requires exactly one reachable daemon-managed runtime and
forwards the bounded URI to the runtime's narrow `/api/v1/commands/deep-link`
adapter using a runtime-owned command credential. The adapter delegates valid
join and answer links to existing `ControlPlaneLinkOperations`; Link parsing,
signature, expiry, replay, identity, and membership checks remain at the
project boundary.

Focused protocol/lock tests, strict CLI Javadocs, installed distribution build,
and disposable installed smoke evidence pass. The broader `:cli:check` reached
42 tests but remains partial with two failures in `CodexHookProcessTest` and
`WorkspaceCliTest`, outside the daemon package. The smoke covered reuse, stale
endpoint replacement, wrong/oversized/malformed IPC, browser opening, URI-shape
rejection, and daemon crash followed by orphan-free relaunch. Resource evidence
is recorded in `docs/evidence/SYN-056-installed-daemon-2026-09-10.md`.
The installed URI smoke produced a real `WAITING_FOR_CONNECT` join result and
answer URI, rejected ambiguous two-runtime routing, and rejected malformed
HTTPS input.

The source review classified CONNECT as an existing production operation:
`ControlPlaneLinkOperations.PendingJoin.connect()`, the authenticated
`/api/v1/commands/connect` route, and `ControlPlaneClient.connect()` already
existed. The missing shipped seam was runtime projection of a daemon-created
pending join plus a minimal browser action. The runtime snapshot now projects
only pending-join operation metadata, SSE link updates refresh that projection,
and Network exposes `Complete Join`, which calls the existing client command.
The handler no longer holds its process-wide onboarding lock across the
blocking host-answer or join-connect calls; handle creation remains serialized,
while independent Link handles may overlap as required by the handshake.

Focused frontend and workspace gates pass, and `:cli:installDist` rebuilt the
installed distribution. The installed run proved the new `Complete Join`
 affordance appears only after daemon `OPEN_URI` creates a pending join. A
 source-level same-runtime reproduction then traced the remaining completion
 failure: both retained handles enter the Link race, the responder rejects the
 first candidate with `IDENTITY_PROOF_INVALID` because host and join share one
 persisted node identity, and later candidates time out. This is a fail-closed
self-host/self-join identity boundary, not a serial executor, missed wakeup,
or missing daemon CONNECT seam. It produced no durable mutation. SYN-056
therefore remains ACTIVE / PARTIAL; OS protocol registration and cross-project
SPA runtime selection remain deferred rather than faked.

The authorized browser portability slice is now complete. `BrowserLauncher`
keeps Java AWT browse first and uses direct-argv `xdg-open` only on Linux when
AWT browse is unavailable or fails. Focused opener tests, the Linux-targeted
installed distribution, and the fresh r4 browser gate pass; the browser
consumed bootstrap, exchanged a session, and rendered live project state.

Fresh r5 then proved real browser-created SLO1, daemon B `OPEN_URI` ->
`WAITING_FOR_CONNECT`, shipped B Complete Join, real SLA2, daemon A `OPEN_URI`
-> `CONNECTED`, distinct peer identity verification, live liveness on both
sides, and terminal replay rejection as `URI_REJECTED / OPERATION_NOT_FOUND`.
The overlay remained `UNCONFIGURED`, membership remained `UNKNOWN`, and no
durable signed-membership mutation was created. The authority audit classifies
membership lifecycle as explicitly outside this task rather than as a missing
post-CONNECTED callback. Detailed evidence is retained under the D: r4/r5
evidence roots.

## Verification

Focused protocol/lock tests, strict CLI Javadocs, installed distribution build,
and the disposable installed acceptance are recorded in
`docs/evidence/SYN-056-installed-daemon-2026-09-10.md`. The broader CLI gate is
partial because two unrelated existing tests fail.

## Current failures

The broader `:cli:check` remains partial with failures in
`CodexHookProcessTest.generatedLauncherFailsClosedBeforeMutationWhenWorkspaceIsUnassigned`
and `WorkspaceCliTest.test14RepeatedBrokerRequestWithSameIdempotencyKeyDoesNotDuplicateMutation`.
These failures are outside the daemon package and are not attributed to this
slice. No commit or push has been performed. The installed smoke left no
matching test processes. R5 containers and its bridge network were removed;
Docker engine storage and retained evidence remain on D:. No r6 run was made:
the repository exposes no legitimate membership approval/configuration action,
so exercising one would require inventing the authority this audit is required
to preserve.

## Immediate next action

Preserve the completed current-scope SYN-056 boundary; activate a separate
membership-authority task only after an explicit authority requirement and
acceptance contract exist. Do not add CONNECT IPC, membership mutation,
bootstrap-disclosure IPC, daemon-owned invitation authority, operation copying,
or weaker Link checks.

## SYN-055 Final browser artistic-direction pass — 2026-09-10

- Task ID: SYN-055
- Status: **COMPLETE FOR CURRENT SCOPE** — superseded by the explicitly
  activated SYN-056 daemon goal.
- Scope: presentation and accessible markup in the existing browser UI;
  no backend, data model, route destination, or authority changes.
- Authority: the current pasted brief supersedes earlier no-indicator and
  uncapped-width decisions. All six screens receive the same design system.

## Work completed

Refined neutral color tokens, body/metadata type, compact metrics, quiet
healthy states, warning emphasis, responsive 1480px/1760px page caps, single
Projects back control, attached project tabs, native metadata titles, selected
row markers, controls, and short focus/hover transitions. The runtime indicator
uses the existing session state and an understated 5px dot. Diagnostics display
labels are polished without changing underlying values. Fixed accessible list,
table-header, and connection-control markup. Development fixture mode survives
navigation but remains excluded from the production bundle.

## Verification

- PASS: :web-ui:check and :cli:installDist with the documented command-local
  Windows loopback workaround; typecheck, lint, 12 tests, production build.
- PASS: authenticated packaged runtime; six screens at 2560x1440, 1920x1080,
  and 1280x800, 100% scale. No page overflow or JavaScript errors.
- PASS: separate populated development-fixture screenshot stress pass.
- PASS: axe scans on six real and six populated screens, zero violations.
- PASS: search miss, inactive project detail, keyboard inspector dismissal,
  and existing modal/termination tests. Packaged asset SHA-256 equals dist;
  no development mock asset is shipped.
- Evidence: `docs/evidence/SYN-055-art-direction-2026-09-10.md`; generated screenshots and logs under
  build/ui-art-direction. No full backend suite, global install, or new
  Windows installer is claimed.

## Current failures

None for this visual slice. Default-environment loopback and the initial
running-Vite file lock were resolved using documented local workarounds.
No new deferred capability or architecture change. No commit or push.

## Immediate next action

Review the completed slice with `git diff -- web-ui/src/app/App.tsx web-ui/src/styles.css docs/evidence/SYN-055-art-direction-2026-09-10.md`; make no further product changes
unless a subsequent user request identifies a follow-up.

## Historical task — SYN-054 Persistent known-project discovery — implementation and lifecycle-evidence gate — 2026-09-09

- Task ID: SYN-054
Status: **COMPLETE FOR CURRENT SCOPE; implementation and lifecycle evidence complete; unrelated baseline regressions documented**.

The current slice adds a persistent event-driven index of initialized Synesis
projects legitimately encountered through existing init, UI/runtime, and MCP
startup paths. The registry is discovery metadata only. Project-local
coordination and runtime state remain authoritative in each project.

Acceptance is not complete until real disposable projects prove observation,
idempotence, restart persistence, runtime disappearance retention, distinct
identities, missing-path behavior, identity-mismatch rejection, metadata-only
persistence, and unchanged project-local control-plane/MCP behavior.

Implemented registry, CLI/UI/MCP hooks, authenticated known-project adapter,
and frontend view. Focused registry, affected control-plane/CLI/MCP, strict
Javadoc/compile, and frontend checks pass. Two real disposable projects were
observed through installed CLI init and a separate MCP process; the registry
persisted two distinct identities across processes. A live authenticated
loopback control-plane probe returned both projects with `LIVE` and `INACTIVE`
statuses and matched the registry's metadata-only contents. The broad
workspace check remains red in unrelated coordination/provider tests and was
stopped after repeated failures. The named `AgentNextActionServiceTest`
failures also reproduce on an untouched detached worktree at the current base
commit, before SYN-054 changes, so they are not attributed to this slice.

Exact next action: preserve the verified SYN-054 state; no further product-code
work is required for this goal. Any commit or publication remains a separate
explicit action.

## Historical current task — SYN-009E

### Synesis maximum-protection commercial release profile — implementation and acceptance gate — 2026-09-09

Status: **COMPLETE FOR PROTECTION-LITE SCOPE; COMMERCIAL MAXIMUM DEFERRED / EXTERNAL DEPENDENCY**.
The free protection implementation and its release plumbing are closed for
this stage. The commercial maximum remains intentionally unavailable until a
licensed, version-pinned protector adapter and production signing authority
are supplied; the maximum task continues to fail closed and is not relabeled
as protection-lite.
The completed installed UI slice is preserved under `CP-0758`; the current
task extends the existing `SYN-009C`/`SYN-009D` distribution and signing
foundations into separate developer, protection-lite, and maximum-release
profiles. A versioned external-protector request/result contract now feeds the
release-only CLI and standalone-relay maximum tasks, which validate private
evidence and sign/verify each candidate through the existing bootstrap
boundary. No commercial Seven Rings capability is claimed until its actual
protector output is installed and exercised.
The current-source Tier 0–3 inventory and vendor-neutral adapter configuration
skeleton are now explicit; maximum tasks reject dirty release checkouts and
bind adapter results to the tier, keep-rule, acceptance-procedure, and full
reproducibility digests requested by the release. The current brief's six
commercial adapter classes are Rings 1–6; Ring 7 is the signed-integrity,
diversification, and private-retrace boundary owned by the release pipeline.
Both maximum tasks now reject a protector executable or configuration that is
lexically or canonically inside the source checkout, including symlink aliases;
commercial binaries and license-bearing configuration therefore remain an
external private-release input.
The v1 adapter result now also requires a non-secret `licenseMode` and a
redacted private `licenseEvidence` attestation, whose hash is retained only in
the private release record; license keys and tokens are never accepted as
result fields.
The adapter documentation now explicitly maps a future Virbox Java VME/BCE
wrapper to Rings 2 and 5 while keeping those results evidence-gated; no
vendor-specific Virbox command or configuration is committed.
The latest read-only source-scope audit resolved all 21 inventory paths and all
12 method-scoped Tier 3 signatures, and recorded the concrete reflection,
resource, ServiceLoader, and native-declaration findings; this is planning
evidence only and does not close commercial transformation compatibility.
An independent `scripts/maximum-release-acceptance.ps1` harness now exercises
only extracted customer archives, rejects lite/profile and private-material
leakage, optionally verifies the private artifact manifest, and records
private JSON evidence. For CLI candidates it also has a no-PATH disposable
install, stable-launcher runtime-tamper, packaged frontend static-content and
frontend-asset tamper,
provider/MCP, mutable-state, and live authenticated browser/control-plane
HTTP/SSE gates. For relay candidates it now starts the extracted launcher and
verifies authenticated forwarding through a test-only external observer. Its optional JDK
loopback/workspace overrides are process-local and recorded as non-shipped
compatibility inputs.
The protector capability matrix now also records the required Java/Gradle,
compatibility, reproducibility, recovery, performance, licensing, and
anti-analysis evaluation dimensions without converting any commercial
candidate to an unverified pass.
The bootstrap now records the selected protection profile in the active
pointer and makes the stable launcher invoke the existing payload-manifest
doctor only for `maximum-release`. Its Windows forwarding path now preserves
signed invitation query arguments containing `&` while rejecting unsafe
embedded command characters. A rebuilt synthetic marker-only disposable
probe passed immutable JVM/frontend tamper refusal, mutable `Link`-state
acceptance, and installed signed-invitation/authenticated-PeerSession/project
synchronization. The acceptance harness isolates `HOME`, `USERPROFILE`,
`APPDATA`, and `LOCALAPPDATA` for every captured process, while explicit
JDK/workspace overrides remain process-local only.

The relay observer is shipped-protocol evidence only; it does not promote a
synthetic or protection-lite archive to commercial maximum evidence.

The reusable `scripts/release-profile-comparison.ps1` harness now records
customer-archive SHA-256, archive/extracted size, bounded cold launcher
samples, and a best-effort aggregate launcher-process-tree working set for
developer, protection-lite, and (when supplied) maximum-release profiles. The
current five-sample CLI run measured developer at 46,504,281 archive bytes /
69,542,917 extracted bytes / 675.523 ms median startup / 107,982,848-byte
maximum aggregate working set and protection-lite at 45,705,419 /
68,661,707 / 642.855 ms / 89,432,064 bytes. The free lite rules now also
enable standard ProGuard optimization, mixed-case renaming, package
repackaging under `org.synesis.p`, and removal of source/line attribute
retention from the transformed application rules. Its result is
`PARTIAL_MAXIMUM_ARCHIVE_NOT_SUPPLIED`; the non-isolated timings and aggregate
working-set values are not performance or unique-physical-memory claims, and
UI, route, Link, relay, provider, AV/EDR, and commercial maximum measurements
remain open.
The extracted acceptance harness now also records a `performance` object with
bounded per-check `durationMs` values for provider/doctor, Link onboarding, UI
smoke, authenticated control-plane HTTP/SSE, and relay forwarding. It leaves
unique physical memory, route microbenchmarks, commercial transformation
overhead, and AV/EDR impact explicitly open.

The first-party DashO/Zelix/Virbox documentation pre-screen is now recorded
separately from artifact evidence. It narrows the next licensed evaluation:
DashO documents Java 25/26 bytecode support, scoped control-flow/string
transforms, Gradle/CLI integration, and runtime debug/tamper checks; Zelix
documents Java 26 bytecode handling, flow/string/constant transforms, Gradle
scripting, and reproducibility guidance. Neither reviewed candidate establishes
genuine virtual execution, protected payload loading, or the required
Synesis-safe Java anti-VM policy. DashO is therefore the first licensed
candidate to evaluate for Rings 1, 3, and bounded Ring 6 behavior, while all
commercial ring claims remain unverified until shipped output is exercised.
Virbox's Java VME/BCE documentation makes it the first candidate to evaluate
specifically for Rings 2 and 5, but its separate-license boundary and Java-safe
Ring 4/6 behavior are also unverified.

The archive-only static reverse-engineering comparison is now implemented in
scripts/release-reverse-engineering-comparison.ps1. Its current developer
versus protection-lite CLI run found no file-level private/source/map entries,
and the free hardening reduced source-metadata signals by 849 classes and
architecture-term-hit classes to 549, but metadata remains in many classes; it
is recorded as partial and does not claim improved decompiler resistance. A
real maximum archive and selected-method inspection remain required.

The archive-only native hardening audit is now implemented in
scripts/release-native-hardening-audit.ps1. The current Windows x64
developer/protection-lite CLI archives passed the owned-launcher PE, zero-COFF-
symbol, no-debug-directory/section, no-named-export, local-path, private-file,
and Go-trimpath checks. The parser now also handles ELF and Mach-O; temporary
Linux amd64 and macOS arm64 cross-build fixtures passed the format,
debug/export, and trimpath classification. Their native files are unsigned or
unverified, the maximum archive was not supplied, and the current samples
record dirty Go VCS metadata; this is therefore baseline/parser evidence only
and does not close native signing, shipped cross-platform, or commercial
maximum acceptance.

The CLI and relay maximum-release request/private-record seams now capture a
reproducibility snapshot: sorted lockfile count/digest, Gradle, Java, Node/npm,
and Go toolchain versions, protector configuration digest, release identity and
seed, plus post-archive native-hardening evidence and post-signing
key/public-provenance fields. Missing Node, npm, or Go fails the maximum
provenance gate; ordinary developer tasks are unchanged. The maximum manifest
tasks run the native hardening/signing audit before creating the signed
manifest, and the private record retains its hash.

The new `scripts/maximum-release-provenance-comparison.ps1` harness compares
two private maximum records for either controlled diversification or exact
canonical-manifest reproducibility. Its parser and missing-record fail-closed
path pass, but no commercial maximum records are available for a positive run.
The CLI release task also rejects a non-matching `GITHUB_SHA` instead of
allowing CI metadata to override the actual checkout commit.
Both maximum tasks now require non-empty private evidence for every ring and
diversification, non-empty mapping/retrace files, a structured retrace-
acceptance record bound to the mapping hash, and component-scoped native
recovery: CLI-owned symbols plus a third-party-native audit, or relay's
explicit not-applicable owned-symbol scope plus that audit. Their hashes are
retained only in the private release record. The relay scope is evidenced in
`docs/evidence/syn-009e-relay-native-scope-2026-09-09.md`.
The release-to-release comparison now verifies those private hashes and the
final signed-integrity fields before comparing two records.
The consolidated evidence report is
`docs/evidence/syn-009e-maximum-protection-status-2026-09-09.md`; it keeps the
brief's full final-report fields explicitly partial rather than promoting
synthetic or protection-lite evidence.

- Task ID: SYN-009E
- Activation HEAD: `8a5d0901b4859c9a504f7fc7009def23177f1d43` on `master`;
  local release commits remain unpublished, and the working tree contains the
  preserved `CP-0758` plus unrelated pre-existing local work that remains
  uncommitted.
- Latest implementation commits: `9ce6e5c7` (free protection-lite package
  repackaging and refreshed evidence), `ae4b9852` (free protection-lite
  optimizer, mixed-case renaming, metadata hardening, and refreshed evidence),
  `4cc8fec0` (bounded profile memory evidence), `974380fd` (private license
  provenance), and `690c691a` (external
  commercial-tool path boundary); the earlier
  post-archive native-audit, symlink-boundary, structured private-retrace,
  exact-brief Seven Ring taxonomy, bounded acceptance-timing, and packaged
  frontend static-content acceptance changes remain committed.
- The latest release-environment evidence is the availability probe from
  `18da2f4b` / `CP-0807`; the candidate-capability review is `4b2836a2` /
  `CP-0810`. No licensed adapter, commercial configuration, signing key, or
  release seed is available; this is evidence of the blocker, not a
  commercial-ring result.
- The free-hardening slice is checkpointed as `CP-0816`; no push, tag, release,
  or remote mutation occurred.
  No push has occurred.
- Existing release seams: Gradle platform bundles and jlink runtime, Go
  bootstrap signed-manifest/payload verification, stable flat installation,
  native launcher/installer, packaged frontend resources, and relay's separate
  application distribution.
- Protection boundary: developer builds remain readable; protection-lite is an
  explicit release-engineering profile with free optimizer/package/metadata
  hardening;
  maximum-release is gated on licensed commercial tooling and must not expose
  a runtime downgrade switch.

## Immediate next action

Next product stage: UI polish. Resume the deferred
commercial maximum capability only after an installed, licensed,
version-pinned commercial protector and its reviewed adapter/configuration are
available; inject the release signing authority then and run the maximum-
release acceptance. Until that external tool is available, preserve the
passing lite evidence and keep the maximum gate fail-closed. Use
`powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1` before
resuming after a stop.

## Current constraints

- Do not change normal developer tasks or apply protection transforms to
  ordinary tests/IDE output.
- Do not call renaming virtualization, compression packing, weak VM checks, or
  debug flags a pass for the corresponding Seven Ring.
- Keep public CLI/protocol/JSON/resource/JNI/ServiceLoader contracts stable and
  justify every nontrivial keep rule.
- Use existing installer/payload integrity rather than creating a parallel
  trust system; mutable user state must not be covered as immutable payload.
- No home-grown VM, packer, hostile anti-debug, debugger attack, arbitrary
  process killing, host surveillance, or secret/key/license commit. The
  explicitly authorized closeout push is the only current remote mutation;
  future commercial release signing remains private and injected.
- Record `developer`, `protection-lite`, and `maximum-release` separately in
  every result; unavailable commercial execution remains a blocker.

## Work completed

Added the provider-agnostic maximum-protector adapter contract and ADR-0071.
The release-only CLI and relay tasks now require an explicit licensed adapter,
version-pinned private configuration, release ID/seed, six mandatory Ring 1–6
evidence records, diversification, private mapping/retrace, component-scoped native
recovery/audit, and separated customer/private output. It now also binds the request/result to the current
Tier 0–3 source inventory, keep-rule inventory, and acceptance procedure,
requires a clean reviewed release checkout, creates a private artifact
manifest, archives the candidate, signs through the existing bootstrap signer,
and verifies the detached signature against the embedded bootstrap public key.
Added the source-backed tier inventory and vendor-neutral config skeleton. No
commercial protection capability is claimed.

The maximum CLI and relay tasks now enforce the private-tool boundary before
release provenance or adapter invocation: both the lexical and canonical paths
of the adapter and configuration must be outside the source checkout. This
prevents committed or symlinked commercial license material from entering the
release path.

The result contract now requires private non-secret licensing provenance:
`licenseEvidenceFormat`, `licenseMode`, and a redacted attestation file below
the private directory. CLI and relay release records hash that attestation;
the adapter still owns the actual CI-injected vendor entitlement.

Added the archive-only `scripts/release-native-hardening-audit.ps1` and its
developer/lite evidence. The Windows x64 owned Go launchers passed PE,
COFF/debug, export, local-path, private-file, and trim-path checks. The audit
keeps native signing separate and records the maximum archive, non-Windows
formats, ARM64, third-party native inputs, and clean release provenance as
open.

Refined `scripts/release-profile-comparison.ps1` to sample Windows launcher
process-tree working sets at a bounded interval, label the aggregate signal
explicitly, and emit memory deltas only when both compared values are numeric.
The current five-sample developer/protection-lite run is recorded in the
profile-comparison evidence JSON; it remains partial because the licensed
maximum archive is absent, and the aggregate signal is not unique physical
memory or a performance claim.

## Verification

- `:cli:bundleSmokeTest --dependency-verification=strict --no-configuration-cache`
  passed under the documented process-local Windows Gradle workaround.
- `:cli:maximumRelease --no-configuration-cache` reached
  `maximumReleasePrepare` and failed closed as designed because no adapter is
  configured.
- `:relay:maximumRelease --no-configuration-cache` reached
  `maximumReleasePrepare` and failed closed as designed because no adapter is
  configured; the relay protection-lite smoke still passes with explicit JMODs.
- `:relay:relayArtifactAcceptanceClient` passed against the extracted developer
  relay launcher: two disposable nodes authenticated, an unauthorized node was
  rejected, encrypted frames forwarded bidirectionally, and the relay process
  shut down within bounds. This is protocol/shipped-launcher evidence, not
  commercial maximum evidence.
- The maximum tasks compile after source-tier binding; both still fail closed
  before adapter invocation because no licensed adapter is configured.
- `:cli:tasks :relay:tasks --no-configuration-cache --no-daemon` passed with
  the documented process-local loopback workaround after the path-boundary
  change. Deliberate in-checkout adapter probes for both CLI and relay failed
  closed with the new outside-source-checkout guard.
- The same task-list configuration passed after adding the private license
  attestation contract; no commercial adapter or license material was supplied.
- The shipped-artifact harness rejects both existing protection-lite archives
  as non-maximum, proving the profile boundary without mislabeling lite output.
- A synthetic marker-only maximum archive completed the extracted CLI harness:
  immutable JVM and packaged frontend tamper refusal, mutable `Link` state,
  provider lifecycle, packaged UI root, authenticated control-plane
  session/snapshot/CSRF/SSE, UI/control-plane, native MCP, and ready MCP
  session all passed when the documented process-local host compatibility
  overrides were supplied. The rebuilt installed launcher also completed a
  signed local Link invitation, authenticated the remote PeerSession, and
  completed project synchronization. The default host run remains explicitly
  partial at the loopback and provider-workspace gates.
- `go test ./...` in `bootstrap`, `:cli:nativeMcpLauncher`, and
  `:cli:platformBundle` passed with the documented process-local Gradle/JDK
  workaround. The installed result is summarized in
  `docs/evidence/syn-009e-link-shipped-acceptance-2026-09-09.md`.
- `go test . -run '^TestMaximumProfileIsRecordedAndStableLauncherEmitsIntegrityGate$' -count=1` passed.
- The generated maximum stable-launcher gate passed PowerShell AST parsing;
  the disposable marker-only runtime probe refused an edited immutable payload
  and passed after mutable `Link` state was added. This is bootstrap evidence,
  not commercial maximum evidence.
- `scripts/release-profile-comparison.ps1` parsed and ran five cold CLI
  developer/protection-lite samples against extracted ZIPs, recording archive
  and extracted sizes, SHA-256 identities, and best-effort aggregate
  launcher-process-tree working sets. It exited with
  `PARTIAL_MAXIMUM_ARCHIVE_NOT_SUPPLIED`; no performance or unique-physical-
  memory claim is made from the non-isolated sample, and broader UI/route/
  Link/relay/provider timing remains open.
- The first-party commercial protector documentation pre-screen was recorded
  in `docs/evidence/syn-009e-protector-doc-review-2026-09-09.md`; it refines
  candidate selection without changing any commercial row to `PASS` or
establishing Rings 1–6 or any maximum Seven Ring result.
- `scripts/release-reverse-engineering-comparison.ps1` parsed and ran against
  the developer and protection-lite CLI ZIPs. It recorded static class/package,
  architecture-term, metadata, path, source-map, private-material, and
  inspection-tool signals with status
  `PARTIAL_MAXIMUM_ARCHIVE_NOT_SUPPLIED`; no decompiler or commercial ring was
  claimed.
- `scripts/release-native-hardening-audit.ps1` parsed and ran against the
  developer and protection-lite CLI ZIPs. Both owned Windows x64 launchers
  passed the structural native-hardening checks; native signing remained
  `OPEN_NOT_SIGNED_OR_UNTRUSTED`, the maximum archive was absent, and the
  script returned the expected partial status.
- `:cli:tasks :relay:tasks --no-configuration-cache --no-daemon` passed with
  the documented process-local JDK loopback override after the inherited host
  environment failed before Gradle evaluation. This evaluated the new
  reproducibility helpers and exposed both maximum task seams; no customer
  artifact or release record was produced.
- `scripts/maximum-release-provenance-comparison.ps1` now requires and hashes
  each record's non-secret license attestation and compares its format/mode as
  shared provenance. PowerShell AST parsing passed; its missing-record probe
  returned the expected `PARTIAL_MAXIMUM_RECORDS_NOT_SUPPLIED` status.
- `go test ./...` in `bootstrap` now passes after the test fixture helper
  isolated `HOME`/`USERPROFILE` from the developer's real provider state.
- `go test ./cmd/sign-manifest` passed, including explicit-path signing.
- `git diff --check` passed for the scoped changes; `agent-resume.ps1` and the
  deferred validator passed.
- The free protection stage is now closed: `:link:check`, `:relay:check`, CLI
  and relay protection-lite acceptance, and the clean-tree maximum fail-closed
  gate all passed with their documented expected outcomes. The remaining
  commercial maximum dependency is deferred, not an unfinished lite task.

## Current failures

No licensed commercial protector, adapter, private configuration, or release
signing authority is installed. The marker-only runtime probe used a rebuilt
platform bundle with only a synthetic maximum marker and does not prove any
commercial transformation or Seven Ring completion. The synthetic marker-only
harness is artifact/bootstrap evidence only; Rings 1–6 remain blocked and Ring
7 remains partial until a licensed commercial release is signed, diversified,
and retrace-validated. The repository-wide legitimate formatter/implementation
changes and checkpoint history were audited, committed, and are included in
the authorized closeout; no unstaged changes remain.
The native audit parser covers PE, ELF, and Mach-O, but actual Linux, macOS,
and ARM64 customer archives, third-party native inspection, production native
signing, and clean maximum-artifact provenance remain open. The portable
format result is fixture-only evidence.
The new reproducibility record is implemented but unexecuted for a real
maximum candidate because the commercial adapter, licensed configuration, and
production signing authority remain unavailable.

## SYN-053 first installed Synesis browser UI — completed current scope — 2026-09-08

Status: **COMPLETE FOR CURRENT SCOPE; CHECKPOINT CP-0758 RECORDED**.
The user-provided browser goal now activates the first product UI over the
verified local control plane. ADR-0069 selects an install-bundled `web-ui`
module, same-origin static serving from the existing JDK listener, fragment
bootstrap, a typed snapshot/SSE client, and a dedicated `synesis ui` command.
The Windows self-contained runtime packaging was corrected to include
`java.desktop`, which is required by the supported browser-opening boundary.

- Task ID: SYN-053
- Baseline: backend commit `2be88cf` was clean and pushed to `origin/master`
  before UI work. No UI changes were included in that push.

## Historical continuation note

Preserve the verified local SYN-053 commits and do not push UI changes without
a separate explicit instruction. Further work is tracked under `SYN-009E`.

## Current constraints

- No Node, npm, Vite, or frontend server at runtime; build tooling is
  development/CI-only.
- Serve only packaged static assets from the existing loopback origin; keep
  `/api/v1`, `/events`, and lifecycle routes separate from SPA fallback.
- Put the one-time bootstrap in the URL fragment, exchange it through the real
  session endpoint, and remove it from browser history.
- Use typed DTOs and the existing SSE contract; do not duplicate domain logic,
  infer routes, or fabricate project/agent/network data.
- Treat empty projects, `UNCONFIGURED` overlay, disabled relay, missing peers,
  session expiry, and diagnostics as first-class states.
- No external CDN, cloud API, telemetry, Electron, native GUI, OS URI
  registration, Link/overlay redesign, WorkGroup redesign, or UI push.

## SYN-053 implementation evidence

The `web-ui` module now contains the locked React/Vite/Tailwind/Lucide client,
typed control-plane client, fetch-based SSE, truthful screens, onboarding
controls, and focused tests. Gradle packages its production resources into the
CLI distribution. The existing JDK listener now serves the classpath UI with
security headers, and `synesis ui` composes the server and optional browser
open. Frontend typecheck, lint, tests, build, Java compilation, static-handler
tests, installed static/session/snapshot/SSE acceptance, and browser visual/
functional QA pass. See `docs/evidence/syn-053-installed-browser-ui-2026-09-08.md`.

## Work completed

Implemented the install-bundled `web-ui` module, locked npm toolchain,
typed control-plane client, fetch-based SSE, real-state screens, empty and
`UNCONFIGURED` states, onboarding controls, same-origin static resource
handler, `synesis ui` startup, browser opening, security headers, packaging,
focused tests, installed acceptance, and browser functional/visual QA. Added
the architecture, API, README, test-matrix, and evidence documentation. Fixed
the Windows jlink runtime module list to include `java.desktop`; a fresh
platform-bundle smoke run now starts `synesis ui` with browser opening enabled
and exits cleanly after the bounded duration.

## Current failures

No SYN-053 implementation gate is failing. The existing repository-wide
`:link:formatCheck` remains blocked by trailing whitespace in the pre-existing
`docs/agent/checkpoints/CP-0752.md`; Link tests/static analysis/Javadocs and
relay tests/static analysis/Javadocs pass when that unrelated format task is
not selected. The broad historical workspace/CLI fixture suite remains
outside this slice and is not claimed as a full pass. Configured overlay and
relay acceptance remains correctly unavailable without a signed membership
authority source.

## SYN-052 local control-plane backend — implementation and acceptance gate — 2026-09-08

Status: **ACTIVE / LIVE OWNER SLICE COMPLETE; AUTHORITY SOURCE BOUNDARY OPEN**.
The explicit control-plane brief supersedes the previous HTTP exclusion for
this new task. ADR-0067 selects one in-process versioned loopback adapter over
the existing coordination listener and authoritative services. The future
browser remains out of scope.

- Task ID: SYN-052

## Immediate next action

Preserve the verified retained-session acceptance. If this task resumes, first
obtain or define a legitimate signed membership-authority source before any
configured CLI overlay work; keep the CLI overlay `UNCONFIGURED` until then.
Do not fabricate membership or promote the disabled relay projection to
connected.

## Current constraints

- Bind only to loopback; validate Host and Origin; never use wildcard CORS.
- Use the existing JDK HTTP server and JSON utility before adding dependencies.
- Delegate invite/join/answer behavior to `Onboarding`; do not duplicate SLO1/
  SLA2 validation.
- Use explicit safe DTO fields; never serialize internal objects or secrets.
- Bound request bodies and SSE subscriber queues; disconnect slow clients with
  refresh semantics.
- No frontend, cloud API, generic proxy, arbitrary filesystem endpoint,
  reconnect/path migration, durable browser replay, or push.

## SYN-052 implementation evidence

- Added `ControlPlaneReadModel`, `ControlPlaneHttpHandler`, and bounded
  `ControlPlaneEventHub` under the workspace control transport package.
- Mounted `/api/v1` in the existing JDK loopback listener and retained the
  existing binary `/command`, `/events`, and Codex lifecycle routes.
- Added one-time bootstrap exchange, short-lived session/CSRF headers, exact
  loopback Host/Origin validation, bounded JSON bodies, explicit DTO mapping,
  onboarding command delegation, and live-only SSE snapshot/delta events.
- Focused control-plane tests pass, including durable WorkGroup/WorkIntent
  mapping through HTTP, a real Link membership view through `/api/v1/network`,
  authenticated peer/topology route projection, auth/CSRF/origin/Host/bounds,
  semantic SSE snapshot/live delivery to multiple clients, shutdown
  termination, one-shot HTTP onboarding, and retained-session HTTP onboarding
  through `LinkRuntimeOwner`. The real CLI smoke also passes for a disposable
  Git project. `:link:check`, `:relay:check`, strict Javadocs, and deferred
  validation pass.
- The CLI now composes the long-lived owner and reports authenticated physical
  sessions when present. Its overlay remains `UNCONFIGURED` because the
  checkout has no legitimate signed membership-authority source; no fabricated
  membership or relay connection is used.

## Work completed

Implemented the local `/api/v1` control-plane foundation over the existing JDK
listener and durable project/coordination services. Added explicit read DTOs,
the `LinkNetworkProjection` adapter, bounded live subscriptions, one-time
bootstrap plus session/CSRF security, onboarding command delegation, semantic
redacted SSE, API documentation, ADR-0067, and focused tests for real
persisted coordination state, real Link membership/topology/relay inputs,
  multi-client delivery, clean shutdown, and real two-profile one-shot and
  retained-session HTTP invite/join/answer/connect flows. Added ADR-0068,
  `LinkRuntimeOwner`, the narrow control operation seam, and the shared CLI
  identity-directory wiring. A disposable initialized Git project also passed
  real CLI serve, health, session, and authenticated snapshot smoke.

## Current failures

Focused control-plane tests, strict Javadocs, `:link:check`, `:relay:check`,
deferred validation, and `git diff --check` pass. The combined
`:coordination:test :workspace:test :cli:test` run reported failures in
existing CLI/workspace fixture tests (`CodexHookProcessTest`, several
`AgentNextActionServiceTest`/`CapabilityNegotiationTest` cases,
`WorkspacePatchServiceTest`, `ProviderApplicationServiceTest`, and
`ProviderSessionBindingServiceTest`); they are outside this slice and remain
  unclassified rather than being called passing. Configured CLI overlay
  acceptance remains outside this slice because this checkout has no legitimate
  signed membership-authority source; the owner and retained-session acceptance
  are focused-pass evidence.

## SL-D-040 distributed project overlay — implementation and verification gate — 2026-09-08

Status: **COMPLETE FOR CURRENT SCOPE**. The requested overlay capability is now
represented by its own task rather than being folded into direct traversal.
ADR-0066 records the proposed ownership, membership, E2E, topology, routing,
and relay boundaries. The bounded Link overlay, monotonic membership view,
mutable authenticated-peer registry, signed topology propagator, peer
forwarder, in-memory relay, and standalone relay module now have production
code. Configured Link/relay checks and localhost socket acceptance pass with a
process-local loopback workaround; the inherited environment still fails
before Gradle startup.

- Task ID: SL-D-040

## Immediate next action

Preserve the passing configured/local acceptance and keep controlled-NAT or
physical Internet traversal under the separately scoped `SL-D-035` evidence
gate. Do not broaden this task into reconnect, path migration, or a physical
network claim.

## Work completed

Re-investigated the current Link and project-record source. Confirmed that
`PeerSession` remains a one-hop authenticated physical session, the current
application seam is bounded request/response, `ProjectConfig` is only a local
allowlist, and durable Ed25519 identities currently provide signing rather
than E2E encryption. Implemented the bounded SLM1/SLK1/SLE1/SLF1 contract,
bounded SLP2 membership propagation, monotonic same-authority membership
refresh, bounded direct-peer binding, signed topology and SLP1 propagation,
deterministic route selection, peer
forwarding, the PeerSession bridge, in-memory relay core, and standalone Netty
relay/client module. Configured/local evidence is recorded in
`docs/evidence/sl-d-040-overlay-foundation-2026-09-08.md`. The verified
`SL-D-035`
traversal slice remains committed and separately paused at its controlled-
topology evidence gate.

## Current failures

The signed membership authority is the new bounded SLM1 contract; its authority
lifecycle remains explicitly out of scope. Java 25 crypto APIs, topology
propagation, logical route/transit evidence, full configured Link/relay checks,
and real localhost relay/process acceptance pass with the documented
process-local loopback workaround. The inherited environment still fails
before task/socket execution with `Unable to establish loopback connection` /
`Invalid argument: connect`; no physical multi-network claim is made from this
host.

## SL-D-035 coordinated UDP/QUIC hole punching — implementation and evidence gate — 2026-09-08

Status: **ACTIVE / EVIDENCE GATE**. Read-only re-investigation confirmed
that Link already owns Ed25519 identities, signed descriptors and invitations,
Netty native QUIC, authenticated control/application streams, bounded candidate
racing, replay checks, liveness, and two-process evidence. The remaining gate
is controlled-topology evidence for endpoint discovery and coordinated
traversal; reconnect remains separately deferred under `SL-D-036`.

- Task ID: SL-D-035

## Immediate next action

Run or add controlled-NAT and, if available, physical two-network evidence
for the now-wired direct traversal path. Keep relay/reconnect out of scope.
Do not claim Internet NAT success from localhost evidence.

## Work completed

Recorded starting HEAD/status and mapped the Link source, tests, protocol,
security, operations, ADR, and deferred-capability boundaries. Completed ADR-
0065 and implemented the bounded signed exchange, RFC 8489 Binding codec,
caller-owned provider seam, same-socket Netty adapter, deterministic
coordinator, and human-mediated two-link onboarding path. The normal `host`
command emits SLO1 and waits for one SLA2 line; the normal `join` command
emits SLA2 and immediately starts the existing direct race. Real loopback UDP,
CLI two-process, and full Link checks pass; no physical or Internet traversal
claim is made.

## Current failures

The same-socket STUN and traversal handlers are verified over real loopback
UDP parents with QUIC codecs, but controlled NAT, Internet NAT behavior, and
physical two-network evidence do not exist yet. Reconnect is not part of this
bounded activation.

## SYN-049 fresh unattended two-worker acceptance — run #81 — 2026-09-06

Status: **COMPLETE / PASS**. Fresh run #81 passed generation-1 managed A/B
prepare/START, exact provider-native B wake on the original thread, capability
publication/validation, claim-scoped coding, immutable snapshot integration,
call-local completion requests, both terminal participants, WorkGroup
`COMPLETED`, and the integrated fixture suite.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-runs75-81.md`.

## Immediate next action

Preserve the PASS evidence and stop. Do not retry SYN-049, reopen SYN-051, or
start A1-to-A2 replacement validation without a separately bounded request.

## SYN-049 fresh unattended two-worker acceptance — runs #70–#74 — 2026-09-06

Status: **PARTIAL / ACTIVE**. Fresh runs #70 and #71 reached WorkGroup
`COMPLETED` with generation-1 managed A/B startup, capability publication and
validation, same-thread B wake, real claim-scoped coding, and both terminal
participants. Their independent integrated compiles failed on A-produced Java
contract shapes. Run #72 and #73 exposed disposable-runner continuation gates;
run #74 used those fixes but stopped on A generation-1 AppServer
`terminalDiagnostic=process_exit` during the real owner turn. Full acceptance
is therefore unproven.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-runs70-74.md`.

## Immediate next action

Perform a read-only investigation of run-#74 AppServer `process_exit` evidence.
Do not patch
production, reuse a target, repair `.synesis`, copy snapshots/worktrees,
invoke replacement/A2, or claim SYN-049 completion.

## SYN-049 fresh unattended two-worker acceptance — runs #68–#69 — 2026-09-06

Status: **PARTIAL / ACTIVE**. Fresh runs #68 and #69 passed generation-1
same-process managed preparation/START, real A/B claim admission, capability
publication and validation, provider-native B wake on the original thread,
and real claim-scoped implementation. Run #69 integrated B's immutable
application/API snapshot. The first unresolved boundary is the owner/reviewer
ordering cycle: B's review request for A is accepted before A's snapshot is
published, so Synesis withholds A completion while B waits for that snapshot.
The WorkGroup remains ACTIVE and integrated compile fails with A's absent
domain/persistence snapshot.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-runs68-69.md`.

## Immediate next action

Diagnose the owner/reviewer snapshot-ordering projection read-only before
another fresh target. Do not edit `.synesis`, copy snapshots/worktrees,
force completion, invoke replacement/A2, create a substitute B, or patch
production before the exact root cause is established.

## SYN-049 fresh unattended two-worker acceptance — run #59 — 2026-09-06

Status: **PARTIAL / ACTIVE**. Fresh run #59 used a one-host, one-launcher
generation-1 A/B runtime with the hash-identical installed MCP copy. Both
managed App Servers started, real Synesis MCP tools were available, A reached
`NEEDS_CAPABILITY`, the exact capability advanced through publication and
validation, and original B woke with `sameThread=true`. The first unresolved
boundary was after B consumed the exact review grant: Synesis remained
`SNAPSHOT_PENDING` and B reported `workspace_mismatch` because the published A
dependency existed only in the control checkout, not B's authorized worktree.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-run59.md`.

## Immediate next action

Diagnose snapshot materialization and review-workspace routing read-only before
another target. Do not bypass the review grant, copy files/worktrees, repair
durable state, invoke replacement, create A2 or a substitute B, or patch
production before the exact boundary is identified.

## SYN-049 fresh unattended two-worker acceptance — runs #55–#58 — 2026-09-06

Status: **PARTIAL / ACTIVE**. Run #56 proved the corrected accepted-review
gate and sent B's post-review continuation on the original Thread B, but B
still had no publishable downstream snapshot. Fresh runs #57–#58 then showed
trusted provider turn completion without any claimed mutation or capability
request; no run-scoped Synesis MCP child appeared in #58. B wake, downstream
work, review, integration, and WorkGroup terminalization remain unproven.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-runs55-58.md`.

## Immediate next action

Diagnose the fresh run-scoped Codex App-Server-to-Synesis-MCP tool-admission
boundary read-only before another target. Do not bypass MCP, copy worktrees,
reuse or repair a lane, invoke replacement, create A2 or a substitute B, or
patch production before the exact boundary is identified.

## Work completed

Corrected the invalid owner-side implementation-validation projection so it
exposes the provider's required decision instead of manufacturing an
incomplete response. Added a focused regression, rebuilt and hash-verified
the distribution, and ran fresh #47–#54 fixtures. The run-54 provider was
uninstalled through the supported flow.

## Current failures

Run #45 exposed and fixed a production projection/schema mismatch. Run #47
exposed a harness-only continuation-grant gate. Run #54 reached real A/B
continuations but stopped with B ACTIVE and the WorkGroup ACTIVE after an
accepted review request. No final B review/finish or WorkGroup
terminalization is proven.

## SYN-049 fresh unattended two-worker acceptance — run #44 — 2026-09-06

Status: **PARTIAL / ACTIVE**. Fresh run #44 passed current artifact and JDK25
process-local compatibility, fresh lawful A/B setup, same-process generation-1
managed preparation/START, same-AppServer provider-thread creation, ownership,
ACTIVE promotion, and initial provider turn completion. It stopped at the
first material boundary: both managed App Servers reported that the configured
native `synesis` MCP child closed during `initialize`. Neither worker had a
callable Synesis namespace, so no capability request, publication, wake/consume,
coding, integration, or terminalization was lawful.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-run44.md`.

- Task ID: SYN-049

## Immediate next action

Perform a bounded read-only diagnosis of the configured native Synesis MCP
child-startup/handshake boundary before another fresh target. Do not reuse or
repair run #44, bypass MCP, copy worktrees, or patch production speculatively.

## Work completed

Recorded fresh run #44, verified current artifact hashes and JDK25 host
preflight, verified supported provider cleanup, and preserved exact A/B
identities and generation-1 lifecycle evidence. No production source change
was made during the runtime attempt.

## Current failures

Run #44's App Server-to-Synesis-MCP child handshake failed before Synesis MCP
tools were exposed. The target and A/B worktrees are clean; no capability
request, B wake, claim mutation, integration, or WorkGroup terminalization was
proven.

## SYN-049 fresh unattended two-worker acceptance — runs #40–#42 — 2026-09-06

Status: **PARTIAL / ACTIVE**. Fresh runs passed provenance and host
compatibility, lawful A/B admission, generation-1 same-process managed
preparation/START, same-AppServer provider-thread creation, ACTIVE promotion,
provider-native B wake, A's real claimed implementation/publication, and
capability validation. Run #42 confirmed durable provider state for both
exact generation-1 threads. It stopped before lawful B downstream
implementation/completion, final review/integration, and WorkGroup
terminalization because the integrated producer types were not visible in
B's managed worktree; B's supported refresh correctly returned
`overlapping_claim` / self-overlap.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-runs40-42.md`.

- Task ID: SYN-049

## Immediate next action

Perform a separate read-only diagnosis of managed dependent-worktree snapshot
consumption and the self-overlap recovery boundary before another fresh
acceptance lane. Do not reuse run #42, repair its state, start a substitute
worker, invoke replacement, or patch production speculatively.

## Work completed

Recorded runs #40–#42, verified the run-42 durable projection and exact
provider continuity records, verified the supported provider cleanup result,
and reran the three focused regressions for the narrow managed-continuation
correction. No production source change was made during the runtime attempt.

## Current failures

SYN-049 remains incomplete at lawful dependent-worker snapshot consumption:
B's managed worktree contains only its skeleton after A publication, and
re-announcing B's existing claim is correctly rejected as self-overlap. The
target control checkout is clean; no B completion, final integration, or
WorkGroup terminalization was proven.

## SYN-049 fresh unattended two-worker acceptance — run #28 — 2026-09-06

Status: **FAIL at production worktree fence / ACTIVE / PARTIAL**. Fresh
skeleton-based run #28 passed provenance, JDK25 process-local compatibility,
lawful A/B setup, same-process generation-1 preparation and START, real A
implementation/publication, and same-thread provider-native B wake. The exact
same-thread B continuation then failed with `lifecycle_worktree_mismatch`.
Read-only evidence shows B's durable binding was rewritten to a recovery
worktree/base commit while the original B provider process still referenced its
original worktree. No B consumption, final integration, or terminalization was
reached.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-run28.md`.

- Exact next action: diagnose the binding-recovery/reconciliation race behind
  `lifecycle_worktree_mismatch` read-only and with focused tests; do not reuse
  run #28, invoke replacement, start a Worker B substitute, or perform state
  surgery.

## SYN-049 fresh unattended two-worker acceptance — run #25 — 2026-09-06

Status: **ACTIVE / PARTIAL**. Fresh run #25 passed corrected generation-1
managed setup, same-process prepare through START, original-thread provider
native B wake, capability publication/validation, real provider turns, exact
ownership, and read-only provider durability for Threads A and B. Both
lifecycle checkpoints ended `COMPLETED` at generation 1. The durable
collaboration projection still leaves Participant A `ACTIVE` with WorkIntent A
`ANNOUNCED`, so WorkGroup terminalization was not proven.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-run25.md`.

- Exact next action: diagnose the completion/finish projection for the exact
  active A lane read-only; do not reuse run #25, invoke replacement, start
  Worker B, or perform `.synesis` surgery.

## SYN-049 fresh unattended two-worker acceptance — runs #12–#16 — 2026-09-05

Status: **FAIL at the provider-native wake boundary; ACTIVE / PARTIAL**.
Fresh runs proved generation-1 same-process preparation and START, live
managed B attachment after its initial `NEEDS_CAPABILITY` turn, exact A
publication, and 60 clean wake-relay scans. The exact B actionable candidate
was present while the production host reported `attachmentAlive=true`, but no
provider-native B continuation was dispatched. No B consumption, integration,
or terminalization evidence exists.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-05-run12-run16.md`.

- Exact next action: diagnose the production wake-admission/dispatch boundary
  read-only using the captured run #16 evidence; do not reuse any target,
  invoke manual wake, create A2/B replacement, or patch production before the
  exact rejection path is identified.

## SYN-049 fresh unattended two-worker acceptance — runs #10 and #11 — 2026-09-05

Status: **ACTIVE / PARTIAL**. Two completely fresh acceptance attempts were
stopped at the first material runtime boundary. Run #10 stopped after Worker
B's generation-1 App Server exited during START. Run #11 stopped after both
real App Servers reported that the native Synesis MCP child failed its
`initialize` handshake (`connection closed: initialize response`). No
capability exchange, provider-native wake, claim mutation, integration, or
terminalization was reached.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-05-run10-run11.md`.

- Exact next action: perform a separate read-only child-startup compatibility
  diagnosis to determine whether the native MCP child independently hits the
  known AF_UNIX boundary. Do not propagate the workaround, patch production,
  reuse either target, or start another acceptance lane before that diagnosis.

## Historical: SYN-049 fresh unattended two-worker acceptance — 2026-09-05

Status: **ACTIVE / PARTIAL**. This is the sole active task. Fresh run #08
passed lawful A/B admission, generation-1 managed startup, B's structured
capability wait, A's exact publication and integrated snapshot, and the
same-node binding projection regression. It then stopped on the first
material runtime failure: the harness received `lifecycle_binding_stale`
while polling A after its asynchronous turn; A's turn was interrupted during
normal cleanup and B never consumed the published capability. No terminal
WorkGroup acceptance was reached. Historical fixtures and completed SYN-051
evidence remain read-only evidence.

- Task ID: SYN-049

## Immediate next action

Preserve run #08 evidence and do not reuse or repair its target. The next
attempt must first resolve the reproduced `lifecycle_binding_stale` boundary
in a separately verified source slice, then use a new target; do not continue
this lane or invoke replacement.

## Historical: SYN-051 fresh two-worker/restart acceptance — 2026-09-05

Status: **PASS-A / COMPLETE for bounded SYN-051 managed continuity**.
Provenance and JDK25 process-local compatibility passed. A fresh supported A/B
setup proved distinct identities, claims, generation-1 pending preparation,
cross-proof rejection, Job containment, same-connection MCP promotion,
same-AppServer thread creation, exact ownership, ACTIVE, real claim-aligned
turns, trusted `turn/completed`, `persistenceReady=false -> true`, and
read-only provider DB/history/rollout durability for both workers. Each worker
then passed trusted generation-1 death, fresh-proof generation-2 replacement,
exact same-thread resume, and a second real turn while the other successor
remained live. Evidence:
`docs/evidence/SYN-051-fresh-two-worker-2026-09-05.md`.

## Immediate next action

Preserve the SYN-051 PASS-A evidence; do not reopen the accepted managed
runtime or run full SYN-049 acceptance without a new explicit task.

## Historical: SYN-051 fresh Worker-A A1/A2 runtime validation — 2026-09-05

Status: **ACTIVE / PARTIAL (runtime PASS-A)**. One fresh lawful lane passed provenance, JDK25
process-local compatibility, same-process `prepareFirst -> START`, pending
authority quarantine, exact AppServer/MCP Job containment, same-AppServer
`thread/start`, ownership, broker pin, ACTIVE promotion, two trusted exact-claim
turns, controlled generation-1 death, exact generation-2 resume, and provider
DB/history/rollout durability for the same Thread A. A narrow hard-stop
checkpoint race was fixed and verified before the final lane. The complete
workspace lifecycle-codex suite passed. The in-scope aggregate MCP classes
`McpServerTest`, `CodexLinkedWorktreeRootSelectionRegressionTest`,
`McpStage2BSlice1Test`, and `Slice4FailureScenariosTest` also passed under the
same bounded JDK25 configuration. Explicit two-process and older SYN-039
acceptance classes remain unrun by scope.

Evidence: `docs/evidence/SYN-051-fresh-worker-a1a2-2026-09-05.md`.

## Immediate next action

Preserve the runtime PASS-A evidence; the remaining repository criterion is
separately scoped two-worker/restart acceptance. Do not start Worker B or run
full SYN-049 acceptance in this bounded objective.

## Work completed

The in-scope aggregate MCP classes passed after their long-running Git/provider
fixtures completed; the 33 `McpServerTest` methods and 13 linked-worktree
methods also passed in isolated fresh JVMs. Explicit two-process and older
SYN-039 acceptance classes were not run.

## Current failures

No assertion failure remains in the in-scope MCP tests. The unresolved gate is
the repository task's separately scoped two-worker/restart acceptance, not a
managed-continuity invariant in this single-worker A1/A2 objective.

- Task ID: SYN-051
- Status: ACTIVE / PARTIAL (runtime PASS-A)

## SYN-051 fresh Worker-A persistence validation — 2026-09-05

The bounded run is **PARTIAL**. A successful fresh lane proved same-process
`prepareFirst → START`, generation-1 `PENDING_ACTIVATION`, same-App-Server
`thread/start`, exact ownership and broker pin, `ACTIVE`, one real
claim-aligned Codex turn, trusted `turn/completed`, and
`persistenceReady=false → true`. Provider SQLite/history/rollout durability
for the exact Thread A was confirmed read-only. The target mutation was only
the exact claimed file in the assigned worktree.

PASS-A is not claimed: pending-state authority denial and direct child-to-exact
Windows Job membership were not independently captured in this run. SYN-049
remains `PARTIAL`; SYN-051 remains `ACTIVE / PARTIAL`. No production source,
credential, global setting, replacement, A2, Worker B, full acceptance, or
push occurred.

Evidence: `docs/evidence/SYN-051-fresh-worker-a-persistence-2026-09-05.md`.

## Immediate next action

Checkpoint and stop. Do not start Worker B or invoke A1-to-A2 replacement;
future work requires separate authorization for the two narrow evidence gaps.

## Verification

JDK25 process-local selector/HTTP preflight and exact artifact hashes passed.
The successful lane used Binding A `session-e3783a86-78c4-41c5-965c-6fb10e854781`,
Thread A `01a06f47-0e24-7ce2-9e9f-bf4fdb36c18f`, and turn
`01a06f47-0f2d-7a71-aa81-91a0ea83d707`. Normal host close left the attachment
`DISCONNECTED` and lifecycle checkpoint `STOPPED`; provider ownership remained
`persistenceReady=true`. A preliminary disposable lane was not reused.

## SYN-051 build-JVM compatibility and provenance — 2026-09-05

The command-local `GRADLE_OPTS` carrier succeeded with `TEMP/TMP=C:\t` and
`-Djdk.net.unixdomain.tmpdir=temporary workspace`. Gradle startup and
the `help` task passed. Focused workspace and MCP test selections compiled but
stalled at their test tasks without assertions and were stopped as incomplete.

The clean `:cli:installDist` build passed, including the native MCP launcher.
Produced and installed workspace, MCP, CLI, and native launcher hashes match
exactly. `BUILD_COMMIT=UNKNOWN` remains documented. No global setting,
repository build configuration, production source, target project, or managed
runtime changed.

Evidence: `docs/evidence/SYN-051-build-jvm-provenance-2026-09-05.md`.

## Immediate next action

Historical next action: run one fresh lawful SYN-051 Worker-A validation using JDK25 with the same process-local AF_UNIX
property, keeping `prepareFirst` and START in the same live caller; do not start Worker B or invoke A1-to-A2
replacement.

## Work completed

Proved the build-JVM compatibility carrier, completed clean build/install, and established exact produced-to-installed
artifact provenance.

## Current failures

Focused workspace and MCP test tasks remain incomplete because they stalled after compilation without assertion output.
Package-wide acceptance remains unrun.

## Verification

`gradlew --version`, `help`, clean `:cli:installDist`, deferred validation, artifact hash equality, target identity, and
persistent-environment checks passed. No Worker A or managed runtime was run.

## SYN-051 process-local preflight and clean-build gate — 2026-09-05

The compatibility evidence was committed as `bff97418672e2197cd00f293409d68446918bdd8`.
Under JDK25 `25+36-LTS`, the authorized process-local property
`-Djdk.net.unixdomain.tmpdir=temporary workspace` made the required
`Selector.open()` and minimal IPv4/IPv6 `HttpServer.create/start/stop` sanity
checks pass. No global Java/network/Codex setting changed.

The required clean rebuild/install then failed in the independent Gradle/JDK
process before task execution with `java.io.IOException: Unable to establish
loopback connection`. The stop rule therefore forbids propagating the
diagnostic property into build logic or broadening the workaround here.
Artifact provenance was not freshly re-established. No fresh Worker-A lane was
created in this slice; the target and its pre-existing `probe-runtime/` were
untouched.

Evidence: `docs/evidence/SYN-051-process-local-preflight-build-blocker-2026-09-05.md`.

## Immediate next action

Obtain separate authorization for a bounded process-local compatibility path for the build/Gradle JVM itself, then
perform a fresh clean build/install and provenance lock before creating any new Worker-A lane; do not launch or reuse a
lane.

## Work completed

Committed the standalone compatibility evidence, passed the exact JDK25 selector and HTTP preflight, verified the target
baseline, and attempted the required clean build without global settings changes.

## Current failures

Gradle's JDK25 process independently reproduces the Windows AF_UNIX loopback failure before task execution. Existing
installed hashes are observations only and are not a new provenance lock for this slice.

## Verification

`git diff --check`, deferred validation, JDK25 selector preflight, JDK25 IPv4/IPv6 HTTP preflight, target Git identity,
and environment-setting checks passed. Clean `:cli:installDist` stopped before task execution. No fresh lane or managed
runtime was run.

## SYN-051 standalone host compatibility — 2026-09-05

The bounded investigation is COMPLETE; SYN-051 remains ACTIVE / PARTIAL.
Both Temurin 25+36 and 21.0.11+10 pass Pipe.open() and IPv4/IPv6 TCP, but
fail Selector.open() and HttpServer creation with an AF_UNIX connect error.
The smaller direct UNIX socket test fails in inherited/expanded user TEMP
and passes in temporary workspace. With only the diagnostic JVM's
jdk.net.unixdomain.tmpdir set to that directory, the unchanged full probe
passes 13/13 on BOTH JDKs. Classification: temporary-path-dependent Windows
AF_UNIX boundary, not JDK25-specific, not IPv4/IPv6, not host-wide TCP failure.
The underlying OS/filesystem/environment cause is unproven. User-TEMP socket
cleanup failed; exact files and limitations are recorded in the evidence.
No managed runtime, target project, production source, .synesis, credentials,
global settings, or remote state was touched.

Evidence: `docs/evidence/SYN-051-standalone-loopback-compatibility-2026-09-05.md`
and its `-raw.txt` companion. Checkpoint: CP-0683.md.

## Immediate next action

Review the standalone compatibility evidence and obtain separate authorization for one new Worker-A run on existing
JDK25 with a process-local UNIX socket directory and matching primitive preflight; do not launch or reuse a lane in this
slice.

## Work completed

Completed standalone JDK25/JDK21 comparison and source-supported AF_UNIX reduction; recorded exact results and traces.
Production unchanged. No managed acceptance run.

## Current failures

Both default JDKs fail selector initialization at UNIX socket connect in user TEMP. Process-local diagnostic directory
resolves all 13 primitive cases on both JDKs. User-TEMP socket cleanup failed; root cause and managed runtime remain
unverified.

## Verification

Commands: external run_probe.py baseline, run_probe.py ipv4, run_unix.py; exact command arrays and all results in
evidence. Run git diff --check and scripts/agent-checkpoint.ps1; no Doctor, MCP, or production test invocation.

## Historical handoffs (superseded by the current entry above)

# Current Task

## SYN-051 fresh single-worker managed-runtime validation — 2026-09-05

The bounded pass is **PARTIAL / STOPPED ON FIRST MATERIAL HARNESS/HOST
FAILURE**. Source/runtime provenance and target identity were verified, and a
fresh lawful Worker-A lane was created. The same trusted caller constructed one
live `ManagedCodexProcessLauncher` and the production runtime host, but JDK 25
failed at `HttpServer.create(...)` with `Unable to establish loopback
connection` caused by `SocketException: Invalid argument: connect` in
`PipeImpl`/`WEPollSelectorProvider`. The failure occurred before
`prepareFirst`, so no START, proof, generation, attachment, App Server, MCP,
Job, provider thread, turn, or persistence transition was reached.

The fresh lane is connection
`syn051-live-a-40ff76be-a20b-4148-99d1-d04a153152b7`, participant
`agt_81dcd2a3-5d43-3cb5-9068-6a68b5f2f699`, WorkIntent
`c340aab7-376f-3172-b43f-2580c3045603`, binding
`session-756b644a-bac0-479c-be24-577581f286c6`, claim
`probe-runtime/persistence-live-a-40ff76be-a20b-4148-99d1-d04a153152b7.txt`.
The previous preparation-only lane was not reused. The host closed normally;
replacement, A2, and Worker B were not invoked. No production source changed.

Evidence: `docs/evidence/SYN-051-live-worker-a-loopback-blocker-2026-09-05.md`.

## Historical next action

Separately resolve or investigate the JDK 25 loopback `PipeImpl`/
`WEPollSelectorProvider` invalid-argument failure, then run a new fresh lawful
Worker-A validation with `prepareFirst` and START in the same trusted live
caller. Do not reuse this lane, start Worker B, invoke A1-to-A2 replacement,
or broaden acceptance.

## Work completed

Recorded the first-material-failure result in the dedicated evidence file and
durable agent records. Verified source/runtime provenance, target identity,
fresh-lane identity, normal host cleanup, and the absence of a managed
attachment or new lifecycle checkpoint. No production source was changed.

## Current failures

JDK 25 cannot create the disposable JDK loopback HTTP boundary in this
environment: `HttpServer.create(...)` fails with `Unable to establish loopback
connection` and `SocketException: Invalid argument: connect` in
`PipeImpl`/`WEPollSelectorProvider`. This blocks reaching `prepareFirst` and
START; it is not provider-runtime evidence.

## SYN-051 caller-contract-only preparation validation — 2026-09-05

The bounded pass is **PASS-A for the preparation boundary; SYN-051 remains
ACTIVE / PARTIAL overall**. The prior requested binding
`session-8b82fd05-6c97-4f8f-bc1a-2a1ce0c56c2a` was inspected through supported
state and correctly left untouched because it was already `FAILED` with
`managed_attachment_not_prepared` and had no attachment. Exactly one new
lawful Worker-A lane was created through the production session and
collaboration services:

- Binding: `session-444ee24b-1ff8-4111-b818-787f900088a0`
- Participant: `agt_c74b5d08-5a0b-3017-bce9-652498da186c`
- WorkIntent: `befca654-af37-31cb-a605-f6bfbec7a8f0`
- Claim: `probe-runtime/caller-contract-preparation-62178e4d-e95a-4529-845a-22ae1d8fbfff.txt`

The actual production call
`ManagedCodexProcessLauncher.prepareFirst(AuthorityContext)` succeeded and
created exactly one generation 1 `PENDING_ACTIVATION` record. The durable
record contains only the proof digest
`897b305ba8aa01fc162a5466a4c0de49c35250a73161717a2c362b2d2e118591`; the raw
proof was held only in the trusted launcher's volatile preparation. The
provider thread and provider-thread ownership remain absent. Binding,
participant, WorkIntent, and claim preservation all passed. No START, Codex,
App Server, MCP, Job, or provider-thread operation was performed, and no
production source changed.

Evidence: `docs/evidence/SYN-051-fresh-worker-a-validation-2026-09-04.md`.

## Historical next action

If separately authorized, keep `prepareFirst` and START in the same trusted
live caller and run only the next bounded single-worker runtime step; do not
call START from a new process against this pending record, do not start Worker
B, and do not run broader acceptance.

## SYN-051 fresh Worker A managed-runtime validation — 2026-09-04

The bounded runtime pass is **PARTIAL / STOPPED BEFORE MANAGED APP SERVER
LAUNCH**. The clean final build/install and 35 focused workspace/lifecycle
tests passed; the selected MCP test task stalled without assertion output and
was stopped. A fresh target authority lane was created for binding
`session-8b82fd05-6c97-4f8f-bc1a-2a1ce0c56c2a`, participant
`agt_9db6eb10-fff3-3711-8f97-1d0b75ed40de`, WorkIntent
`45cb6507-0dd0-397d-aecb-51e8754b069e`, and claim
`probe-runtime/persistence-a.txt`. The caller submitted START without first
calling the managed launcher's `prepareFirst`, so the owner returned
`managed_attachment_not_prepared` before App Server launch. No proof,
generation, App Server, MCP child, Job, provider thread, turn, or mutation
was created. Historical target state and source production code were not
changed.

Evidence: `docs/evidence/SYN-051-fresh-worker-a-validation-2026-09-04.md`.

## SYN-051 generation-1 provider persistence boundary — 2026-09-04

The bounded production slice is **PARTIAL**. The managed App Server now
creates generation 1 with `thread/start` inside the existing pending MCP
quarantine, acquires and broker-verifies the exact returned thread, binds it
to the pending attachment, and activates only after that sequence. Provider
thread ownership now carries a durable, idempotent `persistenceReady` fact;
`thread/start`, same-process read, and graceful exit do not set it. The
managed lifecycle marks it only after a trusted exact `turn/completed` event.
Successor/replacement paths remain blocked while provisional and require the
persisted owner plus trusted death evidence.

Focused changed-source workspace tests and clean `:cli:installDist` passed;
installed workspace/MCP/CLI jars match their produced hashes. The selected
workspace and MCP package-wide test tasks stalled and were stopped, so they
are incomplete evidence. No fresh managed Worker A, Worker B, A1/A2, or full
acceptance was run.

## Historical next action

Do not retry this stopped pass. Decide or separately authorize the supported
managed caller sequence that invokes `prepareFirst` before START, then run a
new fresh single-worker validation only if authorized; do not start Worker B,
A1-to-A2 replacement, or full acceptance.

## SYN-051 provider-thread provenance review — 2026-09-04

The read-only investigation is **PASS-B — architecture simplification found**
with full managed compatibility still unverified. Codex 0.153.0 creates a
start-only thread in the originating App Server but does not provide a
successor-loadable provider rollout/history until a persisted turn or
equivalent persistence operation exists. The exact managed failure was caused
by treating that live ID as a durable handoff; provider state roots matched.
No production source, `.synesis` state, credentials, historical evidence,
Worker B, or remote state changed. Evidence is in
`docs/evidence/SYN-051-provider-thread-provenance-2026-09-04.md` and ADR-0064.

## Historical next action

Implement the bounded SYN-051 first-generation lifecycle change: let managed
App Server call `thread/start`, then broker-acquire/pin the returned thread and
require one persisted turn before successor/replacement acceptance. Rebuild,
install, and run only focused provider/lifecycle verification; do not start
Worker B or full acceptance.

## SYN-051 bounded real-runtime validation — 2026-09-04

The pass is **PARTIAL / STOPPED ON FIRST MATERIAL FAILURE**. The current
source at `088cb239f2e2109c4dddd40cbaee36c158337e82` built and installed with
matching artifact hashes. A fresh lawful Worker A binding and exact provider
thread ownership were established, and the production managed launcher
created a generation-1 pending attachment inside a Windows Job. Codex
`0.153.0` rejected the pinned `thread/resume` in the fresh App Server with
`thread not loaded`; therefore A1 never reached a real managed turn or
activation. The lifecycle failed closed and its startup cleanup produced a
trusted generation-1 death receipt. This is not the requested controlled
App-Server-root crash evidence. The one-shot harness did not fail-fast after
the exception and later reached the production replacement call, creating a
generation-2 pending record from that startup-cleanup receipt; this was not
accepted as A1-to-A2 evidence and was terminalized through the public service.

The fresh aborted attachment was terminalized through the public application
service at generation 2 for safe stopping. The historical binding remains
generation 1 `ACTIVE` with no receipt and was not touched. Worker B, A/B
isolation, A1/A2 restart, and full acceptance were not run. No production
source changed and no push occurred. The harness-control defect is recorded
as a test-execution failure, not a production lifecycle conclusion.

## Historical next action

Perform a separately authorized, read-only provider-thread provenance review
for why a bootstrap-created Codex thread is not loadable by a new App Server
process. Do not retry managed A1/A2, relax proof gating, recover the
historical generation, start Worker B, or broaden SYN-051 until that cause is
resolved.

## SYN-051 production lifecycle fix — 2026-09-04

The bounded lifecycle slice is **PARTIAL**. A trusted managed-process
supervisor now produces generation-scoped, non-secret death evidence only
after root exit, Job teardown, and an empty Job process count are proven. The
managed launcher persists that receipt and can replace the exact active or
disconnected generation without accepting the old raw proof. Replacement
preserves the broker binding, provider thread, ownership, participant,
WorkIntent, and claims while atomically fencing the old generation and minting
a fresh proof.

The historical `SkibidiToilert` generation 1 has no receipt produced by this
seam. Its process is gone, but it is therefore not lawfully replaceable; no
receipt was manufactured and no durable fixture state was edited. No real
A1-to-A2 rerun was performed.

## Historical next action

Resolve the workstation Gradle loopback failure, rebuild and install the
current source with matching provenance, then run only the focused lifecycle
and MCP tests. A fresh lawful real-runtime probe may follow only after that;
do not touch the historical generation or start Worker B.

## SYN-051 production compatibility fix — 2026-09-04

The bounded compatibility slice is **PARTIAL / real boundary PASS**. A
proof-bearing Synesis MCP child may now establish a transport while its
attachment is `PENDING_ACTIVATION`, with no authority; each managed tool call
re-reads the exact binding and generation and remains blocked until trusted
lifecycle activation promotes the same connection. A per-generation OS lock
prevents duplicate managed transports. The managed Codex launcher also
forwards the non-secret connection selector through Codex's explicit
per-server environment override, while the proof remains on `env_vars`.

Focused tests, strict compilation, install provenance, and a fresh real
`SkibidiToilert` A retry passed the compatibility boundary: MCP startup became
`ready`, the App Server resumed the exact broker-pinned thread, the MCP child
was observed inside the same Windows Job, and the real turn reached Synesis.
The disposable turn used a mismatched `compat-a5` claim against the durable
`compat-a4` lane and therefore failed closed at `ensure_session`; this is
harness input failure, not a managed transport failure.

## Historical next action

Checkpoint and commit this compatibility slice, preserve the real evidence,
and stop before Worker B, A/B isolation, A1/A2 restart, or full acceptance.

## SYN-051 runtime-boundary follow-on — 2026-09-03

The bounded production slice is **PARTIAL**. Java 25 FFM now supplies a
Windows-only Job Object supervisor with suspended root creation, assignment
before resume, kill-on-close, native waits, and Job accounting. Managed
Codex lifecycle launch and teardown use that supervisor. Managed proofs use
launch-local `env_vars` from the App Server environment and remain pending
until exact broker-pinned thread response/readback verification. Focused
source/build, lifecycle, attachment, admission, and real root-plus-descendant
containment checks pass; real Codex A/B proof delivery and restart acceptance
remain outstanding.

## Historical next action

Rebuild and install the current source with matching hashes, then run only the
focused real Codex child-proof, A1-to-A2 restart, fresh-proof, and Worker B
isolation probe. Do not run full task-tracker acceptance.

## Work completed

Implemented the Windows Job Object supervisor, lifecycle integration, pending
managed-proof activation fence, launch-local Codex `env_vars` carrier, focused
tests, ADR-0062, and updated SYN-051 evidence/planning records.

## Current failures

No focused implementation test failure remains. The real managed MCP
transport boundary passed, but the one disposable model turn did not perform
the requested file mutation because its prompt selected a path outside the
durable lane claim. Full A/B, restart, and task acceptance remain unrun.

## SYN-051 shared-normal-home production slice — 2026-09-03

The explicit user authorization selects the PASS-B shared-normal-provider-home
architecture. Historical feasibility rejection records remain unchanged; this
task now implements the compensating Synesis controls: atomic unique
`(provider, providerThreadId)` ownership, proof-gated managed admission,
broker-derived immutable thread pinning, generation fencing, and owned
process-tree lifecycle. Ordinary `SESSION_BOUND` behavior, exact authority,
the ten-tool surface, and `UNSAFE_FILE_AUTH` semantics for isolated homes are
preserved.

## Historical next action

Implement and test the durable provider-thread ownership record/store first,
then wire its exact thread into managed attachment issuance and admission.

## SYN-051 Windows Job Object follow-on — 2026-09-03

The bounded process-tree spike is **PARTIAL**. Suspended launch plus
assignment-before-resume contained generic descendants and real Codex
AppServer/MCP children in independent Jobs. App Server-only death left MCP
alive until Job teardown, which killed it and proved the Job empty. Generic
controller kill-on-close passed, B stayed usable during A teardown, and A2/B2
resumed exact threads with fresh proofs. Ambiguous query failure, a real
two-successor loser cleanup, and real Codex post-supervisor-crash recovery
remain untested. Evidence is in
`docs/evidence/SYN-051-job-object-process-tree-feasibility-2026-09-03.md` and
ADR-0059. No Synesis MCP path was invoked.

## Historical next action

Preserve `UNSAFE_FILE_AUTH`. Obtain separate authorization for the three
missing edge cases; do not change production code or run full acceptance.

## SYN-051 broker follow-on — 2026-09-03

The bounded broker-pinned stock-Codex feasibility spike is **PASS-B**. A
disposable lifecycle-only broker using the normal Codex home/auth successfully
bound each dedicated App Server to one exact persisted provider thread and
rejected wrong-thread operations before Codex received them. A/B concurrency,
proof isolation, exact restart/resume, live-owner refusal, ambiguous-liveness
refusal, and one-winner successor locking passed. App Server-only death left
the disposable MCP child orphaned (`parentPid=-1`), so owned process-tree
teardown is the missing production primitive. Preserve `UNSAFE_FILE_AUTH` and
do not implement integration or run managed acceptance. Evidence is in
`docs/evidence/SYN-051-broker-pinned-thread-feasibility-2026-09-03.md` and
ADR-0058. The broker remained outside this repository and the Synesis MCP
attachment path was not invoked.

## Historical next action

Obtain or design-review a verified owned process-tree supervisor and authorize
a fresh real Synesis attachment probe; until both exist, preserve the
PASS-B feasibility result, the dedicated-home design, strict authority
resolution, and `UNSAFE_FILE_AUTH` without production changes.

## SYN-051 Bounded stock-Codex MANAGED_CONTINUITY implementation — 2026-09-03

- Task ID: SYN-051
- Status: ACTIVE; the existing bounded implementation is preserved, but
  managed acceptance and further production auth-policy changes remain blocked
  after the safe-auth hard stop and the shared-home follow-on rejection.
- Scope: Codex App Server only; one retained dedicated `CODEX_HOME` and one
  dedicated App Server per logical worker; exact thread resume; selected
  `env_vars` proof delivery; hash-only durable proof; rotation and monotonic
  generation fencing; existing strict authority and lifecycle seams.
- Non-goals: Claude, anonymous continuity, shared App Server, arbitrary fleet
  launch, upstream Codex changes, Review/Doctor redesign, SYN-049 closure,
  manual IDs/state, worktree copying, and push/tag/release.
- Required gate: inspect and reuse existing runtime/provider/binding/authority
  seams before edits, then prove safe isolated Codex authentication, matching
  artifact provenance, real authenticated model turn, exact restart/resume,
  stale-generation rejection, and preservation of logical coordination state.
- Follow-on result: the shared normal CODEX_HOME alternative is rejected for
  worker isolation. Evidence is recorded in
  `docs/evidence/SYN-051-shared-normal-home-feasibility-2026-09-03.md` and
  ADR-0057; no production code changed.

## Historical next action

The bounded shared-normal-home follow-on is complete and classified `FAIL` for
the full worker-isolation hypothesis. Concurrent dedicated App Servers,
provider-owned normal authentication, launch-local MCP `env_vars`, short real
turns, and same-home process recreation worked operationally. A fresh B3
process nevertheless resumed A's persisted provider thread after A stopped.
Preserve `UNSAFE_FILE_AUTH`; do not run managed acceptance or weaken the
security boundary.

Artifact provenance is complete and recorded in
`docs/evidence/SYN-051-managed-continuity-provenance-2026-09-03.md`. The
provider-authentication compatibility pass is recorded in
`docs/evidence/SYN-051-keyring-auth-compatibility-2026-09-03.md`. Codex
`0.145.0` reports the normal home as file-backed, explicit keyring mode has no
credentials, and a fresh isolated home fails its real model turn with HTTP

401. Preserve the hard stop; do not copy `auth.json`, perform destructive
     provider migration, or run managed Codex acceptance.

The source map is recorded in
`docs/evidence/SYN-051-source-insertion-map-2026-09-03.md`. The first
implementation slice now provides versioned hash-only attachment records,
cryptographically random proof issuance, exact binding/thread/generation
authentication, atomic replacement fencing, dedicated Codex homes, selected
`env_vars` delivery, MCP startup authentication, continuity-mode reporting,
and provider guidance. Focused tests and strict Javadocs pass.

The committed build/install provenance is recorded in
`docs/evidence/SYN-051-managed-continuity-provenance-2026-09-03.md`.
Source/runtime/install hashes match. The current Codex home is file-backed
(`auth.json`) without a keyring credential. The provider's Windows
keyring-backed storage is scoped to each `CODEX_HOME`, so the existing login is
not a supported shared carrier for fresh managed homes. Safe authenticated
managed runtime acceptance is blocked and no credential copy is permitted.

## Work completed

Verified the installed `codex-cli 0.145.0` behavior and the existing
hash-matched SYN-051 distribution. Normal file-backed Codex authentication
completed a minimal model turn. Explicit keyring status found no credentials;
a fresh isolated `CODEX_HOME` contained no copied auth and failed its real
model turn with HTTP 401. Exact provider source confirms Windows keyring auth
uses a per-home key and encrypted home-local auth store. Evidence is recorded
in `docs/evidence/SYN-051-keyring-auth-compatibility-2026-09-03.md`. No
production source, credential, `.synesis` state, or historical fixture changed.

The shared-normal-home follow-on used only disposable provider and boundary
observers. A/B proof delivery and concurrent real turns passed, but provider
thread ownership failed after owner shutdown: B3 resumed A's persisted thread
with a fresh process proof. The first disposable attempt caused two temporary
Codex trust-entry additions; exact cleanup restored the normal config's
pre-probe SHA-256 at the cleanup point. A later read found an unattributed
length/hash change with no probe references; it was not overwritten blindly.
Evidence is recorded in
`docs/evidence/SYN-051-shared-normal-home-feasibility-2026-09-03.md`.

## Current failures

The safe isolated provider-authentication gate failed. The current normal
login remains file-backed and the managed adapter must continue to classify it
as `UNSAFE_FILE_AUTH`. The provider's Windows keyring path is not an
account-wide carrier across fresh isolated homes; using separate homes would
require provider credential duplication or a supported cross-home mechanism,
neither of which is established. A focused Gradle regression invocation also
failed before test execution with `java.io.IOException: Unable to establish
loopback connection`, including after the IPv4 preference retry. Do not run
managed acceptance or weaken the security boundary.

The shared-home alternative does not remove the managed-continuity blocker:
the provider store is operationally concurrent but does not fence a persisted
thread to its original managed worker. The current Synesis attachment probe
was intentionally not invoked under the no-Synesis-in-source boundary. The
targeted Gradle catalog test also remained incomplete because the host could
not establish loopback; direct catalog inspection still reports exactly ten
raw tools.

## SYN-050 Provider-session continuity across MCP process restart — 2026-09-03

- Task ID: SYN-050
- Status: DONE / FEASIBILITY ACCEPTED; DESIGN_APPROVED / PASS-A FEASIBILITY /
  NO PRODUCTION EDITS.
  The provider-neutral core architecture is Result A. The stock Codex
  isolated-runtime experiment is PASS-A for conservative one-process-per-
  worker managed delivery; production continuity is still not implemented in
  this feasibility pass.
- Capability decision: ordinary Codex and Claude stdio remain
  `SESSION_BOUND`; `MANAGED_CONTINUITY` is approved as a supported profile and
  Synesis-supervised Codex App Server is its first target. A future provider-
  native assertion path is `NATIVE_CONTINUITY`. Static wrappers, anonymous
  brokers, process lineage, and model-visible bearers do not establish
  continuity. Evidence:
  `docs/evidence/SYN-050-protected-carrier-prototype-2026-09-03.md`,
  `docs/evidence/SYN-050-codex-app-server-child-launch-boundary-2026-09-03.md`,
  `docs/evidence/SYN-050-stock-codex-isolated-runtime-feasibility-2026-09-03.md`,
  `docs/evidence/SYN-050-managed-attachment-design-2026-09-03.md`, and
  `docs/evidence/SYN-050-provider-boundary-feasibility-2026-09-03.md`.
- SYN-049 status: PARTIAL; its Defect A and Defect B results remain separate
  and are not reopened.
- Scope: define and, only after a verified provider contract and accepted ADR,
  implement the smallest provider-neutral runtime-authentication mechanism for
  an authenticated replacement runtime to recover an existing Synesis logical
  worker. Preserve exact connection fencing and reject unrelated, concurrent,
  stale, replayed, and terminal recovery attempts.

## Historical next action

The bounded stock-Codex isolated-runtime investigation is complete and
recorded in `docs/evidence/SYN-050-stock-codex-isolated-runtime-feasibility-2026-09-03.md`.
Dedicated `CODEX_HOME` roots, worker-specific MCP configuration, selected
`env_vars`, concurrent A/B processes, exact same-home thread resume, and
A1-to-A2 proof rotation passed. A fresh home cannot resume an existing thread,
so each logical worker must retain its own provider home/thread store. The
earlier dynamic child-launch result remains a narrower `FAIL`: stock Codex
still has no dynamic thread/proof/extra-handle ingress. The current stock
isolated-runtime result is `PASS-A` for conservative v1 feasibility; do not
make production continuity edits, restart or re-admit the preserved SYN-049
fixture, or weaken exact authority.

## SYN-050 trace result

The normal MCP startup uses the explicit `SYNESIS_MCP_CONNECTION_INSTANCE_ID`
when supplied and otherwise generates a random UUID. The MCP handshake does
not consume Codex thread/conversation metadata. The Codex hook sees that
metadata in a separate process, while the static Codex MCP configuration does
not pass it to MCP. In App Server, the thread-owned MCP runtime creates the
configured stdio client through a sealed launcher; the local launch clears the
parent environment and applies only configured environment values. The durable
logical binding is
`ProviderSessionBindingService.Binding.sessionId`; participant and WorkIntent
identities derive from that binding, while the lease remains keyed to the exact
transport connection. The existing recovery path is an audited transfer to a
new participant/intent, not same-session reattachment. A disposable real Codex
App Server probe confirms exact thread correlation, per-thread MCP child chains,
dedicated A/B process isolation, and process restart/resume, but no
Synesis-managed per-worker proof can enter the exact child through the current
provider boundary.

ADR-0055 and
`docs/evidence/syn050-provider-session-continuity-capability-design-2026-09-03.md`
record the expanded capability evaluation. The provider-boundary feasibility
record adds the verified Codex/Claude ordinary-MCP and managed-launch matrix,
and the version-matched child-launch investigation.
A high-entropy, hash-backed, single-use capability is server-side expressible,
but ordinary providers offer no provider-controlled non-model-visible way for
the same conversation to receive and present it after restart. The isolated
protected-carrier model passed; the real Codex boundary did not prove private
proof delivery. Result A remains the smallest provider-neutral core
architecture, while the managed Codex design remains Result B and production
implementation remains blocked. No production files changed.

## SYN-050 protected-carrier prototype result

The disposable Windows harness at
`local temporary workspace`
used an anonymous inherited child-stdin pipe, random per-worker proofs,
hash-only durable state, challenge-response verification, generation rotation,
replay/race rejection, live/ambiguous/terminal fail-closed cases, and secret
hygiene checks. It passed 48 assertions for isolated Worker A/Worker B
attachment behavior.

The real installed `codex-cli 0.145.0` probe used temporary provider state. One
App Server hosted two exact threads and created two observed MCP child chains;
two dedicated App Servers hosted distinct A/B threads and child chains while
running concurrently. A request using A's thread ID on B's App Server was
rejected. After A1 stopped, A2 resumed the exact A thread and relaunched its
MCP child while B remained live. Every wrapper and child saw the static config
marker but not the App Server parent markers, thread context, or attachment
proof. The child-launch boundary therefore fails the private-carrier gate.
Full redacted evidence is in
`docs/evidence/SYN-050-codex-app-server-child-launch-boundary-2026-09-03.md`.

## Acceptance boundary

The eventual same-conversation restart must recover the original
participant/session and WorkIntent without duplicate claims or re-admission;
unrelated and concurrent processes must fail closed; races have one winner;
stale/replayed proof and terminal sessions remain fenced; and the recovered lane
must complete through the existing explicit-completion, review, integration,
and ten-tool lifecycle. This acceptance is not authorized until the provider
primitive exists.

No latest-session fallback, inferred authority, durable-state rewrite, worktree
copy, manual Synesis identifier, new MCP tool, or unrelated Review/Doctor
redesign is permitted. ADR-0055 records the completed design-gate result.

## Work completed

Mapped the source-backed durable `Binding.sessionId`/participant/WorkIntent
chain, the ephemeral MCP connection/process evidence, the exact resolver and
lease coupling, and the separate Codex App Server wake/thread path. Evaluated a
Synesis-issued high-entropy, hash-backed, single-use rotating capability, the
provider-authenticated/Synesis-managed/anonymous capability classes, and all
currently available Codex carriers. Formally approved `MANAGED_CONTINUITY` as
a product profile with Codex App Server as its first target, selected Result A
for the provider-neutral core, and recorded the Result B prototype outcome.
The isolated carrier passed, but the real provider carrier is not proven. No
production code, provider configuration, durable Synesis state, or fixture was
changed.

## Current failures

The ordinary Codex stdio provider primitive remains unavailable, and the
current App Server child-launch primitive is now a bounded `FAIL`: its
configured launcher has no thread-scoped private carrier and clears
parent-private environment before local MCP launch. A model-visible bearer
cannot provide reliable same-conversation retention or prevent another process
from using a copied credential, and the existing recovery path transfers to a
new participant/WorkIntent. Production implementation and SYN-049 terminal
acceptance remain blocked. `agent-doctor.ps1` retains its known historical
false-positive vague-continuation result and existing absolute-path warning;
neither is part of this continuity design.

## SYN-049 Pre-release explicit completion and dependency actionability — 2026-09-02

- Status: PARTIAL; preserve the completed Defect A/Defect B evidence and the
  fresh fixture. Do not re-admit or restart either worker.
- Task ID: SYN-049
- Scope: independently correct and verify explicit completion lifecycle
  semantics (Defect A) and structured dependency preservation/actionability
  through the rebuilt MCP path (Defect B). Preserve the existing capability,
  review, integration, provider, authority, and ten-tool architecture.

## Historical next action

Preserve the fresh `SynesisTaskTrackerRealAcceptance-20260903-08` evidence and
do not re-admit or restart either worker. Review the recorded post-compliance
review/session lifecycle blocker as a separate follow-up only if it is
explicitly promoted; no further SYN-049 production edit is justified.

## Acceptance criteria

- Defect A: multiple mutations and tests remain `IMPLEMENT`; only a
  call-local `completionRequested:true` request can project exact
  `finish_lane` after current-state revalidation; old modes/formats fail
  loudly; existing fences/outcomes and the ten-tool catalog remain intact.
- Defect B: structured dependencies survive the real rebuilt MCP admission,
  durable replay, and `NEEDS_CAPABILITY` projection; the existing capability
  request/publication/review/integration path makes the requester actionable.
- A fresh two-worker task-tracker acceptance proves both defects together with
  no manual IDs/state, worktree copying, protocol bypass, or speculative
  review/Doctor changes.

## Architecture and scope

This is a bounded evolution of the existing local modular monolith. ADR-0053
owns Defect A and ADR-0054 owns Defect B. No new dependency graph, service,
MCP tool, provider integration, or review/Doctor redesign is authorized.
`GOONSQUAD`, KogMaw, and all historical fixtures remain preserved evidence.

## Current evidence

The source currently forwards `knownDependencies` through MCP admission,
`WorkIntent`, `WORK_INTENT_ANNOUNCED`, codec replay, and
`CapabilityRequestProjection`; no active source drop is proven. A clean
distribution from source `a789af671e6bf168436a61b4119d305af9db407e` was
installed by absolute artifact path and the fresh MCP regression passed. The
full evidence is in
`docs/evidence/syn049-completion-dependency-acceptance-2026-09-03.md`.

## Work completed

Planning and separate ADRs are recorded in SYN-049, ADR-0053, and ADR-0054.
Defect A now uses a call-local `completionRequested` request, V7 intent
encoding, explicit finish projection, and the existing execution fences and
terminal outcomes. Snapshot publishability uses the server-recorded lane base
commit so multiple committed lane mutations are evaluated together. Focused
coordination, MCP contract, MCP admission, and workspace regressions pass.
The rebuilt MCP Defect B path also passes, so its forwarding production code
was not changed. The fresh real run proved both scoped behaviors but remains
PARTIAL at the separate review/session lifecycle stall.

## Current failures

Plain Gradle startup still needs the documented local
`-Djdk.net.unixdomain.tmpdir=C:\\tmp` host mitigation for this Windows/JDK
environment; this is not a product/build-semantics change. A broad,
process-heavy MCP selection was stopped without an assertion result and is
incomplete evidence, not a passing result. The fresh two-worker run is
PARTIAL: A completed the explicit finish path and B consumed A's published
contract, but the separate review/session lifecycle prevented B's final finish
and WorkGroup terminalization. Doctor ended DEGRADED with six warnings and no
mutations.

## SYN-048 Completion record — 2026-09-02

- Task ID: SYN-048
- Status: DONE / VERIFIED
- Scope: correct the proven provider-admission mismatch for an unpinned MCP
  connection launched from an ordinary linked Git worktree, while preserving
  explicit project pinning, assigned-worktree rejection, provider admission,
  and the ten-tool surface.

## Acceptance criteria

- An unpinned initialized linked worktree sharing the control checkout's Git
  common directory resolves to the main checkout before provider admission.
- Unrelated roots, project-ID mismatches, Synesis-assigned worktrees, and a
  missing control-checkout provider integration remain rejected.
- Focused tests, diff validation, and deferred-register validation pass.

## Completion evidence

The bounded read-only resolver, focused regression, guard tests, and strict
local distribution rebuild are recorded in
`docs/evidence/syn048-unpinned-linked-root-2026-09-02.md`. The complete MCP
test class remains incomplete because it exceeded the bounded wait.

## SYN-045 Preserve explicit MCP project authority across provider-created Git worktrees — 2026-09-02

- Task ID: SYN-045
- Status: DONE
- Scope: prevent provider-reported MCP roots from replacing an explicit
  launcher-pinned control checkout unless they are verified as the same Git and
  Synesis project, while continuing to use the control checkout for provider
  admission and Synesis workspace allocation.

## Completion record

The bounded root-authority correction is complete and recorded in
`docs/evidence/syn045-codex-linked-worktree-bootstrap-2026-09-02.md` and
checkpoint `docs/agent/checkpoints/CP-0626.md`. Its incomplete broader
task-tracker fixture remains preserved as evidence and is not restarted.

## Acceptance criteria

- A Codex app linked worktree sharing the configured repository cannot make an
  installed control-checkout provider appear `NOT_INSTALLED`.
- Unrelated initialized roots and Synesis-assigned worktrees remain rejected.
- Missing provider installation at the configured control checkout remains
  blocked.
- Session admission still allocates distinct isolated Synesis worktrees.
- Focused tests, diff validation, and an installed-runtime fresh-pair check pass.

## Current evidence

The clean `ToPouliTouTzala` control checkout reports Codex provider metadata,
profile, hook, and a bound session. Its two Codex-created linked worktrees share
the same canonical Git common directory but lack ignored provider/profile/hook
state and initially reported `NOT_INSTALLED`, causing
`provider_integration_required` before either worker could mutate repository
files. The explicit launcher-root fix now keeps the control checkout
authoritative and verifies equivalent provider roots by project ID and Git
common directory. No manual state copy or bypass was performed.

## Work completed

Added the read-only explicit MCP root-authority resolver, fail-closed startup
handling, latched root-mismatch guards, focused linked-worktree regressions,
and the corresponding ADR. The installed runtime was rebuilt and repaired
through the normal installer workflow.

## Verification

Focused MCP root-selection coverage (12), startup coverage (2), selected
existing MCP coverage (7), and the focused workspace provider test (9) passed.
The platform bundle completed and the repaired installed MCP executable
matched the bundle SHA-256. A fresh installed-runtime pair reached distinct
Synesis worktrees and disjoint claims.

## Current failures

The broader task-tracker acceptance is incomplete: the bootstrap lane committed
`8225b9f` in its own Synesis worktree but did not publish/integrate it before
creating the workers, so their lanes started from control commit `36bc543`.
Both workers stopped safely without edits. A new parent connection's empty
recovery could not restore the old claim, which Luna confirmed is expected
fail-closed behavior. Preserve this fixture; do not copy files, manually
integrate branches, edit durable state, or restart the pair.

## SYN-043 Preserve explicit worker dependencies during MCP admission — 2026-08-31

- Task ID: SYN-043
- Status: PAUSED while SYN-046 is active
- Scope: carry `knownDependencies` from the existing `ensure_session` task
  admission path into durable work-intent state and expose the existing
  capability-request continuation. Preserve backward event decoding, the
  existing capability/contract lifecycle, isolated worktrees, and the exact
  ten-tool MCP catalog.

## Historical next action

Preserve the bounded live two-Codex result, allow only the already-running pair
to reach a terminal state, and then classify the remaining task-launch or
worker-liveness boundary without manually driving Synesis or starting another
pair.

## Work completed

The slice adds intent payload version 6, carries `knownDependencies` through MCP
admission and collaboration announcement, preserves the field during codec
replay, exposes the dependency in the provider-facing projection, and routes
capability-owner actions by authority lineage. The bootstrap slice also makes
the stable native MCP launcher resolve and validate the active versioned
payload, and repair refreshes its stable copy.

## Verification

The codec, focused workspace, MCP, and isolated bootstrap suites passed. The
rebuilt bundle was repaired into the installed runtime, and a native MCP probe
confirmed that the child Java process uses the active versioned payload. The
fresh live run reached two disjoint Synesis claims with both sessions ACTIVE;
Worker A created its domain/persistence slice, while Worker B has not yet
created files or published a capability. The pair has not integrated or
terminalized, so this is not a whole-acceptance pass.

## Current failures

The remaining evidence boundary is worker/task liveness and normal capability
publication in the already-running pair. Do not copy worktrees, edit durable
Synesis state, manually transition lifecycle records, or launch a replacement
pair.

## SYN-044 Require installed provider integration before any agent work — 2026-08-31

- Task ID: SYN-044
- Status: DONE
- Scope: require a Git repository before user-facing `synesis init`, and add
  a fail-closed provider-integration admission check before session binding
  and to the shared readiness path, preserving unborn-Git baseline bootstrap,
  degraded Codex semantics, and the unrelated SYN-043 dependency-admission
  changes.

## Historical next action

Preserve checkpoint CP-0622 and keep the provider/Git changes separate from
SYN-043 implementation work.

## Work completed

Added a provider work-admission predicate backed by the existing provider
status records. Session binding now fails closed before worktree allocation
when the selected provider is missing, unknown, obsolete, or broken; the
shared workspace readiness predicate applies the same gate after binding.
Added the stable `provider_integration_required` response reason and focused
coverage for initialization-only, installed/degraded, and uninstalled states.
Updated workspace and MCP fixtures to use normal project provider installation
instead of treating the global Synesis manual as an integration.

Added a read-only Git repository preflight to the user-facing `synesis init`
command. It fails with `GIT_REQUIRED` before creating `.synesis` state when
Git metadata is missing or unreadable, while preserving the existing unborn
repository baseline commit. Updated generated `AGENTS.md` guidance and the
root contract with matching Claude Code/Codex provider installation commands.
Recorded the boundary in ADR-0050.

## Verification

Using `TEMP`/`TMP=C:\\Temp\\synesis-gradle`, the focused workspace and CLI init
tests pass after this slice, and the complete `:cli:test` suite passes. The
prior full workspace (322 tests) and full MCP (79 tests) suites passed before
this slice. A post-slice aggregate `:workspace:test` rerun was stopped after
becoming process-heavy and silent; it produced no failure report before being
stopped and is incomplete evidence, not a pass claim. `git diff --check` and
the deferred register/checkpoint validator passed.

## Current failures

No SYN-044 test failures remain. The working tree still contains unrelated
pre-existing SYN-043 implementation and documentation changes; do not stage
or commit those as part of SYN-044.

## MAINT-003 Final source-level documentation, packaging, and reconciliation — 2026-08-30

- Task ID: MAINT-003
- Status: ACTIVE
- Handoff: source pass complete; the user-authorized release packaging slice is
  active and must be checkpointed before handoff.
- Scope: audit and document meaningful source contracts and invariants across
  the complete tracked source tree: 572 Java files (409 production and 163
  tests), 8 Go files, and 9 install/validation scripts. Reconcile active
  documentation with current source, CLI, module boundaries, provider IDs, the
  MCP contract, and verified limitations.
  Preserve ADRs, checkpoints, evidence, accepted historical records,
  backward-read compatibility, and unrelated work. The user additionally
  authorized a self-contained platform bundle installer, artifact renaming,
  and Gradle caching.
- Acceptance: important source types and APIs are documented where semantics
  warrant it; comments explain meaning rather than syntax; stale comments are
  corrected or removed; active docs are source-accurate; each platform bundle
  contains a runnable native installer offering install, repair, and uninstall;
  install does not install providers; repair preserves project/workspace data;
  the metadata wipe is explicit; Actions publishes six `synesis-<platform>`
  bundle artifacts with Gradle caching; and verification is honestly
  classified.
- Existing user-owned changes and the unrelated lifecycle stash remain
  protected. No SYN-042, Claude integration, MCP tool addition, release, tag,
  force push, or historical rewrite is allowed. Commit and normal push are
  authorized after this pass is validated.

## Work completed

The prior Antigravity removal and repository cleanup are complete and pushed in
`aee96c3` and `48e0103`. This task completed the separate active-documentation
reconciliation; historical Antigravity records remain preserved.

The whole-tree source documentation pass is complete and was committed as
`2b32aa8` and pushed to `origin/master`. The working tree was clean before this
packaging slice.

The user-authorized packaging slice adds a native installer to each bundle,
uses local bundles for install/repair, adds an explicit metadata-removal option
to uninstall, and removes separate bootstrap/metadata uploads from the release
workflow. The exact design is recorded in ADR-0048.

The build-performance slice removes release packaging from the ordinary Java
`check`, removes the CLI test dependency on `installDist`, limits each native
launcher invocation to the requested bundle and host platforms, enables the
Gradle daemon/configuration/build caches with a 24 GB heap ceiling, and upgrades
GitHub Actions to `gradle/actions/setup-gradle@v6` caching. CI no longer runs
`clean` before verification or builds both archive formats for one platform.

The follow-up configuration-cache repair removes Gradle script/`Project`
captures from every verification task reported by the user's `build` log,
including build metadata, repository hygiene, format checks, and architecture
checks. The task actions now operate on configuration-time-captured files and
providers.

The user's next run narrowed the remaining failure to four `DefaultTask`
actions: CLI build metadata, CLI format checking, Link format checking, and
repository hygiene. Those actions now use isolated custom task types with
Gradle-managed inputs and task actions that do not capture the build script.

The following run exposed an independent missing-file issue: both the root
hygiene task and Link format task listed optional root documents that are not
present in this checkout. Their managed file roots now filter to existing files
before Gradle executes them.

## Verification

- Cleanup baseline: PASS; active residue search, hygiene, focused workspace
  regressions, ten-tool MCP catalog test, compilation, and packaging passed.
- Historical Antigravity records remain preserved; the only active mention is
  the current-state removal note.
- Known validation limits carried forward: three bootstrap migration fixture
  failures (`update migrations not prepared`) and an incomplete root Java
  `check` stopped during process-heavy MCP tests.
- Documentation audit corrections: current-state module/provider/configuration
  summary, project-layout metadata description, provider-maturity matrix, CLI
  command reference, and current-state navigation links.
- Documentation gates: `repositoryHygieneCheck`, deferred-register
  validation, active Markdown links, and the source/CLI cross-check PASS.
- Whole-tree source pass: all 572 Java files now contain Javadoc, all detected
  Java type declarations have documentation, and the previously undocumented
  49 test classes plus 61 nested/helper types were covered. All remaining 8 Go
  files and 9 install/validation scripts also have file-level comments. Routine
  CLI options and obvious locals remain intentionally uncommented.
- Validation after the source pass: strict multi-module `javadoc`,
  `repositoryHygieneCheck`, deferred validation, `git diff --check`, Go vet,
  MCP contract/catalog tests, `TerminalLeaseStateTest`, launcher smoke,
  platform bundle/archive, and bundle smoke PASS. The baseline Go tree still
  reports one existing CRLF-normalization `gofmt -l` finding in the
  `provision-acceptance-key` command; no implementation formatting was introduced
  in this docs-only pass. The
  selected MCP suite and broader workspace lifecycle selection stalled in
  process-heavy test workers without assertion output and were interrupted;
  they remain incomplete.
- Packaging validation: native installer build, focused local
  install/repair/uninstall tests, Go vet, and workflow/diff review pass. The
  Gradle bundle task is currently blocked locally by Gradle's `Unable to
  establish loopback connection`; hosted Gradle bundle execution remains the
  required final evidence.
- Build-performance validation: workflow YAML parsing and `git diff --check`
  pass. Gradle task execution remains blocked before project configuration by
  the same host loopback failure, including direct Gradle 9.7.1 invocation.
- Configuration-cache repair validation: the affected Gradle scripts pass
  deferred validation, workflow parsing, whitespace checks, and Go vet. The
  user's prior build log remains the only execution evidence for the original
  failure; this shell still cannot start Gradle because of loopback failure.
- Residual four-problem repair validation: isolated task-type changes pass
  diff, deferred, workflow, and Go gates; a fresh Gradle cache-store run is
  still required.
- Missing optional-document repair validation: root-file inventory and
  non-Gradle repository gates pass; the reported `CONTRIBUTING.md` failure is
  addressed by filtering absent roots.
- Hosted CI run `33325552193` confirmed the configuration cache now stores
  successfully (`47 actionable tasks`, with `25 from cache`), but `:cli:test`
  failed in eight process-level tests because the earlier performance change
  removed the generated `build/install/synesis` distribution. The fix adds a
  lightweight `testInstallDist` staging task for the Java launcher tests while
  keeping native MCP and installer builds on release-only paths. Local Gradle
  execution remains blocked by the host loopback failure.
- The Gradle Actions summary is disabled for the Java verification job while
  Gradle state caching remains enabled.
- The first post-fix bundle run exposed five configuration-cache problems in
  the release-only native/runtime/bundle/smoke tasks, including execution-time
  `Task.project` access. Those tasks are now explicitly marked as
  configuration-cache-incompatible, and bundle workflow invocations use
  `--no-configuration-cache`; the ordinary Java `check` remains cached.
- The subsequent macOS bundle smoke exposed a missing executable bit on the
  extracted Unix `synesis-installer`. Bundle assembly now sets and validates
  executable permissions for both Unix native binaries, and smoke extraction
  restores the installer permission before invocation.
- The requested final artifact shape is now implemented: each matrix job
  appends its platform archive to the native installer and uploads one
  self-extracting runnable file named `synesis-<platform>-<architecture>`
  (Windows adds `.exe`). The bootstrapper extracts and delegates to its
  embedded installer, while the bundle smoke test exercises the standalone
  file's menu entry point.

## Historical next action

Run the hosted `check` and six-platform bundle matrix again, confirm the
self-extracting files launch on all six targets without configuration-cache or
Unix-permission failures, create a checkpoint, and review the exact staged
artifact boundary before commit/push.

## Current failures

The bootstrap Go suite has three update-migration fixture failures reporting
`update migrations not prepared`. The bounded module-check run reached
process-heavy workspace tests without an assertion result and was stopped; the
selected MCP and workspace lifecycle runs likewise remain incomplete. These
are not source-documentation failures. Local Gradle task execution is blocked
before configuration by `Unable to establish loopback connection`; this is a
host/tooling blocker, not evidence against the build-script changes.

## MAINT-001 IntelliJ analyzer cleanup — 2026-08-29

- Task ID: MAINT-001
- Status: PAUSED while MAINT-002 is active.
- Scope: behavior-preserving cleanup of current IntelliJ file-inspection
  findings; SYN-041 remains closed and its terminal-session semantics are not
  being broadened.
- Baseline: 1,072 findings across 576 Java files: 767 warnings and 305 weak
  warnings. Latest result: 783 findings across 576 Java files, comprising
  484 warnings and 299 weak warnings; no analyzer request failed.
- Acceptance: bounded fixes are re-analyzed and tested; remaining findings are
  either resolved or explicitly classified as intentional/design guidance.
- Existing uncommitted work is preserved. No commit, push, release, or
  SYN-042 creation is part of this task.

## Historical next action

Run a separately reviewed high-confidence warning batch and preserve
intentional lifecycle, polling, reflection, and public-API behavior. The latest
full pass is recorded in checkpoint CP-0573.

## SYN-041 final real Codex closure acceptance — 2026-08-29

- Task ID: SYN-041
- Status: DONE / ACCEPTED; primary result RESULT A.
- The one real Codex lifecycle completed lawful no-change terminalization at
  fence sequence 7. The original native Java runtime was dead before the sole
  rejected same-session probe, which returned `SESSION_TERMINAL` and exited
  cleanly. The final persisted lease is `TERMINAL_DISCONNECTED`; original
  process metadata is preserved.
- Evidence: `docs/evidence/syn041-final-real-codex-closure-2026-08-29.md`.

## Historical next action

Preserve the accepted SYN-041 closure and do not create SYN-042 or broaden
terminal-session semantics without a separately activated task.

## Closure validation

Focused lease/supervision/MCP/SYN-039 regressions, exact 10-tool catalog,
Javadocs, platform bundle, bundle smoke, deferred validation, and diff check
passed. Broader process-heavy MCP selections remain incomplete after host
timeout and are not reported as passing.

## SYN-041 terminal-disconnect trigger implementation — 2026-08-29

- Task ID: SYN-041
- Status: ACTIVE; primary result RESULT C.
- Read-first tracing proved that externally launched Codex MCP has no surviving
  Synesis observer after the packaged Java child dies. Java-local transport
  failures now finalize synchronously, and a later close from a different PID
  verifies the original runtime before refusing clean close and recording
  `TERMINAL_DISCONNECTED` when it is gone.
- Fresh official packaged acceptance passed terminal seal, exact Java PID
  termination, `SESSION_TERMINAL` rejection, probe clean EOF, and final raw
  `TERMINAL_DISCONNECTED` with original identity metadata preserved.
- Evidence: `docs/evidence/syn041-terminal-disconnect-trigger-cp0567-2026-08-29.md`.

## Historical next action

Review CP-0567 and await separately authorized final provider confirmation; do
not run Codex, close SYN-041, change Doctor, or broaden the terminal-session
model.

## Work completed

The production boundary was localized and fixed in the existing lease/MCP
seams. Focused tests, strict Javadocs, fresh official bundle/smoke, and
provider-independent packaged acceptance pass. The full process-heavy MCP
selection remains incomplete after host timeout and is not reported as pass.

## Current failures

No local or packaged production-path failure remains in this slice. Immediate
classification is available when Java observes transport failure; an external
hard kill without a surviving observer is classified at the first later close
through persisted PID evidence. Final real-provider confirmation is deferred.

## SYN-041 CP-0563 terminal transport-history preservation — 2026-08-29

- Task ID: SYN-041
- Status: ACTIVE; bounded CP-0562 defect resolved.
- Root cause was proven in the old no-argument `markClosedCleanly`: a
  rejected same-session connection close unconditionally rewrote a durable
  `TERMINAL_DISCONNECTED` lease to `CLOSED_CLEANLY`.
- The minimal fix serializes clean/abnormal finalization with the existing
  project append lock, makes clean cleanup durable-state-aware, and keeps
  terminal-abnormal finalization PID-gated and idempotent. Metadata is
  preserved; terminal history is not overwritten.
- Focused lease, terminalization, MCP rebind, rejection, supervision, wake,
  catalog, Javadoc, bundle, smoke, and deferred checks passed. The packaged
  provider-independent acceptance preserved `TERMINAL_DISCONNECTED` after a
  rejected probe close. Broader process-heavy MCP tests remain incomplete due
  host timeouts, with no assertion failure observed.
- Evidence: `docs/evidence/syn041-terminal-transport-history-fix-2026-08-29.md`.

## Historical next action

Run `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`, read
CP-0563 and the new evidence, and await a separately authorized final
provider-acceptance decision; do not run a real provider, broaden identity, or
close SYN-041 in this slice.

## Work completed

The CP-0562 lease-history overwrite is fixed and re-verified. Focused source
tests, strict Javadocs, deferred validation, fresh platform bundle/smoke, and
provider-independent packaged acceptance passed. The packaged rejected probe
left the exact raw lease `TERMINAL_DISCONNECTED`; all listed lease metadata
was preserved and Doctor performed zero mutations.

## Current failures

Broader process-heavy MCP selections remain incomplete because they timed out
on this host without assertion output. This is a verification limitation, not
a proven product failure. No real Codex run was authorized for this slice.

## SYN-041 final real Codex terminal-seal acceptance — 2026-08-28

- Status: ACTIVE; primary result RESULT C.
- The one actual real Codex lifecycle used the rebuilt official bundle and
  completed task-bearing `ensure_session`, `read_file`, `get_next_action`,
  lawful no-change `finish_lane`, and explicit `terminalSession=true`.
- Synesis committed `PROVIDER_SESSION_TERMINALIZED` at fence sequence 7 for
  the exact session before native Java/MCP exit code 1; Codex exited 0.
- An exact-connection rebind probe returned `SESSION_TERMINAL`, proving no
  authority reactivation. Its clean probe EOF then rewrote the same lease to
  `CLOSED_CLEANLY`, despite the durable terminal event remaining present.
  This directly violates terminal transport-history immutability.
- Evidence:
  `docs/evidence/syn041-final-real-codex-terminal-seal-acceptance-2026-08-28.md`.

## Historical next action

Inspect `SessionLeaseService.markClosedCleanly` and add a narrow regression
test for refusal to overwrite terminal authority; do not run another provider
experiment, change Doctor, or close SYN-041 until this proven defect is
resolved and re-verified.

## SYN-041 terminal-session seal implementation — 2026-08-28

- Task ID: SYN-041
- Status: ACTIVE
- The first bounded implementation slice is complete. `finish_lane` remains
  lane-only by default; `terminalSession=true` requests an exact-session seal
  after lawful lane completion.
- The append-locked `PROVIDER_SESSION_TERMINALIZED` event fences binding,
  heartbeat, rebind, coordination, review, continuation, and next-action
  admission. Clean EOF remains `CLOSED_CLEANLY`; unsealed abnormal loss keeps
  stale/recovery behavior; sealed abnormal loss is `TERMINAL_DISCONNECTED`.
- SYN-039 remains CLOSED / DONE / ACCEPTED. No generalized identity,
  provider migration, new MCP tool, push, tag, or release was added.

## Historical next action

Run `git status --short` and inspect the SYN-041 implementation/evidence diff
before any future commit; do not commit, push, tag, release, or run another
real provider experiment.

## Work completed

Focused terminal-seal, lease, direct-admission, SYN-039, wake/continuation,
strict-Javadoc, packaged-bundle, bundle-smoke, and synthetic Doctor checks
passed. The first concurrent full-module attempt hit process-test timeouts;
the affected tests passed individually and the authoritative targeted suites
passed serialized with `--max-workers=1`.

## SYN-041 terminal-disconnect semantics — 2026-08-28

The requested design-only investigation is complete. No provider experiment,
production change, lease persistence change, Doctor change, migration, or
generalized identity work was performed. Current source has no irreversible
provider-session authority seal: `WorkGroup.COMPLETED`, participant
`COMPLETED`, binding `COMPLETED`, zero active intents, and zero claims are each
insufficient alone. A completed binding may still serve bounded review-only
authority, and the same connection evidence can be rebound by `ensure`.

Primary classification: RESULT C. A future explicit exact-session terminal
intent plus an atomic no-mutable/no-resumable-authority proof is required
before abnormal transport loss can be considered non-recoverable. Preserve
`CLOSED_CLEANLY` for observed clean EOF and preserve abnormal transport as
separate forensic history. Evidence:
`docs/evidence/syn041-terminal-disconnect-semantics-2026-08-28.md`.

Exact next action: stop SYN-041 at this design boundary; do not implement the
proposal, rerun Codex, change leases or Doctor, reopen SYN-039, or create
SYN-042.

## SYN-041 read-only exit-code causal analysis — 2026-08-28

The source and disposable-control analysis is complete. The native launcher
waits for Java and propagates a Java `exec.ExitError` exit code directly. Java
`McpStdioServer` returns 0 only for clean EOF and returns 1 from its caught
transport/loop `Throwable` path; `handler.close()` is conditional on clean
EOF. Clean EOF produced Java/MCP 0 and `CLOSED_CLEANLY`; partial EOF produced
Java/MCP 1, `MCP_PARTIAL_FRAME_EOF`, and `ACTIVE`; closing the stdout reader
produced Java/MCP 0 and `CLOSED_CLEANLY`. Classification: RESULT B primary,
RESULT D secondary. Evidence:
`docs/evidence/syn041-exit-code-causal-analysis-2026-08-28.md`.

Exact next action: stop SYN-041 at this read-only RESULT B/D boundary; do not
run another provider experiment or change transport, lease, or Doctor policy.

## SYN-041 final native measurement — 2026-08-28

The one authorized handle-based native measurement completed with direct
`Codex -> official synesis-mcp.exe -> packaged Java` parentage. Codex completed
the task and exited 0, while the official MCP and Java children were observed
through retained native handles with exit code 1; both child exits preceded
Codex exit. This is SYN-041 RESULT C / MCP-Java failure evidence. The exact
internal-versus-external origin remains unresolved; no clean-close or lease
defect is proven. Evidence: `docs/evidence/syn041-final-handle-native-measurement-2026-08-28.md`.

Exact next action: stop SYN-041 work at this verified RESULT C boundary and
report the retained artifacts; do not run another provider experiment or
change production code, leases, Doctor, or SYN-039.

## SYN-041 measurement-design slice — 2026-08-28

The design-only investigation is complete; no equivalent provider run was
performed. Evidence: `docs/evidence/syn041-native-observability-design-2026-08-28.md`.
The recommended future measurement retains native process handles with query /
synchronize rights, captures Codex JSONL and stderr to regular files, and
keeps the direct `Codex -> official MCP -> Java` topology. This closes the
current MCP/Java exit-code and timing gap without interposing, but ordinary
Windows user-mode telemetry still cannot attribute a zero-code termination to
the caller or prove literal anonymous-pipe EOF. SYN-041 remains ACTIVE and
RESULT E remains the current classification.

Exact blocker: Need native, non-interposing MCP/Java exit-code and
transport-lifetime telemetry sufficient to classify the child termination
observed ~24.5 seconds before Codex exit.

The five unrelated lifecycle files remain preserved and excluded. No
production files, lease semantics, Doctor classification, provider migration,
identity architecture, or SYN-039 state changed. Nothing was pushed, tagged,
or released.

## SYN-041 real Codex clean-exit provider lifecycle validation

- Task ID: SYN-041
- Status: ACTIVE
- Initial real-provider engagement and exact no-change completion are proven;
  no product defect is established.
- SYN-039 and SYN-040 remain DONE / ACCEPTED / VERIFIED and are not reopened.
- Scope is measurement-only: final native Codex-to-MCP topology, process
  timing, lease state, and Doctor. RESULT E remains because the native polling
  harness lost the Codex transcript and could not directly capture EOF/child
  exit code; no lease defect is proven.

## Historical next action

Preserve RESULT E as the stopping boundary. Do not run another equivalent
probe, implement a lease fix, suppress Doctor, alter lifecycle semantics, or
investigate beyond this task without a new explicit task.

## Work completed

Default ChatGPT authentication works; the earlier 401 was caused by an empty
isolated `CODEX_HOME`. The prior real Codex probe used task-bearing
`ensure_session` but skipped `read_file` and left its first
`get_next_action({})` outstanding. A fresh real Codex run with the supported
task-bearing sequence completed `ensure_session`, `read_file`,
`get_next_action`, exact `finish_lane`, and the WorkGroup. No Synesis product
defect was established.

Evidence target:
`docs/evidence/syn041-real-codex-native-teardown-2026-08-28.md`.

## SYN-040 post-SYN-039 provider-session and Doctor hygiene

- Task ID: SYN-040
- Status: DONE / VERIFIED
- Scope: classify the six post-SYN-039 Doctor warnings and fix only a proven
  provider-session, Doctor-classification, or provider-migration defect.
- SYN-039 remains DONE / ACCEPTED at CP-0547 and is not reopened.
- Existing dirty lifecycle files remain outside scope unless causality is
  proven.

## Historical next action

No product action remains for SYN-040. Preserve the evidence, do not
auto-reconcile the two stale leases, and explicitly promote any future task
before implementation.

## Work completed

The task was explicitly promoted in response to the user's separate
post-SYN-039 hygiene request. Startup validation had found zero active tasks
after SYN-039 closure; the task is now verified complete with no product fix.

Evidence:
`docs/evidence/syn040-post-syn039-doctor-hygiene-2026-08-28.md`.

## SYN-039 closure — final canonical acceptance

- Task ID: SYN-039
- Status: DONE / ACCEPTED
- Checkpoint: CP-0547

Evidence:
`docs/evidence/syn039-final-canonical-acceptance-closure-2026-08-28.md`.

The final canonical acceptance is green. Two independent ordinary Codex
sessions established reviewer-first, order-independent producer/reviewer
roles with disjoint exact claims; published immutable S1; rejected its
objective invalid-index defect; continued the same producer lineage through
claim epoch and intent version 2; published and accepted fresh S2; integrated
only S2; explicitly completed the reviewer's no-change lane; and left zero
active WorkGroups, intents, claims, requests, or current grants.

The final control checkout was clean and the focused tests plus independent
behavior probe passed. Doctor remained DEGRADED with six non-fatal stale-lease,
command namespace/retention, and provider migration warnings. No SYN-039
collaboration state remained active, and no hygiene repair was performed.

## Historical next action

Select and explicitly promote the next authorized task before beginning any
implementation; do not reopen SYN-039 for its separately recorded Doctor
warnings.

## Work completed

Recorded final acceptance closure, evidence references, Doctor caveat, dirty
file classification, and the coherent local SYN-039 completion commit.

## Current failures

No SYN-039 semantic failure remains. Doctor/provider-session hygiene remains
DEGRADED as separately classified non-causal warning state; do not repair it in
this closure task.

## SYN-039 CP-0545 exact-projection diagnostic boundary

Evidence:
`docs/evidence/syn039-diagnostic-cp0548-exact-rule-2026-08-25.md`.

The exact-rule diagnostic used a fresh project, current bundled MCP, and two
ready/isolated independent sessions. They converged on WorkGroup
`7dad9076-f0be-3117-9667-b5260ce1ca1e`, executed exact REVIEW admission and
owner response projections, and consumed grant
`4fe63ef0-418d-3abc-a442-c768a3b73f6a` after one fail-closed malformed retry.

The run stopped before snapshot publication. The implementation participant
ended at a valid `WAIT -> get_next_action({})` continuation; the reviewer then
received `review_validation` polling with no snapshot available and chose a
non-projected pytest action. No unchanged projected action failed and no new
production defect is proven.

## Historical next action

Preserve CP-0545 as agent-compliance evidence and do not change lifecycle
semantics. Run another fresh two-agent acceptance only with new provider-session
termination evidence; stop at the first unchanged projected action failure or
state with no usable projection.

## Work completed

Recorded CP-0545 with exact diagnostic rule, project and bundled executable,
participants, claims/epochs, request/grant, projections and arguments,
fail-closed retry, final WorkGroup, control tests, and Doctor state. No
production code changed.

## Current failures

WorkGroup `7dad9076-f0be-3117-9667-b5260ce1ca1e` remains ACTIVE. Grant
`4fe63ef0-418d-3abc-a442-c768a3b73f6a` was consumed, but no snapshot,
validation, integration, or closure occurred. Control pytest is 1 passed / 1
failed because `todo.py` was not published. Doctor remains DEGRADED with six
warnings; Git/bootstrap issues remain separate.

## SYN-039 CP-0544 ordinary review-continuation boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0547-ordinary-review-continuation-2026-08-25.md`.

The fresh ordinary run used the current bundled MCP and two independent
ready/isolated sessions. Agents converged on WorkGroup
`31941d9a-11dd-3b49-98ab-86042f5b6faa`, created and accepted REVIEW request
`e2ac6ec7-e860-42f3-8dfb-c3acbc8816ae`, and consumed REVIEW grant
`bd3d274c-e4e6-3c63-930d-8ba19b783c5d` after one correctly rejected malformed
retry. Ownership, participant, epoch, and fail-closed checks remained intact.

Both sessions then ended at valid `WAIT -> get_next_action({})`
continuations. No snapshot, validation, integration, or WorkGroup closure was
reached. No unchanged projected action failed and no production defect is
proven; the first blocker remains ordinary provider engagement.

## Historical next action

Run another fresh ordinary unattended two-agent Todo acceptance with only
complementary visible coding prompts. Preserve the first unchanged projected
action failure or required state with no usable projection. Do not alter
review/wait semantics, push, or create SYN-040 from CP-0544 alone.

## Work completed

Recorded CP-0544 with exact project, bundled executable, participant and claim
identities, request/grant, projection/action, fail-closed retry, final
WorkGroup, control test, Doctor, and independent Git-stall evidence. No
production code changed.

## Current failures

WorkGroup `31941d9a-11dd-3b49-98ab-86042f5b6faa` remains ACTIVE. Both claims
remain active; grant `bd3d274c-e4e6-3c63-930d-8ba19b783c5d` was consumed by A,
but no snapshot was published. Control pytest is 1 passed / 1 failed because
the implementation snapshot was not published. Doctor remains DEGRADED with
six warnings; Git/bootstrap issues remain separate.

## SYN-039 CP-0543 ordinary grant-continuation boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0544-ordinary-grant-continuation-2026-08-25.md`.

The fresh ordinary run reached WorkGroup
`f82161ed-39f9-3662-851d-d90f07245a46`, reciprocal REVIEW, grant
`964ea299-c1fb-3298-a61b-d448522fb33d` consumption, immutable snapshot
`snap_0bd17b0b5256e6a3cc6a5a9c79487085`, structured ACCEPT, and integration.
Agent A once omitted a projected grant field and was correctly rejected
fail-closed before retrying unchanged. Agent B changed only the projected
informational finish summary; publication still succeeded.

Both sessions ended at valid `WAIT -> get_next_action({})` continuations
before grant `c7d4b141-32fd-3547-9928-c8cf191cc1b8` consumption and the
implementation snapshot. No unchanged projected action failed and no new
production defect is proven.

## Historical next action

Run another fresh ordinary unattended two-agent Todo acceptance with only
complementary visible coding prompts. Preserve the first unchanged projected
action failure or required state with no usable projection. Do not alter
review/wait semantics, push, or create SYN-040 from CP-0543 alone.

## Work completed

Recorded CP-0543 with complete WorkGroup, participant, claim/epoch,
request/grant, projection/action, snapshot, validation, integration, control
test, Doctor, and fail-closed retry evidence. No production code changed.

## Current failures

WorkGroup `f82161ed-39f9-3662-851d-d90f07245a46` remains ACTIVE. Agent A's
`todo.py` claim remains active and grant
`c7d4b141-32fd-3547-9928-c8cf191cc1b8` remains pending. Control pytest is
1 passed / 2 failed because only the test snapshot integrated. Doctor remains
DEGRADED with six warnings; Git/bootstrap issues remain separate.

## SYN-039 CP-0542 ordinary reciprocal-review boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0543-ordinary-reciprocal-review-2026-08-25.md`.

The fresh ordinary acceptance used the current bundled MCP and two
ready/isolated sessions in one shared WorkGroup
`0b1f5704-1c63-3373-b5ea-7e16ea6c8b79`. Reciprocal REVIEW admission, exact
grant consumption for the test snapshot, immutable publication, structured
ACCEPT, and test-snapshot integration succeeded. The reviewer command
returned 1 passed / 2 failed because the sibling implementation was not yet
integrated; the agent chose the valid structured ACCEPT decision. This is not
an unchanged tool failure.

The implementation lane then received and executed its reciprocal owner
response, but the provider sessions ended at valid `WAIT -> get_next_action({})`
continuations before the second grant and implementation snapshot. No new
production defect is proven; generated `AGENTS.md` already requires continued
polling while unresolved WorkGroup state remains.

## Historical next action

Run another fresh ordinary unattended two-agent Todo acceptance with only
complementary visible coding prompts. Preserve the first unchanged projected
action failure or required state with no usable projection. Do not alter
review/wait semantics, push, or create SYN-040 from CP-0542 alone.

## Work completed

Recorded CP-0542 with complete WorkGroup, participant, claim/epoch,
request/grant, projection/action, snapshot, validation, integration, control
test, and Doctor evidence. No production code changed.

## Current failures

WorkGroup `0b1f5704-1c63-3373-b5ea-7e16ea6c8b79` remains ACTIVE. Agent A's
`todo.py` claim remains active and reciprocal grant
`939017b6-16be-377e-9ff2-915dc002ffc6` remains pending. Control pytest is
1 passed / 2 failed because only the test snapshot integrated. Doctor is
DEGRADED with six warnings; Git/bootstrap issues remain separate.

## SYN-039 CP-0541 ordinary continuation diagnostic

Evidence:
`docs/evidence/syn039-unattended-todo-cp0541-ordinary-continuation-2026-08-25.md`.

The fresh ordinary two-agent run used the current bundled MCP and two
independent ready/isolated sessions in one shared WorkGroup
`94a259df-c1b3-3b0f-8bd9-3c481745c87c`. Exact REVIEW admission, owner
responses, single-use grant consumption, immutable snapshot publication,
structured ACCEPT, and implementation-lane integration succeeded. Every
unchanged concrete projection that was executed succeeded.

The run stopped at valid polling continuations. Agent A ended after polling
`WAIT -> get_next_action({})` for the reciprocal owner response; Agent B later
accepted that response and remained at `WAIT -> get_next_action({})` while the
reciprocal grant targeted at A remained pending. Source inspection confirms
these are intentional wait states, so this is provider/session compliance
evidence, not a new production defect. No production code changed.

## Historical next action

Run another fresh ordinary unattended two-agent Todo acceptance with only
complementary visible coding prompts. Preserve the first unchanged projected
action failure or required state with no usable projection. Do not alter
review/wait semantics, push, or create SYN-040 from CP-0541 alone.

## Work completed

Recorded CP-0541 with complete WorkGroup, participant, claim/epoch,
request/grant, projection/action, snapshot, validation, integration, control
test, Doctor, and Git-stall evidence. Focused workspace tests and validators
pass; no production code changed.

## Current failures

WorkGroup `94a259df-c1b3-3b0f-8bd9-3c481745c87c` remains ACTIVE. A is
COMPLETED, B remains ACTIVE with `test_todo.py`, and reciprocal grant
`976388e2-d7f2-373e-83a1-9f36df6045ca` remains pending because A's provider
session ended before consumption. Control pytest is 2/2 because only the
implementation snapshot integrated. Doctor is DEGRADED with six warnings;
the focused MCP Git subprocess stall and bootstrap migration failures remain
separate verification issues.

## SYN-039 CP-0540 bounded exact-action diagnostic

Evidence:
`docs/evidence/syn039-unattended-todo-cp0540-review-contract-diagnostic-2026-08-25.md`.

The fresh bounded diagnostic used two independent ready/isolated sessions in
one shared WorkGroup. Exact REVIEW admission, owner responses, grant
consumption, immutable snapshot publication, structured ACCEPT, and one-lane
integration all succeeded. The review decision projection intentionally
exposed an explicit ACCEPT/REJECT choice rather than an incomplete command;
the reviewer submitted the valid existing response and Synesis accepted it.

The run stopped at repeated valid `WAIT -> get_next_action({})` continuations
before reciprocal grant consumption and implementation publication. One grant
call changed the projected informational target participant but was still
authorized by the bound caller and consumed; this is agent-compliance evidence.
No unchanged concrete projection failed and no new production defect is
proven.

## Historical next action

Run another fresh ordinary unattended two-agent Todo acceptance with only
complementary visible coding prompts. Do not change the review-decision
contract, push, or create SYN-040 from this evidence alone.

## Work completed

Recorded the fresh CP-0540 exact-action diagnostic with complete WorkGroup,
participant, claim/epoch, request, grant, snapshot, validation, integration,
projection, and Doctor evidence. No production code changed; existing
fail-closed review and grant behavior remains intact.

## Current failures

The diagnostic WorkGroup remains ACTIVE because provider sessions stopped at
valid polling continuations before the reciprocal grant and implementation
snapshot. The control checkout contains the test snapshot but not the
implementation snapshot, so control pytest is 1 passed / 3 failed. Doctor is
DEGRADED with six warnings; the focused MCP test reproduced the known Git
subprocess stall, and bootstrap migration failures remain separate
verification issues.

- Task ID: SYN-039
- Status: ACTIVE
- Checkpoint: CP-0540

## SYN-039 CP-0539 ordinary acceptance compliance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0539-ordinary-compliance-2026-08-25.md`.

The CP-0537 pending outgoing REVIEW projection defect and the CP-0538
completed-lane terminal projection defect are fixed and regression-covered.
Fresh ordinary CP-0539 reached one shared WorkGroup, reciprocal REVIEW
requests, accepted grants, immutable snapshot publication, integration, and
structured ACCEPT. Every unchanged projection remained executable. One agent
omitted a projected `targetParticipant` once and received the expected
fail-closed error before correcting the call; later it stopped despite a
projected `WAIT -> get_next_action({})`. These are provider/session
compliance boundaries, not new production defects.

## Historical next action

Run another fresh ordinary unattended two-agent Todo acceptance with only
complementary visible coding prompts and the rebuilt bundled MCP. Preserve
the first unchanged projected-action failure or required state with no usable
projection. Do not relay, manually transition, push, or create SYN-040.

## Work completed

Fixed and regression-covered the pending outgoing REVIEW re-projection and
the completed-lane terminal projection defects. CP-0539 exercised both fixes
through accepted reciprocal REVIEW, grant consumption, immutable snapshot
publication, snapshot validation, structured ACCEPT, and integration. No
unchanged projected action failed. Nothing was pushed and no SYN-040 was
created.

## Current failures

The ordinary acceptance remains incomplete because provider sessions stopped
despite usable `WAIT -> get_next_action({})` continuations; one earlier grant
call also omitted a projected `targetParticipant` and was correctly rejected
fail-closed before correction. Fixture Doctor remains DEGRADED with six
warnings, while the Git subprocess stall and bootstrap migration failures are
separate verification issues.

- Task ID: SYN-039
- Status: ACTIVE
- Checkpoint: CP-0537

## SYN-039 CP-0536 review projection fix and post-fix acceptance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0536-review-gate-2026-08-25.md`.

The recorded CP-0535 state exposed a concrete stale-review projection defect:
after a REVIEW grant had a completed validation, the fallback branch could
re-project `REVIEW_ADMISSION_REQUIRED` for that already-reviewed intent. The
narrow `AgentNextActionService` filter and deterministic MCP regression are
implemented. Provider guidance also now requires executing concrete mutation
continuations and polling `get_next_action` after every completed mutation.

The fresh post-fix bounded diagnostic reached one shared WorkGroup, exact
REVIEW admission, grant consumption, immutable snapshot publication, snapshot
inspection, and structured ACCEPT. It stopped with a reciprocal grant pending
because the provider session did not remain engaged; no unchanged projected
action failed. The ordinary post-fix probe stopped at the same provider/session
engagement boundary before the second snapshot. This remains agent/session
compliance evidence, not a new production lifecycle defect.

## Historical next action

Run one fresh ordinary unattended two-agent Todo acceptance using only
complementary visible coding prompts and the rebuilt bundled MCP. Preserve the
first unchanged projection failure or missing usable action. Do not relay,
manually transition, push, or create SYN-040.

## SYN-039 CP-0535 continuation diagnostic and ordinary acceptance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0534-continuation-2026-08-25.md`.

The fresh bounded diagnostic applied the exact-action rule to both
`get_next_action` projections and concrete continuations returned by mutating
tools. It completed one shared WorkGroup through REVIEW admission, both
single-use grants, immutable snapshots, structured ACCEPT decisions,
integration, and terminal `COMPLETED` state. No unchanged projected action
failed.

The required second run used only ordinary complementary coding prompts. It
reached one shared WorkGroup, exact admission, grant consumption, immutable
snapshot publication, integration, and structured ACCEPT for Agent A. Agent
A then ended its provider turn after `finish_lane` returned the exact
reciprocal `request_coordination(work_group_join)` continuation for Agent B's
intent. Agent B remained active and later received only ordinary `IMPLEMENT`
projections with no executable lifecycle action. This is provider/session
compliance evidence, not a proven production lifecycle defect. No production
code changed.

## Historical next action

Preserve CP-0535 evidence and checkpoint state. Do not modify lifecycle code,
push, or create SYN-040. A future SYN-039 slice is not justified unless an
unchanged executable continuation fails or a required state still has no
usable projection after all preceding projections are executed.

## SYN-039 CP-0534 ordinary acceptance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0534-ordinary-2026-08-25.md`.

The fresh ordinary acceptance used the rebuilt bundled MCP, two independent
ready/isolated sessions, one shared WorkGroup, and disjoint epoch-1 claims.
The run reached exact REVIEW admission, owner acceptance, single-use grant
consumption, immutable snapshot publication, integration, and structured
ACCEPT. A's `finish_lane` result then projected the exact reciprocal
`request_coordination(work_group_join)` for B's intent, but A's provider turn
ended without executing it. B later corrected one invalid review identity and
then followed bounded polling; `get_next_action` repeatedly returned ordinary
`IMPLEMENT` with no executable lifecycle action while B remained ACTIVE.

No unchanged `get_next_action` projection failed. The first concrete boundary
is agent/session compliance with a continuation returned by a mutating tool,
not a new production protocol defect. No second ordinary acceptance was run.

## Historical next action

Run one bounded diagnostic applying the exact-projected action rule to
continuation actions returned by mutating tools as well as `get_next_action`.
Do not modify lifecycle code, relay messages, or trigger transitions unless an
unchanged executable action fails or the same state still has no usable
projection after all preceding continuations are obeyed.

## SYN-039 CP-0533 ordinary acceptance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0533-ordinary-2026-08-25.md`.

The fresh ordinary acceptance used the current bundled MCP, the same project,
distinct ready/isolated sessions, and disjoint epoch-1 claims. Both agents
converged on WorkGroup `62e57bbc-897e-3946-a5d2-5082d1e1a2c1`. Agent B
executed the exact projected `request_coordination(work_group_join)` and
created pending REVIEW request `574c290b-36bd-417e-9286-dce2d9a57cc6`.
Agent A had already ended its ordinary coding turn while its last projection
was `IMPLEMENT` without a lifecycle action, so it never received the later
owner-acceptance projection. No grant, snapshot, validation, integration, or
terminal WorkGroup state was created. Control pytest was `4 failed, 1 passed`.

No unchanged projected Synesis action failed. The first blocker is ordinary
provider/session engagement, not a proven production protocol defect.

## Historical next action

Run one bounded continuity diagnostic with the same two independent provider
sessions and no manual relay: preserve the owner session across the delayed
peer REVIEW request, then verify whether Synesis projects and the owner
executes the exact acceptance action. Do not add retries, orchestration,
cleanup, or argument repair unless an unchanged projected action fails.

## Work completed

Recorded the CP-0533 fresh ordinary acceptance and exact provider traces. The
run proved shared WorkGroup convergence and successful exact REVIEW admission
request creation, but not owner acceptance or later lifecycle stages. No
production code changed, no remote state was modified, no lifecycle transition
was manually triggered, and no SYN-040 was created.

## Current failures

The remaining SYN-039 acceptance gap is ordinary provider/session engagement:
the owner session ended after ordinary implementation work before a later peer
REVIEW request existed, leaving that request pending. No unchanged projected
Synesis action failed. Control pytest is `4 failed, 1 passed`; fixture Doctor
is DEGRADED with six warnings and no errors or critical findings. The root Git
subprocess stall and bootstrap migration failures remain independent.

## SYN-039 CP-0532 exact diagnostic closure and ordinary acceptance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0532-exact-diagnostic-and-ordinary-2026-08-25.md`.

The fresh exact-projection diagnostic used two independent GPT-5.6 Luna High
sessions and the current bundled MCP. WorkGroup
`35931e39-9eb1-3693-b03e-b89fc7088b72` reached exact REVIEW admission, both
single-use grants, immutable snapshot inspection, structured ACCEPT decisions,
both snapshot integrations, and `workGroupStatus=COMPLETED`. The control
checkout was clean at `04c2720` and reported `5 passed`. Every concrete
projected lifecycle action in the retained trace was executed exactly; no
unchanged projection failed.

The required second ordinary acceptance used only complementary coding
prompts. WorkGroup `4646b6ba-66bc-3760-8fda-fc04b9db1b66` integrated the test
snapshot and exercised structured REJECT routing, but an ordinary reviewer
first sent unsupported review fields, then a wrong intent, and the provider
sessions stopped before reciprocal grant consumption and implementation
publication. Control reported `1 failed, 4 passed`. These are agent
compliance/session-engagement findings, not a proven production defect.

The full root `check` still reproduces the known Git subprocess startup stall
in `ProcessCommandRunner.execute` during `:cli:test`; bootstrap migration
failures and Doctor warnings remain separately classified.

## Historical next action

Run one fresh ordinary unattended two-agent Todo acceptance with only the
complementary coding prompts and the current bundled MCP. Do not relay,
trigger, repair, or coach lifecycle actions. Preserve the first unchanged
projection failure or missing usable action; classify wrong agent arguments and
provider-turn termination separately unless an exact projected action fails.

## Work completed

Recorded the CP-0532 exact diagnostic and ordinary acceptance evidence. The
exact-rule run proved end-to-end protocol correctness and terminal WorkGroup
completion; the ordinary run preserved fail-closed rejection and exposed only
agent/session engagement gaps. No production code changed, no remote state was
modified, no lifecycle transition was manually triggered, and no SYN-040 was
created.

## Current failures

The remaining SYN-039 product acceptance gap is ordinary provider/session
engagement: the CP-0542 ordinary run stopped with reciprocal review state still
active after invalid agent-selected review payloads were rejected fail-closed.
The exact CP-0541 diagnostic completed the same lifecycle, so no new Synesis
protocol defect is proven. Fixture Doctor remains DEGRADED with six warnings;
the root Git subprocess stall and three bootstrap migration test failures remain
independent verification issues.

## SYN-039 CP-0530 pytest-artifact recovery fix and post-fix diagnostic

Evidence:
`docs/evidence/syn039-unattended-todo-cp0530-bytecode-recovery-2026-08-25.md`.

The CP-0530 baseline proved a concrete readiness/recovery defect: normal
pytest-generated `__pycache__/` files made a reviewer worktree appear dirty,
so the unchanged projected `ensure_session({})` action returned
`failed / internal_failure / request_human_help`. The stale workspace
classifier was inconsistent with the existing `SnapshotArtifactPolicy`,
which already treats `__pycache__/` as an allowed runtime artifact.

`ProviderSessionBindingService` now recognizes only `__pycache__/` as the
additional ephemeral category for stale-workspace cleanliness and confirmed
user-change checks. Unknown untracked content remains fail-closed. The new
workspace regressions were red before the change and green after it.

The post-fix diagnostic used a fresh project, current bundled MCP, two
independent GPT-5.6 Luna High sessions, disjoint epoch-1 claims, and one
shared WorkGroup. B executed the exact projected recovery action and was
rebound successfully; the recovery defect is therefore fixed. The run later
stopped on agent compliance when A supplied a malformed `intentId` instead of
the exact projected REVIEW admission payload, then ended while A still had a
valid projected continuation. No unchanged projected action failed. The
conditional second ordinary acceptance was not run.

## Historical next action

Run one fresh bounded two-agent exact-projection diagnostic with the current
rebuilt MCP and the same complementary Todo responsibilities. Capture whether
both agents execute every unchanged projection, especially the reciprocal
REVIEW request and subsequent `WAIT -> get_next_action({})`, without any relay
or manual transition. Do not change production code from agent-selected wrong
arguments or a provider turn ending; only a concrete unchanged projected
action failure or missing usable action can justify another SYN-039 slice.

## Work completed

Implemented and verified the narrow pytest-artifact recovery fix, added
fail-closed regression coverage for real untracked user content, rebuilt the
Windows platform bundle, and recorded the fresh post-fix diagnostic. No remote
state was modified, no lifecycle transition was manually triggered, and no
SYN-040 was created.

## Current failures

The remaining SYN-039 blocker is ordinary agent/session engagement: CP-0530
WorkGroup `ab7d068e-f6cb-3c88-84e7-be59bf3e2c20` did not reach terminal closure
because A first changed the projected intent ID and later ended while its
reciprocal review continuation remained unresolved. Fixture Doctor remains
DEGRADED with six warnings. The known root Git subprocess stall and three
bootstrap migration test failures remain independent verification issues.

## SYN-039 CP-0529 continuity probe and ordinary acceptance

Evidence:
`docs/evidence/syn039-unattended-todo-cp0529-continuity-and-ordinary-2026-08-25.md`.

The supported non-ephemeral continuity probe preserved the same participant
and session identities across the durable WAIT boundary. The implementer
received and executed the exact projected `finish_lane` action, publishing
immutable snapshot `snap_3e21542358dd37d57cb6963d6f128557`. The reviewer then
reached a projected `ensure_session`, but its ordinary pytest-generated
`__pycache__` files made the review worktree dirty under the existing
fail-closed cleanliness rule; `ensure_session` returned
`failed / internal_failure / request_human_help`. No unchanged projected
Synesis action failed and no production defect was proven.

The fresh ordinary acceptance reached shared WorkGroup
`c8834a58-fe9d-3a75-8b56-bbf7a86f7a6a`, integrated test-only snapshot
`snap_d678f31fc5591c897c7a648c41d4322d`, and recorded a structured ACCEPT.
The reviewer then received the exact executable `request_coordination`
projection for the reciprocal REVIEW admission but chose another
`get_next_action` call and its turn ended. The implementation snapshot was
not published; the control checkout therefore remained at `2 failed, 2
passed`. This is agent/session engagement evidence, not a proven protocol
defect.

## Historical next action

Run one fresh ordinary unattended two-agent Todo acceptance with only
complementary coding prompts and the current bundled MCP. Do not provide
lifecycle coaching, relay messages, trigger transitions, or repair state
manually. Keep the known root Git subprocess stall, bootstrap migration
failures, and six Doctor warnings separately classified. Do not change
production code, push, or create SYN-040 unless an unchanged projected action
fails or required progress has no usable projection.

## Work completed

Recorded the CP-0529 continuity and ordinary acceptance evidence. No
production code changed, no remote state was modified, and no manual
coordination transition, relay, ownership repair, merge, or control-checkout
mutation was performed.

## Current failures

The remaining SYN-039 gap is ordinary provider/session engagement: WorkGroup
`c8834a58-fe9d-3a75-8b56-bbf7a86f7a6a` is ACTIVE with B COMPLETED and A
ACTIVE after B ignored the exact projected reciprocal REVIEW admission.
Doctor is DEGRADED with six warnings and no errors or critical findings.
The root Git subprocess stall and bootstrap migration failures remain
independent verification issues.

## SYN-039 CP-0528 bounded diagnostic and ordinary acceptance

Evidence:
`docs/evidence/syn039-unattended-todo-cp0528-diagnostic-and-ordinary-2026-08-25.md`.

The fresh bounded exact-projection diagnostic used the current bundled MCP,
two independent GPT-5.6 Luna High sessions, disjoint epoch-1 claims, and one
WorkGroup `f0c02558-ab10-3bf6-b369-1d21011ffe64`. Exact REVIEW admission,
owner responses, both single-use grant consumptions, both immutable snapshot
publications, structured ACCEPT decisions, integration, control pytest 4/4,
and terminal WorkGroup `COMPLETED` all succeeded. No unchanged projected
lifecycle action failed.

The required second ordinary acceptance used only the complementary coding
prompts. It reached WorkGroup `b319999f-7060-360b-a26b-0a0891e23be1`,
integrated implementation snapshot `snap_3596291c5018036277f7a780da877dea`,
and recorded reviewer ACCEPT. The implementer session ended after submitting
the reciprocal REVIEW request; grant `abacded1-ee9c-354f-9271-dabcd00bffa5`
targeted that ended participant and remained unresolved. The WorkGroup stayed
ACTIVE; control pytest passed 3/3 and contains the implementation but not the
test-lane snapshot. This is agent/session engagement evidence, not a proven
protocol defect.

## Historical next action

Run one fresh supported non-ephemeral Codex provider-session continuity probe
across the ordinary pending-grant `WAIT -> get_next_action({})` boundary,
preserving both independent sessions and without lifecycle coaching, relay,
manual transitions, replacement intents, or production changes. If the
provider still ends before consuming the exact projected grant, classify the
boundary as external agent/session behavior and keep Synesis production code
unchanged. Do not push or create SYN-040.

## Work completed

Recorded CP-0528 evidence for both the successful exact-projection diagnostic
and the incomplete ordinary acceptance. No production code changed and no
remote state was modified.

## Current failures

The remaining SYN-039 acceptance gap is ordinary provider/session engagement:
the ordinary run leaves WorkGroup `b319999f-7060-360b-a26b-0a0891e23be1`
ACTIVE with a valid single-use grant targeted at the ended implementer. No
exact projected lifecycle action failed. Doctor remains DEGRADED with six
warnings; the root Git subprocess stall and bootstrap migration failures are
separately classified.

## SYN-039 CP-0527 REVIEW replay fix and post-fix diagnostic

Evidence:
`docs/evidence/syn039-unattended-todo-cp0527-review-replay-fix-2026-08-25.md`.

The prior ordinary acceptance exposed a real unchanged projected REVIEW
admission failure: replay after the target lane released its live intent
returned `INTENT_NOT_FOUND`. Commit `81aa2f6` now checks the durable exact
REVIEW request before resolving the released live intent. Deterministic
coordination and MCP regressions pass, including idempotent replay and
fail-closed new requests.

The fresh post-fix diagnostic reached one WorkGroup, exact REVIEW admission,
owner responses, grant consumption, immutable snapshot publication,
integration, and structured REJECT. The provider turn ended before the
reciprocal REVIEW grant was consumed, so the WorkGroup remains ACTIVE. No
unchanged projected action failed in the post-fix run.

## Historical next action

Run one fresh completely ordinary unattended two-agent Todo acceptance using
only the complementary coding prompts and the current bundled MCP. Do not
provide protocol-conformance instructions, relay messages, trigger lifecycle
actions, or repair state manually. Stop at the first unchanged projected
action failure or state requiring progress with no usable projection. If it
reaches clean WorkGroup completion, inspect the remaining Doctor warnings.
Do not push or create SYN-040.

## Work completed

The released-lane REVIEW replay defect is fixed in `81aa2f6`; the exact
pre-fix failure, red/green regression evidence, post-fix IDs and action trace,
and independent verification classifications are recorded in the evidence
file above. No remote state was changed.

## Current failures

The required ordinary end-to-end acceptance is still incomplete at the
provider/session continuation boundary: WorkGroup
`ff42da2a-719f-34cb-8851-de17edb9aba8` is ACTIVE with reciprocal grant
`6eb5cd9c-f949-3080-9bc1-5391a6db17cd` pending after the Codex turn ended.
Doctor remains DEGRADED with six separately classified warnings. The root Git
subprocess stall and bootstrap migration failures remain independent
verification issues.

## SYN-039 CP-0525-003 bounded and ordinary acceptance

Evidence:
`docs/evidence/syn039-unattended-todo-cp0525-003-bounded-and-ordinary-2026-08-25.md`.

The fresh bounded exact-projection diagnostic completed WorkGroup
`18f226ad-d28b-3fd6-b8aa-3afb83429f4b` through REVIEW admission, exact grant
consumption, immutable snapshot publication, structured ACCEPT, integration,
and terminal completion. No unchanged projected action failed. The required
ordinary acceptance reached WorkGroup `0d6e6301-e6d1-3084-b0be-abbca3cdaa10`,
integrated A's implementation snapshot, and accepted it from B, but A's
Codex session ended before the reciprocal grant targeted at A was consumed.
This remains provider/session engagement evidence, not a proven production
defect.

## Historical next action

Run one supported non-ephemeral Codex provider-session continuity probe in a
fresh disposable project, preserving both independent sessions across a
durable `WAIT -> get_next_action({})` boundary. Do not manually invoke a
Synesis lifecycle action, create a new intent to repair the old run, relay
coordination, or modify production code. If the provider still ends without
executing the unchanged projection, classify the limitation at the provider
session boundary and keep Synesis lifecycle code unchanged. Do not push or
create SYN-040.

## Work completed

The CP-0525-003 bounded and ordinary traces, exact IDs, terminal states, and
Doctor output are recorded in the evidence file above. No production code
changed in this slice.

## Current failures

The ordinary product acceptance remains incomplete only at provider/session
continuation: WorkGroup `0d6e6301-e6d1-3084-b0be-abbca3cdaa10` is ACTIVE with
the reciprocal grant targeted at the ended A session. The bounded protocol
diagnostic completed cleanly. Doctor remains DEGRADED with six warnings. The
root Git subprocess stall and bootstrap migration failures remain separately
classified.

## SYN-039 CP-0525 bounded and ordinary acceptance

Evidence:
`docs/evidence/syn039-unattended-todo-cp0525-002-bounded-and-ordinary-2026-08-24.md`.

The fresh bounded exact-projection diagnostic completed one shared WorkGroup
through REVIEW admission, exact grant consumption, immutable snapshot
publication, structured REJECT/ACCEPT, integration, and terminal WorkGroup
completion. No unchanged projected action failed. The required second ordinary
acceptance reached one shared WorkGroup, integrated both complementary
snapshots, and passed control pytest 3/3, but stopped with WorkGroup
`5e0a82d7-635d-3e47-9e3e-5a4c37d83822` ACTIVE while the agent session did not
continue the valid projected reviewer continuation. This remains agent/session
engagement evidence, not a proven production defect.

## Historical next action

Run one bounded provider-session continuation check from the preserved CP-0525
ordinary `WAIT` boundary, retaining the same participant/intent and executing
only the projected `get_next_action({})`. Do not create a new coding intent or
relay lifecycle state. If the provider ends again without executing the exact
continuation, classify the limitation as external agent/session behavior and
do not change production lifecycle code. Do not push or create SYN-040.

## Work completed

The bounded and ordinary acceptance traces and terminal states are recorded in
the evidence file above. No production code changed in this slice.

## Current failures

The ordinary product acceptance remains incomplete only at the Codex
session-engagement boundary. The ordinary control checkout passed 3/3 but
contains a disposable untracked `__pycache__/` artifact; this is not evidence
for changing integration behavior. Doctor remains DEGRADED with six warnings.
The root Git subprocess stall and bootstrap migration failures remain
separately classified.

## SYN-039 CP-0522 third ordinary acceptance

The third fresh ordinary run used current bundled MCP sessions and the same
two visible coding prompts. Both agents reached ready/isolated state and one
shared WorkGroup. A implemented `todo.py`, then followed exact REVIEW
admission and grant-consumption projections. Once A received `WAIT` with
`recommendedTool=get_next_action` and `{}`, it instead performed unprojected
reads/recovery and hit the expected fail-closed `workspace_stale` and
`internal_failure` responses. B's exact `finish_lane` succeeded, publishing
and integrating `snap_012bbfe1bc5f22b8e69d51e9638b4c05`; A rejected that
snapshot because its completion test failed against the incomplete base.
WorkGroup `e769b143-f9b0-337f-b06a-9eb1603c8cc9` remains `ACTIVE`.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0522-third-ordinary-2026-08-24.md`.

No unchanged projected lifecycle action failed and no production protocol
defect is proven. This is a repeat of the ordinary agent/session projection
compliance boundary, with additional evidence that a test-only lane can be
published before the sibling implementation lane is integrated when the
agent executes the projected action despite its local failing test.

## Historical next action

Keep production code unchanged. Run a bounded provider-session continuity
diagnostic at a fresh durable `WAIT → get_next_action({})` boundary, recording
whether the ordinary Codex session can execute the unchanged projection and
continue to the next action without lifecycle coaching. Implement only a
concrete unchanged projected-action failure; otherwise preserve the
agent/session limitation. Do not push or create SYN-040.

## Work completed

The third ordinary acceptance evidence is recorded alongside the successful
bounded CP-0522 diagnostic and the prior ordinary run. No production code
changed and nothing was pushed.

## Current failures

The real ordinary acceptance remains incomplete: A ignored a concrete WAIT
projection and did not publish its implementation snapshot; B's test snapshot
integrated but control pytest is `1 failed, 2 passed`. The WorkGroup remains
ACTIVE with an unresolved REVIEW grant. Doctor remains DEGRADED with six
warnings. The root Git subprocess stall and bootstrap migration failures are
still independent verification issues. No SYN-040 is defined or created.

## SYN-039 CP-0522 valid diagnostic and ordinary acceptance

The corrected bounded diagnostic used a genuinely incomplete Todo seed and
completed the existing protocol without relay: shared WorkGroup discovery,
exact REVIEW admission, both single-use grants, immutable snapshot
publication, structured ACCEPT decisions, integration, and terminal
WorkGroup completion. Evidence:
`docs/evidence/syn039-unattended-todo-cp0522-valid-diagnostic-and-ordinary-2026-08-24.md`.

The second run used only the two ordinary coding prompts. It reached one
shared WorkGroup, integrated Agent A's snapshot, and submitted B's ACCEPT,
but A's Codex turn ended after a valid projected reciprocal
`request_coordination` action was exposed. B correctly remained in exact
projected `WAIT`; WorkGroup
`0f999cd8-e9b2-38cc-a382-ab6722b76139` remained `ACTIVE`, with A's grant
`051f07ff-e0c0-3f10-8422-705d066afc57` pending. No unchanged projected
action failed and no production protocol defect is proven.

## Historical next action

Run one third fresh ordinary unattended two-agent Todo acceptance with the
same actual complementary coding prompts and no lifecycle coaching. Retain
the exact projection/action trace. If the same turn-ending boundary repeats,
record it as the external agent/session blocker; do not change production
coordination, review, snapshot, validation, integration, cleanup, or Doctor
behavior without a concrete unchanged projected action failure or a missing
usable action.

## Work completed

The bounded diagnostic and ordinary acceptance evidence are recorded. The
current bundled MCP, focused SYN-039 tests, validators, Javadocs, Go vet,
control pytest, and `git diff --check` remain passing where previously
verified. No production code changed in CP-0522 and nothing was pushed.

## Current failures

The ordinary product acceptance remains incomplete only at agent/session
engagement: Agent A ended instead of consuming a repeated concrete
projection, while Agent B correctly followed `WAIT`. Doctor remains
DEGRADED with six separately classified warnings. The root Git subprocess
stall and bootstrap migration failures remain independent verification
issues. No SYN-040 is defined or created.

## SYN-039 CP-0521 continuation diagnostic seed correction

The bounded continuation diagnostic used a fresh project, but its seed already
implemented `TodoList.complete`. Agent A correctly made no artificial edit,
remained in `IMPLEMENT`, and never reached snapshot publication. Agent B
correctly waited at `SNAPSHOT_PENDING`, later added its test, and passed 4/4;
no snapshot, validation, integration, or closure occurred. This is invalid
fixture/agent evidence, not a Synesis defect.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0521-invalid-seed-continuation-2026-08-24.md`.

## Historical next action

Run a fresh bounded two-agent diagnostic with a genuinely missing
`TodoList.complete` implementation, current bundled MCP, and the existing
no-code completed-lane continuation harness. Preserve the first unchanged
projected-action failure or missing usable action. If the diagnostic closes,
run the ordinary acceptance with only the two coding prompts. Do not change
production code, push, or create SYN-040 unless a concrete Synesis defect is
reproduced.

## Work completed

CP-0521 invalid-seed evidence is recorded. No production code changed. The
current MCP bundle, focused SYN-039 tests, validators, Go vet, and prior
CP-0520 acceptance evidence remain preserved.

## Current failures

The overall ordinary acceptance remains incomplete. CP-0520 stopped because
Agent A ended before reciprocal REVIEW-grant consumption; CP-0521 did not
exercise that phase because its seed was already complete. No production
protocol failure is currently proven. Doctor warnings, the root Git stall,
and bootstrap migration failures remain separately classified.

## SYN-039 CP-0520 ordinary completed-lane acceptance

The fresh ordinary acceptance used the current bundled MCP, two independent
ready/isolated Codex sessions, disjoint claims, and one shared WorkGroup
`5c1609bd-f88d-36e5-845b-0f07677e9ffe`. Agent A published and integrated
snapshot `snap_41f8664537c23fe67293f8e08f740fa6`; Agent B consumed the REVIEW
grant, inspected the immutable snapshot, and submitted ACCEPT. The corrected
harness stopped resuming A after its original lane became `COMPLETED`, so it
did not create a replacement intent.

Agent A then ended without polling its already projected reciprocal REVIEW
grant. Agent B correctly remained in `WAIT` with
`recommendedTool=get_next_action` and `{}` while grant
`22bc7d10-0337-31c9-9155-6de7f0130b73` targeted A. WorkGroup state remained
`ACTIVE`; no unchanged projected action failed. This is ordinary agent/session
engagement evidence, not a production protocol defect.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0520-ordinary-completed-lane-2026-08-24.md`.

## Historical next action

Run one bounded no-code continuation diagnostic in a fresh disposable project:
retain a completed participant only long enough to execute any already
projected REVIEW action, without announcing a new coding intent or relaying
coordination; if it closes cleanly, rerun the ordinary two-agent acceptance.
Do not change production lifecycle code, push, or create SYN-040 unless an
unchanged projected action fails.

## Work completed

Fresh ordinary acceptance evidence is recorded. The current MCP bundle,
focused `McpSyn039SliceTest`, deferred/fixture validators, Go vet, control
pytest 3/3, and `git diff --check` pass. No production code changed in this
slice.

## Current failures

Ordinary acceptance is still partial: Agent A ended after publishing its
integrated snapshot and exact REVIEW admission request, before polling the
reciprocal REVIEW grant targeted at A. Agent B correctly remained in exact
WAIT, so WorkGroup `5c1609bd-f88d-36e5-845b-0f07677e9ffe` remains `ACTIVE`.
This is agent/session engagement evidence; no unchanged projected action
failed and no production defect is proven. Doctor remains DEGRADED with six
separately classified warnings.

## SYN-039 command-scope recovery and post-fix acceptance

The CP-0519 ordinary acceptance proved a concrete MCP command-scope defect:
after successful clean recovery moved a participant to a new verified
worktree, `run_command` retained the old physical `ProjectCommandProcessAnchor`
and returned `command_admission_stale / MCP_PROCESS_SCOPE_CHANGED`. The narrow
fix is implemented in `McpProtocolHandler`; successful `ensure_session` now
re-arms the anchor only when a separately verified worktree locator changed.
The deterministic regression and full `McpSyn039SliceTest` pass.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0519-command-scope-recovery-2026-08-24.md`.

The post-fix exact-action diagnostic reached both reciprocal review paths,
single-use grant consumption, snapshot publication, a structured REJECT and
ACCEPT, integration, clean control `pytest` 3/3, and WorkGroup
`89fea014-9f5b-326b-8521-5d2218cc55fc` `COMPLETED`. The second ordinary run
reached the same core lifecycle but retained an extra continuation participant
after the harness resumed a completed lane; its WorkGroup
`dfc93a1a-de2e-3db4-859e-c0eb7d60eaab` remains `ACTIVE`. That remainder is
agent/session engagement evidence, not a new production defect.

## Historical next action

Run one fresh ordinary unattended two-agent Todo acceptance with retained
sessions, but do not resume a completed lane as a new coding intent. Preserve
the first unchanged projected-action failure or missing usable action. Do not
change production lifecycle behavior, push, or create SYN-040 without that
evidence.

## Work completed

Focused MCP regression, full SYN-039 MCP slice, MCP Javadocs, bundled MCP
rebuild, deferred and fixture validators, Doctor structural checks, Go vet,
and `git diff --check` pass. Fixture Doctor remains DEGRADED with six
separately classified warnings. The known root Git subprocess stall,
bootstrap migration failures, and documentation format findings remain
independent verification issues.

## Current failures

The clean exact-action diagnostic is complete, but ordinary product acceptance
is not yet clean: the retained Codex harness continued after A's original lane
was integrated, created a new active `todo.py` participant, and stopped with
an ACTIVE WorkGroup. This must be resolved as harness/session engagement
behavior or reproduced in a clean ordinary run before any further production
change.

## SYN-039 CP-0536 terminal-WorkGroup guard and acceptance evidence

CP-0535 proved a concrete lifecycle defect: a late disjoint intent could be
announced into a completed WorkGroup, leaving an active participant with no
executable completion action. The narrow fix is present in
`WorkIntentService` and `WorkspaceCollaborationService`, with deterministic
coordination and workspace regressions. Evidence and the fresh acceptance
traces are recorded in
`docs/evidence/syn039-unattended-todo-cp0536-bounded-and-ordinary-2026-08-24.md`.

The bounded CP-0536 diagnostic reached exact REVIEW admission, grant
consumption, both snapshot publications, immutable review inspection,
structured REJECT/ACCEPT, integration, and WorkGroup `COMPLETED` with control
pytest 5/5. The second ordinary acceptance reached one shared WorkGroup and
integrated the test snapshot, but stopped when an agent changed then ignored a
concrete projected coordination action. Its WorkGroup remains ACTIVE with the
reciprocal grant unresolved. No additional production defect is proven.

## Historical next action

Run one fresh ordinary unattended two-agent Todo acceptance with the harness
retaining both independent Codex sessions across durable WAIT/projection
continuations; do not relay or trigger transitions, and do not change
production code unless an unchanged projected action fails. Do not push or
create SYN-040.

## Work completed

The terminal-WorkGroup guard and fresh-default-group allocation are implemented
and their deterministic RED/GREEN regressions pass. The CP-0536 bounded run
proves the existing review/snapshot/validation/integration path can close a
WorkGroup. The ordinary run is preserved as agent-compliance evidence rather
than used to justify speculative lifecycle changes. Focused tests, Javadocs,
bundle rebuild, validators, Go vet, and diff checks pass; the known full-check,
Git subprocess, bootstrap migration, and Doctor issues are recorded in the
CP-0536 evidence.

## Current failures

The ordinary CP-0536 WorkGroup
`1c9fd0e2-eda4-3505-a20e-db86de14ec8a` remains ACTIVE because Agent B did not
execute its projected `request_coordination(work_group_join)` and then ended
its turn; reciprocal grant `4ba34d35-976a-3d55-bc40-0d7c9656f46b` remains
unresolved. This is not an unchanged projected-action failure. The root Git
subprocess stall, three bootstrap migration failures, and Doctor's six
DEGRADED warnings remain separately classified.

## SYN-039 CP-0534 reviewer snapshot access

CP-0534 is recorded in
`docs/evidence/syn039-unattended-todo-cp0534-review-snapshot-access-2026-08-24.md`.
Commit `a03abe0` fixes the concrete reviewer snapshot-access defect: an
authorized reviewer can use the existing `read_file` and `run_command` tools
against a disposable immutable snapshot workspace after the control checkout
advances, without rebinding, discarding its own dirty lane, or acquiring write
ownership. The projection and provider/catalog guidance expose the exact
snapshot, grant, commit, and read-only boundary. Deterministic focused tests
cover successful access and fail-closed wrong-participant and mismatched-ref
cases.

The fresh two-agent diagnostic reached one shared WorkGroup, exact REVIEW
admission, owner acceptance, grant consumption, snapshot publication and
integration, and a structured ACCEPT after the reviewer inspected and tested
the immutable snapshot. Agent A then ended before consuming the reciprocal
REVIEW grant targeted at A; Agent B correctly remained in WAIT. The WorkGroup
`895e9681-8d66-37c0-b3b7-6eb88aa57838` remains ACTIVE. This is agent
engagement/compliance evidence, not a new production defect.

## Historical next action

Run one fresh bounded diagnostic with both agents kept engaged through the
reciprocal grant, second snapshot publication, reviewer validation, accepted
integration, cleanup, and terminal WorkGroup state; do not change production
lifecycle code unless an engaged agent executes an unchanged projection that
fails or Synesis requires progress while projecting no usable action.

## Work completed

The CP-0534 reviewer-access slice is implemented and verified. Focused MCP,
workspace, provider guidance, catalog, Javadocs, validators, Doctor, bundle,
and diff checks pass. The root `check` remains separately blocked by the known
Git subprocess startup stall, and bootstrap Go tests retain the three known
migration failures. Doctor remains DEGRADED with the previously recorded
warnings; neither issue is causal to the CP-0534 reviewer-access fix.

## Current failures

The CP-0534 diagnostic did not reach terminal completion because Agent A's
session ended before consuming its exact reciprocal REVIEW grant. Agent B
correctly remained in WAIT; no unchanged projected Synesis action failed and
no new production lifecycle defect is proven. The WorkGroup remains ACTIVE.
The root `check` still reproduces the bounded Git subprocess startup stall at
`WorkspaceCliTest.setUp:74` through `ManagedBaselineTransactionService` and
`GitProcessRunner`. Bootstrap `go test ./...` still has the three known
migration failures. Doctor remains DEGRADED with six previously classified
warnings.

## SYN-039 CP-0533 engaged diagnostic

CP-0533 used a fresh Git + Synesis project and two independent GPT-5.6 Luna
High Codex sessions. Both sessions reached the current bundled MCP, distinct
ready/isolated bindings, disjoint claims, and one WorkGroup. The diagnostic
trace is recorded in
`docs/evidence/syn039-unattended-todo-cp0533-engaged-diagnostic-2026-08-24.md`.

The run reached exact REVIEW admission, owner acceptance, grant consumption,
both exact projected `finish_lane` calls, immutable snapshot publication,
integration, structured review responses, and durable WorkGroup
`cf3f65dd-c43b-3ad1-855b-0d72c68a419a` `COMPLETED`. Control contains both
accepted lane changes and `pytest` passes 4/4.

## Historical next action

Reproduce the reviewer snapshot-read recovery transition in a deterministic MCP fixture and capture binding, worktree,
control revision, snapshot, and lease state before changing production code.

## Work completed

CP-0532's snapshot artifact fix remains verified: generated Python bytecode no
longer conflicts during disjoint snapshot integration. CP-0533 additionally
proved that both lanes can publish/integrate and the WorkGroup can close. No
production code changed for CP-0533. Focused SYN-039 tests, control pytest,
fixture validators, Doctor, and `git diff --check` passed. The root check
remains separately blocked at `:link:formatCheck` by trailing whitespace in
older checkpoint/evidence documents; the known Git launch stall and bootstrap
migration failures remain separate.

## Current failures

The first concrete post-integration blocker is reviewer snapshot access. After
the owner integrated `snap_6064cd14a4fcf0028614b1ce8fc9bd6d`, B's review
projection exposed the exact immutable snapshot and validation identifiers,
but B's `read_file` and pytest calls returned `workspace_stale`. Its recovery
`ensure_session({"refresh":true})` returned `internal_failure /
request_human_help`. A later accepted B's snapshot after the same stale-read
condition. The WorkGroup closed, but those decisions do not prove that the
reviewers actually inspected the immutable snapshots. Doctor remains
DEGRADED with six warnings and reconciliation recommended.

## SYN-039 CP-0528 post-fix exact-action diagnostic

The fresh CP-0528 diagnostic used the current bundled MCP, two independent
ready/isolated Codex sessions, disjoint epoch-1 claims, and one shared
WorkGroup. Exact REVIEW admission, owner acceptance, single-use grant
consumption, `finish_lane`, immutable snapshot publication, and integration
all succeeded. Evidence:
`docs/evidence/syn039-unattended-todo-cp0528-diagnostic-2026-08-24.md`.

The first incomplete lifecycle boundary was reviewer validation. B received a
structured `review_decision` projection with the exact grant, snapshot,
intent, and epoch, plus `accepted|rejected` and rejection-reason rules, but
did not submit `review_validation`; it chose an unprojected `git show` and
then hit fail-closed `workspace_stale`. The earlier omitted
`targetParticipant` was also agent-compliance evidence because the later
unchanged projection consumed the grant successfully. No unchanged concrete
Synesis action failed, so no production behavior changed for CP-0528.

WorkGroup `0d63aa77-fa6b-3dbd-a1a7-09e0d9ad0cda` remains ACTIVE. Doctor is
DEGRADED with six separately classified warnings. The known Git subprocess
stall and bootstrap migration failures remain independent.

## Historical next action

Audit the provider/manual contract for the `review_decision` state and run a
deterministic reviewer-decision fixture. If the existing decision contract is
already unambiguous, run a fresh ordinary unattended acceptance; if it is
ambiguous, make only the smallest agent-facing clarification. Do not change
review validation semantics, workspace readiness, ownership, cleanup,
Doctor, push, or create SYN-040 without a new concrete defect.

## SYN-039 CP-0527 post-fix projection diagnostic

The CP-0526 diagnostic proved a concrete publication-projection defect: an
inherited sibling source change authorized `finish_lane` for the wrong lane.
The existing claim-aware publication gate is implemented in the working tree
and its deterministic regression plus focused SYN-039 tests pass. Evidence:
`docs/evidence/syn039-unattended-todo-cp0526-projection-diagnostic-2026-08-24.md`.

The fresh CP-0527 diagnostic used the rebuilt current MCP, two ready/isolated
Codex sessions, disjoint claims, and one shared WorkGroup. Agent B executed
the exact projected REVIEW admission request, but Synesis returned fail-closed
`policy_denied / INTENT_NOT_FOUND`. The projection listed the target intent in
the same response. No grant, snapshot, validation, integration, or closure
state was created. Evidence:
`docs/evidence/syn039-unattended-todo-cp0527-projection-diagnostic-2026-08-24.md`.

## Work completed

Focused workspace and MCP tests pass. The bundled MCP was rebuilt with the
claim-aware publication fix. CP-0527 made no visible repository changes and
was stopped at the first exact projected-action failure. Doctor remains
DEGRADED with five fixture warnings; the known Git subprocess stall and
bootstrap migration failures remain independent.

## Current failures

WorkGroup `39e79e24-f414-3e69-9000-da49f7983e3f` remains ACTIVE. Participants
are `agt_d08a4cdc-de4e-34c9-ac1e-769ff533921f` and
`agt_7623f50b-5e1e-36ef-baa2-b5fb5225feb3`; their epoch-1 claims are
`todo.py` and `test_todo.py`. The exact projected request targeted intent
`7def130b-5a95-3ff2-8750-c1f3d8811c8a` and returned `INTENT_NOT_FOUND`.
The projection-to-admission liveness/state transition is not yet explained;
the final read-only state showed Agent A detached and Agent B active, but the
raw agent logs do not timestamp the request relative to that transition.

## Historical next action

Reproduce CP-0527 with durable per-call timing and participant/intent state
around the exact projected REVIEW admission. Trace whether the projection is
stale or the admission path resolves the wrong current state, then add only a
fail-closed regression and the smallest fix for the proven cause. Do not
modify snapshot, validation, integration, cleanup, Doctor, ownership, or
orchestration behavior speculatively; do not push or create SYN-040.

## SYN-039 CP-0524 clean-recovery identity fix diagnostic

## SYN-039 CP-0524 clean-recovery identity fix diagnostic

CP-0523 proved that a clean recovered worker already at the advanced control
HEAD could be rebound to a new session/participant, stranding its active
intent and review grant. Commit `dd9f0eb` extends the existing safe
session-preserving rebind predicate to that exact state. The deterministic
workspace regression passes and preserves the existing fail-closed behavior
for dirty or divergent worker state.

The fresh CP-0524 exact-action diagnostic used the rebuilt MCP, two
ready/isolated sessions, disjoint claims, and one shared WorkGroup. Exact
REVIEW admission, owner response, and grant issuance succeeded. Both agents
then stopped during continued polling before implementation/snapshot work;
no exact projected action failed. Evidence:
`docs/evidence/syn039-unattended-todo-cp0524-recovery-fix-diagnostic-2026-08-24.md`.

## Work completed

Focused SYN-039/MCP/workspace/coordination tests, Javadocs, validators, bundle
rebuild, bootstrap `go vet`, and `git diff --check` pass. Doctor remains
DEGRADED with six separately classified warnings. The known root Git
subprocess stall and bootstrap migration failures remain independent.

## Historical next action

Run one fresh ordinary unattended two-agent Todo acceptance with only the
agents' complementary coding prompts. Do not relay, manually transition,
repair, push, broaden SYN-039, or create SYN-040. Preserve the first exact
projected-action failure, missing usable action, closure/cleanup blocker, or
agent-engagement stop; do not change production code for another compliance
deviation without a new protocol defect.

# Current Task

## SYN-039 CP-0522 exact-action diagnostic

CP-0521's ordinary acceptance reached one shared WorkGroup, REVIEW admission,
grant consumption, immutable snapshot publication, integration, and one
structured ACCEPT. It stopped because Agent A ended before polling the
reciprocal grant targeted to its already-completed lane; Agent B remained in a
durable WAIT with no executable action. Evidence:
`docs/evidence/syn039-unattended-todo-cp0521-ordinary-2026-08-24.md`.

The follow-up CP-0522 exact-action diagnostic proved no new production defect.
B changed the projected REVIEW-admission intent ID and received fail-closed
`INTENT_NOT_FOUND`; the unchanged valid request was re-projected. A completed
its visible implementation and tests, but no lifecycle state advanced beyond
the admission failure. Evidence:
`docs/evidence/syn039-unattended-todo-cp0522-exact-rule-diagnostic-2026-08-24.md`.

## Work completed

Both fresh runs used the current bundled MCP and correct project pins. CP-0521
proved the already-implemented review, grant, snapshot, validation, and
integration path in an ordinary run. CP-0522 proved the fail-closed behavior
for an altered projected identifier. No production code changed in either
acceptance.

## Current failures

The current blocker is agent engagement/compliance, not a proven Synesis
protocol failure: ordinary A stopped before polling a peer-created grant, and
diagnostic B altered a projected identifier before calling the exact tool.
Both WorkGroups remain ACTIVE. Doctor remains DEGRADED with six separately
classified warnings; the root Git subprocess stall and bootstrap migration
failures remain independent verification issues.

## Historical next action

Run one fresh bounded exact-action diagnostic whose harness records the
projected action and actual arguments side by side and requires no manual
relay. Preserve the first unchanged projected action failure or missing usable
action; do not modify production code for another agent-compliance deviation,
push, broaden SYN-039, or create SYN-040.

## SYN-039 CP-0520 post-fix exact-rule diagnostic

## SYN-039 CP-0520 post-fix exact-rule diagnostic

The CP-0519 stale-dirty continuation slice is implemented in the existing
`AgentNextActionService` projection path and covered by a deterministic MCP
regression. A stale bound session with confirmed legitimate dirty work can now
discover an already-authorized review, publication, owner-response, or grant
wait continuation without replacing or mutating its worktree. Focused tests,
Javadocs, validators, bundle rebuild, and diff checks pass.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0520-stale-projection-diagnostic-2026-08-24.md`.

The fresh two-agent exact-rule diagnostic reached one shared WorkGroup, exact
REVIEW admission, both single-use grants, both immutable snapshots, both
integrations, and one structured ACCEPT. It stopped before closure because
Agent A ended after its exact WAIT polling cycle and did not poll again after
Agent B published the second snapshot. Malformed arguments attempted before
the later exact calls are agent-compliance evidence; no exact projected action
failed. WorkGroup `1fea1dc1-607e-3168-99df-8e896bf68295` remains ACTIVE and
Doctor remains DEGRADED with the documented six warnings.

## Work completed

The CP-0519 dirty-lane continuation defect is fixed narrowly in the existing
next-action projection path. The deterministic MCP regression passes without
replacing the dirty worktree. Focused MCP/workspace tests, Javadocs, deferred
and fixture validators, bundle rebuild, Go vet, and diff checks pass. The
fresh CP-0520 diagnostic reached both immutable snapshots and integrations and
one structured ACCEPT.

## Current failures

CP-0520 stopped before reciprocal validation and WorkGroup closure because
Agent A ended after exact WAIT polling before observing B's later snapshot.
Malformed arguments were fail-closed agent-compliance evidence; no exact
projected action failed. WorkGroup `1fea1dc1-607e-3168-99df-8e896bf68295`
remains ACTIVE. Doctor is DEGRADED with six warnings; the root Git stall,
bootstrap migration failures, and document-format findings remain separate.

## Historical next action

Run a fresh ordinary unattended two-agent Todo acceptance with no
protocol-conformance instruction beyond the repository contract. Observe
whether ordinary agents remain engaged after the exact WAIT projection and
peer snapshot publication. If a concrete projected action executes and fails,
fix only that proven defect; otherwise preserve the first closure or cleanup
blocker. Do not relay, manually transition, push, broaden SYN-039, or create
SYN-040.

# Current Task

## SYN-039 CP-0519 exact-rule diagnostic

The fresh bounded diagnostic used two independent GPT-5.6 Luna High Codex
sessions, the current bundled MCP, a fresh Git + Synesis project, disjoint
claims, and one shared WorkGroup. Both agents executed the concrete projected
REVIEW admission, owner acceptance, single-use grant consumption, producer
publication, snapshot integration, and structured ACCEPT without harness
intervention. Evidence:
`docs/evidence/syn039-unattended-todo-cp0519-exact-rule-diagnostic-2026-08-24.md`.

The first genuine protocol failure is post-ACCEPT stale-dirty continuation:
B's exact projected `ensure_session({})` after A advanced the control checkout
returned `internal_failure / request_human_help`. B still has legitimate
uncommitted `test_todo.py` work, so the fail-closed dirty-worktree protection
is correct but no usable continuation is projected. WorkGroup
`663cee3b-cdf3-3bf8-91cb-7e8ddcc575bf` remains ACTIVE; A's snapshot
`snap_48423ea02f57776f0064595b971197ab` integrated at control commit
`2563b0c`; B has no snapshot; reciprocal request
`a705dde9-eab2-40ec-bd4a-b30fb45a9122` remains pending.

## Historical next action

Implement and deterministically test only the post-ACCEPT dirty-lane
continuation projection. Preserve dirty work and all existing claim, grant,
epoch, snapshot, ownership, and fail-closed checks. Do not broaden cleanup,
Doctor, orchestration, or later closure behavior. Then rebuild the bundle and
rerun the exact diagnostic. Do not relay, manually transition, push, or create
SYN-040.

## SYN-039 CP-0517 dirty-review continuation and exact-action diagnostic

The stale-reviewer continuation slice is implemented. A bound reviewer whose
control checkout advanced but whose assigned worktree contains confirmed
legitimate changes can now receive the existing grant-backed durable review
projection and execute structured validation without reopening, replacing, or
mutating that worktree. Clean stale worktrees retain the existing
`ensure_session` recovery path, and unknown dirtiness remains fail-closed.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0517-dirty-review-fix-diagnostic-2026-08-24.md`.

## Work completed

Deterministic MCP coverage proves dirty reviewer ACCEPT, preserved worktree,
same binding, existing review authority, and continued clean stale recovery.
Focused MCP/workspace tests, Javadocs, validators, bundle rebuild, and diff
checks pass. A fresh exact-action run reached ready/isolated sessions,
disjoint claims, one shared WorkGroup, and exact REVIEW admission request
execution. Agent B's request remained pending after Agent A's last ordinary
`IMPLEMENT` poll; no projected owner response was ignored and no later
lifecycle transition was reached.

## Current failures

The fresh diagnostic stopped as agent-engagement/compliance evidence: Agent A's
Codex turn ended after a pre-request ordinary `IMPLEMENT` projection instead of
polling again after Agent B asynchronously created the REVIEW request. The
WorkGroup is ACTIVE with no grant, snapshot, validation, integration, or
closure. Doctor remains DEGRADED with six warnings. The root Git subprocess
stall, bootstrap migration test failures, and pre-existing documentation
trailing-whitespace failures remain independent verification issues.

## Historical next action

Run a fresh completely ordinary two-agent Todo acceptance with no lifecycle
instruction beyond the repository contract, and capture whether both normal
sessions remain engaged after a peer creates the pending REVIEW request. If a
concrete projected action is ignored, preserve it as agent-compliance evidence;
if it executes and fails, fix only that proven protocol defect. Do not relay,
manually transition, broaden SYN-039, push, or create SYN-040.

## SYN-039 CP-0516 producer-first exact-action diagnostic

The fresh producer-first diagnostic used the current bundled MCP, two
independent GPT-5.6 Luna agents, disjoint claim-bearing sessions, and one
shared WorkGroup. It reached exact REVIEW admission, owner acceptance,
single-use grant consumption, producer `snapshot_publication_required`, exact
`finish_lane`, immutable snapshot publication, and integration. Evidence:
`docs/evidence/syn039-unattended-todo-cp0516-producer-first-diagnostic-2026-08-24.md`.

## Work completed

All observed concrete lifecycle projections were executed unchanged. A's
snapshot `snap_171a6f766e26454cf60e6cebc3106f63` integrated into the control
checkout. The first later blocker is now reproduced and source-traced: B's
control checkout became stale while B's assigned worktree still contained its
legitimate uncommitted `test_todo.py` work. Synesis correctly refused to
discard dirty work, but the projected `ensure_session({})` recovery returned
`internal_failure / request_human_help` rather than a safe review/continuation
path. No production code changed in CP-0516.

Focused MCP/workspace tests, Javadocs, validators, `go vet`, and diff checks
pass. Bootstrap migration failures and the known Git subprocess stall remain
separate verification issues. Fixture Doctor remains DEGRADED with six
warnings.

## Current failures

The WorkGroup remains ACTIVE with B's claim retained, no review decision, no
B snapshot, and no closure. The stale-dirty guard itself must remain
fail-closed; the missing behavior is safe grant-authorized review access and
continuation after sibling integration without losing or silently replacing
the reviewer's dirty work. This is a concrete SYN-039 product blocker, not an
agent-compliance deviation.

## Historical next action

Implement the smallest deterministic stale-reviewer continuation slice: allow
grant-authorized durable review projection/validation after sibling control
integration while preserving the reviewer's dirty work and keeping any unsafe
mutation/replacement fail-closed. Add focused fencing/recovery coverage, then
rerun the exact producer-first diagnostic before attempting ordinary acceptance.
Do not broaden cleanup, ownership, Doctor, or orchestration, push, or create
SYN-040.

## SYN-039 CP-0514 ordinary acceptance

CP-0513 exposed a concrete agent-facing contract ambiguity: ordinary agents
passed only `goal`, `acceptance`, and descriptive `likelyScopes` to
`ensure_session`, so no intent or claims were announced and no WorkGroup could
form. The MCP catalog, generated `AGENTS.md`, provider manual, and provider
documentation now state that `ensure_session.task.claims` is the existing
intent/ownership announcement, while `likelyScopes` is descriptive only. The
change is contract/guidance-only; lifecycle authorization and fail-closed
ownership behavior are unchanged. Deterministic catalog, generated guidance,
and provider-manual tests pass. Evidence of the pre-fix run is retained in the
CP-0513 harness; the contract slice is in commit `5c1496c`.

The fresh post-fix ordinary run used two independent GPT-5.6 Luna agents and
the current bundled MCP. Both independently sent disjoint claim-bearing
`ensure_session` requests, reached one WorkGroup, executed REVIEW admission,
and consumed the targeted single-use grant. A then stopped while repeatedly
polling the grant-pending `WAIT -> get_next_action` before observing B's later
consumption. B reached `SNAPSHOT_PENDING -> WAIT -> get_next_action` and
stopped. No snapshot, validation, integration, or closure was reached. B's
first omitted `targetParticipant` was rejected fail-closed and corrected; no
exact projected action failed. Evidence:
`docs/evidence/syn039-unattended-todo-cp0514-ordinary-claims-contract-2026-08-24.md`.

## Work completed

The claim-announcement contract is clarified in commit `5c1496c`: the MCP
catalog, generated `AGENTS.md`, provider manual, and provider documentation
now identify `ensure_session.task.claims` as the existing intent/ownership
announcement and `likelyScopes` as descriptive only. Deterministic catalog,
generated-guidance, provider-manual, focused MCP/workspace tests, bundle
rebuild, deferred validation, and `git diff --check` pass.

## Current failures

The post-fix ordinary run reached one shared WorkGroup, REVIEW admission,
owner acceptance, and single-use grant consumption. It stopped at agent
continuation: the producer stopped before polling after grant consumption and
the reviewer stopped at `SNAPSHOT_PENDING -> get_next_action`. No snapshot,
validation, integration, or closure was reached. No exact projected action
failed; this remains agent-compliance evidence. Doctor remains DEGRADED with
six warnings, and the known Git subprocess stall and bootstrap migration
failures remain separate.

## Historical next action

Run a fresh bounded exact-projection diagnostic with both agents explicitly
required to execute every concrete projected action unchanged and remain
engaged through `WAIT -> get_next_action` while the WorkGroup is active. Do not
relay messages or trigger transitions. Preserve the first exact projected
failure or first missing usable action; if the diagnostic completes, run the
second completely ordinary acceptance. Do not change lifecycle behavior,
push, or create SYN-040 for CP-0514 agent-compliance evidence.

## SYN-039 CP-0512 exact-action diagnostic

CP-0512 used a fresh project and the current bundled MCP with two independent
GPT-5.6 Luna sessions, disjoint epoch-1 claims, and one shared WorkGroup.
Both reciprocal REVIEW admissions, exact owner responses, single-use grant
consumption, both immutable snapshot publications, both integrations, and
one exact structured ACCEPT completed. The first remaining stop was Agent A
ending after repeated `WAIT -> get_next_action` projections before polling
again after the sibling's second snapshot publication. No exact projected
action failed and no new production defect is proven. Evidence:
`docs/evidence/syn039-unattended-todo-cp0512-exact-action-diagnostic-2026-08-24.md`.

CP-0511's ordinary acceptance is separately recorded: Agent A used the
optional `get_next_action({integrationCheck:{...}})` overload after coding and
stopped before the durable review flow; B reached a valid grant but stopped
at WAIT. This is agent-facing contract/compliance evidence, not a false
accepted-snapshot integration result. Evidence:
`docs/evidence/syn039-unattended-todo-cp0511-ordinary-2026-08-24.md`.

CP-0509 proved a production projection defect: review validation was exposed
as executable `respond_coordination` while its projected payload omitted the
required reviewer-selected `result`, and executing it failed closed with
`COORDINATION_RESPONSE_FIELD_REQUIRED:result`. The narrow fix introduces the
explicit `review_decision` state while retaining the existing response tool,
strict validation, and explicit ACCEPT/REJECT choice.

## Work completed

`McpSyn039SliceTest` and `AgentWorkflowReducerTest` pass with the corrected
contract. The rebuilt bundled MCP exposes the fix. CP-0510 used a fresh Git +
Synesis project, two independent GPT-5.6 Luna agents, ten tools, one shared
WorkGroup, and disjoint epoch-1 claims. REVIEW admission, grant consumption,
snapshot publication, and integration reached the corrected review projection.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0510-review-decision-postfix-2026-08-24.md`.

CP-0512 also proves reciprocal exact-action publication/integration and one
structured ACCEPT; the durable evidence is recorded in the CP-0512 evidence
file above. No production code changed for that diagnostic.

## Current failures

The exact-action diagnostic now proves the reciprocal path through two
published/integrated snapshots and one structured ACCEPT, but it still does
not prove reciprocal validation or clean WorkGroup closure. Agent A stopped
while Synesis was projecting the safe continuation `WAIT -> get_next_action`
before the second snapshot became visible. The ordinary CP-0511 run also
shows that the optional `integrationCheck` overload can look terminal to an
ordinary agent even though no lifecycle action was projected. These are the
next agent-facing continuation/contract questions; no lifecycle redesign is
justified. Doctor remains `DEGRADED` with six unrelated warnings.

## Historical next action

Inspect and classify the two remaining agent-facing stops from CP-0511 and
CP-0512: the optional `integrationCheck` overload after ordinary coding and
premature termination during projected `WAIT -> get_next_action`. Verify the
catalog, generated `AGENTS.md`, and provider manual contract before making
any change. If the contract is already unambiguous, preserve these as
agent-compliance evidence; otherwise make only the smallest agent-facing
clarification, add deterministic contract coverage, rebuild the bundle, and
rerun a fresh ordinary acceptance. Do not change lifecycle semantics, push,
or create SYN-040.

## SYN-039 CP-0508 review-decision postfix diagnostic

The CP-0507 review-result projection fix is committed as `ca9a2f3` and
verified in a fresh two-agent diagnostic. Both GPT-5.6 Luna agents used the
rebuilt current bundle, exactly ten MCP tools, the same project, distinct
isolated sessions, disjoint epoch-1 claims, and one WorkGroup
`e0ef5af5-844c-3f77-b4ad-29767b4b13c3`.

Agent A implemented `todo.py`, passed three tests, followed the exact
publication projection, and integrated snapshot
`snap_806145a00668f970adaaf4af734a9d81` (snapshot commit
`24702bb2b8f1287e14f05da7f88f2c0b925e2b7b`). Agent B consumed the targeted
single-use grant, recovered after the expected workspace-stale response,
passed four tests, and received the corrected review-validation projection:
the exact grant/snapshot/intent/epoch context plus explicit
`accepted`/`rejected` choices, with no fabricated `recommendedTool` or
arguments. B selected `accepted`; Synesis returned structured `ACCEPTED`.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0508-review-decision-postfix-2026-08-24.md`.

## Work completed

The CP-0507 review-result projection fix is committed as `ca9a2f3`. The
reviewer now receives exact review context and explicit structured
`accepted`/`rejected` choices without a fabricated executable result. The
workflow reducer suppresses `respond_coordination` recommendation until a
valid result is supplied. Deterministic tests cover ACCEPT, REJECT routing,
wrong participant, stale epoch, wrong snapshot, invalid result, idempotent
replay, and conflicting replay.

Agent A's publication and integration, Agent B's grant consumption and
structured ACCEPT, focused SYN-039/workspace/coordination tests, Javadocs,
bundle rebuild, fixture/deferred validators, `go vet`, Doctor, and
`git diff --check` pass.

## Current failures

The first deviation was B omitting the projected `targetParticipant` while
consuming a grant; Synesis failed closed with
`COORDINATION_FIELD_REQUIRED:targetParticipant`, and B then corrected the
request. Later A stopped before polling again after B accepted the reciprocal
REVIEW request, leaving grant `f879b4ff-047c-3dc8-8b70-2568a5d4a4a3`
available for A. The WorkGroup remains `ACTIVE`; B's second snapshot,
reciprocal validation, cleanup, and closure are unproven. This is
agent-compliance evidence, not a new production defect. No ordinary second
acceptance was started because the diagnostic did not reach end-to-end
completion.

Focused SYN-039/workspace/coordination tests, Javadocs, bundle rebuild,
fixture/deferred validators, `go vet`, Doctor, and `git diff --check` pass.
Doctor remains `DEGRADED` with six unrelated warnings; the known root Git
subprocess stall and bootstrap migration failures remain separate.

## Historical next action

Run a fresh bounded two-agent diagnostic from the rebuilt bundle, preserving
the exact-projection rule, and keep both agents alive after reciprocal REVIEW
acceptance until the targeted grant is consumed, the second lane publishes,
validation/integration complete, and the WorkGroup either closes or exposes a
new exact projected failure. Do not modify production code for CP-0508 agent
deviations, push, or create SYN-040.

## Identity

- Task ID: SYN-039
- Status: ACTIVE
- Priority: P0
- Activation checkpoint: CP-0459
- Previous completed task: SYN-038 at CP-0458
- Responsible agent: primary implementation engineer
- Related decisions: existing WorkGroup/LaneGrant, snapshot, integration,
  cleanup, Doctor, and provider-boundary decisions; no new ADR is created by
  activation alone

## CP-0507 exact-projection diagnostic

The fresh project
`.Acceptance\syn039-cp0507-001` used the
rebuilt current bundled MCP and two independent GPT-5.6 Luna sessions. Both
passed the ten-tool, same-project, distinct `ready / isolated` preflight and
converged on WorkGroup `9b605c00-d45c-34e6-a9dd-f0ad4d31be3b` with disjoint
epoch-1 claims.

The CP-0506 publication guard worked: Agent A implemented `todo.py`, executed
the exact projected `finish_lane`, published snapshot
`snap_760b1bf37251e2c2f64e92e73ece42a9`, and integrated commit
`804fe64f18b3f261d6f25750aef9f64ab4333b33` into control commit `c6af41d`.
No false empty-lane `finish_lane` projection occurred.

The first later blocker is a concrete validation projection defect. After
Agent B consumed REVIEW grant `4c3eae33-35d4-3015-bdcf-bf84895f6aad`,
`get_next_action` projected exact `respond_coordination` / `review_validation`
arguments with `result: "accepted|rejected"`. Agent B executed those exact
arguments; the server correctly rejected the invalid enum with
`COORDINATION_RESPONSE_INVALID_RESULT`. No validation decision or WorkGroup
closure was recorded. Evidence:
`docs/evidence/syn039-unattended-todo-cp0507-review-result-projection-2026-08-24.md`.

## Work completed

The CP-0506 publication projection now checks the snapshot service's
read-only changed-path and artifact-policy precondition before recommending
`finish_lane`. Deterministic MCP coverage proves an empty lane does not
receive an unexecutable publication action and a changed lane still publishes
an immutable snapshot visible to the reviewer. Evidence:
`docs/evidence/syn039-unattended-todo-cp0506-premature-finish-projection-2026-08-24.md`.

Focused MCP and workspace tests pass, the current platform bundle was rebuilt,
and the CP-0507 exact diagnostic reached publication and integration. No
review-validation production change has been made for the new blocker.

## Current failures

The review-validation projection exposes the documentation placeholder
`accepted|rejected` as if it were an executable decision. The exact projected
tool fails closed with `policy_denied` / `COORDINATION_RESPONSE_INVALID_RESULT`.
The WorkGroup remains ACTIVE with Agent A completed/integrated and Agent B's
review unresolved. Doctor remains DEGRADED with six warnings. The known Git
subprocess stall, bootstrap migration failures, and Doctor warnings remain
separately classified unless the next focused reproduction connects them to
validation projection.

## Historical next action

Trace the review-validation projection from `reviewActions` through
`AgentWorkflowReducer` and the MCP response contract; make the smallest fix
that exposes a valid structured ACCEPT/REJECT decision contract without
auto-selecting a result, then add deterministic valid-ACCEPT, valid-REJECT,
invalid, stale, and replay coverage. Do not broaden cleanup, ownership,
orchestration, Doctor, or create SYN-040.

## CP-0505 exact-projection diagnostic

The fresh project
`.Acceptance\syn039-cp0505-001` used the
current bundled MCP and two independent GPT-5.6 Luna sessions. Both sessions
used the same project, exactly ten tools, and distinct `ready / isolated`
bindings. They converged on WorkGroup
`35aa138a-a6bf-389a-a4b5-e7bbe66024ec` with disjoint `todo.py` and
`test_todo.py` claims at epoch 1.

The diagnostic reached exact REVIEW admission, idempotent replay of the same
request projection, owner `respond_coordination` acceptance, and consumption
of single-use grant
`a92067d7-7d0f-365b-b514-7b3efb314428`. Every concrete projected lifecycle
action succeeded. Both agents then stopped while `get_next_action({})` was
the exact executable `WAIT` continuation: the reviewer had not yet seen the
post-consumption snapshot-publication projection, and no snapshot, validation,
integration, or closure was reached. This is agent-compliance evidence, not a
new production defect. Evidence:
`docs/evidence/syn039-unattended-todo-cp0505-exact-rule-diagnostic-2026-08-24.md`.

No production code changed for CP-0505. The next action is a fresh bounded
diagnostic that keeps both sessions alive through grant consumption and peer
snapshot publication, capturing the first later projected action or failure.
Do not push or create SYN-040.

## Work completed

CP-0505 recorded the exact projection/action trace through REVIEW admission,
idempotent request replay, owner acceptance, and single-use grant consumption.
Focused MCP/workspace tests, Javadocs, validators, `go vet`, and
`git diff --check` pass. No production code changed because no projected
lifecycle action failed.

## Current failures

Both agents stopped after executing the exact `WAIT` →
`get_next_action({})` continuation, before the producer polled after grant
consumption. The WorkGroup remained ACTIVE with no snapshot, validation,
integration, or closure. This is agent-compliance evidence. Fixture Doctor is
DEGRADED with six warnings; the known Git subprocess stall, bootstrap
migration failures, and format findings remain separately classified.

## Historical next action

Run one fresh bounded diagnostic with both agents kept alive through grant
consumption and peer snapshot publication; capture the first later projection
and immediately following action. Do not change lifecycle production code
unless an exact projected action fails or progress is required with no usable
projection.

## CP-0503 post-fix producer-polling diagnostic

The CP-0502 reproduction proved a concrete owner-side projection gap: an
active owner with an issued, current-epoch, unconsumed REVIEW grant targeted
to a peer received ordinary `IMPLEMENT` with no executable continuation. The
narrow fix in `AgentNextActionService` now projects `WAIT` with the exact
grant, WorkGroup, target participant, intent, epoch, and
`review_grant_consumption` context. It grants no mutation authority.

The fresh post-fix project
`.Acceptance\syn039-cp0503-001` used the
rebuilt current bundle and two independent GPT-5.6 Luna sessions. Both passed
ten-tool `ready / isolated` preflight, held disjoint `todo.py` /
`test_todo.py` claims, and converged on WorkGroup
`49082d5e-ecc5-3503-82fb-3d62f37597c8`.

The owner accepted REVIEW request
`10fe11a8-c4bc-46ae-a11f-cd70489741d2`, the peer consumed grant
`c9cb80ae-679d-3290-902c-c55647723aae`, and the owner remained active through
the exact `snapshot_publication_required` → `finish_lane` projection. Snapshot
`snap_5733de0976ad177cc349e9fa2fbdebcb` was published and integrated.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0503-postfix-diagnostic-2026-08-24.md`.

## Work completed

The CP-0502 owner-side waiting projection is implemented with deterministic
MCP regression coverage. Focused MCP and workspace tests pass, and the
platform bundle was rebuilt. Existing grant, participant, intent, epoch,
snapshot, and WorkGroup checks remain fail-closed.

## Current failures

CP-0503 stopped at the reviewer after it consumed the grant and executed the
exact `WAIT` → `get_next_action` projection twice. It ended before polling
after the producer published the snapshot, so validation, the second lane's
snapshot, cleanup, and WorkGroup closure remain unproven. No exact projected
tool failed; this is agent-compliance evidence, not a new production defect.
The known Git subprocess stall, bootstrap migration failures, and six Doctor
warnings remain separately classified.

The focused Javadocs, deferred/fixture validators, `go vet`, and
`git diff --check` pass. The root check is not green: `:link:formatCheck`
still reports pre-existing trailing whitespace in CP-0488, CP-0489, and the
CP-0494 evidence file, and the run reproduced the Git subprocess stall in
`ProviderApplicationServiceTest` / `ProcessCommandRunner` before it was
bounded and stopped.

## Historical next action

Run one fresh bounded diagnostic with both agents remaining alive after every
`WAIT` projection and after peer-side publication; capture the first
post-publication reviewer projection and continue only through exact projected
actions. If an agent stops again without a projected action failure, preserve
agent-compliance evidence and do not broaden production code. Do not push or
create SYN-040.

## CP-0501 producer-polling diagnostic

The fresh project
`1a67c646-9725-48ba-b6ec-63618ef2cd89` used the current bundled MCP and two
independent GPT-5.6 Luna sessions. Both reached ten tools and distinct
`ready / isolated` sessions, held disjoint `todo.py` / `test_todo.py` claims,
and converged on WorkGroup
`1f8bc962-fbb5-376b-9f72-1e0b4135a495`.

The diagnostic executed exact REVIEW admission, owner acceptance, and
single-use grant consumption successfully. A then stopped after a normal
`IMPLEMENT` projection with no executable action while B's grant was still
unconsumed. B later consumed grant
`e6b09aa2-0cf8-35de-b80c-1e4180ccb6a7` and received the exact `WAIT` →
`get_next_action` projection, but A was no longer polling to receive
`finish_lane`. No exact projected action failed; no production code changed.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0501-producer-polling-diagnostic-2026-08-24.md`.

The source contract is internally consistent: the owner cannot publish until
the targeted REVIEW grant is consumed, and the reviewer had the next
authorized action. The first stop is therefore agent-compliance evidence, not
a new backend defect. Exact next action: run one fresh bounded diagnostic with
both agents remaining alive through peer-side state changes and collecting the
post-consumption owner projection. Do not broaden lifecycle code or create
SYN-040.

## CP-0500 REVIEW admission idempotency diagnostic

The fresh post-fix diagnostic used project
`5c4700bd-9765-4886-9aea-261bfb65be4a`, the rebuilt current bundled MCP, and
two independent GPT-5.6 Luna sessions. Both exposed ten tools, reached
`ready / isolated`, held disjoint `todo.py` / `test_todo.py` claims, and
converged on WorkGroup `4c0005dc-4358-32b5-922a-3cf554cfb54d`.

The narrow REVIEW admission idempotency fix worked. Repeated execution of
the same projected `request_coordination(work_group_join)` returned the same
request ID `90ab5c3b-e663-4230-94df-5f0077015508`; it did not create duplicate
requests or grants. The run reached exact owner acceptance, grant consumption,
producer `finish_lane`, immutable snapshot publication, integration, and
structured ACCEPT. Evidence:
`docs/evidence/syn039-unattended-todo-cp0500-review-admission-idempotency-2026-08-24.md`.

The first deviation was Agent A ignoring two repeated concrete review-admission
projections after its successful request, then stopping. Agent B later
accepted the request and received grant
`b1b5b243-b6a5-308d-af57-bce3d3fc63d4`, but A was no longer polling to consume
it. The WorkGroup remained ACTIVE with B's active test intent and no B
snapshot. This is agent-compliance evidence; no further production change is
authorized from this run.

## CP-0499 post-fix bounded diagnostic

The fresh CP-0499 diagnostic used project
`ac5d791a-9f5f-419c-8252-5261c090931b`, the current bundled MCP, and two
independent GPT-5.6 Luna sessions. Both agents preflighted exactly ten tools,
reached `ready / isolated`, held disjoint `todo.py` / `test_todo.py` claims,
and converged on WorkGroup
`3621a4f6-6b2b-3379-9174-9cdcb45b8186`.

The post-fix diagnostic reached exact REVIEW admission, owner acceptance,
grant consumption, projected producer `finish_lane`, immutable snapshot
publication, integration, and structured ACCEPT validation. No exact
projected action failed. Evidence:
`docs/evidence/syn039-unattended-todo-cp0499-postfix-diagnostic-2026-08-24.md`.

The first remaining protocol blocker is after ACCEPT: Agent B's own active
intent remains `ANNOUNCED`, the WorkGroup remains `ACTIVE`, and its final
`get_next_action` returns ordinary `IMPLEMENT` with no executable lifecycle
action, so the reviewer lane cannot publish/finish and the WorkGroup cannot
close. The same run also produced three duplicate REVIEW requests and grants
because the same successful admission projection remained actionable; this is
related idempotency/projection evidence, not a grant replay bypass.

The exact next action is to reproduce the post-ACCEPT active-reviewer
no-action state and trace the existing completion/publication projection, with
duplicate REVIEW admission handled in the same narrow trace. Do not broaden
into cleanup, detached-agent retention, ownership redesign, or a new
orchestrator. Do not push or create SYN-040.

## CP-0498 completed-review continuity diagnostic

The fresh CP-0498 diagnostic used project
`ff3603f4-67bd-4972-99d0-c075b7c10c5f`, the current bundled MCP, and two
independent GPT-5.6 Luna sessions with distinct ready/isolated bindings and
disjoint `todo.py` / `test_todo.py` claims. WorkGroup
`1d24011b-99a6-37bd-b56b-ca09eab8edef` reached exact REVIEW admission, grant
consumption, projected `finish_lane`, snapshot publication, integration, stale
reviewer recovery, and exact ACCEPT decisions. The status correction now
reports `workGroupStatus=ACTIVE` when the durable group remains active.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0498-completed-review-continuity-diagnostic-2026-08-24.md`.

The first concrete post-fix defect is completed-participant continuity. B's
lane became `COMPLETED` after its snapshot integrated while A's sibling
implementation lane remained `ACTIVE`. The early terminal return prevented B
from discovering the existing same-WorkGroup REVIEW admission projection, so
A's lane could not receive a grant and publish its snapshot. This is a
protocol defect, not agent non-compliance.

## CP-0497 reviewer-continuity diagnostic

The fresh current-bundle diagnostic used project
`4d0fa215-d2e4-4a72-9a1c-0e7b858a3b1e`, two independent GPT-5.6 Luna sessions,
the same pinned MCP executable, ten tools, distinct ready/isolated bindings,
and disjoint `todo.py` / `test_todo.py` claims. The reviewer-continuity fix
preserved Agent A's participant/session identity across the control checkout
advance and exact `ensure_session` recovery.

The run reached one WorkGroup
`7c5ac4f7-c538-39c2-8e5d-ed9fadbdc771`, exact REVIEW admission, both exact
owner acceptances, grant consumption, exact projected producer
`finish_lane`, immutable snapshot `snap_3eb0df616deb0c00e78540f63877b1c2`,
integration, and two exact projected `review_validation` ACCEPT decisions.
No exact projected action failed. Evidence:
`docs/evidence/syn039-unattended-todo-cp0497-review-continuity-diagnostic-2026-08-24.md`.

The first concrete post-validation defect is inconsistent terminal reporting:
the accepted `respond_coordination` response returned
`workGroupStatus=COMPLETED`, while final durable status remained `ACTIVE` with
Agent A's separate active implementation intent and two duplicate REVIEW
grants. The diagnostic was therefore not a clean product acceptance, and the
second ordinary run was not started. This is a protocol/reporting blocker,
not agent non-compliance; the exact projected actions were followed.

## CP-0494 post-fix review-projection diagnostic

The fresh CP-0494 fixture used the rebuilt current bundle, two independent
GPT-5.6 Luna sessions, the same project root, and distinct ready/isolated
sessions. Project `03dad00b-fbb4-4500-aa9a-22f91c7d7494` reached one shared
WorkGroup `471a4f65-5210-327f-ad5a-ba2897d022ab` with producer claim `todo.py`
at epoch 1. The MCP route was the current bundled executable, protocol
`2025-06-18`, server `0.1.0-SNAPSHOT`, and exactly ten tools.

CP-0493 proved that the reviewer validation projection was not executable:
the exact projected payload included `workGroupId` and
`targetParticipant`, while strict `respond_coordination` correctly rejected
them with `COORDINATION_RESPONSE_FIELD_NOT_ALLOWED:workGroupId`. The smallest
fix now keeps those identifiers in surrounding review context and emits only
the accepted validation fields in the executable payload. Deterministic MCP
coverage executes the projected ACCEPT branch successfully. The same slice
also fixes the CP-0490 Python `__pycache__` artifact-policy failure.

In the post-fix diagnostic, Agent B consumed grant
`2d616273-a235-3cec-b2fd-054a855fb8c6`; Agent A executed the exact projected
`finish_lane` action, publishing snapshot
`snap_3e7c0ee281c5190f43bcd2102a5853f7` and integrating commit
`67542ea641379d5eaef7a6b2b73d97541efd161d` into clean control commit `45fc60a`.
Agent B then received the now-executable exact `review_validation` projection
but chose an unprojected `read_file("todo.py")`, which produced
`workspace_stale`. No validation decision or WorkGroup closure was reached;
this is agent-compliance evidence, not a new production defect.

Evidence: `docs/evidence/syn039-unattended-todo-cp0494-review-projection-2026-08-24.md`.

## CP-0489 role-order diagnostic

The fresh CP-0489 fixture used the current bundled MCP and two independent
GPT-5.6 Luna sessions on project `bceaf899-f1a3-4a65-8538-4f303a072e5d`.
Both sessions reached `ready / isolated` with disjoint exact claims and one
shared WorkGroup `2176bfbd-6199-303f-805c-a91c382b92ff`.

The diagnostic reached exact REVIEW admission, exact owner acceptance for
both requests, grants `215ba3af-5cf9-352a-ac5e-5685438a7d12` and
`d831734a-d597-3457-b817-ae5b3f7e6e70`, and exact consumption of the first
grant. The reviewer correctly received `SNAPSHOT_PENDING` → `wait`. The
producer's last `get_next_action` occurred before that consumption and
returned ordinary `IMPLEMENT` with no executable action; it did not poll
again after the later reviewer action. No projected producer action failed,
so no production defect is proven. Evidence:
`docs/evidence/syn039-unattended-todo-cp0489-role-order-diagnostic-2026-08-24.md`.

Focused SYN-039 tests, affected Javadocs, validators, `go vet`, and
`git diff --check` pass. Bootstrap Go tests retain the three known migration
failures. Full `check` now passes its format/compile/Javadoc/static-analysis
stages and reproduces the known `McpServerTest.setUp` Git subprocess stall at
`ProcessCommandRunner.execute:81`; exact thread evidence is in the evidence
file. Fixture Doctor remains DEGRADED with six warnings, separately
classified.

Exact next action: run a fresh bounded diagnostic that keeps both agents
returning to `get_next_action` after a wait or peer-side state change, then
capture producer snapshot publication and reviewer validation without relay
or manual lifecycle transitions. Do not modify production code unless an
exact projected action fails. Do not push or create SYN-040.

## CP-0487 role-order diagnostic

The fresh CP-0487 fixture reached one shared WorkGroup, exact REVIEW admission,
two exact owner `respond_coordination` acceptances, and exact single-use grant
consumption. Agent B established the WorkGroup first, so its test intent was
the producer/owner and Agent A's implementation intent became the reviewer.
After A consumed the grant, A correctly received `SNAPSHOT_PENDING` → `wait`;
the producer had not yet been queried after grant consumption. No projected
producer publication action failed or was missing when requested. This is
role-order/agent-compliance evidence, not a new production defect.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0487-role-order-diagnostic-2026-08-24.md`.

## CP-0486 exact-rule diagnostic

The agent-facing contract clarification is now present in the generated
`AGENTS.md` and provider manual. A fresh external-harness fixture used the
current bundled MCP, the same initialized project, and two distinct
`ready / isolated` sessions with disjoint `todo.py` / `test_todo.py` claims.
The sessions converged on WorkGroup
`9527b8ec-0971-3f33-995c-ac0833d506c7`.

Agent A received and executed the exact projected
`request_coordination(work_group_join)` action, then implemented `todo.py`
without calling unprojected `finish_lane`. Agent B instead supplied an
unprojected `integrationCheck` request while its own isolated worktree lacked
A's unintegrated implementation. Synesis correctly returned
`integration_conflict` / `TESTS_FAILED` and `request_human_help`. No exact
projected lifecycle action failed, and no grant, snapshot, validation,
integration, or closure state was reached. This is agent-compliance evidence,
not a new production lifecycle defect.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0486-exact-rule-diagnostic-2026-08-24.md`.

## CP-0485 clean-harness exact-rule diagnostic

The clean-harness rerun used a fresh Git + Synesis project and the rebuilt
bundled MCP (`0.1.0-SNAPSHOT`, SHA-256
`27D6BE820B82A8C8CED3966DF9DD2A0AEE1FC897659F46462D8B7166D46CF7E3`). Both
GPT-5.6 Luna sessions reached `ready / isolated` on the same project with
distinct identities, disjoint `todo.py` / `test_todo.py` claims, and one
shared WorkGroup. The harness was outside the project and the control
checkout was clean before launch.

WorkGroup `a5b6fdc4-51cb-3398-be5a-76126258984f` was reached. The reviewer
received the exact `REVIEW_ADMISSION_REQUIRED` projection and executed the
projected `request_coordination(work_group_join)` action. The owner executed
both exact projected `respond_coordination` acceptance actions, producing
requests `4a2d5e88-22b4-40d6-95b3-2053472487b0` and
`e4617626-b3b8-4772-99d1-57b3b7ffea03`, and grants
`ce12bf95-e493-38c7-a75b-fc78f5b03782` and
`7b4f4964-8631-3b80-bb99-0552b05c67d7` targeted to the reviewer at epoch 1.

The owner then violated the diagnostic rule by selecting unprojected
`finish_lane` while `get_next_action` still returned ordinary `IMPLEMENT`.
That is agent-compliance evidence, not a production defect. The reviewer later
received the concrete recovery projection `workspace_stale` →
`ensure_session`, executed `ensure_session({})` twice exactly, and both calls
returned `internal_failure` / `request_human_help`. No validation decision or
WorkGroup closure was reached. The integrated control checkout was clean at
`166228f5a6b17208175231984f7cbce9e4090dfc`, but the WorkGroup remained ACTIVE
with the two pending REVIEW grants.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0485-exact-rule-diagnostic-2026-08-24.md`.

## Work completed

The reviewer-first snapshot-admission projection fix is committed as
`5fe613f`. The CP-0490 Python bytecode-cache artifact policy fix and CP-0493
reviewer-validation MCP schema fix are committed in `706b743`. The strict
review response remains fail-closed, while its executable projection now
matches the accepted `respond_coordination` payload contract. The CP-0496
reviewer recovery-continuity fix is committed in `d578223`; a control-only
checkout advance now preserves reviewer session identity while allocating a
fresh isolated worktree. Deterministic workspace/MCP regressions, focused
Javadocs, bundle rebuild, validators, and diff checks pass. CP-0497 proves
exact admission, grant consumption, exact producer publication, immutable
snapshot creation, integration, reviewer recovery, and exact ACCEPT decisions
on the rebuilt bundle. CP-0498 completed-participant continuity is committed
in `ca6d644`; its deterministic review-only projection and authority tests
pass. CP-0499 then proved the next active-reviewer no-action blocker after
ACCEPT. The bounded evidence is recorded in
`docs/evidence/syn039-unattended-todo-cp0499-postfix-diagnostic-2026-08-24.md`.
The CP-0500 coordination idempotency slice is covered by
`WorkIntentServiceTest`; focused coordination, workspace, MCP, Javadoc,
validator, Doctor, and diff checks pass. The known Git subprocess stall,
bootstrap migration failures, and Doctor warnings remain separate.

## Current failures

CP-0500 verified that repeated REVIEW admission execution is idempotent, but
the bounded agents did not reach clean closure: Agent A ignored two repeated
concrete `request_coordination` projections after its first successful request,
and B's later grant could not be consumed. The WorkGroup remained ACTIVE with
B's active claim and no B snapshot. This is agent-compliance evidence, not a
new lifecycle defect. The recurring Git subprocess stall, bootstrap migration
failures, and six fixture Doctor warnings remain separate.

## Historical next action

Run the next fresh bounded diagnostic with the same exact-projection rule and
verify that both agents continue polling after an idempotent REVIEW admission
until the second grant is consumed, B publishes its snapshot, validation and
integration complete, and the WorkGroup closes. If an agent again ignores a
concrete projection, preserve that as compliance evidence; do not change
production lifecycle code. Do not broaden cleanup, ownership, Doctor, push, or
create SYN-040.

## CP-0480 convergence projection slice

The CP-0480 diagnostic and ordinary runs did not reproduce a backend
WorkGroup split. Claim-bearing sessions with complementary exact scopes
converged on one deterministic active WorkGroup. The ordinary run exposed the
existing WorkGroup and exact REVIEW admission payload, but the executable
workflow reduced `request_coordination` to empty arguments. `AgentNextActionService`
now promotes the selected review protocol kind/payload to the response root,
and `AgentWorkflowReducer` copies it into exact executable arguments. Focused
MCP/workspace tests pass. Evidence:
`docs/evidence/syn039-unattended-todo-cp0480-convergence-projection-2026-08-24.md`.

Immediate next action: rerun a fresh post-fix diagnostic and ordinary
acceptance, then preserve the first lifecycle blocker after exact REVIEW
admission. Do not broaden cleanup, integration, Doctor, or ownership behavior.

## Objective

Make two ordinary Synesis-aware coding agents complete one shared repository
task unattended through the existing Synesis coordination model. Agent A must
implement, Agent B must review and validate without conflicting write
ownership, rejection must return work to the correct implementer, accepted
work must integrate, and the WorkGroup must close with no unresolved state.

## Activation boundary

- The reviewer-validation and producer-publication slices are implemented and
  under verification; reviewer admission, grant consumption, and the explicit
  owner publication action are now covered.
- The existing independent Codex/Claude Code session model remains underneath
  Synesis. Do not add a central orchestrator, UI, daemon, Fleet system,
  centralized launcher, provider intelligence, or manual relay service.
- SYN-038 remains complete and its prior acceptance evidence and
  `turn_interrupted_command_remained_active` limitation are preserved.

## Evidence and known gap

- Primary failure input: the user-supplied previous unattended Todo smoke test.
- The raw Todo smoke-test artifact was not present in the checkout. The
  reproduction is now captured in
  `docs/evidence/syn039-unattended-todo-baseline-2026-08-22.md`; raw Codex
  JSONL remains in the disposable fixture's `baseline-logs` directory.
- Checked-in supporting evidence is
  `docs/architecture/zero-touch-agent-collaboration.md`, where the two-process
  path is DEMO_ONLY and manually driven, and
  `docs/validation/multi-chat-provider-acceptance.md`, which does not claim
  autonomous end-to-end integration.

## Acceptance target

- Two ordinary Synesis-aware sessions discover and join one durable WorkGroup
  without user file assignment or message relay.
- A reviewer/validator can inspect and validate another agent's completed
  immutable snapshot through a read-only or explicitly delegated review path
  without acquiring the implementer's mutation ownership.
- Validation emits structured accept/reject evidence. Rejection returns
  durable work to the correct implementer with preserved lineage and
  idempotent request handling.
- Accepted work integrates through the existing guarded integration path into
  the final project state without manual intervention.
- Completion closes participants, claims, lane grants, pending requests,
  detached coordination state, and temporary artifacts.
- Final `synesis doctor` is healthy or reports only explicitly accepted
  non-blocking warnings.
- The unattended two-agent Todo experiment passes end to end: Agent A
  implements, Agent B reviews/validates, one rejected result routes back
  correctly, the corrected result is accepted, tests pass, the control
  checkout contains the completed application, the WorkGroup closes, and no
  unresolved coordination state remains.
- The existing ten-tool MCP boundary and provider model remain intact.

## Baseline result

The real two-session reproduction failed. Agent A created WorkGroup
`7c5ab815-5f05-365b-a78b-3478440036af`, implemented the Todo completion
change, passed three focused tests, and published snapshot
`snap_6162f6fd4ff4d51aadb5484609270ab3`. Integration failed with
`integration_failed` / `TESTS_FAILED`. Agent B discovered the WorkGroup but
could not obtain review authorization: `work_group_join` required an unavailable
`grantId`, and no validation request or snapshot view was exposed. The control
checkout stayed at baseline `7a5925f`; post-run Doctor was `DEGRADED` with two
`stale_session_lease` warnings and reconciliation recommended.

The baseline identified the first narrow implementation seam as reviewer
snapshot authorization plus the structured integration evidence mismatch; it
did not authorize a new orchestrator, daemon, UI, Fleet system, or launcher.

## Work completed

Implemented the first two defects and deterministic regressions. A reviewer
can now submit a typed `REVIEW` request after discovering a WorkGroup; owner
acceptance issues a targeted single-use LaneGrant without changing write
ownership. Collaboration status and next-action projections expose WorkGroups,
grants, and immutable snapshots. The integration-check adapter now recognizes
the recorded bounded passing Todo evidence instead of manufacturing
`TESTS_FAILED`.

The prior implementation and rerun evidence is recorded in
`docs/evidence/syn039-unattended-todo-review-validation-2026-08-22.md`. The
producer-publication slice is recorded in
`docs/evidence/syn039-unattended-todo-snapshot-publication-2026-08-22.md`.

The CP-0475 publication slice fixed the executable workflow projection: the
existing `nextProtocolPayload.summary` is now passed to `finish_lane` instead
of being discarded. A deterministic MCP fixture reached real `finish_lane`
execution, produced a `PUBLISHED` immutable snapshot, and exposed its ID in
the reviewer coordination projection. Evidence is recorded in
`docs/evidence/syn039-unattended-todo-snapshot-publication-cp0475-2026-08-24.md`.

## Current failures

The CP-0475 deterministic reproduction confirmed that the publication action
was emitted with an empty executable argument map even though the protocol
payload supplied the required summary. The minimal reducer fix is verified.
The first fresh two-agent harness attempt then failed before coordination:
Agent A remained at `workspace_not_ready` / `ensure_session`, while Agent B
reached a ready session with no peer WorkGroup and canceled its lane. A second
fresh attempt remained non-terminal for a bounded five-minute observation and
was stopped. These are harness/configuration failures, not a valid lifecycle
result and not a reason to change production readiness behavior.

The CP-0476 fresh fixture passed an explicit two-connection control preflight
with the current bundled MCP, but both independent agent harnesses failed
three times at `ensure_session(refresh=true)` with
`retry_required / workspace_not_ready / ensure_session`. No Todo work or
coordination lifecycle state was created. The first blocker is therefore the
agent-route configuration/session mismatch; it is not yet a production
readiness defect because the same executable and project passed through the
explicit control route. Evidence:
`docs/evidence/syn039-unattended-todo-harness-preflight-cp0476-2026-08-24.md`.

CP-0477 then corrected the harness with explicit per-agent current-bundle
configuration and reached a real WorkGroup. The owner implemented the Todo
completion change and passed 3 tests, but called `finish_lane` before review
readiness and received `task_not_ready / retry`. The reviewer saw the exact
`request_coordination(work_group_join)` review-admission projection, attempted
an invalid inbox acknowledgement, received `policy_denied / INBOX_ITEM_NOT_FOUND`,
and stopped. No grant, snapshot, validation, integration, or closure was
reached. This is agent action ordering, not a proven production protocol
defect. Evidence:
`docs/evidence/syn039-unattended-todo-cp0477-2026-08-24.md`.

The serialized root `check` remains incomplete. A focused
`:mcp:test --tests org.synesis.mcp.application.McpServerTest` reproduced the
Git subprocess stall at `McpServerTest.java:181`; worker `24912` was blocked
through `AgentNextActionService` → `RepositoryPrivateStateService` →
`GitProcessRunner` → `ProcessCommandRunner`. It was stopped only after thread
and child-process evidence was captured. Doctor remains `DEGRADED` with the
existing documented warnings.

The corrected CP-0481 diagnostic used the current bundled MCP with two
distinct ready/isolated sessions and disjoint claims. The agents converged on
WorkGroup `f0666aa0-31db-3025-a7e7-2e46f3fad1de`; Agent A published
`snap_0c58f76fb959553d7d64d64ce7b0d21c` but selected unprojected
`finish_lane`, which returned `integration_failed`. No REVIEW action was
projected and Agent B never reached admission. This is agent-compliance
evidence, not a production defect. Evidence:
`docs/evidence/syn039-unattended-todo-cp0481-postfix-review-admission-2026-08-24.md`.

The CP-0482 diagnostic then required exact execution of every concrete
projection. Both agents completed visible work and repeatedly received
ordinary `IMPLEMENT` with no executable tool or arguments. No REVIEW,
publication, grant, validation, or integration action was exposed. This is a
confirmed projection defect, not agent noncompliance. Evidence:
`docs/evidence/syn039-unattended-todo-cp0482-actionable-projection-2026-08-24.md`.

## CP-0483 active-reviewer projection rerun

The fresh post-fix diagnostic used the current bundled MCP, two distinct
ready/isolated sessions, and disjoint `todo.py` / `test_todo.py` claims. Both
intents converged on WorkGroup `af1807bc-ab46-3c98-8908-7073a807a7a6`. Agent A
published snapshot `snap_2ecbf452a75a69a8048168e6a1f177f2`, but the reviewer
intent was recorded first. The reviewer then repeatedly received ordinary
`IMPLEMENT` with no executable action even though the implementation snapshot
was visible. No REVIEW request, grant, validation, integration, or closure was
created. Evidence:
`docs/evidence/syn039-unattended-todo-cp0483-active-reviewer-projection-2026-08-24.md`.

The active-peer admission slice is verified by focused workspace/MCP tests,
but the live run proves an order-dependent producer/reviewer selection gap.
The known unprojected `finish_lane` calls remain agent-compliance evidence.

## Implementation order

1. Reproduce and capture the supplied unattended Todo failure.
2. Implement reviewer admission and the integration evidence fix.
3. Re-run the admitted-review path through the new owner `finish_lane` action;
   if reached, preserve the next lifecycle failure as a bounded blocker.
4. Implement autonomous rejection routing, handoff lineage, and WorkGroup
   cleanup/Doctor closure only as required by evidence.
5. Rerun the same unattended Todo experiment with no babysitting and record
   the complete evidence.

## Historical next action

Trace the order-dependent review-admission owner selection in
`AgentNextActionService.reviewActions`. Reproduce the reviewer-first state with
a deterministic fixture, then implement only the smallest existing-model fix
that lets the reviewer discover the implementation snapshot and exact
`request_coordination(work_group_join)` action using WorkGroup, intent,
claim-epoch, and snapshot provenance. Preserve fail-closed ownership and do
not add roles, orchestration, cleanup, or a side channel. Keep the Git stall,
bootstrap migration failures, and Doctor warnings separate. Do not push or
create SYN-040.

## CP-0471 owner REVIEW-acceptance slice

The owner-side projection defect is fixed. For a pending REVIEW request,
`get_next_action` now includes the exact request-specific strict
`respond_coordination` payload and WorkGroup/intent/claim-epoch context;
`AgentWorkflowReducer` carries that payload into the existing executable
workflow action. The collaboration service still performs authorization and
replay checks. Deterministic MCP, workspace, coordination, Javadocs,
validator, Go, vet, and diff checks pass. Evidence:
`docs/evidence/syn039-unattended-todo-owner-acceptance-2026-08-22.md`.

The fresh app-managed two-agent rerun did not reach this state: initial
ownership admission returned the existing typed `overlapping_claim` blocker
because another participant already held both Todo paths. It is not a valid
end-to-end result for this slice. Root `check` remains incomplete at the known
Git subprocess stall in `WorkspaceCliTest.setUp:74`; Doctor remains DEGRADED.

Immediate next action: run the exact fresh two-agent Todo acceptance with
isolated initial ownership, then verify autonomous owner acceptance and
preserve the next lifecycle failure. Do not broaden SYN-039 or create SYN-040.

## CP-0472 unattended acceptance result

The fresh two-agent run is recorded in
`docs/evidence/syn039-unattended-todo-workspace-not-ready-2026-08-23.md`.
Both independent Luna High agents stopped at the same typed
`workspace_not_ready` → `ensure_session` recovery projection. No WorkGroup,
claim, request, grant, snapshot, validation, integration, or closure state was
created. The fixture remained at coordination sequence zero with zero tasks and
zero ownerships. This is the first blocker for this run, but it is currently
classified as per-project MCP/session readiness until reproduced
deterministically outside the agent harness. No production behavior was
changed.

## CP-0473 workspace-readiness implementation and rerun

The readiness trace identified that Codex's managed global MCP entry omitted
the initialized project root. `ensure_session` therefore depended on the MCP
process directory when a provider did not send MCP roots. Codex installation
now writes the existing `--project <root>` argument through the explicit-root
configuration path. Fresh provider installation, repeated session ensure, and
two independent bindings are covered by deterministic tests. Commit:
`bea47c4`.

The direct MCP process with the generated project-pinned entry returned
`ready/isolated`. The fresh unattended CP-0473 rerun still stopped before
coordination because the agent harness reported an incompatible/stale MCP
distribution and project-schema-v2 readiness failure. No lifecycle state was
created. Evidence:
`docs/evidence/syn039-workspace-readiness-cp0473-2026-08-23.md`.

## CP-0474 current-bundle unattended acceptance

The fresh acceptance used two independent GPT-5.6 Luna High agents and the
rebuilt Windows platform bundle. Both MCP processes launched the current
`synesis-mcp.exe` with the exact disposable project root. Initialize reported
protocol `2025-06-18`, the catalog contained exactly ten tools, and both agents
reached `ready / isolated`.

The run reached the real lifecycle: Agent B discovered WorkGroup
`33e8329c-fd66-3174-9e3f-f115f6dae550`, autonomously obtained and consumed
REVIEW grant `496f1893-ca32-3939-82a1-24f860dea86a`, and did not take write
ownership. Agent A implemented the Todo operation and passed four pytest tests,
but its projected `PUBLISH` action remained blocked by
`snapshot_publication_required`; repeated `finish_lane` returned
`task_not_ready` / `retry`. No snapshot, validation, integration, or closure
was reached. Evidence:
`docs/evidence/syn039-unattended-todo-cp0474-2026-08-24.md`.

Immediate next action: reproduce the owner-side `PUBLISH` /
`snapshot_publication_required` stop deterministically and trace why the
implementer does not execute the already-projected snapshot-publication path.
Implement only that narrow producer transition if the evidence confirms a
protocol defect. Keep cleanup, Doctor, ownership, integration redesign, the
Git stall, and bootstrap migration failures separate; do not create SYN-040.

## CP-0475 snapshot-publication projection slice

The exact contradiction was reproduced: `get_next_action` projected
`snapshot_publication_required` and `nextProtocolPayload.summary`, while the
workflow reducer emitted empty `finish_lane` arguments. The reducer now carries
that existing payload into the executable action. The deterministic fixture
verified the exact WorkGroup, intent, claim epoch, and summary, then executed
the real MCP `finish_lane` path and observed a `PUBLISHED` immutable snapshot
visible in reviewer coordination status. Focused MCP/workspace tests and
Javadocs pass.

The fresh CP-0475 agent harness attempts did not reach a valid shared
WorkGroup: the first had one readiness failure and one isolated peer with no
WorkGroup; the second remained non-terminal and was stopped after bounded
observation. Evidence:
`docs/evidence/syn039-unattended-todo-snapshot-publication-cp0475-2026-08-24.md`.

Immediate next action: run the exact unattended two-agent Todo acceptance with
both MCP processes independently verified against the current bundle and
project root. Preserve the first post-publication lifecycle blocker if the run
reaches it; do not modify review, cleanup, Doctor, or integration behavior
speculatively.

## CP-0477 explicit harness and lifecycle rerun

The CP-0477 comparison confirmed that one ordinary multi-agent route selected a
stale installed launcher without `--project`, while a pinned current-bundle
route failed when its disposable project had disappeared. The acceptance
harness was corrected with explicit per-agent current-bundle, project,
provider, and connection-instance overrides; no production code changed.

Both explicit preflight agents passed. The unattended lifecycle reached
WorkGroup `62f4a6d0-0061-3e3d-8cc5-7536b556782c`, intent
`8fcbab57-9293-3321-8945-e5a5fd4af6b9`, claim epoch 1, and a passing 3-test
Todo implementation. The owner called `finish_lane` before review readiness
and received `task_not_ready / retry`. The reviewer observed the exact
`REVIEW_ADMISSION_REQUIRED → request_coordination(work_group_join)` projection,
attempted an invalid inbox acknowledgement, received
`policy_denied / INBOX_ITEM_NOT_FOUND`, and stopped. No grant, snapshot,
validation, integration, or closure was reached. This is agent action ordering,
not a proven production protocol defect. Evidence:
`docs/evidence/syn039-unattended-todo-cp0477-2026-08-24.md`.

## CP-0476 harness preflight

Evidence is recorded in
`docs/evidence/syn039-unattended-todo-harness-preflight-cp0476-2026-08-24.md`.
The fresh fixture and exact repository-bundled MCP passed an explicit
two-connection control preflight: protocol `2025-06-18`, exactly ten tools,
the same initialized project ID, distinct isolated worktrees, and
`ensure_session(refresh=true)=ready`.

Both independent `gpt-5.6-luna` agent harnesses nevertheless failed their
required preflight three times at `ensure_session(refresh=true)` with the
typed state `retry_required / workspace_not_ready / ensure_session`. Neither
agent began Todo work; coordination sequence remained zero and no WorkGroup,
claim, request, grant, snapshot, validation, integration, or closure state was
created. This is the first CP-0476 blocker and is not evidence for changing
production readiness or lifecycle behavior because the same bundled executable
and project succeed through the explicit control invocation.

Exact next action: capture the effective MCP executable, startup version/commit,
project arguments, connection identity, and readiness trace from the agent
route itself, then reconcile that route with the passing control invocation.
Keep the Git subprocess stall, bootstrap migration failures, and Doctor
warnings separate. Do not push or create SYN-040.

## CP-0479 agent contract clarification and acceptance reruns

The CP-0478 audit found an ambiguous agent-facing gap: `IMPLEMENT` with no
concrete `recommendedTool` and `arguments` did not explicitly mean continue
ordinary coding in the visible assigned worktree. The managed provider manual
and `get_next_action` MCP description now state that behavior, explicitly
forbid `.synesis/**` inspection through workspace file tools, and require exact
execution only when a concrete tool and arguments are projected. Deterministic
catalog/manual tests pass; path protection and lifecycle semantics are
unchanged.

The bounded CP-0479 diagnostic reached one shared WorkGroup, review admission,
review grants, a passing four-test implementation, snapshot publication, and
integrated control checkout commit `24ed805`. The owner still selected
unprojected `finish_lane` once and received `task_not_ready / retry` before a
later retry succeeded. No structured reviewer ACCEPT/REJECT decision was
captured, and three managed worktrees remained. Evidence:
`docs/evidence/syn039-unattended-todo-cp0479-contract-and-ordinary-acceptance-2026-08-24.md`.

The required second ordinary acceptance did not form a shared WorkGroup. Both
agents independently published snapshots, each reached `integration_blocked`,
and the control checkout stayed at its managed baseline. One agent's
non-projected integration-check facts reported pytest green but received
`integration_conflict / TESTS_FAILED`; this is preserved as an observation,
not a production-fix authorization. The next concrete blocker is ordinary
agent coordination discoverability/compliance, with integration classification
still secondary.

Both fixtures reported coordination sequence zero, zero tasks, and zero
ownerships after the runs. Doctor remained `DEGRADED` with six warnings. The
Git subprocess stall and bootstrap migration failures remain separate.

Exact next action: preserve CP-0479 and design the smallest evidence-led slice
for ordinary peer/WorkGroup discovery and convergence, without changing
hidden-path protection or adding orchestration. Do not push or create SYN-040.

## CP-0481 post-fix diagnostic

The fresh corrected fixture `syn039-cp0480-006` used the current bundled MCP,
the same initialized project, distinct ready/isolated sessions, and disjoint
`todo.py` / `test_todo.py` claims. Both agents converged on WorkGroup
`f0666aa0-31db-3025-a7e7-2e46f3fad1de`. Agent A published snapshot
`snap_0c58f76fb959553d7d64d64ce7b0d21c`, but selected unprojected
`finish_lane`; integration returned `integration_failed` and no REVIEW action
was projected. Evidence:
`docs/evidence/syn039-unattended-todo-cp0481-postfix-review-admission-2026-08-24.md`.

This is agent action/compliance evidence, not a production integration defect.
The next bounded diagnostic must execute every concrete projected action
exactly and preserve the first post-publication result. Do not push or create
SYN-040.

## CP-0482 actionable-projection defect

The fresh bounded diagnostic `syn039-cp0481-001` used the current bundled MCP,
two ready/isolated sessions, and disjoint claims. Both agents converged on
WorkGroup `ffd58516-2313-3ccc-a402-b20c921d2f8f`, completed visible work, and
obeyed the exact-action rule. Repeated `get_next_action` calls still exposed
ordinary `IMPLEMENT` with no concrete tool or arguments, while no REVIEW,
publication, grant, validation, or other progress action was projected. This
is the first confirmed CP-0482 production projection defect. Evidence:
`docs/evidence/syn039-unattended-todo-cp0482-actionable-projection-2026-08-24.md`.

## CP-0478 exact-action protocol diagnostic

The fresh CP-0478 fixture used the current bundled MCP, explicit project pin,
distinct connection IDs, exactly ten tools, and two `ready / isolated`
sessions. Both agents reached an initial `IMPLEMENT` projection, but no
specific lifecycle tool or arguments were projected because no WorkGroup yet
existed. Agent A then selected `read_file(".synesis/project.json")` and Agent
B selected the same hidden-path read; both received `blocked / invalid_path`.
Agent B confirmed the metadata through the valid command
`git show HEAD:.synesis/project.json` and stopped. No files or coordination
state changed. Evidence:
`docs/evidence/syn039-unattended-todo-cp0478-protocol-diagnostic-2026-08-24.md`.

This is an agent-selected repository-inspection failure, not an exact
projected lifecycle action failure and not a proven production defect. No
second ordinary-agent acceptance was run. Coordination sequence remained
zero with no WorkGroup, claims, requests, grants, snapshots, validation, or
integration. Doctor was `DEGRADED` with six existing warnings; the Git
subprocess stall, bootstrap migration failures, and Doctor findings remain
separate.

Exact next action: preserve CP-0478, assess the hidden metadata path contract,
and only rerun a diagnostic after the initial agent inspection uses valid
repository operations. Do not modify production lifecycle code, push, or
create SYN-040.

## 2026-09-10 — SYN-055 modal Diagnostics inspector

The Diagnostics finding inspector is now a modal popup rather than a right-side
grid panel. It preserves the underlying findings table, supports close,
Escape, backdrop dismissal, and left/right navigation. Previous and next
controls remain visible and are natively disabled at the ends of the finding
list. The Doctor data model and repair semantics are unchanged.

Exact next action: preserve the verified modal behavior and package; perform a
viewport screenshot pass only when a viewport-capable browser surface is
available. Do not add repair actions or backend changes.

## 2026-09-10 — SYN-055 project header navigation

The shared live-project header now places the project name and runtime status
in the right metadata rail beside Project ID and Local path. The left side now
contains a visible Back to projects button that returns to the Projects home.
The breadcrumb remains for location context.

Exact next action: preserve the packaged header navigation and run a viewport
screenshot pass when a viewport-capable browser surface is available.

## 2026-09-10 — SYN-055 icon-only back control

The project header keeps the project name/status in the right metadata rail and
now uses only a back-arrow icon in the left control. The visible button text
was removed; its accessible label remains `Back to projects`.

Exact next action: preserve CP-0843 and run the pending viewport screenshot
pass when a viewport-capable browser surface is available.

## 2026-09-10 — SYN-055 header layout correction

Restored the project name and runtime status to their original left-side
position. The right metadata rail again contains only Project ID and Local
path. The new back control remains an icon-only arrow beside the title.

Exact next action: preserve CP-0844 and run the pending viewport screenshot
pass when a viewport-capable browser surface is available.

## 2026-09-10 — SYN-055 local mock-data development mode

Added an explicit Vite development-only `?mock=1` mode backed by a local
snapshot fixture. It bypasses bootstrap/control-plane startup only in Vite
development mode, exposes realistic project/agent/coordination/network/
diagnostics data, and leaves the normal runtime path unchanged.

Live smoke passed at `/projects/proj_test/overview?mock=1` and
`/projects/proj_test/diagnostics?mock=1`.

Exact next action: use `npm run dev` in `web-ui` for UI iteration; do not
reinstall the Windows bundle for frontend-only changes.

## 2026-09-10 — SYN-055 detail popup refinement

Removed the always-visible right-side detail rails from Agents, Coordination,
and Network. Agent rows now open an Agent Detail popup; Coordination exposes a
View details popup; Network exposes Membership and Relay detail popups. The
Network Authority boundary note was removed as requested. All popup content
continues to use the existing snapshot model.

Exact next action: continue UI iteration against the running mock server at
`http://127.0.0.1:5173/projects/proj_test/network?mock=1`.

## 2026-09-10 — SYN-055 Projects page refinement

The global Projects page now has client-side project search across name,
identity, path, and status. The right rail contains only Connections. Each
projected connection has its own View network control and a visibly disabled
Terminate end control because no terminate-connection command exists in the
current control-plane contract. The page header and registry heading are
sticky on the Projects route when the page scrolls.

Exact next action: preserve CP-0848 and continue browser QA at
`http://127.0.0.1:5173/projects?mock=1`.

## 2026-09-10 — SYN-055 connection termination popup and mock scroll slice

Replaced the per-connection disabled text action with a red bin icon on both
the global Connections rail and the project Network peer table. The icon opens
a confirmation popup with the requested peer-to-peer dependency warning for
DIRECT and PEER_TRANSIT routes; relay routes do not receive that warning. The
confirm action remains disabled because the current control-plane contract has
no terminate-connection command. Expanded the development-only mock registry
to 14 projects and 8 peers for scrollability checks.

Exact next action: preserve the running Vite mock server and use the two mock
URLs in the final handoff; no reinstall is required for this frontend slice.

## 2026-09-10 — SYN-055 Network row detail refinement

Removed the standalone Server-selected routes widget from Network. Clicking
any peer row now opens a Connection detail popup with the projected route,
path, membership, health, authentication, usability, and session data. The
global Connections rail uses the same row-detail behavior; termination icons
remain separate actions.

Exact next action: preserve CP-0849 and continue frontend QA on the running
mock server.

## 2026-09-10 — SYN-055 supplied logo asset

Replaced the generated CSS mark in the global header and connection screen
with the supplied `SynesisLogo.png` asset copied to
`web-ui/public/synesis-logo.png`. The existing accessible Synesis label and
navigation behavior remain intact.

Exact next action: keep frontend-only iteration on the running Vite server; no
reinstall or package rebuild is required for this asset change.

## 2026-09-10 — SYN-055 consistent project view width and browser history

Removed the special wide layout from Coordination and Network so every project
view now uses the same content width as Overview, Agents, and Diagnostics.
Project-view navigation now uses browser history entries and preserves the
development `?mock=1` query; `popstate` restores the selected project and tab
when the browser Back or Forward controls are used.

Verification: typecheck, lint, 15 tests, production build, diff check, and
live mock Back/Forward navigation passed.

Exact next action: continue visual QA from
`http://127.0.0.1:5173/projects/proj_test/overview?mock=1`.

## 2026-09-10 — SYN-055 Coordination table scrollbar removal

Removed the nested horizontal scrollbar from Coordination Claims, Tasks,
Capabilities, and Ownership tables. The tables now fit their panel and wrap
long values while the page-level scroll remains available.

Verification: typecheck, lint, 15 tests, production build, diff check, and
live mock Coordination inspection passed.

## 2026-09-10 — SYN-055 Coordination nested scrollbar correction

The Coordination records tabs no longer expose a nested scrollbar. The tab
strip is clipped to its own bounds and record tables wrap long values within
the panel; page-level scrolling remains available.

Verification: typecheck, lint, 15 tests, production build, diff check, and
live mock screenshot inspection passed.

## 2026-09-10 — SYN-055 global runtime indicator removal

Removed the `Runtime connected` indicator from the global header. Runtime and
connection state remain intact for loading and offline behavior; only the
header presentation was removed.

Verification: typecheck, lint, 15 tests, production build, and diff check
passed. Live preview reopening was blocked because the local Vite server was
not running after the prior preview session ended.

## 2026-09-10 — SYN-055 registry detail back control

Added the same icon-only Back to projects control to every registry detail
state, including INACTIVE, UNAVAILABLE, and IDENTITY_MISMATCH projects. The
accessible label remains available while the visual button contains only the
arrow.

## 2026-09-11 — SYN-056 Docker retry and daemon endpoint correction

SYN-056 remains the sole active task and ACTIVE / PARTIAL. Docker initially
ran the fresh D:-backed two-container harness: both installed daemon runtimes
reported RUNNING, the bridge candidate probe found a non-loopback address, and
distinct project identities were initialized. The daemon endpoint slash defect
was isolated and corrected with a focused regression test.

Exact next action: after docker version reaches the Linux server again, verify
the exact D:-backed containers/network, repair the disposable X11 image
libraries, then rerun the browser-mediated two-identity JOIN, Complete Join,
ANSWER, durability, and replay checks.

## 2026-09-24 — SYN-082 overnight acceptance checkpoint

SYN-082 remains incomplete. Phase 1 passes the deterministic continuation
crash-prefix/replay tests and bounded disposable-project process-loss recovery,
with the limitation that the real target did not crash the full MCP server
during the continuation append. Phase 2 is PARTIAL: blind A/B had disjoint
active work and B's full process tree was killed; C lawfully continued B's
exact lane at epoch 2, but A exited before C acquired it. A later blind E
continued the storage lane again and verified its retained implementation.
The WorkGroup is still ACTIVE. Phase 3 is PARTIAL; the hostile mandatory-review
path did not reach a valid reviewer decision. Synesis rejected producer-origin
review acceptance with REVIEWER_INTENT_REQUIRED. The published snapshots
reported reviewRequired=false.

The 2026-09-24 test checkpoint includes 91 coordination tests, 432 workspace
tests, 108 MCP tests, 20 focused SYN-039 tests, the 10-tool catalog check,
static/architecture/Javadoc/validator gates, and cli installDist. The GUI
distribution smoke task hung and was interrupted; it is not a pass. See
docs/evidence/SYN-082-lawful-recovery-continuation-2026-09-20.md for the
superseding run record and exact limitations.
# 2026-09-23 — SYN-082 real process-crash proof and reviewer wait repair
