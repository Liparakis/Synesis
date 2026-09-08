# SYN-052 local control-plane evidence — 2026-09-08

## Scope

This record covers the local control-plane foundation over the existing
coordination listener. It does not claim frontend, public/cloud, physical
network, or live production overlay/relay-owner acceptance.

## Real CLI smoke

Starting repository commit: `d2eaf639494f7132231810bff9aff945523a9991`.

A disposable Git repository was initialized at:

`C:\t\synesis-control-plane-acceptance-20260908`

The installed CLI initialized it successfully and created project ID:

`c579908b-ad6d-4481-840b-b43f29cc2fdb`

The real command was run with an ephemeral port and one-second bounded
duration, using a process-local temporary directory for the known Windows
loopback workaround:

```text
synesis coordination serve --project . --duration-seconds 1 --port 0
```

Observed ready output, with the one-time bootstrap secret intentionally
omitted:

```text
COORDINATION_SERVE_READY endpoint=http://127.0.0.1:62573/ project=c579908b-ad6d-4481-840b-b43f29cc2fdb nodeId=<redacted> hostInstanceId=<redacted> codexLifecycleRoute=/codex-lifecycle/v1 controlPlaneRoute=/api/v1 controlBootstrap=<redacted>
```

The process exited successfully after the bounded duration.

## Live authenticated query

A second real `coordination serve` process was started on ephemeral port
`54794` with the same disposable project. A separate HTTP client then called
the live process:

```text
GET /api/v1/health                         -> 200
POST /api/v1/session                       -> 201
GET /api/v1/snapshot                       -> 200
```

The observed safe result was:

```json
{"healthStatus":200,"sessionExpires":true,"snapshotStatus":200,"apiVersion":"v1","projectId":"c579908b-ad6d-4481-840b-b43f29cc2fdb","agentCount":0,"networkStatus":"UNCONFIGURED"}
```

No bootstrap or session token is recorded here.

`UNCONFIGURED` is expected for this checkout: source re-investigation found
Link overlay/relay implementations but no production long-lived owner that
composes their live views into `coordination serve`. The control adapter keeps
that source explicit and does not fabricate peer, topology, route, or relay
state.

## Focused verification

Passing checks:

```text
:workspace:test --tests org.synesis.workspace.transport.control.*
:coordination:compileJava
:workspace:compileJava
:cli:compileJava
:coordination:javadoc
:workspace:javadoc
:cli:javadoc
:link:check
:relay:check
scripts/agent-validate-deferred.ps1
git diff --check
```

The focused class includes a real two-profile HTTP invite, join, answer, and
connect flow over the existing Link `Onboarding` implementation; it passed.

The combined `:coordination:test :workspace:test :cli:test` run reported
failures in unrelated existing CLI/workspace fixture tests. Those failures are
recorded in the active task state as unclassified; no full-suite pass is
claimed from this slice.
