package org.synesis.workspace.transport.control;

import java.util.concurrent.CompletionStage;
import org.synesis.link.onboarding.OnboardingFailure;

/**
 * Narrow control-plane seam for supported Link onboarding operations.
 *
 * <p>The HTTP adapter owns operation bookkeeping only. Implementations retain
 * protocol validation and session lifecycle in Link-owned components.</p>
 */
public interface ControlPlaneLinkOperations extends AutoCloseable {

  /**
   * Creates one pending signed invitation.
   *
   * @param expectedPeer   optional expected responder node ID
   * @param openRouterPort whether the user chose to have the home router forward a port
   * @return pending host operation
   * @throws OnboardingFailure if Link cannot create the invitation
   */
  PendingHost createInvitation(String expectedPeer, boolean openRouterPort) throws OnboardingFailure;

  /**
   * Imports one pending signed invitation.
   *
   * @param link exact invitation URI
   * @return pending join operation
   * @throws OnboardingFailure if Link rejects the invitation
   */
  PendingJoin importInvitation(String link) throws OnboardingFailure;

  /**
   * Closes retained runtime-owned Link state.
   */
  @Override
  void close();

  /**
   * Pending host-side onboarding operation.
   */
  interface PendingHost extends AutoCloseable {

    /**
     * Returns the exact compact invitation URI.
     *
     * @return invitation URI
     */
    String invitationLink();

    /**
     * Accepts the joiner's answer and starts sending toward the addresses it names. It returns at
     * once; the session completes {@link #completion()}.
     *
     * @param link exact answer URI
     * @throws OnboardingFailure if Link rejects the answer
     */
    void importAnswer(String link) throws OnboardingFailure;

    /**
     * Returns the stage that completes when the host's wait for its peer ends.
     *
     * @return completion stage
     */
    CompletionStage<Void> completion();

    /**
     * Reports whether successful completion leaves a live session owned by the implementation.
     *
     * @return true when the implementation retains the session
     */
    boolean retainsSession();

    /**
     * Closes this pending or retained operation.
     */
    @Override
    void close();
  }

  /**
   * Pending join-side onboarding operation.
   */
  interface PendingJoin extends AutoCloseable {

    /**
     * Returns the answer URI for the host's user, needed when the two sides are on different
     * networks.
     *
     * @return answer URI
     */
    String answerLink();

    /**
     * Dials the host until one authenticated connection is made or the invitation expires.
     *
     * @throws OnboardingFailure if no connection is made before the invitation expires
     */
    void connect() throws OnboardingFailure;

    /**
     * Reports whether successful completion leaves a live session owned by the implementation.
     *
     * @return true when the implementation retains the session
     */
    boolean retainsSession();

    /**
     * Closes this pending or retained operation.
     */
    @Override
    void close();
  }
}
