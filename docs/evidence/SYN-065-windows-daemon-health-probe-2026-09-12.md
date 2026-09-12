# SYN-065 — Windows daemon health-probe compatibility

Date: 2026-09-12
Scope: fix the installed `synesis ui` request rejection caused by the daemon's
local runtime health probe on this Windows/JDK environment.

## Diagnosis

The daemon-managed Test runtime was alive and returned HTTP 200 from
`http://127.0.0.1:<runtime-port>/api/v1/health`. The daemon's original Java
`HttpClient` probe failed with `java.io.UncheckedIOException:
java.io.IOException: Unable to establish loopback connection`. The same URL
returned HTTP 200 through `HttpURLConnection`; setting the JDK Unix-domain
socket temporary directory also made `HttpClient` succeed. This isolates the
failure to the JDK probe transport/configuration rather than the project
runtime or authenticated UI request.

## Implementation

`DaemonServer.ProcessRuntimeHandle.reachable()` now delegates to a bounded
`HttpURLConnection` GET of `/api/v1/health`. It preserves the existing 500 ms
connect timeout, 1 second read bound, HTTP-200 requirement, and false-on-IO
failure behavior. The authenticated daemon protocol, lifecycle ownership,
project registry, browser route, Link state, and project files are unchanged.

## Verification

- Added `DaemonServerTest.recognizesHealthyLoopbackRuntimeThroughTheJdkCompatibleProbe`.
- Focused `org.synesis.cli.daemon.DaemonServerTest`: PASS.
- `:cli:installDist` with the process-local JDK socket-path workaround: PASS.
- `:cli:runnableInstaller` with the same workaround: PASS.
- Supported Windows installer `repair`: `REPAIR_RESULT=SUCCESS`.
- From `C:\Users\Liparakis\Desktop\Test`, installed `synesis ui
  --no-browser --project .`: PASS, no output/error.
- From the same directory, installed `synesis ui --project .`: PASS,
  `SYNESIS_UI_OPENED`.
- The broader `:cli:test` invocation was bounded and interrupted after it
  produced no completion or failure output; no failure was attributed to this
  change.

No commit or push was made.
