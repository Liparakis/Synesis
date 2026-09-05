# SYN-049 fresh unattended two-worker runtime acceptance — runs #12–#16

Date: 2026-09-05

Status: **FAIL at the provider-native wake boundary; SYN-049 remains ACTIVE /
PARTIAL.**

## Scope and provenance

Runs #12–#16 were separate disposable task-tracker targets. No historical
lane, binding, participant, claim, or `.synesis` state was reused. The current
installed artifacts were used without rebuilding. The runtime source
provenance remained `a7697bbb5de83ced8b61b275056f9204e4467fbc`.

The JDK25 host used the process-local property
`-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`. Selector and
minimal HttpServer preflights passed. The global Codex configuration was
restored byte-for-byte to the pre-run SHA-256
`088199043ACFA98CD0430374D685B7363FD34299331652B6F81BC66A84B0BEC4` after
supported target provider setup; no credentials were inspected or copied.

## Run #16 exact runtime evidence

Fresh target:
`C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260905-16`

Target project ID: `4c5985e3-3eee-472a-a844-b7f3cb0b8133`.

The target was initialized on `main` from a fresh seed and had baseline
commit `344b52a3e39df3326b0761733aacd51dbda33616`. The control checkout was
clean at the stop boundary.

WorkGroup: `84a72225-ed4f-387a-8bc8-3c83a9555acf`.

Worker A:

- Binding: `session-443924a5-24f7-43c7-b14c-6f35d57d2e77`
- Participant: `agt_bc5bb41c-b5b7-32c0-9690-007e547b1a22`
- WorkIntent: `56fc1178-38ab-30c1-bb7b-a78532c94717`
- Claims: `src/main/java/tasktracker/domain`,
  `src/main/java/tasktracker/persistence`,
  `src/test/java/tasktracker/domain`,
  `src/test/java/tasktracker/persistence`

Worker B:

- Binding: `session-e0ee3bc3-d07d-45d0-9028-efe084f5ab87`
- Participant: `agt_b9191859-ad89-3960-a55b-ab58bd842cfb`
- WorkIntent: `8b2ff1c9-973d-3d71-8931-e9754e3c4dcc`
- Claims: `src/main/java/tasktracker/application`,
  `src/main/java/tasktracker/api`,
  `src/test/java/tasktracker/application`,
  `src/test/java/tasktracker/api`
- Declared dependency: `tasktracker.domain.persistence`

The harness held one live `ManagedCodexProcessLauncher` and one live
`ProjectRuntimeHost` across `prepareFirst` and START. Both preparations were
generation 1 and `PENDING_ACTIVATION`; both provider threads were null and
both ownership records were absent before START. Proof output was restricted
to digests. A's digest was
`96bb70567f6177377b004d067dd21f0a851458055f6f724b2fff26cceb8c8d93a` and
B's digest was
`b72d272c40094cf68f5e12216ddad5ad5cfc0f58bb9e209808237d8aaff915adb`.

B START succeeded directly from the same live launcher and reached
generation-1 Thread B
`01a07344-9e8b-7620-b306-31050fac82b9`. B's initial real turn completed at
revision 8 and the attachment remained `ACTIVE`. The production host
reported `attachmentAlive=true` for B at that point. A START likewise
succeeded directly from the same launcher and reached generation-1 Thread A
`01a07345-ad5c-76f1-be78-4e877bc3731a`.

The exact AppServer roots were contained in their production Windows Jobs;
the MCP children were launched through the same managed process trees. No
bootstrap AppServer or second generation was created. A's first turn
completed, and one normal same-thread `NOTIFY` continuation was issued by the
disposable harness through the real lifecycle service because the model had
ended after owner review. The continuation published the exact capability
request:

`req_N777J6C50741230662A435A0J59010M0`

The durable event projection then showed
`CAPABILITY_IMPLEMENTATION_PUBLISHED` and
`IMPLEMENTATION_AVAILABLE`, with A's identity and B's exact request preserved.

The live host performed 60 clean wake-relay scans (`wakeRelayDiagnostic` was
empty). A direct read-only projection scan independently found the exact
actionable B candidate for Thread B. B's attachment was still live, so the
production admission decision should have been same-attachment `NOTIFY`.
Nevertheless, no provider-native B wake was dispatched: B remained at
revision 8, with the same completed turn and Thread B, and no wake turn was
observed. No capability consumption, B implementation, integration,
completion request, lane terminalization, or WorkGroup terminalization was
reached.

Runs #12–#15 established the same boundary. Run #12 proved B's request could
remain `ACCEPTED` while B's original connection stayed alive; runs #13–#15
proved A publication after a normal same-thread continuation and repeated the
missing provider-native wake. Run #16 added the decisive `attachmentAlive=true`
observation and still reproduced the missing dispatch.

## Stop and classification

The lane was stopped immediately after the first material provider-native
wake failure. No replacement, A2, Worker B replacement, cross-process resume,
full SYN-049 acceptance, or deliberate death test was invoked. The temporary
managed processes were torn down by normal host interruption; no trusted
DeathEvidence is claimed. A's isolated worktree retained uncommitted changes
within A's claims for evidence; the control checkout remained clean.

This is not PASS-A or PASS-B. It is **FAIL / PARTIAL** for the bounded runtime
acceptance: the remaining blocker is the production wake-admission/dispatch
boundary between an actionable exact capability event and the live B
attachment. The focused wake tests pass, but they do not explain this real
runtime discrepancy. A production-source diagnosis is required before another
fresh acceptance run.
