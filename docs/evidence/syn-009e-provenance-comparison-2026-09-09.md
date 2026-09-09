# SYN-009E maximum-release provenance comparison — 2026-09-09

Status: **IMPLEMENTED SEAM / NOT EXECUTED AGAINST A COMMERCIAL ARTIFACT**.

## What is covered

`scripts/maximum-release-provenance-comparison.ps1` compares two private
`release-record.properties` files and the canonical private artifact manifests
they name. It requires both records to describe clean `maximum-release`
builds for the selected component, verifies each manifest hash against its
record, verifies signed-record provenance and non-empty private recovery/ring
evidence hashes, and compares the exact source/toolchain/protector/configuration
and source-scoped inventory/procedure digest fields.

It has two explicit modes:

- `Diversification` requires different release IDs, different seeds, and
  different canonical artifact-manifest hashes while the shared provenance
  remains equal.
- `Reproducibility` requires the same release ID, seed, shared provenance, and
  canonical artifact-manifest hash. ZIP archive metadata is deliberately not
  used as a reproducibility signal.

The evidence output contains release IDs, source/protector identifiers,
manifest hashes, entry counts, and one-way seed fingerprints only. It does not
copy seeds, mappings, retrace files, native symbols, or private filesystem
paths.

## Verification

- PowerShell AST parse: **PASS**, zero parser errors.
- Missing-record invocation: **PASS fail-closed behavior**; returned
  `PARTIAL_MAXIMUM_RECORDS_NOT_SUPPLIED` with an explicit open gate and exit
  code `2`.
- Real maximum private records/manifests: **NOT SUPPLIED**. No commercial
  protector or maximum-release artifact is installed locally, so neither
  diversification nor reproducibility is claimed.

## Required release evidence

Run both modes against records from the licensed release environment, storing
the JSON evidence outside the customer archive:

```powershell
.\scripts\maximum-release-provenance-comparison.ps1 `
  -Component cli `
  -Mode Diversification `
  -FirstRecord .\private\release-1.0\release-record.properties `
  -SecondRecord .\private\release-1.1\release-record.properties `
  -EvidenceFile .\private\comparison\cli-diversification.json

.\scripts\maximum-release-provenance-comparison.ps1 `
  -Component cli `
  -Mode Reproducibility `
  -FirstRecord .\private\repro-a\release-record.properties `
  -SecondRecord .\private\repro-b\release-record.properties `
  -EvidenceFile .\private\comparison\cli-reproducibility.json
```

Repeat for `relay`. These comparisons supplement, rather than replace, the
installed shipped-artifact, signing, tamper, UI/control-plane, Link/overlay,
provider, native, performance, and commercial six-ring acceptance.
