## Purpose

This file defines **how agents work in this repository**.

It is not a task plan, architecture specification, roadmap, or source of truth for current implementation details.

Do not invent component hierarchies, domain names, state machines, IDs, or abstractions from this file. Discover the repository's actual terminology and structure from the current source and durable project documents.

Current goals and active work belong in the repository's planning/evidence documents, not hard-coded here.

---

## Synesis source repository — HARD RULE

**Do not use Synesis to manage the Synesis source repository itself.**

For the Synesis source checkout:

- Do not run `synesis init`.
- Do not use Synesis MCP for source reads, writes, commands, claims, lifecycle, or coordination.
- Do not create `.synesis` in the source checkout.
- Use normal filesystem, shell, IDE, Git, and human authority.
- Preserve unrelated modified and untracked work.
- Do not reset, clean, discard, overwrite, or normalize unrelated changes.
- Do not push unless explicitly authorized.
- Do not commit unless explicitly authorized by the current task/session.

The source checkout should remain free of `.synesis`.

Synesis may be used normally in **external disposable acceptance projects** created to test the product.

---

## Required skills

When applicable to repository work, use:

- `graphify`
- `constaint-driver-architecture`
- `ponytail`

Use the skills as working methods, not as excuses to impose abstractions the repository does not already support.

If a skill is unavailable in the current harness, say so and continue using the repository's existing conventions rather than inventing a substitute process.

---

## Start-of-session workflow

Before making changes:

1. Inspect the current repository state.
2. Read the current operating/project documents if present.
3. Read the latest relevant evidence, checkpoints, ADRs, and test-matrix material.
4. Inspect the actual source and tests involved.
5. Reconcile documentation with implementation before editing.

If summaries, old notes, or prior chat context disagree with the repository:

**current source + newest durable repository documents win.**

Do not assume old task descriptions still match the code.

---

## General engineering rules

### Work source-first

- Inspect before editing.
- Follow existing terminology and conventions.
- Reuse existing mechanisms when they already express the required behavior.
- Do not introduce parallel abstractions for concepts the codebase already models.
- Do not rename or restructure large areas merely to make the current task easier.
- Prefer the smallest correct change that satisfies the actual invariant.

### Do not invent architecture

Do not create names, layers, services, events, states, or ownership models simply because they sound clean.

Before introducing a new concept, determine:

- whether the repository already has one;
- which existing code owns the behavior;
- which durable state represents it;
- how it is validated;
- how it fails;
- how it recovers;
- how it is tested.

If a new concept is genuinely required, derive it from the missing invariant and integrate it into existing architecture.

### Preserve authority boundaries

For any security-, lifecycle-, coordination-, identity-, or ownership-sensitive change:

- identify the current authority source;
- identify what proves authority;
- identify what invalidates it;
- avoid implicit fallback;
- avoid "latest known" guessing;
- avoid treating correlation metadata as authority;
- fail closed when authoritative state is ambiguous.

Never weaken an authority check simply to make a test or acceptance flow pass.

### Preserve durable history

When work changes owner, state, reviewer, lifecycle, or recovery status:

- preserve historical lineage where the current architecture expects it;
- do not rewrite history to make the new state look original;
- make replay/restart behavior deterministic;
- make stale actions fail safely.

### Concurrency and replay

For mutating coordination/state-machine work, reason about:

- duplicate requests;
- retries after lost responses;
- stale actions;
- concurrent actors;
- process interruption;
- restart/replay;
- partial progress;
- idempotency;
- race fencing.

Do not claim crash safety without testing the actual durable boundaries being claimed.

### Keep scope bounded

Do not turn a narrow defect into a broad refactor unless the source proves the defect is architectural.

If unrelated failures or cleanup are discovered:

- record them;
- isolate them;
- do not silently fold them into the current change.

---

## Repository hygiene

