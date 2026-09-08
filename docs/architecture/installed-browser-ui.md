# Installed browser UI

SYN-053 adds the first real Synesis browser UI as an install-bundled frontend
over the existing local control plane. The UI is a view and command surface;
Synesis remains the authority for project, coordination, Link, overlay,
onboarding, and diagnostic state.

## Runtime shape

- `web-ui/` is a first-class Gradle module containing TypeScript, React, Vite,
  Tailwind, Lucide, and Vitest source.
- `web-ui/package-lock.json` pins the development toolchain. `npm ci` and Vite
  run during development or packaging only.
- The production `dist/` output is copied into `web-ui-*.jar` under
  `web-ui/` and is carried by the existing `cli` application distribution.
- Installed runtime startup uses only Java. It does not require Node, npm,
  Vite, a second frontend server, Electron, or a cloud service.
- `synesis ui` composes the existing loopback control-plane listener, serves
  the static UI from the same origin, and optionally opens the browser. The
  one-time bootstrap is placed in the URL fragment, exchanged through
  `POST /api/v1/session`, and removed from browser history by the client.

The static handler serves an allowlisted classpath resource root. It reserves
the API and legacy coordination paths, permits SPA fallback only for UI
routes, rejects traversal, gives the document a no-cache policy, and gives
hashed assets an immutable cache policy.

## State and security

The typed client consumes the existing control-plane DTOs. Private requests
use the session header; mutations use the existing CSRF header. SSE is a
fetch-based stream because browser `EventSource` cannot send the required
session header. The stream starts with the real snapshot and coalesces
semantic refreshes; it does not create a client-side event log or infer routes.

The server supplies loopback binding and exact Host/Origin validation. The UI
handler adds same-origin security headers, including a self-only CSP with no
external script, style, font, image, or connection source. There is no CDN,
telemetry, browser-side Link transport, or arbitrary filesystem endpoint.

## Product surfaces

The shell exposes Dashboard, Projects, Agents, WorkGroups, Peers & network,
and Diagnostics. The network surface includes real invite, join, and host
answer controls over the existing onboarding commands. Empty collections are
rendered as explicit empty states. With the current CLI authority boundary,
the overlay is shown as `UNCONFIGURED`, relay as `DISABLED`, and physical
peer/route collections remain empty rather than being filled with demo data.

## Verification

The focused gates are:

```powershell
npm run typecheck
npm run lint
npm run test
npm run build
.\gradlew.bat :web-ui:check :coordination:compileJava :cli:compileJava --no-daemon --console=plain
.\gradlew.bat :cli:test --tests 'org.synesis.cli.ui.StaticResourceHandlerTest' --no-daemon --console=plain
.\gradlew.bat :cli:installDist --no-daemon --console=plain
```

The installed acceptance starts the packaged `synesis ui` command against a
real disposable Git project, then verifies static index and asset delivery,
security headers, authenticated snapshot state, and the initial SSE snapshot.
The browser pass verifies navigation, real project display, empty agents and
WorkGroups, truthful network posture, diagnostics, and real invite/join
onboarding behavior. Detailed results are recorded in
`docs/evidence/syn-053-installed-browser-ui-2026-09-08.md`.

The current production bundle is approximately 0.52 kB HTML, 19.72 kB CSS,
and 222.03 kB JavaScript before gzip (0.31 kB, 5.01 kB, and 68.50 kB gzip).
