# Current Task

## SYN-055 Final browser artistic-direction pass — 2026-09-10

- Task ID: SYN-055
- Status: **ACTIVE** — requested visual slice verified complete; administrative review record.
- Scope: presentation and accessible markup in the existing browser UI;
  no backend, data model, route destination, or authority changes.
- Authority: the current pasted brief supersedes earlier no-indicator and
  uncapped-width decisions. All six screens receive the same design system.

## Work completed

Refined neutral color tokens, body/metadata type, compact metrics, quiet
healthy states, warning emphasis, responsive 1480px/1760px page caps, single
Projects back control, attached project tabs, native metadata titles, selected
row markers, controls, and short focus/hover transitions. The runtime indicator
uses the existing session state and an understated 5px dot. Diagnostics display
labels are polished without changing underlying values. Fixed accessible list,
table-header, and connection-control markup. Development fixture mode survives
navigation but remains excluded from the production bundle.

## Verification

- PASS: :web-ui:check and :cli:installDist with the documented command-local
  Windows loopback workaround; typecheck, lint, 12 tests, production build.
- PASS: authenticated packaged runtime; six screens at 2560x1440, 1920x1080,
  and 1280x800, 100% scale. No page overflow or JavaScript errors.
- PASS: separate populated development-fixture screenshot stress pass.
- PASS: axe scans on six real and six populated screens, zero violations.
- PASS: search miss, inactive project detail, keyboard inspector dismissal,
  and existing modal/termination tests. Packaged asset SHA-256 equals dist;
  no development mock asset is shipped.
- Evidence: `docs/evidence/SYN-055-art-direction-2026-09-10.md`; generated screenshots and logs under
  build/ui-art-direction. No full backend suite, global install, or new
  Windows installer is claimed.

## Current failures

None for this visual slice. Default-environment loopback and the initial
running-Vite file lock were resolved using documented local workarounds.
No new deferred capability or architecture change. No commit or push.

## Immediate next action

Review the completed slice with `git diff -- web-ui/src/app/App.tsx web-ui/src/styles.css docs/evidence/SYN-055-art-direction-2026-09-10.md`; make no further product changes
unless a subsequent user request identifies a follow-up.

## Historical task — SYN-054 Persistent known-project discovery — implementation and lifecycle-evidence gate — 2026-09-09

- Task ID: SYN-054
Status: **COMPLETE FOR CURRENT SCOPE; implementation and lifecycle evidence complete; unrelated baseline regressions documented**.

The current slice adds a persistent event-driven index of initialized Synesis
projects legitimately encountered through existing init, UI/runtime, and MCP
startup paths. The registry is discovery metadata only. Project-local
coordination and runtime state remain authoritative in each project.

Acceptance is not complete until real disposable projects prove observation,
idempotence, restart persistence, runtime disappearance retention, distinct
identities, missing-path behavior, identity-mismatch rejection, metadata-only
persistence, and unchanged project-local control-plane/MCP behavior.

Implemented registry, CLI/UI/MCP hooks, authenticated known-project adapter,
and frontend view. Focused registry, affected control-plane/CLI/MCP, strict
Javadoc/compile, and frontend checks pass. Two real disposable projects were
observed through installed CLI init and a separate MCP process; the registry
persisted two distinct identities across processes. A live authenticated
loopback control-plane probe returned both projects with `LIVE` and `INACTIVE`
statuses and matched the registry's metadata-only contents. The broad
workspace check remains red in unrelated coordination/provider tests and was
stopped after repeated failures. The named `AgentNextActionServiceTest`
failures also reproduce on an untouched detached worktree at the current base
commit, before SYN-054 changes, so they are not attributed to this slice.

Exact next action: preserve the verified SYN-054 state; no further product-code
work is required for this goal. Any commit or publication remains a separate
explicit action.

## Historical current task — SYN-009E

### Synesis maximum-protection commercial release profile — implementation and acceptance gate — 2026-09-09

Status: **COMPLETE FOR PROTECTION-LITE SCOPE; COMMERCIAL MAXIMUM DEFERRED / EXTERNAL DEPENDENCY**.
The free protection implementation and its release plumbing are closed for
this stage. The commercial maximum remains intentionally unavailable until a
licensed, version-pinned protector adapter and production signing authority
are supplied; the maximum task continues to fail closed and is not relabeled
as protection-lite.
The completed installed UI slice is preserved under `CP-0758`; the current
task extends the existing `SYN-009C`/`SYN-009D` distribution and signing
foundations into separate developer, protection-lite, and maximum-release
profiles. A versioned external-protector request/result contract now feeds the
release-only CLI and standalone-relay maximum tasks, which validate private
evidence and sign/verify each candidate through the existing bootstrap
boundary. No commercial Seven Rings capability is claimed until its actual
protector output is installed and exercised.
The current-source Tier 0–3 inventory and vendor-neutral adapter configuration
skeleton are now explicit; maximum tasks reject dirty release checkouts and
bind adapter results to the tier, keep-rule, acceptance-procedure, and full
reproducibility digests requested by the release. The current brief's six
commercial adapter classes are Rings 1–6; Ring 7 is the signed-integrity,
diversification, and private-retrace boundary owned by the release pipeline.
Both maximum tasks now reject a protector executable or configuration that is
lexically or canonically inside the source checkout, including symlink aliases;
commercial binaries and license-bearing configuration therefore remain an
external private-release input.
The v1 adapter result now also requires a non-secret `licenseMode` and a
redacted private `licenseEvidence` attestation, whose hash is retained only in
the private release record; license keys and tokens are never accepted as
result fields.
The adapter documentation now explicitly maps a future Virbox Java VME/BCE
wrapper to Rings 2 and 5 while keeping those results evidence-gated; no
vendor-specific Virbox command or configuration is committed.
The latest read-only source-scope audit resolved all 21 inventory paths and all
12 method-scoped Tier 3 signatures, and recorded the concrete reflection,
resource, ServiceLoader, and native-declaration findings; this is planning
evidence only and does not close commercial transformation compatibility.
An independent `scripts/maximum-release-acceptance.ps1` harness now exercises
only extracted customer archives, rejects lite/profile and private-material
leakage, optionally verifies the private artifact manifest, and records
private JSON evidence. For CLI candidates it also has a no-PATH disposable
install, stable-launcher runtime-tamper, packaged frontend static-content and
frontend-asset tamper,
provider/MCP, mutable-state, and live authenticated browser/control-plane
HTTP/SSE gates. For relay candidates it now starts the extracted launcher and
verifies authenticated forwarding through a test-only external observer. Its optional JDK
loopback/workspace overrides are process-local and recorded as non-shipped
compatibility inputs.
The protector capability matrix now also records the required Java/Gradle,
compatibility, reproducibility, recovery, performance, licensing, and
anti-analysis evaluation dimensions without converting any commercial
candidate to an unverified pass.
The bootstrap now records the selected protection profile in the active
pointer and makes the stable launcher invoke the existing payload-manifest
doctor only for `maximum-release`. Its Windows forwarding path now preserves
signed invitation query arguments containing `&` while rejecting unsafe
embedded command characters. A rebuilt synthetic marker-only disposable
probe passed immutable JVM/frontend tamper refusal, mutable `Link`-state
acceptance, and installed signed-invitation/authenticated-PeerSession/project
synchronization. The acceptance harness isolates `HOME`, `USERPROFILE`,
`APPDATA`, and `LOCALAPPDATA` for every captured process, while explicit
JDK/workspace overrides remain process-local only.

The relay observer is shipped-protocol evidence only; it does not promote a
synthetic or protection-lite archive to commercial maximum evidence.

The reusable `scripts/release-profile-comparison.ps1` harness now records
customer-archive SHA-256, archive/extracted size, bounded cold launcher
samples, and a best-effort aggregate launcher-process-tree working set for
developer, protection-lite, and (when supplied) maximum-release profiles. The
current five-sample CLI run measured developer at 46,504,281 archive bytes /
69,542,917 extracted bytes / 675.523 ms median startup / 107,982,848-byte
maximum aggregate working set and protection-lite at 45,705,419 /
68,661,707 / 642.855 ms / 89,432,064 bytes. The free lite rules now also
enable standard ProGuard optimization, mixed-case renaming, package
repackaging under `org.synesis.p`, and removal of source/line attribute
retention from the transformed application rules. Its result is
`PARTIAL_MAXIMUM_ARCHIVE_NOT_SUPPLIED`; the non-isolated timings and aggregate
working-set values are not performance or unique-physical-memory claims, and
UI, route, Link, relay, provider, AV/EDR, and commercial maximum measurements
remain open.
The extracted acceptance harness now also records a `performance` object with
bounded per-check `durationMs` values for provider/doctor, Link onboarding, UI
smoke, authenticated control-plane HTTP/SSE, and relay forwarding. It leaves
unique physical memory, route microbenchmarks, commercial transformation
overhead, and AV/EDR impact explicitly open.

The first-party DashO/Zelix/Virbox documentation pre-screen is now recorded
separately from artifact evidence. It narrows the next licensed evaluation:
DashO documents Java 25/26 bytecode support, scoped control-flow/string
transforms, Gradle/CLI integration, and runtime debug/tamper checks; Zelix
documents Java 26 bytecode handling, flow/string/constant transforms, Gradle
scripting, and reproducibility guidance. Neither reviewed candidate establishes
genuine virtual execution, protected payload loading, or the required
Synesis-safe Java anti-VM policy. DashO is therefore the first licensed
candidate to evaluate for Rings 1, 3, and bounded Ring 6 behavior, while all
commercial ring claims remain unverified until shipped output is exercised.
Virbox's Java VME/BCE documentation makes it the first candidate to evaluate
specifically for Rings 2 and 5, but its separate-license boundary and Java-safe
Ring 4/6 behavior are also unverified.

The archive-only static reverse-engineering comparison is now implemented in
scripts/release-reverse-engineering-comparison.ps1. Its current developer
versus protection-lite CLI run found no file-level private/source/map entries,
and the free hardening reduced source-metadata signals by 849 classes and
architecture-term-hit classes to 549, but metadata remains in many classes; it
is recorded as partial and does not claim improved decompiler resistance. A
real maximum archive and selected-method inspection remain required.

The archive-only native hardening audit is now implemented in
scripts/release-native-hardening-audit.ps1. The current Windows x64
developer/protection-lite CLI archives passed the owned-launcher PE, zero-COFF-
symbol, no-debug-directory/section, no-named-export, local-path, private-file,
and Go-trimpath checks. The parser now also handles ELF and Mach-O; temporary
Linux amd64 and macOS arm64 cross-build fixtures passed the format,
debug/export, and trimpath classification. Their native files are unsigned or
unverified, the maximum archive was not supplied, and the current samples
record dirty Go VCS metadata; this is therefore baseline/parser evidence only
and does not close native signing, shipped cross-platform, or commercial
maximum acceptance.

The CLI and relay maximum-release request/private-record seams now capture a
reproducibility snapshot: sorted lockfile count/digest, Gradle, Java, Node/npm,
and Go toolchain versions, protector configuration digest, release identity and
seed, plus post-archive native-hardening evidence and post-signing
key/public-provenance fields. Missing Node, npm, or Go fails the maximum
provenance gate; ordinary developer tasks are unchanged. The maximum manifest
tasks run the native hardening/signing audit before creating the signed
manifest, and the private record retains its hash.

The new `scripts/maximum-release-provenance-comparison.ps1` harness compares
two private maximum records for either controlled diversification or exact
canonical-manifest reproducibility. Its parser and missing-record fail-closed
path pass, but no commercial maximum records are available for a positive run.
The CLI release task also rejects a non-matching `GITHUB_SHA` instead of
allowing CI metadata to override the actual checkout commit.
Both maximum tasks now require non-empty private evidence for every ring and
diversification, non-empty mapping/retrace files, a structured retrace-
acceptance record bound to the mapping hash, and component-scoped native
recovery: CLI-owned symbols plus a third-party-native audit, or relay's
explicit not-applicable owned-symbol scope plus that audit. Their hashes are
retained only in the private release record. The relay scope is evidenced in
`docs/evidence/syn-009e-relay-native-scope-2026-09-09.md`.
The release-to-release comparison now verifies those private hashes and the
final signed-integrity fields before comparing two records.
The consolidated evidence report is
`docs/evidence/syn-009e-maximum-protection-status-2026-09-09.md`; it keeps the
brief's full final-report fields explicitly partial rather than promoting
synthetic or protection-lite evidence.

- Task ID: SYN-009E
- Activation HEAD: `8a5d0901b4859c9a504f7fc7009def23177f1d43` on `master`;
  local release commits remain unpublished, and the working tree contains the
  preserved `CP-0758` plus unrelated pre-existing local work that remains
  uncommitted.
- Latest implementation commits: `9ce6e5c7` (free protection-lite package
  repackaging and refreshed evidence), `ae4b9852` (free protection-lite
  optimizer, mixed-case renaming, metadata hardening, and refreshed evidence),
  `4cc8fec0` (bounded profile memory evidence), `974380fd` (private license
  provenance), and `690c691a` (external
  commercial-tool path boundary); the earlier
  post-archive native-audit, symlink-boundary, structured private-retrace,
  exact-brief Seven Ring taxonomy, bounded acceptance-timing, and packaged
  frontend static-content acceptance changes remain committed.
- The latest release-environment evidence is the availability probe from
  `18da2f4b` / `CP-0807`; the candidate-capability review is `4b2836a2` /
  `CP-0810`. No licensed adapter, commercial configuration, signing key, or
  release seed is available; this is evidence of the blocker, not a
  commercial-ring result.
- The free-hardening slice is checkpointed as `CP-0816`; no push, tag, release,
  or remote mutation occurred.
  No push has occurred.
- Existing release seams: Gradle platform bundles and jlink runtime, Go
  bootstrap signed-manifest/payload verification, stable flat installation,
  native launcher/installer, packaged frontend resources, and relay's separate
  application distribution.
- Protection boundary: developer builds remain readable; protection-lite is an
  explicit release-engineering profile with free optimizer/package/metadata
  hardening;
  maximum-release is gated on licensed commercial tooling and must not expose
  a runtime downgrade switch.

## Immediate next action

Next product stage: UI polish. Resume the deferred
commercial maximum capability only after an installed, licensed,
version-pinned commercial protector and its reviewed adapter/configuration are
available; inject the release signing authority then and run the maximum-
release acceptance. Until that external tool is available, preserve the
passing lite evidence and keep the maximum gate fail-closed. Use
`powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1` before
resuming after a stop.

## Current constraints

- Do not change normal developer tasks or apply protection transforms to
  ordinary tests/IDE output.
- Do not call renaming virtualization, compression packing, weak VM checks, or
  debug flags a pass for the corresponding Seven Ring.
- Keep public CLI/protocol/JSON/resource/JNI/ServiceLoader contracts stable and
  justify every nontrivial keep rule.
- Use existing installer/payload integrity rather than creating a parallel
  trust system; mutable user state must not be covered as immutable payload.
- No home-grown VM, packer, hostile anti-debug, debugger attack, arbitrary
  process killing, host surveillance, or secret/key/license commit. The
  explicitly authorized closeout push is the only current remote mutation;
  future commercial release signing remains private and injected.
- Record `developer`, `protection-lite`, and `maximum-release` separately in
  every result; unavailable commercial execution remains a blocker.

## Work completed

Added the provider-agnostic maximum-protector adapter contract and ADR-0071.
The release-only CLI and relay tasks now require an explicit licensed adapter,
version-pinned private configuration, release ID/seed, six mandatory Ring 1–6
evidence records, diversification, private mapping/retrace, component-scoped native
recovery/audit, and separated customer/private output. It now also binds the request/result to the current
Tier 0–3 source inventory, keep-rule inventory, and acceptance procedure,
requires a clean reviewed release checkout, creates a private artifact
manifest, archives the candidate, signs through the existing bootstrap signer,
and verifies the detached signature against the embedded bootstrap public key.
Added the source-backed tier inventory and vendor-neutral config skeleton. No
commercial protection capability is claimed.

The maximum CLI and relay tasks now enforce the private-tool boundary before
release provenance or adapter invocation: both the lexical and canonical paths
of the adapter and configuration must be outside the source checkout. This
prevents committed or symlinked commercial license material from entering the
release path.

The result contract now requires private non-secret licensing provenance:
`licenseEvidenceFormat`, `licenseMode`, and a redacted attestation file below
the private directory. CLI and relay release records hash that attestation;
the adapter still owns the actual CI-injected vendor entitlement.

Added the archive-only `scripts/release-native-hardening-audit.ps1` and its
developer/lite evidence. The Windows x64 owned Go launchers passed PE,
COFF/debug, export, local-path, private-file, and trim-path checks. The audit
keeps native signing separate and records the maximum archive, non-Windows
formats, ARM64, third-party native inputs, and clean release provenance as
open.

Refined `scripts/release-profile-comparison.ps1` to sample Windows launcher
process-tree working sets at a bounded interval, label the aggregate signal
explicitly, and emit memory deltas only when both compared values are numeric.
The current five-sample developer/protection-lite run is recorded in the
profile-comparison evidence JSON; it remains partial because the licensed
maximum archive is absent, and the aggregate signal is not unique physical
memory or a performance claim.

## Verification

- `:cli:bundleSmokeTest --dependency-verification=strict --no-configuration-cache`
  passed under the documented process-local Windows Gradle workaround.
- `:cli:maximumRelease --no-configuration-cache` reached
  `maximumReleasePrepare` and failed closed as designed because no adapter is
  configured.
- `:relay:maximumRelease --no-configuration-cache` reached
  `maximumReleasePrepare` and failed closed as designed because no adapter is
  configured; the relay protection-lite smoke still passes with explicit JMODs.
- `:relay:relayArtifactAcceptanceClient` passed against the extracted developer
  relay launcher: two disposable nodes authenticated, an unauthorized node was
  rejected, encrypted frames forwarded bidirectionally, and the relay process
  shut down within bounds. This is protocol/shipped-launcher evidence, not
  commercial maximum evidence.
- The maximum tasks compile after source-tier binding; both still fail closed
  before adapter invocation because no licensed adapter is configured.
- `:cli:tasks :relay:tasks --no-configuration-cache --no-daemon` passed with
  the documented process-local loopback workaround after the path-boundary
  change. Deliberate in-checkout adapter probes for both CLI and relay failed
  closed with the new outside-source-checkout guard.
- The same task-list configuration passed after adding the private license
  attestation contract; no commercial adapter or license material was supplied.
- The shipped-artifact harness rejects both existing protection-lite archives
  as non-maximum, proving the profile boundary without mislabeling lite output.
- A synthetic marker-only maximum archive completed the extracted CLI harness:
  immutable JVM and packaged frontend tamper refusal, mutable `Link` state,
  provider lifecycle, packaged UI root, authenticated control-plane
  session/snapshot/CSRF/SSE, UI/control-plane, native MCP, and ready MCP
  session all passed when the documented process-local host compatibility
  overrides were supplied. The rebuilt installed launcher also completed a
  signed local Link invitation, authenticated the remote PeerSession, and
  completed project synchronization. The default host run remains explicitly
  partial at the loopback and provider-workspace gates.
- `go test ./...` in `bootstrap`, `:cli:nativeMcpLauncher`, and
  `:cli:platformBundle` passed with the documented process-local Gradle/JDK
  workaround. The installed result is summarized in
  `docs/evidence/syn-009e-link-shipped-acceptance-2026-09-09.md`.
- `go test . -run '^TestMaximumProfileIsRecordedAndStableLauncherEmitsIntegrityGate$' -count=1` passed.
- The generated maximum stable-launcher gate passed PowerShell AST parsing;
  the disposable marker-only runtime probe refused an edited immutable payload
  and passed after mutable `Link` state was added. This is bootstrap evidence,
  not commercial maximum evidence.
- `scripts/release-profile-comparison.ps1` parsed and ran five cold CLI
  developer/protection-lite samples against extracted ZIPs, recording archive
  and extracted sizes, SHA-256 identities, and best-effort aggregate
  launcher-process-tree working sets. It exited with
  `PARTIAL_MAXIMUM_ARCHIVE_NOT_SUPPLIED`; no performance or unique-physical-
  memory claim is made from the non-isolated sample, and broader UI/route/
  Link/relay/provider timing remains open.
- The first-party commercial protector documentation pre-screen was recorded
  in `docs/evidence/syn-009e-protector-doc-review-2026-09-09.md`; it refines
  candidate selection without changing any commercial row to `PASS` or
establishing Rings 1–6 or any maximum Seven Ring result.
- `scripts/release-reverse-engineering-comparison.ps1` parsed and ran against
  the developer and protection-lite CLI ZIPs. It recorded static class/package,
  architecture-term, metadata, path, source-map, private-material, and
  inspection-tool signals with status
  `PARTIAL_MAXIMUM_ARCHIVE_NOT_SUPPLIED`; no decompiler or commercial ring was
  claimed.
- `scripts/release-native-hardening-audit.ps1` parsed and ran against the
  developer and protection-lite CLI ZIPs. Both owned Windows x64 launchers
  passed the structural native-hardening checks; native signing remained
  `OPEN_NOT_SIGNED_OR_UNTRUSTED`, the maximum archive was absent, and the
  script returned the expected partial status.
- `:cli:tasks :relay:tasks --no-configuration-cache --no-daemon` passed with
  the documented process-local JDK loopback override after the inherited host
  environment failed before Gradle evaluation. This evaluated the new
  reproducibility helpers and exposed both maximum task seams; no customer
  artifact or release record was produced.
- `scripts/maximum-release-provenance-comparison.ps1` now requires and hashes
  each record's non-secret license attestation and compares its format/mode as
  shared provenance. PowerShell AST parsing passed; its missing-record probe
  returned the expected `PARTIAL_MAXIMUM_RECORDS_NOT_SUPPLIED` status.
- `go test ./...` in `bootstrap` now passes after the test fixture helper
  isolated `HOME`/`USERPROFILE` from the developer's real provider state.
- `go test ./cmd/sign-manifest` passed, including explicit-path signing.
- `git diff --check` passed for the scoped changes; `agent-resume.ps1` and the
  deferred validator passed.
- The free protection stage is now closed: `:link:check`, `:relay:check`, CLI
  and relay protection-lite acceptance, and the clean-tree maximum fail-closed
  gate all passed with their documented expected outcomes. The remaining
  commercial maximum dependency is deferred, not an unfinished lite task.

## Current failures

No licensed commercial protector, adapter, private configuration, or release
signing authority is installed. The marker-only runtime probe used a rebuilt
platform bundle with only a synthetic maximum marker and does not prove any
commercial transformation or Seven Ring completion. The synthetic marker-only
harness is artifact/bootstrap evidence only; Rings 1–6 remain blocked and Ring
7 remains partial until a licensed commercial release is signed, diversified,
and retrace-validated. The repository-wide legitimate formatter/implementation
changes and checkpoint history were audited, committed, and are included in
the authorized closeout; no unstaged changes remain.
The native audit parser covers PE, ELF, and Mach-O, but actual Linux, macOS,
and ARM64 customer archives, third-party native inspection, production native
signing, and clean maximum-artifact provenance remain open. The portable
format result is fixture-only evidence.
The new reproducibility record is implemented but unexecuted for a real
maximum candidate because the commercial adapter, licensed configuration, and
production signing authority remain unavailable.

## SYN-053 first installed Synesis browser UI — completed current scope — 2026-09-08

Status: **COMPLETE FOR CURRENT SCOPE; CHECKPOINT CP-0758 RECORDED**.
The user-provided browser goal now activates the first product UI over the
verified local control plane. ADR-0069 selects an install-bundled `web-ui`
module, same-origin static serving from the existing JDK listener, fragment
bootstrap, a typed snapshot/SSE client, and a dedicated `synesis ui` command.
The Windows self-contained runtime packaging was corrected to include
`java.desktop`, which is required by the supported browser-opening boundary.

- Task ID: SYN-053
- Baseline: backend commit `2be88cf` was clean and pushed to `origin/master`
  before UI work. No UI changes were included in that push.

## Historical continuation note

Preserve the verified local SYN-053 commits and do not push UI changes without
a separate explicit instruction. Further work is tracked under `SYN-009E`.

## Current constraints

- No Node, npm, Vite, or frontend server at runtime; build tooling is
  development/CI-only.
- Serve only packaged static assets from the existing loopback origin; keep
  `/api/v1`, `/events`, and lifecycle routes separate from SPA fallback.
- Put the one-time bootstrap in the URL fragment, exchange it through the real
  session endpoint, and remove it from browser history.
- Use typed DTOs and the existing SSE contract; do not duplicate domain logic,
  infer routes, or fabricate project/agent/network data.
- Treat empty projects, `UNCONFIGURED` overlay, disabled relay, missing peers,
  session expiry, and diagnostics as first-class states.
- No external CDN, cloud API, telemetry, Electron, native GUI, OS URI
  registration, Link/overlay redesign, WorkGroup redesign, or UI push.

## SYN-053 implementation evidence

The `web-ui` module now contains the locked React/Vite/Tailwind/Lucide client,
typed control-plane client, fetch-based SSE, truthful screens, onboarding
controls, and focused tests. Gradle packages its production resources into the
CLI distribution. The existing JDK listener now serves the classpath UI with
security headers, and `synesis ui` composes the server and optional browser
open. Frontend typecheck, lint, tests, build, Java compilation, static-handler
tests, installed static/session/snapshot/SSE acceptance, and browser visual/
functional QA pass. See `docs/evidence/syn-053-installed-browser-ui-2026-09-08.md`.

## Work completed

Implemented the install-bundled `web-ui` module, locked npm toolchain,
typed control-plane client, fetch-based SSE, real-state screens, empty and
`UNCONFIGURED` states, onboarding controls, same-origin static resource
handler, `synesis ui` startup, browser opening, security headers, packaging,
focused tests, installed acceptance, and browser functional/visual QA. Added
the architecture, API, README, test-matrix, and evidence documentation. Fixed
the Windows jlink runtime module list to include `java.desktop`; a fresh
platform-bundle smoke run now starts `synesis ui` with browser opening enabled
and exits cleanly after the bounded duration.

## Current failures

No SYN-053 implementation gate is failing. The existing repository-wide
`:link:formatCheck` remains blocked by trailing whitespace in the pre-existing
`docs/agent/checkpoints/CP-0752.md`; Link tests/static analysis/Javadocs and
relay tests/static analysis/Javadocs pass when that unrelated format task is
not selected. The broad historical workspace/CLI fixture suite remains
outside this slice and is not claimed as a full pass. Configured overlay and
relay acceptance remains correctly unavailable without a signed membership
authority source.

## SYN-052 local control-plane backend — implementation and acceptance gate — 2026-09-08

Status: **ACTIVE / LIVE OWNER SLICE COMPLETE; AUTHORITY SOURCE BOUNDARY OPEN**.
The explicit control-plane brief supersedes the previous HTTP exclusion for
this new task. ADR-0067 selects one in-process versioned loopback adapter over
the existing coordination listener and authoritative services. The future
browser remains out of scope.

- Task ID: SYN-052

## Immediate next action

Preserve the verified retained-session acceptance. If this task resumes, first
obtain or define a legitimate signed membership-authority source before any
configured CLI overlay work; keep the CLI overlay `UNCONFIGURED` until then.
Do not fabricate membership or promote the disabled relay projection to
connected.

## Current constraints

- Bind only to loopback; validate Host and Origin; never use wildcard CORS.
- Use the existing JDK HTTP server and JSON utility before adding dependencies.
- Delegate invite/join/answer behavior to `Onboarding`; do not duplicate SLO1/
  SLA2 validation.
- Use explicit safe DTO fields; never serialize internal objects or secrets.
- Bound request bodies and SSE subscriber queues; disconnect slow clients with
  refresh semantics.
- No frontend, cloud API, generic proxy, arbitrary filesystem endpoint,
  reconnect/path migration, durable browser replay, or push.

## SYN-052 implementation evidence

- Added `ControlPlaneReadModel`, `ControlPlaneHttpHandler`, and bounded
  `ControlPlaneEventHub` under the workspace control transport package.
- Mounted `/api/v1` in the existing JDK loopback listener and retained the
  existing binary `/command`, `/events`, and Codex lifecycle routes.
- Added one-time bootstrap exchange, short-lived session/CSRF headers, exact
  loopback Host/Origin validation, bounded JSON bodies, explicit DTO mapping,
  onboarding command delegation, and live-only SSE snapshot/delta events.
- Focused control-plane tests pass, including durable WorkGroup/WorkIntent
  mapping through HTTP, a real Link membership view through `/api/v1/network`,
  authenticated peer/topology route projection, auth/CSRF/origin/Host/bounds,
  semantic SSE snapshot/live delivery to multiple clients, shutdown
  termination, one-shot HTTP onboarding, and retained-session HTTP onboarding
  through `LinkRuntimeOwner`. The real CLI smoke also passes for a disposable
  Git project. `:link:check`, `:relay:check`, strict Javadocs, and deferred
  validation pass.
- The CLI now composes the long-lived owner and reports authenticated physical
  sessions when present. Its overlay remains `UNCONFIGURED` because the
  checkout has no legitimate signed membership-authority source; no fabricated
  membership or relay connection is used.

## Work completed

Implemented the local `/api/v1` control-plane foundation over the existing JDK
listener and durable project/coordination services. Added explicit read DTOs,
the `LinkNetworkProjection` adapter, bounded live subscriptions, one-time
bootstrap plus session/CSRF security, onboarding command delegation, semantic
redacted SSE, API documentation, ADR-0067, and focused tests for real
persisted coordination state, real Link membership/topology/relay inputs,
  multi-client delivery, clean shutdown, and real two-profile one-shot and
  retained-session HTTP invite/join/answer/connect flows. Added ADR-0068,
  `LinkRuntimeOwner`, the narrow control operation seam, and the shared CLI
  identity-directory wiring. A disposable initialized Git project also passed
  real CLI serve, health, session, and authenticated snapshot smoke.

## Current failures

