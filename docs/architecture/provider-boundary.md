# Provider boundary

`ProviderApplicationService` resolves project state and delegates lifecycle
operations to the static `ProviderRegistry`. Provider integrations only know
their provider configuration path, hook group, managed identifier, matcher, and
payload contract.

Codex is the first provider with a provider-specific managed-entry shape: its
project-local `.codex/hooks.json` entry uses the documented `commandWindows`
override and no synthetic `id` field. This remains a narrow lifecycle hook,
not a general provider framework.

Provider hook execution remains:

```text
provider JSON -> provider hook adapter -> ActionGuardrail -> typed constraints
```

Provider code does not discover projects, verify records, or implement scope
matching. Provider metadata is machine-local at
`.synesis/local/providers/<provider>.json`; it contains paths and health state,
never keys, records, credentials, or tokens.
