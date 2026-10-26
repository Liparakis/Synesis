# ADR-0103 — Zero-friction onboarding and developer experience

## Status

Proposed on 2026-10-19, at the owner's request (USER-STATED: "Powerful by default. Simple by design. Complexity only
when requested."). Refined the same day at the owner's review (USER-STATED): the project's README is context, never a
task; readiness and first-task times are measured apart, with Synesis's own overhead apart from external latency; stage
estimates are preliminary and each stage lists its tasks, blockers, approvals, tests and completion criteria. Nothing here
is built. It changes published CLI output and deprecates commands, so the items marked
**contract change** need the owner's approval before they are built (AGENTS.md, "Public contract stability").

**Accepted on 2026-10-20 by the repository owner**, deciding the eight items below one by one (USER-STATED): 1 yes,
2 yes, 3 yes, 4 **remove** `host`, `join`, `sync host` and `sync join` now rather than deprecate them (an explicitly
approved breaking change to the published CLI: every caller, test and document is migrated in the same change, and
`link invite`/`link join` are the one way to join), 5 yes, 6 yes, 7 deferred to stage 4 (taken up on 2026-10-26: see "Stage 4 amendment"), 8 at the first invite.
Implementation is authorized stage by stage. A ninth decision the same day (USER-STATED: "No readme ... choose one"): **one place holds the
agent instructions for a user's project, the installed skill** (`synesis-provider-manual`, in `~/.claude/skills` and
`~/.codex/skills`). `init` stops writing the managed `AGENTS.md` section and the Claude rule file that imports it; on an
existing project Synesis removes only its own marked section (`<!-- SYNESIS-BEGIN -->` … `<!-- SYNESIS-END -->`) and
keeps the user's text; the Synesis MCP server's instructions, shown to every connected agent, point to the skill. Stage
1 no longer restores the README.

Claims are marked VERIFIED (file:line on branch `adr-0098-step3-code-over-link`), MEASURED (real runs of 2026-10-18/19),
DERIVED, ASSUMED or REQUIRES-MEASUREMENT.

## In plain words

Today a developer who wants one agent working in one repository has to learn the word "provider", run four or five
commands nobody tells them about in order, and read `KEY=VALUE` lines. The repository's README is a three-line stub.
Adding a second computer takes seven or eight commands and two node IDs copied by hand. When something fails, the
message often names the wrong cause.

This ADR keeps every capability Synesis has and changes the path to them:

1. Install Synesis (one installer, as today).
2. `cd` into a repository and run `synesis init`. It finds the agents already on the computer, connects the ones the
   developer picks (one question, skipped when there is only one choice), and prints the one next thing to do.
3. Start the agent the usual way (`claude`, `codex`) and tell it what to do, or let Synesis start several with an
   explicit task (`synesis agents run --agent claude --prompt "Implement X"`). Synesis never invents the task.
4. `synesis status` (or the browser UI) answers "what is happening, what is blocked, what needs me".
5. `synesis link invite` / `synesis link join` adds a computer; the owner approves the join on the invite's own screen.

Nothing new is invented where an existing command already does the job: `init`, `status`, `doctor`, `link` and `ui`
carry the experience. The deep commands stay, behind `synesis help --all`.

## Context: what a newcomer meets today (VERIFIED unless marked)

### Front door and docs

- **The root `README.md` is a three-line placeholder** ("Issue tracker acceptance target"), left by a commit that
  replaced the previous 140-line README.
- `docs/getting-started/README.md` (47 lines) covers building from source, `init`, `doctor` and `provider list`; nothing
  on agents, the UI or a second computer. There is no quickstart for agents, no troubleshooting guide and no UI guide.
- `docs/cli/command-reference.md:5-32` omits `ui`, `open`, `daemon`, `conversations`, `operator`, `trash`, all of `link`
  and most of `project`. `docs/architecture/installed-browser-ui.md:41-48` lists tabs the UI does not have.
  `docs/development/current-state.md` says 10 MCP tools; `McpToolCatalog.java:30-70` defines 11.

### Installation

- One self-extracting installer per platform (`cli/build.gradle.kts:1689+`), a bundled Java runtime (`:488-520`), a
  Windows wizard and a Unix console menu (`bootstrap/main.go:29,50-55`). Git is required at run time; the user needs no
  JDK. No OS package (winget, brew, apt) exists.
- The wizard's "Next steps" are `synesis --version` and `synesis init` (`pages_windows.go:196-198`); they do not mention
  connecting an agent, which is the step that makes Synesis useful.
- The firewall rule for Link is an unticked checkbox (`controls_windows.go:53-54`) or `--allow-link`; the rule names the
  installed `java.exe` of one version, so every upgrade makes it `STALE` (`link_firewall.go:52-87`). MEASURED
  2026-10-18: without the rule a VM could join only through a pasted answer link and could not redial after a long drop.
- Building the installer needs Go (`nativeMcpLauncher`, `:393-461`); a developer without Go cannot build it
  (MEASURED 2026-10-18).

### First run

- `synesis init` needs a Git repository and refuses otherwise (`ProjectApplicationService.java:194-204`); the web UI's
  path runs `git init` itself (`:258-268`). Init makes a "managed baseline" commit
  (`ManagedBaselineTransactionService.java:95-130`), is idempotent (`:285-299`), and ends with
  `NEXT_COMMAND=synesis constraint create --help` (`InitCommand.java:61`): the wrong next step for almost everyone.
- **Init writes Codex's global `~/.codex/config.toml` even when Codex is not installed**, and ignores errors there
  (`ProjectApplicationService.java:331-336`). That modifies configuration the user did not ask to touch.
- Connecting an agent is a separate command the user must know: `synesis provider install <claude|codex>`
  (`ProviderApplicationService.java:92-161`). It also writes global files (`~/.claude/skills/...`,
  `~/.codex/skills/...`, `ProviderManualService.java:79-84`) without listing them.
- **Every failure of `provider install|status|list` prints `ERROR=PROJECT_NOT_CONFIGURED`**, whatever the cause
  (`ProviderInstallCommand.java:48-50`). MEASURED 2026-10-18: a missing launcher printed it.
- Nothing detects which agents are installed or signed in before use; the only PATH lookup happens when `agents run`
  starts one (`ProviderAgentProcesses.java:115-145`).
- After a reboot the project runtime is started by `link` commands and, since 2026-10-18, `agents run`; an interactive
  `claude`/`codex` session's MCP server does not start it (`TWO_MACHINE_TESTING.md:164-170`).

### Commands and output

- 36 top-level commands, none hidden (`SynesisCli.java:140-238`). Overlaps: three ways to connect computers (`host`/
  `join`, `sync host`/`sync join`, `link invite`/`link join`, plus `project share/add-member/follow`); three ways to
  claim work (`collaboration announce`, `ownership claim`, `task claim`); three status views (`coordination status`,
  `collaboration status`, `operator list`); `doctor` and `repair` exist in both the CLI and the installer.
- Default help groups commands (`SynesisCli.java:118-130`), but `link` and `conversations` fall into "Other commands";
  ten agent-facing commands sit on one line in the default help.
- Error output uses at least seven shapes: `ERROR=` (48 places), `REASON=` (10), `HINT=` (5), `FAILURE=` (2),
  `MESSAGE=` (2), `ERROR_CODE=` (1), thirteen per-command `*_ERROR=` keys and seventeen prose lines on stderr.
- `--porcelain` and `SYNESIS_OUTPUT` select machine output; human output re-aligns the same `KEY=VALUE` lines with
  colour (`HumanTerminal.java`); exit codes are defined (`ExitCodes.java`), but `DOCTOR_BROKEN=3` is unused and provider
  results reuse 2 and 3 with other meanings.
- Picocli prints suggestions for unknown subcommands (`SynesisCli.java:286-299`).

### Seeing what happens

- There is no `synesis status`. "Are my computers connected" is answered only by `coordination status`
  (`REPLICATION_MEMBER=... STATE=`); "what needs me" by `operator list`; "what is each agent doing" by
  `collaboration status` (`CollaborationStatusCommand.java:38-57`). None of the three has an output test.
- `synesis doctor` (about 55 finding codes) does not check git, the agents, their sign-in, Link, peers, replication,
  operator requests or the firewall, and looks for the launcher in `~/.synesis/bin`, not where the installer puts it
  (`DoctorService.java:240-254`).

### Web UI

- Six tabs per project: overview, agents, coordination, trash, network, diagnostics (`routes.ts:1-27`). It cannot
  start or stop agents, show an agent's output, recheck or close an operator request (read-only, `App.tsx:972-977`), or
  show ADR-0098 membership (owner, members, recovery key, replication). Only the project serving the runtime opens live
  (`ControlPlaneReadModel.java:263`).
- Defects: coordination tables ignore the selected WorkGroup (`App.tsx:863`); "View details" always shows the first
  record (`App.tsx:1005-1008`); the overview's empty state says to create a WorkGroup, which no UI action does.
- Presentation mappings live in the UI (error texts, labels); no coordination rule is duplicated there (VERIFIED).

### What the real runs of 2026-10-18 taught (MEASURED)

Eighteen product defects in a day of PC + VM runs; the ones a newcomer would hit first were onboarding defects: the
member's agents found no server after joining, an MCP server ran a different build than its hooks, the runtime was not
restarted after a reboot, a reviewer did not know how to close its lane, `link join` failed with a bare `FAILED` when the
answer came late, and the firewall rule went stale after an upgrade.

## Decision

### The rule: one path, three depths

| Depth | Who | Sees | Words used |
| --- | --- | --- | --- |
| **Everyday** | anyone | `init`, `status`, `ui`, `agents run`, `link invite`/`join`, `doctor`, `help` | project, agent, task, computer, review |
| **Team** | someone running several agents or computers | `operator`, `trash`, `conversations`, `project share/remove-member/recover`, `provider` | plus WorkGroup, lane, snapshot, owner |
| **Expert** | maintainers, scripts | everything else, `--porcelain` | plus claims, ownership, replication, predictions |