Focused control-plane tests, strict Javadocs, `:link:check`, `:relay:check`,
deferred validation, and `git diff --check` pass. The combined
`:coordination:test :workspace:test :cli:test` run reported failures in
existing CLI/workspace fixture tests (`CodexHookProcessTest`, several
`AgentNextActionServiceTest`/`CapabilityNegotiationTest` cases,
`WorkspacePatchServiceTest`, `ProviderApplicationServiceTest`, and
`ProviderSessionBindingServiceTest`); they are outside this slice and remain
  unclassified rather than being called passing. Configured CLI overlay
  acceptance remains outside this slice because this checkout has no legitimate
  signed membership-authority source; the owner and retained-session acceptance
  are focused-pass evidence.

## SL-D-040 distributed project overlay — implementation and verification gate — 2026-09-08

Status: **COMPLETE FOR CURRENT SCOPE**. The requested overlay capability is now
represented by its own task rather than being folded into direct traversal.
ADR-0066 records the proposed ownership, membership, E2E, topology, routing,
and relay boundaries. The bounded Link overlay, monotonic membership view,
mutable authenticated-peer registry, signed topology propagator, peer
forwarder, in-memory relay, and standalone relay module now have production
code. Configured Link/relay checks and localhost socket acceptance pass with a
process-local loopback workaround; the inherited environment still fails
before Gradle startup.

- Task ID: SL-D-040

## Immediate next action

Preserve the passing configured/local acceptance and keep controlled-NAT or
physical Internet traversal under the separately scoped `SL-D-035` evidence
gate. Do not broaden this task into reconnect, path migration, or a physical
network claim.

## Work completed

Re-investigated the current Link and project-record source. Confirmed that
`PeerSession` remains a one-hop authenticated physical session, the current
application seam is bounded request/response, `ProjectConfig` is only a local
allowlist, and durable Ed25519 identities currently provide signing rather
than E2E encryption. Implemented the bounded SLM1/SLK1/SLE1/SLF1 contract,
bounded SLP2 membership propagation, monotonic same-authority membership
refresh, bounded direct-peer binding, signed topology and SLP1 propagation,
deterministic route selection, peer
forwarding, the PeerSession bridge, in-memory relay core, and standalone Netty
relay/client module. Configured/local evidence is recorded in
`docs/evidence/sl-d-040-overlay-foundation-2026-09-08.md`. The verified
`SL-D-035`
traversal slice remains committed and separately paused at its controlled-
topology evidence gate.

## Current failures

The signed membership authority is the new bounded SLM1 contract; its authority
lifecycle remains explicitly out of scope. Java 25 crypto APIs, topology
propagation, logical route/transit evidence, full configured Link/relay checks,
and real localhost relay/process acceptance pass with the documented
process-local loopback workaround. The inherited environment still fails
before task/socket execution with `Unable to establish loopback connection` /
`Invalid argument: connect`; no physical multi-network claim is made from this
host.

## SL-D-035 coordinated UDP/QUIC hole punching — implementation and evidence gate — 2026-09-08

Status: **ACTIVE / EVIDENCE GATE**. Read-only re-investigation confirmed
that Link already owns Ed25519 identities, signed descriptors and invitations,
Netty native QUIC, authenticated control/application streams, bounded candidate
racing, replay checks, liveness, and two-process evidence. The remaining gate
is controlled-topology evidence for endpoint discovery and coordinated
traversal; reconnect remains separately deferred under `SL-D-036`.

- Task ID: SL-D-035

## Immediate next action

Run or add controlled-NAT and, if available, physical two-network evidence
for the now-wired direct traversal path. Keep relay/reconnect out of scope.
Do not claim Internet NAT success from localhost evidence.

## Work completed

Recorded starting HEAD/status and mapped the Link source, tests, protocol,
security, operations, ADR, and deferred-capability boundaries. Completed ADR-
0065 and implemented the bounded signed exchange, RFC 8489 Binding codec,
caller-owned provider seam, same-socket Netty adapter, deterministic
coordinator, and human-mediated two-link onboarding path. The normal `host`
command emits SLO1 and waits for one SLA2 line; the normal `join` command
emits SLA2 and immediately starts the existing direct race. Real loopback UDP,
CLI two-process, and full Link checks pass; no physical or Internet traversal
claim is made.

## Current failures

The same-socket STUN and traversal handlers are verified over real loopback
UDP parents with QUIC codecs, but controlled NAT, Internet NAT behavior, and
physical two-network evidence do not exist yet. Reconnect is not part of this
bounded activation.

## SYN-049 fresh unattended two-worker acceptance — run #81 — 2026-09-06

Status: **COMPLETE / PASS**. Fresh run #81 passed generation-1 managed A/B
prepare/START, exact provider-native B wake on the original thread, capability
publication/validation, claim-scoped coding, immutable snapshot integration,
call-local completion requests, both terminal participants, WorkGroup
`COMPLETED`, and the integrated fixture suite.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-runs75-81.md`.

## Immediate next action

Preserve the PASS evidence and stop. Do not retry SYN-049, reopen SYN-051, or
start A1-to-A2 replacement validation without a separately bounded request.

## SYN-049 fresh unattended two-worker acceptance — runs #70–#74 — 2026-09-06

Status: **PARTIAL / ACTIVE**. Fresh runs #70 and #71 reached WorkGroup
`COMPLETED` with generation-1 managed A/B startup, capability publication and
validation, same-thread B wake, real claim-scoped coding, and both terminal
participants. Their independent integrated compiles failed on A-produced Java
contract shapes. Run #72 and #73 exposed disposable-runner continuation gates;
run #74 used those fixes but stopped on A generation-1 AppServer
`terminalDiagnostic=process_exit` during the real owner turn. Full acceptance
is therefore unproven.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-runs70-74.md`.

## Immediate next action

Perform a read-only investigation of run-#74 AppServer `process_exit` evidence.
Do not patch
production, reuse a target, repair `.synesis`, copy snapshots/worktrees,
invoke replacement/A2, or claim SYN-049 completion.

## SYN-049 fresh unattended two-worker acceptance — runs #68–#69 — 2026-09-06

Status: **PARTIAL / ACTIVE**. Fresh runs #68 and #69 passed generation-1
same-process managed preparation/START, real A/B claim admission, capability
publication and validation, provider-native B wake on the original thread,
and real claim-scoped implementation. Run #69 integrated B's immutable
application/API snapshot. The first unresolved boundary is the owner/reviewer
ordering cycle: B's review request for A is accepted before A's snapshot is
published, so Synesis withholds A completion while B waits for that snapshot.
The WorkGroup remains ACTIVE and integrated compile fails with A's absent
domain/persistence snapshot.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-runs68-69.md`.

## Immediate next action

Diagnose the owner/reviewer snapshot-ordering projection read-only before
another fresh target. Do not edit `.synesis`, copy snapshots/worktrees,
force completion, invoke replacement/A2, create a substitute B, or patch
production before the exact root cause is established.

## SYN-049 fresh unattended two-worker acceptance — run #59 — 2026-09-06

Status: **PARTIAL / ACTIVE**. Fresh run #59 used a one-host, one-launcher
generation-1 A/B runtime with the hash-identical installed MCP copy. Both
managed App Servers started, real Synesis MCP tools were available, A reached
`NEEDS_CAPABILITY`, the exact capability advanced through publication and
validation, and original B woke with `sameThread=true`. The first unresolved
boundary was after B consumed the exact review grant: Synesis remained
`SNAPSHOT_PENDING` and B reported `workspace_mismatch` because the published A
dependency existed only in the control checkout, not B's authorized worktree.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-run59.md`.

## Immediate next action

Diagnose snapshot materialization and review-workspace routing read-only before
another target. Do not bypass the review grant, copy files/worktrees, repair
durable state, invoke replacement, create A2 or a substitute B, or patch
production before the exact boundary is identified.

## SYN-049 fresh unattended two-worker acceptance — runs #55–#58 — 2026-09-06

Status: **PARTIAL / ACTIVE**. Run #56 proved the corrected accepted-review
gate and sent B's post-review continuation on the original Thread B, but B
still had no publishable downstream snapshot. Fresh runs #57–#58 then showed
trusted provider turn completion without any claimed mutation or capability
request; no run-scoped Synesis MCP child appeared in #58. B wake, downstream
work, review, integration, and WorkGroup terminalization remain unproven.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-runs55-58.md`.

## Immediate next action

Diagnose the fresh run-scoped Codex App-Server-to-Synesis-MCP tool-admission
boundary read-only before another target. Do not bypass MCP, copy worktrees,
reuse or repair a lane, invoke replacement, create A2 or a substitute B, or
patch production before the exact boundary is identified.

## Work completed

Corrected the invalid owner-side implementation-validation projection so it
exposes the provider's required decision instead of manufacturing an
incomplete response. Added a focused regression, rebuilt and hash-verified
the distribution, and ran fresh #47–#54 fixtures. The run-54 provider was
uninstalled through the supported flow.

## Current failures

Run #45 exposed and fixed a production projection/schema mismatch. Run #47
exposed a harness-only continuation-grant gate. Run #54 reached real A/B
continuations but stopped with B ACTIVE and the WorkGroup ACTIVE after an
accepted review request. No final B review/finish or WorkGroup
terminalization is proven.

## SYN-049 fresh unattended two-worker acceptance — run #44 — 2026-09-06

Status: **PARTIAL / ACTIVE**. Fresh run #44 passed current artifact and JDK25
process-local compatibility, fresh lawful A/B setup, same-process generation-1
managed preparation/START, same-AppServer provider-thread creation, ownership,
ACTIVE promotion, and initial provider turn completion. It stopped at the
first material boundary: both managed App Servers reported that the configured
native `synesis` MCP child closed during `initialize`. Neither worker had a
callable Synesis namespace, so no capability request, publication, wake/consume,
coding, integration, or terminalization was lawful.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-run44.md`.

- Task ID: SYN-049

## Immediate next action

Perform a bounded read-only diagnosis of the configured native Synesis MCP
child-startup/handshake boundary before another fresh target. Do not reuse or
repair run #44, bypass MCP, copy worktrees, or patch production speculatively.

## Work completed

Recorded fresh run #44, verified current artifact hashes and JDK25 host
preflight, verified supported provider cleanup, and preserved exact A/B
identities and generation-1 lifecycle evidence. No production source change
was made during the runtime attempt.

## Current failures

Run #44's App Server-to-Synesis-MCP child handshake failed before Synesis MCP
tools were exposed. The target and A/B worktrees are clean; no capability
request, B wake, claim mutation, integration, or WorkGroup terminalization was
proven.

## SYN-049 fresh unattended two-worker acceptance — runs #40–#42 — 2026-09-06

Status: **PARTIAL / ACTIVE**. Fresh runs passed provenance and host
compatibility, lawful A/B admission, generation-1 same-process managed
preparation/START, same-AppServer provider-thread creation, ACTIVE promotion,
provider-native B wake, A's real claimed implementation/publication, and
capability validation. Run #42 confirmed durable provider state for both
exact generation-1 threads. It stopped before lawful B downstream
implementation/completion, final review/integration, and WorkGroup
terminalization because the integrated producer types were not visible in
B's managed worktree; B's supported refresh correctly returned
`overlapping_claim` / self-overlap.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-runs40-42.md`.

- Task ID: SYN-049

## Immediate next action

Perform a separate read-only diagnosis of managed dependent-worktree snapshot
consumption and the self-overlap recovery boundary before another fresh
acceptance lane. Do not reuse run #42, repair its state, start a substitute
worker, invoke replacement, or patch production speculatively.

## Work completed

Recorded runs #40–#42, verified the run-42 durable projection and exact
provider continuity records, verified the supported provider cleanup result,
and reran the three focused regressions for the narrow managed-continuation
correction. No production source change was made during the runtime attempt.

## Current failures

SYN-049 remains incomplete at lawful dependent-worker snapshot consumption:
B's managed worktree contains only its skeleton after A publication, and
re-announcing B's existing claim is correctly rejected as self-overlap. The
target control checkout is clean; no B completion, final integration, or
WorkGroup terminalization was proven.

## SYN-049 fresh unattended two-worker acceptance — run #28 — 2026-09-06

Status: **FAIL at production worktree fence / ACTIVE / PARTIAL**. Fresh
skeleton-based run #28 passed provenance, JDK25 process-local compatibility,
lawful A/B setup, same-process generation-1 preparation and START, real A
implementation/publication, and same-thread provider-native B wake. The exact
same-thread B continuation then failed with `lifecycle_worktree_mismatch`.
Read-only evidence shows B's durable binding was rewritten to a recovery
worktree/base commit while the original B provider process still referenced its
original worktree. No B consumption, final integration, or terminalization was
reached.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-run28.md`.

- Exact next action: diagnose the binding-recovery/reconciliation race behind
  `lifecycle_worktree_mismatch` read-only and with focused tests; do not reuse
  run #28, invoke replacement, start a Worker B substitute, or perform state
  surgery.

## SYN-049 fresh unattended two-worker acceptance — run #25 — 2026-09-06

Status: **ACTIVE / PARTIAL**. Fresh run #25 passed corrected generation-1
managed setup, same-process prepare through START, original-thread provider
native B wake, capability publication/validation, real provider turns, exact
ownership, and read-only provider durability for Threads A and B. Both
lifecycle checkpoints ended `COMPLETED` at generation 1. The durable
collaboration projection still leaves Participant A `ACTIVE` with WorkIntent A
`ANNOUNCED`, so WorkGroup terminalization was not proven.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-06-run25.md`.

- Exact next action: diagnose the completion/finish projection for the exact
  active A lane read-only; do not reuse run #25, invoke replacement, start
  Worker B, or perform `.synesis` surgery.

## SYN-049 fresh unattended two-worker acceptance — runs #12–#16 — 2026-09-05

Status: **FAIL at the provider-native wake boundary; ACTIVE / PARTIAL**.
Fresh runs proved generation-1 same-process preparation and START, live
managed B attachment after its initial `NEEDS_CAPABILITY` turn, exact A
publication, and 60 clean wake-relay scans. The exact B actionable candidate
was present while the production host reported `attachmentAlive=true`, but no
provider-native B continuation was dispatched. No B consumption, integration,
or terminalization evidence exists.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-05-run12-run16.md`.

- Exact next action: diagnose the production wake-admission/dispatch boundary
  read-only using the captured run #16 evidence; do not reuse any target,
  invoke manual wake, create A2/B replacement, or patch production before the
  exact rejection path is identified.

## SYN-049 fresh unattended two-worker acceptance — runs #10 and #11 — 2026-09-05

Status: **ACTIVE / PARTIAL**. Two completely fresh acceptance attempts were
stopped at the first material runtime boundary. Run #10 stopped after Worker
B's generation-1 App Server exited during START. Run #11 stopped after both
real App Servers reported that the native Synesis MCP child failed its
`initialize` handshake (`connection closed: initialize response`). No
capability exchange, provider-native wake, claim mutation, integration, or
terminalization was reached.

Evidence: `docs/evidence/syn049-fresh-unattended-two-worker-2026-09-05-run10-run11.md`.

- Exact next action: perform a separate read-only child-startup compatibility
  diagnosis to determine whether the native MCP child independently hits the
  known AF_UNIX boundary. Do not propagate the workaround, patch production,
  reuse either target, or start another acceptance lane before that diagnosis.

## Historical: SYN-049 fresh unattended two-worker acceptance — 2026-09-05

Status: **ACTIVE / PARTIAL**. This is the sole active task. Fresh run #08
passed lawful A/B admission, generation-1 managed startup, B's structured
capability wait, A's exact publication and integrated snapshot, and the
same-node binding projection regression. It then stopped on the first
material runtime failure: the harness received `lifecycle_binding_stale`
while polling A after its asynchronous turn; A's turn was interrupted during
normal cleanup and B never consumed the published capability. No terminal
WorkGroup acceptance was reached. Historical fixtures and completed SYN-051
evidence remain read-only evidence.

- Task ID: SYN-049

## Immediate next action

Preserve run #08 evidence and do not reuse or repair its target. The next
attempt must first resolve the reproduced `lifecycle_binding_stale` boundary
in a separately verified source slice, then use a new target; do not continue
this lane or invoke replacement.

## Historical: SYN-051 fresh two-worker/restart acceptance — 2026-09-05

Status: **PASS-A / COMPLETE for bounded SYN-051 managed continuity**.
Provenance and JDK25 process-local compatibility passed. A fresh supported A/B
setup proved distinct identities, claims, generation-1 pending preparation,
cross-proof rejection, Job containment, same-connection MCP promotion,
same-AppServer thread creation, exact ownership, ACTIVE, real claim-aligned
turns, trusted `turn/completed`, `persistenceReady=false -> true`, and
read-only provider DB/history/rollout durability for both workers. Each worker
then passed trusted generation-1 death, fresh-proof generation-2 replacement,
exact same-thread resume, and a second real turn while the other successor
remained live. Evidence:
`docs/evidence/SYN-051-fresh-two-worker-2026-09-05.md`.

## Immediate next action

Preserve the SYN-051 PASS-A evidence; do not reopen the accepted managed
runtime or run full SYN-049 acceptance without a new explicit task.

## Historical: SYN-051 fresh Worker-A A1/A2 runtime validation — 2026-09-05

Status: **ACTIVE / PARTIAL (runtime PASS-A)**. One fresh lawful lane passed provenance, JDK25
process-local compatibility, same-process `prepareFirst -> START`, pending
authority quarantine, exact AppServer/MCP Job containment, same-AppServer
`thread/start`, ownership, broker pin, ACTIVE promotion, two trusted exact-claim
turns, controlled generation-1 death, exact generation-2 resume, and provider
DB/history/rollout durability for the same Thread A. A narrow hard-stop
checkpoint race was fixed and verified before the final lane. The complete
workspace lifecycle-codex suite passed. The in-scope aggregate MCP classes
`McpServerTest`, `CodexLinkedWorktreeRootSelectionRegressionTest`,
`McpStage2BSlice1Test`, and `Slice4FailureScenariosTest` also passed under the
same bounded JDK25 configuration. Explicit two-process and older SYN-039
acceptance classes remain unrun by scope.

Evidence: `docs/evidence/SYN-051-fresh-worker-a1a2-2026-09-05.md`.

## Immediate next action

Preserve the runtime PASS-A evidence; the remaining repository criterion is
separately scoped two-worker/restart acceptance. Do not start Worker B or run
full SYN-049 acceptance in this bounded objective.

## Work completed

The in-scope aggregate MCP classes passed after their long-running Git/provider
fixtures completed; the 33 `McpServerTest` methods and 13 linked-worktree
methods also passed in isolated fresh JVMs. Explicit two-process and older
SYN-039 acceptance classes were not run.

## Current failures

No assertion failure remains in the in-scope MCP tests. The unresolved gate is
the repository task's separately scoped two-worker/restart acceptance, not a
managed-continuity invariant in this single-worker A1/A2 objective.

- Task ID: SYN-051
- Status: ACTIVE / PARTIAL (runtime PASS-A)

## SYN-051 fresh Worker-A persistence validation — 2026-09-05

The bounded run is **PARTIAL**. A successful fresh lane proved same-process
`prepareFirst → START`, generation-1 `PENDING_ACTIVATION`, same-App-Server
`thread/start`, exact ownership and broker pin, `ACTIVE`, one real
claim-aligned Codex turn, trusted `turn/completed`, and
`persistenceReady=false → true`. Provider SQLite/history/rollout durability
for the exact Thread A was confirmed read-only. The target mutation was only
the exact claimed file in the assigned worktree.

PASS-A is not claimed: pending-state authority denial and direct child-to-exact
Windows Job membership were not independently captured in this run. SYN-049
remains `PARTIAL`; SYN-051 remains `ACTIVE / PARTIAL`. No production source,
credential, global setting, replacement, A2, Worker B, full acceptance, or
push occurred.

Evidence: `docs/evidence/SYN-051-fresh-worker-a-persistence-2026-09-05.md`.

## Immediate next action

Checkpoint and stop. Do not start Worker B or invoke A1-to-A2 replacement;
future work requires separate authorization for the two narrow evidence gaps.

## Verification

JDK25 process-local selector/HTTP preflight and exact artifact hashes passed.
The successful lane used Binding A `session-e3783a86-78c4-41c5-965c-6fb10e854781`,
Thread A `01a06f47-0e24-7ce2-9e9f-bf4fdb36c18f`, and turn
`01a06f47-0f2d-7a71-aa81-91a0ea83d707`. Normal host close left the attachment
`DISCONNECTED` and lifecycle checkpoint `STOPPED`; provider ownership remained
`persistenceReady=true`. A preliminary disposable lane was not reused.

## SYN-051 build-JVM compatibility and provenance — 2026-09-05

The command-local `GRADLE_OPTS` carrier succeeded with `TEMP/TMP=C:\t` and
`-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`. Gradle startup and
the `help` task passed. Focused workspace and MCP test selections compiled but
stalled at their test tasks without assertions and were stopped as incomplete.

The clean `:cli:installDist` build passed, including the native MCP launcher.
Produced and installed workspace, MCP, CLI, and native launcher hashes match
exactly. `BUILD_COMMIT=UNKNOWN` remains documented. No global setting,
repository build configuration, production source, target project, or managed
runtime changed.

Evidence: `docs/evidence/SYN-051-build-jvm-provenance-2026-09-05.md`.

## Immediate next action

Historical next action: run one fresh lawful SYN-051 Worker-A validation using JDK25 with the same process-local AF_UNIX
property, keeping `prepareFirst` and START in the same live caller; do not start Worker B or invoke A1-to-A2
replacement.

## Work completed

Proved the build-JVM compatibility carrier, completed clean build/install, and established exact produced-to-installed
artifact provenance.

## Current failures

Focused workspace and MCP test tasks remain incomplete because they stalled after compilation without assertion output.
Package-wide acceptance remains unrun.

## Verification

`gradlew --version`, `help`, clean `:cli:installDist`, deferred validation, artifact hash equality, target identity, and
persistent-environment checks passed. No Worker A or managed runtime was run.

## SYN-051 process-local preflight and clean-build gate — 2026-09-05

The compatibility evidence was committed as `bff97418672e2197cd00f293409d68446918bdd8`.
Under JDK25 `25+36-LTS`, the authorized process-local property
`-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe` made the required
`Selector.open()` and minimal IPv4/IPv6 `HttpServer.create/start/stop` sanity
checks pass. No global Java/network/Codex setting changed.

The required clean rebuild/install then failed in the independent Gradle/JDK
process before task execution with `java.io.IOException: Unable to establish
loopback connection`. The stop rule therefore forbids propagating the
diagnostic property into build logic or broadening the workaround here.
Artifact provenance was not freshly re-established. No fresh Worker-A lane was
created in this slice; the target and its pre-existing `probe-runtime/` were
untouched.

Evidence: `docs/evidence/SYN-051-process-local-preflight-build-blocker-2026-09-05.md`.

## Immediate next action

Obtain separate authorization for a bounded process-local compatibility path for the build/Gradle JVM itself, then
perform a fresh clean build/install and provenance lock before creating any new Worker-A lane; do not launch or reuse a
lane.

## Work completed

Committed the standalone compatibility evidence, passed the exact JDK25 selector and HTTP preflight, verified the target
baseline, and attempted the required clean build without global settings changes.

## Current failures

Gradle's JDK25 process independently reproduces the Windows AF_UNIX loopback failure before task execution. Existing
installed hashes are observations only and are not a new provenance lock for this slice.

## Verification

`git diff --check`, deferred validation, JDK25 selector preflight, JDK25 IPv4/IPv6 HTTP preflight, target Git identity,
and environment-setting checks passed. Clean `:cli:installDist` stopped before task execution. No fresh lane or managed
runtime was run.

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

## Immediate next action

Review the standalone compatibility evidence and obtain separate authorization for one new Worker-A run on existing
JDK25 with a process-local UNIX socket directory and matching primitive preflight; do not launch or reuse a lane in this
slice.

## Work completed

Completed standalone JDK25/JDK21 comparison and source-supported AF_UNIX reduction; recorded exact results and traces.
Production unchanged. No managed acceptance run.

## Current failures

Both default JDKs fail selector initialization at UNIX socket connect in user TEMP. Process-local diagnostic directory
resolves all 13 primitive cases on both JDKs. User-TEMP socket cleanup failed; root cause and managed runtime remain
unverified.

## Verification

Commands: external run_probe.py baseline, run_probe.py ipv4, run_unix.py; exact command arrays and all results in
evidence. Run git diff --check and scripts/agent-checkpoint.ps1; no Doctor, MCP, or production test invocation.

## Historical handoffs (superseded by the current entry above)

# Current Task

## SYN-051 fresh single-worker managed-runtime validation — 2026-09-05

The bounded pass is **PARTIAL / STOPPED ON FIRST MATERIAL HARNESS/HOST
FAILURE**. Source/runtime provenance and target identity were verified, and a
fresh lawful Worker-A lane was created. The same trusted caller constructed one
live `ManagedCodexProcessLauncher` and the production runtime host, but JDK 25
failed at `HttpServer.create(...)` with `Unable to establish loopback
connection` caused by `SocketException: Invalid argument: connect` in
`PipeImpl`/`WEPollSelectorProvider`. The failure occurred before
`prepareFirst`, so no START, proof, generation, attachment, App Server, MCP,
Job, provider thread, turn, or persistence transition was reached.

The fresh lane is connection
`syn051-live-a-40ff76be-a20b-4148-99d1-d04a153152b7`, participant
`agt_81dcd2a3-5d43-3cb5-9068-6a68b5f2f699`, WorkIntent
`c340aab7-376f-3172-b43f-2580c3045603`, binding
`session-756b644a-bac0-479c-be24-577581f286c6`, claim
`probe-runtime/persistence-live-a-40ff76be-a20b-4148-99d1-d04a153152b7.txt`.
The previous preparation-only lane was not reused. The host closed normally;
replacement, A2, and Worker B were not invoked. No production source changed.

Evidence: `docs/evidence/SYN-051-live-worker-a-loopback-blocker-2026-09-05.md`.

## Historical next action

Separately resolve or investigate the JDK 25 loopback `PipeImpl`/
`WEPollSelectorProvider` invalid-argument failure, then run a new fresh lawful
Worker-A validation with `prepareFirst` and START in the same trusted live
caller. Do not reuse this lane, start Worker B, invoke A1-to-A2 replacement,
or broaden acceptance.

## Work completed

Recorded the first-material-failure result in the dedicated evidence file and
durable agent records. Verified source/runtime provenance, target identity,
fresh-lane identity, normal host cleanup, and the absence of a managed
attachment or new lifecycle checkpoint. No production source was changed.

## Current failures

JDK 25 cannot create the disposable JDK loopback HTTP boundary in this
environment: `HttpServer.create(...)` fails with `Unable to establish loopback
connection` and `SocketException: Invalid argument: connect` in
`PipeImpl`/`WEPollSelectorProvider`. This blocks reaching `prepareFirst` and
START; it is not provider-runtime evidence.

## SYN-051 caller-contract-only preparation validation — 2026-09-05

The bounded pass is **PASS-A for the preparation boundary; SYN-051 remains
ACTIVE / PARTIAL overall**. The prior requested binding
`session-8b82fd05-6c97-4f8f-bc1a-2a1ce0c56c2a` was inspected through supported
state and correctly left untouched because it was already `FAILED` with
`managed_attachment_not_prepared` and had no attachment. Exactly one new
lawful Worker-A lane was created through the production session and
collaboration services:

- Binding: `session-444ee24b-1ff8-4111-b818-787f900088a0`
- Participant: `agt_c74b5d08-5a0b-3017-bce9-652498da186c`
- WorkIntent: `befca654-af37-31cb-a605-f6bfbec7a8f0`
- Claim: `probe-runtime/caller-contract-preparation-62178e4d-e95a-4529-845a-22ae1d8fbfff.txt`

The actual production call
`ManagedCodexProcessLauncher.prepareFirst(AuthorityContext)` succeeded and
created exactly one generation 1 `PENDING_ACTIVATION` record. The durable
record contains only the proof digest
`897b305ba8aa01fc162a5466a4c0de49c35250a73161717a2c362b2d2e118591`; the raw
proof was held only in the trusted launcher's volatile preparation. The
provider thread and provider-thread ownership remain absent. Binding,
participant, WorkIntent, and claim preservation all passed. No START, Codex,
App Server, MCP, Job, or provider-thread operation was performed, and no
production source changed.

Evidence: `docs/evidence/SYN-051-fresh-worker-a-validation-2026-09-04.md`.

## Historical next action

If separately authorized, keep `prepareFirst` and START in the same trusted
live caller and run only the next bounded single-worker runtime step; do not
call START from a new process against this pending record, do not start Worker
B, and do not run broader acceptance.

## SYN-051 fresh Worker A managed-runtime validation — 2026-09-04

The bounded runtime pass is **PARTIAL / STOPPED BEFORE MANAGED APP SERVER
LAUNCH**. The clean final build/install and 35 focused workspace/lifecycle
tests passed; the selected MCP test task stalled without assertion output and
was stopped. A fresh target authority lane was created for binding
`session-8b82fd05-6c97-4f8f-bc1a-2a1ce0c56c2a`, participant
`agt_9db6eb10-fff3-3711-8f97-1d0b75ed40de`, WorkIntent
`45cb6507-0dd0-397d-aecb-51e8754b069e`, and claim
`probe-runtime/persistence-a.txt`. The caller submitted START without first
calling the managed launcher's `prepareFirst`, so the owner returned
`managed_attachment_not_prepared` before App Server launch. No proof,
generation, App Server, MCP child, Job, provider thread, turn, or mutation
was created. Historical target state and source production code were not
changed.

Evidence: `docs/evidence/SYN-051-fresh-worker-a-validation-2026-09-04.md`.

## SYN-051 generation-1 provider persistence boundary — 2026-09-04

The bounded production slice is **PARTIAL**. The managed App Server now
creates generation 1 with `thread/start` inside the existing pending MCP
quarantine, acquires and broker-verifies the exact returned thread, binds it
to the pending attachment, and activates only after that sequence. Provider
thread ownership now carries a durable, idempotent `persistenceReady` fact;
`thread/start`, same-process read, and graceful exit do not set it. The
managed lifecycle marks it only after a trusted exact `turn/completed` event.
Successor/replacement paths remain blocked while provisional and require the
persisted owner plus trusted death evidence.

