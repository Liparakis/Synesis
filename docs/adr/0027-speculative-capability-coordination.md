# ADR-0027: Speculative capability coordination MVP

## Status

Accepted for SYN-012.

## Decision

Add a bounded `:coordination` module between project records and workspace
execution. It owns semantic capability ownership, prediction contracts, an
append-only per-project event log, deterministic lifecycle projections, and
the coordinator/supervisor protocol. The first implementation is one local
coordinator process using JDK file persistence, signed binary envelopes, HTTP
commands, and replayable server-sent events. Node identities authenticate
commands and events; supervisor and worker identifiers remain logical claims.

The MVP keeps the existing `:link` transport/session layer unaware of projects,
tasks, ownership, or prediction state. Existing SDR2 records remain the
verified project knowledge plane; they are not replaced by the coordination log.
Semantic ownership is durable. Temporary execution leases may expire, but an
expired lease never transfers ownership.

## Context and alternatives

The Collaborative Agent Fabric material calls for local supervisors, a shared
coordination/knowledge plane, semantic ownership, ordered live updates, and
isolated work. A PostgreSQL schema, broker/WebSocket tier, Obsidian projection,
and general knowledge graph would be premature for the first two-agent proof:
they increase deployment and failure surface before the protocol is exercised.
The file log plus HTTP/SSE seam preserves those contracts and can be replaced
behind the module boundary when evidence requires it.

An in-memory-only coordinator was rejected because restart and replay are core
acceptance criteria. A shared mutable owner table was rejected because it would
blur durable ownership with temporary leases. A Link-layer implementation was
rejected because it would make the transport depend on product semantics.

## Security, failure, and performance

Every command/event is bounded, canonical, hash-chained, and Ed25519 signed.
The first server binds to loopback; remote HTTPS, certificate pinning, and
multi-host enrollment are explicitly later work. Duplicate command identifiers
must be idempotent. SSE is at-least-once: clients reconnect with a sequence
cursor and deduplicate by event identifier. A bounded subscriber queue may
force reconnect/replay rather than dropping durable events. Corrupt, reordered,
or unverifiable files fail closed during startup.

The event store is deliberately small and per project. Its fitness target is
the two-supervisor demonstration, not unbounded throughput; migration to a
database or broker requires measured evidence from that path.

## Fitness functions and invalidation

Focused coordination tests must prove deterministic contract encoding, event
signature/hash verification, ordered replay after restart, and illegal state
transition rejection. The next slices must add an HTTP/SSE reconnect test,
signed command idempotency, provider `REQUEST_OWNER` behavior, isolated
worktree/speculation metadata, and a real two-process acceptance transcript.
Reconsider this ADR only when those tests or observed scale/security evidence
show the file-backed local coordinator is insufficient.
