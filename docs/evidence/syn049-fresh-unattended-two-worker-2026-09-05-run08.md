# SYN-049 fresh unattended two-worker acceptance — run #08

Date: 2026-09-05  
Classification: **PARTIAL**  
Target: `C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260905-08`  
Project: `8b7abee0-950a-4d7a-81a7-70c5daa81e69`  
WorkGroup: `7ed6723d-06c7-3d59-9e26-8285576b474e`

## Scope and provenance

This was a fresh two-worker run. Historical runs #04–#07 were not reused.
The installed artifacts were rebuilt after the source corrections and matched
their produced copies. The run used JDK 25 with the process-local property
`-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`; no global Java,
network, Codex, or Synesis configuration was changed. The source correction
was committed as `d73a9a3`.

## Observed positive evidence

- A binding: `session-66b49eea-3ff7-46c2-94f9-41b58220fe7b`;
  participant: `agt_b1ac0f90-e7b0-3d3e-8597-0cf9a61ac65d`;
  intent: `6ab686c3-c2c7-39ed-8793-3f7f3718e6d5`.
- B binding: `session-2b57ddfa-d4b1-4afc-bf62-82440cae8868`;
  participant: `agt_ba1ba2f-0eac-33fa-9a5c-9f2422b2e287`;
  intent: `64307111-edd2-3efc-aa10-5859d2e24d9c`.
- Both `prepareFirst` results were generation 1 and
  `PENDING_ACTIVATION`, with distinct recorded proof digests. Provider
  threads were unresolved before START and no ownership existed then.
- B START returned thread
  `01a072ae-5d1b-7dd2-be68-6dca38d40663`; its initial turn completed at
  generation 1 and its attachment was ACTIVE.
- A START returned thread
  `01a072b0-b234-7e10-a276-5e2271525b2c`.
- A's real Codex work mutated only its assigned source/test lane, published
  capability `tasktracker.domain.persistence`, and `finish_lane` returned an
  integrated published snapshot (`snap_91627f26c37af133c8ea4e07225f43f5`).
- The exact-binding projection correction was exercised: after A accepted B's
  request, A received the proper implementation projection instead of B's
  requester record.

## First material failure and stop

After A's asynchronous turn, the harness status request failed with
`lifecycle_binding_stale` from `ProjectRuntimeHost.verifyAuthority`. The
failure occurred before B consumed the capability. The evidence shows A's
turn ended as `interrupted` during normal cleanup; both managed binding files
ended at generation 1 with `STOPPED` / `managed_job_empty`. The target control
checkout remained clean, while A's isolated worktree retained its uncommitted
claim files. No WorkGroup terminal state was reached.

The run was not restarted. No A2, replacement, Worker B, manual `.synesis`
edit, proof recovery, credential access, or provider-state modification was
performed. The resulting death receipts are incidental cleanup evidence, not
trusted-death validation.

## Verification

Focused tests covering the binding projection correction, capability request
scoping, wake admission/coordinator, and ProjectRuntimeHost wake path passed.
`git diff --check` passed before commit. No production source was changed after
the committed correction. SYN-049 remains **ACTIVE / PARTIAL**; SYN-051
remains separately **COMPLETE / PASS-A**.
