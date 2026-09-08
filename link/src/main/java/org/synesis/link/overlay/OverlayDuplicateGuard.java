package org.synesis.link.overlay;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded expiring duplicate guard for hop-local logical frames.
 */
public final class OverlayDuplicateGuard {

    private final int maxEntries;
    private final Duration lifetime;
    private final Map<Key, Instant> entries = new LinkedHashMap<>();

    /**
     * Creates a duplicate guard with a bounded 10-minute cache.
     *
     * @param maxEntries maximum remembered frame keys
     */
    public OverlayDuplicateGuard(int maxEntries) {
        this(maxEntries, Duration.ofMinutes(10));
    }

    /**
     * Creates a duplicate guard.
     *
     * @param maxEntries maximum remembered frame keys
     * @param lifetime duration for one remembered key
     */
    public OverlayDuplicateGuard(int maxEntries, Duration lifetime) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("duplicate guard bound must be positive");
        }
        Objects.requireNonNull(lifetime, "lifetime");
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("duplicate guard lifetime must be positive");
        }
        this.maxEntries = maxEntries;
        this.lifetime = lifetime;
    }

    /**
     * Records a frame if it has not been seen during the bounded lifetime.
     *
     * @param frame forwarding frame
     * @param now current time
     * @return true for a first observation, false for a duplicate
     */
    public synchronized boolean firstSeen(OverlayForwardingFrame frame, Instant now) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(now, "now");
        prune(now);
        Key key = new Key(frame.projectId(), frame.messageId(), OverlayCodecSupport.sha256(frame.innerRecord()));
        Instant expiry = entries.get(key);
        if (expiry != null && expiry.isAfter(now)) {
            return false;
        }
        entries.put(key, expiryAt(now));
        while (entries.size() > maxEntries) {
            Iterator<Key> iterator = entries.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
        return true;
    }

    /**
     * Removes expired entries.
     *
     * @param now current time
     * @return number removed
     */
    public synchronized int prune(Instant now) {
        Objects.requireNonNull(now, "now");
        int before = entries.size();
        entries.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        return before - entries.size();
    }

    /**
     * Returns the current bounded cache size.
     *
     * @return number of remembered keys
     */
    public synchronized int size() {
        return entries.size();
    }

    private Instant expiryAt(Instant now) {
        try {
            return now.plus(lifetime);
        } catch (RuntimeException overflow) {
            return Instant.MAX;
        }
    }

    private record Key(UUID projectId, UUID messageId, byte[] digest) {

        private Key {
            digest = digest.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && projectId.equals(key.projectId) && messageId.equals(key.messageId)
                    && java.util.Arrays.equals(digest, key.digest);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * projectId.hashCode() + messageId.hashCode())
                    + java.util.Arrays.hashCode(digest);
        }
    }
}
