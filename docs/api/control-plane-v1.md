# Synesis local control-plane API v1

The control plane is the local backend for the installed Synesis browser UI.
It is served from the existing loopback coordination listener; it is not a
public API, hosted service, generic proxy, or arbitrary filesystem browser.

`synesis ui` starts this listener with the packaged static UI mounted at `/`
and optionally opens the browser. `synesis coordination serve` remains
available as the lower-level server command.

## Base URL and bootstrap

The command binds to `127.0.0.1` by default. `::1` is accepted only when
selected explicitly. The ready line contains the actual bound endpoint and a
one-time `controlBootstrap` value, for example:

```text
COORDINATION_SERVE_READY ... controlPlaneRoute=/api/v1 controlBootstrap=<token>
```

`POST /api/v1/session` exchanges that value once for a short-lived session:

```json
{"bootstrapToken":"<token>"}
```

The response contains `sessionToken`, `csrfToken`, and `expiresAt`. The
bootstrap value is then invalid. Private reads send
`X-Synesis-Control-Session`; mutations additionally send
`X-Synesis-Control-CSRF`. Tokens are not cookies and are never included in
snapshots or SSE events.

Every request must have an exact local `Host` and an exact loopback `Origin`
when `Origin` is present. Untrusted origins, wildcard CORS, non-loopback
listeners, and missing mutation CSRF are rejected. The only unauthenticated
read is health.

## Installed browser UI

The `web-ui` Gradle module builds a static TypeScript/React application. Its
production resources are packaged into the CLI distribution and served by the
same listener through a classpath allowlist. The installed command does not
start Node, npm, Vite, Electron, or a second HTTP server.

The UI is launched with a one-time fragment bootstrap URL. The fragment is
consumed by the browser, exchanged through `/api/v1/session`, and removed from
history. The UI uses the session header for reads and the existing CSRF header
for mutations. Its fetch-based SSE client receives the initial snapshot and
live semantic updates without replaying a browser event log.

The static handler sends a self-only CSP, `X-Content-Type-Options: nosniff`,
`X-Frame-Options: DENY`, and `Referrer-Policy: no-referrer`. UI routes fall
back to `index.html`; API, lifecycle, legacy coordination, traversal, and
missing asset paths do not.

## Read endpoints

| Method | Path                   | Result                                                      |
|--------|------------------------|-------------------------------------------------------------|
| GET    | `/api/v1/health`       | Listener, project ID, and durable sequence health           |
| GET    | `/api/v1/snapshot`     | Complete bounded public-safe project snapshot               |
| GET    | `/api/v1/projects`     | Bounded known-project identities and derived statuses       |
| GET    | `/api/v1/agents`       | Participant and current work summaries                      |
| GET    | `/api/v1/workgroups`   | WorkGroup status and participant summaries                  |
| GET    | `/api/v1/claims`       | Work-intent selectors and conflict summaries                |
| GET    | `/api/v1/capabilities` | Capability handles, lifecycle, and explicit contract fields |
| GET    | `/api/v1/network`      | Physical Link, overlay, route, and relay read-model seam    |
| GET    | `/api/v1/diagnostics`  | Safe doctor findings without raw detail maps                |
| GET    | `/api/v1/events`       | Live SSE updates with an initial snapshot event             |

The snapshot is assembled explicitly from `ProjectApplicationService`, the
durable coordination projections, provider status, and `DoctorService`.
Private keys, provider credentials, authority lineages, recovery references,
raw event payloads, and arbitrary file contents are not DTO fields. Network
state is `UNCONFIGURED` until a signed membership source configures the
long-lived Link/overlay owner; the endpoint does not invent connection state.

`/api/v1/projects` reports the bounded installation-local known-project index.
Entries are recorded only when an existing Synesis lifecycle or runtime path
successfully validates a project through `ProjectApplicationService`; the
endpoint does not scan the filesystem, crawl directories, or infer projects
from coordination data. Each entry contains only project identity, validated
path, creation/observation timestamps, and a derived status of `LIVE`,
`INACTIVE`, `UNAVAILABLE`, or `IDENTITY_MISMATCH`. Status is recomputed at read
time, and only the project served by the current listener can be `LIVE`.

