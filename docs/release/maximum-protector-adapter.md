# Maximum-protector adapter contract

The repository does not contain a commercial protector. The maximum-release
Gradle task therefore accepts one explicit release-environment adapter and
fails closed when it is absent. The adapter is a wrapper supplied by the
licensed release environment; it owns the vendor-specific DashO, Zelix, or
later approved protector invocation. The Gradle task does not implement a
home-grown obfuscator, virtual machine, packer, anti-debugger, or host
surveillance layer.

## Invocation

For the CLI candidate, run:

```powershell
.\gradlew.bat :cli:maximumRelease `
  '-PsynesisMaximumProtector=C:\private\bin\synesis-maximum-adapter.exe' `
  '-PsynesisMaximumConfig=C:\private\config\maximum.properties' `
  '-PsynesisReleaseId=1.0.0-windows-x64-r001' `
  '-PsynesisProtectionSeed=<release-specific-secret>' `
  '-PsynesisSigningKeyId=<key-id>' `
  '-PsynesisPublishedAt=<fixed-rfc3339-time>' `
  --dependency-verification=strict --no-configuration-cache
```

For the standalone relay candidate, use the same injected values with
`:relay:maximumRelease`. The relay task consumes the readable
`relay/build/install/synesis-relay` distribution and produces its own protected
archive, private record, and detached manifest/signature.

The same values may be provided through `SYNESIS_MAXIMUM_PROTECTOR`,
`SYNESIS_MAXIMUM_CONFIG`, `SYNESIS_RELEASE_ID`,
`SYNESIS_PROTECTION_SEED`, `SYNESIS_MANIFEST_SIGNING_KEY_ID`, and
`SYNESIS_RELEASE_PUBLISHED_AT`. The production private signing key is supplied
only through `SYNESIS_MANIFEST_PRIVATE_KEY_B64`. None of these values belong in
the repository or the customer bundle.

The maximum task also requires a reviewed customer-style developer archive for
the post-protection native comparison through
`SYNESIS_DEVELOPER_ARCHIVE` or `-PsynesisDeveloperArchive`. This is a release
input path, not a secret, and it is never copied into the customer bundle.

The adapter is invoked without a shell as:

```text
<adapter> --synesis-request <build>/maximum-release/request.properties
```

On Windows, `.cmd` and `.bat` adapters are invoked through `cmd.exe`; an
executable adapter is preferred. Gradle discards adapter output so that vendor
license paths, credentials, or private protector diagnostics are not copied
into ordinary build logs.

The maximum task must run from a reviewed clean release commit. A dirty
developer checkout is rejected before adapter invocation; the request still
records the source commit and dirty-tree state so a release record cannot
silently lose that provenance.

## Request

The request is a Java `.properties` file with `schema=1`, `profile=maximum-release`,
and `component=cli` or `component=relay`. It contains the exact source
commit/dirty-tree state,
release ID, release seed, platform, readable developer input bundle, requested
customer output directory, private-record directory, configuration path, and
the six vendor-transformation ring names:

```text
requiredRings=controlFlow,virtualization,strings,analysisEnvironment,protectedPayload,antiDebug
nativeSymbolsScope=owned (cli)
nativeSymbolsScopePolicy=owned-or-not-applicable (relay)
```

The signed-integrity layer is deliberately not an adapter-reported ring. The
Gradle release task owns that boundary: it creates the private per-file
artifact manifest, archives the customer bundle, creates the canonical release
manifest, invokes the existing bootstrap signer, and verifies the detached
Ed25519 signature against the embedded bootstrap trust root. The private
release record receives `signedIntegrity=verified` only after that verification
passes. This keeps a vendor result from turning a claimed integrity setting
into evidence.

It also supplies absolute paths to the current source-scoped inventories:

```text
tierInventory=<docs/release/protection-tier-inventory.md>
tierInventorySha256=<sha256 of that inventory>
keepRuleInventory=<docs/release/protection-keep-rules.md>
keepRuleInventorySha256=<sha256 of that inventory>
acceptanceProcedure=<docs/release/protected-acceptance.md>
acceptanceProcedureSha256=<sha256 of that procedure>
```

The adapter must bind its vendor configuration and result to all three
source-scoped inventory/procedure digests; the Gradle gate rejects a result
that does not return the same values. The vendor configuration may be generated from
`maximum-protector-config.template.properties`, but that file is only a
vendor-neutral skeleton and is not itself a licensed protector configuration.

The adapter must not modify the input bundle or any source checkout. It must
write the protected customer bundle to `outputBundle`, private support material
to `privateDirectory`, and the result below to the request directory as
`result.properties`.

## Result

The result is accepted only when all of the following are true:

```text
schema=1
status=success
profile=maximum-release
component=<the request component, cli or relay>
releaseId=<same release ID as the request>
sourceCommit=<same source commit as the request>
dirtyTree=false
seed=<same release seed as the request>
protectorName=<exact vendor/product>
protectorVersion=<exact pinned version>
tierInventorySha256=<same digest as the request>
keepRuleInventorySha256=<same digest as the request>
acceptanceProcedureSha256=<same digest as the request>
lockfileCount=<same count as the request>
lockfilesSha256=<same digest as the request>
gradleVersion=<same version as the request>
javaRuntime=<same runtime as the request>
javaToolchain=<same toolchain as the request>
nodeVersion=<same version as the request>
npmVersion=<same version as the request>
nativeToolchain=<same version as the request>
configurationSha256=<same digest as the request>
ring.controlFlow=verified
ring.virtualization=verified
ring.strings=verified
ring.analysisEnvironment=verified
ring.protectedPayload=verified
ring.antiDebug=verified
diversification=verified
diversificationEvidence=<non-empty file below privateDirectory>
bundleDirectory=<the requested outputBundle>
privateDirectory=<the requested privateDirectory>
retraceFile=<file below privateDirectory>
mappingFile=<file below privateDirectory>
nativeSymbolsScope=owned (cli) or owned/not-applicable (relay, based on the shipped output)
nativeSymbolsDirectory=<directory below privateDirectory, required only when nativeSymbolsScope=owned; otherwise not-applicable>
thirdPartyNativeAudit=<non-empty audit file below privateDirectory for every shipped native dependency>
evidence.controlFlow=<file below privateDirectory>
evidence.virtualization=<file below privateDirectory>
evidence.strings=<file below privateDirectory>
evidence.analysisEnvironment=<file below privateDirectory>
evidence.protectedPayload=<file below privateDirectory>
evidence.antiDebug=<file below privateDirectory>
```

The adapter result therefore proves the six vendor transformation classes,
release diversification, and non-empty private evidence for each of those
claims. The Gradle task hashes every private ring/diversification evidence
file into `release-record.properties`, and requires non-empty mapping and
retrace material, owned native-symbol recovery when the component owns native
launchers, and a third-party-native audit for shipped dependency binaries. The
signed-integrity layer is proved by
the later Gradle/bootstrap manifest verification and is recorded separately in
the private release record.

The maximum request also carries a reproducibility snapshot for the exact
source checkout: the sorted lockfile digest/count, Gradle version, Java
runtime/toolchain, Node/npm versions, native Go toolchain, and protector
configuration digest. The adapter result must echo that snapshot and the
inventory/procedure digests before the task accepts it. The private
`release-record.properties` repeats those
values together with the release ID, seed, protector identity, artifact
manifest hashes, and—after signing—the signing key ID, fixed publication time,
SHA-256 of the bootstrap public key, and detached-signature provenance. A
missing Node, npm, or Go toolchain fails the maximum provenance gate; values are
never copied into the customer bundle.

After the protected archive is created, the Gradle task runs
`scripts/release-native-hardening-audit.ps1` against the developer and maximum
customer archives before it creates the signed release manifest. Its private
JSON evidence is hashed into the final release record as
`privateNativeHardeningEvidence`. CLI native signing must verify; relay records
native signing as not-applicable only when its shipped output has no
Synesis-owned native binary. Relay's third-party native dependency audit remains
separate and is also required.

The Gradle gate additionally requires the protected profile marker, the
customer CLI/runtime/native launcher files for `cli`, or the protected relay
launcher/application JARs for `relay`, with no mappings/seeds/private records,
and a private SHA-256 manifest of every bundle file. For relay, Netty QUIC
native JARs are third-party inputs: they remain unmodified and are covered by
the private audit rather than by a fabricated Synesis native-symbol directory.
A result property is not
itself proof that a ring is real: the evidence files and the later shipped
artifact/installed-runtime acceptance must demonstrate the vendor's actual
transformation and safe behavior. In particular, renaming is not accepted as
control-flow protection, compression is not accepted as packing, and a VM-name
check is not accepted as a complete analysis policy.

## Release-to-release provenance comparison

The adapter's `diversification=verified` result is necessary but does not by
itself prove that two protected outputs differ or that repeated inputs
reproduce. After two clean licensed releases exist, run
`scripts/maximum-release-provenance-comparison.ps1` in `Diversification` mode
with different release IDs/seeds, and in `Reproducibility` mode with the same
release ID/seed and otherwise identical provenance. The harness compares the
canonical private artifact manifests, not ZIP timestamps, and emits only
manifest hashes plus one-way seed fingerprints. Store its evidence outside the
customer bundle and keep the full mapping/retrace/native recovery and
third-party-native audit material in the private release directory.

## Signing and private recovery

After each adapter output is validated, Gradle creates a per-component,
per-platform release manifest, invokes the existing
`bootstrap/cmd/sign-manifest` signer, and verifies the detached Ed25519
signature against the public key embedded in `bootstrap/main.go`. The
candidate archive, manifest, and signature are the only customer-facing
outputs. Mappings, retrace information, owned native symbols when applicable,
third-party-native audit, native-hardening evidence, ring evidence,
configuration, seed, and provenance remain under the private release directory.

The current task is a release seam, not maximum-profile acceptance. Acceptance
still requires inspection of the exact protected artifact and installed CLI,
UI/control plane, Link/overlay, relay, provider/native, tamper, legitimate-VM,
debugger/instrumentation, performance, and memory behavior. Until a real
licensed adapter produces that evidence, `maximum-release` remains blocked.
