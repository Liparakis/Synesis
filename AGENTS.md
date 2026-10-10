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

## Skills

Use these skills when they apply to the work. Names below are the Claude Code names. Other harnesses may expose the same skills under different names or prefixes, so use the equivalent.

| Skill | Use it for |
| --- | --- |
| `graphify` | First stop for codebase questions when `graphify-out/graph.json` exists: `graphify query`, `graphify path`, `graphify explain`. Run `graphify update .` after code changes. `graphify-out/` is generated and gitignored. Never hand-edit it or commit it. |
| `constraint-driven-architecture` | New capabilities, architecture decisions, ADRs, and repository-level architecture review. Skip it for ordinary fixes and small changes. |
| `ponytail:ponytail` | Default coding discipline: solve the task with the least code and the fewest new concepts. Reuse before writing, delete before adding. |
| `pstack:poteto-mode` and the pstack skills it routes to | Workflow and principles: `pstack:architect` for shape-before-code on non-trivial work, `pstack:blast-radius` and `pstack:interrogate` before risky changes, `pstack:tdd`, `pstack:fix-ci`, `pstack:deslop` and `pstack:unslop` for cleanup. |
| `pstack:thermo-nuclear-code-quality-review` | Strict maintainability and abstraction review of a substantial change before calling it done. Run it on any change that adds or reshapes an abstraction, a module boundary, or a public API. |

Use skills as working methods, not as excuses to impose abstractions the repository does not already support.

If a skill is unavailable in the current harness, say so and continue using the repository's existing conventions rather than inventing a substitute process.

These skills govern work on the Synesis source repository. A blind acceptance agent receives only the bootstrapper's prompt, and skills must not be used to hand it hidden task knowledge.

### Skill precedence

This file wins over any skill, hook, or routing mandate, and the hard rules have no exceptions. When a skill's default behavior conflicts with a rule here, follow the rule here and say which skill step you skipped. The conflicts that actually arise:

- **Commits, pushes, pull requests.** Skills that ship, babysit, or fix CI (for example `pstack:babysit`, `pstack:fix-ci`, `pstack:make-pr-easy-to-review`) must not commit, push, open or edit a pull request, or post comments unless the current task explicitly authorizes it.
- **Scratch artifacts.** Audit trails, todolists, review reports, benchmark output, and similar scratch files that a skill wants to write belong outside the checkout, in the operating system's temporary directory. If one must live inside the checkout, leave it untracked, never commit it, and report it. Do not edit `.gitignore` for this.
- **Minimal change.** `ponytail` and `pstack:principle-subtract-before-you-add` mean the least code and the fewest concepts. They do not override "Change shape" below.
- **Comments.** `pstack:no-comments`, `pstack:deslop`, and `pstack:unslop` remove noise comments only. Keep comments that record why, and keep the Javadocs the documentation gates require.
- **Redesign.** `pstack:principle-redesign-from-first-principles` and thermo-nuclear "code judo" restructuring stay inside the owning responsibility and must preserve published contracts (see Public contract stability).
- **Subagent models.** Test subagents follow "Test subagent model", not pstack's model sheet or its default effort. Other pstack subagents follow the sheet.

---

## Design principles

The code should achieve four things. The skills carry the general technique. This section states the targets and the parts specific to this repository.

- **Good abstractions.** An abstraction names a concept the repository already shows in repeated behavior, repeated policy, durable state, or an existing invariant. It hides a decision behind a small interface and has one reason to change. Recognizing an existing concept and giving it one representation is not invention. Speculative layers, managers, factories, and wrappers are.
- **Clean patterns.** Solve a problem the way the neighboring code already solves it, and the same way in every equivalent path. One explicit representation of a policy beats several special cases kept in sync by hand.
- **Understandable code.** A maintainer can tell locally what the code does, why it is that way, and who owns the state it touches. Orchestration reads top to bottom over named policy, and names come from the domain.
- **Modular API, stable contracts.** Each module owns one responsibility and exposes the smallest surface it needs. Default to package-private. Keep the contract separate from the implementation, and put extension points only where variation already exists.

### Public contract stability

Published contracts are the public Java API of a module, the MCP and CLI surface, wire formats, and anything persisted in durable project state. Do not break them as a side effect of other work.