Focused changed-source workspace tests and clean `:cli:installDist` passed;
installed workspace/MCP/CLI jars match their produced hashes. The selected
workspace and MCP package-wide test tasks stalled and were stopped, so they
are incomplete evidence. No fresh managed Worker A, Worker B, A1/A2, or full
acceptance was run.

## Historical next action

Do not retry this stopped pass. Decide or separately authorize the supported
managed caller sequence that invokes `prepareFirst` before START, then run a
new fresh single-worker validation only if authorized; do not start Worker B,
A1-to-A2 replacement, or full acceptance.

## SYN-051 provider-thread provenance review — 2026-09-04

The read-only investigation is **PASS-B — architecture simplification found**
with full managed compatibility still unverified. Codex 0.153.0 creates a
start-only thread in the originating App Server but does not provide a
successor-loadable provider rollout/history until a persisted turn or
equivalent persistence operation exists. The exact managed failure was caused
by treating that live ID as a durable handoff; provider state roots matched.
No production source, `.synesis` state, credentials, historical evidence,
Worker B, or remote state changed. Evidence is in
`docs/evidence/SYN-051-provider-thread-provenance-2026-09-04.md` and ADR-0064.

## Historical next action

Implement the bounded SYN-051 first-generation lifecycle change: let managed
App Server call `thread/start`, then broker-acquire/pin the returned thread and
require one persisted turn before successor/replacement acceptance. Rebuild,
install, and run only focused provider/lifecycle verification; do not start
Worker B or full acceptance.

## SYN-051 bounded real-runtime validation — 2026-09-04

The pass is **PARTIAL / STOPPED ON FIRST MATERIAL FAILURE**. The current
source at `088cb239f2e2109c4dddd40cbaee36c158337e82` built and installed with
matching artifact hashes. A fresh lawful Worker A binding and exact provider
thread ownership were established, and the production managed launcher
created a generation-1 pending attachment inside a Windows Job. Codex
`0.153.0` rejected the pinned `thread/resume` in the fresh App Server with
`thread not loaded`; therefore A1 never reached a real managed turn or
activation. The lifecycle failed closed and its startup cleanup produced a
trusted generation-1 death receipt. This is not the requested controlled
App-Server-root crash evidence. The one-shot harness did not fail-fast after
the exception and later reached the production replacement call, creating a
generation-2 pending record from that startup-cleanup receipt; this was not
accepted as A1-to-A2 evidence and was terminalized through the public service.

The fresh aborted attachment was terminalized through the public application
service at generation 2 for safe stopping. The historical binding remains
generation 1 `ACTIVE` with no receipt and was not touched. Worker B, A/B
isolation, A1/A2 restart, and full acceptance were not run. No production
source changed and no push occurred. The harness-control defect is recorded
as a test-execution failure, not a production lifecycle conclusion.

## Historical next action

Perform a separately authorized, read-only provider-thread provenance review
for why a bootstrap-created Codex thread is not loadable by a new App Server
process. Do not retry managed A1/A2, relax proof gating, recover the
historical generation, start Worker B, or broaden SYN-051 until that cause is
resolved.

## SYN-051 production lifecycle fix — 2026-09-04

The bounded lifecycle slice is **PARTIAL**. A trusted managed-process
supervisor now produces generation-scoped, non-secret death evidence only
after root exit, Job teardown, and an empty Job process count are proven. The
managed launcher persists that receipt and can replace the exact active or
disconnected generation without accepting the old raw proof. Replacement
preserves the broker binding, provider thread, ownership, participant,
WorkIntent, and claims while atomically fencing the old generation and minting
a fresh proof.

The historical `SkibidiToilert` generation 1 has no receipt produced by this
seam. Its process is gone, but it is therefore not lawfully replaceable; no
receipt was manufactured and no durable fixture state was edited. No real
A1-to-A2 rerun was performed.

## Historical next action

Resolve the workstation Gradle loopback failure, rebuild and install the
current source with matching provenance, then run only the focused lifecycle
and MCP tests. A fresh lawful real-runtime probe may follow only after that;
do not touch the historical generation or start Worker B.

## SYN-051 production compatibility fix — 2026-09-04

The bounded compatibility slice is **PARTIAL / real boundary PASS**. A
proof-bearing Synesis MCP child may now establish a transport while its
attachment is `PENDING_ACTIVATION`, with no authority; each managed tool call
re-reads the exact binding and generation and remains blocked until trusted
lifecycle activation promotes the same connection. A per-generation OS lock
prevents duplicate managed transports. The managed Codex launcher also
forwards the non-secret connection selector through Codex's explicit
per-server environment override, while the proof remains on `env_vars`.

Focused tests, strict compilation, install provenance, and a fresh real
`SkibidiToilert` A retry passed the compatibility boundary: MCP startup became
`ready`, the App Server resumed the exact broker-pinned thread, the MCP child
was observed inside the same Windows Job, and the real turn reached Synesis.
The disposable turn used a mismatched `compat-a5` claim against the durable
`compat-a4` lane and therefore failed closed at `ensure_session`; this is
harness input failure, not a managed transport failure.

## Historical next action

Checkpoint and commit this compatibility slice, preserve the real evidence,
and stop before Worker B, A/B isolation, A1/A2 restart, or full acceptance.

## SYN-051 runtime-boundary follow-on — 2026-09-03

The bounded production slice is **PARTIAL**. Java 25 FFM now supplies a
Windows-only Job Object supervisor with suspended root creation, assignment
before resume, kill-on-close, native waits, and Job accounting. Managed
Codex lifecycle launch and teardown use that supervisor. Managed proofs use
launch-local `env_vars` from the App Server environment and remain pending
until exact broker-pinned thread response/readback verification. Focused
source/build, lifecycle, attachment, admission, and real root-plus-descendant
containment checks pass; real Codex A/B proof delivery and restart acceptance
remain outstanding.

## Historical next action

Rebuild and install the current source with matching hashes, then run only the
focused real Codex child-proof, A1-to-A2 restart, fresh-proof, and Worker B
isolation probe. Do not run full task-tracker acceptance.

## Work completed

Implemented the Windows Job Object supervisor, lifecycle integration, pending
managed-proof activation fence, launch-local Codex `env_vars` carrier, focused
tests, ADR-0062, and updated SYN-051 evidence/planning records.

## Current failures

No focused implementation test failure remains. The real managed MCP
transport boundary passed, but the one disposable model turn did not perform
the requested file mutation because its prompt selected a path outside the
durable lane claim. Full A/B, restart, and task acceptance remain unrun.

## SYN-051 shared-normal-home production slice — 2026-09-03

The explicit user authorization selects the PASS-B shared-normal-provider-home
architecture. Historical feasibility rejection records remain unchanged; this
task now implements the compensating Synesis controls: atomic unique
`(provider, providerThreadId)` ownership, proof-gated managed admission,
broker-derived immutable thread pinning, generation fencing, and owned
process-tree lifecycle. Ordinary `SESSION_BOUND` behavior, exact authority,
the ten-tool surface, and `UNSAFE_FILE_AUTH` semantics for isolated homes are
preserved.

## Historical next action

Implement and test the durable provider-thread ownership record/store first,
then wire its exact thread into managed attachment issuance and admission.

## SYN-051 Windows Job Object follow-on — 2026-09-03

The bounded process-tree spike is **PARTIAL**. Suspended launch plus
assignment-before-resume contained generic descendants and real Codex
AppServer/MCP children in independent Jobs. App Server-only death left MCP
alive until Job teardown, which killed it and proved the Job empty. Generic
controller kill-on-close passed, B stayed usable during A teardown, and A2/B2
resumed exact threads with fresh proofs. Ambiguous query failure, a real
two-successor loser cleanup, and real Codex post-supervisor-crash recovery
remain untested. Evidence is in
`docs/evidence/SYN-051-job-object-process-tree-feasibility-2026-09-03.md` and
ADR-0059. No Synesis MCP path was invoked.

## Historical next action

Preserve `UNSAFE_FILE_AUTH`. Obtain separate authorization for the three
missing edge cases; do not change production code or run full acceptance.

## SYN-051 broker follow-on — 2026-09-03

The bounded broker-pinned stock-Codex feasibility spike is **PASS-B**. A
disposable lifecycle-only broker using the normal Codex home/auth successfully
bound each dedicated App Server to one exact persisted provider thread and
rejected wrong-thread operations before Codex received them. A/B concurrency,
proof isolation, exact restart/resume, live-owner refusal, ambiguous-liveness
refusal, and one-winner successor locking passed. App Server-only death left
the disposable MCP child orphaned (`parentPid=-1`), so owned process-tree
teardown is the missing production primitive. Preserve `UNSAFE_FILE_AUTH` and
do not implement integration or run managed acceptance. Evidence is in
`docs/evidence/SYN-051-broker-pinned-thread-feasibility-2026-09-03.md` and
ADR-0058. The broker remained outside this repository and the Synesis MCP
attachment path was not invoked.

## Historical next action

Obtain or design-review a verified owned process-tree supervisor and authorize
a fresh real Synesis attachment probe; until both exist, preserve the
PASS-B feasibility result, the dedicated-home design, strict authority
resolution, and `UNSAFE_FILE_AUTH` without production changes.

## SYN-051 Bounded stock-Codex MANAGED_CONTINUITY implementation — 2026-09-03

- Task ID: SYN-051
- Status: ACTIVE; the existing bounded implementation is preserved, but
  managed acceptance and further production auth-policy changes remain blocked
  after the safe-auth hard stop and the shared-home follow-on rejection.
- Scope: Codex App Server only; one retained dedicated `CODEX_HOME` and one
  dedicated App Server per logical worker; exact thread resume; selected
  `env_vars` proof delivery; hash-only durable proof; rotation and monotonic
  generation fencing; existing strict authority and lifecycle seams.
- Non-goals: Claude, anonymous continuity, shared App Server, arbitrary fleet
  launch, upstream Codex changes, Review/Doctor redesign, SYN-049 closure,
  manual IDs/state, worktree copying, and push/tag/release.
- Required gate: inspect and reuse existing runtime/provider/binding/authority
  seams before edits, then prove safe isolated Codex authentication, matching
  artifact provenance, real authenticated model turn, exact restart/resume,
  stale-generation rejection, and preservation of logical coordination state.
- Follow-on result: the shared normal CODEX_HOME alternative is rejected for
  worker isolation. Evidence is recorded in
  `docs/evidence/SYN-051-shared-normal-home-feasibility-2026-09-03.md` and
  ADR-0057; no production code changed.

## Historical next action

The bounded shared-normal-home follow-on is complete and classified `FAIL` for
the full worker-isolation hypothesis. Concurrent dedicated App Servers,
provider-owned normal authentication, launch-local MCP `env_vars`, short real
turns, and same-home process recreation worked operationally. A fresh B3
process nevertheless resumed A's persisted provider thread after A stopped.
Preserve `UNSAFE_FILE_AUTH`; do not run managed acceptance or weaken the
security boundary.

Artifact provenance is complete and recorded in
`docs/evidence/SYN-051-managed-continuity-provenance-2026-09-03.md`. The
provider-authentication compatibility pass is recorded in
`docs/evidence/SYN-051-keyring-auth-compatibility-2026-09-03.md`. Codex
`0.145.0` reports the normal home as file-backed, explicit keyring mode has no
credentials, and a fresh isolated home fails its real model turn with HTTP

401. Preserve the hard stop; do not copy `auth.json`, perform destructive
     provider migration, or run managed Codex acceptance.

The source map is recorded in
`docs/evidence/SYN-051-source-insertion-map-2026-09-03.md`. The first
implementation slice now provides versioned hash-only attachment records,
cryptographically random proof issuance, exact binding/thread/generation
authentication, atomic replacement fencing, dedicated Codex homes, selected
`env_vars` delivery, MCP startup authentication, continuity-mode reporting,
and provider guidance. Focused tests and strict Javadocs pass.

The committed build/install provenance is recorded in
`docs/evidence/SYN-051-managed-continuity-provenance-2026-09-03.md`.
Source/runtime/install hashes match. The current Codex home is file-backed
(`auth.json`) without a keyring credential. The provider's Windows
keyring-backed storage is scoped to each `CODEX_HOME`, so the existing login is
not a supported shared carrier for fresh managed homes. Safe authenticated
managed runtime acceptance is blocked and no credential copy is permitted.

## Work completed

Verified the installed `codex-cli 0.145.0` behavior and the existing
hash-matched SYN-051 distribution. Normal file-backed Codex authentication
completed a minimal model turn. Explicit keyring status found no credentials;
a fresh isolated `CODEX_HOME` contained no copied auth and failed its real
model turn with HTTP 401. Exact provider source confirms Windows keyring auth
uses a per-home key and encrypted home-local auth store. Evidence is recorded
in `docs/evidence/SYN-051-keyring-auth-compatibility-2026-09-03.md`. No
production source, credential, `.synesis` state, or historical fixture changed.

The shared-normal-home follow-on used only disposable provider and boundary
observers. A/B proof delivery and concurrent real turns passed, but provider
thread ownership failed after owner shutdown: B3 resumed A's persisted thread
with a fresh process proof. The first disposable attempt caused two temporary
Codex trust-entry additions; exact cleanup restored the normal config's
pre-probe SHA-256 at the cleanup point. A later read found an unattributed
length/hash change with no probe references; it was not overwritten blindly.
Evidence is recorded in
`docs/evidence/SYN-051-shared-normal-home-feasibility-2026-09-03.md`.

## Current failures

The safe isolated provider-authentication gate failed. The current normal
login remains file-backed and the managed adapter must continue to classify it
as `UNSAFE_FILE_AUTH`. The provider's Windows keyring path is not an
account-wide carrier across fresh isolated homes; using separate homes would
require provider credential duplication or a supported cross-home mechanism,
neither of which is established. A focused Gradle regression invocation also
failed before test execution with `java.io.IOException: Unable to establish
loopback connection`, including after the IPv4 preference retry. Do not run
managed acceptance or weaken the security boundary.

The shared-home alternative does not remove the managed-continuity blocker:
the provider store is operationally concurrent but does not fence a persisted
thread to its original managed worker. The current Synesis attachment probe
was intentionally not invoked under the no-Synesis-in-source boundary. The
targeted Gradle catalog test also remained incomplete because the host could
not establish loopback; direct catalog inspection still reports exactly ten
raw tools.

## SYN-050 Provider-session continuity across MCP process restart — 2026-09-03

- Task ID: SYN-050
- Status: DONE / FEASIBILITY ACCEPTED; DESIGN_APPROVED / PASS-A FEASIBILITY /
  NO PRODUCTION EDITS.
  The provider-neutral core architecture is Result A. The stock Codex
  isolated-runtime experiment is PASS-A for conservative one-process-per-
  worker managed delivery; production continuity is still not implemented in
  this feasibility pass.
- Capability decision: ordinary Codex and Claude stdio remain
  `SESSION_BOUND`; `MANAGED_CONTINUITY` is approved as a supported profile and
  Synesis-supervised Codex App Server is its first target. A future provider-
  native assertion path is `NATIVE_CONTINUITY`. Static wrappers, anonymous
  brokers, process lineage, and model-visible bearers do not establish
  continuity. Evidence:
  `docs/evidence/SYN-050-protected-carrier-prototype-2026-09-03.md`,
  `docs/evidence/SYN-050-codex-app-server-child-launch-boundary-2026-09-03.md`,
  `docs/evidence/SYN-050-stock-codex-isolated-runtime-feasibility-2026-09-03.md`,
  `docs/evidence/SYN-050-managed-attachment-design-2026-09-03.md`, and
  `docs/evidence/SYN-050-provider-boundary-feasibility-2026-09-03.md`.
- SYN-049 status: PARTIAL; its Defect A and Defect B results remain separate
  and are not reopened.
- Scope: define and, only after a verified provider contract and accepted ADR,
  implement the smallest provider-neutral runtime-authentication mechanism for
  an authenticated replacement runtime to recover an existing Synesis logical
  worker. Preserve exact connection fencing and reject unrelated, concurrent,
  stale, replayed, and terminal recovery attempts.

## Historical next action

The bounded stock-Codex isolated-runtime investigation is complete and
recorded in `docs/evidence/SYN-050-stock-codex-isolated-runtime-feasibility-2026-09-03.md`.
Dedicated `CODEX_HOME` roots, worker-specific MCP configuration, selected
`env_vars`, concurrent A/B processes, exact same-home thread resume, and
A1-to-A2 proof rotation passed. A fresh home cannot resume an existing thread,
so each logical worker must retain its own provider home/thread store. The
earlier dynamic child-launch result remains a narrower `FAIL`: stock Codex
still has no dynamic thread/proof/extra-handle ingress. The current stock
isolated-runtime result is `PASS-A` for conservative v1 feasibility; do not
make production continuity edits, restart or re-admit the preserved SYN-049
fixture, or weaken exact authority.

## SYN-050 trace result

The normal MCP startup uses the explicit `SYNESIS_MCP_CONNECTION_INSTANCE_ID`
when supplied and otherwise generates a random UUID. The MCP handshake does
not consume Codex thread/conversation metadata. The Codex hook sees that
metadata in a separate process, while the static Codex MCP configuration does
not pass it to MCP. In App Server, the thread-owned MCP runtime creates the
configured stdio client through a sealed launcher; the local launch clears the
parent environment and applies only configured environment values. The durable
logical binding is
`ProviderSessionBindingService.Binding.sessionId`; participant and WorkIntent
identities derive from that binding, while the lease remains keyed to the exact
transport connection. The existing recovery path is an audited transfer to a
new participant/intent, not same-session reattachment. A disposable real Codex
App Server probe confirms exact thread correlation, per-thread MCP child chains,
dedicated A/B process isolation, and process restart/resume, but no
Synesis-managed per-worker proof can enter the exact child through the current
provider boundary.

ADR-0055 and
`docs/evidence/syn050-provider-session-continuity-capability-design-2026-09-03.md`
record the expanded capability evaluation. The provider-boundary feasibility
record adds the verified Codex/Claude ordinary-MCP and managed-launch matrix,
and the version-matched child-launch investigation.
A high-entropy, hash-backed, single-use capability is server-side expressible,
but ordinary providers offer no provider-controlled non-model-visible way for
the same conversation to receive and present it after restart. The isolated
protected-carrier model passed; the real Codex boundary did not prove private
proof delivery. Result A remains the smallest provider-neutral core
architecture, while the managed Codex design remains Result B and production
implementation remains blocked. No production files changed.

## SYN-050 protected-carrier prototype result

The disposable Windows harness at
`C:\Users\Liparakis\AppData\Local\Temp\syn050-protected-carrier-20260903-01`
used an anonymous inherited child-stdin pipe, random per-worker proofs,
hash-only durable state, challenge-response verification, generation rotation,
replay/race rejection, live/ambiguous/terminal fail-closed cases, and secret
hygiene checks. It passed 48 assertions for isolated Worker A/Worker B
attachment behavior.

The real installed `codex-cli 0.145.0` probe used temporary provider state. One
App Server hosted two exact threads and created two observed MCP child chains;
two dedicated App Servers hosted distinct A/B threads and child chains while
running concurrently. A request using A's thread ID on B's App Server was
rejected. After A1 stopped, A2 resumed the exact A thread and relaunched its
MCP child while B remained live. Every wrapper and child saw the static config
marker but not the App Server parent markers, thread context, or attachment
proof. The child-launch boundary therefore fails the private-carrier gate.
Full redacted evidence is in
`docs/evidence/SYN-050-codex-app-server-child-launch-boundary-2026-09-03.md`.

## Acceptance boundary

The eventual same-conversation restart must recover the original
participant/session and WorkIntent without duplicate claims or re-admission;
unrelated and concurrent processes must fail closed; races have one winner;
stale/replayed proof and terminal sessions remain fenced; and the recovered lane
must complete through the existing explicit-completion, review, integration,
and ten-tool lifecycle. This acceptance is not authorized until the provider
primitive exists.

No latest-session fallback, inferred authority, durable-state rewrite, worktree
copy, manual Synesis identifier, new MCP tool, or unrelated Review/Doctor
redesign is permitted. ADR-0055 records the completed design-gate result.

## Work completed

Mapped the source-backed durable `Binding.sessionId`/participant/WorkIntent
chain, the ephemeral MCP connection/process evidence, the exact resolver and
lease coupling, and the separate Codex App Server wake/thread path. Evaluated a
Synesis-issued high-entropy, hash-backed, single-use rotating capability, the
provider-authenticated/Synesis-managed/anonymous capability classes, and all
currently available Codex carriers. Formally approved `MANAGED_CONTINUITY` as
a product profile with Codex App Server as its first target, selected Result A
for the provider-neutral core, and recorded the Result B prototype outcome.
The isolated carrier passed, but the real provider carrier is not proven. No
production code, provider configuration, durable Synesis state, or fixture was
changed.

## Current failures

The ordinary Codex stdio provider primitive remains unavailable, and the
current App Server child-launch primitive is now a bounded `FAIL`: its
configured launcher has no thread-scoped private carrier and clears
parent-private environment before local MCP launch. A model-visible bearer
cannot provide reliable same-conversation retention or prevent another process
from using a copied credential, and the existing recovery path transfers to a
new participant/WorkIntent. Production implementation and SYN-049 terminal
acceptance remain blocked. `agent-doctor.ps1` retains its known historical
false-positive vague-continuation result and existing absolute-path warning;
neither is part of this continuity design.

## SYN-049 Pre-release explicit completion and dependency actionability — 2026-09-02

- Status: PARTIAL; preserve the completed Defect A/Defect B evidence and the
  fresh fixture. Do not re-admit or restart either worker.
- Task ID: SYN-049
- Scope: independently correct and verify explicit completion lifecycle
  semantics (Defect A) and structured dependency preservation/actionability
  through the rebuilt MCP path (Defect B). Preserve the existing capability,
  review, integration, provider, authority, and ten-tool architecture.

## Historical next action

Preserve the fresh `SynesisTaskTrackerRealAcceptance-20260903-08` evidence and
do not re-admit or restart either worker. Review the recorded post-compliance
review/session lifecycle blocker as a separate follow-up only if it is
explicitly promoted; no further SYN-049 production edit is justified.

## Acceptance criteria

- Defect A: multiple mutations and tests remain `IMPLEMENT`; only a
  call-local `completionRequested:true` request can project exact
  `finish_lane` after current-state revalidation; old modes/formats fail
  loudly; existing fences/outcomes and the ten-tool catalog remain intact.
- Defect B: structured dependencies survive the real rebuilt MCP admission,
  durable replay, and `NEEDS_CAPABILITY` projection; the existing capability
  request/publication/review/integration path makes the requester actionable.
- A fresh two-worker task-tracker acceptance proves both defects together with
  no manual IDs/state, worktree copying, protocol bypass, or speculative
  review/Doctor changes.

## Architecture and scope

This is a bounded evolution of the existing local modular monolith. ADR-0053
owns Defect A and ADR-0054 owns Defect B. No new dependency graph, service,
MCP tool, provider integration, or review/Doctor redesign is authorized.
`GOONSQUAD`, KogMaw, and all historical fixtures remain preserved evidence.

## Current evidence

The source currently forwards `knownDependencies` through MCP admission,
`WorkIntent`, `WORK_INTENT_ANNOUNCED`, codec replay, and
`CapabilityRequestProjection`; no active source drop is proven. A clean
distribution from source `a789af671e6bf168436a61b4119d305af9db407e` was
installed by absolute artifact path and the fresh MCP regression passed. The
full evidence is in
`docs/evidence/syn049-completion-dependency-acceptance-2026-09-03.md`.

## Work completed

Planning and separate ADRs are recorded in SYN-049, ADR-0053, and ADR-0054.
Defect A now uses a call-local `completionRequested` request, V7 intent
encoding, explicit finish projection, and the existing execution fences and
terminal outcomes. Snapshot publishability uses the server-recorded lane base
commit so multiple committed lane mutations are evaluated together. Focused
coordination, MCP contract, MCP admission, and workspace regressions pass.
The rebuilt MCP Defect B path also passes, so its forwarding production code
was not changed. The fresh real run proved both scoped behaviors but remains
PARTIAL at the separate review/session lifecycle stall.

## Current failures

Plain Gradle startup still needs the documented local
`-Djdk.net.unixdomain.tmpdir=C:\\tmp` host mitigation for this Windows/JDK
environment; this is not a product/build-semantics change. A broad,
process-heavy MCP selection was stopped without an assertion result and is
incomplete evidence, not a passing result. The fresh two-worker run is
PARTIAL: A completed the explicit finish path and B consumed A's published
contract, but the separate review/session lifecycle prevented B's final finish
and WorkGroup terminalization. Doctor ended DEGRADED with six warnings and no
mutations.

## SYN-048 Completion record — 2026-09-02

- Task ID: SYN-048
- Status: DONE / VERIFIED
- Scope: correct the proven provider-admission mismatch for an unpinned MCP
  connection launched from an ordinary linked Git worktree, while preserving
  explicit project pinning, assigned-worktree rejection, provider admission,
  and the ten-tool surface.

## Acceptance criteria

- An unpinned initialized linked worktree sharing the control checkout's Git
  common directory resolves to the main checkout before provider admission.
- Unrelated roots, project-ID mismatches, Synesis-assigned worktrees, and a
  missing control-checkout provider integration remain rejected.
- Focused tests, diff validation, and deferred-register validation pass.

## Completion evidence

The bounded read-only resolver, focused regression, guard tests, and strict
local distribution rebuild are recorded in
`docs/evidence/syn048-unpinned-linked-root-2026-09-02.md`. The complete MCP
test class remains incomplete because it exceeded the bounded wait.

## SYN-045 Preserve explicit MCP project authority across provider-created Git worktrees — 2026-09-02

- Task ID: SYN-045
- Status: DONE
- Scope: prevent provider-reported MCP roots from replacing an explicit
  launcher-pinned control checkout unless they are verified as the same Git and
  Synesis project, while continuing to use the control checkout for provider
  admission and Synesis workspace allocation.

## Completion record

The bounded root-authority correction is complete and recorded in
`docs/evidence/syn045-codex-linked-worktree-bootstrap-2026-09-02.md` and
checkpoint `docs/agent/checkpoints/CP-0626.md`. Its incomplete broader
task-tracker fixture remains preserved as evidence and is not restarted.

## Acceptance criteria

- A Codex app linked worktree sharing the configured repository cannot make an
  installed control-checkout provider appear `NOT_INSTALLED`.
- Unrelated initialized roots and Synesis-assigned worktrees remain rejected.
- Missing provider installation at the configured control checkout remains
  blocked.
- Session admission still allocates distinct isolated Synesis worktrees.
- Focused tests, diff validation, and an installed-runtime fresh-pair check pass.

## Current evidence

The clean `ToPouliTouTzala` control checkout reports Codex provider metadata,
profile, hook, and a bound session. Its two Codex-created linked worktrees share
the same canonical Git common directory but lack ignored provider/profile/hook
state and initially reported `NOT_INSTALLED`, causing
`provider_integration_required` before either worker could mutate repository
files. The explicit launcher-root fix now keeps the control checkout
authoritative and verifies equivalent provider roots by project ID and Git
common directory. No manual state copy or bypass was performed.

## Work completed

Added the read-only explicit MCP root-authority resolver, fail-closed startup
handling, latched root-mismatch guards, focused linked-worktree regressions,
and the corresponding ADR. The installed runtime was rebuilt and repaired
through the normal installer workflow.

## Verification

Focused MCP root-selection coverage (12), startup coverage (2), selected
existing MCP coverage (7), and the focused workspace provider test (9) passed.
The platform bundle completed and the repaired installed MCP executable
matched the bundle SHA-256. A fresh installed-runtime pair reached distinct
Synesis worktrees and disjoint claims.

## Current failures

The broader task-tracker acceptance is incomplete: the bootstrap lane committed
`8225b9f` in its own Synesis worktree but did not publish/integrate it before
creating the workers, so their lanes started from control commit `36bc543`.
Both workers stopped safely without edits. A new parent connection's empty
recovery could not restore the old claim, which Luna confirmed is expected
fail-closed behavior. Preserve this fixture; do not copy files, manually
integrate branches, edit durable state, or restart the pair.

## SYN-043 Preserve explicit worker dependencies during MCP admission — 2026-08-31

- Task ID: SYN-043
- Status: PAUSED while SYN-046 is active
- Scope: carry `knownDependencies` from the existing `ensure_session` task
  admission path into durable work-intent state and expose the existing
  capability-request continuation. Preserve backward event decoding, the
  existing capability/contract lifecycle, isolated worktrees, and the exact
  ten-tool MCP catalog.

## Historical next action

Preserve the bounded live two-Codex result, allow only the already-running pair
to reach a terminal state, and then classify the remaining task-launch or
worker-liveness boundary without manually driving Synesis or starting another
pair.

## Work completed

The slice adds intent payload version 6, carries `knownDependencies` through MCP
admission and collaboration announcement, preserves the field during codec
replay, exposes the dependency in the provider-facing projection, and routes
capability-owner actions by authority lineage. The bootstrap slice also makes
the stable native MCP launcher resolve and validate the active versioned
payload, and repair refreshes its stable copy.

## Verification

The codec, focused workspace, MCP, and isolated bootstrap suites passed. The
rebuilt bundle was repaired into the installed runtime, and a native MCP probe
confirmed that the child Java process uses the active versioned payload. The
fresh live run reached two disjoint Synesis claims with both sessions ACTIVE;
Worker A created its domain/persistence slice, while Worker B has not yet
created files or published a capability. The pair has not integrated or
terminalized, so this is not a whole-acceptance pass.

## Current failures

The remaining evidence boundary is worker/task liveness and normal capability
publication in the already-running pair. Do not copy worktrees, edit durable
Synesis state, manually transition lifecycle records, or launch a replacement
pair.

## SYN-044 Require installed provider integration before any agent work — 2026-08-31

- Task ID: SYN-044
- Status: DONE
- Scope: require a Git repository before user-facing `synesis init`, and add
  a fail-closed provider-integration admission check before session binding
  and to the shared readiness path, preserving unborn-Git baseline bootstrap,
  degraded Codex semantics, and the unrelated SYN-043 dependency-admission
  changes.

