# Build and test

Requirements: Java 25, the Gradle Wrapper, and Go 1.26.5 for bootstrapper work.

```powershell
.\gradlew.bat check --no-daemon
```

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