The `LinkNetworkProjection` adapter maps a single consistent runtime source of
`PeerSession`, verified `OverlayMembershipView`, verified
`OverlayTopologyView`, and safe relay state into that DTO. It keeps physical
Link peers separate from overlay membership and topology. It can expose
authenticated peer/liveness health, membership nodes, direct edges, desired
edges, selected routes (`DIRECT`, `PEER_TRANSIT`, `ORGANIZATION_RELAY`,
`UNREACHABLE`), and relay configured/connected/authorized/usage state without
exposing cryptographic material. `LinkRuntimeOwner` owns retained sessions and
composes these views; the CLI deliberately supplies no membership snapshot, so
its overlay remains `UNCONFIGURED` while physical Link state remains real when
connected.

## Supported commands

Commands are deliberately narrow adapters over existing application services.
They do not duplicate SLO1/SLA2 validation or create a second Link runtime.

### `POST /api/v1/commands/invite`

```json
{"expectedPeer":"optional-node-id"}
```

Delegates to `Onboarding.createInvitation`. When the listener has the live
`LinkRuntimeOwner`, the authenticated session remains owned after the later
answer command returns; the compatibility adapter retains the original
one-shot behavior. The response contains an opaque
`operationId`, the exact `inviteUri` for the operator to copy, the authenticated
peer identity, expiry, and `WAITING_FOR_ANSWER` state. The link is returned only
to the authenticated caller and is never emitted through SSE.

### `POST /api/v1/commands/join`

```json
{"inviteUri":"synesis://join/SLO1-..."}
```

Delegates to `Onboarding.importInvitation`. When the listener has the live
`LinkRuntimeOwner`, the authenticated session remains owned after the later
connect command returns; the compatibility adapter retains the original
one-shot behavior. The response contains an opaque
`operationId`, the exact `answerUri`, the pinned host identity, expiry, and
`WAITING_FOR_CONNECT` state.

### `POST /api/v1/commands/answer`

```json
{"operationId":"...","answerUri":"synesis://answer/SLA2-..."}
```

Delegates to the stored `PreparedHost.importAnswer`, then closes the one-shot
handle and returns `CONNECTED` or a typed redacted onboarding error.

### `POST /api/v1/commands/connect`

```json
{"operationId":"..."}
```

Delegates to the stored `PreparedJoin.connect`, then closes the one-shot handle
and returns `CONNECTED` or a typed redacted onboarding error.

### `POST /api/v1/commands/cancel`

```json
{"operationId":"..."}
```

Closes a pending invite or join handle before it is used and returns
`CANCELLED`. An operation already in `answer` or `connect` is conflict-locked
and cannot be cancelled concurrently.

At most 16 pending onboarding handles are retained in memory. Expired or
completed handles are closed; links are not written to project state.

## SSE behavior

`GET /api/v1/events` sends `text/event-stream` with:

- one `snapshot` event containing the current public-safe snapshot;
- semantic coordination events named `agent.updated`, `workgroup.updated`,
  `claim.updated`, `capability.updated`, `task.updated`, or the fallback
  `coordination.updated`, containing only sequence and the UI event type;
- `peer.connected`, `peer.disconnected`, `peer.updated`, or bounded
  `link.updated` events containing only redacted onboarding lifecycle facts;
- `refresh_required` followed by disconnect when a bounded subscriber queue
  overflows.

The control stream is live-only. A reconnect receives a new snapshot; it does
not create a browser event log or replay raw durable events. Coordination's
internal binary `/events` endpoint remains a separate compatibility surface.
Closing the control handler terminates active streams and lets the existing
coordination listener release its executor and socket.

## Errors and limits

Errors use this stable envelope:

```json
{"apiVersion":"v1","error":{"code":"...","message":"..."}}
```

Request bodies are capped at 128 KiB. JSON is required for session and command
requests. Unsupported routes and methods, invalid JSON, body overflow, bad
host/origin, missing authentication, missing CSRF, expired bootstrap/session,
operation races, and invalid onboarding input fail closed with an explicit
HTTP status and stable code. Exception stacks, private paths, credentials, and
share-link values are not returned.
