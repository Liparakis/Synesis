package org.synesis.workspace.transport.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.onboarding.OnboardingFailure;
import org.synesis.link.session.LivenessConfiguration;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.SessionCloseReason;

import org.synesis.workspace.application.project.ProjectIdentity;

/**
 * A session the owner holds is dialed again by the computer that dialed the invitation and admitted again by the
 * computer that hosted it, without a new invitation, for the nodes the owner's rule names and for no others
 * (ADR-0098), and every start and end of a session is written down with the reason (2026-10-16: a link dropped
 * about fifteen minutes into a real run and nothing recorded why).
 */
@Timeout(120)
class LinkRuntimeOwnerRedialTest {

    @TempDir
    Path temp;

    private final ProjectIdentity project = new ProjectIdentity(UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MILLIS));
    private final List<String> hostJournal = new CopyOnWriteArrayList<>();
    private final List<String> dialerJournal = new CopyOnWriteArrayList<>();
    private final AtomicBoolean dialerMayReturn = new AtomicBoolean(true);

    private static NodeIdentity identity(Path profile) throws Exception {
        return new IdentityBootstrap(profile.resolve("link")).loadOrCreate().identity();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "condition not reached");
    }

    private LinkRuntimeOwner owner(String name, NodeIdentity node, java.util.function.Predicate<String> returning, List<String> journal) {
        return new LinkRuntimeOwner(new Onboarding(temp.resolve(name + "/link"), ignored -> {
        }), node, ignored -> {
        }, project, (remote, payload) -> CompletableFuture.completedFuture("pong".getBytes(StandardCharsets.UTF_8)), session -> {
        }, returning, journal::add);
    }

    private static PeerSession sessionWith(LinkRuntimeOwner owner, NodeIdentity peer) {
        return owner.get().sessions().stream().filter(session -> session.remoteNodeId().equals(peer.nodeId())).findFirst().orElse(null);
    }

    @Test
    void aDroppedSessionComesBackWithoutANewInvitationAndTheReasonIsRecorded() throws Exception {
        NodeIdentity hostNode = identity(temp.resolve("host"));
        NodeIdentity dialerNode = identity(temp.resolve("dialer"));
        try (LinkRuntimeOwner host = owner("host", hostNode, node -> node.equals(dialerNode.nodeId()), hostJournal);
                LinkRuntimeOwner dialer = owner("dialer", dialerNode, node -> dialerMayReturn.get() && node.equals(hostNode.nodeId()), dialerJournal)) {
            ControlPlaneLinkOperations.PendingHost invitation = host.createInvitation(null, false);
            dialer.importInvitation(invitation.invitationLink()).connect();
            await(() -> sessionWith(host, dialerNode) != null && sessionWith(dialer, hostNode) != null);
            PeerSession firstOnDialer = sessionWith(dialer, hostNode);
            PeerSession firstOnHost = sessionWith(host, dialerNode);
            assertEquals(LivenessConfiguration.RETAINED, firstOnDialer.livenessConfiguration());

            firstOnDialer.closeGracefully(SessionCloseReason.LOCAL_REQUEST);

            await(() -> {
                PeerSession now = sessionWith(dialer, hostNode);
                PeerSession there = sessionWith(host, dialerNode);
                return now != null && now != firstOnDialer && there != null && there != firstOnHost && now.isUsable();
            });
            PeerSession again = sessionWith(dialer, hostNode);
            assertNotEquals(firstOnDialer.sessionId(), again.sessionId());
            assertArrayEquals("pong".getBytes(StandardCharsets.UTF_8), again.requestApplication("ping".getBytes(StandardCharsets.UTF_8)).toCompletableFuture().get(10, TimeUnit.SECONDS));
            await(() -> hostJournal.stream().filter(line -> line.startsWith("CONNECTED")).count() == 2);
            assertTrue(dialerJournal.stream().anyMatch(line -> line.startsWith("DISCONNECT peer=" + hostNode.nodeId() + " reason=LOCAL_REQUEST")), dialerJournal.toString());
            assertTrue(hostJournal.stream().anyMatch(line -> line.startsWith("DISCONNECT peer=" + dialerNode.nodeId() + " reason=REMOTE_REQUEST")), hostJournal.toString());
            assertTrue(dialerJournal.stream().anyMatch(line -> line.startsWith("REDIAL_OK")), dialerJournal.toString());
        }
    }

    @Test
    void closingTheOwnerRecordsItsSessionsAsEndedNotReplaced() throws Exception {
        NodeIdentity hostNode = identity(temp.resolve("host"));
        NodeIdentity dialerNode = identity(temp.resolve("dialer"));
        try (LinkRuntimeOwner dialer = owner("dialer", dialerNode, node -> false, dialerJournal)) {
            LinkRuntimeOwner host = owner("host", hostNode, node -> node.equals(dialerNode.nodeId()), hostJournal);
            ControlPlaneLinkOperations.PendingHost invitation = host.createInvitation(null, false);
            dialer.importInvitation(invitation.invitationLink()).connect();
            await(() -> sessionWith(host, dialerNode) != null && sessionWith(dialer, hostNode) != null);

            host.close();

            // A reader of the journal skips replaced lines, so a clean stop written as one left the peer connected.
            await(() -> hostJournal.stream().anyMatch(line -> line.startsWith("DISCONNECT peer=" + dialerNode.nodeId())));
            assertTrue(hostJournal.stream().filter(line -> line.startsWith("DISCONNECT")).noneMatch(line -> line.endsWith("replaced=true")), hostJournal.toString());
        }
    }

    @Test
    void aDialerTheRuleNoLongerNamesIsNotDialedAndTheHostDoesNotAdmitAnUnnamedNode() throws Exception {
        NodeIdentity hostNode = identity(temp.resolve("host"));
        NodeIdentity dialerNode = identity(temp.resolve("dialer"));
        NodeIdentity strangerNode = identity(temp.resolve("stranger"));
        try (LinkRuntimeOwner host = owner("host", hostNode, node -> node.equals(dialerNode.nodeId()), hostJournal);
                LinkRuntimeOwner dialer = owner("dialer", dialerNode, node -> dialerMayReturn.get() && node.equals(hostNode.nodeId()), dialerJournal);
                LinkRuntimeOwner stranger = owner("stranger", strangerNode, node -> true, new CopyOnWriteArrayList<>())) {
            ControlPlaneLinkOperations.PendingHost invitation = host.createInvitation(null, false);
            dialer.importInvitation(invitation.invitationLink()).connect();
            await(() -> sessionWith(host, dialerNode) != null && sessionWith(dialer, hostNode) != null);
            PeerSession first = sessionWith(dialer, hostNode);

            // The role changed: the rule stops naming the host before the session ends.
            dialerMayReturn.set(false);
            first.closeGracefully(SessionCloseReason.LOCAL_REQUEST);
            await(() -> sessionWith(dialer, hostNode) == null);
            Thread.sleep(LinkRuntimeOwner.REDIAL_FIRST.toMillis() * 4);

            assertEquals(null, sessionWith(dialer, hostNode), "no redial once the rule says no");
            assertEquals(null, sessionWith(host, dialerNode));
            assertTrue(dialerJournal.stream().anyMatch(line -> line.startsWith("REDIAL_STOPPED")), dialerJournal.toString());

            // The used invitation lets nobody else in, whoever knows its link.
            ControlPlaneLinkOperations.PendingJoin attempt = stranger.importInvitation(invitation.invitationLink());
            CompletableFuture<Void> dialing = CompletableFuture.runAsync(() -> {
                try {
                    attempt.connect();
                } catch (OnboardingFailure refused) {
                    throw new IllegalStateException(refused);
                }
            });
            Thread.sleep(8_000);
            assertFalse(dialing.isDone() && !dialing.isCompletedExceptionally(), "the used invitation admitted a stranger");
            assertFalse(stranger.get().sessions().stream().anyMatch(session -> session.remoteNodeId().equals(hostNode.nodeId())));
            assertEquals(null, sessionWith(host, strangerNode));
            attempt.close();
        }
    }
}
