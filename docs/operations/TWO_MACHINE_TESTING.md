# Two-machine testing

The required two-machine test must use two physical computers on a documented direct-network
scenario, exchange the signed compact bootstrap out of band, require the expected identity,
establish `synesis-link/1`, exercise a typed stream, record
liveness, and verify graceful and abrupt-close behavior. Do not claim this evidence until it is run.

For candidate and direct-connectivity validation, record the provider list,
normalized candidate count, compatible pair ordering, attempt diagnostics,
selected authenticated/control-ready winner, and loser cleanup. A
local-interface candidate or successful QUIC transport does not prove physical
reachability across a NAT or firewall. Use the
[`NETWORK_VALIDATION_MATRIX.md`](NETWORK_VALIDATION_MATRIX.md) for topology
rows and evidence status. Hosted rendezvous, STUN, TURN, and relay services are
not fallback requirements; unsupported direct topologies must fail clearly.

The reproducible first demonstration is documented in
[`docs/demo/FIRST_DEMO.md`](../demo/FIRST_DEMO.md). Its required physical
evidence classification is `TWO_MACHINE_VERIFIED`; the existing JVM/process
tests remain `TWO_PROCESS_VERIFIED` and must not be relabeled.

For launcher validation, build with
`./gradlew.bat :cli:installDist --dependency-verification=strict` and run the
generated `cli\build\install\synesis\bin\synesis.bat` on both machines. The
normal flow is `synesis link invite` on one machine, copy its `INVITE_LINK=synesis://project/SLP1-...`
output to the other machine, and run `synesis link join "<link>"` there. On different networks, give the
`ANSWER_LINK` the join prints to the waiting invitation (`synesis link answer`), or invite from the browser (`synesis ui`)
with "Ask my router to open a port" ticked; see ADR-0097 for which networks each gets through (the one-shot
`synesis host`/`join` commands were removed on 2026-10-20, ADR-0103).
No signaling server or mailbox is used. Use a temporary PATH change only in the current
shell if desired; do not record a permanent PATH or installation claim.
Capture sanitized launcher output in `docs/evidence/PHYSICAL-CLI-ONBOARDING.md`; the existing
`PHYSICAL-DEMO-2026-07-20.md` is diagnostic `DemoCli` evidence and does not
prove the generated launcher path.

Record only sanitized machine labels, operating systems, Java versions,
topology/address classes, candidate counts, redacted pair ID, expected and
authenticated node IDs, control readiness, LIVE state, request/result status,
close reason, cleanup, and exact command/date. Do not record private keys,
identity or descriptor files, passwords, full personal addresses, usernames,
access tokens, or absolute personal paths.

## Two computers, one project

This is the flow for one project that runs on two or more computers (ADR-0098): computer A (the owner) shares the
project; computer B (the friend) joins it. Since step 6 every computer writes its own log and keeps a copy of everyone
else's, who belongs to the project is written in the logs (`PROJECT_SHARED`, `JOIN_REQUESTED`, `JOIN_ANSWERED`,
`MEMBER_REMOVED`, ...), and each member dials every other member it can reach. Code moves between the computers over
Link (ADR-0098 step 3); no Git remote is needed. A Git remote they can reach, if there is one, is an optional mirror.

`synesis project share` on A shares the project and prints `RECOVERY_WORDS=` once: 24 words that are stored nowhere.
Write them down; with them `synesis project recover` (which reads them from standard input) makes any member computer
the owner's, for example when A is lost. `project share --new-recovery-key` replaces them. A project shared by inviting
alone, or migrated from before step 6, has no recovery key until `project share` is run on the owner's computer.

**One link does it.** The commands print `KEY=VALUE` lines; run each one in the project directory, or give
`--project <directory>`.

1. **A (the owner) invites.** In the browser UI, open the project's Network view and press **Create Invitation**; or run
   `synesis link invite`. This writes nothing on A: A keeps working exactly as before until it accepts B. It prints one
   link, `INVITE_LINK=synesis://project/SLP1-...`, and the code remote the link names, `CODE_REMOTE=<name>` and
   `CODE_REMOTE_URL=<url>` (the configured `code.remote`, or the project's only Git remote; never credentials, and only
   an `https://`, `ssh://` or `user@host:path` address). A project with several remotes and none configured, no remote, or
   a remote that is a local path or a network share prints `CODE_REMOTE_URL=NONE` and a `NOTE=`: code then moves
   over Link only, with no mirror. Any member may invite the same way (since step 6): it holds the joiner and records its
   request, and the owner accepts it. The command waits for B.
