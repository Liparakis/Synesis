<div align="center">

# 🧩 Synesis

**A local-first coordination and constraint-enforcement layer for independently running AI coding agents.**

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/status-early%20developer%20preview-orange)](#implemented-today)
[![Java](https://img.shields.io/badge/Java-25-red?logo=openjdk&logoColor=white)](#five-minute-start)
[![Go](https://img.shields.io/badge/Go-1.26.5-00ADD8?logo=go&logoColor=white)](#five-minute-start)
[![Build tool](https://img.shields.io/badge/build-Gradle%20Wrapper-02303A?logo=gradle&logoColor=white)](#five-minute-start)
[![Transport](https://img.shields.io/badge/transport-QUIC-8A2BE2)](#implemented-today)
[![MCP Tools](https://img.shields.io/badge/MCP-tools-11-informational)](#implemented-today)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](AGENTS.md)

Local identity. Workspace boundaries. Peer sessions over Synesis Link. Bounded provider integrations.

</div>

---

> ⚠️ **Early developer preview.** This is not a production security, compliance, or policy guarantee. Provider hooks can
> be bypassed, direct connectivity can fail, and APIs may change.

## Implemented today

- 🔗 **Synesis Link** — authenticated peer identity, candidate exchange, QUIC sessions, bounded control/liveness
  behavior, graceful close, and a bounded application stream.
- 🗂️ **Project-local state** — initialization, identity bootstrap, signed onboarding invitations, typed constraints,
  provider hooks, workspace verification, lifecycle inspection/cleanup, repair, and reconciliation diagnostics.
- 🛠️ **Unified tooling** — a single `synesis` CLI and stdio MCP server with exactly 11 tools. One persistent MCP
  connection owns one provider binding; worker sessions and worktrees remain isolated.
- 🔌 **Provider integrations**

  | Provider | ID | Maturity |
      |---|---|---|
  | Antigravity | `antigravity` | `beta` |
  | Codex | `codex` | `experimental`, trust-review limited |
  | Claude Code | `claude` (canonical) / `claude-code` (alias) | `experimental` |

- 🏗️ **Builds** — Java 25 Gradle builds and a Go bootstrapper for distribution artifacts.

## Five-minute start

**Requirements:** Java 25, the Gradle Wrapper, and Go 1.26.5 (for bootstrapper development).

From the repository root:

```powershell
.\gradlew.bat :cli:installDist --dependency-verification=strict
& ".\cli\build\install\synesis\bin\synesis.bat" --help
```

In a project directory, initialize local state and inspect it:

```powershell
synesis init
synesis provider list
synesis doctor
```

Install one provider integration with its canonical ID, for example:

```powershell
synesis provider install claude
synesis provider status claude
```

Normal work uses the provider's managed hook or MCP connection. Read files through Synesis, apply revision-bearing
patches, and stop when identity, ownership, freshness, or workspace verification fails.

📖 See the [getting-started guide](docs/getting-started/README.md) and [provider guides](docs/providers/README.md).

## Roadmap

- [x] Local project initialization and isolated provider workspace state
- [x] Authenticated local/two-process Link and application-stream evidence
- [x] Bounded provider hooks and an 11-tool MCP surface
- [x] Read-only doctor plus cleanup, repair, and reconciliation flows
- [ ] Trusted real-agent validation for every provider
- [ ] Physical cross-network validation beyond the recorded limited evidence
- [ ] Production packaging hardening, signing replacement, and notarization
- [ ] Remote coordination, rendezvous, relay fallback, and hole-punching

> Incomplete items are future work, not current product claims. Synesis does **not** currently provide hosted services,
> rendezvous, relay fallback, or remote multi-machine coordination.
> The [deferred capability register](docs/agent/DEFERRED.md) is authoritative.

## Documentation map

| Topic                      | Link                                                                     |
|----------------------------|--------------------------------------------------------------------------|
| Getting started            | [docs/getting-started/README.md](docs/getting-started/README.md)         |
| Provider guides            | [docs/providers/README.md](docs/providers/README.md)                     |
| Architecture               | [docs/architecture/README.md](docs/architecture/README.md)               |
| Operations                 | [docs/operations/README.md](docs/operations/README.md)                   |
| Development & verification | [docs/development/build-and-test.md](docs/development/build-and-test.md) |
| Security model             | [docs/security/THREAT_MODEL.md](docs/security/THREAT_MODEL.md)           |
| Release & signing notes    | [docs/release/RELEASE_READINESS.md](docs/release/RELEASE_READINESS.md)   |
| Repository agent contract  | [AGENTS.md](AGENTS.md)                                                   |
| Security reporting         | [SECURITY.md](SECURITY.md)                                               |

## License

Synesis is licensed under the **GNU Affero General Public License v3.0 only** (SPDX: `AGPL-3.0-only`). See [
`LICENSE`](LICENSE).
