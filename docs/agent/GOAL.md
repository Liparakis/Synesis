# Current operator goal — 2026-09-23 process-crash continuation verification

- Task ID: SYN-082
- Status: ACTIVE. Continuation is persisted as one `LANE_CONTINUATION_ACCEPTED`
  event. A new child-JVM fault test now halts the process before the event's
  atomic move, after the durable move, and after projection but before the
  caller receives a result. Restart/retry yields either a recovery-held source
  with no continuation or one detached predecessor and one successor. The
  reviewer-before-producer regression was repaired using the durable
  continuation-grant marker; its targeted test, full ordering class, and
  focused MCP continuation tests pass.
- Verification boundary: full coordination suite passed in the serial combined
  run. The full workspace suite completed 432 tests with one reviewer-wait
  failure before the repair; rerun it after the repair. The earlier full MCP
  attempt was interrupted and has no result; rerun it. Phases 2 and 3 remain
  PARTIAL, with no review-enabled WorkGroup/goal terminal result.
- Exact next action: run the complete serial `:workspace:test` suite with the
  command recorded in `docs/agent/NEXT_SESSION.md`.

# Current operator goal — 2026-09-20 review-recovery admission and hostile boundary

- Task ID: SYN-082
- Status: ACTIVE; the source-backed recovery-held reviewer admission seam is
  implemented and focused verification is green. The fresh target
  `SynesisReviewRecoveryFixed-20260920` proved concurrent blind lanes,
  process-tree loss, ordinary reconciliation, disjoint producer successors,
  and a reviewer successor reaching the projected `SNAPSHOT_PENDING` /
  `review_validation` gate. It did not reach an independent review decision,
  reviewer publication, review integration, WorkGroup completion, or goal
  closure. The final later blind launch produced mixed/stale role evidence,
  so terminal convergence is explicitly not claimed.
- Boundary: Phase 1 crash-safe successor authority is PASS; Phase 2 hostile
  A/B/C recovery is PARTIAL; Phase 3 mandatory review/reviewer-loss recovery
  is PARTIAL/NOT REACHED. No manual target repair, source `.synesis`, source
  Synesis coordination, commit, or push was used.
- Exact next action: finish the full serial MCP regression and strict quality
  gates, then run `scripts/agent-checkpoint.ps1` and preserve the explicit
  review-enabled acceptance gap.

# Current operator goal — 2026-09-20 lawful continuation

- Task ID: SYN-082
- Status: ACTIVE; the fresh real target `SynesisSuccessorTarget-20260920`
  passed the bounded crash-safe single-successor path end to end: blind A
  crashed before completion, ordinary reconciliation completed, blind B
  discovered the server-projected continuation, acquired a new participant /
  intent under its own authority, restored the assigned artifact, passed 3
  tests, integrated the result, and closed the WorkGroup/goal. A later fresh
  participant did not reclaim A's historical lane. The source-level and
  focused verification remain PASS. Hostile concurrent multi-lane recovery is
  PARTIAL, and mandatory independent review/reviewer-loss terminal convergence
  remains PARTIAL/NOT REACHED.
- User authorization: investigate and repair the supplied fresh-project,
  cache-loss, and blind interruption lifecycle failures in this source
  checkout, preserve the existing security/authority boundaries and ten-tool
  MCP contract, run real disposable acceptance after focused verification,
  and do not commit or push. The Synesis source checkout is managed directly
  with ordinary Git/human authority; do not use Synesis coordination/MCP or
  edit its `.synesis` state.
- Exact next action: run `powershell -ExecutionPolicy Bypass -File
  scripts/agent-checkpoint.ps1`, then preserve the fresh-target evidence and
  explicit review-enabled acceptance gap. The clean review target now also
  requires a source-backed review of recovery-held reviewer admission before
  any further blind launch; do not bypass the retained reviewer claim.

# Current operator goal — 2026-09-19 overnight continuation

- Task ID: SYN-082
- Status: ACTIVE; source repair, lawful recovery continuation, artifact restore, targeted real completion, and WorkGroup/goal closure PASS for the bounded single-successor case. The current user-authorized overnight objective continues SYN-082 through crash-safe successor semantics, hostile concurrent A/B/C multi-lane recovery, and mandatory independent-review/reviewer-loss recovery. Source-level successor-scoped publication, reviewer-grant succession, producer pending-review retargeting, and continuation-grant self-review rejection are verified. The current-build hostile target proved process-tree loss and three exact successor continuations; the extended unchanged-prompt target recovered a failed integration with an epoch-2 successor and integrated a later 42-test correction. All real snapshots still projected `reviewRequired:false`, so mandatory review, reviewer loss, and review-enabled closure remain NOT REACHED.
- User authorization: investigate and repair the supplied fresh-project, cache-loss, and blind interruption lifecycle failures in this source checkout, preserve the existing security/authority boundaries and ten-tool MCP contract, run real disposable acceptance after focused verification, and do not commit or push. The Synesis source checkout is managed directly with ordinary Git/human authority; do not use Synesis coordination/MCP or edit its `.synesis` state.
- Boundary: the Windows path-alias false negative is repaired at the existing physical-identity seam. The lifecycle follow-up adds only the missing ordinary-MCP admission trigger for the existing bounded reconciliation plan and stale-plan terminal revalidation. The explicitly activated ADR-0083 continuation slice may expose recovery-held eligibility through `get_next_action` and execute an explicit successor request through the existing generic coordination action. The overnight extension may add crash-prefix/replay proof, concurrent multi-lane recovery fencing, and reviewer-loss continuation through existing review semantics; it does not weaken timeouts or claims, auto-assign successors, auto-review, add tools, bypass review/integration, or fabricate authority.
- Evidence target: `docs/evidence/SYN-082-lifecycle-recovery-2026-09-18.md`, `docs/evidence/SYN-082-lawful-recovery-continuation-2026-09-19.md`, `docs/evidence/SYN-082-overnight-successor-recovery-2026-09-19.md`, ADR-0083, and ADR-0085; checkpoint required.
- Exact next action: run `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`, then use the rebuilt current distribution in a fresh disposable target to exercise one uniquely selected producer revision through the existing review-request path before testing reviewer loss. Keep the blind producer prompt unchanged; do not infer review policy from README prose, fabricate a claim, or auto-assign a reviewer.

- Task ID: SYN-081
- Status: DONE; make `.synesis/` the invisible, canonical home for Synesis project metadata
- User authorization: pursue the supplied filesystem-boundary and UX-hygiene goal in the existing Synesis source checkout, preserving visible user/agent project files, the current Link/MCP/security boundaries, and no push.
- Architecture: ADR-0081; one project-storage seam owns `.synesis` creation/validation and best-effort Windows Hidden presentation, while identity, authorization, Git, and registry semantics remain independent.
- Exact next action: run `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1` before any future task; preserve the verified uncommitted slice and do not publish without explicit authorization.
- Evidence target: `docs/evidence/SYN-081-invisible-project-metadata-2026-09-18.md`; no commit or push.

