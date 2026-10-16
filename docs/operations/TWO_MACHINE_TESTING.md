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

This is the flow for one project that runs on two computers (ADR-0098 step 2): computer A is the leader and keeps the
authoritative log; computer B is a member, runs its own agents, and sends each entry to A. It needs a Git remote that
**both** computers can push to and fetch from, because code travels through it and never through the log. Name the
remote in each clone (`git remote -v`); `origin` is not assumed. A bare repository on a shared folder or a host works.

The commands print `KEY=VALUE` lines. Run each one in the project directory, or give `--project <directory>`.

**On A (the owner), once:**

1. `synesis project share --code-remote origin` makes this computer the leader. It prints `NODE_ID` (A's node ID).
2. `synesis project add-member <B's node ID>` allows B. B's node ID comes from step 2 below.

**On B, once:**

1. `git clone <the same repository>`, then `synesis init` in the clone. B must hold the same `.synesis/project.json`
   as A, which the clone carries; the Link session is refused when the project identity differs.
2. `synesis identity show --project .` prints B's `NODE_ID`; give it to A for `add-member`.
3. `synesis project follow --leader <A's node ID> --code-remote origin`. It refuses a log that already has entries
   (`LOG_NOT_EMPTY`): a member's log is a copy of A's, so it must start empty. Do not start agents on B before this.

`share` and `follow` stop the project's runtime if the installation daemon runs one (`RUNTIME_STOPPED=true`); the
runtime starts again with the new role the next time `synesis link` needs it. A runtime started by `synesis ui` in
a terminal is not the daemon's: stop it there and start it again. `add-member` and `remove-member` need no restart; the
leader reads its member list for every request. A member added after its session was established is served at once, but
hears of new entries only at its next 15 second pull.

**Connecting (either side can invite). On B:**

1. `synesis link invite --expect <A's node ID>` prints `INVITE_LINK=synesis://join/...` and waits.
2. On A: `synesis link join "<INVITE_LINK>"`. It prints `ANSWER_LINK=synesis://answer/...` and waits.
3. On the same network that is all. Across two networks, give A's `ANSWER_LINK` to B:
   `synesis link answer <OPERATION_ID printed by invite> "<ANSWER_LINK>"` (ADR-0097 says which networks get through).

Both commands end with `LINK_STATE=CONNECTED` and `PEER_NODE_ID=<the other computer>`, or `LINK_STATE=WAITING` when
`--wait-seconds` (default 300) ran out and `LINK_STATE=FAILED` when the invitation ended without a session. The
connection stays with the project's runtime after the command ends. From then on B pulls A's log and sends its entries
to A, and A merges B's lanes without an agent of its own. `synesis coordination status` on B shows
`REPLICATION_STATE`. `synesis project remove-member <node ID>` on A stops serving a member; its earlier entries stay.

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
redial, a redial that worked) are in `replication.log` beside `replication.status`, in the project's coordination
folder (`.synesis/coordination`). While the link is down a member's proposals answer `LEADER_UNREACHABLE` at once, and
the member catches up with a pull (within 15 seconds) once the link is back.

Not done in step 2: a restart of either coordination server still needs a new `link invite` and `link join` (neither
side remembers the other's endpoints or the invitation across a restart), and a member list is local state of A.
