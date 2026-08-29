# SYN-039 unattended Todo baseline — 2026-08-22

## Result

The unattended two-agent acceptance failed before review could become
actionable and failed again at guarded integration. No production Synesis code
was changed by this run.

The disposable fixture was
`C:\Users\LIPARA~1\AppData\Local\Temp\syn039-unattended-todo-baseline-20260822`.
The raw Codex JSONL captures remain under its `baseline-logs` directory:
`agent-a.jsonl`, `agent-b.jsonl`, and their stderr files.

## Reproduction setup

- Fixture: fresh Git repository with `todo.py` and `test_todo.py`; initial
  commit `f0c0f99`.
- Synesis initialization: project
  `749429f7-0881-4dca-952f-0765b6b1273e`; managed baseline commit
  `7a5925f20a4cd6b0400bfdb857a74affb27b708e`.
- Provider: two ordinary `codex exec --ephemeral --json` sessions, launched
  concurrently with separate MCP connections and no manual relay or file
  assignment.
- Agent A prompt: implement a Todo completion operation, test it, publish a
  snapshot, and complete the Synesis workflow autonomously.
- Agent B prompt: discover the other agent, review/validate without taking its
  mutation ownership, and autonomously accept or reject and integrate.

## Observed durable sequence

1. Both sessions initially observed no participants, intents, pending
   coordination, or active WorkGroup.
2. Agent A called `ensure_session` with a task for `todo.py` and
   `test_todo.py`. Synesis created WorkGroup
   `7c5ab815-5f05-365b-a78b-3478440036af`, lane/intent
   `e167026d-3340-3892-b66c-0cbcb5a1c7ee`, participant
   `agt_19a81786-c8c0-3dc3-a31d-0f8335a28ad0`, and task
   `39168b91-9fc9-37d5-9703-6d06d251620e`.
3. Agent B discovered that WorkGroup and the implementer's exact claims, but
   had no review grant or actionable validation item. Its status request was
   first rejected as `COORDINATION_FIELD_NOT_ALLOWED:body`. Its attempted
   `work_group_join` was rejected as
   `COORDINATION_FIELD_REQUIRED:grantId`. A subsequent coordination status
   query returned `requests: []`; no validation request was created or exposed
   to B.
4. Agent A implemented `complete_todo` and two tests in its isolated lane.
   Its focused `python -m pytest -q` passed: `3 passed in 0.01s`.
5. Agent A called `finish_lane`. Synesis published immutable snapshot
   `snap_6162f6fd4ff4d51aadb5484609270ab3`, but returned
   `integration_pending` with `integrationState: integration_failed`.
6. The required integration retry was rejected with
   `integration_conflict`, `nextAction: request_human_help`, and
   `failures: [TESTS_FAILED]`, even when the supplied integration check
   included the passing `pytest` result. Agent A reported the lane remained
   active with claims on both files and that no manual merge or control
   checkout edit occurred.
7. Agent B terminated without editing or claiming A's files. Its final
   message recorded no published snapshot, handoff, pending validation
   request, or reviewable revision in its session and a durable blocker of
   `coordination_intent_required`.

## Reviewer collision boundary

This real run did **not** reach an overlapping reviewer claim: B was blocked
at the missing review grant before it could attempt to claim A's files. The
existing deterministic two-process test still establishes the lower-level
collision behavior: `mcp/src/test/java/org/synesis/mcp/application/TwoProcessCapabilityNegotiationProcessTest.java`
asserts that an overlapping claim and mutation are rejected with
`overlapping_claim`. SYN-039 therefore has two distinct baseline gates: the
real-agent path cannot yet authorize a reviewer, while the underlying claim
fence already rejects conflicting ownership once reached.

## Final state evidence

- Control checkout: branch `master`, HEAD `7a5925f`, no Todo completion code;
  the control files still contained only `add_todo` and its original one
  test.
- Agent A isolated worktree: modified `todo.py` and `test_todo.py`, based on
  the managed baseline; changes were not integrated.
- Agent B isolated worktree: no implementation changes; only generated
  `__pycache__/` appeared from its test attempt.
- Managed worktrees after the run: `3` (`master` plus the two Codex session
  worktrees). Coordination event files: `35`; Codex evidence records: `2`;
  session records: `4`.
- Post-run `synesis coordination status --project=<fixture>` reported
  `TASKS=0` and `OWNERSHIPS=0`, while the last session coordination status
  still reported the active WorkGroup/participant and A's claims. This is
  consistent with a detached/orphaned lifecycle rather than clean closure.
- Post-run `synesis doctor --project=<fixture> --json --verbose` was
  `DEGRADED`, with two high-confidence `stale_session_lease` warnings,
  `reconciliationRecommended=true`, and no repair available.
- The control fixture remained otherwise at its baseline Git commit; no
  accepted application state reached the control checkout.

## Acceptance mapping

| Required behavior                                                     | Baseline result                                                                                                          |
|-----------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------|
| Two sessions discover and join one WorkGroup without assistance       | **FAIL** — A created the WorkGroup; B discovered it but could not join without an unavailable `grantId`.                 |
| Reviewer sees completed immutable snapshot without mutation ownership | **FAIL** — no reviewer grant or snapshot projection became available to B.                                               |
| Structured validation accept/reject and rejection routing             | **FAIL** — no validation item was created; no accept/reject decision or handoff occurred.                                |
| Accepted work integrates into control checkout                        | **FAIL** — snapshot published, integration failed with `TESTS_FAILED`; control stayed at `7a5925f`.                      |
| WorkGroup closes and coordination artifacts are cleaned               | **FAIL** — A's lane/claims were active at stop; session worktrees and stale leases remained.                             |
| Final Doctor healthy or explicitly accepted warning-only state        | **FAIL** — Doctor was `DEGRADED` with stale leases and reconciliation recommended.                                       |
| Unattended Todo end-to-end completion                                 | **FAIL** — implementation and isolated tests passed, but review, handoff, integration, closure, and clean state did not. |

## Scope conclusion

This baseline identifies a narrow SYN-039 implementation seam: expose a
read-only/delegated review capability for a published snapshot, create and
route an actionable validation decision, then make the existing completion
path consume that decision and finish integration/cleanup without inventing a
new orchestrator, daemon, UI, Fleet system, or centralized launcher.

The next implementation action is to inspect the existing grant, snapshot,
validation, and completion state transitions and add the smallest focused
regression fixture for B's missing review authorization and A's failed
completion/integration handoff.
