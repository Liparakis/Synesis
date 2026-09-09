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

The commercial candidates remain candidates, not evidence. A future maximum
release entry requires the exact licensed version, non-interactive Gradle/CI
invocation, protected artifact inspection, normal-host and legitimate-VM
acceptance, debugger/instrumentation safety checks, tamper behavior, private
retrace, and performance/size measurements.

The open-source lite result does not satisfy Rings 1, 2, 4, 5, or 6. It
provides only a bounded shrinking/renaming compatibility baseline and private
mapping material. Ring 7 remains only a release-foundation partial until the
protected artifact is included in the existing signed manifest and its
tamper/retrace/diversification gates pass.
