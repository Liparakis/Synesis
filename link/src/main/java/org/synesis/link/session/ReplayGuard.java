package org.synesis.link.session;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded process-local replay guard for authenticated session transcripts.
 *
 * <p>A guard is intentionally not persisted: a process restart must establish
 * a fresh session and must not pretend that in-memory replay state survived.
 *
 * @since 1.0
 */
public final class ReplayGuard {

    /** Default maximum number of accepted transcript fingerprints retained. */
    public static final int DEFAULT_MAX_ENTRIES = 1_024;

    private final int maxEntries;
    private final Set<String> fingerprints = new HashSet<>();

    /** Creates a guard with the default bounded capacity. */
    public ReplayGuard() {
        this(DEFAULT_MAX_ENTRIES);
    }

    /**
     * Creates a bounded guard.
     *
     * @param maxEntries positive maximum retained fingerprints
     */
    public ReplayGuard(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("max entries must be positive");
        }
        this.maxEntries = maxEntries;
    }

    /**
     * Atomically records a fingerprint if it has not been seen.
     *
     * @param fingerprint non-empty opaque fingerprint
     * @return {@code true} when newly recorded, {@code false} for a replay
     */
    public synchronized boolean accept(String fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (fingerprint.isEmpty() || fingerprint.length() > 256) {
            throw new IllegalArgumentException("fingerprint exceeds supported bound");
        }
        if (fingerprints.contains(fingerprint)) {
            return false;
        }
        if (fingerprints.size() == maxEntries) {
            throw new IllegalStateException("replay guard capacity exhausted");
        }
        fingerprints.add(fingerprint);
        return true;
    }
}
