# Synesis protection profiles

Protection is an explicit release concern. The default developer build stays
readable and debuggable; no ordinary `check`, IDE build, or local bundle is
silently obfuscated.

| Profile | Intended use | Current status |
| --- | --- | --- |
| `developer` | normal development, tests, diagnosis, and ordinary artifacts | available and unchanged |
| `protection-lite` | compatibility, keep-rule, artifact, and bounded runtime acceptance | implemented with ProGuard 7.10.0; not a maximum security claim |
| `maximum-release` | commercial customer release with all verified Seven Rings | fail-closed until an installed, licensed, version-pinned protector integration is supplied |

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

# Maximum release intentionally stops until the commercial integration exists.
.\gradlew.bat :cli:maximumRelease -PsynesisMaximumProtector=<licensed-integration>
```

The CLI lite output is under `cli/build/protection-lite/`; the standalone
relay lite output is under `relay/build/protection-lite/`:

- the staged bundle and its ZIP are candidate shipped artifacts;
- `PROTECTION_PROFILE` marks the bundle as `protection-lite`;
- `private/mapping.txt`, `private/seeds.txt`, `private/usage.txt`,
  `private/provenance.json`, and `private/artifact-manifest.txt` are support/
  retrace material and must not be copied
  into a customer bundle; and
- the provenance record binds the source commit, dirty-tree state, protector,
  JDK library image, rules hash, seed, artifact hash, and archive hash.

The current local run used ProGuard 7.10.0 with Java 25 as the execution
runtime and an explicit Java 21 JMOD directory as the analysis library image.
That fallback is recorded in provenance and is suitable for compatibility
investigation only. Release CI must provide a matching, approved Java library
image rather than silently relying on a developer machine.

`maximum-release` is not an alias for lite. It must fail closed when the
licensed protector, its configuration, private recovery records, final
manifest/signature, and post-protection acceptance evidence are absent.
