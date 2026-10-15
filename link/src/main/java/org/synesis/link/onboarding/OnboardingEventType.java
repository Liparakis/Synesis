package org.synesis.link.onboarding;

/**
 * Operational facts emitted by the Link-owned terminal onboarding workflow.
 *
 * @since 1.0
 */
public enum OnboardingEventType {
  /**
   * The local identity was created.
   */
  IDENTITY_CREATED,
  /**
   * The local identity was loaded.
   */
  IDENTITY_LOADED,
  /**
   * A session identity and capability were allocated.
   */
  SESSION_CREATED,
  /**
   * The host listener is accepting connections.
   */
  LISTENER_READY,
  /**
   * Candidate gathering completed; the value is the bounded count.
   */
  CANDIDATES_GATHERED,
  /**
   * A signed candidate descriptor was created.
   */
  DESCRIPTOR_CREATED,
  /**
   * A signed invitation was created.
   */
  INVITE_CREATED,
  /**
   * An invitation was parsed.
   */
  INVITE_PARSED,
  /**
   * An invitation passed version, signature, and expiry checks.
   */
  INVITE_VERIFIED,
  /**
   * The host accepted the joiner's answer link and is sending toward the joiner's addresses.
   */
  ANSWER_VERIFIED,
  /**
   * The joiner has started dialing the invitation's endpoints.
   */
  TRAVERSAL_STARTED,
  /**
   * The joiner's first attempts failed and it keeps dialing until the invitation expires. Across
   * two networks this is expected until the host's user pastes the answer link.
   */
  WAITING_FOR_HOST,
  /**
   * The home router forwards a public port to this host; the value is that public address and port.
   */
  ROUTER_PORT_OPENED,
  /**
   * The host asked the home router for a forward and did not get one; the value says why.
   */
  ROUTER_PORT_UNAVAILABLE,
  /**
   * The invitation's host identity was pinned.
   */
  HOST_IDENTITY_PINNED,
  /**
   * The local candidate descriptor is ready.
   */
  LOCAL_DESCRIPTOR_CREATED,
  /**
   * The selected candidate pair identifier.
   */
  PATH_SELECTED,
  /**
   * The exact invitation share link.
   */
  SHARE_LINK,
  /**
   * The joiner's answer link, which the host's user pastes when a direct connection does not
   * happen on its own.
   */
  ANSWER_LINK,
  /**
   * An authenticated peer session was connected.
   */
  PEER_CONNECTED,
  /**
   * The authenticated remote node identifier.
   */
  PEER_IDENTITY_VERIFIED,
  /**
   * Whether the control path is ready.
   */
  CONTROL_READY,
  /**
   * Complete signed candidate descriptors were exchanged after control readiness.
   */
  DESCRIPTORS_EXCHANGED,
  /**
   * The current liveness state.
   */
  LIVENESS,
  /**
   * The bounded demo work result status.
   */
  WORK_RESULT,
  /**
   * The local node identifier.
   */
  NODE_ID,
  /**
   * The session closed after bounded cleanup.
   */
  SESSION_CLOSED
}
