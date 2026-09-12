# SYN-058 Native Deep-Link Activation Evidence

Date: 2026-09-12
Task: SYN-058 — Native Deep-Link Registration and Installed Activation
Current result: COMPLETE FOR CURRENT SCOPE

## Closure and successor

SYN-057 is represented as COMPLETE FOR CURRENT SCOPE in `TASKS.md`,
`CURRENT.md`, `STATE.md`, `NEXT_SESSION.md`, and `SESSION_LOG.md`. CP-0904,
ADR-0074, and the installed two-project selection evidence remain the closure
authority. The task registry contained no authoritative successor after
SYN-057, so the operator-authorized SYN-058 definition was activated. The
transition checkpoint is CP-0905; ADR-0075 records the architecture boundary.

## Reconstructed activation path

The existing `synesis ui` command starts or reuses the per-user daemon through
`DaemonClient.request`. The daemon owns authenticated loopback IPC and the
`OPEN_URI` operation; `DaemonServer.openUri` owns URI validation and SYN-057
routing. There was no CLI-facing URI wrapper and no Windows, Linux, or macOS
native scheme registration.

SYN-058 adds `synesis open <uri>`. It accepts exactly one argument, forwards
the same Java string through `DaemonClient.request(runtime, "OPEN_URI", null,
uri, true)`, surfaces the existing browser selection flow, and exits with a
bounded stable result. It does not parse Link semantics or select a project.

## Platform and lifecycle decision

Windows is SUPPORTED NOW for native registration because the Go bootstrap
already owns the stable installed layout, version pointer, update, repair, and
uninstall lifecycle. Linux is SUPPORTED BUT UNREGISTERED until a tested
desktop-entry lifecycle is activated. macOS is SUPPORTED BUT UNREGISTERED;
the repository has no native application bundle/Info.plist URL-type lifecycle.

Registration is explicit through the bootstrap's `register-protocol` command,
not a daemon-start side effect. It writes only per-user
`HKCU\\Software\\Classes\\synesis`, targets the stable
`bin\\synesis-activate.exe`, is idempotent, repairs a stale Synesis-owned
target, refuses an unowned existing key, and removes only a matching owned
registration through `unregister-protocol` or uninstall.

The registered command is:

```text
"<install>\\bin\\synesis-activate.exe" activate "%1"
```

The native helper requires exactly one URI argument and invokes the active
version's bundled Java runtime with `exec.Command`; it does not invoke
`cmd.exe`, PowerShell, `sh -c`, or a concatenated command string. The existing
general-purpose Windows `.cmd` launcher remains outside this URI trust
boundary because Windows PowerShell 5 cannot safely marshal every percent-rich
argument to a batch child.

## Verification

- `OpenCommandTest`: passed exact one-argument forwarding, hostile characters
  remaining one Java argument, selection-required success, exact arity, and
  bounded unresolved failure.
- `:cli:test --tests 'org.synesis.cli.command.OpenCommandTest' --tests
  'org.synesis.cli.SynesisCliParsingTest' --no-daemon`: PASS with
  `-Djdk.net.unixdomain.tmpdir=external acceptance workspace`.
- `go test ./...` in `bootstrap`: PASS.
- Isolated registration-policy tests: PASS for quoted command construction,
  idempotence, owned stale-target repair, refusal to overwrite unowned keys,
  and ownership-checked unregister.
- Forced `:cli:platformBundle --rerun-tasks --no-daemon`: PASS. The packaged
  Windows bundle contained the native installer/helper, Java runtime, CLI, and
  static UI.
- Disposable D:-backed install and repair: PASS; stable
  `bin\\synesis-activate.exe` was present.
- Real per-user Windows registration: PASS. `reg.exe query` showed the exact
  Synesis-owned marker, install root, and quoted `%1` command. A second
  registration was idempotent.
- Real OS activation: PASS. `Start-Process -FilePath
  'synesis://join/not-valid'` launched the registered handler, reached the
  installed Java `open` command, and returned exit code 10 with the bounded
  daemon rejection `REQUEST_REJECTED`.
- Repeated and near-simultaneous registered activations: PASS. Four bounded
  URI operations returned exit code 10 and all observed the same daemon PID
  (`14612`); no second daemon endpoint was created.
- Real registration cleanup: PASS. `unregister-protocol` removed the exact
  owned HKCU key. The disposable D: daemon was stopped after acceptance; the
  disposable installation directory was retained because the host safety
  policy refused recursive deletion.
