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

Tagged GitHub releases and manual runs from the Actions **Run workflow** button
publish each row independently as a raw release asset named by the runnable
file (for example, `synesis-windows-x64.exe`). Manual runs require a release tag
such as `v0.1.0`. The file itself is the self-extracting installer with the CLI,
bundled Java runtime, native MCP launcher, and native installer; it is not
wrapped in a ZIP by the workflow. Branch and pull-request runs build and
smoke-test the files but do not publish downloadable artifacts. No separate
bootstrap, metadata, aggregate-release-candidate, or install-script artifact
is produced.
