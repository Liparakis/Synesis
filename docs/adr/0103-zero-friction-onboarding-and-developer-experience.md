# ADR-0103 — Zero-friction onboarding and developer experience

## Status

Proposed on 2026-10-19, at the owner's request (USER-STATED: "Powerful by default. Simple by design. Complexity only
when requested."). Nothing here is built. It changes published CLI output and deprecates commands, so the items marked
**contract change** need the owner's approval before they are built (AGENTS.md, "Public contract stability").

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
3. Start the agent the usual way (`claude`, `codex`) or let Synesis start several (`synesis agents run`).
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

- `synesis agents run` keeps its contract and becomes the way to start several agents. It defaults `--prompt` to "work
  on this project's README goal" when the README has one (DERIVED from every real run of 2026-10-18, which used the same
  prompt), so `synesis agents run --agent claude` is a complete command.
- **Logs:** `agents run` prints where each agent's transcript is, and `synesis agents log <label>` tails it (the one
  other new command; today the logs exist outside the project with no command to read them,
  `AgentsRunCommand.java:180`).
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
3. **UI-first onboarding, CLI unchanged.** Rejected: half of the users never open the UI, and scripts need the CLI path;
   both are thin over one service instead.
4. **Hide the expert commands entirely.** Rejected: the owner asked for complexity on request, not removed; `help --all`
   keeps them one flag away.
5. **Auto-accept joins from computers on the same network.** Rejected: a join is a trust decision (ADR-0098, owner
   approves each join); the approval moves to the invite screen instead.
6. **A Synesis-run account or relay for onboarding.** Rejected: no mandatory service, free and open-source (ADR-0097).

## Module responsibilities

| Module | Gains | Does not gain |
| --- | --- | --- |
| `workspace` | environment discovery (agents on `PATH`, versions, provider sign-in checks), as findings of the existing `DoctorService` and inputs to `ProjectApplicationService.init`; the status summary as a view over `ControlPlaneReadModel` | a second read model; any credential reading |
| `cli` | `init` prompts and flags, `status`, `agents log`, error shape, help depths, interactive accept in `link invite`, `synesis mcp` starting the runtime | business rules (they stay in workspace/coordination) |
| `web-ui` | first run, readiness panel, operator and member actions, the two defects | coordination rules |
| `bootstrap` | wizard next steps, firewall default, rule refreshed on upgrade | provider setup |
| `coordination`, `link`, `mcp` | nothing new | |

No new module and no new interface type: discovery is a set of doctor findings and init inputs; status is a renderer.

## Build order (each stage ships on its own)

**Stage 1 — immediately implementable (no contract change; about one to two days):**
README; quickstart; `init` next step; `provider install` real error causes; doctor launcher path; `link` and
`conversations` in their help groups; generated command reference; stale docs; the two UI defects and the overview empty
state; `agents run` default prompt; `synesis mcp` starts the runtime.

**Stage 2 — discovery and visibility (additive contract changes; about two to three days):**
agent discovery in `init` and `doctor`; `init --agent/--no-agents/--git-init`; `status` (+ `--porcelain`, `--watch`);
doctor environment and network groups; `agents log`; the error shape added beside the old keys; executed walkthroughs in
the Linux gate.

**Stage 3 — flows that change behaviour (owner approval; about two days):**
interactive accept in `link invite`; firewall offer in `link invite` and refreshed on upgrade; installer default;
`help` depths; init stops writing Codex's global config when Codex is not connected; deprecation of `host`/`join`/`sync`;
UI first run, readiness panel, operator and member actions.

**Stage 4 — needs foundations first:**
starting and stopping agents from the UI (needs a daemon-side agent runner shared with `agents run`); OS packages
(winget, Homebrew, apt) with signed release manifests (the manifest key is a placeholder today,
`bootstrap/main.go:117-119`); removal of the deprecated commands and old error keys; AppCDS if the measurement asks for
it; adapters for further agents beside Claude and Codex.

Stages 1 and 2 depend on nothing else; stage 3's interactive accept depends on ADR-0098 step 6 (built); stage 4's UI
agent start depends on stage 2's status read model.

## Acceptance tests and targets

| Measure | Target | How |
| --- | --- | --- |
| Install → first agent task done | ≤ 5 min, median of 5 runs (REQUIRES-MEASUREMENT; baseline first) | scripted on a fresh Windows user and a fresh VM user, real Claude Code and Codex, the README goal of the lab |
| User actions to first task | ≤ 4: run installer, `cd`, `synesis init` (+1 answer), start the agent | the same script counts commands and prompts |
| Mandatory decisions | ≤ 1 (which agents, only when several are found) | test over discovery fixtures: 0, 1, 2 agents |
| Second computer joins | ≤ 1 action per side + 1 approval (same network) | real PC + VM run, counted |
| Setup failure messages | 100 % of setup failures print `ERROR`, `MESSAGE`, `FIX` | a test enumerating every refusal path of init, provider, link |
| Interrupted setup | re-running `init` after a kill at each step ends in the same state | fault-injection test in the Linux gate |
| CLI responsiveness | `--help` and `status` p95 under a budget set from the measured baseline | timed in the gate on this PC and the VM |
| Docs accuracy | every walkthrough runs green in the gate | executed fixtures |
| Credentials | no command output, log or diagnostic contains a token file's content; discovery opens no credential file | a test with planted token files, grepping all output |
| Files touched by `init` | exactly the documented list, project and global | snapshot test of the file system before and after |
| Cross-platform | the five-step path on Windows and Linux (macOS when a Mac is available) | real runs |

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

- A newcomer runs three commands and answers at most one question before an agent works.
- One status answer in the CLI and the UI, from one read model.
- Errors say what happened and what to do.
- More of the CLI's surface is behind `--all`, none of it removed in this ADR.
- Two new commands (`status`, `agents log`); everything else is existing commands made clearer.

## Not decided

- Whether interactive `init` should also offer `project share` (the owner key and recovery words) on first run, or only
  when the first invite is made (ASSUMED: at the first invite, so a single-computer user never sees it).
- The exact CLI start-time budget, until it is measured.
- Which further agent harnesses get adapters first (ASSUMED: whichever the owner uses next; none is mandatory).
