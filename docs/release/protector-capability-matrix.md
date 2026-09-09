# Protector capability matrix

This matrix separates a documented feature from a feature exercised against a
Synesis release artifact. `PASS` means local evidence exists for the stated
scope. `UNVERIFIED` means the candidate may be worth evaluating but has not
been installed and exercised here. `BLOCKED` means the current checkout must
not claim the capability.

| Candidate | Shrink/rename/map | Control flow | String/constant protection | Virtualization | Protected loading/packing | Safe anti-analysis | Current decision |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [ProGuard 7.10.0](https://www.guardsquare.com/manual/home) | `PASS` for the lite JVM payload | `BLOCKED` | `BLOCKED` | `BLOCKED` | `BLOCKED` | `BLOCKED` | selected for `protection-lite` only |
| [yGuard](https://yworks.github.io/yGuard/) | `UNVERIFIED` in this checkout | `BLOCKED` | `BLOCKED` | `BLOCKED` | `BLOCKED` | `BLOCKED` | not selected |
| [R8](https://r8.googlesource.com/r8/) | `UNVERIFIED` for this Java distribution | `UNVERIFIED` | `UNVERIFIED` | `BLOCKED` | `BLOCKED` | `BLOCKED` | not selected; no JVM release proof |
| [DashO](https://support.preemptive.com/hc/en-us) | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` | commercial evaluation candidate; no license/tool in checkout |
| [Zelix KlassMaster](https://www.zelix.com/klassmaster/docs/obfuscateOptions.html) | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` | commercial evaluation candidate; no license/tool in checkout |

## Documentation-only candidate pre-screen

The claim table above remains deliberately `UNVERIFIED` for commercial
candidates. The following is a first-party documentation pre-screen, not
Synesis artifact evidence and not permission to mark any ring `PASS`.

| Candidate | Documentation signals found | Limits that remain for SYN-009E |
| --- | --- | --- |
| DashO 12.8 | PreEmptive's changelog records Java 25 bytecode/JEP support in 12.6, Java 26 bytecode support in 12.8, Gradle/CLI integration, and runtime self-protection improvements. Its project reference documents scoped control-flow and string-encryption transforms. | The reviewed material does not establish genuine JVM code virtualization or a protected customer payload loader. Java 25 Gradle, reflection/ServiceLoader/JNI/native-QUIC compatibility, shipped artifact inspection, safe debugger/VM behavior, and private recovery remain unexecuted. |
| Zelix KlassMaster 26 | Zelix documents processing bytecode through Java 26, script/Gradle invocation, flow obfuscation, string/integer/long encryption, reference/reflection handling, change logs/stack-trace translation, and a reproducible-obfuscated-output tutorial. | The reviewed material does not establish genuine virtual execution, a protected payload loader/packing mechanism, or Synesis-safe anti-debug/instrumentation behavior. The exact Java 25 Gradle/native/Link acceptance, shipped inspection, and private recovery remain unexecuted. |

Detailed links and the exact distinction between vendor documentation and local
artifact evidence are recorded in
`docs/evidence/syn-009e-protector-doc-review-2026-09-09.md`. Neither candidate
is selected as the maximum protector until a licensed version is installed and
the missing Ring 2/Ring 5 and compatibility gates are exercised.

## Required evaluation dimensions

The ring table above is the claim boundary. The following dimensions are the
minimum evaluation record for selecting DashO or Zelix; an `UNVERIFIED`
commercial cell cannot be converted to `PASS` by documentation alone.

| Dimension | ProGuard 7.10.0 local result | yGuard | R8 | DashO | Zelix KlassMaster |
| --- | --- | --- | --- | --- | --- |
| Java 25 input/runtime | `PASS` for the bounded lite run; the analysis JMOD fallback is recorded | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` |
| Gradle and non-interactive CI invocation | `PASS` for protection-lite tasks | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` |
| Shrinking, renaming, package layout, metadata | `PASS` for protection-lite | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` |
| String and constant protection | `BLOCKED` | `BLOCKED` | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` |
| Control-flow transformation | `BLOCKED` | `BLOCKED` | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` |
| Genuine code virtualization | `BLOCKED` | `BLOCKED` | `BLOCKED` | `UNVERIFIED` | `UNVERIFIED` |
| Protected loading or payload packing | `BLOCKED` | `BLOCKED` | `BLOCKED` | `UNVERIFIED` | `UNVERIFIED` |
| Anti-debug and anti-instrumentation | `BLOCKED` | `BLOCKED` | `BLOCKED` | `UNVERIFIED` | `UNVERIFIED` |
| Safe analysis-environment policy and legitimate-VM behavior | `BLOCKED` | `BLOCKED` | `BLOCKED` | `UNVERIFIED` | `UNVERIFIED` |
| Seeded diversification and reproducibility | `UNVERIFIED` for the lite baseline | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` |
| Mapping/retrace and private recovery | `PASS` for a private lite mapping; retrace recovery is not executed | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` |
| JNI, reflection, ServiceLoader, Netty/native compatibility | `PASS` only for the bounded CLI/relay lite checks | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` |
| Performance, memory, and archive-size impact | `PASS` for bounded lite measurements only | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` |
| License automation and CI secret boundary | open-source dependency is integrated; commercial licensing is not applicable | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` | `UNVERIFIED` |

For a commercial candidate, the missing cells require the exact licensed
version, vendor configuration, non-interactive invocation, shipped artifact,
ordinary static inspection, normal-host/legitimate-VM/debugger tests, and
private recovery evidence. The matrix intentionally does not select a vendor
before that evaluation.

The commercial candidates remain candidates, not evidence. A future maximum
release entry requires the exact licensed version, non-interactive Gradle/CI
invocation, protected artifact inspection, normal-host and legitimate-VM
acceptance, debugger/instrumentation safety checks, tamper behavior, private
retrace, and performance/size measurements.

The repository now has a provider-agnostic maximum-protector adapter and
signed-candidate seam documented in
[maximum-protector-adapter.md](maximum-protector-adapter.md). That seam only
validates the contract and keeps the maximum task fail-closed; it does not
change any `UNVERIFIED` commercial row to `PASS`.

The open-source lite result does not satisfy the commercial transformation
claims. It provides only a bounded shrinking/renaming compatibility baseline
and private mapping material. The release-level signed-integrity/diversification
ring remains only a foundation partial until the protected artifact is
included in the existing signed manifest and its tamper/retrace/diversification
gates pass.
