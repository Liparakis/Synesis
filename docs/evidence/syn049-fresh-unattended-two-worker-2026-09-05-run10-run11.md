# SYN-049 fresh unattended two-worker acceptance — runs #10 and #11

## Classification

**PARTIAL.** These two fresh attempts did not reach the SYN-049 dependency,
integration, or terminalization acceptance. Both attempts stopped at the first
material runtime boundary and were not reused or repaired.

Run #10 failed before Worker A started: Worker B's generation-1 App Server
exited during its initial START path. The lifecycle checkpoint recorded
`FAILED/process_exit`; sanitized App Server stderr contained repeated internal
loopback HTTP connection failures. This was a provider/host startup failure,
not a Synesis capability or ownership decision.

Run #11 used a fresh target and a hidden detached validation host. Both real
generation-1 App Servers launched and their model turns reached trusted
completion, but both reported that the Synesis MCP server failed its startup
handshake with `connection closed: initialize response`. The durable
coordination log therefore contains only the WorkGroup and two WorkIntent
announcements. No capability request, capability publication, provider-native
wake, capability consumption, managed file mutation, integration, lane
terminalization, or WorkGroup terminalization is claimed.

## Common gates

- Source repository at start: `611e4ab10ecc3481d67bdb3ec62be8896ef6ad65`,
  branch `master`, clean.
- Runtime source provenance remained `a7697bbb5de83ced8b61b275056f9204e4467fbc`.
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe`,
  Temurin `25+36-LTS`.
- Process-local property: `-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`.
- Selector and minimal HttpServer create/start/stop preflights passed.
- Produced and installed artifact hashes matched exactly:
  - workspace JAR: `CE095B0FADA30F5D1025AB13216B64CA2F07A9CAE91A32792CC0DDC77FC1C1F1`
  - MCP JAR: `029CCA350D77A9D362B37F5AE74BB7FA32EB1E20C30138C96D948900556AEB5F`
  - CLI JAR: `B7C8EA536D4B12B0E88F0651AE7464C0E8B2EC465E8D7DC1EAA52F6F58B803AE`
  - native MCP: `7F72F84E4C1081684163F4AFAECC6445EC1BE05230393B7AFD29ACBC4C0B55E1`
- No production source, Java configuration, or global network setting changed.

## Run #10

- Fresh target: `C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260905-10`.
- Target branch: `main`; final control checkout remained clean at
  `a31e808c3cba15644660f0e0396f2eacb0394528`.
- Project: `3afb8088-3cb5-40b6-93bb-df2ac8a4643a`.
- WorkGroup: `7fffed91-ee22-3fd2-bd88-09aa434cfe94`.
- A binding/participant/intent: `session-7b5a3f55-d6a1-474b-a90e-e791f384e340`,
  `agt_5fa12aa0-3f6e-3775-a0d3-e5638ae8ef42`,
  `0ccc6753-2fa9-319b-a34f-2497fe49256b`.
- B binding/participant/intent: `session-836bfd2a-acff-4ff8-ae32-8e0ca0e76672`,
  `agt_f62096e3-5b80-3b5f-a775-7a0930a719d8`,
  `3ae4681e-c83f-3f96-ae59-c76877a23a66`.
- Both preparations were generation 1 and `PENDING_ACTIVATION`; proof digests
  only were recorded: A `b8ffe4da9d84a40f6d32c3f5cf26814b658df281bcaac4295333ddb4a9d0463a`,
  B `ffb3b34c455403c04a255731f00daf8fe98066c7d06b67eca4cb76b8fd391bd39`.
- B START returned a generation-1 thread/turn, then the App Server exited with
  lifecycle `FAILED`, revision 8, diagnostic `process_exit`, before A START.
- No Worker A turn, capability flow, replacement, A2, or Worker B substitute
  was invoked. Exact run evidence was retained outside the repository.

## Run #11

- Fresh target: `C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260905-11`.
- Target branch: `main`; final control checkout was clean at
  `2bcfa6e89981dc7702aeb314db78e70c6dcd770f`.
- Project: `8f80ff54-24b5-4b4a-9dd8-b23045ac8726`.
- WorkGroup: `81a91e1d-c15b-31dd-948d-99025fc5c17f`.
- A binding/thread: `session-4746521e-91e4-4f68-b2bf-e653981c31b6` /
  `01a072f6-87d8-7c50-90d8-095fae6de648`.
- B binding/thread: `session-356f8b7b-fac2-490e-bb07-7a8ea2fe62fc` /
  `01a072f5-07ab-7741-bdc0-719ce8e1a65d`.
- A and B each used generation 1 and one real provider turn. The lifecycle
  evidence contains `initialize`, `thread/start`, and `turn/start` responses;
  no Synesis MCP tool call is evidenced. The Synesis MCP startup status was
  `failed` for both connections with the exact handshake diagnostic above.
- Coordination state remained at three events: WorkGroup creation and the two
  WorkIntent announcements. Both intents and participants remained active;
  no claim request was created.
- The host and exact target-matching App Server processes were stopped after
  the material child-process failure. The managed attachment records remain
  durable evidence and were not manually altered. No replacement or worker B
  continuation was attempted.

## Configuration and hygiene

The Codex configuration hash was captured before each supported provider setup.
For run #10 it was restored to
`EDCA2AE2676C97CCAB23D0F0B1A05A98AF3CA96C9B79515D376DFAA42E1210`; for run #11
it was restored to
`4C2CAE18BC2BE8C157619EA9F8D732974BA271226052436A0984F667C4F753CE`.
Each restored hash matched its pre-run snapshot. No credentials were read,
copied, logged, or inspected. Raw attachment proofs were not logged or
persisted; only digests appear in this evidence. The AF_UNIX property remained
process-local and was not added to source, configuration, or child launch
environment.

SYN-049 remains **ACTIVE / PARTIAL**. SYN-051 remains **COMPLETE / PASS-A** and
was not reopened. No push occurred.

## Exact remaining blocker and next action

The real installed native MCP child does not complete the App Server
`initialize` handshake in the detached run context; run #10 additionally
showed a provider internal loopback startup failure. A separate bounded
diagnostic is required to establish whether the child independently hits the
known AF_UNIX boundary or whether the installed Codex App Server/MCP launch
context is otherwise invalid. Do not propagate the workaround, patch
production, or create another acceptance lane until that diagnostic is
completed. The next action is a read-only child-startup compatibility
investigation; a fresh SYN-049 acceptance remains unauthorized by evidence.
