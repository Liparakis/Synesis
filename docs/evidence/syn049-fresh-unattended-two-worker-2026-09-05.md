# SYN-049 fresh unattended two-worker acceptance — 2026-09-05

## Classification

**PARTIAL.** Provenance and host compatibility passed, and a fresh real
WorkGroup admitted two distinct lawful participants. Worker A performed real
Synesis-managed implementation and published the requested capability. Worker
B reached the real structured dependency wait, but its original Codex process
ended while waiting for the owner response. Resuming the same Codex task opened
a new provider connection and participant; Synesis correctly rejected the
re-admission because the original B claims were still owned. The run was
stopped without creating a third lane, editing durable state, or bypassing
Synesis.

## Repository and artifacts

- Source repository at start: `8fcd32752700d17f75e08a6830c9e176f4fffea4`,
  branch `master`, clean, no push.
- Runtime source provenance: `a7697bbb5de83ced8b61b275056f9204e4467fbc`.
- Final source commit after this evidence/checkpoint update is recorded by Git
  in the enclosing repository.
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-25.0.0.36-hotspot\bin\java.exe`,
  Temurin `25+36-LTS`.
- Process-local property: `-Djdk.net.unixdomain.tmpdir=C:\t\synesis-loopback-probe`.
- `Selector.open()` passed.
- Minimal `HttpServer` create/start/stop passed.
- Produced and installed artifacts matched exactly:
  - workspace JAR: `a7049e8b0a9c7de792038c254f1c546515de1c02757ec97834ac67be5e6863a1`
  - MCP JAR: `dddc6df0decc0530dc30d6809ded118b9cf0e801ad7238b73aa53ab90bae0bfd`
  - CLI JAR: `07f6770592f923a78f3071be39e27d8dea4dfd13b99f99acbd5a8937d98cd0bb`
  - native MCP: `37a2db02909d434a6a8135a6326e2592ecf5f2e83c873e146e589b95a27f97ea`
- No global Java/network setting was changed.

The supported `synesis provider install codex` setup flow did mutate the
global Codex configuration: its Synesis entry was pointed at the fresh target
and the target was added to the Codex trusted-project list. A pre-run snapshot
was not taken, so this run does not claim restoration. Subsequent worker
launches used a process-local command override for the verified Desktop native
MCP executable. No credentials were read or copied.

## Fresh target and identities

- Fresh target: `C:\Users\Liparakis\Desktop\SynesisTaskTrackerRealAcceptance-20260905-01`.
- Target branch: `main`.
- Ordinary Git baseline before Synesis initialization:
  `e1ff2c114f4601970393c3e0ca79bddb4e5fd5ef`.
- Synesis initialization baseline: `c64eac26bd76752438704dc4854497d608d9fc7c`.
- Historical `SynesisTaskTrackerRealAcceptance-20260903-08` was not mutated
  or reused. No historical worker lane or copied state was used.
- WorkGroup:
  `7d89a793-f33a-3d69-b7aa-944d36d4579e` (remained `ACTIVE`).
- Worker A participant:
  `agt_09aea420-47bd-373e-9ea6-497c27084423`.
- Worker A WorkIntent:
  `d0944f30-a7c4-3fd9-89cd-08fab9f97002`.
- Worker A authority lineage:
  `8caf3f49-c348-3ad1-9209-dcd811ea4009`.
- Worker B participant:
  `agt_3aa6feab-5356-33b7-a138-6888bf8651e5`.
- Worker B WorkIntent:
  `9c14bc3d-7e8a-307e-bb91-b7f45db42591`.
- Worker B authority lineage:
  `fc93c805-d1d3-3022-abd0-2e71b1d5d784`.
- The exact structured dependency was `tasktracker.domain.persistence`.
- A’s claims were the domain/persistence main and focused-test subtrees. B’s
  claims were the application, API, reporting, README, and integration-test
  selectors. The original claims were disjoint.

## Observed collaboration

Worker B initially received `NEEDS_CAPABILITY` / `owner_required`, submitted a
lawful capability request, and reached `WAIT` with the server-issued request
handle. No B files were read or modified during that wait.

Worker A implemented real files in its isolated Synesis worktree:

`C:\Users\Liparakis\AppData\Local\Synesis\workspaces\a5383d5e-0d76-48e4-82f9-85e6f29fa98e\worktrees\session-06bc7680-6615-40de-905d-e1b7a1fd3836`

The implementation included immutable `Task`, `TaskStatus`,
`TaskRepository`, thread-safe `InMemoryTaskRepository`, and versioned
Base64-safe `FileTaskRepository`, plus focused tests. Java 21 compilation,
domain tests, in-memory repository tests, and file repository tests passed
after A corrected two implementation/test defects exposed by verification.

A accepted the exact capability request and published implementation revision

1. The owner-side publication reached the server’s validation checkpoint.
   The original B provider connection had already ended, so B never consumed the
   published implementation and never produced a validation result.

The original B worktree remained clean:

`C:\Users\Liparakis\AppData\Local\Synesis\workspaces\a5383d5e-0d76-48e4-82f9-85e6f29fa98e\worktrees\session-c11c1513-5ce6-4fb4-a141-0c5f828f4831`

The resumed B task was not allowed to mutate. Its new connection was assigned
participant `agt_3cbd002c-8b2b-3392-b6ce-9a1e207fbfbe`; re-admission of the
same B claims was rejected as `overlapping_claim` against the original B
participant. No third worker was created.

No Synesis integration, handoff consumption, completion request,
`finish_lane`, lane terminalization, or WorkGroup terminalization was reached.
The target control checkout remained at `c64eac26bd76752438704dc4854497d608d9fc7c`
with no tracked mutation. A’s legitimate uncommitted work remains isolated in
its worker worktree and was not copied or manually integrated.

## Stop and hygiene

- The original A and resumed B Codex processes were stopped normally after the
  blocker; no replacement path was invoked.
- No A2 was created and no additional Worker B was created.
- No manual `.synesis` edits, manual IDs, manual claim changes, cherry-pick,
  copy, or WorkGroup terminalization occurred.
- No provider credentials were inspected or copied.
- Raw provider proofs were not logged, persisted, or placed in the target or
  evidence. Only public identifiers and digests were recorded.
- No Synesis production source was changed.
- No push occurred.

## Exact remaining blocker and next action

The acceptance cannot continue in this target because the original B provider
connection ended before capability consumption, while the same-task resume
created a new connection that could not lawfully re-acquire B’s still-owned
claims. Continuing would require a new lane, manual state repair, or a bypass;
all are outside this run.

SYN-049 remains **PARTIAL**. SYN-051 remains **COMPLETE / PASS-A** and was not
reopened. The next action is a new bounded fresh-target acceptance only after
the harness preserves Worker B’s live provider connection through the
dependency wait and captures the pre-run Codex configuration so the supported
setup does not leave an untracked global mutation.
