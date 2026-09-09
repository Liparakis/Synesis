# SYN-009E Synesis maximum-protection commercial release profile — activated 2026-09-09

Build and verify a release-only maximum-protection profile for Synesis using
the existing `SYN-009*` distribution, bootstrap, signing, and stable-install
lineage. Keep the ordinary developer build readable and debuggable. The
protected profile must distinguish protection-lite from the requested
commercial maximum profile and must classify every Seven Rings capability with
artifact and installed-runtime evidence rather than marketing terminology.

- Status: ACTIVE / protection-lite evidence complete; maximum adapter/signing seam implemented; commercial profile blocked
- Baseline: activation checkout `8a5d0901b4859c9a504f7fc7009def23177f1d43`
  on `master`; the scoped release commits remain local and ahead of
  `origin/master`. `CP-0758.md` is preserved as the completed UI checkpoint
  and remains uncommitted.
- Lineage: `SYN-009C` and `SYN-009D` are the existing distribution/install
  foundations; this task extends them rather than creating a parallel release
  system. `SYN-010B` remains a separate historical aggregation task.
- Planning basis: the user-provided maximum-protection goal, current-source
  and bundle investigation, ADR-0070, and ADR-0071's external adapter/signing
  contract.
- Exact next action: obtain an installed, licensed, version-pinned commercial
  protector plus its reviewed adapter/configuration and run the maximum-release
  acceptance with injected release signing authority; until that
  authority/tool exists, keep the maximum gate fail-closed and do not invent
  a replacement implementation.
- Required rings: control-flow hardening, genuine virtualization, runtime
  string/constant protection, safe analysis-environment detection, protected
  payload packaging, safe anti-debug/instrumentation handling, and signed
  integrity/diversification/private retrace.
- Honest boundary: ProGuard 7.10.0 transformation is only `PROTECTION_LITE`;
  the provider-agnostic maximum adapter and existing bootstrap-signer seam are
  release plumbing, not ring evidence. Commercial rings remain `BLOCKED` until
  a licensed protector is installed and its shipped output is exercised.
- Developer boundary: no protection transforms, anti-debug behavior, packing,
  or virtualization may enter the normal developer build or customer-visible
  runtime toggle surface.
- Release boundary: no production signing keys, commercial licenses, private
  seeds, mappings, or native symbols may be committed or shipped; no push,
  tag, release, or remote mutation is authorized.

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
and passes in C:\t\synesis-loopback-probe. With only the diagnostic JVM's
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
- Goal revision: 26
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