These are views of one command set, not limits: every command runs for everyone. `synesis help` shows the everyday
commands with one line each and "`synesis help --all` for everything"; `--all` shows today's grouped help. Commands
agents call (`hook`, `mcp`, `check-action`, `workspace`, ...) move out of the default help into `help --all`.

### 1. `synesis init` connects the agents (no new setup command)

`init` keeps its contract and gains discovery:

1. **Git.** In a folder that is not a repository, an interactive `init` offers `git init` (one yes/no); `--git-init`
   says yes for scripts. It never commits user files: the managed baseline commit stays the only commit it makes, as
   today.
2. **Agents.** It looks for each registered provider's executable on `PATH` and asks it for its version (a process run,
   no credential read). Sign-in is judged only by the provider's own non-secret command where one exists (for Codex,
   `codex login status`); where none exists it says "could not tell" and does not open credential files.
3. **Choice.** No agent found: it says how to install one and continues. One found: it connects it. Several found and
   interactive: one multi-select question. Scripts use `--agent claude,codex` or `--no-agents`.
4. **Connect** = today's `provider install`, unchanged in what it writes, plus a list of every file it wrote or changed,
   project and global, at the end.
5. **Next step**: one line for the chosen agent ("Start Claude Code here: `claude`"), and `synesis status`.
6. **Stops writing what was not asked for**: Codex's global config is written only when Codex is connected
   (fixes `ProjectApplicationService.java:331-336`; **contract change**: a project initialized without Codex no longer
   has a Codex MCP entry; an existing entry is left alone).

`provider install` stays, for adding an agent later. Re-running `init` on an initialized project is the way to connect
newly installed agents (idempotent, as today).

### 2. `synesis status`: one answer to "what is going on"

A new read-only command, the only new top-level command in this ADR, because the question "what is happening" has no
command today and three partial ones. It prints, in this order, each line drill-down-able:

```text
Project  my-app (shared, owner: this computer)            healthy
Agents   2 working  (claude: digits/, codex: review of fib)   1 waiting for review
Needs you  1 operator request: "combine lane cannot start"  → synesis operator list
Done     4 of 5 tasks on main (last: gcd, 3 min ago)
Computers  2 connected (vm: last sync 4 s ago)  firewall ok
```

- It is a renderer of the same read model the browser UI uses (`ControlPlaneReadModel`) plus `coordination status`'s
  replication facts, so the CLI and UI never disagree (no second implementation).
- `--porcelain` prints stable `STATUS_*` keys; `--watch` redraws on change (from the existing event stream, no polling
  loop of its own).
- `coordination status`, `collaboration status` and `operator list` stay, as the expert views.

### 3. One error shape with a fix

Every failure prints, human and porcelain alike:

```text
ERROR=<CODE>          stable, documented
MESSAGE=<one sentence what happened>
FIX=<one command or action, when one exists>
```

- Existing keys (`REASON=`, `HINT=`, `*_ERROR=`) keep being printed for one release beside the new ones, then are
  removed in a separately authorized change (**contract change**, additive first).
- Causes are reported truthfully: `provider install`'s blanket `PROJECT_NOT_CONFIGURED` is replaced by the real cause
  (launcher missing, provider unknown, project not initialized, ...).
- Exit codes keep their meanings; `DOCTOR_BROKEN=3` is used by `doctor --strict` as its name says, and provider results
  stop reusing 2 and 3 (**contract change**, documented in the exit-code table).

### 4. `synesis doctor` covers the environment and the network, and repairs what is safe

`doctor` keeps its findings and gains three groups, each finding marked **confirmed**, **suspected** or **advice**,
with a `FIX=`:

- **Environment:** git present and new enough; each connected agent present on `PATH`, its version, its sign-in where
  the provider can tell; the launcher found where the installer puts it (fixes `DoctorService.java:240-254`); the MCP
  server and hooks of each agent use the same Synesis build (MEASURED defect of 2026-10-18).
- **Network** (shared projects): Link firewall rule present and pointing at the running Java; each member's session and
  last successful log pull (not just "session up": the stuck-pipe defect of 2026-10-18 showed CONNECTED for 30 minutes
  with nothing pulled); the project runtime running.
- **Work:** open operator requests, lanes waiting on an away computer, stale worktrees (from the existing findings).

`doctor --fix` applies only repairs that are safe and deterministic and already exist as commands (start the runtime,
reinstall a provider's managed files, cleanup of Synesis's own temporary state). Anything that deletes user work, changes
a security setting (the firewall) or answers an operator request is printed as `FIX=` for a person to run; `--fix` never
does it.

### 5. Adding a computer: invite, open, approve on the same screen

The one path is `link invite` / `link join` (today's ADR-0098 flow), with two changes:

- **Approve where you invited.** An interactive `link invite` that receives the join request prints who is asking
  ("`vm-laptop` (node sl1-5479…) wants to join `my-app`. Accept? [y/N]") and accepts on yes. No node ID is copied by
  hand. `link requests` / `link accept` stay for scripts and the UI. Never automatic: a join is accepted only by a
  person's yes (or `JOIN_POLICY_SET{ANY_MEMBER}`, as ADR-0098 decided).
- **The firewall is part of the invite.** When the rule is missing or stale, the invite says so and, interactively,
  offers to run the elevated fix (one Windows UAC prompt the person approves). The installer's checkbox becomes checked
  by default with a one-line explanation (**contract change** to the installer's default; it still needs the person's
  elevation).
- `host`/`join` and `sync host`/`sync join` are deprecated with `link invite`/`link join` as the replacement, removed in a
  later, separately authorized change (**contract change**). `project share/add-member/follow` stay as team-depth
  commands.

Target: a second computer joins with **one action on each side plus one approval** on the same network, and one pasted
answer link more across networks.

### 6. Agents: discover, start, see, recover

**Project context is not a task.** The README and the rest of the repository belong to the user's project. Agents read
them, as they read any code, to understand what the project is; Synesis never turns their content into work. A task
reaches an agent only from a person: what they type to an interactive agent, an explicit `--prompt`/`--prompt-file`, or
a task a person created in Synesis.

- `synesis agents run` keeps its contract: starting headless agents needs a task, because the harnesses' headless modes
  take an initial prompt (`claude -p`, `codex exec`; VERIFIED in `ProviderAgentProcesses`) and an empty one would only
  produce an agent with nothing it is allowed to do.
  - `--prompt "Implement X"` or `--prompt-file` start the agents with that task, as today.
  - With neither, on an interactive terminal, it asks one question, "What should the agents work on?", shows the
    answer back, and starts only on a non-empty answer. Nothing is filled in for the person.
  - With neither, in a script or with `--porcelain` or no terminal, it never asks: it refuses with
    `ERROR=TASK_REQUIRED`, `MESSAGE=agents run needs a task`, `FIX=pass --prompt "<task>" or --prompt-file <file>`
    (today it refuses with a picocli usage error, so only the message improves; exit code 2 stays).
  - There is no default prompt, and the README is never used as one.
- **Ready without a task** (an agent that connects and waits for instructions) is *not* part of this ADR's default: a
  waiting headless agent needs a way for a person to send it work later, and today a person cannot post into a
  conversation (`ConversationsCommand` is read-only, VERIFIED). It is listed under stage 4 and "Decisions for the
  owner". Until then, "ready" means connected and passing its health check (`provider status` = `HEALTHY`), which is
  what an interactive `claude`/`codex` session needs.
- **Logs:** `agents run` prints where each agent's transcript is, and `synesis agents log <label>` tails it (today the
  logs exist outside the project with no command to read them, `AgentsRunCommand.java:180`).
- **Interactive sessions** stay the provider's own (`claude`, `codex`); Synesis only connects them. After a reboot the
  MCP server starts the project runtime itself, through the CLI's daemon client (the MCP server is launched by
  `synesis mcp`, in the CLI module, so no module boundary moves).
- Recovery stays automatic (ADR-0099); `status` shows "resumed after a restart" instead of the internal state names.

### 7. Web UI: same capabilities, first-run and actions

- **First run:** with no project, the UI opens on "Open a folder" (picker → init → agent discovery, the same service as
  CLI `init`), not on an empty registry.
- **Readiness panel** on the overview: agents found, connected, signed in; runtime; computers; firewall: the `doctor`
  environment and network groups, from the same service.
- **Actions it lacks today:** recheck and close an operator request, remove a member (owner only), see the owner and
  members (ADR-0098 membership in the read model). Starting agents from the UI waits for stage 4.
- **Defects fixed:** coordination tables follow the selected WorkGroup; "View details" opens the chosen record; the
  overview's empty state points at something the user can do.
- **No logic in the UI**: every new view is a read-model field or an existing command endpoint
  (`ControlPlaneHttpHandler`), as today.

### 8. Documentation that is executed

- **README.md** restored as the front door: what Synesis is, install, the five-step path above, links.
- `docs/getting-started/`: quickstart (one agent), multi-agent, second computer, collaborator, troubleshooting by
  `ERROR=` code. Each walkthrough's commands live in a test fixture the Linux gate runs in a disposable project, so a
  doc that drifts fails the build (no doc is checked by hand).
- `command-reference.md` is generated from the picocli tree (descriptions stay in code), so it cannot omit commands
  again.
- Stale documents named in "Context" are corrected or archived, each with a line of why.

### 9. Responsiveness

- Measure first (REQUIRES-MEASUREMENT): CLI start for `--help`, `status` and `init` on this PC and the VM. The bundled
  runtime is jlinked Java 25; an AppCDS archive in the runtime image is the expected lever for start time, applied only
  if the measurement shows it is needed.
