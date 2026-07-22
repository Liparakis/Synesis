# Synesis

Synesis is an experimental coordination and constraint-enforcement layer for
independently running AI coding agents. The repository currently ships the
Synesis Link transport/session layer, a terminal CLI, and a small bootstrapper.

## Status

This repository is an early developer preview. It is not production-ready and
is not a security, compliance, or policy guarantee for an AI coding agent. APIs,
provider hooks, build outputs, and documentation may change without notice.

## What is here

- Authenticated peer sessions over QUIC with bounded control and liveness behavior.
- Verified SDR2 and PRP1 protocol paths, typed constraints, and action guardrails.
- Provider lifecycle support with explicit support levels:
    - Antigravity: BETA.
    - Claude Code: EXPERIMENTAL.
    - Codex: EXPERIMENTAL and REVIEW_REQUIRED/DEGRADED.
- Cross-platform Java distributions with a Go bootstrapper. Go is used for
  distribution bootstrap, not as a replacement for the Java implementation.

## Limitations

Synesis is not an LLM, coding-agent runtime, Git replacement, or enterprise
governance system. It does not guarantee that a model or provider obeys every
constraint, and local or provider-side bypasses remain possible. Direct peer
connectivity is not guaranteed. Production signing, platform notarization,
enterprise hardening, and other deferred capabilities are not claims of this
preview; see [`docs/agent/DEFERRED.md`](docs/agent/DEFERRED.md).

Do not use this preview with secrets, sensitive data, or untrusted artifacts.

## Build

Requirements: Java 25, the Gradle Wrapper, and Go 1.26.5 for bootstrapper work.

```powershell
.\gradlew.bat clean check --dependency-verification=strict
```

```powershell
go test ./...
go vet ./...
```

Build and inspect the local CLI distribution:

```powershell
.\gradlew.bat :cli:installDist --dependency-verification=strict
& ".\cli\build\install\synesis\bin\synesis.bat" --help
```

The launcher supports local diagnostics such as `synesis init`, `synesis
provider list`, `synesis provider install antigravity`, and `synesis doctor`.
There is no hosted installer or public release artifact in this preview.

## Documentation

- [`docs/architecture/`](docs/architecture/) — architecture and protocol notes.
- [`docs/installation/provider-management.md`](docs/installation/provider-management.md) — provider lifecycle.
- [`docs/installation/bootstrap-install.md`](docs/installation/bootstrap-install.md) — bootstrap installation.
- [`docs/adr/0025-cross-platform-release-and-signing.md`](docs/adr/0025-cross-platform-release-and-signing.md) — release
  model.
- [`docs/security/THREAT_MODEL.md`](docs/security/THREAT_MODEL.md) — threat model.
- [`SECURITY.md`](SECURITY.md) — reporting and handling guidance.

## License

Synesis is licensed under the GNU Affero General Public License v3.0 only
(SPDX: `AGPL-3.0-only`). Commercial licenses are available for organizations
that want to embed, modify, or distribute Synesis without AGPL obligations.
See [`LICENSE`](LICENSE) and
[`docs/legal/LICENSE_DECISION_REQUIRED.md`](docs/legal/LICENSE_DECISION_REQUIRED.md).
