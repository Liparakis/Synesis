# SYN-009E protection-lite evidence — 2026-09-09

## 2026-09-09 free-hardening follow-up

The free protection-lite rules were tightened without adding a commercial
protector: standard ProGuard optimization and mixed-case renaming are enabled,
and `SourceFile`/`LineNumberTable` retention was removed from the CLI and relay
application rules. The compatibility keep rules and Java verification remain
in place.

Verification passed for the CLI bundle smoke, integrity, and provenance tasks,
and for the standalone relay smoke and provenance tasks, using ProGuard
7.10.0. The independent maximum-release acceptance correctly rejected the
candidate with `Candidate profile marker is not maximum-release: protection-lite`.

The refreshed CLI candidate is `45,711,321` archive bytes and `68,673,953`
extracted bytes, with archive SHA-256
`236cec027bfaadf21e219d103ee8f5443f4fcaca7d474e4175d31567f7d2369b`. The
archive-only comparison recorded `3,156` class entries, `765` internal class
entries, `581` architecture-term-hit classes, and `2,395` source-metadata
classes; source-metadata signals fell by `849` versus the developer archive,
but were not eliminated. The five-sample profile comparison recorded a
`630.098 ms` median startup and `84,520,960` bytes maximum aggregate
process-tree working set for lite versus `629.620 ms` and `108,498,944` for
developer. These are bounded baseline measurements, not performance or
physical-memory claims.

This follow-up is recorded in commit `ae4b9852` and checkpoint `CP-0816`.
This remains compatibility/artifact hardening only. It does not claim string
encryption, control-flow obfuscation, virtualization, protected packing,
anti-VM, anti-debugging, anti-instrumentation, or commercial maximum release.

## Baseline

- checkout at the start of this slice: `8a5d0901b4859c9a504f7fc7009def23177f1d43`;
- scoped release-hardening commit: `654d229` (`Add release-only protection
  profiles`); `origin/master` remains unchanged;
- normal developer bundle smoke remained passing before protection work;
- developer install tree: 23 files / 10,346,001 bytes;
- developer platform bundle: 197 files / 69,542,918 bytes;
- developer distribution directory: 2 files / 99,917,528 bytes; and
- packaged frontend build: 0.52 kB HTML, 19.72 kB CSS, 222.03 kB JavaScript
  (68.50 kB gzip; 1,580 transformed modules).

## Lite build

Command used:

```powershell
$env:TEMP = 'C:\t\synesis-build-temp'
$env:TMP = 'C:\t\synesis-build-temp'
.\gradlew.bat :cli:protectionLiteBundleSmokeTest :cli:protectionLiteIntegrityCheck :cli:protectionLiteProvenance `
  '-PsynesisJmods=<approved-jdk-jmods>' `
  --dependency-verification=strict --no-configuration-cache --console=plain
```

Observed tool/runtime facts:

- ProGuard 7.10.0 executed from the locked Gradle dependency;
- Java 25 executed the protected bundle (`25+36-LTS`); and
- the selected explicit analysis library image was the local Java 21 JMOD
  directory because the available Java 25 installations expose `lib/modules`
  rather than a usable JMOD directory. This is recorded as a local fallback,
  not approved production release configuration.

Protected output measurements:

- protected JVM payload: 1,624,469 bytes;
- protected ZIP candidate: 45,911,493 bytes;
- private mapping: 885,198 bytes;
- private seeds: 232,212 bytes;
- private usage report: 100,003 bytes;
- private artifact manifest: generated and verified against every staged
  payload file;
- protected JAR entries: 775, including 770 class entries;
- UI resource root and packaged assets remained present; and
- transformed classes were renamed while the explicitly required CLI/MCP
  entrypoint contracts remained callable.

Size and bounded startup observations from the same Windows host:

- readable CLI platform bundle: 69,542,918 bytes / 197 files;
- protection-lite CLI bundle: 68,870,410 bytes / 191 files;
- readable CLI ZIP: 46,504,284 bytes;
- protection-lite CLI ZIP: 45,911,492 bytes;
- readable standalone relay install tree: 4,779,659 bytes / 14 files; and
- protection-lite relay tree: 4,436,149 bytes / 14 files.

Five cold-process `version` samples were 497, 666, 570, 534, 524 ms for the
developer launcher (median 534 ms) and 492, 461, 467, 454, 483 ms for the
protection-lite launcher (median 467 ms). These are bounded observations, not
a performance win claim: no controlled host isolation, repeated JVM warm-up
protocol, memory profile, UI latency, Link route, or relay throughput study
has been completed.

## Acceptance result

`protectionLiteBundleSmokeTest` passed after two compatibility fixes discovered
by the staged artifact itself:

1. removing unsafe `-dontpreverify` after the Java 25 launcher produced a real
   `VerifyError`; and
2. preserving Picocli enum descriptors/constants after reflective option
   parsing exposed an NPE.

The passing archive gate exercised CLI version/help, UI command metadata and a
bounded no-browser UI server, native installer version output, project init,
provider lifecycle, doctor, native MCP launcher → protected CLI version, and
stdio MCP initialize/tools/session behavior.

The standalone relay protection-lite archive also passed its transformed
entrypoint smoke and reached the guarded argument parser. Post-change Link and
relay Java compilation, tests, Javadocs, and static analysis passed with the
documented process-local loopback setting. The aggregate `:link:check
:relay:check` command still stops in `formatCheck` before compilation because
preserved checkpoint files contain trailing whitespace; those checkpoints were
not rewritten as part of this task.

The integrity gate verified every staged file against the private manifest,
verified that mapping/provenance/source material was absent from the customer
archive, retained the packaged UI inside the protected CLI payload, and showed
that a non-mutating change to `VERSION` produces a digest mismatch. This is a
manifest-detection foundation, not a signed maximum-release tamper refusal.

An archive-only static comparison later found zero file/entry-name matches for
private mappings, seeds, provenance, debug symbols, source files, or source
maps in the developer and protection-lite ZIPs. It did find `SourceFile` and
`.java`-name metadata signals in most class entries in both profiles. That is
not source content and does not invalidate the file-level leakage result, but
it leaves class-metadata leakage open for the commercial profile; it is not
reported as a complete lite leakage `PASS`.

Private provenance records the source commit, dirty-tree result, tool/runtime,
analysis library image, rules hash, seed, protected JAR hash, and shipped
archive hash. The working tree is intentionally dirty while this task is in
progress; that record is not a release approval.

## Limitations and disposition

This evidence does not prove the Seven Rings. No licensed commercial protector
is installed in this checkout, and the protected relay/parser result is only a
lite boundary rather than maximum-release live overlay evidence. The signed
final manifest, tamper refusal, private
retrace/diversification, leakage, performance, and debugger/VM safety gates
remain open. `maximum-release` therefore remains fail-closed and the current
commercial profile is `PARTIAL`.

The explicit maximum gate was also invoked without a protector and failed with
the expected message: `Maximum release is blocked: set
-PsynesisMaximumProtector or SYNESIS_MAXIMUM_PROTECTOR ... protection-lite is
not a substitute.` This is intentional evidence of the no-fake-capability
boundary.