- `scripts/agent-validate-deferred.ps1`: PASS.
- `git diff --check`: PASS.
- `:cli:installDist :link:check --no-daemon`: PASS, including CLI Javadocs,
  static analysis, Link tests, and the packaged frontend build.
- `:cli:check --no-daemon`: 60 tests executed; the activation tests passed,
  while the unrelated pre-existing `CodexHookProcessTest` and
  `WorkspaceCliTest` failed. No unrelated repair was attempted.

The full aggregate workspace suite retains the unrelated capability,
workspace-mutation, and provider-session failures recorded under SYN-057. The
CLI aggregate also retains the unrelated `CodexHookProcessTest` and
`WorkspaceCliTest` failures above; no repair was attempted.

## Windows composition acceptance — 2026-09-12

The disposable acceptance topology was D:-backed:

- project A: `4e356190-275f-48f2-83ce-9fe7fa0b8b64`, with a path containing
  spaces;
- project B: `a31d6aa5-2709-46dc-84d6-5d19e76f33b6`;
- installed product: `external acceptance workspace`.

The pre-existing known-project registry was backed up and restored byte for
byte after the run. No user project was changed.

A fresh signed SLO1 was created by the installed `host` command using the
production Link stack. A real Windows shell activation through the registered
`synesis://` handler started/reused the installed daemon and produced the
existing `PROJECT_SELECTION_REQUIRED` result. A bounded supplemental IPC
observation of the same installed daemon exposed exactly A and B. The shipped
authenticated picker rendered both with no default selection. Selecting B sent
only the selection and project IDs, closed the picker, and dispatched the
retained invitation once. The selected runtime returned a real SLA2 answer
with `state=WAITING_FOR_CONNECT`, proving normal Link invitation validation;
selection replay returned `SELECTION_CONSUMED`. The unselected A runtime did
not receive the invitation.

The separate default-browser window could not be captured by this harness, so
the authenticated picker was observed through the same production runtime URL
in the local browser surface, with the exact daemon selection context and the
shipped UI. This is composition evidence, not a claim that a desktop-window
screenshot was captured.

For SLA2 v2, a real signed v2 answer was generated by the production Link
protocol classes with a signed `ReturnTarget.projectId` for project A. Opening
that exact URI through the Windows handler routed to A; the independently
verified A runtime returned `URI_REJECTED / OPERATION_NOT_FOUND`. This is the
intended negative semantic-authority proof: the daemon selected A while the
runtime decided that no matching host operation existed. B was not selected.

The daemon-absent OS activation created one daemon and a project runtime; the
running-daemon path reused that daemon. Earlier repeated and near-simultaneous
activation evidence remains valid for concurrent startup and URI preservation.
Native registration was re-run and removed after the run. The focused
registration suite covers idempotent repair, install paths with spaces,
refusal to overwrite an unowned key, and ownership-safe unregister.

## Final acceptance audit

| Criterion | Result |
|---|---|
| Stable installed activation entrypoint | PASS |
| Exact one-argument URI forwarding and no shell parsing | PASS |
| Per-user Windows registration and quoted install path | PASS |
| Idempotent registration and ownership-safe unregister | PASS |
| Daemon start, reuse, and concurrent activation safety | PASS |
| Real Windows SLO1 activation reaches selection-required routing | PASS |
| Existing authenticated no-default picker and explicit B selection | PASS, with browser-window capture limitation stated above |
| One-shot selection replay protection | PASS |
| Selected runtime performs normal Link verification | PASS — `WAITING_FOR_CONNECT` |
| Real signed SLA2 v2 OS routing to the signed target | PASS |
| Runtime semantic authority remains independent | PASS — bounded `OPERATION_NOT_FOUND` |
| Duplicate routing or persistent project preference | PASS — none added |
| Linux/macOS native registration | EXPLICITLY DEFERRED |
| Login/reboot autostart | EXPLICITLY DEFERRED |

Focused results: `go test ./...` in `bootstrap`, the native/open/daemon
Gradle tests, `:link:check`, `:cli:installDist`, and all web-ui typecheck,
lint, test, and production-build gates passed. The existing aggregate CLI and
workspace failures in `CodexHookProcessTest`, `WorkspaceCliTest`, and the
documented capability/provider/workspace suites remain unrelated and were not
repaired.

## Publication-hygiene note — 2026-09-12

This evidence record was redacted for publication after acceptance. Repository
paths are relative, external acceptance locations are non-clickable descriptive
references, and local workstation identity was removed. Project IDs, node IDs,
digests, operation results, timestamps, protocol versions, test names, and all
acceptance conclusions are preserved.
