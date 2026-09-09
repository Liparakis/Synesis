# SYN-009E release-profile comparison — 2026-09-09

The reusable comparison harness is
`scripts/release-profile-comparison.ps1`. It extracts customer-style ZIP
archives, validates the `developer`, `protection-lite`, and optional
`maximum-release` profile markers, runs bounded cold-process launcher smokes,
records archive/extracted sizes, and writes machine-readable JSON. It does not
inspect source classes and it does not treat a missing maximum archive as a
pass.

## Current CLI run

The run used five cold `version` launches for each available profile on the
same Windows x64 host. The inputs were the current developer distribution and
the current protection-lite distribution:

| Profile | Archive bytes | Extracted bytes | Files | Median cold startup |
|---|---:|---:|---:|---:|
| developer | 46,504,281 | 69,542,917 | 197 | 715.513 ms |
| protection-lite | 45,911,491 | 68,870,411 | 191 | 685.841 ms |

The archive delta is `-592,790` bytes and the extracted-size delta is
`-672,506` bytes for this lite run. The startup difference is not a
performance-win claim: this is a small, non-isolated cold-process sample and
does not cover UI latency, snapshot latency, Link establishment, route
selection, relay throughput, or provider lifecycle.

The wrapper-level Windows process did not expose a reliable peak working-set
value, so memory is recorded as `NOT_AVAILABLE`; no memory claim is made.

The JSON result is
`docs/evidence/syn-009e-profile-comparison-2026-09-09.json` with status
`PARTIAL_MAXIMUM_ARCHIVE_NOT_SUPPLIED`. A licensed maximum-release archive is
still required to complete the three-profile comparison.

This evidence is profile-engineering data only. It does not promote
protection-lite to maximum protection or change the blocked status of Rings
1–6 and the partial status of Ring 7.
