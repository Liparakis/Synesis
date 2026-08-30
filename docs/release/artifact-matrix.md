# Artifact matrix

| Platform      | Bundle archive | Installer in bundle | Expected native runner |
|---------------|----------------|---------------------|------------------------|
| windows-x64   | ZIP            | `bin/synesis-installer.exe` | `windows-2025`         |
| windows-arm64 | ZIP            | `bin/synesis-installer.exe` | `windows-11-arm`       |
| linux-x64     | tar.gz         | `bin/synesis-installer` | `ubuntu-24.04`         |
| linux-arm64   | tar.gz         | `bin/synesis-installer` | `ubuntu-24.04-arm`     |
| macos-x64     | tar.gz         | `bin/synesis-installer` | `macos-15-intel`       |
| macos-arm64   | tar.gz         | `bin/synesis-installer` | `macos-15`             |

The runner labels are current GitHub-hosted labels; if a runner is unavailable,
the workflow must report `NOT_SUPPORTED_BY_RUNNER` rather than claim execution.

GitHub Actions publishes each row independently as the artifact
`synesis-<platform>` (for example, `synesis-windows-x64`). The archive contains
the CLI, bundled Java runtime, native MCP launcher, and native installer. The
installer is self-contained and does not auto-install a provider. No separate
bootstrap, metadata, aggregate-release-candidate, or install-script artifact
is produced.
