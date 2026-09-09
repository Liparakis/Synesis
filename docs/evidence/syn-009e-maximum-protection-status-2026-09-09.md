# SYN-009E Synesis maximum-protection status report — 2026-09-09

Classification: **MAXIMUM PROFILE = PARTIAL / COMMERCIAL TOOL EXECUTION
REQUIRED**.

This is the current evidence-led release report requested by the Seven Rings
brief. It records the exact boundary reached in this checkout; it is not a
claim that a commercial maximum artifact exists.

## Starting state and task authority

- Current referenced brief reread: `C:\Users\Liparakis\.codex\attachments\3aa44bec-cbc3-45bc-a38b-c9ed3be9ee98\pasted-text-1.txt`, SHA-256
  `B5D067ABC48D9B78B9CFABDE06E04A7C82E236D7B9944CD9077A49955EEE458C`.
- Continuation start: commit `afd83488` on `master`.
- Active task: `SYN-009E`, the existing maximum-protection release/security
  lineage.
- `CP-0758.md` remains preserved and is not modified by this work.
- Latest verified implementation slice is `cbecbac5`, checkpointed as
  `CP-0804`; the post-archive native-audit, output-boundary, structured
  private-retrace, exact-brief taxonomy, acceptance-timing, and packaged
  frontend static-content slices are committed locally.
- The checkout contains unrelated pre-existing working-tree changes; no staged
  changes or reset were used. The maximum Gradle tasks reject such a dirty
  checkout and must run from a reviewed clean release commit.
- Local release commits remain unpublished. No push, tag, release, or remote
  mutation was performed.

The existing release/signing seams are reused: Gradle application bundles and
jlink images, the Go bootstrap installer/doctor, detached Ed25519 manifest
signing, SHA-256 immutable payload manifests, stable activation, native
launcher/installer packaging, packaged `web-ui`, and the separate relay
distribution.

## Profiles

| Profile | Current result |
|---|---|
| `developer` | PASS baseline; readable, debuggable, normal testing and stack traces remain unchanged |
| `protection-lite` | PASS bounded compatibility/artifact profile using ProGuard 7.10.0; not a maximum-security claim |
| `maximum-release` | PARTIAL; external adapter, commercial transformation, signing authority, and shipped maximum acceptance remain unavailable |

No customer-facing runtime switch can downgrade a maximum release. Protection
is release-only and opt-in.

## Tooling and selection

ProGuard 7.10.0 is selected only for `protection-lite`. DashO and Zelix
KlassMaster remain credible commercial evaluation candidates based on the
first-party documentation pre-screen. Virbox Protector Standalone is now an
additional candidate because its Java VME/BCE documentation directly describes
private-VM method execution and encrypted method payloads. No candidate is
selected as the maximum protector because none is installed, licensed, or
exercised against a Synesis artifact. No vendor documentation is treated as
local ring evidence.

The current first-party recheck sharpens that choice: DashO documents
control-flow, string encryption, and runtime debug/tamper checks, while Zelix
documents flow/string/constant protection and reproducible output. The reviewed
pages still do not establish genuine JVM virtualization, protected payload
loading, or the Synesis-safe Java anti-VM/anti-instrumentation behavior needed
for Rings 2, 4, and 5. DashO is the first licensed candidate to evaluate for
Rings 1, 3, and bounded Ring 6 behavior, while Virbox is the first candidate
to evaluate specifically for Rings 2 and 5; these are selection priorities,
not passes. Virbox's separately licensed VME/BCE modes and its Java-safe Ring
4/6 behavior remain unverified.

The capability matrix is in
`docs/release/protector-capability-matrix.md`; the documentation-only review is
`docs/evidence/syn-009e-protector-doc-review-2026-09-09.md`.
The current callable-tool and release-input probe is recorded in
`docs/evidence/syn-009e-commercial-tool-availability-2026-09-09.md`.

## Tier 0–3 scope

- Tier 0 preserves CLI and relay entrypoints, Picocli command/option metadata,
  MCP reflective entrypoints, control-plane and protocol JSON/markers,
  Link/overlay wire contracts, resource paths, native/JNI boundaries, and
  required ServiceLoader/resource contracts.
- Tier 1 covers ordinary owned CLI, coordination, workspace, provider, and
  Link implementation eligible for shrinking, renaming, package layout, and
  vendor-supported string/constant protection.
- Tier 2 targets authority/continuity, capability/claim lifecycle, provider
  review/integration, overlay membership/topology/route policy, and relay
  admission decisions.
