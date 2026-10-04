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
5. Inspect important callers, consumers, and neighboring implementations when they materially affect the behavior being changed.
6. Reconcile documentation with implementation before editing.

If summaries, old notes, or prior chat context disagree with the repository:

**current source + newest durable repository documents win.**

Do not assume old task descriptions still match the code.

---

## General engineering rules

### Work source-first

- Inspect before editing.
- Understand the affected responsibility before choosing the patch shape.
- Follow existing terminology and conventions.
- Reuse existing mechanisms when they already express the required behavior.
- Do not introduce parallel abstractions for concepts the codebase already models.
- Do not rename or restructure unrelated areas merely to make the current task easier.
- Prefer the smallest **coherent engineering change** that fully expresses and enforces the actual invariant.
- Diff size is not a quality metric.
- A broader local rewrite is preferable to a smaller patch when the smaller patch leaves the affected responsibility duplicated, implicit, brittle, misleading, unsafe, or difficult to test.

Existing source is evidence of current behavior. It is not proof that every local implementation decision is desirable or must be preserved.

### Reconstruct the contract before editing

For meaningful changes, determine what the affected component is actually responsible for before modifying it.

Identify:

- the externally observable behavior;
- the invariant being introduced, restored, or protected;
- the component or responsibility that owns that invariant;
- the inputs and trust boundaries involved;
- the authoritative state, where applicable;
- the important failure modes;
- lifecycle/resource ownership, where applicable;
- retry, replay, concurrency, and restart behavior, where applicable;
- the tests or evidence that currently define expected behavior.

Do not reduce the task to the failing line if the failing line is only a symptom of a poorly represented responsibility.

### Transform the affected responsibility, not merely the failing line

The goal is not the smallest textual diff. The goal is the smallest **coherent responsibility-level change**.

When a task exposes a responsibility that is poorly represented, duplicated, implicit, unsafe, difficult to validate, or difficult to test, agents may restructure that responsibility even when the immediate defect could be patched more narrowly.

Before choosing an implementation:

1. Identify the actual behavioral invariant being changed or restored.
2. Identify the component or responsibility that owns that invariant.
3. Inspect the complete affected component, its important callers, and its focused tests.
4. Determine whether the existing representation makes the invariant explicit and difficult to violate.
5. Look for adjacent defects or unsafe assumptions that arise from the same underlying responsibility.
6. Decide whether a narrow patch or a local structural transformation produces the more coherent result.

Prefer a local structural transformation when it makes the affected responsibility:

- more explicit;
- easier to reason about;
- harder to misuse;
- safer at boundary conditions;
- easier to test independently;
- less duplicated;
- more consistent internally;
- more consistent with repository terminology and architecture.

Examples of appropriate transformation include:

- replacing repeated special-case conditionals with an explicit representation of the underlying policy;
- extracting deterministic policy from orchestration code;
- centralizing behavior that must remain consistent across multiple paths;
- separating validation, mapping, policy selection, persistence, transport, resource access, or response construction when those are genuinely distinct responsibilities;
- introducing named constants, collections, or value representations when they correspond to real behavior or policy already present in the code;
- changing helper visibility when package-level access materially improves focused testability without unnecessarily expanding the public API;
- correcting nearby edge cases that are manifestations of the same missing or incorrectly represented invariant;
- using APIs according to their actual lifecycle and ownership semantics rather than preserving an accidental existing pattern;
- replacing several individually correct branches with one representation of the rule they collectively implement.

Do not preserve an implementation solely because it already works on the happy path.

Do not preserve accidental complexity merely to minimize the diff.

Do not force a rewrite when the existing representation is already coherent.

### Separate orchestration from policy

High-level methods should preferably communicate the flow of the operation.

When useful, isolate deterministic or independently meaningful policy such as:

- validation;
- route/path interpretation;
- authorization decisions;
- state transition eligibility;
- cache policy;
- content/type selection;
- retry policy;
- mapping;
- serialization rules;
- resource lookup;
- response/header construction;
- failure classification.

Do not extract helpers merely to shorten methods.

Extract behavior when giving it a name makes an actual rule, invariant, responsibility, or policy easier to understand and test.

A useful test is:

> Can a maintainer understand the operation from the orchestration method and inspect the named policies independently when more detail is needed?

### Prefer explicit invariants

Important behavior should not depend on maintainers mentally reconstructing a rule from scattered conditionals.

When several branches, constants, or call sites collectively express one policy:

- identify the underlying concept;
- represent it once where practical;
- name it using repository terminology;
- make violations difficult or obvious.

Prefer:

**one explicit representation of a policy**

over:

**several individually correct special cases that must remain synchronized manually.**

### Improve testability deliberately

When restructuring affected code, consider whether important deterministic behavior can be tested directly without exercising unrelated machinery.

Where appropriate:

