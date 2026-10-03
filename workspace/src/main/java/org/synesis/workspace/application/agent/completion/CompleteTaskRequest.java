package org.synesis.workspace.application.agent.completion;

import java.nio.file.Path;

import java.util.Objects;
import java.util.UUID;

/**
 * Caller evidence and requested completion outcome for one exact provider intent.
 *
 * @param projectRoot              control project root path
 * @param provider                 provider identifier
 * @param connectionInstanceId     provider connection instance
 * @param summary                  human-readable completion summary
 * @param outcome                  completion outcome
 * @param expectedIntentId         server-issued intent identity observed by the caller
 * @param expectedWorkGroupId      server-issued work-group identity observed by the caller
 * @param expectedClaimEpoch       current claim epoch observed by the caller
 * @param expectedWorkGroupVersion current work-group version observed by the caller
 * @param expectedRevision         current event-log revision observed by the caller
 * @param expectedParticipant      exact participant handle observed by the caller
 * @param terminalSession          whether to seal the exact provider session after lane completion
 */
public record CompleteTaskRequest(Path projectRoot, String provider, String connectionInstanceId, String summary, CompletionOutcome outcome, UUID expectedIntentId, UUID expectedWorkGroupId, Long expectedClaimEpoch,
                                  Long expectedWorkGroupVersion, Long expectedRevision, String expectedParticipant, boolean terminalSession) {

    /**
     * Validates the request's authority-bearing inputs.
     */
    public CompleteTaskRequest {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        outcome = outcome == null ? CompletionOutcome.SNAPSHOT : outcome;
    }

    /**
     * Constructs the existing snapshot completion request shape.
     *
     * @param projectRoot          control project root
     * @param provider             provider identifier
     * @param connectionInstanceId provider connection instance
     * @param summary              completion summary
     */
    public CompleteTaskRequest(Path projectRoot, String provider, String connectionInstanceId, String summary) {
        this(projectRoot, provider, connectionInstanceId, summary, CompletionOutcome.SNAPSHOT, null, null, null, null, null, null, false);
    }

    /**
     * Constructs a request with terminal session sealing disabled.
     *
     * @param projectRoot              control project root
     * @param provider                 provider identifier
     * @param connectionInstanceId     provider connection instance
     * @param summary                  completion summary
     * @param outcome                  completion outcome
     * @param expectedIntentId         exact intent evidence
     * @param expectedWorkGroupId      exact work-group evidence
     * @param expectedClaimEpoch       exact claim epoch
     * @param expectedWorkGroupVersion exact work-group version
     * @param expectedRevision         exact event revision
     * @param expectedParticipant      exact participant evidence
     */
    public CompleteTaskRequest(Path projectRoot,
            String provider,
            String connectionInstanceId,
            String summary,
            CompletionOutcome outcome,
            UUID expectedIntentId,
            UUID expectedWorkGroupId,
            Long expectedClaimEpoch,
            Long expectedWorkGroupVersion,
            Long expectedRevision,
            String expectedParticipant) {
        this(projectRoot, provider, connectionInstanceId, summary, outcome, expectedIntentId, expectedWorkGroupId, expectedClaimEpoch, expectedWorkGroupVersion, expectedRevision, expectedParticipant, false);
    }
}
