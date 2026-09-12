# ADR-0074: Deep-link recipient selection and signed return routing

## Status

Accepted — protocol v2 serialization/binding, bounded daemon routing, and the
authenticated local selection control surface are authorized.

## Context

SYN-057 discovery established that current SLO1 and SLA2 links contain signed
Link/session/peer data but no trustworthy destination project or runtime
identity. The daemon therefore correctly returns `URI_AMBIGUOUS` when multiple
live project runtimes exist. Broadcasting or testing a capability-bearing link
against every runtime would violate isolation and would make runtime order an
authority decision.

SLO1 and SLA2 are different routing problems. SLO1 is an invitation arriving
at a recipient installation, where the remote sender cannot choose an
arbitrary local project. SLA2 is an answer returning to the host installation,
which already knows the project that created the invitation.

## Proposed decision

### SLO1 recipient selection

Do not put a recipient-local project target in the host invitation. The daemon
computes candidates only from validated `KnownProjectRegistry` entries and
existing runtime start/reuse semantics:

- zero candidates returns `URI_UNRESOLVED`;
- one candidate is selected automatically;
- multiple candidates return `PROJECT_SELECTION_REQUIRED` and a bounded,
  authenticated, one-shot local selection challenge.

The choice is ephemeral per invitation. There is no persisted current-project
preference in SYN-057. The selected project runtime performs all SLO1 Link
verification.

### SLA2 return routing

The approved v2 link formats carry a signed `ReturnTarget` in the host-signed
SLO1 traversal offer and copy it into the joiner-signed SLA2 answer:

```text
ReturnTarget {
    returnTargetFormat: 1
    returnProjectId: UUID
}
```

The stable project UUID identifies the host project, not the recipient project.
The existing session ID and offer digest bind it to the exact exchange. The
daemon may structurally read the UUID to select a candidate, but the selected
runtime must verify the unchanged full SLA2, target equality, signature,
expiry, replay, identity, membership, and operation state.

Stable UUID is proposed over an opaque handle for the first slice because it
uses existing durable project identity and registry data, survives daemon
restart without a new route table, and carries no path/name/secret. It does
introduce stable cross-link correlation. If that disclosure is unacceptable,
the alternative is an opaque signed handle plus a bounded runtime-registered
route index; that alternative requires a separate explicit privacy decision.

### Versioning

Existing format 1 remains unchanged. The authorized implementation uses
explicit format 2 for the SLO1 wrapper/offer and SLA2 answer, with mandatory signed
return-target fields. The nested `SessionInvitation` and Link transport
`ProtocolVersion.V1` remain unchanged. New parsers may explicitly accept v1
and v2; old parsers reject v2. No optional trailing field or unsigned query
parameter is allowed.

## Authority boundaries

- Link protocol classes own wire format, canonical encoding, signing, and
  verification.
- The host project runtime creates and signs the return target.
- The join project runtime copies it into the signed answer.
- The daemon performs only local candidate selection, stable-ID lookup, runtime
  start/reuse, and liveness checks.
- The selected runtime remains the sole Link and project authority.
- A routing target can choose a candidate only; it cannot authorize Link
  mutation, membership, commands, or browser bootstrap.

Current pending Link operations are in-memory. A daemon/runtime restart may
therefore resolve the project but still receive `OPERATION_NOT_FOUND`; SYN-057
must not claim durable operation continuity.

### Authenticated local selection surface

When ambiguous SLO1 selection is requested with browser opening enabled, the
daemon may open the existing runtime UI as a neutral control-plane host. The
browser uses that runtime's existing bootstrap/session and CSRF contract to
read `GET /api/v1/selection/{selectionId}` and submit
`POST /api/v1/commands/select-project`. The projection contains only the
selection ID, expiry, and validated project ID/display name pairs. The submit
body contains only the selection ID and chosen project ID. The original SLO1
URI remains in the daemon's ephemeral selection store and is dispatched only
after the one-shot claim succeeds.

## Consequences

The design preserves fail-closed behavior for old links, ambiguous local SLO1
selection, stale projects, duplicate runtimes, malformed/tampered links, and
runtime operation loss. The implementation exposes only the bounded
authenticated selection projection and one-shot claim path; it does not create
a persistent route table or move Link authority into the daemon.

Detailed field tables, compatibility, threat analysis, acceptance criteria,
and the proposed tests are recorded in
`docs/evidence/SYN-057-routing-protocol-design-2026-09-11.md`.