- Tier 3 is method-scoped: authority resolution, workspace trust, ownership
  evaluation, capability validation, overlay membership acceptance, route
  selection, and relay policy decisions. Hot QUIC/Netty loops, DTOs, parser
  metadata, and browser assets are excluded by default.

The source inventory is `docs/release/protection-tier-inventory.md`; narrow
compatibility rules are in `docs/release/protection-keep-rules.md`. A current
read-only audit resolved all 21 named paths and all 12 method-scoped Tier 3
signatures; it also recorded the concrete reflective/resource boundaries and
the absence of selected-source `ServiceLoader` registrations or Java native
declarations in
`docs/evidence/syn-009e-protection-scope-audit-2026-09-09.md`. This confirms
scope freshness only; it does not prove commercial compatibility or a ring.

## Seven Rings

The release report uses the exact ring structure from the current referenced
brief. The adapter's six `requiredRings` fields are the mandatory commercial
Ring 1–6 inputs; Ring 7 is completed by the Gradle/bootstrap release boundary
and private recovery record. Shrink/strip/sanitize, symbol/package reduction,
native hardening, and stable-contract compatibility are supporting distribution
gates rather than additional rings.

| Ring | Result | Evidence and exact boundary |
|---|---|---|
| 1 — Control-flow obfuscation | **BLOCKED** | No licensed commercial transformation or shipped crown-jewel decompiler comparison is available; lite renaming is not control-flow protection. |
| 2 — Code virtualization | **BLOCKED** | No protector-defined virtual execution output or artifact inspection is available; wrappers and dispatch tables are not accepted. |
| 3 — String/constant encryption | **BLOCKED** | Lite does not claim runtime string/constant protection; commercial before/after static extraction evidence is absent. |
| 4 — Anti-VM/analysis-environment detection | **BLOCKED** | No licensed multi-signal analysis-risk behavior has been exercised; ordinary VM/CI compatibility policy is documented but not commercial evidence. |
| 5 — Packed/protected code payload | **BLOCKED** | No protected loader/payload archive has been inspected; ZIP compression and ordinary class loading do not count. |
| 6 — Anti-debug/anti-instrumentation | **BLOCKED** | No controlled debugger, agent, bytecode-instrumentation, or protected-loader tamper run exists; destructive retaliation is prohibited. |
| 7 — Signed integrity/diversification/private retrace | **PARTIAL SEAM** | Existing bootstrap signing, immutable manifests, tamper gates, release-ID/seed provenance, private mappings, structured retrace acceptance, and comparison tooling exist; no signed commercial maximum artifact or installed maximum retrace exists. |

The exact ring boundary and fail-safe policy are also captured in
`docs/adr/0070-maximum-protection-commercial-release-profile.md`.

## Keep rules, supporting hardening, and anti-analysis policy

The keep-rule audit is narrow and source-backed: Picocli metadata, MCP
reflection, UI resources, Netty/QUIC loading, native/resource names, and public
protocol/configuration contracts are preserved. The scan found no additional
owned ServiceLoader registration or JNI declaration requiring a global rule.
Global `org.synesis.**` keeping is prohibited.

Planned Tier 3 maximum-transformation targets are authority resolution,
workspace trust, ownership/capability decisions, overlay membership/route
policy, and relay admission policy. They remain unexecuted until a commercial
tool proves the selected Ring 1/3 transformations on the shipped artifact.
Ring 2 virtualization, Ring 5 protected loading, Ring 4 analysis-risk, and
Ring 6 anti-instrumentation each require their own vendor output and controlled
acceptance; none can be inferred from another ring.

The anti-analysis policy is fail-safe: ordinary VMware, Hyper-V, Parallels,
VDI, CI, and development VMs must continue to work. Only documented,
bounded, multi-signal integrity/instrumentation risk may refuse a protected
operation with a stable diagnostic. No host surveillance, debugger attacks,
process killing, kernel components, persistence, security-tool disabling, or
filesystem destruction exists or is permitted.

## Native hardening

The archive-only audit now parses PE, ELF, and Mach-O and checks Synesis-owned
symbols/debug data, exports, source-path signals, trim-path metadata, and
private native-file leakage without modifying third-party binaries.

- Windows x64 developer/protection-lite owned launchers: static hardening PASS;
  no debug directory, debug sections, named exports, or tested local paths;
  `-trimpath` metadata is present.
- Linux ELF64 and macOS arm64 Mach-O parser fixtures: parser PASS only; they
  are not customer release evidence.
- The local relay install contains third-party Netty QUIC native content but
  no Synesis-owned native binary; the maximum contract therefore requires a
  private third-party-native audit and uses `nativeSymbolsScope=not-applicable`
  for relay instead of requiring fabricated Synesis symbols. See
  `docs/evidence/syn-009e-relay-native-scope-2026-09-09.md`.
