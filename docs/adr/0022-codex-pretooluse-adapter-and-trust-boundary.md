# ADR-0022: Codex PreToolUse adapter and trust boundary

- Status: Accepted for SYN-009B.1 implementation
- Date: 2026-07-22

## Context

Synesis already has provider-local hook adapters and lifecycle management for
Antigravity and Claude Code. Codex exposes project-local lifecycle hooks through
`.codex/hooks.json`, but project hooks are conditional on project trust. A
Codex integration must therefore be useful for synthetic validation without
claiming that installation proves runtime enforcement.

## Decision

Add one Codex `PreToolUse` adapter for the `apply_patch` tool. Parse only a
bounded patch directive grammar, resolve every path through the shared
`ProjectPathResolver`, and evaluate every path through `ActionGuardrail`.
Any blocked or invalid path denies the entire patch; warnings add context;
allowed and unsupported events remain silent on stdout. The adapter never
applies a patch.

Install only a managed command entry in project-local `.codex/hooks.json`.
Preserve all unknown and unrelated configuration, write atomically, and never
modify Codex trust state. Codex remains `EXPERIMENTAL` and status remains
degraded until an authenticated real-agent experiment satisfies the explicit
block/recognition/reason/replan/hash gate.

## Alternatives rejected

- Reusing a transcript or SDK/App Server: not part of the requested hook
  contract and adds an unverified dependency.
- Applying patches in Synesis: violates the hook's guardrail-only boundary.
- Editing Codex trust state: creates machine-owned side effects and would turn
  installation into an unverifiable security claim.
- A new module or general provider framework: no independent ownership or
  release evidence justifies the coordination cost.

## Consequences

Synthetic and process-level evidence can prove parser, response, lifecycle,
and preservation behavior. It cannot prove Codex runtime trust, denial
recognition, replanning, or protected-file integrity; those remain explicit
real-agent validation evidence and the support level stays experimental until
they pass.
