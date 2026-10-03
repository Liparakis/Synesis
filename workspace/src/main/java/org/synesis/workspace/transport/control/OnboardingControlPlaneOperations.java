package org.synesis.workspace.transport.control;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.onboarding.Onboarding.PreparedHost;
import org.synesis.link.onboarding.Onboarding.PreparedJoin;
import org.synesis.link.onboarding.OnboardingFailure;
import org.synesis.link.onboarding.OnboardingFailureCode;
import org.synesis.link.protocol.CompactBootstrapInvitation;

/**
 * Compatibility adapter that preserves the existing one-shot onboarding behavior for callers that
 * do not provide a live runtime owner.
 *
 * <p>Handles returned here never retain their session: once an operation completes, the control
 * plane closes the underlying handle.
 */
final class OnboardingControlPlaneOperations implements ControlPlaneLinkOperations {

    /**
     * Link-owned onboarding facade that all operations delegate to.
     */
    private final Onboarding onboarding;

    /**
     * Creates an adapter over a Link onboarding facade.
     *
     * @param onboarding Link-owned onboarding facade
     * @throws NullPointerException when {@code onboarding} is {@code null}
     */
    OnboardingControlPlaneOperations(Onboarding onboarding) {
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
    }

    /**
     * Creates a compact invitation and wraps it as a pending host operation.
     *
     * @param expectedPeer identity the invitation is restricted to, or {@code null} for any peer
     * @return pending host operation
     * @throws OnboardingFailure when the invitation cannot be created
     */
    @Override
    public PendingHost createInvitation(String expectedPeer) throws OnboardingFailure {
        return new Host(onboarding.createCompactInvitation(expectedPeer));
    }

    /**
     * Imports an invitation and wraps it as a pending join operation.
     *
     * @param link invitation share link
     * @return pending join operation
     * @throws OnboardingFailure when the invitation is invalid or cannot be imported
     */
    @Override
    public PendingJoin importInvitation(String link) throws OnboardingFailure {
        return new Join(onboarding.importInvitation(link));
    }

    /**
     * Does nothing: this adapter owns no resources beyond the handles it has already handed out.
     */
    @Override
    public void close() {
        // Intentionally empty; per-operation handles are closed by their owner.
    }

    /**
     * Host-side handle over a {@link PreparedHost}.
     *
     * <p>Compact invitations connect without an answer, so construction starts waiting for the
     * peer in the background.
     */
    private static final class Host implements PendingHost {

        /**
         * Underlying Link host operation.
         */
        private final PreparedHost delegate;

        /**
         * Whether the invitation is compact and therefore rejects answers.
         */
        private final boolean compact;

        /**
         * Wraps a prepared host and, for compact invitations, starts awaiting the peer.
         *
         * @param delegate underlying Link host operation
         */
        private Host(PreparedHost delegate) {
            this.delegate = delegate;
            compact = CompactBootstrapInvitation.isShareLink(delegate.invitationLink());
            if (!compact) {
                return;
            }
            CompletableFuture.runAsync(() -> {
                try {
                    delegate.awaitConnection();
                } catch (OnboardingFailure ignored) {
                    // The bounded operation remains observable through its normal handle lifecycle.
                }
            });
        }

        /**
         * Returns the invitation link to share with the peer.
         *
         * @return invitation share link
         */
        @Override
        public String invitationLink() {
            return delegate.invitationLink();
        }

        /**
         * Imports the peer's answer.
         *
         * @param link answer share link
         * @throws OnboardingFailure {@link OnboardingFailureCode#ANSWER_INVALID} for compact
         *                           invitations, otherwise whatever the Link layer reports
         */
        @Override
        public void importAnswer(String link) throws OnboardingFailure {
            if (compact) {
                throw new OnboardingFailure(OnboardingFailureCode.ANSWER_INVALID, new IllegalArgumentException("compact invitation does not accept an answer"));
            }
            delegate.importAnswer(link);
        }

        /**
         * Returns the stage that completes when the operation succeeds or fails.
         *
         * @return completion stage of the underlying operation
         */
        @Override
        public CompletionStage<Void> completion() {
            return delegate.completion();
        }

        /**
         * Reports that the session is closed with the handle.
         *
         * @return always {@code false}
         */
        @Override
        public boolean retainsSession() {
            return false;
        }

        /**
         * Closes the underlying operation.
         */
        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * Join-side handle over a {@link PreparedJoin}.
     *
     * @param delegate underlying Link join operation
     */
    private record Join(PreparedJoin delegate) implements PendingJoin {

        /**
         * Returns the answer link to hand back to the host.
         *
         * @return answer share link
         */
        @Override
        public String answerLink() {
            return delegate.answerLink();
        }

        /**
         * Connects to the host.
         *
         * @throws OnboardingFailure when the connection cannot be established
         */
        @Override
        public void connect() throws OnboardingFailure {
            delegate.connect();
        }

        /**
         * Reports that the session is closed with the handle.
         *
         * @return always {@code false}
         */
        @Override
        public boolean retainsSession() {
            return false;
        }

        /**
         * Closes the underlying operation.
         */
        @Override
        public void close() {
            delegate.close();
        }
    }
}