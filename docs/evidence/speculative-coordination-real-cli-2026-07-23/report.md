# Real speculative coordination CLI acceptance

Date: 2026-07-23. Result: PASS. This is a real operating-system-process run
using the built Synesis CLI, one loopback coordinator, two independent
profiles/identities, two Git worktrees, and durable event replay. The run used
`C:\\synesis-demo10` as disposable runtime state; safe bounded copies are stored
beside this report.

1. **Demonstration status.** The complete flow passed: ownership conflict,
   exact prediction, live owner acceptance, isolated speculation, unresolved
   gate rejection, owner implementation, live availability, real integration,
   validation, retirement, and resolved gate acceptance.

2. **Identity and process summary.** Project
   `6c788d72-cfbd-47e6-834b-6d2badb2d869`; coordinator PID `41620`, Supervisor B
   PID `32060`, Supervisor A first-process PID `13636`, and Supervisor A resumed
   PID `41872`. Node A is
   `sl1-80600acd324dceedf8117b71d6e87c50f8debdf38e6a436c61b2b005fc242e6a` and
   node B is
   `sl1-cf329808a94088df89ff1889318fabbe8beda0bb90aed29ef5cfb9efba028aad`.
   Supervisor/task identifiers are `supervisor-a`/`worker-a` and
   `supervisor-b`/`workspace.prediction-query`. Profiles were
   `C:\\synesis-demo10\\profile-a` and `C:\\synesis-demo10\\profile-b`.
   Worktrees were `C:\\synesis-demo10\\worktree-a` and
   `C:\\synesis-demo10\\worktree-b`; branches were
   `synesis-demo-a-6858b06c08f44cd1a354e9a9b92f263c` and
   `synesis-demo-b-e9ecd34ab9a9437599d0749ff04c8fbd`. Endpoint:
   `http://127.0.0.1:48125/`. Both cursors started at 0.

3. **Ownership.** A's request named capability `workspace.prediction-query`,
   owner node B, owner supervisor `supervisor-b`, and intent version 1. The
   concrete scope was
   `workspace/src/main/java/org/synesis/workspace/application/SupervisorApplicationService.java`.

4. **Provider enforcement.** The real Antigravity-shaped structured hook path
   returned `REQUEST_OWNER`; `UNAUTHORIZED_MUTATION_OCCURRED=false`. The output
   carried capability, owner node, owner supervisor, and intent version.
   Protected scope was `ABSENT` before and after the hook.

5. **Prediction.** Prediction ID
   `f6e444f0-2855-4136-900c-9af44d32962b`; request/event ID
   `fe362c2f-361a-342b-a4e3-69fb3b07ead1`. The exact contract included purpose
   “Expose predictionStatus(UUID)”, UUID input, `PredictionState` output,
   projection lookup behavior, missing-prediction rejection, no side effects,
   authenticated projection invariant, Java 25 compatibility, local O(1)
   lookup, single-threaded constraint, `:workspace:check` acceptance test,
   base sequence 0, base commit `9c23120`, protected hash `scope=absent`, owner
   intent version 1, confidence 90, and risk 10.

6. **Ordered event timeline (UTC).**

   | Sequence | Time | Event | Process evidence |
   |---:|---|---|---|
   | 1 | 15:59:51.959121Z | `PREDICTION_CREATED` | A created |
   | 2 | 15:59:51.959121Z | `PREDICTION_ROUTED` | A routed |
   | 3 | 15:59:51.978678Z | `REQUEST_RECEIVED` | B live, 19.6 ms after creation |
   | 4 | 15:59:51.993912Z | `ACCEPTED_EXACT` | B accepted; A observed ordered event at 15:59:52.093448Z |
   | 5 | 15:59:52.016059Z | `IMPLEMENTATION_STARTED` | B owner transition (persisted order is seq 5) |
   | 6 | 15:59:55.315537Z | `PATCH_READY` | B published |
   | 7 | 15:59:55.325567Z | `CAPABILITY_AVAILABLE` | B published; A received live at 15:59:55.337611Z (12.0 ms) |
   | 8 | 16:00:03.797622Z | `VALIDATION_STARTED` | A validation |
   | 9 | 16:00:03.859757Z | `SPECULATION_RETIRED` | A retired |

   The process logs are authoritative for each actor's observation; the
   coordinator event files contain all nine signed/hash-chained events.

