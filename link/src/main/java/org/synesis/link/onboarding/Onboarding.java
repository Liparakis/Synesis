package org.synesis.link.onboarding;

import io.netty.channel.Channel;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.synesis.link.SynesisLink;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidatePair;
import org.synesis.link.candidate.CandidatePairs;
import org.synesis.link.session.CandidateRacer;
import org.synesis.link.candidate.ConnectionPolicy;
import org.synesis.link.session.DirectConnectionResult;
import org.synesis.link.demo.DemoWorkRequest;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.CompactAnswer;
import org.synesis.link.protocol.CompactBootstrapInvitation;
import org.synesis.link.protocol.HandshakeException;
import org.synesis.link.protocol.IncompatibleVersionException;
import org.synesis.link.protocol.LinkCompatibility;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.protocol.HandshakeRole;
import org.synesis.link.protocol.HandshakeTranscript;
import org.synesis.link.session.LivenessConfiguration;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.ReplayGuard;
import org.synesis.link.session.SessionCloseReason;

/**
 * Link-owned synchronous onboarding faÃ§ade.
 *
 * <p>This type owns the existing identity, invitation, candidate, Netty,
 * handshake, admission, liveness, work, and cleanup lifecycle. It emits facts to the supplied
 * listener and never writes terminal output or maps process exit codes. Each operation is bounded
 * and cleans up all resources before it returns or throws.
 *
 * <p>The type is thread-confined: one operation may be active at a time, and
 * the listener is called synchronously on the operation thread. The one exception is
 * {@link PreparedHost#importAnswer(String)}, which a user's paste delivers while the host waits on
 * another thread; its event is emitted on the calling thread, so a listener shared with a waiting
 * host must accept a call from either.
 *
 * @since 1.0
 */
public final class Onboarding {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int NONCE_BYTES = 16;
  /**
   * Dials every endpoint of a compact invitation at once: the endpoints are alternatives of which
   * usually one answers, and waiting out a dead one before trying the next would spend the race.
   */
  private static final ConnectionPolicy COMPACT_CONNECTION_POLICY = new ConnectionPolicy(
      CompactBootstrapInvitation.MAX_ENDPOINTS, CompactBootstrapInvitation.MAX_ENDPOINTS,
      CompactBootstrapInvitation.MAX_ENDPOINTS, Duration.ofMillis(100), Duration.ofSeconds(10),
      Duration.ofSeconds(20), 16);
  /** The longest one race of {@link #COMPACT_CONNECTION_POLICY} is waited for. */
  private static final Duration RACE_BOUND = Duration.ofSeconds(25);
  /** The shortest time between the starts of two races. */
  private static final Duration MIN_ROUND = Duration.ofSeconds(5);
  /** Environment variable naming the STUN server as {@code host:port}, or {@code off}. */
  public static final String STUN_SERVER_VARIABLE = "SYNESIS_STUN_SERVER";
  /**
   * STUN server used when none is configured. A STUN server only tells a machine the public
   * address its router gives it; it carries no Synesis traffic and learns nothing but that address.
   */
  static final String DEFAULT_STUN_SERVER = "stun.cloudflare.com:3478";
  private final Path profileDirectory;
  private final Consumer<OnboardingEvent> events;
  private final InetSocketAddress stunServer;
  private volatile ReplayGuard retainedReplay = new ReplayGuard(OnboardingEndpoint.RETAINED_REPLAY_ENTRIES);

  /**
   * Replaces the replay guard that every retained host endpoint of this facade shares, so that a caller can give it
   * one restored from disk before any endpoint listens. Without it the guard is process-local.
   *
   * @param guard guard of {@link #retainedReplayEntries()} capacity
   */
  public void useRetainedReplayGuard(ReplayGuard guard) {
    this.retainedReplay = Objects.requireNonNull(guard, "guard");
  }

  /**
   * Capacity of the retained host's replay guard.
   *
   * @return maximum accepted handshakes it remembers
   */
  public static int retainedReplayEntries() {
    return OnboardingEndpoint.RETAINED_REPLAY_ENTRIES;
  }

  /**
   * Creates an onboarding faÃ§ade for one profile.
   *
   * @param profileDirectory profile directory; it may be created by host or join identity
   *                         bootstrap
   * @param events           synchronous operational-event listener
   */
  public Onboarding(Path profileDirectory, Consumer<OnboardingEvent> events) {
    this(profileDirectory, events, null);
  }

  /**
   * Creates an onboarding facade with an explicitly configured optional STUN server.
   *
   * <p>The server is used only for bounded server-reflexive discovery. STUN
   * traffic is sent through the same bound parent UDP channel that carries Link QUIC traffic. A
   * {@code null} server preserves the ordinary local/manual candidate path.
   *
   * @param profileDirectory profile directory; it may be created by host or join identity
   *                         bootstrap
   * @param events           synchronous operational-event listener
   * @param stunServer       configured STUN endpoint, or {@code null} to disable
   */
  public Onboarding(Path profileDirectory, Consumer<OnboardingEvent> events,
      InetSocketAddress stunServer) {
    this.profileDirectory = Objects.requireNonNull(profileDirectory, "profile directory");
    this.events = Objects.requireNonNull(events, "events");
    this.stunServer = stunServer;
  }

  /**
   * Creates a faÃ§ade using the platform-local default profile directory.
   *
   * @param events synchronous operational-event listener
   * @return configured faÃ§ade
   */
  public static Onboarding defaults(Consumer<OnboardingEvent> events) {
    return new Onboarding(IdentityBootstrap.defaultDirectory(), events);
  }