- Evolve additively: add new methods, optional fields, new types, or new versions rather than changing the meaning of an existing one.
- Readers tolerate unknown fields and values where the format allows it. Writers keep emitting what existing readers understand.
- When a replacement is needed, add it beside the old form, migrate every caller in the repository, deprecate the old form with a stated replacement, and remove it in a separate, explicitly authorized change.
- When a break looks unavoidable, first look for an adapter or a versioned form. A breaking change to a published contract needs an ADR and explicit human approval.
- Internal, package-private, and test-only code is not a published contract. Change it freely when every caller is migrated in the same change.
- Telling an accidental behavior from a promised one is part of the work. Inspect callers, docs, and tests before treating either as fixed.

### Priority order

When goals conflict, earlier wins:

1. correctness;
2. authority and safety properties;
3. stability of published contracts;
4. coherent responsibility boundaries and explicit invariants;
5. realistic boundary and failure handling;
6. understandable, testable code;
7. evidence;
8. bounded scope.

---

## Start-of-session workflow

Before making changes:

1. Inspect the current repository state.
2. Read the current operating/project documents if present.
3. Read the latest relevant evidence, checkpoints, ADRs, and test-matrix material.
4. Inspect the actual source and tests involved, and the callers and neighboring implementations that affect the behavior being changed.
5. Reconcile documentation with implementation before editing.

If summaries, old notes, or prior chat context disagree with the repository, **current source + newest durable repository documents win.**

---

## Engineering rules

Only what the skills do not already say, or what this repository needs stated its own way.

### Change shape

- Make the smallest **coherent responsibility-level change** that fully expresses and enforces the invariant. Diff size is not a quality metric in either direction.
- For a meaningful change, first identify the invariant, the component that owns it, the externally observable behavior, the trust boundaries and authoritative state involved, the failure modes, and the tests or evidence that define expected behavior.
- When the task exposes a responsibility that is duplicated, implicit, unsafe, or hard to test, restructure that responsibility instead of stacking another special case on it, and fix adjacent defects that come from the same missing invariant. Do not rewrite a representation that is already coherent.
- Existing source is evidence of current behavior. It is not proof that the behavior is intended.
- A change is out of scope when it alters a separate responsibility that is not needed to enforce, validate, or safely integrate the invariant. That rules out repository-wide cleanup, naming churn, unrelated modernization, dependency replacement, and formatting churn. Record unrelated failures and cleanup. Do not fold them into the change.

### Authority boundaries

For any security-, lifecycle-, coordination-, identity-, or ownership-sensitive change:

- identify the current authority source, what proves authority, and what invalidates it;
- avoid implicit fallback and "latest known" guessing;
- do not treat correlation metadata as authority;
- fail closed when authoritative state is ambiguous.

Never weaken an authority check to make a test or acceptance flow pass.

### Durable history

When work changes owner, state, reviewer, lifecycle, or recovery status:

- preserve historical lineage where the current architecture expects it;
- do not rewrite history to make the new state look original;
- make replay and restart behavior deterministic;
- make stale actions fail safely.

### Failure, replay, and concurrency

For meaningful changes, reason about the cases the component's real inputs and responsibilities imply:

- malformed, missing, empty, or unexpectedly encoded input, including traversal and namespace escape;
- unknown enum, type, or version values, and unversioned versus versioned resources;
- stale state, duplicate requests, retries after lost responses, and idempotency;
- partial failure, interrupted work, and corrupted or contradictory state;
- concurrent actors and race fencing;
- restart and replay.

Do not add defenses with no relationship to the affected boundary. Do not claim crash safety without testing the durable boundaries being claimed.

### Resource ownership

When an object is a closable resource or a lifecycle boundary, determine who owns it before changing how it is closed. Cleanup of an owned resource is deterministic. Closing a borrowed resource is as wrong as leaking an owned one.

---

## Review pass

After a meaningful change, review it as though it were another engineer's pull request. For a substantial change, run `pstack:thermo-nuclear-code-quality-review`, act on the findings that belong to the owning responsibility, and record the rest.

The skills cover abstraction and readability review. Also check what they do not:

- Are authority checks intact, and does ambiguous state still fail closed?
- Can failure leave partially applied or contradictory state?
- Are equivalent paths governed by the same rule?
- Does the change break or reinterpret a published contract? Accidental behavior may change. A published contract may not.
- Did the change stay within the owning responsibility?

