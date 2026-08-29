# SYN-038 real Codex App Server acceptance

Date: 2026-08-03

Installed Codex: `0.145.0`
Schema manifest: `513638ff16789ba79d49dccc53f8564fd9c9ebec889b2e038d20b7a692b3035b`

Status: acceptance complete for the required lifecycle and normal Codex
completion path, with the independent MCP-command cleanup outcome recorded as
unconfirmed. The production-owner, authority, START, event-driven control,
passive exact-thread resume, immutable duplicate replay, protocol handling,
deterministic lifecycle gates, and Codex-driven validation/snapshot/integration
are evidenced below. The interrupted-turn fixture remains classified
`turn_interrupted_command_remained_active`; SYN-038 does not infer MCP
cancellation or command-tree cleanup from a turn interruption.

## Fixture and owner

- Disposable project: `C:\Users\Liparakis\AppData\Local\Temp\syn038-verify-20260803-141821`.
- Project ID: `c138f1e4-5177-4568-8336-8af127713900`.
- Git baseline: `00015a52933fbebae9943fe44b9e716cbf34f537`.
- Production entry point: installed `synesis coordination serve`.
- Codex-only route: `http://127.0.0.1:64321/codex-lifecycle/v1` (first
  acceptance owner), later restarted on ports `64322` and `64323`.
- A second simultaneous owner was rejected with
  `COORDINATION_ERROR=lifecycle_owner_already_running`.
- The final owner was host
  `host-329d922d-3736-4159-8a31-f274ba3320aa` (PID 23264 while live).

## Authority before START

`ProviderSessionCommand` used the existing session and collaboration workflow
before constructing the immutable START envelope. The exact verified context
was:

- binding session: `session-610af205-9b89-4bfc-83cf-c79c092951a6`;
- binding fingerprint/version: `44f7dc7a7e46a0560b161d2dd58b71152f23ead1c951a9e7e5f142616a6f7c94` / `1`;
- participant: `agt_721a4575-543c-361c-9e12-4ec06e8685b2`;
- WorkIntent/lane: `99827ebc-adb3-3b19-9e6e-22d145295abb`;
- lane epoch: `1`; claim: acquired;
- canonical and real worktree:
  `C:\Users\Liparakis\AppData\Local\Synesis\workspaces\c138f1e4-5177-4568-8336-8af127713900\worktrees\session-610af205-9b89-4bfc-83cf-c79c092951a6`;
- branch: `synesis/codex/session-610af205-9b89-4bfc-83cf-c79c092951a6`;
- Git common directory: `C:\Users\Liparakis\AppData\Local\Temp\syn038-verify-20260803-141821\.git`.

The authority phase emitted no Codex frame and launched no App Server. The
owner then verified the frozen identities, committed and re-read durable
lifecycle idempotency state, acquired the attachment lock, launched Codex,
initialized the generated schema, bound the exact Synesis MCP connection, and
performed `thread/start`, matching `thread/started`, and explicit
`turn/start`.

## START and identity evidence

The first real START returned `started`, state `RUNNING`, revision `5`, thread
`019fc760-e3b0-7453-a63f-e393f09a3be3`, and turn
`019fc760-f8fb-7bb3-98b0-408e99fab08c`. Later generations retained that exact
thread. No replacement thread was created during resume or continuation.

## Control and restart evidence

- Passive owner restart resumed the exact stored thread and performed
  `thread/read` without starting a turn: `thread_resumed_without_turn`, state
  `IDLE`, same thread and prior turn.
- Explicit continuation RESUME created a new turn on that same thread. A later
  explicit continuation created turn
  `019fc779-37ed-7492-bf1f-fdd645e435dd`.
- A long WAIT held by a separate Java client (PID 32648) did not occupy the
  control path: another client successfully STEERed the exact active turn,
  then successfully INTERRUPTed it. The waiter completed after the lifecycle
  transition; it did not block protocol reading or either control request.
- INTERRUPT returned an acknowledgement and `turn_interrupted`; the evidence
  journal recorded the exact `turn/completed` event with terminal status
  `interrupted` for the exact turn.
- The observed independent classification was
  `turn_interrupted_command_state_unconfirmed`: no active `run_command` had
  reached execution, so no MCP cancellation or command-tree termination is
  claimed.

## Duplicate replay and diagnostics