  /**
   * Returns the STUN server the product uses: {@value #STUN_SERVER_VARIABLE} when set, otherwise
   * {@value #DEFAULT_STUN_SERVER}. The name is resolved when an invitation is created or answered.
   *
   * @return unresolved STUN server, or {@code null} when it is configured {@code off}
   * @throws IllegalArgumentException if the variable is neither {@code host:port} nor {@code off}
   */
  public static InetSocketAddress stunServerFromEnvironment() {
    return parseStunServer(System.getenv(STUN_SERVER_VARIABLE));
  }

  static InetSocketAddress parseStunServer(String value) {
    String configured = value == null || value.isBlank() ? DEFAULT_STUN_SERVER : value.trim();
    if ("off".equalsIgnoreCase(configured)) {
      return null;
    }
    int colon = configured.lastIndexOf(':');
    try {
      int port = Integer.parseInt(configured.substring(colon + 1));
      if (colon < 1 || port < 1 || port > 65_535) {
        throw new NumberFormatException();
      }
      return InetSocketAddress.createUnresolved(configured.substring(0, colon), port);
    } catch (NumberFormatException | IndexOutOfBoundsException invalid) {
      throw new IllegalArgumentException(STUN_SERVER_VARIABLE + " must be host:port or off");
    }
  }

  private static byte[] randomBytes() {
    byte[] value = new byte[NONCE_BYTES];
    RANDOM.nextBytes(value);
    return value;
  }

  /**
   * Builds the failure, upgrading it to {@code INCOMPATIBLE_VERSION} when the cause chain shows a
   * compatibility mismatch, so no caller can report one as an invalid invitation or a lost
   * connection.
   */
  private static OnboardingFailure failure(OnboardingFailureCode code, Throwable cause) {
    for (Throwable link = cause; link != null; link = link.getCause()) {
      LinkCompatibility.Mismatch mismatch = link instanceof IncompatibleVersionException value
          ? value.mismatch()
          : link instanceof HandshakeException value ? value.mismatch() : null;
      if (mismatch != null) {
        return new OnboardingFailure(code, cause, mismatch);
      }
    }
    return new OnboardingFailure(code, cause);
  }

  private PreparedJoin importCompactInvitation(String link,
      PeerSession.ApplicationStreamHandler applicationHandler,
      Consumer<PeerSession> sessionAction, boolean retained, boolean resumed)
      throws OnboardingFailure {
    CompactBootstrapInvitation invitation;
    try {
      invitation = CompactBootstrapInvitation.fromShareLink(link);
      // A resumed join dials a host that already admitted this node, so the invitation is checked as of
      // its issue time: its signature and the host it pins still count, its expiry does not.
      if (!invitation.protocolVersion()
          .equals(ProtocolVersion.V1)
          || !invitation.verifyAt(resumed ? invitation.issuedAt() : Instant.now(),
              CompactBootstrapInvitation.DEFAULT_CLOCK_SKEW)) {
        throw new IllegalArgumentException("compact invitation is invalid or expired");
      }
      emit(OnboardingEventType.INVITE_PARSED, "");
      emit(OnboardingEventType.INVITE_VERIFIED, "");
    } catch (Exception failure) {
      throw failure(OnboardingFailureCode.INVITE_INVALID, failure);
    }
    IdentityBootstrap.Result local = loadIdentity();
    emit(local.created() ? OnboardingEventType.IDENTITY_CREATED
        : OnboardingEventType.IDENTITY_LOADED, "");
    requireAnotherHost(invitation.hostNodeId(), local);
    emit(OnboardingEventType.HOST_IDENTITY_PINNED, invitation.hostNodeId());
    OnboardingEndpoint endpoint = null;
    try {
      endpoint = OnboardingEndpoint.openClient(stunServer, retained);
      List<Candidate> candidates = endpoint.gatherCandidates("join");
      CandidateDescriptor localDescriptor = CandidateDescriptor.create(local.identity(),
          Instant.now().truncatedTo(ChronoUnit.SECONDS),
          resumed ? Instant.now().plus(CompactBootstrapInvitation.MAX_LIFETIME)
              : invitation.expiresAt(),
          candidates);
      CompactAnswer answer = CompactAnswer.create(invitation, candidates);
      emit(OnboardingEventType.LOCAL_DESCRIPTOR_CREATED, "");
      emit(OnboardingEventType.CANDIDATES_GATHERED, Integer.toString(candidates.size()));
      emit(OnboardingEventType.ANSWER_LINK, answer.shareLink());
      PreparedJoin prepared = new PreparedJoin(local.identity(), localDescriptor, invitation, answer,
          endpoint, applicationHandler, sessionAction, retained);
      if (resumed) {
        prepared.started.set(true);
      }
      return prepared;
    } catch (Exception failure) {
      closeAfterFailedOpen(endpoint);
      throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
    }
  }

  /**
   * Creates and serves one compact one-way invitation.
   *
   * <p>The method returns only after the bounded authenticated session has
   * completed. The compact invitation is emitted before the wait through the
   * normal {@link OnboardingEventType#SHARE_LINK} event.
   *
   * @param expectedPeer optional expected responder node ID
   * @throws OnboardingFailure if setup, authentication, descriptor exchange,
   *                           or bounded cleanup fails
   */
  public void hostCompact(String expectedPeer) throws OnboardingFailure {
    hostCompact(expectedPeer, null, _ -> {
    });
  }

  /**
   * Creates and serves one compact one-way invitation with callbacks.
   *
   * @param expectedPeer       optional expected responder node ID
   * @param applicationHandler optional handler for non-bootstrap application payloads
   * @param sessionAction      callback invoked after authenticated descriptor exchange
   * @throws OnboardingFailure if setup, authentication, descriptor exchange,
   *                           or bounded cleanup fails
   */
  public void hostCompact(String expectedPeer,
      PeerSession.ApplicationStreamHandler applicationHandler,
      Consumer<PeerSession> sessionAction) throws OnboardingFailure {
    try {
      try (PreparedHost prepared = createCompactInvitation(expectedPeer, applicationHandler,
          sessionAction)) {
        prepared.awaitConnection();
      }
    } catch (OnboardingFailure failure) {
      throw failure;
    }
  }