# 2026-09-18 — Completed predecessor

- Task ID: SYN-080
- Status: DONE; bounded universal portable project identity and replica model complete
- Evidence: `docs/evidence/SYN-080-portable-project-identity-2026-09-18.md`; no commit or push.

# 2026-09-14 — Previous operator goal

- Task ID: SYN-079 (existing task; no new task created)
- Status: COMPLETE; operator-scoped installer roadmap complete. Native fixture verification and package freshness checks passed after the result-screen cleanup, uninstall layout, gradient and smooth-rendering follow-ups.
- User authorization: confirmed implementation of the attached installer brief and four mockups; directed use of existing project components, no new tasks, and no Synesis tooling.
- Current brief supersedes older UI-only confirmation-page and copy requirements below: Manage installation -> location/options -> truthful progress/log -> action-specific summary. Preserve operation ordering, security, filesystem/registry, metadata and project/workspace semantics.
- Evidence: docs/evidence/SYN-079-uninstall-template-parity-2026-09-14.md, current continuation section. Historical goals follow.

# 2026-09-14 — SYN-070 Windows uninstall page cleanup

- Status: COMPLETE FOR CURRENT SCOPE; presentation-only cleanup closed 2026-09-14
- Task ID: SYN-070

## Goal boundary

Correct only the remaining visible uninstall-page defects shown in the operator capture: stale hidden text above the options card, unnecessary helper-copy wrapping, title-bar product-mark fallback, and undersized compact path/action typography. Preserve all uninstall behavior and every non-uninstall page.

## Acceptance criteria

1. No clipped or duplicated text is visible above the uninstall card.
2. Helper text fits as a clean single line at the default compact scale.
3. The uninstall surface remains logo-free/banner-free, with a readable path field and balanced Back/Uninstall/Cancel controls.
4. Focused tests, vet, Windows GUI compilation, and diff hygiene pass; no installer action is executed.

## Completion evidence

The bounded cleanup is complete in `bootstrap/installer_menu_windows.go`. The
hidden install heading is cleared before page switches, the uninstall page
suppresses the class-level title-bar icon fallback, the path uses the body face,
helper text fits as one line, and the Back/Uninstall/Cancel action row has
balanced compact proportions. `go test ./...`, `go vet .`, Windows test
compilation, Windows GUI compilation, and `git diff --check` passed. The
canonical single-file output was locked by the already-running prior installer,
so `cli/build/distributions/synesis-windows-x64-uninstall-polished.exe` was
assembled from the fresh native launcher and bundle archive and its footer was
validated. No installer action was executed and no behavior or data boundary
changed. Evidence: `CP-0950` and
`docs/evidence/SYN-070-windows-uninstall-page-cleanup-2026-09-14.md`.

# 2026-09-14 — SYN-069 Windows uninstall page visual parity

- Status: COMPLETE FOR CURRENT SCOPE; presentation-only implementation closed 2026-09-14
- Task ID: SYN-069

## Goal boundary

Apply the approved dark, logo-free, banner-free uninstall mockup to the existing native Windows setup wizard's uninstall page. Preserve the existing native controls, navigation, metadata toggle, uninstall execution, project/workspace/repository preservation, and every non-uninstall page.

## Acceptance criteria

1. The uninstall page uses a balanced full-width dark installer composition without the global brand/logo treatment or footer warning banner.
2. The existing title, explanatory copy, uninstall-options grouping, installation path, metadata checkbox/helper text, and Back/Uninstall/Cancel actions remain present and legible.
3. Navigation and uninstall operation code remain behaviorally unchanged.
4. Focused bootstrap tests, vet, Windows GUI compilation, and diff hygiene pass; no installer action is executed.

## Completion evidence

The native uninstall page was updated in `bootstrap/installer_menu_windows.go` with the full-width dark options surface, polished path presentation, custom metadata checkbox/helper layout, quiet logo-free/banner-free chrome, and requested bottom actions. The existing event/navigation and operation code remains the behavior boundary. Focused tests, vet, Windows test compilation, Windows GUI compilation, and diff hygiene passed. Evidence: `CP-0949` and `docs/evidence/SYN-069-windows-uninstall-page-visual-parity-2026-09-14.md`. A native screenshot was not claimed because the Windows visual bridge was unavailable; no installer operation was executed.

# 2026-09-13 — SL-D-040 Checkpoint 3A Docker process/network isolation lab

- Status: ACTIVE; bounded Docker implementation and two-cycle evidence
  complete; native Linux/KVM/physical traversal remains unavailable
- Task ID: SL-D-040, Checkpoint 3A

## Goal boundary

Build a repeatable local Docker lab for six independent Synesis JVMs on the
current Windows/Docker Desktop Linux engine. The lab must retain the existing
signed identity, candidate, membership, and authenticated Link boundaries while
exercising the deterministic N=6 topology, SLB2 first contact, peer transit,
hard process/container failures, changed endpoints, partitions, simultaneous
reconnect, bounded network impairment, repeated cycles, topology evidence, and
cleanup.

The host is not a freshly installed native Linux machine: no Ubuntu WSL distro,
SSH target, or usable KVM exposure exists. Docker-on-WSL2 is therefore the
available local isolation substrate, and the result must be reported as such.
The shipped CLI has no membership authority and the production runtime owner
does not yet wire automatic reconciliation/direct descriptor reconnect. The
test-only lab supplied a legitimate signed snapshot and existing authenticated
Link/overlay classes. Those facts remain acceptance boundaries, not reasons to
invent an unsigned allowlist, fake peer, relay, or source-checkout coordination
state. Two cycles passed at
`D:\SynesisDocker\p2p-lab-3a\run-20260913-181647`.

## Required evidence and exclusions

The final evidence must identify the exact build/image, node identities,
verified membership digest, 12-edge/degree-4 graph, authenticated transit,
failure and recovery transitions, impairment parameters, repeat-cycle results,
and cleanup. Native Linux/KVM, external machines, physical NAT/Internet
traversal, public daemons, credentials, remote publish, push, and source
`.synesis` state remain excluded.

## Exact next action

Review `docs/agent/checkpoints/CP-0941.md` and the verified Docker evidence
before any future extension, then stop at the documented Windows/Docker
boundary unless a native Linux/KVM host is explicitly supplied.

# 2026-09-13 — SL-D-032 SLB2 minimization refinement

- Status: COMPLETE FOR CURRENT SCOPE; roadmap complete for this activated
  slice; SLB1 remains the preserved decode-compatible baseline
- Task ID: SL-D-032

## Goal boundary

