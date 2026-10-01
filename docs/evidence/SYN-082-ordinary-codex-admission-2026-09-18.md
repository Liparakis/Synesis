# SYN-082 — Ordinary Codex workspace admission under Windows path aliases

Date: 2026-09-18

Status: source repair and ordinary front-door acceptance PASS; the blind
two-agent acceptance is PARTIAL. Goal discovery, autonomous decomposition,
non-overlapping claims, and a review handoff were observed, but product
completion and fresh-session recovery were not reached.

## Scope and starting state

The source checkout was `C:\Users\Liparakis\Desktop\Synesis`, at starting
HEAD `813b1f1c5c3572d895444850b1e22ad1f4cc114b`. The checkout
already contained unrelated uncommitted SYN-080 and SYN-081 work. That work was
preserved. No source `.synesis` state was edited and no Synesis coordination/MCP
tool was used to manage this checkout.

The bounded defect was ordinary Codex admission on Windows when Git and the
provider binding used different spellings for the same physical path. The
repair does not change ProjectIdentity, replica semantics, provider policy,
connection authority, or the ten-tool MCP catalog.

## Source-defined admission chain

The admission owner chain is:

`get_next_action` / durable commands → `AgentSessionService` →
`WorkspaceReadinessService` → `ProviderSessionBindingService`.

`WorkspaceReadinessService` requires provider admission, an exact current
connection binding, a bound session, strict workspace verification, and a
verified workspace trust state. `ProviderSessionBindingService` owns the
registered-worktree, Git common-directory, control-checkout, containment, and
duplicate-worktree predicates. `RuntimeAuthenticator` still precedes the
strict `SessionAuthorityResolver`; the latter still resolves only the exact
current provider/session/connection binding.

The first failing predicate in the clean repro was `WORKTREE_NOT_REGISTERED`.
Git worktree porcelain reported a package-local Codex path such as:

`C:/Users/Liparakis/AppData/Local/Packages/OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/Synesis/...`

while the persisted binding used the normal spelling:

`C:\Users\Liparakis\AppData\Local\Synesis/...`

Java `toRealPath()` preserved those alias spellings, so textual/path-object
comparison rejected a physically identical worktree. `fsutil file queryfileid`
confirmed equal NTFS file identities for the worktree and its `.git` directory.
The result was a correct safety check applied to a false negative, not an
unregistered or unsafe worktree.

## Repair

The existing provider-binding seam now uses `Files.isSameFile` for physical
identity comparisons. A bounded parent walk uses the same identity predicate
for control-checkout containment. The same check is applied consistently to
registered-worktree lookup, Git common-directory validation, strict workspace
verification, trust verification, and duplicate-worktree detection.

`WORKTREE_NOT_REGISTERED` remains a fail-closed result for a genuinely missing
or distinct worktree. Missing files, dirty state, wrong project identity, and
unsupported layouts are not trusted from the current directory alone.

Stale-worktree reallocation also uses a compact recovery suffix so a legitimate
recovery path does not exceed the Windows checkout path limit. Readiness failure
responses now carry a bounded stable diagnostic such as
`WORKTREE_NOT_REGISTERED` instead of only `workspace_not_ready`.

No fallback to the latest session, PID, thread, provider conversation, or path
alone was added. `ConnectionInstanceId`, `RuntimeAuthenticator`,
`SessionAuthorityResolver`, canonical `.synesis/project.json`, and the ten-tool
catalog remain unchanged.

## Provider and refresh semantics

The current Codex installation reports `DEGRADED` / `REVIEW_REQUIRED` because
the synthetic MCP checks and configuration evidence are current but
real-agent/native interception validation is not complete. Source policy treats
`DEGRADED` as admissible when the required metadata/configuration and MCP
evidence are present. It is not the readiness blocker in this repro. After the
repair, the same healthy compatible installation reports MCP wire/catalog
compatibility as current, manual Synesis attestation as present, and workspace
trust as verified while retaining the honest review state.

`ensure_session(refresh=true)` was not a repair path for this defect. The
refresh flag is passed through the resolution request, but the pre-ready
workspace check still has to pass; the handler's bounded refresh behavior is
claim release when an admitted session explicitly supplies an empty claim set.
Turning refresh into a universal registration or authority bypass would be
incorrect. Correcting the false filesystem-identity comparison fixes the owner
of the failed predicate instead.

## Derived-cache behavior

`KnownProjectRegistry` continues to be discovery-only derived state. A focused
test deletes only its derived registry file, observes the canonical project
again, and verifies the same ProjectId and metadata bytes are reconstructed.
The real acceptance repeats this through an isolated installation-state root:
after deleting only the derived `registry.json`, a fresh MCP process rebuilt the
cache and reached `ready` / `isolated` without ProjectId rotation.

## Focused verification

All commands below used the JDK 25 loopback workaround already documented by
the repository (`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`).

| Check | Result |
| --- | --- |
| `:workspace:test --tests org.synesis.workspace.ProviderSessionBindingServiceTest` | PASS; 15 tests, including the packaged Codex alias case and stale recovery |
| `:workspace:test --tests org.synesis.workspace.discovery.KnownProjectRegistryTest --tests org.synesis.workspace.application.agent.AgentSessionServiceTest --tests org.synesis.workspace.WorkspaceMutationBrokerTest` | PASS |
| `:mcp:test --tests org.synesis.mcp.application.McpServerTest` | PASS; 37 tests |
| `:workspace:formatCheck :workspace:staticAnalysis :workspace:architectureCheck :workspace:javadoc :mcp-contract:test` | PASS |
| `git diff --check` | PASS; Git emitted only existing LF/CRLF normalization warnings |

