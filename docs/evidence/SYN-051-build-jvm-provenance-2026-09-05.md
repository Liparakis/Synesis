# SYN-051 build-JVM compatibility and provenance

Date: 2026-09-05  
Classification: **PASS-A for the bounded build/provenance slice; runtime not run**

## Source and environment

- Starting repository HEAD: `bcccad2fd339ba4fcd916c50d6fbd9b2ec057586`.
- Every post-`a7697bbb5de83ced8b61b275056f9204e4467fbc` change is documentation,
  evidence, or checkpoint-only. Runtime source remains `a7697bbb...`.
- JDK25: `C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot`, x64,
  Temurin `25+36-LTS`.
- The successful command-local environment was `TEMP=C:\t`, `TMP=C:\t`,
  `GRADLE_OPTS=-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`.
  The wrapper used `--no-daemon --max-workers=1`.
- User/system environment checks found no persistent `JAVA_TOOL_OPTIONS`,
  `GRADLE_OPTS`, `JDK_JAVA_OPTIONS`, or `_JAVA_OPTIONS`. Repository
  `gradle.properties` was unchanged.

## Verification

`gradlew --version --no-daemon` passed, then `gradlew help --no-daemon
--max-workers=1` executed successfully. The focused workspace and MCP test
selections compiled but stalled after reaching their test tasks without
assertion output; both were stopped after bounded waits and are incomplete
evidence, not passes. Deferred validation passed.

The clean command passed:

```text
.\gradlew.bat clean :cli:installDist --no-daemon --max-workers=1 --console=plain
```

It completed in 32 seconds with `BUILD SUCCESSFUL`, including
`:cli:installDist` and `:cli:nativeMcpLauncher`. Configuration-cache reported
one existing serialization warning for `:cli:nativeMcpLauncher`; this did not
fail the build.

## Artifact provenance

| Produced artifact                                   | SHA-256                                                            | Installed artifact                                           | SHA-256                                                            |
|-----------------------------------------------------|--------------------------------------------------------------------|--------------------------------------------------------------|--------------------------------------------------------------------|
| `workspace/build/libs/workspace-0.1.0-SNAPSHOT.jar` | `af41102bb20b03d87c76c3dd8547840905d16f0338443ec039253e6c56248f6a` | `cli/build/install/synesis/lib/workspace-0.1.0-SNAPSHOT.jar` | `af41102bb20b03d87c76c3dd8547840905d16f0338443ec039253e6c56248f6a` |
| `mcp/build/libs/mcp-0.1.0-SNAPSHOT.jar`             | `dddc6df0decc0530dc30d6809ded118b9cf0e801ad7238b73aa53ab90bae0bfd` | `cli/build/install/synesis/lib/mcp-0.1.0-SNAPSHOT.jar`       | `dddc6df0decc0530dc30d6809ded118b9cf0e801ad7238b73aa53ab90bae0bfd` |
| `cli/build/libs/cli-0.1.0-SNAPSHOT.jar`             | `438fbea4ffbf739a530233814db44b53f928f626b3b48b10c263ac6312ed2467` | `cli/build/install/synesis/lib/cli-0.1.0-SNAPSHOT.jar`       | `438fbea4ffbf739a530233814db44b53f928f626b3b48b10c263ac6312ed2467` |
| `cli/build/native-mcp/windows-x64/synesis-mcp.exe`  | `21c07b3ee2653fc262e150ed92144adceed7f67dd48e46f78edd24918ed3d8eb` | `cli/build/install/synesis/bin/synesis-mcp.exe`              | `21c07b3ee2653fc262e150ed92144adceed7f67dd48e46f78edd24918ed3d8eb` |

Installed `synesis.bat` hash: `b8bcb137eb83659360f332c6ca6c17b328300844f83f28f82977cdec56c0670e`.
The installed version reports `BUILD_COMMIT=UNKNOWN` and
`JAVA_RUNTIME=25+36-LTS`.

## Scope boundary

Target `C:\Users\Liparakis\Desktop\SkibidiToilert` remained on `main` at
`8cf929c4def2a5d900f654c5b99d9ebef8bc972e`, with only its pre-existing
untracked `probe-runtime/` directory. No Worker A, `prepareFirst`, START,
managed provider, App Server, MCP runtime, credentials, or `.synesis` state was
accessed or changed. No push occurred.

The next separately bounded action is the fresh single-worker runtime probe
using the same process-local AF_UNIX property. It is authorized by this
successful PASS-A build/provenance result, but was not performed here.
