package org.synesis.workspace.transport.control;

import java.util.Objects;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.onboarding.OnboardingFailure;

/**
 * Compatibility adapter that preserves the existing one-shot onboarding
 * behavior for callers that do not provide a live runtime owner.
 */
final class OnboardingControlPlaneOperations implements ControlPlaneLinkOperations {

    private final Onboarding onboarding;

    OnboardingControlPlaneOperations(Onboarding onboarding) {
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
    }

    @Override
    public PendingHost createInvitation(String expectedPeer) throws OnboardingFailure {
        return new Host(onboarding.createInvitation(expectedPeer));
    }

    @Override
    public PendingJoin importInvitation(String link) throws OnboardingFailure {
        return new Join(onboarding.importInvitation(link));
    }

    @Override
    public void close() {
    }

    private static final class Host implements PendingHost {

        private final Onboarding.PreparedHost delegate;

        private Host(Onboarding.PreparedHost delegate) {
            this.delegate = delegate;
        }

        @Override
        public String invitationLink() {
            return delegate.invitationLink();
        }

        @Override
        public void importAnswer(String link) throws OnboardingFailure {
            delegate.importAnswer(link);
        }

        @Override
        public boolean retainsSession() {
            return false;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class Join implements PendingJoin {

        private final Onboarding.PreparedJoin delegate;

        private Join(Onboarding.PreparedJoin delegate) {
            this.delegate = delegate;
        }

        @Override
        public String answerLink() {
            return delegate.answerLink();
        }

        @Override
        public void connect() throws OnboardingFailure {
            delegate.connect();
        }

        @Override
        public boolean retainsSession() {
            return false;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
