# Protected artifact acceptance

Acceptance is performed on the extracted candidate artifact, not only on a
Gradle output directory. The protected archive must be exercised with the
same launcher, bundled runtime, native installer, resources, and external
state boundaries used by a customer-style installation.

## Current protection-lite gate

Run:

```powershell
.\gradlew.bat :cli:protectionLiteBundleSmokeTest :cli:protectionLiteIntegrityCheck :cli:protectionLiteProvenance `
  '-PsynesisJmods=C:\path\to\jdk\jmods' `
  --dependency-verification=strict --no-configuration-cache
```

The current gate covers:

- archive extraction, protected profile marker, CLI `version`, and CLI help;
- Picocli UI command metadata and a bounded no-browser UI server start;
- native installer `version` output and the native MCP launcher reaching the
  protected Java CLI;
- disposable Git project initialization and provider list/install/status/
  uninstall operations;
- project doctor; and
- installed stdio MCP `initialize`, `tools/list`, and `ensure_session`.
- private artifact-manifest verification, source/private-material leakage
  checks, and a non-mutating tamper-difference check.

The separate relay lite archive also passes a transformed-entrypoint smoke that
reaches the guarded argument parser. It does not yet prove a protected live
relay socket/overlay path; that remains a maximum-release acceptance gate.

The current run passed this gate for the Windows x64 bundle. Its result is
`protection-lite`, not `maximum-release`.

## Maximum-release gates still required

After a licensed adapter has produced and signed a candidate, run the
independent shipped-artifact harness against the archive rather than against
source classes:

```powershell
.\scripts\maximum-release-acceptance.ps1 `
  -Component cli `
  -Archive .\cli\build\maximum-release\synesis-<version>-<platform>-maximum-release.zip `
  -ArtifactManifest .\cli\build\maximum-release\private\artifact-manifest.txt

.\scripts\maximum-release-acceptance.ps1 `
  -Component relay `
  -Archive .\relay\build\maximum-release\synesis-relay-<version>-<platform>-maximum-release.zip `
  -ArtifactManifest .\relay\build\maximum-release\private\artifact-manifest.txt
```

The harness extracts only to a disposable directory, requires the
`maximum-release` marker, rejects source/private material, optionally verifies
the private per-file SHA-256 manifest, and—when that manifest is supplied—
mutates one disposable extracted file and proves that the manifest rejects the
change before restoring the file. That is a static digest-difference check,
not runtime tamper acceptance. The harness also rejects common private
mapping, seed, provenance, keystore, and native-debug-file leakage forms. It
then exercises the shipped CLI/native launcher/UI/control-plane/provider/MCP
boundaries or the relay guarded launcher boundary. It records JSON evidence
under the private acceptance directory. The relay authentication/forwarding
scenario remains an explicit open gate until a real protected relay socket test
is run; a parser smoke is not promoted to full relay acceptance.

For a CLI candidate, the harness also starts a bounded disposable UI server
long enough to exercise the installed browser/control-plane seam over HTTP. It
checks the packaged HTML root, public loopback health, invalid and one-time
bootstrap handling, authenticated session credentials, snapshot/diagnostics/
network projections, CSRF refusal for an unauthenticated mutation, and the
first authenticated `text/event-stream` snapshot. This is a shipped-artifact
HTTP/SSE acceptance check; it is not browser automation and it does not prove
the protected Link, overlay, relay, or commercial transformation rings.

The CLI path also targets one packaged `web-ui` JavaScript or CSS entry inside
the installed JAR. It edits that entry in a disposable copy, requires the
stable launcher to refuse the changed immutable payload, and restores the
original JAR byte-for-byte before continuing. This is separate from the
generic first-file tamper check and from mutable `Link` state.

For the CLI component, the harness also installs the extracted candidate into
a second disposable root with the explicit `--skip-path-update` acceptance
flag. It then launches the installed stable wrapper, edits one file inside the
installed immutable version, and requires the wrapper to refuse to start. The
file is restored before the harness adds disposable mutable `Link` state and
requires a normal start. This exercises the bootstrap-owned runtime gate
without changing the operator's PATH; it remains unexecuted until a real
licensed maximum archive is available.

For an installed CLI candidate, the stable bootstrap launcher carries the
profile into the active pointer and invokes the existing versioned installer
doctor before starting a `maximum-release` payload. That doctor hashes the
complete immutable payload manifest. The generated gate is covered by the
bootstrap test and recorded in
`docs/evidence/syn-009e-runtime-integrity-gate-2026-09-09.md`; it is not a
runtime acceptance result until a real licensed maximum archive is installed,
tampered, and restored in a disposable environment.

On Windows hosts with the documented JDK AF_UNIX temporary-path problem, the
harness records the default UI/MCP result as blocked and exits `2` rather than
silently treating it as a pass. A host-compatible rerun may supply explicit,
process-local overrides:

```powershell
.\scripts\maximum-release-acceptance.ps1 `
  -Component cli `
  -Archive .\cli\build\maximum-release\synesis-<version>-<platform>-maximum-release.zip `
  -JdkUnixDomainTempDirectory C:\t\synesis-loopback-probe `
  -LocalAppDataOverride C:\t\synesis-maximum-runtime-data
```

The JSON evidence records these overrides as
`PROCESS_LOCAL_ONLY; NOT_SHIPPED_LAUNCHER_CONFIGURATION`. They are host
compatibility inputs, not a release feature and not evidence that the default
customer environment passes. Every captured shipped process also receives a
disposable `HOME`, `USERPROFILE`, `APPDATA`, and `LOCALAPPDATA`; this keeps
provider installation and workspace admission tests from mutating the
operator's real configuration.

The following must be separately evidenced before a customer release can be
called maximum:

1. a licensed, version-pinned adapter must produce the exact JVM/native/UI/relay
   inputs, all six commercial ring evidence records, private retrace/native
   symbols, a release-specific transformation seed, and a customer/private
   boundary that passes the Gradle adapter gate;
2. all six vendor transformation rings on the exact JVM/native/UI/relay
   inputs, with genuine virtualization and protected loading demonstrated by
   the selected commercial tool, plus the separately verified signed-integrity
   layer produced by the Gradle/bootstrap release pipeline;
3. relay and overlay/Link acceptance from the protected artifacts, including
   provider-boundary behavior;
4. tamper detection/refusal for the signed immutable payload and protected
   loader;
5. private JVM retrace, native symbol recovery, release diversification, and
   leakage scans;
6. performance, startup, memory, and archive-size comparisons against the
   developer baseline; and
7. final manifest/signature, installer/doctor verification, normal-host,
   legitimate-VM, CI, debugger/instrumentation, and failure-safety evidence.

No missing maximum gate is replaced by a passing lite smoke test.