## Historical next action

Preserve checkpoint CP-0622 and keep the provider/Git changes separate from
SYN-043 implementation work.

## Work completed

Added a provider work-admission predicate backed by the existing provider
status records. Session binding now fails closed before worktree allocation
when the selected provider is missing, unknown, obsolete, or broken; the
shared workspace readiness predicate applies the same gate after binding.
Added the stable `provider_integration_required` response reason and focused
coverage for initialization-only, installed/degraded, and uninstalled states.
Updated workspace and MCP fixtures to use normal project provider installation
instead of treating the global Synesis manual as an integration.

Added a read-only Git repository preflight to the user-facing `synesis init`
command. It fails with `GIT_REQUIRED` before creating `.synesis` state when
Git metadata is missing or unreadable, while preserving the existing unborn
repository baseline commit. Updated generated `AGENTS.md` guidance and the
root contract with matching Claude Code/Codex provider installation commands.
Recorded the boundary in ADR-0050.

## Verification

Using `TEMP`/`TMP=C:\\Temp\\synesis-gradle`, the focused workspace and CLI init
tests pass after this slice, and the complete `:cli:test` suite passes. The
prior full workspace (322 tests) and full MCP (79 tests) suites passed before
this slice. A post-slice aggregate `:workspace:test` rerun was stopped after
becoming process-heavy and silent; it produced no failure report before being
stopped and is incomplete evidence, not a pass claim. `git diff --check` and
the deferred register/checkpoint validator passed.

## Current failures

No SYN-044 test failures remain. The working tree still contains unrelated
pre-existing SYN-043 implementation and documentation changes; do not stage
or commit those as part of SYN-044.

## MAINT-003 Final source-level documentation, packaging, and reconciliation — 2026-08-30

- Task ID: MAINT-003
- Status: ACTIVE
- Handoff: source pass complete; the user-authorized release packaging slice is
  active and must be checkpointed before handoff.
- Scope: audit and document meaningful source contracts and invariants across
  the complete tracked source tree: 572 Java files (409 production and 163
  tests), 8 Go files, and 9 install/validation scripts. Reconcile active
  documentation with current source, CLI, module boundaries, provider IDs, the
  MCP contract, and verified limitations.
  Preserve ADRs, checkpoints, evidence, accepted historical records,
  backward-read compatibility, and unrelated work. The user additionally
  authorized a self-contained platform bundle installer, artifact renaming,
  and Gradle caching.
- Acceptance: important source types and APIs are documented where semantics
  warrant it; comments explain meaning rather than syntax; stale comments are
  corrected or removed; active docs are source-accurate; each platform bundle
  contains a runnable native installer offering install, repair, and uninstall;
  install does not install providers; repair preserves project/workspace data;
  the metadata wipe is explicit; Actions publishes six `synesis-<platform>`
  bundle artifacts with Gradle caching; and verification is honestly
  classified.
- Existing user-owned changes and the unrelated lifecycle stash remain
  protected. No SYN-042, Claude integration, MCP tool addition, release, tag,
  force push, or historical rewrite is allowed. Commit and normal push are
  authorized after this pass is validated.

## Work completed

The prior Antigravity removal and repository cleanup are complete and pushed in
`aee96c3` and `48e0103`. This task completed the separate active-documentation
reconciliation; historical Antigravity records remain preserved.

The whole-tree source documentation pass is complete and was committed as
`2b32aa8` and pushed to `origin/master`. The working tree was clean before this
packaging slice.

The user-authorized packaging slice adds a native installer to each bundle,
uses local bundles for install/repair, adds an explicit metadata-removal option
to uninstall, and removes separate bootstrap/metadata uploads from the release
workflow. The exact design is recorded in ADR-0048.

The build-performance slice removes release packaging from the ordinary Java
`check`, removes the CLI test dependency on `installDist`, limits each native
launcher invocation to the requested bundle and host platforms, enables the
Gradle daemon/configuration/build caches with a 24 GB heap ceiling, and upgrades
GitHub Actions to `gradle/actions/setup-gradle@v6` caching. CI no longer runs
`clean` before verification or builds both archive formats for one platform.

The follow-up configuration-cache repair removes Gradle script/`Project`
captures from every verification task reported by the user's `build` log,
including build metadata, repository hygiene, format checks, and architecture
checks. The task actions now operate on configuration-time-captured files and
providers.

The user's next run narrowed the remaining failure to four `DefaultTask`
actions: CLI build metadata, CLI format checking, Link format checking, and
repository hygiene. Those actions now use isolated custom task types with
Gradle-managed inputs and task actions that do not capture the build script.

The following run exposed an independent missing-file issue: both the root
hygiene task and Link format task listed optional root documents that are not
present in this checkout. Their managed file roots now filter to existing files
before Gradle executes them.

## Verification

- Cleanup baseline: PASS; active residue search, hygiene, focused workspace
  regressions, ten-tool MCP catalog test, compilation, and packaging passed.
- Historical Antigravity records remain preserved; the only active mention is
  the current-state removal note.
- Known validation limits carried forward: three bootstrap migration fixture
  failures (`update migrations not prepared`) and an incomplete root Java
  `check` stopped during process-heavy MCP tests.
- Documentation audit corrections: current-state module/provider/configuration
  summary, project-layout metadata description, provider-maturity matrix, CLI
  command reference, and current-state navigation links.
- Documentation gates: `repositoryHygieneCheck`, deferred-register
  validation, active Markdown links, and the source/CLI cross-check PASS.
- Whole-tree source pass: all 572 Java files now contain Javadoc, all detected
  Java type declarations have documentation, and the previously undocumented
  49 test classes plus 61 nested/helper types were covered. All remaining 8 Go
  files and 9 install/validation scripts also have file-level comments. Routine
  CLI options and obvious locals remain intentionally uncommented.
- Validation after the source pass: strict multi-module `javadoc`,
  `repositoryHygieneCheck`, deferred validation, `git diff --check`, Go vet,
  MCP contract/catalog tests, `TerminalLeaseStateTest`, launcher smoke,
  platform bundle/archive, and bundle smoke PASS. The baseline Go tree still
  reports one existing CRLF-normalization `gofmt -l` finding in the
  `provision-acceptance-key` command; no implementation formatting was introduced
  in this docs-only pass. The
  selected MCP suite and broader workspace lifecycle selection stalled in
  process-heavy test workers without assertion output and were interrupted;
  they remain incomplete.
- Packaging validation: native installer build, focused local
  install/repair/uninstall tests, Go vet, and workflow/diff review pass. The
  Gradle bundle task is currently blocked locally by Gradle's `Unable to
  establish loopback connection`; hosted Gradle bundle execution remains the
  required final evidence.
- Build-performance validation: workflow YAML parsing and `git diff --check`
  pass. Gradle task execution remains blocked before project configuration by
  the same host loopback failure, including direct Gradle 9.7.1 invocation.
- Configuration-cache repair validation: the affected Gradle scripts pass
  deferred validation, workflow parsing, whitespace checks, and Go vet. The
  user's prior build log remains the only execution evidence for the original
  failure; this shell still cannot start Gradle because of loopback failure.
- Residual four-problem repair validation: isolated task-type changes pass
  diff, deferred, workflow, and Go gates; a fresh Gradle cache-store run is
  still required.
- Missing optional-document repair validation: root-file inventory and
  non-Gradle repository gates pass; the reported `CONTRIBUTING.md` failure is
  addressed by filtering absent roots.
- Hosted CI run `33325552193` confirmed the configuration cache now stores
  successfully (`47 actionable tasks`, with `25 from cache`), but `:cli:test`
  failed in eight process-level tests because the earlier performance change
  removed the generated `build/install/synesis` distribution. The fix adds a
  lightweight `testInstallDist` staging task for the Java launcher tests while
  keeping native MCP and installer builds on release-only paths. Local Gradle
  execution remains blocked by the host loopback failure.
- The Gradle Actions summary is disabled for the Java verification job while
  Gradle state caching remains enabled.
- The first post-fix bundle run exposed five configuration-cache problems in
  the release-only native/runtime/bundle/smoke tasks, including execution-time
  `Task.project` access. Those tasks are now explicitly marked as
  configuration-cache-incompatible, and bundle workflow invocations use
  `--no-configuration-cache`; the ordinary Java `check` remains cached.
- The subsequent macOS bundle smoke exposed a missing executable bit on the
  extracted Unix `synesis-installer`. Bundle assembly now sets and validates
  executable permissions for both Unix native binaries, and smoke extraction
  restores the installer permission before invocation.
- The requested final artifact shape is now implemented: each matrix job
  appends its platform archive to the native installer and uploads one
  self-extracting runnable file named `synesis-<platform>-<architecture>`
  (Windows adds `.exe`). The bootstrapper extracts and delegates to its
  embedded installer, while the bundle smoke test exercises the standalone
  file's menu entry point.

## Historical next action

Run the hosted `check` and six-platform bundle matrix again, confirm the
self-extracting files launch on all six targets without configuration-cache or
Unix-permission failures, create a checkpoint, and review the exact staged
artifact boundary before commit/push.

## Current failures

The bootstrap Go suite has three update-migration fixture failures reporting
`update migrations not prepared`. The bounded module-check run reached
process-heavy workspace tests without an assertion result and was stopped; the
selected MCP and workspace lifecycle runs likewise remain incomplete. These
are not source-documentation failures. Local Gradle task execution is blocked
before configuration by `Unable to establish loopback connection`; this is a
host/tooling blocker, not evidence against the build-script changes.

## MAINT-001 IntelliJ analyzer cleanup — 2026-08-29

- Task ID: MAINT-001
- Status: PAUSED while MAINT-002 is active.
- Scope: behavior-preserving cleanup of current IntelliJ file-inspection
  findings; SYN-041 remains closed and its terminal-session semantics are not
  being broadened.
- Baseline: 1,072 findings across 576 Java files: 767 warnings and 305 weak
  warnings. Latest result: 783 findings across 576 Java files, comprising
  484 warnings and 299 weak warnings; no analyzer request failed.
- Acceptance: bounded fixes are re-analyzed and tested; remaining findings are
  either resolved or explicitly classified as intentional/design guidance.
- Existing uncommitted work is preserved. No commit, push, release, or
  SYN-042 creation is part of this task.

## Historical next action

Run a separately reviewed high-confidence warning batch and preserve
intentional lifecycle, polling, reflection, and public-API behavior. The latest
full pass is recorded in checkpoint CP-0573.

## SYN-041 final real Codex closure acceptance — 2026-08-29

- Task ID: SYN-041
- Status: DONE / ACCEPTED; primary result RESULT A.
- The one real Codex lifecycle completed lawful no-change terminalization at
  fence sequence 7. The original native Java runtime was dead before the sole
  rejected same-session probe, which returned `SESSION_TERMINAL` and exited
  cleanly. The final persisted lease is `TERMINAL_DISCONNECTED`; original
  process metadata is preserved.
- Evidence: `docs/evidence/syn041-final-real-codex-closure-2026-08-29.md`.

## Historical next action

Preserve the accepted SYN-041 closure and do not create SYN-042 or broaden
terminal-session semantics without a separately activated task.

## Closure validation

Focused lease/supervision/MCP/SYN-039 regressions, exact 10-tool catalog,
Javadocs, platform bundle, bundle smoke, deferred validation, and diff check
passed. Broader process-heavy MCP selections remain incomplete after host
timeout and are not reported as passing.

## SYN-041 terminal-disconnect trigger implementation — 2026-08-29

- Task ID: SYN-041
- Status: ACTIVE; primary result RESULT C.
- Read-first tracing proved that externally launched Codex MCP has no surviving
  Synesis observer after the packaged Java child dies. Java-local transport
  failures now finalize synchronously, and a later close from a different PID
  verifies the original runtime before refusing clean close and recording
  `TERMINAL_DISCONNECTED` when it is gone.
- Fresh official packaged acceptance passed terminal seal, exact Java PID
  termination, `SESSION_TERMINAL` rejection, probe clean EOF, and final raw
  `TERMINAL_DISCONNECTED` with original identity metadata preserved.
- Evidence: `docs/evidence/syn041-terminal-disconnect-trigger-cp0567-2026-08-29.md`.

## Historical next action

Review CP-0567 and await separately authorized final provider confirmation; do
not run Codex, close SYN-041, change Doctor, or broaden the terminal-session
model.

## Work completed

The production boundary was localized and fixed in the existing lease/MCP
seams. Focused tests, strict Javadocs, fresh official bundle/smoke, and
provider-independent packaged acceptance pass. The full process-heavy MCP
selection remains incomplete after host timeout and is not reported as pass.

## Current failures

No local or packaged production-path failure remains in this slice. Immediate
classification is available when Java observes transport failure; an external
hard kill without a surviving observer is classified at the first later close
through persisted PID evidence. Final real-provider confirmation is deferred.

## SYN-041 CP-0563 terminal transport-history preservation — 2026-08-29

- Task ID: SYN-041
- Status: ACTIVE; bounded CP-0562 defect resolved.
- Root cause was proven in the old no-argument `markClosedCleanly`: a
  rejected same-session connection close unconditionally rewrote a durable
  `TERMINAL_DISCONNECTED` lease to `CLOSED_CLEANLY`.
- The minimal fix serializes clean/abnormal finalization with the existing
  project append lock, makes clean cleanup durable-state-aware, and keeps
  terminal-abnormal finalization PID-gated and idempotent. Metadata is
  preserved; terminal history is not overwritten.
- Focused lease, terminalization, MCP rebind, rejection, supervision, wake,
  catalog, Javadoc, bundle, smoke, and deferred checks passed. The packaged
  provider-independent acceptance preserved `TERMINAL_DISCONNECTED` after a
  rejected probe close. Broader process-heavy MCP tests remain incomplete due
  host timeouts, with no assertion failure observed.
- Evidence: `docs/evidence/syn041-terminal-transport-history-fix-2026-08-29.md`.

## Historical next action

Run `powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1`, read
CP-0563 and the new evidence, and await a separately authorized final
provider-acceptance decision; do not run a real provider, broaden identity, or
close SYN-041 in this slice.

## Work completed

The CP-0562 lease-history overwrite is fixed and re-verified. Focused source
tests, strict Javadocs, deferred validation, fresh platform bundle/smoke, and
provider-independent packaged acceptance passed. The packaged rejected probe
left the exact raw lease `TERMINAL_DISCONNECTED`; all listed lease metadata
was preserved and Doctor performed zero mutations.

## Current failures

Broader process-heavy MCP selections remain incomplete because they timed out
on this host without assertion output. This is a verification limitation, not
a proven product failure. No real Codex run was authorized for this slice.

## SYN-041 final real Codex terminal-seal acceptance — 2026-08-28

- Status: ACTIVE; primary result RESULT C.
- The one actual real Codex lifecycle used the rebuilt official bundle and
  completed task-bearing `ensure_session`, `read_file`, `get_next_action`,
  lawful no-change `finish_lane`, and explicit `terminalSession=true`.
- Synesis committed `PROVIDER_SESSION_TERMINALIZED` at fence sequence 7 for
  the exact session before native Java/MCP exit code 1; Codex exited 0.
- An exact-connection rebind probe returned `SESSION_TERMINAL`, proving no
  authority reactivation. Its clean probe EOF then rewrote the same lease to
  `CLOSED_CLEANLY`, despite the durable terminal event remaining present.
  This directly violates terminal transport-history immutability.
- Evidence:
  `docs/evidence/syn041-final-real-codex-terminal-seal-acceptance-2026-08-28.md`.

## Historical next action

Inspect `SessionLeaseService.markClosedCleanly` and add a narrow regression
test for refusal to overwrite terminal authority; do not run another provider
experiment, change Doctor, or close SYN-041 until this proven defect is
resolved and re-verified.

## SYN-041 terminal-session seal implementation — 2026-08-28

- Task ID: SYN-041
- Status: ACTIVE
- The first bounded implementation slice is complete. `finish_lane` remains
  lane-only by default; `terminalSession=true` requests an exact-session seal
  after lawful lane completion.
- The append-locked `PROVIDER_SESSION_TERMINALIZED` event fences binding,
  heartbeat, rebind, coordination, review, continuation, and next-action
  admission. Clean EOF remains `CLOSED_CLEANLY`; unsealed abnormal loss keeps
  stale/recovery behavior; sealed abnormal loss is `TERMINAL_DISCONNECTED`.
- SYN-039 remains CLOSED / DONE / ACCEPTED. No generalized identity,
  provider migration, new MCP tool, push, tag, or release was added.

## Historical next action

Run `git status --short` and inspect the SYN-041 implementation/evidence diff
before any future commit; do not commit, push, tag, release, or run another
real provider experiment.

## Work completed

Focused terminal-seal, lease, direct-admission, SYN-039, wake/continuation,
strict-Javadoc, packaged-bundle, bundle-smoke, and synthetic Doctor checks
passed. The first concurrent full-module attempt hit process-test timeouts;
the affected tests passed individually and the authoritative targeted suites
passed serialized with `--max-workers=1`.

## SYN-041 terminal-disconnect semantics — 2026-08-28

The requested design-only investigation is complete. No provider experiment,
production change, lease persistence change, Doctor change, migration, or
generalized identity work was performed. Current source has no irreversible
provider-session authority seal: `WorkGroup.COMPLETED`, participant
`COMPLETED`, binding `COMPLETED`, zero active intents, and zero claims are each
insufficient alone. A completed binding may still serve bounded review-only
authority, and the same connection evidence can be rebound by `ensure`.

Primary classification: RESULT C. A future explicit exact-session terminal
intent plus an atomic no-mutable/no-resumable-authority proof is required
before abnormal transport loss can be considered non-recoverable. Preserve
`CLOSED_CLEANLY` for observed clean EOF and preserve abnormal transport as
separate forensic history. Evidence:
`docs/evidence/syn041-terminal-disconnect-semantics-2026-08-28.md`.

Exact next action: stop SYN-041 at this design boundary; do not implement the
proposal, rerun Codex, change leases or Doctor, reopen SYN-039, or create
SYN-042.

## SYN-041 read-only exit-code causal analysis — 2026-08-28

The source and disposable-control analysis is complete. The native launcher
waits for Java and propagates a Java `exec.ExitError` exit code directly. Java
`McpStdioServer` returns 0 only for clean EOF and returns 1 from its caught
transport/loop `Throwable` path; `handler.close()` is conditional on clean
EOF. Clean EOF produced Java/MCP 0 and `CLOSED_CLEANLY`; partial EOF produced
Java/MCP 1, `MCP_PARTIAL_FRAME_EOF`, and `ACTIVE`; closing the stdout reader
produced Java/MCP 0 and `CLOSED_CLEANLY`. Classification: RESULT B primary,
RESULT D secondary. Evidence:
`docs/evidence/syn041-exit-code-causal-analysis-2026-08-28.md`.

Exact next action: stop SYN-041 at this read-only RESULT B/D boundary; do not
run another provider experiment or change transport, lease, or Doctor policy.

## SYN-041 final native measurement — 2026-08-28

The one authorized handle-based native measurement completed with direct
`Codex -> official synesis-mcp.exe -> packaged Java` parentage. Codex completed
the task and exited 0, while the official MCP and Java children were observed
through retained native handles with exit code 1; both child exits preceded
Codex exit. This is SYN-041 RESULT C / MCP-Java failure evidence. The exact
internal-versus-external origin remains unresolved; no clean-close or lease
defect is proven. Evidence: `docs/evidence/syn041-final-handle-native-measurement-2026-08-28.md`.

Exact next action: stop SYN-041 work at this verified RESULT C boundary and
report the retained artifacts; do not run another provider experiment or
change production code, leases, Doctor, or SYN-039.

## SYN-041 measurement-design slice — 2026-08-28

The design-only investigation is complete; no equivalent provider run was
performed. Evidence: `docs/evidence/syn041-native-observability-design-2026-08-28.md`.
The recommended future measurement retains native process handles with query /
synchronize rights, captures Codex JSONL and stderr to regular files, and
keeps the direct `Codex -> official MCP -> Java` topology. This closes the
current MCP/Java exit-code and timing gap without interposing, but ordinary
Windows user-mode telemetry still cannot attribute a zero-code termination to
the caller or prove literal anonymous-pipe EOF. SYN-041 remains ACTIVE and
RESULT E remains the current classification.

Exact blocker: Need native, non-interposing MCP/Java exit-code and
transport-lifetime telemetry sufficient to classify the child termination
observed ~24.5 seconds before Codex exit.

The five unrelated lifecycle files remain preserved and excluded. No
production files, lease semantics, Doctor classification, provider migration,
identity architecture, or SYN-039 state changed. Nothing was pushed, tagged,
or released.

## SYN-041 real Codex clean-exit provider lifecycle validation

- Task ID: SYN-041
- Status: ACTIVE
- Initial real-provider engagement and exact no-change completion are proven;
  no product defect is established.
- SYN-039 and SYN-040 remain DONE / ACCEPTED / VERIFIED and are not reopened.
- Scope is measurement-only: final native Codex-to-MCP topology, process
  timing, lease state, and Doctor. RESULT E remains because the native polling
  harness lost the Codex transcript and could not directly capture EOF/child
  exit code; no lease defect is proven.

## Historical next action

Preserve RESULT E as the stopping boundary. Do not run another equivalent
probe, implement a lease fix, suppress Doctor, alter lifecycle semantics, or
investigate beyond this task without a new explicit task.

## Work completed

Default ChatGPT authentication works; the earlier 401 was caused by an empty
isolated `CODEX_HOME`. The prior real Codex probe used task-bearing
`ensure_session` but skipped `read_file` and left its first
`get_next_action({})` outstanding. A fresh real Codex run with the supported
task-bearing sequence completed `ensure_session`, `read_file`,
`get_next_action`, exact `finish_lane`, and the WorkGroup. No Synesis product
defect was established.

Evidence target:
`docs/evidence/syn041-real-codex-native-teardown-2026-08-28.md`.

## SYN-040 post-SYN-039 provider-session and Doctor hygiene

- Task ID: SYN-040
- Status: DONE / VERIFIED
- Scope: classify the six post-SYN-039 Doctor warnings and fix only a proven
  provider-session, Doctor-classification, or provider-migration defect.
- SYN-039 remains DONE / ACCEPTED at CP-0547 and is not reopened.
- Existing dirty lifecycle files remain outside scope unless causality is
  proven.

## Historical next action

No product action remains for SYN-040. Preserve the evidence, do not
auto-reconcile the two stale leases, and explicitly promote any future task
before implementation.

## Work completed

The task was explicitly promoted in response to the user's separate
post-SYN-039 hygiene request. Startup validation had found zero active tasks
after SYN-039 closure; the task is now verified complete with no product fix.

Evidence:
`docs/evidence/syn040-post-syn039-doctor-hygiene-2026-08-28.md`.

## SYN-039 closure — final canonical acceptance

- Task ID: SYN-039
- Status: DONE / ACCEPTED
- Checkpoint: CP-0547

Evidence:
`docs/evidence/syn039-final-canonical-acceptance-closure-2026-08-28.md`.

The final canonical acceptance is green. Two independent ordinary Codex
sessions established reviewer-first, order-independent producer/reviewer
roles with disjoint exact claims; published immutable S1; rejected its
objective invalid-index defect; continued the same producer lineage through
claim epoch and intent version 2; published and accepted fresh S2; integrated
only S2; explicitly completed the reviewer's no-change lane; and left zero
active WorkGroups, intents, claims, requests, or current grants.

The final control checkout was clean and the focused tests plus independent
behavior probe passed. Doctor remained DEGRADED with six non-fatal stale-lease,
command namespace/retention, and provider migration warnings. No SYN-039
collaboration state remained active, and no hygiene repair was performed.

## Historical next action

Select and explicitly promote the next authorized task before beginning any
implementation; do not reopen SYN-039 for its separately recorded Doctor
warnings.

## Work completed

Recorded final acceptance closure, evidence references, Doctor caveat, dirty
file classification, and the coherent local SYN-039 completion commit.

## Current failures

No SYN-039 semantic failure remains. Doctor/provider-session hygiene remains
DEGRADED as separately classified non-causal warning state; do not repair it in
this closure task.

## SYN-039 CP-0545 exact-projection diagnostic boundary

Evidence:
`docs/evidence/syn039-diagnostic-cp0548-exact-rule-2026-08-25.md`.

The exact-rule diagnostic used a fresh project, current bundled MCP, and two
ready/isolated independent sessions. They converged on WorkGroup
`7dad9076-f0be-3117-9667-b5260ce1ca1e`, executed exact REVIEW admission and
owner response projections, and consumed grant
`4fe63ef0-418d-3abc-a442-c768a3b73f6a` after one fail-closed malformed retry.

The run stopped before snapshot publication. The implementation participant
ended at a valid `WAIT -> get_next_action({})` continuation; the reviewer then
received `review_validation` polling with no snapshot available and chose a
non-projected pytest action. No unchanged projected action failed and no new
production defect is proven.

## Historical next action

Preserve CP-0545 as agent-compliance evidence and do not change lifecycle
semantics. Run another fresh two-agent acceptance only with new provider-session
termination evidence; stop at the first unchanged projected action failure or
state with no usable projection.

## Work completed

Recorded CP-0545 with exact diagnostic rule, project and bundled executable,
participants, claims/epochs, request/grant, projections and arguments,
fail-closed retry, final WorkGroup, control tests, and Doctor state. No
production code changed.

## Current failures

WorkGroup `7dad9076-f0be-3117-9667-b5260ce1ca1e` remains ACTIVE. Grant
`4fe63ef0-418d-3abc-a442-c768a3b73f6a` was consumed, but no snapshot,
validation, integration, or closure occurred. Control pytest is 1 passed / 1
failed because `todo.py` was not published. Doctor remains DEGRADED with six
warnings; Git/bootstrap issues remain separate.

## SYN-039 CP-0544 ordinary review-continuation boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0547-ordinary-review-continuation-2026-08-25.md`.

The fresh ordinary run used the current bundled MCP and two independent
ready/isolated sessions. Agents converged on WorkGroup
`31941d9a-11dd-3b49-98ab-86042f5b6faa`, created and accepted REVIEW request
`e2ac6ec7-e860-42f3-8dfb-c3acbc8816ae`, and consumed REVIEW grant
`bd3d274c-e4e6-3c63-930d-8ba19b783c5d` after one correctly rejected malformed
retry. Ownership, participant, epoch, and fail-closed checks remained intact.

Both sessions then ended at valid `WAIT -> get_next_action({})`
continuations. No snapshot, validation, integration, or WorkGroup closure was
reached. No unchanged projected action failed and no production defect is
proven; the first blocker remains ordinary provider engagement.

## Historical next action

Run another fresh ordinary unattended two-agent Todo acceptance with only
complementary visible coding prompts. Preserve the first unchanged projected
action failure or required state with no usable projection. Do not alter
review/wait semantics, push, or create SYN-040 from CP-0544 alone.

## Work completed

Recorded CP-0544 with exact project, bundled executable, participant and claim
identities, request/grant, projection/action, fail-closed retry, final
WorkGroup, control test, Doctor, and independent Git-stall evidence. No
production code changed.

## Current failures

WorkGroup `31941d9a-11dd-3b49-98ab-86042f5b6faa` remains ACTIVE. Both claims
remain active; grant `bd3d274c-e4e6-3c63-930d-8ba19b783c5d` was consumed by A,
but no snapshot was published. Control pytest is 1 passed / 1 failed because
the implementation snapshot was not published. Doctor remains DEGRADED with
six warnings; Git/bootstrap issues remain separate.

## SYN-039 CP-0543 ordinary grant-continuation boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0544-ordinary-grant-continuation-2026-08-25.md`.

The fresh ordinary run reached WorkGroup
`f82161ed-39f9-3662-851d-d90f07245a46`, reciprocal REVIEW, grant
`964ea299-c1fb-3298-a61b-d448522fb33d` consumption, immutable snapshot
`snap_0bd17b0b5256e6a3cc6a5a9c79487085`, structured ACCEPT, and integration.
Agent A once omitted a projected grant field and was correctly rejected
fail-closed before retrying unchanged. Agent B changed only the projected
informational finish summary; publication still succeeded.

Both sessions ended at valid `WAIT -> get_next_action({})` continuations
before grant `c7d4b141-32fd-3547-9928-c8cf191cc1b8` consumption and the
implementation snapshot. No unchanged projected action failed and no new
production defect is proven.

## Historical next action

Run another fresh ordinary unattended two-agent Todo acceptance with only
complementary visible coding prompts. Preserve the first unchanged projected
action failure or required state with no usable projection. Do not alter
review/wait semantics, push, or create SYN-040 from CP-0543 alone.

## Work completed

Recorded CP-0543 with complete WorkGroup, participant, claim/epoch,
request/grant, projection/action, snapshot, validation, integration, control
test, Doctor, and fail-closed retry evidence. No production code changed.

## Current failures

WorkGroup `f82161ed-39f9-3662-851d-d90f07245a46` remains ACTIVE. Agent A's
`todo.py` claim remains active and grant
`c7d4b141-32fd-3547-9928-c8cf191cc1b8` remains pending. Control pytest is
1 passed / 2 failed because only the test snapshot integrated. Doctor remains
DEGRADED with six warnings; Git/bootstrap issues remain separate.

## SYN-039 CP-0542 ordinary reciprocal-review boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0543-ordinary-reciprocal-review-2026-08-25.md`.

The fresh ordinary acceptance used the current bundled MCP and two
ready/isolated sessions in one shared WorkGroup
`0b1f5704-1c63-3373-b5ea-7e16ea6c8b79`. Reciprocal REVIEW admission, exact
grant consumption for the test snapshot, immutable publication, structured
ACCEPT, and test-snapshot integration succeeded. The reviewer command
returned 1 passed / 2 failed because the sibling implementation was not yet
integrated; the agent chose the valid structured ACCEPT decision. This is not
an unchanged tool failure.

