package org.synesis.link.candidate;

import java.net.InetAddress;
import java.util.Objects;

/**
 * An immutable direct-connectivity route candidate.
 *
 * <p>The value is thread-safe. The address is copied into the JDK immutable
 * address representation. Port numbers are in the TCP/UDP range and priority
 * is an unsigned 32-bit ordering value represented by a non-negative Java
 * integer. The diagnostic string intentionally omits the address.
 *
 * @param type source category
 * @param address route address, never {@code null}
 * @param port destination port from 1 through 65535
 * @param priority lower values are preferred; must be non-negative
 * @since 1.0
 */
public record Candidate(CandidateType type, InetAddress address, int port, int priority) {

    /** Creates and validates a candidate. */
    public Candidate {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(address, "address");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (priority < 0) {
            throw new IllegalArgumentException("priority must be non-negative");
        }
    }

    /**
     * Returns a redacted diagnostic value.
     *
     * @return type, port, and priority without the potentially sensitive address
     */
    @Override
    public String toString() {
        return "Candidate[type=" + type + ", port=" + port + ", priority=" + priority + "]";
    }

    byte[] addressBytes() {
        return address.getAddress().clone();
    }
}
