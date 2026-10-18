package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.command.coordination.TestComputers.Computer;
import org.synesis.cli.command.coordination.TestComputers.Response;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.LoopbackExchange;
import org.synesis.coordination.persistence.ReplicationPeers;
import org.synesis.coordination.persistence.ReplicationSettings;

import org.synesis.link.protocol.ProjectInvitation;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * One link takes a friend into a project (ADR-0098, "Joining, revised"): the owner invites, the friend opens the link,
 * the owner accepts. Computers here are composed as the launcher composes them, over real loopback sessions, and driven
 * through the control plane with the runtime command token as {@code synesis link} drives it.
 */
@Timeout(180)
final class ProjectLinkFlowTest {

    private static final String REMOTE_URL = "https://example.test/org/project.git";

    @TempDir
    Path temp;
    private final TestComputers computers = new TestComputers();
    private final ProjectApplicationService service = computers.service();
    private KnownProjectRegistry friendRegistry;
    private ProjectLocation ownerProject;
    private Path friendHomeRoot;

    @BeforeEach
    void projects() throws Exception {
        Path ownerRoot = Files.createDirectories(temp.resolve("owner"));
        ownerProject = service.initForRegistration(ownerRoot, false).location();
        GitProcessRunner.run(ownerRoot, "remote", "add", "origin", "https://user:secret@example.test/org/project.git");
        friendRegistry = new KnownProjectRegistry(temp.resolve("friend-registry/known.json"));
        friendHomeRoot = Files.createDirectories(temp.resolve("friend-home"));
        service.initForRegistration(friendHomeRoot, false);
    }

    @AfterEach
    void stop() {
        computers.close();
    }

    /** The friend's copy: a Git clone of the owner's repository made a replica of the project, as `init` on a clone does not. */
    private ProjectLocation friendClone(String name) throws Exception {
        Path root = temp.resolve(name);
        GitProcessRunner.run(temp, "clone", ownerProject.root().toString(), root.toString());
        return service.initReplica(root, ownerProject.identity(), false).location();
    }

    /** The project folders under the test directory; the registries' own folders are not projects. */
    private long folderCount() throws IOException {
        try (var children = Files.list(temp)) {
            return children.filter(child -> !child.getFileName().toString().endsWith("registry")).count();
        }
    }

