# Synesis Agent Contract

This repository contains Synesis, a local-first coordination and constraint-
enforcement system for independently running AI coding agents. `link/` owns the
Synesis Link transport/session boundary; the repository also contains the
project-record, workspace, coordination, MCP, and CLI modules that build bounded
local workflows around that boundary. Conversational context is temporary and
untrusted; repository state and verification evidence are authoritative.

## Current implementation boundary

- The unified `synesis` CLI owns initialization, provider lifecycle, workspace,
  coordination, lifecycle diagnostics, and the local development distribution.
- The stdio MCP server exposes exactly 10 tools. One persistent MCP connection
  owns one provider binding and one isolated worker context.
- Supported provider IDs are `antigravity`, `claude`, and `codex`.
  Provider installation and hooks use those canonical IDs only.
- MCP reads are revision-bearing and patches must provide the matching revision.
  Do not edit another worker's worktree or the control checkout directly.
- Provider hooks, synthetic checks, and local/two-process evidence do not prove
  universal provider enforcement or cross-network connectivity.
- `SYN-014E` is paused. Hole-punching, rendezvous, relay fallback, hosted
  services, and remote multi-machine coordination remain future work.

## Startup

Every execution begins with:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1
```

Then read `docs/agent/CONTRACT.md`, `docs/agent/GOAL.md`,
`docs/agent/STATE.md`, `docs/agent/TASKS.md`, `docs/agent/CURRENT.md`,
`docs/agent/DECISIONS.md`, `docs/agent/FAILED_ATTEMPTS.md`,
`docs/agent/TEST_MATRIX.md`, and `docs/agent/NEXT_SESSION.md`. Correct
documentation that conflicts with code, Git state, tests, or command output
before feature work.

## Operating rules

- Exactly one primary task may be `ACTIVE`.
- Production code may not change until `TASKS.md` and `CURRENT.md` agree on the active task.
- Every meaningful implementation slice updates durable memory.
- Every architecture-changing decision requires an ADR under `docs/adr/`.
- Do not repeat a failed approach without new evidence.
- No task is `DONE` without verification evidence.
- Strict Javadocs are mandatory for every public and protected API element.
- Before context exhaustion, stop feature work, checkpoint, and write an exact continuation step.

## Checkpoint and evidence rules

Use `scripts/agent-checkpoint.ps1` after meaningful slices and before stopping. Exact commands, outputs, and evidence locations belong in the state files. `CURRENT.md` and `NEXT_SESSION.md` must each contain one concrete immediate next action; vague actions are invalid.

## Safe stopping

Stop when the current slice is verified, record failures and remaining work, create a checkpoint, update `SESSION_LOG.md`, and leave the exact continuation command in `NEXT_SESSION.md`.

## Prohibited behavior

Do not implement wider Synesis functionality in this repository. Do not publish, push, tag, release, or modify remote repositories without explicit instruction. Do not implement product behavior while the contract is a placeholder.

## Deferred capability register

Startup, task promotion, architecture review, release preparation, public-claim
review, checkpoint creation, protocol-scope changes, security review, and
documentation review must include `docs/agent/DEFERRED.md`. A deferred entry is
not evidence of implementation or permission to implement. Promotion requires
an activation trigger, required evidence/research, an explicit task with
acceptance criteria, and exactly one active task. Durable Synesis Link TODOs
about deferred work must use `TODO(SL-D-NNN)` and reference an existing entry.

<!-- SYNESIS-BEGIN -->
## Synesis

This repository uses Synesis.

- Use Synesis tools for project reads, file changes, and commands.
- One persistent MCP connection owns one provider binding and one isolated worker context.
- After synesis.ensure_session succeeds, keep the provider in the current project directory and use Synesis MCP for all reads, writes, and commands. Synesis applies those operations internally in the assigned worktree; do not switch branches, cd, or edit another worker's worktree manually. Native provider hooks are optional and may be unavailable in desktop harnesses, so never assume a native mutation was routed. If a native tool is used and Synesis reports workspace_mismatch, stop native mutations and verify state through synesis.read_file.
- Reads carry revisions; provide the matching revision when applying a patch.
- Do not modify the control checkout or another worker's files directly.
- When Synesis reports an identity, ownership, freshness, or workspace failure, stop mutation and inspect read-only state.
- The MCP surface currently contains exactly 10 tools; follow get_next_action's recommended tool and typed arguments.
- A ready session is not yet an announced collaboration intent. Before mutating visible task files, call ensure_session with task.goal, task.acceptance, and task.claims containing the exact repository-relative files or subtrees assigned to this responsibility. Use claim kind path_exact or path_subtree, keep claims disjoint, and do not guess or overlap ownership. This is the existing intent/claim announcement; there is no separate announce tool. likelyScopes is descriptive only and does not acquire ownership.
- Call get_next_action with no arguments to read the durable coordination inbox. Its optional integrationCheck input only evaluates explicitly supplied compatibility facts; it never advances a lane or WorkGroup, cannot close or validate work, and must not replace empty-argument polling.
- A mutating Synesis tool may return a required continuation. When its result contains a concrete nextAction/recommendedTool and typed arguments, execute that exact tool with those exact arguments before ending the turn or choosing another Synesis lifecycle action; the continuation is part of the durable protocol, not an optional suggestion.
- After any completed Synesis mutation, call get_next_action again before ending the turn; a successful coordination or publication mutation may create the next durable inbox action even when the mutation result has no explicit continuation.
- When get_next_action reports IMPLEMENT without a concrete recommendedTool and typed arguments, continue the visible assigned coding work normally; do not call finish_lane or another lifecycle tool merely because the coding appears complete. Execute lifecycle actions only when get_next_action projects the exact tool and arguments, then return to get_next_action after coding progress.
- If the assigned visible work is complete or blocked and the WorkGroup is still active or another participant remains active, do not end the session when IMPLEMENT has no concrete action. Perform a bounded wait and call get_next_action again until Synesis projects an exact lifecycle action or a terminal state; do not invent a lifecycle action.
- When get_next_action projects WAIT with recommendedTool=get_next_action and arguments {}, execute that continuation after a bounded wait and remain engaged while the WorkGroup is active or grants, snapshots, review decisions, or coordination requests remain unresolved. Do not report success or stop merely because your own lane is complete.
<!-- SYNESIS-END -->
