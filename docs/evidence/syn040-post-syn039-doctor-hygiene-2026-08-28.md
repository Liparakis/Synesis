# SYN-040 Post-SYN-039 Doctor hygiene investigation

Date: 2026-08-28  
Starting HEAD: `f5622eba03c7631a7e3c8620a5598e8037ded001` (`feat: complete autonomous workgroup lifecycle`)  
Scope: classify the six Doctor warnings observed after the accepted SYN-039 run. No repair, cleanup, migration
execution, or source-product mutation was authorized.

## Warning inventory

The current packaged bundle (`0.1.0-dev.local`) reports `DEGRADED`, six warnings, zero errors, zero mutations,
`CLEANUP_RECOMMENDED=false`, `RECONCILIATION_RECOMMENDED=true`, and `REPAIR_AVAILABLE=true` against the SYN-039 fixture.

| # | Finding and object                                                                                                                                                                                             | Source and durable state                                                                                                                                                                                                               | Classification and actionability                                                                                                                                                                                                     |
|---|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | `stale_session_lease` — connection `syn039-final-agent-a-20260828-001`; session `session-218acdb4-e18e-4ed7-81d7-d1fd361f45b7`; fingerprint `ddd3a4df5da93a41c40dd8b7bf0b1ba1b9c4f59c60dc54e92f9511056554c054` | `DoctorService.checkSessionsOwnershipWorktrees` reads the external session-lease store. The exact JSON record remains `leaseState=ACTIVE`, with the historical provider process identity and no current process observed.              | **F — requires more measurement.** The accepted run did not capture the MCP child’s EOF/exit path, so stale classification is real but causation by SYN-039 or normal provider exit is not proven. It is not safe to auto-reconcile. |
| 2 | `stale_session_lease` — connection `syn039-final-agent-b-20260828-001`; session `session-220d5039-4e8f-428d-b037-18a5f56ca50e`; fingerprint `a12884695ace9e83fe32d98a2b8567c68161b5f20815af904b0567f8c86c7b37` | Same lease store and same `ACTIVE` durable state; historical process identity is no longer observed.                                                                                                                                   | **F — requires more measurement.** Same conclusion as #1.                                                                                                                                                                            |
| 3 | `command_namespace_reconciliation_required`; affected resource `command_namespace`; fingerprint `f641737456330e6e9c11d1a1b7e0b2e7f366524f36e73bdb966a8ddbfe99337b`                                             | Host-wide `AppData\\Local\\Synesis\\commands`; `namespace.json` reports `objectRevision=7667`, `anchorCount=401`, `scopeCount=360`, `permanentLockCount=360`. Doctor does not render individual record IDs for this aggregate finding. | **D — warning is correct.** Existing repair-plan workflow is actionable; no automatic reconciliation was run. This is administrative command state, not SYN-039 acceptance state.                                                    |
| 4 | `command_capacity_or_retention`; affected resource `command_namespace`; fingerprint `c0905ac8475a08ebe0dae33efa2f25ac4cb48b13fb009d1f0f59b1827ecbcb43`                                                         | Same host-wide namespace. The source condition is one or more eligible terminal records, pins, dead anchors, or capacity conditions; the rendered Doctor contract intentionally exposes the aggregate, not a single object ID.         | **D — warning is correct.** Existing cleanup dry-run is the prescribed action; no cleanup or mutation was run.                                                                                                                       |
| 5 | `provider_migration_required`; provider `antigravity`; fingerprint `ac0a3dfd6dddb20962cecff6ee5fe65e19d3923be20e52c5ab52ff877f7e4c32`                                                                          | `DoctorService.checkProviderConfiguration` delegates to `ProviderConfigMigrationService`; the user-global provider configuration is not the stable launcher configuration.                                                             | **C — user-global configuration is stale.** Existing `synesis provider migrate --dry-run/--prepare/--execute` is the supported path. No user-global file was changed.                                                                |
| 6 | `provider_migration_required`; provider `codex`; fingerprint `57de4cf40144bdf7d00010f2f5557a7d642c2b9705309bfade167dd313e2ca93`                                                                                | Same migration service and same user-global configuration boundary.                                                                                                                                                                    | **C — user-global configuration is stale.** The direct isolated acceptance bundle is correct; it does not make the user-global config current. No migration was executed.                                                            |

The two lease fingerprints are exact SHA-256 values of `stale_lease_` plus the two acceptance connection IDs. They
identify the acceptance-created lease records, but they do not prove whether the provider process ended through clean
MCP EOF or an abnormal path.

## Causal and invariant review

1. A completed binding cannot become recovery eligible: the accepted completion path marks the exact provider binding
   `COMPLETED`; lease liveness separately returns `CLOSED_CLEANLY` as terminal when clean EOF invokes
   `McpProtocolHandler.close`.
2. A completed participant plus released claims plus a completed WorkGroup makes collaboration state non-actionable. The
   final SYN-039 evidence was `ACTIVE WorkGroups=0`, `TASKS=0`, `OWNERSHIPS=0`; the stale lease warning is outside that
   projection.
3. Normal provider exit after explicit completion is terminal/historical in a fresh packaged run. An abnormal provider
   death while active must remain stale/recovery-relevant; changing close handling to hide that would violate
   fail-closed behavior.
4. Therefore no proven product defect exists in the remaining lease warning, and no Doctor correlation change is
   justified from this evidence.
5. The current aggregate provider status label `BOUND` is not used as proof of active work: it does not distinguish
   completed bindings. The exact binding and lease evidence are authoritative.

## Fresh packaged runtime

Disposable fixture: `C:\\t\\syn040-packaged-clean-20260828-001`, project ID `f370eadd-3d0a-4918-aeae-b39d4946bb35`.

Sequence: fresh `synesis-mcp.exe` process -> `initialize` -> `ensure_session` with `no_change_allowed` producer task ->
projected `get_next_action` -> exact projected `finish_lane` payload -> provider EOF -> process exit -> Doctor.

Result: `NO_CHANGE`, `claimsReleased=true`, `workGroupState=COMPLETED`, `MCP_EXIT=0`, isolated Doctor `HEALTHY`, zero
warnings, zero mutations. This is the smallest ordinary workflow proving that clean provider termination does not leave
a stale lease.

## Verification and scope

- Current bundle native hash: `07F23EF1E1C9C6D344CA31A640CAA92BD483345C6F8260DE82A84C69F9E4A53B`.
- Current bundle JAR hash: `E5D10201094A99925E975DC593A8DF606DE7308A080E48652186D07DAE313329`; it differs from the
  earlier closure artifact because the build metadata embeds the current timestamp. Native behavior was unchanged.
- Prior SYN-039 focused tests, strict Javadocs, deferred validation, bundle build, and smoke evidence remain the
  accepted evidence baseline. A fresh Gradle rerun on this host failed before task execution with
  `Unable to establish loopback connection`, including a fresh Gradle cache; this is reported as an environment
  limitation, not as a passing test.
- The five pre-existing lifecycle edits under `workspace/src/main/java/org/synesis/workspace/lifecycle/` and their two
  tests were inspected and excluded: their output truncation/repository-portability behavior has no demonstrated causal
  link to any Doctor finding.
- No source product fix, repair, cleanup, provider migration, push, tag, release, architecture change, or SYN-039
  reopening was performed.