  /**
   * Opens a compact host handle and returns after the invite has been emitted.
   *
   * <p>The returned handle owns the listener until {@link PreparedHost#awaitConnection()} or
   * {@link PreparedHost#close()} is called. This is the non-blocking seam used by the local
   * control-plane owner; the terminal one-shot façade is {@link #hostCompact(String)}.
   *
   * @param expectedPeer       optional expected responder node ID
   * @param applicationHandler optional handler for non-bootstrap application payloads
   * @param sessionAction      callback invoked after authenticated descriptor exchange
   * @return open compact host handle
   * @throws OnboardingFailure if identity, listener, candidate, or compact-signing setup fails
   */
  public PreparedHost createCompactInvitation(String expectedPeer,
      PeerSession.ApplicationStreamHandler applicationHandler,
      Consumer<PeerSession> sessionAction) throws OnboardingFailure {
    return createCompactInvitation(expectedPeer, false, applicationHandler, sessionAction);
  }

  /**
   * Opens a compact host handle, and when the user chose to, first asks the home router to forward
   * the listening port so a joiner on another network reaches this side directly.
   *
   * <p>A router that does not answer or refuses is reported through
   * {@link OnboardingEventType#ROUTER_PORT_UNAVAILABLE} and the invitation is made without the
   * forward; the answer link still helps where the network allows it.
   *
   * @param expectedPeer       optional expected responder node ID
   * @param openRouterPort     whether to ask the router for a forward, which changes a setting on
   *                           the user's router until the handle closes
   * @param applicationHandler optional handler for non-bootstrap application payloads
   * @param sessionAction      callback invoked after authenticated descriptor exchange
   * @return open compact host handle
   * @throws OnboardingFailure if identity, listener, candidate, or compact-signing setup fails
   */
  public PreparedHost createCompactInvitation(String expectedPeer, boolean openRouterPort,
      PeerSession.ApplicationStreamHandler applicationHandler,
      Consumer<PeerSession> sessionAction) throws OnboardingFailure {
    return createCompactInvitation(expectedPeer, openRouterPort, applicationHandler, sessionAction,
        null, false);
  }

  /**
   * Opens a compact host handle for sessions a runtime keeps: they run under
   * {@link org.synesis.link.session.LivenessConfiguration#RETAINED}, and the listening socket stays
   * bound for as long as the handle is open. After the invitation is used, a peer that
   * {@code returningPeers} names may connect again without one, and each such session is passed to
   * {@code sessionAction} like the first, on another thread; a session whose action throws is
   * closed.
   *
   * <p>The rule is read at every connection, so it should be cheap, and a peer it stops naming is
   * refused from then on. A peer never named by it can use only the invitation.
   *
   * @param expectedPeer       optional expected responder node ID
   * @param openRouterPort     whether to ask the home router for a port forward
   * @param applicationHandler optional handler for non-bootstrap application payloads
   * @param sessionAction      callback invoked after authenticated descriptor exchange
   * @param returningPeers     who may reconnect without the invitation
   * @return open compact host handle
   * @throws OnboardingFailure if identity, listener, candidate, or compact-signing setup fails
   */
  public PreparedHost createRetainedInvitation(String expectedPeer, boolean openRouterPort,
      PeerSession.ApplicationStreamHandler applicationHandler,
      Consumer<PeerSession> sessionAction, Predicate<String> returningPeers)
      throws OnboardingFailure {
    return createCompactInvitation(expectedPeer, openRouterPort, applicationHandler, sessionAction,
        Objects.requireNonNull(returningPeers, "returning peers"), false);
  }

  /**
   * Opens a member card: a retained host whose invitation counts as used from the start, so every peer
   * {@code returningPeers} names may connect to it, again and again, and nobody can connect with the
   * capability it carries (ADR-0098 step 6, "Links"). The link it returns names this computer's key and
   * endpoints, for the members to dial.
   *
   * @param applicationHandler optional handler for non-bootstrap application payloads
   * @param sessionAction      callback invoked for each admitted session
   * @param returningPeers     who may connect; read at every connection
   * @return the open card
   * @throws OnboardingFailure if identity, listener, candidate, or compact-signing setup fails
   */
  public PreparedHost createRetainedCard(PeerSession.ApplicationStreamHandler applicationHandler,
      Consumer<PeerSession> sessionAction, Predicate<String> returningPeers) throws OnboardingFailure {
    return createCompactInvitation(null, false, applicationHandler, sessionAction,
        Objects.requireNonNull(returningPeers, "returning peers"), true);
  }

