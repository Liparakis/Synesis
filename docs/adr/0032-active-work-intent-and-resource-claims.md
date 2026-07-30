# ADR-0032: Active work intent and resource claims

## Status

Accepted for SYN-020.

## Decision

Extend the existing modular-monolith coordination event log with authenticated
work intents and atomic repository-relative resource claims. The first slice
supports exact files and directory subtrees. `:coordination` owns records,
normalization, overlap evaluation, events, and projections; `:workspace` owns
session-bound mutation authorization; CLI and MCP remain thin adapters.

An MCP mutation requires an owned compatible claim. Reads remain available.
Claims are never transferred by timeout alone and direct filesystem writes
outside Synesis-managed mutation paths remain outside the portable guarantee.

## Rationale

Isolated worktrees prevent physical checkout interference but do not tell a
second worker that another worker intends to implement the same file. A
single signed, deterministic claim decision closes that discovery gap without
introducing a broker, remote service, or shared mutable control checkout.

## Rejected alternatives

- Permanent OS/file locks conflate physical files with semantic ownership and
  do not cover isolated worktrees.
- A new broker or database adds deployment and trust boundaries without local
  scale evidence.
- AGENTS.md instructions alone cannot enforce pre-mutation authorization.

## Revisit when

Measured local event-log limits, a verified remote coordination requirement,
or a provider requiring a separate security boundary invalidates the local
modular-monolith assumptions.