Complete the SL-D-032 minimization refinement: retain the normal one-way,
versioned, signed first-contact architecture while reducing the human-facing
token to the irreducible secure pre-auth boundary. The existing `SLB1` remains
decode-compatible; the normal encoder may use a clean `SLB2` revision. Use
one-way direct
connection when the signed endpoint hint is reachable; keep the existing
operator-mediated SLO1/SLA2 path as a compatibility and coordinated-traversal
fallback. After authenticated control readiness, exchange the complete signed
candidate descriptors in-band through the existing bounded application stream.

The task must preserve expected identity checks, single-use admission,
expiry/replay protection, project/membership boundaries, direct-only
connectivity, and legacy decoder compatibility. It must not add a Synesis
service, rendezvous, relay, TOFU, overlay redesign, session resumption, or
universal NAT claim.

## Acceptance criteria

1. Source trace and ADR-0078 identify the current SLO1/SLA2 fields, identity
   proof inputs, endpoint selection, admission state, CLI path, and deep-link
   boundaries.
2. The compact parser is strict, canonical, bounded, versioned, signed, and
   rejects tampering, expiry, replay, wrong identity, wrong protocol, and
   malformed endpoints without allocating unbounded state.
3. The compact encoder carries no full candidate descriptor; measured URI and
   raw-payload sizes are recorded for IPv4 and IPv6 fixtures.
4. A compact loopback host/join completes one authenticated Link session with
   no answer link, and both full signed descriptors are exchanged only after
   `CONTROL_READY`.
5. Legacy SLO1/SLA2 encode/decode and the existing mediated lifecycle remain
   covered; no physical NAT or Internet reachability claim is made.
6. CLI output and supported deep-link adapters accept the compact URI without
   leaking keys, descriptors, or capability material in diagnostics.
7. Focused Link/CLI tests, formatting, static analysis, Javadocs, and the
   documented checkpoint/evidence updates pass; no remote repository is
   modified.
8. The exact SLB1 byte budget and an SLB1-vs-SLB2 threat comparison explain
   every retained pre-auth byte, the capability entropy floor, and the
   resulting IPv4/IPv6 URI-size regression budget.

## Prior closure evidence

Criteria 1–7 are satisfied for the bounded local scope. `:link:check` passed
with 107 tests; focused workspace control-plane and packaged CLI compact
onboarding/sync tests passed; workspace and CLI non-test gates passed; and the
measured sizes and negative cases are recorded in
`docs/evidence/sl-d-032-compact-bootstrap-2026-09-13.md`. Full CLI and broad
workspace-suite results retain the unrelated failures documented in
`docs/agent/CURRENT.md`. Durable checkpoint `CP-0939.md` was created after the
deferred-register validator passed. No remote repository was changed, and no
`.synesis` state was created in this source checkout.

The active minimization refinement is complete. Normal SLB2 output is 120/132
raw bytes and 180/196 URI characters for IPv4/IPv6; the signed full Ed25519
key, endpoint, freshness, and host signature remain because they are the
self-contained non-TOFU trust boundary. The 80-120 character target is below
that secure primitive floor. A future reduction requires a separately
authorized protocol or identity change.

# 2026-09-13 — SL-D-040 Checkpoint 2 bounded multi-node overlay convergence

## Task boundary

Continue from `f26fb03c` (Heal overlay adjacency after Link loss) and verify
the next existing `SL-D-040` overlay milestone. Checkpoint 1 established local
authenticated adjacency recovery; this checkpoint establishes that the
existing bounded topology policy is realized by native QUIC sessions, supports
opaque peer transit, withdraws routes after meaningful physical loss, and
returns to a stable graph after rejoin.

- Task ID: SL-D-040
- Checkpoint: 2
- Status: COMPLETE FOR CURRENT SCOPE; Checkpoint 2 is complete for its current scope
- Authority: signed membership remains the membership source; physical loss
  never revokes a member
- Exclusions: VM/NAT/Internet traversal, long soak or randomized chaos,
  session resumption, QUIC path migration, installer/daemon/UI redesign,
  Claude integration, hosted relay, and source `.synesis` mutation

## Exact next action

After the local commit, begin Checkpoint 3 by defining the isolated local-VM
topology/churn matrix and environment prerequisites. Do not implement VM
testing or broaden production overlay behavior in this checkpoint.

## Checkpoint 2 result

Source inspection derives full mesh through three members and a sorted circular
`+/-1`/`+/-2` policy above that threshold, with maximum intended degree four.
Therefore six members is the smallest population with an intentionally
non-direct valid pair. Deterministic tests and a native six-endpoint QUIC
acceptance prove the target graph, peer transit, E2E opaque transit, abrupt
transit loss, local route withdrawal, automatic bounded reconciliation,
alternate delivery, and same-identity rejoin. The acceptance is one JVM with
six native QUIC endpoints; the existing separate-JVM Link regression remains
the process-boundary evidence. No UI/SSE overlay projection exists to verify,
so no UI change was made.

# SYN-057 deterministic multi-project runtime selection and deep-link routing — activated 2026-09-11

## Task boundary

SYN-057 is explicitly authorized. SYN-056 is complete for its current scope
and is not reopened. The goal is deterministic local routing across known
project runtimes without moving coordination, Link, or membership authority
into the daemon.

The discovery and architecture slices reconstruct the identity hierarchy,
trust classification, daemon project/runtime discovery, endpoint liveness,
and pre-dispatch information. The operator approved the versioned targeting
contract. The current slice implements protocol v2 serialization/binding,
deterministic SLA2 target routing, and bounded ephemeral SLO1 selection.

## Exact next action

ADR-0074 and
`docs/evidence/SYN-057-routing-protocol-design-2026-09-11.md` record the
approved stable UUID, ephemeral local SLO1 selection, and no-runtime-restart-
continuity decisions. Routing evidence is recorded in
`docs/evidence/SYN-057-routing-implementation-2026-09-11.md`. The next action
is normal Gradle verification after the local loopback startup failure is
resolved; preserve v1 and all runtime authority boundaries.

## 2026-09-11 — prior SYN-056 installed local daemon shell goal

## 2026-09-11 final post-CONNECTED semantics audit

The authoritative daemon-shell criteria are complete for current scope. Fresh
r4/r5 evidence proves installed browser bootstrap, browser-mediated SLO1,
daemon JOIN/ANSWER, Complete Join, distinct authenticated peers, `CONNECTED`,
live liveness, and terminal replay rejection. The CLI overlay remaining
`UNCONFIGURED` / `UNKNOWN` is intentional: ADR-0068 requires an explicitly
supplied verified signed membership snapshot, and the CLI has no membership
authority source or shipped approval/configuration action. `WORK_RESULT=OK`
is demo application work only; Link onboarding does not mutate coordination
events or `headSequence`. Classification E: membership authority is outside
SYN-056 and remains deferred. No r6 run or production repair is justified.

