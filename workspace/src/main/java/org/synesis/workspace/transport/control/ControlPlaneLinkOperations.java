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
   * @param expectedPeer optional expected responder node ID
   * @return pending host operation
   * @throws OnboardingFailure if Link cannot create the invitation
   */
  PendingHost createInvitation(String expectedPeer) throws OnboardingFailure;

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
     * Returns the exact compact one-way invitation URI, or the legacy SLO1 URI when the
     * implementation selects the coordinated compatibility flow.
     *
     * @return invitation URI
     */
    String invitationLink();

    /**
     * Consumes one exact legacy SLA2 answer. Compact one-way invitations do not accept an
     * answer and fail closed through this method.
     *
     * @param link exact answer URI
     * @throws OnboardingFailure if Link rejects the answer or session
     */
    void importAnswer(String link) throws OnboardingFailure;

    /**
     * Returns the completion stage for a compact one-way host wait.
     *
     * <p>Legacy host operations return an already-completed stage because their lifecycle is
     * driven by the explicit answer command.
     *
     * @return compact completion stage or an already-completed legacy stage
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
     * Returns the exact legacy SLA2 answer URI, or {@code null} for a compact one-way invitation.
     *
     * @return answer URI, or {@code null} when no answer is required
     */
    String answerLink();

    /**
     * Consumes the answer by starting one authenticated connection.
     *
     * @throws OnboardingFailure if Link rejects the answer or connection
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
