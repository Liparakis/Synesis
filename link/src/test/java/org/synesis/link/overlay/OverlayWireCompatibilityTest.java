package org.synesis.link.overlay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.overlay.OverlayKeyAgreement.E2eSession;
import org.synesis.link.overlay.OverlayKeyAgreement.InitRecord;
import org.synesis.link.overlay.OverlayKeyAgreement.ResponseRecord;

/**
 * Pins the encoded bytes of every overlay wire record and what its decoder does with a trailing
 * byte and a truncated input. Every identity and ephemeral key comes from a fixed seed, and
 * Ed25519 signatures are deterministic, so the same inputs must always produce the same bytes.
 * The session key derivation of the key agreement is pinned through the envelopes it seals.
 */
final class OverlayWireCompatibilityTest {

  private static final String TRAILING = "trailing overlay record bytes";

  private static final UUID PROJECT = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
  private static final UUID MESSAGE = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
  private static final UUID SESSION = UUID.fromString("c0c1c2c3-c4c5-c6c7-c8c9-cacbcccdcecf");
  private static final Instant ISSUED = Instant.ofEpochMilli(1_750_000_000_123L);
  private static final Instant EXPIRES = Instant.ofEpochMilli(1_750_000_600_123L);
  private static final long REVISION = 7;
  private static final byte[] CIPHERTEXT = unhex("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3");
  private static final byte[] INIT_NONCE = filled(32, 0x71);
  private static final byte[] RESPONSE_NONCE = filled(32, 0x72);
  private static final String PLAINTEXT = "grüße ✓ 日本";