- `status --watch` and the UI use the existing event stream; no new polling.
- Discovery runs agents' `--version` once per `init`/`doctor`, never in the background.

## Alternatives considered

1. **A new `synesis setup` wizard beside `init`.** Rejected: two commands for one moment, and `init` already is the
   first command the installer names. Extending `init` keeps one entry.
2. **The installer configures agents.** Rejected: the installer is per computer, agent connection is per project, and
   the installer runs before any project exists.
3. **UI-first onboarding, CLI unchanged.** Rejected: users who work in a terminal may never open the UI, and scripts need the CLI path;
   both are thin over one service instead.
4. **Hide the expert commands entirely.** Rejected: the owner asked for complexity on request, not removed; `help --all`
   keeps them one flag away.
5. **The README as a default task for `agents run`.** Rejected (USER-STATED, 2026-10-19): the README is the project's
   context, not an instruction; turning it into work would authorize changes nobody asked for.
6. **Auto-accept joins from computers on the same network.** Rejected: a join is a trust decision (ADR-0098, owner
   approves each join); the approval moves to the invite screen instead.
7. **A Synesis-run account or relay for onboarding.** Rejected: no mandatory service, free and open-source (ADR-0097).

## Module responsibilities

| Module | Gains | Does not gain |
| --- | --- | --- |
| `workspace` | environment discovery (agents on `PATH`, versions, provider sign-in checks), as findings of the existing `DoctorService` and inputs to `ProjectApplicationService.init`; the status summary as a view over `ControlPlaneReadModel` | a second read model; any credential reading |
| `cli` | `init` prompts and flags, `status`, `agents log`, error shape, help depths, interactive accept in `link invite`, `synesis mcp` starting the runtime | business rules (they stay in workspace/coordination) |
| `web-ui` | first run, readiness panel, operator and member actions, the two defects | coordination rules |
| `bootstrap` | wizard next steps, firewall default, rule refreshed on upgrade | provider setup |
| `coordination`, `link`, `mcp` | nothing new | |

No new module and no new interface type: discovery is a set of doctor findings and init inputs; status is a renderer.

## Build order

Each stage ships on its own. Durations are preliminary estimates for planning, not commitments: correctness, security
and maintainability decide when a stage is done, by its completion criteria below, not the calendar.

### Stage 1 — corrections with no contract change (preliminary: one to two days)

- **Tasks.** Restore `README.md` as the front door. Write the one-agent quickstart. `init` prints the right next step
  (connect an agent, start it, `synesis status`). `provider install|status|list` report the real cause instead of the
  blanket `PROJECT_NOT_CONFIGURED`. `doctor` looks for the launcher where the installer puts it. `link` and
  `conversations` join their help groups. `command-reference.md` is generated from the picocli tree. The stale documents
  named under "Context" are corrected or archived. The UI's coordination tables follow the selected WorkGroup, "View
  details" opens the chosen record, and the overview's empty state names an action the user can take. `synesis mcp`
  starts the project runtime when it is down. `agents run` with no task refuses with a message naming the fix (message
  only; exit code 2 as today).
- **Dependencies and blockers.** None outside the repository. `synesis mcp` starting the runtime reuses the daemon client
  `agents run` uses since 2026-10-18; it must not slow a single-computer session (measured before and after).
- **Contract changes needing approval.** None: new or clearer messages only, same keys and exit codes.
- **Automated tests.** One test per refusal path of `provider` naming its real cause; a doctor test for the installed
  launcher path; a test that every command appears in the generated reference; web-ui tests for the WorkGroup filter and
  the details record; a test that `synesis mcp` starts a stopped runtime; `agents run` without a task starts no agent.
- **Real-world test.** A fresh VM user follows the new README from install to an interactive `claude` session.
  Done on 2026-10-20 without a README (decision 9) as VM user `synesis3`, build remaster89: `init` wrote no `AGENTS.md`
  and printed the next steps, `provider install codex` installed the skill, `agents run` without a task named the fix,
  and two `codex:gpt-6-luna:high` agents given "Make the failing test pass without changing the test" finished in 64 s
  with the fix on `main`, the test file unchanged and the tests passing (REAL, one run).
- **Completion criteria.** Every item merged with its tests green on the Linux gate and the Windows gate; the README path
  works on the fresh VM user without any step the README does not state.