The implementation lane then received and executed its reciprocal owner
response, but the provider sessions ended at valid `WAIT -> get_next_action({})`
continuations before the second grant and implementation snapshot. No new
production defect is proven; generated `AGENTS.md` already requires continued
polling while unresolved WorkGroup state remains.

## Historical next action

Run another fresh ordinary unattended two-agent Todo acceptance with only
complementary visible coding prompts. Preserve the first unchanged projected
action failure or required state with no usable projection. Do not alter
review/wait semantics, push, or create SYN-040 from CP-0542 alone.

## Work completed

Recorded CP-0542 with complete WorkGroup, participant, claim/epoch,
request/grant, projection/action, snapshot, validation, integration, control
test, and Doctor evidence. No production code changed.

## Current failures

WorkGroup `0b1f5704-1c63-3373-b5ea-7e16ea6c8b79` remains ACTIVE. Agent A's
`todo.py` claim remains active and reciprocal grant
`939017b6-16be-377e-9ff2-915dc002ffc6` remains pending. Control pytest is
1 passed / 2 failed because only the test snapshot integrated. Doctor is
DEGRADED with six warnings; Git/bootstrap issues remain separate.

## SYN-039 CP-0541 ordinary continuation diagnostic

Evidence:
`docs/evidence/syn039-unattended-todo-cp0541-ordinary-continuation-2026-08-25.md`.

The fresh ordinary two-agent run used the current bundled MCP and two
independent ready/isolated sessions in one shared WorkGroup
`94a259df-c1b3-3b0f-8bd9-3c481745c87c`. Exact REVIEW admission, owner
responses, single-use grant consumption, immutable snapshot publication,
structured ACCEPT, and implementation-lane integration succeeded. Every
unchanged concrete projection that was executed succeeded.

The run stopped at valid polling continuations. Agent A ended after polling
`WAIT -> get_next_action({})` for the reciprocal owner response; Agent B later
accepted that response and remained at `WAIT -> get_next_action({})` while the
reciprocal grant targeted at A remained pending. Source inspection confirms
these are intentional wait states, so this is provider/session compliance
evidence, not a new production defect. No production code changed.

## Historical next action

Run another fresh ordinary unattended two-agent Todo acceptance with only
complementary visible coding prompts. Preserve the first unchanged projected
action failure or required state with no usable projection. Do not alter
review/wait semantics, push, or create SYN-040 from CP-0541 alone.

## Work completed

Recorded CP-0541 with complete WorkGroup, participant, claim/epoch,
request/grant, projection/action, snapshot, validation, integration, control
test, Doctor, and Git-stall evidence. Focused workspace tests and validators
pass; no production code changed.

## Current failures

WorkGroup `94a259df-c1b3-3b0f-8bd9-3c481745c87c` remains ACTIVE. A is
COMPLETED, B remains ACTIVE with `test_todo.py`, and reciprocal grant
`976388e2-d7f2-373e-83a1-9f36df6045ca` remains pending because A's provider
session ended before consumption. Control pytest is 2/2 because only the
implementation snapshot integrated. Doctor is DEGRADED with six warnings;
the focused MCP Git subprocess stall and bootstrap migration failures remain
separate verification issues.

## SYN-039 CP-0540 bounded exact-action diagnostic

Evidence:
`docs/evidence/syn039-unattended-todo-cp0540-review-contract-diagnostic-2026-08-25.md`.

The fresh bounded diagnostic used two independent ready/isolated sessions in
one shared WorkGroup. Exact REVIEW admission, owner responses, grant
consumption, immutable snapshot publication, structured ACCEPT, and one-lane
integration all succeeded. The review decision projection intentionally
exposed an explicit ACCEPT/REJECT choice rather than an incomplete command;
the reviewer submitted the valid existing response and Synesis accepted it.

The run stopped at repeated valid `WAIT -> get_next_action({})` continuations
before reciprocal grant consumption and implementation publication. One grant
call changed the projected informational target participant but was still
authorized by the bound caller and consumed; this is agent-compliance evidence.
No unchanged concrete projection failed and no new production defect is
proven.

## Historical next action

Run another fresh ordinary unattended two-agent Todo acceptance with only
complementary visible coding prompts. Do not change the review-decision
contract, push, or create SYN-040 from this evidence alone.

## Work completed

Recorded the fresh CP-0540 exact-action diagnostic with complete WorkGroup,
participant, claim/epoch, request, grant, snapshot, validation, integration,
projection, and Doctor evidence. No production code changed; existing
fail-closed review and grant behavior remains intact.

## Current failures

The diagnostic WorkGroup remains ACTIVE because provider sessions stopped at
valid polling continuations before the reciprocal grant and implementation
snapshot. The control checkout contains the test snapshot but not the
implementation snapshot, so control pytest is 1 passed / 3 failed. Doctor is
DEGRADED with six warnings; the focused MCP test reproduced the known Git
subprocess stall, and bootstrap migration failures remain separate
verification issues.

- Task ID: SYN-039
- Status: ACTIVE
- Checkpoint: CP-0540

## SYN-039 CP-0539 ordinary acceptance compliance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0539-ordinary-compliance-2026-08-25.md`.

The CP-0537 pending outgoing REVIEW projection defect and the CP-0538
completed-lane terminal projection defect are fixed and regression-covered.
Fresh ordinary CP-0539 reached one shared WorkGroup, reciprocal REVIEW
requests, accepted grants, immutable snapshot publication, integration, and
structured ACCEPT. Every unchanged projection remained executable. One agent
omitted a projected `targetParticipant` once and received the expected
fail-closed error before correcting the call; later it stopped despite a
projected `WAIT -> get_next_action({})`. These are provider/session
compliance boundaries, not new production defects.

## Historical next action

Run another fresh ordinary unattended two-agent Todo acceptance with only
complementary visible coding prompts and the rebuilt bundled MCP. Preserve
the first unchanged projected-action failure or required state with no usable
projection. Do not relay, manually transition, push, or create SYN-040.

## Work completed

Fixed and regression-covered the pending outgoing REVIEW re-projection and
the completed-lane terminal projection defects. CP-0539 exercised both fixes
through accepted reciprocal REVIEW, grant consumption, immutable snapshot
publication, snapshot validation, structured ACCEPT, and integration. No
unchanged projected action failed. Nothing was pushed and no SYN-040 was
created.

## Current failures

The ordinary acceptance remains incomplete because provider sessions stopped
despite usable `WAIT -> get_next_action({})` continuations; one earlier grant
call also omitted a projected `targetParticipant` and was correctly rejected
fail-closed before correction. Fixture Doctor remains DEGRADED with six
warnings, while the Git subprocess stall and bootstrap migration failures are
separate verification issues.

- Task ID: SYN-039
- Status: ACTIVE
- Checkpoint: CP-0537

## SYN-039 CP-0536 review projection fix and post-fix acceptance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0536-review-gate-2026-08-25.md`.

The recorded CP-0535 state exposed a concrete stale-review projection defect:
after a REVIEW grant had a completed validation, the fallback branch could
re-project `REVIEW_ADMISSION_REQUIRED` for that already-reviewed intent. The
narrow `AgentNextActionService` filter and deterministic MCP regression are
implemented. Provider guidance also now requires executing concrete mutation
continuations and polling `get_next_action` after every completed mutation.

The fresh post-fix bounded diagnostic reached one shared WorkGroup, exact
REVIEW admission, grant consumption, immutable snapshot publication, snapshot
inspection, and structured ACCEPT. It stopped with a reciprocal grant pending
because the provider session did not remain engaged; no unchanged projected
action failed. The ordinary post-fix probe stopped at the same provider/session
engagement boundary before the second snapshot. This remains agent/session
compliance evidence, not a new production lifecycle defect.

## Historical next action

Run one fresh ordinary unattended two-agent Todo acceptance using only
complementary visible coding prompts and the rebuilt bundled MCP. Preserve the
first unchanged projection failure or missing usable action. Do not relay,
manually transition, push, or create SYN-040.

## SYN-039 CP-0535 continuation diagnostic and ordinary acceptance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0534-continuation-2026-08-25.md`.

The fresh bounded diagnostic applied the exact-action rule to both
`get_next_action` projections and concrete continuations returned by mutating
tools. It completed one shared WorkGroup through REVIEW admission, both
single-use grants, immutable snapshots, structured ACCEPT decisions,
integration, and terminal `COMPLETED` state. No unchanged projected action
failed.

The required second run used only ordinary complementary coding prompts. It
reached one shared WorkGroup, exact admission, grant consumption, immutable
snapshot publication, integration, and structured ACCEPT for Agent A. Agent
A then ended its provider turn after `finish_lane` returned the exact
reciprocal `request_coordination(work_group_join)` continuation for Agent B's
intent. Agent B remained active and later received only ordinary `IMPLEMENT`
projections with no executable lifecycle action. This is provider/session
compliance evidence, not a proven production lifecycle defect. No production
code changed.

## Historical next action

Preserve CP-0535 evidence and checkpoint state. Do not modify lifecycle code,
push, or create SYN-040. A future SYN-039 slice is not justified unless an
unchanged executable continuation fails or a required state still has no
usable projection after all preceding projections are executed.

## SYN-039 CP-0534 ordinary acceptance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0534-ordinary-2026-08-25.md`.

The fresh ordinary acceptance used the rebuilt bundled MCP, two independent
ready/isolated sessions, one shared WorkGroup, and disjoint epoch-1 claims.
The run reached exact REVIEW admission, owner acceptance, single-use grant
consumption, immutable snapshot publication, integration, and structured
ACCEPT. A's `finish_lane` result then projected the exact reciprocal
`request_coordination(work_group_join)` for B's intent, but A's provider turn
ended without executing it. B later corrected one invalid review identity and
then followed bounded polling; `get_next_action` repeatedly returned ordinary
`IMPLEMENT` with no executable lifecycle action while B remained ACTIVE.

No unchanged `get_next_action` projection failed. The first concrete boundary
is agent/session compliance with a continuation returned by a mutating tool,
not a new production protocol defect. No second ordinary acceptance was run.

## Historical next action

Run one bounded diagnostic applying the exact-projected action rule to
continuation actions returned by mutating tools as well as `get_next_action`.
Do not modify lifecycle code, relay messages, or trigger transitions unless an
unchanged executable action fails or the same state still has no usable
projection after all preceding continuations are obeyed.

## SYN-039 CP-0533 ordinary acceptance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0533-ordinary-2026-08-25.md`.

The fresh ordinary acceptance used the current bundled MCP, the same project,
distinct ready/isolated sessions, and disjoint epoch-1 claims. Both agents
converged on WorkGroup `62e57bbc-897e-3946-a5d2-5082d1e1a2c1`. Agent B
executed the exact projected `request_coordination(work_group_join)` and
created pending REVIEW request `574c290b-36bd-417e-9286-dce2d9a57cc6`.
Agent A had already ended its ordinary coding turn while its last projection
was `IMPLEMENT` without a lifecycle action, so it never received the later
owner-acceptance projection. No grant, snapshot, validation, integration, or
terminal WorkGroup state was created. Control pytest was `4 failed, 1 passed`.

No unchanged projected Synesis action failed. The first blocker is ordinary
provider/session engagement, not a proven production protocol defect.

## Historical next action

Run one bounded continuity diagnostic with the same two independent provider
sessions and no manual relay: preserve the owner session across the delayed
peer REVIEW request, then verify whether Synesis projects and the owner
executes the exact acceptance action. Do not add retries, orchestration,
cleanup, or argument repair unless an unchanged projected action fails.

## Work completed

Recorded the CP-0533 fresh ordinary acceptance and exact provider traces. The
run proved shared WorkGroup convergence and successful exact REVIEW admission
request creation, but not owner acceptance or later lifecycle stages. No
production code changed, no remote state was modified, no lifecycle transition
was manually triggered, and no SYN-040 was created.

## Current failures

The remaining SYN-039 acceptance gap is ordinary provider/session engagement:
the owner session ended after ordinary implementation work before a later peer
REVIEW request existed, leaving that request pending. No unchanged projected
Synesis action failed. Control pytest is `4 failed, 1 passed`; fixture Doctor
is DEGRADED with six warnings and no errors or critical findings. The root Git
subprocess stall and bootstrap migration failures remain independent.

## SYN-039 CP-0532 exact diagnostic closure and ordinary acceptance boundary

Evidence:
`docs/evidence/syn039-unattended-todo-cp0532-exact-diagnostic-and-ordinary-2026-08-25.md`.

The fresh exact-projection diagnostic used two independent GPT-5.6 Luna High
sessions and the current bundled MCP. WorkGroup
`35931e39-9eb1-3693-b03e-b89fc7088b72` reached exact REVIEW admission, both
single-use grants, immutable snapshot inspection, structured ACCEPT decisions,
both snapshot integrations, and `workGroupStatus=COMPLETED`. The control
checkout was clean at `04c2720` and reported `5 passed`. Every concrete
projected lifecycle action in the retained trace was executed exactly; no
unchanged projection failed.

The required second ordinary acceptance used only complementary coding
prompts. WorkGroup `4646b6ba-66bc-3760-8fda-fc04b9db1b66` integrated the test
snapshot and exercised structured REJECT routing, but an ordinary reviewer
first sent unsupported review fields, then a wrong intent, and the provider
sessions stopped before reciprocal grant consumption and implementation
publication. Control reported `1 failed, 4 passed`. These are agent
compliance/session-engagement findings, not a proven production defect.

The full root `check` still reproduces the known Git subprocess startup stall
in `ProcessCommandRunner.execute` during `:cli:test`; bootstrap migration
failures and Doctor warnings remain separately classified.

## Historical next action

Run one fresh ordinary unattended two-agent Todo acceptance with only the
complementary coding prompts and the current bundled MCP. Do not relay,
trigger, repair, or coach lifecycle actions. Preserve the first unchanged
projection failure or missing usable action; classify wrong agent arguments and
provider-turn termination separately unless an exact projected action fails.

## Work completed

Recorded the CP-0532 exact diagnostic and ordinary acceptance evidence. The
exact-rule run proved end-to-end protocol correctness and terminal WorkGroup
completion; the ordinary run preserved fail-closed rejection and exposed only
agent/session engagement gaps. No production code changed, no remote state was
modified, no lifecycle transition was manually triggered, and no SYN-040 was
created.

## Current failures

The remaining SYN-039 product acceptance gap is ordinary provider/session
engagement: the CP-0542 ordinary run stopped with reciprocal review state still
active after invalid agent-selected review payloads were rejected fail-closed.
The exact CP-0541 diagnostic completed the same lifecycle, so no new Synesis
protocol defect is proven. Fixture Doctor remains DEGRADED with six warnings;
the root Git subprocess stall and three bootstrap migration test failures remain
independent verification issues.

## SYN-039 CP-0530 pytest-artifact recovery fix and post-fix diagnostic

Evidence:
`docs/evidence/syn039-unattended-todo-cp0530-bytecode-recovery-2026-08-25.md`.

The CP-0530 baseline proved a concrete readiness/recovery defect: normal
pytest-generated `__pycache__/` files made a reviewer worktree appear dirty,
so the unchanged projected `ensure_session({})` action returned
`failed / internal_failure / request_human_help`. The stale workspace
classifier was inconsistent with the existing `SnapshotArtifactPolicy`,
which already treats `__pycache__/` as an allowed runtime artifact.

`ProviderSessionBindingService` now recognizes only `__pycache__/` as the
additional ephemeral category for stale-workspace cleanliness and confirmed
user-change checks. Unknown untracked content remains fail-closed. The new
workspace regressions were red before the change and green after it.

The post-fix diagnostic used a fresh project, current bundled MCP, two
independent GPT-5.6 Luna High sessions, disjoint epoch-1 claims, and one
shared WorkGroup. B executed the exact projected recovery action and was
rebound successfully; the recovery defect is therefore fixed. The run later
stopped on agent compliance when A supplied a malformed `intentId` instead of
the exact projected REVIEW admission payload, then ended while A still had a
valid projected continuation. No unchanged projected action failed. The
conditional second ordinary acceptance was not run.

## Historical next action

Run one fresh bounded two-agent exact-projection diagnostic with the current
rebuilt MCP and the same complementary Todo responsibilities. Capture whether
both agents execute every unchanged projection, especially the reciprocal
REVIEW request and subsequent `WAIT -> get_next_action({})`, without any relay
or manual transition. Do not change production code from agent-selected wrong
arguments or a provider turn ending; only a concrete unchanged projected
action failure or missing usable action can justify another SYN-039 slice.

## Work completed

Implemented and verified the narrow pytest-artifact recovery fix, added
fail-closed regression coverage for real untracked user content, rebuilt the
Windows platform bundle, and recorded the fresh post-fix diagnostic. No remote
state was modified, no lifecycle transition was manually triggered, and no
SYN-040 was created.

## Current failures

The remaining SYN-039 blocker is ordinary agent/session engagement: CP-0530
WorkGroup `ab7d068e-f6cb-3c88-84e7-be59bf3e2c20` did not reach terminal closure
because A first changed the projected intent ID and later ended while its
reciprocal review continuation remained unresolved. Fixture Doctor remains
DEGRADED with six warnings. The known root Git subprocess stall and three
bootstrap migration test failures remain independent verification issues.

## SYN-039 CP-0529 continuity probe and ordinary acceptance

Evidence:
`docs/evidence/syn039-unattended-todo-cp0529-continuity-and-ordinary-2026-08-25.md`.

The supported non-ephemeral continuity probe preserved the same participant
and session identities across the durable WAIT boundary. The implementer
received and executed the exact projected `finish_lane` action, publishing
immutable snapshot `snap_3e21542358dd37d57cb6963d6f128557`. The reviewer then
reached a projected `ensure_session`, but its ordinary pytest-generated
`__pycache__` files made the review worktree dirty under the existing
fail-closed cleanliness rule; `ensure_session` returned
`failed / internal_failure / request_human_help`. No unchanged projected
Synesis action failed and no production defect was proven.

The fresh ordinary acceptance reached shared WorkGroup
`c8834a58-fe9d-3a75-8b56-bbf7a86f7a6a`, integrated test-only snapshot
`snap_d678f31fc5591c897c7a648c41d4322d`, and recorded a structured ACCEPT.
The reviewer then received the exact executable `request_coordination`
projection for the reciprocal REVIEW admission but chose another
`get_next_action` call and its turn ended. The implementation snapshot was
not published; the control checkout therefore remained at `2 failed, 2
passed`. This is agent/session engagement evidence, not a proven protocol
defect.

## Historical next action

Run one fresh ordinary unattended two-agent Todo acceptance with only
complementary coding prompts and the current bundled MCP. Do not provide
lifecycle coaching, relay messages, trigger transitions, or repair state
manually. Keep the known root Git subprocess stall, bootstrap migration
failures, and six Doctor warnings separately classified. Do not change
production code, push, or create SYN-040 unless an unchanged projected action
fails or required progress has no usable projection.

## Work completed

Recorded the CP-0529 continuity and ordinary acceptance evidence. No
production code changed, no remote state was modified, and no manual
coordination transition, relay, ownership repair, merge, or control-checkout
mutation was performed.

## Current failures

The remaining SYN-039 gap is ordinary provider/session engagement: WorkGroup
`c8834a58-fe9d-3a75-8b56-bbf7a86f7a6a` is ACTIVE with B COMPLETED and A
ACTIVE after B ignored the exact projected reciprocal REVIEW admission.
Doctor is DEGRADED with six warnings and no errors or critical findings.
The root Git subprocess stall and bootstrap migration failures remain
independent verification issues.

## SYN-039 CP-0528 bounded diagnostic and ordinary acceptance

Evidence:
`docs/evidence/syn039-unattended-todo-cp0528-diagnostic-and-ordinary-2026-08-25.md`.

The fresh bounded exact-projection diagnostic used the current bundled MCP,
two independent GPT-5.6 Luna High sessions, disjoint epoch-1 claims, and one
WorkGroup `f0c02558-ab10-3bf6-b369-1d21011ffe64`. Exact REVIEW admission,
owner responses, both single-use grant consumptions, both immutable snapshot
publications, structured ACCEPT decisions, integration, control pytest 4/4,
and terminal WorkGroup `COMPLETED` all succeeded. No unchanged projected
lifecycle action failed.

The required second ordinary acceptance used only the complementary coding
prompts. It reached WorkGroup `b319999f-7060-360b-a26b-0a0891e23be1`,
integrated implementation snapshot `snap_3596291c5018036277f7a780da877dea`,
and recorded reviewer ACCEPT. The implementer session ended after submitting
the reciprocal REVIEW request; grant `abacded1-ee9c-354f-9271-dabcd00bffa5`
targeted that ended participant and remained unresolved. The WorkGroup stayed
ACTIVE; control pytest passed 3/3 and contains the implementation but not the
test-lane snapshot. This is agent/session engagement evidence, not a proven
protocol defect.

## Historical next action

Run one fresh supported non-ephemeral Codex provider-session continuity probe
across the ordinary pending-grant `WAIT -> get_next_action({})` boundary,
preserving both independent sessions and without lifecycle coaching, relay,
manual transitions, replacement intents, or production changes. If the
provider still ends before consuming the exact projected grant, classify the
boundary as external agent/session behavior and keep Synesis production code
unchanged. Do not push or create SYN-040.

## Work completed

Recorded CP-0528 evidence for both the successful exact-projection diagnostic
and the incomplete ordinary acceptance. No production code changed and no
remote state was modified.

## Current failures

The remaining SYN-039 acceptance gap is ordinary provider/session engagement:
the ordinary run leaves WorkGroup `b319999f-7060-360b-a26b-0a0891e23be1`
ACTIVE with a valid single-use grant targeted at the ended implementer. No
exact projected lifecycle action failed. Doctor remains DEGRADED with six
warnings; the root Git subprocess stall and bootstrap migration failures are
separately classified.

## SYN-039 CP-0527 REVIEW replay fix and post-fix diagnostic

Evidence:
`docs/evidence/syn039-unattended-todo-cp0527-review-replay-fix-2026-08-25.md`.

The prior ordinary acceptance exposed a real unchanged projected REVIEW
admission failure: replay after the target lane released its live intent
returned `INTENT_NOT_FOUND`. Commit `81aa2f6` now checks the durable exact
REVIEW request before resolving the released live intent. Deterministic
coordination and MCP regressions pass, including idempotent replay and
fail-closed new requests.

The fresh post-fix diagnostic reached one WorkGroup, exact REVIEW admission,
owner responses, grant consumption, immutable snapshot publication,
integration, and structured REJECT. The provider turn ended before the
reciprocal REVIEW grant was consumed, so the WorkGroup remains ACTIVE. No
unchanged projected action failed in the post-fix run.

## Historical next action

Run one fresh completely ordinary unattended two-agent Todo acceptance using
only the complementary coding prompts and the current bundled MCP. Do not
provide protocol-conformance instructions, relay messages, trigger lifecycle
actions, or repair state manually. Stop at the first unchanged projected
action failure or state requiring progress with no usable projection. If it
reaches clean WorkGroup completion, inspect the remaining Doctor warnings.
Do not push or create SYN-040.

## Work completed

The released-lane REVIEW replay defect is fixed in `81aa2f6`; the exact
pre-fix failure, red/green regression evidence, post-fix IDs and action trace,
and independent verification classifications are recorded in the evidence
file above. No remote state was changed.

## Current failures

The required ordinary end-to-end acceptance is still incomplete at the
provider/session continuation boundary: WorkGroup
`ff42da2a-719f-34cb-8851-de17edb9aba8` is ACTIVE with reciprocal grant
`6eb5cd9c-f949-3080-9bc1-5391a6db17cd` pending after the Codex turn ended.
Doctor remains DEGRADED with six separately classified warnings. The root Git
subprocess stall and bootstrap migration failures remain independent
verification issues.

## SYN-039 CP-0525-003 bounded and ordinary acceptance

Evidence:
`docs/evidence/syn039-unattended-todo-cp0525-003-bounded-and-ordinary-2026-08-25.md`.

The fresh bounded exact-projection diagnostic completed WorkGroup
`18f226ad-d28b-3fd6-b8aa-3afb83429f4b` through REVIEW admission, exact grant
consumption, immutable snapshot publication, structured ACCEPT, integration,
and terminal completion. No unchanged projected action failed. The required
ordinary acceptance reached WorkGroup `0d6e6301-e6d1-3084-b0be-abbca3cdaa10`,
integrated A's implementation snapshot, and accepted it from B, but A's
Codex session ended before the reciprocal grant targeted at A was consumed.
This remains provider/session engagement evidence, not a proven production
defect.

## Historical next action

Run one supported non-ephemeral Codex provider-session continuity probe in a
fresh disposable project, preserving both independent sessions across a
durable `WAIT -> get_next_action({})` boundary. Do not manually invoke a
Synesis lifecycle action, create a new intent to repair the old run, relay
coordination, or modify production code. If the provider still ends without
executing the unchanged projection, classify the limitation at the provider
session boundary and keep Synesis lifecycle code unchanged. Do not push or
create SYN-040.

## Work completed

The CP-0525-003 bounded and ordinary traces, exact IDs, terminal states, and
Doctor output are recorded in the evidence file above. No production code
changed in this slice.

## Current failures

The ordinary product acceptance remains incomplete only at provider/session
continuation: WorkGroup `0d6e6301-e6d1-3084-b0be-abbca3cdaa10` is ACTIVE with
the reciprocal grant targeted at the ended A session. The bounded protocol
diagnostic completed cleanly. Doctor remains DEGRADED with six warnings. The
root Git subprocess stall and bootstrap migration failures remain separately
classified.

## SYN-039 CP-0525 bounded and ordinary acceptance

Evidence:
`docs/evidence/syn039-unattended-todo-cp0525-002-bounded-and-ordinary-2026-08-24.md`.

The fresh bounded exact-projection diagnostic completed one shared WorkGroup
through REVIEW admission, exact grant consumption, immutable snapshot
publication, structured REJECT/ACCEPT, integration, and terminal WorkGroup
completion. No unchanged projected action failed. The required second ordinary
acceptance reached one shared WorkGroup, integrated both complementary
snapshots, and passed control pytest 3/3, but stopped with WorkGroup
`5e0a82d7-635d-3e47-9e3e-5a4c37d83822` ACTIVE while the agent session did not
continue the valid projected reviewer continuation. This remains agent/session
engagement evidence, not a proven production defect.

## Historical next action

Run one bounded provider-session continuation check from the preserved CP-0525
ordinary `WAIT` boundary, retaining the same participant/intent and executing
only the projected `get_next_action({})`. Do not create a new coding intent or
relay lifecycle state. If the provider ends again without executing the exact
continuation, classify the limitation as external agent/session behavior and
do not change production lifecycle code. Do not push or create SYN-040.

## Work completed

The bounded and ordinary acceptance traces and terminal states are recorded in
the evidence file above. No production code changed in this slice.

## Current failures

The ordinary product acceptance remains incomplete only at the Codex
session-engagement boundary. The ordinary control checkout passed 3/3 but
contains a disposable untracked `__pycache__/` artifact; this is not evidence
for changing integration behavior. Doctor remains DEGRADED with six warnings.
The root Git subprocess stall and bootstrap migration failures remain
separately classified.

## SYN-039 CP-0522 third ordinary acceptance

The third fresh ordinary run used current bundled MCP sessions and the same
two visible coding prompts. Both agents reached ready/isolated state and one
shared WorkGroup. A implemented `todo.py`, then followed exact REVIEW
admission and grant-consumption projections. Once A received `WAIT` with
`recommendedTool=get_next_action` and `{}`, it instead performed unprojected
reads/recovery and hit the expected fail-closed `workspace_stale` and
`internal_failure` responses. B's exact `finish_lane` succeeded, publishing
and integrating `snap_012bbfe1bc5f22b8e69d51e9638b4c05`; A rejected that
snapshot because its completion test failed against the incomplete base.
WorkGroup `e769b143-f9b0-337f-b06a-9eb1603c8cc9` remains `ACTIVE`.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0522-third-ordinary-2026-08-24.md`.

No unchanged projected lifecycle action failed and no production protocol
defect is proven. This is a repeat of the ordinary agent/session projection
compliance boundary, with additional evidence that a test-only lane can be
published before the sibling implementation lane is integrated when the
agent executes the projected action despite its local failing test.

## Historical next action

Keep production code unchanged. Run a bounded provider-session continuity
diagnostic at a fresh durable `WAIT → get_next_action({})` boundary, recording
whether the ordinary Codex session can execute the unchanged projection and
continue to the next action without lifecycle coaching. Implement only a
concrete unchanged projected-action failure; otherwise preserve the
agent/session limitation. Do not push or create SYN-040.

## Work completed

The third ordinary acceptance evidence is recorded alongside the successful
bounded CP-0522 diagnostic and the prior ordinary run. No production code
changed and nothing was pushed.

## Current failures

The real ordinary acceptance remains incomplete: A ignored a concrete WAIT
projection and did not publish its implementation snapshot; B's test snapshot
integrated but control pytest is `1 failed, 2 passed`. The WorkGroup remains
ACTIVE with an unresolved REVIEW grant. Doctor remains DEGRADED with six
warnings. The root Git subprocess stall and bootstrap migration failures are
still independent verification issues. No SYN-040 is defined or created.

## SYN-039 CP-0522 valid diagnostic and ordinary acceptance

The corrected bounded diagnostic used a genuinely incomplete Todo seed and
completed the existing protocol without relay: shared WorkGroup discovery,
exact REVIEW admission, both single-use grants, immutable snapshot
publication, structured ACCEPT decisions, integration, and terminal
WorkGroup completion. Evidence:
`docs/evidence/syn039-unattended-todo-cp0522-valid-diagnostic-and-ordinary-2026-08-24.md`.

The second run used only the two ordinary coding prompts. It reached one
shared WorkGroup, integrated Agent A's snapshot, and submitted B's ACCEPT,
but A's Codex turn ended after a valid projected reciprocal
`request_coordination` action was exposed. B correctly remained in exact
projected `WAIT`; WorkGroup
`0f999cd8-e9b2-38cc-a382-ab6722b76139` remained `ACTIVE`, with A's grant
`051f07ff-e0c0-3f10-8422-705d066afc57` pending. No unchanged projected
action failed and no production protocol defect is proven.

## Historical next action

Run one third fresh ordinary unattended two-agent Todo acceptance with the
same actual complementary coding prompts and no lifecycle coaching. Retain
the exact projection/action trace. If the same turn-ending boundary repeats,
record it as the external agent/session blocker; do not change production
coordination, review, snapshot, validation, integration, cleanup, or Doctor
behavior without a concrete unchanged projected action failure or a missing
usable action.

## Work completed

The bounded diagnostic and ordinary acceptance evidence are recorded. The
current bundled MCP, focused SYN-039 tests, validators, Javadocs, Go vet,
control pytest, and `git diff --check` remain passing where previously
verified. No production code changed in CP-0522 and nothing was pushed.

## Current failures

The ordinary product acceptance remains incomplete only at agent/session
engagement: Agent A ended instead of consuming a repeated concrete
projection, while Agent B correctly followed `WAIT`. Doctor remains
DEGRADED with six separately classified warnings. The root Git subprocess
stall and bootstrap migration failures remain independent verification
issues. No SYN-040 is defined or created.

## SYN-039 CP-0521 continuation diagnostic seed correction

The bounded continuation diagnostic used a fresh project, but its seed already
implemented `TodoList.complete`. Agent A correctly made no artificial edit,
remained in `IMPLEMENT`, and never reached snapshot publication. Agent B
correctly waited at `SNAPSHOT_PENDING`, later added its test, and passed 4/4;
no snapshot, validation, integration, or closure occurred. This is invalid
fixture/agent evidence, not a Synesis defect.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0521-invalid-seed-continuation-2026-08-24.md`.

