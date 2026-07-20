# Synesis

Synesis is a modular-monolith repository. The first implemented module is
[`link/`](link/), the Synesis Link transport and authenticated session layer.

Link provides local-first direct peer-to-peer QUIC sessions, long-term node
identity binding, bounded control/liveness behavior, candidate selection, and
a demo-only authenticated work exchange. It does not claim that every pair of
computers can connect directly.

The Link implementation and tests live under `link/`; the standalone terminal
CLI and development distribution live under `cli/`.

## Build

```powershell
./gradlew.bat clean check --dependency-verification=strict
```

Java 25 is required. Build the development distribution with:

```powershell
./gradlew.bat :cli:installDist --dependency-verification=strict
& ".\cli\build\install\synesis\bin\synesis.bat" --help
& ".\cli\build\install\synesis\bin\synesis.bat" --version
& ".\cli\build\install\synesis\bin\synesis.bat" identity show
```

For the current PowerShell session only, optionally prepend the generated
launcher directory to PATH:

```powershell
$env:Path = "$(Resolve-Path .\cli\build\install\synesis\bin);$env:Path"
synesis --help
```

The physical demonstration procedure is [`docs/demo/FIRST_DEMO.md`](docs/demo/FIRST_DEMO.md).
Unsupported networking and product capabilities are tracked in
[`docs/agent/DEFERRED.md`](docs/agent/DEFERRED.md).

The generated launcher owns zero-configuration onboarding:

```powershell
synesis host
synesis join "synesis://join/<share-link>"
synesis doctor
```

Quote the full invitation as one argument on Windows. `DemoCli` remains a
documented Gradle-only diagnostic fallback. Generated-launcher onboarding is
two-process tested; physical launcher onboarding is not claimed until recorded
in [`docs/evidence/PHYSICAL-CLI-ONBOARDING.md`](docs/evidence/PHYSICAL-CLI-ONBOARDING.md).