- **Built, web UI part (2026-10-20).** The coordination tables follow the selected WorkGroup: claims by their
  `workGroupId`; tasks, ownership records and review disputes through their task id, which an agent's current work or a
  trash entry pairs with a WorkGroup (a task paired with none stays visible under every group). Capabilities,
  conversations and operator requests carry no WorkGroup in the snapshot and stay project-wide, which the tab says. A
  `workGroupId` on tasks, ownerships and disputes in `ControlPlaneReadModel` (additive) would make the rule exact. "View
  details" opens the row the person picked (click or Enter), the first row until a pick. The overview's and the
  coordination view's empty state says a WorkGroup appears when an agent announces work, gives `synesis agents run
  --agent claude --prompt "..."`, and offers "Open agents". Tests: `web-ui/src/app/App.test.tsx`, "the coordination
  view" (4 tests); `:web-ui:check` green on the Linux gate. Other stage 1 items are not part of this entry.
- **Built 2026-10-20 (CLI and workspace items; source semantics proven on the whole Linux gate, `check`: cli 265 tests
  and workspace 1080, 0 failures, plus `repositoryHygieneCheck`; not yet the Windows gate or a real run).** New tests:
  `ProviderRefusalCausesTest` 5, `McpCommandTest` 4, `CommandReferenceTest` 2, `AgentsRunCommandTest` +2,
  `SynesisCliParsingTest` +1, `DoctorServiceTest` +3.
  1. `provider install|status|list|uninstall` no longer print `PROJECT_NOT_CONFIGURED` for every failure
     (`ProjectOptions.refuse`): a directory that is not an initialized project keeps it; a nonexistent directory prints
     `ERROR=PROJECT_INVALID`, unreadable metadata `ERROR=MALFORMED`, any other failure `ERROR=PROJECT_ACCESS_FAILED`,
     each with a `REASON=` line, exit 10 as before. The causes the provider service already named (`UNKNOWN_PROVIDER`,
     `LAUNCHER_MISSING`) are pinned by a test. `agents run` uses the same report for its own project lookup, so an
     unreadable `--prompt-file` is no longer reported as a missing project either.
  2. `doctor` finds the launcher where provider hooks run it from (PATH, configured, the installer's `Synesis/bin`) and
     reports `installed_launcher_missing` only when that directory exists without one; it used to look in
     `~/.synesis/bin`, which the installer never writes.
  3. `link` is listed under "Work with another person" and `conversations` under "Coordinate work".
  4. The command list of `docs/cli/command-reference.md` is generated from the picocli tree
     (`CommandReferenceTest`, which fails on drift and rewrites the block with
     `SYNESIS_REGENERATE_COMMAND_REFERENCE=1`); the prose around it stays hand-written.
  5. `synesis mcp` asks the daemon (`ProjectRuntime.DAEMON`, now shared with `agents run`) for the project's runtime on a
     background thread, only for a project shared with another computer, so a single-computer session starts nothing
     and no session waits for the daemon. Nothing is written to standard output. The start-time measurement before and
     after is still to be made.
  6. `agents run` without a task, or with a blank one, prints
     `ERROR=agents run needs a task; pass --prompt "<task>" or --prompt-file <file>`, exit 2, and starts no agent; a
     blank `--prompt` used to start agents.
- **Built 2026-10-20 (decision 9, init's next step, stale documents).** The installed `synesis-provider-manual` skill
  (version 13) now holds every rule the managed `AGENTS.md` section held, and is the one place for agent instructions.
  `init` and `provider install` write no `AGENTS.md` section and no `.claude/rules/synesis.md`; `init` (new, existing and
  replica) removes only an earlier version's marked section (`<!-- SYNESIS-BEGIN -->` … `<!-- SYNESIS-END -->`, user text
  kept, a file left with nothing else deleted, unpaired markers and symbolic links never touched) and a rule file that
  carries Synesis's ownership marker, and lists them as `INSTRUCTIONS_REMOVED=<path>`; `provider install` reports
  `PROJECT_INSTRUCTIONS=REMOVED|ABSENT|CONFLICT|NOT_APPLICABLE`. The MCP `initialize` result now carries `instructions`
  naming the skill (additive). `AGENTS.md` left the managed paths of `ManagedPathPolicy`, `SnapshotArtifactPolicy`,
  `SnapshotTreeMaterialization`, `ControlBranchAdvancementService` and `CodeOverLink`: it is the user's own source file, so
  a lane's change to it travels with the lane, an untracked one counts as a foreign change of the control checkout, and a
  folder holding one is not "only Synesis's files". The removal is not committed: where Git tracks `AGENTS.md` it shows as
  a modification the user commits (`init` says so). `init` ends with `NEXT_COMMAND=synesis provider install claude` and
  three `NEXT_STEP=` lines (connect an agent, start it, `synesis ui` / `coordination status` / `operator list`) instead of
  `synesis constraint create --help`. Stale documents corrected: the AGENTS.md contract (now
  `docs/architecture/agent-instructions-contract.md`), `docs/development/current-state.md` (tool count),
  `docs/architecture/installed-browser-ui.md` (tab list), `docs/getting-started/README.md`, `docs/providers/claude-code.md`,
  `docs/providers/mcp.md`, `docs/installation/project-layout.md`. Left to the other stage-1 owners: provider refusal
  messages, the doctor launcher lookup, help groups, `docs/cli/command-reference.md` (its line on untracked
  `AGENTS.md` not counting as a foreign change is now out of date), the `synesis mcp` runtime start, `agents run`, the UI.

### Stage 2 — discovery and visibility (additive contract changes; preliminary: two to three days)

- **Built 2026-10-20 (agent discovery and `init`; not the rest of stage 2).** `ProviderIntegration` gained
  `executableName`, `versionArguments`, `signInStatusArguments` and `signInState` (defaults: the id, `--version`, no
  status command, `UNKNOWN`); Claude Code asks `claude auth status --json` (reads only `"loggedIn"`) and Codex
  `codex login status` (VERIFIED 2026-10-20 against Claude Code 2.1.302 and Codex 0.163.0). `ProviderDiscovery` finds the
  executable on `PATH` (`ProviderExecutables`, shared with `agents run`, which now runs the same lookup), runs the
  version and status commands with a 15 s limit, and keeps only the version line and the sign-in state; it opens no file
  and a command that fails leaves the answer `UNKNOWN`, never "not found". `init` prints `AGENT_<ID>=`, `_VERSION`,
  `_SIGN_IN`, connects one found agent, asks once on a terminal when several are found (a script gets a `NEXT_STEP=`
  instead), takes `--agent <id>` (repeatable or comma separated), `--no-agents` (no discovery) and `--git-init`
  (`GIT_INIT_PROMPT=` on a terminal), prints `AGENT_<ID>_CONNECT=` and `AGENT_<ID>_WORKTREE=`, and lists every file it
  created or changed as `FILE_CREATED=`/`FILE_CHANGED=` (`TouchedFiles` watches the places setup can write by content
  hash, so the cost does not depend on the project's size). Per decision 2, initialization (new, existing and replica)
  refreshes Codex's user-wide `config.toml` entry only when Codex is already connected in that project, and leaves an
  existing entry alone otherwise; `init --agent codex` is what connects it (this is the stage 3 item "`init` stops
  writing Codex's global config", pulled forward on the owner's decision). Every `init` refusal also prints `ERROR=`,
  `MESSAGE=` and `FIX=` on standard error beside `ERROR_CODE=`/`REASON=`; exit codes are unchanged, except that a failed
  connection exits with the `provider install` code (10) and an option mistake with 2. `Terminal.interactive()` is true
  only at a terminal without `--porcelain`/`SYNESIS_OUTPUT=porcelain`, so a script is never asked a question. Tests:
  `ProviderDiscoveryTest` 10 (0, 1, 2 agents, a harness without a status command, a failing command, Windows shims, the
  planted-token test with the real process runner, and a guard that discovery reads no file), `InitAgentSetupTest` 12
  (the file-system snapshot of what `init` writes and lists, choice and prompt paths, `--no-agents` running nothing,
  Codex's configuration left alone, the refusal triples), `ProviderApplicationServiceTest` +1 (the old test that init
  registers Codex is replaced by two for decision 2). Source semantics proven on the Linux gate (touched classes green;
  whole gate result in the commit report) and on Windows for the touched classes, `ProviderDiscoveryTest` running the
  real process runner on `.cmd` shims there. A whole Windows `check` ended with three `cli` scenario tests failing on
  temp-folder cleanup or a Link join timeout; they pass on the Linux gate and initialize their projects with provider configuration off. No real Claude or
  Codex was connected, and a real fresh-user run is still to do. Still open in
  stage 2: `doctor` groups, `status`, `agents log`, the `agents run` question and `ERROR=TASK_REQUIRED`, the executed
  walkthroughs, and the same error shape in `provider`, `link` and `agents run`.

- **Tasks.** Agent discovery per provider (`ProviderIntegration` finds its executable on `PATH`, asks its version, asks
  its own sign-in status command where one exists, else "could not tell"). `init` uses it: connects one found agent,
  asks once when several are found, offers `--agent`, `--no-agents`, `--git-init` for scripts, and lists every file it
  wrote. `doctor` gains the environment, network and work groups with confirmed / suspected / advice and `FIX=`.
  `synesis status` (+ `--porcelain`, `--watch`) as a renderer over `ControlPlaneReadModel` and the replication facts.
  `agents log`. The error shape (`ERROR`, `MESSAGE`, `FIX`) added beside the existing keys, with `ERROR=TASK_REQUIRED`
  for `agents run` without a task. `agents run`'s one interactive question for a task. The walkthroughs become executed
  fixtures in the Linux gate.
- **Dependencies and blockers.** The status renderer needs the read model to carry what it shows (agents' current work,
  operator requests, computers and their last successful pull); the last-pull time needs ADR-0102's per-log pull record
  or an equivalent field. Sign-in discovery depends on each harness offering a status command (Codex has
  `codex login status`, VERIFIED 2026-10-19; Claude Code's is to be checked; without one the answer is "could not
  tell").
- **Contract changes needing approval.** New CLI output keys (`ERROR`/`MESSAGE`/`FIX` beside the old ones, `STATUS_*`),
  two new commands (`status`, `agents log`), new `init` flags, new doctor finding codes. All additive.
- **Automated tests.** Discovery over fixtures with 0, 1 and 2 agents and with a harness that has no status command; a
  planted-token test (no output, log or diagnostic contains a token file's content, and discovery opens no credential
  file); a file-system snapshot test of what `init` touches; output tests for `status` (none exist today for the three
  status commands); a test that every setup refusal path prints `ERROR`, `MESSAGE` and `FIX`; executed walkthroughs.
- **Real-world tests.** Fresh Windows user and fresh VM user: install, `init` with Claude only, Codex only, and both;
  readiness measured as below; `status` checked against a real PC + VM run.
- **Completion criteria.** Readiness measured on both computers with Synesis's own overhead reported separately from
  external latency; every setup failure in the tests prints a fix; the planted-token test green; the old error keys still
  printed.
- **Built 2026-10-20 (error shape, `agents run` question, doctor groups; source semantics proven on the Linux gate by the
  focused tests listed below; not yet the whole gate, the Windows gate or a real run).** Decision 1 of the owner's
  approval ("yes", `ERROR`/`MESSAGE`/`FIX` beside the old keys) is built for `provider`, `link` and `agents run`;
  `init`, `status`, `agents log`, discovery and the web UI belong to other changes.
  1. **One error shape** (`ErrorShape`, `cli/command`): `ERROR=<CODE>`, `MESSAGE=`, `FIX=`, from one catalog of codes with
     a message and fix each; a command with input-dependent wording gives its own. Every provider refusal
     (`ProjectOptions.refuse`, `ProviderCommand.report` for install/status/uninstall results including a broken or
     degraded status that had no `ERROR` of its own, `provider migrate`), every link refusal (`LinkCommand.refuse`: a
     runtime rejection, a project that is not initialized, an unreachable runtime, an interrupted wait, an invitation that
     ended without a connection, a wait that ran out) and every `agents run` refusal prints it. Old keys and exit codes
     are unchanged (`REASON=`, `*_RESULT=`, `LINK_STATE=`, `LINK_FAILURE=`). Two values did change: the sentence that
     `agents run` printed after `ERROR=` is now the `MESSAGE=` and `ERROR=` is the code (`TASK_REQUIRED`,
     `PROMPT_CONFLICT`, `AGENT_INVALID`, `UNKNOWN_PROVIDER`, `PROVIDER_NOT_INSTALLED`, `AGENT_RUN_INVALID`), and
     `link join` printed `MESSAGE=` before `ERROR=INCOMPATIBLE_VERSION` and now prints it after. The code table and an
     exit-code table are in `docs/cli/command-reference.md`; a test fails when a code is not documented.
  2. **`agents run`'s question.** `Terminal.interactive()` is true only with standard input and output on a terminal and no
     `--porcelain` / `SYNESIS_OUTPUT=porcelain`. At a terminal with neither `--prompt` nor `--prompt-file` it asks "What
     should the agents work on?" once, after the project, providers and options were checked, prints the answer back as
     `AGENT_TASK=` and starts only on a non-empty one; a blank task, an empty answer, and any script refuse with
     `ERROR=TASK_REQUIRED` (exit 2, no agent, standard input unread).
  3. **Doctor groups.** `DoctorGroup` (environment, network, work groups; the earlier findings stay in `project` except
     the launcher and provider-configuration ones, now environment, and leases and cleanup, now work groups),
     `DoctorConfidence.ADVICE` and `mark()` (confirmed / suspected / advice), a `FIX` on every finding (a
     recommendation's own when a finding has none). New finding codes: `git_missing`, `agent_not_on_path` (suspected, for
     a connected agent only), `provider_files_stale`, `project_runtime_not_running` (suspected: the installation daemon
     holds none), `link_firewall_missing`, `link_firewall_stale`, `operator_requests_open` (advice, changes no status).
     The output keeps every earlier line and adds `<GROUP>_STATUS`, `<GROUP>_FINDINGS` and per-finding `_MARK`, `_MESSAGE`,
     `_FIX` keys; `--json` gains `groups` and `group`/`mark`/`fix`. The daemon's `STATUS` answer gained a `projects` list
     (additive) so the doctor can ask whether a project's runtime runs without starting anything.
  4. **`doctor --fix`** makes exactly three repairs, each an existing command, and reports again with
     `MUTATIONS_PERFORMED=<n>`: Synesis's own stale lock files and corrupt plan files through the existing repair plan;
     `provider install` for a connected agent whose managed files are stale; starting a shared project's runtime. It
     never touches a person's files, the firewall or an operator request.
  5. **Left for the rest of stage 2 / stage 3:** sign-in discovery and the agent version (the doctor checks that a
     connected agent is on the `PATH`, with the provider id as the executable name, until `ProviderIntegration` discovery
     lands); that the MCP server and the hooks use the same build; each member's last successful pull (needs ADR-0102's
     per-log record); lanes waiting on an away computer; the planted-token test; `--strict` exit code 3.
- **Built 2026-10-20 (`synesis status` and `synesis agents log` only; source semantics proven on the Linux gate for the
  classes named below, not yet a real PC + VM run or the Windows gate).** Discovery, `init`'s new flags, the `doctor`
  groups, the `ERROR`/`MESSAGE`/`FIX` shape on the setup refusals and the executed walkthroughs are not part of this entry.
  1. `status [--project] [--watch]` renders `ProjectStatus` (workspace, `transport.control`), a view over
     `ControlPlaneReadModel` (agents with their lane, role and claimed files, open operator requests) and the replication
     facts `coordination status` prints (membership or role file, `replication.members`, the loopback endpoint), so the CLI
     and the UI read one model. A terminal gets one screen (project, runtime, agents, needs you, computers); `--porcelain`
     gets `STATUS_*` keys. Computers asking to join count under "needs you". It works with the runtime stopped, as `log
     dump` does: `CoordinationService.readOnly` and the now public `PredictionEventStore.openReadOnly` replay the logs
     without the creating, moving and removing an ordinary open does (archiving a superseded log, removing interrupted
     writes), no identity is created and no private key read. The runtime counts as running only when something listens
     at the recorded address (`LoopbackExchange.runningServer`); while it does not, a computer's connection is
     `RUNTIME_STOPPED`, never a claimed `CONNECTED`. `--watch` follows the running server's event stream from the log's
     head and draws again per entry; without the runtime it draws once and refuses with `ERROR=WATCH_NEEDS_RUNTIME`
     (`MESSAGE`, `FIX=synesis ui --no-browser`), exit 10.
  2. **Missing, reported:** the last *successful pull* of a computer is not recorded anywhere (ADR-0102's per-log pull
     record is not built). `LAST_ENTRY_RECEIVED` is the newest write into this computer's copy of that log
     (`LogDump.lastArrival`): when something last came, not when the computers last talked. The read model carries no
     coordination requests between agents and no task completion, so "Done: 4 of 5 tasks" of the sketch is not shown;
     computers have no names, only nodes.
  3. `agents log [<agent>] [--run <run-name>]` reads the newest run's log files (`agent-runs/<project>/run-<uuid>/
     <provider>-<n>-<launch>.log`), one provider decoder each (`HeadlessSession.logEntries`, new default method; Claude
     `stream-json`, Codex `exec --json`), as `AGENT_LOG_SAID|CALLED|RESULT|FAILED` lines, newest run by default, `--porcelain`
     the same lines. Entries are one line, cut short, with secret-looking values, bearer tokens, token shapes, private keys
     and recovery words replaced; the files are never changed and links in a run are not followed. The redaction is a
     pattern match, which the docs say.
  New tests: `StatusCommandTest` 9, `AgentsLogCommandTest` 4, `AgentRunLogsTest` 10, `ReadOnlyCoordinationServiceTest` 2,
  `LoopbackRunningServerTest` 3, `LogDumpTest` +1. Linux gate: workspace `check` 1101 tests, 0 failures; cli 280 tests with
  two Link scenario tests of `ThreeComputersMainMeetingScenarioTest` timing out at 60 s while the host was overloaded
  (the run took 19 minutes instead of 3) and passing alone; coordination, javadoc, `formatCheck`, `staticAnalysis` and the
  architecture checks of the three modules green on the Windows Gradle run.
  4. Registered under "Coordinate work"; `docs/cli/command-reference.md` (generated list and two new sections) and
     `docs/getting-started/README.md` updated. All output keys are additive; nothing existing changed meaning.

- **Built 2026-10-20 (measurement and executed walkthroughs; source semantics proven on the Linux gate, not yet a real
  run).**
  1. *The predefined task fixture* is checked in at `docs/fixtures/first-task`: `repo/` (a shell function with a
     wrong operator and the one test that exposes it, `sh test.sh`; it needs only `sh`, which Git for Windows ships) and
     `task.txt` ("Make the failing test pass without changing the test"). `FirstTaskFixtureTest` pins the task text,
     that the test fails before the one-line fix, passes after it, and that the test file is unchanged.
  2. *The measuring script* is `scripts/onboarding-measure/measure-onboarding.ps1` (PowerShell 7, Windows or Linux; how
     to run it: `docs/development/build-and-test.md`, "Measuring onboarding"). Per run it times the installer (when
     given), the commands of the getting-started guide's `WALKTHROUGH` block up to the first `provider status`
     (readiness, and the user actions to it), then `agents run` with the fixture's task and the check that the test
     passes on `main` and the test file is unchanged (first task). It records the conditions (machine, OS, network,
     provider, model and effort, harness version, Synesis version) and reports medians over the runs; failed runs and
     runs whose task did not succeed are listed, not averaged. It reads no credential and closes every command's
     standard input. Synesis's own time during the task comes from the MCP server's trace: the one product change is
     additive, in `McpStdioServer`: with the existing opt-in `SYNESIS_MCP_TRACE_FILE` set, each answered request also
     writes `mcp_call method=<m> tool=<name> started_epoch_ms=<ms> duration_micros=<n>` (frame read to answer flushed;
     no arguments or results; a tool name that is not a plain identifier is written as `?`), and nothing changes
     without the variable (`McpStdioServerTraceTest`, 2 tests). Model time comes from Claude Code's transcript, reading
     only each entry's type and timestamp; for Codex it is reported as missing.
  3. *Executed walkthroughs*: `GettingStartedWalkthroughTest` runs the guide's `WALKTHROUGH` block (`init`, `provider
     install claude`, `provider status claude`, `doctor`) through the staged launcher in a disposable Git repository,
     with a stand-in `claude` and a home directory and Link profile of the test's own, and fails on any non-zero exit;
     it skips on Windows, where the stand-in is not a shell script. Another walkthrough joins the same way by marking
     its block.
     Gate results: `:cli:check` 271 tests, 0 failures (2 skipped, both existing) and `repositoryHygieneCheck` green; the
     whole `check` of the other modules, including the whole `mcp` suite, was green in an earlier run whose container
     was lost before `cli` finished; a guide with a command that does not exist fails the walkthrough test.
  4. *CLI responsiveness*: `scripts/linux-gate/cli-startup-baseline.sh`, run as `:cli:cliStartupBaseline`, prints the
     first run, minimum, median, p95 (nearest rank) and maximum of 20 starts of `--help`, `version` and `coordination
     status` on an initialized project. It is not part of `check`; the budget is set from it later.
  - **Not built, and why.** `init`'s sub-steps (git checks, baseline commit, discovery, provider connection, health
    probe) are not timed separately: `init` prints no timestamps and its behaviour belongs to another stage-2 item, so
    the script times the commands from outside. The installer's extraction and activation are one command (`install`
    prints no phase times; the wizard's phase events are not reachable from the command line). "Runtime start" is read
    as the time until `agents run` prints `PROJECT_RUNTIME=`, null when it does not. Whether Claude Code passes
    `SYNESIS_MCP_TRACE_FILE` through to the MCP server it starts is unknown until a real run; the script reports a
    missing trace instead of a zero.
  - **First baseline (conditions stated; not the targets' measurement).** Linux gate, a Podman container on this PC (16
    logical CPUs, WSL2 kernel, Java 25.0.4.1), 20 starts each, two runs, milliseconds (the second while other sessions used
    the PC, so the spread is the noise to budget for): `--help` median 456 and 589, p95 510 and 620; `version` median 402
    and 631, p95 452 and 741; `coordination status` median 458 and 519, p95 485 and 626. Windows, the build
    installed on this PC, a disposable home directory, no agent task, 6 runs in three sessions: `init` about 3 s,
    `provider install` about 2 s, `provider status` about 0.8 s, so readiness through `provider status` took 4.0 to
    6.0 s on a quiet PC and 7.4 to 8.3 s while a Linux gate ran beside it (a median of 5.7 s over the first two), with 4
    user actions (`cd` and the three guide commands) and no prompt, against the target's `cd` and `init`: stage 2's
    `init` is to connect the agent, and the health check is for the script. Time to first task, MCP overhead per call
    and model time need a real signed-in agent and have not been measured.

### Stage 3 — behaviour changes (owner approval first; preliminary: about two days)

- **Tasks.** Interactive approval in `link invite` (who asks; accept? y/N). The firewall fix offered by `link invite`
  and refreshed by the installer on upgrade; the installer's checkbox checked by default. Help in depths (`help` and
  `help --all`). `init` stops writing Codex's global config when Codex is not connected. `host`/`join`/`sync host`/
  `sync join` deprecated, naming `link invite`/`link join` as the replacement. UI first run, readiness panel, operator
  recheck/close and member removal, membership in the read model.
- **Dependencies and blockers.** ADR-0098 step 6 (membership, built) for member views and removal; stage 2's discovery
  and doctor groups for the readiness panel; the firewall offer needs the person's elevation and cannot be tested
  unattended on a real machine.
- **Built 2026-10-20 (the UI parts only: first run, readiness panel, operator recheck and close, member removal,
  membership in the read model; source semantics proven on the Linux gate for the classes named below, not yet a real
  browser run, the whole gate or the Windows gate).** The other stage 3 tasks are not part of this entry.
  1. **Read model** (all additive): `membership` `{shared, owner, self, selfIsOwner, members[{nodeId, self, owner, state,
     since, lastReceived}]}`, built by `ProjectMembers`, the one rule `synesis status` now also follows (`ProjectStatus`
     calls it, so the CLI and the page cannot disagree); `workGroupId` on tasks, ownerships and disputes (the WorkGroup of
     the task's active or trashed lanes when exactly one, else `null`), which the page's coordination tables use when
     present and otherwise keep inferring as in stage 1; `group`, `mark` and `fix` on each diagnostic finding.
     `CoordinationService.membershipProjection()` is new (a read).
  2. **Commands** (`ControlPlaneHttpHandler`): `recheck-operator-requests` and `close-operator-request`
     (`OperatorEndpoints`; the services `operator recheck` and `operator close` use, so a request Synesis raised is still
     refused), and `remove-member` (`ProjectLinkEndpoints` over `ProjectSharing.removeMember`, the steps of `project
     remove-member`: role file, migration, `MembershipService.remove`, `replication.peers`; it needs `confirmed: true`,
     and the owner rule stays the log's). No new authority path; the CLI commands are unchanged and do not yet call
     `ProjectSharing.removeMember`, so the removal steps exist twice until a later change makes the command use it.
  3. **UI.** `ProjectReadiness` (first-run view when no agent and no WorkGroup exist: one line on what Synesis is, the
     checks, the next command; the Readiness rail entry with its attention count; the checks on the overview),
     `OperatorRequests` (recheck, close for a request a lane asked, "closes by itself" for the rest; on the overview as
     "Needs you" and in the Operator tab), `MembersPanel` (connected or away, the owner's Remove with a confirmation) in the
     Network tab, `readiness.ts` (the checks, read from the snapshot alone). Shared presentation moved out of
     `App.tsx` into `ui.tsx` and `format.ts`. "Signed in" is not a check: no read-model field says it.
  4. **Tests.** web-ui: `ProjectOnboarding.test.tsx` (21), `coordinationScope.test.ts` (2), two more in
     `controlPlane.test.ts`; `:web-ui:check` green on the Linux gate (80 tests, typecheck, lint, build).
     `ControlPlaneReadModelTest` (3 new: membership, WorkGroup ids, finding fields) and `ControlPlaneHttpHandlerTest` (5
     new: operator close and recheck, member removal and its refusals, a follower refused). Linux gate: `:workspace:test`
     for `transport.control.*` (91 tests) and `:cli:test` for `command.coordination.*`, `command.status.*`,
     `command.project.*` (78 tests, 2 skipped as before), `:workspace:javadoc`, `formatCheck`, `staticAnalysis`
     and `:coordination:javadoc`, all green. Not run: the whole gate, the Windows gate, a real browser against a running
     runtime (the page was looked at once in the mock preview).
- **Contract changes needing approval.** The installer's firewall default; `init` no longer writing Codex's global
  config without Codex; deprecation of four commands; the default help showing fewer commands; `doctor --strict` using
  exit code 3 and provider results no longer reusing 2 and 3.
- **Automated tests.** The interactive invite accepts on yes and declines on no or end of input; the deprecated commands
  print their replacement and still work; UI tests for the new actions; `init` without Codex leaves `~/.codex`
  untouched.
- **Real-world tests.** A second computer joins with one action per side and one approval on the same network, counted;
  an upgrade keeps Link reachable without a manual firewall step.
- **Completion criteria.** The owner's approval recorded for each contract change; the join count met on PC + VM;
  nothing accepted without a person's yes.
- **Built 2026-10-20 (the joining parts only: interactive approval, the firewall `FIX=`, the installer default; source
  semantics proven on the Linux gate and `go test ./...`, not yet a real PC + VM run, and the UAC prompt never run).**
  1. *Interactive approval.* After `link invite` reports `LINK_STATE=CONNECTED` for a project invitation, at a terminal
     (`Terminal.interactive()`) it looks for the join request of that node for a few polls (the session is usable a moment
     before the runtime lists the request), prints `JOIN_REQUEST=<node> REQUESTED_AT= EXPIRES_AT=` and asks
     "`<node> wants to join <project folder>. Accept? [y/N]`". Only `y` or `yes` accepts; no, a blank line, anything else
     and the end of input decline. The answer goes through `LinkDecideCommand.decide`, the one call `link accept` and
     `link decline` make, so there is no second authority path; a runtime refusal (for example an expired request) is
     reported as for those commands. In a script (no terminal, `--porcelain`), for a plain invitation and with
     `--wait-seconds 0` nothing is asked and the output is as before. The request carries no computer name, only the
     node ID and times, so those are what the question shows; a name needs the join request to carry one, which it does
     not.
  2. *The firewall fix.* `link invite` prints `FIX=in an administrator terminal, run: netsh advfirewall firewall add|set
     rule ...` after `LINK_FIREWALL=MISSING|STALE` and its `NOTE=` (the command moved from the end of the `NOTE=` to its
     own `FIX=` line). Synesis does not run it or ask for elevation.
  3. *The installer.* The wizard's "Let other computers connect to this one (asks for administrator approval)" checkbox
     starts ticked (it was ticked only when the rule already existed), so every wizard install or update that keeps it
     ticked adds the rule again for the new version's `java.exe`; Windows still shows its prompt and declining leaves the
     install complete with a warning. A command-line install, repair or update still needs `--allow-link` and otherwise
     prints `LINK_FIREWALL=STALE` and its fix, because an unattended update cannot answer a prompt.
  4. *Tests.* `LinkCommandsTest` (yes variants accept; no, blank, other text and end of input decline; script mode, no
     join request and a plain invitation ask nothing; a refused decision is reported), `LinkFirewallRuleTest` (the
     `FIX=` line), `bootstrap/internal/wizard` (`TestTheFirewallRuleIsTickedByDefault`). Linux gate: cli 311 tests, 0
     failures, 2 skipped; link 201, coordination 562, project-record 60, relay 12, mcp-contract 20, workspace 1114
     (3 skipped), mcp 214; `formatCheck`, `staticAnalysis` and `javadoc` pass. `repositoryHygieneCheck` fails on a
     predating link, `docs/getting-started/README.md` to `../cli/command-reference.md#status` (the check does not strip
     the `#fragment`); not part of this entry. `go test ./...` in `bootstrap` passes.
  5. *Needs a real machine with elevation.* The UAC prompt after a ticked install, that the rule then names the new
     version's `java.exe` and an upgrade keeps Link reachable, the printed `netsh` command run in an administrator
     terminal, and the one-action-per-side join count on PC + VM with the question answered by a person.