- Preserve unrelated user work.
- Do not delete untracked files merely because they are unfamiliar.
- Do not rewrite evidence/history files unless the task actually requires it.
- Do not make formatting-only churn across unrelated files.
- Do not silently normalize line endings across the repository.
- Do not bypass generated-file or checked-in-artifact conventions.
- Do not edit `.synesis` manually in disposable targets.
- Do not create `.synesis` in the Synesis source repository at all.

---

## Testing workflow

Work from narrow to broad.

Typical order:

1. focused unit tests for the changed behavior;
2. focused integration tests;
3. relevant module suite;
4. broader workspace/MCP/coordination suites where applicable;
5. architecture/static/Javadocs/format/catalog/validator gates;
6. real disposable acceptance only after source-level behavior is credible.

Do not call a test or suite PASS if it was:

- interrupted;
- timed out;
- skipped unexpectedly;
- hung;
- never run.

Report those states honestly.

Do not paper over existing unrelated failures to make a milestone appear green.

---

## Test subagent model — HARD RULE

Any subagent spawned to implement, run, debug, investigate, or review tests must be explicitly launched with:

```text
model: gpt-6-luna
reasoning effort: low | medium | high
```

Never rely on inherited or default model settings. Prefer `low` effort unless the task genuinely requires more.

Immediately after launch and before substantive work, perform a bounded check for authoritative effective session metadata.

### Effective metadata available

When the harness exposes authoritative effective session metadata:

1. Verify the effective model and reasoning effort immediately after launch and before substantive work.
2. Continue only when the model is `gpt-6-luna` and the effort is `low`, `medium`, or `high`.
3. If either exposed value does not match, terminate the subagent, discard its work, and reassign the same task using the required model and an allowed effort.

### Effective metadata unavailable

When the harness does not expose one or both effective values after a bounded check:

1. The spawn or launch call must still explicitly specify `gpt-6-luna` and an allowed effort. Inherited or default model selection remains forbidden.
2. Record the metadata limitation, exact requested model and effort, and spawned agent or session identifier before substantive work.
3. Allow the subagent to proceed under that explicitly pinned launch configuration.
4. Do not claim that effective runtime settings were verified; report: `launch configuration explicitly pinned; effective runtime metadata unavailable in this harness.`
5. If later evidence reveals that the actual model or effort differs from the requested values, terminate the subagent, discard its work, and replace it.

This rule applies to any subagent that:

- writes tests;
- runs tests;
- debugs test failures;
- investigates test behavior;
- reviews test results.

---

## Validation and evidence

For any meaningful change, be able to state:

- what failed before;
- what invariant was missing or violated;
- what source behavior changed;
- why the change belongs where it was made;
- what authority/security properties remain intact;
- how retries, races, stale state, and restart behave where relevant;
- what tests prove;
- what tests do not prove.

Distinguish clearly between:

```text
source semantics proven
```

and:

```text
real current-build acceptance proven
```

Do not promote deterministic/unit evidence into a claim about an untested real environment.

---

## Documentation

Update durable docs when behavior or architecture materially changes.

Do not create documentation that merely restates implementation noise.

Use existing repository conventions for planning, ADRs, evidence, checkpoints, and test matrices.

Do not invent a new document type when an existing one already serves the purpose.

---

# Disposable acceptance targets

The rules below apply only to **external disposable projects used to acceptance-test Synesis**.

They do not authorize Synesis use inside the Synesis source repository.

## Disposable acceptance target

Use the project's normal Synesis MCP integration for all project reads,
mutations, and lifecycle actions. Do not edit `.synesis` manually.

### Synesis usage

- Use Synesis tools for project reads, file changes, and commands.
- One persistent MCP connection owns one provider binding and one isolated worker context.
- After `synesis init` succeeds, install the integration matching the responding harness before starting work:
  - Claude Code: `synesis provider install claude`
  - Codex: `synesis provider install codex`
