# SYN-057 deterministic multi-project runtime routing — discovery evidence — 2026-09-11

## Scope and result

SYN-057 was explicitly authorized after SYN-056 completed its current scope.
The first slice reconstructed identity, trust, daemon discovery, runtime
endpoint state, and deep-link target data before any routing implementation.

Result: **current links do not provide a trustworthy destination project or
runtime identity before dispatch**. The existing daemon can therefore remain
fail-closed, but it cannot safely replace `URI_AMBIGUOUS` with deterministic
multi-project routing without a protocol or recipient-targeting contract.
No production code, Link format, OS registration, membership behavior, or UI
selection behavior was changed in this slice.

## Identity hierarchy

| Identity | Current source and lifetime | Trust/authority classification |
| --- | --- | --- |
| Known project | `.synesis/project.json` `projectId` plus normalized root; persisted by `KnownProjectRegistry` after normal validated Synesis use | Durable local discovery metadata; validated against the project path, not signed as a deep-link destination and not a coordination authority |
| Project runtime | One `coordination serve --project ...` child process held by the daemon's in-memory `Map<Path, RuntimeHandle>` | Process-local; project runtime owns coordination, control-plane, Link, and project state |
| Runtime endpoint | Loopback HTTP URI from `COORDINATION_SERVE_READY`, plus the runtime command bearer | Ephemeral/advisory endpoint; command bearer authenticates only the narrow runtime deep-link adapter |
| Daemon instance | Per-user lock and `endpoint.json` containing loopback port, random IPC bearer, and PID | Durable only while the endpoint document exists; local IPC authentication, not project authority |
| Browser/bootstrap project | Runtime endpoint plus one-time bootstrap token in the browser fragment; session is created by that runtime | Runtime-local authenticated bootstrap; it identifies the selected runtime only after the browser contacts it, not before daemon `OPEN_URI` dispatch |
| Link host operation | Runtime-local random `operationId` mapped to a retained `PendingHost`; host matching parses stored SLO1 and compares `sessionId` | Runtime-local and absent from SLO1/SLA2; not a daemon routing handle |
| Link join operation | Runtime-local random `operationId` mapped to a retained `PendingJoin` | Runtime-local and absent from SLO1/SLA2; not a daemon routing handle |
| SLO1 | Signed `synesis://join/SLO1-...` containing the invitation and traversal offer | Structurally untrusted until runtime parsing and signature/expiry/peer checks; no destination project/runtime identity |
| SLA2 | Signed `synesis://answer/SLA2-...` containing the answer and offer digest | Structurally untrusted until runtime parsing and verification; no destination project/runtime identity |
| Persisted node identity | Project-local Link identity directory loaded by the runtime | Durable and cryptographically authenticated for Link peer identity; a node ID is not a project ID and does not select a local runtime |
| Participant/session identity | Link `sessionId`, node IDs, and later handshake/session state | Session/peer authenticated within Link after runtime verification; not a project-routing authority |

## Known-project and runtime discovery

`KnownProjectRegistry` records only project ID, normalized path, creation and
observation times. It validates the remembered path through
`ProjectApplicationService` and derives `LIVE`, `INACTIVE`, `UNAVAILABLE`, or
`IDENTITY_MISMATCH` for a supplied set of process-owned live project IDs. It
does not persist runtime endpoints or liveness and does not crawl the
filesystem.

The daemon currently does not use that registry to resolve `OPEN_URI`. Its
runtime map is keyed by project path and exists only in the daemon process.
`OPEN_PROJECT` starts or reuses the explicit requested path. A runtime is
reachable only when its child process is alive and its `/api/v1/health` call
returns 200. `STATUS` removes unreachable handles; `OPEN_URI` filters them and
returns `URI_UNRESOLVED` for zero or `URI_AMBIGUOUS` for more than one.

After daemon restart, the runtime map is empty. No runtime endpoint registration
or routing index is reconstructed. The ready line contains project and host
metadata, but the daemon currently retains only endpoint, bootstrap, and
command-token values. `hostInstanceId` is a project-runtime lifecycle identity,
not a deep-link target.

## Pre-dispatch deep-link information

The daemon sees only an authenticated local `OPEN_URI` request containing a
bounded URI and an `openBrowser` preference. It validates URI shape but does
not decode or verify Link payloads. It has no `projectId`, runtime ID,
operation ID, host instance ID, or recipient selection in the request.

The runtime deep-link adapter then parses the URI. For SLO1 it creates a local
join operation. For SLA2 it parses the answer and finds exactly one local
pending host operation by matching the embedded `sessionId` against stored
SLO1 invitations. That operation lookup happens inside the selected runtime;
the `operationId` is generated locally and is not carried by the link.

### Field trust classification before daemon dispatch

