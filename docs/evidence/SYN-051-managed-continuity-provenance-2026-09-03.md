# SYN-051 managed-continuity build provenance — 2026-09-03

This record is the provenance gate for managed-Codex runtime verification. It
does not claim that authenticated managed-Codex acceptance passed.

## Source

- Source HEAD before the SYN-051 implementation slice:
  `e801d54c2e08d31b0fd9dbfbb8b8593d90d0af8f`
- Implementation source HEAD used for this build:
  `fd450495daf591959c7572f2842e99723de77963`
- Clean build command:
  `.\gradlew.bat clean :cli:installDist --no-daemon --max-workers=1 --console=plain`
- Result: `BUILD SUCCESSFUL`.

## Produced and installed artifacts

The installed distribution is `cli/build/install/synesis`. The source jars
and their installed copies have matching SHA-256 hashes:

| Artifact                                                     | SHA-256                                                            |
|--------------------------------------------------------------|--------------------------------------------------------------------|
| `workspace/build/libs/workspace-0.1.0-SNAPSHOT.jar`          | `ea7501e7e57fd728daa6448fa794596441b47a975fd2290e59e7e1b879beea1b` |
| `mcp/build/libs/mcp-0.1.0-SNAPSHOT.jar`                      | `dc79eee420e9962da8712cb2a2028b83f327f88ba3a540e542b210df849a8185` |
| `cli/build/libs/cli-0.1.0-SNAPSHOT.jar`                      | `baadc04e282f603153f524426e851db249405887cfbb82db89429c427f4b39ee` |
| `cli/build/install/synesis/lib/workspace-0.1.0-SNAPSHOT.jar` | `ea7501e7e57fd728daa6448fa794596441b47a975fd2290e59e7e1b879beea1b` |
| `cli/build/install/synesis/lib/mcp-0.1.0-SNAPSHOT.jar`       | `dc79eee420e9962da8712cb2a2028b83f327f88ba3a540e542b210df849a8185` |
| `cli/build/install/synesis/lib/cli-0.1.0-SNAPSHOT.jar`       | `baadc04e282f603153f524426e851db249405887cfbb82db89429c427f4b39ee` |
| `cli/build/install/synesis/bin/synesis.bat`                  | `b8bcb137eb83659360f332c6ca6c17b328300844f83f28f82977cdec56c0670e` |

The installed launcher smoke command was:

`.\cli\build\install\synesis\bin\synesis.bat version`

It returned `SYNESIS_VERSION=0.1.0-dev.local`, `BUILD_PLATFORM=windows-x64`,
and `JAVA_RUNTIME=25+36-LTS`. The installed runtime jars match the produced
source jars byte-for-byte by SHA-256.

## Authentication gate

The current user Codex home is `%USERPROFILE%\.codex`. Its `auth.json` exists,
and no `cli_auth_credentials_store` keyring setting was present. The managed
adapter therefore classifies authentication as `UNSAFE_FILE_AUTH` and fails
closed. The file was not read, copied, logged, or modified.

Safe authenticated managed-Codex model-turn acceptance remains blocked until
Codex keyring-backed authentication is available. No provider or build logic
was changed to work around this host condition.
