# Current Task

## Identity

- Task ID: SL-DEMO-001
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0035
- Responsible agent: fresh coding agent
- Related decisions: ADR-0001, ADR-0002, ADR-0004, ADR-0005, ADR-0006, ADR-0007

## Objective

Prepare the first physical Synesis Link cooperation demonstration without
implementing broad SL-009 reconnect/path behavior or deferred networking.

## Work completed

Created the authoritative 27-entry deferred register and validator; deferred
SL-009; added the demo gap analysis; implemented the bounded
`synesis-demo-work/1` request/result records and strict UTF-8 codec; bound the
demo exchange to authenticated control-ready `PeerSession`; added bounded
internal QUIC application streams; added the source-run identity/server/client
CLI; added local/two-process demo exchange tests; documented the exact
two-machine procedure and release boundary; and completed the Netty source
artifact verification allowlist, including the six test-classpath source JARs.

## Verification

- `gradlew.bat clean check --dependency-verification=strict`: PASS.
- Netty, JUnit, Gradle, and Kotlin artifact hashes: PASS against published sidecars or byte-identical cross-repository copies.
- `DemoWorkProtocolTest`, `DemoWorkBindingTest`: PASS.
- Local QUIC demo request/result: PASS.
- Two-process demo request/result: PASS.
- `DemoCliTest`: PASS.
- `gradlew.bat demoCli --args=--help`: PASS.
- `scripts/agent-validate-deferred.ps1`: PASS; 27 entries.
- `scripts/agent-validate-fixtures.ps1`: PASS.
- `scripts/agent-doctor.ps1`: PASS.
- `scripts/agent-resume.ps1`: PASS.

## Current failures

- No second independent physical computer is available in this workspace;
  physical normal-operation, abrupt-loss, and wrong-identity evidence cannot
  be honestly recorded here.

## Known limitations

NAT traversal, PCP, NAT-PMP, UPnP, STUN/TURN, hole punching, CGNAT, relays,
rendezvous, production discovery, path migration, reconnect, session
resumption, physical IPv6/public IPv4, all-firewall operation, packaging, GUI,
and production Synesis cooperation remain deferred or unverified.

## Immediate next action

Run `docs/demo/FIRST_DEMO.md` on two independent computers, record sanitized
normal/abrupt/wrong-identity evidence, and classify it as
`TWO_MACHINE_VERIFIED` only if those runs actually pass.