This historical SYN-056 goal is superseded by the explicitly authorized
SYN-057 task above. Membership authority remains outside both this routing
slice and the completed SYN-056 scope.

## 2026-09-11 distinct-peer acceptance attempt

The fresh D:-backed A/B setup and Linux-targeted installed distribution passed
through distinct persisted identities and one healthy daemon-managed runtime on
each side. Docker Engine storage/API failure blocked the browser bootstrap
before SLO1, so SYN-056 remains ACTIVE / PARTIAL and no peer completion claim
is made.

## 2026-09-11 HOST-A invitation result

The installed HOST-A invitation boundary is proven: a corrected Linux-targeted
distribution creates and returns a real SLO1 URI through the shipped browser.
The Docker hang was caused by a platform-native packaging mismatch, not a peer
wait, candidate deadlock, or response handoff failure. SYN-056 remains ACTIVE /
PARTIAL until the separate distinct-peer JOIN, ANSWER, durable completion, and
replay acceptance is proven.

Build the smallest production-correct per-user Synesis local daemon as a thin
installation shell around existing project runtimes. Keep project coordination,
browser security, Link onboarding, and `KnownProjectRegistry` at their existing
authority boundaries. Implement only singleton lifecycle, authenticated local
IPC, runtime launch/reuse and truthful health discovery, secure browser opening,
the shared `synesis ui` path, and a bounded deep-link handler entrypoint.

- Status: ACTIVE; architecture recorded in ADR-0073.
- Current slice: existing production CONNECT is now exposed through the
  smallest runtime/browser seam: pending-join metadata in the authenticated
  snapshot and a `Complete Join` action that calls the existing client command.
  A fresh installed run proves the action is projected after daemon JOIN and
  invokes the existing runtime route; clean installed self-runtime
  CONNECT/ANSWER completion still times out before durable mutation.
- Status remains ACTIVE / PARTIAL. No daemon CONNECT IPC or daemon-owned Link
  state was added. Trace evidence shows the same-runtime case reaches both
  Link operations but fails closed when both roles use one persisted node
  identity: the responder reports `IDENTITY_PROOF_INVALID`, then candidate
  deadlines expire. No durable transition occurs.
- Exact next action: preserve this identity boundary and, if positive installed
  completion is required, exercise two distinct durable peer runtimes with
  concurrent host ANSWER and join CONNECT. Do not add bootstrap/session
  disclosure, operation copying, daemon-owned invitation authority, or weaker
  Link checks.
- Exclusions: second HTTP control plane, global project authority, filesystem
  crawling, peer aggregation, privileged service, installer redesign, OS URI
  registration, and duplicate SLO1/SLA2 validation.

# SYN-055 final browser artistic-direction pass — 2026-09-10

Polish the six current browser screens using the latest pasted brief as visual
authority and current code as semantic authority. Preserve product structure,
available actions, real data, and authenticated backend boundaries.

- Status: COMPLETE FOR CURRENT SCOPE; superseded by SYN-056.
- Result: neutral colors, comfortable typography, restrained semantic color,
  unified project header and tabs, quiet runtime dot/text, consistent controls,
  1480px simple-page and 1760px dense-page caps, responsive technical tables.
- PASS: frontend gates, local distribution, real authenticated six-screen
  screenshots at 2560x1440, 1920x1080, 1280x800, populated-state stress check,
  automated accessibility scans and packaged asset identity.
- Evidence: `docs/evidence/SYN-055-art-direction-2026-09-10.md`.
- Supersedes earlier no-indicator and uncapped-width requests; no new
  product behavior, architecture, authority, endpoint, or fixture is introduced.
- Exact next action: review the completed diff with `git diff -- web-ui/src/app/App.tsx web-ui/src/styles.css docs/evidence/SYN-055-art-direction-2026-09-10.md`.

# SYN-054 persistent known-project discovery — activated 2026-09-09

Add the smallest event-driven local discovery index needed for Synesis to
truthfully remember initialized projects it has legitimately encountered.
Keep project runtimes authoritative for agents, WorkGroups, claims, tasks,
capabilities, peers, overlay, diagnostics, and coordination state. Reuse the
existing project identity, CLI/UI, MCP startup, and application-state seams;
do not add a daemon, filesystem crawler, new MCP tool, global coordination
authority, runtime auto-start, or project relocation workflow.

- Status: COMPLETE FOR CURRENT SCOPE; implementation and lifecycle evidence
  complete; unrelated baseline regressions documented
- Exact next action: preserve the verified state; no further product-code work
  is required for this goal. Any commit or publication is separately
  authorized.
- Architecture basis: current-source review and ADR-0072.
- Boundary: known-project metadata is persistent local index data; live status
  is process-local evidence only and is never persisted.
- Required fields: project UUID, validated remembered path, project creation
  time, first observation time, and last observation time. No authoritative
  display name exists in current project metadata, so no name is persisted.
- Required behavior: event-driven observation from init, UI/runtime startup,
  and valid MCP attachment; idempotent re-observation; restart persistence;
  unavailable-path and identity-mismatch reporting; current live-project
  reporting only when the serving process owns the live runtime.
- Scope exclusions: no recursive disk scan, project deletion or relocation,
  automatic runtime startup, aggregated project-local state, multi-project
  SSE fanout, remote exposure, or MCP catalog change.

# SYN-009E Synesis maximum-protection commercial release profile — activated 2026-09-09

Build and verify a release-only maximum-protection profile for Synesis using
the existing `SYN-009*` distribution, bootstrap, signing, and stable-install
lineage. Keep the ordinary developer build readable and debuggable. The
protected profile must distinguish protection-lite from the requested
commercial maximum profile and must classify every Seven Rings capability with
artifact and installed-runtime evidence rather than marketing terminology.

- Status: ROADMAP COMPLETE FOR PROTECTION-LITE SCOPE; commercial maximum protection DEFERRED / EXTERNAL DEPENDENCY
- Baseline: activation checkout `8a5d0901b4859c9a504f7fc7009def23177f1d43`
  on `master`; the completed local release/UI series is being closed and
  published with the authorized final push. `CP-0758.md` is preserved as the
  completed UI checkpoint and is included in the closeout.
- Last implementation checkpoint: `12315694` at `CP-0820`; the latest
  availability evidence is `18da2f4b` at `CP-0807`, and the candidate-capability
  review is `4b2836a2` at `CP-0810`; the working copy also
  contains a repository-wide formatter/implementation rewrite. The final
  closeout audits and includes legitimate repository changes rather than
  silently discarding them.
- Lineage: `SYN-009C` and `SYN-009D` are the existing distribution/install
  foundations; this task extends them rather than creating a parallel release
  system. `SYN-010B` remains a separate historical aggregation task.