    private WorkIntent lane(String participant, String path) {
        UUID intentId = UUID.randomUUID();
        return new WorkIntent(intentId, ownerProject.projectId(), participant, "codex", UUID.randomUUID(), "Build a board", "CRUD and persistence", "base-commit",
                List.of(ResourceSelector.pathExact(path)), 1, UUID.randomUUID(), WorkIntent.defaultAuthorityLineage(intentId), WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER, List.of());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "condition not reached");
    }

    /** How many entries of {@code author} took effect in {@code holder}'s log, its own or copied; -1 while it cannot be read. */
    private static long entriesOf(Computer holder, Computer author) {
        try {
            return holder.store().events().stream().filter(event -> event.actorNodeId().equals(author.node.nodeId())).count();
        } catch (Exception unreadable) {
            return -1;
        }
    }

    private static boolean announces(Computer computer, WorkIntent intent) {
        try {
            return new WorkIntentService(computer.store(), computer.node).announce(intent).acquired();
        } catch (Exception refused) {
            return false;
        }
    }

    private String projectLinkOf(Computer owner) throws Exception {
        Response invited = owner.post("invite", Map.of("project", true));
        assertEquals(201, invited.status(), invited.toString());
        return (String) invited.body().get("inviteUri");
    }

    @Test
    void theOwnerInvitesTheFriendOpensTheLinkTheOwnerAcceptsAndTheEntriesOfBothLandOnTheOther() throws Exception {
        Computer owner = computers.start(ownerProject, null);
        ProjectLocation friendProject = friendClone("friend");
        friendRegistry.register(friendProject.root());
        Computer friend = computers.start(friendProject, friendRegistry);
        Computer friendHome = computers.start(service.require(friendHomeRoot), friendRegistry);

        Response invited = owner.post("invite", Map.of("project", true));
        assertEquals(201, invited.status(), invited.toString());
        String link = (String) invited.body().get("inviteUri");
        assertTrue(link.startsWith("synesis://project/SLP1-"), link);
        assertEquals("PROJECT", invited.body().get("kind"));
        assertEquals(REMOTE_URL, invited.body().get("codeRemoteUrl"), "the credentials in the remote never reach the link");
        assertFalse(link.contains("secret"));
        assertEquals("origin", invited.body().get("codeRemoteName"));
        assertTrue(ReplicationSettings.read(ownerProject.coordinationDirectory()).isEmpty(), "inviting writes nothing: the project is shared when a joiner is held");
        assertEquals(List.of(), owner.membershipEntries());

        // The friend pastes it into a UI served by another project's runtime; the folder is found by identity.
        Response opened = friendHome.post("open-project-link", Map.of("link", link));
        assertEquals(200, opened.status(), opened.toString());
        assertEquals("CONNECTING", opened.body().get("state"));
        assertEquals(friendProject.root().toString(), opened.body().get("folder"));
        assertEquals("member", opened.body().get("role"));
        assertNotNull(opened.body().get("answerUri"), "the answer link for a friend on another network");
        assertEquals(ReplicationSettings.sharedProject("synesis"), friend.role(), "the joiner is role=shared at once; who belongs is the owner's log");
        assertEquals(Set.of(owner.node.nodeId()), ReplicationPeers.read(friendProject.coordinationDirectory()).dialed().keySet(),
                "the inviter is a computer it dialed, which it trusts until the owner's entries arrive");
        assertEquals(REMOTE_URL, GitProcessRunner.run(friendProject.root(), "remote", "get-url", "synesis").strip());
        assertEquals("CODE_REMOTE_UNAVAILABLE", opened.body().get("codeRemoteCheck"), "the join goes on and says this computer cannot fetch without a prompt");
        assertTrue(String.valueOf(opened.body().get("codeRemoteDetail")).contains("git fetch synesis"), opened.toString());

        // Authenticated and identity-checked, held, and on the owner's log as a request; not served until the owner accepts.
        await(() -> {
            try {
                return owner.joinRequests().size() == 1;
            } catch (Exception failure) {
                return false;
            }
        });
        assertEquals(friend.node.nodeId(), owner.joinRequests().getFirst().get("nodeId"));
        assertEquals(List.of(PredictionEventType.PROJECT_SHARED, PredictionEventType.JOIN_REQUESTED), owner.membershipEntries(), "holding the joiner shared the project and asked");
        assertEquals(owner.node.nodeId(), owner.membership().owner().orElseThrow());
        assertEquals(Set.of(owner.node.nodeId()), owner.membership().members());
        assertEquals(friend.node.nodeId(), owner.membership().openRequests().getFirst().joinerNodeId());
        assertTrue(announces(friend, lane("agt-f", "src/before.py")), "a joiner appends to its own log whether or not the owner accepted it");
        Thread.sleep(2_000);
        assertTrue(owner.store().events().stream().allMatch(event -> event.actorNodeId().equals(owner.node.nodeId())),
                "nothing of the friend's reached the owner, which does not serve or copy a node it has not accepted");
        assertEquals(Set.of(owner.node.nodeId()), owner.membership().members());

        Response accepted = owner.post("accept-join", Map.of("nodeId", friend.node.nodeId()));
        assertEquals(200, accepted.status(), accepted.toString());
        assertEquals("ACCEPTED", accepted.body().get("state"));
        assertEquals(ReplicationSettings.sharedProject("origin"), owner.role(), "accepting wrote role=shared and the remote the link carried");
        assertEquals(List.of(PredictionEventType.PROJECT_SHARED, PredictionEventType.JOIN_REQUESTED, PredictionEventType.JOIN_ANSWERED), owner.membershipEntries());
        assertEquals(Set.of(owner.node.nodeId(), friend.node.nodeId()), owner.membership().members());
        assertEquals(List.of(), owner.joinRequests());

        // After acceptance both pull each other: the owner's entries reach the friend, which then sees itself a member.
        await(() -> friend.namesMember(friend.node.nodeId()));
        assertEquals(owner.node.nodeId(), friend.membership().owner().orElseThrow(), "the friend learned who owns the project from the owner's log");
        assertEquals(List.of(PredictionEventType.PROJECT_SHARED, PredictionEventType.JOIN_REQUESTED, PredictionEventType.JOIN_ANSWERED), friend.membershipEntries());

        assertTrue(announces(friend, lane("agt-f", "src/after.py")));
        await(() -> entriesOf(owner, friend) > 0);
        await(() -> entriesOf(owner, friend) == entriesOf(friend, friend));
        assertTrue(announces(owner, lane("agt-o", "src/owner.py")));
        await(() -> entriesOf(friend, owner) == entriesOf(owner, owner));
        assertEquals(owner.store().events().size(), friend.store().events().size(), "each holds the same entries, its own and the other's");
        assertTrue(Files.readString(ownerProject.coordinationDirectory().resolve("replication.peers")).contains(friend.node.nodeId()), "recorded for a restart");
    }

    @Test
    void decliningClosesTheFriendsSessionAndLeavesItNoMember() throws Exception {
        Computer owner = computers.start(ownerProject, null);
        ProjectLocation friendProject = friendClone("friend");
        Computer friend = computers.start(friendProject, friendRegistry);
        friendRegistry.register(friendProject.root());

        Response opened = friend.post("open-project-link", Map.of("link", projectLinkOf(owner)));
        assertEquals(200, opened.status(), opened.toString());
        await(() -> {
            try {
                return owner.joinRequests().size() == 1;
            } catch (Exception failure) {
                return false;
            }
        });

        Response declined = owner.post("decline-join", Map.of("nodeId", friend.node.nodeId()));

        assertEquals(200, declined.status(), declined.toString());
        assertEquals("DECLINED", declined.body().get("state"));
        assertEquals(List.of(), owner.joinRequests());
        assertTrue(ReplicationSettings.read(ownerProject.coordinationDirectory()).isEmpty(), "declining writes no role either");
        await(() -> owner.owner.get().sessions().isEmpty());
        Thread.sleep(3_500);
        assertTrue(owner.owner.get().sessions().isEmpty(), "the friend's redial is not admitted: its invitation is used up and it is no member");
        assertTrue(announces(friend, lane("agt-f", "src/declined.py")), "the friend still writes its own log");
        assertTrue(owner.store().events().stream().allMatch(event -> event.actorNodeId().equals(owner.node.nodeId())), "none of it reached the owner");
        assertEquals(List.of(PredictionEventType.PROJECT_SHARED, PredictionEventType.JOIN_REQUESTED, PredictionEventType.JOIN_ANSWERED), owner.membershipEntries());
        assertEquals(Optional.of(false), owner.membership().requests().getFirst().accepted(), "the decline is an entry of the owner's log: JOIN_ANSWERED(false)");
        assertEquals(friend.node.nodeId(), owner.membership().requests().getFirst().joinerNodeId());
        assertEquals(Set.of(owner.node.nodeId()), owner.membership().members());
        assertEquals(404, owner.post("accept-join", Map.of("nodeId", friend.node.nodeId())).status());
    }

    @Test
    void aProjectNotRegisteredHereIsNotClonedAndTheFolderIsAskedForThenCheckedThenUsed() throws Exception {
        Computer owner = computers.start(ownerProject, null);
        Computer friendHome = computers.start(service.require(friendHomeRoot), friendRegistry);
        String link = projectLinkOf(owner);
        long before = folderCount();

        Response missing = friendHome.post("open-project-link", Map.of("link", link));

        assertEquals(200, missing.status(), missing.toString());
        assertEquals("PROJECT_NOT_FOUND", missing.body().get("state"));
        assertEquals(REMOTE_URL, missing.body().get("codeRemoteUrl"), "the person is told where to clone from");
        assertEquals(owner.node.nodeId(), missing.body().get("hostNodeId"));
        assertEquals(before, folderCount(), "nothing was cloned");

        Response wrongProject = friendHome.post("open-project-link", Map.of("link", link, "folder", friendHomeRoot.toString()));
        assertEquals(409, wrongProject.status(), wrongProject.toString());
        assertEquals("PROJECT_IDENTITY_MISMATCH", wrongProject.code());
        assertTrue(friendRegistry.projects().stream().noneMatch(view -> view.projectId().equals(ownerProject.projectId())));
        assertEquals(400, friendHome.post("open-project-link", Map.of("link", link, "folder", temp.resolve("nowhere/deeper").toString())).status());

        // The friend cloned the project themselves; it holds only the tracked project file, so it becomes a replica.
        Path clone = temp.resolve("friend-clone");
        GitProcessRunner.run(temp, "clone", ownerProject.root().toString(), clone.toString());
        assertFalse(Files.exists(clone.resolve(".synesis/local")));
        Response chosen = friendHome.post("open-project-link", Map.of("link", link, "folder", clone.toString()));

        assertEquals(200, chosen.status(), chosen.toString());
        assertEquals("CONNECTING", chosen.body().get("state"));
        assertEquals(clone.toString(), chosen.body().get("folder"));
        assertTrue(Files.isDirectory(clone.resolve(".synesis/local")), "registered as a copy of the project (initReplica)");
        assertTrue(friendRegistry.projects().stream().anyMatch(view -> view.projectId().equals(ownerProject.projectId()) && view.path().equals(clone)));
        assertEquals(ReplicationSettings.sharedProject("synesis"), ReplicationSettings.read(clone.resolve(".synesis/coordination")).orElseThrow());
    }

    @Test
    void aLinkThatWasChangedOrIsNotOursIsRefusedAndNothingIsWritten() throws Exception {
        Computer owner = computers.start(ownerProject, null);
        ProjectLocation friendProject = friendClone("friend");
        friendRegistry.register(friendProject.root());
        Computer friend = computers.start(friendProject, friendRegistry);
        byte[] encoded = ProjectInvitation.fromShareLink(projectLinkOf(owner)).encoded();
        encoded[2 + 15] ^= 1;
        String tampered = "synesis://project/SLP1-" + Base64.getUrlEncoder().withoutPadding().encodeToString(encoded);

        Response changed = friend.post("open-project-link", Map.of("link", tampered));
        Response garbage = friend.post("open-project-link", Map.of("link", "synesis://project/SLP1-AAAA"));

        assertEquals(400, changed.status(), changed.toString());
        assertEquals("INVITE_INVALID", changed.code());
        assertEquals(400, garbage.status(), garbage.toString());
        assertTrue(ReplicationSettings.read(friendProject.coordinationDirectory()).isEmpty());
    }

    @Test
    void aFriendWithItsOwnLogCannotFollowAndOneThatFollowsInvitesWithAProjectLinkToo() throws Exception {
        Computer owner = computers.start(ownerProject, null);
        ProjectLocation friendProject = friendClone("friend");
        friendRegistry.register(friendProject.root());
        Computer friend = computers.start(friendProject, friendRegistry);
        assertTrue(announces(friend, lane("agt-f", "src/own.py")));
        String link = projectLinkOf(owner);

        Response refused = friend.post("open-project-link", Map.of("link", link));

        assertEquals(409, refused.status(), refused.toString());
        assertEquals("LOG_NOT_EMPTY", refused.code());
        assertTrue(ReplicationSettings.read(friendProject.coordinationDirectory()).isEmpty(), "nothing was written");

        ProjectLocation second = friendClone("second");
        friendRegistry.register(second.root());
        Computer follower = computers.start(second, friendRegistry);
        Response joined = follower.post("open-project-link", Map.of("link", link));
        assertEquals(200, joined.status(), joined.toString());
        Response invite = follower.post("invite", Map.of("project", true));
        assertEquals(201, invite.status(), invite.toString());
        assertEquals("PROJECT", invite.body().get("kind"), "any member invites to the project: it holds the joiner's session and records the request (ADR-0098 step 6)");
        assertTrue(((String) invite.body().get("inviteUri")).startsWith("synesis://project/SLP1-"), invite.toString());
        assertEquals(ReplicationSettings.sharedProject("synesis"), follower.role(), "and it changed nothing");
    }

    @Test
    void aFriendWithNoCopyJoinsIntoAnEmptyFolderAndGetsTheOwnersBranchOverLink() throws Exception {
        Path ownerRoot = ownerProject.root();
        Files.writeString(ownerRoot.resolve("board.txt"), "the owner's work\n");
        GitProcessRunner.run(ownerRoot, "add", "board.txt");
        GitProcessRunner.run(ownerRoot, "commit", "-m", "owner's work");
        String ownerHead = GitProcessRunner.run(ownerRoot, "rev-parse", "HEAD").strip();
        String ownerBranch = GitProcessRunner.run(ownerRoot, "symbolic-ref", "--short", "HEAD").strip();
        Computer owner = computers.start(ownerProject, null);
        Computer friendHome = computers.start(service.require(friendHomeRoot), friendRegistry);
        Path joined = temp.resolve("joined");

        Response opened = friendHome.post("open-project-link", Map.of("link", projectLinkOf(owner), "folder", joined.toString()));

        assertEquals(200, opened.status(), opened.toString());
        assertEquals("CONNECTING", opened.body().get("state"));
        assertEquals(joined.toString(), opened.body().get("folder"));
        assertFalse(Files.exists(joined.resolve(".git")), "no code before the owner accepts");
        assertEquals(ReplicationSettings.sharedProject(null), ReplicationSettings.read(joined.resolve(".synesis/coordination")).orElseThrow(),
                "a folder with no repository yet names no mirror");
        await(() -> {
            try {
                return owner.joinRequests().size() == 1;
            } catch (Exception failure) {
                return false;
            }
        });

        assertEquals(200, owner.post("accept-join", Map.of("nodeId", owner.joinRequests().getFirst().get("nodeId"))).status());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!Files.isRegularFile(joined.resolve("board.txt")) && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(ownerHead, GitProcessRunner.run(joined, "rev-parse", "HEAD").strip(), "the friend's copy holds the owner's head");
        assertEquals(ownerBranch, GitProcessRunner.run(joined, "symbolic-ref", "--short", "HEAD").strip(), "under the owner's branch name");
        assertEquals("the owner's work\n", Files.readString(joined.resolve("board.txt")).replace("\r\n", "\n"));
        assertTrue(Files.isRegularFile(joined.resolve(".synesis/coordination").resolve(LoopbackExchange.ENDPOINT_FILE)),
                "the friend's agents can still find their computer's server after it became a member");
    }

    @Test
    void anInvitationNeverStopsTheOwnersOwnWorkAndAnAcceptWithoutACodeRemoteMakesALeaderWithNoMirror() throws Exception {
        GitProcessRunner.run(ownerProject.root(), "remote", "remove", "origin");
        Computer owner = computers.start(ownerProject, null);
        ProjectLocation friendProject = friendClone("friend");
        friendRegistry.register(friendProject.root());
        Computer friend = computers.start(friendProject, friendRegistry);

        Response invited = owner.post("invite", Map.of("project", true));

        assertEquals(201, invited.status(), invited.toString());
        assertNull(invited.body().get("codeRemoteUrl"));
        assertEquals("NO_GIT_REMOTE", invited.body().get("codeRemoteReason"));
        assertTrue(ReplicationSettings.read(ownerProject.coordinationDirectory()).isEmpty(), "no role file without a code remote: completion and publication on this computer keep working");
        assertEquals(200, friend.post("open-project-link", Map.of("link", invited.body().get("inviteUri"))).status());
        await(() -> {
            try {
                return owner.joinRequests().size() == 1;
            } catch (Exception failure) {
                return false;
            }
        });

        Response accepted = owner.post("accept-join", Map.of("nodeId", friend.node.nodeId()));

        assertEquals(200, accepted.status(), accepted.toString());
        assertEquals(ReplicationSettings.sharedProject(null), owner.role(), "code moves over Link, so no remote is needed");
        assertEquals(List.of(), owner.joinRequests());
    }

    @Test
    void openingALinkRefusedForCapacityWritesNoRoleAndAddsNoRemoteAndTheSameLinkWorksLater() throws Exception {
        Computer owner = computers.start(ownerProject, null);
        ProjectLocation friendProject = friendClone("friend");
        friendRegistry.register(friendProject.root());
        Computer friend = computers.start(friendProject, friendRegistry);
        String link = projectLinkOf(owner);
        List<String> plain = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            Response filler = friend.post("invite", Map.of());
            assertEquals(201, filler.status(), filler.toString());
            plain.add((String) filler.body().get("operationId"));
        }

        Response refused = friend.post("open-project-link", Map.of("link", link));

        assertEquals(429, refused.status(), refused.toString());
        assertTrue(ReplicationSettings.read(friendProject.coordinationDirectory()).isEmpty(), "no member role was written for a connection that was never prepared");
        assertFalse(GitProcessRunner.run(friendProject.root(), "remote").contains("synesis"), "and no Git remote was added");

        assertEquals(200, friend.post("cancel", Map.of("operationId", plain.getFirst())).status());
        Response again = friend.post("open-project-link", Map.of("link", link));
        assertEquals(200, again.status(), again.toString());
        assertEquals(ReplicationSettings.sharedProject("synesis"), friend.role());
    }

    @Test
    void anOwnerWhoseRemoteIsAFilePathInvitesWithNoUrlAndSaysWhy() throws Exception {
        Computer owner = computers.start(ownerProject, null);
        GitProcessRunner.run(ownerProject.root(), "remote", "set-url", "origin", "file:///srv/project.git");

        Response invited = owner.post("invite", Map.of("project", true));

        assertEquals(201, invited.status(), invited.toString());
        assertNull(invited.body().get("codeRemoteUrl"));
        assertEquals("URL_NOT_SHAREABLE", invited.body().get("codeRemoteReason"));
        assertNull(ProjectInvitation.fromShareLink((String) invited.body().get("inviteUri")).codeRemoteUrl().orElse(null));
    }

    @Test
    void aProjectLinkIsDescribedBeforeAnythingIsDoneWithIt() throws Exception {
        Computer owner = computers.start(ownerProject, null);
        ProjectLocation friendProject = friendClone("friend");
        friendRegistry.register(friendProject.root());
        Computer friend = computers.start(friendProject, friendRegistry);
        String link = projectLinkOf(owner);

        Response described = friend.post("describe-project-link", Map.of("link", link));

        assertEquals(200, described.status(), described.toString());
        assertEquals(owner.node.nodeId(), described.body().get("hostNodeId"));
        assertEquals(ownerProject.projectId().toString(), described.body().get("projectId"));
        assertEquals("friend", described.body().get("projectName"));
        assertEquals(REMOTE_URL, described.body().get("codeRemoteUrl"));
        assertNotNull(described.body().get("expiresAt"));
        assertTrue(ReplicationSettings.read(friendProject.coordinationDirectory()).isEmpty(), "describing writes nothing");
        assertEquals(400, friend.post("describe-project-link", Map.of("link", "synesis://project/SLP1-AAAA")).status());
    }

    @Test
    void pendingRequestsAreDroppedWhenTheOwnersRuntimeRestarts() throws Exception {
        Computer owner = computers.start(ownerProject, null);
        ProjectLocation friendProject = friendClone("friend");
        friendRegistry.register(friendProject.root());
        Computer friend = computers.start(friendProject, friendRegistry);
        assertEquals(200, friend.post("open-project-link", Map.of("link", projectLinkOf(owner))).status());
        await(() -> {
            try {
                return owner.joinRequests().size() == 1;
            } catch (Exception failure) {
                return false;
            }
        });

        owner.stop();
        Computer restarted = computers.start(ownerProject, null);

        assertEquals(List.of(), restarted.joinRequests(), "the invitation lived in the runtime that is gone; the friend asks again with a new one");
        assertTrue(ReplicationSettings.read(ownerProject.coordinationDirectory()).isEmpty());
    }
}
