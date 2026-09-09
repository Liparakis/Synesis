# SYN-009E signed-integrity release-record boundary

Date: 2026-09-09

## Change

The maximum CLI and standalone-relay release records now make the release
boundary explicit:

- `commercialRings` records the six mandatory commercial transformation rings
  (Rings 1–6) required from the external adapter;
- `diversification`, `privateRetraceFile`, and
  `privateNativeSymbolsDirectory` preserve the adapter's private recovery and
  release-specific evidence; and
- `signedIntegrity=verified` is written only after the existing bootstrap
  Ed25519 signature has been verified against the embedded trust root.

The adapter contract and configuration skeleton now state that signed
distribution integrity is Gradle/bootstrap-owned. A vendor result property
cannot promote an integrity setting to evidence, and Ring 7 combines that
release-level signed-integrity layer with diversification and private retrace.

## Verification boundary

The build-script changes are limited to the existing maximum-release private
record and documentation seam. No customer artifact, production key, release
seed, commercial license, or ordinary developer task was changed.

The maximum profile remains `PARTIAL / commercial execution required`: no
licensed adapter is installed, so no real maximum archive, protected loader,
runtime tamper refusal, retrace execution, or installed protected acceptance
was run. The new record fields will only be produced by a successful licensed
maximum release after the existing fail-closed adapter, archive, manifest,
signature, and independent acceptance gates pass.
