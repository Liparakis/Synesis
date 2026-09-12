# SYN-056 installed local daemon shell evidence — 2026-09-10

## 2026-09-11 — final post-CONNECTED membership-semantics audit

This audit supersedes the provisional interpretation below that treated the
absence of durable membership as a SYN-056 acceptance failure.

The authoritative transition is:

```text
SLO1 -> SLA2 -> CONNECT/ANSWER -> authenticated PeerSession
     -> CONTROL_READY/PEER_CONNECTED/LIVENESS
     -> CONNECTED -> retained physical Link session and safe projection
```

The current CLI intentionally stops at the physical session. `LinkRuntimeOwner`
accepts membership only from an explicitly supplied, signature-verified
`OverlayMembershipSnapshot`. Without one it retains authenticated physical
sessions, reports overlay `UNCONFIGURED`, and installs no overlay forwarding
or membership authority. `UNKNOWN` therefore means that the authenticated
physical peer has no authoritative project-membership decision; it does not
mean identity proof failed.

SLM1 membership is created by an authority identity, signed, revisioned,
expiring, and verified against its authority member. `ProjectConfig` is only a
local authenticated-peer allowlist. `OverlayMembershipView.accept` is a
monotonic read-model install seam, not an approval, revocation, or persistence
service. No production CLI, daemon, control-plane, or browser action creates,
approves, configures, or persists membership. The shipped Network actions are
Membership detail, Relay detail, Create Invitation, Join from Invitation, and
Complete Join.

`WORK_RESULT=OK` is bounded demo application-stream work. The Link demo CLI
prints it after `requestDemoWork`; the default one-shot onboarding callback
also emits it after control readiness. It is not a membership proposal,
attestation, authority decision, or durable event, and no consumer maps it to
membership.

The exposed `headSequence` is the coordination `PredictionEventStore`
sequence. It changes only when a coordination service appends a prediction
event. Invite/join/answer/connect operation state is in memory and does not
append coordination events, so `CONNECTED` does not increment that sequence.
The r5 artifact did not capture a before/after health pair; this audit does not
invent numeric values. The source-level result is that Link completion calls
neither the coordination event store nor a membership mutation service.

Classification: **E — membership authority lifecycle is explicitly outside
SYN-056 acceptance scope**, with `CONNECTED` intentionally representing
physical authentication in the unconfigured CLI mode. This is not a missing
callback, rejected membership command, or stale projection. No r6 run was
performed because there is no legitimate shipped membership action to
exercise. No production repair was made and no membership was auto-mutated.

The authoritative SYN-056 task definition covers the daemon shell,
authenticated IPC, runtime reuse/health, browser/bootstrap security, existing
onboarding deep-link forwarding, and truthful installed evidence. It does not
promote project membership authority. ADR-0068 and the overlay protocol keep
that capability separately scoped. The r5 evidence therefore closes the
current SYN-056 scope based on real `CONNECTED` plus terminal replay evidence;
membership-authority work remains deferred and requires a separate activation.

### Final current-scope audit

| Criterion | Result | Evidence boundary |
| --- | --- | --- |
| Installed daemon lifecycle, bounded authenticated IPC, runtime reuse/recovery | PASS | Focused daemon tests and installed smoke |
| Browser bootstrap/session security and Linux installed UI launch | PASS | r4 installed browser gate |
| Browser-mediated SLO1, daemon JOIN, Complete Join, SLA2, daemon ANSWER | PASS | r5 installed evidence |
| Distinct persisted identities and authenticated live physical Link peer | PASS | r5 node IDs, identity hashes, `CONNECTED`, `LIVE` |
| Terminal SLA2 replay rejection | PASS | r5 `URI_REJECTED / OPERATION_NOT_FOUND` |
| Automatic durable signed membership on `CONNECTED` | EXPLICITLY OUT OF SCOPE | No authority source or shipped mutation action; CLI remains `UNCONFIGURED` by ADR-0068 |
| Membership-authority lifecycle, revocation, overlay routing/relay | EXPLICITLY DEFERRED | SL-D-040, ADR-0066, overlay protocol |

