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
{
    "bootstrapToken": "<token>"
}
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

| Method | Path                              | Result                                                      |
|--------|-----------------------------------|-------------------------------------------------------------|
| GET    | `/api/v1/health`                  | Listener, project ID, and durable sequence health           |
| GET    | `/api/v1/snapshot`                | Complete bounded public-safe project snapshot               |
| GET    | `/api/v1/projects`                | Bounded known-project identities and derived statuses       |
| GET    | `/api/v1/selection/{selectionId}` | Safe projection of one pending local project selection      |
| GET    | `/api/v1/agents`                  | Participant and current work summaries                      |
| GET    | `/api/v1/workgroups`              | WorkGroup status and participant summaries                  |
| GET    | `/api/v1/claims`                  | Work-intent selectors and conflict summaries                |
| GET    | `/api/v1/capabilities`            | Capability handles, lifecycle, and explicit contract fields |
| GET    | `/api/v1/network`                 | Physical Link, overlay, route, and relay read-model seam    |
| GET    | `/api/v1/diagnostics`             | Safe doctor findings without raw detail maps                |
| GET    | `/api/v1/events`                  | Live SSE updates with an initial snapshot event             |

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
They do not duplicate invitation or answer validation or create a second Link runtime.

### `POST /api/v1/commands/invite`

```json
{
    "expectedPeer": "optional-node-id",
    "openRouterPort": false
}
```

Delegates to `Onboarding.createCompactInvitation`. With `openRouterPort`
`true`, the user's choice, the host first asks its router over UPnP to forward
the listening port (ADR-0097). The response contains an opaque `operationId`,
the exact `synesis://join/SLB3-...` `inviteUri` for the operator to copy, the
host identity, expiry, and `WAITING_FOR_CONNECTION` state. The link is
returned only to the authenticated caller and is never emitted through SSE.

### `POST /api/v1/commands/join`

```json
{
    "inviteUri": "synesis://join/SLB3-..."
}
```

Delegates to `Onboarding.importInvitation` and returns at once with an opaque
`operationId`, the `answerUri` (`synesis://answer/SLA3-...`) the host's user
pastes when the computers are on different networks, the pinned host identity,
expiry, and `CONNECTING` state. The join dials in the background until it
connects or the invitation expires; it leaves `pendingJoins` either way.

### `POST /api/v1/commands/answer`

```json
{
    "operationId": "...",
    "answerUri": "synesis://answer/SLA3-..."
}
```

Gives a waiting host operation the joiner's answer. The host starts sending
toward the joiner's endpoints and keeps waiting; the response is
`SENDING_TO_JOINER`, or `ONBOARDING_ANSWER_INVALID` for a malformed answer or
one that answers another invitation. An invitation or answer that carries
another Synesis compatibility number, and a handshake that fails the same way,
return `400` with code `INCOMPATIBLE_VERSION` and a message naming both numbers,
which side is older and what to do; it is never reported as `INVITE_INVALID` or
`ANSWER_INVALID`.

### Project invitations (ADR-0098, "Joining, revised")

One link carries the connection invitation, the project's identity and the inviter's code remote URL. The owner makes
it, the friend opens it, the owner decides.

#### `POST /api/v1/commands/invite` with `"project": true`

Creates the connection invitation as above and returns it wrapped as a signed `synesis://project/SLP1-...` `inviteUri`
(`WIRE_FORMAT.md`). **It writes nothing**: no role file, so a computer that has none keeps working exactly as before
(task completion and publication included) until it accepts its first joiner. The code remote the link names is the
project's explicit `code.remote`, or its only Git remote; never a guess among several. The response adds
`kind: "PROJECT"`, `projectId`, `role: "leader"`, `codeRemoteName` and `codeRemoteUrl` (the URL without credentials; the
owner is shown both), and `codeRemoteReason` when `codeRemoteUrl` is `null`: `NO_GIT_REMOTE`, `SEVERAL_GIT_REMOTES`,
`CODE_REMOTE_MISSING` (`code.remote` names a remote the project does not have) or `URL_NOT_SHAREABLE` (not `https://`,
`ssh://` or `user@host:path`). A link without a URL can be opened, but its joiner cannot be accepted
(`accept-join` answers `409 CODE_REMOTE_NOT_CONFIGURED`); add a Git remote and create a new invitation. On a computer
that follows another leader the same command (with or without `project`) creates the plain connection invitation of the
section above, with no `kind`: a member can host a connection for its leader.

