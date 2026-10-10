package org.synesis.workspace.lifecycle.codex.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.link.identity.NodeIdentity;

/**
 * Pins the canonical bytes, digest, and signed encoding of a lifecycle control request.
 *
 * <p>Ed25519 signatures are deterministic, so the fixed key below always yields the same envelope.
 */
class LifecycleControlRequestEnvelopeFormatTest {

  private static final String PRIVATE_KEY =
      "302e020100300506032b6570042204200102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
  private static final String PUBLIC_KEY =
      "302a300506032b657003210079b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664";

  private static final String CANONICAL =
      """
      {"requestId":"00000000-0000-4000-8000-000000000001","hostInstanceId":"host-1","operation":"STEER","expectedLifecycleRevision":7,"expectedThreadId":"thread-1","expectedTurnId":"turn-1","continuation":true,"input":"input text","callerDeadlineEpochMillis":1700000000000,"options":{"alpha":"1","zeta":"2"},"authority":{"projectId":"project-a","controlProjectRoot":"control-root","provider":"codex","connectionInstanceId":"connection-1","bindingSessionId":"session-1","bindingFingerprint":"fingerprint-1","bindingVersion":3,"participant":"agt_participant","workIntentId":"intent-1","laneEpoch":2,"canonicalWorktree":"canonical-worktree","realWorktree":"real-worktree","gitCommonDirectory":"git-common","branch":"branch-1","baseCommit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","supervisorId":"supervisor-1","workerId":"worker-1"}}""";

  @Test
  void canonicalBytesAndDigest() {
    LifecycleControlRequestEnvelope request = request();

    assertEquals(CANONICAL, new String(request.canonicalBytes(), StandardCharsets.UTF_8));
    assertEquals("ccd761458716d24b9bd5ecd1566bbb90fc5ea27132e1d6ec90d184339de94b5a", request.digest());
  }

  @Test
  void signedEnvelopeEncoding() throws Exception {
    NodeIdentity signer =
        NodeIdentity.fromEncoded(
            HexFormat.of().parseHex(PUBLIC_KEY), HexFormat.of().parseHex(PRIVATE_KEY));

    LifecycleControlRequestEnvelope.SignedEnvelope signed = request().sign(signer);

    assertEquals(
        """
        {"request":{"requestId":"00000000-0000-4000-8000-000000000001","hostInstanceId":"host-1","operation":"STEER","expectedLifecycleRevision":7,"expectedThreadId":"thread-1","expectedTurnId":"turn-1","continuation":true,"input":"input text","callerDeadlineEpochMillis":1700000000000,"options":{"alpha":"1","zeta":"2"},"authority":{"projectId":"project-a","controlProjectRoot":"control-root","provider":"codex","connectionInstanceId":"connection-1","bindingSessionId":"session-1","bindingFingerprint":"fingerprint-1","bindingVersion":3,"participant":"agt_participant","workIntentId":"intent-1","laneEpoch":2,"canonicalWorktree":"canonical-worktree","realWorktree":"real-worktree","gitCommonDirectory":"git-common","branch":"branch-1","baseCommit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","supervisorId":"supervisor-1","workerId":"worker-1"}},"signerNodeId":"sl1-646d6be49d9f0048f94f67749eca35156eed4f7a7be18e4fc4a94bfd44e300b0","signerPublicKey":"MCowBQYDK2VwAyEAebVWLo/mVPlAeLES6KmLp5AfhTrmlb7X4OORC60ElmQ=","signature":"EazF5fsUFIPdZTmmrHykbryqlekYPLRH/DVyIlUn0oMFZsbfBPJ67rsN6vHSChZlkVBE2wPtcI2S2xd2K2G9AQ=="}""",
        new String(signed.encoded(), StandardCharsets.UTF_8));
    LifecycleControlRequestEnvelope.SignedEnvelope decoded =
        LifecycleControlRequestEnvelope.SignedEnvelope.decode(signed.encoded());
    assertTrue(decoded.verify());
    assertArrayEquals(request().canonicalBytes(), decoded.request().canonicalBytes());
  }

  private static LifecycleControlRequestEnvelope request() {
    return new LifecycleControlRequestEnvelope(
        UUID.fromString("00000000-0000-4000-8000-000000000001"),
        "host-1",
        new LifecycleControlRequestEnvelope.AuthorityContext(
            "project-a",
            "control-root",
            "codex",
            "connection-1",
            "session-1",
            "fingerprint-1",
            3,
            "agt_participant",
            "intent-1",
            2L,
            "canonical-worktree",
            "real-worktree",
            "git-common",
            "branch-1",
            "a".repeat(40),
            "supervisor-1",
            "worker-1"),
        LifecycleControlRequestEnvelope.Operation.STEER,
        7L,
        "thread-1",
        "turn-1",
        true,
        "input text",
        1_700_000_000_000L,
        Map.of("zeta", "2", "alpha", "1"));
  }
}
