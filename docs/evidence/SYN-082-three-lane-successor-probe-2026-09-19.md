# SYN-082 three-lane successor probes — 2026-09-19

## Boundary

These probes used disposable projects outside the Synesis source checkout.
The source repository was inspected and changed only through ordinary local
Git/filesystem authority. No source `.synesis` directory, source Synesis MCP
connection, commit, or push was used. Both harnesses used the unchanged blind
prompt and the rebuilt local MCP distribution.

## Fresh concurrent producer and successor probe

Target:
`C:\Users\Liparakis\Desktop\SynesisThreeLaneFresh-20260919`

ProjectId: `f229030f-234f-4210-8c08-b0c828ffe2a5`.

Two blind processes admitted concurrently and selected disjoint claims:

* A: `agt_094cacae-1b62-38ef-b518-369ad62964d6`, intent
  `6728d5d4-b351-3397-a820-e1663cbb452e`, `issue_tracker/` subtree.
* B: `agt_9e73d84e-b87a-308a-857e-d94d528ca861`, exact claims
  `package.json`, `src/store.js`, and `test/store.test.js`.

B published `snap_fc9b4fe2a802f987fbc7fb6ed83c9c17`. A published
`snap_f5a9542620d7cecdd7e5e50900bcfcc4`, which remained review-pending. A's
Codex process later exited while waiting; the attempted operator kill did not
find a live target tree, so this target is not counted as verified process-tree
kill evidence. After the configured grace, fresh blind D received
`continuation_available` and submitted the exact generic request for A's
intent/epoch. Synesis returned `CONTINUED` with successor intent
`8329514b-58fd-378a-86ca-c09f2dc596c0` and participant
`agt_2d952392-dcee-3454-99b4-084c8f79ce4c`; A became detached and retained no
claims. D restored the immutable lane and stopped at the existing independent
review-grant boundary.

Fresh blind E selected a disjoint CLI slice and completed its own authorized
changes, but the target did not reach review decision, integration, or
WorkGroup closure. The target therefore proves concurrent disjoint admission
and blind successor action discovery, not full hostile A/B/C convergence.

## Fresh unchanged-prompt three-agent admission probe

Target:
`C:\Users\Liparakis\Desktop\SynesisBlindReviewLoss4-20260919`

ProjectId: `bc5523ed-49d1-4a6e-b6a3-642b17bb3572`; seed commit
`676b16bc33749af9f7a4518442418376c3776c6e`; WorkGroup
`b2ae29c6-27b7-303c-be1c-30a61045f73a`.

The unchanged blind prompt was used for all three participants. A selected
the `issue_tracker/` producer lane as participant
`agt_8734ff0f-dcb5-3ce9-8e6e-35b222c08ea8`, intent
`db61a1d5-bd6b-395a-ac47-586693445bf6`. B selected the disjoint
`pyproject.toml` lane as participant `agt_5c1c693e-7966-3557-89a5-9d78a4c2815e`,
intent `f5140de7-c83e-3aa8-b0d2-7fccce92e0fc`, and published
`snap_0f9b49c2c7bf7c147e8c46c6861c72dd`. Both Codex processes exited normally;
neither was a verified operator process-tree kill.

Fresh C then entered with the same prompt as participant
`agt_1db9afee-21e9-3d11-83fd-85065f74e112`, selected the disjoint `tests/`
lane as intent `d9d2d517-8138-30f1-967c-e8c41f7ec57f`, ran `7 passed`, and
published `snap_b8f8181821d3314b371cb4244008b269`. Its final normal
`get_next_action` projection still showed A, B, and C as `ACTIVE`, with empty
`continuationCandidates` and `reviewActions`; C stopped at the server-issued
integration-pending boundary. This run therefore adds no successor or
reviewer-loss evidence. It is recorded to distinguish a clean process exit
from the verified terminal/stale process-tree path required for continuation.

## Fresh unpublished-artifact and pending-review probe

Target:
`C:\Users\Liparakis\Desktop\SynesisReviewerLossFresh-20260919`

ProjectId: `5f0e80fa-5fd4-49c7-a735-7b025873d6ad`; seed commit
`c7cc3d3542a562147ed15628c8daa0f9aed9a023`.

Blind A and B became concurrently active with disjoint claims:

* A: `agt_8d9ef7d9-bc7d-31b1-9fef-2c06770c886b`, intent
  `8c467a35-4eb6-33df-a6ee-bfaca94c14f5`, `src` plus `package.json`.
* B: `agt_ecd99add-56d8-33ae-a63e-2ae6f51894c7`, intent
  `d1bec4af-2906-362e-9856-4231cfd8d034`, exact `issue_tracker.py`.

A published `snap_23cb3df0ef8c837e7a2438b84bda4e96` and requested independent
review. B created real partial `issue_tracker.py` output but its process exited
after Synesis returned the fail-closed `WORKSPACE_STALE_DIRTY` recovery result;
there was no verified operator process-tree kill and no B snapshot. After the
configured grace, fresh blind C received the exact continuation candidate and
submitted `{intentId:d1bec4af-2906-362e-9856-4231cfd8d034, claimEpoch:1}` through
the normal generic coordination action. Synesis returned successor intent
`e421edbb-f74b-381e-a94d-18099ac5b741` at epoch 2 under participant
`agt_b465e05f-554d-35e6-b27e-a0bd3c6ab345`; B became detached and C received a
new worktree with the durable partial artifact restored.

C then stopped at `REVIEW_GRANT_PENDING`. The outstanding grant was addressed
to A's completed participant, which had not become a recovery-held reviewer.
That is an honest downstream gate, not a reviewer-loss pass: this probe did not
kill a live reviewer and did not claim a review decision or integration.

## Source and protocol result

The focused crash-prefix test and continuation service tests passed. The
probes confirm that a fresh participant can discover and execute continuation
without inheriting the predecessor identity, while review authority remains
separate and unresolved review is not bypassed. The remaining real acceptance
gaps are abrupt reviewer process-tree loss, an independent review decision
after that loss, and terminal review-enabled WorkGroup/goal convergence. The
additional unchanged-prompt three-agent probe above did not enter the stale
state and is not counted as a continuation attempt.

Harness logs are retained under:

* `C:\Users\Liparakis\Desktop\SynesisThreeLaneFreshHarness-20260919\acceptance-logs`
* `C:\Users\Liparakis\Desktop\SynesisReviewerLossFreshHarness-20260919\acceptance-logs`
* `C:\Users\Liparakis\Desktop\SynesisBlindReviewLoss4Harness-20260919\acceptance-logs`
