# Build and test

Requirements: Java 25, the Gradle Wrapper, and Go 1.26.5 for bootstrapper work.

```powershell
.\gradlew.bat check --dependency-verification=strict
```

`check` intentionally covers Java verification only. It does not assemble or
smoke-test release distributions. Run the explicit distribution checks when
working on packaging or release artifacts:

```powershell
.\gradlew.bat :cli:distributionCheck --dependency-verification=strict
```

Gradle keeps its daemon, parallel execution, configuration cache, build cache,
and file-system watching enabled by default in this repository. Avoid `clean`
for iterative builds because it discards the outputs those caches can reuse.

The Go module is `bootstrap`; run its checks from that directory:

```powershell
Push-Location bootstrap
go test -count=1 ./...
go vet ./...
Pop-Location
```

Build the local application distribution with
`.\gradlew.bat :cli:installDist`. The generated launcher is under
`cli/build/install/synesis/bin/`. Run `synesis --help`, `synesis version`, and
`synesis provider list` as smoke checks. Do not treat a synthetic provider check
as real-agent evidence.
