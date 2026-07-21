# SYN-009B Implementation Note

## Audit

- Project metadata: `<project>/.synesis/project.json`.
- Shareable project state: `<project>/.synesis/shared/records/`.
- Local-only state: `<project>/.synesis/local/profile/`, `providers/`, and
  `runtime/`; provider metadata belongs in `local/providers/`.
- Existing provider adapters: `org.synesis.workspace.integration.antigravity`
  and `org.synesis.workspace.integration.claude`.
- Existing verified hook contracts: Antigravity workspace
  `<project>/.agents/hooks.json`, `PreToolUse`, and structured mutation matcher;
  Claude Code project hook configuration in `docs/integration/claude-code-hook.json`
  using `PreToolUse` and the existing `hook claude-code` command.
- Existing shared runtime evaluation: `ActionGuardrail`, called through
  `HookApplicationService`; provider lifecycle code will not evaluate records
  or match scopes itself.
- Existing atomic-write patterns: `ProjectApplicationService`, `ProjectConfig`,
  and `DecisionStore` use temporary sibling files with atomic replacement and a
  safe fallback.
- JSON dependencies: none; SYN-009B will use a small JDK-only JSON value parser
  and writer scoped to provider configuration.

## Design

- `ProviderSupportLevel` contains `BETA`, `EXPERIMENTAL`, and `UNAVAILABLE`.
- A small static `ProviderRegistry` exposes only `antigravity` and
  `claude-code`.
- `ProviderApplicationService` resolves the project/profile, delegates to
  provider integrations, and returns structured results without Picocli or
  console output.
- Provider metadata is `<project>/.synesis/local/providers/<id>.json`; it stores
  schema, provider, support level, timestamps, configuration path, launcher,
  profile, managed-entry ID, and last synthetic result. It contains no secrets,
  keys, records, or credentials.
- Antigravity modifies `<project>/.agents/hooks.json`; Claude Code modifies the
  existing project hook configuration path confirmed by its integration example.
  Each managed hook has a stable `id` and is merged without removing unrelated
  groups, events, hooks, or unknown fields.
- Configuration and metadata writes use temporary siblings and atomic replace
  where available. Malformed existing JSON is rejected without overwrite.
- Synthetic checks create temporary project/profile/SDR2 state and invoke the
  existing provider hook adapter; real-agent validation remains explicitly
  incomplete.

## Expected files

`workspace`: provider model/registry, JSON/configuration utility, provider
integrations, and `ProviderApplicationService` plus focused tests.

`cli`: provider command tree, provider commands, runtime wiring, and doctor
integration plus focused tests.

`docs`: ADR-0021, provider boundary, provider management, doctor, integration
documentation, current state, and durable task state.
