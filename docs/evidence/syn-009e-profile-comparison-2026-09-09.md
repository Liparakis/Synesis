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
| developer | 46,504,281 | 69,542,917 | 197 | 675.523 ms |
| protection-lite | 45,705,419 | 68,661,707 | 191 | 642.855 ms |

The archive delta is `-798,862` bytes and the extracted-size delta is
`-881,210` bytes for this lite run. The startup difference is `-32.668 ms`, not a
performance-win claim: this is a small, non-isolated cold-process sample and
does not cover UI latency, snapshot latency, Link establishment, route
selection, relay throughput, or provider lifecycle.

The extracted acceptance harness now records bounded `durationMs` values for
provider/doctor, Link onboarding, UI smoke, authenticated control-plane
HTTP/SSE, and relay forwarding checks in its machine-readable result. Those
values are a measurement seam for the eventual maximum archive, not current
developer/lite results; peak memory, route microbenchmarks, commercial
transformation overhead, and AV/EDR impact remain open.

The Windows harness recorded the best-effort aggregate launcher process-tree
working-set scope as `PROCESS_TREE_WORKING_SET_BEST_EFFORT`: developer peaked
at `107,982,848` bytes and protection-lite at `89,432,064` bytes, a numeric
delta of `-18,550,784` bytes. This is not unique physical memory, because
monitor overhead and process scheduling were not controlled.

The JSON result is
`docs/evidence/syn-009e-profile-comparison-2026-09-09.json` with status
`PARTIAL_MAXIMUM_ARCHIVE_NOT_SUPPLIED`. A licensed maximum-release archive is
still required to complete the three-profile comparison.

This evidence is profile-engineering data only. It does not promote
protection-lite to maximum protection or change the blocked/partial status of
the Seven Rings in the maximum-release report.
