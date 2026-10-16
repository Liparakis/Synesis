package org.synesis.workspace.transport.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
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
 * The project application traffic of a session the control plane established is answered by the handler the owner
 * was given (ADR-0098: replication frames), and an owner without one refuses it as before.
 */
@Timeout(120)
class LinkRuntimeOwnerProjectHandlerTest {

    @TempDir
    Path temp;

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

    /** Connects {@code sender} to {@code receiver} through invite and join and returns the sender's session to it. */
    private static PeerSession connect(LinkRuntimeOwner receiver, LinkRuntimeOwner sender, NodeIdentity receiverIdentity) throws Exception {
        ControlPlaneLinkOperations.PendingHost invitation = receiver.createInvitation(null, false);
        ControlPlaneLinkOperations.PendingJoin join = sender.importInvitation(invitation.invitationLink());
        join.connect();
        await(() -> sender.get().sessions().stream().anyMatch(session -> session.remoteNodeId().equals(receiverIdentity.nodeId())));
        return sender.get().sessions().stream().filter(session -> session.remoteNodeId().equals(receiverIdentity.nodeId())).findFirst().orElseThrow();
    }

    @Test
    void theGivenHandlerAnswersProjectFramesAndTheListenerSeesTheAuthenticatedSession() throws Exception {
        ProjectIdentity project = new ProjectIdentity(UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MILLIS));
        NodeIdentity receiverNode = identity(temp.resolve("receiver"));
        NodeIdentity senderNode = identity(temp.resolve("sender"));
        List<String> handled = new CopyOnWriteArrayList<>();
        List<String> listened = new CopyOnWriteArrayList<>();
        try (LinkRuntimeOwner receiver = new LinkRuntimeOwner(new Onboarding(temp.resolve("receiver/link"), ignored -> {
        }), receiverNode, ignored -> {
        }, project, (remote, payload) -> {
            handled.add(remote + ":" + new String(payload, StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture("pong".getBytes(StandardCharsets.UTF_8));
        }, session -> listened.add(session.remoteNodeId()));
                LinkRuntimeOwner sender = new LinkRuntimeOwner(new Onboarding(temp.resolve("sender/link"), ignored -> {
                }), senderNode, null, ignored -> {
                }, project)) {
            PeerSession toReceiver = connect(receiver, sender, receiverNode);

            await(() -> listened.contains(senderNode.nodeId()));
            assertArrayEquals("pong".getBytes(StandardCharsets.UTF_8), toReceiver.requestApplication("ping".getBytes(StandardCharsets.UTF_8)).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(List.of(senderNode.nodeId() + ":ping"), handled);
            assertEquals(List.of(senderNode.nodeId()), listened);
        }
    }

    @Test
    void anOwnerWithoutAHandlerStillRefusesEveryProjectFrame() throws Exception {
        ProjectIdentity project = new ProjectIdentity(UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MILLIS));
        NodeIdentity receiverNode = identity(temp.resolve("receiver"));
        NodeIdentity senderNode = identity(temp.resolve("sender"));
        try (LinkRuntimeOwner receiver = new LinkRuntimeOwner(new Onboarding(temp.resolve("receiver/link"), ignored -> {
        }), receiverNode, null, ignored -> {
        }, project);
                LinkRuntimeOwner sender = new LinkRuntimeOwner(new Onboarding(temp.resolve("sender/link"), ignored -> {
                }), senderNode, null, ignored -> {
                }, project)) {
            PeerSession toReceiver = connect(receiver, sender, receiverNode);

            await(() -> receiver.get().sessions().size() == 1);
            assertThrows(ExecutionException.class, () -> toReceiver.requestApplication("ping".getBytes(StandardCharsets.UTF_8)).toCompletableFuture().get(10, TimeUnit.SECONDS));
        }
    }
}
