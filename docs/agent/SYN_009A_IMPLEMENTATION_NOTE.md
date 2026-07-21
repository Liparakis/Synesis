# SYN-009A Implementation Note

## Current repository truth

1. Public entry points are `synesis` from `:cli`, `synesis-workspace` from
   `:workspace`, and `synesis-project-record` from `:project-record`. The
   requested task retires the workspace launcher; the unified `synesis` CLI is
   the only user-facing application launcher for this slice.
2. The module graph is `:project-record -> :link`, `:workspace -> :link,
   :project-record`, and `:cli -> :link`; the CLI does not yet depend on
   `:workspace`.
3. The former `WorkspaceCli` contained argument parsing, process exit/error
   formatting, project/configuration and record orchestration, sync host/join
   lifecycle, constraint evaluation, and hook stdin/stdout adaptation. It is
   now an internal `WorkspaceOperations` helper; public parsing and formatting
   live in `:cli`.
4. The justified service boundaries are `ProjectApplicationService`,
   `SyncApplicationService`, `ConstraintApplicationService`,
   `GuardrailApplicationService`, and `HookApplicationService`. They will be
   public, Picocli-free, console-free, and return typed results. No generic
   result abstraction or new Gradle module is warranted.
5. The target project state is:

   ```text
   <project>/.synesis/project.json
   <project>/.synesis/shared/records/
   <project>/.synesis/local/profile/link/
   <project>/.synesis/local/profile/records/
   <project>/.synesis/local/providers/
   <project>/.synesis/local/runtime/
   ```

   `project.json` contains only schema version, project ID, and creation time;
   all identity, profile, provider, runtime, and machine-private state is
   local.
6. Expected code changes are the `:workspace` application package/classes and
   tests, `:cli` command/runtime wiring and tests, both build scripts,
   package-boundary checks, and the requested architecture/CLI/layout/state
   documentation. `:link` protocol classes and `:project-record` domain types
   are not expected to move.
7. No public release exists, so the unreleased `synesis-workspace` launcher
   alias and old `project.conf` layout may be removed after replacement tests
   pass. Provider management, expanded doctor, and portable packaging remain
   out of scope.

## Architecture decision summary

- Mode: EVOLUTION.
- Selected architecture: one modular monolith with `:cli` as composition root
  and `:workspace` as a library of application services.
- Evidence: current Gradle files, CP-0095, existing process tests, and the
  requested local-first project workflow.
- Unknowns: adoption scale, external deployment, and physical network
  topology. They do not alter this local CLI/module decision and remain
  reversible; performance validation is limited to existing process tests.
- Fitness functions: Gradle dependency direction, source import audit,
  service tests, generated launcher checks, project discovery/init tests, and
  strict full verification.
