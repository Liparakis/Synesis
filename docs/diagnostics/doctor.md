# Doctor

Run `synesis doctor --project <path>` for local project, profile, record, and
provider diagnostics. Doctor does not repair state, recreate identities, or
rewrite malformed configuration.

Provider checks include metadata, configuration parsing, managed hook count,
launcher/profile existence, and isolated synthetic protected/unrelated hook
checks. Known limitations are warnings: Antigravity `run_command` mutations
are not inspected, Antigravity real-agent re-planning is not completed, and
Claude Code remains experimental.
Codex trust is reported as `REVIEW_REQUIRED`/`UNKNOWN`; project-local Codex
hooks require explicit `/hooks` review, and Codex remains `DEGRADED` until a
real authenticated denial/re-plan/hash experiment is recorded.

`DOCTOR_RESULT=HEALTHY_WITH_WARNINGS` exits successfully. Broken local
provider or record state produces `DOCTOR_RESULT=BROKEN` and exit code `3`.

The bootstrapper's `doctor --install-dir <root>` validates the installation
root directly and reports `INSTALL_ROOT`, `INSTALL_LAYOUT=FLAT_STABLE`,
`INSTALLED_VERSION`, `LAUNCHER`, `PATH_STATUS`, and `DOCTOR_RESULT`. It does
not inspect a version pointer or select an active version directory.
