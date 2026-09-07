# SYN-049 fresh unattended two-worker acceptance — run #59

Date: 2026-09-06
Classification: **PARTIAL / ACTIVE; run stopped at first material production boundary**
Production source changes: **none**

## Provenance and host

The Synesis source checkout started at `ddb3266b1c0200c7d3468afeee165c4b55e88bd1`,
branch `master`, clean. Runtime source provenance remained
`a7697bbb5de83ced8b61b275056f9204e4467fbc`. The four locked artifact hashes
matched exactly:

- workspace `0b741052a309281361105a49397f0b52e4fddc144d1b74cd93e9c4f688e97ec3`;
- MCP `029cca350d77a9d362b37f5ae74bb7fa32eb1e20c30138c96d948900556aeb5f`;
- CLI `7da82f6ed68dcdbc9672b313a3a3f1add36f1fc2b302cc4d6afce49891c57994`;
- native MCP `4719257fccd82513df464e0e35f6ddb7f1fd25931ae24347a2237a2d71ce5a17`.

The validation host was JDK 25 at
`C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot` with only the
process-local property
`-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`. Selector and
minimal HttpServer preflight were already passing. No global Java, network, or
Codex setting was changed.

The prescribed native executable path was functionally checked read-only and
reported `SYNESIS_MCP_LAUNCH_ERROR=Synesis lib directory missing` because the
standalone native directory contains only the executable. The installed copy
at `cli/build/install/synesis/bin/synesis-mcp.exe` has the required adjacent
`lib` directory and matched the native executable hash exactly. Run #59 used
that hash-identical installed copy; no rebuild occurred.

## Fresh target and identities

Fresh target: `C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260906-59`.
It was initialized from a new Git fixture, then initialized through the
supported Synesis application flow and installed with the supported Codex
provider flow. Baseline branch was `main`, baseline fixture commit was
`4ef3e3472817e5b0fdab5e04c7e3d91472d3fede`, and project ID was
`2b1a6a90-bb3f-4d33-8cad-6f9fe63ca038`. No historical `.synesis` state, lane,
binding, participant, intent, claim, provider thread, or worktree was reused.

WorkGroup: `a7b47d76-3dc4-3ce3-8769-9f2022900d46`.

Worker A:

- binding `session-b2e8aab2-9a40-4001-be38-6491be3c258f`;
- participant `agt_da5de781-9535-3724-a0d9-5e8924d9a878`;
- WorkIntent `b2f8005e-9cb0-3561-bbb9-499b9487e21a`;
- claims: `src/main/java/tasktracker/domain`,
  `src/main/java/tasktracker/persistence`,
  `src/test/java/tasktracker/domain`,
  `src/test/java/tasktracker/persistence`.

Worker B:

- binding `session-08b27db2-6ab8-46d0-a852-25a02eb6050e`;
- participant `agt_32e28624-38cb-355b-ac2e-e6bc14fbdd16`;
- WorkIntent `8c4dca14-ea70-32a5-890f-b7a990224767`;
- claims: `src/main/java/tasktracker/application`,
  `src/main/java/tasktracker/api`,
  `src/test/java/tasktracker/application`,
  `src/test/java/tasktracker/api`;
- exact declared dependency: `tasktracker.domain.persistence`.

## Managed lifecycle

One validation-host JVM and one `ProjectRuntimeHost` held both launcher
preparations through START. A prepared as generation 1
`PENDING_ACTIVATION`, proof digest
`f24d0bb5462584df60e94fc6355ada4ee3e55164ebf1e48013ff215417048343`, null
provider thread, and no ownership. B prepared as generation 1
`PENDING_ACTIVATION`, proof digest
`843cb20d31c079d4f8274bde0889af2c3b82d3a6b22e56a66497222d2e4c21e0d`, null
provider thread, and no ownership. Raw proofs were never printed or persisted
in evidence.

Both same-process START operations succeeded with generation 1 and the same
prepared binding. No second preparation, generation 2, bootstrap App Server,
or replacement operation occurred. The exact provider threads were created by
the same App Servers:

- Thread A `01a0764b-baea-7ac1-acec-5a6b09983c8c`;
- Thread B `01a0764a-9d24-7db2-8ae6-6eec0b0cc1d5`.

Observed App Server roots were PID `26364` for A and PID `9636` for B. The
run-scoped MCP children were PID `15552` for A and PID `15888` for B, each with
its Java child. The managed supervisor accepted the process trees and the
same-process MCP startup succeeded; no bootstrap App Server was used.

B completed its initial turn while its attachment remained alive. A then
performed the capability-owner work. The exact capability lifecycle advanced
`AWAITING_OWNER → ACCEPTED → IMPLEMENTATION_AVAILABLE → VALIDATED`. Provider
native wake was observed on the original B thread:
`sameThread=true`, initial B turn
`01a0764a-9e4d-71c0-9915-2af3624e83a9`, wake turn
`01a07651-c1aa-72c1-b29b-0d4f386bf91a`.

A published its claimed domain/persistence implementation. B consumed the
server-issued review grant on its original provider thread and received the
published review path. Both lifecycle checkpoints remained generation 1 and
ended `COMPLETED` during normal harness cleanup. No A2, replacement, substitute
B, deliberate death test, or Worker B was created.

## First material boundary

After B consumed the exact review grant, the durable state remained
`SNAPSHOT_PENDING`. B's original provider thread reported that the published
dependency source could not be exposed in its exact assigned worktree:
`workspace_mismatch: the files exist only in the control checkout`.

Consequently B could not lawfully compile/validate its downstream application
and API work or reach final completion. The target control checkout contains
A's integrated snapshot at `c4be749` and is clean; B's exact worktree retains
its uncommitted application/API files. No files were manually copied between
worktrees and no state was repaired.

This is a production collaboration/snapshot-consumption boundary, not a
provider authentication failure. The run was stopped immediately after the
material failure by interrupting the disposable harness; normal host cleanup
removed the run-scoped process tree. No replacement or trusted-death evidence
was invoked.

## Hygiene and remaining blocker

No raw proof was logged, persisted, copied to the target, or placed in global
Codex configuration. No provider credentials were read or copied. The
process-local AF_UNIX property was not persisted or propagated to the MCP
child; the hash-identical installed child worked without that propagation.

SYN-049 remains **ACTIVE / PARTIAL**. The exact remaining blocker is that an
accepted review grant reaches `SNAPSHOT_PENDING`, but the original B cannot
read the immutable A dependency snapshot from its authorized worktree. The
next action is a read-only source diagnosis of snapshot materialization and
review-workspace routing before any new acceptance target. Do not bypass the
grant, copy worktrees, repair durable state, invoke replacement, start A2, or
start an independent Worker B.