- **Built 2026-10-20 (CLI surface: removal of the four commands, help in depths, exit codes; source semantics proven on
  the Linux gate, `:cli:check` 318 tests and the workspace `*Provider*` and `ProjectContextExchangeTest` classes 110, 0
  failures, plus `repositoryHygieneCheck`; not yet the Windows gate or a real run).** The rest of stage 3 (interactive
  approval in `link invite`, the firewall offer, the installer default, the web UI) is not part of this entry.
  1. **Removed, not deprecated (the owner's decision 4).** `host`, `join`, `sync host`, `sync join` and the `sync` parent
     are unknown commands (exit 2). Deleted: `HostCommand`, `JoinCommand`, `SyncCommand`, `SyncHostCommand`,
     `SyncJoinCommand` (`cli.command.sync`), `SyncApplicationService` (workspace) and `CliRuntime.syncService()`, which
     only they used; the tests that ran them, `UnifiedCliSyncProcessTest` and `GeneratedOnboardingTest`; the obsolete
     `docs/demo/FIRST_TWO_PERSON_PROJECT_DEMO.md` (it also described `decision` commands that do not exist); the installed
     Link probe of `scripts/maximum-release-acceptance.ps1` (it ran `sync host`/`sync join`; Link is covered by the PC + VM
     runs and the test suites, which `docs/release/protected-acceptance.md` now says). `FailureMapper`'s texts (still used
     by `identity show`) name `link answer` and the browser's router option instead of `host --open-router-port`;
     `TWO_MACHINE_TESTING.md`, `SYNESIS_LINK_V1.md`, `package-boundaries.md` and the command reference are migrated.
     `ProjectContextExchange` stays (the runtime owner uses it). **Consequence to know:** asking the router for a port
     (UPnP) was `host --open-router-port`; `link invite` has no such option, so it is now the browser's invitation
     checkbox only. **Left, then without a production caller:** the `org.synesis.projectrecord.sync` protocol classes
     (`ProjectRecordSync`, `ProjectReconciliationSync`, `ReconciliationClient`, `ReconciliationHost`, `InventorySnapshot`,
     their `protocol` messages and tests) and `Onboarding.hostCompact`/`joinOnce`; deleted in stage 4 (see "Done 2026-10-26").
  2. **Help in depths (decision 5).** `synesis`, `synesis --help` and `synesis help` list the everyday commands
     (`init`, `status`, `ui`, `agents`, `link`, `doctor`, `help`) and name `synesis help --all`; `synesis help --all`
     and `synesis --help --all` list every command by group, as before without `host`, `join` and `sync`. Both are views
     of the one command tree (`SynesisCli.EVERYDAY_COMMANDS`); every command still runs. New tests in
     `SynesisCliParsingTest`: short help lists exactly the everyday set, `help --all` lists every root command with its
     group, every root command answers `--help`, the removed commands are unknown.
  3. **Exit codes (decision 6).** `doctor --strict` exits 3 (`DOCTOR_BROKEN`, was 10). Provider results stop reusing 2
     and 3: an unknown provider or a missing user-level entry is 20 (`PROVIDER_UNAVAILABLE`, was 2), a broken
     integration status is 21 (`PROVIDER_BROKEN`, was 3); `provider` option mistakes stay 2 (usage). The codes live in
     `ExitCodes` (the provider ones defined by `ProviderApplicationService` and carried by `ExitCodes`), the one table is
     in `docs/cli/command-reference.md`, and `CommandReferenceTest` fails when the table and `ExitCodes` differ.
     Not changed: a provider status whose synthetic check fails still exits 1 although it reports `BROKEN`.

