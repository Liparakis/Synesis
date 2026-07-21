# Current Task

## Identity

- Task ID: SYN-004
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0081
- Latest checkpoint: CP-0081
- Responsible agent: fresh coding agent
- Related decisions: none

## Objective

Reduce the two-person workspace demo to the fewest safe operator steps. Update the CLI: `sync host` takes optional `--project` and `--record` and outputs a query-parameterized invitation link; `sync join` parses the link, pins the connection to the host Node ID, and runs sync. Clean error exit code `10` is accompanied by stderr contextual `HINT:` messages. No change to wire protocols, record schemas, or storage.

## Planning state

SYN-PRODUCT-REVIEW is DONE at CP-0080. Design is complete in `docs/agent/SYN_004_DESIGN.md`.

## Work completed

None yet (this session is planning-only).

## Verification

Resume, doctor, deferred register, and fixtures validators pass.

## Current failures

None.

## Immediate next action

Implement connection link parsing and validation unit tests.
