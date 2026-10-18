package org.synesis.workspace.transport.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.session.PeerSession;

import org.synesis.workspace.application.project.ProjectIdentity;

/**
 * A node that authenticates through the invitation a computer hosts, and is not a member, is held until that
 * computer's person decides (ADR-0098, "Joining, revised"): it has a session and is served nothing.
 */
@Timeout(120)
class LinkRuntimeOwnerJoinRequestsTest {

    @TempDir
    Path temp;

    private final ProjectIdentity project = new ProjectIdentity(UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MILLIS));
    private final Set<String> members = ConcurrentHashMap.newKeySet();
    private final List<String> served = new CopyOnWriteArrayList<>();
    private final List<String> remembered = new CopyOnWriteArrayList<>();

    private static NodeIdentity identity(Path profile) throws Exception {
        return new IdentityBootstrap(profile.resolve("link")).loadOrCreate().identity();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "condition not reached");
    }

    private LinkRuntimeOwner host(NodeIdentity node, LinkRuntimeOwner.MemberAdmission admission) {
        return new LinkRuntimeOwner(new Onboarding(temp.resolve("host/link"), ignored -> {
        }), node, ignored -> {
        }, project, (remote, payload) -> CompletableFuture.completedFuture(new byte[0]), session -> {
            if (members.contains(session.remoteNodeId())) {
                served.add(session.remoteNodeId());
            }
        }, members::contains, line -> {
        }, new LinkRuntimeOwner.PeerMemory() {
            @Override
            public void dialed(String hostNodeId, String invitationLink) {
            }

            @Override
            public void hosted(int port, String nodeId) {
                remembered.add(port + ":" + nodeId);
            }
        }, admission);
    }

    private LinkRuntimeOwner joiner(NodeIdentity node) {
        return new LinkRuntimeOwner(new Onboarding(temp.resolve("joiner/link"), ignored -> {
        }), node, null, ignored -> {
        }, project);
    }

    private static void connect(LinkRuntimeOwner host, LinkRuntimeOwner joiner, NodeIdentity hostNode) throws Exception {
        joiner.importInvitation(host.createInvitation(null, false).invitationLink()).connect();
        await(() -> joiner.get().sessions().stream().anyMatch(session -> session.remoteNodeId().equals(hostNode.nodeId())));
    }

    @Test
    void aJoinerThatNoMemberListNamesIsHeldAndServedNothingUntilAccepted() throws Exception {
        NodeIdentity hostNode = identity(temp.resolve("host"));
        NodeIdentity joinerNode = identity(temp.resolve("joiner"));
        try (LinkRuntimeOwner host = host(hostNode, (node, url) -> members.add(node));
                LinkRuntimeOwner joiner = joiner(joinerNode)) {
            connect(host, joiner, hostNode);

            await(() -> host.joinRequests().size() == 1);
            ControlPlaneLinkOperations.JoinRequest request = host.joinRequests().getFirst();
            assertEquals(joinerNode.nodeId(), request.nodeId());
            assertTrue(request.expiresAt().isAfter(request.requestedAt()));
            assertTrue(host.get().sessions().stream().anyMatch(session -> session.remoteNodeId().equals(joinerNode.nodeId())), "authenticated and identity-checked, so it has a session");
            assertEquals(List.of(), served, "but nothing was served");
            assertEquals(List.of(), remembered, "and nothing is recorded for a restart");

            host.acceptJoin(joinerNode.nodeId());

            assertEquals(List.of(), host.joinRequests());
            assertTrue(members.contains(joinerNode.nodeId()));
            assertEquals(List.of(joinerNode.nodeId()), served, "the live session is served from now on");
            assertEquals(1, remembered.size());
            assertTrue(remembered.getFirst().endsWith(":" + joinerNode.nodeId()));
        }
    }

    @Test
    void decliningClosesTheSessionAndTheJoinerIsNeverAMember() throws Exception {
        NodeIdentity hostNode = identity(temp.resolve("host"));
        NodeIdentity joinerNode = identity(temp.resolve("joiner"));
        try (LinkRuntimeOwner host = host(hostNode, (node, url) -> members.add(node));
                LinkRuntimeOwner joiner = joiner(joinerNode)) {
            connect(host, joiner, hostNode);
            await(() -> host.joinRequests().size() == 1);

            host.declineJoin(joinerNode.nodeId());

            assertEquals(List.of(), host.joinRequests());
            assertFalse(members.contains(joinerNode.nodeId()));
            await(() -> host.get().sessions().stream().noneMatch(session -> session.remoteNodeId().equals(joinerNode.nodeId())));
            assertEquals(List.of(), served);
            assertEquals(List.of(), remembered);
            assertEquals("JOIN_REQUEST_UNKNOWN", assertThrows(ControlPlaneLinkOperations.JoinRequestException.class, () -> host.acceptJoin(joinerNode.nodeId())).code());
        }
    }

    @Test
    void aMemberListThatCannotBeSavedLeavesTheRequestPending() throws Exception {
        NodeIdentity hostNode = identity(temp.resolve("host"));
        NodeIdentity joinerNode = identity(temp.resolve("joiner"));
        try (LinkRuntimeOwner host = host(hostNode, (node, url) -> {
            throw new IOException("disk full");
        });
                LinkRuntimeOwner joiner = joiner(joinerNode)) {
            connect(host, joiner, hostNode);
            await(() -> host.joinRequests().size() == 1);

            assertEquals("MEMBER_NOT_SAVED", assertThrows(ControlPlaneLinkOperations.JoinRequestException.class, () -> host.acceptJoin(joinerNode.nodeId())).code());

            assertEquals(1, host.joinRequests().size());
            assertEquals(List.of(), served);
            assertFalse(members.contains(joinerNode.nodeId()));
        }
    }

    @Test
    void aSessionThatEndsBeforeTheDecisionLeavesTheRequestAndAcceptingStillMakesAMember() throws Exception {
        NodeIdentity hostNode = identity(temp.resolve("host"));
        NodeIdentity joinerNode = identity(temp.resolve("joiner"));
        try (LinkRuntimeOwner host = host(hostNode, (node, url) -> members.add(node));
                LinkRuntimeOwner joiner = joiner(joinerNode)) {
            connect(host, joiner, hostNode);
            await(() -> host.joinRequests().size() == 1);
            joiner.get().sessions().forEach(session -> session.closeGracefully(org.synesis.link.session.SessionCloseReason.LOCAL_REQUEST));
            await(() -> host.get().sessions().isEmpty());

            assertEquals(1, host.joinRequests().size());
            host.acceptJoin(joinerNode.nodeId());

            assertTrue(members.contains(joinerNode.nodeId()), "it is served when it dials again");
            assertEquals(List.of(), served, "there was no live session to serve now");
            assertEquals(List.of(), host.joinRequests());
        }
    }

    @Test
    void anOwnerThatHoldsNoRequestsAdmitsAsBeforeAndDecidesNothing() throws Exception {
        NodeIdentity hostNode = identity(temp.resolve("host"));
        NodeIdentity joinerNode = identity(temp.resolve("joiner"));
        try (LinkRuntimeOwner host = host(hostNode, null);
                LinkRuntimeOwner joiner = joiner(joinerNode)) {
            connect(host, joiner, hostNode);

            assertEquals(List.of(), host.joinRequests());
            assertEquals("JOIN_REQUEST_UNKNOWN", assertThrows(ControlPlaneLinkOperations.JoinRequestException.class, () -> host.acceptJoin(joinerNode.nodeId())).code());
        }
    }

    @Test
    void acceptingHandsTheAdmissionTheUrlTheProjectLinkCarriedAndNoneForAPlainInvitation() throws Exception {
        NodeIdentity hostNode = identity(temp.resolve("host"));
        NodeIdentity joinerNode = identity(temp.resolve("joiner"));
        List<String> urls = new CopyOnWriteArrayList<>();
        try (LinkRuntimeOwner host = host(hostNode, (node, url) -> urls.add(String.valueOf(url)));
                LinkRuntimeOwner joiner = joiner(joinerNode)) {
            String connection = host.createInvitation(null, false).invitationLink();
            String projectLink = host.projectInvitationLink(connection, project.projectId(), project.createdAt(), "https://example.test/org/project.git");
            joiner.importInvitation(org.synesis.link.protocol.ProjectInvitation.fromShareLink(projectLink).invitation().shareLink()).connect();
            await(() -> host.joinRequests().size() == 1);

            host.acceptJoin(joinerNode.nodeId());

            assertEquals(List.of("https://example.test/org/project.git"), urls, "the remote of the link that brought the joiner, not whatever is configured later");
        }
        urls.clear();
        try (LinkRuntimeOwner host = host(hostNode, (node, url) -> urls.add(String.valueOf(url)));
                LinkRuntimeOwner joiner = joiner(joinerNode)) {
            connect(host, joiner, hostNode);
            await(() -> host.joinRequests().size() == 1);

            host.acceptJoin(joinerNode.nodeId());

            assertEquals(List.of("null"), urls);
        }
    }
}