Using the same signed request envelope twice against the live owner produced
the same successful result for both STATUS calls and the same successful
result for both WAIT calls. No duplicate thread or turn was created. The owner
reported `ledgerUtilization=16/1024`, `bindingLedgerUtilization=16/128`,
`correlationFailures=0`, `lateResponseCount=0`, `tombstones=0`, and
`oversizedProtocolFrameFailures=0`. Two earlier state-changing attempts remain
explicitly `AMBIGUOUS` in the durable ledger; they were not replayed.

The installed server also sent a numeric server-request ID (`0`). The local
schema projection now accepts the generated string/int64 `RequestId` union and
the protocol client preserves the numeric JSON type when replying. The
focused deterministic protocol test covers this real-version behavior.

## Interaction-required boundary and command cleanup

When the explicit continuation asked Codex to invoke Synesis MCP
`run_command`, Codex 0.145.0 emitted
`mcpServer/elicitation/request` for `ensure_session`, with server request ID
`0`, message `Allow the synesis MCP server to run tool "ensure_session"?`, and
`activeFlags=[waitingOnApproval]`. The production protocol client recorded
authoritative `INTERACTION_REQUIRED` state and did not fabricate approval.
An acceptance-only `approval_policy="never"` override did not remove this MCP
elicitation handshake. Disabling Codex's stable
`tool_call_mcp_elicitation` feature changed the request to
`item/tool/requestUserInput`, but still required explicit Allow/Cancel input.
Therefore:

- active MCP request identity is recorded first for `ensure_session`; with the
  feature disabled, the later `run_command` call also reached the explicit
  Allow/Cancel prompt, but the command never executed;
- Codex-to-MCP cancellation propagation is not observed;
- `ProjectProcessExecutor` command cancellation and barrier-tree termination
  are not claimed;
- no false causal attribution is made between turn interruption and command
  cleanup.

## Hard stop and evidence generations

Hard-stop calls on verified attachment generations returned structured
`root_already_exited` results after the Codex root had exited. The deterministic
repeated-discovery fixtures cover spawning descendants, re-enumeration,
children-first forced termination, generation mismatch, PID/start-instant
reuse, and survivor reporting. This real run does not claim forced cleanup of
an active command tree. Generations 1--8 have bounded JSONL manifests;
generation 8 is marked incomplete because the Codex process entered the
interaction-required boundary before terminal detailed evidence was written.
After the final attachment stop, the disposable owner process was terminated
using its validated fixture PID; no fixture-launched Codex App Server, barrier,
or acceptance MCP process remained, and no stale owner record was adopted by
another process. The desktop's separately managed Synesis MCP process is not
part of that disposable owner tree and was not targeted.

## Post-restart direct MCP harness retry

After the Codex harness was restarted, a fresh disposable initialized project
was exercised through the installed Codex CLI with the existing Synesis MCP
configuration. `ensure_session` returned `ready` and established an isolated
worktree. The first attempted command shape was rejected by Synesis as
`invalid_path`; a retry using the supported `git_log` command intent completed
with exit code 0 and output `SYN038_MCP_RETRY_OK`. The managed Codex Synesis
Manual was refreshed through the existing provider-install workflow before the
retry. No project files were edited and no external shell command was used by
the Codex agent.

This is direct MCP-harness evidence only. It does not replace the
Codex-App-Server-owner acceptance and does not prove MCP cancellation,
barrier-process cleanup, or normal `finish_lane` validation/snapshot/
integration.

At 2026-08-03T18:00:25+03:00, the rebuilt current bundle was retried directly
through `synesis.bat mcp` after the harness restart. The stdio exchange
returned MCP `initialize` successfully, `ensure_session` returned `ready`,
and `run_command` completed with exit code 0. The bounded command output was:

```text
85f3ab8 Synesis managed baseline txn_c67c3f653e2247be8670fb1ae8731b63
```

The process exited 0 and reported `version=0.1.0-SNAPSHOT` on stderr. This
confirms the current MCP executable is launchable and can establish a fresh
isolated session and execute a supported read-only Git command. It remains
separate from the real Codex App Server lifecycle acceptance and does not add
evidence for turn interruption, MCP cancellation, or lane completion.

