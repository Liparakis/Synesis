# Two-machine testing

The required two-machine test must use two physical computers on a documented direct-network scenario, exchange signed descriptors out of band, require the expected fingerprint, establish `synesis-link/1`, exercise a typed stream, record liveness, and verify graceful and abrupt-close behavior. Do not claim this evidence until it is run.

For SL-008, record the provider list, normalized candidate count, compatible
pair ordering, attempt diagnostics, selected authenticated/control-ready
winner, and loser cleanup. A local-interface candidate or a successful QUIC
transport does not prove physical reachability across a NAT or firewall. PCP,
NAT-PMP, UPnP, STUN/TURN, relay, hole-punching, path-migration, and temporary
liveness-suppression recovery evidence must remain explicitly unverified until
those scenarios are exercised.

The reproducible first demonstration is documented in
[`docs/demo/FIRST_DEMO.md`](../demo/FIRST_DEMO.md). Its required physical
evidence classification is `TWO_MACHINE_VERIFIED`; the existing JVM/process
tests remain `TWO_PROCESS_VERIFIED` and must not be relabeled.

Record only sanitized machine labels, operating systems, Java versions,
topology/address classes, candidate counts, redacted pair ID, expected and
authenticated node IDs, control readiness, LIVE state, request/result status,
close reason, cleanup, and exact command/date. Do not record private keys,
identity or descriptor files, passwords, full personal addresses, usernames,
access tokens, or absolute personal paths.