  private static final String ENVELOPE =
      "534c45310100112233445566778899aabbccddeeff0f1e2d3c4b5a69788796a5b4c3d2e1f0503d8ee46d581c64"
      + "9966569ae095e27041de83322e8e0480e5539e00d2d5d874744f36cca67eb1912cb282e08e86bd2fe0d09004a2"
      + "8cf6e7c6bd56cdd6bf65aac0c1c2c3c4c5c6c7c8c9cacbcccdcecf00000000000000030014a0a1a2a3a4a5a6a7"
      + "a8a9aaabacadaeafb0b1b2b3";
  private static final String FORWARDING =
      "534c46310100112233445566778899aabbccddeeff744f36cca67eb1912cb282e08e86bd2fe0d09004a28cf6e7"
      + "c6bd56cdd6bf65aa0f1e2d3c4b5a69788796a5b4c3d2e1f0050093534c45310100112233445566778899aabbcc"
      + "ddeeff0f1e2d3c4b5a69788796a5b4c3d2e1f0503d8ee46d581c649966569ae095e27041de83322e8e0480e553"
      + "9e00d2d5d874744f36cca67eb1912cb282e08e86bd2fe0d09004a28cf6e7c6bd56cdd6bf65aac0c1c2c3c4c5c6"
      + "c7c8c9cacbcccdcecf00000000000000030014a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3";
  private static final String MEMBERSHIP =
      "534c4d310100112233445566778899aabbccddeeff503d8ee46d581c649966569ae095e27041de83322e8e0480"
      + "e5539e00d2d5d8740000000000000007000001977420dc7b00000197742a043b043532fb2b134488a2a18f2a07"
      + "cd6db85b4c1b808ddb10972f0ad5526856177bd401002c302a300506032b657003210017cb79fb2b4120f2b1ec"
      + "65e4198d6e08b28e813feb01e4a400839b85e18080ce503d8ee46d581c649966569ae095e27041de83322e8e04"
      + "80e5539e00d2d5d87400002c302a300506032b6570032100d04ab232742bb4ab3a1368bd4615e4e6d0224ab71a"
      + "016baf8520a332c977873755ddbb3ea4e4011206a0eab2aa2aeb47104d64fd58b4811ba91adcca7ebac23d0000"
      + "2c302a300506032b657003210034b4d9043156cb6dcf0beb0a2949b7559c940d2bcb6dbe8c53a9b30278e3a746"
      + "744f36cca67eb1912cb282e08e86bd2fe0d09004a28cf6e7c6bd56cdd6bf65aa00002c302a300506032b657003"
      + "2100a09aa5f47a6759802ff955f8dc2d2a14a5c99d23be97f864127ff9383455a4f000409870b605f41e9933b7"
      + "69b24f20b9771201a0b7133e0f59065d52029a380241898f6d78c5f73ca38104d634f1150462fb9655a24d0c4b"
      + "87f1faa89f14996fc602";
  private static final String TOPOLOGY =
      "534c54310100112233445566778899aabbccddeeff503d8ee46d581c649966569ae095e27041de83322e8e0480"
      + "e5539e00d2d5d8740000000000000007000000000000000900000197742a043b0255ddbb3ea4e4011206a0eab2"
      + "aa2aeb47104d64fd58b4811ba91adcca7ebac23d744f36cca67eb1912cb282e08e86bd2fe0d09004a28cf6e7c6"
      + "bd56cdd6bf65aa004015df479dddccca9e2e0f890fe123164ccdb5faa6e01bd96efd8b4b955811cf5fb087cf32"
      + "ee5bab548c3f4585acd3a1e1f452cd90c4aba1c9f35f92e6e472af0a";
  private static final String MEMBERSHIP_FRAME =
      "534c50320100112233445566778899aabbccddeeff503d8ee46d581c649966569ae095e27041de83322e8e0480"
      + "e5539e00d2d5d87400000000000000070401cc534c4d310100112233445566778899aabbccddeeff503d8ee46d"
      + "581c649966569ae095e27041de83322e8e0480e5539e00d2d5d8740000000000000007000001977420dc7b0000"
      + "0197742a043b043532fb2b134488a2a18f2a07cd6db85b4c1b808ddb10972f0ad5526856177bd401002c302a30"
      + "0506032b657003210017cb79fb2b4120f2b1ec65e4198d6e08b28e813feb01e4a400839b85e18080ce503d8ee4"
      + "6d581c649966569ae095e27041de83322e8e0480e5539e00d2d5d87400002c302a300506032b6570032100d04a"
      + "b232742bb4ab3a1368bd4615e4e6d0224ab71a016baf8520a332c977873755ddbb3ea4e4011206a0eab2aa2aeb"
      + "47104d64fd58b4811ba91adcca7ebac23d00002c302a300506032b657003210034b4d9043156cb6dcf0beb0a29"
      + "49b7559c940d2bcb6dbe8c53a9b30278e3a746744f36cca67eb1912cb282e08e86bd2fe0d09004a28cf6e7c6bd"
      + "56cdd6bf65aa00002c302a300506032b6570032100a09aa5f47a6759802ff955f8dc2d2a14a5c99d23be97f864"
      + "127ff9383455a4f000409870b605f41e9933b769b24f20b9771201a0b7133e0f59065d52029a380241898f6d78"
      + "c5f73ca38104d634f1150462fb9655a24d0c4b87f1faa89f14996fc602";
  private static final String TOPOLOGY_FRAME =
      "534c50310100112233445566778899aabbccddeeff503d8ee46d581c649966569ae095e27041de83322e8e0480"
      + "e5539e00d2d5d87400000000000000090400d0534c54310100112233445566778899aabbccddeeff503d8ee46d"
      + "581c649966569ae095e27041de83322e8e0480e5539e00d2d5d874000000000000000700000000000000090000"
      + "0197742a043b0255ddbb3ea4e4011206a0eab2aa2aeb47104d64fd58b4811ba91adcca7ebac23d744f36cca67e"
      + "b1912cb282e08e86bd2fe0d09004a28cf6e7c6bd56cdd6bf65aa004015df479dddccca9e2e0f890fe123164ccd"
      + "b5faa6e01bd96efd8b4b955811cf5fb087cf32ee5bab548c3f4585acd3a1e1f452cd90c4aba1c9f35f92e6e472"
      + "af0a";
  private static final String INIT =
      "534c4b31010000112233445566778899aabbccddeeff503d8ee46d581c649966569ae095e27041de83322e8e04"
      + "80e5539e00d2d5d874744f36cca67eb1912cb282e08e86bd2fe0d09004a28cf6e7c6bd56cdd6bf65aac0c1c2c3"
      + "c4c5c6c7c8c9cacbcccdcecf000000000000000771717171717171717171717171717171717171717171717171"
      + "71717171717171ff2ee45601ec1b67310c7790404585ae697331eee1c1f8cf2419731c1fff3e6b0040dd0b858f"
      + "bd3e0a2e2f467ac1319b9a7a434b06eca448e6f5e863f65493f5dec457499d7d9b8deaae94269aa5b13463400c"
      + "ffacf7c707127b9be0ac39325b200e";
  private static final String RESPONSE =
      "534c4b31010100112233445566778899aabbccddeeff503d8ee46d581c649966569ae095e27041de83322e8e04"
      + "80e5539e00d2d5d874744f36cca67eb1912cb282e08e86bd2fe0d09004a28cf6e7c6bd56cdd6bf65aac0c1c2c3"
      + "c4c5c6c7c8c9cacbcccdcecf000000000000000772727272727272727272727272727272727272727272727272"
      + "7272727272727238ab664bd86f77d7e66bdd9ae0792913a94fd8b33a1260027e4b46c1f4884c6760d0ad9cdda7"
      + "acc67136a131875160a454911fee99781e3b6c63010567cdd8670040d8243df66bebd5587c3ae214e3edb78c99"
      + "b078093b20cf9f598a75c7b8ea26a786eea291da980bee06e5a8f1370220deb49a4d894e85f334e5c667d9fb74"
      + "e701";
  private static final String SEALED_BY_INITIATOR =
      "534c45310100112233445566778899aabbccddeeff0f1e2d3c4b5a69788796a5b4c3d2e1f0503d8ee46d581c64"
      + "9966569ae095e27041de83322e8e0480e5539e00d2d5d874744f36cca67eb1912cb282e08e86bd2fe0d09004a2"
      + "8cf6e7c6bd56cdd6bf65aac0c1c2c3c4c5c6c7c8c9cacbcccdcecf000000000000000000220b7628d9c6795bdb"
      + "556a0bddf9e60e5a5e5d581b27f81f53b6a31bf2512db0f85f98";
  private static final String SEALED_BY_RESPONDER =
      "534c45310100112233445566778899aabbccddeeff0f1e2d3c4b5a69788796a5b4c3d2e1f0744f36cca67eb191"
      + "2cb282e08e86bd2fe0d09004a28cf6e7c6bd56cdd6bf65aa503d8ee46d581c649966569ae095e27041de83322e"
      + "8e0480e5539e00d2d5d874c0c1c2c3c4c5c6c7c8c9cacbcccdcecf00000000000000000022e617bf9e7b933946"
      + "ff4429c5f3d52d1add035eb090f03a23e3896cb12916fe3e751d";

