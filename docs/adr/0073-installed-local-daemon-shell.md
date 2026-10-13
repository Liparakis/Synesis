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

## Follow-up (2026-10-13): reopening the UI, and opening it outside a project

Two defects of `synesis ui` came from this slice and are corrected.

* **A reopened UI was refused.** A project runtime issued one bootstrap token
  when it started, and the daemon built every UI link from it. The runtime
  outlives the browser tab, so the second `synesis ui`, and any deep link that
  opened the browser after the first, carried a spent or expired token and the
  page answered "bootstrap token is invalid or expired". The runtime now has
  `POST /api/v1/commands/bootstrap`, authenticated by the runtime command token
  the daemon already holds for deep links. It returns a fresh one-time token
  with the same five-minute life. The daemon asks for one each time it opens a
  browser. Only the newest unredeemed token is redeemable, and a new session
  still replaces the one before it, so an earlier tab stops being served. The
  ready line keeps its `controlBootstrap` field for the foreground command and
  for scripts. No new authority is created: the daemon could already open the
  UI once with the token it was handed.
* **`synesis ui` needed `--project`.** The command sent no project unless the
  option was given, and the daemon refused an `OPEN_UI` without one. The
  command now sends the working directory when it is an initialized project.
  Without one, the daemon serves the UI from a runtime that is already up, or
  from the first known project whose runtime starts; the UI lists every known
  project whichever runtime serves it. With no known project it answers
  `NO_KNOWN_PROJECT` and the command says to run `synesis init` or pass
  `--project`. A UI with no project at all would need a control plane that no
  project runtime owns, which this ADR rejected above and which stays out of
  scope.
* **A home workspace serves an installation without projects (supersedes the
  limit in the item above).** Once the page could remove every registry entry,
  the current project included, an emptied registry locked the user out: no
  runtime was left to serve the page on which a project is added, and a link or
  shortcut failed without a word. The daemon now falls back to the
  installation's own workspace, a private Synesis project under the
  application state root (`ui-home`), initialized on first need without provider
  configuration. It is not a user project: the registry refuses to record it,
  the control plane reports no current project when it serves from it, and
  deep links are never routed to it. A project runtime that is up, or a known
  project that starts, is always preferred. This keeps the rule that a project
  runtime owns the control plane; it does not add the second control plane
  rejected above. The activation helper also shows a message box when a link
  could not be opened, since it has no console to print to.
* **An address for the UI.** `synesis://ui`, `synesis://open` and the bare
  `synesis://` open the UI on a known project, as `synesis ui` does outside a
  project. They carry nothing to verify, so the daemon matches them as text
  before deep-link validation and never hands them to a runtime.
* **The daemon can be told to stop.** The daemon outlives every command, so an
  updated installation kept being served by the old build and an uninstalled
  one left a process behind. The protocol gains `SHUTDOWN`, authenticated like
  every other operation: the daemon answers `STOPPING`, then closes itself and
  the runtimes it started. The installer sends it once a new build is active
  and before an uninstall removes files. A daemon from a build without the
  operation is ended directly, but only when its program file lies inside the
  installation root; the process identifier in the endpoint file is not taken
  as proof of what runs there. An older client is unaffected: it never sends
  the operation.
* **Uninstall ends what runs from the installation.** A daemon ended directly
  leaves its project runtimes to notice and exit on their own, and agents may
  have MCP servers running from the installation. Either held application
  files open, and a first real uninstall failed on a file in use. Uninstall
  now, after stopping the daemon, waits a moment and then ends every process
  whose program file lies inside the installation root, and retries a removal
  that is refused for about three seconds. An update ends only the daemon.
