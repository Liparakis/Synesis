# SYN-051 provider-thread provenance and loadability investigation

Date: 2026-09-04
Scope: read-only investigation of the Codex provider-thread load failure.
Classification: **PASS-B — architecture simplification found**, with full
managed-runtime compatibility still unverified.

## Boundary and safety

This pass did not modify Synesis production source, managed lifecycle
semantics, `.synesis` state, credentials, historical evidence, or remote
repositories. It did not retry managed A1/A2, start Worker B, use proof
material, or run broad acceptance. The disposable provider probes used the
normal logged-in provider home but did not print, copy, or inspect credentials.

The Synesis checkout began at `3afce552a043b71bfa22067a22f17b15f3d448aa`, on
`master`, clean and 44 commits ahead of `origin`. The only repository changes
from this pass are this evidence record and the durable agent/ADR updates
listed below.

## Direct finding

The bootstrap process called `thread/start` and treated the returned ID as a
durable handoff identity. Codex 0.153.0 created a live in-memory thread and
returned its ID, but no first user turn occurred, no rollout-bearing history
was materialized, and no provider `threads` row was present. A fresh managed
successor correctly attempted `thread/resume` for that exact ID, but the
provider store had no rollout, so resume failed with `no rollout found for
thread id ...`. A fresh `thread/read` then mapped the same absence to
`thread not loaded: ...`.

This is not a Synesis ownership or state-root mismatch, and it is not a
missing provider `thread/load` ordering step. It is an invalid cross-process
handoff assumption: `thread/start` alone supplies a process-local live thread,
not a successor-loadable provider resource in the observed lifecycle.

## Provider source meaning