Final state for the authoritative SYN-056 scope: **COMPLETE FOR CURRENT
SCOPE**. The task registry remains `ACTIVE` only because its validator requires
one active task until the repository owner explicitly activates the next task
or declares the whole roadmap complete; this is bookkeeping, not a membership
blocker.

## 2026-09-11 — Linux browser fallback and fresh r5 acceptance

The modern Linux acceptance image reported Java AWT
`Desktop.Action.BROWSE=false` even though the platform `xdg-open` worked. The
production opener now preserves Java Desktop browse when supported and uses a
direct-argv `xdg-open <uri>` fallback only on Linux when browse is unavailable
or fails. No shell is invoked and URI validation remains before process launch.

Focused opener tests passed, followed by the Linux-targeted installed build:

```text
.\gradlew.bat :cli:installDist --no-daemon --no-configuration-cache --console=plain -PsynesisNativeQuicClassifier=linux-x86_64
```

The fresh r4 D: gate passed real installed `synesis ui` browser opening,
bootstrap/session exchange, and live project-state rendering. The fresh r5
D: A/B browser-mediated run passed distinct persisted identities, real SLO1,
daemon B `WAITING_FOR_CONNECT`, shipped B Complete Join, real SLA2, daemon A
`CONNECTED`, distinct peer identity verification, live liveness, and terminal
SLA2 replay rejection (`URI_REJECTED / OPERATION_NOT_FOUND`).

The r5 overlay remained `UNCONFIGURED` with `UNKNOWN` membership and no
durable signed-membership mutation. Durable onboarding and exactly-once
durable membership therefore remain PARTIAL. Detailed evidence is retained at
`external acceptance evidence` and
`external acceptance evidence`.

### Provisional r5 criterion audit before the authority-semantics review

| Criterion | Result | Evidence boundary |
| --- | --- | --- |
| Linux installed `synesis ui` opens the shipped browser | PASS | r4 installed gate |
| Browser bootstrap/session exchange and UI load | PASS | r4 URL has no bootstrap fragment and live project state rendered |
| A/B distinct persisted identities | PASS | r5 distinct project/node IDs and identity files/hashes |
| A creates real SLO1 through shipped UI | PASS | r5 browser Create Invitation |
| B consumes SLO1 through installed daemon | PASS | r5 B `OPEN_URI` |
| B reaches `WAITING_FOR_CONNECT` | PASS | r5 daemon response |
| B clicks shipped Complete Join | PASS | r5 browser action and live peer projection |
| A consumes real SLA2 through installed daemon | PASS | r5 A `OPEN_URI` -> `CONNECTED` |
| Distinct peer authentication and live transport | PASS | both views show the other node with `LIVE`; no identity-proof failure |
| Both sides reach connected/live state | PASS | A terminal response and B live peer projection |
| Durable onboarding exactly once | PARTIAL | physical session is live, but overlay membership is `UNCONFIGURED` / `UNKNOWN` and no durable signed-membership mutation exists |
| Terminal replay is safe | PASS | exact SLA2 replay -> `URI_REJECTED / OPERATION_NOT_FOUND` |
| Deferred OS registration, multi-project selection, autostart, hosted control plane | EXPLICITLY DEFERRED | unchanged deferred register and product boundary |

This provisional interpretation is superseded by the final post-CONNECTED
membership-semantics audit at the start of this document. It remains as
historical evidence of the question that triggered the audit.

## Boundary

Active task: `SYN-056`. Starting repository HEAD for the uncommitted work was
`b600b446521b19a3aa94fb4c6e667b5364834f9d`. No commit, push, tag, release, or
remote mutation was performed. The daemon remains a thin installation shell;
project runtime authority, browser session/bootstrap security, and Link
onboarding remain in their existing modules.

## 2026-09-11 — distinct-peer Docker attempt blocked by engine I/O

