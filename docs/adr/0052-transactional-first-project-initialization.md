# ADR-0052: Keep first project initialization behind the baseline safety gate

- Status: Accepted
- Date: 2026-09-02
- Task: SYN-046

## Decision

For a new project, `ProjectApplicationService.init` must perform the existing
managed-baseline safety preflight before creating project-local `.synesis`
directories. The existing `ManagedBaselineTransactionService` remains the
only Git transaction protocol. Initialization may create local profile,
identity, records, and runtime directories only after the baseline operation
has accepted the checkout and returned successfully.

## Context and evidence

This is an EVOLUTION change to the local modular monolith. The first KogMaw
bootstrap attempt was rejected with `CONTROL_CHECKOUT_DIRTY`, but
`ProjectApplicationService.init` had already created `.synesis`; the partial
state then interfered with later retries. The failure occurred before any
valid baseline commit was available, so premature project-local creation had
no useful recovery value.

## Alternatives considered

- Keep the current ordering and rely on retry cleanup: rejected because it
  leaves ambiguous state after a normal safety rejection.
- Add a second initialization transaction: rejected because the existing
  baseline transaction already owns Git safety and recovery semantics.
- Delete `.synesis` from a failed attempt: rejected because initialization
  cannot safely distinguish newly-created state from pre-existing user state
  without a broader provenance protocol.

## Consequences

Successful unborn-Git initialization continues to create its managed baseline,
then local state. A dirty or otherwise rejected checkout fails before new
project-local state is created. The change does not initialize Git, create
provider sessions, alter provider migration, or resolve stale sessions.

## Invalidation and fitness function

Reopen this decision if a failure can still leave new project-local state
before baseline acceptance, or if post-baseline local-state creation requires
its own recoverable transaction. The focused project test must prove that a
dirty checkout remains free of a new `.synesis` directory and commit, while
existing success and idempotency tests continue to pass.
