package org.synesis.link.session;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded process-local replay guard for authenticated session transcripts.
 *
 * <p>A guard is process-local unless it is given a {@link Store}: then every newly accepted fingerprint
 * is saved before {@link #accept} returns, and a guard rebuilt from what the store saved refuses it again.
 * A host that keeps its port across a restart needs that; a one-shot guard does not.
 *
 * @since 1.0
 */
public final class ReplayGuard {

  /**
   * Default maximum number of accepted transcript fingerprints retained.
   */
  static final int DEFAULT_MAX_ENTRIES = 1_024;

  private final int maxEntries;
  private final Set<String> fingerprints = new LinkedHashSet<>();
  private final Store store;

  /**
   * Keeps the accepted fingerprints beyond the process.
   */
  @FunctionalInterface
  public interface Store {

    /**
     * Durably replaces what is kept with every fingerprint accepted so far, the new one included.
     *
     * @param fingerprints all accepted fingerprints
     * @throws IOException when they cannot be kept
     */
    void save(Set<String> fingerprints) throws IOException;
  }

  /**
   * Creates a guard with the default bounded capacity.
   */
  public ReplayGuard() {
    this(DEFAULT_MAX_ENTRIES);
  }

  /**
   * Creates a bounded guard.
   *
   * @param maxEntries positive maximum retained fingerprints
   */
  public ReplayGuard(int maxEntries) {
    this(maxEntries, Set.of(), null);
  }

  /**
   * Creates a bounded guard that survives a restart.
   *
   * @param maxEntries positive maximum retained fingerprints
   * @param restored   what {@code store} saved before; no more than {@code maxEntries}
   * @param store      keeps each newly accepted fingerprint, or {@code null} for none
   */
  public ReplayGuard(int maxEntries, Collection<String> restored, Store store) {
    if (maxEntries <= 0) {
      throw new IllegalArgumentException("max entries must be positive");
    }
    if (restored.size() > maxEntries) {
      throw new IllegalArgumentException("restored fingerprints exceed capacity");
    }
    this.maxEntries = maxEntries;
    this.store = store;
    fingerprints.addAll(restored);
  }

  /**
   * Atomically records a fingerprint if it has not been seen.
   *
   * @param fingerprint non-empty opaque fingerprint
   * @return {@code true} when newly recorded, {@code false} for a replay
   * @throws IllegalStateException when the guard is full, or the store could not keep the fingerprint (the
   *                               handshake is then refused: a fingerprint that is not kept is not accepted)
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
    if (store != null) {
      try {
        store.save(fingerprints);
      } catch (IOException | RuntimeException unkept) {
        fingerprints.remove(fingerprint);
        throw new IllegalStateException("replay guard could not record the handshake", unkept);
      }
    }
    return true;
  }
}