The requested fresh two-identity installed acceptance was started under
`external acceptance workspace` with containers
`syn056-distinct-r2-a` and `syn056-distinct-r2-b` on bridge network
`syn056-distinct-peer-r2`. All acceptance-controlled homes, projects, logs,
build, compose, evidence, and temporary paths were under that D: root.

The installed distribution was rebuilt with:

```text
.\gradlew.bat :cli:installDist --no-daemon --no-configuration-cache --console=plain -PsynesisNativeQuicClassifier=linux-x86_64
```

The staged distribution contained the base Netty QUIC artifact and only the
Linux native artifact. Safe hashes were:

```text
launcher: 7EDF94534B4C71BFA3A83BB821C7065A2C59CC6170067C338F32E27E17D553C6
netty-codec-native-quic-4.2.16.Final-linux-x86_64.jar:
  3C29587F8B2953032BD37FC20E3EDA5BB7270E5E1E09B496F2C024F8AC8B5DC1
```

The installed product initialized separate Git projects and persisted
identities:

```text
A project c063484b-fa1c-4b6c-8d7d-4e49b3aa6f02
  node sl1-27a4c287e2c05b9bc6fbda56fecaa83f2121c226833d4af8c1c96694d4381608
B project c4e2befe-356d-4efa-95f4-23c988bd046b
  node sl1-e8cec353f81a5f6ddcdf3f1ae1ff7bd89c77439d65033e947396155fe71d48f0
```

The A and B project roots and home/state roots were distinct. Their public
identity files were independently present; private key material and daemon
bearer tokens were not read into evidence. Each installed daemon reached
`{"ok":true,"state":"RUNNING","runtimeCount":1}`. The runtime endpoints
were A `http://127.0.0.1:39069` and B `http://127.0.0.1:40815`. Each isolated
container reported daemon PID 77 and runtime PID 106. The bridge assigned A
`172.18.0.2` and B `172.18.0.3`.

Before the Link invitation step, a Docker-layer probe returned `502 Bad
Gateway` from the Linux engine for `docker cp`; subsequent bounded
`docker version` calls did not complete. No SLO1 was created in this run, so
no candidate, JOIN, CONNECT, ANSWER, authenticated peer session, durable
mutation, exactly-once result, or replay result is claimed. This is an
infrastructure blocker, not Link evidence. The containers and network were
still present when the engine became unresponsive, so cleanup could not be
verified.

## 2026-09-11 — HOST-A invitation trace

The fresh D:-backed HOST-A browser reproduction used the shipped Projects and
Network UI and the authenticated `/api/v1/commands/invite` route. The initial
Windows-built `:cli:installDist` mounted in Linux Docker contained only
`netty-codec-native-quic-4.2.16.Final-windows-x86_64.jar`. Trace
`2391fce9-7a3a-40ba-a543-cf7fa5b923dc` reached `INVITE_SSL_ALPN_READY`, then
`QuicSslContextBuilder.build()` raised `UnsatisfiedLinkError` because the Linux
native resource was absent. The handler thread ended without an HTTP response;
no listener, candidate, SLO1, or Link return existed in that run.

The smallest general repair was added in `link/build.gradle.kts`: host-native
selection remains the default and cross-target staging accepts the validated
`synesisNativeQuicClassifier` property. The corrected distribution was built
with `-PsynesisNativeQuicClassifier=linux-x86_64`.

Successful trace `0a82ad6c-d807-4bb0-9247-22630b25058e` ordered the stages as:

```text
INVITE_HTTP_ENTER
INVITE_AUTH_OK
INVITE_OPERATION_ADMITTED
INVITE_LINK_ENTER
identity/admission/TLS/event-loop readiness
INVITE_SSL_BUILD_RETURNED
INVITE_LISTENER_BIND_START
INVITE_LISTENER_BIND_DONE port=40697
INVITE_CANDIDATES_READY count=1
INVITE_SLO1_SERIALIZED slo1Length=1198 slo1Sha256=4c150464d844c4d9275027b84463c870f51c8655a918c2fe7d0799a4784354c6
INVITE_HOST_REGISTERED
INVITE_LINK_RETURN
INVITE_HTTP_RESPONSE_START status=201 bodyBytes=1435
INVITE_HTTP_RESPONSE_DONE status=201
```