- isolate pure policy;
- keep deterministic transformations separate from I/O;
- use package-private visibility rather than public exposure when focused tests need access;
- avoid requiring an entire server, process, network stack, filesystem, or state machine merely to verify a small deterministic rule.

Do not distort production architecture purely for tests.

Do not expose internals publicly solely to make testing easier.

### Look beyond the happy path

For meaningful changes, reason about applicable boundary and adversarial cases.

Depending on the responsibility, consider:

- malformed input;
- missing input;
- empty input;
- unexpected encodings or characters;
- traversal or namespace escape;
- unknown enum/type/version values;
- stale state;
- duplicate requests;
- retries;
- partial failure;
- interrupted work;
- resource absence;
- resource ownership and cleanup;
- invalid lifecycle order;
- cache staleness;
- unversioned versus versioned resources;
- integer/range boundaries;
- concurrent actors;
- replay after restart;
- corrupted or contradictory state.

Do not add hypothetical defenses with no relationship to the affected boundary.

Do investigate realistic failure modes implied by the component's actual inputs and responsibilities.

### Do not invent architecture

Do not create names, layers, services, events, states, ownership models, factories, managers, wrappers, or abstractions simply because they sound clean.

Before introducing a new concept, determine:

- whether the repository already has one;
- which existing code owns the behavior;
- which durable state represents it;
- how it is validated;
- how it fails;
- how it recovers;
- how it is tested.

If a new concept is genuinely required, derive it from the missing invariant and integrate it into existing architecture.

This rule forbids **speculative concepts**. It does not forbid extracting a concept that is already demonstrably present in repeated behavior, repeated policy, existing state, or an existing invariant.

Recognizing an existing concept and giving it one coherent representation is not architectural invention.

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

Scope is bounded primarily by **responsibility**, not by line count.

Do not turn a narrow defect into an unrelated subsystem or repository-wide refactor.

However, when requested work exposes multiple problems belonging to the same owning responsibility or the same missing invariant, fix that responsibility coherently rather than stacking another narrow special case onto it.

A larger local rewrite is acceptable when it produces a materially clearer, safer, more internally consistent, or more testable representation of the required behavior.

A change is not out of scope merely because it touches more lines than the minimal patch.

A change is out of scope when it alters a separate responsibility whose behavior is not necessary to understand, enforce, validate, or safely integrate the requested invariant.

Do not use a narrow task as justification for:

- repository-wide cleanup;
- speculative architecture;
- broad naming churn;
- unrelated modernization;
- aesthetic refactoring;
- dependency replacement unrelated to the invariant;
- formatting churn outside the affected area.

If unrelated failures or cleanup are discovered:

- record them;
- isolate them;
- do not silently fold them into the current change.

### Do not confuse restraint with passivity

Repository safety does not mean preserving weak local design forever.

Agents are expected to exercise engineering judgment.

When the existing implementation:

- duplicates the same rule in several places;
- encodes a policy only through incidental control flow;
- has an obvious edge-case defect within the affected responsibility;
- misuses API lifecycle or ownership semantics;
- makes deterministic behavior unnecessarily difficult to test;
- creates inconsistent behavior between equivalent paths;
- relies on a misleading abstraction;
- or obscures the invariant the task is intended to enforce;

fix the underlying local representation when doing so remains within the affected responsibility.

Do not intentionally leave a known defect in the same responsibility merely because the original request described only one manifestation of it.

---

## Engineering review pass

After implementing a meaningful change, review the result as though reviewing another engineer's pull request.

Ask:

- Is the important invariant explicit, or only implied by control flow?
- Is the same policy represented in more than one place?
- Did the implementation inherit an unsafe assumption merely because the old code had it?
- Are equivalent paths governed by the same rule?
- Are malformed, missing, stale, duplicate, boundary, or partial inputs handled correctly where relevant?
- Are lifecycle and resource ownership semantics correct?
- Can failure leave partially applied or contradictory state?
- Could orchestration code be simpler if deterministic policy were isolated?
- Can important deterministic behavior be tested without exercising unrelated machinery?
- Did the change introduce a new abstraction that the repository does not actually need?
- Is an abstraction merely hiding complexity rather than expressing a real concept?
- Did the transformation remain within the owning responsibility?
- Did the implementation create needless compatibility behavior because the old code happened to behave that way?
- Are names describing actual domain concepts and policies?
- Would a maintainer encountering this code six months later understand why the important decisions exist?

Fix issues found during this review before considering the implementation complete.

Do not stop at:

**the requested test now passes.**

A passing test is evidence, not proof that the representation is good.

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
2. focused tests for deterministic policy or edge cases exposed by the change;
3. focused integration tests;
4. relevant module suite;
5. broader workspace/MCP/coordination suites where applicable;
6. architecture/static/Javadocs/format/catalog/validator gates;
7. real disposable acceptance only after source-level behavior is credible.

For regressions, prefer proving both:

