package org.synesis.link.protocol;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;

/**
 * An immutable Synesis Link protocol version.
 *
 * <p>Major versions are incompatible. Minor versions are comparable but are
 * not silently downgraded by negotiation; callers must explicitly advertise
 * the versions they support. The value is thread-safe and safe to log.
 *
 * @param major incompatible protocol generation, non-negative
 * @param minor compatible revision within the generation, non-negative
 * @since 1.0
 */
public record ProtocolVersion(int major, int minor) implements Comparable<ProtocolVersion> {

    /** The only currently supported protocol version. */
    public static final ProtocolVersion V1 = new ProtocolVersion(1, 0);

    /** Validates version components. */
    public ProtocolVersion {
        if (major < 0 || minor < 0 || major > 255 || minor > 255) {
            throw new IllegalArgumentException("version components must be between 0 and 255");
        }
    }

    /**
     * Selects the highest exact common version.
     *
     * @param local versions supported locally
     * @param remote versions advertised by the peer
     * @return highest common version
     * @throws IllegalArgumentException if no exact common version exists
     * @throws NullPointerException if an argument or element is {@code null}
     */
    public static ProtocolVersion negotiate(Collection<ProtocolVersion> local,
            Collection<ProtocolVersion> remote) {
        Objects.requireNonNull(local, "local versions");
        Objects.requireNonNull(remote, "remote versions");
        return local.stream().filter(Objects::nonNull).filter(remote::contains)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalArgumentException("no compatible protocol version"));
    }

    /**
     * Compares major first, then minor.
     *
     * @param other version to compare
     * @return comparison result
     */
    @Override
    public int compareTo(ProtocolVersion other) {
        Objects.requireNonNull(other, "other");
        int majorComparison = Integer.compare(major, other.major);
        return majorComparison != 0 ? majorComparison : Integer.compare(minor, other.minor);
    }
}
