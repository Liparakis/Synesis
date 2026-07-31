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