Elapsed time from HTTP entry to response completion was about 1.26 seconds.
Temporary trace instrumentation was removed after diagnosis. This proves the
installed HOST-A invitation boundary only; it does not prove distinct-peer
completion.

## Earlier installed daemon shell evidence

The daemon owns one per-user file lock, an atomic endpoint document,
authenticated bounded loopback JSON-line IPC, runtime launch/reuse, health
validation, browser opening, and bounded URI handoff. Project runtime
authority remains in `coordination serve`; the daemon never owns a project
session or Link onboarding handle.

Focused protocol/lock tests, strict CLI Javadocs, installed distribution
build, and disposable lifecycle smoke passed. The smoke covered daemon reuse,
stale endpoint replacement, wrong/oversized/malformed IPC, browser opening,
URI-shape rejection, and crash/orphan-free relaunch. The installed URI smoke
also proved a real `WAITING_FOR_CONNECT` join result and a real answer URI,
rejected ambiguous two-runtime routing, and rejected malformed HTTPS input.

The shipped browser path consumed the one-time fragment bootstrap and created
a real signed SLO1 invitation through the authenticated invite route. An
installed daemon-managed join runtime accepted that SLO1 and produced a real
signed SLA2. Same-runtime and split-runtime answer experiments correctly
exposed operation ownership and the fail-closed self-identity boundary; they
did not produce durable mutation and were not used as positive distinct-peer
evidence.

The broader `:cli:check` remains partial with two unrelated, reproducible
failures:

```text
CodexHookProcessTest.generatedLauncherFailsClosedBeforeMutationWhenWorkspaceIsUnassigned
WorkspaceCliTest.test14RepeatedBrokerRequestWithSameIdempotencyKeyDoesNotDuplicateMutation
```

These are not attributed to the daemon or Link onboarding slice.

## Deliberate incompleteness

OS `synesis://` registration, cross-project SPA runtime selection, persistent
global selected-project state, login/reboot daemon autostart, broader UI, and
hosted control plane remain deferred. No Docker-specific Link behavior,
identity exception, self-peer allowance, daemon CONNECT IPC, operation copy,
global registry, or candidate bypass was added.

## Current acceptance classification

| Criterion | Classification | Evidence |
| --- | --- | --- |
| Singleton daemon, bounded authenticated IPC, stale/crash recovery | PASS | Focused daemon tests and installed lifecycle smoke |
| Runtime launch/reuse, lazy health, browser bootstrap preservation | PASS | Installed daemon/runtime smoke and control-plane tests |
| Linux-targeted installed HOST-A invitation | PASS | Successful installed trace and browser-visible SLO1 |
| A/B distinct persisted identities and isolated projects | PASS for setup | `distinct-peer-r2` initialized both products separately on D: |
| Installed B JOIN, browser Complete Join, A ANSWER | PARTIAL | Not reached in the engine-blocked run |
| Authenticated distinct-peer CONNECTED semantics | PARTIAL | No valid installed cross-peer run yet |
| Durable exactly-once onboarding and replay | PARTIAL | No valid installed cross-peer run yet |
| OS protocol registration and broader deferred capabilities | DEFERRED BY EXPLICIT SCOPE | ADR-0073 and `DEFERRED.md` |

SYN-056 remains ACTIVE / PARTIAL. The remaining acceptance gap is the
browser-mediated Complete-Join/ANSWER path and its durable exactly-once/replay
proof in an image with a supported browser opener. The transport boundary is
now positively evidenced without weakening Link identity or daemon authority.

## 2026-09-11 — distinct-peer Docker retry-3

