# ADR-0070: Synesis maximum-protection commercial release profile

- Status: Accepted for `SYN-009E`; maximum profile execution remains
  evidence-gated
- Date: 2026-09-09
- Scope: release engineering for the JVM, native, packaged UI, relay, and
  installer artifacts

## Context

Synesis now has a real self-contained distribution and a packaged browser UI.
The current developer bundle is intentionally useful to its maintainers: it is
readable, debuggable, testable, and compatible with normal IDE and stack-trace
workflows. That property must not be traded away in ordinary development or
test tasks.

The commercial release goal is different. A customer-style protected release
must materially increase the cost of casual static and dynamic reverse
engineering while remaining supportable, signed, installable, and safe. The
current brief defines seven rings:

1. control-flow obfuscation;
2. genuine code virtualization;
3. string and constant encryption;
4. anti-VM and analysis-environment detection;
5. packed or otherwise protected code payloads;
6. anti-debug and anti-instrumentation; and
7. signed integrity, release diversification, and private retrace.

The first six are mandatory commercial-protector capabilities and are supplied
through the external adapter. Ring 7 is owned jointly by the release pipeline
and the private release record. Shrinking, stripping, metadata/source-path
sanitization, symbol renaming, native hardening, and stable-contract keep rules
are supporting distribution hardening, not substitutes for these rings.
Renaming is not control-flow protection; compression is not protected packing;
a VM-name check is not an analysis policy; and a debug flag is not
anti-debugging. A wrapper, method-handle dispatch table, or ordinary encrypted
class loader is not code virtualization unless the selected protector's
documented transformation makes it a genuine virtual execution boundary.

The repository already owns the relevant distribution seams:

- Gradle `application` packaging, jlink runtime images, and platform bundles;
- a Go bootstrap with detached Ed25519 manifest verification, SHA-256 payload
  manifests, stable activation, and doctor checks;
- native launcher/installer and MCP launcher components;
- the separately packaged `relay` application;
- the install-bundled `web-ui` resource module; and
- existing release documentation and CI artifact boundaries.

Creating a second installer, trust root, or runtime service would multiply
security and operational boundaries without improving protection.

## Decision

### 1. Use three build/release profiles

The protection pipeline is opt-in and release-only:

| Profile           | Purpose                                | Transformations                                                                                                                               | Runtime use                                                       |
|-------------------|----------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------|
| `developer`       | normal engineering                     | none beyond ordinary compiler/jlink settings                                                                                                  | default local build and tests                                     |
| `protection-lite` | cheap compatibility and artifact audit | shrinking, renaming, metadata reduction, mappings, and any explicitly verified open-source string protection                                  | internal CI/disposable acceptance only unless separately approved |
| `maximum-release` | customer-style commercial release      | every licensed and verified ring, selective Tier 2/3 targeting, finalized assets, immutable manifest, signing, and post-protection acceptance | ship only after all required gates pass                           |

No customer-facing runtime switch may downgrade a maximum release to a
developer profile. Internal acceptance accommodations must be build-time and
separated from production trust and signing material.

The official order is:

```text
compile and normal-test
  -> frontend production build
  -> protection-lite compatibility check
  -> maximum commercial protection
  -> native hardening and final asset layout
  -> canonical immutable manifest
  -> signing
  -> disposable install and post-protection acceptance
  -> private archive of maps, seeds, symbols, and provenance
```

No artifact may be modified after the manifest is signed.

### 2. Keep protection selective by contract and value tier

The source is classified into four protection tiers:

- **Tier 0 — public contract.** Stable CLI command names, documented protocol
  versions and identifiers, public JSON fields, configuration keys, resource
  paths, required JNI/export names, ServiceLoader contracts, and any other
  externally consumed symbols remain stable or are explicitly adapted.
- **Tier 1 — proprietary implementation.** Ordinary project implementation
  is eligible for shrinking, renaming, package flattening, metadata reduction,
  and verified string/constant protection.
- **Tier 2 — high-value engine.** Authority, claims, coordination, provider
  lifecycle, managed continuity, review/integration, membership, overlay
  routing, relay policy, and runtime recovery decisions are candidates for
  stronger control-flow and constant transformations after profiling.
- **Tier 3 — crown-jewel methods.** Only intentionally selected decision
  methods may receive maximum Ring 1/3 protection and the selected Ring 2/5
  virtualization or protected-payload boundary when the licensed vendor proves
  it safely.
  Netty/QUIC loops, relay forwarding hot paths, DTO accessors, CLI parsing, and
  browser assets are not default Tier 3 targets.

The final Tier 2/3 list must be source- and profile-specific, reviewed against
the current implementation, and accompanied by before/after performance
measurements. It is not permitted to protect every class merely to obtain a
larger transformed artifact.

### 3. Select tools by demonstrated capability

`proguard-base` is the initial `protection-lite` candidate because it is
usable from the existing Gradle build without adding a daemon or changing the
application architecture. Its output is explicitly classified as
`PROTECTION_LITE`; it must not be described as a security boundary or as
virtualization, packing, anti-VM, or anti-debugging.

The commercial shortlist remains DashO and Zelix KlassMaster. A vendor feature
is considered available only when the exact licensed version is installed,
the build can invoke it non-interactively, and a shipped artifact proves the
feature. The following official references are the starting points for the
matrix:

