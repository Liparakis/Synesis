# ADR-0073: Installed local daemon shell

## Status

Accepted for the bounded SYN-056 implementation slice.

## Context

The installed browser UI currently starts one project-local loopback control
plane in `CoordinationServerLauncher`. `KnownProjectRegistry` already persists
installation-local discovery metadata, while `ControlPlaneHttpHandler` owns
one-time browser bootstrap, session exchange, Origin/Host checks, CSRF, and
Link onboarding adapters. There is no global long-lived daemon or authoritative
runtime registry in the source tree.

The product goal requires an always-available installation shell, but does not
permit moving project coordination authority into it. A second control-plane
implementation would duplicate browser security and project authority.

## Decision

Add the daemon as a small package inside the existing `cli` module. It is an
installation lifecycle and launcher process, not a new Gradle module or runtime.

The daemon will hold one per-user `FileLock` below the existing application
state root, publish an atomic endpoint document containing only loopback port,
protocol version, and a random IPC bearer, and serve a length-limited
newline-delimited JSON IPC protocol over loopback TCP. Every request is
authenticated with the random bearer; malformed, oversized, unknown, and
unauthenticated requests fail closed.

It starts or reuses the existing `coordination serve --project ... --port 0`
entrypoint for requested projects, using its real ready line and one-time
browser bootstrap rather than reproducing its composition. It validates runtime
reachability through the existing public health endpoint and retains runtime
handles only in memory. Initial operations are `OPEN_UI`, `OPEN_PROJECT`,
`OPEN_URI`, and `STATUS`.

For `OPEN_URI`, the project runtime publishes a separate high-entropy command
credential in its ready line. The daemon forwards only the bounded URI to the
runtime-owned `/api/v1/commands/deep-link` adapter. That adapter delegates to
the existing `ControlPlaneLinkOperations` and retains Link parsing, signature,
expiry, replay, identity, and membership checks at the project boundary. The
daemon dispatches only when exactly one reachable runtime is present; zero or
multiple eligible runtimes return explicit unresolved or ambiguous results.

`synesis ui` will call this daemon path. The existing `coordination serve`
command remains the project-runtime/developer entrypoint and is not replaced by
the daemon.

The daemon owns installation lifecycle, child-process handles, endpoint cleanup,
and browser opening. Each project runtime owns its own identity, coordination
state, control-plane security, Link state, and shutdown. Runtime endpoint
records are process-local; no liveness is persisted globally.

Loopback is treated as a transport boundary, not authentication. The file lock
prevents same-user duplicate daemons, the random bearer authenticates IPC, and
all request sizes and URI lengths are bounded. A stale endpoint document is
ignored when connection or authentication fails; the lock itself is released by
the operating system after a crash.

## Alternatives rejected

* A second HTTP control plane would duplicate the existing
  session/CSRF/bootstrap boundary and require project-state proxying.
* A global coordination database would duplicate `KnownProjectRegistry` and
  move project authority out of project runtimes.
* Filesystem crawling and process-name/port-file liveness are unverifiable and
  can report stale state.
* A privileged Windows service or full installer auto-start integration is
  deferred; the stable daemon entrypoint is sufficient for this slice.

## Consequences

The first slice makes `synesis ui` reusable across invocations and provides a
truthful installation shell around existing project runtimes. Deep-link
dispatch is now a transport adapter into the existing runtime authority, not a
daemon-owned session or onboarding implementation. The existing UI continues
to show the registry from the selected runtime; cross-project runtime selection
and OS deep-link registration require later thin adapters and are not faked
here. Acceptance must include malformed/oversized IPC, duplicate launch, crash
and stale endpoint recovery, runtime disappearance, secure browser bootstrap,
installed disposable-artifact smoke coverage, and resource measurements from
the daemon process.
