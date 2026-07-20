package org.synesis.link.candidate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Bounded, caller-owned source of direct-connectivity candidates.
 *
 * <p>Providers must not create global threads or retain resources after their
 * returned stage completes. The gatherer owns operation deadlines; providers
 * observe the cooperative cancellation token and keep their own cleanup
 * idempotent. Implementations are expected to be thread-safe.</p>
 */
public interface CandidateProvider extends AutoCloseable {
    /**
     * Returns the stable non-sensitive provider identifier.
     *
     * @return provider identifier
     */
    String id();

    /**
     * Returns the candidate types this provider may return.
     *
     * @return immutable supported-type set
     */
    Set<CandidateType> supportedTypes();

    /**
     * Starts one bounded discovery operation.
     *
     * @param cancellation cooperative operation cancellation
     * @return stage containing a bounded candidate list
     */
    CompletionStage<List<Candidate>> gather(CandidateCancellation cancellation);

    /** Closes provider-owned resources; repeated calls must be safe. */
    @Override
    default void close() { }
}
