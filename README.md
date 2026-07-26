# Synesis

Synesis is a local-first coordination and constraint-enforcement layer for
independently running AI coding agents. It gives a project durable local
identity and workspace boundaries, connects peers through Synesis Link over
QUIC when the network permits, and exposes bounded provider integrations.

This is an early developer preview, not a production security, compliance, or
policy guarantee. Provider hooks can be bypassed, direct connectivity can fail,
and APIs may change.

## Implemented today

- Synesis Link: authenticated peer identity, candidate exchange, QUIC sessions,
  bounded control/liveness behavior, graceful close, and a bounded application
  stream.
- Project-local initialization, identity bootstrap, signed onboarding
  invitations, typed constraints, provider hooks, workspace verification,
  lifecycle inspection/cleanup, repair, and reconciliation diagnostics.
- A unified `synesis` CLI and stdio MCP server with exactly 11 tools. A single
  persistent MCP connection owns one provider binding; worker sessions and
  worktrees remain isolated.
- Provider integrations: Antigravity (`beta`), Codex (`experimental` and
  trust-review limited), and Claude Code (`experimental`). `claude` is the
  canonical provider ID; `claude-code` remains an accepted input alias.
- Java 25 Gradle builds and a Go bootstrapper for distribution artifacts.

## Five-minute start

Requirements are Java 25, the Gradle Wrapper, and Go 1.26.5 for bootstrapper
development. From the repository root:

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

Normal work uses the provider's managed hook or MCP connection. Read files
through Synesis, apply revision-bearing patches, and stop when identity,
ownership, freshness, or workspace verification fails. See the [getting-started
guide](docs/getting-started/README.md) and [provider guides](docs/providers/README.md).

## Roadmap

- [x] Local project initialization and isolated provider workspace state
- [x] Authenticated local/two-process Link and application-stream evidence
- [x] Bounded provider hooks and an 11-tool MCP surface
- [x] Read-only doctor plus cleanup, repair, and reconciliation flows
- [ ] Trusted real-agent validation for every provider
- [ ] Physical cross-network validation beyond the recorded limited evidence
- [ ] Production packaging hardening, signing replacement, and notarization
- [ ] Remote coordination, rendezvous, relay fallback, and hole-punching

Incomplete items are future work, not current product claims. Synesis does not
currently provide hosted services, rendezvous, relay fallback, or remote
multi-machine coordination. The [deferred capability register](docs/agent/DEFERRED.md)
is authoritative.

## Documentation map

- [Getting started](docs/getting-started/README.md)
- [Provider guides](docs/providers/README.md)
- [Architecture](docs/architecture/README.md)
- [Operations](docs/operations/README.md)
- [Development and verification](docs/development/build-and-test.md)
- [Security model](docs/security/THREAT_MODEL.md)
- [Release and signing notes](docs/release/RELEASE_READINESS.md)
- [Repository agent contract](AGENTS.md)
- [Security reporting](SECURITY.md)

## License

Synesis is licensed under the GNU Affero General Public License v3.0 only
(SPDX: `AGPL-3.0-only`). See [`LICENSE`](LICENSE).