- Planning basis: the user-provided maximum-protection goal, current-source
  and bundle investigation, ADR-0070, and ADR-0071's external adapter/signing
  contract.
- Exact next action: none for the completed free-protection scope. Future
  commercial maximum work resumes only after an installed, licensed,
  version-pinned protector, reviewed adapter/configuration, and release
  signing authority are supplied; until then the maximum gate remains
  fail-closed and no replacement implementation is permitted.
- Required Seven Rings: Ring 1 control-flow obfuscation, Ring 2 genuine code
  virtualization, Ring 3 string/constant encryption, Ring 4 anti-VM and
  analysis-environment detection, Ring 5 protected payload/packing, Ring 6
  anti-debug/anti-instrumentation, and Ring 7 signed integrity, release
  diversification, and private retrace. Shrink/strip/sanitize,
  symbol/package reduction, native hardening, and stable-contract compatibility
  remain supporting distribution gates rather than ring substitutes.
- Honest boundary: ProGuard 7.10.0 transformation is only `PROTECTION_LITE`;
  the provider-agnostic CLI/relay maximum adapter and existing bootstrap-
  signer seam are release plumbing, not ring evidence. Commercial Seven Ring
  evidence remains blocked or partial until a licensed protector is installed
  and its shipped output is exercised. The independent acceptance harness rejects lite artifacts and
  leaves relay authentication/forwarding explicitly open until real protected
  socket evidence exists.
- Developer boundary: no release-only protection transforms, hostile
  anti-analysis behavior, packing, or virtualization may enter the normal
  developer build or customer-visible runtime toggle surface.
- Release boundary: no production signing keys, commercial licenses, private
  seeds, mappings, or native symbols may be committed or shipped. The current
  closeout push is authorized; future commercial release signing remains
  private and injected.

# SYN-053 first installed Synesis browser UI — completed current scope — 2026-09-08

Build and integrate the first real Synesis browser UI as a first-class,
install-bundled repository module over the existing local control plane. The
browser must render actual local project, agent, WorkGroup, peer, network, and
diagnostic state; use the existing authenticated snapshot/SSE/onboarding
contract; remain truthful for empty and `UNCONFIGURED` states; and require no
Node runtime or development server after installation.

- Status: COMPLETE FOR CURRENT LOCAL SCOPE; checkpoint `CP-0758.md` recorded
- Baseline: backend commit `2be88cf` was pushed to `origin/master` before UI
  work. UI commits remain local until separately authorized.
- Planning basis: user-provided browser-UI goal, current-source investigation,
  and ADR-0069.
- Exact next action: preserve the verified local UI commits and do not push UI
  commits without explicit authorization; release-hardening work proceeds under
  `SYN-009E`.
- Security boundary: preserve loopback-only binding, exact Host/Origin checks,
  one-time fragment bootstrap, session/CSRF headers, explicit DTOs, bounded
  SSE, and no secret exposure.
- Scope exclusions: no Electron/native GUI, cloud dashboard, SaaS login,
  telemetry, external CDN, browser P2P, OS URI registration, fake runtime
  data, Link/overlay redesign, WorkGroup redesign, or automatic UI push.

## SYN-052 local control-plane backend — activated 2026-09-08

Build the local Synesis control-plane backend described by the user-provided
brief. The surface is a thin adapter over authoritative project, coordination,
provider, Link, overlay, relay, and diagnostic state. It exposes versioned
localhost HTTP queries and supported commands plus bounded SSE updates for a
future browser UI; it does not build that UI.

- Status: COMPLETE FOR CURRENT SCOPE / implementation foundation and focused
  real-CLI acceptance complete; broader acceptance remains separately bounded
- Exact next action: review and commit the verified local slice; do not claim live overlay/relay acceptance without a real long-lived owner.
- Planning basis: current-source re-investigation and ADR-0067.
- Security boundary: loopback-only bind, exact Host/Origin checks, one-time bootstrap, session plus CSRF for mutations, bounded bodies/queues, explicit DTOs, and no secret exposure.
- Scope exclusions: no frontend, public/cloud API, generic proxy, arbitrary filesystem access, new database/broker, OS URI handler, reconnect/path migration, durable browser event replay, or push.

## SL-D-040 distributed project overlay — activated 2026-09-08

Extend the existing Synesis Link implementation into a bounded project overlay
with authenticated membership, opaque end-to-end logical envelopes, direct and
peer-transit routing, deterministic topology, and an optional self-hosted live
organization relay. Preserve `PeerSession` as a physical adjacency. Do not
build the browser UI, HTTP control plane, generic proxy, durable mailbox, or
transparent reconnect in this task.

- Status: COMPLETE FOR CURRENT SCOPE; physical traversal remains separately scoped
- Exact next action: preserve the passing configured/local acceptance and keep
  controlled-NAT or physical traversal under the separately scoped `SL-D-035`
  evidence gate; do not broaden this task into reconnect or path migration.
- Planning basis: user-provided distributed-Link goal, current-source re-investigation, and proposed `docs/adr/0066-distributed-project-overlay-proposal.md`.
- Existing `SL-D-035` direct traversal work is paused at its separate controlled-topology evidence gate; `SL-D-036` reconnect remains deferred.

## SL-D-035 coordinated UDP/QUIC hole punching — activated 2026-09-08

Extend the existing Synesis Link implementation so two independent nodes can
discover bounded endpoint candidates, coordinate direct UDP/QUIC traversal,
authenticate the intended durable peer, and exchange Link traffic directly
when the topology permits. Preserve the existing Link architecture and fail
closed on unsupported NAT/firewall conditions. No relay/TURN fallback,
distributed Synesis state, or transparent reconnect is included in this task.

- Status: ACTIVE / implementation and network-evidence gate
- Exact next action: run controlled-NAT validation where practical and record the exact limitation otherwise; do not claim physical P2P from localhost evidence.
- Existing planning basis: `SL-D-031`, `SL-D-032`, and `SL-D-035` in `docs/agent/DEFERRED.md`.

## SYN-049 fresh unattended two-worker acceptance — runs #55–#58 — 2026-09-06

Complete one fresh real two-worker acceptance through the supported Synesis
and managed Codex paths. Runs #55–#58 remain **PARTIAL**: #56 proved the
corrected same-Thread-B post-review continuation, but B produced no
publishable downstream snapshot; #57–#58 reached trusted turn completion
without claimed mutation or a capability request, and #58 had no run-scoped
Synesis MCP child. B wake, downstream work, final review/finish, and terminal
WorkGroup state remain unproven.

- Exact next action: diagnose the run-scoped Codex App-Server-to-Synesis-MCP
  tool-admission boundary read-only before any new fresh target.
- Do not reopen SYN-051, reuse historical fixtures, start a replacement, or
  make production changes without a directly reproduced material defect.

