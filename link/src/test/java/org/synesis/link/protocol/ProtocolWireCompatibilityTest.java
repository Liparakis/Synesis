package org.synesis.link.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidateType;

/**
 * Pins the encoded bytes and share links of the invitation, descriptor, and handshake forms that
 * production code emits. Every vector was encoded once by the code under test with fixed Ed25519
 * keys; the tests only decode, verify, and re-encode, so a codec change that moves a byte fails
 * here.
 */
final class ProtocolWireCompatibilityTest {

  private static final Instant ISSUED = Instant.ofEpochSecond(1_750_000_000L);
  private static final Instant EXPIRES = ISSUED.plusSeconds(600);
  private static final Instant NOW = ISSUED.plusSeconds(30);
  private static final Duration SKEW = Duration.ofMinutes(2);
  private static final UUID SESSION = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
  private static final String HOST_ID =
      "sl1-4202b14fde83332a3f7223e907fb0d4f368847fc4b0e746ea314739ce94efe80";
  private static final String HOST_PUBLIC_KEY =
      "302a300506032b65700321006c778044bef297dad48a3d92345b957748c07b535f27ba53612c86d0d150e04a";

  private static final String HOST_DESCRIPTOR =
      "534c4431010044736c312d34323032623134666465383333333261336637323233653930376662306434663336"
      + "383834376663346230653734366561333134373339636539346566653830002c302a300506032b65700321006c"
      + "778044bef297dad48a3d92345b957748c07b535f27ba53612c86d0d150e04a00000000684ee18000000000684e"
      + "e3d8000201001020010db8000000000000000000000001113200000002040004c633640111310000000100408c"
      + "7ff4a3cdf9f8e3db01bfd496cf4e5db63995f4f910febe0bff49b1b618e64d92848bf5df9fb4082cce2e581d92"
      + "b0425657d185fc32268ba1b6babf73a4b709";
  private static final String JOINER_DESCRIPTOR =
      "534c4431010044736c312d30653764376265626230626135613837313366306263636363303631323431656532"
      + "346232633564313165323038303937633836653438333664323064326133002c302a300506032b657003210043"
      + "dd4151c82991ba97fa221974651b1d9985e7b1901387f7f0ae16dd9325988900000000684ee18000000000684e"
      + "e3d80001000004c000020911330000000300407bbc5b23481b70390cc8cdbaed955ba406eeb0266e76ea09b3dc"
      + "59646b55a02ebde0a04a514a67c658641d607d9465571ce1c74f891cee73d3aa8f4f0a2cbd0c";
  /** The inviter of the SLB3 and SLP1 vectors: a fixed key, so every signature below is reproducible. */
  private static final String INVITER_ID =
      "sl1-646d6be49d9f0048f94f67749eca35156eed4f7a7be18e4fc4a94bfd44e300b0";
  private static final String INVITER_PUBLIC_KEY =
      "302a300506032b657003210079b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664";
  private static final String SLB3_V4 =
      "000a684ee1800258101112131415161718191a1b79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0"
      + "e3910bad0496640104cb0071071134fe61f58982d7ccb488dbf2532ebd9301981194d4b53720511a76812b756c"
      + "12ef3f0439325a654dc5c8f51f939453e9d6883437bbb6680ba0f90034c5eb60e109";
  private static final String SLB3_V4_LINK =
      "synesis://join/SLB3-AApoTuGAAlgQERITFBUWFxgZGht5tVYuj-ZU-UB4sRLoqYunkB-FOuaVvtfg45ELrQSWZA"
      + "EEywBxBxE0_mH1iYLXzLSI2_JTLr2TAZgRlNS1NyBRGnaBK3VsEu8_BDkyWmVNxcj1H5OUU-nWiDQ3u7ZoC6D5ADTF"
      + "62DhCQ";
  private static final String SLB3_V6 =
      "000a684ee1800258101112131415161718191a1b79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0"
      + "e3910bad049664011020010db800000000000000000000000711352897400ec774b5c3a787aa595cc0a9d89ac0"
      + "c4020f0faaf4bcf0f4dfb48d2ef95df05e0721c0907c45ae0c5422f70083aedba45eebf57d3a1e000baaee10c7"
      + "02";
  private static final String SLB3_V6_LINK =
      "synesis://join/SLB3-AApoTuGAAlgQERITFBUWFxgZGht5tVYuj-ZU-UB4sRLoqYunkB-FOuaVvtfg45ELrQSWZA"
      + "EQIAENuAAAAAAAAAAAAAAABxE1KJdADsd0tcOnh6pZXMCp2JrAxAIPD6r0vPD037SNLvld8F4HIcCQfEWuDFQi9wCD"
      + "rtukXuv1fToeAAuq7hDHAg";
  private static final String SLB3_BOTH =
      "000a684ee1800258101112131415161718191a1b79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0"
      + "e3910bad049664021020010db8000000000000000000000007113504cb00710711346b0ffc5d3f9f8d01d02569"
      + "c7550d6c5901ec683d447df9fe305bddb78eca191ef4b89529b62fa106acd3f9d63ab8b88f734931a0dae78541"
      + "12e769c4eb734808";
  private static final String SLB3_BOTH_LINK =
      "synesis://join/SLB3-AApoTuGAAlgQERITFBUWFxgZGht5tVYuj-ZU-UB4sRLoqYunkB-FOuaVvtfg45ELrQSWZA"
      + "IQIAENuAAAAAAAAAAAAAAABxE1BMsAcQcRNGsP_F0_n40B0CVpx1UNbFkB7Gg9RH35_jBb3beOyhke9LiVKbYvoQas"
      + "0_nWOri4j3NJMaDa54VBEudpxOtzSAg";
  private static final String SLB3_SESSION = "081c0309-93ab-6c7d-42d7-4289e1152220";
  private static final String SLA3 =
      "000a00112233445566778899aabbccddeeff0104cb0071071134";
  private static final String SLA3_LINK =
      "synesis://answer/SLA3-AAoAESIzRFVmd4iZqrvM3e7_AQTLAHEHETQ";
  /** A project link over {@link #SLB3_V4}: project 0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0 and a code remote URL. */
  private static final String SLP1 =
      "000a0f1e2d3c4b5a69788796a5b4c3d2e1f00000000067b64b000754d4c0002168747470733a2f2f6578616d70"
      + "6c652e746573742f6f72672f7265706f2e676974007c000a684ee1800258101112131415161718191a1b79b556"
      + "2e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad0496640104cb0071071134fe61f58982d7ccb4"
      + "88dbf2532ebd9301981194d4b53720511a76812b756c12ef3f0439325a654dc5c8f51f939453e9d6883437bbb6"
      + "680ba0f90034c5eb60e10908860be3d9e53ac436c6c3c995d17e090e5d05d038869b31402dcbbe5a883cb6239c"
      + "d7109616191cc0b313af426011a78ed8473610260337eb8ed1f9daca8a01";
  private static final String SLP1_LINK =
      "synesis://project/SLP1-AAoPHi08S1ppeIeWpbTD0uHwAAAAAGe2SwAHVNTAACFodHRwczovL2V4YW1wbGUudGV"
      + "zdC9vcmcvcmVwby5naXQAfAAKaE7hgAJYEBESExQVFhcYGRobebVWLo_mVPlAeLES6KmLp5AfhTrmlb7X4OORC60El"
      + "mQBBMsAcQcRNP5h9YmC18y0iNvyUy69kwGYEZTUtTcgURp2gSt1bBLvPwQ5MlplTcXI9R-TlFPp1og0N7u2aAug-QA"
      + "0xetg4QkIhgvj2eU6xDbGw8mV0X4JDl0F0DiGmzFALcu-Wog8tiOc1xCWFhkcwLMTr0JgEaeO2Ec2ECYDN-uO0fnay"
      + "ooB";
  /** A project link with no code remote URL over {@link #SLB3_V6}. */
  private static final String SLP1_NO_URL =
      "000a0f1e2d3c4b5a69788796a5b4c3d2e1f00000000067b64b000754d4c000000088000a684ee1800258101112"
      + "131415161718191a1b79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad04966401102001"
      + "0db800000000000000000000000711352897400ec774b5c3a787aa595cc0a9d89ac0c4020f0faaf4bcf0f4dfb4"
      + "8d2ef95df05e0721c0907c45ae0c5422f70083aedba45eebf57d3a1e000baaee10c70200ec8779ccfb94176cb0"
      + "8fe75c8aa14efe2216aea478eb0a3b448a1d04dfad37d25453ec0df0210e752f4a7f45fa8a3a056a453d69da76"
      + "2596cc2b8a4975d10c";
  private static final String TRANSCRIPT =
      "534c54310100000e73796e657369732d6c696e6b2f3100112233445566778899aabbccddeeff00000000000000"
      + "0100000000000000020020202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f0020"
      + "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f0010707172737475767778797a"
      + "7b7c7d7e7f0044736c312d34323032623134666465383333333261336637323233653930376662306434663336"
      + "383834376663346230653734366561333134373339636539346566653830002c302a300506032b65700321006c"
      + "778044bef297dad48a3d92345b957748c07b535f27ba53612c86d0d150e04a0044736c312d3065376437626562"
      + "623062613561383731336630626363636330363132343165653234623263356431316532303830393763383665"
      + "3438333664323064326133002c302a300506032b657003210043dd4151c82991ba97fa221974651b1d9985e7b1"
      + "901387f7f0ae16dd93259889";
  private static final String CHALLENGE_INITIATOR =
      "7f1547e675295881a0e03aefc20eac11899db9ad1c347b600d4ffc3db29dc978";
  private static final String CHALLENGE_RESPONDER =
      "eeb9a7018700e520624d87092bff70d216daa36203dcedb8554b0e5411d12b05";
  private static final String PROOF =
      "534c4850010000112233445566778899aabbccddeeff00207f1547e675295881a0e03aefc20eac11899db9ad1c"
      + "347b600d4ffc3db29dc9780044736c312d34323032623134666465383333333261336637323233653930376662"
      + "306434663336383834376663346230653734366561333134373339636539346566653830002c302a300506032b"
      + "65700321006c778044bef297dad48a3d92345b957748c07b535f27ba53612c86d0d150e04a00403c0c3cbcd55c"
      + "30dd9c8c50b1bddedb0e515a8daedc66cea28c8f9357e49188218307f714e621249232db68eb8d7f60df66d028"
      + "a0fff0a6b90b5ba6f01366ab05";
  private static final String ENVELOPE =
      "534c483101000a0002010101000174534c54310100000e73796e657369732d6c696e6b2f3100112233445566778899"
      + "aabbccddeeff000000000000000100000000000000020020202122232425262728292a2b2c2d2e2f3031323334"
      + "35363738393a3b3c3d3e3f0020606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f"
      + "0010707172737475767778797a7b7c7d7e7f0044736c312d343230326231346664653833333332613366373232"
      + "33653930376662306434663336383834376663346230653734366561333134373339636539346566653830002c"
      + "302a300506032b65700321006c778044bef297dad48a3d92345b957748c07b535f27ba53612c86d0d150e04a00"
      + "44736c312d30653764376265626230626135613837313366306263636363303631323431656532346232633564"
      + "313165323038303937633836653438333664323064326133002c302a300506032b657003210043dd4151c82991"
      + "ba97fa221974651b1d9985e7b1901387f7f0ae16dd9325988900ee534c4850010000112233445566778899aabb"
      + "ccddeeff00207f1547e675295881a0e03aefc20eac11899db9ad1c347b600d4ffc3db29dc9780044736c312d34"
      + "323032623134666465383333333261336637323233653930376662306434663336383834376663346230653734"
      + "366561333134373339636539346566653830002c302a300506032b65700321006c778044bef297dad48a3d9234"
      + "5b957748c07b535f27ba53612c86d0d150e04a00403c0c3cbcd55c30dd9c8c50b1bddedb0e515a8daedc66cea2"
      + "8c8f9357e49188218307f714e621249232db68eb8d7f60df66d028a0fff0a6b90b5ba6f01366ab05";

