# SYN-051 broker-pinned stock Codex thread feasibility — 2026-09-03

## Classification

**PASS-B — the broker closes the provider-thread selection gap, but one
bounded lifecycle primitive is missing.**

The disposable broker successfully used the normal Codex home and provider
authentication while keeping one App Server process pinned to one exact
persisted thread. A/B concurrent authenticated turns and proof delivery
passed. After pinning, wrong-thread operations were rejected locally and did
not produce an outbound App Server request. A live-owner lock rejected a
successor, an ambiguous-liveness marker rejected recovery, and a two-process
successor race had one winner. A2/B2 recreated processes and resumed their
own exact threads with fresh proofs.

The missing primitive is reliable owned process-tree teardown and liveness
classification when App Server alone is killed: the disposable MCP child was
observed with `parentPid=-1`. The broker detected App Server death and refused
further use, but this run does not establish that all descendants are always
terminated or that no orphan can retain authority. That is a production
requirement, not evidence to weaken existing fencing.

This is a feasibility result only. `UNSAFE_FILE_AUTH` is unchanged; no
production continuity/auth policy, Codex source, `.synesis` state, historical
fixture, credential, or MCP catalog changed. The current Synesis managed MCP
attachment path was not invoked because the explicit boundary was no Synesis
in the Synesis source.

## Scope and threat model

In scope were accidental cross-worker thread confusion, replacement runtimes,
stale generations, ordinary process restarts, simultaneous managed workers,
and incorrect provider routing caused by a shared thread store. A fully
compromised same-user account, direct local-state editing, and hostile process
debugging/injection were out of scope.

The tested v1 relation was:

```text
logical binding → broker → dedicated App Server process → exact Codex thread
```

The broker owned the App Server stdin/stdout handles exclusively. Synesis
generation fencing remains the authority for which logical runtime may be
active; the broker only enforces exact provider-thread attachment.

## Disposable prototype