#### `POST /api/v1/commands/describe-project-link`

`{"link": "synesis://project/SLP1-..."}`. Verifies the link (`400 INVITE_INVALID`, `400 INCOMPATIBLE_VERSION`) and
returns `hostNodeId` (the inviter), `projectId`, `projectName` (the folder name when this computer knows the project,
else `null`), `codeRemoteUrl` (or `null`) and `expiresAt`. It changes nothing; the UI shows it before the person presses
Join.

#### `POST /api/v1/commands/open-project-link`

```json
{
    "link": "synesis://project/SLP1-...",
    "folder": "optional path of the folder that holds the project"
}
```

Verifies both signatures and the expiry (`400 INVITE_INVALID`, `400 INCOMPATIBLE_VERSION`), then finds the project's
folder on this computer:

- no `folder`: the installation's known-project registry is asked for the identity (project UUID and creation time).
  A registered copy is used; with several, the one this runtime serves wins, else the first by path.
- `folder`: it must be the project. Its `.synesis/project.json` must carry the link's identity (`409
  PROJECT_IDENTITY_MISMATCH`); a clone that holds only that file (no local state) is made a replica (`initReplica`,
  ADR-0080); a folder with no `.synesis` is accepted only if one of its Git remotes has the link's code remote URL
  (`409 PROJECT_FOLDER_NOT_THE_PROJECT`), and is made a replica. The folder is then registered. A path that is not a
  directory is `400 PROJECT_FOLDER_INVALID`.

No folder found answers `200` with `state: "PROJECT_NOT_FOUND"`, `projectId`, `hostNodeId` and `codeRemoteUrl`
(or `null`): the person is told the project is not on this computer and where to clone it from. Synesis never clones.

A folder found is followed: the runtime that serves it (this one, or the folder's own, reached with its runtime command
token through `POST /api/v1/commands/join-project-link`) sets the member role with the inviter as leader, sets the
code remote (the remote whose URL matches, else one named `synesis` is added, `409 CODE_REMOTE_NAME_TAKEN` if that name
is taken), switches its replication side to a member's without a restart, and joins the connection invitation. The
connection is prepared first (capacity `429 OPERATION_LIMIT`, an unusable invitation) so that those refusals write
nothing; then the role file, the `synesis` remote and the live switch happen under the project lock, and a failed switch
puts the file and the remote back. The same link can be opened again after a failure. Refusals
(`409`, nothing written): `LOG_NOT_EMPTY` (a member's log is a copy of the leader's, so it must be empty),
`FOLLOWING_ANOTHER_LEADER`, `ALREADY_A_LEADER`, `INVITER_IS_THIS_COMPUTER`. The response is `200` with `state:
"CONNECTING"`, `operationId`, `answerUri` (for a friend on another network), `peerIdentity`, `expiresAt`, `folder`,
`role: "member"`, `leaderNodeId` and `codeRemote`. When `codeRemote` is set, the answer also carries `codeRemoteCheck`:
`REACHABLE` when `git ls-remote <codeRemote> HEAD` answered with no prompt (the way every lane on a member fetches the
leader's head), else the refusal code (`CODE_REMOTE_UNAVAILABLE`) with `codeRemoteDetail`, Git's reason with any URL
credentials removed and the command to run (`git fetch <codeRemote>` once in a terminal, signing in if asked). The check
runs after the join and never refuses it: the person can sign in afterwards, and the invitation is single use. `synesis
link join` prints the same as `CODE_REMOTE_CHECK` and `CODE_REMOTE_DETAIL`.

#### `POST /api/v1/commands/join-project-link`

`{"link": "synesis://project/SLP1-..."}`. The same follow-and-join for the project this runtime serves, refusing a link
of another project (`409 PROJECT_IDENTITY_MISMATCH`). It is what `open-project-link` and `synesis link join` call on the
folder's runtime.

#### `accept-join` and `decline-join`

`{"nodeId": "sl1-..."}`. A node that authenticates through the invitation and is not a member is held: it has a Link
session, the project identity was checked, and nothing is served on it. It is listed in the snapshot as
`onboarding.joinRequests`, each `{nodeId, requestedAt, expiresAt}`, where `expiresAt` is the expiry of the invitation it
used; a request ends there and its session is closed. `accept-join` adds the node to the member list, serves its live
session at once (it also stays served after a restart, because it is recorded in `replication.peers`) and returns
`state: "ACCEPTED"`. Accepting is what writes the leader role (keeping the members), adds the node and sets `code.remote`
to the remote whose URL the link that brought the node carried, under the project lock so two accepts never lose a
member; if that link carried no URL, or no remote has it any more, it answers `409 CODE_REMOTE_NOT_CONFIGURED` and the
request stays. `decline-join` closes its session and returns `DECLINED`; its invitation was used up by its first
connection, so it cannot come back with it. `404 JOIN_REQUEST_UNKNOWN` when no such request waits (decided, expired, or
the runtime restarted: requests are kept in memory and a restart drops them), `503 MEMBER_NOT_SAVED` when the member
list could not be written, in which case the request stays.

### `POST /api/v1/commands/cancel`

```json
{
    "operationId": "..."
}
```

Closes a pending invite or join handle and returns `CANCELLED`. A join that is
still dialing stops.

At most 16 pending onboarding handles are retained in memory. Expired or
completed handles are closed; links are not written to project state.

### Project selection and discovery

These routes use the authenticated session described above. The POST commands
also require `X-Synesis-Control-CSRF`.

`GET /api/v1/selection/{selectionId}` reads a pending selection from the local
daemon. Its response is the daemon's bounded safe selection projection; it
does not include the original invitation. Unknown, expired, consumed, or
unavailable selections return the corresponding stable error envelope.

`POST /api/v1/commands/select-project` selects an existing project for a
pending daemon selection:

```json
{
    "selectionId": "<uuid>",
    "projectId": "<uuid>"
}
```

The response is the daemon's safe routing result. The command accepts project
identity, not a raw invitation or filesystem path.

`POST /api/v1/commands/choose-project` opens the native Windows folder picker;
send an empty JSON object:

```json
{}
```

The response is `{"apiVersion":"v1","state":"SELECTED","path":"...","name":"..."}`
or `{"apiVersion":"v1","state":"CANCELLED","path":"","name":""}`.
Headless, non-Windows, missing-picker, interrupted, and timed-out requests fail
with `503 PROJECT_PICKER_UNAVAILABLE`.

`POST /api/v1/commands/register-project` validates and remembers an already
initialized project in the installation-local discovery registry:

```json
{
    "path": "<project-directory>"
}
```

The response contains `state: "REGISTERED"` and the refreshed `projects`
list. It does not initialize the project or change its files.

`POST /api/v1/commands/remove-project` removes only one entry from that local
registry:

```json
{
    "projectId": "<uuid>"
}
```

The response contains `state: "REMOVED"`, the selected `projectId`, and the
refreshed `projects` list. It never removes the project directory or its local
Synesis state.

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
{
    "apiVersion": "v1",
    "error": {
        "code": "...",
        "message": "..."
    }
}
```

Request bodies are capped at 128 KiB. JSON is required for session and command
requests. Unsupported routes and methods, invalid JSON, body overflow, bad
host/origin, missing authentication, missing CSRF, expired bootstrap/session,
operation races, and invalid onboarding input fail closed with an explicit
HTTP status and stable code. Exception stacks, private paths, credentials, and
share-link values are not returned.
