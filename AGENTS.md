# Synesis Link Agent Contract

This repository contains only Synesis Link. Conversational context is temporary and untrusted; repository state and verification evidence are authoritative.

## Startup

Every execution begins with:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/agent-resume.ps1
```

Then read `docs/agent/CONTRACT.md`, `GOAL.md`, `STATE.md`, `TASKS.md`, `CURRENT.md`, `DECISIONS.md`, `FAILED_ATTEMPTS.md`, `TEST_MATRIX.md`, and `NEXT_SESSION.md`. Correct documentation that conflicts with code, Git state, tests, or command output before feature work.

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
