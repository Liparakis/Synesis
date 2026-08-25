# SYN-039 CP-0536 review projection fix and post-fix acceptance

Date: 2026-08-25

## Disposition

The recorded CP-0535 state exposed one concrete production projection defect:
after a reviewer had accepted a single-use REVIEW grant, the fallback branch
of `AgentNextActionService.reviewActions` could select the already-reviewed
sibling intent and re-emit `REVIEW_ADMISSION_REQUIRED`. Repeating the exact
projected request was idempotent, but the reviewer was not given a usable next
state. This contradicted the durable at-least-once inbox contract.

The narrow fix records target intents whose grant already has a validation and
excludes them from the fallback admission projection. It does not change
ownership, grants, epochs, snapshot authorization, or fail-closed mutation
checks. The deterministic regression is
`McpSyn039SliceTest.acceptedReviewDoesNotReprojectAdmissionForActiveReviewerLane`.

The fresh post-fix acceptance runs below found no new unchanged-action failure.
Their first boundary was provider/session engagement after a valid projected
continuation, so no additional production lifecycle change is justified.

## Distribution and harness boundary

Both runs used fresh disposable Git + Synesis projects, two independent
GPT-5.6 Luna Codex sessions, explicit wrappers for the repository-built MCP,
one initialized project root, ten MCP tools, distinct participants, and
`ready / isolated` sessions. The harness did not relay messages, accept
requests, publish snapshots, validate snapshots, repair ownership, or mutate
the control checkout.

- MCP executable:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64\bin\synesis-mcp.exe`
- Post-verification executable SHA-256:
  `47EEFD10AA69AE2A66FD488C1AEB6FDD990B8485AF7119E90A543D76B326A1AD`
- Reported version: `SYNESIS_VERSION=0.1.0-dev.local`
- Protocol/catalog contract: ten MCP tools; the acceptance wrappers were
  project-pinned to the same initialized root.
- Current packaged workspace library SHA-256:
  `3CDB8DA45CADC9235C1235813703239EC903FC87423C3AB44B46F1FCE2BB82A0`

## Post-fix bounded diagnostic

Fixture and raw trace:

- Project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-diagnostic-cp0536-review-gate-2026-08-25-005`
- Project ID: `2bdf7495-3cc4-489f-bc7c-2cead47c734e`
- Harness/logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-syn039-diagnostic-cp0536-review-gate-2026-08-25-005\logs`
- WorkGroup: `ee0bc4df-04ba-3f09-b151-b3baa8416231`

| Agent | Participant | Intent | Claim | Epoch |
|---|---|---|---|---:|
| A | `agt_9a8d35a6-1864-36c8-b353-b20a3b068d6b` | `5b08a138-dc9f-3d8c-ba47-de3ff541f8de` | `PATH_EXACT todo.py` | 1 |
| B | `agt_4f3ad568-842f-37ba-b505-a462021845e5` | `b763d39d-b730-3c70-8738-3660cda924ee` | `PATH_EXACT test_todo.py` | 1 |

Requests and grants:

- A -> B REVIEW request:
  `f1a659c7-1bba-4d23-93d9-32dd2acd6455`, accepted by B.
- B -> A REVIEW request:
  `6cd997c2-278b-41dc-b266-101ed567d36d`, accepted by A.
- Grant to A for B's intent:
  `36add1f0-6d26-3ba5-8964-507944076278`, epoch 1, consumed by A.
- Reciprocal grant to B for A's intent:
  `549697bd-394e-3a9c-9438-244b12a93a69`, epoch 1, remained pending for B.

The exact projection/action sequence was:

1. Both agents received ordinary `IMPLEMENT` with no executable lifecycle
   action and performed only their assigned visible work.
2. A received and executed the exact projected
   `request_coordination({kind: work_group_join, payload: {workGroupId,
   intentId: b763d39d-b730-3c70-8738-3660cda924ee, proposal}})`. Request
   `f1a659c7-1bba-4d23-93d9-32dd2acd6455` was created.
3. B received and executed the exact projected owner
   `respond_coordination` for that request. B then consumed the exact
   projected grant `36add1f0-6d26-3ba5-8964-507944076278` with intent,
   WorkGroup, target participant, and epoch unchanged.
4. B received `snapshot_publication_required` and executed the exact
   `finish_lane({summary: "Publish the completed immutable snapshot"})`.
   Snapshot `snap_3a535abb5fdf569431c3413929f5257f` was published and
   integrated. `finish_lane` returned the exact reciprocal
   `request_coordination` continuation for A's intent.
5. A received the exact review context for that snapshot, read the immutable
   `test_todo.py`, and ran the bounded command
   `python -m pytest -q`. It returned exit code 1 because the disjoint
   `todo.py` implementation was still the pass stub. A nevertheless chose a
   structured `ACCEPT`; this is agent decision evidence, not an integration
   classification defect.
6. A executed the exact projected owner response for request
   `6cd997c2-278b-41dc-b266-101ed567d36d`, then applied its visible
   `todo.py` change. The next projections repeatedly showed
   `WAIT -> get_next_action({})` with grant
   `549697bd-394e-3a9c-9438-244b12a93a69` targeted to B. B did not remain
   engaged to consume that grant, so A did not publish its snapshot.

No unchanged projected Synesis action failed. The terminal state was:

```text
Agent B = COMPLETED
Agent A = ACTIVE
WorkGroup ee0bc4df-04ba-3f09-b151-b3baa8416231 = ACTIVE
Snapshot = snap_3a535abb5fdf569431c3413929f5257f, integrated
Validation = ACCEPTED for B's snapshot
Second snapshot = not published
WorkGroup closure = not reached
```

## Post-fix ordinary acceptance probe

This was run before the bounded diagnostic as a post-fix ordinary probe; it
is not counted as the conditional second ordinary run because the bounded
diagnostic did not complete.

- Project:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-ordinary-cp0536-review-gate-2026-08-25-004`
- Project ID: `d8835a89-b567-4f79-b000-cb7bd56de7da`
- Harness/logs:
  `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-ordinary-cp0536-review-gate-2026-08-25-004\logs`
