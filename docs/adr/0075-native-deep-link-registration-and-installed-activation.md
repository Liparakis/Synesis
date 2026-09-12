# ADR-0075: Native Deep-Link Registration and Installed Activation

- Status: Accepted; SYN-058 COMPLETE FOR CURRENT SCOPE
- Date: 2026-09-12
- Decision owners: Synesis local product boundary

## Context

SYN-057 established deterministic local SLO1 recipient selection and signed
SLA2 v2 project routing. The remaining installed-product gap is OS activation:
an external `synesis://` URI must enter the existing authenticated daemon
`OPEN_URI` path without moving routing or Link authority into an OS launcher.

The current repository has two relevant seams. The Java CLI's `synesis ui`
command starts or reuses the per-user daemon through `DaemonClient.request`,
but it has no CLI-facing URI activation command. The Go bootstrap owns the
stable installed launcher and the install, update, repair, and uninstall
lifecycle, but it has no native URI registration. No Windows registry,
Linux desktop-entry, or macOS bundle URL registration exists today.

## Decision

SYN-058 first adds a short-lived Java `synesis open <uri>` command. It accepts
exactly one supported `synesis://` URI, delegates daemon startup/reuse and
authenticated `OPEN_URI` transport to the existing `DaemonClient` boundary,
and exits with a bounded deterministic result. The command does not parse Link
semantics, choose a project, persist a project preference, or create a second
daemon.

Windows is the primary native-registration platform for the first slice. The
Go installer owns a per-user `HKCU\\Software\\Classes\\synesis` registration
and points it at a stable native activation helper with the URI as one quoted
argument. The helper is deliberately registered instead of a versioned Java
payload path, so updates retain the handler target while the helper resolves
the active pointer. Install/repair keep that helper current; uninstall removes
the registration only when its ownership marker and install root match
Synesis. An existing unowned registration is not silently overwritten.

The daemon remains the only URI validator and routing authority. The handler
process is transport only and must not invoke a shell with concatenated input.
The Windows registration string follows the OS protocol-handler convention;
the launcher and tests must prove that spaces, quotes, shell metacharacters,
percent escapes, fragments, and encoded delimiters remain one argument or are
rejected before execution.

## Platform boundary

- Windows: supported now for per-user registration and installed acceptance.
- Linux: installed artifact exists, but native scheme registration is deferred
  until a tested desktop-entry lifecycle is explicitly activated.
- macOS: installed artifact exists, but no application bundle/Info.plist URL
  type lifecycle is present; native registration is deferred.

Login autostart, hosted relay, protocol changes, selected-project persistence,
membership changes, routing redesign, and browser UI redesign remain outside
this decision.

## Consequences

External activation reuses the existing daemon and SYN-057 resolver, including
`PROJECT_SELECTION_REQUIRED`, rather than duplicating picker or routing state.
The stable launcher remains the installation boundary for updates. Native
registration tests can use an isolated registry abstraction and must not mutate
the developer hive; real acceptance is bounded and cleans up only the owned
Synesis registration.

## Acceptance status — 2026-09-12

The Windows composition acceptance passed for current scope. A signed SLO1
opened through the registered handler reached the existing two-project
`PROJECT_SELECTION_REQUIRED` flow; the shipped authenticated picker selected
project B once and the selected runtime returned `WAITING_FOR_CONNECT`. A
signed SLA2 v2 answer opened through the same handler routed to its signed
project A target, where the runtime independently rejected the unmatched
operation with `OPERATION_NOT_FOUND`. Registration, daemon start/reuse,
concurrency, quoting, and ownership-safe lifecycle evidence passed. The
acceptance harness could not capture the separate default-browser window, so
the picker result was observed through the same production authenticated
runtime in the local browser surface; this does not change the protocol or
authority result.

## Publication-hygiene status — 2026-09-12

The implementation and acceptance record was redacted for publication. Internal
repository paths are relative and external acceptance locations are described
without machine-specific paths. Protocol, routing, security, and acceptance
conclusions are unchanged.
