# SYN-082 lifecycle recovery and blind interruption evidence — 2026-09-18

## Scope and authority boundary

This evidence records the lifecycle repair authorized after the latest blind
Codex acceptance run. Work was performed directly in the Synesis source
checkout with ordinary Git/human authority. No Synesis coordination/MCP
connection was opened for the source checkout, and no source `.synesis` state
was created or modified. No commit or push was made.

The disposable acceptance project was:

```text
C:\Users\Liparakis\Desktop\SynesisBlindRecovery
ProjectId=8ca26e53-f13a-47c7-bbc2-fd48765cc8b2
baseline HEAD=2b46d0b
```

The rebuilt launcher was `0.1.0-dev.local-syn082`. The target's normal Codex
provider installation was refreshed to that bundle before the blind run. The
MCP catalog remained exactly ten tools.

## Source findings

The source trace established the following lifecycle boundary:

1. An ordinary stdio MCP handler owns one exact provider-session binding and
   one ephemeral connection instance. The binding is not selected by latest
   PID, thread, or filesystem state.
2. A clean EOF emits the existing clean-close/detach path. A hard process kill
   runs neither the clean close nor abnormal-close callback, so the lease is
   initially still projected `ACTIVE` even though the process is gone.
3. `SessionLeaseService` derives `SUSPECTED_STALE` and then
   `RECOVERY_ELIGIBLE` from the persisted process evidence and the configured
   five-minute abandonment grace. Discovery is read-only; plan execution is a
   separate durable operation.
4. The ordinary MCP admission path renewed its exact lease but did not invoke
   the existing `ReconciliationService`. Therefore a fresh admission did not
   execute the already-supported recovery transition.
5. Discovery also planned against terminal participants when an old lease file
   remained recovery-eligible. That could make a completed/detached lane look
   recoverable.

The minimal repair is recorded in
`docs/adr/0082-admission-triggered-lane-reconciliation.md`:

- after the first successful exact `ensure_session`, ordinary MCP admission
  invokes the existing reconciliation plan once per handler;
- discovery excludes terminal participants;
- execution revalidates participant state immediately before each mutation and
  skips a stale plan if the participant became terminal;
- claims remain retained, no continuation grant is fabricated, no new MCP tool
  is added, and `get_next_action` is unchanged.

The admission hook is intentionally best-effort and bounded: a reconciliation
failure is reported to the MCP process log and does not turn the admission path
into an authority-transfer mechanism.

## Focused verification

The following focused command passed after formatting:

```text
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe'
.\gradlew.bat :workspace:test --tests org.synesis.workspace.lifecycle.reconciliation.RecoveryLifecycleTest :mcp:test --tests org.synesis.mcp.application.McpAdmissionRecoveryTest --console=plain
```

Result: `BUILD SUCCESSFUL`; 15 actionable tasks; both lifecycle classes passed.
The tests prove:

- a dead ordinary lane is fenced and held once on admission;
- a second admission is idempotent and emits no duplicate recovery actions;
- a plan cannot suspend a participant that became `COMPLETED` before execution;
- the ordinary MCP catalog remains exactly ten tools;
- the MCP admission path invokes the existing recovery lifecycle without
  releasing claims or issuing a new grant.

The rebuilt distribution also passed:

```text
SYNESIS_VERSION=0.1.0-dev.local-syn082 .\gradlew.bat :cli:installDist --console=plain
SYNESIS_VERSION=0.1.0-dev.local-syn082 .\gradlew.bat :cli:platformBundle --console=plain
```

The stable installed `synesis.cmd` and `synesis-mcp.exe` both reported the new
version. `git diff --check` reported no whitespace errors. A module-wide
`:workspace:test :mcp:test` attempt was stopped after roughly sixteen minutes
inside historical long-running MCP integration tests; no completed failure was
reported, but the MCP result phase was not reached. This is incomplete broad
verification, not a full-suite pass claim.

## Targeted recovery result

Before the targeted recovery, the disposable project had three
`RECOVERY_ELIGIBLE` records from the earlier run. The repaired dry run reported
four executable actions: the two unfinished non-terminal lanes were eligible,
while the completed lane and already-held lanes were excluded from mutation.