- WorkGroup: `a459e11f-6600-35e3-a15d-7bfb0a667447`
- A: `agt_c11293b9-c3ff-36b3-a115-c09568d03208`, intent
  `28978504-361e-32ac-a86a-a78dc5e91dcc`, `PATH_EXACT todo.py`, epoch 1.
- B: `agt_54a53637-d78c-39e2-90a1-029230b49244`, intent
  `19a1351d-3af0-34b2-903b-5b9cae061703`, `PATH_EXACT test_todo.py`, epoch 1.
- REVIEW request B -> A:
  `1ce7510c-e08d-4d26-aff7-c99111cc01c4`, accepted.
- REVIEW grant to B:
  `3def37ae-d0c0-3e03-8952-bdec0bb36a2e`, epoch 1, consumed exactly.

B executed the exact admission request, A executed the exact owner response,
and B consumed the exact grant. A then stopped at ordinary polling while B's
grant remained the only actionable state. No snapshot, validation, integration,
or closure occurred. No exact projected action failed.

## Final diagnostics and separate verification issues

Both fixtures ended with Doctor `DEGRADED`, six warnings, zero errors, zero
critical findings, `CLEANUP_RECOMMENDED=false`,
`RECONCILIATION_RECOMMENDED=true`, `REPAIR_AVAILABLE=true`, and
`NEXT_ACTION=prepare_repair_plan`. These warnings remain separately
classified; they did not cause a failed Synesis action in either run.

Verification after the narrow fix:

| Check | Result |
|---|---|
| Focused workspace, provider guidance, agent projection, MCP catalog, and SYN-039 regression tests | PASS |
| `McpSyn039SliceTest.acceptedReviewDoesNotReprojectAdmissionForActiveReviewerLane` | PASS in isolation and in the focused suite |
| `:cli:platformBundle --rerun-tasks --no-daemon` | PASS; 21 tasks |
| Deferred and fixture validators | PASS |
| Strict Javadocs | PASS |
| `go vet ./...` | PASS |
| `git diff --check` | PASS |
| `go test -count=1 ./...` | Three known bootstrap migration tests fail: update migrations not prepared |
| Root `check --no-daemon` | Not green: existing `:link:formatCheck` trailing whitespace and the recurring Git subprocess startup stall in `McpServerTest` |

The root Git stall remains infrastructure evidence: the test worker blocked in
`ProcessCommandRunner.startProcess` while `McpServerTest.setUp` prepared a Git
baseline. It was not hidden with a larger timeout. Historical documentation
trailing-whitespace failures were not rewritten as part of this SYN-039 slice.

## Exact next action

Run one fresh ordinary two-agent Todo acceptance against the rebuilt bundled
MCP with only complementary visible coding prompts and no lifecycle coaching.
Keep both provider sessions alive only through their normal independent
turns; do not relay, accept, publish, validate, or repair anything manually.
Preserve the first unchanged projection failure or the first required state
with no usable projection. Do not make another production lifecycle change
from provider/session disengagement alone.
