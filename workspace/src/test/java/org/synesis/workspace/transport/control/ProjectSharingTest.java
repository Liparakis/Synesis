package org.synesis.workspace.transport.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.synesis.coordination.application.MembershipService;
import org.synesis.coordination.domain.membership.MembershipProjection;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.coordination.persistence.ReplicationPeers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.CompactBootstrapInvitation;
import org.synesis.link.protocol.ProjectInvitation;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/** What a computer does to make and follow a project link, decided once (ADR-0098, "Joining, revised"). */
final class ProjectSharingTest {

  private static final String URL = "https://example.test/org/project.git";

  @TempDir
  Path temp;
  private final ProjectApplicationService service = new ProjectApplicationService();
  private KnownProjectRegistry registry;
  private ProjectLocation owner;
  private NodeIdentity ownerNode;

  @BeforeEach
  void owner() throws Exception {
    Path root = Files.createDirectories(temp.resolve("owner"));
    owner = service.initForRegistration(root, false).location();
    ownerNode = owner.loadOrCreateIdentity();
    registry = new KnownProjectRegistry(temp.resolve("registry/known.json"));
  }

  private ProjectInvitation invitation(String url) throws Exception {
    CompactBootstrapInvitation connection = CompactBootstrapInvitation.createMinimized(ownerNode, ProtocolVersion.V1,
        Instant.now(), Instant.now().plusSeconds(300), new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES],
        List.of(new Candidate(CandidateType.MANUAL, InetAddress.getByName("203.0.113.7"), 4433, 0)));
    return ProjectInvitation.create(ownerNode, connection, owner.projectId(), owner.createdAt(), url);
  }

  private ProjectLocation member(String name) throws Exception {
    Path root = Files.createDirectories(temp.resolve(name));
    GitProcessRunner.run(temp, "clone", owner.root().toString(), root.toString());
    return service.initReplica(root, owner.identity(), false).location();
  }

  private MembershipService membership() throws Exception {
    return new MembershipService(new PredictionEventStore(owner.coordinationDirectory(), owner.projectId()), ownerNode);
  }

  private MembershipProjection projection() throws Exception {
    return new PredictionEventStore(owner.coordinationDirectory(), owner.projectId()).membershipProjection();
  }

  private void admit(String node, String url) throws Exception {
    ProjectSharing.admit(owner.root(), owner.coordinationDirectory(), membership(), node, url);
  }

  private static String newNode() throws Exception {
    return NodeIdentity.generate().nodeId();
  }

  private static String follow(ProjectLocation location, ProjectInvitation invitation, String ownNodeId) throws Exception {
    return ProjectSharing.follow(location.root(), location.coordinationDirectory(), invitation, ownNodeId, () -> { });
  }

  /** The folders under the test directory; the registry's own folder is not a project. */
  private long folderCount() throws java.io.IOException {
    try (var children = Files.list(temp)) {
      return children.filter(child -> !child.getFileName().toString().equals("registry")).count();
    }
  }

  @Test
  void invitingNamesTheOnlyRemoteWithoutCredentialsAndWritesNothing() throws Exception {
    GitProcessRunner.run(owner.root(), "remote", "add", "origin", "https://user:secret@example.test/org/project.git");

    ProjectSharing.Leader leader = ProjectSharing.invitationRemote(owner.root(), owner.coordinationDirectory());

    assertEquals(new ProjectSharing.Leader("origin", URL, null), leader);
    assertTrue(ReplicationSettings.read(owner.coordinationDirectory()).isEmpty(),
        "an invitation writes no role file: a computer without one keeps working until it accepts a joiner");
  }

  @Test
  void aConfiguredRemoteWinsSeveralRemotesAreNeverGuessedAndTheRoleFileIsLeftAlone() throws Exception {
    GitProcessRunner.run(owner.root(), "remote", "add", "origin", URL);
    GitProcessRunner.run(owner.root(), "remote", "add", "backup", "https://example.test/org/backup.git");
    ReplicationSettings configured = new ReplicationSettings(null, Set.of("sl1-a"), "backup");
    configured.write(owner.coordinationDirectory());

    ProjectSharing.Leader named = ProjectSharing.invitationRemote(owner.root(), owner.coordinationDirectory());

    assertEquals(new ProjectSharing.Leader("backup", "https://example.test/org/backup.git", null), named);
    assertEquals(configured, ReplicationSettings.read(owner.coordinationDirectory()).orElseThrow());
    Files.delete(owner.coordinationDirectory().resolve(ReplicationSettings.FILE));

    assertEquals(new ProjectSharing.Leader(null, null, "SEVERAL_GIT_REMOTES"), ProjectSharing.invitationRemote(owner.root(), owner.coordinationDirectory()),
        "two remotes and none configured: the invitation names no URL");
    assertTrue(ReplicationSettings.read(owner.coordinationDirectory()).isEmpty());

    new ReplicationSettings(null, Set.of(), "gone").write(owner.coordinationDirectory());
    assertEquals(new ProjectSharing.Leader("gone", null, "CODE_REMOTE_MISSING"), ProjectSharing.invitationRemote(owner.root(), owner.coordinationDirectory()),
        "an explicit code.remote is never replaced by a guess");
  }

  @Test
  void noRemoteOrAnUrlAFriendMustNotFetchFromGivesNoUrlAndSaysWhy() throws Exception {
    assertEquals(new ProjectSharing.Leader(null, null, "NO_GIT_REMOTE"), ProjectSharing.invitationRemote(owner.root(), owner.coordinationDirectory()));

    GitProcessRunner.run(owner.root(), "remote", "add", "origin", URL);
    for (String unsafe : List.of("C:\\work\\project", "\\\\attacker\\share\\project.git", "file:///srv/project.git", "/srv/project.git", "../project", "ftp://example.test/p.git")) {
      GitProcessRunner.run(owner.root(), "remote", "set-url", "origin", unsafe);
      assertEquals(new ProjectSharing.Leader("origin", null, "URL_NOT_SHAREABLE"), ProjectSharing.invitationRemote(owner.root(), owner.coordinationDirectory()), unsafe);
    }
    for (String fine : List.of("ssh://git@example.test/org/p.git", "git@example.test:org/p.git", "https://example.test/org/p.git")) {
      GitProcessRunner.run(owner.root(), "remote", "set-url", "origin", fine);
      assertEquals(fine, ProjectSharing.invitationRemote(owner.root(), owner.coordinationDirectory()).url(), fine);
    }
  }

  @Test
  void aStepFiveMemberFileIsStillRecognizedAndASharedFileIsNotAMemberFile() throws Exception {
    assertFalse(ProjectSharing.isMember(owner.coordinationDirectory()));
    ReplicationSettings.sharedProject(null).write(owner.coordinationDirectory());
    assertFalse(ProjectSharing.isMember(owner.coordinationDirectory()), "a role=shared file names no leader");
    ReplicationSettings.member("sl1-leader").write(owner.coordinationDirectory());

    assertTrue(ProjectSharing.isMember(owner.coordinationDirectory()));
  }

  @Test
  void followingMakesTheComputerASharedProjectComputerThatDialedTheInviterAndReusesTheRemoteThatHasTheUrl() throws Exception {
    ProjectLocation friend = member("friend");
    GitProcessRunner.run(friend.root(), "remote", "add", "upstream", "git@example.test:org/project.git");
    GitProcessRunner.run(friend.root(), "remote", "add", "code", "https://EXAMPLE.test/org/project");
    ProjectInvitation invitation = invitation(URL);

    String remote = follow(friend, invitation, newNode());

    assertEquals("code", remote, "the remote whose URL matches, spelled differently");
    assertEquals(ReplicationSettings.sharedProject("code"), ReplicationSettings.read(friend.coordinationDirectory()).orElseThrow());
    assertEquals(java.util.Map.of(ownerNode.nodeId(), invitation.invitation().shareLink()), ReplicationPeers.read(friend.coordinationDirectory()).dialed(),
        "the inviter is recorded as dialed with the link: until the owner's entries arrive it is who this computer trusts");
  }

  @Test
  void followingDropsTheRecordsOfEveryOtherNodeFromAnEarlierRole() throws Exception {
    ProjectLocation friend = member("friend");
    String stale = newNode();
    ReplicationPeers.rememberDialed(friend.coordinationDirectory(), stale, "synesis://old-link");

    follow(friend, invitation(URL), newNode());

    assertEquals(Set.of(ownerNode.nodeId()), ReplicationPeers.read(friend.coordinationDirectory()).dialed().keySet());
  }

  @Test
  void followingAddsARemoteForTheUrlWhenNoneMatchesAndRefusesToTakeOverAnotherSynesisRemote() throws Exception {
    ProjectLocation friend = member("friend");

    assertEquals(ProjectSharing.ADDED_REMOTE, follow(friend, invitation(URL), newNode()));
    assertEquals(URL, GitProcessRunner.run(friend.root(), "remote", "get-url", "synesis").strip());

    ProjectLocation other = member("other");
    GitProcessRunner.run(other.root(), "remote", "add", "synesis", "https://elsewhere.test/x.git");
    assertEquals("CODE_REMOTE_NAME_TAKEN", assertThrows(ProjectSharing.Refusal.class, () -> follow(other, invitation(URL), newNode())).code());
    assertTrue(ReplicationSettings.read(other.coordinationDirectory()).isEmpty(), "a refusal writes nothing");
    assertTrue(ReplicationPeers.read(other.coordinationDirectory()).dialed().isEmpty(), "and records no inviter");
  }

  @Test
  void followingWithoutAnUrlSetsNoRemote() throws Exception {
    ProjectLocation friend = member("friend");

    assertNull(follow(friend, invitation(null), newNode()));
    assertEquals(ReplicationSettings.sharedProject(null), ReplicationSettings.read(friend.coordinationDirectory()).orElseThrow());
  }

  @Test
  void followingIsRefusedWhenItWouldStrandWork() throws Exception {
    ProjectLocation friend = member("friend");
    ProjectInvitation invitation = invitation(URL);
    String self = newNode();

    assertEquals("INVITER_IS_THIS_COMPUTER", assertThrows(ProjectSharing.Refusal.class, () -> follow(friend, invitation, ownerNode.nodeId())).code());

    new ReplicationSettings(null, Set.of("sl1-someone"), null).write(friend.coordinationDirectory());
    assertEquals("ALREADY_A_LEADER", assertThrows(ProjectSharing.Refusal.class, () -> follow(friend, invitation, self)).code(),
        "a step-5 leader file with members still refuses: it has members of its own not yet migrated");

    ReplicationSettings.member("sl1-another").write(friend.coordinationDirectory());
    assertEquals("FOLLOWING_ANOTHER_LEADER", assertThrows(ProjectSharing.Refusal.class, () -> follow(friend, invitation, self)).code());
    assertEquals("sl1-another", ReplicationSettings.read(friend.coordinationDirectory()).orElseThrow().leaderNodeId());

    ProjectLocation withLog = member("withLog");
    Files.createDirectories(withLog.coordinationDirectory().resolve("events"));
    Files.writeString(withLog.coordinationDirectory().resolve("events/00000000000000000001.sce"), "x");
    assertEquals("LOG_NOT_EMPTY", assertThrows(ProjectSharing.Refusal.class, () -> follow(withLog, invitation, self)).code());
    assertTrue(ReplicationPeers.read(withLog.coordinationDirectory()).dialed().isEmpty(), "a refusal records no inviter");
  }

  @Test
  void followingTheSameInviterAgainIsAllowed() throws Exception {
    ProjectLocation friend = member("friend");
    String self = newNode();
    follow(friend, invitation(URL), self);

    assertEquals(ProjectSharing.ADDED_REMOTE, follow(friend, invitation(URL), self));
    assertEquals(ReplicationSettings.sharedProject(ProjectSharing.ADDED_REMOTE), ReplicationSettings.read(friend.coordinationDirectory()).orElseThrow());
  }

  @Test
  void acceptingSharesTheProjectAndAdmitsTheNodeOnceAsEntriesWithTheRemoteTheLinkCarried() throws Exception {
    GitProcessRunner.run(owner.root(), "remote", "add", "origin", URL);
    String friend = newNode();

    admit(friend, URL);

    assertEquals(ReplicationSettings.sharedProject("origin"), ReplicationSettings.read(owner.coordinationDirectory()).orElseThrow(),
        "the file says only that the project is shared; who belongs is in the log");
    MembershipProjection membership = projection();
    assertTrue(membership.shared());
    assertEquals(ownerNode.nodeId(), membership.owner().orElseThrow());
    assertEquals(Set.of(ownerNode.nodeId(), friend), membership.members());
    assertEquals(1, membership.requests().size());
    assertEquals(0, membership.openRequests().size());

    String second = newNode();
    admit(second, "https://EXAMPLE.test/org/project");
    assertEquals(Set.of(ownerNode.nodeId(), friend, second), projection().members());
  }

  @Test
  void acceptingAMemberAgainIsIdempotentSoARetryAfterALostResponseOrAnUnwrittenFileWorks() throws Exception {
    GitProcessRunner.run(owner.root(), "remote", "add", "origin", URL);
    String friend = newNode();
    admit(friend, URL);
    Files.delete(owner.coordinationDirectory().resolve(ReplicationSettings.FILE));

    admit(friend, URL);

    assertEquals(ReplicationSettings.sharedProject("origin"), ReplicationSettings.read(owner.coordinationDirectory()).orElseThrow());
    assertEquals(1, projection().requests().size(), "no second request is written");
  }

  @Test
  void acceptingWithoutAUrlOrWithOneNoRemoteHasNamesNoMirrorBecauseCodeMovesOverLink() throws Exception {
    GitProcessRunner.run(owner.root(), "remote", "add", "origin", URL);
    String friend = newNode();
    String second = newNode();

    admit(friend, null);
    assertEquals(ReplicationSettings.sharedProject(null), ReplicationSettings.read(owner.coordinationDirectory()).orElseThrow());

    admit(second, "https://example.test/other.git");
    assertEquals(ReplicationSettings.sharedProject(null), ReplicationSettings.read(owner.coordinationDirectory()).orElseThrow());
    assertEquals(Set.of(ownerNode.nodeId(), friend, second), projection().members());
  }

  @Test
  void aMirrorAlreadyNamedStaysWhenALaterInvitationCarriedNoUrl() throws Exception {
    GitProcessRunner.run(owner.root(), "remote", "add", "origin", URL);
    admit(newNode(), URL);

    admit(newNode(), null);

    assertEquals(ReplicationSettings.sharedProject("origin"), ReplicationSettings.read(owner.coordinationDirectory()).orElseThrow());
  }

  @Test
  void acceptingOverAStepFiveLeaderFileRewritesItAsSharedKeepingTheCodeRemote() throws Exception {
    GitProcessRunner.run(owner.root(), "remote", "add", "origin", URL);
    ReplicationSettings.leader(Set.of("sl1-old")).withCodeRemote("origin").write(owner.coordinationDirectory());

    admit(newNode(), null);

    assertEquals(ReplicationSettings.sharedProject("origin"), ReplicationSettings.read(owner.coordinationDirectory()).orElseThrow());
  }

  @Test
  void aComputerThatIsNotTheOwnerCannotAcceptUnderTheOwnerOnlyPolicyAndNothingIsWritten() throws Exception {
    String friend = newNode();
    admit(friend, null);
    NodeIdentity friendIdentity = NodeIdentity.generate();
    PredictionEventStore store = new PredictionEventStore(owner.coordinationDirectory(), owner.projectId());
    assertFalse(store.membershipProjection().members().contains(friendIdentity.nodeId()));
    MembershipService stranger = new MembershipService(store, friendIdentity);

    assertThrows(java.io.IOException.class, () -> ProjectSharing.admit(owner.root(), owner.coordinationDirectory(), stranger, newNode(), null),
        "a computer that is not a member writes no request");
    assertEquals(Set.of(ownerNode.nodeId(), friend), projection().members());
    assertEquals(0, projection().openRequests().size());
  }

  @Test
  void concurrentAcceptsKeepEveryMember() throws Exception {
    GitProcessRunner.run(owner.root(), "remote", "add", "origin", URL);
    int accepts = 8;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(accepts);
    try {
      List<Future<?>> done = new java.util.ArrayList<>();
      for (int index = 0; index < accepts; index++) {
        String node = newNode();
        done.add(pool.submit(() -> {
          start.await();
          admit(node, URL);
          return null;
        }));
      }
      start.countDown();
      for (Future<?> one : done) {
        one.get();
      }
    } finally {
      pool.shutdownNow();
    }

    assertEquals(accepts + 1, projection().members().size(), "every accepted node and the owner");
  }

  /**
   * The interleaving is forced instead of raced: another writer (an agent's append, a second open) holds the project
   * lock while the open is made, and appends an entry before letting go.
   */
  @Test
  void followingWaitsForTheProjectLockSoAnEntryAppendedMeanwhileStopsItAndNothingIsWritten() throws Exception {
    ProjectLocation friend = member("friend");
    String self = newNode();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    ProjectAppendLock held = ProjectAppendLock.acquire(friend.coordinationDirectory());
    try {
      Future<String> following = pool.submit(() -> follow(friend, invitation(URL), self));
      Thread.sleep(300);
      assertFalse(following.isDone(), "the open waits for the lock");
      assertTrue(ReplicationSettings.read(friend.coordinationDirectory()).isEmpty());
      Files.createDirectories(friend.coordinationDirectory().resolve("events"));
      Files.writeString(friend.coordinationDirectory().resolve("events/00000000000000000001.sce"), "x");
      held.close();

      java.util.concurrent.ExecutionException failed = assertThrows(java.util.concurrent.ExecutionException.class, following::get);
      assertEquals("LOG_NOT_EMPTY", ((ProjectSharing.Refusal) failed.getCause()).code());
    } finally {
      pool.shutdownNow();
    }

    assertTrue(ReplicationSettings.read(friend.coordinationDirectory()).isEmpty());
    assertFalse(GitProcessRunner.run(friend.root(), "remote").contains(ProjectSharing.ADDED_REMOTE));
  }

  @Test
  void followingPutsTheRoleAndTheRemoteBackWhenTheRunningSideCannotSwitch() throws Exception {
    ProjectLocation friend = member("friend");
    String self = newNode();

    assertThrows(java.io.IOException.class, () -> ProjectSharing.follow(friend.root(), friend.coordinationDirectory(), invitation(URL), self,
        () -> {
          throw new java.io.IOException("FOLLOWING_ANOTHER_LEADER");
        }));

    assertTrue(ReplicationSettings.read(friend.coordinationDirectory()).isEmpty(), "the file never names a leader the running side does not follow");
    assertFalse(GitProcessRunner.run(friend.root(), "remote").contains(ProjectSharing.ADDED_REMOTE), "the remote this call added is removed");
    assertEquals(ProjectSharing.ADDED_REMOTE, follow(friend, invitation(URL), self), "opening the same link again works");
  }

  @Test
  void followingThatCannotSwitchTheRunningSideLeavesNoDialedInviterToTrust() throws Exception {
    ProjectLocation friend = member("friend");

    assertThrows(java.io.IOException.class, () -> ProjectSharing.follow(friend.root(), friend.coordinationDirectory(), invitation(URL), newNode(),
        () -> {
          throw new java.io.IOException("FOLLOWING_ANOTHER_LEADER");
        }));

    assertTrue(ReplicationPeers.read(friend.coordinationDirectory()).dialed().isEmpty(),
        "before sharing the dialed lines are who the computer trusts: a failed follow must not leave the inviter there");
  }

  @Test
  void aProjectRegisteredHereIsFoundByItsIdentityWithoutAskingForAFolder() throws Exception {
    ProjectLocation friend = member("friend");
    registry.register(friend.root());

    Optional<Path> found = ProjectSharing.locate(registry, service, invitation(URL), null, null);

    assertEquals(Optional.of(friend.root()), found);
  }

  @Test
  void ofSeveralCopiesRegisteredHereTheOneBeingWorkedInWinsElseTheFirstByPath() throws Exception {
    ProjectLocation first = member("a-first");
    ProjectLocation second = member("b-second");
    registry.register(first.root());
    registry.register(second.root());

    assertEquals(Optional.of(first.root()), ProjectSharing.locate(registry, service, invitation(URL), null, null));
    assertEquals(Optional.of(second.root()), ProjectSharing.locate(registry, service, invitation(URL), null, second.root()));
    assertEquals(Optional.of(first.root()), ProjectSharing.locate(registry, service, invitation(URL), null, temp.resolve("elsewhere")));
  }

  @Test
  void anUnregisteredProjectIsNotFoundAndNothingIsCreated() throws Exception {
    long before = folderCount();

    assertEquals(Optional.empty(), ProjectSharing.locate(registry, service, invitation(URL), null, null));
    assertEquals(before, folderCount(), "Synesis never clones");
  }

  @Test
  void aChosenFolderWithTheSameIdentityIsRegisteredAndUsed() throws Exception {
    ProjectLocation friend = member("friend");

    Optional<Path> found = ProjectSharing.locate(registry, service, invitation(URL), friend.root(), null);

    assertEquals(Optional.of(friend.root()), found);
    assertEquals(List.of(friend.root()), registry.projects().stream().filter(view -> view.projectId().equals(owner.projectId())).map(KnownProjectRegistry.ProjectView::path)
        .filter(friend.root()::equals).toList());
  }

  @Test
  void aChosenCloneWithOnlyTheProjectFileBecomesAReplica() throws Exception {
    Path clone = Files.createDirectories(temp.resolve("clone"));
    GitProcessRunner.run(temp, "clone", owner.root().toString(), clone.toString());
    assertTrue(Files.exists(clone.resolve(".synesis/project.json")), "the project file travels with Git");
    assertFalse(Files.exists(clone.resolve(".synesis/local")));

    Optional<Path> found = ProjectSharing.locate(registry, service, invitation(URL), clone, null);

    assertEquals(Optional.of(clone), found);
    assertTrue(Files.isDirectory(clone.resolve(".synesis/local")), "initReplica made the local state");
  }

  @Test
  void aFolderOfAnotherProjectIsRefusedAndNotRegistered() throws Exception {
    Path elsewhere = Files.createDirectories(temp.resolve("elsewhere"));
    service.initForRegistration(elsewhere, false);

    assertEquals("PROJECT_IDENTITY_MISMATCH", assertThrows(ProjectSharing.Refusal.class, () -> ProjectSharing.locate(registry, service, invitation(URL), elsewhere, null)).code());
    assertTrue(registry.projects().isEmpty());
  }

  @Test
  void aFolderThatIsNotTheProjectAtAllIsRefusedUnlessItsRemoteIsTheInvitationsUrl() throws Exception {
    Path plain = Files.createDirectories(temp.resolve("plain"));
    GitProcessRunner.run(plain, "init");

    assertEquals("PROJECT_FOLDER_NOT_THE_PROJECT", assertThrows(ProjectSharing.Refusal.class, () -> ProjectSharing.locate(registry, service, invitation(URL), plain, null)).code());
    assertEquals("PROJECT_FOLDER_NOT_THE_PROJECT", assertThrows(ProjectSharing.Refusal.class, () -> ProjectSharing.locate(registry, service, invitation(null), plain, null)).code());
    assertEquals("PROJECT_FOLDER_INVALID", assertThrows(ProjectSharing.Refusal.class, () -> ProjectSharing.locate(registry, service, invitation(URL), temp.resolve("missing/deeper"), null)).code());
    assertFalse(Files.exists(temp.resolve("missing")), "only a new folder in a folder that exists is made");
    assertFalse(Files.exists(plain.resolve(".synesis")), "a refusal initializes nothing");
  }

  @Test
  void anEmptyOrNewFolderBecomesACopyOfTheProjectThatHoldsNoCodeYet() throws Exception {
    Path empty = Files.createDirectories(temp.resolve("empty"));
    Path fresh = temp.resolve("fresh");

    for (Path folder : List.of(empty, fresh)) {
      Optional<Path> found = ProjectSharing.locate(registry, service, invitation(null), folder, null);

      assertEquals(Optional.of(folder.toAbsolutePath().normalize()), found);
      assertTrue(service.require(folder).identity().matches(owner.identity()), "the copy carries the invitation's project");
      assertFalse(Files.exists(folder.resolve(".git")), "the repository comes over Link once the owner accepts");
      assertTrue(registry.projects().stream().anyMatch(view -> view.path().equals(found.orElseThrow())));
    }
  }
}