| Field or source | Classification before dispatch | Routing use |
| --- | --- | --- |
| URI scheme/host/path shape | Untrusted structural input | Bounds/rejection only |
| SLO1/SLA2 encoded bytes and decoded payload | Untrusted until runtime parser | Cannot select a runtime |
| SLO1/SLA2 signatures, expiry, candidate descriptors | Authenticated only after runtime parsing and Link verification | Must remain in Link/runtime authority; not daemon parsing |
| `sessionId` | Signed/authenticated only after runtime verification | Matches a runtime-local pending host operation, but is not a project identity |
| SLO1 initiator/expected responder node IDs | Signed/authenticated only after runtime verification | Peer binding only; no local destination project mapping |
| SLA2 initiator/responder node IDs and offer digest | Signed/authenticated only after runtime verification | Peer/offer binding only; no local destination project mapping |
| `operationId` | Absent from deep links | Exists only after runtime-local operation creation |
| `projectId` | Absent from SLO1, SLA2, and `OPEN_URI` | No pre-dispatch project target exists |
| runtime endpoint/command token | Daemon-local, authenticated only for the chosen runtime adapter | Not present in links and cannot be inferred from them |
| `openBrowser` | Authenticated local request field, user-controlled preference | Not a target identity |

## Architecture classification

The current architecture is **E — no current trustworthy targeting
information exists**, with a narrower **D** observation for SLA2: the runtime
can identify its own pending host operation only after it receives and parses
the link. That D behavior does not give the daemon a project mapping.

Broadcasting the URI to every runtime, or testing each runtime until one
accepts, is forbidden. It leaks capability-bearing input across unrelated
projects and makes acceptance order an authority decision. Registry ordering,
last-started runtime, port, PID, or peer node ID are not legal substitutes.

## Protocol-gap conclusion

The smallest safe next design decision is not a daemon resolver. It is an
explicit protocol/recipient-targeting contract owned by the Link/deep-link
layer. A casually added unsigned `projectId=` query parameter would be
spoofable and would not be sufficient.

The contract must distinguish both directions:

1. An answer can potentially carry a signed identity for the host-side target,
   but that identity must be bound into the signed protocol payload and remain
   backward-compatible and versioned.
2. An incoming join link is addressed to a recipient's local project, which
   the current host-signed SLO1 cannot know. Naming the host project in SLO1
   would not select the recipient project. A recipient-created, authenticated
   routing envelope/handle or an explicit user-selected-project action may be
   required, and those are different contracts.

Until that distinction is designed and authorized, SYN-057 must not add a
project field, decode Link cryptography in the daemon, persist selected-project
state, or change `URI_AMBIGUOUS` behavior.

## First-slice disposition

- Task definition and active-task transition: complete.
- Identity/trust reconstruction: complete and recorded here.
- Safe deterministic resolver implementation: **not possible with current
  link/request data; intentionally not started**.
- Protocol change: none.
- New ADR: none; no production architecture or protocol decision was accepted.
- Current task state: ACTIVE / protocol-gap analysis complete, awaiting an
  explicit signed targeting contract or a separately authorized protocol task.
- Next smallest slice: decide and specify the signed recipient-targeting
  contract, including SLO1/SLA2 versioning, backward compatibility,
  downgrade/spoofing tests, and ownership of recipient project selection.

## Source and test basis

- `cli/src/main/java/org/synesis/cli/daemon/DaemonServer.java`
- `cli/src/main/java/org/synesis/cli/daemon/DaemonProtocol.java`
- `workspace/src/main/java/org/synesis/workspace/discovery/KnownProjectRegistry.java`
- `workspace/src/main/java/org/synesis/workspace/transport/control/ControlPlaneHttpHandler.java`
- `workspace/src/main/java/org/synesis/workspace/transport/control/ControlPlaneLinkOperations.java`
- `workspace/src/main/java/org/synesis/workspace/lifecycle/codex/ProjectRuntimeHost.java`
- `link/src/main/java/org/synesis/link/protocol/TraversalInvitation.java`
- `link/src/main/java/org/synesis/link/protocol/TraversalOffer.java`
- `link/src/main/java/org/synesis/link/protocol/TraversalAnswer.java`
- `workspace/src/test/java/org/synesis/workspace/discovery/KnownProjectRegistryTest.java`
- `cli/src/test/java/org/synesis/cli/daemon/DaemonServerTest.java`
- `workspace/src/test/java/org/synesis/workspace/transport/control/ControlPlaneHttpHandlerTest.java`

The source tests cover registry identity/liveness and the existing unresolved
daemon path; no current focused resolver test can prove exact multi-project
dispatch because no trustworthy target field exists. Existing installed
evidence proves conservative ambiguity rejection, not deterministic selection.