## Historical next action

Run a fresh bounded two-agent diagnostic with a genuinely missing
`TodoList.complete` implementation, current bundled MCP, and the existing
no-code completed-lane continuation harness. Preserve the first unchanged
projected-action failure or missing usable action. If the diagnostic closes,
run the ordinary acceptance with only the two coding prompts. Do not change
production code, push, or create SYN-040 unless a concrete Synesis defect is
reproduced.

## Work completed

CP-0521 invalid-seed evidence is recorded. No production code changed. The
current MCP bundle, focused SYN-039 tests, validators, Go vet, and prior
CP-0520 acceptance evidence remain preserved.

## Current failures

The overall ordinary acceptance remains incomplete. CP-0520 stopped because
Agent A ended before reciprocal REVIEW-grant consumption; CP-0521 did not
exercise that phase because its seed was already complete. No production
protocol failure is currently proven. Doctor warnings, the root Git stall,
and bootstrap migration failures remain separately classified.

## SYN-039 CP-0520 ordinary completed-lane acceptance

The fresh ordinary acceptance used the current bundled MCP, two independent
ready/isolated Codex sessions, disjoint claims, and one shared WorkGroup
`5c1609bd-f88d-36e5-845b-0f07677e9ffe`. Agent A published and integrated
snapshot `snap_41f8664537c23fe67293f8e08f740fa6`; Agent B consumed the REVIEW
grant, inspected the immutable snapshot, and submitted ACCEPT. The corrected
harness stopped resuming A after its original lane became `COMPLETED`, so it
did not create a replacement intent.

Agent A then ended without polling its already projected reciprocal REVIEW
grant. Agent B correctly remained in `WAIT` with
`recommendedTool=get_next_action` and `{}` while grant
`22bc7d10-0337-31c9-9155-6de7f0130b73` targeted A. WorkGroup state remained
`ACTIVE`; no unchanged projected action failed. This is ordinary agent/session
engagement evidence, not a production protocol defect.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0520-ordinary-completed-lane-2026-08-24.md`.

## Historical next action

Run one bounded no-code continuation diagnostic in a fresh disposable project:
retain a completed participant only long enough to execute any already
projected REVIEW action, without announcing a new coding intent or relaying
coordination; if it closes cleanly, rerun the ordinary two-agent acceptance.
Do not change production lifecycle code, push, or create SYN-040 unless an
unchanged projected action fails.

## Work completed

Fresh ordinary acceptance evidence is recorded. The current MCP bundle,
focused `McpSyn039SliceTest`, deferred/fixture validators, Go vet, control
pytest 3/3, and `git diff --check` pass. No production code changed in this
slice.

## Current failures

Ordinary acceptance is still partial: Agent A ended after publishing its
integrated snapshot and exact REVIEW admission request, before polling the
reciprocal REVIEW grant targeted at A. Agent B correctly remained in exact
WAIT, so WorkGroup `5c1609bd-f88d-36e5-845b-0f07677e9ffe` remains `ACTIVE`.
This is agent/session engagement evidence; no unchanged projected action
failed and no production defect is proven. Doctor remains DEGRADED with six
separately classified warnings.

## SYN-039 command-scope recovery and post-fix acceptance

The CP-0519 ordinary acceptance proved a concrete MCP command-scope defect:
after successful clean recovery moved a participant to a new verified
worktree, `run_command` retained the old physical `ProjectCommandProcessAnchor`
and returned `command_admission_stale / MCP_PROCESS_SCOPE_CHANGED`. The narrow
fix is implemented in `McpProtocolHandler`; successful `ensure_session` now
re-arms the anchor only when a separately verified worktree locator changed.
The deterministic regression and full `McpSyn039SliceTest` pass.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0519-command-scope-recovery-2026-08-24.md`.

The post-fix exact-action diagnostic reached both reciprocal review paths,
single-use grant consumption, snapshot publication, a structured REJECT and
ACCEPT, integration, clean control `pytest` 3/3, and WorkGroup
`89fea014-9f5b-326b-8521-5d2218cc55fc` `COMPLETED`. The second ordinary run
reached the same core lifecycle but retained an extra continuation participant
after the harness resumed a completed lane; its WorkGroup
`dfc93a1a-de2e-3db4-859e-c0eb7d60eaab` remains `ACTIVE`. That remainder is
agent/session engagement evidence, not a new production defect.

## Historical next action

Run one fresh ordinary unattended two-agent Todo acceptance with retained
sessions, but do not resume a completed lane as a new coding intent. Preserve
the first unchanged projected-action failure or missing usable action. Do not
change production lifecycle behavior, push, or create SYN-040 without that
evidence.

## Work completed

Focused MCP regression, full SYN-039 MCP slice, MCP Javadocs, bundled MCP
rebuild, deferred and fixture validators, Doctor structural checks, Go vet,
and `git diff --check` pass. Fixture Doctor remains DEGRADED with six
separately classified warnings. The known root Git subprocess stall,
bootstrap migration failures, and documentation format findings remain
independent verification issues.

## Current failures

The clean exact-action diagnostic is complete, but ordinary product acceptance
is not yet clean: the retained Codex harness continued after A's original lane
was integrated, created a new active `todo.py` participant, and stopped with
an ACTIVE WorkGroup. This must be resolved as harness/session engagement
behavior or reproduced in a clean ordinary run before any further production
change.

## SYN-039 CP-0536 terminal-WorkGroup guard and acceptance evidence

CP-0535 proved a concrete lifecycle defect: a late disjoint intent could be
announced into a completed WorkGroup, leaving an active participant with no
executable completion action. The narrow fix is present in
`WorkIntentService` and `WorkspaceCollaborationService`, with deterministic
coordination and workspace regressions. Evidence and the fresh acceptance
traces are recorded in
`docs/evidence/syn039-unattended-todo-cp0536-bounded-and-ordinary-2026-08-24.md`.

The bounded CP-0536 diagnostic reached exact REVIEW admission, grant
consumption, both snapshot publications, immutable review inspection,
structured REJECT/ACCEPT, integration, and WorkGroup `COMPLETED` with control
pytest 5/5. The second ordinary acceptance reached one shared WorkGroup and
integrated the test snapshot, but stopped when an agent changed then ignored a
concrete projected coordination action. Its WorkGroup remains ACTIVE with the
reciprocal grant unresolved. No additional production defect is proven.

## Historical next action

Run one fresh ordinary unattended two-agent Todo acceptance with the harness
retaining both independent Codex sessions across durable WAIT/projection
continuations; do not relay or trigger transitions, and do not change
production code unless an unchanged projected action fails. Do not push or
create SYN-040.

## Work completed

The terminal-WorkGroup guard and fresh-default-group allocation are implemented
and their deterministic RED/GREEN regressions pass. The CP-0536 bounded run
proves the existing review/snapshot/validation/integration path can close a
WorkGroup. The ordinary run is preserved as agent-compliance evidence rather
than used to justify speculative lifecycle changes. Focused tests, Javadocs,
bundle rebuild, validators, Go vet, and diff checks pass; the known full-check,
Git subprocess, bootstrap migration, and Doctor issues are recorded in the
CP-0536 evidence.

## Current failures

The ordinary CP-0536 WorkGroup
`1c9fd0e2-eda4-3505-a20e-db86de14ec8a` remains ACTIVE because Agent B did not
execute its projected `request_coordination(work_group_join)` and then ended
its turn; reciprocal grant `4ba34d35-976a-3d55-bc40-0d7c9656f46b` remains
unresolved. This is not an unchanged projected-action failure. The root Git
subprocess stall, three bootstrap migration failures, and Doctor's six
DEGRADED warnings remain separately classified.

## SYN-039 CP-0534 reviewer snapshot access

CP-0534 is recorded in
`docs/evidence/syn039-unattended-todo-cp0534-review-snapshot-access-2026-08-24.md`.
Commit `a03abe0` fixes the concrete reviewer snapshot-access defect: an
authorized reviewer can use the existing `read_file` and `run_command` tools
against a disposable immutable snapshot workspace after the control checkout
advances, without rebinding, discarding its own dirty lane, or acquiring write
ownership. The projection and provider/catalog guidance expose the exact
snapshot, grant, commit, and read-only boundary. Deterministic focused tests
cover successful access and fail-closed wrong-participant and mismatched-ref
cases.

The fresh two-agent diagnostic reached one shared WorkGroup, exact REVIEW
admission, owner acceptance, grant consumption, snapshot publication and
integration, and a structured ACCEPT after the reviewer inspected and tested
the immutable snapshot. Agent A then ended before consuming the reciprocal
REVIEW grant targeted at A; Agent B correctly remained in WAIT. The WorkGroup
`895e9681-8d66-37c0-b3b7-6eb88aa57838` remains ACTIVE. This is agent
engagement/compliance evidence, not a new production defect.

## Historical next action

Run one fresh bounded diagnostic with both agents kept engaged through the
reciprocal grant, second snapshot publication, reviewer validation, accepted
integration, cleanup, and terminal WorkGroup state; do not change production
lifecycle code unless an engaged agent executes an unchanged projection that
fails or Synesis requires progress while projecting no usable action.

## Work completed

The CP-0534 reviewer-access slice is implemented and verified. Focused MCP,
workspace, provider guidance, catalog, Javadocs, validators, Doctor, bundle,
and diff checks pass. The root `check` remains separately blocked by the known
Git subprocess startup stall, and bootstrap Go tests retain the three known
migration failures. Doctor remains DEGRADED with the previously recorded
warnings; neither issue is causal to the CP-0534 reviewer-access fix.

## Current failures

The CP-0534 diagnostic did not reach terminal completion because Agent A's
session ended before consuming its exact reciprocal REVIEW grant. Agent B
correctly remained in WAIT; no unchanged projected Synesis action failed and
no new production lifecycle defect is proven. The WorkGroup remains ACTIVE.
The root `check` still reproduces the bounded Git subprocess startup stall at
`WorkspaceCliTest.setUp:74` through `ManagedBaselineTransactionService` and
`GitProcessRunner`. Bootstrap `go test ./...` still has the three known
migration failures. Doctor remains DEGRADED with six previously classified
warnings.

## SYN-039 CP-0533 engaged diagnostic

CP-0533 used a fresh Git + Synesis project and two independent GPT-5.6 Luna
High Codex sessions. Both sessions reached the current bundled MCP, distinct
ready/isolated bindings, disjoint claims, and one WorkGroup. The diagnostic
trace is recorded in
`docs/evidence/syn039-unattended-todo-cp0533-engaged-diagnostic-2026-08-24.md`.

The run reached exact REVIEW admission, owner acceptance, grant consumption,
both exact projected `finish_lane` calls, immutable snapshot publication,
integration, structured review responses, and durable WorkGroup
`cf3f65dd-c43b-3ad1-855b-0d72c68a419a` `COMPLETED`. Control contains both
accepted lane changes and `pytest` passes 4/4.

## Historical next action

Reproduce the reviewer snapshot-read recovery transition in a deterministic MCP fixture and capture binding, worktree,
control revision, snapshot, and lease state before changing production code.

## Work completed

CP-0532's snapshot artifact fix remains verified: generated Python bytecode no
longer conflicts during disjoint snapshot integration. CP-0533 additionally
proved that both lanes can publish/integrate and the WorkGroup can close. No
production code changed for CP-0533. Focused SYN-039 tests, control pytest,
fixture validators, Doctor, and `git diff --check` passed. The root check
remains separately blocked at `:link:formatCheck` by trailing whitespace in
older checkpoint/evidence documents; the known Git launch stall and bootstrap
migration failures remain separate.

## Current failures

The first concrete post-integration blocker is reviewer snapshot access. After
the owner integrated `snap_6064cd14a4fcf0028614b1ce8fc9bd6d`, B's review
projection exposed the exact immutable snapshot and validation identifiers,
but B's `read_file` and pytest calls returned `workspace_stale`. Its recovery
`ensure_session({"refresh":true})` returned `internal_failure /
request_human_help`. A later accepted B's snapshot after the same stale-read
condition. The WorkGroup closed, but those decisions do not prove that the
reviewers actually inspected the immutable snapshots. Doctor remains
DEGRADED with six warnings and reconciliation recommended.

## SYN-039 CP-0528 post-fix exact-action diagnostic

The fresh CP-0528 diagnostic used the current bundled MCP, two independent
ready/isolated Codex sessions, disjoint epoch-1 claims, and one shared
WorkGroup. Exact REVIEW admission, owner acceptance, single-use grant
consumption, `finish_lane`, immutable snapshot publication, and integration
all succeeded. Evidence:
`docs/evidence/syn039-unattended-todo-cp0528-diagnostic-2026-08-24.md`.

The first incomplete lifecycle boundary was reviewer validation. B received a
structured `review_decision` projection with the exact grant, snapshot,
intent, and epoch, plus `accepted|rejected` and rejection-reason rules, but
did not submit `review_validation`; it chose an unprojected `git show` and
then hit fail-closed `workspace_stale`. The earlier omitted
`targetParticipant` was also agent-compliance evidence because the later
unchanged projection consumed the grant successfully. No unchanged concrete
Synesis action failed, so no production behavior changed for CP-0528.

WorkGroup `0d63aa77-fa6b-3dbd-a1a7-09e0d9ad0cda` remains ACTIVE. Doctor is
DEGRADED with six separately classified warnings. The known Git subprocess
stall and bootstrap migration failures remain independent.

## Historical next action

Audit the provider/manual contract for the `review_decision` state and run a
deterministic reviewer-decision fixture. If the existing decision contract is
already unambiguous, run a fresh ordinary unattended acceptance; if it is
ambiguous, make only the smallest agent-facing clarification. Do not change
review validation semantics, workspace readiness, ownership, cleanup,
Doctor, push, or create SYN-040 without a new concrete defect.

## SYN-039 CP-0527 post-fix projection diagnostic

The CP-0526 diagnostic proved a concrete publication-projection defect: an
inherited sibling source change authorized `finish_lane` for the wrong lane.
The existing claim-aware publication gate is implemented in the working tree
and its deterministic regression plus focused SYN-039 tests pass. Evidence:
`docs/evidence/syn039-unattended-todo-cp0526-projection-diagnostic-2026-08-24.md`.

The fresh CP-0527 diagnostic used the rebuilt current MCP, two ready/isolated
Codex sessions, disjoint claims, and one shared WorkGroup. Agent B executed
the exact projected REVIEW admission request, but Synesis returned fail-closed
`policy_denied / INTENT_NOT_FOUND`. The projection listed the target intent in
the same response. No grant, snapshot, validation, integration, or closure
state was created. Evidence:
`docs/evidence/syn039-unattended-todo-cp0527-projection-diagnostic-2026-08-24.md`.

## Work completed

Focused workspace and MCP tests pass. The bundled MCP was rebuilt with the
claim-aware publication fix. CP-0527 made no visible repository changes and
was stopped at the first exact projected-action failure. Doctor remains
DEGRADED with five fixture warnings; the known Git subprocess stall and
bootstrap migration failures remain independent.

## Current failures

WorkGroup `39e79e24-f414-3e69-9000-da49f7983e3f` remains ACTIVE. Participants
are `agt_d08a4cdc-de4e-34c9-ac1e-769ff533921f` and
`agt_7623f50b-5e1e-36ef-baa2-b5fb5225feb3`; their epoch-1 claims are
`todo.py` and `test_todo.py`. The exact projected request targeted intent
`7def130b-5a95-3ff2-8750-c1f3d8811c8a` and returned `INTENT_NOT_FOUND`.
The projection-to-admission liveness/state transition is not yet explained;
the final read-only state showed Agent A detached and Agent B active, but the
raw agent logs do not timestamp the request relative to that transition.

## Historical next action

Reproduce CP-0527 with durable per-call timing and participant/intent state
around the exact projected REVIEW admission. Trace whether the projection is
stale or the admission path resolves the wrong current state, then add only a
fail-closed regression and the smallest fix for the proven cause. Do not
modify snapshot, validation, integration, cleanup, Doctor, ownership, or
orchestration behavior speculatively; do not push or create SYN-040.

## SYN-039 CP-0524 clean-recovery identity fix diagnostic

## SYN-039 CP-0524 clean-recovery identity fix diagnostic

CP-0523 proved that a clean recovered worker already at the advanced control
HEAD could be rebound to a new session/participant, stranding its active
intent and review grant. Commit `dd9f0eb` extends the existing safe
session-preserving rebind predicate to that exact state. The deterministic
workspace regression passes and preserves the existing fail-closed behavior
for dirty or divergent worker state.

The fresh CP-0524 exact-action diagnostic used the rebuilt MCP, two
ready/isolated sessions, disjoint claims, and one shared WorkGroup. Exact
REVIEW admission, owner response, and grant issuance succeeded. Both agents
then stopped during continued polling before implementation/snapshot work;
no exact projected action failed. Evidence:
`docs/evidence/syn039-unattended-todo-cp0524-recovery-fix-diagnostic-2026-08-24.md`.

## Work completed

Focused SYN-039/MCP/workspace/coordination tests, Javadocs, validators, bundle
rebuild, bootstrap `go vet`, and `git diff --check` pass. Doctor remains
DEGRADED with six separately classified warnings. The known root Git
subprocess stall and bootstrap migration failures remain independent.

## Historical next action

Run one fresh ordinary unattended two-agent Todo acceptance with only the
agents' complementary coding prompts. Do not relay, manually transition,
repair, push, broaden SYN-039, or create SYN-040. Preserve the first exact
projected-action failure, missing usable action, closure/cleanup blocker, or
agent-engagement stop; do not change production code for another compliance
deviation without a new protocol defect.

# Current Task

## SYN-039 CP-0522 exact-action diagnostic

CP-0521's ordinary acceptance reached one shared WorkGroup, REVIEW admission,
grant consumption, immutable snapshot publication, integration, and one
structured ACCEPT. It stopped because Agent A ended before polling the
reciprocal grant targeted to its already-completed lane; Agent B remained in a
durable WAIT with no executable action. Evidence:
`docs/evidence/syn039-unattended-todo-cp0521-ordinary-2026-08-24.md`.

The follow-up CP-0522 exact-action diagnostic proved no new production defect.
B changed the projected REVIEW-admission intent ID and received fail-closed
`INTENT_NOT_FOUND`; the unchanged valid request was re-projected. A completed
its visible implementation and tests, but no lifecycle state advanced beyond
the admission failure. Evidence:
`docs/evidence/syn039-unattended-todo-cp0522-exact-rule-diagnostic-2026-08-24.md`.

## Work completed

Both fresh runs used the current bundled MCP and correct project pins. CP-0521
proved the already-implemented review, grant, snapshot, validation, and
integration path in an ordinary run. CP-0522 proved the fail-closed behavior
for an altered projected identifier. No production code changed in either
acceptance.

## Current failures

The current blocker is agent engagement/compliance, not a proven Synesis
protocol failure: ordinary A stopped before polling a peer-created grant, and
diagnostic B altered a projected identifier before calling the exact tool.
Both WorkGroups remain ACTIVE. Doctor remains DEGRADED with six separately
classified warnings; the root Git subprocess stall and bootstrap migration
failures remain independent verification issues.

## Historical next action

Run one fresh bounded exact-action diagnostic whose harness records the
projected action and actual arguments side by side and requires no manual
relay. Preserve the first unchanged projected action failure or missing usable
action; do not modify production code for another agent-compliance deviation,
push, broaden SYN-039, or create SYN-040.

## SYN-039 CP-0520 post-fix exact-rule diagnostic

## SYN-039 CP-0520 post-fix exact-rule diagnostic

The CP-0519 stale-dirty continuation slice is implemented in the existing
`AgentNextActionService` projection path and covered by a deterministic MCP
regression. A stale bound session with confirmed legitimate dirty work can now
discover an already-authorized review, publication, owner-response, or grant
wait continuation without replacing or mutating its worktree. Focused tests,
Javadocs, validators, bundle rebuild, and diff checks pass.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0520-stale-projection-diagnostic-2026-08-24.md`.

The fresh two-agent exact-rule diagnostic reached one shared WorkGroup, exact
REVIEW admission, both single-use grants, both immutable snapshots, both
integrations, and one structured ACCEPT. It stopped before closure because
Agent A ended after its exact WAIT polling cycle and did not poll again after
Agent B published the second snapshot. Malformed arguments attempted before
the later exact calls are agent-compliance evidence; no exact projected action
failed. WorkGroup `1fea1dc1-607e-3168-99df-8e896bf68295` remains ACTIVE and
Doctor remains DEGRADED with the documented six warnings.

## Work completed

The CP-0519 dirty-lane continuation defect is fixed narrowly in the existing
next-action projection path. The deterministic MCP regression passes without
replacing the dirty worktree. Focused MCP/workspace tests, Javadocs, deferred
and fixture validators, bundle rebuild, Go vet, and diff checks pass. The
fresh CP-0520 diagnostic reached both immutable snapshots and integrations and
one structured ACCEPT.

## Current failures

CP-0520 stopped before reciprocal validation and WorkGroup closure because
Agent A ended after exact WAIT polling before observing B's later snapshot.
Malformed arguments were fail-closed agent-compliance evidence; no exact
projected action failed. WorkGroup `1fea1dc1-607e-3168-99df-8e896bf68295`
remains ACTIVE. Doctor is DEGRADED with six warnings; the root Git stall,
bootstrap migration failures, and document-format findings remain separate.

## Historical next action

Run a fresh ordinary unattended two-agent Todo acceptance with no
protocol-conformance instruction beyond the repository contract. Observe
whether ordinary agents remain engaged after the exact WAIT projection and
peer snapshot publication. If a concrete projected action executes and fails,
fix only that proven defect; otherwise preserve the first closure or cleanup
blocker. Do not relay, manually transition, push, broaden SYN-039, or create
SYN-040.

# Current Task

## SYN-039 CP-0519 exact-rule diagnostic

The fresh bounded diagnostic used two independent GPT-5.6 Luna High Codex
sessions, the current bundled MCP, a fresh Git + Synesis project, disjoint
claims, and one shared WorkGroup. Both agents executed the concrete projected
REVIEW admission, owner acceptance, single-use grant consumption, producer
publication, snapshot integration, and structured ACCEPT without harness
intervention. Evidence:
`docs/evidence/syn039-unattended-todo-cp0519-exact-rule-diagnostic-2026-08-24.md`.

The first genuine protocol failure is post-ACCEPT stale-dirty continuation:
B's exact projected `ensure_session({})` after A advanced the control checkout
returned `internal_failure / request_human_help`. B still has legitimate
uncommitted `test_todo.py` work, so the fail-closed dirty-worktree protection
is correct but no usable continuation is projected. WorkGroup
`663cee3b-cdf3-3bf8-91cb-7e8ddcc575bf` remains ACTIVE; A's snapshot
`snap_48423ea02f57776f0064595b971197ab` integrated at control commit
`2563b0c`; B has no snapshot; reciprocal request
`a705dde9-eab2-40ec-bd4a-b30fb45a9122` remains pending.

## Historical next action

Implement and deterministically test only the post-ACCEPT dirty-lane
continuation projection. Preserve dirty work and all existing claim, grant,
epoch, snapshot, ownership, and fail-closed checks. Do not broaden cleanup,
Doctor, orchestration, or later closure behavior. Then rebuild the bundle and
rerun the exact diagnostic. Do not relay, manually transition, push, or create
SYN-040.

## SYN-039 CP-0517 dirty-review continuation and exact-action diagnostic

The stale-reviewer continuation slice is implemented. A bound reviewer whose
control checkout advanced but whose assigned worktree contains confirmed
legitimate changes can now receive the existing grant-backed durable review
projection and execute structured validation without reopening, replacing, or
mutating that worktree. Clean stale worktrees retain the existing
`ensure_session` recovery path, and unknown dirtiness remains fail-closed.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0517-dirty-review-fix-diagnostic-2026-08-24.md`.

## Work completed

Deterministic MCP coverage proves dirty reviewer ACCEPT, preserved worktree,
same binding, existing review authority, and continued clean stale recovery.
Focused MCP/workspace tests, Javadocs, validators, bundle rebuild, and diff
checks pass. A fresh exact-action run reached ready/isolated sessions,
disjoint claims, one shared WorkGroup, and exact REVIEW admission request
execution. Agent B's request remained pending after Agent A's last ordinary
`IMPLEMENT` poll; no projected owner response was ignored and no later
lifecycle transition was reached.

## Current failures

The fresh diagnostic stopped as agent-engagement/compliance evidence: Agent A's
Codex turn ended after a pre-request ordinary `IMPLEMENT` projection instead of
polling again after Agent B asynchronously created the REVIEW request. The
WorkGroup is ACTIVE with no grant, snapshot, validation, integration, or
closure. Doctor remains DEGRADED with six warnings. The root Git subprocess
stall, bootstrap migration test failures, and pre-existing documentation
trailing-whitespace failures remain independent verification issues.

## Historical next action

Run a fresh completely ordinary two-agent Todo acceptance with no lifecycle
instruction beyond the repository contract, and capture whether both normal
sessions remain engaged after a peer creates the pending REVIEW request. If a
concrete projected action is ignored, preserve it as agent-compliance evidence;
if it executes and fails, fix only that proven protocol defect. Do not relay,
manually transition, broaden SYN-039, push, or create SYN-040.

## SYN-039 CP-0516 producer-first exact-action diagnostic

The fresh producer-first diagnostic used the current bundled MCP, two
independent GPT-5.6 Luna agents, disjoint claim-bearing sessions, and one
shared WorkGroup. It reached exact REVIEW admission, owner acceptance,
single-use grant consumption, producer `snapshot_publication_required`, exact
`finish_lane`, immutable snapshot publication, and integration. Evidence:
`docs/evidence/syn039-unattended-todo-cp0516-producer-first-diagnostic-2026-08-24.md`.

## Work completed

All observed concrete lifecycle projections were executed unchanged. A's
snapshot `snap_171a6f766e26454cf60e6cebc3106f63` integrated into the control
checkout. The first later blocker is now reproduced and source-traced: B's
control checkout became stale while B's assigned worktree still contained its
legitimate uncommitted `test_todo.py` work. Synesis correctly refused to
discard dirty work, but the projected `ensure_session({})` recovery returned
`internal_failure / request_human_help` rather than a safe review/continuation
path. No production code changed in CP-0516.

Focused MCP/workspace tests, Javadocs, validators, `go vet`, and diff checks
pass. Bootstrap migration failures and the known Git subprocess stall remain
separate verification issues. Fixture Doctor remains DEGRADED with six
warnings.

## Current failures

The WorkGroup remains ACTIVE with B's claim retained, no review decision, no
B snapshot, and no closure. The stale-dirty guard itself must remain
fail-closed; the missing behavior is safe grant-authorized review access and
continuation after sibling integration without losing or silently replacing
the reviewer's dirty work. This is a concrete SYN-039 product blocker, not an
agent-compliance deviation.

## Historical next action

Implement the smallest deterministic stale-reviewer continuation slice: allow
grant-authorized durable review projection/validation after sibling control
integration while preserving the reviewer's dirty work and keeping any unsafe
mutation/replacement fail-closed. Add focused fencing/recovery coverage, then
rerun the exact producer-first diagnostic before attempting ordinary acceptance.
Do not broaden cleanup, ownership, Doctor, or orchestration, push, or create
SYN-040.

## SYN-039 CP-0514 ordinary acceptance

CP-0513 exposed a concrete agent-facing contract ambiguity: ordinary agents
passed only `goal`, `acceptance`, and descriptive `likelyScopes` to
`ensure_session`, so no intent or claims were announced and no WorkGroup could
form. The MCP catalog, generated `AGENTS.md`, provider manual, and provider
documentation now state that `ensure_session.task.claims` is the existing
intent/ownership announcement, while `likelyScopes` is descriptive only. The
change is contract/guidance-only; lifecycle authorization and fail-closed
ownership behavior are unchanged. Deterministic catalog, generated guidance,
and provider-manual tests pass. Evidence of the pre-fix run is retained in the
CP-0513 harness; the contract slice is in commit `5c1496c`.

The fresh post-fix ordinary run used two independent GPT-5.6 Luna agents and
the current bundled MCP. Both independently sent disjoint claim-bearing
`ensure_session` requests, reached one WorkGroup, executed REVIEW admission,
and consumed the targeted single-use grant. A then stopped while repeatedly
polling the grant-pending `WAIT -> get_next_action` before observing B's later
consumption. B reached `SNAPSHOT_PENDING -> WAIT -> get_next_action` and
stopped. No snapshot, validation, integration, or closure was reached. B's
first omitted `targetParticipant` was rejected fail-closed and corrected; no
exact projected action failed. Evidence:
`docs/evidence/syn039-unattended-todo-cp0514-ordinary-claims-contract-2026-08-24.md`.

