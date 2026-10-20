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
`link invite`/`link join` are the one way to join), 5 yes, 6 yes, 7 deferred to stage 4, 8 at the first invite.
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

### Stage 2 — discovery and visibility (additive contract changes; preliminary: two to three days)

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

### Stage 3 — behaviour changes (owner approval first; preliminary: about two days)

- **Tasks.** Interactive approval in `link invite` (who asks; accept? y/N). The firewall fix offered by `link invite`
  and refreshed by the installer on upgrade; the installer's checkbox checked by default. Help in depths (`help` and
  `help --all`). `init` stops writing Codex's global config when Codex is not connected. `host`/`join`/`sync host`/
  `sync join` deprecated, naming `link invite`/`link join` as the replacement. UI first run, readiness panel, operator
  recheck/close and member removal, membership in the read model.
- **Dependencies and blockers.** ADR-0098 step 6 (membership, built) for member views and removal; stage 2's discovery
  and doctor groups for the readiness panel; the firewall offer needs the person's elevation and cannot be tested
  unattended on a real machine.
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

### Stage 4 — needs foundations first

- **Tasks.** A person sends an agent a task after it started ("ready without a task"), which needs a person-to-agent
  message path (conversations are read-only for people today) and a decision on what such a message may authorize.
  Starting and stopping agents from the UI through a daemon-side runner shared with `agents run`. OS packages (winget,
  Homebrew, apt) with signed release manifests (the manifest key is a placeholder today, `bootstrap/main.go:117-119`).
  Removal of the deprecated commands and old error keys. AppCDS if the start-time measurement asks for it. Adapters for
  further agent harnesses.
- **Dependencies and blockers.** Each needs its own design and approval; release signing needs a key the owner holds.
- **Completion criteria.** Set in its own ADR or amendment.

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