2. **B (the friend) opens the link.** Click it (the installer's `synesis://` handler opens the browser UI with the link
   filled in, on the installation's home workspace if B knows no project yet; the UI first shows the inviter, the project,
   the code remote URL and the expiry, and B presses **Join**), or paste it into the **Join from an invitation** box on the UI's Projects page or Network view, or run
   `synesis link join "<INVITE_LINK>"`, with or without `--project`. Synesis looks the project's identity up in B's
   known-project registry:
   - **Known:** that folder is used, with no question.
   - **Not known, but B has it:** the UI asks for the folder (type it, or **Choose folder**); on the command line pass
     `--project <folder>`. The folder is accepted only if its `.synesis/project.json` has the same project identity; a
     clone that holds only that file is made a replica (`initReplica`) and registered.
   - **Not on this computer:** B chooses an empty folder, or types a new one (`synesis link join <link> --into
     <folder>`). It becomes a copy of the project with no code yet; once A accepts, B's coordination server fetches A's
     branch over Link, in pieces, and checks it out under A's branch name (ADR-0098 step 3). The URL the link carries
     (`CODE_REMOTE_URL`), if any, is only a faster start: B may clone it and choose the clone instead.

   Then, with no node ID typed, B's computer becomes a member of A, sets its code mirror when the link names a URL and the
   folder already holds a clone (the remote of the clone whose URL matches A's; if none matches, a remote named
   `synesis` is added), and connects. Code moves over Link either way. It refuses, and writes
   nothing, when B's copy already has log entries (`LOG_NOT_EMPTY`), follows another leader, or leads members of its
   own. B should not start agents before this.

   With a mirror, B's computer then asks it for its head, with no prompt (`git ls-remote`). If that fails, the join still
   goes on, and the UI (or `CODE_REMOTE_CHECK=CODE_REMOTE_UNAVAILABLE` and `CODE_REMOTE_DETAIL`) says what Git said and
   what to run: `git fetch <remote>` once in a terminal in the project folder, signing in if Git asks, so the sign-in is
   stored. Code still comes over Link while A is connected; the mirror is asked only when it is not, and then an agent on
   B that cannot get the code stops with `workspace_not_ready` and the same detail, and `synesis provider status` shows
   `WORKTREE_BINDING_DIAGNOSTIC=CODE_REMOTE_UNAVAILABLE`. Synesis reads Git's system
   configuration (where Git for Windows registers its credential manager) but not `~/.gitconfig`, so a credential
   helper set only there, for example by `gh auth setup-git`, is not used.