The prototype was retained outside the repository at
`C:\Users\Liparakis\AppData\Local\Temp\syn051-broker-20260903-07`.
The source is `src\BrokerSpike.java`; the redacted report is
`broker-report.txt`; child/broker audit logs are under `logs\`.

The public lifecycle surface was deliberately small:

```text
launch()
startPinnedThread()
resumePinnedThread()
readPinnedThread()
startTurn(marker)
shutdown()
```

There is no `sendRawAppServerRequest`, `resumeAnyThread`, `startAnyThread`,
thread multiplexer, prompt planner, task router, retry policy, or autonomous
agent loop. Thread IDs are held in private broker state. The two invalid
selection checks are test-only guards proving that the unsupported caller
paths produce local rejection and no outbound frame; they are not public
provider operations.

The broker launched `codex app-server --stdio` directly, used per-launch
`--config` overrides for the selected `SYNESIS_ATTACH_PROOF` variable, and
opened no TCP or alternate IPC control port. The disposable MCP child saw
only its selected proof environment variable and MCP protocol messages.

## Provider/authentication baseline

- Starting HEAD: `1ec85065743fc012c01e7f8d9e3d2c731f6a106d`
- Provider: `codex-cli 0.145.0`
- Normal home: `C:\Users\Liparakis\.codex`
- Auth: provider-owned file-backed `auth.json`; `codex login status` reported
  `Logged in using ChatGPT`.
- The auth payload was not read, parsed, hashed, copied, migrated, or placed
  into environment/config by Synesis or the prototype.
- No raw attachment proof was persisted or logged. Only digests and presence
  flags were retained.

## Required report

|  # | Field                                     | Result                                                                                                                                                                                                                                                                                                                              |
|---:|-------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
|  1 | Starting HEAD                             | `1ec85065743fc012c01e7f8d9e3d2c731f6a106d`                                                                                                                                                                                                                                                                                          |
|  2 | Codex version                             | `codex-cli 0.145.0`                                                                                                                                                                                                                                                                                                                 |
|  3 | Normal CODEX_HOME                         | `C:\Users\Liparakis\.codex`                                                                                                                                                                                                                                                                                                         |
|  4 | Auth mode                                 | Provider-owned file-backed `auth.json`; login status ChatGPT                                                                                                                                                                                                                                                                        |
|  5 | Credential read/copy                      | No read or copy; no credential payload was inspected                                                                                                                                                                                                                                                                                |
|  6 | Broker prototype location                 | `C:\Users\Liparakis\AppData\Local\Temp\syn051-broker-20260903-07`                                                                                                                                                                                                                                                                   |
|  7 | Broker API surface                        | `launch`, `startPinnedThread`, `resumePinnedThread`, `readPinnedThread`, `startTurn`, `shutdown`                                                                                                                                                                                                                                    |
|  8 | Generic raw protocol access               | No; no generic raw-request or arbitrary-thread API exists                                                                                                                                                                                                                                                                           |
|  9 | Exclusive App Server stdio                | PASS within the bounded process: broker alone held stdin/stdout; no control port was opened                                                                                                                                                                                                                                         |
| 10 | Worker A App Server PID                   | `17512`                                                                                                                                                                                                                                                                                                                             |
| 11 | Worker A thread                           | `01a0687f-9c89-7172-bdf9-479e0001fbb0`                                                                                                                                                                                                                                                                                              |
| 12 | Worker A MCP child PID                    | `3052`                                                                                                                                                                                                                                                                                                                              |
| 13 | Worker A proof                            | PASS; digest `514b31a5e56f276eed408e58b8979aa578422f76940a004535e9547e1dbb43c2`, own only                                                                                                                                                                                                                                           |
| 14 | Worker A model turn                       | PASS; marker `SYN051_BROKER_A1_OK`                                                                                                                                                                                                                                                                                                  |
| 15 | Worker B App Server PID                   | `11808`                                                                                                                                                                                                                                                                                                                             |
| 16 | Worker B thread                           | `01a0687f-b249-7810-893a-54e8043776cd`                                                                                                                                                                                                                                                                                              |
| 17 | Worker B MCP child PID                    | `21440`                                                                                                                                                                                                                                                                                                                             |
| 18 | Worker B proof                            | PASS; digest `9abb6896c757ab0972933ef0820f7fffbcfb0ed1f63e06b67ac8ad2fe87c70a9`, own only                                                                                                                                                                                                                                           |
| 19 | Worker B model turn                       | PASS; marker `SYN051_BROKER_B1_OK`                                                                                                                                                                                                                                                                                                  |
| 20 | Concurrent A/B result                     | PASS; both dedicated App Servers stayed live and completed authenticated turns                                                                                                                                                                                                                                                      |
| 21 | Immutable pin result                      | PASS; each broker pinned exactly once and kept its private thread state immutable                                                                                                                                                                                                                                                   |
| 22 | Post-pin `resume(A→B)`                    | Local rejection: `BROKER_REJECTED_LOCALLY_NO_OUTBOUND`                                                                                                                                                                                                                                                                              |
| 23 | Post-pin `resume(B→A)`                    | Local rejection: `BROKER_REJECTED_LOCALLY_NO_OUTBOUND`                                                                                                                                                                                                                                                                              |
| 24 | Rejected requests reached Codex           | No; outbound-count delta was false and audit logs recorded `outbound=false`                                                                                                                                                                                                                                                         |
| 25 | Pre-pin wrong-thread successor            | Local rejection before launch; expected B versus requested A; `launched=false`                                                                                                                                                                                                                                                      |
| 26 | Exact returned-thread verification        | PASS; start/resume response and follow-up read contained the exact expected thread before pin                                                                                                                                                                                                                                       |
| 27 | Model ability to select thread            | No; model turns use broker-owned pinned state and do not expose lifecycle selection                                                                                                                                                                                                                                                 |
| 28 | MCP child ability to select thread        | No; child received MCP traffic/proof only, not App Server control or thread APIs                                                                                                                                                                                                                                                    |
| 29 | Restart A1→A2                             | PASS; A1 stopped, B1 remained usable, A2 acquired the binding after owner release                                                                                                                                                                                                                                                   |
| 30 | Exact Thread A resume                     | PASS; A2 resumed `01a0687f-9c89-7172-bdf9-479e0001fbb0`                                                                                                                                                                                                                                                                             |
| 31 | Fresh P_A2                                | PASS; digest `44434da3e1ecc39da320853e8c9e42b3d9a4c21263014adb4fb7e8f831d36312`                                                                                                                                                                                                                                                     |
| 32 | Stale P_A1                                | Not reused; old digest absent from the A2 child log                                                                                                                                                                                                                                                                                 |
| 33 | Model turn after resume                   | PASS; marker `SYN051_BROKER_A2_OK`                                                                                                                                                                                                                                                                                                  |
| 34 | B unaffected                              | PASS; B1 stayed active during A1 stop and A2 resume; B2 later resumed exact B                                                                                                                                                                                                                                                       |
| 35 | Live-old-runtime result                   | PASS; same-binding successor rejected `REJECTED_LIVE_OR_RACING_OWNER`                                                                                                                                                                                                                                                               |
| 36 | Ambiguous-liveness result                 | PASS; ambiguous marker refused successor launch                                                                                                                                                                                                                                                                                     |
| 37 | Concurrent successor race                 | PASS-B input; two independent successors yielded one lock winner and one local loser                                                                                                                                                                                                                                                |
| 38 | Broker versus Synesis fencing             | Broker enforces exact provider thread; Synesis generation fencing still selects the sole active logical runtime                                                                                                                                                                                                                     |
| 39 | Broker crash                              | Broker root termination was detected and no temp-root process remained in the post-run inventory; positive descendant-teardown proof is incomplete                                                                                                                                                                                  |
| 40 | App Server crash                          | Broker detected EOF/death and rejected further use; App Server-only kill orphaned the MCP child (`parentPid=-1`)                                                                                                                                                                                                                    |
| 41 | Orphan-process result                     | No temp-root process remained after the bounded cleanup snapshot, but child-tree teardown is not proven under isolated App Server kill                                                                                                                                                                                              |
| 42 | Process-level host recreation             | PASS; A2/B2 were fresh broker processes and resumed only their durable A/B threads with fresh proofs                                                                                                                                                                                                                                |
| 43 | Shared-store provider errors              | No SQLite, rollout, lock, corruption, or protocol error surfaced in the bounded run; stderr was not retained as a full audit stream                                                                                                                                                                                                 |
| 44 | Proof leakage                             | PASS; raw-proof file scan had `0` hits; logs held digests/presence flags only                                                                                                                                                                                                                                                       |
| 45 | Provider credential leakage               | PASS; credential content was not read/emitted/copied; only non-secret auth metadata was checked                                                                                                                                                                                                                                     |
| 46 | Audit/logging                             | PASS bounded; broker/binding/thread/generation/PID/pin/rejection/restart fields recorded without raw secrets                                                                                                                                                                                                                        |
| 47 | Lifecycle-only boundary                   | PASS; no reasoning, prompting policy, task assignment, semantic routing, or agent loop                                                                                                                                                                                                                                              |
| 48 | Compatibility risk                        | Depends on stock Codex App Server JSONL lifecycle schema and per-launch config behavior; provider/version upgrades require regression verification                                                                                                                                                                                  |
| 49 | Upstream patch comparison                 | Broker: provider-neutral and no Codex fork, but requires process-tree supervision and protocol maintenance. Provider-native context: stronger provider assertion and cleaner UX, but requires upstream patch/coordination and is provider-specific                                                                                  |
| 50 | SYN-051 assumptions invalidated           | Process-private proof alone is not thread ownership; broker pinning can close the selection gap only at a trusted lifecycle boundary                                                                                                                                                                                                |
| 51 | Minimum production change                 | Add/reuse a trusted one-process/one-thread broker, bind expected thread before MCP admission, and retain owned process-tree/liveness fencing; no implementation authorized here                                                                                                                                                     |
| 52 | `UNSAFE_FILE_AUTH` assessment             | Unchanged and still required for the current dedicated-home design; broker feasibility is not permission to weaken it                                                                                                                                                                                                               |
| 53 | Final classification                      | **PASS-B**                                                                                                                                                                                                                                                                                                                          |
| 54 | Managed acceptance unblocked in principle | No; safe isolated-auth gate remains blocked, current Synesis MCP probe was not run, and child teardown remains unresolved                                                                                                                                                                                                           |
| 55 | Remaining unknowns                        | Real Synesis admission/cross-proof probe; production process-tree supervisor; long stress; machine reboot; adversarial same-user case; full acceptance                                                                                                                                                                              |
| 56 | Planning/evidence changes                 | This record, ADR-0058, and SYN-051 durable state updates document the bounded PASS-B and hard stops                                                                                                                                                                                                                                 |
| 57 | Prototype artifacts                       | Retained at the temp path for audit; no production artifact or Synesis source was added                                                                                                                                                                                                                                             |
| 58 | Validation                                | Prototype compile PASS; provider A/B/restart/race/crash probes PASS as stated; direct catalog check PASS with exactly 10 names; deferred/fixture validators PASS; secret scan found no new credential material; `git diff --check` PASS; focused Gradle test failed before execution with `Unable to establish loopback connection` |
| 59 | Commits created                           | One documentation-only commit for this feasibility record and durable-state/ADR updates; no source/build/.synesis paths                                                                                                                                                                                                             |
| 60 | Final HEAD                                | Updated to the final amended commit hash in the completion record                                                                                                                                                                                                                                                                   |
| 61 | Final Git status                          | Expected clean `master` after the documentation commit                                                                                                                                                                                                                                                                              |
| 62 | Push                                      | No push occurred                                                                                                                                                                                                                                                                                                                    |
| 63 | Exact next action                         | Preserve `UNSAFE_FILE_AUTH`; do not implement integration or run managed acceptance. First obtain an owned process-tree supervisor and authorize a fresh real Synesis attachment probe                                                                                                                                              |

## Decision and production boundary

The broker concept is viable as a narrow provider/runtime attachment mechanism,
but the experiment does not authorize production integration. Its strongest
result is negative: a caller cannot convert broker B into thread A once B is
pinned, and a successor cannot select a different thread before attachment.
The lock is an experiment-level single-owner guard; it does not replace
Synesis's durable generation compare-and-replace fencing.

The minimum future production slice is one trusted lifecycle component that
owns the App Server process tree, obtains the exact expected thread from the
verified managed binding, verifies the provider response and readback before
MCP admission, and delegates active-runtime authority to the existing
generation fence. It must preserve strict `SessionAuthorityResolver`, avoid
latest-session fallback, and keep the MCP catalog at ten tools.

The provider-native launch-context approach would be preferable if Codex
provided a stable, authenticated assertion that binds the exact provider
thread to the exact MCP child. Until that exists, the broker is more
provider-neutral but carries stock-protocol upgrade and process-supervision
cost. Neither path changes `UNSAFE_FILE_AUTH` in this spike.

## Reproduction and integrity notes

- The successful run used `C:\Users\Liparakis\Desktop\SSMP`, already trusted by
  Codex, to avoid a disposable trust-entry mutation.
- The corrected run used launch-local selected `env_vars`; it did not rewrite
  the normal Codex configuration for worker proofs.
- No raw proof appeared in the retained report or logs. The broker's parent
  environment necessarily held the transient proof while a generation lived;
  the child received only its selected variable.
- No Synesis MCP/coordination call, `synesis init`, fixture restart, worktree
  copy, credential migration, or remote push occurred.
