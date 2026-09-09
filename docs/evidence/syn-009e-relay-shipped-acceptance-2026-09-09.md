# SYN-009E extracted relay shipped-artifact protocol acceptance — 2026-09-09

This evidence records the relay acceptance seam added to the maximum-release
harness. The disposable fixture was built from the current extracted
`relay/build/install/synesis-relay` launcher and given only a synthetic
`PROTECTION_PROFILE=maximum-release` marker so that the independent harness
could exercise its relay branch. It is not a licensed commercial maximum
artifact and does not claim any Seven Rings capability.

## Executed boundary

The test-only `:relay:relayArtifactAcceptanceClient` observer started the
extracted launcher with a disposable relay identity directory and project
allow-list. It then:

- authenticated two allowed node identities with the signed relay handshake;
- rejected a validly signed but non-allowlisted node;
- forwarded encrypted frames in both directions through the extracted relay;
- verified destination-side end-to-end decryption and hop-budget consumption;
- terminated the relay process within the bounded shutdown window.

The observer is a release-test client and is not copied into the customer
bundle. Identity private keys and the transient payload were kept in a
temporary directory and were not written to durable evidence.

## Result

The harness result was `PASS_WITH_EXPLICIT_OPEN_GATES`. The relay checks were:

```text
relay-launcher          PASS
relay-auth-forwarding   PASS
```

The observer also emitted `RELAY_ARTIFACT_ACCEPTANCE=PASS` with
`RELAY_AUTHENTICATED=true`, `RELAY_UNAUTHORIZED_REJECTED=true`,
`RELAY_FORWARDING=true`, `RELAY_E2E=true`, and `RELAY_SHUTDOWN=true`.

This closes the prior parser-only harness gap. It does not close the
commercial maximum gate: the real licensed protected relay archive, all six
commercial transformation rings, signed/diversified release provenance,
private retrace, performance/size evidence, and configured multi-peer/physical
network limits remain separate requirements.
