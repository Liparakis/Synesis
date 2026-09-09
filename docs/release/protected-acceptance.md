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

The following must be separately evidenced before a customer release can be
called maximum:

1. all seven requested protection rings on the exact JVM/native/UI/relay
   inputs, with genuine virtualization and protected loading demonstrated by
   the selected commercial tool;
2. relay and overlay/Link acceptance from the protected artifacts, including
   provider-boundary behavior;
3. tamper detection/refusal for the signed immutable payload and protected
   loader;
4. private JVM retrace, native symbol recovery, release diversification, and
   leakage scans;
5. performance, startup, memory, and archive-size comparisons against the
   developer baseline; and
6. final manifest/signature, installer/doctor verification, normal-host,
   legitimate-VM, CI, debugger/instrumentation, and failure-safety evidence.

No missing maximum gate is replaced by a passing lite smoke test.