### Stage 4 — needs foundations first

- **Tasks.** A person sends an agent a task after it started ("ready without a task"), which needs a person-to-agent
  message path (conversations are read-only for people today) and a decision on what such a message may authorize.
  Starting and stopping agents from the UI through a daemon-side runner shared with `agents run`. OS packages (winget,
  Homebrew, apt) with signed release manifests (the manifest key is a placeholder today, `bootstrap/main.go:117-119`).
  Removal of the deprecated commands and old error keys. AppCDS if the start-time measurement asks for it. Adapters for
  further agent harnesses.
- **Dependencies and blockers.** Each needs its own design and approval; release signing needs a key the owner holds.
- **Completion criteria.** Set in its own ADR or amendment.
- **Status on 2026-10-21 (designer).** Stages 1 to 3 are built. Of stage 4:
  - **Done or not needed:** removal of the old commands happened in stage 3 (decision 4). AppCDS is not needed: the
    measured baseline is `--help` about 0.45 s median and `coordination status` about 0.46 s on the Linux gate (stage 2
    measurement), so no start-time budget is missed yet. The old error keys stay until a separate, authorized removal.
  - **Needs the owner before it is built:** (a) "ready without a task" — deferred by decision 7; it needs a person-to-agent
    message path and a rule for what such a message may authorize (never review, merge or authority). (b) Starting and
    stopping agents from the UI — a daemon-side runner shared with `agents run`; it gives the browser page the power to
    start processes, so the authority of that route must be decided. (c) OS packages with signed manifests — needs a
    release signing key only the owner holds (`bootstrap/main.go`'s manifest key is a placeholder). (d) Adapters for
    further harnesses — which ones.
  - **OS packages, built without the owner's key or accounts (2026-10-26):** `packaging/` holds winget, Homebrew and
    Debian templates; `scripts/release-packages/render-packages.sh` fills versions and SHA-256 values from the release files and
    writes the release `manifest.json`; the `release-artifacts` workflow now builds a `v*` tag at the tag's version,
    publishes the platform archives, and runs a `package-release` job that signs the manifest only when the
    `SYNESIS_MANIFEST_PRIVATE_KEY_B64` secret exists (it is absent today, so the job says so and publishes no manifest).
    The installer refuses an unsigned manifest, even a `developmentOnly` one, as soon as a key other than the
    placeholder is pinned; with the placeholder pinned behaviour is unchanged. Tests: Go tests for `Verify`, the
    placeholder rule and the signer, and a shell test of the renderer. **Left for the owner:** create the Ed25519 key,
    store the secret, pin the public key in `bootstrap/main.go`, and register winget, a Homebrew tap and an apt
    repository; the winget silent switch, the formula and the `.deb` were not run. Steps are in
    `docs/installation/packages.md`.
  - **Done 2026-10-26 (owner: "lets do all"):** deleted the `org.synesis.projectrecord.sync` package of `:project-record`
    (`ProjectRecordSync`, `ProjectReconciliationSync`, `ReconciliationClient`, `ReconciliationHost`, `InventorySnapshot`,
    the `protocol` messages and their tests) and `Onboarding.hostCompact` / `joinOnce`, which had no production caller after
    decision 4. Every other class of both modules stays: `link`, the runtime owner and `ProjectContextExchange` do not use
    them. The record format and storage stay in `:project-record`.

### Stage 4 amendment (2026-10-26): ready without a task, and agents started from the page

The owner asked on 2026-10-26 (USER-STATED) for both items that the status above left to the owner, (a) and (b). The
designer (the agent working under the owner) took the two decisions below and records them here before the build, as
the stage 4 text asks ("Set in its own ADR or amendment"). Claims are marked VERIFIED against the source at commit
`5da9a0e6`, or DESIGNED for what this amendment decides.

#### What exists today (VERIFIED)

- `synesis agents run` runs `AgentRunController` in the foreground of the CLI process. A headless agent needs a first
  prompt (`claude -p`, `codex exec`); the controller starts each agent with the one prompt and resumes a stopped agent
  for reasons it reads from the coordination log (`wakeFor`), among them a message addressed to the lane the agent holds.
- Conversations are lane-to-lane and a person only reads them (`ConversationsCommand`; ADR-0093). An agent that has not
  started has no lane, so a conversation message cannot reach it.
- The page's commands (`/api/v1/commands/<name>`) are POSTs under a browser session opened with the terminal bootstrap
  token, loopback only, with the CSRF header (`ControlPlaneHttpHandler.route`). None starts a process.

#### Decision A: a task given later is text for the agents and carries no authority (designer decision)

1. **The rule.** A message from a person to the agents can only give the task or replace the task text. It carries no
   authority: it never grants a claim, never approves, skips or decides a review, never merges, never changes policy,
   never closes an operator request, and never ends or releases anything an agent holds. The agent that receives it goes
   through Synesis exactly as an agent given its task at start: it announces work, claims, asks for review and integrates
   through the same tools and gates, and those refuse what they refuse.
2. **How the rule is kept: by construction, not by checking the text.** The message is not an entry of the coordination
   log. It is a UTF-8 text file in `agent-runs/<project>/tasks/` of the application state, beside the run logs, written
   atomically (`AgentTaskInbox`). Nothing in coordination, the MCP server or the gates reads that directory; the only
   reader is `AgentRunController`, which hands the text to an agent as its prompt on standard input. A message that says
   "skip review and merge everything" is therefore a sentence the agent reads, as it could read it in `--prompt`, and
   changes nothing a gate looks at. Because it is not in the log it is also never replicated to another computer, which
   is the safe default for text that could otherwise look like an instruction from the owner.
3. **Why not a conversation message.** A conversation message needs a lane to address, and an agent waiting for its first
   task has none. A log entry would also be replicated and replayed, and would have to be given a meaning in the event
   model (a published contract); a wrong reading of it would be an authority bug. A file the log never reads cannot be one.
   The read-only rule for people on conversations (ADR-0093) is unchanged.
4. **What it does to agents.** Agents registered to wait have no process (below), so the first task starts them with the
   task as the start prompt, exactly as `--prompt` does. A later task, to agents that already worked, resumes each stopped
   agent through its own provider session with a sentence that says the task replaces the earlier one and grants nothing
   else, then the task text (`NEW_TASK_PREFIX`); an agent whose process is running gets it when the process ends. A task
   given before a run started is not that run's. It does not cancel claims: what the agent holds stays held until the
   agent releases it through Synesis, and a lane it cannot continue is recovered by the ordinary path (ADR-0099).
5. **Starting without a task.** `agents run --wait-for-task` (with no `--prompt` or `--prompt-file`; both together is
   `ERROR=WAIT_FOR_TASK_CONFLICT`, exit 2) checks the same things as a normal start (providers installed, runtime), starts
   no process, and prints `AGENT_READY=<agent>` for each agent and `AGENT_RUN_WAITING=TASK_WANTED`. No process is started
   because a headless provider needs a prompt and Synesis does not invent one (the rule of decision 6 stands); a waiting
   process would also spend model turns on nothing. "Ready" therefore means: registered, provider installed, and starts
   at once when a task arrives. A run that is given no task ends `TIMED_OUT` at `--timeout-minutes` and never reports
   `COMPLETED`. Plain `agents run` with no task still refuses with `ERROR=TASK_REQUIRED` (and asks at a terminal), unchanged.
6. **Giving the task.** `synesis agents task <text>` or `--file <file>` (CLI), "Give the agents a task" and the start
   panel (page). Blank text, text and `--file` together or neither, or more than 20,000 characters is
   `ERROR=AGENT_TASK_INVALID` (exit 2). A task goes to every run of the project that listens, as a prompt goes to every
   agent of a run: the foreground `agents run` of the CLI and the runs the page started.

#### Decision B: agents are started and stopped from the page through one runner (designer decision)

1. **One runner.** `AgentRunner` (workspace) is the only code that turns `provider[:model[:effort]]` into an agent the
   project may run (provider known, headless mode, installed in this project, hook launcher), builds the controller and
   runs it. `agents run` calls it in the foreground of the CLI process, as before; the project runtime's control plane
   calls the same code on a background thread for the page. There is no second parser, no second installed-provider
   check and no second controller wiring. (`agents run` itself does not move into the runtime: it stays a foreground
   command whose exit code reports the outcome. Moving it is a separate change and is not needed for the page.)
2. **Authority of the route.** The same loopback authority every command of the page has: a browser session opened with
   the terminal bootstrap token, the CSRF header, loopback `Host` and `Origin` only. Nothing is added to widen it and
   nothing about it changes. On top of that the request is narrowed so the page cannot start anything but the product's
   own agents:
   - it can name only providers that `ProviderApplicationService` lists as `INSTALLED` in this project, and each one's
     model and effort as plain text (64 characters, no `:`), and a task or "wait for a task". It cannot name a command
     line, a directory, an environment variable, a flag or a path: those come from the provider's headless contract;
   - at most 8 agents in one start and 4 live sets of agents in one runtime;
   - `stop-agents` reaches only runs this runtime started (by the identifier it returned); it stops their processes and
     ends their connections through the controller's own `finally`. It cannot stop an `agents run` of the CLI (that stops
     with Ctrl-C) or an agent the person started in a terminal;
   - closing the runtime stops every run it started;
   - the page is told each run's state and its last lines, not the agents' logs; `synesis agents log` reads those.
