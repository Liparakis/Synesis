# SYN-009E installed Link acceptance — 2026-09-09

## Result

The rebuilt Windows x64 platform bundle was staged as a disposable
marker-only `maximum-release` candidate and exercised through the installed
CLI stable launcher. The input was not produced by a commercial protector;
this is shipped-launcher/Link seam evidence only.

- Archive SHA-256:
  `8d78e7c9500e1d43e5c674a8db5dd56ac5132d217e99809759086c423283cb11`
- Archive size: `46,944,176` bytes
- Harness result: `PASS_WITH_EXPLICIT_OPEN_GATES`
- Runtime overrides: process-local JDK AF_UNIX temporary directory and
  `LOCALAPPDATA` override; neither is shipped configuration
- Private artifact manifest: not supplied for this synthetic run
- Bearer invitation: held only in process memory and not recorded

## Acceptance exercised

The harness created two isolated installed-CLI profiles, generated distinct
durable `sl1-` identities, configured reciprocal one-peer project state, and
started the installed host process. It passed the signed invitation to a
second installed CLI process, required the authenticated remote identity, and
required `SYNC_RESULT=SUCCESS`. This exercised the shipped CLI path through
invitation serialization, Link authentication, native QUIC/PeerSession
onboarding, and project synchronization on the local host.

The same run also passed the existing installed-archive checks for version and
help, native installer/MCP, provider lifecycle and doctor, immutable JVM
payload tamper refusal, packaged frontend-asset tamper refusal, mutable-state
non-false-positive behavior, packaged UI/control-plane HTTP/SSE, and MCP
session admission.

## Launcher correction

The first installed probe exposed a real Windows bootstrap forwarding defect:
the generated `synesis-launcher.ps1` used `cmd /c call` with an argument array,
which split the signed invitation query at `&host=`. `bootstrap/main.go` now
keeps the manifest/profile/doctor gates, constructs one explicitly quoted raw
`cmd /d /s /c` invocation for the versioned payload launcher, and fails closed
for embedded quote, percent, exclamation, or caret characters. The focused
bootstrap Go suite passed, and the rebuilt installer plus platform bundle
passed the installed Link probe above.

## Remaining boundary

This result does not promote Rings 1–6 or complete Ring 7. The archive has a
synthetic profile marker only; genuine control-flow protection, virtualization,
string/constant protection, safe analysis-environment policy, protected
payload/packing, anti-debug/instrumentation behavior, commercial signing and
diversification, private retrace/native symbols, and reproducibility remain
blocked on the licensed commercial protector and release authority. It also
does not prove Internet NAT traversal, multi-peer overlay routing, or a
protected relay authentication/forwarding socket.
