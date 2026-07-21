# SYN-009A Package and Module Boundaries

```text
:link
  ↑
:project-record
  ↑
:workspace
  ↑
:cli
```

The CLI is the composition root. It owns Picocli parsing, terminal formatting,
exit codes, and the one `synesis` Application distribution.

`:workspace` owns application orchestration under
`org.synesis.workspace.application`. It may call Link and project-record
components, but it does not parse Picocli, print command output, or exit the
process. Provider-specific JSON remains in:

- `org.synesis.workspace.integration.antigravity`
- `org.synesis.workspace.integration.claude`

Provider-independent evaluation is in `org.synesis.workspace.guardrail`.

`:project-record` contains record models, validation, storage, and sync logic;
it imports neither workspace nor provider packages. `:link` remains transport,
identity, and session infrastructure and does not know project semantics.

The boundary is checked by `:workspace:architectureCheck` and by the direct
Gradle dependencies. No new module or interface is introduced for SYN-009A.