- The maximum Gradle tasks now run the archive-only native hardening/signing
  audit after protection and before manifest signing. CLI requires native
  hardening and signing `PASS`; relay may record signing `NOT_APPLICABLE` only
  when its shipped output has no Synesis-owned native binary.
- Actual Linux/macOS/ARM64 maximum archives, third-party native inspection,
  Authenticode/Apple signing, and notarization: OPEN.

Evidence: `docs/evidence/syn-009e-native-hardening-2026-09-09.md` and
`docs/evidence/syn-009e-native-format-parser-2026-09-09.json`.

## Integrity, signing, diversification, and private recovery

The maximum Gradle seams require an executable version-pinned external
adapter, private configuration, explicit release ID/seed, a clean checkout,
the six mandatory Ring 1–6 records, non-empty private capability and
diversification evidence, non-empty mapping/retrace outputs, and a structured
private retrace-acceptance record
bound to the mapping hash. Owned native-symbol material is required where the component owns
native launchers, a private audit of shipped third-party native dependencies,
and a customer/private boundary. Results must echo the source, seed, lockfile
snapshot, Java/Gradle/Node/npm/Go toolchains, protector configuration, Tier
0–3 inventory, keep-rule inventory, and acceptance-procedure digests. The CLI
also rejects a `GITHUB_SHA` that does not equal local `HEAD`.

Both CLI and relay additionally reject the adapter executable and protector
configuration when either normalized lexical or canonical path is inside the
source checkout, including symlink aliases. This keeps commercial binaries and
license-bearing configuration in the private release environment rather than
allowing a committed or hidden build-tree input.

The adapter result now also requires a non-secret license mode, an explicit
attestation format, and a non-empty redacted license-attestation file below the
private directory. The CLI and relay private records hash that attestation;
this is release provenance and review evidence, not a license key or a claim
that an entitlement exists in the current checkout.

The release-to-release provenance comparator now requires and verifies the
private attestation path, SHA-256, format, and shared mode before it can report
diversification or reproducibility; no positive commercial records exist.

The existing bootstrap signer then creates and verifies the detached Ed25519
manifest against its embedded trust root. Production signing keys are injected
and never generated or committed. Private capability/recovery evidence hashes are
stored only in `release-record.properties`; native-hardening evidence is also
hashed there after the protected archive is audited. Private material is
forbidden from the customer archive.

`scripts/maximum-release-provenance-comparison.ps1` requires final signed
records, verifies private recovery/evidence hashes, and provides separate
diversification and reproducibility modes. No positive commercial pair exists.

The maximum CLI and relay tasks also reject symbolic links anywhere in the
adapter-produced customer bundle or private release directory before creating
the artifact manifest or signing. This prevents a lexical containment bypass;
the gate is configured and syntax-checked but has not run against a commercial
adapter output.

## Artifact size, performance, and reverse-engineering evidence

The current five-sample Windows x64 CLI comparison recorded:

| Profile | Archive | Extracted | Median cold startup | Max aggregate process-tree working set* |
|---|---:|---:|---:|---:|
| developer | 46,504,281 bytes | 69,542,917 bytes | 973.955 ms | 103,034,880 bytes |
| protection-lite | 45,911,491 bytes | 68,870,411 bytes | 794.654 ms | 104,062,976 bytes |
| maximum-release | NOT SUPPLIED | NOT SUPPLIED | NOT SUPPLIED | NOT SUPPLIED |

*The working-set values are Windows `PROCESS_TREE_WORKING_SET_BEST_EFFORT`
samples: an aggregate of the live launcher tree, not unique physical memory;
monitor overhead is not controlled. The extracted acceptance harness now
records bounded `durationMs` values for provider/doctor, Link onboarding, UI
smoke, authenticated control-plane HTTP/SSE, and relay forwarding, but no
commercial maximum run has populated those fields. Route-selection
microbenchmarks, relay throughput, unique physical-memory measurement, and
transformation overhead remain open. The profile timings and memory values are
baseline evidence only, not a performance win or release-acceptance claim.

The archive-only static comparison found zero source-map/private-file entries
in developer/lite archives and recorded lite renaming/package changes, but
SourceFile metadata remains in most classes. CFR/Procyon/jd-cli were not
available, so selected-method decompiler inspection is NOT RUN. No maximum
static comparison exists.

AV/EDR and false-positive checks were not run; signed reputable commercial
output and bounded platform-security checks remain release gates.

## Shipped-artifact acceptance

