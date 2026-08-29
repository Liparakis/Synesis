# SYN-039 external provider-session boundary — CP-0546

Date: 2026-08-25

## Finding

SYN-039 remains incomplete because ordinary Codex provider sessions repeatedly
terminate while Synesis still has an active WorkGroup and projects the durable
polling continuation `WAIT -> get_next_action({})`. This is not a missing
request, grant, snapshot, validation, integration, or authorization projection.

The latest exact-rule diagnostic is recorded in
`docs/evidence/syn039-diagnostic-cp0548-exact-rule-2026-08-25.md`. Its raw
Codex messages show the implementation agent finishing with a handoff-style
summary immediately after valid polling, while the reviewer selected a
non-projected test command because no snapshot had yet become available.

## Repeated evidence

The same boundary recurred in the fresh ordinary runs CP-0541 through CP-0544
and in the exact-projection diagnostic CP-0545:

- agents reached current bundled MCP, ready/isolated sessions, disjoint claims,
  and a shared WorkGroup;
- REVIEW admission, owner response, grant creation, and fail-closed grant
  validation worked;
- valid `WAIT -> get_next_action({})` continuations were projected and often
  executed repeatedly;
- provider turns nevertheless ended before the next participant-driven state
  change, leaving WorkGroup state `ACTIVE` and no terminal lifecycle result.

CP-0535 is the contrasting exact-action diagnostic: when the agents remained
engaged and obeyed every projected action, both snapshots, validation,
integration, passing control tests, and WorkGroup completion succeeded. That
proves the existing lifecycle can complete; it does not prove ordinary Codex
session autonomy.

## Synesis-side proof

The current source explicitly requires continued engagement:

- `ProjectApplicationService.java:64-65` generates project `AGENTS.md`
  instructions not to end an active session and to execute the `WAIT` polling
  continuation;
- `ProviderManualService.java:28,31` installs the same requirement in the
  Codex provider manual;
- `ProjectApplicationServiceTest` and `ProviderManualServiceTest` assert those
  instructions are present;
- `AgentNextActionServiceTest` and `McpSyn039SliceTest` assert that pending
  owner responses, grant consumption, snapshot publication, and review states
  project the exact next action and arguments;
- `AgentWorkflowReducer` maps `WAIT` to the only permitted lifecycle operation
  `get_next_action`, preserving the fail-closed boundary.

There is no unresolved server-side state transition in the traces. After the
reviewer consumes a grant, the producer would receive the snapshot-publication
projection on its next poll; the producer session has already ended and makes
no such call. Synesis cannot execute a provider's next turn after Codex has
returned its final response.

## Product-boundary conclusion

Resolving this exact condition would require either a provider/runtime behavior
that keeps the normal agent session engaged or a persistent Synesis process that
polls, launches, or drives agents after their turns. The latter is a central
launcher/daemon/orchestrator capability explicitly outside SYN-039. Adding one
speculatively would violate the preserved Synesis product model rather than
fixing a proven lifecycle defect.

Therefore no production code change is justified in the current repository
state. SYN-039 is externally blocked pending a Codex/provider runtime that
honors the existing continued-engagement contract.

## Completion audit

| Requirement                                                | Current evidence                                                  |
|------------------------------------------------------------|-------------------------------------------------------------------|
| Fresh Git + Synesis and two independent current-MCP agents | Repeated PASS, CP-0541–CP-0545                                    |
| Shared WorkGroup, claims, REVIEW admission, grants         | PASS in diagnostic and ordinary runs                              |
| Snapshot, validation, ACCEPT/REJECT, integration           | PASS only in CP-0535 exact diagnostic; not in ordinary acceptance |
| Ordinary unattended completion with only coding prompts    | NOT PROVEN; repeated provider-session termination                 |
| Green integrated control tests and clean WorkGroup closure | NOT PROVEN in ordinary acceptance                                 |
| Final cleanup and healthy Doctor                           | NOT PROVEN; fixture Doctor remains DEGRADED with six warnings     |

Known Git subprocess stalls, bootstrap migration failures, and Doctor warnings
remain separately classified. They are not causal to the provider-session
termination boundary.