3. **A (the owner) accepts.** B's connection is authenticated and the project identity is checked, but nothing is served
   on it until A's person accepts it. The Network view lists `<node> wants to join <project>` with **Accept** and
   **Decline**; or run `synesis link requests`, then `synesis link accept <node ID>` or `synesis link decline <node ID>`.
   Accepting makes A the leader, adds B as a member, sets `code.remote` to the remote the link named, if any, as a mirror,
   and starts serving B at once; a link with no URL is accepted the same way, and code moves over Link. Declining closes B's session.
   Since step 6 the request is an entry (`JOIN_REQUESTED`, written by the computer that holds B's session) and the
   answer too (`JOIN_ANSWERED`), so every member sees a waiting request and it outlives a restart. Only the owner
   answers, unless the owner ran `synesis project join-policy ANY_MEMBER`, after which any member may accept (a decline
   stays the owner's).
4. **Two networks.** If B does not connect within a few seconds, B's UI (and `synesis link join`) shows an answer link
   (`ANSWER_LINK=synesis://answer/...`). Give it to A: paste it into the answer box of the Network view, or
   `synesis link answer <OPERATION_ID printed by invite> "<ANSWER_LINK>"` (ADR-0097 says which networks get through).
   Or A starts with the router-port option, which asks the router to forward a port.

`synesis project add-member <node>` lets a computer in by name (a request and its acceptance at once), and
`synesis project remove-member <node>` on the owner's computer removes one (`MEMBER_REMOVED`): its lanes end on every
computer, its entries after that point count for nothing, and it is no longer dialed or admitted. `follow` stays for
scripts; the one link replaces it. `follow` and `share` stop the project's runtime if the installation daemon runs one
(`RUNTIME_STOPPED=true`); the link does not need a restart. Before step 6 the owner's member list was in
`replication.properties`; a coordination server of this build moves it into the log once when it starts, and the file
then says only `role=shared` (and `code.remote`, if any).

`link invite` and `link join` end with `LINK_STATE=CONNECTED` and `PEER_NODE_ID=<the other computer>`, or
`LINK_STATE=WAITING` when `--wait-seconds` (default 300) ran out and `LINK_STATE=FAILED` when the invitation ended
without a session, followed by `LINK_FAILURE=<reason>` and `LINK_FAILURE_DETAIL=<what happened and what to do>`
(`EXPIRED` when nobody connected in time; `link answer` after the invitation expired says the same). A join that
returned `WAITING` keeps dialing in the project's runtime until the invitation expires, so the answer can still be
pasted after it. `CONNECTED` means the session exists, not that A accepted it. The connection stays with the project's
runtime after the command ends. Once A has accepted, B pulls A's log and sends its entries to A, and A merges B's lanes
without an agent of its own. `synesis coordination status` shows, on every computer of a shared project,
`REPLICATION_ROLE=shared`, `REPLICATION_OWNER=<node>`, `REPLICATION_RECOVERY_KEY=SET|NONE`, one line per member,
`REPLICATION_MEMBER=<node> STATE=CONNECTED since=<time>` while that member's session is up, `STATE=DISCONNECTED
since=<time>` after it ended (or, for a member that has not connected since this coordination server started, since that
start) and `STATE=THIS_COMPUTER` for itself, and `JOIN_REQUEST=<node> inviter=<node>` for each waiting request.
`STATE=NO_COORDINATION_SERVER` means this computer's coordination server is not running.

**Every member dials every other.** Each member hosts one retained invitation of its own listener, its member card,
and publishes it in its log (`MEMBER_CARD`); every 15 seconds it dials the card of each member it holds no session with
(of two members the one with the lower node ID first, the other after a minute). So the members keep working together
when the owner's computer is off, and two that cannot reach each other still get each other's entries through any
member both reach. The card's port is kept in `replication.card` and listened on again after a restart.

**A dropped link redials by itself.** The computer that ran `link join` (the one that dialed) dials the other again when
the session ends, at the endpoints of the invitation it used, one second later and then every 2, 4, 8, 16, 30 seconds
and every 30 seconds after that, for as long as it is still the leader of that member or a member of that leader. The
computer that ran `link invite` keeps its port open and admits the redial with no new invitation, but only from the
leader it follows (on a member) or from a current member (on a leader). Nothing needs doing; if the dialing computer
can only dial out (no inbound firewall rule on Windows, say), that is why it is the one that dials. Both computers
use a looser liveness timing than a one-shot onboarding session (heartbeat 5 s, `SUSPECT` after 15 s, `EXPIRED`
after 30 s), so a pause of up to half a minute does not end the session.

`synesis coordination status` shows `REPLICATION_LAST_DISCONNECT=<reason> <time> <details>` for the last session that
ended, with the reason Link closed it for (`LIVENESS_EXPIRED`, `TRANSPORT_CLOSED`, `REMOTE_REQUEST`, `LOCAL_REQUEST`,
`PROTOCOL_ERROR`) and how long it had lasted. The last 100 events (sessions starting and ending, the first failure of a
redial, a redial that worked) are in `replication.log` beside `replication.status` (B) and `replication.members` (A), in the project's coordination
folder (`.synesis/coordination`). While the link is down a member's proposals answer `LEADER_UNREACHABLE` at once, and
the member catches up with a pull (within 15 seconds) once the link is back.

**A restart of either coordination server recovers by itself.** Each computer writes down, in `replication.peers` in the
project's coordination folder (local, excluded from version control, never sent to a peer), the host it dialed and its
invitation link, and the UDP port it hosted an invitation on with the node IDs it admitted. When the server starts again
(`synesis project share`/`follow` stop it, and after a reboot nothing runs; `synesis agents run` and every `synesis link ...`
start it) the computer that dialed dials the same
host again, and the computer that hosted listens on the same port again and admits exactly the nodes it admitted before
that its role still names. No new invitation is needed. `replication.log` says what happened: `DIAL_RESUMED`,
`HOST_RESUMED` and, when something cannot resume, `DIAL_RESUME_SKIPPED`/`HOST_RESUME_SKIPPED` (the role no longer names
the peer), `PEERS_IGNORED` (the file cannot be read) or `HOST_RESUME_FAILED`. `remove-member` and `follow` drop the
records of nodes the role no longer names.

`synesis agents run` asks the installation daemon for the project's runtime before it starts any agent, starting the
daemon and the runtime when they are not running, and prints `PROJECT_RUNTIME=READY`. In a shared project (a
`replication.properties` in the coordination folder) it refuses with `ERROR=PROJECT_RUNTIME_UNAVAILABLE` and the `REASON`
when they cannot be started, rather than start agents that would not reach the other computer; in a project on one
computer it prints `PROJECT_RUNTIME=UNAVAILABLE` with `PROJECT_RUNTIME_REASON` and starts the agents. An interactive agent
session does not start it: its MCP server (`synesis-mcp`) does not start the daemon. After a
reboot, run `synesis link requests` (or `synesis agents run`) in the project folder before opening such a session there.
A `replication.endpoint` left from before the reboot is not taken for a connection: a process that finds nothing listening
there treats the other computer as not connected.

What still needs a new `link invite` and `link join`: the hosting computer's port was taken by another program when it
started again (`HOST_RESUME_FAILED`), the dialing computer can no longer reach the endpoints of the invitation (the host's
address changed), or the file was lost or unreadable. A member list is local state of A.

## Fast loop rules

These rules come from ADR-0102 (items 3, 7, 8 and 9).

**Splits are short and firewall-only (item 3).** A Link split lasts about 5 minutes. Make it with the VM firewall
partition: `~/accept/partition.sh <seconds>` on the VM, for example `~/accept/partition.sh 300`. Never pull a cable.
A pulled cable also cuts the agents' model provider, so the run then tests the network, not the protocol. The
partition drops only the traffic between the two computers.

**Fixers test only what they touch (item 7).** A fixer runs only the affected test classes, on Linux, with
`D:\Podman\work\linux-gate.ps1 :workspace:test --tests '*AgentNextActionServiceTest'`. Use the test classes the fix
touches. Windows runs only the touched classes too. One final Windows gate runs before a push.

**Each parallel session gets its own state and Gradle home (item 8).** Run Gradle with
`D:\SynesisAcceptance\lab\gradle-session.ps1 -Session <name> <gradle arguments>`, for example
`D:\SynesisAcceptance\lab\gradle-session.ps1 -Session fix-42 :workspace:test --tests '*AgentNextActionServiceTest'`.
The session uses `C:\t\<name>` for temp files and test state, and `D:\gradle-homes\<name>` as its Gradle home. Pick a
new name for each parallel session. Run it from inside the checkout.

**A background gate checks each new commit (item 9).** Start it with
`D:\SynesisAcceptance\lab\background-gate.ps1 -Branch <name> [-Checkout <path>]`. It runs the whole Linux gate on the
newest commit of the branch each time the branch moves, and checks every 60 seconds. It reads local refs only and
fetches nothing. The result goes to `D:\SynesisAcceptance\lab\gate-status.txt`: `RESULT=GREEN`, `RED`, `RUNNING` or
`INTERRUPTED`, with the failing lines. Stop it with Ctrl+C.

## Fast loop scripts

Three scripts in `D:\SynesisAcceptance\lab\` cover a real run end to end (ADR-0102 items 4, 5 and 2). Run the `.sh`
scripts from Git Bash and the `.ps1` script from PowerShell. They never start or stop Podman.

**Build once, copy to the VM (item 4).** `pwsh -File D:\SynesisAcceptance\lab\build-and-copy.ps1 <tag>` builds
`:cli:installDist` as `0.1.0-dev.<tag>`, copies it to `D:\SynesisAcceptance\offline\synesis<tag>` (with the
`synesis.cmd` shim), and copies the same distribution to `~/synesis/<tag>` for the VM users `synesis` and `synesis3`.
On the VM, `~/.local/bin/synesis` is a small wrapper that sets `JAVA_HOME=/opt/synesis/openjdk` (the VM has no `java`
on PATH) and runs `~/synesis/current`. The shared `/usr/local/bin/synesis` is not touched. `~/.local/bin` is not on
PATH for non-interactive ssh, so the lab scripts put it first, as agents are started. It prints the version seen from
each place. Options: `-Check` (read-only look at both VM users), `-NoVm`, `-NoBuild`, `-Force`. It keeps the last three
builds on the VM, which has little free disk.

**One call per run (item 5).** `bash D:/SynesisAcceptance/lab/run.sh <together|apart|three> <name> <tag> [seconds]`.
`together` has no split. `apart` partitions the PC and the VM with `~/accept/partition.sh` for `[seconds]` (default
300). `three` adds the VM user `synesis3` as a third computer. It creates and shares the project, invites and joins each
VM user, starts logwatch on every computer, starts the agents (`claude:sonnet:medium` on the PC, `codex:gpt-6-luna:high`
on the VM), waits for the runs to end, runs `evidence.sh`, and validates: the copies of `main` are equal, every computer
holds the same logs byte for byte, logwatch saw no log removed or changed, and the goal's test command passes on every
computer. The last line is `RESULT=PASS`, `FAIL`, `PARTIAL` (a run did not end COMPLETED) or `TIME_BOUND`. Add
`--check` to verify the prerequisites only, and `--help` for the goal file, prompt, agent counts and models. The goal
file may name its test command in a line `Test command: ` followed by the command in backticks.

**Evidence from every computer (item 2).** `bash D:/SynesisAcceptance/lab/evidence.sh <name> [tag]` copies each
computer's coordination folder, agent transcripts, `git log --all --oneline --graph`, refs, worktree list and leases to
`D:\SynesisAcceptance\evidence\<name>\<pc|vm|vm3>\`, and runs `synesis log dump` on each when the build has that command
(otherwise `log-dump.txt` says it is absent). `run.sh` calls it; run it by hand after any run that stopped early.
