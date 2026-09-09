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

The same values may be provided through `SYNESIS_MAXIMUM_PROTECTOR`,
`SYNESIS_MAXIMUM_CONFIG`, `SYNESIS_RELEASE_ID`,
`SYNESIS_PROTECTION_SEED`, `SYNESIS_MANIFEST_SIGNING_KEY_ID`, and
`SYNESIS_RELEASE_PUBLISHED_AT`. The production private signing key is supplied
only through `SYNESIS_MANIFEST_PRIVATE_KEY_B64`. None of these values belong in
the repository or the customer bundle.

The adapter is invoked without a shell as:

```text
<adapter> --synesis-request <build>/maximum-release/request.properties
```

On Windows, `.cmd` and `.bat` adapters are invoked through `cmd.exe`; an
executable adapter is preferred. Gradle discards adapter output so that vendor
license paths, credentials, or private protector diagnostics are not copied
into ordinary build logs.

## Request

The request is a Java `.properties` file with `schema=1`, `profile=maximum-release`,
and `component=cli`. It contains the exact source commit/dirty-tree state,
release ID, release seed, platform, readable developer input bundle, requested
customer output directory, private-record directory, configuration path, and
the required ring names:

```text
requiredRings=controlFlow,virtualization,strings,analysisEnvironment,protectedPayload,antiDebug
```

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
component=cli
protectorName=<exact vendor/product>
protectorVersion=<exact pinned version>
ring.controlFlow=verified
ring.virtualization=verified
ring.strings=verified
ring.analysisEnvironment=verified
ring.protectedPayload=verified
ring.antiDebug=verified
diversification=verified
bundleDirectory=<the requested outputBundle>
privateDirectory=<the requested privateDirectory>
retraceFile=<file below privateDirectory>
nativeSymbolsDirectory=<directory below privateDirectory>
evidence.controlFlow=<file below privateDirectory>
evidence.virtualization=<file below privateDirectory>
evidence.strings=<file below privateDirectory>
evidence.analysisEnvironment=<file below privateDirectory>
evidence.protectedPayload=<file below privateDirectory>
evidence.antiDebug=<file below privateDirectory>
```

The Gradle gate additionally requires the protected profile marker, the
customer CLI/runtime/native launcher files, no mappings/seeds/private records,
and a private SHA-256 manifest of every bundle file. A result property is not
itself proof that a ring is real: the evidence files and the later shipped
artifact/installed-runtime acceptance must demonstrate the vendor's actual
transformation and safe behavior. In particular, renaming is not accepted as
control-flow protection, compression is not accepted as packing, and a VM-name
check is not accepted as a complete analysis policy.

## Signing and private recovery

After the adapter output is validated, Gradle creates a per-platform release
manifest, invokes the existing `bootstrap/cmd/sign-manifest` signer, and
verifies the detached Ed25519 signature against the public key embedded in
`bootstrap/main.go`. The candidate archive, manifest, and signature are the
only customer-facing outputs. Mappings, retrace information, native symbols,
ring evidence, configuration, seed, and provenance remain under the private
release directory.

The current task is a release seam, not maximum-profile acceptance. Acceptance
still requires inspection of the exact protected artifact and installed CLI,
UI/control plane, Link/overlay, relay, provider/native, tamper, legitimate-VM,
debugger/instrumentation, performance, and memory behavior. Until a real
licensed adapter produces that evidence, `maximum-release` remains blocked.
