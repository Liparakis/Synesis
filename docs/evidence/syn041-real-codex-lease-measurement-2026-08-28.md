# SYN-041 final real Codex lease measurement

Date: 2026-08-28  
Starting HEAD: `f5622eba03c7631a7e3c8620a5598e8037ded001`  
Branch: `master`

## Scope and controls

This was the explicitly authorized final unresolved SYN-041 measurement. It
did not reopen SYN-039 or SYN-040 and did not change provider guidance,
`ensure_session`, `get_next_action`, lease semantics, Doctor semantics, or
provider migration. The run used the default authenticated Codex account,
the official packaged Synesis MCP executable, and a fresh disposable Git
project. No shell or direct filesystem operation was issued by the provider.

- Codex CLI: `codex-cli 0.145.0`
- Authentication: default ChatGPT login; `CODEX_HOME` was not overridden
- Official bundle:
  `C:\Users\Liparakis\Desktop\Synesis\cli\build\platform-bundle\synesis-0.1.0-dev.local-windows-x64`
- MCP SHA-256:
  `07F23EF1E1C9C6D344CA31A640CAA92BD483345C6F8260DE82A84C69F9E4A53B`
- Packaged CLI JAR SHA-256:
  `E5D10201094A99925E975DC593A8DF606DE7308A080E48652186D07DAE313329`
- Fresh project: `C:\t\syn041-real-codex-lease-20260828-002`
- Project ID: `911cc3cd-3d2a-436b-9b8f-9ca1193420b0`
- Baseline commit: `8bb89a99cc49be6602ff59d7a41b226e52ace8e5`
- Baseline file: `verification.txt` contained exact text `ok` plus newline
- Connection: `syn041-real-codex-lease-20260828-002`
- Session/binding: `session-87275e3f-557d-4022-91f0-0579f9efacd6`
- Participant: `agt_d3e28734-3e84-38d1-a425-54138e276a23`
- Intent/lane: `4efda2bd-5fd6-31f7-9e56-2ce9491977c0`
- WorkGroup: `8bb192f1-dc6a-3c0e-8997-58fab5bd5376`
- Authority lineage: `a9eadffa-38bd-3cba-899b-ea3cfa53fbc5`
- Claim: `path_exact:verification.txt`, epoch 1

## Provider completion evidence

The captured real Codex JSONL recorded the required provider-driven sequence:

1. Task-bearing `ensure_session` returned `status=ready` and an isolated
   worktree.
2. `read_file({"path":"verification.txt"})` returned `status=completed`,
   content `ok\n`, and `truncated=false`.
3. `get_next_action({})` returned `status=ready`, `nextAction=finish_lane`,
   with the exact typed no-change payload and `expectedRevision=4`.
4. Codex executed that exact `finish_lane` payload. The result returned
   `status=completed`, `outcome=NO_CHANGE`, `claimsReleased=true`, and
   `workGroupState=COMPLETED`.
5. Codex reported lifecycle completion and exited with code `0` at
   `2026-08-28T16:39:21.1133482Z`.

The disposable control checkout remained at baseline commit
`8bb89a99cc49be6602ff59d7a41b226e52ace8e5` with no status changes.

## Process and lease evidence

The measured process topology was:

| Process | PID | Parent | Observed command |
|---|---:|---:|---|
| Codex provider | 5608 | harness root | `codex exec --json --ephemeral ...` |
| Official `synesis-mcp.exe` | 20908 | 5608 | official bundle `mcp --provider codex --project ... --connection-instance-id syn041-real-codex-lease-20260828-002` |
| Packaged Java runtime | 18076 | 20908 | official bundle Java running `org.synesis.cli.SynesisCli mcp ...` |

Codex started at `2026-08-28T16:38:55.1645554Z`. The lease record was created
at `2026-08-28T16:39:06.238Z`, recorded the Java process identity PID `18076`,
and last heartbeat `2026-08-28T16:39:10.608Z`. After Codex exit, PIDs 5608,
20908, and 18076 were absent. The observation did not capture a direct exit
code for the MCP executable or Java child, and the configured MCP trace file
was not present. Therefore process disappearance is not treated as proof of
MCP EOF or clean MCP exit.

Final lease file:

`C:\Users\Liparakis\AppData\Local\Synesis\workspaces\911cc3cd-3d2a-436b-9b8f-9ca1193420b0\admin\session-leases\syn041-real-codex-lease-20260828-002.json`

The final record retained `leaseState=ACTIVE`, provider `codex`, connection
`syn041-real-codex-lease-20260828-002`, session
`session-87275e3f-557d-4022-91f0-0579f9efacd6`, and Java PID `18076`.

## Doctor evidence

The final read-only command was `synesis doctor --project <fixture> --json`.
It returned:

- `doctorResult=DEGRADED`
- `findingsCount=3`, `critical=0`, `errors=0`, `warnings=3`
- `stale_session_lease:WARNING` — “Stale session lease detected”
- `command_namespace_reconciliation_required:WARNING`
- `command_capacity_or_retention:WARNING`
- `mutationsPerformed=0`

The stale finding is consistent with the final `ACTIVE` lease. Source review
confirms that a directly observed clean stdio EOF would return MCP exit 0 and
invoke `handler.close()`, while `markClosedCleanly` would persist
`CLOSED_CLEANLY`; those source semantics do not prove that this real Codex run
delivered EOF or that the child exited 0.

## Classification and disposition

Primary result: **RESULT D — inconclusive**.

The real provider completed the task, the WorkGroup reached `COMPLETED`, and
Codex exited 0. However, the required clean-close proof tuple is incomplete:
MCP EOF and MCP exit 0 were not directly measured. The observed `ACTIVE` lease
and Doctor stale warning therefore do not prove a lease defect (RESULT B),
and do not establish RESULT C either. No implementation change is justified.

SYN-041 remains `ACTIVE` because the acceptance condition requiring direct
MCP clean-close evidence was not met. SYN-039 remains `DONE / ACCEPTED` at
CP-0547; SYN-040 remains `DONE / VERIFIED`. No provider migration, cleanup,
architecture change, push, tag, or release was performed.
