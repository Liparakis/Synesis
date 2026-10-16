package org.synesis.link.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.NodeIdentity;

/** The project link of ADR-0098 ("Joining, revised"): one signed link for the connection and the project. */
final class ProjectInvitationTest {

  private static final Instant ISSUED = Instant.ofEpochSecond(1_750_000_000L);
  private static final Instant EXPIRES = ISSUED.plus(Duration.ofMinutes(10));
  private static final UUID PROJECT = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
  private static final Instant CREATED = Instant.ofEpochSecond(1_740_000_000L, 123_000_000);
  private static final String URL = "https://example.test/org/repo.git";

  private static CompactBootstrapInvitation connection(NodeIdentity host, int port) throws Exception {
    return CompactBootstrapInvitation.createMinimized(host, ProtocolVersion.V1, ISSUED, EXPIRES,
        new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES],
        List.of(new Candidate(CandidateType.MANUAL, InetAddress.getByName("203.0.113.7"), port, 0)));
  }

  @Test
  void roundTripsTheProjectTheUrlAndTheUnchangedConnectionInvitation() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    CompactBootstrapInvitation inner = connection(host, 4433);
    ProjectInvitation created = ProjectInvitation.create(host, inner, PROJECT, CREATED, URL);

    ProjectInvitation parsed = ProjectInvitation.fromShareLink(created.shareLink());

    assertTrue(created.shareLink().startsWith("synesis://project/SLP1-"));
    assertTrue(ProjectInvitation.isShareLink(created.shareLink()));
    assertFalse(ProjectInvitation.isShareLink(inner.shareLink()));
    assertArrayEquals(created.encoded(), parsed.encoded());
    assertEquals(PROJECT, parsed.projectId());
    assertEquals(CREATED, parsed.createdAt());
    assertEquals(Optional.of(URL), parsed.codeRemoteUrl());
    assertEquals(host.nodeId(), parsed.hostNodeId());
    assertEquals(inner.shareLink(), parsed.invitation().shareLink());
    assertTrue(parsed.verifyAt(ISSUED.plusSeconds(30), Duration.ofMinutes(2)));
    assertFalse(parsed.verifyAt(EXPIRES, Duration.ZERO), "an expired connection invitation expires the project link");
  }

  @Test
  void aLinkWithoutAnUrlSaysSo() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    ProjectInvitation parsed = ProjectInvitation.fromShareLink(
        ProjectInvitation.create(host, connection(host, 4433), PROJECT, CREATED, null).shareLink());

    assertEquals(Optional.empty(), parsed.codeRemoteUrl());
    assertTrue(parsed.verifyAt(ISSUED.plusSeconds(1), Duration.ZERO));
  }

  @Test
  void changingTheProjectOrTheUrlBreaksTheSignatureEvenThoughTheConnectionInvitationIsStillValid()
      throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    ProjectInvitation created = ProjectInvitation.create(host, connection(host, 4433), PROJECT, CREATED, URL);
    byte[] encoded = created.encoded();

    byte[] otherProject = encoded.clone();
    otherProject[2 + 15] ^= 1;
    byte[] otherCreation = encoded.clone();
    otherCreation[2 + 16 + 7] ^= 1;
    byte[] otherUrl = encoded.clone();
    int urlStart = 2 + 16 + 12 + 2;
    otherUrl[urlStart + 8] ^= 1;

    for (byte[] tampered : List.of(otherProject, otherCreation, otherUrl)) {
      ProjectInvitation decoded = ProjectInvitation.fromShareLink(
          "synesis://project/SLP1-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(tampered));
      assertTrue(decoded.invitation().verifyAt(ISSUED.plusSeconds(1), Duration.ZERO));
      assertFalse(decoded.verifyAt(ISSUED.plusSeconds(1), Duration.ZERO));
    }
  }

  @Test
  void aSignedConnectionInvitationCannotBeMovedOntoAnotherProject() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    ProjectInvitation mine = ProjectInvitation.create(host, connection(host, 4433), PROJECT, CREATED, URL);
    ProjectInvitation other = ProjectInvitation.create(host, connection(host, 4434), UUID.randomUUID(), CREATED, URL);
    byte[] spliced = other.encoded();
    byte[] signature = Arrays.copyOfRange(mine.encoded(), mine.encoded().length - 64, mine.encoded().length);
    System.arraycopy(signature, 0, spliced, spliced.length - 64, 64);

    ProjectInvitation decoded = ProjectInvitation.fromShareLink(
        "synesis://project/SLP1-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(spliced));

    assertFalse(decoded.verifyAt(ISSUED.plusSeconds(1), Duration.ZERO));
  }

  @Test
  void onlyTheHostOfTheConnectionInvitationCanSignTheProjectLink() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    NodeIdentity someoneElse = NodeIdentity.generate();

    assertThrows(IllegalArgumentException.class,
        () -> ProjectInvitation.create(someoneElse, connection(host, 4433), PROJECT, CREATED, URL));
  }

  @Test
  void parsingIsStrict() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    ProjectInvitation created = ProjectInvitation.create(host, connection(host, 4433), PROJECT, CREATED, URL);
    String link = created.shareLink();
    byte[] encoded = created.encoded();

    assertThrows(IOException.class, () -> ProjectInvitation.fromShareLink(link.replace("SLP1-", "SLP2-")));
    assertThrows(IOException.class, () -> ProjectInvitation.fromShareLink(link.replace("synesis://project/", "synesis://join/")));
    assertThrows(IOException.class, () -> ProjectInvitation.fromShareLink(link + "x".repeat(ProjectInvitation.MAX_LINK_CHARS)));
    assertThrows(IOException.class, () -> ProjectInvitation.fromShareLink(link + "?a=b"));
    assertThrows(IOException.class, () -> ProjectInvitation.fromShareLink(link.substring(0, link.length() - 4)));
    byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
    assertThrows(IOException.class, () -> ProjectInvitation.fromShareLink(
        "synesis://project/SLP1-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(trailing)));
    byte[] urlLongerThanThePayload = encoded.clone();
    urlLongerThanThePayload[2 + 16 + 12] = (byte) 0xFF;
    assertThrows(IOException.class, () -> ProjectInvitation.fromShareLink(
        "synesis://project/SLP1-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(urlLongerThanThePayload)));
  }

  @Test
  void anotherCompatibilityNumberIsRefusedBeforeAnythingElseIsRead() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    byte[] older = ProjectInvitation.create(host, connection(host, 4433), PROJECT, CREATED, URL).encoded();
    older[1] = 5;

    IncompatibleVersionException refused = assertThrows(IncompatibleVersionException.class,
        () -> ProjectInvitation.fromShareLink("synesis://project/SLP1-"
            + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(older)));

    assertEquals(new LinkCompatibility.Mismatch("invitation", LinkCompatibility.CURRENT, 5), refused.mismatch());
  }

  @Test
  void aLinkNeverCarriesCredentials() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    CompactBootstrapInvitation inner = connection(host, 4433);

    assertEquals("https://github.com/org/repo.git", ProjectInvitation.publicUrl("https://user:secret@github.com/org/repo.git"));
    assertEquals("https://github.com/org/repo.git", ProjectInvitation.publicUrl("https://ghp_token@github.com/org/repo.git"));
    assertEquals("ssh://host/org/repo.git", ProjectInvitation.publicUrl("ssh://deploy:pw@host/org/repo.git"));
    assertEquals("git@github.com:org/repo.git", ProjectInvitation.publicUrl("git@github.com:org/repo.git"));
    assertEquals("ssh://git@github.com/org/repo.git", ProjectInvitation.publicUrl("ssh://git@github.com/org/repo.git"));
    for (String bad : List.of("https://user:secret@github.com/org/repo.git", "https://ghp_token@github.com/o/r.git", "ext::sh -c evil", "--upload-pack=evil",
        "https://example.test/a b", " ", "x".repeat(ProjectInvitation.MAX_URL_BYTES + 1))) {
      assertThrows(IllegalArgumentException.class, () -> ProjectInvitation.create(host, inner, PROJECT, CREATED, bad), bad);
    }
  }

  @Test
  void onlyHttpsSshAndScpStyleUrlsAreAcceptedBothWhenMakingAndWhenReadingALink() throws Exception {
    NodeIdentity host = NodeIdentity.generate();
    CompactBootstrapInvitation inner = connection(host, 4433);
    for (String fine : List.of("https://github.com/org/repo.git", "ssh://git@github.com/org/repo.git", "ssh://host:2222/org/repo", "git@github.com:org/repo.git",
        "git@github.com:/abs/repo.git")) {
      assertEquals(fine, ProjectInvitation.fromShareLink(ProjectInvitation.create(host, inner, PROJECT, CREATED, fine).shareLink()).codeRemoteUrl().orElseThrow(), fine);
    }
    for (String refused : List.of("C:\\work\\repo", "C:/work/repo", "\\\\attacker\\share\\repo.git", "//attacker/share/repo.git", "file:///srv/repo.git", "/srv/repo.git",
        "./repo", "../repo", "http://example.test/repo.git", "git://example.test/repo.git", "ftp://example.test/repo.git", "https://example.test\\repo.git",
        "git@example.test://x", "https:///repo.git")) {
      assertThrows(IllegalArgumentException.class, () -> ProjectInvitation.create(host, inner, PROJECT, CREATED, refused), refused);
      assertFalse(ProjectInvitation.isFetchableUrl(refused), refused);
    }
  }

  @Test
  void twoSpellingsOfOneRepositoryNormalizeAlike() {
    String https = ProjectInvitation.normalizedUrl("https://GitHub.com/Org/Repo.git/");
    assertEquals(ProjectInvitation.normalizedUrl("https://github.com/Org/Repo"), https);
    assertEquals(ProjectInvitation.normalizedUrl("git@github.com:Org/Repo.git"), ProjectInvitation.normalizedUrl("ssh://git@GITHUB.com/Org/Repo"));
    assertFalse(https.equals(ProjectInvitation.normalizedUrl("https://github.com/org/repo")), "the path is case sensitive");
    assertFalse(https.equals(ProjectInvitation.normalizedUrl("https://github.com/Org/Other")));
    assertEquals("/srv/git/repo", ProjectInvitation.normalizedUrl("/srv/git/repo.git"));
  }
}
