# SYN-049 completion/dependency acceptance evidence — 2026-09-03

## Disposition

**PARTIAL — Defect A and Defect B are proven in focused checks and in the
fresh rebuilt two-worker run. The final whole-WorkGroup terminal acceptance is
not green.** After the scoped behavior was exercised, both worker bindings
reached a separate provider/session lifecycle stall while review/finalization
was still pending. No review/Doctor redesign was made.

The two defects remain independently testable:

* **Defect A:** completion is an explicit, call-local request; ordinary
  mutation and polling remain implementation work.
* **Defect B:** structured capability dependencies survive the real rebuilt MCP
  admission path and enter the existing capability lifecycle.

## Source and planning provenance

* Source HEAD before SYN-049: `2ad246adcd4737b65f08e2edcee2082e5329c799`.
* Planning/ADR commit: `54b6018` (`docs: scope completion and dependency
  verification`).
* Defect A implementation commit: `a789af6` (`fix: require explicit
  completion requests`).
* Source HEAD used for the clean rebuild and dependency verification:
  `a789af671e6bf168436a61b4119d305af9db407e`.
* Active task: `SYN-049` in `docs/agent/TASKS.md`.
* Separate decisions: ADR-0053 for Defect A and ADR-0054 for Defect B.

## Rebuilt artifact provenance

The clean rebuild used the existing Windows/JDK host mitigation:

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\tmp'
.\gradlew.bat clean :cli:installDist --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

The command completed successfully. The resulting distribution was:
`C:\Users\Liparakis\Desktop\Synesis\cli\build\install\synesis`.

The runtime/provider artifacts and SHA-256 values were:

| Artifact                                                      | SHA-256                                                            |
|---------------------------------------------------------------|--------------------------------------------------------------------|
| `bin\synesis`                                                 | `066fc3dfb1f73a5f6738ffc83e5430a51195a99e7368d2ac9d938cc2d80b10df` |
| `bin\synesis-mcp.exe`                                         | `0472f2c43bdf91ce2eb779710c5a379e87d00c13dd593fa63b29d548af97fb8d` |
| `bin\synesis.bat`                                             | `b8bcb137eb83659360f332c6ca6c17b328300844f83f28f82977cdec56c0670e` |
| `lib\cli-0.1.0-SNAPSHOT.jar`                                  | `db0708c56cad969c212a1d942fadc42d688e0f7f5fecf9ec0330ea34c54da6e5` |
| `lib\coordination-0.1.0-SNAPSHOT.jar`                         | `7648e6ef85b0bbe75a4ca4e98047b0504119107ff4e5e65803df4ec0afe87af6` |
| `lib\link-0.1.0-SNAPSHOT.jar`                                 | `f6b228e50ca52c0353ea18637e0f98aa3b54913ff4c928467a32a42099a9dd6a` |
| `lib\mcp-0.1.0-SNAPSHOT.jar`                                  | `98fece1495c5fda943a3007dcdd21512ca535c6f5c944c043fe9690672f08692` |
| `lib\mcp-contract-0.1.0-SNAPSHOT.jar`                         | `2b72e65fbb61ad8febffa9428b5b219d0e553add856a4c77b61333655c55e132` |
| `lib\project-record-0.1.0-SNAPSHOT.jar`                       | `50a45aefc11fc11bd0e99aa7f239be5feba22e019f2853d075c1260380dcc95f` |
| `lib\workspace-0.1.0-SNAPSHOT.jar`                            | `5921cfc5253e7ea4a4a2b9945cf744e2b52a09519bea720ced41d813b9169f07` |
| `lib\core-3.5.4.jar`                                          | `71de5d89341b5fcf5dd89da7f44e84d825d0e084cdf3ec77c9abe26b0f0ceb13` |
| `lib\netty-buffer-4.2.16.Final.jar`                           | `cc36ae9fbd0b03fe755eb4eb4424ca53b59cfd297d7fd47d49e5b1059beded6c` |
| `lib\netty-codec-base-4.2.16.Final.jar`                       | `feb410225938d9970de6b624a0c031d079804fa5cc5e1ec6e9298f16db5998a7` |
| `lib\netty-codec-classes-quic-4.2.16.Final.jar`               | `9b2856532681b109ecc1d15ba81341665fea4ba1aab78788e3d74c1aff38ca00` |
| `lib\netty-codec-native-quic-4.2.16.Final-windows-x86_64.jar` | `ebe477b5374382f0d34583907b7441dc59c52492c890f946154deff47883041d` |
| `lib\netty-codec-native-quic-4.2.16.Final.jar`                | `cf2d2a587ea7e45f130ccd2a132e70e048979bc20e25efdbf332c2f18abe5230` |
| `lib\netty-common-4.2.16.Final.jar`                           | `9825ee68a0dc4cd2b53e2f532502401b2211bad9b77e8b04882d9e64487283ff` |
| `lib\netty-handler-4.2.16.Final.jar`                          | `a259ca496da05ac1981f95cd856211f894a328056a6129e9cd70dbbd5df401f7` |
| `lib\netty-resolver-4.2.16.Final.jar`                         | `c9eca6a99036485cf1d186b4a6a595b0a54c53808319ee03924270eae04c32bb` |
| `lib\netty-transport-4.2.16.Final.jar`                        | `cfa3f654caff906653385f4b7ddaa539d795b78f5711a622482b17d2b73484c0` |
| `lib\netty-transport-native-unix-common-4.2.16.Final.jar`     | `41ca8fe192083d17917be7ddcecded4da4fc1db1d1656d03d0bcb14e2f431155` |
| `lib\picocli-4.7.7.jar`                                       | `f86e30fffd10d2b13b8caa8d4b237a7ee61f2ffccf5b1941de718b765d235bf8` |

