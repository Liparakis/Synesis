package org.synesis.link.candidate;

import java.util.concurrent.CompletionStage;

/** Handle for one cancellable candidate gathering operation. */
public final class CandidateGatheringOperation {
    private final CompletionStage<CandidateGatheringResult> completion;
    private final Runnable cancelAction;
    private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();

    CandidateGatheringOperation(CompletionStage<CandidateGatheringResult> completion, Runnable cancelAction) {
        this.completion = completion;
        this.cancelAction = cancelAction;
    }

    /**
     * Returns exactly-once completion of the bounded gathering result.
     *
     * @return gathering completion stage
     */
    public CompletionStage<CandidateGatheringResult> completion() { return completion; }

    /**
     * Cancels provider work and completes with bounded cancellation diagnostics.
     *
     * @return true only for the call that selected cancellation
     */
    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) return false;
        cancelAction.run();
        return true;
    }
}