The executed plan was:

```text
plan=recplan-ad07eb03f7e348ca962d3173ca31b176
actions requested=4
completed=4
skipped stale=0
ambiguous=0
failed=0
```

After execution, the old CLI and verification participants were
`RECOVERY_HELD` with their exact claims retained. The completed core participant
remained `COMPLETED`. A post-recovery dry run exposed zero executable actions.
No checkout files were modified and no process was terminated by the plan.

## Blind interruption, replacement, and observed projection

Exactly two fresh Codex processes were launched with the supplied minimal
blind prompt, with no product goal, role, task split, or cross-agent message
added. Both independently discovered the durable issue-tracker goal and chose
disjoint claims:

- Agent A: `SMOKE_TEST_REPORT.md`;
- Agent B: `REVIEW_NOTES.md`.

The actual Windows process tree for Agent A was:

```text
codex outer PID 13996
  -> synesis-mcp.exe PID 28920
       -> Java PID 24612
```

Agent B completed independently. Agent A was then force-stopped at the outer
Codex process boundary while active. The MCP and Java children disappeared with
it; no helper remained alive. The exact Agent A lease nevertheless remained
persisted as `ACTIVE` immediately after the kill, which is expected because a
hard kill cannot run the clean-detach callback.

The existing policy then behaved as designed:

- before five minutes: the dead sessions were `SUSPECTED_STALE` and had no
  executable recovery actions;
- after the five-minute grace: the dry run showed five recovery-eligible
  records overall and four executable actions for the two fresh unfinished
  lanes;
- the replacement's first successful admission triggered the repaired hook and
  moved the interrupted fresh lanes to `RECOVERY_HELD`, retaining their
  `SMOKE_TEST_REPORT.md` and `REVIEW_NOTES.md` claims.

The replacement was launched with the identical minimal blind prompt. It
created a fresh participant and independently claimed
`INTEGRATION_REPORT.md`. It ran eight automated tests plus the CRUD/status/
archive/delete/persistence CLI smoke flow, published the report, and did not
modify implementation-owned files. Its publication projection ended at
`owner_request_pending` / `INTEGRATION_PENDING`; no lawful continuation action
was projected. The target's final read-only projection was:

```text
old core participant        COMPLETED
old CLI lane                RECOVERY_HELD
old verification lane      RECOVERY_HELD
fresh interrupted Agent A   RECOVERY_HELD
fresh completed Agent B     RECOVERY_HELD
replacement report lane    ACTIVE
dry-run executable actions  0
work group                  ACTIVE
```

The target checkout itself remained clean. An unrelated global Codex HTTP MCP
at `127.0.0.1:64580` continued to emit startup warnings; the Synesis stdio MCP
was healthy and the warning did not prevent admission or recovery.

## Acceptance verdict and remaining defect

The repair is correct for the exposed lifecycle omission: ordinary MCP
admission now triggers the existing bounded recovery plan, recovery is
idempotent, and terminal participants cannot be revived by an old lease or
stale plan. The process-tree interruption also proves that Synesis does not
mistake a dead runtime for an already-clean detach.

The full blind acceptance remains **PARTIAL**. Recovery fencing deliberately
retains claims and does not auto-issue continuation authority. In the fresh
replacement run, `get_next_action` reported
`coordination_intent_required`; the replacement could lawfully take a new
disjoint audit claim, but no evidence showed that it could lawfully inherit or
continue the held CLI lane (`pyproject.toml`, `cli.py`, and
`tests/test_issue_tracker.py`) and integrate it. That is a separate
continuation/grant or operator-response gap, not a reason to weaken claims,
timeouts, or authority checks.

Accordingly:

- a dead runtime is not considered capable merely because its old projection
  is still `ACTIVE` during the grace period;
- `WorkIntent` describes unfinished participant work and keeps the participant
  projected active while the bound runtime heartbeats, but it is not proof that
  a dead process is alive and must not defeat lease liveness;
- the repaired admission/recovery path is proven, while fresh blind
  end-to-end integration after interruption remains unproven;
- no further blind launch or manual continuation assignment is authorized by
  this evidence. A follow-up task must explicitly define and review the lawful
  continuation-authority boundary first.