The installed Codex provider used the absolute rebuilt paths through normal
provider installation. Its fixture metadata was
`C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260903-08\.synesis\local\providers\codex.json`;
the project `.codex/hooks.json` and user Codex configuration pointed at the
same rebuilt `synesis.bat` and `synesis-mcp.exe`. Installed hashes therefore
matched the values above exactly. No stale AppData executable was used by the
worker MCP connections.

The prior failed fixture used the different historical executable
`C:\Users\Liparakis\AppData\Local\Synesis\bin\synesis-mcp.exe` with SHA-256
`FEF3C1A867CC8570B1C8184DC0F7141AFA60DAE35F7475573A8322C94A0C4893`.
Fixture `...-07` remains untouched evidence of that provenance/configuration
failure.

## Defect A — explicit completion lifecycle

### Root cause and change

The old admission-time `WorkIntent.CompletionMode` allowed the next-action
path to treat a first publishable claimed delta as completion-eligible. That
made a worker's first meaningful mutation capable of projecting completion
before the worker had finished implementation, tests, or coordination
obligations. A multi-commit regression also exposed that snapshot
publishability had to compare against the server-recorded lane base commit,
not only `HEAD^`; the completion path now uses the intent's `baseCommit`.

The active contracts now remove `WorkIntent.CompletionMode`,
`snapshot_required`, `no_change_allowed`, and `completionMode`. The new
durable intent codec is V7 and does not contain a completion policy. V1–V6
encodings are rejected with `unsupported legacy intent format; fresh
initialization required`; they are not migrated or reinterpreted. Admission
payloads containing the removed `completionMode` are rejected.

`get_next_action` accepts the optional boolean `completionRequested`:

```json
{"completionRequested": true}
```

Missing or false means normal implementation and can return `IMPLEMENT` after
any number of mutations. True is evaluated only for that invocation; it is not
sticky and is not durably stored as a caller-selected policy. A later ordinary
`get_next_action({})` does not inherit it.

