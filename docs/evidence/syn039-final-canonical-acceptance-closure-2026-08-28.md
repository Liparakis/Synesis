# SYN-039 final canonical acceptance closure

Date: 2026-08-28

## Result

`SYN-039 ACCEPTED` was established by the final canonical acceptance run and
is closed here as `DONE / ACCEPTED`. This document records closure only; it
does not reopen the acceptance or repair the separate Doctor warnings.

## Acceptance provenance

- Project: `d8989767-9cdd-486a-ad0f-65779b0152a9`
- WorkGroup: `9ab6bfc2-a552-3dd2-a098-986f4ca31f8a`
- Fixture: `C:/t/syn039-final-20260828-002`
- Harness evidence: `C:/t/syn039-final-harness-20260828-001`
- Build: `0.1.0-dev.local`, `windows-x64`, Gradle `9.6.1`, JDK `25+36-LTS`
- Build commit: `d64be66f8c2822d521ff8d341cfdc738dd5b28f5`

The official bundle was used by two independent Codex processes through
distinct MCP connections. Reviewer B was admitted first and correctly waited
in `REVIEWER_PENDING`; producer A then joined with the producer role. Claims
were disjoint: A owned `todo.py`, and B owned `test_todo.py` while targeting
A's `todo.py` work.

## Reviewed lifecycle

S1 was `snap_7b67d044139ec98cdfdd4e7eaefa445b`, epoch 1. It contained the
objective defect that invalid indexes returned `None`. B rejected S1 through
the exact projected review-validation action, and S1 never entered the final
control tree.

The same producer and authority lineage continued in the same WorkGroup with
intent version and claim epoch advancing from 1 to 2. S2 was
`snap_a647c37915538fdb2b32707ffa3dae86`, reviewed under fresh grant
`625e5c3a-44bb-387f-9663-f2bd084dbe0a`, and accepted. Earlier grant
`e403c966-1993-3018-86fd-67849b90ed61` remained consumed/obsolete and was not
reused. The final control commit contains the corrected S2 behavior only.

B received `NO_CHANGE_COMPLETION_READY` and explicitly invoked the projected
`finish_lane`; provider EOF did not complete the lane. Final coordination
status reported zero active WorkGroups, tasks, and ownerships, with no pending
review obligation or unconsumed current grant. Focused tests passed (`2
passed`) and the independent full behavior probe passed.

## Doctor caveat

`synesis doctor` remained `DEGRADED` with six warnings, zero errors, zero
critical findings, and zero mutations. Warnings were two stale
provider-session leases, command namespace/retention warnings, and provider
migration warnings. They did not leave an active SYN-039 WorkGroup, intent,
claim, pending review obligation, or accepted snapshot stranded from
integration. No repair or provider migration cleanup was performed.

The acceptance evidence is preserved at:

- `C:/t/syn039-final-harness-20260828-001/provenance.json`
- `C:/t/syn039-final-harness-20260828-001/final-verification.txt`
- `C:/t/syn039-final-harness-20260828-001/agent-a.jsonl`
- `C:/t/syn039-final-harness-20260828-001/agent-b.jsonl`

No generalized identity/provider architecture, launcher, daemon,
orchestrator, relay, or new SYN milestone was introduced.

## Closure staging boundary

Category A, accepted SYN-039 production implementation: the modified
coordination domain/application files, `mcp-contract/src/main/java/org/synesis/mcp/contract/McpToolCatalog.java`,
`mcp/src/main/java/org/synesis/mcp/application/McpProtocolHandler.java`, the
modified workspace agent/collaboration/integration/provider/task/readiness
files, and the new `NoChangeCompletion.java` and
`NoChangeCompletionEligibility.java` files.

Category B, accepted SYN-039 tests: the modified coordination and MCP tests,
the modified `AgentNextActionServiceTest`, and the new no-change,
reviewed-snapshot, rejected-continuation, and order-independence tests.

Category C/D, accepted architecture/evidence/task-state closure: ADR-0045,
ADR-0046, this evidence file, CP-0547, and the seven updated durable files
under `docs/agent` (`CURRENT.md`, `GOAL.md`, `NEXT_SESSION.md`, `SESSION_LOG.md`,
`STATE.md`, `TASKS.md`, and `TEST_MATRIX.md`).

Category E, deliberately excluded pre-existing lifecycle work: `GitProcessRunner.java`,
`ProcessCommandRunner.java`, `RepositoryPortabilityService.java`,
`ProcessCommandRunnerTest.java`, and `RepositoryPortabilityServiceTest.java`.
Their changes concern bounded process/tree-output portability and are not
required by the accepted SYN-039 lifecycle. No category F generated output or
category G ambiguous path was staged.
