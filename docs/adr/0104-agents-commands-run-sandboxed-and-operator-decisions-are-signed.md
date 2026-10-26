# ADR-0104 — Agents' commands run in a sandbox, and operator decisions are signed

## Status

Proposed on 2026-10-26, at the owner's request. Nothing here is built. The owner decided the three points under
"Decisions made by the owner" the same day (USER-STATED); every other choice is the designer's and is marked so, open
to the owner. It extends ADR-0089 (review policy), ADR-0091 (operator requests), ADR-0094 (validation gates) and
ADR-0098 (membership entries signed by the owner). It closes the gap the owner parked on 2026-10-18 ("agents can bypass
the gate via `run_command`; fix properly later, once the product works end to end"; memory note
"Operator authorization deferred").

**Accepted on 2026-10-26 by the repository owner**, with four more answers to the design's open questions
(USER-STATED): (1) **a separate operator key** signs owner decisions — kept apart from the node key, protected by a
passphrase a person types, used only by commands a person runs; the node key stays for the background server and still
verifies older owner entries (an additive change to ADR-0098's "the owner key is the node key"); (2) **Windows uses a
low-integrity process**, no admin prompts, with Synesis's keys and state marked unreadable to low integrity
(no-read-up), not AppContainer; (3) **no Mac is available**: the macOS sandbox is written from the documentation, marked
UNVERIFIED, and `run_command` on macOS refuses until it is tested (fail closed); (4) **agents lose `git commit` in
lanes** (Synesis builds lane snapshots itself), and `synesis project validation` stops printing
`PROJECT_FILE_CHANGED`/`PROJECT_FILE_COMMITTED` (the owner did not choose to keep them). Implementation is authorized.

Claims are marked VERIFIED (read in code on branch `adr-0098-step3-code-over-link`, tip `c6c2c424`), USER-STATED,
DERIVED (follows from verified facts), or ASSUMED (needs a spike or a test before it is relied on). Every ASSUMED claim
that a stage depends on has a spike task in that stage.

Mode: evolution of the existing system. Published contracts grow additively (see "Published contracts").

## In plain words

An agent can ask Synesis to run a command (`run_command`). Today Synesis starts that command as you, in the agent's
own work folder, with nothing else holding it back. The command can therefore do what you can do: run
`synesis project validation clear` to switch the test gate off, edit `.synesis/project.json`, move `main` with Git,
or read the file that holds this computer's signing key. Synesis' own rule ("an agent may not write under `.synesis`")
is only checked in the tools that write files; `run_command` goes around it.

This ADR does two things.

1. **Agents' commands run inside an operating-system sandbox.** The sandbox lets a command read and write its own work
   folder and its own temporary folder, and read the tools it needs (compilers, Git, Java, Node). It does not let the
   command write the project's main folder, `.synesis`, other agents' work folders, or Synesis's own saved state, and
   it does not let it read Synesis's keys. The network stays open. Linux uses bubblewrap, Windows an AppContainer
   process, macOS `sandbox-exec`.
2. **Decisions that belong to a person are signed entries in the project's log.** The test gate (on, off, which
   command), whether review is mandatory, whether commands may run without a sandbox, and the like, are written by
   `synesis` commands you run in your own terminal. They are signed with your computer's key, which the sandbox keeps
   away from agents. Integration and every other gate read the decision from the log. Editing `project.json`, or
   committing to it, then changes nothing.

If a computer has no sandbox, `run_command` refuses and tells you how to install one. You can allow unsandboxed
commands for a project, but only as one of those signed decisions.

What this does not do: it does not stop you, or an agent you started yourself in your own shell, from doing anything
you can do. It stops the agents Synesis starts and the commands they ask Synesis to run.

## Decisions made by the owner (USER-STATED, 2026-10-26)

1. **Sandbox and signed decisions.** `run_command` runs in an OS sandbox: Linux bubblewrap, Windows a low-privilege
   process (the designer decides which kind, below), macOS `sandbox-exec`. Inside: read and write the agent's own
   worktree and temp, read toolchains. It **cannot** write the control checkout, the project's `.synesis`, other
   agents' worktrees, the control repository's shared Git refs except through Synesis, or Synesis's application state
   and keys, and it cannot read the keys. Operator decisions (validation gate on or off, review policy, project
   policy, the unsandboxed-commands allowance, and so on) become **signed log entries**; integration and every gate
   read the decision from the log, so editing `project.json` or committing to it changes nothing.
2. **No sandbox available: refuse.** `run_command` refuses with ERROR, MESSAGE and FIX (how to install the sandbox).
   The owner can explicitly allow unsandboxed commands for a project, as a signed decision.
3. **Network is allowed** inside the sandbox.

## Context

### What `run_command` is today (VERIFIED)

- The MCP tool `run_command` (`McpToolCatalog.RUN_COMMAND`) is run by `McpDurableCommand.runCommand`. It chooses one of
  three paths in order: a requester's validation snapshot (`CapabilityValidationSnapshotAccessService`), a review
  snapshot (`ReviewSnapshotAccessService`), or the durable command path (`ProjectCommandService`). It refuses a command
  that only sleeps (`SleepCommands`).
- All three end in one primitive, `ProjectProcessExecutor.execute`, which builds a `ProcessBuilder` from the argv, sets
  the working directory inside the assigned worktree, removes environment variables whose names contain `TOKEN`,
  `SECRET`, `KEY` or `AUTH`, and starts the process as the current user. Nothing else confines it: no path check, no
  user switch, no sandbox. The working-directory check only constrains where the process starts.
- The same executor runs the project's validation command in the integration worktree and in `RemergeService`
  (`IntegrationOrchestrationService`, `RemergeService`, `AgentTaskCompletionService`). That command runs test code the
  agents wrote.
- The argv has no shell, but `argv[0]` can be `bash`, `powershell`, `git`, `java` or `synesis`, so that is no limit.
- The MCP server (`synesis mcp`) is a child of the provider (Claude Code or Codex) and runs as the same user. It holds
  the node key while it runs, because agent actions in the log are signed with it.

### How agents are confined today (VERIFIED)

- Claude: `claude -p ... --allowedTools mcp__synesis Read Glob Grep` (`ClaudeCodeProviderIntegration`, line 125). No
  shell tool, so a `synesis agents run` Claude changes things only through Synesis tools. `Read`, `Glob` and `Grep`
  take any path.
- Codex: `codex exec` with `--config sandbox_mode=read-only` and the Synesis tools approved
  (`CodexProviderIntegration`, line 212). Codex's read-only sandbox forbids writes; whether it forbids reads outside the
  project is ASSUMED to be no.
- The provider hook guards only `Edit|Write|Bash|PowerShell` for Claude (`ClaudeCodeProviderIntegration`, line 243),
  and `apply_patch` for Codex. The hook is written into each lane worktree by
  `ProviderHookConfiguration.materializeHook` (`ProviderWorktreeAllocation`).
- An agent a person starts in their own terminal (`claude`, `codex`) has the provider's own shell and file tools. It is
  not confined by Synesis at all, and only the Synesis tools are policed.

### Where state lives (VERIFIED)