  private PreparedHost createCompactInvitation(String expectedPeer, boolean openRouterPort,
      PeerSession.ApplicationStreamHandler applicationHandler,
      Consumer<PeerSession> sessionAction, Predicate<String> returningPeers, boolean invitationUsed)
      throws OnboardingFailure {
    Objects.requireNonNull(sessionAction, "session action");
    IdentityBootstrap.Result local = loadIdentity();
    emit(local.created() ? OnboardingEventType.IDENTITY_CREATED
        : OnboardingEventType.IDENTITY_LOADED, "");
    byte[] capability = new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES];
    RANDOM.nextBytes(capability);
    Instant issued = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant expires = issued.plus(CompactBootstrapInvitation.MAX_LIFETIME);
    UUID sessionId = CompactBootstrapInvitation.sessionIdForMinimized(
        local.identity().publicKeyEncoded(), capability, issued, expires);
    OnboardingEndpoint endpoint = null;
    try {
      AtomicReference<CandidateDescriptor> localDescriptor = new AtomicReference<>();
      CompletableFuture<CandidateDescriptor> remoteDescriptor = new CompletableFuture<>();
      PeerSession.ApplicationStreamHandler bootstrapHandler = CompactDescriptorExchange.compactApplicationHandler(
          localDescriptor, remoteDescriptor, applicationHandler);
      endpoint = OnboardingEndpoint.openServer(local.identity(), expectedPeer, sessionId,
          capability, bootstrapHandler, stunServer, returningPeers,
          session -> acceptReturning(session, sessionAction), retainedReplay, invitationUsed);
      endpoint.onIncompatiblePeer(mismatch -> emit(OnboardingEventType.INCOMPATIBLE_PEER, mismatch.message()));
      emit(OnboardingEventType.SESSION_CREATED, "");
      emit(OnboardingEventType.LISTENER_READY, "");
      List<Candidate> candidates = new java.util.ArrayList<>(endpoint.gatherCandidates("host"));
      if (openRouterPort) {
        try {
          Candidate forwarded = endpoint.openRouterPort();
          candidates.add(forwarded);
          emit(OnboardingEventType.ROUTER_PORT_OPENED,
              forwarded.address().getHostAddress() + ":" + forwarded.port());
        } catch (java.io.IOException refused) {
          emit(OnboardingEventType.ROUTER_PORT_UNAVAILABLE, String.valueOf(refused.getMessage()));
        }
      }
      emit(OnboardingEventType.CANDIDATES_GATHERED, Integer.toString(candidates.size()));
      CandidateDescriptor descriptor = CandidateDescriptor.create(local.identity(), issued, expires,
          candidates);
      localDescriptor.set(descriptor);
      CompactBootstrapInvitation invitation = CompactBootstrapInvitation.createMinimized(
          local.identity(), ProtocolVersion.V1, issued, expires, capability,
          CompactBootstrapInvitation.selectEndpoints(candidates));
      emit(OnboardingEventType.DESCRIPTOR_CREATED, "");
      emit(OnboardingEventType.INVITE_CREATED, "");
      emit(OnboardingEventType.SHARE_LINK, invitation.shareLink());
      return new PreparedHost(local.identity(), expectedPeer, invitation,
          remoteDescriptor, endpoint, sessionAction);
    } catch (Exception failure) {
      closeAfterFailedOpen(endpoint);
      throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
    }
  }

  /**
   * Runs the session action for a peer that came back, off the connection's event loop (the action
   * may block on the peer), and closes a session the action rejected.
   */
  private void acceptReturning(PeerSession session, Consumer<PeerSession> sessionAction) {
    CompletableFuture.runAsync(() -> {
      try {
        emitSession(session);
        sessionAction.accept(session);
      } catch (RuntimeException rejected) {
        session.closeGracefully(SessionCloseReason.LOCAL_REQUEST);
      }
    });
  }

  /**
   * Opens a compact host handle with no application callback.
   *
   * @param expectedPeer optional expected responder node ID
   * @return open compact host handle
   * @throws OnboardingFailure if compact host setup fails
   */
  public PreparedHost createCompactInvitation(String expectedPeer) throws OnboardingFailure {
    return createCompactInvitation(expectedPeer, null, _ -> {
    });
  }

  /**
   * Imports a compact invitation and keeps the local QUIC endpoint ready for the direct attempt.
   *
   * @param link exact compact link received from the host
   * @return open join handle
   * @throws OnboardingFailure if the link, identity, transport, or candidates are invalid
   */
  public PreparedJoin importInvitation(String link) throws OnboardingFailure {
    return importInvitation(link, null, session -> {
      try {
        var work = session.requestDemoWork(new DemoWorkRequest(UUID.randomUUID(),
                DemoWorkRequest.DESCRIBE_SESSION))
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
        emit(OnboardingEventType.WORK_RESULT, work.status().toString());
      } catch (Exception failure) {
        throw new IllegalStateException("demo work failed", failure);
      }
    });
  }

  /**
   * Imports a compact invitation with callbacks.
   *
   * @param link               exact compact link received from the host
   * @param applicationHandler optional application-stream handler
   * @param sessionAction      callback invoked after control readiness
   * @return open join handle
   * @throws OnboardingFailure if the link, identity, transport, or candidates are invalid
   */
  public PreparedJoin importInvitation(String link,
      PeerSession.ApplicationStreamHandler applicationHandler,
      Consumer<PeerSession> sessionAction) throws OnboardingFailure {
    Objects.requireNonNull(sessionAction, "session action");
    Objects.requireNonNull(link, "link");
    return importCompactInvitation(link, applicationHandler, sessionAction, false, false);
  }

  /**
   * Imports a compact invitation for a session a runtime keeps: it runs under
   * {@link org.synesis.link.session.LivenessConfiguration#RETAINED}, and when it ends
   * {@link PreparedJoin#redial()} dials the host again without a new invitation.
   *
   * @param link               exact compact link received from the host
   * @param applicationHandler optional application-stream handler
   * @param sessionAction      callback invoked after control readiness, for the first session and for
   *                           each one {@code redial} makes
   * @return open join handle
   * @throws OnboardingFailure if the link, identity, transport, or candidates are invalid
   */
  public PreparedJoin importRetainedInvitation(String link,
      PeerSession.ApplicationStreamHandler applicationHandler,
      Consumer<PeerSession> sessionAction) throws OnboardingFailure {
    Objects.requireNonNull(sessionAction, "session action");
    Objects.requireNonNull(link, "link");
    return importCompactInvitation(link, applicationHandler, sessionAction, true, false);
  }

  /**
   * Restores the join handle of a session a runtime kept before it restarted, from the invitation it
   * was made with, so that {@link PreparedJoin#redial()} dials the host again with no new invitation.
   * The handle counts as connected: the host admitted this node once, and admits it again only if its
   * own rule still names it.
   *
   * @param link               the invitation link the first session was made with, expired or not
   * @param applicationHandler optional application-stream handler
   * @param sessionAction      callback invoked after control readiness, for each session {@code redial} makes
   * @return open join handle
   * @throws OnboardingFailure if the link is not an authentic invitation, or the transport cannot open
   */
  public PreparedJoin resumeRetainedJoin(String link,
      PeerSession.ApplicationStreamHandler applicationHandler,
      Consumer<PeerSession> sessionAction) throws OnboardingFailure {
    Objects.requireNonNull(sessionAction, "session action");
    Objects.requireNonNull(link, "link");
    return importCompactInvitation(link, applicationHandler, sessionAction, true, true);
  }

  /**
   * Opens the listening side of a host that restarted, on the UDP port it had, with no invitation: it
   * admits only the peers {@code returningPeers} names, as {@link #createRetainedInvitation} does after
   * its invitation is used, and nobody can connect with a capability. Each admitted session is passed
   * to {@code sessionAction}, on another thread.
   *
   * @param port               the port the host listened on before
   * @param applicationHandler optional application-stream handler
   * @param sessionAction      callback invoked for each admitted session
   * @param returningPeers     who may connect; read at every connection
   * @return the open listener
   * @throws OnboardingFailure if the identity cannot be loaded or the port cannot be bound
   */
  public ResumedHost resumeRetainedHost(int port,
      PeerSession.ApplicationStreamHandler applicationHandler,
      Consumer<PeerSession> sessionAction, Predicate<String> returningPeers)
      throws OnboardingFailure {
    Objects.requireNonNull(sessionAction, "session action");
    Objects.requireNonNull(returningPeers, "returning peers");
    IdentityBootstrap.Result local = loadIdentity();
    try {
      return new ResumedHost(OnboardingEndpoint.openResumedServer(local.identity(), port,
          applicationHandler, stunServer, returningPeers,
          session -> acceptReturning(session, sessionAction), retainedReplay));
    } catch (Exception failure) {
      throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
    }
  }

  /**
   * Loads or creates the local identity and emits its status and node ID.
   *
   * @return stable local node ID
   * @throws OnboardingFailure if identity storage is invalid or inaccessible
   */
  public String showIdentity() throws OnboardingFailure {
    try {
      IdentityBootstrap.Result result = bootstrap().loadOrCreate();
      emit(result.created() ? OnboardingEventType.IDENTITY_CREATED
          : OnboardingEventType.IDENTITY_LOADED, "");
      emit(OnboardingEventType.NODE_ID,
          result.identity()
              .nodeId());
      return result.identity()
          .nodeId();
    } catch (Exception failure) {
      throw failure(OnboardingFailureCode.IDENTITY_FAILED, failure);
    }
  }

  /**
   * Verifies an invitation and completes one authenticated join operation.
   *
   * @param link exact signed share link supplied by the user
   * @throws OnboardingFailure for invalid invitation, unusable candidates, identity mismatch, or
   *                           bounded connection failure
   */
  public void join(String link) throws OnboardingFailure {
    join(link, null, session -> {
      try {
        var work = session.requestDemoWork(new DemoWorkRequest(UUID.randomUUID(),
                DemoWorkRequest.DESCRIBE_SESSION))
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
        emit(OnboardingEventType.WORK_RESULT,
            work.status()
                .toString());
      } catch (Exception failure) {
        throw new IllegalStateException("demo work failed", failure);
      }
    });
  }

  /**
   * Joins one invitation and runs a bounded callback on the authenticated session.
   *
   * @param link               exact signed share link
   * @param applicationHandler optional bounded application-stream handler
   * @param sessionAction      callback invoked after control readiness
   * @throws OnboardingFailure if setup, callback, or cleanup fails
   */
  public void join(String link, PeerSession.ApplicationStreamHandler applicationHandler,
      Consumer<PeerSession> sessionAction) throws OnboardingFailure {
    Objects.requireNonNull(sessionAction, "session action");
    Objects.requireNonNull(link, "link");
    try (PreparedJoin prepared = importInvitation(link, applicationHandler, sessionAction)) {
      prepared.connect();
    }
  }

  /**
   * Joins one invitation with a single round of attempts, for a caller that cannot show the
   * answer link: without it the host never sends toward this side, so waiting longer cannot help.
   *
   * @param link               exact signed share link
   * @param applicationHandler optional bounded application-stream handler
   * @param sessionAction      callback invoked after control readiness
   * @throws OnboardingFailure if setup, the attempts, the callback, or cleanup fails
   */
  public void joinOnce(String link, PeerSession.ApplicationStreamHandler applicationHandler,
      Consumer<PeerSession> sessionAction) throws OnboardingFailure {
    Objects.requireNonNull(sessionAction, "session action");
    Objects.requireNonNull(link, "link");
    try (PreparedJoin prepared = importInvitation(link, applicationHandler, sessionAction)) {
      prepared.connect(false, Instant.now());
    }
  }

  private static HandshakeTranscript handshakeTranscript(NodeIdentity identity, UUID sessionId,
      byte[] capability, String hostNodeId, byte[] hostPublicKey) {
    byte[] initiatorNonce = randomBytes();
    byte[] responderNonce = randomBytes();
    return HandshakeTranscript.create(ProtocolVersion.V1, SynesisLink.ALPN, sessionId, 1, 1,
        initiatorNonce, responderNonce, capability, identity.nodeId(), identity.publicKeyEncoded(),
        hostNodeId, hostPublicKey);
  }

  /**
   * Races the candidate pairs until one authenticates the host named by the transcript or the
   * invitation expires, then runs the session step.
   *
   * <p>The race is run again rather than once because, across two routers, the first rounds are
   * expected to fail: the host's router drops this side's packets until the host has the answer
   * link and sends toward this side, and a person carries that link over.
   *
   * @param deadline            when the invitation expires; no round starts that would end after it
   * @param afterAuthentication step that runs on the verified session before the path is announced
   */
  private PeerSession connectAndRun(NodeIdentity identity, HandshakeTranscript transcript,
      List<CandidatePair> pairs, Channel udp, Instant deadline,
      PeerSession.ApplicationStreamHandler applicationHandler, Consumer<PeerSession> sessionAction,
      boolean keepAlive, SessionStep afterAuthentication, LivenessConfiguration liveness,
      Consumer<CandidatePair> onWinner)
      throws Exception {
    String hostNodeId = transcript.nodeId(HandshakeRole.RESPONDER);
    byte[] hostPublicKey = transcript.publicKeyEncoded(HandshakeRole.RESPONDER);
    DirectConnectionResult result;
    boolean waitingAnnounced = false;
    while (true) {
      long roundStart = System.nanoTime();
      try (CandidateRacer racer = new CandidateRacer(COMPACT_CONNECTION_POLICY)) {
        result = racer.race(pairs, hostNodeId,
                pair -> ClientQuicConnectionAttempt.attempt(identity, hostNodeId, hostPublicKey,
                    transcript, udp, pair, applicationHandler, liveness))
            .completion()
            .toCompletableFuture()
            .get(RACE_BOUND.toSeconds(), TimeUnit.SECONDS);
      }
      if (result.session() != null || !udp.isOpen()
          || Instant.now().plus(RACE_BOUND).isAfter(deadline)) {
        break;
      }
      if (!waitingAnnounced) {
        emit(OnboardingEventType.WAITING_FOR_HOST, "");
        waitingAnnounced = true;
      }
      // A round that fails at once, as with no route, must not turn the wait into a busy loop.
      long elapsed = System.nanoTime() - roundStart;
      TimeUnit.NANOSECONDS.sleep(Math.max(0, MIN_ROUND.toNanos() - elapsed));
    }
    if (result.session() == null) {
      throw failure(OnboardingFailureCode.CONNECTION_FAILED,
          new IllegalStateException("candidate race failed: " + result.failureCategory()
              + " diagnostics=" + result.diagnostics()));
    }
    PeerSession session = result.session();
    if (!session.hasRemotePublicKey(hostPublicKey)) {
      throw failure(OnboardingFailureCode.HOST_IDENTITY_MISMATCH, null);
    }
    afterAuthentication.run(session);
    onWinner.accept(result.winner());
    emit(OnboardingEventType.PATH_SELECTED, result.winner().identifier());
    emitSession(session);
    sessionAction.accept(session);
    if (!keepAlive) {
      session.closeGracefully(SessionCloseReason.LOCAL_REQUEST)
          .toCompletableFuture()
          .get(10, TimeUnit.SECONDS);
      emit(OnboardingEventType.SESSION_CLOSED, "");
    }
    return session;
  }

  private static void closeAfterFailedOpen(OnboardingEndpoint endpoint) {
    if (endpoint != null) {
      endpoint.close();
    }
  }

  @FunctionalInterface
  private interface SessionStep {

    void run(PeerSession session) throws Exception;
  }

  private IdentityBootstrap bootstrap() {
    return new IdentityBootstrap(profileDirectory);
  }

  /**
   * Refuses an invitation this identity created. A node cannot hold a session with itself, and a
   * join that tried ran its whole traversal and ended as an unexplained connection failure.
   */
  private void requireAnotherHost(String hostNodeId, IdentityBootstrap.Result local)
      throws OnboardingFailure {
    if (hostNodeId.equals(local.identity().nodeId())) {
      throw failure(OnboardingFailureCode.OWN_INVITATION, null);
    }
  }

  private IdentityBootstrap.Result loadIdentity() throws OnboardingFailure {
    try {
      return bootstrap().loadOrCreate();
    } catch (Exception failure) {
      throw failure(OnboardingFailureCode.IDENTITY_FAILED, failure);
    }
  }

  private void emitSession(PeerSession session) {
    emit(OnboardingEventType.PEER_CONNECTED, "");
    emit(OnboardingEventType.PEER_IDENTITY_VERIFIED, session.remoteNodeId());
    emit(OnboardingEventType.CONTROL_READY, Boolean.toString(session.isUsable()));
    emit(OnboardingEventType.LIVENESS,
        session.livenessState()
            .toString());
  }

  private void emit(OnboardingEventType type, String value) {
    events.accept(new OnboardingEvent(type, value));
  }

  /**
   * Open host-side state for one compact invitation.
   *
   * <p>The instance is single-use: it serves one authenticated session.
   */
  public final class PreparedHost implements AutoCloseable {

    private final NodeIdentity identity;
    private final String expectedPeer;
    private final CompactBootstrapInvitation compactInvitation;
    private final CompletableFuture<CandidateDescriptor> remoteDescriptor;
    private final OnboardingEndpoint endpoint;
    private final Consumer<PeerSession> sessionAction;
    private final CompletableFuture<Void> compactCompletion;
    private final AtomicBoolean closed = new AtomicBoolean();

    private PreparedHost(NodeIdentity identity, String expectedPeer,
        CompactBootstrapInvitation compactInvitation,
        CompletableFuture<CandidateDescriptor> remoteDescriptor, OnboardingEndpoint endpoint,
        Consumer<PeerSession> sessionAction) {
      this.identity = identity;
      this.expectedPeer = expectedPeer;
      this.compactInvitation = compactInvitation;
      this.remoteDescriptor = remoteDescriptor;
      this.endpoint = endpoint;
      this.sessionAction = sessionAction;
      this.compactCompletion = new CompletableFuture<>();
    }

    /**
     * Returns the exact compact invitation link.
     *
     * @return copyable signed invitation link
     */
    public String invitationLink() {
      return compactInvitation.shareLink();
    }

    /**
     * Returns the UDP port this host listens on, which a restarted host binds again.
     *
     * @return local UDP port
     */
    public int listeningPort() {
      return endpoint.port();
    }

    /**
     * Waits for the session and closes it after the callback.
     *
     * @throws OnboardingFailure if the session or descriptor exchange fails
     */
    public void awaitConnection() throws OnboardingFailure {
      awaitConnection(false);
    }

    /**
     * Waits for the session while retaining the authenticated endpoint.
     *
     * @return authenticated control-ready session
     * @throws OnboardingFailure if the session or descriptor exchange fails
     */
    public PeerSession awaitConnectionAndKeepAlive() throws OnboardingFailure {
      return awaitConnection(true);
    }

    /**
     * Returns the completion stage for the host wait, which completes after descriptor exchange
     * and the configured session callback have finished. A failed wait completes it with the
     * {@link OnboardingFailure} the wait threw, and {@link #close()} with a cancellation.
     *
     * @return host wait completion
     */
    public CompletionStage<Void> completion() {
      return compactCompletion;
    }

    private PeerSession awaitConnection(boolean keepAlive) throws OnboardingFailure {
      try {
        PeerSession session;
        try {
          session = endpoint.established().get(
              CompactBootstrapInvitation.MAX_LIFETIME.toSeconds() + 30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException nobody) {
          throw failure(OnboardingFailureCode.HOST_TIMEOUT, nobody);
        }
        // A peer that authenticated and then sent no descriptor did connect: that is a failed connection, not a host
        // nobody joined.
        remoteDescriptor.get(12, TimeUnit.SECONDS);
        emit(OnboardingEventType.DESCRIPTORS_EXCHANGED, "");
        emitSession(session);
        sessionAction.accept(session);
        if (!keepAlive) {
          session.terminalCompletion().toCompletableFuture().get(30, TimeUnit.SECONDS);
          emit(OnboardingEventType.SESSION_CLOSED, "");
        }
        compactCompletion.complete(null);
        return session;
      } catch (OnboardingFailure failure) {
        compactCompletion.completeExceptionally(failure);
        throw failure;
      } catch (Exception cause) {
        OnboardingFailure failure = failure(OnboardingFailureCode.CONNECTION_FAILED, cause);
        compactCompletion.completeExceptionally(failure);
        throw failure;
      }
    }

    /**
     * Accepts the joiner's answer link and starts sending toward the addresses it names, so this
     * side's router lets the joiner's connection attempts through. It returns at once; the
     * session still arrives through {@link #awaitConnection()}. Pasting the answer again starts the
     * sending again.
     *
     * @param link exact answer link the joiner's user sent back
     * @throws OnboardingFailure {@code ANSWER_INVALID} if the link is malformed or answers another
     *                           invitation
     */
    public void importAnswer(String link) throws OnboardingFailure {
      CompactAnswer answer;
      try {
        answer = CompactAnswer.fromShareLink(link);
      } catch (java.io.IOException invalid) {
        throw failure(OnboardingFailureCode.ANSWER_INVALID, invalid);
      }
      if (!answer.sessionId().equals(compactInvitation.sessionId())) {
        throw failure(OnboardingFailureCode.ANSWER_INVALID,
            new IllegalArgumentException("answer belongs to another invitation"));
      }
      if (closed.get()) {
        throw failure(OnboardingFailureCode.ANSWER_INVALID,
            new IllegalStateException("host handle is closed"));
      }
      endpoint.punch(answer.endpoints());
      emit(OnboardingEventType.ANSWER_VERIFIED, "");
    }

    /**
     * Releases the bound endpoint and host admission state.
     */
    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        compactCompletion.completeExceptionally(
            new java.util.concurrent.CancellationException("host handle closed"));
        endpoint.close();
      }
    }
  }

  /**
   * The listening side of a host that restarted, owned until {@link #close()}.
   */
  public static final class ResumedHost implements AutoCloseable {

    private final OnboardingEndpoint endpoint;

    private ResumedHost(OnboardingEndpoint endpoint) {
      this.endpoint = endpoint;
    }

    /**
     * Returns the UDP port this listener is bound to.
     *
     * @return local UDP port
     */
    public int listeningPort() {
      return endpoint.port();
    }

    @Override
    public void close() {
      endpoint.close();
    }
  }

  /**
   * Opens join-side state for one invitation.
   *
   * <p>The instance is single-use: it keeps the local endpoint ready for the direct race, and
   * {@link #connect()} starts that race once.
   */
  public final class PreparedJoin implements AutoCloseable {

    private final NodeIdentity identity;
    private final CandidateDescriptor localDescriptor;
    private final CompactBootstrapInvitation compactInvitation;
    private final CompactAnswer answer;
    private final OnboardingEndpoint endpoint;
    private final PeerSession.ApplicationStreamHandler applicationHandler;
    private final Consumer<PeerSession> sessionAction;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final boolean retained;
    /** The host endpoint the last session was made over; redialed first. */
    private volatile Candidate lastRemote;

    private PreparedJoin(NodeIdentity identity, CandidateDescriptor localDescriptor,
        CompactBootstrapInvitation compactInvitation, CompactAnswer answer,
        OnboardingEndpoint endpoint, PeerSession.ApplicationStreamHandler applicationHandler,
        Consumer<PeerSession> sessionAction, boolean retained) {
      this.retained = retained;
      this.identity = identity;
      this.answer = answer;
      this.localDescriptor = localDescriptor;
      this.compactInvitation = compactInvitation;
      this.endpoint = endpoint;
      this.applicationHandler = applicationHandler;
      this.sessionAction = sessionAction;
    }

    /**
     * Returns the answer link for the host's user. It is needed only when the two sides are on
     * different networks and the host has to send toward this side before either can connect.
     *
     * @return copyable answer link
     */
    public String answerLink() {
      return answer.shareLink();
    }

    /**
     * Returns the node ID of the host this handle dials.
     *
     * @return the host's node ID
     */
    public String hostNodeId() {
      return compactInvitation.hostNodeId();
    }

    /**
     * Dials the host until one authenticated session is made or the invitation expires, runs the
     * session callback, and closes the session.
     *
     * @throws OnboardingFailure if this handle was already used, no candidate is usable, or no
     *                           session is made before the invitation expires
     */
    public void connect() throws OnboardingFailure {
      connect(false, compactInvitation.expiresAt());
    }

    /**
     * Dials the host like {@link #connect()} and returns after authentication while retaining the
     * live Link endpoint and session for an owning runtime.
     *
     * @return authenticated control-ready session
     * @throws OnboardingFailure if this handle was already used, no candidate is usable, or no
     *                           session is made before the invitation expires
     */
    public PeerSession connectAndKeepAlive() throws OnboardingFailure {
      return connect(true, compactInvitation.expiresAt());
    }

    private PeerSession connect(boolean keepAlive, Instant deadline) throws OnboardingFailure {
      if (!started.compareAndSet(false, true)) {
        throw failure(OnboardingFailureCode.ANSWER_INVALID,
            new IllegalStateException("answer has already been used"));
      }
      try {
        List<CandidatePair> pairs = CandidatePairs.onePerRemote(localDescriptor.candidates(),
            compactInvitation.endpoints());
        if (pairs.isEmpty()) {
          throw failure(OnboardingFailureCode.NO_USABLE_CANDIDATE, null);
        }
        HandshakeTranscript transcript = handshakeTranscript(identity,
            compactInvitation.sessionId(), compactInvitation.capability(),
            compactInvitation.hostNodeId(), compactInvitation.publicKeyEncoded());
        emit(OnboardingEventType.TRAVERSAL_STARTED, "");
        return connectAndRun(identity, transcript, pairs, endpoint.udp(),
            deadline, applicationHandler, sessionAction, keepAlive,
            session -> {
              CompactDescriptorExchange.exchangeDescriptor(session, localDescriptor,
                  compactInvitation.hostNodeId(), compactInvitation.publicKeyEncoded(),
                  compactInvitation.expiresAt());
              emit(OnboardingEventType.DESCRIPTORS_EXCHANGED, "");
            }, liveness(), winner -> lastRemote = winner.remote());
      } catch (OnboardingFailure failure) {
        throw failure;
      } catch (Exception failure) {
        throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
      }
    }

    private LivenessConfiguration liveness() {
      return retained ? LivenessConfiguration.RETAINED : LivenessConfiguration.DEFAULT;
    }

    /**
     * Dials the host again after the session this handle made has ended, with no new invitation, and
     * keeps the new session. One round of attempts is made, at the endpoint the last session was made
     * over first and then the invitation's others; the caller decides when to try again and when to
     * stop.
     *
     * <p>The dial carries no capability: the invitation was used up by the first session. The host
     * admits it only if its own rule names this node (see
     * {@link Onboarding#createRetainedInvitation}), and it authenticates both identities as for the first
     * session, with a fresh session ID and fresh nonces, so a recorded dial cannot be replayed.
     * The session action runs as for the first session, and a session it rejects is closed.
     *
     * @return the new authenticated, control-ready session
     * @throws OnboardingFailure if this handle never connected or is closed, or no endpoint answers
     *                           or admits this node
     */
    public PeerSession redial() throws OnboardingFailure {
      if (!retained || !started.get() || closed.get()) {
        throw failure(OnboardingFailureCode.CONNECTION_FAILED,
            new IllegalStateException("redial needs an open, retained handle that connected"));
      }
      try {
        List<Candidate> remotes = new java.util.ArrayList<>(compactInvitation.endpoints());
        Candidate last = lastRemote;
        if (last != null && remotes.remove(last)) {
          remotes.add(0, last);
        }
        List<CandidatePair> pairs = CandidatePairs.onePerRemote(localDescriptor.candidates(),
            remotes);
        if (pairs.isEmpty()) {
          throw failure(OnboardingFailureCode.NO_USABLE_CANDIDATE, null);
        }
        HandshakeTranscript transcript = handshakeTranscript(identity, UUID.randomUUID(),
            new byte[0], compactInvitation.hostNodeId(), compactInvitation.publicKeyEncoded());
        emit(OnboardingEventType.TRAVERSAL_STARTED, "");
        return connectAndRun(identity, transcript, pairs, endpoint.udp(), Instant.now(),
            applicationHandler, session -> {
              try {
                sessionAction.accept(session);
              } catch (RuntimeException rejected) {
                session.closeGracefully(SessionCloseReason.LOCAL_REQUEST);
                throw rejected;
              }
            }, true, session -> {
            }, liveness(), winner -> lastRemote = winner.remote());
      } catch (OnboardingFailure failure) {
        throw failure;
      } catch (Exception failure) {
        throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
      }
    }

    /**
     * Releases the bound endpoint and candidate resources.
     */
    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        endpoint.close();
      }
    }
  }
}
