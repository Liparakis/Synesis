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
   * Wraps an invitation this computer made into a signed project link (ADR-0098, "Joining, revised").
   *
   * @param connectionLink the link {@link #createInvitation} returned
   * @param projectId      the project's identifier
   * @param createdAt      the project's creation time
   * @param codeRemoteUrl  URL of the code remote without credentials, or {@code null}
   * @return the {@code synesis://project/SLP1-...} link
   * @throws java.io.IOException              when {@code connectionLink} is not an invitation
   * @throws java.security.GeneralSecurityException when signing fails
   */
  default String projectInvitationLink(String connectionLink, java.util.UUID projectId,
      java.time.Instant createdAt, String codeRemoteUrl)
      throws java.io.IOException, java.security.GeneralSecurityException {
    throw new UnsupportedOperationException("project invitations are not supported");
  }

  /**
   * Returns this computer's node ID.
   *
   * @return the node ID
   */
  default String localNodeId() {
    throw new UnsupportedOperationException("local node is unknown");
  }

  /**
   * Lists the joiners waiting for this computer's person to accept or decline them.
   *
   * @return pending requests, oldest first; empty when this computer holds none
   */
  default java.util.List<JoinRequest> joinRequests() {
    return java.util.List.of();
  }

  /**
   * Accepts a joiner: it becomes a member and is served.
   *
   * @param nodeId the requesting node
   * @throws JoinRequestException when there is no such request, it expired, or the member list was not saved
   */
  default void acceptJoin(String nodeId) throws JoinRequestException {
    throw new JoinRequestException("JOIN_REQUEST_UNKNOWN");
  }

  /**
   * Declines a joiner: its session is closed.
   *
   * @param nodeId the requesting node
   * @throws JoinRequestException when there is no such request or it expired
   */
  default void declineJoin(String nodeId) throws JoinRequestException {
    throw new JoinRequestException("JOIN_REQUEST_UNKNOWN");
  }

  /**
   * Closes retained runtime-owned Link state.
   */
  @Override
  void close();

  /**
   * A node that authenticated with an invitation this computer hosts and is not (yet) one it serves.
   *
   * @param nodeId      the requesting node
   * @param requestedAt when its session was admitted
   * @param expiresAt   when the invitation it used expires, which ends the request
   */
  record JoinRequest(String nodeId, java.time.Instant requestedAt, java.time.Instant expiresAt) {
  }

  /**
   * Why a join request cannot be decided.
   */
  final class JoinRequestException extends Exception {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** The reason. */
    private final String code;

    /**
     * Creates the exception.
     *
     * @param code the reason
     */
    JoinRequestException(String code) {
      super(code);
      this.code = code;
    }

    /**
     * Returns the reason.
     *
     * @return {@code JOIN_REQUEST_UNKNOWN} or {@code MEMBER_NOT_SAVED}
     */
    public String code() {
      return code;
    }
  }

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
