# SYN-039 CP-0498 completed-review continuity diagnostic

## Scope and classification

This was a fresh bounded two-agent diagnostic after the CP-0497 status-report
correction was built into the current MCP distribution. It used no manual
request acceptance, message relay, snapshot publication, validation decision,
integration, or cleanup transition. The exact projected-action rule was
provided to both agents.

The status-report correction behaved correctly: ACCEPT responses reported the
durable WorkGroup as `ACTIVE` while another implementation intent or an
available grant remained. The diagnostic then exposed the next concrete
protocol defect: the participant whose own lane had completed was returned as
terminal `COMPLETED` before it could discover and review the still-active
sibling lane in the same WorkGroup.

## Harness and preflight

- Project: `C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0498-001`
- Harness: `C:\Users\Liparakis\Desktop\SynesisAcceptance\harness-cp0498-001`
- Project ID: `ff3603f4-67bd-4972-99d0-c075b7c10c5f`
- Seed commit: `752205d Seed Todo acceptance fixture`
- Managed baseline: `a3abcc9`
- MCP executable: repository-built
  `cli/build/platform-bundle/synesis-0.1.0-dev.local-windows-x64/bin/synesis-mcp.exe`
- MCP SHA-256: `BD8AAD11D6ABCDE946684AD4E1F0EC150A5489592E82B30F32580514E680CF6E`
- MCP protocol: `2025-06-18`
- MCP version: `0.1.0-SNAPSHOT`
- Startup commit: `bc334ac`
- Catalog: exactly ten tools
- Both independent agents used the same pinned project and reached
  `ensure_session=ready` with isolated worktrees.

## Durable participants and group

| Agent | Participant                                | Intent                                 | Claim          | Epoch |
|-------|--------------------------------------------|----------------------------------------|----------------|------:|
| A     | `agt_b7311eda-8883-3905-87f4-744ea613e098` | `6b4e35e3-fbcb-321c-8f61-5f230cee12ae` | `todo.py`      |     1 |
| B     | `agt_2bee971d-5541-3812-94b7-1b7c862a110c` | `d6d0e407-da32-3dc2-b4f5-093727a01762` | `test_todo.py` |     1 |

Shared WorkGroup: `1d24011b-99a6-37bd-b56b-ca09eab8edef`.

Requests `1e0356fa-19f9-4a77-ba96-2cedea541b3b` and
`23c2c9ff-7654-4712-885a-b188b66b2ecb` were accepted REVIEW requests. Grants
`f6a5a529-2b59-3fb9-b0a0-64a31e86ebe8` and
`38e8db15-4f00-3a26-868d-9bba437cd021` were single-use REVIEW grants at epoch

1. Snapshot `snap_ba45b50e94a95018f0994f54e9e68716` was published from B's
   `test_todo.py` lane and integrated.

## Exact progression and first blocker

1. B performed visible `test_todo.py` work and established the shared group.
2. A received `REVIEW_ADMISSION_REQUIRED` and projected
   `request_coordination(work_group_join)` with exact group and intent IDs.
3. A issued the exact request twice; B executed both exact projected owner
   acceptances and grants were issued.
4. A first called the projected grant-consumption operation without the
   projected `targetParticipant`; Synesis rejected it with
   `COORDINATION_FIELD_REQUIRED:targetParticipant`. A then re-read the
   projection and executed the exact arguments successfully. This is agent
   compliance evidence, not a production defect.
5. B received the exact projected `finish_lane`, published the immutable
   snapshot, and integration advanced the control checkout.
6. A recovered its stale workspace with exact projected `ensure_session`,
   retained its participant identity, consumed the second grant, and submitted
   two exact projected ACCEPT decisions. Both responses reported
   `workGroupStatus=ACTIVE`, matching the durable state after the status fix.
7. Final durable state was still `ACTIVE`: B was `COMPLETED`, A remained
   `ACTIVE` with its `todo.py` implementation intent, both grants remained in
   the projection, and only B's snapshot had been integrated. A's later
   `get_next_action` returned ordinary `IMPLEMENT` with no executable lifecycle
   action, so it correctly did not invent `finish_lane`.

The first production defect in this post-fix run is the completed participant
terminal path: after B's own lane completed, B could not remain available as a
review-only participant for A's active sibling lane. The early
`AgentNextActionService` `COMPLETED` return prevented the existing
`reviewActions` projection from running; completed bindings were also rejected
by the normal active-authority resolver. The fix is limited to same-WorkGroup
review-only projection/authority and does not reopen completed write claims.

## Final state and separately classified issues

- WorkGroup: `ACTIVE`
- Integrated control history: `6485dec`, then `d3b9f18`
- A's `todo.py` lane: active and not published
- B's `test_todo.py` lane: completed and integrated
- Doctor: `DEGRADED`, six warnings, reconciliation recommended, no critical
  errors; not proven causal to this lifecycle defect
- Known root `McpServerTest.setUp` Git subprocess stall: separate
  verification/infrastructure issue
- Known bootstrap Go migration failures: separate verification issue

The second ordinary acceptance was not run because this bounded diagnostic did
not reach clean WorkGroup closure.