The authoritative 0.153.0 implementation is
[`thread_processor.rs`](https://raw.githubusercontent.com/openai/codex/rust-v0.153.0/codex-rs/app-server/src/request_processors/thread_processor.rs).
`read_thread_view` first looks for a live thread in the current
`ThreadManager`, then attempts persisted metadata/history. When neither is
available, it returns `-32600` with `thread not loaded: {thread_id}`. The
persisted read path treats both “no rollout found for thread id” and a missing
thread-store record as absence. Thus the message means “not live in this
process and not available in provider durable storage”; it does not mean “call
thread/read before thread/resume.”

The 0.153 cold-resume path is `initialize`, then
`thread/resume({threadId: exactId})`; optional `thread/read`,
`thread/turns/list`, or `thread/items/list` follows if inspection is needed.
There is no separate `thread/load` method in the observed 0.153 method
inventory. Resume reads the stored thread/rollout directly. The current
protocol documentation also describes resuming by exact `thread_id` as the
preferred persisted-thread mode:
[`ThreadResumeParams`](https://github.com/openai/codex/blob/main/codex-rs/app-server-protocol/src/protocol/v2/thread.rs).

`ThreadManager` is explicitly an in-memory owner of live `CodexThread`
objects. `thread/start` reserves/creates that live object; App Server starts
the creation task asynchronously. A completed turn or another provider
persistence operation is therefore required before relying on a cross-process
successor. No public App Server flush/persist endpoint was found in the
observed method inventory.

## Environment and provenance comparison

| Property | Bootstrap | Managed successor |
|---|---|---|
| Codex | `codex-cli 0.153.0` | `codex-cli 0.153.0` |
| Executable | Direct `codex app-server --stdio -c mcp_servers.synesis.enabled=false`; exact bootstrap PID/path was not retained | Root PID `4952`; package executable `C:\Users\Liparakis\.codex\packages\standalone\releases\0.153.0-x86_64-pc-windows-msvc\bin\codex.exe` |
| `CODEX_HOME` | Normal user-home fallback | Explicit `C:\Users\Liparakis\.codex` |
| `CODEX_SQLITE_HOME` | Unset | Unset; process/user/machine checks were empty |
| Effective provider home | `C:\Users\Liparakis\.codex` | `C:\Users\Liparakis\.codex` |
| CWD | Assigned managed worktree | Same assigned managed worktree |
| Provider state DB | `C:\Users\Liparakis\.codex\state_5.sqlite` | Same |
| Provider history DB | `C:\Users\Liparakis\.codex\thread_history_1.sqlite` | Same |
| Rollout root | `C:\Users\Liparakis\.codex\sessions\` | Same |
| Additional configuration | Synesis MCP disabled for bootstrap | Synesis MCP command/args, proof env, and connection-instance metadata |

The state roots and provider identity matched. The successor’s MCP/proof
overrides govern Synesis admission and transport; they do not select a
different Codex thread store and did not cause this failure.

## Historical managed failure

The affected managed binding was
`session-04c9cf84-c5e1-4438-86fb-9c956d56321b`, participant
`agt_b20c250c-6c97-359b-af7b-0beda8f49440`, WorkIntent
`7ee2c934-38b7-384f-9cbe-d65cfd8a8373`, claim
`probe-runtime/replacement-a.txt`, and provider thread
`01a069b4-1028-7472-8a77-21e847c51cc7`.

The generation-1 journal recorded initialize success, a remote-control status
event, then `thread/resume` failure. The exact result was
`codex_request_failed:thread/resume`, with provider response
`thread not loaded: 01a069b4-1028-7472-8a77-21e847c51cc7`.

Read-only provider-store checks found no row for that ID in
`state_5.sqlite.threads`, no turn/item rows in `thread_history_1.sqlite`, and
no provider rollout file. Occurrences in host-level logs or the current
investigation rollout are not provider thread-store records. No completed turn
or explicit flush/persistence operation occurred. The historical bootstrap
executable path and PID were not retained, so they are not claimed beyond the
known command and version.

## Disposable T1/T2 provider probes

The probes used a fresh temporary CWD, normal provider home, and Synesis MCP
disabled. They were provider-only and were not managed A1/A2 evidence.

### T1: start-only

Thread ID: `01a069c9-dfe0-7e40-bde8-60020c344edd`.

`initialize` and `thread/start` succeeded. `thread/read` succeeded while the
originating App Server remained alive, proving process-local live visibility.
In a same-process check, `thread/loaded/list` also succeeded, but
`thread/resume` already returned `no rollout found for thread id ...`.
After a clean provider process exit, a fresh App Server returned:

```text
thread/read:   thread not loaded: 01a069c9-dfe0-7e40-bde8-60020c344edd
thread/resume: no rollout found for thread id 01a069c9-dfe0-7e40-bde8-60020c344edd
```

Durable checks found zero `threads` row, zero turns, zero items, and no
rollout. Graceful-versus-abrupt termination was not separately compared; the
clean exit did not make this start-only thread persistent.

### T2: one completed turn

Thread ID: `01a069c9-fb91-7172-ba59-cb7f4a726343`.

`initialize`, `thread/start`, and a minimal `turn/start` completed
successfully. A fresh App Server then returned the exact thread from
`thread/read includeTurns=true` and accepted `thread/resume` with the same
ID. Durable checks found one provider `threads` row, one completed turn, two
history items, `history_mode=paginated`, and the rollout
`C:\Users\Liparakis\.codex\sessions\2026\09\04\rollout-2026-09-04T03-20-39-01a069c9-fb91-7172-ba59-cb7f4a726343.jsonl`.

This is direct evidence that the same 0.153.0 binary, normal home, state DB,
history DB, and rollout root support cold read/resume when the thread has
provider-persisted history. It is not evidence of managed authority, proof
admission, Worker A/B isolation, or process-tree acceptance.

## Version comparison

The official 0.145.0 source has the same relevant architecture: asynchronous
`thread/start`, live in-memory thread management, persisted-store lookup for
cold `thread/resume`, and the same missing-thread read failure branch. See the
[`0.145.0 thread processor`](https://raw.githubusercontent.com/openai/codex/rust-v0.145.0/codex-rs/app-server/src/request_processors/thread_processor.rs).
Repository evidence from the earlier 0.145 real-runtime acceptance records
successful exact-thread restart/resume after completed turns. No relevant
0.145-to-0.153 behavior delta was found. 0.153’s paginated-history support is
not the cause; the failing T1 has no persisted history at all.

## Architectural decision and bounded next slice

The current bootstrap handoff is not provider-correct for a cross-process
successor because it equates a live `thread/start` result with durable
loadability. A separate bootstrap process is unnecessary for generation 1 if
the managed App Server itself performs `thread/start` after the existing
quarantine/proof boundary, the broker acquires unique `(provider, thread)`
ownership from the returned exact ID, and managed activation remains proof- and
generation-gated.

The next bounded production slice should therefore let the managed App Server
create generation 1, broker-pin the returned thread, and require one persisted
turn (or an explicitly identified provider persistence operation) before any
successor/replacement acceptance. Preserve unique `(provider, thread)`
ownership, immutable broker-derived pinning, proof-gated admission, generation
fencing, and owned process-tree teardown. Do not weaken those controls or treat
the raw provider thread ID as Synesis authority.

The T2 probe proves that successor resume is feasible once provider persistence
exists. Full managed compatibility, controlled A1 root failure, A2, Worker B,
and full acceptance remain unverified.

## Files and verification

Documentation/evidence updates from this pass:

- `docs/evidence/SYN-051-provider-thread-provenance-2026-09-04.md`
- `docs/adr/0064-syn051-provider-thread-persistence-boundary.md`
- durable SYN-051 sections in `docs/agent/CURRENT.md`, `GOAL.md`, `STATE.md`,
  `TASKS.md`, `NEXT_SESSION.md`, `FAILED_ATTEMPTS.md`, `TEST_MATRIX.md`, and
  `SESSION_LOG.md`

No production source changed. The repository was checked for whitespace and
status after documentation updates; no push was performed.
