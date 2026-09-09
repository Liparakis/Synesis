# SYN-009E native hardening audit — 2026-09-09

## Result

The archive-only native audit passed the current structural hardening checks for
the Windows x64 developer and protection-lite CLI archives:

```text
native hardening: PASS for supplied developer/lite archives
native signing: OPEN_NOT_SIGNED_OR_UNTRUSTED
overall: PARTIAL_MAXIMUM_ARCHIVE_NOT_SUPPLIED
```

This is a bounded static audit of Synesis-owned Go launchers. It is not
commercial maximum evidence, does not inspect third-party Netty binaries, and
does not claim native anti-reversing or cross-platform coverage.

## Command and inputs

```powershell
.\scripts\release-native-hardening-audit.ps1 `
  -Component cli `
  -DeveloperArchive .\cli\build\distributions\synesis-0.1.0-dev.local-windows-x64.zip `
  -ProtectionLiteArchive .\cli\build\protection-lite\synesis-0.1.0-dev.local-windows-x64-protection-lite.zip `
  -EvidenceFile .\docs\evidence\syn-009e-native-hardening-2026-09-09.json
```

The script extracts each ZIP to a disposable temporary directory and audits the
two owned native files under `bin/`:

```text
bin/synesis-installer.exe
bin/synesis-mcp.exe
```

It does not mutate the archive or the source checkout. The machine had Go
`go1.26.5 windows/amd64` available. The archive-embedded Go metadata reports
`-trimpath=true`, `CGO_ENABLED=0`, and the expected Windows x64 target.

## Checks

Both supplied profiles passed all of the following:

| Check | Result | Meaning |
|---|---|---|
| Owned native files | PASS | Both expected Synesis launchers are present. |
| PE format | PASS | Both are valid PE32+ x64 binaries. |
| COFF symbols | PASS | COFF symbol count is zero. |
| Debug directory | PASS | No PE debug directory is present. |
| Debug sections | PASS | No `.debug`, DWARF, or STABS section was found. |
| Named exports | PASS | No named PE exports were found. |
| Local source paths | PASS | No tested absolute/local source-path signal was found. |
| Private native files | PASS | No PDB, dSYM, debug, symbol, map, or source-map file was shipped. |
| Go trimpath metadata | PASS | Both binaries report `trimpath=true`. |

The developer and protection-lite native launcher hashes are identical:

```text
synesis-installer.exe  be49af2297c665ee170adef4a28f84cfce67447ea3d57a77d4eef0ca8800caa7
synesis-mcp.exe        a6b0bc30a60df7748c33beaa26d4aa72820e59e628db497a22ec4803f26c24f0
```

That is expected for the current protection-lite profile: it transforms the
selected JVM distribution while the native bootstrap inputs remain the same.
It is not evidence that the native layer is protected by the commercial rings.

The release build seam already uses `-trimpath` and `-ldflags=-s -w` in
`cli/build.gradle.kts`. A separate same-checkout two-build probe also produced
matching SHA-256 output for `synesis-mcp.exe`; that probe is supporting
reproducibility evidence, not a release reproducibility claim.

## Open gates

- No maximum-release archive was supplied, so the audit cannot close the
  maximum profile or any commercial ring.
- The current release pipeline does not implement Authenticode, Apple Developer
  ID signing, or notarization. The extracted native files are unsigned, so
  native signing remains an explicit release gate.
- The current developer/lite archives record `vcs.modified=true` in Go build
  metadata. They are therefore not clean release-provenance artifacts.
- Linux, macOS, ARM64, third-party native libraries, signed production
  artifacts, and protected-loader behavior remain unverified.

The machine-readable result is
`docs/evidence/syn-009e-native-hardening-2026-09-09.json`.