- [ProGuard manual](https://www.guardsquare.com/manual/home) and
  [Java support](https://www.guardsquare.com/manual/languages/java):
  shrinking/optimization/obfuscation, mappings, and Java 25 compatibility;
- [ProGuard usage guidance](https://github.com/Guardsquare/proguard/blob/master/docs/md/manual/configuration/usage.md):
  the open-source tool is not itself a complete anti-reversing or tamper
  security product;
- [Zelix obfuscation options](https://www.zelix.com/klassmaster/docs/obfuscateOptions.html),
  [Gradle integration](https://www.zelix.com/klassmaster/docs/buildToolGradle.html),
  and [reproducible-output guidance](https://www.zelix.com/klassmaster/docs/tutorials/reproducibleOutputTutorial.html);
- [DashO Gradle plugins](https://support.preemptive.com/hc/en-us/articles/32032198737297-Gradle-Plugins-for-Java-Overview),
  [control-flow obfuscation](https://support.preemptive.com/hc/en-us/articles/32019801944081-Control-Flow-Obfuscation),
  and [string encryption](https://support.preemptive.com/hc/en-us/articles/32020004710033-String-Encryption).

The capability matrix must record `PASS`, `UNVERIFIED`, or `BLOCKED` for each
candidate capability. Marketing descriptions or a successful build invocation
are not sufficient evidence for virtualization, protected loading,
anti-analysis, or anti-instrumentation. The first six matrix capabilities map
to Rings 1–6; Ring 7 remains a separate release-artifact and private-recovery
classification.

### 4. Preserve reflection, resource, and native contracts narrowly

Keep rules are derived from current source and runtime boundaries, not from a
global `org.synesis.**` keep. Initial audit targets are:

- the explicit Picocli command tree and its annotation/parameter metadata;
- JSON/HTTP DTO fields and stable protocol/resource identifiers;
- entrypoints used by the CLI, MCP server, and relay applications;
- ServiceLoader registrations, if present in the selected artifact set;
- JNI/native launcher names and Netty native QUIC resources; and
- packaged UI resources and their expected classpath paths.

Third-party Netty/native artifacts remain third-party inputs and are not
rewritten by the Synesis protection pass unless a vendor-supported, separately
verified treatment exists. Every broad keep rule requires a written reason in
the keep-rule inventory and a regression test.

### 5. Reuse the installer integrity boundary and add protected provenance

The existing signed release manifest, payload SHA-256 manifest, immutable
payload layout, stable activation, launcher checks, and doctor path remain the
authoritative installation boundary. The protected pipeline adds a canonical
protected-artifact manifest and a private release record containing:

```text
source commit and dirty-tree result
release ID and platform
JDK, Gradle, Node, native toolchain, and protector versions
complete protected configuration and release-specific seed
owned artifact hashes and signed-manifest provenance
private JVM mapping/retrace data
private native debug symbols
commercial protector metadata required for support/recovery
```

Private records never enter the customer bundle, repository, logs, or normal
runtime state. Mutable project/user state is not included in the immutable
payload manifest. Tamper handling is fail-safe and diagnostic: detect,
classify, and refuse the protected operation or installation; never delete
files, kill unrelated processes, or modify the host.

### 6. Treat anti-analysis as a safety policy, not hostile surveillance

VM presence alone is not a failure. Synesis must continue to work in ordinary
VMware, Hyper-V, Parallels, cloud-VM, VDI, CI, and development environments.
Only a selected protector's documented, bounded signals—such as unexpected
agents/instrumentation, protected-loader tampering, or a cryptographic
integrity failure—may contribute to an analysis-risk decision. The policy must
be multi-signal, explainable through a stable diagnostic, and limited to the
protected operation. It may refuse a high-value protected operation; it may
not fingerprint users, scan unrelated files, attack debuggers/hypervisors,
install persistence, disable security tools, or damage the host.

If the selected commercial tool exposes an optional analysis-risk or
anti-instrumentation capability, it must prove controlled normal-host,
legitimate-VM, CI, debugger/instrumentation, and tamper behavior before that
capability is enabled. No local `if (debug)`, VM-name check, or environment
variable counts.

### 7. Browser and native scope

The React frontend is treated as inspectable client code: production Vite
minification/tree-shaking, no source maps, no external runtime CDN, and
coverage in the final integrity manifest. Backend authority and crown-jewel
decisions remain server-side; no effort is spent pretending browser JavaScript
is a secret.

Synesis-owned native components retain release stripping, `-trimpath`, minimal
exports, and platform signing where supported, with private symbols retained
outside the customer artifact. Third-party native QUIC binaries are not
modified unsafely.

## Consequences

Positive consequences:

- maintainers keep a readable default build and normal debugging workflow;
- the release pipeline has one distribution and trust boundary;
- weak open-source transformations remain useful without being mislabeled;
- high-value protection receives explicit performance and compatibility
  scrutiny; and
- support can retrace protected failures from a private, release-specific
  record.

Costs and limits:

- a commercial license and a non-interactive CI installation are required for
  the requested maximum transformation and release evidence;
- protected output may be slower, larger, harder to diagnose, and more likely
  to trigger AV/EDR heuristics;
- reflection, JNI, Netty, ServiceLoader, and resource compatibility require
  narrow keep rules and installed acceptance;
- obfuscation does not make secrets safe or prevent a determined analyst from
  observing behavior; and
- until licensed tooling is exercised, the maximum profile is `PARTIAL` and
  the missing Seven Ring evidence remains explicitly blocked or partial by
  ring.

## Rejected alternatives

- Applying obfuscation to every developer build would impose an unnecessary
  productivity and diagnostic tax.
- Calling renaming, compression, reflection wrappers, or method handles
  virtualization/packing would make the security claim indefensible.
- Building a home-grown VM, packer, debugger attack, or host-surveillance
  layer would create unacceptable security, malware-detection, and maintenance
  risk.
- Creating a second manifest/installer trust system would duplicate the
  existing signed distribution boundary and increase divergence risk.
- Rejecting all VMs would break legitimate customer, CI, and development
  environments without proving analysis resistance.
