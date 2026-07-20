# First Synesis Link Demonstration

## Zero-configuration onboarding (source-run)

On clean profiles the normal LAN flow is:

```powershell
./gradlew.bat :link:synesisCli --args="host"
./gradlew.bat :link:synesisCli --args="join \"synesis://join/<share-link>\""
```

The host automatically creates or reuses its local identity, binds its listener
before gathering candidates, signs a ten-minute single-use invitation, and
prints the copyable link plus a compact Unicode QR when the terminal fits it.
The joiner verifies the invitation,
creates or reuses its own identity, gathers candidates, authenticates both
identities through the existing Link handshake, exercises control/liveness and
the bounded demo request, then closes gracefully.

Stable terminal failure lines use `FAILURE=...`; common bounded outcomes are
`INVITE_INVALID`, `NO_USABLE_CANDIDATE`, `CONNECTION_FAILED`,
`HOST_IDENTITY_MISMATCH`, and `HOST_TIMEOUT`. A failure before identity
authentication releases the host's short reservation; authenticated admission
is consumed even if later control work fails.

QR output is optional: `QR_RENDERED=COMPACT` means it fit the terminal;
`QR_SKIPPED=TERMINAL_TOO_NARROW` or `QR_SKIPPED=UNICODE_UNSUPPORTED` means the
copyable `SHARE_LINK` remains the complete onboarding path.

The source-run command is not an installed `synesis` package; packaging remains
deferred under `SL-D-024`. The existing manual `DemoCli` procedure below stays
available as a diagnostic fallback and is not replaced by onboarding.

This is a source-run validation demo, not a production installer or a general
Synesis cooperation protocol. It proves one authenticated, control-ready,
live QUIC session carrying one bounded `synesis-demo-work/1`
`describe-session` request and correlated result.

## Diagnostic DemoCli prerequisites

- Two separate computers, labeled A (listener) and B (client).
- Java 25 and this repository on both computers.
- A documented network path allowing UDP to the chosen listener port.
- A PKCS#12 TLS keystore on A containing a server private key and certificate.
- No generated identities, descriptors, or keystores committed to the repository.

Create a demo TLS keystore on A outside the repository if needed:

```powershell
keytool -genkeypair -alias synesis-demo -keyalg RSA -keystore $env:TEMP\synesis-demo.p12 -storetype PKCS12 -storepass changeit -keypass changeit -dname CN=synesis-demo -validity 30 -noprompt
```

The CLI uses application identity proofs for node authentication. The demo
keystore is transport encryption material; do not reuse it as a Synesis node
identity and do not copy it to B.

## Prepare identities

On A:

```powershell
New-Item -ItemType Directory -Force demo | Out-Null
./gradlew.bat :link:demoCli --args="identity --identity demo\node-a.identity"
```

On B:

```powershell
New-Item -ItemType Directory -Force demo | Out-Null
./gradlew.bat :link:demoCli --args="identity --identity demo\node-b.identity"
```

Record only the printed `NODE_ID` values. Never record identity files,
keystore passwords, private keys, or personal paths.

## Scenario A — normal operation

On A, replace `<NODE_B_ID>` and choose a UDP port allowed by the host firewall:

```powershell
$env:SYNESIS_DEMO_TLS_PASSWORD = 'changeit'
./gradlew.bat :link:demoCli --args="server --identity demo\node-a.identity --descriptor demo\node-a.descriptor --expected-client <NODE_B_ID> --tls-keystore $env:TEMP\synesis-demo.p12 --tls-password-env SYNESIS_DEMO_TLS_PASSWORD --port 4433"
```

Copy `demo\node-a.descriptor` from A to B using the agreed out-of-band method.
Copy the printed A `NODE_ID` separately. Do not copy A's identity or keystore.

On B, replace `<NODE_A_ID>`:

```powershell
./gradlew.bat :link:demoCli --args="client --identity demo\node-b.identity --descriptor demo\node-a.descriptor --expected-node <NODE_A_ID>"
```

Expected safe output includes `AUTHENTICATED_REMOTE=<NODE_A_ID>`,
`CONTROL_READY=true`, `LIVENESS=LIVE`, `WORK_RESULT=OK`, a UUID `SESSION_ID`,
`TERMINAL_REASON=LOCAL_REQUEST`, and `CLEANUP=true`. A prints the matching
remote identity, `LIVE`, and a terminal reason after B closes.

Record: machine labels/OS/Java version, topology and address class (not full
personal addresses), provider count, compatible pair count, selected redacted
pair identifier, expected/authenticated IDs, control readiness, liveness,
request/result status and ID, close reason, and cleanup.

## Scenario B — abrupt process loss

Repeat Scenario A. After B prints `WORK_RESULT=OK` and both sides show `LIVE`,
terminate B without graceful close:

```powershell
Stop-Process -Id <CLIENT_PID> -Force
```

Use the exact client PID from the launch wrapper; do not terminate an unrelated
Java process. A must report the actual `TRANSPORT_CLOSED` or
`LIVENESS_EXPIRED` category within the documented bound. Do not call this
instant crash detection. Confirm A exits and no child process remains.

## Scenario C — invalid expected identity

Copy a fresh descriptor to B and provide an intentionally wrong, well-formed
node ID:

```powershell
./gradlew.bat :link:demoCli --args="client --identity demo\node-b.identity --descriptor demo\node-a.descriptor --expected-node sl1-0000000000000000000000000000000000000000000000000000000000000000"
```

Expected result is a non-zero failure before a usable session or `LIVE` state is
published. No work message is sent. This is a physical command-level identity
rejection; the transport-level wrong-identity integration test remains part of
the automated evidence.

## Safe claims after the run

If Scenario A completes on two physical computers, it is valid to claim direct
authenticated communication, independent identities, signed descriptor
validation, expected-identity verification, bounded direct candidate selection,
one bounded demo request/result, application heartbeat/liveness, graceful
shutdown, and deterministic cleanup for the tested topology.

It is not valid to claim universal reachability, NAT traversal, router mapping,
STUN/TURN, hole punching, relays, CGNAT support, path migration, reconnection,
session resumption, global IPv6/public IPv4 reachability, operation across all
firewalls, or production-ready agent cooperation.

## Troubleshooting boundary

If no candidates or pairs appear, record the address class and firewall result;
do not add a new provider. If authentication fails, compare the expected node
ID with the signed descriptor and do not bypass verification. If the process
closes early, classify the exact terminal reason and preserve the automated
candidate/authentication/control tests. Do not increase deadlines to hide a
network limitation.

## Cleanup

Delete only the demo directory and the temporary TLS keystore after evidence is
sanitized. Confirm no identity, descriptor, key, password, personal path, or
full endpoint appears in repository files, logs, or recordings.
