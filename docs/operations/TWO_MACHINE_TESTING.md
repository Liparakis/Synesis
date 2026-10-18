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
normal flow is `synesis host`, copy its
`synesis://join/SLB3-...` output to the other machine, and run
`synesis join "<link>"`. On different networks, paste the `ANSWER_LINK` the
join prints into the waiting `synesis host`, or start the host with
`--open-router-port`; see ADR-0097 for which networks each gets through.
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

This is the flow for one project that runs on two computers (ADR-0098): computer A (the owner) is the leader and keeps the
authoritative log; computer B (the friend) is a member, runs its own agents, and sends each entry to A. Code moves between the
two computers over Link (ADR-0098 step 3); no Git remote is needed. A Git remote both can reach, if there is one, is an
optional mirror.

**One link does it.** The commands print `KEY=VALUE` lines; run each one in the project directory, or give
`--project <directory>`.

1. **A (the owner) invites.** In the browser UI, open the project's Network view and press **Create Invitation**; or run
   `synesis link invite`. This writes nothing on A: A keeps working exactly as before until it accepts B. It prints one
   link, `INVITE_LINK=synesis://project/SLP1-...`, and the code remote the link names, `CODE_REMOTE=<name>` and
   `CODE_REMOTE_URL=<url>` (the configured `code.remote`, or the project's only Git remote; never credentials, and only
   an `https://`, `ssh://` or `user@host:path` address). A project with several remotes and none configured, no remote, or
   a remote that is a local path or a network share prints `CODE_REMOTE_URL=NONE` and a `NOTE=`: code then moves
   over Link only, with no mirror. On a computer that is a member, `link invite` makes
   the plain connection invitation (no project link), as in the topology below. The command waits for B.
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
   and starts serving B at once; a link with no URL is accepted the same way, and code moves over Link. Declining closes B's session. A request ends when the
   invitation it used expires (ten minutes), and requests are not kept across a restart of A's runtime: B asks again
   with a new invitation.
4. **Two networks.** If B does not connect within a few seconds, B's UI (and `synesis link join`) shows an answer link
   (`ANSWER_LINK=synesis://answer/...`). Give it to A: paste it into the answer box of the Network view, or
   `synesis link answer <OPERATION_ID printed by invite> "<ANSWER_LINK>"` (ADR-0097 says which networks get through).
   Or A starts with the router-port option, which asks the router to forward a port.

`synesis project share`, `add-member`, `remove-member` and `follow` stay for scripts and for removing a member
(`remove-member` on A stops serving a member; its earlier entries stay). The other documented way in is the old one
(`share`, `identity show`, `add-member`, `follow --leader`, then `link invite --expect` / `link join`); the one link
replaces it. `follow` and `share` stop the project's runtime if the installation daemon runs one
(`RUNTIME_STOPPED=true`); the link does not need a restart.

`link invite` and `link join` end with `LINK_STATE=CONNECTED` and `PEER_NODE_ID=<the other computer>`, or
`LINK_STATE=WAITING` when `--wait-seconds` (default 300) ran out and `LINK_STATE=FAILED` when the invitation ended
without a session, followed by `LINK_FAILURE=<reason>` and `LINK_FAILURE_DETAIL=<what happened and what to do>`
(`EXPIRED` when nobody connected in time; `link answer` after the invitation expired says the same). A join that
returned `WAITING` keeps dialing in the project's runtime until the invitation expires, so the answer can still be
pasted after it. `CONNECTED` means the session exists, not that A accepted it. The connection stays with the project's
runtime after the command ends. Once A has accepted, B pulls A's log and sends its entries to A, and A merges B's lanes
without an agent of its own. `synesis coordination status` on B shows `REPLICATION_STATE`. On A it shows one line per
member, `REPLICATION_MEMBER=<node> STATE=CONNECTED since=<time>` while that member's session is up and
`STATE=DISCONNECTED since=<time>` after it ended (or, for a member that has not connected since A's coordination server
started, since that start). `STATE=NO_COORDINATION_SERVER` means A's coordination server is not running.

**A dropped link redials by itself.** The computer that ran `link join` (the one that dialed) dials the other again when
the session ends, at the endpoints of the invitation it used, one second later and then every 2, 4, 8, 16, 30 seconds
and every 30 seconds after that, for as long as it is still the leader of that member or a member of that leader. The
computer that ran `link invite` keeps its port open and admits the redial with no new invitation, but only from the
leader it follows (on a member) or from a current member (on a leader). Nothing needs doing; if the dialing computer
can only dial out (no inbound firewall rule on Windows, say), that is why it is the one that dials. Both computers
use a looser liveness timing than a one-shot `synesis host`/`join` (heartbeat 5 s, `SUSPECT` after 15 s, `EXPIRED`
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
