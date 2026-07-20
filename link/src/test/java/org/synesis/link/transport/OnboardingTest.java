package org.synesis.link.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Verifies the Link onboarding façade event and failure boundary. */
final class OnboardingTest {
    @Test
    void identityShowEmitsTypedIdentityFacts() throws Exception {
        Path profile = Files.createTempDirectory("synesis-onboarding");
        List<OnboardingEvent> events = new ArrayList<>();
        String nodeId = new Onboarding(profile, events::add).showIdentity();
        assertTrue(nodeId.startsWith("sl1-"));
        assertEquals(OnboardingEventType.IDENTITY_CREATED, events.get(0).type());
        assertEquals(OnboardingEventType.NODE_ID, events.get(1).type());
        assertEquals(nodeId, events.get(1).value());
    }

    @Test
    void invalidJoinIsClassifiedWithoutEchoingTheLink() throws Exception {
        Path profile = Files.createTempDirectory("synesis-onboarding");
        String link = "synesis://join/not-a-valid-invitation";
        OnboardingFailure failure = org.junit.jupiter.api.Assertions.assertThrows(OnboardingFailure.class,
                () -> new Onboarding(profile, event -> { }).join(link));
        assertEquals(OnboardingFailureCode.INVITE_INVALID, failure.code());
        assertTrue(!failure.getMessage().contains(link));
    }
}
