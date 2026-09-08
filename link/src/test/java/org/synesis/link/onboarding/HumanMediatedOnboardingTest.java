package org.synesis.link.onboarding;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.synesis.link.protocol.TraversalAnswer;
import org.synesis.link.protocol.TraversalInvitation;
import org.synesis.link.protocol.TraversalOffer;

/** Verifies the two-link onboarding lifecycle through an authenticated local session. */
@Timeout(90)
final class HumanMediatedOnboardingTest {

    @Test
    void importsAnswerAndStartsExistingAuthenticatedQuicRace() throws Exception {
        Path hostProfile = Files.createTempDirectory("synesis-mediated-host");
        Path joinProfile = Files.createTempDirectory("synesis-mediated-join");
        List<OnboardingEvent> hostEvents = new ArrayList<>();
        List<OnboardingEvent> joinEvents = new ArrayList<>();
        Onboarding hostOnboarding = new Onboarding(hostProfile, hostEvents::add);
        Onboarding joinOnboarding = new Onboarding(joinProfile, joinEvents::add);

        try (Onboarding.PreparedHost host = hostOnboarding.createInvitation(null, null, ignored -> {
                 });
             Onboarding.PreparedJoin join = joinOnboarding.importInvitation(host.invitationLink(), null,
                     ignored -> {
                     })) {
            assertTrue(host.invitationLink().startsWith("synesis://join/SLO1-"));
            assertTrue(join.answerLink().startsWith("synesis://answer/SLA2-"));
            TraversalInvitation invitation = TraversalInvitation.fromShareLink(host.invitationLink());
            TraversalAnswer answer = TraversalAnswer.fromShareLink(join.answerLink());
            assertTrue(invitation.offer().verifyAt(java.time.Instant.now(), TraversalOffer.DEFAULT_CLOCK_SKEW,
                    answer.responderNodeId()));
            assertTrue(answer.verifyAt(java.time.Instant.now(), TraversalAnswer.DEFAULT_CLOCK_SKEW,
                    invitation.offer()));
            CompletableFuture<Void> hostWait = CompletableFuture.runAsync(() -> {
                try {
                    host.importAnswer(join.answerLink());
                } catch (OnboardingFailure failure) {
                    throw new IllegalStateException(failure);
                }
            });
            assertDoesNotThrow(join::connect);
            assertDoesNotThrow(() -> hostWait.get(45, TimeUnit.SECONDS));
            assertTrue(joinEvents.stream().anyMatch(event -> event.type() == OnboardingEventType.TRAVERSAL_STARTED));
            assertTrue(hostEvents.stream().anyMatch(event -> event.type() == OnboardingEventType.ANSWER_VERIFIED));
            assertTrue(joinEvents.stream().anyMatch(event -> event.type() == OnboardingEventType.CONTROL_READY
                    && Boolean.parseBoolean(event.value())));
            OnboardingFailure replay = assertThrows(OnboardingFailure.class,
                    () -> host.importAnswer(join.answerLink()));
            assertEquals(OnboardingFailureCode.ANSWER_INVALID, replay.code());
        }
    }
}