- Control checkout: the project folder. `.synesis/project.json` (tracked by Git where the project is a Git repository;
  Synesis commits it itself, `ProjectApplicationService.rewriteValidation`), `.synesis/coordination` (the event logs and
  `replication.properties`), `.synesis/local/profile` (the project's profile), `.synesis/local/profile/link/identity.bin`
  (the node's Ed25519 key, `IdentityBootstrap`, `FileIdentityStore`; stored unencrypted), `.synesis/local/runtime`.
- Application state: `%LOCALAPPDATA%\Synesis` (else `~/.synesis`). Lane worktrees are
  `<state>/workspaces/<projectId>/worktrees/<session suffix>`, on a branch `synesis/<provider>/<suffix>`
  (`ProviderWorktreeAllocation`); integration worktrees are siblings (`ExternalWorktrees`). The default Link identity
  directory and the daemon endpoint file are also there (`IdentityBootstrap.defaultDirectory`, `DaemonEndpoint`).
- Lane worktrees are created with `git worktree add` from the control repository, so each shares the control `.git`
  (objects, config, hooks, refs) and has its own admin directory `.git/worktrees/<name>` (HEAD, index).
- Synesis does not need an agent to commit. A lane snapshot is built by Synesis itself: a temporary index, `write-tree`,
  `commit-tree`, `update-ref` under `refs/synesis/...` (`SnapshotTreeMaterialization`, `SnapshotRefLifecycle`). Main
  moves only through `ControlBranchAdvancementService`.

### Operator decisions today, and who reads them (VERIFIED)

| Decision | Where it is stored today | Written by | Read by |
| --- | --- | --- | --- |
| Validation command (argv, directory, timeout) | `project.json` `validation`; committed to Git | `synesis project validation set/clear` (`ProjectApplicationService.setValidation/clearValidation`) | `IntegrationOrchestrationService` (lines 610, 853), `RemergeService` (483), `ValidationFailureGuard` (56), `AgentTaskCompletionService` (479, 831), `AgentNextActionService` (312, 945) |
| Validation also in each lane | `project.json` `validation.alsoInLane` | same | `AgentTaskCompletionService` |
| Review mandatory | `project.json` `review.mandatory`; **no command sets it**, it is edited by hand | a person with an editor | `WorkIntentAnnouncement` (169) copies it into the work group in the log; later gates read the group's copy (`ReviewAdmissionRules`, `AgentReviewActionProjection`, `LaneCompletionProjection`) |
| Who may answer a join | `JOIN_POLICY_SET` log entry (owner statement) | `synesis project join-policy` | `MembershipProjection` |
| Membership, owner, recovery | `PROJECT_SHARED`, `JOIN_ANSWERED`, `MEMBER_REMOVED`, `OWNER_CHANGED` (owner statements, revision-numbered) | `project share`, `link accept`, `project add-member`, `remove-member`, `recover` | `MembershipProjection` |
| Trash emptied | `TRASH_EMPTIED` entry | `synesis trash empty` | `TrashProjection` |
| Operator request closed | `OPERATOR_REQUEST_CLOSED` entry | `synesis operator close` | `AgentNextActionService`, integration |
| Project constraints | `DecisionStore` records under the profile, verified when read | `synesis constraint create` | `ActionGuardrail` |
| Mirror remote for main (`code.remote`) | `replication.properties` (this computer only, never replicated) | `project follow` and hand edits | `CodeRemote` |

The first two rows, and the lack of a setter for the third, are the unsigned ones. `project.json` is an ordinary file
in the control checkout.

### What an agent's command can do today (DERIVED from the above, not run)

Through `run_command`: `synesis project validation clear` (it is the person's CLI and runs as the person); write
`.synesis/project.json`; `git -C <control> update-ref refs/heads/main <sha>`; `git -C <control> config`, or plant a
hook in `<control>/.git/hooks` that runs unsandboxed the next time a person uses Git there; read `identity.bin` and
sign anything the node can sign (including owner statements on the owner's computer); write another lane's
worktree; write `replication.properties`; call the daemon's loopback API if it can find the token. The adversarial test
in stage 2 turns each into a test.

Through the provider's own file tools: Claude's `Read` can read `identity.bin` today (DERIVED: allowed, no path rule,
the hook does not match `Read`). This is not closed by a sandbox around `run_command`; see "Keys and the provider's own
tools".

## Decision

### The two rules

1. **A decision is the log's, not the file's.** An operator decision is an entry in the project's coordination log,
   signed by the key of the person's computer, in a form every computer judges the same way. Gates ask the
   policy projection of the log. They never read policy from `project.json` or any other file an agent could write.
2. **Agents' commands never run unsandboxed unless a signed decision says so.** Every process Synesis starts to run
   agent-authored commands or agent-authored code (`run_command`, the review and validation snapshot commands, the
   project's validation command, the re-merge validation) runs through one sandbox choke point. If the sandbox cannot
   be set up, the command does not run.

Neither rule alone is enough. A sandbox without signed decisions leaves `project.json` to anything that can write it
outside the sandbox. Signed decisions without a sandbox leave the key readable.

### Part A — Signed decisions

#### What becomes a signed decision (designer, open to the owner)

| Decision | Entry | Who may write |
| --- | --- | --- |
| Validation command, or none | `PROJECT_POLICY_SET` `validation` | the owner computer |
| Validation also in each lane | `PROJECT_POLICY_SET` `validation.alsoInLane` | the owner computer |
| Review mandatory (and independence) | `PROJECT_POLICY_SET` `review` | the owner computer |
| Unsandboxed commands allowed on computers with no sandbox | `PROJECT_POLICY_SET` `sandbox.unsandboxed` (`DENY` default, `ALLOW`) | the owner computer |
| Extra sandbox paths: read-only toolchain folders, shared writable caches | `PROJECT_POLICY_SET` `sandbox.paths` | the owner computer |
| Join policy, membership, owner, recovery | existing entries, unchanged | the owner, as today |
| Trash emptied, operator request closed | existing entries, unchanged | the key of the computer it is run on, as today |
| Project constraints | existing `DecisionStore` records, unchanged | the profile key, as today |
| `code.remote` (this computer's mirror) | stays a local file | this computer; protected by the sandbox only (see "What is not signed") |

`PROJECT_POLICY_SET` is one new entry type carrying `{revision, name, value}`, with `name` from a closed set. An unknown
name makes the entry invalid, so a newer writer cannot slip a policy an older reader does not understand past it
(fail closed). The "closed set" is the table above and nothing else.

#### Validity and replay (DERIVED from step 6, designer)

- **Same rule as membership statements.** `PROJECT_POLICY_SET` counts as an owner statement: it carries the
  `revision` of the owner statements applied before it, and `MembershipProjection.requireOwnerStatement` judges it
  (VERIFIED: the function exists and judges `JOIN_POLICY_SET` this way). A replayed or reordered statement is stale.
  Policy and membership share one counter, so their order is one order.
- **Unshared project.** Membership checks nothing in a project with no `PROJECT_SHARED`. A policy entry there is valid
  when its signer is the node that wrote the project's *baseline* entry, the first `PROJECT_POLICY_SET`, which
  `synesis init` writes (empty policy, revision 0). Sharing the project later (`project share`) requires the sharing
  computer to be that same node, so the baseline signer becomes the owner and nothing is re-keyed. A project that
  already exists and was shared before this ADR gets its baseline from migration, below.
- **The baseline is what makes "no entry" safe.** A computer that has not applied the owner's baseline entry has no
  policy. It refuses to integrate and to run a gate, with `POLICY_NOT_RECEIVED`, instead of using defaults. Without
  this a new member that has not yet synced would integrate with the gate off.
- **The signer is the owner's node key**, which is what ADR-0098 step 6 decided the owner key is (VERIFIED in the
  ADR). No second key is added by this ADR (see "Keys and the provider's own tools" for why that is a recorded risk
  and the one open question the owner may want to answer differently).

#### The projection and the readers

- A `PolicyProjection` is a new log projection beside `MembershipProjection`, built the same way (applied in each
  computer's applied order, judged by the same append-time and arrival-time rules). It returns the current
  `validation`, `alsoInLane`, `review`, `sandbox.unsandboxed`, `sandbox.paths` and the revision.
- **One door.** `ProjectLocation` carries `validation()`, `review()` and `validationAlsoInLane()`, and every reader
  listed above goes through it (VERIFIED by grep). `ProjectApplicationService.locate` fills those three from the
  `PolicyProjection`, not from the file. No call site changes, and none can forget. Stage 1 first makes a grep-based
  fitness test that no code outside `ProjectMetadataCodec` and the migration reads `validation` or `review` from the
  file.
- **Fail closed.** If the log cannot be read, or verification fails, `locate` raises `POLICY_UNAVAILABLE`, and the
  gate does not run. It never falls back to the file after migration.
- **`project.json` keeps the identity** (`projectId`, `createdAt`, `schemaVersion`). Its `validation` and `review`
  fields stay readable (they are the migration's input and an old Synesis reads them) and are ignored once the
  baseline exists. `synesis project validation set/clear` stop rewriting the file and stop committing to Git. That
  removes the commit "Synesis project validation policy" and the `CONTROL_CHECKOUT_FOREIGN_CHANGES` refusal that
  existed only because the file was policy (VERIFIED: `rewriteValidation`).
- **Doctor tells you when the file disagrees.** If `project.json` still holds a `validation` or `review` that differs
  from the log, doctor reports `PROJECT_FILE_POLICY_IGNORED`: "this file says X, the signed policy says Y, the signed
  policy is used." That is how an agent's or a tool's edit shows up.

#### How a person proves a decision

Both a person and an agent run as the same operating-system user, so the proof cannot be "who is logged in". The proof
is possession of the node key, and **the sandbox is what makes that key a proof**: an agent's command runs inside it
and the key is outside it. In more detail:

- A decision command (`synesis project validation set`, `project review set`, `project sandbox allow-unsandboxed`,
  `project join-policy`, `link accept`, ...) runs in the person's own terminal (or the UI the person opened), loads the
  key, and appends the signed entry. Inside the sandbox the key cannot be opened, so the command cannot sign. It stops
  with an error in the shape ADR-0103 gave: `ERROR=OPERATOR_KEY_UNAVAILABLE`,
  `MESSAGE=This command needs your signing key, which a sandboxed command cannot open.`,
  `FIX=run it in your own terminal, outside the agent.` The message is a convenience, not the guard: the guard is that
  the key file cannot be read.
- The same holds for the browser UI: its state-changing routes need a CSRF token (VERIFIED, `ControlPlaneHttpHandler`
  line 62) and the daemon's command token, both in files the sandbox hides (DERIVED: they are under the application
  state or `.synesis/local`).
- **Interactive commands a person runs outside the sandbox** are every `synesis` command typed in a terminal, the UI,
  and the person's own `git` and editor. Synesis starts sandboxed processes only for the agents it runs (and for the
  validation command); it never wraps what a person types.

#### Migration of existing projects

- **When:** the first start of this build's runtime on the project, on the computer that holds the project's owner
  key (the owner of a shared project, the only computer of an unshared one). A member's computer waits for the owner's
  entries (as step 6 decided for membership). This is the same pattern as the step-5 `replication.properties`
  migration (VERIFIED in ADR-0098 item 14).
- **What it adopts:** the `validation` and `review` of `project.json` **as committed on the control branch's head**
  (`git show HEAD:.synesis/project.json`), because only integration moves that branch and agents cannot reach it. When
  the working file differs from the committed one, or the file is untracked, the migration adopts nothing silently:
  it raises an operator request (ADR-0091), `POLICY_ADOPTION_NEEDS_CONFIRMATION`, showing both, and
  `synesis project policy adopt` signs the one the person picks. This closes the case where the file was already
  edited before the upgrade.
- **What it writes:** one baseline `PROJECT_POLICY_SET` plus one entry per non-default field, in one append, signed by
  the owner's key. It prints what it adopted as `POLICY_ADOPTED=...` lines.
- **Idempotent:** a project with a baseline entry is not migrated again. A second computer cannot write a second
  baseline: the validity rule refuses it (`POLICY_BASELINE_EXISTS`).
- **Old builds:** an older Synesis on the same project ignores the unknown entry type, as it does any type it does not
  know (DERIVED from step 4's rule that unknown wire codes are carried but not applied; verify in stage 1) and keeps
  reading `project.json`. That is a fail-open for an old build; ADR-0098's version rule, that all computers of a
  project run builds that know every entry in its log, covers it. The ADR therefore asks stage 1 to raise the
  project's minimum required version at the baseline, which is how step 3 gated Code over Link (ASSUMED: confirm the
  mechanism there).

#### Many computers (ADR-0098)

- Decisions are entries in the **owner's own log**, like membership. Each computer applies them and every gate reads
  its own projection. The owner is the only writer, so decisions form one line and never conflict, as steps 4 and 6
  decided for owner statements.
- A decision reaches a member when the member's log copy of the owner's log does. A computer that is away keeps
  integrating **its own lanes under the last policy it applied**, and the policy it applied is part of the evidence
  of that integration (the attempt records the policy revision). When it reconnects and the owner's newer decision
  arrives, later attempts use it. This follows the premise (work apart, merge back; only `main` has to be stable): the
  merge into the shared `main` happens on a computer that has the owner's latest policy only if that computer is
  connected to the owner, and the combine lane there (ADR-0098 step 5) runs the gate again under its policy. A decision
  that makes the gate stricter therefore catches up at the next combine without any waiting rule. A decision that
  makes it looser never reaches an away computer before it reconnects, which is the safe side.
- `sandbox.unsandboxed = ALLOW` is a project decision, but sandbox availability is per computer. It means: every
  computer of the project that cannot set up a sandbox may run agents' commands unsandboxed. It never lets a computer
  that *can* sandbox skip it. A member cannot grant it for itself, because only the owner's entries count.
- A computer's own **mirror remote** (`code.remote`) stays a local setting, as ADR-0098 decided; the sandbox keeps
  agents from writing it.

### Part B — The sandbox

#### One choke point and one policy

`ProjectProcessExecutor` is the one primitive that starts agent-authored commands (VERIFIED). The sandbox is applied
there. `ExecutionRequest` gains a `SandboxPolicy` (designer): the paths below, derived by the caller from the
authoritative lane binding, never from the command's own input. The executor asks a `Sandbox` for a wrapped process
start; the platform classes are the only code that knows `bwrap`, AppContainer or `sandbox-exec`. If `Sandbox` reports
unavailable, the executor returns `COMMAND_SANDBOX_UNAVAILABLE` (a new outcome) before anything starts. There is no
other `ProcessBuilder` for agent commands; a fitness test finds any new one.

Policy, in words (the same on every platform):

| | Read | Write |
| --- | --- | --- |
| The lane's own worktree | yes | yes (except the provider's hook and config files, below) |
| The lane's own temp and private home | yes | yes |
| Toolchains: system folders, `JAVA_HOME`, the folders of the program being run and of `PATH` entries outside the user's home, the project's declared `sandbox.paths` read-only list | yes | no |
| Shared tool caches the owner declared in `sandbox.paths` (for example `~/.gradle`) | yes | yes, only because the owner chose to (a poisoned cache reaches later builds) |
| Control checkout working files | no | no |
| Control `.git`: objects, packed-refs, refs, config, hooks | yes (objects and refs, so Git works) | **no** |
| `.git/worktrees/<this lane's name>` (HEAD, index, logs) | yes | yes, except `config.worktree`, which is read-only |
| Control `.synesis`, including `local/profile` and every key | no | no |
| Application state (`%LOCALAPPDATA%\Synesis`, `~/.synesis`), including the daemon endpoint and Link identity | no | no |
| Other lanes' worktrees and integration worktrees | no | no |
| The person's home outside the above | no (Linux, macOS, Windows AppContainer) | no |
| Network | yes (USER-STATED) | n/a |

The environment is scrubbed, extending today's `filterEnvironment`: the existing names, plus `SSH_AUTH_SOCK`,
`DBUS_*`, `XDG_RUNTIME_DIR`, `GPG_*`, `SYNESIS_*` (the connection-instance variables are the MCP identity of the
agent and must not reach a child), cloud-credential names (`AWS_*`, `AZURE_*`, `GOOGLE_*`) and the usual proxy-auth
ones. `HOME`, `USERPROFILE` and `TEMP` point at the lane's private folders. This is a default list; the executor
keeps one list for all platforms. Today's list is a name-contains filter, a heuristic and not a boundary, and this ADR
does not rely on it (VERIFIED: `filterEnvironment`).

The process is started in its own session with no controlling terminal where the platform allows (Linux
`--new-session`; this stops a command pushing keystrokes into the person's terminal), in a job or process group that
the existing `killProcessTree` ends on timeout, and the existing output bounds stay.

#### Git: how a worktree can work without moving `main`

The sandbox does not special-case Git. It makes Git's own files do the work:

- Control `.git` is mounted read-only. So `refs/heads/main`, every other ref, `packed-refs`, `config` and `hooks` cannot
  be written, and `git update-ref refs/heads/main` and `git config` fail the same way on every platform.
- `.git/worktrees/<this lane>` is writable, so `git status`, `git diff`, `git log` and `git add`
  work (HEAD and the index are per worktree). Its `config.worktree` is read-only, because a `core.fsmonitor` or a
  filter set there runs a program the next time any Git command runs in that worktree, and Synesis itself runs Git
  there unsandboxed (see below).
- **New objects do not go into the control repository.** The sandbox sets `GIT_OBJECT_DIRECTORY` to a private folder
  in the lane's temp and `GIT_ALTERNATE_OBJECT_DIRECTORIES` to the control `.git/objects` (read-only), so reads find
  everything and writes land in the lane's own folder. A writable shared object store would let a command overwrite a
  loose object of `main` (Git does not verify an object's hash when it reads it, so the next checkout would carry the
  changed file). This is a finding of this design, not of the code, and is DERIVED from how Git reads objects.
- **The agent cannot commit.** Moving the lane's branch needs a write of `refs/heads/synesis/<provider>/<suffix>`,
  which is in the read-only refs. Synesis never needed it: a lane's snapshot is made by Synesis from the worktree files
  with a temporary index, `commit-tree` and `update-ref` under `refs/synesis/` (VERIFIED). `git commit` in the sandbox
  therefore fails with Git's own message about locking the ref. The provider manual says so (stage 2 task), and the
  failure is not a Synesis ERROR because it comes from Git. If the owner wants agents to commit, the answer is a
  Synesis tool that does it for them, not a writable ref (open question 5).
- **Synesis's own Git on an agent's worktree is hardened.** The worktree's `.git` is a file the agent can rewrite to
  point anywhere, including a directory in its own worktree that holds a hostile `config`. Today Synesis runs
  `git` in lane worktrees as a plain subprocess (`GitProcessRunner`, `runGitOutput(workerWorktreePath, ...)`,
  VERIFIED). After this ADR every Synesis Git command on a worktree an agent can write names the git directory
  explicitly (`--git-dir=<control>/.git/worktrees/<name> --work-tree=<worktree>` from the binding, not from the
  worktree's file) and neutralises repository-level program hooks: `-c core.fsmonitor=false -c core.hooksPath=<empty>
  -c core.sshCommand= -c protocol.ext.allow=never`, with `GIT_CONFIG_NOSYSTEM=1` and a fixed global config. This is a
  stage 2 task, because without it the sandbox only moves the unsandboxed code execution from "the command" to "the
  next Git status Synesis runs".
- **Provider config in the worktree is read-only.** `.claude/`, `.codex/`, `.mcp.json` and the hook files that
  `materializeHook` writes into the lane are mounted read-only (or, on Windows, denied write by ACL), so a command
  cannot loosen the guard of the next provider session or add an MCP server that the provider would start unsandboxed
  (DERIVED from `ProviderWorktreeAllocation`, which writes them into the worktree).

#### Linux: bubblewrap (designer; mechanism ASSUMED until the spike)

- Start `bwrap` with: `--die-with-parent --new-session --unshare-pid --unshare-ipc --unshare-uts` (not
  `--unshare-net`: USER-STATED), `--ro-bind / /`, `--dev /dev`, `--proc /proc`, `--tmpfs /tmp`. Then hide with
  `--tmpfs` over the control checkout, the application state directory, `/run/user/<uid>` and `$HOME`; then bind back,
  read-only, `<control>/.git` and the toolchain list; then bind back, writable, the lane worktree, its temp, and
  `<control>/.git/worktrees/<name>`; then bind `/dev/null` over `config.worktree` and the provider-config files.
  Because the hidden paths are overlaid by `tmpfs`, the control checkout's working files, `.synesis` and the keys do not
  exist inside the sandbox.
- **Detection:** `bwrap` on `PATH`, and a probe that really works: `bwrap --unshare-user --ro-bind / / -- true` exits
  zero. The probe matters: unprivileged user namespaces are off by default in some distributions and restricted by
  AppArmor on Ubuntu 24.04 (`kernel.apparmor_restrict_unprivileged_userns`), and are off in many containers (ASSUMED).
  Detection result is cached per Synesis process and shown by doctor.
- **Known gaps the spike must close:** with the network shared, abstract-namespace Unix sockets of the host (some D-Bus
  and X11 setups) are reachable from the sandbox (DERIVED from Linux network namespaces). The scrubbed environment and
  the hidden `/run/user/<uid>` close the common ones. The stronger fix, `--unshare-net` with a user-mode network
  (`pasta`, `slirp4netns`) that still reaches the internet, is recorded as a later hardening (open question 4).

#### Windows: an AppContainer process, one per lane (designer; ASSUMED until the spike)

Three options were weighed for "a low-privilege process":

1. **Low integrity level only.** The mandatory integrity policy denies writes up from low to medium for everything
   not labelled low, which fits "cannot write the control checkout, `.synesis`, application state". It does **not**
   deny reads (the default label policy is no-write-up only), so the keys stay readable unless each key file gets a
   no-read-up label, and every lane worktree is low, so lanes can write each other. Fails the per-lane requirement.
2. **Restricted token plus low integrity.** Can isolate lanes (a per-lane restricting SID) and deny reads, but the
   restricted access check applies to reads too: everything under the user's profile that is not granted to the
   restricting SIDs becomes unreadable, which is the same toolchain problem as AppContainer with more machinery.
3. **AppContainer** (`CreateAppContainerProfile`, `CreateProcess` with `PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES`).
   The process can touch only objects whose ACL names its package SID, the all-application-packages SID, or a
   capability it holds. A user's profile folder grants neither, so the keys, `.synesis`, the control checkout, the
   application state and other lanes' worktrees are unreadable **and** unwritable by default, without per-file labels.
   One profile per lane gives lane-to-lane isolation for free, because the ACE for lane A's package SID is on lane
   A's worktree only. The `internetClient` capability gives the network (USER-STATED). It also runs at low
   integrity. Windows Program Files, the Windows folder and most system tools grant all-application-packages read, so
   toolchains installed there work.

**Decision (designer):** AppContainer, one profile per lane, named from the lane (`synesis.<projectId>.<laneSuffix>`),
created on lane start and deleted on lane end (a stale one is deleted by the lifecycle cleanup that owns lane resources), launched
from Java with the Foreign Function API (the repository already does this for Win32:
`WindowsJobObjectProcessTreeSupervisor`, VERIFIED) with no native helper and no new dependency. Grants Synesis makes:
read-and-modify on the lane worktree, the lane temp and `<control>/.git/worktrees/<name>` (the lane's package SID);
read on `<control>/.git` and on the toolchain folders, through **one project-scoped capability SID**
(`DeriveCapabilitySidsFromName("synesis.<projectId>.read")`) that every lane's container holds, so that the ACL on the
control `.git` is written once and does not grow by one entry per lane (ASSUMED: confirm in the spike that custom
capability SIDs can be granted in ACLs and held by a container this way); write-deny by ACE on `config.worktree`
and the provider-config files. `GIT_OBJECT_DIRECTORY` and the alternates work as on Linux.

The costs of AppContainer, stated rather than hidden (all ASSUMED until the spike measures them):

- **User-profile toolchains** (`%USERPROFILE%\.gradle`, `.cargo`, `scoop`, `nvm`, `%LOCALAPPDATA%\Programs`) are
  unreadable until granted. Synesis grants read on the folders of the executable it is asked to run and of the `PATH`
  entries under the user's profile **only when the owner has listed them** in `sandbox.paths` (a signed decision);
  doctor lists the ones a first run found missing. Granting changes the ACLs of the person's own folders, so it is
  done once, with a printed list, never silently.
- **Loopback.** An AppContainer cannot connect to `127.0.0.1` unless loopback is exempted for the container
  (`CheckNetIsolation`, which needs elevation). A test that starts a server on localhost fails. This also stops a
  sandboxed command from reaching the Synesis daemon on loopback, which is a benefit. It is the biggest compatibility
  risk of the choice; open question 3.
- Some programs write to `%APPDATA%` or the registry at start and fail. The private `HOME`/`USERPROFILE` and
  per-tool cache variables (`GRADLE_USER_HOME`, `npm_config_cache`, `CARGO_HOME`) redirect most of them into the
  lane's temp.

**If the Windows spike fails** on loopback or toolchain compatibility, the fallback is option 2, restricted token plus
low integrity plus a job object, with a no-read-up label on `.synesis` and the key files (SDDL `ML;;NRNW;;;ME`, ASSUMED
to be honoured as written) and a per-lane restricting SID. The stage 3 spike decides between them with measurements
and the owner is told which was chosen. Running commands as a separate local user account was rejected: it needs an
administrator to create the account and a stored password, and one account for every lane gives no isolation between
lanes. Windows Sandbox and a Hyper-V or WSL2 guest were rejected for this purpose: they do not see the Windows
toolchains the project uses.

#### macOS: `sandbox-exec` (designer; ASSUMED until the spike)

`sandbox-exec -p <profile> -- argv...` with a Scheme profile generated per run: default allow, then
`(deny file-write* (subpath "/"))`, then `(allow file-write* ...)` for the lane worktree, its temp,
`<control>/.git/worktrees/<name>` and `/dev` pseudo-devices; then `(deny file-read* ...)` for the control checkout,
`.synesis`, the application state and `$HOME`, with `(allow file-read* ...)` for the toolchain list; then
`(allow file-read* (subpath "<control>/.git"))` and `(deny file-write* (subpath "<control>/.git"))` ordered last,
since later rules win. Network is allowed by default. The tool is documented as deprecated and still present on
current macOS (ASSUMED; the doctor probe is what proves it on a given Mac). Nothing else on macOS offers
file-path sandboxing to an unsigned command-line program. If a later macOS removes it, the same refusal path applies
and the fix text says so.

### Part C — Refusal, the override, and doctor

- **Detection.** Each platform class reports `AVAILABLE`, or `UNAVAILABLE` with a reason and a fix. Availability is a
  live probe that runs a trivial command in the sandbox and checks two negative facts (it cannot write a file in the
  control checkout, it cannot read the profile directory) and one positive (it writes its own temp). A probe that
  cannot prove the negatives is `UNAVAILABLE`.
- **Refusal.** When unavailable and the project has not signed `sandbox.unsandboxed = ALLOW`, `run_command` and the
  other agent-command paths return a `BLOCKED` response with reason `SANDBOX_UNAVAILABLE` (a new
  `AgentReason`, additive) whose `instruction` text carries the three lines the CLI also prints:
  - `ERROR=SANDBOX_UNAVAILABLE`
  - `MESSAGE=Commands asked for by agents run in a sandbox on this computer, and none is available: <reason>.`
  - `FIX=<platform text>`: Linux `install bubblewrap (sudo apt install bubblewrap, or your distribution's package), and if user namespaces are restricted, run 'synesis doctor' for the one setting to change`; macOS
    `sandbox-exec is missing from this macOS, run 'synesis doctor'`; Windows `run 'synesis doctor'; AppContainer needs Windows 10 or later`.
  The validation command and re-merge validation that need the sandbox are not skipped: the gate is **not run**, the
  integration waits, and an operator request (ADR-0091) is raised once with the same text, so a person finds it in
  `synesis operator list` and in the UI. The gate is never treated as passed.
- **The override.** `synesis project sandbox allow-unsandboxed` (and `deny-unsandboxed`) writes the signed decision.
  It prints what it allows, `UNSANDBOXED_COMMANDS=ALLOW` and a warning that an agent's command on a computer without a
  sandbox can do what the person's user can do. A run with the allowance records, in the command's evidence, that it
  ran unsandboxed, so reviews and the UI show it (`SANDBOX=NONE_ALLOWED_BY_DECISION`). There is no environment
  variable, flag or file that grants it: a bypass an agent could set is no bypass.
- **Failure of setup is a refusal.** If the sandbox was available at the probe but setup for a run fails (a mount, an
  ACL grant, a profile creation, a limit), the run returns `SANDBOX_SETUP_FAILED` with the platform's reason and does
  not start the command. It never retries unsandboxed. A failure while tearing down (an AppContainer profile that
  will not delete) is logged and left for cleanup, and does not change the command's result.
- **Doctor.** New findings: `SANDBOX_UNAVAILABLE` (WARNING, with the platform fix), `SANDBOX_PROBE_FAILED` (ERROR, a
  negative fact did not hold: the sandbox is present but leaks), `UNSANDBOXED_COMMANDS_ALLOWED` (WARNING, shows the
  signed decision and its revision), `PROJECT_FILE_POLICY_IGNORED` (INFO), `POLICY_NOT_RECEIVED` (WARNING, on a
  member), `POLICY_ADOPTION_NEEDS_CONFIRMATION` (WARNING). `doctor --fix` repairs only what is safe: it can delete a
  stale AppContainer profile; it never writes a decision and never grants an ACL.

### Keys and the provider's own tools (a finding, with a decision)

The sandbox covers `run_command`. It does not cover the provider's **own** tools: Claude's `Read`/`Glob`/`Grep`
(allowed for headless agents, VERIFIED) and Codex's read-only sandbox, which reads outside the project (ASSUMED). An
agent can therefore read `.synesis/local/profile/link/identity.bin` through those tools without `run_command`.

The obvious fix, running the whole provider process in the sandbox, fails: the provider starts the MCP server as its
child, the child inherits the sandbox, and the MCP server needs the key and `.synesis`. Moving the key out of the
reach of every same-user reader is impossible by file permissions alone, and the node key cannot be passphrase-
protected, because the MCP server uses it unattended.

Decision (designer, open question 1): this ADR closes what it can and records the rest as a residual risk.

- Stage 5 extends the provider guards so the provider's own read tools are denied the Synesis state: Claude's PreToolUse
  hook matcher grows from `Edit|Write|Bash|PowerShell` to include `Read|Glob|Grep` and denies paths under any
  `.synesis` and the application state; the headless launch adds explicit deny rules for those paths (ASSUMED: Claude
  Code accepts path-scoped `--disallowedTools` rules; spike). Codex has no such control (ASSUMED); its residual is
  recorded below.
- The recommended hardening, if the owner wants the guarantee to hold against a hostile Codex-class agent, is a
  **separate operator key**: an Ed25519 key used only for decisions, wrapped with a passphrase the person types, whose
  public half is registered in the baseline entry and changeable only by a statement signed by the current operator
  key or the recovery key. Then reading the node key lets nobody forge a decision. It adds one passphrase prompt per
  decision (decisions are rare). It is not built by this ADR, because it also changes ADR-0098's rule that the owner
  key is the node key, and the owner decides (open question 1).

### What is not signed, and what this does not protect against

Stated plainly, because a sandbox is easy to over-claim:

- **A person's own shell, editor and Git** are not confined. A person can do anything, which is the point.
- **An agent a person started in their own terminal** with the provider's native shell (`claude`, `codex` interactively,
  or with a shell tool enabled) is not sandboxed by Synesis, and can read the key and edit `.synesis`. Synesis polices
  only the Synesis tools there. A person who wants that agent confined runs the whole provider under their own
  sandbox; this ADR does not provide it.
- **A compromised provider binary** (a hostile `claude` or `codex` on `PATH`) runs unsandboxed as the user, outside
  this design, and so does anything else running as the user.
- **Codex's own file reads** (see above) until the operator key exists or the owner accepts the residual.
- **Local files that are not decisions**: `replication.properties` (mirror remote), the daemon endpoint, provider
  configuration in user folders. They are protected by being unwritable inside the sandbox, not by signatures.
- **A root user or an administrator** on the computer. The sandbox is not a defence against the computer's owner or
  against a kernel escape.
- **Network use.** Network is allowed (USER-STATED): a command can send what it can read, which is the worktree and
  the toolchains, to anywhere. It cannot read the keys or the other lanes.
- **Shared tool caches** the owner declares writable are writable by every lane's commands, and a poisoned cache is
  read by later builds, including the person's.
- **Agent-authored code the person later runs by hand** (a script in the repository) is the person's risk, as it is
  today.
- **Side channels** (timing, CPU, ports of other local services reachable over loopback on Linux and macOS).

## Alternatives considered

1. **Only sign the decisions; no sandbox.** Rejected by the owner's decision 1: the key is readable by the command.
2. **Only a sandbox; keep `project.json` as policy.** Rejected: anything that can write `project.json` outside the
   sandbox (a bug, a tool, an unsandboxed-by-decision computer) changes policy, and Git history is the only record.
3. **Command allow-lists or deny-lists in `run_command`** (refuse `synesis`, refuse `git update-ref`). Rejected: argv
   can name any program, and a script file in the worktree can name them again. This is the check the sandbox replaces.
4. **Run commands as a separate OS user.** Rejected on Windows above; on Linux it needs root to set up and the
   same lane-isolation problem. Considered for the future as a stronger Linux mode.
5. **Containers (Docker, Podman) as the sandbox.** Rejected as the default: a daemon dependency, slower start, and
   Windows and macOS need a VM that does not see the person's toolchains. Possible later as an additional backend.
6. **Landlock or seccomp directly (Linux).** A fine later backend that needs no helper binary; rejected for now because
   Landlock's ABI varies by kernel and the read-hiding of directories is weaker than mount namespaces. Recorded for
   open question 6.
7. **Sign decisions with a second key that agents never see (the operator key).** Not rejected: it is the recommended
   hardening, deferred to the owner (open question 1).
8. **Store decisions in a new file, signed.** Rejected: a second signed store beside the log with its own replay rules;
   the log already has owner statements, revisions, replication and projections.
9. **Make the policy a field of the work group** (as `review` is, once announced). It is, for review (VERIFIED); that
   copy stays. It is not enough alone, because the value is copied from the file at announcement and nothing compares
   it with the owner's current decision.

## Module responsibilities

- `coordination`: `PROJECT_POLICY_SET`, its codec, `PolicyProjection`, the validity rules (beside
  `MembershipProjection`). Knows nothing of files or sandboxes.
- `workspace`: `ProjectApplicationService.locate` (policy overlay), the migration, the `Sandbox` interface and the
  platform classes, `SandboxPolicy` derivation from the lane binding, `ProjectProcessExecutor` (the one choke point),
  hardened Git invocation, doctor findings.
- `cli`: the decision commands (`project validation`, new `project review`, `project sandbox`, `project policy adopt`)
  and their error shapes.
- `mcp` and `mcp-contract`: no new tool. A fitness test fails the build if a tool that changes policy is ever added to
  `McpToolCatalog`.

## Build order

Each stage lists tasks, tests and completion criteria. Estimates are preliminary. Stage 1 must come first because the
sandbox's value rests on gates trusting the log; stages 2 to 4 are independent of each other after the `Sandbox`
interface exists (stage 2 creates it); stage 5 needs stage 2 and at least one platform.

### Stage 1 — Signed decisions, migration, and gates reading the log (about three to four days)

Tasks:

1. `PROJECT_POLICY_SET` in `PredictionEventType` (next free wire code, 93 at the time of writing), its codec, bounds
   (value size, closed `name` set), log-dump fields, and the unknown-name refusal.
2. `PolicyProjection` and the validity rules: owner statement (shared project, via `requireOwnerStatement`), baseline
   signer (unshared project), `POLICY_BASELINE_EXISTS`, stale revision, signer not owner.
3. `synesis init` writes the baseline. `ProjectApplicationService.locate` fills `validation`, `review` and
   `validationAlsoInLane` from the projection and raises `POLICY_UNAVAILABLE` / `POLICY_NOT_RECEIVED` when it cannot.
4. Commands: `project validation set/clear/show` write entries (no file rewrite, no Git commit); new
   `project review set mandatory|none|show`; `project policy show` and `project policy adopt`. Output adds
   `POLICY_REVISION=` and `DECISION_ENTRY=`; existing keys are kept.
5. Migration: adopt from the control head's `project.json`, raise `POLICY_ADOPTION_NEEDS_CONFIRMATION` when the file
   differs; write baseline plus fields; `POLICY_ADOPTED=` output.
6. Attempts record the policy revision they ran under.
7. Doctor: `PROJECT_FILE_POLICY_IGNORED`, `POLICY_NOT_RECEIVED`.
8. A fitness test: no code outside `ProjectMetadataCodec` and the migration reads `validation`/`review` from the file;
   `McpToolCatalog` has no policy-changing tool.
9. Check and pin the old-build behaviour of an unknown entry type, and the minimum-version mechanism (the ASSUMED
   points above).

Tests:

- Unit: codec round trip and bounds; projection for set, clear, replay, stale revision, wrong signer, second baseline,
  unknown name; unshared and shared baselines; `project share` by a different node than the baseline signer is refused.
- Source semantics: with a signed `validation`, rewriting `project.json` to `validation: null`, or committing that
  edit, leaves `IntegrationOrchestrationService` running the signed command (this is the stage's regression test, named
  after the gap: "editing project.json changes nothing"). The same for `review.mandatory` and `alsoInLane`.
- Fail closed: a log that fails verification makes `locate` raise `POLICY_UNAVAILABLE`, and integration does not run a
  gate or skip one.
- Migration: tracked file adopted from head; edited working file raises the operator request and adopts nothing until
  `policy adopt`; untracked file; no file fields (empty baseline); repeated migration writes nothing; a replayed
  migration after a restart is idempotent.
- Multi-computer (in-process `TestComputers`): a member with no baseline refuses to integrate; after the owner's entries
  arrive it uses them; an owner decision signed by a member's key is invalid on every computer; two computers apply
  the same entries in different orders and end with the same policy.
- CLI: output shape and the `OPERATOR_KEY_UNAVAILABLE` error when the key cannot be opened (simulated).

Completion criteria: all of the above green on the Linux gate; the Windows gate once; no reader of policy bypasses
`ProjectLocation`; `project validation set` no longer commits to Git; ADR-0094 and the CLI help updated.

### Stage 2 — Linux sandbox, the choke point, and the adversarial test (about four to five days)

Tasks:

1. **Spike (first, half a day):** `bwrap` mount layout on this computer's Podman Linux gate and one real Linux host:
   `git status`, `git add`, Gradle offline build with a declared writable cache, Java, Node; confirm the userns
   situation of the Podman gate (rootless-in-container usually needs extra privileges: ASSUMED; if it fails here, the
   acceptance projects that run agents in Podman will need the signed allowance, noted in the report).
2. `Sandbox` interface, `SandboxPolicy`, `SandboxResult`, `COMMAND_SANDBOX_UNAVAILABLE`; `ProjectProcessExecutor`
   applies it; callers derive the policy from the lane binding. A fitness test finds any other `ProcessBuilder` on an
   agent-command path.
3. `LinuxBubblewrapSandbox` per "Linux" above, detection and the live probe; scrubbed environment; private home and
   temp; `GIT_OBJECT_DIRECTORY` and alternates; read-only provider config.
4. Hardened Git on agent-writable worktrees (explicit `--git-dir`, neutralised program hooks).
5. Provider manual text: `git commit` is not available to agents; Synesis snapshots the lane.

Tests:

- **Adversarial test (the stage's centrepiece; runs in a real sandbox, skipped with an explicit `SKIPPED, no sandbox`
  status and a failing gate on CI hosts that must have one).** One agent command per attack, each must fail and the
  state afterwards must be unchanged and verified from outside:
  1. `synesis project validation clear` (the real binary): fails; the signed validation is still in force.
  2. Write `.synesis/project.json` in the control checkout: fails; file unchanged.
  3. `git -C <control> update-ref refs/heads/main <other sha>` and `git update-ref` from the lane: fail; `main` unmoved.
  4. `git config` in the lane (shared `.git/config`), planting a hook in `<control>/.git/hooks`, and writing
     `.git/worktrees/<name>/config.worktree`: fail.
  5. Read `.synesis/local/profile/link/identity.bin`: fails (not found or denied); its bytes never appear in the
     command's output.
  6. Write another lane's worktree and the integration worktree: fail.
  7. Write `replication.properties`, the daemon endpoint file and the Link identity directory: fail.
  8. A command calling the daemon's loopback API without a token: refused by the daemon.
  9. Overwrite a loose object in `<control>/.git/objects`: fails; the object is intact.
  10. Rewrite the lane's `.git` file to point at a hostile directory, then let Synesis run its snapshot: Synesis's
      hardened Git ignores it and runs no program from it.
  11. Rewrite `.claude/settings.json` and `.mcp.json` in the lane: fail.
- Positive: build and test a small Maven or Gradle project offline in the lane; `git status`, `git diff`, `git log` work;
  the command can reach the network (a local test server is not enough on Windows, see stage 3; use an external
  address only in a documented manual check, not in CI).
- The same attacks through the validation snapshot and review snapshot paths, and through the project's validation
  command at integration (a test that tries to write the key from the validation command and fails).
- Hostile inputs: argv with traversal, a symlink in the worktree pointing at the control checkout or the key (the
  sandbox's mount view makes it a dangling link: test), a working directory that is a symlink out, a timeout that
  kills a process that forked, output bounds.
- Setup failure (a mount refused, `bwrap` missing between probe and run): `SANDBOX_SETUP_FAILED`, command not started,
  never retried unsandboxed.

Completion criteria: adversarial and positive tests green on Linux; the Podman gate's behaviour recorded; stage 5's
refusal can be wired to the `Sandbox` result.

**Built 2026-10-27 (Linux, source semantics proven on the Podman gate; Windows and macOS not built):**

1. Spike: done. The Podman gate image (`synesis-test:25`, Ubuntu 26.04, container root) has user namespaces but no
   `bwrap`; after `apt-get install bubblewrap` the probe and every adversarial test pass there. The gate image must
   therefore get `bubblewrap` (a run without it skips the adversarial test with `SKIPPED, no sandbox`;
   `SYNESIS_REQUIRE_SANDBOX=true` turns the skip into a failure). The Ubuntu 24.04 VM has no `bwrap` and
   `kernel.apparmor_restrict_unprivileged_userns=1`: a `bwrap` unpacked into a scratch folder fails with
   `setting up uid map: Permission denied`. `sudo apt install bubblewrap` (the package ships the AppArmor profile that
   allows it) is untried there, a package change left to the owner.
2. Built: `Sandbox`, `SandboxPolicy`, `SandboxAvailability`, `WrappedCommand`, `SandboxSetupException`
   (`...project.execution.sandbox`); `ProjectProcessExecutor` applies it; outcomes `COMMAND_SANDBOX_UNAVAILABLE` and
   `COMMAND_SANDBOX_SETUP_FAILED` (`AgentReason.SANDBOX_UNAVAILABLE`, `SANDBOX_SETUP_FAILED`); `LaneSandboxPolicy`
   derives the policy from the request's worktree and control root, finding the lane's Git administrative directory
   from the control side. `AgentCommandProcessArchitectureTest` lists every other process starter.
3. Built: `LinuxBubblewrapSandbox` with the live probe, extended scrubbed environment, private home and temp,
   `GIT_OBJECT_DIRECTORY` plus a read-only alternate, hidden areas remounted read-only, and the worktree's `.git` file,
   provider configuration, `gitdir`, `commondir` and `config.worktree` read-only. `gitdir` and `commondir` are added
   to the ADR's list because a rewritten `commondir` would redirect Synesis's own Git.
4. Built: `GitWorktreeBinding` and `GitProcessRunner.trustControlRoot` (called from `ProjectApplicationService`): a
   trusted control checkout names `GIT_DIR`, `GIT_COMMON_DIR` and `GIT_WORK_TREE` for the worktrees it lists, so a
   rewritten `.git` file is ignored; plus `protocol.ext.allow=never` and `core.sshCommand=false` for local commands.
   Gap: a worktree that no trusted control lists runs as before.
5. Not built here: provider manual text (the manual is attested by version; a bump belongs with stage 5). The
   behaviour is documented in `docs/providers/mcp.md`.

Not covered by the adversarial test: the real `synesis project validation clear` binary (needs the CLI distribution;
the same write is covered as a write to `project.json`) and the daemon loopback API (item 8; needs a running daemon).
The refusal seam is `UnsandboxedAllowance` (`DENY` for now); the override is stage 5. Tests that run ordinary commands
set the JVM property `synesis.sandbox.unconfinedForTests` (build file only).

### Stage 3 — Windows sandbox (about five to seven days; spike first)

Tasks:

1. **Spike (first, one to two days):** on this Windows 11 computer, with FFM: create an AppContainer profile; start
   `cmd`, `git`, `java`, `gradle`, `node` in it; measure which fail; check custom capability SIDs in ACLs, the effect
   of the loopback block on a localhost test, user-profile toolchain access, `.git/worktrees/<n>` and objects through
   a granted ACE; check the no-read-up SDDL label and the restricted-token fallback. Write the result into this ADR
   (as "Windows spike result") and tell the owner which option was chosen. If neither works well enough, stop and ask
   (open question 3).
2. `WindowsAppContainerSandbox` (or the fallback): profile lifecycle with cleanup, grants, capability, environment,
   process in a job object (reuse the existing job-object supervisor), kill-tree, bounded output.
3. Detection and live probe; the Windows `FIX` text.
4. The ACL grant tool for user-profile toolchains: `project sandbox paths add|list|remove`, writing the signed
   `sandbox.paths`, and a separate command that applies the ACEs and prints what it changed. Doctor reports a missing
   one.

Tests: the stage 2 adversarial list on Windows, with the Windows equivalents (the key, `.synesis`, `main`, other
worktrees, `config.worktree`, provider config); positive Git, Java and Gradle builds; loopback behaviour documented as
a test that asserts the known limitation so a change is noticed; profile cleanup after a killed lane and after a
Synesis crash (stale profile found and removed by doctor); two lanes at once cannot write each other.

Completion criteria: adversarial list green on Windows; the Windows gate once; spike result recorded; the owner's
answer on loopback in hand.

**Built 2026-10-27 (Windows, low integrity as the owner decided; source semantics proven on this Windows 11
computer; macOS not built).** The AppContainer plan above is not what was built: the owner chose a low-integrity
process (answer 2), so the spike measured that option and the loopback question does not arise (a Low process keeps
loopback and the network).

*Windows spike result (measured, no elevation at any point):*

1. A Low copy of the user's own token starts a child with `DuplicateTokenEx`, `SetTokenInformation(TokenIntegrityLevel,
   S-1-16-4096)` and `CreateProcessAsUserW`, with no privilege and no prompt. The child reports
   `Mandatory Label\Low Mandatory Level`. Git, Java, `javac`, Node, Python, Windows PowerShell, `pwsh` and `cmd` run
   at Low; so do network and loopback sockets.
2. A Medium label (`ML;;NW`) and a no-read-up label (`ML;;NRNW`) can be set by the owner on their own files and
   folders with `SetNamedSecurityInfoW(LABEL_SECURITY_INFORMATION)`. It needs `WRITE_OWNER`, which a user profile
   grants through inheritance. A folder outside the profile (for example `C:\work\x`, `D:\repos\x`) grants the owner
   only Modify: the code then adds `WRITE_OWNER` for the owner to the access list with `SetEntriesInAclW`, keeping every
   other entry. (Rewriting the list through `java.nio`'s ACL view was tried first and silently corrupts inherited and
   inherit-only entries; it is not used.) A folder label with inheritance rewrites the labels of everything below it
   in one call, but skips without an error any child on which the caller lacks `WRITE_OWNER`; the code therefore
   verifies each node after labelling where it matters. Links are not followed by the propagation (checked with a
   junction and a symlink).
3. No-read-up works as written: a Low process cannot read or list the labelled file or folder.
4. **What a label does not stop:** a Low process may delete or rename a Medium-labelled file that sits in a
   Low-labelled folder, because the delete is checked against the folder. The trusted files inside the lane
   (`.git`, `gitdir`, `commondir`, `config.worktree`, provider configuration) are therefore labelled Medium **and held open
   without delete sharing** for the length of the run, which makes both the delete and the rename fail with a sharing
   violation. Verified.
5. **What no-read-up on a parent breaks:** Git and PowerShell fail on a working folder whose parent they cannot read
   ("failed to stat", `Get-Location` falls back to `C:\`). The folders on the way from a hidden root to the lane are
   therefore left readable (their entries can be listed, their names are visible) and every other entry inside them is
   no-read-up. The consequence: the *names* of the entries that sit directly in those folders (for example the file name
   `daemon.endpoint`, the names of other lanes) are visible to a command; their contents and everything below are not.
6. **What does not run at Low:** programs built on the MSYS2 or Cygwin runtime, which includes the shell that Git for
   Windows ships (`sh.exe`, `bash.exe`, `ls.exe`): they fail at start creating an object directory under
   `\BaseNamedObjects` (`0xC0000022`). Git itself (a native build) runs, but a Git feature that starts `sh` does not
   (the hooks are neutralised anyway). Agents on Windows must use `cmd`, PowerShell or direct argv.

*Built:*

1. `WindowsLowIntegritySandbox` (`...project.execution.sandbox`), registered in `Sandboxes.platform()` for Windows.
   The probe starts a real Low child and checks that it runs at Low, writes its own folder, cannot write an
   unlabelled file and cannot read a no-read-up file, then checks from outside that nothing leaked.
2. The `SandboxPolicy` maps to labels: `writable` Low (inheritable); `hidden` Medium no-read-up (inheritable, set once
   per process and verified node by node, with the readable folders of item 5 left out); `readOnly` below a hidden root
   Medium without no-read-up; `readOnlyWithin` and `emptyReadOnlyFiles` Medium plus a hold (item 4). An empty
   `config.worktree` is created in the lane's Git administrative directory when absent.
3. `WrappedCommand` gained an optional `Hosting` (start the process, and finish the run), because a lowered token cannot
   be expressed as a command-line prefix. `ProjectProcessExecutor` starts through it when present and calls `finish()`
   before it deletes the private temp. The executor now takes process info and descendants by pid, because the Windows
   `Process` is its own object. `WindowsLowIntegrityLauncher` creates the process suspended in a kill-on-close job,
   inherits only the three standard handles (a handle list), and `destroyForcibly()` ends the whole job.
   `AgentCommandProcessArchitectureTest` now also lists any file that calls `CreateProcessW`/`CreateProcessAsUserW`.
4. The label of a writable root is kept between two runs on the same root and restored when a run starts on another
   root or the process ends, because relabelling is proportional to the number of files (3,000 files: 1.1 s the first
   time, then 0.5 ms; restoring 0.3 s).
5. `SandboxAdversarialTest` runs on both systems from one script per attack (shell on Linux, Windows PowerShell on
   Windows). 17 tests on Windows (one is Linux-only and skipped), all attacks of stage 2 except the symlink one, plus a
   test that every attack fragment succeeds where the lane may write (so an attack that fails fails because of the
   sandbox) and a test that an idle lane is not writable after its run. `WindowsLowIntegritySandboxTest` (7 tests) covers the
   token level, the label lifetime and the argument quoting.

*Limits, stated:* all Low objects share one level, so two lanes running at the same moment can write each other (no
per-lane restricting SID; it would need no elevation, but would make user-profile toolchains unreadable, see the
fallback above); a Synesis crash during a run leaves that lane Low until its next run; a path that is read-only inside
the lane but does not exist (for example `.claude` before the provider created it) can be created by the command, as
on Linux; user-profile folders that the policy does not hide stay readable and are not writable except the redirected
`HOME`/`TEMP`; MSYS2 programs do not run (item 6); the ADR's stage 3 items not built: `project sandbox paths` (nothing
needs a grant, because Low reads what the user can read), profile cleanup (there is no profile), and doctor wiring
beyond the probe's reason text. The Windows gate was run on this computer only.

### Stage 4 — macOS sandbox (about two to three days; needs a Mac)

Tasks: `MacSandboxExecSandbox` with the generated profile, detection and probe, environment and Git handling as stage 2.
Spike first on a real Mac (a Mac is needed; none is known to be available, open question 7).

Tests: the adversarial list; positive builds; profile syntax rejects hostile path characters (paths are escaped, a path
containing a quote or a parenthesis cannot add a rule; test).

Completion criteria: green on a Mac, or the stage is marked NOT BUILT in the ADR and macOS refuses (stage 5) with
the fix text, which is a valid outcome and is stated to the owner.

**Built (UNVERIFIED) 2026-10-27, written from the documentation; no Mac was available (owner's decision 3).**
`MacSandboxExecSandbox` generates the Scheme profile from the `SandboxPolicy` and wraps the command with
`/usr/bin/sandbox-exec -p <profile>`; it is registered in `Sandboxes.platform()` for macOS. Rules, in order (the last
match wins): write denied under `/`; `/dev` pseudo-devices writable; `hidden` roots and `$HOME` denied for read and write;
`readOnly` roots readable; `writable` roots read-write; `readOnlyWithin` and `emptyReadOnlyFiles` denied for write.
Network, exec and fork stay allowed. A path is quoted as a Scheme string (quote and backslash escaped, control characters
refused). Known gap: an `emptyReadOnlyFiles` entry cannot be made to read as empty (the caller creates it empty). **Fail
closed:** `probe()` reports unavailable ("macOS sandbox not yet verified on a real Mac (ADR-0104 stage 4)"), so
`run_command` refuses on macOS, unless the test-only JVM property `-Dsynesis.sandbox.macosUnverified=true` is set, which
runs the real probe (same three facts as Linux). Tested anywhere: the profile text (ordering, quoting of quotes,
parentheses, spaces, control characters) and the default refusal. **Must be checked on a real Mac before the switch and the
refusal are removed:**

1. `sandbox-exec -p` still exists and accepts this profile (no syntax error, no deprecation refusal on that macOS version).
2. The probe returns available with the switch set.
3. `SandboxAdversarialTest` with `SYNESIS_REQUIRE_SANDBOX=true`: every attack fails, and every positive fragment succeeds.
4. `git status`, `git diff`, `git add` and `git log` work in a lane worktree whose administrative directory is under the
   hidden control checkout (read-only `.git`, writable `.git/worktrees/<lane>`).
5. A toolchain build (Gradle offline or Maven, Node) works with the toolchain folders in `readOnly`, and `$HOME` hidden
   does not break it (the redirected `HOME` and temp are writable).
6. Loopback and outbound network work; the sandbox does not touch them.
7. Canonical paths: `/tmp` and `/var` are symlinks to `/private/...`; confirm the policy paths are the real ones, and that
   `subpath` on a path below a hidden root is read-writable as the order intends.
8. Then remove `UNVERIFIED_PROPERTY` handling from `probe()` and record the result here.

### Stage 5 — Refusal, the override, doctor, and the provider guards (about two to three days; needs stage 2)

Tasks:

1. `SANDBOX_UNAVAILABLE` and `SANDBOX_SETUP_FAILED` as `AgentReason`s with the ERROR/MESSAGE/FIX text per platform;
   the operator request for a gate that could not run.
2. `project sandbox allow-unsandboxed|deny-unsandboxed|show` writing the signed decision; `SANDBOX=` evidence on every
   command result and in the UI.
3. Doctor findings listed above, including the live probe, and `doctor --fix` for stale profiles only.
4. Provider guards: Claude hook matcher widened to `Read|Glob|Grep` and denying Synesis state paths; headless
   path-scoped deny rules (spike the syntax); the lane's hook and config files made read-only by the sandbox (done in
   stages 2 to 4) re-checked here. Codex residual recorded in the manual and in doctor output.
5. ADR-0094, ADR-0089, ADR-0091 and the provider manual updated (what changed, and that `run_command` is sandboxed).

Tests: no sandbox (a fake `Sandbox` that reports unavailable): `run_command` returns the three lines and does not
start the command; the validation gate does not run, integration waits, one operator request appears and is not raised
again on the next pass; a signed `ALLOW` lets it run and marks the evidence; an `ALLOW` signed by a non-owner key is
invalid; `deny-unsandboxed` after `allow` takes effect on the next command; sandbox available and setup failing:
`SANDBOX_SETUP_FAILED`, never unsandboxed; the Claude hook denies `Read` of `identity.bin` (unit) and the deny rule
works in a real headless Claude run (manual, recorded); doctor output on each state.

Completion criteria: all green; one real run (ADR-0102's loop) of two agents on this PC with the sandbox on and the
adversarial commands asked of them through `run_command` (the real-run evidence is separate from the source tests and
is reported as such, per AGENTS.md).

### Suggested parallel tasks

After stage 1's entry type exists, the work splits as follows. Owners are suggestions; test subagents follow the
repository's Sonnet 5.5 medium rule.

- **A (coordination):** stage 1 items 1 to 2 and 9 (entry type, projection, validity, unknown-type behaviour,
  version gate). Blocks everything that writes or reads policy.
- **B (workspace and cli):** stage 1 items 3 to 8 (locate overlay, commands, migration, doctor, fitness tests). Starts
  once A's codec exists (a day).
- **C (sandbox core and Linux):** stage 2, starting with the spike, in parallel with A and B because it touches
  `ProjectProcessExecutor` and a new package only.
- **D (Windows):** stage 3 spike can start on day one, independently; the implementation waits for C's interface.
- **E (macOS):** stage 4, only if a Mac is available; independent after C's interface.
- **F (refusal, override, doctor, provider guards):** stage 5; Claude hook and deny-rule spike can start on day one,
  the rest after C and B.
- **G (adversarial test harness):** written against C's interface early, as the shared acceptance test for C, D, E.

Files that two tasks touch: `ProjectProcessExecutor` and `ExecutionRequest` (C and F), `DoctorService` (B and F),
`ProjectApplicationService` (B only), `AgentReason` (F). Those four changes are small and should be merged in the
order C, F.

## Published contracts (additive)

- **New log entry type** `PROJECT_POLICY_SET` (additive wire code). Writers emit it only after the baseline; readers
  that do not know it carry it and do not apply it (DERIVED; verified in stage 1), with the minimum-version gate for
  projects that have a baseline.
- **`project.json`:** `validation` and `review` become read-only inputs of migration; no new field. The file's
  `schemaVersion` does not change. Removing the fields is a separate, explicitly authorised change.
- **CLI:** `project validation set|show|clear` keep their arguments and `VALIDATION_*` keys; they no longer print
  `PROJECT_FILE_CHANGED` or `PROJECT_FILE_COMMITTED` (this is a removed output line of a published command: it is
  listed here for the owner's approval, and the lines can stay as fixed `0` and empty values for one release if the
  owner prefers). Additive: `POLICY_REVISION`, `DECISION_ENTRY`, `project review`, `project sandbox`,
  `project policy`.
- **MCP:** no new tool. `run_command` gains a `BLOCKED` reason `SANDBOX_UNAVAILABLE` and `SANDBOX_SETUP_FAILED` (a new
  enum value; additive) and a `sandbox` field in its result evidence. An old client that does not know the reason
  reads it as a blocked command with an instruction string.
- **Behaviour change, not a contract change:** commands that wrote outside the lane (a build that writes to
  `~/.gradle`) stop working in a sandbox unless the owner lists the path. Stated here so it is not read as a bug.
- Deprecation introduced: none. The `project.json` policy fields are ignored, not deprecated with a date.

## Fitness functions

- No `ProcessBuilder` for an agent command outside `ProjectProcessExecutor` (source scan).
- `ProjectLocation.validation()/review()/validationAlsoInLane()` are the only readers of policy; nothing reads them
  from the file after migration (source scan).
- `McpToolCatalog` has no tool that changes a policy, a membership, a trash, or an operator request's closing
  (catalog test).
- The adversarial test list is green on every platform that claims a sandbox; a platform with no passing list is
  reported `UNAVAILABLE`.
- Doctor reports `SANDBOX_PROBE_FAILED` if any negative fact does not hold on the installed computer.

## Consequences

- An agent's `run_command` can no longer switch off its own gate, move `main`, or read the node key. The most direct
  bypass the owner parked on 2026-10-18 is closed. The project's policy has a history, in the log, with a signer and
  a revision.
- `project.json` stops being a file anyone edits to change behaviour, and `project validation set` stops committing to
  Git, which removes a class of "foreign changes" refusals.
- Real runs get slower to start (the sandbox setup is a few milliseconds on Linux and macOS, ASSUMED; an AppContainer
  profile and ACL grants are heavier on Windows, to be measured in stage 3) and some tools need a path declared in
  `sandbox.paths` before they work. Agents lose `git commit` in a lane.
- Acceptance runs inside the Podman Linux hosts and the VM may have no usable sandbox (ASSUMED); those projects need a
  signed `ALLOW`, which is the intended use of the override, and the acceptance reports must say which runs were
  sandboxed.
- The guarantee depends on the key staying out of an agent's reach. For `run_command` that is enforced; for the
  provider's own read tools it is enforced for Claude after stage 5 and not for Codex, until the owner decides on the
  operator key.
- A new platform dependency per OS (bubblewrap on Linux; Windows and macOS use what the OS ships).

## Decisions for the owner (open questions)

1. **The key and Codex.** Is the Codex residual (its own read tools can read the node key) acceptable, or do you want
   the separate **operator key** (passphrase-wrapped, used only for decisions) built now? Recommended: build it as stage
   6 if you intend to run Codex agents you do not trust; accept the residual if all your agents are your own. It changes
   ADR-0098's "the owner key is the node key".
2. **Windows mechanism.** AppContainer first with a measured fallback (the designer's choice), or the restricted-token
   and low-integrity design first? The spike decides on measurements, but you may prefer the lower-risk one.
3. **Localhost on Windows.** AppContainer blocks loopback unless exempted (needs one elevated command per lane or
   a one-time machine setting). Tests that start a local server will fail until then. Are you willing to run that
   one command, or should loopback-heavy projects use the signed unsandboxed allowance on Windows?
4. **Linux network namespace.** Share the host network (simple; abstract Unix sockets of the host stay reachable) or
   `--unshare-net` plus a user-mode network (`pasta`/`slirp4netns`; a new dependency, closes that gap)?
5. **Agents and `git commit`.** Agents lose `git commit` in a lane (Synesis snapshots instead). Is that acceptable, or
   should a Synesis tool commit for the agent?
6. **Landlock as a Linux fallback** when `bwrap` is missing but the kernel supports it: worth a later stage?
7. **macOS.** Is a Mac available to build and test stage 4, or should macOS refuse and wait?
8. **Existing `project validation` output.** Remove `PROJECT_FILE_CHANGED`/`PROJECT_FILE_COMMITTED`, or keep them
   (empty) for one release?
9. **Shared caches.** Default private per-lane caches (slow first build, no cross-lane poisoning) or default shared
   Gradle/npm caches writable (fast, poisonable)? The design defaults to private and lets you list shared ones.
10. **Existing signed statements.** Membership and other owner statements keep the node key's trust model. Do you want
    them also covered by the operator key (question 1) when it exists?

## Not decided

- The operator key (question 1) and any passphrase prompt.
- Resource limits (CPU, memory, process count, disk) inside the sandbox.
- Sandboxing the provider processes themselves, which needs the MCP server split out of the provider's process tree
  (for example a local daemon-hosted MCP proxy), and so a separate ADR.
- Containers or Landlock as additional backends.
- A signed record of which computers are known to lack a sandbox, shown to other members.
- Undoing a decision: it is a new statement, not a deletion; revoking an old statement is not designed, as in ADR-0098
  step 6.
