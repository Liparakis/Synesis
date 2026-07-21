# Current Task

## Identity

- Task ID: SYN-003
- Status: DONE
- Priority: P0
- Started checkpoint: CP-0076
- Latest checkpoint: CP-0079
- Responsible agent: fresh coding agent
- Related decisions: ADR-0010, ADR-0011, ADR-0012, ADR-0013, ADR-0014

## Objective

Implement CP-W3 only: add `decision search` and `decision inspect --record <uuid>` subcommands to `:workspace` launcher, search only verified heads, inspect only the validated current head of the requested record, stable and safe output, and add generated-launcher tests.

## Planning state

SYN-002 is DONE at CP-0075. SYN-001 is DONE at CP-R4 and CP-R5 remains deferred. ADR-0014 selects `:workspace` as a composition layer; it owns no new record or protocol format.

## Work completed

CP-W3 implementation is complete in `:workspace`. Added `decision search` using `DecisionSearch` and `decision inspect --record <uuid>` using a dedicated validator that validates the chain of only the requested record. Output formats are stable, safe, and order-stable. Generated launcher process integration tests verify search, inspect, restart, duplicate sync, empty search, malformed filters, corruption, conflicts, and stale revisions. Demo documentation is updated with sanitized commands and outputs. Frozen production boundaries remain unchanged.

## Verification

CP-W3 focused tests, the full strict root build (`clean check`), and all repository validators (resume, deferred, fixtures, doctor) now pass.

## Current failures

Physical operation, retries, reconnect, discovery, membership, background sync, and broader CAF behavior remain unclaimed. CP-W3 has no known verification failure.

## Immediate next action

Stop for product-level review.
