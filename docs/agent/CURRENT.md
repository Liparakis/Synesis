# Current Task

## Identity

- Task ID: SL-DEMO-001
- Status: ACTIVE
- Priority: P0
- Started checkpoint: CP-0036
- Responsible agent: fresh coding agent
- Related decisions: ADR-0001, ADR-0002, ADR-0004, ADR-0005, ADR-0006, ADR-0007, ADR-0008

## Objective

Prepare the first physical Synesis Link cooperation demonstration without
implementing broad SL-009 reconnect/path behavior or deferred networking.

## Work completed

The Link implementation now lives in `link/` as the first Synesis Gradle
subproject. Root Gradle orchestration, command paths, ADR-0008, and durable
documentation have been updated; automated demo readiness remains complete.

## Verification

- Root `gradlew.bat projects --dependency-verification=strict`: PASS; root and `:link` discovered.
- Root `gradlew.bat clean check --dependency-verification=strict`: PASS; `:link:check` executed.
- Root `gradlew.bat :link:demoCli --args=--help --dependency-verification=strict`: PASS.
- Resume, doctor, fixture, and deferred validators: PASS.
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
normal/abrupt/wrong-identity evidence, and classify it as `TWO_MACHINE_VERIFIED`
only if those runs actually pass.
