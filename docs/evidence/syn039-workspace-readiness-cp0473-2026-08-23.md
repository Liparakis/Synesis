# SYN-039 workspace-readiness slice — CP-0473 — 2026-08-23

## Scope

This slice addressed only the confirmed Codex project-resolution gap after
CP-0472. No review, handoff, ownership, integration, cleanup, or Doctor
redesign was attempted.

## Reproduction and trace

The CP-0472 fresh run used two independent GPT-5.6 Luna High agents. Both
received the typed state `workspace_not_ready` with only `RECOVER →
ensure_session`; coordination sequence stayed zero and no WorkGroup state was
created.

Codex provider installation writes the managed global MCP entry. If that entry
has no explicit project and the provider sends no MCP roots, the MCP process
directory becomes the active project. `AgentSessionService.ensureSession` then
resolves the wrong project before binding/readiness can converge and correctly
returns `WORKSPACE_NOT_READY` with `ENSURE_SESSION`. No coordination state is
created by that fail-closed path.

The CP-0472 fixture was project `6148fa85-90b7-4cbc-8400-51d0d43d2541` at
baseline `7c8d341`. The fresh CP-0473 fixture was project
`b2180ef0-b9e1-42d1-99fd-23448bde51f6` at baseline `393001a`.

## Production change

Codex TOML installation now writes the initialized project root into the
existing MCP argv as `--project <root>`. The existing two-argument public
configuration methods remain available; project installation uses the new
explicit-root overload. No readiness check, trust check, binding ownership,
process anchor, or command namespace gate was weakened.

Changed files:

- `workspace/src/main/java/org/synesis/workspace/provider/codex/CodexTomlConfiguration.java`;
- `workspace/src/main/java/org/synesis/workspace/application/provider/ProviderMcpConfigurationService.java`;
- `workspace/src/test/java/org/synesis/workspace/ProviderApplicationServiceTest.java`.

Commit: `bea47c4 Pin Codex MCP sessions to initialized projects`.

## Verification

Focused readiness/provider/session/MCP/coordination tests passed, including
fresh Codex installation, explicit project argv, repeated `ensure_session`,
and two distinct independent session bindings. Strict coordination, workspace,
and MCP Javadocs passed. Deferred and fixture validators passed; Go vet and
`git diff --check` passed.

A direct MCP process using the generated explicit project entry returned
`status=ready`, `workspace=isolated` for the CP-0473 project.

The fresh unattended two-agent rerun still stopped before coordination. Agent
A and Agent B both repeatedly received `workspace_not_ready`; Agent B reported
that its available MCP build treated project schema v2 as unsupported and that
the fresh copy did not contain the repository resume script. No WorkGroup,
claim, request, grant, snapshot, validation, integration, or closure state was
created. This is recorded as a stale/incompatible agent-harness distribution
blocker, not as evidence to weaken readiness fail-closed behavior.

The root `check` progressed through compilation, packaging, Javadocs, CLI,
coordination, and link checks, then was stopped at the recurring
`McpServerTest.setUp:45` Git subprocess stall. Thread evidence showed
`ProcessCommandRunner.execute:81` → `GitProcessRunner.runInternal:129` →
`ManagedBaselineTransactionService.prepare:132`, with the test worker waiting
for the Git process and an output collector blocked on its stream. No timeout
was enlarged. The first root-check attempt also found committed trailing
whitespace in CP-0471/CP-0472; those two checkpoint lines were corrected.

Bootstrap `go test -count=1 ./...` separately failed in the pre-existing
versioned-install migration tests (`TestBootstrapInstallUpdateRollbackDoctorAndUninstall`,
`TestLegacyLayoutMigration`, and `TestPreparedVersionedUpdateRetainsPayloadAndRollsBack`:
`update migrations not prepared`). This is separate from SYN-039 readiness.

Doctor remains `DEGRADED` with the existing command namespace reconciliation,
command retention/capacity, and provider migration warnings. They are not
readiness inputs in `WorkspaceReadinessService` and remain separately
classified.

## Boundary conclusion

The project-root pin is the smallest production correction for provider MCP
startup when MCP roots are absent. The unattended product acceptance remains
blocked before lifecycle creation by the incompatible MCP distribution used by
the agent harness. No SYN-040 was created and nothing was pushed.