The first workspace test attempt exposed a real Windows path-length failure in
the stale-reallocation fixture. The compact recovery suffix was added, and the
full binding test class then passed. This was a second narrow defect in the
same recovery boundary, not a relaxed readiness check.

## Real current-harness acceptance

Using the rebuilt staged distribution and the current installed Codex MCP
launcher, a fresh disposable project at
`C:\Users\Liparakis\Desktop\SynesisAdmissionSmoke-20260918` reached:

* exactly 10 tools from `tools/list`;
* `ensure_session` → `status=ready`, `workspace=isolated`;
* `get_next_action` → `status=ready` with the normal IMPLEMENT action surface;
* a second fresh MCP process → the same ProjectId, a new session/worktree, and
  `status=ready`, `workspace=isolated`;
* deletion of only isolated derived registry state → cache reconstruction and
  another successful admission.

The user project `C:\Users\Liparakis\Desktop\SomethingVeryFuckingRandom`
has canonical ProjectId `869749d8-aceb-4de3-afc7-c35d6ef02ac6`. It was also
admitted through two fresh MCP connections after the provider integration was
restored. The provider remained honestly `DEGRADED` / `REVIEW_REQUIRED`, while
both connections reached `ready` / `isolated`.

## Installed launcher deployment finding

The first blind pair was started while Codex's global configuration still
pointed at the older `C:\Users\Liparakis\AppData\Local\Synesis\bin\synesis-mcp.exe`.
That executable exposed ten tools but returned `workspace_not_ready` and
`workspace_stale` for a fresh connection, proving that the staged source fix
had not yet reached the ordinary harness front door. The normal installer
staged the current payload, but could not replace the stable launcher while
live MCP child processes held the old executable open. No raw executable copy
or source-state repair was used.

The provider-install command then registered the current immutable payload
launcher at:

`C:\Users\Liparakis\AppData\Local\Synesis\versions\0.1.0-dev.local-236099acbb2c\bin\synesis-mcp.exe`

The resulting Codex configuration points to that path. A fresh direct process
through the registered launcher exposed exactly ten tools, reached
`ensure_session=status=ready` / `workspace=isolated`, and projected the
durable issue-tracker goal through `get_next_action`. This is a deployment
activation finding, not a source admission bypass.

## Blind two-agent retry and interruption

The durable issue-tracker goal was established once through an admitted MCP
session using the normal task-intent/claim mechanism. That created WorkGroup
`7f18ae71-58ec-3b72-aa3c-25c815c33a41`; the temporary bootstrap claim was then
released. No goal text, role, file scope, or task split was added to either
agent prompt.

Exactly two independent Codex tasks were launched against the Git project
`SomethingVeryFuckingRandom` with separate worktrees and the supplied minimal
blind prompt. The prompt was 1,728 characters with SHA-256
`11168a2fb394d18cb6798b3f9ead182b60efdbf6a32f77d77c928dc615db2f1e`; both
launches used the same extracted prompt and neither received a role, product
goal, file scope, or task split.

The earlier stale-launcher pair created disposable worktrees but never
registered live participants and is excluded from the acceptance result. After
the launcher registration was corrected, the actual pair produced:

| Participant | Independent durable choice | Evidence state |
| --- | --- | --- |
| `agt_01f38937-ad73-3afc-9705-ae26cc5cfc7a` | `PATH_SUBTREE=src`; implemented the issue-tracker source | `COMPLETED`; its normal Synesis immutable snapshot advanced the project HEAD to `779b18b1b1543b9f8bca61b8534aece3bcfe2790` |
| `agt_49203a27-9701-3fcf-9af7-16e3618e5f3d` | `PATH_EXACT=app.py`, `PATH_SUBTREE=tests`, `PATH_EXACT=README.md` | `ACTIVE`; review request `36b299ec-7a8e-4d6c-9b74-2bd3f127f1af` was accepted and the surviving participant held the resulting lane grant |

The two active worktree claims were disjoint and the project-level goal was
discovered from WorkGroup `7f18ae71-58ec-3b72-aa3c-25c815c33a41`, not from the
launch prompts. No bootstrap-side message passing was used.

At `2026-09-18T21:26:17.6693649+03:00`, after Agent A had made meaningful
progress and reached `COMPLETED`, its MCP transport and directly owned Java
child process were terminated (PIDs `15700` and `20476`). Public Synesis state
kept Agent A `COMPLETED`; Agent B stayed `ACTIVE` and its review request became
`ACCEPTED`. Because Agent A was already terminal when the transport was
terminated, this does not prove an active-session interruption transition.

A fresh replacement task was then launched with the same 1,728-character
prompt. It received a fresh worktree (`session-92518bc6-6a18-41ce-89de-c4510bb36a64`)
and the fixed launcher, but by `2026-09-18T21:33:19.6653057+03:00` it had not
published a participant or intent and had made no changes. The bootstrapper
did not wake it or provide recovery context.

Verdict: PARTIAL. Synesis demonstrated durable goal discovery, two ordinary
participants, autonomous non-overlapping decomposition, a normal snapshot
integration, and a review handoff without manual task splitting. The evidence
does not support the stronger success claim: the surviving lane did not reach
product completion during the bounded observation, and the replacement did not
complete fresh-session discovery. The remaining gap is currently a Codex task
progression/launch observation, not enough evidence to call it a Synesis
protocol failure.

Checkpoint `CP-0976` records this partial result with `SYN-082` as the sole
active source task. No source commit or push was made.

## Boundaries and remaining cases

The hidden `.synesis` storage boundary and canonical identity/replica cases are
covered by SYN-080 and SYN-081 evidence and were not reimplemented here. This
slice did not broaden into a new goal-planning API, provider migration, or
distributed coordination redesign. No source commit or push was made.