## SYN-051 two-worker/restart acceptance — 2026-09-05

The fresh two-worker/restart runtime path is **PASS-A**. Independent A/B
bindings, participants, WorkIntents, claims, proofs, managed Jobs, provider
threads, trusted turns, generation-1 death evidence, exact generation-2
resumes, and post-resume claim-aligned turns all passed. SYN-049 remains
**PARTIAL** because its broader integration/workgroup acceptance was not run.

- Exact next action: preserve the SYN-051 PASS-A evidence and do not reopen
  the accepted runtime or run full SYN-049 acceptance without a new explicit
  task.

## SYN-051 build-JVM compatibility and provenance — 2026-09-05

The process-local `GRADLE_OPTS` carrier passed Gradle startup, task execution,
clean build, and `:cli:installDist` with exact produced-to-installed hashes.
Focused workspace and MCP tests stalled after compilation without assertions
and remain incomplete. No Worker A or managed runtime was created. SYN-051
remains ACTIVE / PARTIAL.

- Exact next action: run one fresh lawful Worker-A validation on JDK25 with the same process-local AF_UNIX property,
  keeping `prepareFirst` and START in one live caller; do not start Worker B or test replacement.

## SYN-051 process-local preflight and clean-build gate — 2026-09-05

The compatibility evidence was committed as
`bff97418672e2197cd00f293409d68446918bdd8`. JDK25 passed the exact selector
and minimal IPv4/IPv6 HTTP preflight with the authorized process-local UNIX
temporary directory. The required clean Gradle install independently failed
before task execution with `Unable to establish loopback connection`, so the
workaround was not broadened to build logic and no fresh Worker-A lane was
created. SYN-051 remains ACTIVE / PARTIAL.

- Exact next action: obtain separate authorization for a bounded process-local compatibility path for the build/Gradle
  JVM itself, then fresh-build and provenance-lock the runtime before creating any new Worker-A lane; do not launch or
  reuse a lane.

## SYN-051 standalone host compatibility — 2026-09-05

The bounded investigation is COMPLETE; SYN-051 remains ACTIVE / PARTIAL.
Both Temurin 25+36 and 21.0.11+10 pass Pipe.open() and IPv4/IPv6 TCP, but
fail Selector.open() and HttpServer creation with an AF_UNIX connect error.
The smaller direct UNIX socket test fails in inherited/expanded user TEMP
and passes in temporary workspace. With only the diagnostic JVM's
jdk.net.unixdomain.tmpdir set to that directory, the unchanged full probe
passes 13/13 on BOTH JDKs. Classification: temporary-path-dependent Windows
AF_UNIX boundary, not JDK25-specific, not IPv4/IPv6, not host-wide TCP failure.
The underlying OS/filesystem/environment cause is unproven. User-TEMP socket
cleanup failed; exact files and limitations are recorded in the evidence.
No managed runtime, target project, production source, .synesis, credentials,
global settings, or remote state was touched.

Evidence: `docs/evidence/SYN-051-standalone-loopback-compatibility-2026-09-05.md`
and its `-raw.txt` companion. Checkpoint: CP-0683.md.

Next action: Review the standalone compatibility evidence and obtain separate authorization for one new Worker-A run on
existing JDK25 with a process-local UNIX socket directory and matching primitive preflight; do not launch or reuse a
lane in this slice.

# Goal

- SYN-051 fresh single-worker managed-runtime validation (2026-09-05):
  **PARTIAL / STOPPED ON FIRST MATERIAL HARNESS/HOST FAILURE**. Source
  provenance, installed hashes, target identity, and one fresh lawful Worker-A
  lane were verified. The same caller constructed the production launcher and
  runtime host, but JDK 25 failed at `HttpServer.create(...)` with
  `Unable to establish loopback connection` / `SocketException: Invalid
  argument: connect` before `prepareFirst`. No START, attachment, App Server,
  MCP, Job, provider thread, turn, or persistence evidence exists. No retry,
  A2, Worker B, or production source change occurred.
- Exact next action: separately resolve or investigate the JDK 25 loopback
  compatibility blocker, then use a new fresh lawful Worker-A lane with
  `prepareFirst` and START in the same trusted live caller. Do not reuse this
  lane, start Worker B, invoke A1-to-A2 replacement, or broaden acceptance.

- SYN-051 generation-1 persistence-boundary implementation (2026-09-04):
  **PARTIAL**. Same-App-Server `thread/start`, exact broker acquisition and
  binding, pending MCP quarantine, durable provider persistence readiness, and
  fail-closed successor/replacement gates are implemented. Changed workspace
  tests, clean install, and artifact matching passed; package-wide workspace
  and MCP test tasks stalled and remain incomplete. No fresh managed runtime
  was run.
- Exact next action: stop this slice; fresh single-worker managed runtime
  validation needs separate authorization.

- SYN-051 provider-thread provenance review (2026-09-04): **PASS-B —
  architecture simplification found**, with overall managed compatibility
  still **PARTIAL**. Codex 0.153.0 does not make a `thread/start`-only ID
  successor-loadable across App Server processes; one persisted turn/history
  is required in the observed lifecycle. State roots matched, and the exact
  failure was not caused by Synesis proof/ownership state. No production code
  changed. See the provider evidence and ADR-0064.
- Exact next action: implement the bounded managed-generation-1 creation and
  broker pinning change, then require one persisted turn before successor or
  replacement acceptance. Rebuild/install and run focused checks only; do not
  start Worker B or full acceptance.

- SYN-051 real-runtime validation (2026-09-04): **PARTIAL / STOPPED ON FIRST
  MATERIAL FAILURE**. Current source and installed artifacts were verified;
  a fresh managed Worker A binding and provider-thread owner were created.
  Production lifecycle launch reached a Windows Job-contained App Server, but
  Codex `0.153.0` rejected the exact bootstrap-created thread with
  `thread not loaded` before A1 activation. The lifecycle failed closed and
  produced only startup-cleanup death evidence. The non-fail-fast harness
  later created generation 2 pending through the production replacement path,
  then terminalized that aborted record; no controlled crash, valid replacement
  acceptance, A2, Worker B, or broad acceptance was run.
- Exact next action: perform a read-only provider-thread provenance review;
  do not retry managed runtime or modify production code until the loadability
  cause is understood.

- SYN-051 lifecycle replacement slice (2026-09-04): **PARTIAL**. Trusted
  supervisor death evidence is persisted per binding and generation, and
  proofless replacement is race-safe, fresh-proof, and ownership-preserving.
  Missing, mismatched, stale, terminal, live, or ambiguous evidence remains a
  hard rejection. The historical real generation has no trusted receipt and
  was left untouched.
- Exact next action: resolve Gradle loopback, rebuild/install matching
  artifacts, and rerun focused lifecycle/MCP verification. Do not start Worker
  B or perform full acceptance.

