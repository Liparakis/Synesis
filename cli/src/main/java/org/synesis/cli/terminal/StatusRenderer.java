package org.synesis.cli.terminal;

import java.util.function.Consumer;

import org.synesis.link.transport.OnboardingEvent;
import org.synesis.link.transport.OnboardingEventType;

/**
 * Maps typed Link events to the stable terminal status contract.
 */
public final class StatusRenderer implements Consumer<OnboardingEvent> {
    private final Terminal terminal;
    private final QrRenderer qr;

    /**
     * Creates a renderer bound to one terminal.
     *
     * @param terminal output boundary
     */
    public StatusRenderer(Terminal terminal) {
        this.terminal = terminal;
        this.qr = new CompactQrRenderer(terminal.width(), terminal.unicodeSupported());
    }

    /**
     * Renders one operational fact.
     *
     * @param event typed Link event
     */
    @Override
    public void accept(OnboardingEvent event) {
        OnboardingEventType type = event.type();
        if (type == OnboardingEventType.SHARE_LINK) {
            terminal.stdout("SHARE_LINK=" + event.value());
            try {
                String rendered = qr.render(event.value());
                terminal.stdout("QR_RENDERED=COMPACT");
                terminal.stdoutRaw(rendered);
            } catch (IllegalArgumentException failure) {
                String message = failure.getMessage();
                String reason = switch (message == null ? "" : message) {
                    case "TERMINAL_TOO_NARROW", "UNICODE_UNSUPPORTED" -> failure.getMessage();
                    default -> "UNAVAILABLE";
                };
                terminal.stdout("QR_SKIPPED=" + reason);
            }
            return;
        }
        String line = switch (type) {
            case IDENTITY_CREATED, IDENTITY_LOADED, SESSION_CREATED, LISTENER_READY,
                 DESCRIPTOR_CREATED, INVITE_CREATED, INVITE_PARSED, INVITE_VERIFIED,
                 LOCAL_DESCRIPTOR_CREATED, PEER_CONNECTED, SESSION_CLOSED -> type.name();
            default -> type.name() + "=" + event.value();
        };
        terminal.stdout(line);
    }
}