The first direct retry exposed that the global native launcher still pointed at
an older Java application bundle. The current platform bundle's application
JAR and dependency JARs were copied into the existing installed Synesis app
directory (the launcher path and configuration were unchanged). A second
retry through the exact global executable used by Codex,
`%LOCALAPPDATA%\\Synesis\\bin\\synesis-mcp.exe`, then returned `initialize`,
`ensure_session=ready`, and the same successful `git log` result, with process
exit code 0. No new route, tool, or provider was introduced.

## Second production-owner run after harness restart

At 2026-08-03 18:15 local time a second disposable project was exercised
through the current installed `synesis coordination serve` owner. The fixture
was `C:\Users\Liparakis\AppData\Local\Temp\syn038-final2-20260803-1815`
(project `95090e90-befe-495c-97a7-4ea5cdb51d7b`). The owner was host
`host-0d086bb2-7157-412a-a3b6-2998a118c782`, PID 51852, on port 64411. A
second simultaneous owner was rejected with
`COORDINATION_ERROR=lifecycle_owner_already_running`.

The first START attempt used the existing authority workflow and failed before
Codex launch with `lifecycle_claim_not_acquired`. Its exact intent was
released/fenced through the existing collaboration workflow; no App Server was
launched by that failed attempt. A subsequent fresh connection instance
(`syn038-final-appserver-2`) established a new verified binding and claim, then
`ProviderSessionCommand` completed START only after the durable lifecycle
ledger entry was committed and verified. The exact authority was:

- binding `session-0d824f6b-0a95-416b-b4cd-edc62ae9ae8b`, fingerprint
  `b12b1f919ce37eaccba71c5317739a88c509e057adfd594d436d02e4ffe9c3d2`,
  version 1;
- participant `agt_a0651913-e23d-3134-a049-49002b9bb4c7`;
- WorkIntent/lane `59bd0a3c-8e9c-3ec8-9cfa-1efe91661de5`, epoch 1, claim
  acquired;
- canonical and real worktree
  `C:\Users\Liparakis\AppData\Local\Synesis\workspaces\95090e90-befe-495c-97a7-4ea5cdb51d7b\worktrees\session-0d824f6b-0a95-416b-b4cd-edc62ae9ae8b`.

START returned thread
`019fc837-d48e-7fc2-95ff-c677f06e5135` and initial turn
`019fc837-e903-7813-a086-475a76aab5fa` at lifecycle revision 6. The generated
schema connection reached `thread/started` and explicit `turn/start`.
Codex 0.145.0 then requested MCP elicitation for `ensure_session`; the owner
correctly recorded `INTERACTION_REQUIRED` and did not fabricate approval. The
recorded independent classification was
`turn_interrupted_command_state_unconfirmed` after exact-turn interruption;
no MCP cancellation or barrier-process termination is claimed.

The same run independently evidenced:

- exact-thread hard stop (`root_already_exited`) and exact-thread passive
  `RESUME` (`thread_resumed_without_turn`) at revision 12;
- explicit continuation on the same thread, creating turn
  `019fc83b-b776-70c2-95e7-b95991e5ec1d`, which completed with the requested
  `READY` response;
- a long no-tool turn interrupted on exact thread/turn with acknowledgement
  and terminal interrupted event;
- STEER on an exact long-running turn, then exact INTERRUPT and event-driven
  WAIT completion;
- repeated identical STATUS and WAIT envelopes returning the same bounded
  result without creating a second thread or turn;
- bounded diagnostics at the end of the owner run: zero unexplained
  correlation failures, zero late responses, zero tombstone evictions,
  zero oversized protocol-frame failures, and evidence-complete generation
  manifests.

After the lifecycle attachment was stopped, the exact assigned lane was
validated through the existing ten-tool Synesis MCP surface (not attributed to
Codex): `ensure_session` returned `ready`, `apply_patch` changed the tracked
fixture file to `implemented`, `run_command` returned `validation_ok`, and
`finish_lane` returned `integrated` with snapshot
`snap_95f37ea353a1b13a62aea3b32ecc41d1`. This proves the existing MCP/session
and lane-completion path separately; it does not convert the earlier Codex
elicitation boundary into Codex-driven command-cancellation evidence.

The owner was then restarted for three seconds. It acquired the stale owner
lock/record, published a new host identity, and exited cleanly with owner
status `STOPPED`. The fixture control checkout remained clean after
integration. The owner, App Server, and fixture MCP processes were verified
absent at cleanup.