- Real-runtime compatibility result (2026-09-04): **PARTIAL / boundary PASS**.
  Pending proof-bearing MCP transport now survives stock Codex early startup
  without authority, and the same connection promotes after exact thread
  verification and activation. Fresh `SkibidiToilert` A evidence reached MCP
  `ready`, exact thread resume, activation, and real Synesis `ensure_session`
  inside one Job-contained process tree. The disposable turn itself failed
  closed on a mismatched claim path (`compat-a5` versus durable `compat-a4`);
  broader acceptance remains unrun.
- Exact next action: checkpoint and commit this compatibility slice, then
  stop before Worker B or full acceptance.

- Current bounded runtime-boundary slice (2026-09-03): **PARTIAL**. A
  Windows-only Java 25 FFM Job Object supervisor is integrated through the
  managed Codex lifecycle with suspended creation, assignment before resume,
  kill-on-close, native waits, and Job accounting. Managed proofs use
  launch-local Codex `env_vars` from the App Server environment and remain
  pending until the broker-pinned thread is verified. Focused source/build and
  real root-plus-descendant containment evidence passes; real Codex child
  proof delivery and A/B restart acceptance remain outstanding.
- Exact continuation: rebuild/install with matching hashes, then run the
  focused real Codex proof-carrier and A1-to-A2/B-isolation probe only. Full
  task-tracker acceptance is not authorized.

- Selected implementation direction (2026-09-03): PASS-B shared normal
  provider-home managed continuity is authorized. Synesis, not Codex, enforces
  worker authority through unique durable `(provider, providerThreadId)`
  ownership, trusted broker-derived immutable pinning, proof-gated admission,
  generation fencing, and owned process-tree lifecycle. Historical feasibility
  evidence remains preserved; no provider-native ownership is assumed.
- Exact continuation: implement the ownership/admission/pinning slice in
  SYN-051. Preserve ordinary `SESSION_BOUND`, `UNSAFE_FILE_AUTH` for isolated
  homes, exact authority resolution, ten MCP tools, SYN-049, and all fixtures.

- Current bounded implementation (2026-09-03): SYN-051 is the single ACTIVE
  task for stock-Codex `MANAGED_CONTINUITY`. Implement one dedicated retained
  `CODEX_HOME` and App Server per logical worker, exact thread resume, selected
  `env_vars` proof delivery, hash-only durable attachment state, proof
  rotation, and monotonic generation fencing through existing strict authority
  seams. First map insertion points, then implement and verify safe isolated
  authentication, an authenticated model turn, restart/reattachment, and
  stale-generation rejection. Preserve SYN-049, SYN-050 evidence, ordinary
  `SESSION_BOUND` providers, exactly ten MCP tools, and all historical state.
  Do not weaken authority, copy worktrees, edit `.synesis`, invent IDs, push,
  tag, release, or redesign Review/Doctor.

- Current bounded follow-up (2026-09-03): SYN-049 remains PARTIAL with Defect A
  and Defect B preserved separately; its scoped implementation and direct
  acceptance are complete, but the original whole-WorkGroup criterion was not
  met. SYN-050's provider-neutral runtime-authentication design selects Result A
  for the core, and formally approves `MANAGED_CONTINUITY` with
  Synesis-supervised Codex App Server as its first target. The isolated
  inherited-pipe prototype passed. The stock isolated-runtime experiment is
  now `PASS-A` at feasibility level for isolated dedicated Codex homes; its
  dynamic child-launch boundary remains `FAIL`. Ordinary Codex/Claude MCP remain
  `SESSION_BOUND`. Do not reopen SYN-049, weaken exact authority, rewrite
  durable state, copy worktrees, or implement production continuity in this
  prototype-only pass. Evidence:
  `docs/evidence/SYN-050-protected-carrier-prototype-2026-09-03.md`,
  `docs/evidence/SYN-050-codex-app-server-child-launch-boundary-2026-09-03.md`,
  and `docs/evidence/SYN-050-stock-codex-isolated-runtime-feasibility-2026-09-03.md`.
- Provider-boundary feasibility (2026-09-03): ordinary Codex and Claude stdio
  remain `SESSION_BOUND`; static wrappers, anonymous brokers, process
  lineage, and model-visible bearers do not establish same-conversation
  continuity. The managed Codex target is approved and its isolated-runtime
  feasibility evidence is complete. Full evidence:
  `docs/evidence/SYN-050-provider-boundary-feasibility-2026-09-03.md`.
- Exact continuation: review and authorize a bounded dedicated-home managed
  implementation slice using selected `env_vars`, retained thread state, and
  proof rotation. Preserve SYN-049 and all historical fixtures.

- SYN-041 final real Codex closure acceptance (2026-08-29): one authenticated
  Codex lifecycle used the official packaged bundle through direct native MCP
  and Java, committed `PROVIDER_SESSION_TERMINALIZED` at fence sequence 7,
  and completed lawful no-change work. The original runtime was dead before
  the sole rejected same-session probe; it returned `SESSION_TERMINAL` and
  the final durable lease was `TERMINAL_DISCONNECTED` with original process
  metadata preserved. Primary result RESULT A. SYN-041 is DONE / ACCEPTED.
  Evidence: `docs/evidence/syn041-final-real-codex-closure-2026-08-29.md`.
- Exact continuation: preserve the accepted SYN-041 closure; do not create
  SYN-042 or broaden terminal-session semantics without a separately activated
  task.

- SYN-041 final real Codex terminal-seal acceptance (2026-08-28): one actual
  authenticated Codex lifecycle completed lawful no-change work and committed
  `PROVIDER_SESSION_TERMINALIZED` before native Java/MCP exit 1 and Codex exit
    0. Exact-session rebind returned `SESSION_TERMINAL`, but its clean close
       rewrote the same lease to `CLOSED_CLEANLY` while the durable terminal event
       remained. Primary result RESULT C; SYN-041 remains ACTIVE. Evidence:
       `docs/evidence/syn041-final-real-codex-terminal-seal-acceptance-2026-08-28.md`.
- Exact continuation: inspect `SessionLeaseService.markClosedCleanly` and add
  a focused regression test for terminal-history preservation. Do not run
  another provider experiment or close SYN-041 until this defect is resolved.

- SYN-041 bounded implementation slice (2026-08-28): explicit exact-session
  terminal intent and server-validated authority proof are implemented on the
  existing `finish_lane` surface. The monotonic terminal event fences
  rebind/heartbeat/wake/review/continuation/coordination authority; clean EOF
  remains `CLOSED_CLEANLY`; unsealed abnormal transport remains stale/recovery;
  sealed abnormal transport is `TERMINAL_DISCONNECTED` history. Evidence:
  `docs/evidence/syn041-terminal-session-seal-2026-08-28.md`.