7. **Speculative continuation.** A created a detached nested Git worktree and
   wrote a CLI-side dependent consumer there. The speculative commit carried
   `Synesis-Prediction` and `Synesis-Base-Sequence` trailers. The overlay stayed
   outside the normal worktree and was never published.

8. **First Git gate.** Before resolution the explicit gate output was
   `INTEGRATION_GATE=REJECTED REASON=UNRESOLVED_PREDICTION`.

9. **Owner implementation.** B implemented `SupervisorApplicationService.predictionStatus(UUID)`,
   ran `:workspace:compileJava`, and committed
   `8c6fcab09fa0abb31ed1d0636660ea486bd21132` (short `8c6fcab`).

10. **Availability delivery.** B emitted `PATCH_READY` and
    `CAPABILITY_AVAILABLE`; A's resumed process received both as `A_LIVE`, not
    by a shared file or a preloaded projection.

11. **Validation.** A merged B's real commit with `--no-ff` and ran
    `:workspace:compileJava` successfully. Final protected-scope hash was
    `2WCYcH1NOb1FATaoiIVlPm/07L4HqtoG4lRR+/UugTE=`.

12. **Retirement.** A removed the nested speculative worktree and metadata,
    printed `PREDICTION_STATE=RETIRED RETIRED_SEQUENCE=9`, and printed
    `SPECULATIVE_ARTIFACTS_PRESENT=false`.

13. **Final Git gate.** The resolved retry printed
    `INTEGRATION_GATE=ACCEPTED REASON=PREDICTION_RESOLVED`. The final A graph
    contains merge commit `13f7853` over B's `8c6fcab` implementation commit;
    graph and status captures are in `git-graph-a.txt`, `git-graph-b.txt`,
    `git-status-a.txt`, and `git-status-b.txt`.

14. **Reconnect/replay.** A deliberately exited after cursor 4 (exit 75),
    restarted with the same profile/state, replayed sequence 5 in order, then
    consumed sequences 6 and 7 live. The durable cursor advanced exactly once;
    duplicate command submission remains idempotent by the coordination tests.

15. **Automated verification.** `:coordination:check --no-daemon` passed (7
    actionable tasks) and root `check --no-daemon` passed (42 actionable tasks),
    including format, strict Javadocs/static analysis, architecture checks,
    provider regression tests, CLI tests, bundle smoke, and workspace tests.
    Outputs are retained in `coordination-check.txt` and `root-check.txt`.

16. **Bugs and fixes.** The acceptance work exposed and fixed the missing
    production HTTP client/SSE encoding, the integration-gate binding, the
    CLI coordination-demo process harness, provider ownership classification,
    live-vs-replay timing (B now waits before publishing), and the harness's
    Windows process-exit handling. A regression test now covers the gate's
    unresolved, dirty, and accepted outcomes.

17. **Evidence directory.** This directory contains bounded logs, event files,
    process summary, identity transcripts, Git graphs/status, and verification
    outputs. No private keys, bearer tokens, or complete credentials are stored.

18. **Checkpoint.** The next durable checkpoint advances from CP-0143 after
    this report and final verification; its exact identifier is recorded in the
    agent state files.

19. **Commits created.** The demo created disposable branch commits `8c6fcab`
    and `13f7853`; they are evidence-only worktree history and were not pushed.

20. **Final repository commit.** `Complete real speculative coordination
    vertical slice` (the final hash is recorded by Git at handoff).

21. **Final working-tree state.** Disposable worktrees and branches are removed
    after evidence capture. The repository retains only intentional source,
    test, documentation, checkpoint, and evidence changes.

22. **Remaining limitations.** This milestone validates the bounded loopback
    two-process acceptance path. Remote HTTPS/authenticated deployment,
    production supervisor lifecycle management, and a general-purpose
    `supervisor run` command remain deferred; they are not represented as
    completed by this report.
