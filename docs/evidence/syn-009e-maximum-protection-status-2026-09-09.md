# SYN-009E Synesis maximum-protection status report — 2026-09-09

Classification: **MAXIMUM PROFILE = PARTIAL / COMMERCIAL TOOL EXECUTION
REQUIRED**.

This is the current evidence-led release report requested by the Seven Rings
brief. It records the exact boundary reached in this checkout; it is not a
claim that a commercial maximum artifact exists.

## Starting state and task authority

- Continuation start: commit `afd83488` on `master`.
- Active task: `SYN-009E`, the existing maximum-protection release/security
  lineage.
- `CP-0758.md` remains preserved and is not modified by this work.
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
first-party documentation pre-screen. Neither is selected as the maximum
protector because neither is installed, licensed, or exercised against a
Synesis artifact. No vendor documentation is treated as local ring evidence.

The capability matrix is in
`docs/release/protector-capability-matrix.md`; the documentation-only review is
`docs/evidence/syn-009e-protector-doc-review-2026-09-09.md`.

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
compatibility rules are in `docs/release/protection-keep-rules.md`.

## Seven Rings

| Ring | Result | Evidence and exact boundary |
|---|---|---|
| 1 — Control-flow obfuscation | **BLOCKED** | Lite renaming is not control-flow protection; commercial transformation and crown-jewel decompiler inspection are absent. |
| 2 — Code virtualization | **BLOCKED** | No licensed protector output proves genuine virtual execution; wrappers, dispatch tables, and encrypted loaders are explicitly rejected. |
| 3 — String/constant encryption | **BLOCKED** | Lite does not claim runtime string/constant protection; commercial before/after static evidence is absent. |
| 4 — Analysis-environment policy | **BLOCKED** | Safe multi-signal behavior and legitimate-VM/CI acceptance require the selected protector and controlled tests. VM-name checks are not accepted. |
| 5 — Protected payload/packing | **BLOCKED** | Existing archive/bootstrap integrity is a foundation; ZIP compression is not packing and no commercial protected loader is present. |
| 6 — Anti-debug/instrumentation | **BLOCKED** | No commercial detect/refuse evidence exists; no hostile or destructive anti-debugging was implemented. |
| 7 — Integrity/diversification/private recovery | **PARTIAL** | Bootstrap signing, immutable manifests, provenance, private mappings/retrace/native evidence gates, and comparison tooling exist; signed commercial records, release-to-release positive comparisons, and installed maximum tamper/retrace runs are absent. |

The exact ring boundary and fail-safe policy are also captured in
`docs/adr/0070-maximum-protection-commercial-release-profile.md`.

## Keep rules, virtualization, and anti-analysis policy

The keep-rule audit is narrow and source-backed: Picocli metadata, MCP
reflection, UI resources, Netty/QUIC loading, native/resource names, and public
protocol/configuration contracts are preserved. The scan found no additional
owned ServiceLoader registration or JNI declaration requiring a global rule.
Global `org.synesis.**` keeping is prohibited.

Planned Tier 3 virtualization targets are authority resolution, workspace trust,
ownership/capability decisions, overlay membership/route policy, and relay
admission policy. They remain unexecuted until a commercial tool proves genuine
virtualization on the shipped artifact.

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
all six ring records, non-empty diversification/ring evidence, non-empty
mapping/retrace outputs, owned native-symbol material where the component owns
native launchers, a private audit of shipped third-party native dependencies,
and a customer/private boundary. Results must echo the source, seed, lockfile
snapshot, Java/Gradle/Node/npm/Go toolchains, protector configuration, Tier
0–3 inventory, keep-rule inventory, and acceptance-procedure digests. The CLI
also rejects a `GITHUB_SHA` that does not equal local `HEAD`.

The existing bootstrap signer then creates and verifies the detached Ed25519
manifest against its embedded trust root. Production signing keys are injected
and never generated or committed. Private ring/recovery evidence hashes are
stored only in `release-record.properties`; native-hardening evidence is also
hashed there after the protected archive is audited. Private material is
forbidden from the customer archive.

`scripts/maximum-release-provenance-comparison.ps1` requires final signed
records, verifies private recovery/evidence hashes, and provides separate
diversification and reproducibility modes. No positive commercial pair exists.

## Artifact size, performance, and reverse-engineering evidence

The current five-sample Windows x64 CLI comparison recorded:

| Profile | Archive | Extracted | Median cold startup |
|---|---:|---:|---:|
| developer | 46,504,281 bytes | 69,542,917 bytes | 715.513 ms |
| protection-lite | 45,911,491 bytes | 68,870,411 bytes | 685.841 ms |
| maximum-release | NOT SUPPLIED | NOT SUPPLIED | NOT SUPPLIED |

Peak memory was unavailable from the wrapper. UI latency, control-plane
latency, Link setup, route selection, relay throughput, provider lifecycle,
and maximum performance remain open.

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
  help, doctor, packaged UI/control-plane HTTP/SSE, provider/MCP, native
  installer, immutable JVM/frontend tamper refusal, mutable-state
  non-false-positive behavior, local Link invitation/PeerSession/project sync;
  result remains `PASS_WITH_EXPLICIT_OPEN_GATES`.
- Relay protection-lite/extracted launcher: bounded authentication,
  forwarding, encrypted-frame handling, and shutdown through the external
  observer; commercial maximum relay archive is absent.
- Overlay/Internet NAT, protected multi-peer route selection, and final
  provider/native maximum acceptance remain unexecuted.

Tamper acceptance is therefore partial: synthetic immutable-payload refusal
passed, but the real signed commercial maximum archive and protected loader
have not been installed and tampered in a disposable environment.

## Leakage and recovery

Developer/lite archive scans found no source maps, mapping files, seeds,
provenance files, debug symbols, or source files as archive entries. Class-level
SourceFile metadata remains an open commercial leakage decision. No commercial
secret, mapping, seed, production signing key, or private recovery file was
committed.

Private retrace, mapping, owned native-symbol recovery where applicable,
third-party native audit, and ring evidence are contracted and fail closed, but
no positive licensed adapter output exists to exercise retrace or native
acceptance.

## Commits and final boundary

This continuation added the following verified slices after the earlier
release-hardening work:

- `435d6451` — component-scoped native recovery and third-party-native audit
  gate for CLI/relay maximum records;
- `f59f02b2` — post-archive native hardening/signing audit gate before manifest
  creation and private evidence hashing;
- `35e8bf6b` — release provenance comparison;
- `9de90337` — complete adapter provenance echo binding;
- `18092671` — CLI source-commit verification;
- `afd83488` — concrete private maximum evidence requirements.

At report preparation, the branch remains local and unpublished, `CP-0758`
is preserved, unrelated working-tree edits remain outside the release slice,
and no push was performed. The exact remaining external action is to obtain an
installed, licensed, version-pinned commercial protector (DashO or Zelix
candidate evaluation), its reviewed adapter/configuration, platform signing
authority, and clean release checkout; then run the complete maximum adapter,
signed-record comparison, native audit, shipped CLI/UI/control-plane/
Link/overlay/relay/provider acceptance, tamper/retrace/leakage/performance,
and AV/EDR checks. Until those artifacts and results exist, the maximum
profile must remain PARTIAL and the six commercial rings must remain blocked.