  private interface Decoder {

    void decode(byte[] bytes) throws IOException;
  }

  private static final class FixedRandom extends SecureRandom {

    private static final long serialVersionUID = 1L;
    private final int value;

    FixedRandom(int value) {
      this.value = value;
    }

    @Override
    public void nextBytes(byte[] bytes) {
      Arrays.fill(bytes, (byte) value);
    }
  }

  private static byte[] unhex(String value) {
    return HexFormat.of().parseHex(value);
  }

  private static String hex(byte[] value) {
    return HexFormat.of().formatHex(value);
  }

  private static byte[] filled(int length, int value) {
    byte[] bytes = new byte[length];
    Arrays.fill(bytes, (byte) value);
    return bytes;
  }

  private static byte[] withTrailingByte(String hexValue) {
    return unhex(hexValue + "00");
  }

  private static byte[] truncated(String hexValue) {
    return Arrays.copyOf(unhex(hexValue), hexValue.length() / 2 - 1);
  }

  private static void assertRejected(Decoder decoder, byte[] bytes, String message) {
    IOException failure = assertThrows(IOException.class, () -> decoder.decode(bytes));
    assertEquals(message, failure.getMessage());
  }

  private static KeyPair keyPair(String algorithm, int seed) throws GeneralSecurityException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
    generator.initialize(new NamedParameterSpec(algorithm), new FixedRandom(seed));
    return generator.generateKeyPair();
  }

  private static NodeIdentity identity(int seed) throws GeneralSecurityException {
    return NodeIdentity.from(keyPair("Ed25519", seed));
  }

  private static E2eSession deriveSession(InitRecord init, ResponseRecord response,
      PrivateKey privateKey, byte[] peerPublicKey, boolean initiatorSide) throws Exception {
    Method method = OverlayKeyAgreement.class.getDeclaredMethod("deriveSession", InitRecord.class,
        ResponseRecord.class, PrivateKey.class, byte[].class, boolean.class);
    method.setAccessible(true);
    return (E2eSession) method.invoke(null, init, response, privateKey, peerPublicKey,
        initiatorSide);
  }

  private static byte[] longBytes(long value) {
    return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
  }

  private static OverlayMembershipSnapshot.Member member(NodeIdentity identity, int status) {
    return new OverlayMembershipSnapshot.Member(identity.nodeId(), status,
        identity.publicKeyEncoded());
  }

  private static OverlayMembershipSnapshot membership() throws GeneralSecurityException {
    NodeIdentity authority = identity(0x11);
    return OverlayMembershipSnapshot.create(PROJECT, REVISION, ISSUED, EXPIRES,
        List.of(member(identity(0x33), OverlayMembershipSnapshot.STATUS_REVOKED),
            member(authority, OverlayMembershipSnapshot.STATUS_ACTIVE),
            member(identity(0x22), OverlayMembershipSnapshot.STATUS_ACTIVE),
            member(identity(0x66), OverlayMembershipSnapshot.STATUS_ACTIVE)), authority);
  }

  private static OverlayTopologyAdvertisement topology() throws GeneralSecurityException {
    return OverlayTopologyAdvertisement.create(PROJECT, REVISION, 9, EXPIRES,
        List.of(identity(0x66).nodeId(), identity(0x22).nodeId()), identity(0x11));
  }

  private static OverlayEnvelope envelope() throws GeneralSecurityException {
    return OverlayEnvelope.sealed(PROJECT, MESSAGE, identity(0x11).nodeId(),
        identity(0x22).nodeId(), SESSION, 3, CIPHERTEXT);
  }

  private static byte[] initUnsigned() throws GeneralSecurityException {
    return OverlayCodecSupport.concat(unhex("534c4b31" + "01" + "00"),
        OverlayCodecSupport.uuidBytes(PROJECT),
        OverlayCodecSupport.nodeIdBytes(identity(0x11).nodeId()),
        OverlayCodecSupport.nodeIdBytes(identity(0x22).nodeId()),
        OverlayCodecSupport.uuidBytes(SESSION), longBytes(REVISION), INIT_NONCE,
        OverlayCodecSupport.x25519PublicKeyBytes(keyPair("X25519", 0x44)));
  }

  private static byte[] initBytes() throws GeneralSecurityException {
    byte[] unsigned = initUnsigned();
    return OverlayCodecSupport.concat(unsigned, unhex("0040"), identity(0x11).sign(unsigned));
  }

  private static byte[] responseBytes() throws GeneralSecurityException {
    byte[] unsigned = OverlayCodecSupport.concat(unhex("534c4b31" + "01" + "01"),
        OverlayCodecSupport.uuidBytes(PROJECT),
        OverlayCodecSupport.nodeIdBytes(identity(0x11).nodeId()),
        OverlayCodecSupport.nodeIdBytes(identity(0x22).nodeId()),
        OverlayCodecSupport.uuidBytes(SESSION), longBytes(REVISION), RESPONSE_NONCE,
        OverlayCodecSupport.x25519PublicKeyBytes(keyPair("X25519", 0x55)),
        OverlayCodecSupport.sha256(initUnsigned()));
    return OverlayCodecSupport.concat(unsigned, unhex("0040"), identity(0x22).sign(unsigned));
  }

  @Test
  void envelopeSle1KeepsCanonicalBytes() throws Exception {
    assertEquals(ENVELOPE, hex(envelope().encoded()));

    OverlayEnvelope decoded = OverlayEnvelope.decode(unhex(ENVELOPE));
    assertEquals(ENVELOPE, hex(decoded.encoded()));
    assertTrue(ENVELOPE.startsWith("534c453101"));
    assertEquals(PROJECT, decoded.projectId());
    assertEquals(MESSAGE, decoded.messageId());
    assertEquals(identity(0x11).nodeId(), decoded.originNodeId());
    assertEquals(identity(0x22).nodeId(), decoded.destinationNodeId());
    assertEquals(SESSION, decoded.sessionId());
    assertEquals(3, decoded.sequence());
    assertArrayEquals(CIPHERTEXT, decoded.ciphertext());

    assertRejected(OverlayEnvelope::decode, withTrailingByte(ENVELOPE), TRAILING);
    assertRejected(OverlayEnvelope::decode, truncated(ENVELOPE),
        "invalid envelope ciphertext length");
  }

  @Test
  void forwardingFrameSlf1KeepsCanonicalBytes() throws Exception {
    OverlayForwardingFrame frame = OverlayForwardingFrame.create(PROJECT,
        identity(0x22).nodeId(), MESSAGE, 5, envelope().encoded());
    assertEquals(FORWARDING, hex(frame.encoded()));

    OverlayForwardingFrame decoded = OverlayForwardingFrame.decode(unhex(FORWARDING));
    assertEquals(FORWARDING, hex(decoded.encoded()));
    assertTrue(FORWARDING.startsWith("534c463101"));
    assertEquals(PROJECT, decoded.projectId());
    assertEquals(identity(0x22).nodeId(), decoded.destinationNodeId());
    assertEquals(MESSAGE, decoded.messageId());
    assertEquals(5, decoded.remainingHops());
    assertArrayEquals(envelope().encoded(), decoded.innerRecord());

    assertRejected(OverlayForwardingFrame::decode, withTrailingByte(FORWARDING), TRAILING);
    assertRejected(OverlayForwardingFrame::decode, truncated(FORWARDING),
        "invalid forwarding inner record length");
  }

  @Test
  void membershipSnapshotSlm1KeepsCanonicalBytes() throws Exception {
    assertEquals(MEMBERSHIP, hex(membership().encoded()));

    OverlayMembershipSnapshot decoded = OverlayMembershipSnapshot.decode(unhex(MEMBERSHIP));
    assertEquals(MEMBERSHIP, hex(decoded.encoded()));
    assertTrue(MEMBERSHIP.startsWith("534c4d3101"));
    assertEquals(PROJECT, decoded.projectId());
    assertEquals(identity(0x11).nodeId(), decoded.authorityNodeId());
    assertEquals(REVISION, decoded.revision());
    assertEquals(ISSUED, decoded.issuedAt());
    assertEquals(EXPIRES, decoded.expiresAt());
    assertEquals(4, decoded.members().size());
    assertTrue(decoded.allows(identity(0x11).nodeId()));
    assertTrue(decoded.allows(identity(0x22).nodeId()));
    assertEquals(OverlayMembershipSnapshot.STATUS_REVOKED,
        decoded.member(identity(0x33).nodeId()).orElseThrow().status());
    assertTrue(decoded.verify());
    assertTrue(decoded.isUsableAt(ISSUED.plusSeconds(1)));

    assertRejected(OverlayMembershipSnapshot::decode, withTrailingByte(MEMBERSHIP), TRAILING);
    assertRejected(OverlayMembershipSnapshot::decode, truncated(MEMBERSHIP),
        "invalid membership signature");
  }

  @Test
  void topologyAdvertisementSlt1KeepsCanonicalBytes() throws Exception {
    assertEquals(TOPOLOGY, hex(topology().encoded()));

    OverlayTopologyAdvertisement decoded = OverlayTopologyAdvertisement.decode(unhex(TOPOLOGY));
    assertEquals(TOPOLOGY, hex(decoded.encoded()));
    assertTrue(TOPOLOGY.startsWith("534c543101"));
    assertEquals(PROJECT, decoded.projectId());
    assertEquals(identity(0x11).nodeId(), decoded.originNodeId());
    assertEquals(REVISION, decoded.membershipRevision());
    assertEquals(9, decoded.sequence());
    assertEquals(EXPIRES, decoded.expiresAt());
    assertEquals(Stream.of(identity(0x22).nodeId(), identity(0x66).nodeId()).sorted().toList(),
        decoded.adjacentNodeIds());
    decoded.verifyAgainst(membership(), ISSUED.plusSeconds(1));

    assertRejected(OverlayTopologyAdvertisement::decode, withTrailingByte(TOPOLOGY), TRAILING);
    assertRejected(OverlayTopologyAdvertisement::decode, truncated(TOPOLOGY),
        "invalid topology signature");
  }

  @Test
  void membershipPropagationFrameSlp2KeepsCanonicalBytes() throws Exception {
    assertEquals(MEMBERSHIP_FRAME,
        hex(OverlayMembershipPropagationFrame.create(membership(), 4).encoded()));

    OverlayMembershipPropagationFrame decoded =
        OverlayMembershipPropagationFrame.decode(unhex(MEMBERSHIP_FRAME));
    assertEquals(MEMBERSHIP_FRAME, hex(decoded.encoded()));
    assertTrue(MEMBERSHIP_FRAME.startsWith("534c503201"));
    assertEquals(4, decoded.remainingHops());
    assertEquals(MEMBERSHIP, hex(decoded.membership().encoded()));

    assertRejected(OverlayMembershipPropagationFrame::decode, withTrailingByte(MEMBERSHIP_FRAME),
        TRAILING);
    assertRejected(OverlayMembershipPropagationFrame::decode, truncated(MEMBERSHIP_FRAME),
        "invalid membership snapshot length");
  }

  @Test
  void topologyPropagationFrameSlp1KeepsCanonicalBytes() throws Exception {
    assertEquals(TOPOLOGY_FRAME,
        hex(OverlayTopologyPropagationFrame.create(topology(), 4).encoded()));

    OverlayTopologyPropagationFrame decoded =
        OverlayTopologyPropagationFrame.decode(unhex(TOPOLOGY_FRAME));
    assertEquals(TOPOLOGY_FRAME, hex(decoded.encoded()));
    assertTrue(TOPOLOGY_FRAME.startsWith("534c503101"));
    assertEquals(4, decoded.remainingHops());
    assertEquals(TOPOLOGY, hex(decoded.advertisement().encoded()));

    assertRejected(OverlayTopologyPropagationFrame::decode, withTrailingByte(TOPOLOGY_FRAME),
        TRAILING);
    assertRejected(OverlayTopologyPropagationFrame::decode, truncated(TOPOLOGY_FRAME),
        "invalid topology advertisement length");
  }

  @Test
  void keyAgreementInitSlk1KeepsCanonicalBytes() throws Exception {
    assertEquals(INIT, hex(initBytes()));

    InitRecord decoded = InitRecord.decode(unhex(INIT));
    assertEquals(INIT, hex(decoded.encoded()));
    assertEquals(INIT.substring(0, INIT.length() - 132), hex(decoded.unsignedEncoded()));
    assertEquals(PROJECT, decoded.projectId());
    assertEquals(identity(0x11).nodeId(), decoded.originNodeId());
    assertEquals(identity(0x22).nodeId(), decoded.destinationNodeId());
    assertEquals(SESSION, decoded.sessionId());
    assertEquals(REVISION, decoded.membershipRevision());
    assertArrayEquals(INIT_NONCE, decoded.initNonce());
    assertArrayEquals(OverlayCodecSupport.x25519PublicKeyBytes(keyPair("X25519", 0x44)),
        decoded.ephemeralPublicKey());

    assertRejected(InitRecord::decode, withTrailingByte(INIT), TRAILING);
    assertRejected(InitRecord::decode, truncated(INIT), "invalid key-agreement signature");
  }

  @Test
  void keyAgreementResponseSlk1KeepsCanonicalBytes() throws Exception {
    assertEquals(RESPONSE, hex(responseBytes()));

    ResponseRecord decoded = ResponseRecord.decode(unhex(RESPONSE));
    assertEquals(RESPONSE, hex(decoded.encoded()));
    assertEquals(PROJECT, decoded.projectId());
    assertEquals(identity(0x11).nodeId(), decoded.originNodeId());
    assertEquals(identity(0x22).nodeId(), decoded.destinationNodeId());
    assertEquals(SESSION, decoded.sessionId());
    assertEquals(REVISION, decoded.membershipRevision());
    assertArrayEquals(RESPONSE_NONCE, decoded.responseNonce());
    assertArrayEquals(OverlayCodecSupport.x25519PublicKeyBytes(keyPair("X25519", 0x55)),
        decoded.ephemeralPublicKey());
    assertArrayEquals(OverlayCodecSupport.sha256(initUnsigned()), decoded.transcriptHash());

    assertRejected(ResponseRecord::decode, withTrailingByte(RESPONSE), TRAILING);
    assertRejected(ResponseRecord::decode, truncated(RESPONSE), "invalid key-agreement signature");
  }

  @Test
  void sessionKeyDerivationIsPinnedByTheEnvelopesItSeals() throws Exception {
    InitRecord init = InitRecord.decode(unhex(INIT));
    ResponseRecord response = ResponseRecord.decode(unhex(RESPONSE));
    byte[] plaintext = PLAINTEXT.getBytes(StandardCharsets.UTF_8);

    try (E2eSession initiator = deriveSession(init, response,
        keyPair("X25519", 0x44).getPrivate(), response.ephemeralPublicKey(), true);
        E2eSession responder = deriveSession(init, response,
            keyPair("X25519", 0x55).getPrivate(), init.ephemeralPublicKey(), false)) {
      OverlayEnvelope forward = initiator.encrypt(MESSAGE, 0, plaintext);
      OverlayEnvelope reverse = responder.encrypt(MESSAGE, 0, plaintext);

      assertEquals(SEALED_BY_INITIATOR, hex(forward.encoded()));
      assertEquals(SEALED_BY_RESPONDER, hex(reverse.encoded()));
      assertArrayEquals(plaintext, responder.decrypt(OverlayEnvelope.decode(
          unhex(SEALED_BY_INITIATOR))));
      assertArrayEquals(plaintext, initiator.decrypt(OverlayEnvelope.decode(
          unhex(SEALED_BY_RESPONDER))));
    }
  }
}
