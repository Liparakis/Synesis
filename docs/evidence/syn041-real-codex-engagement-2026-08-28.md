# SYN-041 real Codex initial-engagement validation

Date: 2026-08-28

## Scope and starting state

- Starting HEAD: `f5622eba03c7631a7e3c8620a5598e8037ded001`
- Branch: `master`
- Working tree: intentionally dirty; the eight durable SYN-041 files and five
  pre-existing workspace lifecycle files were preserved. No product source was
  changed by this validation.
- SYN-039 remained `DONE / ACCEPTED` at CP-0547. SYN-040 remained
  `DONE / VERIFIED`.
- Codex CLI: `0.145.0`
- Official bundle:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64`
- MCP executable SHA-256:
  `07F23EF1E1C9C6D344CA31A640CAA92BD483345C6F8260DE82A84C69F9E4A53B`
- Packaged CLI JAR SHA-256:
  `E5D10201094A99925E975DC593A8DF606DE7308A080E48652186D07DAE313329`
- Packaged MCP startup metadata: version `0.1.0-SNAPSHOT`, commit `bc334ac`.

## Phase A: previous real probe reconstruction

The exact prompt passed to the previous authenticated Codex process was:

```text
Use the configured synesis MCP server for this real provider lifecycle validation. Do not use shell or direct filesystem operations. First call ensure_session with exactly this task object: {"goal":"Read verification.txt and verify that it contains ok.","acceptance":"verification.txt contains the exact text ok and no repository mutation is required.","claims":[{"kind":"path_exact","path":"verification.txt"}],"completionMode":"no_change_allowed","role":"producer"}. Then follow normal projected Synesis actions. Read verification.txt through Synesis MCP. When the task is satisfied, call get_next_action with no arguments and execute only its exact recommended tool and arguments. Explicitly execute the projected finish_lane no-change action and continue until it returns completed. Do not stop at WAIT, provider EOF, or visible task completion. After completed, terminate naturally; do not kill this process.
```

The provider-visible manual/tool guidance included:

- `ensure_session`: “Before visible task mutation, include task.goal,
  task.acceptance, and task.claims to announce intent and acquire the exact
  repository-relative ownership selectors; likelyScopes alone does not
  announce work or acquire claims.”
- `get_next_action`: call with no arguments for the durable inbox; an exact
  `recommendedTool` and typed `arguments` are required; continue ordinary
  coding when no lifecycle action is projected; use bounded waits for WAIT;
  do not stop while the WorkGroup is active.
- The ready-session instruction: “Keep the provider in its current project
  directory. Use Synesis MCP for all reads, writes, and commands; Synesis
  applies them internally in this assigned worktree.”

The preserved JSONL transcript shows that the provider did **not** issue the
empty call described by the continuation hypothesis. Its first tool call was:

```json
{"name":"ensure_session","arguments":{"task":{"goal":"Read verification.txt and verify that it contains ok.","acceptance":"verification.txt contains the exact text ok and no repository mutation is required.","claims":[{"kind":"path_exact","path":"verification.txt"}],"completionMode":"no_change_allowed","role":"producer"}}}
```

The response was `status=ready`, `workspace=isolated`, `pending=0`, with an
assigned worktree, but it exposed no participant, intent, claim, WorkGroup, or
next action field. The next provider call was exactly:

```json
{"name":"get_next_action","arguments":{}}
```

The Codex JSONL item remained `status=in_progress` until the disposable active
session was terminated as the authorized crash control. No MCP response was
captured from that call. The provider had not issued `read_file` first.

## Phase B-E: current contract and zero-work behavior

Current source and catalog establish this sequence:

```text
task-bearing ensure_session
  -> verified session/worktree
  -> explicit claim announcement in the MCP handler
  -> participant, WorkIntent, claim, and WorkGroup
  -> read/command work through Synesis
  -> get_next_action({})
  -> exact projected lifecycle action or a durable wait/terminal response
