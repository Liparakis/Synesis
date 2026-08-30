# Artifact matrix

| Platform      | Runnable artifact | Embedded installer | Expected native runner |
|---------------|-------------------|--------------------|------------------------|
| windows-x64   | `synesis-windows-x64.exe` | self-extracting file | `windows-2025`         |
| windows-arm64 | `synesis-windows-arm64.exe` | self-extracting file | `windows-11-arm`       |
| linux-x64     | `synesis-linux-x64` | self-extracting file | `ubuntu-24.04`         |
| linux-arm64   | `synesis-linux-arm64` | self-extracting file | `ubuntu-24.04-arm`     |
| macos-x64     | `synesis-macos-x64` | self-extracting file | `macos-15-intel`       |
| macos-arm64   | `synesis-macos-arm64` | self-extracting file | `macos-15`             |

The runner labels are current GitHub-hosted labels; if a runner is unavailable,
the workflow must report `NOT_SUPPORTED_BY_RUNNER` rather than claim execution.

GitHub Actions publishes each row independently as the artifact
`synesis-<platform>` (for example, `synesis-windows-x64`). Each artifact
contains one runnable self-extracting file with the CLI, bundled Java runtime,
native MCP launcher, and native installer. The installer is self-contained and
does not auto-install a provider. No separate bootstrap, metadata,
aggregate-release-candidate, or install-script artifact is produced.
