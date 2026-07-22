# Unified CLI Command Reference

The only public application launcher is `synesis`.

```text
synesis
  help
  version
  host
  join
  identity show
  init [--project <path>]
  project create --peer <node-id>
  sync host
  sync join <invitation>
  constraint create --title <text> --rationale <text> --scope <scope> [--effect block|warn]
  check-action --scope <scope> --action <text>
  hook antigravity
  hook claude-code
  doctor
```

Ordinary project commands discover `.synesis` from the current directory. An
advanced `--profile <dir>` override is available on project, identity, sync,
check-action, and hook commands for isolated process tests and cross-profile
experiments. Hook JSON stays on stdout; diagnostics stay on stderr and hook
exit code remains 0 for provider contract responses.

Provider installation commands are project-local. `synesis version` is safe to
run without a project and reports embedded build metadata.