## Final3 harness-restart run

The harness-restart retry used disposable project
`C:\Users\Liparakis\AppData\Local\Temp\syn038-final3-20260803-1900`
(project `87a6c966-6bce-4d9c-b468-fa9697f255e9`) and the existing production
owner on loopback port `64422`. The newest binding was
`session-773d396e-18b7-4458-b3ae-f1b70d29c325`, connection instance
`syn038-final3-final-1920`, participant
`agt_185700ed-a746-34ed-8498-c97ee5c9b8b4`, lane
`9f78549f-2ff1-34b9-8e31-08e4fb0c6c8e`, epoch `1`, and the assigned isolated
worktree under the global Synesis workspace root. START returned the exact
thread `019fc870-b2bb-7b90-b8da-b381c05251e5`; subsequent turns retained that
thread.

The controlled foreground barrier was observed directly at PID `35292`. A
long HTTP WAIT was held while a second client sent STEER; STEER returned
`success=true, diagnostic=steered` at the unchanged active revision. The
WAIT caller eventually reached its own deadline because the model continued
coordination work, but it never prevented the control request from being
accepted. Steering changed `src/task_tracker.txt` to
`steered-implemented`, and the validation command returned `validation_ok`.

Independent interrupt fixtures recorded both cases required by the plan. A
turn with no observable MCP command returned `turn_interrupted` with
classification `turn_interrupted_command_state_unconfirmed`. A second turn
reached barrier PID `42108` before INTERRUPT; the exact interrupted terminal
event was recorded, the barrier process disappeared without an exit marker,
and the owner again returned
`turn_interrupted_command_state_unconfirmed`. Because neither Codex nor the
MCP transport exposed cancellation confirmation or a
`ProjectProcessExecutor` termination record, no command-cleanup success is
claimed.

The owner was then terminated and relaunched as new host instances. Startup
reconciliation was passive: it recorded the old root as exited/stopped and
did not start a turn. An explicit RESUME launched a replacement App Server,
performed `thread/resume` and `thread/read`, and returned
`thread_resumed_without_turn` for the exact same thread. A later explicit
continuation again reached `INTERACTION_REQUIRED` when Codex 0.145.0 emitted
`mcpServer/elicitation/request` for `get_next_action`; the local protocol
client preserved this authoritative state and did not fabricate approval.

The durable coordination projection contains the immutable snapshot
`snap_6a86317ef44f4e5b8ed36122d30400ba` and its integrity/provenance record.
The final3 lane still has pending coordination requests and is therefore not
represented as a completed/integrated Codex acceptance. This is intentionally
separate from direct MCP smoke evidence.

After the owner was stopped, the exact global executable
`C:\Users\Liparakis\AppData\Local\Synesis\bin\synesis-mcp.exe` was run with a
fresh smoke connection. MCP `initialize` succeeded and `tools/list`
returned exactly the ten raw tools: `ensure_session`, `read_file`,
`apply_patch`, `run_command`, `get_next_action`, `request_coordination`,
`respond_coordination`, `publish_capability_implementation`, `finish_lane`,
and `cancel_lane`.

## Harness MCP retry

After the harness restart, a newly spawned MCP process from the same global
bundle was exercised directly over stdio with connection
`syn038-harness-retry-2005`. It returned `initialize` with protocol version
`2025-06-18`, the exact ten-tool catalog above, and `ensure_session` with
`status=ready` and an isolated worktree. Its bounded stderr startup record
identified `synesis-mcp.exe` version `0.1.0-SNAPSHOT`, provider `codex`, and
the requested fixture project. The callable MCP connection already held by
the desktop harness was a long-lived process from the earlier bundle; calls
through that stale process returned an internal session-construction error.
It was not used as acceptance evidence and was left untouched.

The current production owner was relaunched with the installed
`synesis.cmd coordination serve` path. It acquired host
`host-23b0cfd8-5b7f-4037-a5d7-faddcce0969a`, passively reconciled the stored
attachment to `STOPPED`/`root_already_exited` at revision 46, and did not
create a turn. Keeping the exact old MCP connection alive reactivated its
participant heartbeat, but the projection showed the old claim had already
been released and no active WorkIntent remained. A lifecycle STATUS request
therefore failed closed with `lifecycle_claim_not_acquired`; no continuation
or provider mutation was attempted under stale authority.