The retry-3 harness was rebuilt entirely under
`external acceptance workspace` from the fresh installed
distribution produced with the validated Linux classifier:

```text
.\gradlew.bat :cli:installDist --no-daemon --no-configuration-cache --console=plain -PsynesisNativeQuicClassifier=linux-x86_64
```

The Docker Desktop WSL engine data remained on D:, including
`external Docker storage` and
`external Docker storage`; no Docker VHDX was
deleted. Two isolated containers used distinct project homes, runtime homes,
logs, and installed daemon processes. The current durable identities reported
by the installed product were:

```text
A NODE_ID=sl1-1848ee4cf77325b0e859684010412032c1adac7b5e3aed713132f1ed87e86be0
B NODE_ID=sl1-2ec8b70845f0036a20c2fb65e65c7504126d19d0ee915c225fbe8467ee465274
```

The daemon on B accepted the real A SLO1 through its authenticated
`OPEN_URI` boundary and projected `WAITING_FOR_CONNECT` with a signed answer
URI. The disposable image could not use Java's AWT browser opener
(`Desktop.isDesktopSupported=false`), so this retry does not claim browser
Complete Join or browser ANSWER completion.

For the transport acceptance boundary, the installed B CLI joined the real A
invitation across the Docker bridge while A's host operation waited on its
answer pipe. The observed ordered markers were:

```text
INVITE_PARSED
INVITE_VERIFIED
HOST_IDENTITY_PINNED
LOCAL_DESCRIPTOR_CREATED
CANDIDATES_GATHERED=1
TRAVERSAL_STARTED
PATH_SELECTED=LAN/LAN
PEER_CONNECTED
PEER_IDENTITY_VERIFIED=sl1-1848ee4cf77325b0e859684010412032c1adac7b5e3aed713132f1ed87e86be0
CONTROL_READY=true
LIVENESS=LIVE
WORK_RESULT=OK
SESSION_CLOSED
```

The A host log independently recorded `PEER_CONNECTED`, the same verified
peer identity, `CONTROL_READY=true`, `LIVENESS=LIVE`, and `SESSION_CLOSED`.
This proves installed distinct-peer Link transport, peer authentication,
control readiness, liveness, and a successful work exchange. Because the
positive transport join used the installed CLI directly after the daemon
`OPEN_URI` projection, it does not prove the browser-mediated
Complete-Join/ANSWER path, durable exactly-once onboarding, or replay
behavior.

After evidence capture, only `syn056-distinct-r3-a`,
`syn056-distinct-r3-b`, `syn056-distinct-peer-r3`, and the temporary
`syn056-distinct-peer-r3:latest` image were removed. The D:-backed evidence
root and prior recoverable quarantine were retained.

## 2026-09-11 — r4 browser-opener environment probe

The fresh r4 environment is staged under
`external acceptance workspace`. Its disposable image contains
Google Chrome `153.0.8010.36`, Xvfb, `xdg-open`, GIO, and Selenium. The image
digest is
`sha256:966fd57122a1d6e724459f62ed0ab2ae853661f7cc39488649e4ce5f7d7491e0`.

A standalone `xdg-open` probe launched Chrome under Xvfb. The real installed
`synesis ui --duration-seconds 4` smoke then reached a fresh runtime and
returned `SYNESIS_UI_OPEN_FAILED`. Java reported
`Desktop.isDesktopSupported=true` but `Desktop.Action.BROWSE=false`. The
installed JDK's Linux XDesktopPeer expects legacy GNOME VFS symbols
(`libgnomevfs-2.so.0` / `gnome_url_show`), which the modern base image does
not provide. No Link operation was started after this result, and no
production code or protocol behavior was changed.

SYN-056 remains ACTIVE / PARTIAL. The exact blocker is now the shipped
browser-opening boundary, before browser bootstrap/session exchange. Rerun the
browser smoke only after obtaining a supported non-Synesis-specific Linux
desktop/browser environment or explicit supported-platform product direction.