3. **Failures.** A provider that is not installed is `409 PROVIDER_NOT_INSTALLED`; an unknown provider, a malformed field,
   a prompt together with "wait", neither, or a blank task is `400`; too many live runs is `409 AGENT_RUN_LIMIT`; a stop for
   a run this runtime did not start is `404`. Nothing is started by a refused request.

#### Published contracts (additive)

New: CLI `agents task`, option `--wait-for-task`, outputs `AGENT_READY`, `AGENT_TASK_SENT`, wait reason `TASK_WANTED`,
error codes `WAIT_FOR_TASK_CONFLICT` and `AGENT_TASK_INVALID`; page commands `start-agents`, `stop-agents` and
`send-agent-task`; the snapshot field `agentRuns` (a client that ignores it is unaffected, and the web UI treats its
absence as empty). Java: `AgentRunController.Request` gains a seventh component beside a six-argument constructor with
the old meaning, `AgentRunner` and `AgentTaskInbox` are new. Unchanged: `TASK_REQUIRED` and its text, the exit codes,
every existing key, the coordination event model and the MCP tools.

#### Built (2026-10-26)

`AgentTaskInbox`, `AgentRunner` and the `AgentRunController` changes (agents registered to wait, `TASK_WANTED`, the
new-task resume, `launch` and `resume` shared by the start, the wake and the task paths) in the workspace module;
`AgentsTaskCommand`, `agents run --wait-for-task` and the two error codes in the CLI, with `agents run` now calling
`AgentRunner`; `AgentEndpoints` and the `agentRuns` snapshot field in the control plane; `AgentRunPanel` on the
agents view of the page. New tests (29): `AgentRunControllerTest` 6, `AgentTaskInboxTest` 4, `AgentRunnerTest` 4,
`ControlPlaneHttpHandlerTest` 2 (start and stop on installed providers only with the page's own session and CSRF
token; a task that claims authority leaves the log's head and the project's snapshot as they were),
`AgentsRunCommandTest` 2, `AgentsTaskCommandTest` 3, and in `web-ui` `AgentRunPanel.test.tsx` 6 and
`controlPlane.test.ts` 2. Source semantics proven by those tests; no real provider was run.