A disposable direct App Server probe also tested the installed Codex controls
without changing Synesis. Codex 0.145.0 emitted the same
`mcpServer/elicitation/request` approval for `ensure_session` when launched
with `features.tool_call_mcp_elicitation=false`; launching with the global
`--dangerously-bypass-approvals-and-sandbox` flag still emitted the approval.
These probes confirm that the installed App Server has no usable
non-interactive approval path for this client. They are diagnostic only and
are not substituted for production-owner acceptance.

For the desktop transport diagnosis, the exact stale MCP Java child was
restarted without touching the Codex app process. The app-side transport then
reported `Transport closed` and did not respawn its child. A standalone
post-refresh invocation of the current global `synesis-mcp.exe` still returned
protocol `2025-06-18`, exactly ten tools, and `ensure_session=ready`; this
confirms the bundle is healthy while the desktop connector needs its own
process restart.

## Final3 owner run with the restarted harness and local Codex 0.146

After the harness restart, a fresh disposable run used the existing production
owner on loopback port `64336` and the local Codex executable
`C:\Users\Liparakis\AppData\Local\OpenAI\Codex\bin\d7e8094cfb76a267\codex.exe`
(`0.146.0-alpha.9.2`). The owner was started with the existing
`SYNESIS_CODEX_APP_SERVER_COMMAND` override containing Codex's global
`approval_policy="never"` and `sandbox_mode="danger-full-access"` flags. No
route, listener, approval operation, provider abstraction, or MCP tool was
added. This run is separate from the historical 0.145.0 elicitation runs
above.

The fixture was
`C:\Users\Liparakis\AppData\Local\Temp\syn038-final3-20260803-1900`
(project `87a6c966-6bce-4d9c-b468-fa9697f255e9`, Git baseline
`ff59aef29c2f3c46bb1401e5e467b215c70b553d`). The exact authority established
before START was binding
`session-7f2fb170-6ee3-4dcc-b3a0-734ffa37f11d`, fingerprint
`2210c73484168880a096b1f073217332b7df7ca135a7dda0dc227e7ced778a21`, version
1; participant
`agt_79de5fc5-6fe7-343a-86db-fc1f1c007824`; WorkIntent/lane
`f8183cc7-d173-3d31-aec4-c1e656bb0341`, epoch 1, acquired claim; and the
assigned canonical/real worktree under
`C:\Users\Liparakis\AppData\Local\Synesis\workspaces\87a6c966-6bce-4d9c-b468-fa9697f255e9\worktrees\session-7f2fb170-6ee3-4dcc-b3a0-734ffa37f11d`.
The first owner was `host-279c1171-1475-4290-9357-4c2fe60f6a8b` (Java PID
34120); the restarted owner was `host-21af94a1-98bf-4aba-b07a-51e66f0ee478`
(Java PID 58344). A competing owner was not admitted.

START completed only after the authority verification and durable ledger gate:
it returned revision 5, exact thread
`019fc905-7ff9-78b1-bffd-b03e79d77096`, and initial turn
`019fc905-81df-7470-ae15-f6b2e7e411eb`. The initial Codex turn reached the
existing Synesis MCP `ensure_session` and validation path without elicitation;
the intentionally unmodified fixture value made validation fail with the
recorded `expected steered-implemented, got 'original'` result. No lifecycle
identity was duplicated.

### WAIT/STEER control evidence

An explicit NOTIFY created turn
`019fc910-bcfa-7881-afec-97159b7adc5b` on the same thread and launched a
foreground Synesis barrier (`STEER1461`, PID 36820). A separate production
WAIT request (`44444444-4444-4444-8444-444444444444`) was held while a second
production caller submitted STEER
(`55555555-5555-4555-8555-555555555555`). STEER returned
`success=true, diagnostic=steered` for the exact active thread and turn; the
App Server evidence contains the matching `turn/steer` response and delivered
user message. The WAIT bridge remained independent of the control path and
exited after the terminal transition; it did not prevent STEER or protocol
draining.

The foreground MCP command occupied the single Synesis MCP connection. The
Codex agent therefore reported that the queued MCP mutation could not proceed
until the barrier returned. The barrier was released as fixture cleanup and
the turn completed without attributing the later file mutation to STEER. This
is direct evidence of control acceptance and exact identity, not a claim that
STEER cancels an active MCP command.