A passing test is evidence, not proof that the representation is good.

---

## Repository hygiene

In addition to the hard rule above:

- Do not rewrite evidence or history files unless the task requires it.
- Do not make formatting-only churn or normalize line endings across unrelated files.
- Do not bypass generated-file or checked-in-artifact conventions.
- Do not edit `.synesis` manually in disposable targets.

---

## Testing workflow

Work from narrow to broad:

1. focused unit tests for the changed behavior and the edge cases it exposes;
2. focused integration tests;
3. relevant module suite;
4. broader workspace/MCP/coordination suites where applicable;
5. architecture/static/Javadocs/format/catalog/validator gates;
6. real disposable acceptance only after source-level behavior is credible.

For a regression, prove both the originally observed failure and representative boundary cases of the same responsibility.

Do not call a test or suite PASS if it was interrupted, timed out, skipped unexpectedly, hung, or never run. Report those states as they are. Do not paper over unrelated failures to make a milestone look green.

When an existing test conflicts with a newly clarified invariant:

1. determine whether the test represents intentional compatibility behavior;
2. inspect repository evidence and callers;
3. update the test only when the source of truth supports the new behavior;
4. report the semantic change explicitly.

Do not weaken implementation semantics to preserve a test that encodes accidental behavior.

### Evidence claims

Distinguish `source semantics proven` from `real current-build acceptance proven`. Do not promote deterministic or unit evidence into a claim about an untested real environment.

Do not describe a refactor as behavior-preserving if it intentionally changes an edge case, policy, lifecycle rule, caching behavior, failure mode, or externally observable contract. State the semantic change.

---

## Test subagent model — HARD RULE

Any subagent spawned to write, run, debug, investigate, or review tests must be explicitly launched with exactly one of these configurations:

```text
model: Sonnet 5.5 (claude-sonnet-5-5)    reasoning effort: medium
model: GPT 6 Luna (gpt-6-luna)           reasoning effort: medium
```

Use the one native to the harness: Sonnet 5.5 in Claude Code, GPT 6 Luna in Codex. If both are available, either is allowed. Prefer the one the task names. No other model and no other effort. Never rely on inherited or default settings.

In Claude Code, launch with the `Agent` tool, `model: sonnet`, and `subagent_type: pstack:effort-medium`, so the effort is set by the agent rather than inherited. The `sonnet` alias follows the current Sonnet, so confirm it resolves to Sonnet 5.5.

Immediately after launch and before substantive work, make a bounded check for authoritative effective session metadata.

- **Metadata available.** Continue only when the effective model and effort match an allowed configuration. On a mismatch, terminate the subagent, discard its work, and reassign the task with an allowed configuration.
- **Metadata unavailable.** Record the limitation, the exact requested model and effort, and the agent or session identifier, then let the subagent proceed under its pinned launch configuration. Do not claim the runtime settings were verified. Report: `launch configuration explicitly pinned; effective runtime metadata unavailable in this harness.` If later evidence shows a different model or effort, terminate the subagent, discard its work, and replace it.

---

## Documentation

Update durable docs when behavior or architecture materially changes. Use the repository's existing conventions for planning, ADRs, evidence, checkpoints, and test matrices. Do not invent a new document type when an existing one serves the purpose.

In source, document why and not what: invariants, authority and security assumptions, lifecycle and ownership rules, failure and recovery semantics, protocol and versioning assumptions, concurrency constraints, and why a tempting alternative is not used. Document public and package-visible contracts whose behavior, constraints, or role is not self-evident. Documentation volume is not a quality metric.

---

# Disposable acceptance targets

The rules below apply only to **external disposable projects used to acceptance-test Synesis**.

They do not authorize Synesis use inside the Synesis source repository.

## Disposable acceptance target

Use the project's normal Synesis MCP integration for all project reads,
mutations, and lifecycle actions. Do not edit `.synesis` manually.

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
- impact on published contracts (public API, wire formats, tool schemas, CLI surface, persisted formats), including any deprecation introduced;
- skills used, and any skill step skipped because of this file;
- test subagent model and effort, and whether they were verified or only pinned at launch;
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