- The matching provider executable must already exist for that harness. Installing one provider is not a substitute for another.
- After `synesis.ensure_session` succeeds, keep the provider in the current project directory and use Synesis MCP for reads, writes, and commands.
- Synesis performs those operations in the assigned worktree.
- Do not switch branches, change into another worker's worktree, or edit another worker's files manually.
- Native provider hooks are optional and may be unavailable. They do not replace Synesis MCP.
- If a native mutation causes `workspace_mismatch`, stop native mutation and verify state through `synesis.read_file`.
- Reads carry revisions. Use the matching revision when applying a patch.
- Do not directly modify the control checkout.
- On identity, ownership, freshness, revision, or workspace failure: stop mutation and inspect read-only state.
- The MCP surface contains exactly 10 tools.
- Follow `get_next_action`'s projected tool and typed arguments.
- A ready session is not yet an announced collaboration intent.
- Before mutating visible task files, call `ensure_session` with `task.goal`, `task.acceptance`, and `task.claims`.
- Claims must contain the exact repository-relative files/subtrees assigned to that responsibility.
- Use `path_exact` or `path_subtree`.
- Keep claims disjoint.
- Do not guess or overlap ownership.
- `likelyScopes` is descriptive only and does not acquire ownership.
- There is no separate announce tool; task/claim declaration is the intent announcement.
- Call `get_next_action` with no arguments to read the durable coordination inbox.
- `integrationCheck` evaluates supplied compatibility facts only. It does not advance lifecycle state and must not replace normal inbox polling.
- If a mutating tool returns a concrete `nextAction` / `recommendedTool` with typed arguments, execute that exact continuation before choosing another lifecycle action.
- After any completed Synesis mutation, call `get_next_action` again.
- If `get_next_action` reports `IMPLEMENT` without a concrete lifecycle action, continue visible assigned work normally.
- Do not call `finish_lane` merely because coding appears complete.
- Execute lifecycle actions only when Synesis projects the exact tool and arguments.
- If visible work is complete or blocked while the WorkGroup/other participants remain active, perform bounded waits and continue polling.
- If `WAIT` projects `get_next_action` with `{}`, wait a bounded interval and execute that continuation.
- Remain engaged while grants, snapshots, review decisions, coordination requests, or WorkGroup completion remain unresolved.
- Do not report project success merely because one lane is complete.

---

## Blind multi-agent acceptance

When testing autonomous coordination:

- do not manually assign agent roles;
- do not manually assign files unless the protocol itself establishes that assignment/claim;
- do not tell a replacement which participant died;
- do not relay another agent's conversation;
- do not manually select continuation/review candidates on behalf of an agent;
- do not manually repair `.synesis`;
- do not bypass claims, reviewer intent, grants, review decisions, or integration gates.

The bootstrapper may:

- initialize the disposable project;
- establish authoritative durable project policy/goal;
- launch agents;
- inject process failure;
- observe;
- independently validate the final integrated result.

The bootstrapper is an experiment controller, not the coordination controller.

---

## Acceptance-agent behavior

A blind agent should determine its work through the repository and Synesis.

Do not make acceptance easier by adding hidden task knowledge to the prompt.

A replacement agent should receive no special explanation of:

- who died;
- what lane was abandoned;
- what candidate to continue/review;
- what action ID to invoke.

If the product cannot expose enough durable state for the blind agent to proceed lawfully, record that as product evidence.

---

## Acceptance success claims

A real acceptance claim must identify exactly what was demonstrated.

Examples include:

- admission;
- blind discovery;
- concurrent claims;
- participant loss;
- reconciliation;
- continuation;
- reviewer acquisition;
- review decision;
- integration;
- WorkGroup completion;
- durable goal completion.

Do not collapse `PARTIAL`, `NOT REACHED`, or `TIME BOUND` into PASS.

---

## Final reporting

At the end of substantial work, report:

- starting repository state;
- files/components changed;
- root cause;
- behavioral change;
- relevant authority/replay/concurrency implications;
- focused test results;
- broad suite results;
- real acceptance evidence;
- honest limitations;
- final repository state;
- `.synesis` status in the Synesis source repository;
- commits created, if any;
- push status.

Use the repository's actual terminology in the report.

Do not invent names to make the report sound more architectural.