```

Empty `ensure_session({})` is valid for workspace/session readiness, but it
does not contain the task or claims needed to announce work. It is not supposed
to invent a participant, WorkIntent, claim, or WorkGroup. The task and exact
selectors must be submitted explicitly in the nested `task` object; the
supported engagement call is:

```json
{
  "task": {
    "goal": "Read verification.txt and verify that it contains ok.",
    "acceptance": "verification.txt contains the exact text ok and no repository mutation is required.",
    "claims": [{"kind":"path_exact","path":"verification.txt"}],
    "completionMode": "no_change_allowed",
    "role": "producer"
  }
}
```

`get_next_action({})` is valid before a WorkIntent exists, but the current
`AgentNextActionService` has no blocking, sleep, future, or long-poll path. In
the zero-work state it synchronously returns a ready no-action collaboration
payload (or a readiness/claim-required response when applicable). Therefore
the observed MCP `in_progress` item is not a defined server-side zero-work
state; it is an outstanding provider/MCP interaction that did not yield a
response during the previous run. The server does not project
`nextAction=get_next_action` from the empty ready response.

Classification of the prior probe:

- D1: not proven; the provider manual explicitly requires task-bearing claims.
- D2: not proven; the nested task schema and claim descriptions are explicit.
- D3: not proven; empty ready output contains no next-action instruction.
- D4: observed as a contributing provider execution failure: Codex skipped the
  required `read_file` operation and left the following poll outstanding.
- D5: primary contributing cause: the prior harness prompt described the order
  in prose but did not enforce the immediate, numbered operation sequence.
- D6: not needed; the controlled run resolves the ambiguity without a product
  change.

## Phase F-G: controlled real provider and synthetic control

Fresh real project:

`C:\t\syn041-real-codex-engagement-20260828-002`

Project ID: `95b36225-805d-4377-ab89-b8586cbb23b8`
Connection: `syn041-real-codex-engagement-20260828-002`
Session: `session-7dc9613d-c098-410a-ae59-ef7cfc7c02cd`
Assigned worktree:
`C:\Users\Liparakis\AppData\Local\Synesis\workspaces\95b36225-805d-4377-ab89-b8586cbb23b8\worktrees\session-7dc9613d-c098-410a-ae59-ef7cfc7c02cd`

The fresh provider-visible prompt required, in numbered order, task-bearing
`ensure_session`, immediate `read_file`, empty `get_next_action`, exact
projected action execution, and natural termination. Codex performed all of
those calls without manual MCP calls on its behalf:

1. `ensure_session` with the exact task-bearing payload above: `ready`.
2. `read_file({"path":"verification.txt"})`: `completed`, content `ok\n`.
3. `get_next_action({})`: `ready`, `nextAction=finish_lane`,
   `recommendedTool=finish_lane` with exact typed arguments.
4. `finish_lane` with those exact arguments: `completed`, `outcome=NO_CHANGE`,
   `claimsReleased=true`, `workGroupState=COMPLETED`.
5. Codex exited with code `0` and reported lifecycle completion.

IDs reached in the successful real run:

- participant: `agt_f10b6f55-816e-3cc9-8aa1-47c1a6574bcd`
- intent/lane: `296e6e7a-f9df-3553-ae21-8176b40edcea`
- WorkGroup: `120f3876-2a53-3eeb-b33b-e6f64c7e7bb2`
- claim: `path_exact:verification.txt` (the protocol has no separate claim ID)

The independent packaged synthetic control used fresh project
`C:\t\syn041-engagement-control-20260828-003`. It returned task-bearing
`ensure_session=ready`, `read_file=completed`,
`get_next_action=finish_lane` with participant/intent/WorkGroup IDs, and the
same exact finish operation completed with `MCP_EXIT=0`. This proves the
server-side admission/projection path independently of Codex.

## Result and boundary

Primary result: **RESULT A — no product defect**. The prior probe did not
enforce the operation order strongly enough for the provider. The supported
integration and current server correctly admit explicit task-bearing work, and
the fresh real Codex run completed the initial-engagement chain.

No product code, MCP schema, tool description, provider manual, readiness
projection, Doctor classification, lease behavior, or SYN-039 semantics were
changed. No official bundle rebuild was required; the existing official bundle
was used and its hashes are recorded above. The Gradle focused catalog test
could not start because this host again returned
`java.io.IOException: Unable to establish loopback connection`; the direct
packaged synthetic control and real provider JSONL are the executed focused
controls for this measurement.

The successful run proves engagement, but its process-topology capture was not
instrumented for Codex and native-launcher parent PIDs. The prior failed run's
captured topology remains: Codex PID `17156`, MCP PID `19784`, packaged Java
PID `11356`, connection `syn041-real-codex-connection-20260828-007`, and
session `session-d2915a2f-55f2-4fd0-8277-0d7cb89d643b`. The successful run's
lease recorded Java PID `10988`; no PID inference is made beyond that record.

SYN-041 remains `ACTIVE`. The initial-engagement stop condition is satisfied;
clean-exit lease measurement is not continued in this task without explicit
authorization. SYN-039 remains `CLOSED / ACCEPTED`, no generalized identity
architecture or new task/milestone was created, and nothing was pushed,
tagged, or released.