#### Limits recorded

- Tasks are kept for the life of the application state and never pruned; a person gives few.
- Two ids made in the same millisecond order by their random part; the later of two such tasks may be either.
- Not verified here: a real run of the daemon-side runner with a real provider (the route tests start a run that is
  waiting for a task, which spawns no process); the controller's connections and session leases were written for the CLI
  process and are used unchanged inside the runtime process.

- **Real runs 2026-10-26: D1, D3, D4, D5, D6, D7, D8, D9 fixed** (defects of the stage 2 and 3 real runs; D2, D10 to D13
  are information or lab findings and need no change).
  - D1: discovery takes the version from the first line that looks like a version (digits, a dot, digits), and Codex's
    sign-in answer from its last non-blank line, so a warning printed first is neither.
  - D3: `init` prints one coherent set of next steps (none found: install one; several found in a script: `--agent a,b`
    or `--no-agents`; no stray "connect an agent" or "start the agent" line), ending with `synesis status`.
  - D4: `init --agent <id>` for an agent that is not on the PATH ends `AGENT_<ID>_CONNECT=FAILED` with
    `ERROR=AGENT_NOT_INSTALLED`, MESSAGE and FIX, exit 10, and writes nothing of that agent.
  - D5: the usage error for the removed `host`, `join` and `sync` adds `use synesis link invite / link join`.
  - D6: the default `doctor` report prints the `project` group like the others (`PROJECT_STATUS`, and per finding code,
    mark, message, fix); the JSON `groups` map gains `project`.
  - D7: the decision is that a script names the agent (decision 2 already says so). The getting-started walkthrough and so
    `measure-onboarding.ps1` run `synesis init --agent claude` (the script swaps in `-Agent`); `GettingStartedWalkthroughTest`
    now has both agents on its PATH. A plain script `init` on a two-agent computer still connects none and says why.
  - D8: every daemon answer carries the daemon's build (version, commit, build time). A command that meets a daemon of
    another build, or of one that names none, refuses with `ERROR=DAEMON_BUILD_MISMATCH`, MESSAGE and FIX (end process N);
    it never restarts or uses it. Where the refusal is printed: `ui`, the `link` commands; `agents run` shows it as
    `REASON=daemon_build_mismatch`.
  - D9: every JVM Synesis starts gets `-XX:ErrorFile=<state folder>/Synesis/crash/hs_err_pid%p.log`: the child JVMs
    (`CliChildJvm`: daemon and runtime), the installed `synesis`/`synesis.cmd` launchers, the native MCP launcher and the
    protocol activation helper. Gradle's generated development scripts (`installDist`) do not set it.

## Acceptance tests and targets

### Two onboarding times, measured apart

| Measure | From → to | Target |
| --- | --- | --- |
| **Time to agent readiness** | installer started → `synesis init` done → the chosen agent connected and passing its health check (`provider status` `HEALTHY`; for an interactive agent, its MCP `initialize` and `tools/list` answered) | Synesis's own part ≤ 2 min (REQUIRES-MEASUREMENT; baseline first) |
| **Time to first successful task** | installer started → a predefined task done and verified | ≤ 5 min under normal conditions, median of 5 runs (REQUIRES-MEASUREMENT) |

- **The predefined task** is fixed and reproducible: a disposable fixture repository with one failing test; the task text
  is given explicitly ("Make the failing test pass without changing the test"); it is done when the test passes on
  `main` and the change was reviewed where review is required. The fixture and the task text are checked in, so every
  run measures the same work.
- **Synesis-controlled overhead is measured separately from external latency.** Every run records timestamps for the
  installer's extraction and activation; `init` (git checks, baseline commit, discovery, provider connection, health
  probe); runtime start; and, during the task, the time Synesis spends between an agent's tool calls (MCP request in to
  answer out). External time is recorded on its own: the person's sign-in (excluded from the targets, reported), network
  and provider availability (a run during a provider outage is reported, not averaged in), and model response time (from
  the harness's own transcript timings).
- **Conditions are recorded** with each result: machine, OS, network, provider, model and effort, harness version.

### Other measures

| Measure | Target | How |
| --- | --- | --- |
| User actions to readiness | ≤ 3: run the installer, `cd`, `synesis init` (+1 answer when several agents are found) | the measuring script counts commands and prompts |
| Mandatory decisions | ≤ 1 (which agents, only when several are found) | discovery fixtures with 0, 1, 2 agents |
| Second computer joins | ≤ 1 action per side + 1 approval (same network) | real PC + VM run, counted |
| Setup failure messages | 100 % print `ERROR`, `MESSAGE`, `FIX` | a test over every refusal path of init, provider, link and agents run |
| Interrupted setup | re-running `init` after a kill at each step ends in the same state | fault-injection test in the Linux gate |
| CLI responsiveness | `--help` and `status` p95 under a budget set from the measured baseline | timed in the gate on this PC and the VM |
| Docs accuracy | every walkthrough runs green | executed fixtures |
| Credentials | no output, log or diagnostic contains a token file's content; discovery opens no credential file | planted-token test |
| Files touched by `init` | exactly the documented list, project and global | file-system snapshot test |
| No invented work | `agents run` without a task starts no agent; nothing Synesis sends an agent is derived from the README | a test over `agents run` and the provider prompt builders |
| Cross-platform | readiness and first task on Windows and Linux (macOS when a Mac is available) | real runs |

## Security, compatibility and maintenance risks

- **Credentials.** Discovery must never read token files; sign-in comes only from the provider's own status command.
  A planted-token test guards it.
- **Trust.** Joins stay approved by a person; the firewall fix stays an elevated prompt the person approves;
  `doctor --fix` never deletes work or changes security settings.
- **Published contracts.** New error keys are added beside the old for a release; deprecated commands print their
  replacement; the exit-code table is documented; removals are separate authorized changes.
- **Prompts in scripts.** Every prompt appears only on an interactive terminal and has a flag; `--porcelain`,
  `SYNESIS_OUTPUT=porcelain` or a non-TTY never prompt.
- **Global files.** `init` and `provider install` list what they write outside the project; the Codex global write
  without Codex stops.
- **Discovery drift.** Providers' CLIs change flags; discovery per provider lives in its `ProviderIntegration`, with a
  test per supported version, and degrades to "could not tell".
- **Maintenance.** The command reference is generated and the walkthroughs are executed, so docs cannot silently rot.

## Consequences

- A newcomer runs three commands and answers at most one question before an agent is ready; it works on what the
  person tells it, never on a task Synesis made up.
- One status answer in the CLI and the UI, from one read model.
- Errors say what happened and what to do.
- More of the CLI's surface is behind `--all`, none of it removed in this ADR.
- Two new commands (`status`, `agents log`); everything else is existing commands made clearer.

## Decisions for the owner

Each is needed before the stage named; the designer's recommendation comes first.

1. **Stage 2 — new CLI output and commands (additive).** Add `ERROR`/`MESSAGE`/`FIX` beside today's keys, `STATUS_*`
   keys, `synesis status`, `synesis agents log`, `init --agent/--no-agents/--git-init`, and new doctor finding codes.
   Recommended: yes; nothing existing changes meaning.
2. **Stage 3 — `init` stops writing Codex's global config when Codex is not connected.** A project initialized without
   Codex then has no Codex MCP entry; existing entries are left alone. Recommended: yes; it stops changing configuration
   the user did not ask to touch.
3. **Stage 3 — the installer's "Let other computers connect" checkbox checked by default**, still needing the person's
   elevation. Recommended: yes; joining without it needs pasted answer links and fails to redial after long drops
   (MEASURED 2026-10-18).
4. **Stage 3 — deprecate `host`, `join`, `sync host`, `sync join`** in favour of `link invite`/`link join`; they keep
   working and name their replacement; removal is a later, separately authorized change. Recommended: yes.
5. **Stage 3 — default help shows the everyday commands; `help --all` shows everything.** Recommended: yes.
6. **Stage 3 — exit codes:** `doctor --strict` uses 3 (`DOCTOR_BROKEN`) instead of 10; provider results stop reusing 2
   and 3. Recommended: yes, documented in the exit-code table.
7. **Stage 4 — "ready without a task":** whether a person may send a started agent its task later, which needs a
   person-to-agent message path and a rule for what such a message may authorize (today people cannot post into a
   conversation). Recommended: decide when stage 4 starts; not needed for stages 1 to 3.
8. **When to offer `project share`** (owner key and recovery words): on first run or at the first invite. Recommended:
   at the first invite, so a single-computer user never sees it.

Still open, settled by measurement rather than by a choice: the CLI start-time budget and the readiness target for
Synesis's own overhead (both after the baseline is measured), and which further harnesses get adapters first.
