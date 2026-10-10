@AGENTS.md

## Claude Code specifics

`AGENTS.md` is the single source of truth for how agents work in this repository. This file only adds what is specific to Claude Code. If the two disagree, `AGENTS.md` wins.

- Skills: use `graphify`, `constraint-driven-architecture`, `ponytail:ponytail`, `pstack:poteto-mode` (and the pstack skills it routes to), and `pstack:thermo-nuclear-code-quality-review` as described in the Skills section of `AGENTS.md`, including its precedence rules.
- The pstack `SessionStart` hook injects the poteto-mode routing mandate. It does not override the hard rules in `AGENTS.md`: no Synesis use on this source repository, no commit or push without explicit authorization, no scratch files committed.
- Test subagents: launch with the `Agent` tool, `model: sonnet` (confirm it resolves to Sonnet 5.5), and `subagent_type: pstack:effort-medium`, so effort is `medium` regardless of the session or the pstack `default effort` line. If the harness exposes no effective metadata, report: `launch configuration explicitly pinned; effective runtime metadata unavailable in this harness.`
- Other pstack subagents (arena, architect, interrogate, and so on) follow `~/.claude/pstack-models.md`.
- `graphify-out/` is gitignored. After code changes, run `graphify update .` and never commit its output.
