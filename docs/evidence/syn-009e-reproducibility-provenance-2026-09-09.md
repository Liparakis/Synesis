# SYN-009E reproducibility and signing provenance evidence — 2026-09-09

## Classification

`IMPLEMENTED_SEAM_NOT_EXECUTED`

This evidence records the reproducibility/provenance seam added to the CLI and
relay maximum-release flows. It is not evidence of a protected maximum
artifact: no commercial protector adapter, licensed configuration, production
signing key, customer bundle, or signed maximum release record was available
in this checkout.

## Required private release record

The maximum-release request and private `release-record.properties` now bind:

- source commit and clean-tree state;
- a sorted lockfile count and SHA-256 snapshot;
- JDK/runtime and Gradle versions;
- Node/npm and native Go toolchain versions;
- protector name/version and configuration path/SHA-256;
- Tier 0–3, keep-rule, and protected-acceptance inventory/procedure
  SHA-256 digests, echoed by the adapter result;
- release ID and protection seed; and
- after manifest signing, signing key ID, fixed publication time, bootstrap
  public-key SHA-256, manifest/signature hashes, and detached-signature
  verification provenance.

Missing Node.js, npm, or Go toolchain metadata fails the maximum provenance
gate.
The production signing task separately requires the injected signing key,
signing key ID, and publication timestamp; no key is generated or committed.

## Current checkout snapshot

| Field | Observed value |
|---|---|
| Parent source commit | `b26b223d13fddd3e1f689b51844510002739ec89` |
| Clean release checkout | `false` — unrelated pre-existing work and the preserved `CP-0758` checkpoint remain dirty/untracked |
| Lockfile count | `9` |
| Lockfile snapshot SHA-256 | `85c65042af6259370b21ca1d66cecd9ad8d7951816319b81059b1f7e4d0bc809` |
| Gradle | `9.7.1` |
| Java runtime | `Temurin-25+36 (25+36-LTS)` |
| Java toolchain target | `25` |
| Node.js | `v22.19.0` |
| npm | `11.6.2` |
| Native toolchain | `go1.26.5 windows/amd64` |
| Commercial protector/configuration | `NOT_AVAILABLE` |
| Production signing authority | `NOT_AVAILABLE` |

The lockfile digest is the SHA-256 of UTF-8 lines sorted by repository-relative
path, each line containing `relative-path<TAB>file-SHA-256` and a final newline.
The nine files are discovered from the repository while excluding `.git`,
`build`, and `node_modules` directory segments.

## Verification performed

- `:cli:tasks :relay:tasks --no-configuration-cache --no-daemon --console=plain`
  passed with the documented process-local JDK loopback-directory override.
  This evaluated both Gradle scripts and their new provenance-echo gates; it
  did not invoke a maximum release.
- The normal inherited host environment still fails before Gradle evaluation
  with the previously recorded loopback-connection error.
- No maximum request/result, customer bundle, private release record, signed
  manifest, or production archive was created by this evidence run.

## Open acceptance

Run the maximum flow only from a clean reviewed commit with a version-pinned
licensed commercial adapter/configuration and injected production signing
authority. Verify that the adapter output echoes the request provenance where
required, then compare the private record with the final artifact manifest and
detached signature before executing the complete shipped-artifact acceptance.
