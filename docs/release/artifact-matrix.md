# Artifact matrix

| Platform | Java bundle | Bootstrap | Expected native runner |
|---|---|---|---|
| windows-x64 | ZIP | `.exe` | `windows-2025` |
| windows-arm64 | ZIP | `.exe` | `windows-11-arm` |
| linux-x64 | tar.gz | binary | `ubuntu-24.04` |
| linux-arm64 | tar.gz | binary | `ubuntu-24.04-arm` |
| macos-x64 | tar.gz | binary | `macos-15-intel` |
| macos-arm64 | tar.gz | binary | `macos-15` |

The runner labels are current GitHub-hosted labels; if a runner is unavailable,
the workflow must report `NOT_SUPPORTED_BY_RUNNER` rather than claim execution.
