# SYN-010B aggregation implementation note

## Current workflow graph

`java` gates the build. `bundles` builds and native-smoke-tests six Java
bundles. `bootstrap` cross-compiles six Go bootstrappers. `bootstrap-native`
runs the native Linux bootstrap subprocess tests. `manifest` downloads the
matrix outputs, creates and validates the manifest, and signs protected tags.

The current workflow uploads 6 `bundle-*` artifacts, 6 `bootstrap-*` artifacts,
and 1 `release-manifest` artifact: 13 visible artifacts before aggregation.

## SYN-010B graph

The matrix jobs upload short-lived internal artifacts named
`internal-bundle-<platform>` and `internal-bootstrap-<platform>`. `manifest`
uploads `internal-release-manifest`. The final
`aggregate-release-candidate` job downloads those inputs, runs the reusable Go
aggregator, uploads exactly `synesis-release-candidate`, and deletes the
internal artifacts from the workflow run.

The six bootstrap artifacts come from `bootstrap`; the six Java bundles come
from `bundles`; `manifest` remains the manifest-generation job. Native smoke
tests remain in the matrix bundle jobs and are still prerequisites of the
aggregation job.

## Final layout

```text
synesis-release-candidate/
  install/install.ps1
  install/install.sh
  bootstrap/synesis-bootstrap-{windows-x64,windows-arm64,linux-x64,linux-arm64,macos-x64,macos-arm64}[.exe]
  bundles/synesis-<version>-<platform>.<archive>
  manifest.json
  manifest.json.sig
  checksums.txt
  VERSION
  README.md
```

The Go aggregator rejects missing or duplicate platforms, unexpected files,
version mismatches, checksum mismatches, invalid signatures, and unsafe ZIP or
tar paths. Branch manifests may remain explicitly unsigned only when marked
`developmentOnly`; protected tags require a valid detached signature.

The staged directory is the same source used for the final Actions artifact.
Future GitHub Release publication is intentionally separate: releases may
still expose individual platform assets for bootstrap downloads.
