# Synesis Link

Standalone Java library for direct peer-to-peer QUIC sessions authenticated by long-term Synesis node identities.

The implementation is in progress. The active contract and durable task state are under [`docs/agent/`](docs/agent/). Direct connectivity is attempted when network conditions permit it; Synesis Link does not claim that every pair of computers can connect.

The source-only validation CLI is run with `./gradlew.bat demoCli --args="--help"`.
The two-machine procedure is [`docs/demo/FIRST_DEMO.md`](docs/demo/FIRST_DEMO.md);
unsupported networking and product capabilities are tracked in
[`docs/agent/DEFERRED.md`](docs/agent/DEFERRED.md).

## Build

```powershell
./gradlew clean check
```

Java 25 is required. Native QUIC dependencies are introduced only after the transport slice validates their platform and packaging behavior.