- the originally observed failure or missing invariant;
- representative boundary cases belonging to the same responsibility.

Do not add tests solely to mirror implementation structure.

Prefer tests that describe externally meaningful behavior, invariants, policy, and failure semantics.

Do not call a test or suite PASS if it was:

- interrupted;
- timed out;
- skipped unexpectedly;
- hung;
- never run.

Report those states honestly.

Do not paper over existing unrelated failures to make a milestone appear green.

Do not weaken implementation semantics merely to preserve a test whose expectation represents accidental behavior rather than an intentional contract.

When an existing test conflicts with a newly clarified invariant:

1. determine whether the test represents intentional compatibility behavior;
2. inspect repository evidence and callers;
3. update the test only when the source of truth supports the new behavior;
4. report the semantic change explicitly.

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
- whether the implementation was patched narrowly or structurally transformed, and why;
- what authority/security properties remain intact;
- how malformed/boundary input behaves where relevant;
- how resource ownership/lifecycle behaves where relevant;
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

Do not describe a refactor as behavior-preserving if it intentionally changes an edge case, policy, lifecycle rule, caching behavior, failure mode, or externally observable contract.

Make those semantic improvements explicit.

---

## Code quality

Prefer code whose structure communicates the behavior.

### Names

Use names that describe:

- the domain concept;
- the invariant;
- the policy;
- the lifecycle state;
- the authority source;
- the operation's actual responsibility.

Avoid names whose only purpose is to make newly extracted code appear abstract.

### Constants and collections

Extract literals when they represent:

- policy;
- protocol values;
- repeated domain concepts;
- security rules;
- lifecycle rules;
- externally meaningful configuration.

Do not extract every literal mechanically.

A named constant should communicate something the literal alone does not.

### Helpers

A helper should preferably correspond to a meaningful operation or policy.

Good reasons to extract include:

- removing duplicated policy;
- making an invariant independently understandable;
- making deterministic behavior testable;
- separating I/O from decision logic;
- clarifying orchestration;
- centralizing failure behavior.

Poor reasons include:

- arbitrary line-count reduction;
- creating abstraction for appearance;
- wrapping a single obvious call without adding meaning.

### Control flow

Prefer control flow that makes failure and success paths obvious.

For externally supplied or authority-sensitive input:

- validate early;
- fail closed;
- avoid implicit fallback unless fallback is explicitly part of the contract.

Prefer early rejection when it makes the valid path simpler.

### Resource ownership

Use APIs according to their actual ownership contracts.

When an object represents a closable resource or lifecycle boundary, determine who owns it and ensure cleanup is deterministic.

Do not rely on incidental process cleanup when the API exposes explicit ownership.

Do not introduce closing behavior until ownership is understood; closing a borrowed resource can be as incorrect as leaking an owned one.

---

## Documentation

Update durable docs when behavior or architecture materially changes.

Do not create documentation that merely restates implementation noise.

Use existing repository conventions for planning, ADRs, evidence, checkpoints, and test matrices.

Do not invent a new document type when an existing one already serves the purpose.

### Source documentation

Document **why**, not merely **what**.

Useful documentation includes:

- invariants;
- security assumptions;
- authority rules;
- lifecycle and ownership rules;
- surprising API behavior;
- failure and recovery semantics;
- protocol assumptions;
- non-obvious edge cases;
- caching/versioning assumptions;
- concurrency constraints;
- reasons a tempting alternative is intentionally not used.

Avoid comments or Javadocs that merely:

- restate a method name;
- narrate obvious syntax;
- explain trivial getters/setters;
- describe obvious constants;
- repeat type information already present in the signature;
- inflate the apparent completeness of the implementation.

Public and package-visible contracts should be documented when their behavior, constraints, or role is not self-evident.

Private implementation details should be documented when the reasoning would otherwise be lost.

Documentation volume is not a quality metric.

---

## Implementation quality bar

For meaningful production changes, optimize for:

1. correctness;
2. preservation of authority and safety properties;
3. coherent responsibility boundaries;
4. explicit invariants;
5. realistic boundary and failure handling;
6. maintainability;
7. testability;
8. evidence;
9. bounded scope.

Do **not** optimize for minimum diff size as an independent goal.

Prefer:

- semantic improvement over cosmetic refactoring;
- explicit policy over duplicated special cases;
- coherent local design over incremental patch accumulation;
- meaningful tests over implementation-mirroring tests;
- repository concepts over invented abstractions;
- fail-closed behavior over ambiguous fallback;
- clear ownership over accidental lifecycle behavior.

A small patch is excellent when the component is already well modeled.

A larger local transformation is excellent when that is what is required to make the affected responsibility coherent.

Neither is inherently preferable based on line count.

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
- missing or violated invariant;
- behavioral change;
- structural transformation performed, if any, and why it remained within the owning responsibility;
- relevant authority/replay/concurrency implications;
- relevant boundary/failure/lifecycle implications;
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