## Work completed

The claim-announcement contract is clarified in commit `5c1496c`: the MCP
catalog, generated `AGENTS.md`, provider manual, and provider documentation
now identify `ensure_session.task.claims` as the existing intent/ownership
announcement and `likelyScopes` as descriptive only. Deterministic catalog,
generated-guidance, provider-manual, focused MCP/workspace tests, bundle
rebuild, deferred validation, and `git diff --check` pass.

## Current failures

The post-fix ordinary run reached one shared WorkGroup, REVIEW admission,
owner acceptance, and single-use grant consumption. It stopped at agent
continuation: the producer stopped before polling after grant consumption and
the reviewer stopped at `SNAPSHOT_PENDING -> get_next_action`. No snapshot,
validation, integration, or closure was reached. No exact projected action
failed; this remains agent-compliance evidence. Doctor remains DEGRADED with
six warnings, and the known Git subprocess stall and bootstrap migration
failures remain separate.

## Historical next action

Run a fresh bounded exact-projection diagnostic with both agents explicitly
required to execute every concrete projected action unchanged and remain
engaged through `WAIT -> get_next_action` while the WorkGroup is active. Do not
relay messages or trigger transitions. Preserve the first exact projected
failure or first missing usable action; if the diagnostic completes, run the
second completely ordinary acceptance. Do not change lifecycle behavior,
push, or create SYN-040 for CP-0514 agent-compliance evidence.

## SYN-039 CP-0512 exact-action diagnostic

CP-0512 used a fresh project and the current bundled MCP with two independent
GPT-5.6 Luna sessions, disjoint epoch-1 claims, and one shared WorkGroup.
Both reciprocal REVIEW admissions, exact owner responses, single-use grant
consumption, both immutable snapshot publications, both integrations, and
one exact structured ACCEPT completed. The first remaining stop was Agent A
ending after repeated `WAIT -> get_next_action` projections before polling
again after the sibling's second snapshot publication. No exact projected
action failed and no new production defect is proven. Evidence:
`docs/evidence/syn039-unattended-todo-cp0512-exact-action-diagnostic-2026-08-24.md`.

CP-0511's ordinary acceptance is separately recorded: Agent A used the
optional `get_next_action({integrationCheck:{...}})` overload after coding and
stopped before the durable review flow; B reached a valid grant but stopped
at WAIT. This is agent-facing contract/compliance evidence, not a false
accepted-snapshot integration result. Evidence:
`docs/evidence/syn039-unattended-todo-cp0511-ordinary-2026-08-24.md`.

CP-0509 proved a production projection defect: review validation was exposed
as executable `respond_coordination` while its projected payload omitted the
required reviewer-selected `result`, and executing it failed closed with
`COORDINATION_RESPONSE_FIELD_REQUIRED:result`. The narrow fix introduces the
explicit `review_decision` state while retaining the existing response tool,
strict validation, and explicit ACCEPT/REJECT choice.

## Work completed

`McpSyn039SliceTest` and `AgentWorkflowReducerTest` pass with the corrected
contract. The rebuilt bundled MCP exposes the fix. CP-0510 used a fresh Git +
Synesis project, two independent GPT-5.6 Luna agents, ten tools, one shared
WorkGroup, and disjoint epoch-1 claims. REVIEW admission, grant consumption,
snapshot publication, and integration reached the corrected review projection.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0510-review-decision-postfix-2026-08-24.md`.

CP-0512 also proves reciprocal exact-action publication/integration and one
structured ACCEPT; the durable evidence is recorded in the CP-0512 evidence
file above. No production code changed for that diagnostic.

## Current failures

The exact-action diagnostic now proves the reciprocal path through two
published/integrated snapshots and one structured ACCEPT, but it still does
not prove reciprocal validation or clean WorkGroup closure. Agent A stopped
while Synesis was projecting the safe continuation `WAIT -> get_next_action`
before the second snapshot became visible. The ordinary CP-0511 run also
shows that the optional `integrationCheck` overload can look terminal to an
ordinary agent even though no lifecycle action was projected. These are the
next agent-facing continuation/contract questions; no lifecycle redesign is
justified. Doctor remains `DEGRADED` with six unrelated warnings.

## Historical next action

Inspect and classify the two remaining agent-facing stops from CP-0511 and
CP-0512: the optional `integrationCheck` overload after ordinary coding and
premature termination during projected `WAIT -> get_next_action`. Verify the
catalog, generated `AGENTS.md`, and provider manual contract before making
any change. If the contract is already unambiguous, preserve these as
agent-compliance evidence; otherwise make only the smallest agent-facing
clarification, add deterministic contract coverage, rebuild the bundle, and
rerun a fresh ordinary acceptance. Do not change lifecycle semantics, push,
or create SYN-040.

## SYN-039 CP-0508 review-decision postfix diagnostic

The CP-0507 review-result projection fix is committed as `ca9a2f3` and
verified in a fresh two-agent diagnostic. Both GPT-5.6 Luna agents used the
rebuilt current bundle, exactly ten MCP tools, the same project, distinct
isolated sessions, disjoint epoch-1 claims, and one WorkGroup
`e0ef5af5-844c-3f77-b4ad-29767b4b13c3`.

Agent A implemented `todo.py`, passed three tests, followed the exact
publication projection, and integrated snapshot
`snap_806145a00668f970adaaf4af734a9d81` (snapshot commit
`24702bb2b8f1287e14f05da7f88f2c0b925e2b7b`). Agent B consumed the targeted
single-use grant, recovered after the expected workspace-stale response,
passed four tests, and received the corrected review-validation projection:
the exact grant/snapshot/intent/epoch context plus explicit
`accepted`/`rejected` choices, with no fabricated `recommendedTool` or
arguments. B selected `accepted`; Synesis returned structured `ACCEPTED`.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0508-review-decision-postfix-2026-08-24.md`.

## Work completed

The CP-0507 review-result projection fix is committed as `ca9a2f3`. The
reviewer now receives exact review context and explicit structured
`accepted`/`rejected` choices without a fabricated executable result. The
workflow reducer suppresses `respond_coordination` recommendation until a
valid result is supplied. Deterministic tests cover ACCEPT, REJECT routing,
wrong participant, stale epoch, wrong snapshot, invalid result, idempotent
replay, and conflicting replay.

Agent A's publication and integration, Agent B's grant consumption and
structured ACCEPT, focused SYN-039/workspace/coordination tests, Javadocs,
bundle rebuild, fixture/deferred validators, `go vet`, Doctor, and
`git diff --check` pass.

## Current failures

The first deviation was B omitting the projected `targetParticipant` while
consuming a grant; Synesis failed closed with
`COORDINATION_FIELD_REQUIRED:targetParticipant`, and B then corrected the
request. Later A stopped before polling again after B accepted the reciprocal
REVIEW request, leaving grant `f879b4ff-047c-3dc8-8b70-2568a5d4a4a3`
available for A. The WorkGroup remains `ACTIVE`; B's second snapshot,
reciprocal validation, cleanup, and closure are unproven. This is
agent-compliance evidence, not a new production defect. No ordinary second
acceptance was started because the diagnostic did not reach end-to-end
completion.

Focused SYN-039/workspace/coordination tests, Javadocs, bundle rebuild,
fixture/deferred validators, `go vet`, Doctor, and `git diff --check` pass.
Doctor remains `DEGRADED` with six unrelated warnings; the known root Git
subprocess stall and bootstrap migration failures remain separate.

## Historical next action

Run a fresh bounded two-agent diagnostic from the rebuilt bundle, preserving
the exact-projection rule, and keep both agents alive after reciprocal REVIEW
acceptance until the targeted grant is consumed, the second lane publishes,
validation/integration complete, and the WorkGroup either closes or exposes a
new exact projected failure. Do not modify production code for CP-0508 agent
deviations, push, or create SYN-040.

## Identity

- Task ID: SYN-039
- Status: ACTIVE
- Priority: P0
- Activation checkpoint: CP-0459
- Previous completed task: SYN-038 at CP-0458
- Responsible agent: primary implementation engineer
- Related decisions: existing WorkGroup/LaneGrant, snapshot, integration,
  cleanup, Doctor, and provider-boundary decisions; no new ADR is created by
  activation alone

## CP-0507 exact-projection diagnostic

The fresh project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0507-001` used the
rebuilt current bundled MCP and two independent GPT-5.6 Luna sessions. Both
passed the ten-tool, same-project, distinct `ready / isolated` preflight and
converged on WorkGroup `9b605c00-d45c-34e6-a9dd-f0ad4d31be3b` with disjoint
epoch-1 claims.

The CP-0506 publication guard worked: Agent A implemented `todo.py`, executed
the exact projected `finish_lane`, published snapshot
`snap_760b1bf37251e2c2f64e92e73ece42a9`, and integrated commit
`804fe64f18b3f261d6f25750aef9f64ab4333b33` into control commit `c6af41d`.
No false empty-lane `finish_lane` projection occurred.

The first later blocker is a concrete validation projection defect. After
Agent B consumed REVIEW grant `4c3eae33-35d4-3015-bdcf-bf84895f6aad`,
`get_next_action` projected exact `respond_coordination` / `review_validation`
arguments with `result: "accepted|rejected"`. Agent B executed those exact
arguments; the server correctly rejected the invalid enum with
`COORDINATION_RESPONSE_INVALID_RESULT`. No validation decision or WorkGroup
closure was recorded. Evidence:
`docs/evidence/syn039-unattended-todo-cp0507-review-result-projection-2026-08-24.md`.

## Work completed

The CP-0506 publication projection now checks the snapshot service's
read-only changed-path and artifact-policy precondition before recommending
`finish_lane`. Deterministic MCP coverage proves an empty lane does not
receive an unexecutable publication action and a changed lane still publishes
an immutable snapshot visible to the reviewer. Evidence:
`docs/evidence/syn039-unattended-todo-cp0506-premature-finish-projection-2026-08-24.md`.

Focused MCP and workspace tests pass, the current platform bundle was rebuilt,
and the CP-0507 exact diagnostic reached publication and integration. No
review-validation production change has been made for the new blocker.

## Current failures

The review-validation projection exposes the documentation placeholder
`accepted|rejected` as if it were an executable decision. The exact projected
tool fails closed with `policy_denied` / `COORDINATION_RESPONSE_INVALID_RESULT`.
The WorkGroup remains ACTIVE with Agent A completed/integrated and Agent B's
review unresolved. Doctor remains DEGRADED with six warnings. The known Git
subprocess stall, bootstrap migration failures, and Doctor warnings remain
separately classified unless the next focused reproduction connects them to
validation projection.

## Historical next action

Trace the review-validation projection from `reviewActions` through
`AgentWorkflowReducer` and the MCP response contract; make the smallest fix
that exposes a valid structured ACCEPT/REJECT decision contract without
auto-selecting a result, then add deterministic valid-ACCEPT, valid-REJECT,
invalid, stale, and replay coverage. Do not broaden cleanup, ownership,
orchestration, Doctor, or create SYN-040.

## CP-0505 exact-projection diagnostic

The fresh project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0505-001` used the
current bundled MCP and two independent GPT-5.6 Luna sessions. Both sessions
used the same project, exactly ten tools, and distinct `ready / isolated`
bindings. They converged on WorkGroup
`35aa138a-a6bf-389a-a4b5-e7bbe66024ec` with disjoint `todo.py` and
`test_todo.py` claims at epoch 1.

The diagnostic reached exact REVIEW admission, idempotent replay of the same
request projection, owner `respond_coordination` acceptance, and consumption
of single-use grant
`a92067d7-7d0f-365b-b514-7b3efb314428`. Every concrete projected lifecycle
action succeeded. Both agents then stopped while `get_next_action({})` was
the exact executable `WAIT` continuation: the reviewer had not yet seen the
post-consumption snapshot-publication projection, and no snapshot, validation,
integration, or closure was reached. This is agent-compliance evidence, not a
new production defect. Evidence:
`docs/evidence/syn039-unattended-todo-cp0505-exact-rule-diagnostic-2026-08-24.md`.

No production code changed for CP-0505. The next action is a fresh bounded
diagnostic that keeps both sessions alive through grant consumption and peer
snapshot publication, capturing the first later projected action or failure.
Do not push or create SYN-040.

## Work completed

CP-0505 recorded the exact projection/action trace through REVIEW admission,
idempotent request replay, owner acceptance, and single-use grant consumption.
Focused MCP/workspace tests, Javadocs, validators, `go vet`, and
`git diff --check` pass. No production code changed because no projected
lifecycle action failed.

## Current failures

Both agents stopped after executing the exact `WAIT` →
`get_next_action({})` continuation, before the producer polled after grant
consumption. The WorkGroup remained ACTIVE with no snapshot, validation,
integration, or closure. This is agent-compliance evidence. Fixture Doctor is
DEGRADED with six warnings; the known Git subprocess stall, bootstrap
migration failures, and format findings remain separately classified.

## Historical next action

Run one fresh bounded diagnostic with both agents kept alive through grant
consumption and peer snapshot publication; capture the first later projection
and immediately following action. Do not change lifecycle production code
unless an exact projected action fails or progress is required with no usable
projection.

## CP-0503 post-fix producer-polling diagnostic

The CP-0502 reproduction proved a concrete owner-side projection gap: an
active owner with an issued, current-epoch, unconsumed REVIEW grant targeted
to a peer received ordinary `IMPLEMENT` with no executable continuation. The
narrow fix in `AgentNextActionService` now projects `WAIT` with the exact
grant, WorkGroup, target participant, intent, epoch, and
`review_grant_consumption` context. It grants no mutation authority.

The fresh post-fix project
`C:\Users\Liparakis\Desktop\SynesisAcceptance\syn039-cp0503-001` used the
rebuilt current bundle and two independent GPT-5.6 Luna sessions. Both passed
ten-tool `ready / isolated` preflight, held disjoint `todo.py` /
`test_todo.py` claims, and converged on WorkGroup
`49082d5e-ecc5-3503-82fb-3d62f37597c8`.

The owner accepted REVIEW request
`10fe11a8-c4bc-46ae-a11f-cd70489741d2`, the peer consumed grant
`c9cb80ae-679d-3290-902c-c55647723aae`, and the owner remained active through
the exact `snapshot_publication_required` → `finish_lane` projection. Snapshot
`snap_5733de0976ad177cc349e9fa2fbdebcb` was published and integrated.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0503-postfix-diagnostic-2026-08-24.md`.

## Work completed

The CP-0502 owner-side waiting projection is implemented with deterministic
MCP regression coverage. Focused MCP and workspace tests pass, and the
platform bundle was rebuilt. Existing grant, participant, intent, epoch,
snapshot, and WorkGroup checks remain fail-closed.

## Current failures

CP-0503 stopped at the reviewer after it consumed the grant and executed the
exact `WAIT` → `get_next_action` projection twice. It ended before polling
after the producer published the snapshot, so validation, the second lane's
snapshot, cleanup, and WorkGroup closure remain unproven. No exact projected
tool failed; this is agent-compliance evidence, not a new production defect.
The known Git subprocess stall, bootstrap migration failures, and six Doctor
warnings remain separately classified.

The focused Javadocs, deferred/fixture validators, `go vet`, and
`git diff --check` pass. The root check is not green: `:link:formatCheck`
still reports pre-existing trailing whitespace in CP-0488, CP-0489, and the
CP-0494 evidence file, and the run reproduced the Git subprocess stall in
`ProviderApplicationServiceTest` / `ProcessCommandRunner` before it was
bounded and stopped.

## Historical next action

Run one fresh bounded diagnostic with both agents remaining alive after every
`WAIT` projection and after peer-side publication; capture the first
post-publication reviewer projection and continue only through exact projected
actions. If an agent stops again without a projected action failure, preserve
agent-compliance evidence and do not broaden production code. Do not push or
create SYN-040.

## CP-0501 producer-polling diagnostic

The fresh project
`1a67c646-9725-48ba-b6ec-63618ef2cd89` used the current bundled MCP and two
independent GPT-5.6 Luna sessions. Both reached ten tools and distinct
`ready / isolated` sessions, held disjoint `todo.py` / `test_todo.py` claims,
and converged on WorkGroup
`1f8bc962-fbb5-376b-9f72-1e0b4135a495`.

The diagnostic executed exact REVIEW admission, owner acceptance, and
single-use grant consumption successfully. A then stopped after a normal
`IMPLEMENT` projection with no executable action while B's grant was still
unconsumed. B later consumed grant
`e6b09aa2-0cf8-35de-b80c-1e4180ccb6a7` and received the exact `WAIT` →
`get_next_action` projection, but A was no longer polling to receive
`finish_lane`. No exact projected action failed; no production code changed.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0501-producer-polling-diagnostic-2026-08-24.md`.

The source contract is internally consistent: the owner cannot publish until
the targeted REVIEW grant is consumed, and the reviewer had the next
authorized action. The first stop is therefore agent-compliance evidence, not
a new backend defect. Exact next action: run one fresh bounded diagnostic with
both agents remaining alive through peer-side state changes and collecting the
post-consumption owner projection. Do not broaden lifecycle code or create
SYN-040.

## CP-0500 REVIEW admission idempotency diagnostic

The fresh post-fix diagnostic used project
`5c4700bd-9765-4886-9aea-261bfb65be4a`, the rebuilt current bundled MCP, and
two independent GPT-5.6 Luna sessions. Both exposed ten tools, reached
`ready / isolated`, held disjoint `todo.py` / `test_todo.py` claims, and
converged on WorkGroup `4c0005dc-4358-32b5-922a-3cf554cfb54d`.

The narrow REVIEW admission idempotency fix worked. Repeated execution of
the same projected `request_coordination(work_group_join)` returned the same
request ID `90ab5c3b-e663-4230-94df-5f0077015508`; it did not create duplicate
requests or grants. The run reached exact owner acceptance, grant consumption,
producer `finish_lane`, immutable snapshot publication, integration, and
structured ACCEPT. Evidence:
`docs/evidence/syn039-unattended-todo-cp0500-review-admission-idempotency-2026-08-24.md`.

The first deviation was Agent A ignoring two repeated concrete review-admission
projections after its successful request, then stopping. Agent B later
accepted the request and received grant
`b1b5b243-b6a5-308d-af57-bce3d3fc63d4`, but A was no longer polling to consume
it. The WorkGroup remained ACTIVE with B's active test intent and no B
snapshot. This is agent-compliance evidence; no further production change is
authorized from this run.

## CP-0499 post-fix bounded diagnostic

The fresh CP-0499 diagnostic used project
`ac5d791a-9f5f-419c-8252-5261c090931b`, the current bundled MCP, and two
independent GPT-5.6 Luna sessions. Both agents preflighted exactly ten tools,
reached `ready / isolated`, held disjoint `todo.py` / `test_todo.py` claims,
and converged on WorkGroup
`3621a4f6-6b2b-3379-9174-9cdcb45b8186`.

The post-fix diagnostic reached exact REVIEW admission, owner acceptance,
grant consumption, projected producer `finish_lane`, immutable snapshot
publication, integration, and structured ACCEPT validation. No exact
projected action failed. Evidence:
`docs/evidence/syn039-unattended-todo-cp0499-postfix-diagnostic-2026-08-24.md`.

The first remaining protocol blocker is after ACCEPT: Agent B's own active
intent remains `ANNOUNCED`, the WorkGroup remains `ACTIVE`, and its final
`get_next_action` returns ordinary `IMPLEMENT` with no executable lifecycle
action, so the reviewer lane cannot publish/finish and the WorkGroup cannot
close. The same run also produced three duplicate REVIEW requests and grants
because the same successful admission projection remained actionable; this is
related idempotency/projection evidence, not a grant replay bypass.

The exact next action is to reproduce the post-ACCEPT active-reviewer
no-action state and trace the existing completion/publication projection, with
duplicate REVIEW admission handled in the same narrow trace. Do not broaden
into cleanup, detached-agent retention, ownership redesign, or a new
orchestrator. Do not push or create SYN-040.

## CP-0498 completed-review continuity diagnostic

The fresh CP-0498 diagnostic used project
`ff3603f4-67bd-4972-99d0-c075b7c10c5f`, the current bundled MCP, and two
independent GPT-5.6 Luna sessions with distinct ready/isolated bindings and
disjoint `todo.py` / `test_todo.py` claims. WorkGroup
`1d24011b-99a6-37bd-b56b-ca09eab8edef` reached exact REVIEW admission, grant
consumption, projected `finish_lane`, snapshot publication, integration, stale
reviewer recovery, and exact ACCEPT decisions. The status correction now
reports `workGroupStatus=ACTIVE` when the durable group remains active.
Evidence:
`docs/evidence/syn039-unattended-todo-cp0498-completed-review-continuity-diagnostic-2026-08-24.md`.

The first concrete post-fix defect is completed-participant continuity. B's
lane became `COMPLETED` after its snapshot integrated while A's sibling
implementation lane remained `ACTIVE`. The early terminal return prevented B
from discovering the existing same-WorkGroup REVIEW admission projection, so
A's lane could not receive a grant and publish its snapshot. This is a
protocol defect, not agent non-compliance.

## CP-0497 reviewer-continuity diagnostic

The fresh current-bundle diagnostic used project
`4d0fa215-d2e4-4a72-9a1c-0e7b858a3b1e`, two independent GPT-5.6 Luna sessions,
the same pinned MCP executable, ten tools, distinct ready/isolated bindings,
and disjoint `todo.py` / `test_todo.py` claims. The reviewer-continuity fix
preserved Agent A's participant/session identity across the control checkout
advance and exact `ensure_session` recovery.

The run reached one WorkGroup
`7c5ac4f7-c538-39c2-8e5d-ed9fadbdc771`, exact REVIEW admission, both exact
owner acceptances, grant consumption, exact projected producer
`finish_lane`, immutable snapshot `snap_3eb0df616deb0c00e78540f63877b1c2`,
integration, and two exact projected `review_validation` ACCEPT decisions.
No exact projected action failed. Evidence:
`docs/evidence/syn039-unattended-todo-cp0497-review-continuity-diagnostic-2026-08-24.md`.

The first concrete post-validation defect is inconsistent terminal reporting:
the accepted `respond_coordination` response returned
`workGroupStatus=COMPLETED`, while final durable status remained `ACTIVE` with
Agent A's separate active implementation intent and two duplicate REVIEW
grants. The diagnostic was therefore not a clean product acceptance, and the
second ordinary run was not started. This is a protocol/reporting blocker,
not agent non-compliance; the exact projected actions were followed.

## CP-0494 post-fix review-projection diagnostic

The fresh CP-0494 fixture used the rebuilt current bundle, two independent
GPT-5.6 Luna sessions, the same project root, and distinct ready/isolated
sessions. Project `03dad00b-fbb4-4500-aa9a-22f91c7d7494` reached one shared
WorkGroup `471a4f65-5210-327f-ad5a-ba2897d022ab` with producer claim `todo.py`
at epoch 1. The MCP route was the current bundled executable, protocol
`2025-06-18`, server `0.1.0-SNAPSHOT`, and exactly ten tools.

CP-0493 proved that the reviewer validation projection was not executable:
the exact projected payload included `workGroupId` and
`targetParticipant`, while strict `respond_coordination` correctly rejected
them with `COORDINATION_RESPONSE_FIELD_NOT_ALLOWED:workGroupId`. The smallest
fix now keeps those identifiers in surrounding review context and emits only
the accepted validation fields in the executable payload. Deterministic MCP
coverage executes the projected ACCEPT branch successfully. The same slice
also fixes the CP-0490 Python `__pycache__` artifact-policy failure.

In the post-fix diagnostic, Agent B consumed grant
`2d616273-a235-3cec-b2fd-054a855fb8c6`; Agent A executed the exact projected
`finish_lane` action, publishing snapshot
`snap_3e7c0ee281c5190f43bcd2102a5853f7` and integrating commit
`67542ea641379d5eaef7a6b2b73d97541efd161d` into clean control commit `45fc60a`.
Agent B then received the now-executable exact `review_validation` projection
but chose an unprojected `read_file("todo.py")`, which produced
`workspace_stale`. No validation decision or WorkGroup closure was reached;
this is agent-compliance evidence, not a new production defect.

Evidence: `docs/evidence/syn039-unattended-todo-cp0494-review-projection-2026-08-24.md`.

## CP-0489 role-order diagnostic

The fresh CP-0489 fixture used the current bundled MCP and two independent
GPT-5.6 Luna sessions on project `bceaf899-f1a3-4a65-8538-4f303a072e5d`.
Both sessions reached `ready / isolated` with disjoint exact claims and one
shared WorkGroup `2176bfbd-6199-303f-805c-a91c382b92ff`.

The diagnostic reached exact REVIEW admission, exact owner acceptance for
both requests, grants `215ba3af-5cf9-352a-ac5e-5685438a7d12` and
`d831734a-d597-3457-b817-ae5b3f7e6e70`, and exact consumption of the first
grant. The reviewer correctly received `SNAPSHOT_PENDING` → `wait`. The
producer's last `get_next_action` occurred before that consumption and
returned ordinary `IMPLEMENT` with no executable action; it did not poll
again after the later reviewer action. No projected producer action failed,
so no production defect is proven. Evidence:
`docs/evidence/syn039-unattended-todo-cp0489-role-order-diagnostic-2026-08-24.md`.

Focused SYN-039 tests, affected Javadocs, validators, `go vet`, and
`git diff --check` pass. Bootstrap Go tests retain the three known migration
failures. Full `check` now passes its format/compile/Javadoc/static-analysis
stages and reproduces the known `McpServerTest.setUp` Git subprocess stall at
`ProcessCommandRunner.execute:81`; exact thread evidence is in the evidence
file. Fixture Doctor remains DEGRADED with six warnings, separately
classified.

Exact next action: run a fresh bounded diagnostic that keeps both agents
returning to `get_next_action` after a wait or peer-side state change, then
capture producer snapshot publication and reviewer validation without relay
or manual lifecycle transitions. Do not modify production code unless an
exact projected action fails. Do not push or create SYN-040.

## CP-0487 role-order diagnostic

The fresh CP-0487 fixture reached one shared WorkGroup, exact REVIEW admission,
two exact owner `respond_coordination` acceptances, and exact single-use grant
consumption. Agent B established the WorkGroup first, so its test intent was
the producer/owner and Agent A's implementation intent became the reviewer.
After A consumed the grant, A correctly received `SNAPSHOT_PENDING` → `wait`;
the producer had not yet been queried after grant consumption. No projected
producer publication action failed or was missing when requested. This is
role-order/agent-compliance evidence, not a new production defect.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0487-role-order-diagnostic-2026-08-24.md`.

## CP-0486 exact-rule diagnostic

The agent-facing contract clarification is now present in the generated
`AGENTS.md` and provider manual. A fresh external-harness fixture used the
current bundled MCP, the same initialized project, and two distinct
`ready / isolated` sessions with disjoint `todo.py` / `test_todo.py` claims.
The sessions converged on WorkGroup
`9527b8ec-0971-3f33-995c-ac0833d506c7`.

Agent A received and executed the exact projected
`request_coordination(work_group_join)` action, then implemented `todo.py`
without calling unprojected `finish_lane`. Agent B instead supplied an
unprojected `integrationCheck` request while its own isolated worktree lacked
A's unintegrated implementation. Synesis correctly returned
`integration_conflict` / `TESTS_FAILED` and `request_human_help`. No exact
projected lifecycle action failed, and no grant, snapshot, validation,
integration, or closure state was reached. This is agent-compliance evidence,
not a new production lifecycle defect.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0486-exact-rule-diagnostic-2026-08-24.md`.

## CP-0485 clean-harness exact-rule diagnostic

The clean-harness rerun used a fresh Git + Synesis project and the rebuilt
bundled MCP (`0.1.0-SNAPSHOT`, SHA-256
`27D6BE820B82A8C8CED3966DF9DD2A0AEE1FC897659F46462D8B7166D46CF7E3`). Both
GPT-5.6 Luna sessions reached `ready / isolated` on the same project with
distinct identities, disjoint `todo.py` / `test_todo.py` claims, and one
shared WorkGroup. The harness was outside the project and the control
checkout was clean before launch.

WorkGroup `a5b6fdc4-51cb-3398-be5a-76126258984f` was reached. The reviewer
received the exact `REVIEW_ADMISSION_REQUIRED` projection and executed the
projected `request_coordination(work_group_join)` action. The owner executed
both exact projected `respond_coordination` acceptance actions, producing
requests `4a2d5e88-22b4-40d6-95b3-2053472487b0` and
`e4617626-b3b8-4772-99d1-57b3b7ffea03`, and grants
`ce12bf95-e493-38c7-a75b-fc78f5b03782` and
`7b4f4964-8631-3b80-bb99-0552b05c67d7` targeted to the reviewer at epoch 1.

The owner then violated the diagnostic rule by selecting unprojected
`finish_lane` while `get_next_action` still returned ordinary `IMPLEMENT`.
That is agent-compliance evidence, not a production defect. The reviewer later
received the concrete recovery projection `workspace_stale` →
`ensure_session`, executed `ensure_session({})` twice exactly, and both calls
returned `internal_failure` / `request_human_help`. No validation decision or
WorkGroup closure was reached. The integrated control checkout was clean at
`166228f5a6b17208175231984f7cbce9e4090dfc`, but the WorkGroup remained ACTIVE
with the two pending REVIEW grants.

Evidence:
`docs/evidence/syn039-unattended-todo-cp0485-exact-rule-diagnostic-2026-08-24.md`.

## Work completed