- Exact continuation: inspect the implementation/evidence diff and preserve
  the clean worktree boundary; do not run another real provider experiment,
  commit, push, tag, release, migrate providers, reopen SYN-039, or add
  generalized identity architecture.

- SYN-041 terminal-disconnect semantics (2026-08-28): design-only source
  tracing proves current lane/group/binding completion is not an irreversible
  provider-session terminal state. Completed bindings can retain bounded
  review authority and the same connection evidence can be rebound. Primary
  result is RESULT C: future explicit exact-session terminal intent plus an
  atomic no-authority proof is required; clean EOF and abnormal transport must
  remain diagnostically distinct. Evidence:
  `docs/evidence/syn041-terminal-disconnect-semantics-2026-08-28.md`.
- Exact continuation: stop SYN-041; do not implement, rerun Codex, modify
  leases/Doctor, reopen SYN-039, or create SYN-042.

- SYN-041 read-only exit-code causal analysis (2026-08-28): source tracing
  proves the native launcher waits for Java and mirrors Java exit failures.
  Clean EOF returns 0 and `CLOSED_CLEANLY`; partial EOF returns 1 with
  `MCP_PARTIAL_FRAME_EOF` and `ACTIVE`; closed stdout returns 0. Classify
  RESULT B primary / RESULT D secondary. The exact CP-0557 lower-level
  exception is not uniquely proven. Evidence:
  `docs/evidence/syn041-exit-code-causal-analysis-2026-08-28.md`.
- Exact continuation: stop SYN-041 at this bounded result; do not rerun a
  provider or modify transport, leases, Doctor, migration, identity, or
  SYN-039.

- SYN-041 final handle-based native measurement (2026-08-28): exactly one
  valid real Codex run preserved direct Codex -> official MCP -> Java
  parentage. Native retained handles recorded MCP and Java exit code 1 before
  Codex exit code 0, so the run is RESULT C / MCP-Java failure. The provider
  completion path succeeded, but the internal-versus-external cause and clean
  EOF remain unresolved. Evidence:
  `docs/evidence/syn041-final-handle-native-measurement-2026-08-28.md`.
- Exact continuation: stop SYN-041 at RESULT C; do not run another provider
  measurement or modify leases, Doctor, production code, migrations,
  generalized identity, or SYN-039.

- SYN-041 design outcome (2026-08-28): no equivalent real-provider run was
  performed. The bounded next measurement is native process-handle exit
  capture plus regular-file Codex JSONL/stderr capture, with no MCP stdio
  interposer. Ordinary Windows telemetry cannot by itself attribute a
  zero-code external termination to Codex or prove literal anonymous-pipe
  EOF. Evidence: `docs/evidence/syn041-native-observability-design-2026-08-28.md`.
- SYN-041 exact blocker: Need native, non-interposing MCP/Java exit-code and
  transport-lifetime telemetry sufficient to classify the child termination
  observed ~24.5 seconds before Codex exit.

- Product: Synesis, with Synesis Link as the first implemented transport/session module
- Repository type: modular-monolith Gradle project
- Current phase: SYN-041 is DONE / ACCEPTED at the final real Codex closure acceptance. SYN-039 remains DONE / ACCEPTED
  at CP-0547 and SYN-040 remains DONE / VERIFIED; neither is reopened. SYN-038 is DONE at CP-0458, with its Codex App
  Server lifecycle phase preserved at CP-0447/CP-0448 and its durable project-command extension completed in
  implementation commit `ad9fdd8`. SYN-037 completed at CP-0415. SYN-036 remains DONE at CP-0407; SYN-014E remains
  paused; older verification tasks retain their recorded status.
- Networking implementation: authenticated QUIC sessions, bounded control path, application liveness, bounded direct
  candidate selection, demo-only application request/result exchange, and signed single-use terminal invitations
- Wider Synesis capabilities: out of scope; SYN-038 is the explicitly tasked Codex-only lifecycle slice
- Goal revision: 27
- Status: active
- Goal status: Make two ordinary Synesis-aware coding agents complete one shared repository task unattended through the
  existing workgroup, review, handoff, validation, integration, cleanup, and diagnostics boundaries. SYN-039 is now
  accepted at CP-0547: independent Codex sessions preserved disjoint roles, rejected and corrected immutable snapshots,
  integrated only accepted work, explicitly completed the no-change reviewer lane, and left no active collaboration
  state. No central orchestrator, UI, daemon, Fleet system, or centralized launcher was added. SYN-038 evidence and its
  `turn_interrupted_command_remained_active` limitation remain unchanged. SYN-010A's required license decision is
  recorded as AGPL-3.0-only; publication remains unperformed pending explicit push authorization and remaining review
  gates.
- Completion target: Synesis Link v1 criteria in `docs/agent/CONTRACT.md`
- Evidence: SYN-009C release evidence is complete at CP-0110; SYN-010A
  publication audit, current/reachable-history scan, documentation preparation,
  strict Java verification, and repository validators are recorded at CP-0111.
- Evidence: `docs/architecture/zero-touch-agent-collaboration.md`,
  `docs/validation/multi-chat-provider-acceptance.md`,
  `docs/evidence/syn037-real-codex-acceptance-2026-08-03.md`,
  `docs/evidence/syn038-codex-schema-2026-08-03.md`,
  `docs/evidence/syn038-real-codex-acceptance-2026-08-03.md`,
  `docs/evidence/syn038-real-codex-app-server-acceptance-2026-08-03.md`, and
  ADR-0043.
- Exact continuation: preserve the SYN-041 RESULT E native-topology
  measurement; the polling harness lost the Codex transcript and native EOF/
  child exit evidence. Keep SYN-039/SYN-040 closed and `SYN-014E` paused; no
  lease or Doctor change is authorized.

## 2026-09-24 — SYN-082 overnight acceptance status

The overnight continuation objective remains open. Source crash-prefix and
replay coverage passes, including the single durable continuation event and
fresh-authority invariant. In the latest disposable acceptance, a real agent
process tree was killed and fresh blind participants lawfully continued the
storage lane under new participant/intent identities. The strict Phase 2
survivor-at-acquisition condition was missed, and the WorkGroup did not reach
terminal state. A blind participant also published a CLI snapshot, but
Synesis blocked integration and pytest ran against a mismatched package
workspace. Phase 3 remains incomplete: no accepted independent decision,
reviewer-loss successor, integration, or terminal convergence was observed.

Keep the three phase verdicts separate: Phase 1 PASS with the stated real-MCP
crash limitation; Phase 2 PARTIAL; Phase 3 PARTIAL, with the mandatory hostile
review path not reached. The full test and tool-gate evidence, build versions,
disposable project/WorkGroup identities, and next action are recorded in the
SYN-082 evidence addendum. No source Synesis session, source .synesis state,
commit, or push was used.
