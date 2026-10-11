# ADR-0090 — A capability request names a file, and the lane claiming it is the owner

## Status

Accepted on 2026-10-11. Decided by the repository owner after the five-agent
runs recorded in `docs/agent/CURRENT.md` for that date. The MCP tool catalog
changes additively: `request_coordination` kind `capability_request` gains one
optional payload field.

## Context

A capability request is recorded against the authority lineage of one lane. The
requester had to supply that lineage as `ownerAuthorityLineageId`, or leave it
out and let Synesis use the only other open lane. Nothing durable said which
lane provides which capability, so with more than two lanes the address was
whatever the requester believed.

Two real five-agent runs failed on it.

- Three agents that had each been refused a claim on `docs/USAGE.md` moved on
  to other parts of the goal and then requested the storage API. Each sent the
  lineage it had been shown in the refusal. The manual told it to: an
  overlapping claim was answered with "use the exact `ownerAuthorityLineageId`
  in the conflict result, then submit a capability request". All three
  requests were recorded against the lane writing the usage notes, which could
  never answer them.
- Agents that sent no lineage were answered `owner_required` with the
  capability name and nothing else. They had no way to learn the lineage of the
  storage lane, repeated the request, and the run ended at the resume limit.

A declaration by the owner ("this lane provides capability X") was considered.
It adds a field to every lane announcement, its persisted form and its
amendment rules, and it depends on two agents choosing the same identifier
independently. An owner that omits it is never told, and the requester still
has nothing to go on.

Ownership of a file is already recorded. Claims are exclusive: at most one open
lane of another participant claims any path. A requester knows which file it
builds on, because it imports it or documents it.

## Decision

- A capability request may carry `ownerPath`, a repository-relative file. The
  open lane of another participant that claims that file is the owner, and the
  request is recorded against its lineage.
- A named file decides alone. A lineage sent with it does not overrule the
  claim. When no other lane claims the file the request is not recorded: the
  single-other-lane guess is not used, and neither is the lineage.
- `ownerAuthorityLineageId` and the single-other-lane guess keep their meaning
  for a request that names no file.
- A request whose owner cannot be told is answered with the open lanes, each
  with its goal and the files it claims, and with what to send instead.
  `get_next_action` shows the same for a declared dependency not yet requested.
- A recorded request answers with the goal and claims of the lane it went to.
- A refused claim no longer tells the caller to request a capability from the
  lane it collided with. The agent manual says to name the file.

## Consequences

- No new durable state. The owner of a capability is whoever holds the claim on
  its file at the moment the request is recorded; after that the request is
  bound to that lane's lineage as before.
- A requester can still address the wrong lane by sending only a lineage. It is
  then shown which lane received the request.
- A request for a file nobody has claimed yet waits for a lane to claim it. If
  no lane ever does, that part of the goal is unowned, which is a fact about
  the run and not something addressing can repair.
- A capability implemented across several lanes' files is requested from one of
  them. This has not come up.
- The catalog digests pinned by `McpToolCatalogGoldenTest` change. A provider
  manual installed by an older build no longer attests until
  `synesis provider install` is run again, as after any change to the manual.
