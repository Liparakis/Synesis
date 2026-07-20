# Synesis

Synesis is a modular-monolith repository. The first implemented module is
[`link/`](link/), the Synesis Link transport and authenticated session layer.

Link provides local-first direct peer-to-peer QUIC sessions, long-term node
identity binding, bounded control/liveness behavior, candidate selection, and
a demo-only authenticated work exchange. It does not claim that every pair of
computers can connect directly.

The Link implementation and tests live under `link/`; the root Gradle build
delegates verification to that subproject.

## Build

```powershell
./gradlew.bat clean check --dependency-verification=strict
```

Java 25 is required. Link-only tasks use the `:link:` path, for example:

```powershell
./gradlew.bat :link:demoCli --args="--help"
```

The physical demonstration procedure is [`docs/demo/FIRST_DEMO.md`](docs/demo/FIRST_DEMO.md).
Unsupported networking and product capabilities are tracked in
[`docs/agent/DEFERRED.md`](docs/agent/DEFERRED.md).
