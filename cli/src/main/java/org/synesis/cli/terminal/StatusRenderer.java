package org.synesis.cli.terminal;

import java.util.function.Consumer;

import org.synesis.link.onboarding.OnboardingEvent;
import org.synesis.link.onboarding.OnboardingEventType;

/**
 * Maps typed Link events to the stable terminal status contract.
 */
public final class StatusRenderer implements Consumer<OnboardingEvent> {

    private final Terminal terminal;

    /**
     * Creates a renderer bound to one terminal.
     *
     * @param terminal output boundary
     */
    public StatusRenderer(Terminal terminal) {
        this.terminal = terminal;
    }

    /**
     * Renders one operational fact.
     *
     * @param event typed Link event
     */
    @Override
    public void accept(OnboardingEvent event) {
        OnboardingEventType type = event.type();
        if (type == OnboardingEventType.WAITING_FOR_HOST) {
            terminal.stdout(type.name());
            terminal.stdout("NEXT=Send the answer link above to the person who invited you. This keeps trying until the invitation expires.");
            return;
        }
        String line = switch (type) {
            case IDENTITY_CREATED, IDENTITY_LOADED, SESSION_CREATED, LISTENER_READY, DESCRIPTOR_CREATED, INVITE_CREATED, INVITE_PARSED, INVITE_VERIFIED, ANSWER_VERIFIED, TRAVERSAL_STARTED, LOCAL_DESCRIPTOR_CREATED,
                 DESCRIPTORS_EXCHANGED, PEER_CONNECTED, SESSION_CLOSED -> type.name();
            default -> type.name() + "=" + event.value();
        };
        terminal.stdout(line);
    }
}
