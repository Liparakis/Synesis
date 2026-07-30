# Unified CLI Command Reference

The only public application launcher is `synesis`.

```text
synesis
  help | version
  init [--project <path>]
  identity show
  provider list|install|status|uninstall|migrate <provider>
  project create
  sync host|join
  constraint create
  workspace check-action|verify|mutate
  hook antigravity|claude|codex
  doctor | cleanup | reconcile | repair | migrate
  mcp --provider <codex|antigravity|claude>
```

Ordinary project commands discover `.synesis` from the current directory. An
advanced `--profile <dir>` override is available on project, identity, sync,
check-action, and hook commands for isolated process tests and cross-profile
experiments. Hook JSON stays on stdout; diagnostics stay on stderr and hook
exit code remains 0 for provider contract responses.

Provider installation commands are project-local. `synesis version` is safe to
run without a project and reports embedded build metadata. Provider commands
use canonical IDs only.