The exact server-generated `finish_lane` projection is considered only after
the existing current-state checks have passed: active provider/session
binding, exact participant and intent ownership, assigned-worktree validity,
current claim epoch, current WorkGroup version, current event revision,
server-recorded lane base/publishable or clean no-change evidence, absence of
unresolved review obligations, absence of unresolved capability obligations,
and absence of a higher-priority coordination action. The existing snapshot
and clean no-change checks remain resulting completion artifacts, not caller
selected modes.

Execution-time fencing remains in force. The projected payload carries
server-generated intent, WorkGroup, claim epoch, WorkGroup version, revision,
participant, and authority-lineage values where required; `finish_lane`
revalidates them at execution. In the real run, an initially stale projected
finish was rejected with `SNAPSHOT_COMPLETION_EVIDENCE_STALE`; refreshing the
current server-generated action then succeeded. This is positive evidence that
projection does not bypass the execution fence.

### Focused verification

The revised tests passed for the explicit multi-mutation path, V7 codec
round-trip/rejection behavior, completion mode rejection, stale completion
evidence, MCP schema/catalog, and provider guidance. The catalog remains
exactly 10 tools. The selected source checks were:

*
`:coordination:test --tests org.synesis.coordination.collaboration.NoChangeCompletionTest --tests org.synesis.coordination.domain.prediction.PredictionEventWireCompatibilityTest` —
PASS.
* `:mcp-contract:test` — PASS.
*
`:workspace:test --tests org.synesis.workspace.AgentNextActionServiceTest --tests org.synesis.workspace.application.agent.ReviewAdmissionOrderIndependenceTest --tests org.synesis.workspace.application.provider.ProviderManualServiceTest` —
PASS.

The fresh real run provided the required sequence for Worker A:

```text
domain/status mutation       → get_next_action({}) → IMPLEMENT
repository/storage mutation  → get_next_action({}) → IMPLEMENT
test mutation                 → get_next_action({}) → IMPLEMENT
completionRequested=true     → exact finish_lane projection
```

## Defect B — dependency admission/actionability

### Boundary verification

No current source-level dependency drop was found. The verified path is:

```text
McpProtocolHandler.parseTaskIntent
→ MCP admission forwarding
→ WorkspaceCollaborationService.announce
→ WorkIntent.knownDependencies
→ WORK_INTENT_ANNOUNCED
→ CollaborationCodec encode/decode
→ AgentNextActionService / CapabilityRequestProjection
→ NEEDS_CAPABILITY
→ existing capability_request lifecycle
```

The fresh hash-matched MCP admission/replay regression passed. The structured
dependency survived parsing, admission, WorkIntent construction, durable
reload, and projection. `get_next_action({})` produced the existing
`NEEDS_CAPABILITY` path. Consequently, no dependency production code was
modified and no duplicate dependency graph or admission-fabricated capability
request was added. The previous fixture is classified as stale installed
artifact or fixture provenance mismatch, not as proof of a current source
defect.

The dependency identifier was `tasktracker.domain.persistence`. It was treated
as a capability dependency, not a participant dependency. Worker B submitted a
full `CapabilityContract` through the existing server-projected
`request_coordination` path. The request became durable; Worker A returned the
server-issued capability response; publication was accepted; and B received
authorized visibility of A's actual published contract (`Task`, `TaskStatus`,
and `TaskRepository`) before implementing its consumer lane.

### Focused and real verification

* `McpServerTest.ensureSessionCarriesExplicitDependenciesIntoDurableNextAction` — PASS against the rebuilt runtime.
* Malformed dependency input coverage — PASS.
* Durable capability projection/restart and requester-continuation coverage — PASS in the focused source suite.
* Real fixture: dependency preserved, `NEEDS_CAPABILITY` projected, typed request durably created, provider
  response/publication accepted, B consumed the published contract through authorized Synesis visibility, and B's
  implementation tests passed.

## Fresh two-worker acceptance

Historical `GOONSQUAD` and all earlier fixtures remain untouched. The fresh
fixture was:

`C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260903-08`

* Bootstrap root commit: `725939544816ecbbc94b15d8df2192a00d8713e0`.
* Synesis-managed baseline: `17db31357e44b54180f1692ac5dd52ed44776cd2`.
* Synesis project identity: `48ec29de-3433-4b10-8074-fbb49a0b524c`.
* WorkGroup observed by the workers: `87ed714a-2b00-3fe5-82c0-09514cf3fd70`.
* Worker A task: domain model/status rules, persistence/repository, filtering,
  validation, and focused tests.
* Worker B task: application/service/API, reporting, integration tests, and
  README/run instructions.
* B's structured dependency: `tasktracker.domain.persistence`.

Worker A made three substantive slices and received ordinary `IMPLEMENT`
after each. A completed the capability response/publication flow and then
requested completion. The first server-projected finish payload was stale and
was rejected; A refreshed it and executed the exact current payload. A's
immutable snapshot was published/integrated and its lane reached completed
state.

Worker B was initially projected `NEEDS_CAPABILITY`, created the lawful
capability request, and then made separate application, API, reporting,
integration-test, and README/entry-point mutations. Its ordinary polls
remained `IMPLEMENT`; its task-tracker test script passed. B read the actual
published A contract through authorized Synesis reads and did not guess or copy
an interface.

The run did not reach whole-WorkGroup terminal completion. After the scoped
work was done, both existing provider bindings eventually returned the normal
recovery sequence `session_not_ready → ensure_session({}) →
coordination_intent_required`, with fresh claims required. Re-admitting either
lane would create a new intent/participant and alter the acceptance, so no
re-admission, manual identifier, state reset, worktree copy, or lifecycle
bypass was attempted. B's explicit completion request therefore remained
blocked by the unresolved review/session lifecycle and did not receive a
finish action.

The control checkout is clean at A's immutable snapshot commit
`2cfb897c9d5777a87b090093ab73b5070bf79f3e`. B's assigned Synesis worktree
contains its uncommitted lane changes by design; the operator did not commit,
copy, or integrate them. The WorkGroup remains ACTIVE with review/finalization
pending.

## Review, Doctor, and scope boundary

The compliant run reproduced a separate post-compliance lifecycle stall after
Defect A and Defect B behavior had been demonstrated. It was recorded, not
redesigned. Final Doctor output was:

```text
DOCTOR_RESULT=DEGRADED
FINDINGS=6
CRITICAL=0
ERRORS=0
CLEANUP_RECOMMENDED=false
RECONCILIATION_RECOMMENDED=true
REPAIR_AVAILABLE=false
MUTATIONS_PERFORMED=0
NEXT_ACTION=prepare_reconciliation_plan
```

Provider status for the explicit built-runtime worker connections showed MCP
confirmed working, installed manual/catalog/guidance state current, and no
provider integration gate failure. A later operator `provider status` without
the explicit built-runtime override resolved the stable AppData launcher and
reported `MCP_CONFIG_STATUS=MIGRATION_REQUIRED`; that status invocation did
not represent the worker runtime and was not used to overwrite the provenance
claim.

No unrelated production behavior was changed. No Antigravity integration was
added, the `mcp-contract` boundary was retained, and the MCP catalog stayed at
10 tools.

## Validation disposition

Passed: clean rebuilt distribution, hash identity, focused Defect A tests,
focused MCP contract/schema tests, focused workspace/provider tests, fresh
MCP dependency admission/replay, capability request/publication/visibility,
provider guidance, and the real task-tracker application test script.

Incomplete: selected process-heavy MCP/SYN-039 classes were stopped after
bounded 60-second waits at the known process-heavy Windows/JDK boundary; they
have no assertion result and are not reported as passing. The full fresh
acceptance is also incomplete because the separate review/session lifecycle
stall prevented B's final finish and WorkGroup terminalization.

No push was made because the required full-green acceptance gate was not met.
