# SYN-009E shipped control-plane HTTP/SSE acceptance seam — 2026-09-09

This slice extends the independent extracted-artifact harness. It does not
create or claim a commercial maximum-protection artifact.

## Implemented check

`scripts/maximum-release-acceptance.ps1` now starts the installed `synesis ui`
launcher as a bounded disposable process, reads its
`COORDINATION_SERVE_READY` endpoint and one-time bootstrap token, and verifies:

- packaged HTML is served from the installed UI origin;
- public loopback health is available and reports `loopbackOnly=true`;
- invalid bootstrap is rejected and the valid bootstrap creates a session;
- the bootstrap cannot be reused;
- authenticated snapshot, diagnostics, and network projections are returned;
- a mutation without the CSRF header is rejected; and
- the authenticated `/api/v1/events` response advertises SSE and emits its
  initial `snapshot` event.

The disposable server is terminated after the probe. The harness records a
host loopback compatibility failure as blocked evidence rather than converting
it into a pass.

## Verification

| Check | Result | Evidence |
| --- | --- | --- |
| PowerShell AST parse | PASS | zero parser errors |
| Synthetic maximum marker archive | PASS_WITH_EXPLICIT_OPEN_GATES | archive SHA-256 `4ce7605159dba440ea400cfd438eac537b801bd6902c8b12b6d5718aa691156c`; 46,341,498 bytes |
| Live packaged UI root and control-plane HTTP | PASS | synthetic run `maximum-harness-http-sse-18`, `cli-control-plane-http-sse` |
| Bootstrap/session/snapshot/CSRF/SSE | PASS | synthetic run `maximum-harness-http-sse-18`, `cli-control-plane-http-sse` |
| Default host compatibility boundary | BLOCKED as expected | prior/default evidence records Java loopback failure and exit `2` |
| Real licensed maximum artifact | NOT EXECUTED | no commercial adapter, license, or signed maximum archive is installed |

The compatibility rerun used `C:\t\synesis-loopback-probe-18` and
`C:\t\synesis-maximum-harness-runtime-override-18`. The JSON records these as
`PROCESS_LOCAL_ONLY; NOT_SHIPPED_LAUNCHER_CONFIGURATION`; they are not release
configuration or customer-environment evidence.

## Evidence boundary

The input archive was deliberately synthetic: a protection-lite customer
bundle had only its profile marker changed so the harness could exercise the
acceptance plumbing. Therefore this result proves the installed UI and
control-plane probe works, but it does not promote any of Rings 1–6, does not
complete Ring 7, and does not replace protected Link/overlay/relay, provider,
retrace, performance, reverse-engineering, or commercial-tool evidence.
