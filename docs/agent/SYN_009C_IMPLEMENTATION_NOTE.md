# SYN-009C implementation note

## Starting point

- Commit: `2d39188 Implement SYN-009B.1 Codex hook integration`
- Checkpoint: CP-0105
- Host: Windows 11 amd64
- Java: Temurin 25.0.0.36
- Gradle: 9.0.0
- Application module: `:cli`
- Existing distribution: Gradle Application `:cli:installDist`, with Java and
  Gradle assumed by the generated launchers.

## Selected architecture

`:cli` remains the only Java composition root. A Gradle task builds a native
platform bundle containing the CLI jars, a `jlink` runtime, a relative-path
launcher, and bounded metadata. A small Go standard-library bootstrapper owns
only artifact download/verification and versioned installation state. CI builds
the six platform identifiers and aggregates a detached-signed manifest.

No new Java module, server, database, plugin runtime, or remote publication
is introduced.

## Runtime/module audit

`jdeps --multi-release 25 --ignore-missing-deps --recursive --print-module-deps`
over the installed CLI classpath reports:

```text
java.base,java.logging,java.naming,jdk.jfr,jdk.unsupported
```

The clean-room Java gate completed with 39 actionable tasks. The Windows x64
development jlink runtime measured 34,388,202 bytes; archives measured
25,825,206 bytes (ZIP) and 25,591,351 bytes (tar.gz). The bundled runtime was
executed by the bundle smoke test.

The Netty native QUIC library remains inside the application archive and is
built on the target operating system. A single jlink image is not reused
across operating systems or architectures.

## Artifact matrix

| Platform | Bundle | Bootstrap | Native smoke |
|---|---|---|---|
| windows-x64 | native runner | native runner | local/CI |
| windows-arm64 | native ARM runner | native/cross | CI runner required |
| linux-x64 | native runner | native runner | CI |
| linux-arm64 | native ARM runner | native/cross | CI runner required |
| macos-x64 | native Intel runner | native/cross | CI runner required |
| macos-arm64 | native ARM runner | native/cross | CI |

Unavailable native runners are reported as `CROSS_COMPILED_ONLY` or
`NOT_SUPPORTED_BY_RUNNER`; they are not called smoke-tested.

## Installation/trust model

The bootstrapper selects a platform entry from a bounded manifest, verifies a
detached Ed25519 signature over the exact manifest bytes, verifies artifact
size and SHA-256, extracts into a bounded staging directory, runs the bundled
`synesis version`, and atomically activates a versioned installation. Production
signing keys are CI secrets only. Local unsigned manifests are development-only.

## Release workflow

Pull requests and branches build/test and produce unsigned verification
artifacts. Protected `v*` tags may sign and prepare artifacts; publication stays
manual and is not performed by this task. The CI matrix creates Java bundles,
bootstrap binaries, checksums, and the detached manifest after all artifacts
exist.

## Risks and deferred items

- Go 1.26.5 is verified with a temporary toolchain on D:; a native Windows
  bootstrap binary, six cross-compiles, `go test ./...`, and `go vet ./...`
  pass. CI also runs the opt-in native Linux subprocess smoke.
- Windows ARM, Linux ARM, and macOS Intel native execution require their target
  runners.
- Authenticode, Apple Developer ID signing, and notarization remain deferred.
- The Codex real-agent `/hooks` trust gate remains separate and incomplete.
- No public URL or release is created; install scripts use configurable
  endpoints only.
