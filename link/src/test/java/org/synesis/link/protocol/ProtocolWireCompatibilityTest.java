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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.session.HandshakeFailureCode;
import org.synesis.link.session.HandshakeRole;
import org.synesis.link.session.HandshakeTranscript;

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
  private static final String INVITATION =
      "53494e3101010000112233445566778899aabbccddeeff00000000684ee18000000000684ee3d8002040414243"
      + "4445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f00f3534c4431010044736c312d34323032"
      + "623134666465383333333261336637323233653930376662306434663336383834376663346230653734366561"
      + "333134373339636539346566653830002c302a300506032b65700321006c778044bef297dad48a3d92345b9577"
      + "48c07b535f27ba53612c86d0d150e04a00000000684ee18000000000684ee3d8000201001020010db800000000"
      + "0000000000000001113200000002040004c633640111310000000100408c7ff4a3cdf9f8e3db01bfd496cf4e5d"
      + "b63995f4f910febe0bff49b1b618e64d92848bf5df9fb4082cce2e581d92b0425657d185fc32268ba1b6babf73"
      + "a4b70900402c22fa95c9c91fef4d98fc76a6ce571c93d8bbbba562728017d143f87770133f6f79ca14434868c1"
      + "80039ef3d4f040895fd5f6537b829c5fa47a174b549ae10d";
  private static final String INVITATION_LINK =
      "synesis://join/SYN1-U0lOMQEBAAARIjNEVWZ3iJmqu8zd7v8AAAAAaE7hgAAAAABoTuPYACBAQUJDREVGR0hJSk"
      + "tMTU5PUFFSU1RVVldYWVpbXF1eXwDzU0xEMQEARHNsMS00MjAyYjE0ZmRlODMzMzJhM2Y3MjIzZTkwN2ZiMGQ0ZjM2"
      + "ODg0N2ZjNGIwZTc0NmVhMzE0NzM5Y2U5NGVmZTgwACwwKjAFBgMrZXADIQBsd4BEvvKX2tSKPZI0W5V3SMB7U18nul"
      + "NhLIbQ0VDgSgAAAABoTuGAAAAAAGhO49gAAgEAECABDbgAAAAAAAAAAAAAAAERMgAAAAIEAATGM2QBETEAAAABAECM"
      + "f_Sjzfn449sBv9SWz05dtjmV9PkQ_r4L_0mxthjmTZKEi_Xfn7QILM4uWB2SsEJWV9GF_DImi6G2ur9zpLcJAEAsIv"
      + "qVyckf702Y_Hamzlcck9i7u6VicoAX0UP4d3ATP295yhRDSGjBgAOe89TwQIlf1fZTe4KcX6R6F0tUmuEN";
  private static final String OFFER =
      "534c4f3101010000112233445566778899aabbccddeeff0044736c312d34323032623134666465383333333261"
      + "336637323233653930376662306434663336383834376663346230653734366561333134373339636539346566"
      + "653830002c302a300506032b65700321006c778044bef297dad48a3d92345b957748c07b535f27ba53612c86d0"
      + "d150e04a0044736c312d3065376437626562623062613561383731336630626363636330363132343165653234"
      + "62326335643131653230383039376338366534383336643230643261330020707088440dacb4a036b8a5ccfb4f"
      + "42db97a768a26fb4872b7a1e69fa2d0ac87c00000000684ee18000000000684ee3d80020808182838485868788"
      + "898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f00f3534c4431010044736c312d343230326231346664"
      + "653833333332613366373232336539303766623064346633363838343766633462306537343665613331343733"
      + "39636539346566653830002c302a300506032b65700321006c778044bef297dad48a3d92345b957748c07b535f"
      + "27ba53612c86d0d150e04a00000000684ee18000000000684ee3d8000201001020010db8000000000000000000"
      + "000001113200000002040004c633640111310000000100408c7ff4a3cdf9f8e3db01bfd496cf4e5db63995f4f9"
      + "10febe0bff49b1b618e64d92848bf5df9fb4082cce2e581d92b0425657d185fc32268ba1b6babf73a4b7090040"
      + "312b0e69b678a91601ec12b84861af01eb73a2b082c4783881e0d11114e8d2ed51d1fe01174a591052fa891986"
      + "d76029bd9c96010c1ea57b6c16362f69c45204";
  private static final String SLO1_LINK =
      "synesis://join/SLO1-U0xPMQEAAAGAU0lOMQEBAAARIjNEVWZ3iJmqu8zd7v8AAAAAaE7hgAAAAABoTuPYACBAQU"
      + "JDREVGR0hJSktMTU5PUFFSU1RVVldYWVpbXF1eXwDzU0xEMQEARHNsMS00MjAyYjE0ZmRlODMzMzJhM2Y3MjIzZTkw"
      + "N2ZiMGQ0ZjM2ODg0N2ZjNGIwZTc0NmVhMzE0NzM5Y2U5NGVmZTgwACwwKjAFBgMrZXADIQBsd4BEvvKX2tSKPZI0W5"
      + "V3SMB7U18nulNhLIbQ0VDgSgAAAABoTuGAAAAAAGhO49gAAgEAECABDbgAAAAAAAAAAAAAAAERMgAAAAIEAATGM2QB"
      + "ETEAAAABAECMf_Sjzfn449sBv9SWz05dtjmV9PkQ_r4L_0mxthjmTZKEi_Xfn7QILM4uWB2SsEJWV9GF_DImi6G2ur"
      + "9zpLcJAEAsIvqVyckf702Y_Hamzlcck9i7u6VicoAX0UP4d3ATP295yhRDSGjBgAOe89TwQIlf1fZTe4KcX6R6F0tU"
      + "muENAAACXFNMTzEBAQAAESIzRFVmd4iZqrvM3e7_AERzbDEtNDIwMmIxNGZkZTgzMzMyYTNmNzIyM2U5MDdmYjBkNG"
      + "YzNjg4NDdmYzRiMGU3NDZlYTMxNDczOWNlOTRlZmU4MAAsMCowBQYDK2VwAyEAbHeARL7yl9rUij2SNFuVd0jAe1Nf"
      + "J7pTYSyG0NFQ4EoARHNsMS0wZTdkN2JlYmIwYmE1YTg3MTNmMGJjY2NjMDYxMjQxZWUyNGIyYzVkMTFlMjA4MDk3Yz"
      + "g2ZTQ4MzZkMjBkMmEzACBwcIhEDay0oDa4pcz7T0Lbl6doom-0hyt6Hmn6LQrIfAAAAABoTuGAAAAAAGhO49gAIICB"
      + "goOEhYaHiImKi4yNjo-QkZKTlJWWl5iZmpucnZ6fAPNTTEQxAQBEc2wxLTQyMDJiMTRmZGU4MzMzMmEzZjcyMjNlOT"
      + "A3ZmIwZDRmMzY4ODQ3ZmM0YjBlNzQ2ZWEzMTQ3MzljZTk0ZWZlODAALDAqMAUGAytlcAMhAGx3gES-8pfa1Io9kjRb"
      + "lXdIwHtTXye6U2EshtDRUOBKAAAAAGhO4YAAAAAAaE7j2AACAQAQIAENuAAAAAAAAAAAAAAAAREyAAAAAgQABMYzZA"
      + "ERMQAAAAEAQIx_9KPN-fjj2wG_1JbPTl22OZX0-RD-vgv_SbG2GOZNkoSL9d-ftAgszi5YHZKwQlZX0YX8MiaLoba6"
      + "v3OktwkAQDErDmm2eKkWAewSuEhhrwHrc6KwgsR4OIHg0REU6NLtUdH-ARdKWRBS-okZhtdgKb2clgEMHqV7bBY2L2"
      + "nEUgQ";
  private static final String ANSWER =
      "534c413201010000112233445566778899aabbccddeeff0020e51793af35e89a050e472a5d8524e0b853016223"
      + "b3e6cec35d2debbd131af1d70044736c312d343230326231346664653833333332613366373232336539303766"
      + "623064346633363838343766633462306537343665613331343733396365393465666538300044736c312d3065"
      + "376437626562623062613561383731336630626363636330363132343165653234623263356431316532303830"
      + "3937633836653438333664323064326133002c302a300506032b657003210043dd4151c82991ba97fa22197465"
      + "1b1d9985e7b1901387f7f0ae16dd9325988900000000684ee18000000000684ee3d80020c0c1c2c3c4c5c6c7c8"
      + "c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf00da534c4431010044736c312d306537643762656262"
      + "306261356138373133663062636363633036313234316565323462326335643131653230383039376338366534"
      + "38333664323064326133002c302a300506032b657003210043dd4151c82991ba97fa221974651b1d9985e7b190"
      + "1387f7f0ae16dd9325988900000000684ee18000000000684ee3d80001000004c000020911330000000300407b"
      + "bc5b23481b70390cc8cdbaed955ba406eeb0266e76ea09b3dc59646b55a02ebde0a04a514a67c658641d607d94"
      + "65571ce1c74f891cee73d3aa8f4f0a2cbd0c00403ca7db8effe8e5d7ffb7cbcb0a0a703214cc0a1f23b936623c"
      + "89bebca02d4a627c437c1910a1031804a97b1e0c808aad1a2ef5511cac7aabd18437d3d2557e0f";
  private static final String ANSWER_LINK =
      "synesis://answer/SLA2-U0xBMgEBAAARIjNEVWZ3iJmqu8zd7v8AIOUXk6816JoFDkcqXYUk4LhTAWIjs-bOw10t"
      + "670TGvHXAERzbDEtNDIwMmIxNGZkZTgzMzMyYTNmNzIyM2U5MDdmYjBkNGYzNjg4NDdmYzRiMGU3NDZlYTMxNDczOW"
      + "NlOTRlZmU4MABEc2wxLTBlN2Q3YmViYjBiYTVhODcxM2YwYmNjY2MwNjEyNDFlZTI0YjJjNWQxMWUyMDgwOTdjODZl"
      + "NDgzNmQyMGQyYTMALDAqMAUGAytlcAMhAEPdQVHIKZG6l_oiGXRlGx2ZheexkBOH9_CuFt2TJZiJAAAAAGhO4YAAAA"
      + "AAaE7j2AAgwMHCw8TFxsfIycrLzM3Oz9DR0tPU1dbX2Nna29zd3t8A2lNMRDEBAERzbDEtMGU3ZDdiZWJiMGJhNWE4"
      + "NzEzZjBiY2NjYzA2MTI0MWVlMjRiMmM1ZDExZTIwODA5N2M4NmU0ODM2ZDIwZDJhMwAsMCowBQYDK2VwAyEAQ91BUc"
      + "gpkbqX-iIZdGUbHZmF57GQE4f38K4W3ZMlmIkAAAAAaE7hgAAAAABoTuPYAAEAAATAAAIJETMAAAADAEB7vFsjSBtw"
      + "OQzIzbrtlVukBu6wJm526gmz3Flka1WgLr3goEpRSmfGWGQdYH2UZVcc4cdPiRzuc9Oqj08KLL0MAEA8p9uO_-jl1_"
      + "-3y8sKCnAyFMwKHyO5NmI8ib68oC1KYnxDfBkQoQMYBKl7HgyAiq0aLvVRHKx6q9GEN9PSVX4P";
  private static final String SLB2_V4 =
      "684ee1800258101112131415161718191a1b6c778044bef297dad48a3d92345b957748c07b535f27ba53612c86"
      + "d0d150e04acb007107113429c2cbdabdc7a5e832adfdad5368151f117a2177ad5a53ce9ff517d8ba992c1849e0"
      + "a679b39ef0fc3c7ff46ef2a658bb7a30bdeca771ed2ba805bb2bdeb1e101";
  private static final String SLB2_V4_LINK =
      "synesis://join/SLB2-aE7hgAJYEBESExQVFhcYGRobbHeARL7yl9rUij2SNFuVd0jAe1NfJ7pTYSyG0NFQ4ErLAH"
      + "EHETQpwsvavcel6DKt_a1TaBUfEXohd61aU86f9RfYupksGEngpnmznvD8PH_0bvKmWLt6ML3sp3HtK6gFuyveseEB";
  private static final String SLB2_V6 =
      "684ee1800258101112131415161718191a1b6c778044bef297dad48a3d92345b957748c07b535f27ba53612c86"
      + "d0d150e04a20010db80000000000000000000000071135969357e0332aed3f9f32ae796a937f45a65cc382d2f9"
      + "de085fc55f806bcee0769c7ec7b9d370b1d57acdd39214a261b84c907bf6695e0cfe9ed7f6407bca6c0b";
  private static final String SLB2_V6_LINK =
      "synesis://join/SLB2-aE7hgAJYEBESExQVFhcYGRobbHeARL7yl9rUij2SNFuVd0jAe1NfJ7pTYSyG0NFQ4EogAQ"
      + "24AAAAAAAAAAAAAAAHETWWk1fgMyrtP58yrnlqk39FplzDgtL53ghfxV-Aa87gdpx-x7nTcLHVes3TkhSiYbhMkHv2"
      + "aV4M_p7X9kB7ymwL";
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
      "534c4831010002010101000174534c54310100000e73796e657369732d6c696e6b2f3100112233445566778899"
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
  void sessionInvitationSyn1KeepsCanonicalBytesAndShareLink() throws Exception {
    SessionInvitation invitation = SessionInvitation.decode(unhex(INVITATION));

    assertEquals(INVITATION, hex(invitation.encoded()));
    assertEquals(INVITATION_LINK, invitation.shareLink());
    assertEquals(INVITATION, hex(SessionInvitation.fromShareLink(INVITATION_LINK).encoded()));
    assertEquals(ProtocolVersion.V1, invitation.protocolVersion());
    assertEquals(SESSION, invitation.sessionId());
    assertEquals(ISSUED, invitation.issuedAt());
    assertEquals(EXPIRES, invitation.expiresAt());
    assertEquals("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f",
        hex(invitation.capability()));
    assertEquals(HOST_DESCRIPTOR, hex(invitation.descriptorEncoded()));
    assertTrue(invitation.verifyAt(NOW, SKEW));
    assertFalse(invitation.verifyAt(EXPIRES, SKEW));
  }

  @Test
  void sessionInvitationRejectsTrailingAndTruncatedInput() {
    assertThrows(IOException.class, () -> SessionInvitation.decode(withTrailingByte(INVITATION)));
    assertThrows(IOException.class, () -> SessionInvitation.decode(truncated(INVITATION)));
  }

  @Test
  void traversalOfferV1KeepsCanonicalBytes() throws Exception {
    TraversalOffer offer = TraversalOffer.decode(unhex(OFFER));

    assertEquals(OFFER, hex(offer.encoded()));
    assertEquals(TraversalOffer.FORMAT_VERSION_V1, offer.formatVersion());
    assertTrue(offer.returnTarget().isEmpty());
    assertEquals(ProtocolVersion.V1, offer.protocolVersion());
    assertEquals(SESSION, offer.sessionId());
    assertEquals(HOST_ID, offer.initiatorNodeId());
    assertEquals(HOST_PUBLIC_KEY, hex(offer.initiatorPublicKeyEncoded()));
    assertEquals(joinerId(), offer.expectedResponderNodeId());
    assertEquals(hex(sha256(INVITATION)), hex(offer.invitationDigest()));
    assertEquals(ISSUED, offer.issuedAt());
    assertEquals(EXPIRES, offer.expiresAt());
    assertEquals("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f",
        hex(offer.attemptNonce()));
    assertEquals(HOST_DESCRIPTOR, hex(offer.descriptorEncoded()));
    assertTrue(offer.verifyAt(NOW, SKEW, joinerId()));
    assertEquals(hex(sha256(OFFER)), hex(offer.digest()));
  }

  @Test
  void traversalOfferRejectsTrailingAndTruncatedInput() {
    assertThrows(IOException.class, () -> TraversalOffer.decode(withTrailingByte(OFFER)));
    assertThrows(IOException.class, () -> TraversalOffer.decode(truncated(OFFER)));
  }

  @Test
  void traversalInvitationSlo1V1KeepsCanonicalBytesAndShareLink() throws Exception {
    String bytes = "534c4f31" + "01" + lp(4, INVITATION) + lp(4, OFFER);
    TraversalInvitation link = TraversalInvitation.decode(unhex(bytes));

    assertEquals(bytes, hex(link.encoded()));
    assertEquals(SLO1_LINK, link.shareLink());
    assertEquals(bytes, hex(TraversalInvitation.fromShareLink(SLO1_LINK).encoded()));
    assertEquals(TraversalInvitation.FORMAT_VERSION_V1, link.formatVersion());
    assertTrue(link.returnTarget().isEmpty());
    assertEquals(INVITATION, hex(link.invitation().encoded()));
    assertEquals(OFFER, hex(link.offer().encoded()));
    assertTrue(link.verifyAt(NOW, SKEW, joinerId()));
    assertThrows(IOException.class, () -> TraversalInvitation.decode(withTrailingByte(bytes)));
    assertThrows(IOException.class, () -> TraversalInvitation.decode(truncated(bytes)));
  }

  @Test
  void traversalAnswerV1KeepsCanonicalBytesAndShareLink() throws Exception {
    TraversalAnswer answer = TraversalAnswer.decode(unhex(ANSWER));
    TraversalOffer offer = TraversalOffer.decode(unhex(OFFER));

    assertEquals(ANSWER, hex(answer.encoded()));
    assertEquals(ANSWER_LINK, answer.shareLink());
    assertEquals(ANSWER, hex(TraversalAnswer.fromShareLink(ANSWER_LINK).encoded()));
    assertEquals(TraversalAnswer.FORMAT_VERSION_V1, answer.formatVersion());
    assertTrue(answer.returnTarget().isEmpty());
    assertEquals(ProtocolVersion.V1, answer.protocolVersion());
    assertEquals(SESSION, answer.sessionId());
    assertEquals(hex(offer.digest()), hex(answer.offerDigest()));
    assertEquals(HOST_ID, answer.initiatorNodeId());
    assertEquals(joinerId(), answer.responderNodeId());
    assertEquals(ISSUED, answer.issuedAt());
    assertEquals(EXPIRES, answer.expiresAt());
    assertEquals("c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf",
        hex(answer.answerNonce()));
    assertEquals(JOINER_DESCRIPTOR, hex(answer.descriptorEncoded()));
    assertTrue(answer.verifyAt(NOW, SKEW, offer));
    assertThrows(IOException.class, () -> TraversalAnswer.decode(withTrailingByte(ANSWER)));
    assertThrows(IOException.class, () -> TraversalAnswer.decode(truncated(ANSWER)));
  }

  @Test
  void compactBootstrapSlb2KeepsCanonicalBytesAndShareLinkForBothAddressFamilies()
      throws Exception {
    UUID expectedSession = UUID.fromString("35a5cb90-4a19-cbf9-e995-5a47d00c7e4d");
    for (String[] vector : new String[][]{{SLB2_V4, SLB2_V4_LINK, "120"},
        {SLB2_V6, SLB2_V6_LINK, "132"}}) {
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
      assertEquals(HOST_ID, invitation.hostNodeId());
      assertEquals(HOST_PUBLIC_KEY, hex(invitation.publicKeyEncoded()));
      assertEquals(0, invitation.endpoint().priority());
      assertTrue(invitation.verifyAt(NOW, SKEW));
      assertThrows(IOException.class,
          () -> CompactBootstrapInvitation.decodeMinimized(withTrailingByte(vector[0])));
      assertThrows(IOException.class,
          () -> CompactBootstrapInvitation.decodeMinimized(truncated(vector[0])));
    }
    CompactBootstrapInvitation v4 = CompactBootstrapInvitation.decodeMinimized(unhex(SLB2_V4));
    CompactBootstrapInvitation v6 = CompactBootstrapInvitation.decodeMinimized(unhex(SLB2_V6));
    assertEquals(CandidateType.MANUAL, v4.endpoint().type());
    assertEquals(4404, v4.endpoint().port());
    assertEquals("203.0.113.7", v4.endpoint().address().getHostAddress());
    assertEquals(CandidateType.IPV6, v6.endpoint().type());
    assertEquals(4405, v6.endpoint().port());
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
    String bytes = "534c4831" + "01" + "00" + "02" + "0101" + "0100" + lp(2, TRANSCRIPT)
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
  void handshakeFailureIsMagicVersionAndOrdinalForEveryCode() throws Exception {
    HandshakeFailureCode[] codes = HandshakeFailureCode.values();
    for (int ordinal = 0; ordinal < codes.length; ordinal++) {
      String bytes = "534c463101" + String.format("%02x", ordinal);
      assertEquals(bytes, hex(HandshakeFailure.create(codes[ordinal]).encoded()));
      assertEquals(codes[ordinal], HandshakeFailure.decode(unhex(bytes)).code());
      assertTrue(HandshakeFailure.looksLike(unhex(bytes)));
    }
    assertThrows(IOException.class,
        () -> HandshakeFailure.decode(withTrailingByte("534c46310100")));
    assertThrows(IOException.class, () -> HandshakeFailure.decode(truncated("534c46310100")));
    assertThrows(IOException.class,
        () -> HandshakeFailure.decode(unhex("534c4631" + "01" + String.format("%02x", codes.length))));
  }
}
