# SYN-009E maximum acceptance harness hardening — 2026-09-09

This slice hardens the independent maximum-release shipped-artifact harness.
It does not create or claim a maximum-protection artifact.

## Changes

- Renamed the host-platform variable so PowerShell's read-only automatic
  `$IsWindows` variable cannot prevent the harness from starting.
- Private artifact manifests now have to cover every regular file in the
  extracted customer bundle, not only the entries they declare.
- When the private manifest is supplied, the harness mutates one disposable
  extracted file, requires the SHA-256 manifest to reject it, and restores the
  original bytes. This is a static digest-difference check, not runtime
  tamper-response evidence.
- Customer-bundle leakage checks now include release records, mapping/seed/
  retrace files, common keystores and private-key containers, native debug
  files, and private/mapping/symbol directories.

## Verification

| Check | Result |
| --- | --- |
| PowerShell AST parse | PASS; zero parser errors |
| CLI protection-lite negative probe | PASS; rejected at `protection-lite` profile marker |
| Relay protection-lite negative probe | PASS; rejected at `protection-lite` profile marker |
| Real maximum artifact execution | NOT EXECUTED; no licensed adapter/artifact is installed |
| Runtime immutable-payload tamper acceptance | NOT EXECUTED; remains an explicit maximum-release gate |

The commercial rings remain unchanged: no commercial capability is promoted
by this harness-only slice.
