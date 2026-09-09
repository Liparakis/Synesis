# SYN-009E static reverse-engineering comparison — 2026-09-09

The reusable archive-only scanner is
scripts/release-reverse-engineering-comparison.ps1. It extracts customer ZIPs,
validates the developer, protection-lite, and optional maximum-release
markers, and compares static signals without reading source classes or
publishing decompiled proprietary code.

## Current CLI run

The run compared the current Windows x64 developer and protection-lite CLI
archives. No licensed maximum archive was supplied:

| Profile | Archive bytes | Extracted bytes | Class entries | Internal classes | Static architecture-term hit classes | Source-file metadata classes | Private/source/map entries |
|---|---:|---:|---:|---:|---:|---:|---:|
| developer | 46,504,281 | 69,542,917 | 3,247 | 856 | 967 | 3,244 | 0 |
| protection-lite | 45,705,419 | 68,661,707 | 3,156 | 765 | 549 | 2,395 | 0 |

The free-hardening rerun enabled standard ProGuard optimization and mixed-case
renaming and stopped retaining source/line attributes in the application
rules. The transformed implementation classes were repackaged under
`org/synesis/p`; explicitly kept entrypoints remained stable. Architecture-term
hits fell to 549 classes, but this is a static signal change, not proof of
control-flow protection, virtualization, packing, or reduced decompiler
readability.

Both archives contained zero source-map entries and zero file/entry-name
matches for private mappings, seeds, provenance, debug symbols, or source
files. The scanner still found SourceFile and .java-name signals inside many
class entries in both profiles, although the lite source-metadata count fell by
`849` classes. That is metadata, not source content, and remains an open
leakage-hardening decision for the commercial release rather than a lite PASS.

javap is available on the host; CFR, Procyon, and jd-cli were not found. No
decompiler or selected crown-jewel method inspection was run. The scanner
therefore records staticInspection.decompiler=NOT_RUN.

The JSON result is
docs/evidence/syn-009e-re-comparison-2026-09-09.json with status
PARTIAL_MAXIMUM_ARCHIVE_NOT_SUPPLIED. Required open gates are a real
developer-versus-maximum comparison, ordinary decompiler/protected-method
inspection, and artifact evidence for genuine control-flow transformation,
virtualization, and protected loading. No commercial ring is promoted.