  private static byte[] unhex(String value) {
    return HexFormat.of().parseHex(value);
  }

  private static String hex(byte[] value) {
    return HexFormat.of().formatHex(value);
  }

  private static String lp(int widthBytes, String hexValue) {
    return String.format("%0" + widthBytes * 2 + "x", hexValue.length() / 2) + hexValue;
  }

  private static byte[] withTrailingByte(String hexValue) {
    return unhex(hexValue + "00");
  }

  private static byte[] truncated(String hexValue) {
    return Arrays.copyOf(unhex(hexValue), hexValue.length() / 2 - 1);
  }

  private static byte[] sha256(String hexValue) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(unhex(hexValue));
  }

  private static String joinerId() throws IOException {
    return CandidateDescriptor.decode(unhex(JOINER_DESCRIPTOR)).nodeId();
  }

  @Test
  void candidateDescriptorKeepsCanonicalBytesAndOrdersCandidatesByTypeName() throws Exception {
    CandidateDescriptor descriptor = CandidateDescriptor.decode(unhex(HOST_DESCRIPTOR));

    assertEquals(HOST_DESCRIPTOR, hex(descriptor.encoded()));
    assertTrue(HOST_DESCRIPTOR.startsWith("534c443101"));
    assertEquals(HOST_ID, descriptor.nodeId());
    assertEquals(HOST_PUBLIC_KEY, hex(descriptor.publicKeyEncoded()));
    assertEquals(ISSUED, descriptor.issuedAt());
    assertEquals(EXPIRES, descriptor.expiresAt());
    List<Candidate> candidates = descriptor.candidates();
    assertEquals(2, candidates.size());
    assertEquals(CandidateType.IPV6, candidates.get(0).type());
    assertEquals(4402, candidates.get(0).port());
    assertEquals(2, candidates.get(0).priority());
    assertEquals(CandidateType.MANUAL, candidates.get(1).type());
    assertEquals(4401, candidates.get(1).port());
    assertEquals(1, candidates.get(1).priority());
    assertTrue(descriptor.verify());
    assertTrue(descriptor.isValidAt(NOW, CandidateDescriptor.DEFAULT_CLOCK_SKEW));
    assertFalse(descriptor.isValidAt(EXPIRES, CandidateDescriptor.DEFAULT_CLOCK_SKEW));

    CandidateDescriptor joiner = CandidateDescriptor.decode(unhex(JOINER_DESCRIPTOR));
    assertEquals(JOINER_DESCRIPTOR, hex(joiner.encoded()));
    assertEquals(CandidateType.LAN, joiner.candidates().get(0).type());
    assertTrue(joiner.verify());
  }

  @Test
  void candidateDescriptorRejectsTrailingAndTruncatedInput() {
    assertThrows(IOException.class,
        () -> CandidateDescriptor.decode(withTrailingByte(HOST_DESCRIPTOR)));
    assertThrows(IOException.class, () -> CandidateDescriptor.decode(truncated(HOST_DESCRIPTOR)));
  }

  @Test
  void compactBootstrapSlb3KeepsCanonicalBytesAndShareLinkForBothAddressFamilies()
      throws Exception {
    UUID expectedSession = UUID.fromString(SLB3_SESSION);
    for (String[] vector : new String[][]{{SLB3_V4, SLB3_V4_LINK, "124"},
        {SLB3_V6, SLB3_V6_LINK, "136"}, {SLB3_BOTH, SLB3_BOTH_LINK, "143"}}) {
      CompactBootstrapInvitation invitation = CompactBootstrapInvitation.decodeMinimized(
          unhex(vector[0]));

      assertEquals(vector[0], hex(invitation.encoded()));
      assertEquals(Integer.parseInt(vector[2]), invitation.encoded().length);
      assertEquals(vector[1], invitation.shareLink());
      assertEquals(vector[0],
          hex(CompactBootstrapInvitation.fromShareLink(vector[1]).encoded()));
      assertEquals(ProtocolVersion.V1, invitation.protocolVersion());
      assertEquals(expectedSession, invitation.sessionId());
      assertEquals(ISSUED, invitation.issuedAt());
      assertEquals(EXPIRES, invitation.expiresAt());
      assertEquals("101112131415161718191a1b", hex(invitation.capability()));
      assertEquals(INVITER_ID, invitation.hostNodeId());
      assertEquals(INVITER_PUBLIC_KEY, hex(invitation.publicKeyEncoded()));
      assertTrue(invitation.verifyAt(NOW, SKEW));
      assertThrows(IOException.class,
          () -> CompactBootstrapInvitation.decodeMinimized(withTrailingByte(vector[0])));
      assertThrows(IOException.class,
          () -> CompactBootstrapInvitation.decodeMinimized(truncated(vector[0])));
    }
    Candidate v4 = CompactBootstrapInvitation.decodeMinimized(unhex(SLB3_V4)).endpoints()
        .getFirst();
    Candidate v6 = CompactBootstrapInvitation.decodeMinimized(unhex(SLB3_V6)).endpoints()
        .getFirst();
    assertEquals(CandidateType.MANUAL, v4.type());
    assertEquals(4404, v4.port());
    assertEquals(0, v4.priority());
    assertEquals("203.0.113.7", v4.address().getHostAddress());
    assertEquals(CandidateType.IPV6, v6.type());
    assertEquals(4405, v6.port());
    assertEquals(List.of(v6, v4),
        CompactBootstrapInvitation.decodeMinimized(unhex(SLB3_BOTH)).endpoints());
  }

  @Test
  void compactAnswerSla3KeepsCanonicalBytesAndShareLink() throws Exception {
    CompactAnswer answer = CompactAnswer.fromShareLink(SLA3_LINK);

    assertEquals(SLA3, hex(answer.encoded()));
    assertEquals(SLA3_LINK, answer.shareLink());
    assertEquals(SESSION, answer.sessionId());
    assertEquals("203.0.113.7", answer.endpoints().getFirst().address().getHostAddress());
    assertEquals(4404, answer.endpoints().getFirst().port());
    assertThrows(IOException.class, () -> CompactAnswer.decode(withTrailingByte(SLA3)));
    assertThrows(IOException.class, () -> CompactAnswer.decode(truncated(SLA3)));
  }

  @Test
  void projectInvitationSlp1KeepsCanonicalBytesAndShareLinkWithAndWithoutAnUrl() throws Exception {
    UUID project = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
    Instant created = Instant.ofEpochSecond(1_740_000_000L, 123_000_000);
    for (String[] vector : new String[][]{{SLP1, SLP1_LINK, SLB3_V4, "https://example.test/org/repo.git"},
        {SLP1_NO_URL, null, SLB3_V6, null}}) {
      ProjectInvitation invitation = ProjectInvitation.fromShareLink(
          "synesis://project/SLP1-" + java.util.Base64.getUrlEncoder().withoutPadding()
              .encodeToString(unhex(vector[0])));

      assertEquals(vector[0], hex(invitation.encoded()));
      assertTrue(vector[0].startsWith("000a"));
      if (vector[1] != null) {
        assertEquals(vector[1], invitation.shareLink());
      }
      assertEquals(project, invitation.projectId());
      assertEquals(created, invitation.createdAt());
      assertEquals(Optional.ofNullable(vector[3]), invitation.codeRemoteUrl());
      assertEquals(vector[2], hex(invitation.invitation().encoded()));
      assertEquals(INVITER_ID, invitation.hostNodeId());
      assertTrue(invitation.verifyAt(NOW, SKEW));
      assertThrows(IOException.class, () -> ProjectInvitation.fromShareLink("synesis://project/SLP1-"
          + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(withTrailingByte(vector[0]))));
    }
    assertEquals(255, unhex(SLP1).length);
  }

  @Test
  void authenticatedDescriptorExchangeWrapsDescriptorWithKindByte() throws Exception {
    CandidateDescriptor descriptor = CandidateDescriptor.decode(unhex(HOST_DESCRIPTOR));
    String request = "534c5831" + "01" + "01" + lp(2, HOST_DESCRIPTOR);
    String response = "534c5831" + "01" + "02" + lp(2, HOST_DESCRIPTOR);

    assertEquals(request, hex(AuthenticatedDescriptorExchange.request(descriptor)));
    assertEquals(response, hex(AuthenticatedDescriptorExchange.response(descriptor)));
    assertEquals(HOST_DESCRIPTOR,
        hex(AuthenticatedDescriptorExchange.decodeRequest(unhex(request)).encoded()));
    assertEquals(HOST_DESCRIPTOR,
        hex(AuthenticatedDescriptorExchange.decodeResponse(unhex(response)).encoded()));
    assertTrue(AuthenticatedDescriptorExchange.looksLike(unhex(request)));
    assertThrows(IOException.class,
        () -> AuthenticatedDescriptorExchange.decodeRequest(unhex(response)));
    assertThrows(IOException.class,
        () -> AuthenticatedDescriptorExchange.decodeRequest(withTrailingByte(request)));
    assertThrows(IOException.class,
        () -> AuthenticatedDescriptorExchange.decodeRequest(truncated(request)));
  }

  @Test
  void handshakeTranscriptKeepsCanonicalBytesAndSignedChallenges() throws Exception {
    HandshakeTranscript transcript = HandshakeTranscript.decode(unhex(TRANSCRIPT));

    assertEquals(TRANSCRIPT, hex(transcript.encoded()));
    assertEquals(ProtocolVersion.V1, transcript.version());
    assertEquals("synesis-link/1", transcript.alpn());
    assertEquals(SESSION, transcript.sessionId());
    assertEquals(1, transcript.epoch(HandshakeRole.INITIATOR));
    assertEquals(2, transcript.epoch(HandshakeRole.RESPONDER));
    assertEquals(HOST_ID, transcript.nodeId(HandshakeRole.INITIATOR));
    assertEquals(joinerId(), transcript.nodeId(HandshakeRole.RESPONDER));
    assertEquals("707172737475767778797a7b7c7d7e7f", hex(transcript.invitationCapability()));
    assertEquals(CHALLENGE_INITIATOR, hex(transcript.proofChallenge(HandshakeRole.INITIATOR)));
    assertEquals(CHALLENGE_RESPONDER, hex(transcript.proofChallenge(HandshakeRole.RESPONDER)));
    assertThrows(IOException.class, () -> HandshakeTranscript.decode(withTrailingByte(TRANSCRIPT)));
    assertThrows(IOException.class, () -> HandshakeTranscript.decode(truncated(TRANSCRIPT)));
  }

  @Test
  void handshakeProofKeepsCanonicalBytesAndVerifiesAgainstTheTranscriptChallenge()
      throws Exception {
    HandshakeProof proof = HandshakeProof.decode(unhex(PROOF));

    assertEquals(PROOF, hex(proof.encoded()));
    assertEquals(ProtocolVersion.V1, proof.version());
    assertEquals(SESSION, proof.sessionId());
    assertEquals(HOST_ID, proof.nodeId());
    assertEquals(HOST_PUBLIC_KEY, hex(proof.publicKeyEncoded()));
    assertEquals(CHALLENGE_INITIATOR, hex(proof.challenge()));
    assertTrue(proof.verify(ProtocolVersion.V1, SESSION, unhex(CHALLENGE_INITIATOR), HOST_ID));
    assertFalse(proof.verify(ProtocolVersion.V1, SESSION, unhex(CHALLENGE_RESPONDER), HOST_ID));
    assertThrows(IOException.class, () -> HandshakeProof.decode(withTrailingByte(PROOF)));
    assertThrows(IOException.class, () -> HandshakeProof.decode(truncated(PROOF)));
  }

  @Test
  void handshakeEnvelopeKeepsCanonicalBytesAndListsVersionsNewestFirst() throws Exception {
    String bytes = "534c4831" + "01" + "000a" + "00" + "02" + "0101" + "0100" + lp(2, TRANSCRIPT)
        + lp(2, PROOF);
    HandshakeEnvelope envelope = HandshakeEnvelope.decode(unhex(bytes));

    assertEquals(bytes, hex(envelope.encoded()));
    assertEquals(ENVELOPE, bytes);
    assertTrue(HandshakeEnvelope.looksLike(unhex(bytes)));
    assertEquals(HandshakeRole.INITIATOR, envelope.role());
    assertEquals(List.of(new ProtocolVersion(1, 1), ProtocolVersion.V1),
        envelope.supportedVersions());
    assertEquals(TRANSCRIPT, hex(envelope.transcript().encoded()));
    assertEquals(PROOF, hex(envelope.proof().encoded()));
    assertThrows(IOException.class, () -> HandshakeEnvelope.decode(withTrailingByte(bytes)));
    assertThrows(IOException.class, () -> HandshakeEnvelope.decode(truncated(bytes)));
  }

  @Test
  void compatibilityNumberLeadsEveryInvitationAnswerAndEnvelope() throws Exception {
    assertEquals("000a", hex(unhex(SLB3_V4)).substring(0, 4));
    assertEquals("000a", SLA3.substring(0, 4));
    assertEquals(10, HandshakeEnvelope.peekCompatibility(unhex(ENVELOPE)));
    assertEquals(LinkCompatibility.CURRENT, HandshakeEnvelope.decode(unhex(ENVELOPE)).compatibility());
    assertEquals(-1, HandshakeEnvelope.peekCompatibility(unhex("534c4831")));
  }

  @Test
  void anotherCompatibilityNumberIsRefusedBeforeAnythingElseIsRead() throws Exception {
    byte[] older = unhex(SLB3_V4);
    older[1] = 3;
    IncompatibleVersionException invitation = assertThrows(IncompatibleVersionException.class,
        () -> CompactBootstrapInvitation.decodeMinimized(older));
    assertEquals(new LinkCompatibility.Mismatch("invitation", 10, 3), invitation.mismatch());
    byte[] newerJunk = {0, 11, 1, 2, 3};
    assertEquals(11, assertThrows(IncompatibleVersionException.class,
        () -> CompactBootstrapInvitation.decodeMinimized(newerJunk)).mismatch().remote());
    byte[] answer = unhex(SLA3);
    answer[1] = 7;
    assertEquals(new LinkCompatibility.Mismatch("answer", 10, 7),
        assertThrows(IncompatibleVersionException.class, () -> CompactAnswer.decode(answer))
            .mismatch());
  }

  @Test
  void handshakeFailureIsMagicVersionAndOrdinalForEveryCode() throws Exception {
    HandshakeFailureCode[] codes = HandshakeFailureCode.values();
    for (int ordinal = 0; ordinal < codes.length; ordinal++) {
      if (codes[ordinal] == HandshakeFailureCode.INCOMPATIBLE_VERSION) {
        continue;
      }
      String bytes = "534c463101" + String.format("%02x", ordinal);
      assertEquals(bytes, hex(HandshakeFailure.create(codes[ordinal]).encoded()));
      assertEquals(codes[ordinal], HandshakeFailure.decode(unhex(bytes)).code());
      assertTrue(HandshakeFailure.looksLike(unhex(bytes)));
    }
    String incompatible = "534c463101" + String.format("%02x",
        HandshakeFailureCode.INCOMPATIBLE_VERSION.ordinal()) + "0003";
    assertEquals(incompatible, hex(HandshakeFailure.incompatible(3).encoded()));
    assertEquals(new LinkCompatibility.Mismatch("connection", 10, 3),
        HandshakeFailure.decode(unhex(incompatible)).exception(10).mismatch());
    assertThrows(IOException.class, () -> HandshakeFailure.decode(truncated(incompatible)));
    assertThrows(IOException.class,
        () -> HandshakeFailure.decode(withTrailingByte(incompatible)));
    assertThrows(IOException.class,
        () -> HandshakeFailure.decode(withTrailingByte("534c46310100")));
    assertThrows(IOException.class, () -> HandshakeFailure.decode(truncated("534c46310100")));
    assertThrows(IOException.class,
        () -> HandshakeFailure.decode(unhex("534c4631" + "01" + String.format("%02x", codes.length))));
  }
}
