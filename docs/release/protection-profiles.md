# Synesis protection profiles

Protection is an explicit release concern. The default developer build stays
readable and debuggable; no ordinary `check`, IDE build, or local bundle is
silently obfuscated.

| Profile | Intended use | Current status |
| --- | --- | --- |
| `developer` | normal development, tests, diagnosis, and ordinary artifacts | available and unchanged |
| `protection-lite` | compatibility, keep-rule, artifact, and bounded runtime acceptance | implemented with ProGuard 7.10.0, standard optimization, mixed-case renaming, and reduced source/line metadata; not a maximum security claim |
| `maximum-release` | commercial customer release with all verified Seven Rings | adapter/signing seam implemented; fail-closed until an installed, licensed, version-pinned protector integration is supplied and exercised |

## Commands

The protection tasks are opt-in and do not participate in the default
developer build.

```powershell
# Normal developer evidence
.\gradlew.bat :cli:bundleSmokeTest --dependency-verification=strict --no-configuration-cache

# Protection-lite candidate; use a real JMOD directory for the selected
# analysis library image. The runtime in the bundle remains the Java 25 image.
.\gradlew.bat :cli:protectionLiteBundleSmokeTest :cli:protectionLiteIntegrityCheck :cli:protectionLiteProvenance `
  '-PsynesisJmods=C:\path\to\jdk\jmods' `
  --dependency-verification=strict --no-configuration-cache

.\gradlew.bat :relay:protectionLiteSmokeTest :relay:protectionLiteProvenance `
  '-PsynesisJmods=C:\path\to\jdk\jmods' `
  --dependency-verification=strict --no-configuration-cache

# Maximum release requires the external adapter, private configuration,
# release-specific seed/identity, and injected signing authority.
.\gradlew.bat :cli:maximumRelease `
  '-PsynesisMaximumProtector=C:\private\bin\synesis-maximum-adapter.exe' `
  '-PsynesisMaximumConfig=C:\private\config\maximum.properties' `
  '-PsynesisReleaseId=1.0.0-windows-x64-r001' `
  '-PsynesisProtectionSeed=<release-specific-secret>' `
  '-PsynesisSigningKeyId=<key-id>' `
  '-PsynesisPublishedAt=<fixed-rfc3339-time>' `
  --dependency-verification=strict --no-configuration-cache

.\gradlew.bat :relay:maximumRelease `
  '-PsynesisMaximumProtector=C:\private\bin\synesis-maximum-adapter.exe' `
  '-PsynesisMaximumConfig=C:\private\config\maximum.properties' `
  '-PsynesisReleaseId=1.0.0-windows-x64-r001' `
  '-PsynesisProtectionSeed=<release-specific-secret>' `
  '-PsynesisSigningKeyId=<key-id>' `
  '-PsynesisPublishedAt=<fixed-rfc3339-time>' `
  --dependency-verification=strict --no-configuration-cache
```

The adapter request/result schema and private-evidence requirements are
documented in [maximum-protector-adapter.md](maximum-protector-adapter.md).
The current source-scoped Tier 0–3 boundary is recorded in
[protection-tier-inventory.md](protection-tier-inventory.md), and the
vendor-neutral configuration skeleton is
[maximum-protector-config.template.properties](maximum-protector-config.template.properties).
No adapter, commercial configuration, release seed, or signing secret is
provided in this checkout, so this command remains expected to fail closed.

The CLI lite output is under `cli/build/protection-lite/`; the standalone
relay lite output is under `relay/build/protection-lite/`:

- the staged bundle and its ZIP are candidate shipped artifacts;
- `PROTECTION_PROFILE` marks the bundle as `protection-lite`;
- `private/mapping.txt`, `private/seeds.txt`, `private/usage.txt`,
  `private/provenance.json`, and `private/artifact-manifest.txt` are support/
  retrace material and must not be copied
  into a customer bundle; and
- the lite provenance record binds the source commit, dirty-tree state,
  protector, JDK library image, rules hash, seed, artifact hash, and archive
  hash; the maximum private record additionally binds the complete lockfile
  snapshot, Gradle/Java/Node/native toolchains, protector configuration digest,
  release seed, and signing/public-key provenance.

The current free hardening also enables standard ProGuard optimization and
mixed-case renaming, and stops retaining source-file and line-number
attributes in the transformed application rules. The archive comparison shows
fewer internal classes and fewer source-metadata signals, but metadata remains
in dependency/application entries and no Seven Ring is promoted by this
profile.

The current local run used ProGuard 7.10.0 with Java 25 as the execution
runtime and an explicit Java 21 JMOD directory as the analysis library image.
That fallback is recorded in provenance and is suitable for compatibility
investigation only. Release CI must provide a matching, approved Java library
image rather than silently relying on a developer machine.

`maximum-release` is not an alias for lite. It must fail closed when the
licensed protector adapter, its configuration, private recovery records, final
manifest/signature, and post-protection acceptance evidence are absent. The
maximum task also requires a clean reviewed release checkout; preserved local
UI work or unrelated developer edits must remain outside the release checkout
rather than being silently included in provenance. The
Gradle seam signs the candidate through the existing bootstrap signer and
verifies the compiled bootstrap trust root; it does not make the commercial
ring claims without the later artifact and installed-runtime acceptance.

The post-build archive boundary is exercised by
`scripts/maximum-release-acceptance.ps1`. It is intentionally separate from
the Gradle adapter: a successful adapter invocation alone cannot claim that
the shipped archive starts, serves its packaged UI/control plane, preserves
provider/MCP/native boundaries, or passes relay acceptance.
