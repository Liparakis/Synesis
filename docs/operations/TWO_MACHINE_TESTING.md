# Two-machine testing

The required two-machine test must use two physical computers on a documented direct-network scenario, exchange signed
descriptors out of band, require the expected fingerprint, establish `synesis-link/1`, exercise a typed stream, record
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
generated `cli\build\install\synesis\bin\synesis.bat` on both machines. Use a
temporary PATH change only in the current shell if desired; do not record a
permanent PATH or installation claim. Pass the complete invitation as one
quoted argument to `synesis join "<link>"`. Capture sanitized launcher output
in `docs/evidence/PHYSICAL-CLI-ONBOARDING.md`; the existing
`PHYSICAL-DEMO-2026-07-20.md` is diagnostic `DemoCli` evidence and does not
prove the generated launcher path.

Record only sanitized machine labels, operating systems, Java versions,
topology/address classes, candidate counts, redacted pair ID, expected and
authenticated node IDs, control readiness, LIVE state, request/result status,
close reason, cleanup, and exact command/date. Do not record private keys,
identity or descriptor files, passwords, full personal addresses, usernames,
access tokens, or absolute personal paths.
