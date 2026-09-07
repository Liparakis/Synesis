# SYN-051 process-local preflight and clean-build blocker

Date: 2026-09-05
Classification: **PARTIAL — STOPPED BEFORE FRESH WORKER-A CREATION**

## Completed gates

- The compatibility evidence was committed as `bff97418672e2197cd00f293409d68446918bdd8`.
- JDK25 is Temurin `25+36-LTS` at
  `C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe`.
- With the process-local argument
  `-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`,
  `Selector.open()` passed.
- With the same argument, minimal `HttpServer.create/start/stop` passed for
  both `127.0.0.1` and `::1`.
- `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, and `_JAVA_OPTIONS` were absent from
  the invoking process. No global Java, network, Codex, or production setting
  was changed.
- Target identity remained `C:\Users\Liparakis\Desktop\SkibidiToilert`,
  branch `main`, commit
  `8cf929c4def2a5d900f654c5b99d9ebef8bc972e`. Its pre-existing untracked
  `probe-runtime/` directory was not edited.

## Material stop

The required clean rebuild/install command was attempted without propagating
the diagnostic property into build logic:

```text
.\gradlew.bat clean :cli:installDist --no-daemon --max-workers=1 --console=plain
```

The Gradle/JDK process failed before task execution with:

```text
java.io.IOException: Unable to establish loopback connection
```

This is an independent Java-process reproduction of the AF_UNIX loopback
failure. The request requires stopping rather than broadening the workaround
to Gradle, build logic, or global settings. Because the clean build did not
complete, the previously recorded installed hashes are not freshly
re-established against the intended source in this slice.

## Runtime boundary not reached

No fresh Binding A, Participant A, WorkIntent A, claim, launcher, `prepareFirst`,
START, attachment, App Server, MCP, Job, provider thread, turn, persistence
transition, or target mutation was created or executed in this slice. No
historical lane was reused. No credentials or raw proof were read, copied,
logged, or modified. Worker B, A2, and replacement were not invoked.

The existing installed artifact hashes observed before the failed build were:

| Artifact                         | SHA-256                                                            |
|----------------------------------|--------------------------------------------------------------------|
| workspace JAR and installed copy | `af41102bb20b03d87c76c3dd8547840905d16f0338443ec039253e6c56248f6a` |
| MCP JAR and installed copy       | `dddc6df0decc0530dc30d6809ded118b9cf0e801ad7238b73aa53ab90bae0bfd` |
| CLI JAR and installed copy       | `770c7df632d07481365e6177fc1559eb2ae8568a9c44619319d447d9b29c5c58` |
| installed `synesis.bat`          | `b8bcb137eb83659360f332c6ca6c17b328300844f83f28f82977cdec56c0670e` |

These are observations only, not a new provenance lock.

## Exact next action

Obtain separate authorization for a bounded investigation or process-local
compatibility path for the build/Gradle JVM itself, without changing global
settings or production launch semantics; then perform a fresh clean
build/install and provenance lock before creating any new Worker-A lane.
