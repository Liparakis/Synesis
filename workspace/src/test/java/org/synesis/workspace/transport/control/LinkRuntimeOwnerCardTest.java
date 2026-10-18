package org.synesis.workspace.transport.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;

import org.synesis.workspace.application.project.ProjectIdentity;

/**
 * A member's card is a retained invitation of its own listener that the other members dial again and again (ADR-0098
 * step 6, designer item 13): hosted once, dialed once, and a node that no member list names is held as a join request.
 */
@Timeout(120)
class LinkRuntimeOwnerCardTest {

    @TempDir
    Path temp;

    private final ProjectIdentity project = new ProjectIdentity(UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MILLIS));
    private final List<String> cards = new CopyOnWriteArrayList<>();

    private static NodeIdentity identity(Path profile) throws Exception {
        return new IdentityBootstrap(profile.resolve("link")).loadOrCreate().identity();
    }

    private static void await(BooleanSupplier condition, Object... journals) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "condition not reached; journals " + java.util.Arrays.toString(journals));
    }

    private LinkRuntimeOwner owner(String name, NodeIdentity node, Predicate<String> returning, List<String> journal, LinkRuntimeOwner.MemberAdmission admission) {
        return new LinkRuntimeOwner(new Onboarding(temp.resolve(name + "/link"), ignored -> {
        }), node, ignored -> {
        }, project, (remote, payload) -> CompletableFuture.completedFuture(new byte[0]), session -> {
        }, returning, journal::add, new LinkRuntimeOwner.PeerMemory() {
            @Override
            public void dialed(String hostNodeId, String invitationLink) {
            }

            @Override
            public void hosted(int port, String nodeId) {
            }

            @Override
            public void card(int port, String link) {
                cards.add(port + " " + link);
            }
        }, admission);
    }

    private static long count(List<String> journal, String prefix) {
        return journal.stream().filter(line -> line.startsWith(prefix)).count();
    }

    @Test
    void theCardIsHostedOnceAndTheMemoryIsToldItsPortAndLinkOnce() throws Exception {
        NodeIdentity hostNode = identity(temp.resolve("host"));
        try (LinkRuntimeOwner host = owner("host", hostNode, node -> false, new CopyOnWriteArrayList<>(), null)) {
            String first = host.hostCard();
            String second = host.hostCard();

            assertEquals(first, second);
            assertEquals(1, cards.size(), cards.toString());
            String[] remembered = cards.getFirst().split(" ");
            assertEquals(first, remembered[1]);
            assertTrue(Integer.parseInt(remembered[0]) > 0);
        }
    }

    @Test
    void aCardIsDialedOnceAndNotAgainWhileASessionIsHeldOrAHandleDials() throws Exception {
        NodeIdentity hostNode = identity(temp.resolve("host"));
        NodeIdentity dialerNode = identity(temp.resolve("dialer"));
        List<String> dialerJournal = new CopyOnWriteArrayList<>();
        List<String> hostJournal = new CopyOnWriteArrayList<>();
        try (LinkRuntimeOwner host = owner("host", hostNode, node -> node.equals(dialerNode.nodeId()), hostJournal, null);
                LinkRuntimeOwner dialer = owner("dialer", dialerNode, node -> node.equals(hostNode.nodeId()), dialerJournal, null)) {
            String card = host.hostCard();
            assertFalse(dialer.connectedTo(hostNode.nodeId()));

            assertTrue(dialer.dialCard(hostNode.nodeId(), card));
            assertFalse(dialer.dialCard(hostNode.nodeId(), card), "a handle dials it already");
            assertEquals(1, count(dialerJournal, "DIAL_RESUMED"), dialerJournal.toString());
        }
    }

    /** A card just hosted admits the member that dials it with no invitation capability, the first time (designer item 19). */
    @Test
    void aFreshCardAdmitsAMemberThatDialsItAndAMemberWithASessionIsNotDialedAgain() throws Exception {
        NodeIdentity hostNode = identity(temp.resolve("host"));
        NodeIdentity dialerNode = identity(temp.resolve("dialer"));
        List<String> dialerJournal = new CopyOnWriteArrayList<>();
        List<String> hostJournal = new CopyOnWriteArrayList<>();
        try (LinkRuntimeOwner host = owner("host", hostNode, node -> node.equals(dialerNode.nodeId()), hostJournal, null);
                LinkRuntimeOwner dialer = owner("dialer", dialerNode, node -> node.equals(hostNode.nodeId()), dialerJournal, null)) {
            String card = host.hostCard();
            assertTrue(dialer.dialCard(hostNode.nodeId(), card));

            await(() -> dialer.connectedTo(hostNode.nodeId()) && host.connectedTo(dialerNode.nodeId()), dialerJournal, hostJournal);
            assertFalse(dialer.dialCard(hostNode.nodeId(), card), "a session is held");
            assertEquals(1, count(dialerJournal, "DIAL_RESUMED"), dialerJournal.toString());
        }
    }

    @Test
    void aCardNobodyNamesIsNotDialedAndLeavesNoHandle() throws Exception {
        NodeIdentity hostNode = identity(temp.resolve("host"));
        NodeIdentity dialerNode = identity(temp.resolve("dialer"));
        List<String> dialerJournal = new CopyOnWriteArrayList<>();
        try (LinkRuntimeOwner host = owner("host", hostNode, node -> true, new CopyOnWriteArrayList<>(), null);
                LinkRuntimeOwner dialer = owner("dialer", dialerNode, node -> false, dialerJournal, null)) {
            assertFalse(dialer.dialCard(hostNode.nodeId(), host.hostCard()));
            assertEquals(1, count(dialerJournal, "DIAL_RESUME_SKIPPED"), dialerJournal.toString());
            assertFalse(dialer.connectedTo(hostNode.nodeId()));
        }
    }

    @Test
    void aNodeNoMemberListNamesCannotUseACardsInvitation() throws Exception {
        NodeIdentity hostNode = identity(temp.resolve("host"));
        NodeIdentity strangerNode = identity(temp.resolve("stranger"));
        List<String> hostJournal = new CopyOnWriteArrayList<>();
        Set<String> requested = ConcurrentHashMap.newKeySet();
        LinkRuntimeOwner.MemberAdmission admission = new LinkRuntimeOwner.MemberAdmission() {
            @Override
            public void admit(String nodeId, String codeRemoteUrl) {
            }

            @Override
            public void requested(String nodeId) {
                requested.add(nodeId);
            }
        };
        try (LinkRuntimeOwner host = owner("host", hostNode, node -> false, hostJournal, admission);
                LinkRuntimeOwner stranger = owner("stranger", strangerNode, node -> node.equals(hostNode.nodeId()), new CopyOnWriteArrayList<>(), null)) {
            // A card's invitation counts as used from the start (ADR-0098 step 6, designer item 19): its capability
            // opens nothing, so a node that is no member is neither admitted nor held as a request.
            ControlPlaneLinkOperations.PendingJoin join = stranger.importInvitation(host.hostCard());
            CompletableFuture<Void> dialing = CompletableFuture.runAsync(() -> {
                try {
                    join.connect();
                } catch (Exception refused) {
                    // Refused, or never answered: either way nothing is admitted.
                }
            });
            Thread.sleep(6_000);
            dialing.cancel(true);
            assertEquals(List.of(), host.joinRequests());
            assertTrue(requested.isEmpty());
            assertFalse(host.connectedTo(strangerNode.nodeId()));
        }
    }

    @Test
    void aReplacedCardOfAHostThatTheOldCardNoLongerReachesIsDialedAgain() throws Exception {
        NodeIdentity hostNode = identity(temp.resolve("host"));
        NodeIdentity dialerNode = identity(temp.resolve("dialer"));
        List<String> dialerJournal = new CopyOnWriteArrayList<>();
        String oldCard;
        try (LinkRuntimeOwner firstRun = owner("host", hostNode, node -> node.equals(dialerNode.nodeId()), new CopyOnWriteArrayList<>(), null)) {
            oldCard = firstRun.hostCard();
        }
        try (LinkRuntimeOwner dialer = owner("dialer", dialerNode, node -> node.equals(hostNode.nodeId()), dialerJournal, null)) {
            assertTrue(dialer.dialCard(hostNode.nodeId(), oldCard));
            // The same computer started again: its owner loads the same identity from the same folder.
            try (LinkRuntimeOwner secondRun = owner("host", hostNode, node -> node.equals(dialerNode.nodeId()), new CopyOnWriteArrayList<>(), null)) {
                String newCard = secondRun.hostCard();
                assertFalse(newCard.equals(oldCard));

                assertTrue(dialer.dialCard(hostNode.nodeId(), newCard), "the new card is dialed: the old one reaches nobody. " + dialerJournal);
                await(() -> dialer.connectedTo(hostNode.nodeId()), dialerJournal);
            }
        }
    }

    @Test
    void aNodeNoMemberListNamesThatDialsACardWithoutTheInvitationIsNotHeldAndNotAdmitted() throws Exception {
        NodeIdentity hostNode = identity(temp.resolve("host"));
        NodeIdentity strangerNode = identity(temp.resolve("stranger"));
        List<String> hostJournal = new CopyOnWriteArrayList<>();
        try (LinkRuntimeOwner host = owner("host", hostNode, node -> false, hostJournal, (node, url) -> {
        });
                LinkRuntimeOwner stranger = owner("stranger", strangerNode, node -> node.equals(hostNode.nodeId()), new CopyOnWriteArrayList<>(), null)) {
            assertTrue(stranger.dialCard(hostNode.nodeId(), host.hostCard()));

            Thread.sleep(6_000);
            assertEquals(List.of(), host.joinRequests());
            assertFalse(host.connectedTo(strangerNode.nodeId()));
            assertFalse(stranger.connectedTo(hostNode.nodeId()));
        }
    }

    /** The restart path: the listener is opened again on the card's port as consumed, and a node the rule names is admitted with no capability. */
    @Test
    void aResumedCardAdmitsAMemberThatDialsItAndSaysSo() throws Exception {
        NodeIdentity hostNode = identity(temp.resolve("host"));
        NodeIdentity dialerNode = identity(temp.resolve("dialer"));
        List<String> restartedJournal = new CopyOnWriteArrayList<>();
        List<String> dialerJournal = new CopyOnWriteArrayList<>();
        String card;
        int port;
        try (LinkRuntimeOwner firstRun = owner("host", hostNode, node -> node.equals(dialerNode.nodeId()), new CopyOnWriteArrayList<>(), null)) {
            card = firstRun.hostCard();
            port = Integer.parseInt(cards.getFirst().split(" ")[0]);
        }
        try (LinkRuntimeOwner restarted = owner("host", hostNode, node -> node.equals(dialerNode.nodeId()), restartedJournal, null);
                LinkRuntimeOwner dialer = owner("dialer", dialerNode, node -> node.equals(hostNode.nodeId()), dialerJournal, null)) {
            restarted.resumeCard(port, card);
            assertTrue(restartedJournal.stream().anyMatch(line -> line.equals("CARD_RESUMED port=" + port)), restartedJournal.toString());
            assertEquals(card, restarted.hostCard(), "the resumed card is the card: no second one is hosted");

            assertTrue(dialer.dialCard(hostNode.nodeId(), card));

            await(() -> dialer.connectedTo(hostNode.nodeId()) && restarted.connectedTo(dialerNode.nodeId()), dialerJournal, restartedJournal);
            assertFalse(dialer.dialCard(hostNode.nodeId(), card), "a session is held");
        }
    }
}
