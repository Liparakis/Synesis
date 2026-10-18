package org.synesis.workspace.transport.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.OnboardingFailure;
import org.synesis.link.onboarding.OnboardingFailureCode;
import org.synesis.link.protocol.CompactBootstrapInvitation;
import org.synesis.link.protocol.ProtocolVersion;

/**
 * An onboarding operation that ends without a session says why, in the snapshot the UI and {@code synesis link} read,
 * so that neither has to report a bare failure.
 */
@Timeout(30)
final class ControlPlaneOnboardingEndpointsTest {

    private final CompletableFuture<Void> hostWait = new CompletableFuture<>();

    @Test
    void anInvitationThatExpiresWithNobodyConnectedSaysItExpired() throws Exception {
        ControlPlaneOnboardingEndpoints endpoints = new ControlPlaneOnboardingEndpoints(new Operations(invitationExpiringIn(2)));
        String operation = (String) endpoints.startInvitation(Map.of(), link -> link).get("operationId");

        Thread.sleep(3_000);
        endpoints.cleanupExpiredOperations();

        assertEquals(List.of(), pending(endpoints, "pendingHosts"));
        Map<?, ?> ended = ended(endpoints).getFirst();
        assertEquals(operation, ended.get("operationId"));
        assertEquals("HOST", ended.get("kind"));
        assertEquals("FAILED", ended.get("state"));
        assertEquals("EXPIRED", ended.get("reason"));
        assertEquals("the invitation expired before the other computer connected; make a new invitation", ended.get("message"));
    }

    @Test
    void aHostWaitThatFailsSaysWhy() throws Exception {
        ControlPlaneOnboardingEndpoints endpoints = new ControlPlaneOnboardingEndpoints(new Operations(invitationExpiringIn(600)));
        endpoints.startInvitation(Map.of(), link -> link);

        hostWait.completeExceptionally(new OnboardingFailure(OnboardingFailureCode.CONNECTION_FAILED, null));

        Map<?, ?> ended = ended(endpoints).getFirst();
        assertEquals("FAILED", ended.get("state"));
        assertEquals("CONNECTION_FAILED", ended.get("reason"));
        assertEquals("the other computer connected but the connection could not be completed; make a new invitation", ended.get("message"));
    }

    @Test
    void aHostWaitThatConnectedIsListedAsConnectedWithNoReason() throws Exception {
        ControlPlaneOnboardingEndpoints endpoints = new ControlPlaneOnboardingEndpoints(new Operations(invitationExpiringIn(600)));
        endpoints.startInvitation(Map.of(), link -> link);

        hostWait.complete(null);

        Map<?, ?> ended = ended(endpoints).getFirst();
        assertEquals("CONNECTED", ended.get("state"));
        assertEquals(false, ended.containsKey("reason"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> ended(ControlPlaneOnboardingEndpoints endpoints) {
        return (List<Map<?, ?>>) endpoints.pending().get("endedOperations");
    }

    private static List<?> pending(ControlPlaneOnboardingEndpoints endpoints, String key) {
        return (List<?>) endpoints.pending().get(key);
    }

    private static String invitationExpiringIn(int seconds) throws Exception {
        Instant issued = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return CompactBootstrapInvitation.createMinimized(NodeIdentity.generate(), ProtocolVersion.V1, issued, issued.plusSeconds(seconds),
                new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES],
                List.of(new Candidate(CandidateType.LAN, InetAddress.getLoopbackAddress(), 4433, 0))).shareLink();
    }

    /** Link operations whose one invitation waits on {@link #hostWait}. */
    private final class Operations implements ControlPlaneLinkOperations {

        private final String link;

        Operations(String link) {
            this.link = link;
        }

        @Override
        public PendingHost createInvitation(String expectedPeer, boolean openRouterPort) {
            return new PendingHost() {
                @Override
                public String invitationLink() {
                    return link;
                }

                @Override
                public void importAnswer(String answer) {
                }

                @Override
                public CompletionStage<Void> completion() {
                    return hostWait;
                }

                @Override
                public boolean retainsSession() {
                    return true;
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public PendingJoin importInvitation(String invitation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }
}