The independent acceptance harness is
`scripts/maximum-release-acceptance.ps1`; it accepts only extracted customer
archives, validates the maximum marker and private-manifest boundary, and
records private JSON evidence.

Current bounded evidence is not commercial maximum evidence:

- CLI protection-lite and synthetic marker-only installed candidate: version,
  help, doctor, packaged UI/static-content inspection, control-plane HTTP/SSE,
  provider/MCP, native installer, immutable JVM/frontend tamper refusal, mutable-state
  non-false-positive behavior, local Link invitation/PeerSession/project sync;
  result remains `PASS_WITH_EXPLICIT_OPEN_GATES`.
- Relay protection-lite/extracted launcher: bounded authentication,
  forwarding, encrypted-frame handling, and shutdown through the external
  observer; commercial maximum relay archive is absent.
- Overlay/Internet NAT, protected multi-peer route selection, and final
  provider/native maximum acceptance remain unexecuted.

The new `frontend-static-leakage` check passed against the existing
protection-lite JAR through the helper smoke, but the independent harness has
not been run against a licensed maximum archive. It therefore remains an
implemented gate, not a commercial result.

Tamper acceptance is therefore partial: synthetic immutable-payload refusal
passed, but the real signed commercial maximum archive and protected loader
have not been installed and tampered in a disposable environment.

## Leakage and recovery

Developer/lite archive scans found no source maps, mapping files, seeds,
provenance files, debug symbols, or source files as archive entries. Class-level
SourceFile metadata remains an open commercial leakage decision. No commercial
secret, mapping, seed, production signing key, or private recovery file was
committed.

Private retrace acceptance, mapping, owned native-symbol recovery where applicable,
third-party native audit, and Seven Ring evidence are contracted and fail closed, but
no positive licensed adapter output exists to exercise retrace or native
acceptance.

## Commits and final boundary

This continuation added the following verified slices after the earlier
release-hardening work:

- `435d6451` — component-scoped native recovery and third-party-native audit
  gate for CLI/relay maximum records;
- `f59f02b2` — post-archive native hardening/signing audit gate before manifest
  creation and private evidence hashing;
- `bc37137f` — recorded the post-archive native-audit gate in the status report;
- `35e8bf6b` — release provenance comparison;
- `9de90337` — complete adapter provenance echo binding;
- `18092671` — CLI source-commit verification;
- `afd83488` — concrete private maximum evidence requirements.
- `eebc6bcf` — structured private retrace-acceptance evidence bound to the
  exact mapping and trace hashes.
- `da934163` — added bounded acceptance timing fields; the current taxonomy
  reconciliation is recorded in this continuation.
- `4ae2fe4f` — reconciled the release contract and durable evidence to the
  exact current brief's Ring 1–7 taxonomy and bound the provenance comparator
  to the six mandatory commercial classes.
- `cbecbac5` — added packaged frontend static-content leakage acceptance for
  external runtime URLs, source-map/development references, and Vite/local
  markers.
- `caaa0427` — refreshed current-source Tier 0–3 path/signature and
  compatibility-boundary evidence; no commercial transformation was claimed.
- `18da2f4b` — refreshed the commercial-tool availability evidence and
  confirmed that no licensed adapter, commercial configuration, signing key,
  or release seed is available in the current release environment.
- `4b2836a2` — added the first-party Virbox Java VME/BCE candidate review,
  documented its Ring 2/5 evaluation boundary, and confirmed it is not
  installed or callable in this release environment.
- `1718437d` — made the provider-agnostic adapter documentation explicitly
  map a future Virbox VME/BCE wrapper to Rings 2 and 5 without adding a
  vendor-specific command or claiming evidence.
- `690c691a` — rejected commercial adapter/configuration paths inside the
  source checkout, including canonical symlink aliases.
- `974380fd` — required non-secret private license provenance in CLI/relay
  adapter results and release-to-release comparison records.

At report preparation, the branch remains local and unpublished, `CP-0758`
is preserved, unrelated working-tree edits remain outside the release slice,
and no push was performed. The exact remaining external action is to obtain an
installed, licensed, version-pinned commercial protector (with DashO/Zelix and
Virbox candidate evaluation), its reviewed adapter/configuration, platform signing
authority, and clean release checkout; then run the complete maximum adapter,
signed-record comparison, native audit, shipped CLI/UI/control-plane/
Link/overlay/relay/provider acceptance, tamper/retrace/leakage/performance,
  and AV/EDR checks. Until those artifacts and results exist, the maximum
  profile must remain PARTIAL; Rings 1–6 remain blocked and Ring 7 remains a
  partial seam until the commercial artifact gates pass.