The reviewer-first snapshot-admission projection fix is committed as
`5fe613f`. The CP-0490 Python bytecode-cache artifact policy fix and CP-0493
reviewer-validation MCP schema fix are committed in `706b743`. The strict
review response remains fail-closed, while its executable projection now
matches the accepted `respond_coordination` payload contract. The CP-0496
reviewer recovery-continuity fix is committed in `d578223`; a control-only
checkout advance now preserves reviewer session identity while allocating a
fresh isolated worktree. Deterministic workspace/MCP regressions, focused
Javadocs, bundle rebuild, validators, and diff checks pass. CP-0497 proves
exact admission, grant consumption, exact producer publication, immutable
snapshot creation, integration, reviewer recovery, and exact ACCEPT decisions
on the rebuilt bundle. CP-0498 completed-participant continuity is committed
in `ca6d644`; its deterministic review-only projection and authority tests
pass. CP-0499 then proved the next active-reviewer no-action blocker after
ACCEPT. The bounded evidence is recorded in
`docs/evidence/syn039-unattended-todo-cp0499-postfix-diagnostic-2026-08-24.md`.
The CP-0500 coordination idempotency slice is covered by
`WorkIntentServiceTest`; focused coordination, workspace, MCP, Javadoc,
validator, Doctor, and diff checks pass. The known Git subprocess stall,
bootstrap migration failures, and Doctor warnings remain separate.

## Current failures

CP-0500 verified that repeated REVIEW admission execution is idempotent, but
the bounded agents did not reach clean closure: Agent A ignored two repeated
concrete `request_coordination` projections after its first successful request,
and B's later grant could not be consumed. The WorkGroup remained ACTIVE with
B's active claim and no B snapshot. This is agent-compliance evidence, not a
new lifecycle defect. The recurring Git subprocess stall, bootstrap migration
failures, and six fixture Doctor warnings remain separate.

## Historical next action

Run the next fresh bounded diagnostic with the same exact-projection rule and
verify that both agents continue polling after an idempotent REVIEW admission
until the second grant is consumed, B publishes its snapshot, validation and
integration complete, and the WorkGroup closes. If an agent again ignores a
concrete projection, preserve that as compliance evidence; do not change
production lifecycle code. Do not broaden cleanup, ownership, Doctor, push, or
create SYN-040.

## CP-0480 convergence projection slice

The CP-0480 diagnostic and ordinary runs did not reproduce a backend
WorkGroup split. Claim-bearing sessions with complementary exact scopes
converged on one deterministic active WorkGroup. The ordinary run exposed the
existing WorkGroup and exact REVIEW admission payload, but the executable
workflow reduced `request_coordination` to empty arguments. `AgentNextActionService`
now promotes the selected review protocol kind/payload to the response root,
and `AgentWorkflowReducer` copies it into exact executable arguments. Focused
MCP/workspace tests pass. Evidence:
`docs/evidence/syn039-unattended-todo-cp0480-convergence-projection-2026-08-24.md`.

Immediate next action: rerun a fresh post-fix diagnostic and ordinary
acceptance, then preserve the first lifecycle blocker after exact REVIEW
admission. Do not broaden cleanup, integration, Doctor, or ownership behavior.

## Objective

Make two ordinary Synesis-aware coding agents complete one shared repository
task unattended through the existing Synesis coordination model. Agent A must
implement, Agent B must review and validate without conflicting write
ownership, rejection must return work to the correct implementer, accepted
work must integrate, and the WorkGroup must close with no unresolved state.

## Activation boundary

- The reviewer-validation and producer-publication slices are implemented and
  under verification; reviewer admission, grant consumption, and the explicit
  owner publication action are now covered.
- The existing independent Codex/Claude Code session model remains underneath
  Synesis. Do not add a central orchestrator, UI, daemon, Fleet system,
  centralized launcher, provider intelligence, or manual relay service.
- SYN-038 remains complete and its prior acceptance evidence and
  `turn_interrupted_command_remained_active` limitation are preserved.

## Evidence and known gap

- Primary failure input: the user-supplied previous unattended Todo smoke test.
- The raw Todo smoke-test artifact was not present in the checkout. The
  reproduction is now captured in
  `docs/evidence/syn039-unattended-todo-baseline-2026-08-22.md`; raw Codex
  JSONL remains in the disposable fixture's `baseline-logs` directory.
- Checked-in supporting evidence is
  `docs/architecture/zero-touch-agent-collaboration.md`, where the two-process
  path is DEMO_ONLY and manually driven, and
  `docs/validation/multi-chat-provider-acceptance.md`, which does not claim
  autonomous end-to-end integration.

## Acceptance target

- Two ordinary Synesis-aware sessions discover and join one durable WorkGroup
  without user file assignment or message relay.
- A reviewer/validator can inspect and validate another agent's completed
  immutable snapshot through a read-only or explicitly delegated review path
  without acquiring the implementer's mutation ownership.
- Validation emits structured accept/reject evidence. Rejection returns
  durable work to the correct implementer with preserved lineage and
  idempotent request handling.
- Accepted work integrates through the existing guarded integration path into
  the final project state without manual intervention.
- Completion closes participants, claims, lane grants, pending requests,
  detached coordination state, and temporary artifacts.
- Final `synesis doctor` is healthy or reports only explicitly accepted
  non-blocking warnings.
- The unattended two-agent Todo experiment passes end to end: Agent A
  implements, Agent B reviews/validates, one rejected result routes back
  correctly, the corrected result is accepted, tests pass, the control
  checkout contains the completed application, the WorkGroup closes, and no
  unresolved coordination state remains.
- The existing ten-tool MCP boundary and provider model remain intact.

## Baseline result

The real two-session reproduction failed. Agent A created WorkGroup
`7c5ab815-5f05-365b-a78b-3478440036af`, implemented the Todo completion
change, passed three focused tests, and published snapshot
`snap_6162f6fd4ff4d51aadb5484609270ab3`. Integration failed with
`integration_failed` / `TESTS_FAILED`. Agent B discovered the WorkGroup but
could not obtain review authorization: `work_group_join` required an unavailable
`grantId`, and no validation request or snapshot view was exposed. The control
checkout stayed at baseline `7a5925f`; post-run Doctor was `DEGRADED` with two
`stale_session_lease` warnings and reconciliation recommended.

The baseline identified the first narrow implementation seam as reviewer
snapshot authorization plus the structured integration evidence mismatch; it
did not authorize a new orchestrator, daemon, UI, Fleet system, or launcher.

## Work completed

Implemented the first two defects and deterministic regressions. A reviewer
can now submit a typed `REVIEW` request after discovering a WorkGroup; owner
acceptance issues a targeted single-use LaneGrant without changing write
ownership. Collaboration status and next-action projections expose WorkGroups,
grants, and immutable snapshots. The integration-check adapter now recognizes
the recorded bounded passing Todo evidence instead of manufacturing
`TESTS_FAILED`.

The prior implementation and rerun evidence is recorded in
`docs/evidence/syn039-unattended-todo-review-validation-2026-08-22.md`. The
producer-publication slice is recorded in
`docs/evidence/syn039-unattended-todo-snapshot-publication-2026-08-22.md`.

The CP-0475 publication slice fixed the executable workflow projection: the
existing `nextProtocolPayload.summary` is now passed to `finish_lane` instead
of being discarded. A deterministic MCP fixture reached real `finish_lane`
execution, produced a `PUBLISHED` immutable snapshot, and exposed its ID in
the reviewer coordination projection. Evidence is recorded in
`docs/evidence/syn039-unattended-todo-snapshot-publication-cp0475-2026-08-24.md`.

## Current failures

The CP-0475 deterministic reproduction confirmed that the publication action
was emitted with an empty executable argument map even though the protocol
payload supplied the required summary. The minimal reducer fix is verified.
The first fresh two-agent harness attempt then failed before coordination:
Agent A remained at `workspace_not_ready` / `ensure_session`, while Agent B
reached a ready session with no peer WorkGroup and canceled its lane. A second
fresh attempt remained non-terminal for a bounded five-minute observation and
was stopped. These are harness/configuration failures, not a valid lifecycle
result and not a reason to change production readiness behavior.

The CP-0476 fresh fixture passed an explicit two-connection control preflight
with the current bundled MCP, but both independent agent harnesses failed
three times at `ensure_session(refresh=true)` with
`retry_required / workspace_not_ready / ensure_session`. No Todo work or
coordination lifecycle state was created. The first blocker is therefore the
agent-route configuration/session mismatch; it is not yet a production
readiness defect because the same executable and project passed through the
explicit control route. Evidence:
`docs/evidence/syn039-unattended-todo-harness-preflight-cp0476-2026-08-24.md`.

CP-0477 then corrected the harness with explicit per-agent current-bundle
configuration and reached a real WorkGroup. The owner implemented the Todo
completion change and passed 3 tests, but called `finish_lane` before review
readiness and received `task_not_ready / retry`. The reviewer saw the exact
`request_coordination(work_group_join)` review-admission projection, attempted
an invalid inbox acknowledgement, received `policy_denied / INBOX_ITEM_NOT_FOUND`,
and stopped. No grant, snapshot, validation, integration, or closure was
reached. This is agent action ordering, not a proven production protocol
defect. Evidence:
`docs/evidence/syn039-unattended-todo-cp0477-2026-08-24.md`.

The serialized root `check` remains incomplete. A focused
`:mcp:test --tests org.synesis.mcp.application.McpServerTest` reproduced the
Git subprocess stall at `McpServerTest.java:181`; worker `24912` was blocked
through `AgentNextActionService` → `RepositoryPrivateStateService` →
`GitProcessRunner` → `ProcessCommandRunner`. It was stopped only after thread
and child-process evidence was captured. Doctor remains `DEGRADED` with the
existing documented warnings.

The corrected CP-0481 diagnostic used the current bundled MCP with two
distinct ready/isolated sessions and disjoint claims. The agents converged on
WorkGroup `f0666aa0-31db-3025-a7e7-2e46f3fad1de`; Agent A published
`snap_0c58f76fb959553d7d64d64ce7b0d21c` but selected unprojected
`finish_lane`, which returned `integration_failed`. No REVIEW action was
projected and Agent B never reached admission. This is agent-compliance
evidence, not a production defect. Evidence:
`docs/evidence/syn039-unattended-todo-cp0481-postfix-review-admission-2026-08-24.md`.

The CP-0482 diagnostic then required exact execution of every concrete
projection. Both agents completed visible work and repeatedly received
ordinary `IMPLEMENT` with no executable tool or arguments. No REVIEW,
publication, grant, validation, or integration action was exposed. This is a
confirmed projection defect, not agent noncompliance. Evidence:
`docs/evidence/syn039-unattended-todo-cp0482-actionable-projection-2026-08-24.md`.

## CP-0483 active-reviewer projection rerun

The fresh post-fix diagnostic used the current bundled MCP, two distinct
ready/isolated sessions, and disjoint `todo.py` / `test_todo.py` claims. Both
intents converged on WorkGroup `af1807bc-ab46-3c98-8908-7073a807a7a6`. Agent A
published snapshot `snap_2ecbf452a75a69a8048168e6a1f177f2`, but the reviewer
intent was recorded first. The reviewer then repeatedly received ordinary
`IMPLEMENT` with no executable action even though the implementation snapshot
was visible. No REVIEW request, grant, validation, integration, or closure was
created. Evidence:
`docs/evidence/syn039-unattended-todo-cp0483-active-reviewer-projection-2026-08-24.md`.

The active-peer admission slice is verified by focused workspace/MCP tests,
but the live run proves an order-dependent producer/reviewer selection gap.
The known unprojected `finish_lane` calls remain agent-compliance evidence.

## Implementation order

1. Reproduce and capture the supplied unattended Todo failure.
2. Implement reviewer admission and the integration evidence fix.
3. Re-run the admitted-review path through the new owner `finish_lane` action;
   if reached, preserve the next lifecycle failure as a bounded blocker.
4. Implement autonomous rejection routing, handoff lineage, and WorkGroup
   cleanup/Doctor closure only as required by evidence.
5. Rerun the same unattended Todo experiment with no babysitting and record
   the complete evidence.

## Historical next action

Trace the order-dependent review-admission owner selection in
`AgentNextActionService.reviewActions`. Reproduce the reviewer-first state with
a deterministic fixture, then implement only the smallest existing-model fix
that lets the reviewer discover the implementation snapshot and exact
`request_coordination(work_group_join)` action using WorkGroup, intent,
claim-epoch, and snapshot provenance. Preserve fail-closed ownership and do
not add roles, orchestration, cleanup, or a side channel. Keep the Git stall,
bootstrap migration failures, and Doctor warnings separate. Do not push or
create SYN-040.

## CP-0471 owner REVIEW-acceptance slice

The owner-side projection defect is fixed. For a pending REVIEW request,
`get_next_action` now includes the exact request-specific strict
`respond_coordination` payload and WorkGroup/intent/claim-epoch context;
`AgentWorkflowReducer` carries that payload into the existing executable
workflow action. The collaboration service still performs authorization and
replay checks. Deterministic MCP, workspace, coordination, Javadocs,
validator, Go, vet, and diff checks pass. Evidence:
`docs/evidence/syn039-unattended-todo-owner-acceptance-2026-08-22.md`.

The fresh app-managed two-agent rerun did not reach this state: initial
ownership admission returned the existing typed `overlapping_claim` blocker
because another participant already held both Todo paths. It is not a valid
end-to-end result for this slice. Root `check` remains incomplete at the known
Git subprocess stall in `WorkspaceCliTest.setUp:74`; Doctor remains DEGRADED.

Immediate next action: run the exact fresh two-agent Todo acceptance with
isolated initial ownership, then verify autonomous owner acceptance and
preserve the next lifecycle failure. Do not broaden SYN-039 or create SYN-040.

## CP-0472 unattended acceptance result

The fresh two-agent run is recorded in
`docs/evidence/syn039-unattended-todo-workspace-not-ready-2026-08-23.md`.
Both independent Luna High agents stopped at the same typed
`workspace_not_ready` → `ensure_session` recovery projection. No WorkGroup,
claim, request, grant, snapshot, validation, integration, or closure state was
created. The fixture remained at coordination sequence zero with zero tasks and
zero ownerships. This is the first blocker for this run, but it is currently
classified as per-project MCP/session readiness until reproduced
deterministically outside the agent harness. No production behavior was
changed.

## CP-0473 workspace-readiness implementation and rerun

The readiness trace identified that Codex's managed global MCP entry omitted
the initialized project root. `ensure_session` therefore depended on the MCP
process directory when a provider did not send MCP roots. Codex installation
now writes the existing `--project <root>` argument through the explicit-root
configuration path. Fresh provider installation, repeated session ensure, and
two independent bindings are covered by deterministic tests. Commit:
`bea47c4`.

The direct MCP process with the generated project-pinned entry returned
`ready/isolated`. The fresh unattended CP-0473 rerun still stopped before
coordination because the agent harness reported an incompatible/stale MCP
distribution and project-schema-v2 readiness failure. No lifecycle state was
created. Evidence:
`docs/evidence/syn039-workspace-readiness-cp0473-2026-08-23.md`.

## CP-0474 current-bundle unattended acceptance

The fresh acceptance used two independent GPT-5.6 Luna High agents and the
rebuilt Windows platform bundle. Both MCP processes launched the current
`synesis-mcp.exe` with the exact disposable project root. Initialize reported
protocol `2025-06-18`, the catalog contained exactly ten tools, and both agents
reached `ready / isolated`.

The run reached the real lifecycle: Agent B discovered WorkGroup
`33e8329c-fd66-3174-9e3f-f115f6dae550`, autonomously obtained and consumed
REVIEW grant `496f1893-ca32-3939-82a1-24f860dea86a`, and did not take write
ownership. Agent A implemented the Todo operation and passed four pytest tests,
but its projected `PUBLISH` action remained blocked by
`snapshot_publication_required`; repeated `finish_lane` returned
`task_not_ready` / `retry`. No snapshot, validation, integration, or closure
was reached. Evidence:
`docs/evidence/syn039-unattended-todo-cp0474-2026-08-24.md`.

Immediate next action: reproduce the owner-side `PUBLISH` /
`snapshot_publication_required` stop deterministically and trace why the
implementer does not execute the already-projected snapshot-publication path.
Implement only that narrow producer transition if the evidence confirms a
protocol defect. Keep cleanup, Doctor, ownership, integration redesign, the
Git stall, and bootstrap migration failures separate; do not create SYN-040.

## CP-0475 snapshot-publication projection slice

The exact contradiction was reproduced: `get_next_action` projected
`snapshot_publication_required` and `nextProtocolPayload.summary`, while the
workflow reducer emitted empty `finish_lane` arguments. The reducer now carries
that existing payload into the executable action. The deterministic fixture
verified the exact WorkGroup, intent, claim epoch, and summary, then executed
the real MCP `finish_lane` path and observed a `PUBLISHED` immutable snapshot
visible in reviewer coordination status. Focused MCP/workspace tests and
Javadocs pass.

The fresh CP-0475 agent harness attempts did not reach a valid shared
WorkGroup: the first had one readiness failure and one isolated peer with no
WorkGroup; the second remained non-terminal and was stopped after bounded
observation. Evidence:
`docs/evidence/syn039-unattended-todo-snapshot-publication-cp0475-2026-08-24.md`.

Immediate next action: run the exact unattended two-agent Todo acceptance with
both MCP processes independently verified against the current bundle and
project root. Preserve the first post-publication lifecycle blocker if the run
reaches it; do not modify review, cleanup, Doctor, or integration behavior
speculatively.

## CP-0477 explicit harness and lifecycle rerun

The CP-0477 comparison confirmed that one ordinary multi-agent route selected a
stale installed launcher without `--project`, while a pinned current-bundle
route failed when its disposable project had disappeared. The acceptance
harness was corrected with explicit per-agent current-bundle, project,
provider, and connection-instance overrides; no production code changed.

Both explicit preflight agents passed. The unattended lifecycle reached
WorkGroup `62f4a6d0-0061-3e3d-8cc5-7536b556782c`, intent
`8fcbab57-9293-3321-8945-e5a5fd4af6b9`, claim epoch 1, and a passing 3-test
Todo implementation. The owner called `finish_lane` before review readiness
and received `task_not_ready / retry`. The reviewer observed the exact
`REVIEW_ADMISSION_REQUIRED → request_coordination(work_group_join)` projection,
attempted an invalid inbox acknowledgement, received
`policy_denied / INBOX_ITEM_NOT_FOUND`, and stopped. No grant, snapshot,
validation, integration, or closure was reached. This is agent action ordering,
not a proven production protocol defect. Evidence:
`docs/evidence/syn039-unattended-todo-cp0477-2026-08-24.md`.

## CP-0476 harness preflight

Evidence is recorded in
`docs/evidence/syn039-unattended-todo-harness-preflight-cp0476-2026-08-24.md`.
The fresh fixture and exact repository-bundled MCP passed an explicit
two-connection control preflight: protocol `2025-06-18`, exactly ten tools,
the same initialized project ID, distinct isolated worktrees, and
`ensure_session(refresh=true)=ready`.

Both independent `gpt-5.6-luna` agent harnesses nevertheless failed their
required preflight three times at `ensure_session(refresh=true)` with the
typed state `retry_required / workspace_not_ready / ensure_session`. Neither
agent began Todo work; coordination sequence remained zero and no WorkGroup,
claim, request, grant, snapshot, validation, integration, or closure state was
created. This is the first CP-0476 blocker and is not evidence for changing
production readiness or lifecycle behavior because the same bundled executable
and project succeed through the explicit control invocation.

Exact next action: capture the effective MCP executable, startup version/commit,
project arguments, connection identity, and readiness trace from the agent
route itself, then reconcile that route with the passing control invocation.
Keep the Git subprocess stall, bootstrap migration failures, and Doctor
warnings separate. Do not push or create SYN-040.

## CP-0479 agent contract clarification and acceptance reruns

The CP-0478 audit found an ambiguous agent-facing gap: `IMPLEMENT` with no
concrete `recommendedTool` and `arguments` did not explicitly mean continue
ordinary coding in the visible assigned worktree. The managed provider manual
and `get_next_action` MCP description now state that behavior, explicitly
forbid `.synesis/**` inspection through workspace file tools, and require exact
execution only when a concrete tool and arguments are projected. Deterministic
catalog/manual tests pass; path protection and lifecycle semantics are
unchanged.

The bounded CP-0479 diagnostic reached one shared WorkGroup, review admission,
review grants, a passing four-test implementation, snapshot publication, and
integrated control checkout commit `24ed805`. The owner still selected
unprojected `finish_lane` once and received `task_not_ready / retry` before a
later retry succeeded. No structured reviewer ACCEPT/REJECT decision was
captured, and three managed worktrees remained. Evidence:
`docs/evidence/syn039-unattended-todo-cp0479-contract-and-ordinary-acceptance-2026-08-24.md`.

The required second ordinary acceptance did not form a shared WorkGroup. Both
agents independently published snapshots, each reached `integration_blocked`,
and the control checkout stayed at its managed baseline. One agent's
non-projected integration-check facts reported pytest green but received
`integration_conflict / TESTS_FAILED`; this is preserved as an observation,
not a production-fix authorization. The next concrete blocker is ordinary
agent coordination discoverability/compliance, with integration classification
still secondary.

Both fixtures reported coordination sequence zero, zero tasks, and zero
ownerships after the runs. Doctor remained `DEGRADED` with six warnings. The
Git subprocess stall and bootstrap migration failures remain separate.

Exact next action: preserve CP-0479 and design the smallest evidence-led slice
for ordinary peer/WorkGroup discovery and convergence, without changing
hidden-path protection or adding orchestration. Do not push or create SYN-040.

## CP-0481 post-fix diagnostic

The fresh corrected fixture `syn039-cp0480-006` used the current bundled MCP,
the same initialized project, distinct ready/isolated sessions, and disjoint
`todo.py` / `test_todo.py` claims. Both agents converged on WorkGroup
`f0666aa0-31db-3025-a7e7-2e46f3fad1de`. Agent A published snapshot
`snap_0c58f76fb959553d7d64d64ce7b0d21c`, but selected unprojected
`finish_lane`; integration returned `integration_failed` and no REVIEW action
was projected. Evidence:
`docs/evidence/syn039-unattended-todo-cp0481-postfix-review-admission-2026-08-24.md`.

This is agent action/compliance evidence, not a production integration defect.
The next bounded diagnostic must execute every concrete projected action
exactly and preserve the first post-publication result. Do not push or create
SYN-040.

## CP-0482 actionable-projection defect

The fresh bounded diagnostic `syn039-cp0481-001` used the current bundled MCP,
two ready/isolated sessions, and disjoint claims. Both agents converged on
WorkGroup `ffd58516-2313-3ccc-a402-b20c921d2f8f`, completed visible work, and
obeyed the exact-action rule. Repeated `get_next_action` calls still exposed
ordinary `IMPLEMENT` with no concrete tool or arguments, while no REVIEW,
publication, grant, validation, or other progress action was projected. This
is the first confirmed CP-0482 production projection defect. Evidence:
`docs/evidence/syn039-unattended-todo-cp0482-actionable-projection-2026-08-24.md`.

## CP-0478 exact-action protocol diagnostic

The fresh CP-0478 fixture used the current bundled MCP, explicit project pin,
distinct connection IDs, exactly ten tools, and two `ready / isolated`
sessions. Both agents reached an initial `IMPLEMENT` projection, but no
specific lifecycle tool or arguments were projected because no WorkGroup yet
existed. Agent A then selected `read_file(".synesis/project.json")` and Agent
B selected the same hidden-path read; both received `blocked / invalid_path`.
Agent B confirmed the metadata through the valid command
`git show HEAD:.synesis/project.json` and stopped. No files or coordination
state changed. Evidence:
`docs/evidence/syn039-unattended-todo-cp0478-protocol-diagnostic-2026-08-24.md`.

This is an agent-selected repository-inspection failure, not an exact
projected lifecycle action failure and not a proven production defect. No
second ordinary-agent acceptance was run. Coordination sequence remained
zero with no WorkGroup, claims, requests, grants, snapshots, validation, or
integration. Doctor was `DEGRADED` with six existing warnings; the Git
subprocess stall, bootstrap migration failures, and Doctor findings remain
separate.

Exact next action: preserve CP-0478, assess the hidden metadata path contract,
and only rerun a diagnostic after the initial agent inspection uses valid
repository operations. Do not modify production lifecycle code, push, or
create SYN-040.

## 2026-09-10 — SYN-055 modal Diagnostics inspector

The Diagnostics finding inspector is now a modal popup rather than a right-side
grid panel. It preserves the underlying findings table, supports close,
Escape, backdrop dismissal, and left/right navigation. Previous and next
controls remain visible and are natively disabled at the ends of the finding
list. The Doctor data model and repair semantics are unchanged.

Exact next action: preserve the verified modal behavior and package; perform a
viewport screenshot pass only when a viewport-capable browser surface is
available. Do not add repair actions or backend changes.

## 2026-09-10 — SYN-055 project header navigation

The shared live-project header now places the project name and runtime status
in the right metadata rail beside Project ID and Local path. The left side now
contains a visible Back to projects button that returns to the Projects home.
The breadcrumb remains for location context.

Exact next action: preserve the packaged header navigation and run a viewport
screenshot pass when a viewport-capable browser surface is available.

## 2026-09-10 — SYN-055 icon-only back control

The project header keeps the project name/status in the right metadata rail and
now uses only a back-arrow icon in the left control. The visible button text
was removed; its accessible label remains `Back to projects`.

Exact next action: preserve CP-0843 and run the pending viewport screenshot
pass when a viewport-capable browser surface is available.

## 2026-09-10 — SYN-055 header layout correction

Restored the project name and runtime status to their original left-side
position. The right metadata rail again contains only Project ID and Local
path. The new back control remains an icon-only arrow beside the title.

Exact next action: preserve CP-0844 and run the pending viewport screenshot
pass when a viewport-capable browser surface is available.

## 2026-09-10 — SYN-055 local mock-data development mode

Added an explicit Vite development-only `?mock=1` mode backed by a local
snapshot fixture. It bypasses bootstrap/control-plane startup only in Vite
development mode, exposes realistic project/agent/coordination/network/
diagnostics data, and leaves the normal runtime path unchanged.

Live smoke passed at `/projects/proj_test/overview?mock=1` and
`/projects/proj_test/diagnostics?mock=1`.

Exact next action: use `npm run dev` in `web-ui` for UI iteration; do not
reinstall the Windows bundle for frontend-only changes.

## 2026-09-10 — SYN-055 detail popup refinement

Removed the always-visible right-side detail rails from Agents, Coordination,
and Network. Agent rows now open an Agent Detail popup; Coordination exposes a
View details popup; Network exposes Membership and Relay detail popups. The
Network Authority boundary note was removed as requested. All popup content
continues to use the existing snapshot model.

Exact next action: continue UI iteration against the running mock server at
`http://127.0.0.1:5173/projects/proj_test/network?mock=1`.

## 2026-09-10 — SYN-055 Projects page refinement

The global Projects page now has client-side project search across name,
identity, path, and status. The right rail contains only Connections. Each
projected connection has its own View network control and a visibly disabled
Terminate end control because no terminate-connection command exists in the
current control-plane contract. The page header and registry heading are
sticky on the Projects route when the page scrolls.

Exact next action: preserve CP-0848 and continue browser QA at
`http://127.0.0.1:5173/projects?mock=1`.

## 2026-09-10 — SYN-055 connection termination popup and mock scroll slice

Replaced the per-connection disabled text action with a red bin icon on both
the global Connections rail and the project Network peer table. The icon opens
a confirmation popup with the requested peer-to-peer dependency warning for
DIRECT and PEER_TRANSIT routes; relay routes do not receive that warning. The
confirm action remains disabled because the current control-plane contract has
no terminate-connection command. Expanded the development-only mock registry
to 14 projects and 8 peers for scrollability checks.

Exact next action: preserve the running Vite mock server and use the two mock
URLs in the final handoff; no reinstall is required for this frontend slice.

## 2026-09-10 — SYN-055 Network row detail refinement

Removed the standalone Server-selected routes widget from Network. Clicking
any peer row now opens a Connection detail popup with the projected route,
path, membership, health, authentication, usability, and session data. The
global Connections rail uses the same row-detail behavior; termination icons
remain separate actions.

Exact next action: preserve CP-0849 and continue frontend QA on the running
mock server.

## 2026-09-10 — SYN-055 supplied logo asset

Replaced the generated CSS mark in the global header and connection screen
with the supplied `SynesisLogo.png` asset copied to
`web-ui/public/synesis-logo.png`. The existing accessible Synesis label and
navigation behavior remain intact.

Exact next action: keep frontend-only iteration on the running Vite server; no
reinstall or package rebuild is required for this asset change.

## 2026-09-10 — SYN-055 consistent project view width and browser history

Removed the special wide layout from Coordination and Network so every project
view now uses the same content width as Overview, Agents, and Diagnostics.
Project-view navigation now uses browser history entries and preserves the
development `?mock=1` query; `popstate` restores the selected project and tab
when the browser Back or Forward controls are used.

Verification: typecheck, lint, 15 tests, production build, diff check, and
live mock Back/Forward navigation passed.

Exact next action: continue visual QA from
`http://127.0.0.1:5173/projects/proj_test/overview?mock=1`.

## 2026-09-10 — SYN-055 Coordination table scrollbar removal

Removed the nested horizontal scrollbar from Coordination Claims, Tasks,
Capabilities, and Ownership tables. The tables now fit their panel and wrap
long values while the page-level scroll remains available.

Verification: typecheck, lint, 15 tests, production build, diff check, and
live mock Coordination inspection passed.

## 2026-09-10 — SYN-055 Coordination nested scrollbar correction

The Coordination records tabs no longer expose a nested scrollbar. The tab
strip is clipped to its own bounds and record tables wrap long values within
the panel; page-level scrolling remains available.

Verification: typecheck, lint, 15 tests, production build, diff check, and
live mock screenshot inspection passed.

## 2026-09-10 — SYN-055 global runtime indicator removal

Removed the `Runtime connected` indicator from the global header. Runtime and
connection state remain intact for loading and offline behavior; only the
header presentation was removed.

Verification: typecheck, lint, 15 tests, production build, and diff check
passed. Live preview reopening was blocked because the local Vite server was
not running after the prior preview session ended.

## 2026-09-10 — SYN-055 registry detail back control

Added the same icon-only Back to projects control to every registry detail
state, including INACTIVE, UNAVAILABLE, and IDENTITY_MISMATCH projects. The
accessible label remains available while the visual button contains only the
arrow.
