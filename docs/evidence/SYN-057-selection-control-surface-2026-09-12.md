# SYN-057 authenticated local project-selection control surface — 2026-09-12

## Scope

This slice exposes the already implemented ambiguous SLO1 routing result through
the existing authenticated loopback control plane:

- the daemon retains the original invitation and exposes only
  `selectionId`, expiry, and safe `{projectId, displayName}` candidates;
- the runtime control plane adds authenticated `GET /api/v1/selection/{id}`
  and CSRF-protected `POST /api/v1/commands/select-project`;
- the browser picker has no default candidate, disables all choices while a
  claim is in flight, clears the one-shot selection after success, and reports
  expired/stale/unavailable outcomes without persisting a project choice;
- the daemon dispatches the untouched original SLO1 URI only after the
  selection store atomically claims the selected eligible project.

The existing SLO1/SLA2 wire formats, signed v2 return target, daemon/runtime
authority boundary, Link cryptography, and persistent project state are
unchanged. No raw SLO1 URI crosses the browser control-plane API.

## Verification evidence

- `npm run typecheck`: passed.
- `npm run lint`: passed.
- `npm test -- --run`: passed, 18 tests successful, 0 failed.
- `npm run build`: passed.
- Rebuilt installed distribution with `:cli:installDist` using
  `-Djdk.net.unixdomain.tmpdir=external acceptance workspace`: passed.
- Direct Java compilation of the changed workspace and CLI production sources:
  passed with `javac --release 21` against the existing module outputs.
- Direct compilation of the changed focused Java tests: passed.
- JUnit Platform execution of `DaemonProtocolTest`,
  `LocalProjectSelectionStoreTest`, and `DaemonServerRoutingTest`: 11 tests
  successful, 0 failed.
- Installed focused Gradle verification with the loopback temporary-directory
  workaround passed: `:link:check`, all `org.synesis.cli.daemon.*` tests, and
  `ControlPlaneHttpHandlerTest`.
- The aggregate `:workspace:test :cli:test` run reached the tests but exposed
  unrelated existing failures in capability, workspace-mutation, and
  provider-session suites; no unrelated production code was changed.
- `git diff --check`: passed; only Git's existing LF/CRLF warnings were emitted.

## Installed two-project acceptance

The canonical installed launcher was exercised with all acceptance state under
`external acceptance workspace`. A fresh isolated state contained exactly these two
registered projects:

- `project-a` — `1d99ee3e-9a7e-431b-9e60-801a935e6d31`
- `project-b` — `bd1e98de-a1c9-46ec-b339-9f19cb50aaa7`

A real installed `host` produced an SLO1 invitation. Authenticated daemon
`OPEN_URI` returned `PROJECT_SELECTION_REQUIRED` with exactly those two safe
candidate descriptors. The installed browser picker rendered both projects,
with no default selection. Clicking `project-b` produced exactly one
authenticated `POST /api/v1/commands/select-project` whose JSON body contained
only `selectionId` and `projectId`; no invitation URI or bootstrap value
crossed the browser API. The picker closed after the daemon accepted the claim,
and the installed daemon started the selected project-b runtime to receive the
retained invitation. Screenshots are retained at
`external acceptance workspace` and
`external acceptance workspace`.

The same selection was then queried and claimed again through authenticated
daemon IPC. Both attempts failed closed with `SELECTION_CONSUMED`, proving
one-shot replay protection. Focused routing tests and `:link:check` also pass
the signed SLA2 v2 target-only routing boundary; the installed browser run
itself exercised the newly added SLO1 selection surface.

## Security boundary checks

The control-plane route requires the existing loopback host/session boundary;
the mutation additionally requires the existing CSRF token. The daemon IPC
operation validates UUID-shaped selection and project identifiers. Unknown,
expired, consumed, out-of-set, unavailable, malformed, unauthenticated, and
arbitrary-path requests fail closed. The browser request body contains only
`selectionId` and `projectId`.

## Remaining boundary

Pending selection state remains process-local and expires after the existing
bounded TTL. A daemon/runtime restart still does not promise continuity for
pending Link operations. No commit or push was performed.
