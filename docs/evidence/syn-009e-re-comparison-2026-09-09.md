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
| protection-lite | 45,911,491 | 68,870,411 | 3,161 | 770 | 584 | 3,158 | 0 |

The lite archive retained six internal package prefixes and introduced
obfuscated org/synesis/a, org/synesis/b, and org/synesis/c prefixes in the
scanned class entries. This is a static signal change, not proof of
control-flow protection, virtualization, packing, or reduced decompiler
readability.

Both archives contained zero source-map entries and zero file/entry-name
matches for private mappings, seeds, provenance, debug symbols, or source
files. The scanner still found SourceFile and .java-name signals inside most
class entries in both profiles. That is metadata, not source content, and
remains an open leakage-hardening decision for the commercial release rather
than a lite PASS.

javap is available on the host; CFR, Procyon, and jd-cli were not found. No
decompiler or selected crown-jewel method inspection was run. The scanner
therefore records staticInspection.decompiler=NOT_RUN.

The JSON result is
docs/evidence/syn-009e-re-comparison-2026-09-09.json with status
PARTIAL_MAXIMUM_ARCHIVE_NOT_SUPPLIED. Required open gates are a real
developer-versus-maximum comparison, ordinary decompiler/protected-method
inspection, and artifact evidence for genuine control-flow transformation,
virtualization, and protected loading. No commercial ring is promoted.
