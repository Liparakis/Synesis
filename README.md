# Synesis

Synesis lets several AI coding agents (Claude Code, Codex) work on one Git project at the same time, on one computer
or across several, without stepping on each other. Each agent claims the files it works on, its changes are reviewed
and validated before they reach the main branch, and only main has to stay stable. Computers that lose their
connection keep working apart and merge again when they meet.

## Install

Download the installer for your platform from the [releases](https://github.com/Liparakis/Synesis/releases) and run
it: `synesis-windows-x64.exe` opens a setup wizard; on Linux and macOS run `synesis-linux-x64` or
`synesis-macos-arm64` (and the other architectures). See [bundle installation](docs/installation/bootstrap-install.md).

To build from a checkout instead, with Java 25:

```powershell
.\gradlew.bat :cli:installDist --dependency-verification=strict
```

## Start

In a Git repository:

```powershell
synesis init --agent claude      # or --agent codex
synesis doctor
```

Then start the agent in the project folder (`claude` or `codex`) and tell it what to do. Watch with
`synesis status`, or `synesis ui` in the browser; what needs you is in `synesis operator list`.
[Getting started](docs/getting-started/README.md) has the rest, and the [CLI reference](docs/cli/command-reference.md)
lists every command.

## Documentation

- [Getting started](docs/getting-started/README.md)
- [Installation](docs/installation/bootstrap-install.md)
- [CLI reference](docs/cli/command-reference.md)
- [Architecture decisions](docs/adr/)
- [Security](docs/security/)

## Code signing policy

Release files and how they are signed: [Code signing policy](CODE_SIGNING_POLICY.md).

## License

[GNU Affero General Public License v3.0 only](LICENSE) (`AGPL-3.0-only`).
