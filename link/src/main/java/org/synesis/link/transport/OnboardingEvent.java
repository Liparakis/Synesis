package org.synesis.link.transport;

import java.util.Objects;

/**
 * Immutable, typed operational fact from a Link onboarding operation.
 *
 * <p>The value is event-specific. A share-link value is an invitation
 * capability and must be handled as user-provided secret material; it is
 * intentionally available to the terminal adapter but never included in
 * exception text.
 *
 * @param type event classification
 * @param value bounded event value, or an empty string when no value applies
 * @since 1.0
 */
public record OnboardingEvent(OnboardingEventType type, String value) {
    /** Creates a validated event. */
    public OnboardingEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
    }
}
