package org.synesis.workspace.transport.control;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.onboarding.OnboardingFailure;
import org.synesis.link.protocol.CompactBootstrapInvitation;

/**
 * Compatibility adapter that preserves the existing one-shot onboarding behavior for callers that
 * do not provide a live runtime owner.
 */
final class OnboardingControlPlaneOperations implements ControlPlaneLinkOperations {

  private final Onboarding onboarding;

  OnboardingControlPlaneOperations(Onboarding onboarding) {
    this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
  }

  @Override
  public PendingHost createInvitation(String expectedPeer) throws OnboardingFailure {
    return new Host(onboarding.createCompactInvitation(expectedPeer));
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
    private final boolean compact;

    private Host(Onboarding.PreparedHost delegate) {
      this.delegate = delegate;
      compact = CompactBootstrapInvitation.isShareLink(delegate.invitationLink());
      if (compact) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
          try {
            delegate.awaitConnection();
          } catch (OnboardingFailure ignored) {
            // The bounded operation remains observable through its normal handle lifecycle.
          }
        });
      }
    }

    @Override
    public String invitationLink() {
      return delegate.invitationLink();
    }

    @Override
    public void importAnswer(String link) throws OnboardingFailure {
      if (compact) {
        throw new OnboardingFailure(org.synesis.link.onboarding.OnboardingFailureCode.ANSWER_INVALID,
            new IllegalArgumentException("compact invitation does not accept an answer"));
      }
      delegate.importAnswer(link);
    }

    @Override
    public CompletionStage<Void> completion() {
      return delegate.completion();
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
