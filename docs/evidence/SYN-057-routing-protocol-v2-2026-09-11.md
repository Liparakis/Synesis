# SYN-057 protocol v2 serialization and binding — 2026-09-11

## Scope

The operator-approved slice implements only protocol v2 serialization and
binding tests. It does not implement daemon multi-project routing, SLO1 local
project selection, persistent selected-project state, or runtime-restart
continuity.

## Implemented contract

- `SessionInvitation` remains format 1 with its original canonical bytes.
- `TraversalOffer` accepts explicit formats 1 and 2. Format 2 places the
  mandatory `ReturnTarget` immediately after the inner `ProtocolVersion`.
- `TraversalInvitation` accepts explicit SLO1 wrapper formats 1 and 2. Format
  2 requires a format-2 offer and retains the format-1 nested invitation.
- `TraversalAnswer` accepts explicit formats 1 and 2. Format 2 copies the
  offer's target immediately after the inner `ProtocolVersion`.
- `ReturnTarget` is exactly 17 bytes: one format byte (`1`), followed by the
  UUID most-significant and least-significant words in big-endian order.
- The target is included in the host offer and joiner answer signature input.
  The offer's signed invitation digest and session ID bind it to the exact
  invitation chain. SLA2 verification requires format and target equality with
  the expected offer.
- v1 has no target and remains an explicit legacy format; no automatic
  downgrade is implemented.

## Focused verification

Command:

```text
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=temporary workspace'
.\gradlew.bat :link:test --tests 'org.synesis.link.protocol.TraversalProtocolV2Test' --tests 'org.synesis.link.protocol.SessionInvitationTest' --tests 'org.synesis.link.protocol.HumanMediatedLinkTest' --tests 'org.synesis.link.protocol.TraversalExchangeTest' --no-daemon --no-configuration-cache --console=plain
```

Result: **PASS** — Gradle `:link:test` completed successfully. The v2 test
class covers canonical round trips, deterministic target copying, signed
target modification, unsupported target format, missing/truncated target,
trailing bytes, v2-to-v1 mutation, future format, cross-invitation and
cross-offer transplant, project mismatch, and v1 compatibility.

The production compilation also passed with `:link:compileJava`. No daemon,
workspace routing, Docker, or installed-distribution run was performed because
those surfaces are explicitly outside this slice.

## Authority and remaining work

The UUID remains routing metadata only. The selected runtime must continue to
perform Link signature, expiry, replay, identity, membership, and operation
ownership checks. The next separate SYN-057 slice may consume the target for
daemon resolution and implement the approved ephemeral local SLO1 selection
surface; it must preserve the no-runtime-restart-continuity promise.
