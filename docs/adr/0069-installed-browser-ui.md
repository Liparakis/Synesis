# ADR-0069: Installed browser UI over the local control plane

## Status

Accepted for SYN-053.

## Context

Synesis now has a verified loopback control plane that exposes explicit,
authenticated project, coordination, Link, overlay, relay, diagnostic, and
onboarding read models. The next product slice is the first real browser UI.
It must be an installed product surface, not a development-only frontend or a
second runtime service. The current repository has no frontend module, Node
runtime contract, static-resource handler, or browser-opening command.

The UI must remain truthful when a project is empty or the overlay is
`UNCONFIGURED`. It must not duplicate WorkGroup, claim, routing, onboarding, or
security policy in browser code.

## Decision

Add a first-class `web-ui` Gradle module containing a TypeScript/React/Vite/
Tailwind application. npm's lockfile is committed and the module's production
build emits static assets into a resource JAR. The root build and CLI
distribution depend on that module, so the installed Java distribution carries
the compiled UI and never invokes Node, npm, or Vite at runtime.

Serve the resource JAR from the existing JDK loopback `HttpServer` on the same
origin as `/api/v1`. The static handler has an allowlisted resource mapping,
SPA fallback only for UI paths, immutable caching for hashed assets, no-cache
for `index.html`, and browser security headers including a restrictive CSP.
API, lifecycle, and legacy binary routes remain separate and are never served
by the SPA fallback.

Add a dedicated `synesis ui` command that composes the existing coordination
server, prints the real endpoint, and opens the default browser through the
JDK desktop abstraction. The one-time control bootstrap is placed in the URL
fragment, exchanged by the UI for the existing session/CSRF pair, and removed
from browser history immediately. If browser opening is unavailable, the
command prints the local URL as a manual fallback without weakening server
security.

The frontend has one typed control-plane client. It loads an authenticated
snapshot, consumes the existing SSE stream with a header-capable `fetch`
stream, and coalesces semantic events into authoritative snapshot refreshes.
It keeps only route, selection, modal, session, and cached snapshot state. All
domain state and mutations remain server-owned.

The first screens are a dashboard, projects, agents, WorkGroups, peers/network,
and diagnostics, plus the real invite/join onboarding flow. Empty projects,
`UNCONFIGURED` overlay state, disabled relay state, missing peers, and session
or runtime failures are explicit product states. No production fixture data is
allowed.

## Alternatives rejected

- A separate Node server or Vite dev server would add a runtime dependency and
  a second origin.
- Electron or a native GUI would duplicate the local product shell and exceed
  the requested browser scope.
- Frontend-derived routes, WorkGroup states, or Link trust would create a
  second authority model and could render false state.
- A generic filesystem static endpoint would violate the existing control-plane
  boundary.

## Consequences

The build now needs a deterministic Node/npm toolchain during development and
CI, but end users need only the installed Synesis distribution. Static assets
are versioned through the web module's build output and are tested through the
packaged CLI path. The browser can be extended later without changing Link or
overlay semantics, while any new screen must first be backed by a public
control-plane DTO.