After the release, an explicit NOTIFY on the same thread used the existing
`apply_patch` workflow to set `src/task_tracker.txt` exactly to
`steered-implemented`; the approved validation returned `validation_ok` with
exit code 0. Codex then attempted normal `finish_lane`/snapshot/integration,
but Synesis returned `task_not_ready` with `nextAction=retry` on every attempt;
no snapshot or integration was fabricated.

### Independent INTERRUPT and MCP-command outcome

Another NOTIFY created turn
`019fc916-ad68-7e20-97d1-97f9465c99ea` and a foreground barrier
`INT1460` (PID 8388). A separate WAIT request
`88888888-8888-4888-8888-888888888888` (digest
`d857c76910d782baaa46550145e20346f2beb293828939d74e2cb85c3543ae87`) returned
`diagnostic=state_changed`, `state=IDLE`, and the exact thread/turn. The
INTERRUPT request
`99999999-9999-4999-8999-999999999999` returned
`diagnostic=turn_interrupted`, terminal state `INTERRUPTED`, and the service's
explicit `turn_interrupted_command_state_unconfirmed` classification. The
App Server evidence independently contains the successful `turn/interrupt`
response and exact `turn/completed` event with status `interrupted`.

Direct process evidence showed the `INT1460` barrier PID was still alive and
had no exit marker immediately after the interrupted event. After the
terminal event, the fixture release marker was written and the barrier exited
normally. The observed acceptance classification is therefore
`turn_interrupted_command_remained_active`: turn interruption was proven, while
Codex/MCP cancellation and `ProjectProcessExecutor` command-tree termination
were not. No causal claim equates `turn/interrupt` with child cleanup.

### Passive restart, exact-thread resume, and duplicate replay

The first owner was stopped by its exact recorded PID after the attachment
process had exited. The replacement owner passively reconciled the checkpoint
to `STOPPED`/`root_already_exited` at revision 25, retained the exact stored
thread, and emitted no autonomous `turn/start`. Explicit RESUME request
`aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa` returned
`thread_resumed_without_turn` at revision 28 after `thread/resume` and
`thread/read`; the replacement App Server used attachment generation 2 and
the same thread ID. A continuation RESUME request
`bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb` then created turn
`019fc91a-4c00-7b62-ba1e-7260b3b59485` on that same thread. Validation again
passed, while `finish_lane` remained honestly blocked at `task_not_ready`.

Repeated identical STATUS request `dddddddd-dddd-4ddd-8ddd-dddddddddddd`
and repeated identical WAIT request
`eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee` returned byte-equivalent bounded
results; no duplicate thread, turn, steer, or interrupt was created. Final
status diagnostics reported `protocolFailed=false`, zero tombstones, zero
oversized-frame failures, zero evidence drops, `evidenceComplete=true`, and
ledger utilization `11/1024` (`11/128` for the binding). The lifecycle record
ended at revision 34 after the replacement App Server process exited, with the
exact thread and turn retained.

This run proves production-owner reachability, authority-before-START,
durable START ordering, event-driven control concurrency, exact identity,
passive reconciliation, exact-thread resume, explicit continuation, and
independent interruption evidence. It does not claim Codex-driven command
cancellation or normal Synesis lane integration because the installed fixture
workflow remained `IMPLEMENT`/`task_not_ready` and issued no completion
snapshot or integration action.

### Final bounded completion retry

One final explicit continuation was attempted after a new owner restart using
the same exact binding and thread.  `ensure_session` was given the existing
task goal, acceptance text, and exact `src/task_tracker.txt` claim. Synesis
correctly rejected that attempt as `overlapping_claim` because the original
active lane still owned the path; the agent did not create a second claim or
silently replace the authority.  `get_next_action` remained `ready`/`IMPLEMENT`,
the file and validation remained correct, and `finish_lane` still returned
`task_not_ready`/`retry`. This confirms that the remaining completion gap is
an authoritative Synesis lane-readiness condition, not a missing lifecycle
control path. The owner and attachment were then stopped through their exact
recorded process identities.

### Fresh 0.146.0 completion run

After the harness restart, a fresh exact binding was established through the
existing `ProviderSessionCommand` authority workflow rather than reusing the
stale prior connection fingerprint:

- fixture: `C:\Users\Liparakis\AppData\Local\Temp\syn038-final3-20260803-1900`;
- project: `87a6c966-6bce-4d9c-b468-fa9697f255e9`;
- owner: `synesis coordination serve`, host
  `host-d30c71aa-3b9b-4ba0-a83e-1b59ca393bef`, Java PID `14008`, loopback port
  `64337`;
- binding: `session-8bcc6cc7-1bbf-423a-a28c-ef10a0960b06`, fingerprint
  `c5944b40fefa93bfc843b5e74cad2a24d01ed7331cd8ba2a8b1bbc8ee78ceba7`,
  connection instance `syn038-completion-1460`, version `1`;
- participant: `agt_450cd0d6-1c5c-3019-b2d3-b38ba6752a9d`;
- lane/WorkIntent: `df85b1e2-d802-38c0-9284-c0df7420426d`, epoch `1`, claim
  acquired;
- isolated worktree:
  `C:\Users\Liparakis\AppData\Local\Synesis\workspaces\87a6c966-6bce-4d9c-b468-fa9697f255e9\worktrees\session-8bcc6cc7-1bbf-423a-a28c-ef10a0960b06`;
- exact thread: `019fc943-ecc7-79f1-af19-930044fc2155`.

The first START used the existing owner and reached the exact thread, but its
initial turn exposed that the unqualified `codex` command resolved an older
`0.130.0-alpha.5` executable whose configured model was unsupported. That
turn failed without a duplicate thread or mutation. The owner was restarted
with the installed `0.146.0-alpha.9.2` executable at
`C:\Users\Liparakis\AppData\Local\OpenAI\Codex\bin\d7e8094cfb76a267\codex.exe`.
Passive reconciliation then performed `thread/resume` and `thread/read` on
the same exact thread; it emitted no autonomous turn. An explicit continuation
RESUME created turn `019fc947-5a61-7910-aa04-d9bad01b811a`.

That real Codex turn used the existing ten-tool MCP surface to:

1. update `src/task_tracker.txt` to `steered-implemented`;
2. run the configured validation, returning `validation_ok` with exit code 0;
3. call `get_next_action` with the exact `integrationCheck` fields and
   `testsPassed=true` (the canonical claimed-path representation was accepted);
4. call `finish_lane`, which published snapshot
   `snap_f3bd879455900258bb77ca6cea8fac22`.

The first `finish_lane` result correctly remained
`owner_request_pending` because three stale disposable-fixture coordination
requests were pending. Those requests were resolved as rejected stale
negotiations through the existing coordination service; no claim, file, or
provider authority was replaced. A second explicit continuation on the same
Codex thread called `finish_lane` again and returned:

```text
status=completed
task=integrated
snapshotId=snap_f3bd879455900258bb77ca6cea8fac22
validation.outcome=completed
validation.exitCode=0
validation.stdout=validation_ok
```

The control checkout ended clean with HEAD
`9e30f4183434a5f282a6e42fb55e4339c0879578` (`Synesis immutable lane
snapshot`). The assigned worktree retained the expected uncommitted marker
until integration; no control-checkout edit or Git bypass was used. The
connection-generation evidence contains matching `thread/resume`,
`thread/read`, `turn/start`, MCP tool calls, `turn/completed`, and the final
`integrated` result. This closes the normal Codex validation/snapshot/
integration acceptance while preserving the independent interrupted-command
classification above.

## Repository verification

The final sequential root command `./gradlew.bat check --no-daemon
--max-workers=1 --console=plain` completed successfully in 15m24s after the
long Git-heavy fixtures and ended with `BUILD SUCCESSFUL`. The resulting
reports contain zero failures and zero errors across the repository test
modules (the prior report inventory was 502 tests). The focused Codex
lifecycle suite contains 26 tests and passes. Bootstrap `go test -count=1
./...`, `go vet ./...`, deferred and fixture validation, strict
Javadocs/static and format checks, and `git diff --check` also pass.

## Remaining independent outcome

The interrupted-turn fixture did not expose Codex-to-MCP cancellation or direct
`ProjectProcessExecutor` command-tree termination. It remains honestly
classified as `turn_interrupted_command_remained_active`; no child-cleanup
guarantee is inferred from the exact interrupted terminal event. The fresh
0.146.0 completion run above proves the separate Codex validation, snapshot,
and integration path without adding an approval operation or weakening the
Codex-only lifecycle surface.
