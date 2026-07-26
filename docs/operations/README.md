# Operations

- [Doctor](../diagnostics/doctor.md) is read-only and reports bounded local
  health findings.
- `synesis cleanup` inventories and executes only approved cleanup plans.
- `synesis reconcile` handles bounded crash-recovery plans.
- `synesis repair` manages explicit repair plans with backup and rollback.
- [Two-machine testing](TWO_MACHINE_TESTING.md) defines the evidence boundary;
  local and two-process success is not a universal connectivity claim.
- [Updates and signing](../release/signing.md) documents signed-bundle and
  updater behavior; production key replacement remains deferred.
