package org.synesis.link.onboarding;

import io.netty.channel.Channel;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.synesis.link.SynesisLink;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidatePair;
import org.synesis.link.candidate.CandidatePairs;
import org.synesis.link.candidate.CandidateRacer;
import org.synesis.link.candidate.ConnectionPolicy;
import org.synesis.link.candidate.DirectConnectionResult;
import org.synesis.link.candidate.TraversalCoordinator;
import org.synesis.link.demo.DemoWorkRequest;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.CompactBootstrapInvitation;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.protocol.SessionInvitation;
import org.synesis.link.protocol.TraversalAnswer;
import org.synesis.link.protocol.TraversalInvitation;
import org.synesis.link.protocol.TraversalOffer;
import org.synesis.link.session.HandshakeRole;
import org.synesis.link.session.HandshakeTranscript;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.SessionCloseReason;
import org.synesis.link.transport.quic.NettyTraversalExchange;

/**
 * Link-owned synchronous onboarding faÃ§ade.
 *
 * <p>This type owns the existing identity, invitation, candidate, Netty,
 * handshake, admission, liveness, work, and cleanup lifecycle. It emits facts to the supplied
 * listener and never writes terminal output or maps process exit codes. Each operation is bounded
 * and cleans up all resources before it returns or throws.
 *
 * <p>The type is thread-confined: one operation may be active at a time, and
 * the listener is called synchronously on the operation thread.
 *
 * @since 1.0
 */
public final class Onboarding {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int NONCE_BYTES = 16;
  private static final ConnectionPolicy DIRECT_CONNECTION_POLICY = new ConnectionPolicy(8, 8, 2,
      Duration.ofMillis(100), Duration.ofSeconds(10), Duration.ofSeconds(20), 16);
  private final Path profileDirectory;
  private final Consumer<OnboardingEvent> events;
  private final InetSocketAddress stunServer;

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

  private static byte[] randomBytes() {
    byte[] value = new byte[NONCE_BYTES];
    RANDOM.nextBytes(value);
    return value;
  }

  private static byte[] randomTraversalBytes() {
    byte[] value = new byte[TraversalOffer.NONCE_BYTES];
    RANDOM.nextBytes(value);
    return value;
  }

  private static OnboardingFailure failure(OnboardingFailureCode code, Throwable cause) {
    return new OnboardingFailure(code, cause);
  }

  private static boolean isCompactInvitationLink(String link) {
    return CompactBootstrapInvitation.isShareLink(link);
  }

  /**
   * Opens a host session and creates a human-mediated SLO1 link.
   *
   * <p>The returned handle owns the bound UDP channel and must remain open
   * while the link is copied and while the answer is imported.
   *
   * @param expectedPeer optional expected responder node ID
   * @return open host handle
   * @throws OnboardingFailure if identity, transport, or candidate setup fails
   */
  public PreparedHost createInvitation(String expectedPeer) throws OnboardingFailure {
    return createInvitation(expectedPeer, null, _ -> {
    });
  }

  /**
   * Opens a host session and creates a human-mediated SLO1 link with callbacks.
   *
   * @param expectedPeer       optional expected responder node ID
   * @param applicationHandler optional application-stream handler
   * @param sessionAction      callback invoked after control readiness
   * @return open host handle
   * @throws OnboardingFailure if identity, transport, or candidate setup fails
   */
  public PreparedHost createInvitation(String expectedPeer,
      PeerSession.ApplicationStreamHandler applicationHandler,
      Consumer<PeerSession> sessionAction) throws OnboardingFailure {
    Objects.requireNonNull(sessionAction, "session action");
    IdentityBootstrap.Result local = loadIdentity();
    emit(local.created() ? OnboardingEventType.IDENTITY_CREATED
        : OnboardingEventType.IDENTITY_LOADED, "");
    UUID sessionId = UUID.randomUUID();
    byte[] capability = new byte[SessionInvitation.CAPABILITY_BYTES];
    RANDOM.nextBytes(capability);
    OnboardingEndpoint endpoint = null;
    try {
      endpoint = OnboardingEndpoint.openServer(local.identity(), expectedPeer, sessionId,
          capability, applicationHandler, stunServer);
      emit(OnboardingEventType.SESSION_CREATED, "");
      emit(OnboardingEventType.LISTENER_READY, "");
      List<Candidate> candidates = endpoint.gatherCandidates("host");
      emit(OnboardingEventType.CANDIDATES_GATHERED, Integer.toString(candidates.size()));
      Instant issued = Instant.now().truncatedTo(ChronoUnit.SECONDS);
      Instant expires = issued.plus(SessionInvitation.DEFAULT_LIFETIME);
      CandidateDescriptor descriptor = CandidateDescriptor.create(local.identity(), issued, expires,
          candidates);
      SessionInvitation invitation = SessionInvitation.create(local.identity(), sessionId,
          ProtocolVersion.V1,
          issued, expires, capability, descriptor);
      TraversalOffer offer = TraversalOffer.create(local.identity(), expectedPeer, sessionId,
          invitation.digest(), issued, expires, randomTraversalBytes(), descriptor);
      TraversalInvitation link = TraversalInvitation.create(invitation, offer);
      emit(OnboardingEventType.DESCRIPTOR_CREATED, "");
      emit(OnboardingEventType.INVITE_CREATED, "");
      emit(OnboardingEventType.SHARE_LINK, link.shareLink());
      return new PreparedHost(local.identity(), expectedPeer, invitation, offer, endpoint,
          sessionAction);
    } catch (Exception failure) {
      closeAfterFailedOpen(endpoint);
      throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
    }
  }

  private PreparedJoin importCompactInvitation(String link,
      PeerSession.ApplicationStreamHandler applicationHandler,
      Consumer<PeerSession> sessionAction) throws OnboardingFailure {
    CompactBootstrapInvitation invitation;
    try {
      invitation = CompactBootstrapInvitation.fromShareLink(link);
      if (!invitation.protocolVersion()
          .equals(ProtocolVersion.V1)
          || !invitation.verifyAt(Instant.now(), CompactBootstrapInvitation.DEFAULT_CLOCK_SKEW)) {
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
    emit(OnboardingEventType.HOST_IDENTITY_PINNED, invitation.hostNodeId());
    OnboardingEndpoint endpoint = null;
    try {
      endpoint = OnboardingEndpoint.openClient(stunServer);
      List<Candidate> candidates = endpoint.gatherCandidates("join");
      CandidateDescriptor localDescriptor = CandidateDescriptor.create(local.identity(),
          Instant.now().truncatedTo(ChronoUnit.SECONDS), invitation.expiresAt(), candidates);
      emit(OnboardingEventType.LOCAL_DESCRIPTOR_CREATED, "");
      emit(OnboardingEventType.CANDIDATES_GATHERED, Integer.toString(candidates.size()));
      return new PreparedJoin(local.identity(), null, null, null, null, localDescriptor,
          invitation, endpoint, applicationHandler, sessionAction);
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
          capability, bootstrapHandler, stunServer);
      emit(OnboardingEventType.SESSION_CREATED, "");
      emit(OnboardingEventType.LISTENER_READY, "");
      List<Candidate> candidates = endpoint.gatherCandidates("host");
      emit(OnboardingEventType.CANDIDATES_GATHERED, Integer.toString(candidates.size()));
      Candidate hostEndpoint = CompactBootstrapInvitation.selectEndpoint(candidates);
      CandidateDescriptor descriptor = CandidateDescriptor.create(local.identity(), issued, expires,
          candidates);
      localDescriptor.set(descriptor);
      CompactBootstrapInvitation invitation = CompactBootstrapInvitation.createMinimized(
          local.identity(), ProtocolVersion.V1, issued, expires, capability, hostEndpoint);
      emit(OnboardingEventType.DESCRIPTOR_CREATED, "");
      emit(OnboardingEventType.INVITE_CREATED, "");
      emit(OnboardingEventType.SHARE_LINK, invitation.shareLink());
      return new PreparedHost(local.identity(), expectedPeer, null, null, invitation,
          remoteDescriptor, endpoint, sessionAction);
    } catch (Exception failure) {
      closeAfterFailedOpen(endpoint);
      throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
    }
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
   * Imports a compact one-way or legacy human-mediated invitation and keeps the local QUIC
   * endpoint ready for the direct attempt.
   *
   * @param link exact compact or SLO1 link received from the host
   * @return open join handle; compact handles do not contain an answer link
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
   * Imports a compact or human-mediated invitation with callbacks. Legacy SLO1 input creates an
   * SLA2 answer; compact input keeps the signed endpoint hint and uses one-way contact.
   *
   * @param link               exact compact or SLO1 link received from the host
   * @param applicationHandler optional application-stream handler
   * @param sessionAction      callback invoked after control readiness
   * @return open join handle containing the answer link
   * @throws OnboardingFailure if the link, identity, transport, or candidates are invalid
   */
  public PreparedJoin importInvitation(String link,
      PeerSession.ApplicationStreamHandler applicationHandler,
      Consumer<PeerSession> sessionAction) throws OnboardingFailure {
    Objects.requireNonNull(sessionAction, "session action");
    Objects.requireNonNull(link, "link");
    if (isCompactInvitationLink(link)) {
      return importCompactInvitation(link, applicationHandler, sessionAction);
    }
    SessionInvitation invitation;
    TraversalOffer offer;
    try {
      TraversalInvitation mediated = TraversalInvitation.fromShareLink(link);
      IdentityBootstrap.Result local = loadIdentity();
      emit(local.created() ? OnboardingEventType.IDENTITY_CREATED
          : OnboardingEventType.IDENTITY_LOADED, "");
      if (!mediated.verifyAt(Instant.now(), CandidateDescriptor.DEFAULT_CLOCK_SKEW,
          local.identity().nodeId())) {
        throw new IllegalArgumentException("SLO1 is not valid for this identity");
      }
      invitation = mediated.invitation();
      offer = mediated.offer();
      CandidateDescriptor host = CandidateDescriptor.decode(invitation.descriptorEncoded());
      emit(OnboardingEventType.INVITE_PARSED, "");
      emit(OnboardingEventType.INVITE_VERIFIED, "");
      emit(OnboardingEventType.HOST_IDENTITY_PINNED, host.nodeId());
      OnboardingEndpoint endpoint = null;
      try {
        endpoint = OnboardingEndpoint.openClient(stunServer);
        List<Candidate> candidates = endpoint.gatherCandidates("join");
        CandidateDescriptor localDescriptor = CandidateDescriptor.create(local.identity(),
            Instant.now().truncatedTo(ChronoUnit.SECONDS),
            invitation.expiresAt(), candidates);
        TraversalAnswer answer = TraversalAnswer.create(local.identity(), offer, localDescriptor,
            randomTraversalBytes());
        emit(OnboardingEventType.LOCAL_DESCRIPTOR_CREATED, "");
        emit(OnboardingEventType.CANDIDATES_GATHERED, Integer.toString(candidates.size()));
        emit(OnboardingEventType.ANSWER_LINK, answer.shareLink());
        return new PreparedJoin(local.identity(), invitation, offer, answer, host, localDescriptor,
            endpoint, applicationHandler, sessionAction);
      } catch (Exception failure) {
        closeAfterFailedOpen(endpoint);
        throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
      }
    } catch (OnboardingFailure failure) {
      throw failure;
    } catch (Exception failure) {
      throw failure(OnboardingFailureCode.INVITE_INVALID, failure);
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
   * Hosts one signed invitation and waits for the authenticated peer.
   *
   * @param expectedPeer optional expected peer node ID, or {@code null}
   * @throws OnboardingFailure when identity, listener, candidate, or session setup fails or the
   *                           bounded host wait expires
   */
  public void host(String expectedPeer) throws OnboardingFailure {
    host(expectedPeer, null, _ -> {
    });
  }

  /**
   * Hosts one invitation and runs a bounded callback on the authenticated session.
   *
   * @param expectedPeer       optional expected peer node ID, or {@code null}
   * @param applicationHandler optional bounded application-stream handler
   * @param sessionAction      callback invoked after control readiness
   * @throws OnboardingFailure if setup, callback, or cleanup fails
   */
  public void host(String expectedPeer, PeerSession.ApplicationStreamHandler applicationHandler,
      Consumer<PeerSession> sessionAction) throws OnboardingFailure {
    Objects.requireNonNull(sessionAction, "session action");
    IdentityBootstrap.Result local = loadIdentity();
    emit(local.created() ? OnboardingEventType.IDENTITY_CREATED
        : OnboardingEventType.IDENTITY_LOADED, "");
    UUID sessionId = UUID.randomUUID();
    byte[] capability = new byte[SessionInvitation.CAPABILITY_BYTES];
    RANDOM.nextBytes(capability);
    try (OnboardingEndpoint endpoint = OnboardingEndpoint.openServer(local.identity(),
        expectedPeer, sessionId, capability, applicationHandler, stunServer)) {
      emit(OnboardingEventType.SESSION_CREATED, "");
      emit(OnboardingEventType.LISTENER_READY, "");
      List<Candidate> candidates = endpoint.gatherCandidates("host");
      emit(OnboardingEventType.CANDIDATES_GATHERED, Integer.toString(candidates.size()));
      Instant issued = Instant.now().truncatedTo(ChronoUnit.SECONDS);
      CandidateDescriptor descriptor = CandidateDescriptor.create(local.identity(), issued,
          issued.plus(SessionInvitation.DEFAULT_LIFETIME), candidates);
      SessionInvitation invitation = SessionInvitation.create(local.identity(), sessionId,
          ProtocolVersion.V1,
          descriptor.issuedAt(), descriptor.expiresAt(), capability, descriptor);
      emit(OnboardingEventType.DESCRIPTOR_CREATED, "");
      emit(OnboardingEventType.INVITE_CREATED, "");
      emit(OnboardingEventType.SHARE_LINK, invitation.shareLink());
      byte[] invitationDigest = invitation.digest();
      NettyTraversalExchange traversal = new NettyTraversalExchange(endpoint.udp(),
          Duration.ofSeconds(10), offer -> {
            try {
              if (!sessionId.equals(offer.sessionId())
                  || !Arrays.equals(invitationDigest, offer.invitationDigest())
                  || (expectedPeer != null && !expectedPeer.equals(offer.initiatorNodeId()))
                  || !offer.verifyAt(Instant.now(), CandidateDescriptor.DEFAULT_CLOCK_SKEW,
                  local.identity().nodeId())) {
                return null;
              }
              return TraversalAnswer.create(local.identity(), offer, descriptor,
                  randomTraversalBytes());
            } catch (Exception invalidOffer) {
              return null;
            }
          });
      try {
        PeerSession session;
        try {
          session = endpoint.established().get(SessionInvitation.DEFAULT_LIFETIME.toSeconds() + 30,
              TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
          throw failure(OnboardingFailureCode.HOST_TIMEOUT, timeout);
        }
        emitSession(session);
        sessionAction.accept(session);
        session.terminalCompletion()
            .toCompletableFuture()
            .get(30, TimeUnit.SECONDS);
        emit(OnboardingEventType.SESSION_CLOSED, "");
      } finally {
        traversal.close();
      }
    } catch (OnboardingFailure failure) {
      throw failure;
    } catch (java.util.concurrent.TimeoutException timeout) {
      throw failure(OnboardingFailureCode.HOST_TIMEOUT, timeout);
    } catch (Exception failure) {
      throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
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
    if (isCompactInvitationLink(link)) {
      try (PreparedJoin prepared = importInvitation(link, applicationHandler, sessionAction)) {
        prepared.connect();
      }
      return;
    }
    SessionInvitation invitation;
    try {
      invitation = SessionInvitation.fromShareLink(link);
      emit(OnboardingEventType.INVITE_PARSED, "");
      if (!invitation.protocolVersion()
          .equals(ProtocolVersion.V1)
          || !invitation.verifyAt(Instant.now(), CandidateDescriptor.DEFAULT_CLOCK_SKEW)) {
        throw new IllegalArgumentException("invalid invitation");
      }
      emit(OnboardingEventType.INVITE_VERIFIED, "");
    } catch (Exception failure) {
      throw failure(OnboardingFailureCode.INVITE_INVALID, failure);
    }
    CandidateDescriptor host;
    try {
      host = CandidateDescriptor.decode(invitation.descriptorEncoded());
    } catch (Exception failure) {
      throw failure(OnboardingFailureCode.INVITE_INVALID, failure);
    }
    IdentityBootstrap.Result local = loadIdentity();
    emit(local.created() ? OnboardingEventType.IDENTITY_CREATED
        : OnboardingEventType.IDENTITY_LOADED, "");
    emit(OnboardingEventType.HOST_IDENTITY_PINNED, host.nodeId());
    try (OnboardingEndpoint endpoint = OnboardingEndpoint.openClient(stunServer)) {
      List<Candidate> localCandidates = endpoint.gatherCandidates("join");
      CandidateDescriptor localDescriptor = CandidateDescriptor.create(local.identity(),
          Instant.now().truncatedTo(ChronoUnit.SECONDS),
          invitation.expiresAt(), localCandidates);
      CandidateDescriptor remoteDescriptor = host;
      TraversalCoordinator coordinator = null;
      NettyTraversalExchange traversal = new NettyTraversalExchange(endpoint.udp(),
          Duration.ofSeconds(10), null);
      try {
        try {
          TraversalOffer offer = TraversalOffer.create(local.identity(), host.nodeId(),
              invitation.sessionId(),
              invitation.digest(), localDescriptor.issuedAt(), localDescriptor.expiresAt(),
              randomTraversalBytes(), localDescriptor);
          TraversalAnswer answer = traversal.sendOffer(offer, host.candidates())
              .toCompletableFuture()
              .get(12, TimeUnit.SECONDS);
          coordinator = new TraversalCoordinator(local.identity(), host.nodeId(),
              invitation.sessionId(), 8);
          coordinator.accept(offer, answer, Instant.now(), CandidateDescriptor.DEFAULT_CLOCK_SKEW);
          remoteDescriptor = CandidateDescriptor.decode(answer.descriptorEncoded());
        } catch (java.util.concurrent.TimeoutException timeout) {
          // Existing invitation candidates remain a compatible fallback.
        } catch (java.util.concurrent.ExecutionException failure) {
          if (!(failure.getCause() instanceof java.util.concurrent.TimeoutException)) {
            throw failure;
          }
        }
        emit(OnboardingEventType.LOCAL_DESCRIPTOR_CREATED, "");
        emit(OnboardingEventType.CANDIDATES_GATHERED, Integer.toString(localCandidates.size()));
        List<CandidatePair> pairs = coordinator == null
            ? CandidatePairs.generate(localCandidates, remoteDescriptor.candidates(), 8)
            : coordinator.plan(
                CandidatePairs.generate(localCandidates, remoteDescriptor.candidates(), 8))
              .stream()
              .map(TraversalCoordinator.Attempt::pair)
              .toList();
        if (pairs.isEmpty()) {
          throw failure(OnboardingFailureCode.NO_USABLE_CANDIDATE, null);
        }
        HandshakeTranscript transcript = handshakeTranscript(local.identity(),
            invitation.sessionId(), invitation.capability(), host.nodeId(),
            host.publicKeyEncoded());
        connectAndRun(local.identity(), transcript, pairs, endpoint.udp(), applicationHandler,
            sessionAction, false, _ -> {
            });
      } finally {
        traversal.close();
      }
    } catch (OnboardingFailure failure) {
      throw failure;
    } catch (Exception failure) {
      throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
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
   * Races the candidate pairs, authenticates the host named by the transcript, and runs the
   * session step shared by every join flow.
   *
   * @param describeRaceFailure whether a race with no session carries its diagnostics as the cause
   *                            of the failure; the compact flow does, the legacy flows do not
   * @param afterAuthentication step that runs on the verified session before the path is announced
   */
  private PeerSession connectAndRun(NodeIdentity identity, HandshakeTranscript transcript,
      List<CandidatePair> pairs, Channel udp,
      PeerSession.ApplicationStreamHandler applicationHandler, Consumer<PeerSession> sessionAction,
      boolean keepAlive, SessionStep afterAuthentication)
      throws Exception {
    String hostNodeId = transcript.nodeId(HandshakeRole.RESPONDER);
    byte[] hostPublicKey = transcript.publicKeyEncoded(HandshakeRole.RESPONDER);
    try (CandidateRacer racer = new CandidateRacer(DIRECT_CONNECTION_POLICY)) {
      DirectConnectionResult result = racer.race(pairs, hostNodeId,
              pair -> ClientQuicConnectionAttempt.attempt(identity, hostNodeId, hostPublicKey,
                  transcript, udp, pair, applicationHandler))
          .completion()
          .toCompletableFuture()
          .get(25, TimeUnit.SECONDS);
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
      emit(OnboardingEventType.PATH_SELECTED, pairs.getFirst().identifier());
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
   * Open host-side state for one human-mediated invitation.
   *
   * <p>The instance is single-use: one valid answer may be imported before
   * the authenticated session is awaited.
   */
  public final class PreparedHost implements AutoCloseable {

    private final NodeIdentity identity;
    private final String expectedPeer;
    private final SessionInvitation invitation;
    private final TraversalOffer offer;
    private final CompactBootstrapInvitation compactInvitation;
    private final CompletableFuture<CandidateDescriptor> remoteDescriptor;
    private final OnboardingEndpoint endpoint;
    private final Consumer<PeerSession> sessionAction;
    private final CompletableFuture<Void> compactCompletion;
    private final AtomicBoolean answerImported = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private PreparedHost(NodeIdentity identity, String expectedPeer, SessionInvitation invitation,
        TraversalOffer offer, OnboardingEndpoint endpoint, Consumer<PeerSession> sessionAction) {
      this(identity, expectedPeer, invitation, offer, null, null, endpoint, sessionAction);
    }

    private PreparedHost(NodeIdentity identity, String expectedPeer, SessionInvitation invitation,
        TraversalOffer offer, CompactBootstrapInvitation compactInvitation,
        CompletableFuture<CandidateDescriptor> remoteDescriptor, OnboardingEndpoint endpoint,
        Consumer<PeerSession> sessionAction) {
      this.identity = identity;
      this.expectedPeer = expectedPeer;
      this.invitation = invitation;
      this.offer = offer;
      this.compactInvitation = compactInvitation;
      this.remoteDescriptor = remoteDescriptor;
      this.endpoint = endpoint;
      this.sessionAction = sessionAction;
      this.compactCompletion = compactInvitation == null ? null : new CompletableFuture<>();
    }

    /**
     * Returns the exact compact one-way link, or the legacy SLO1 link for a mediated handle.
     *
     * @return copyable signed invitation link
     */
    public String invitationLink() {
      return compactInvitation == null
          ? TraversalInvitation.create(invitation, offer).shareLink()
          : compactInvitation.shareLink();
    }

    /**
     * Waits for a compact one-way session and closes it after the callback.
     *
     * @throws OnboardingFailure if this is a legacy handle or the session/exchange fails
     */
    public void awaitConnection() throws OnboardingFailure {
      awaitConnection(false);
    }

    /**
     * Waits for a compact one-way session while retaining the authenticated endpoint.
     *
     * @return authenticated control-ready session
     * @throws OnboardingFailure if this is a legacy handle or the session/exchange fails
     */
    public PeerSession awaitConnectionAndKeepAlive() throws OnboardingFailure {
      return awaitConnection(true);
    }

    /**
     * Returns the completion stage for a compact host wait.
     *
     * <p>Legacy host handles return an already-completed stage because their lifecycle is driven
     * explicitly by {@link #importAnswer(String)}. Compact stages complete after descriptor
     * exchange and the configured session callback have finished.
     *
     * @return compact wait completion, or an already-completed stage for a legacy handle
     */
    public CompletionStage<Void> completion() {
      return compactCompletion == null
          ? CompletableFuture.completedFuture(null)
          : compactCompletion;
    }

    private PeerSession awaitConnection(boolean keepAlive) throws OnboardingFailure {
      if (compactInvitation == null || remoteDescriptor == null) {
        throw failure(OnboardingFailureCode.ANSWER_INVALID,
            new IllegalStateException("host handle requires a legacy answer"));
      }
      try {
        PeerSession session = endpoint.established().get(
            CompactBootstrapInvitation.MAX_LIFETIME.toSeconds() + 30, TimeUnit.SECONDS);
        remoteDescriptor.get(12, TimeUnit.SECONDS);
        emit(OnboardingEventType.DESCRIPTORS_EXCHANGED, "");
        emitSession(session);
        sessionAction.accept(session);
        if (!keepAlive) {
          session.terminalCompletion().toCompletableFuture().get(30, TimeUnit.SECONDS);
          emit(OnboardingEventType.SESSION_CLOSED, "");
        }
        if (compactCompletion != null) {
          compactCompletion.complete(null);
        }
        return session;
      } catch (java.util.concurrent.TimeoutException timeout) {
        if (compactCompletion != null) {
          compactCompletion.completeExceptionally(timeout);
        }
        throw failure(OnboardingFailureCode.HOST_TIMEOUT, timeout);
      } catch (Exception failure) {
        if (compactCompletion != null) {
          compactCompletion.completeExceptionally(failure);
        }
        throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
      }
    }

    /**
     * Imports one exact SLA2 answer and waits for the authenticated session.
     *
     * @param link exact answer link returned by the other user
     * @throws OnboardingFailure if the answer is invalid, reused, or the session fails
     */
    public void importAnswer(String link) throws OnboardingFailure {
      importAnswer(link, false);
    }

    /**
     * Imports one exact answer and returns after authentication while retaining the live Link
     * endpoint and session for an owning runtime.
     *
     * @param link exact answer link returned by the other user
     * @return authenticated control-ready session
     * @throws OnboardingFailure if the answer is invalid, reused, or the session fails
     */
    public PeerSession importAnswerAndKeepAlive(String link) throws OnboardingFailure {
      return importAnswer(link, true);
    }

    private PeerSession importAnswer(String link, boolean keepAlive) throws OnboardingFailure {
      try {
        TraversalAnswer answer = TraversalAnswer.fromShareLink(link);
        if (!answer.verifyAt(Instant.now(), TraversalAnswer.DEFAULT_CLOCK_SKEW, offer)) {
          throw new IllegalArgumentException("answer signature or offer binding is invalid");
        }
        if (expectedPeer != null && !expectedPeer.equals(answer.responderNodeId())) {
          throw new IllegalArgumentException("answer responder is not the expected peer");
        }
        if (!answerImported.compareAndSet(false, true)) {
          throw new IllegalStateException("answer has already been imported");
        }
        emit(OnboardingEventType.ANSWER_VERIFIED, "");
        emit(OnboardingEventType.TRAVERSAL_STARTED, "");
        PeerSession session = endpoint.established().get(
            SessionInvitation.DEFAULT_LIFETIME.toSeconds() + 30, TimeUnit.SECONDS);
        emitSession(session);
        sessionAction.accept(session);
        if (!keepAlive) {
          session.terminalCompletion().toCompletableFuture().get(30, TimeUnit.SECONDS);
          emit(OnboardingEventType.SESSION_CLOSED, "");
        }
        return session;
      } catch (java.util.concurrent.TimeoutException timeout) {
        throw failure(OnboardingFailureCode.HOST_TIMEOUT, timeout);
      } catch (Exception failure) {
        throw failure(OnboardingFailureCode.ANSWER_INVALID, failure);
      }
    }

    /**
     * Releases the bound endpoint and host admission state.
     */
    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        if (compactCompletion != null) {
          compactCompletion.completeExceptionally(
              new java.util.concurrent.CancellationException("host handle closed"));
        }
        endpoint.close();
      }
    }
  }

  /**
   * Opens join-side state for one invitation.
   *
   * <p>The instance is single-use: a legacy handle creates an answer and a compact handle keeps
   * the local endpoint ready for the direct race; {@link #connect()} starts that race once.
   */
  public final class PreparedJoin implements AutoCloseable {

    private final NodeIdentity identity;
    private final SessionInvitation invitation;
    private final TraversalOffer offer;
    private final TraversalAnswer answer;
    private final CandidateDescriptor host;
    private final CandidateDescriptor localDescriptor;
    private final CompactBootstrapInvitation compactInvitation;
    private final OnboardingEndpoint endpoint;
    private final PeerSession.ApplicationStreamHandler applicationHandler;
    private final Consumer<PeerSession> sessionAction;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private PreparedJoin(NodeIdentity identity, SessionInvitation invitation, TraversalOffer offer,
        TraversalAnswer answer, CandidateDescriptor host, CandidateDescriptor localDescriptor,
        OnboardingEndpoint endpoint, PeerSession.ApplicationStreamHandler applicationHandler,
        Consumer<PeerSession> sessionAction) {
      this(identity, invitation, offer, answer, host, localDescriptor, null, endpoint,
          applicationHandler, sessionAction);
    }

    private PreparedJoin(NodeIdentity identity, SessionInvitation invitation, TraversalOffer offer,
        TraversalAnswer answer, CandidateDescriptor host, CandidateDescriptor localDescriptor,
        CompactBootstrapInvitation compactInvitation, OnboardingEndpoint endpoint,
        PeerSession.ApplicationStreamHandler applicationHandler,
        Consumer<PeerSession> sessionAction) {
      this.identity = identity;
      this.invitation = invitation;
      this.offer = offer;
      this.answer = answer;
      this.host = host;
      this.localDescriptor = localDescriptor;
      this.compactInvitation = compactInvitation;
      this.endpoint = endpoint;
      this.applicationHandler = applicationHandler;
      this.sessionAction = sessionAction;
    }

    /**
     * Returns the exact legacy SLA2 link to send back to the host user.
     *
     * @return copyable signed SLA2 link, or {@code null} for a compact one-way handle
     */
    public String answerLink() {
      return answer == null ? null : answer.shareLink();
    }

    /**
     * Starts the existing bounded candidate race and authenticated Link session.
     *
     * @throws OnboardingFailure if the answer is reused, candidates are unusable, or connection
     *                           fails
     */
    public void connect() throws OnboardingFailure {
      connect(false);
    }

    /**
     * Starts the bounded candidate race and returns after authentication while retaining the live
     * Link endpoint and session for an owning runtime.
     *
     * @return authenticated control-ready session
     * @throws OnboardingFailure if the answer is reused, candidates are unusable, or connection
     *                           fails
     */
    public PeerSession connectAndKeepAlive() throws OnboardingFailure {
      return connect(true);
    }

    private PeerSession connect(boolean keepAlive) throws OnboardingFailure {
      if (!started.compareAndSet(false, true)) {
        throw failure(OnboardingFailureCode.ANSWER_INVALID,
            new IllegalStateException("answer has already been used"));
      }
      if (compactInvitation != null) {
        return connectCompactPrepared(keepAlive);
      }
      try {
        TraversalCoordinator coordinator = new TraversalCoordinator(identity, host.nodeId(),
            invitation.sessionId(), 8);
        coordinator.acceptAsAnswerer(offer, answer, Instant.now(),
            CandidateDescriptor.DEFAULT_CLOCK_SKEW);
        List<CandidatePair> pairs = coordinator.plan(
                CandidatePairs.generate(localDescriptor.candidates(),
                    host.candidates(), 8))
            .stream()
            .map(TraversalCoordinator.Attempt::pair)
            .toList();
        if (pairs.isEmpty()) {
          throw failure(OnboardingFailureCode.NO_USABLE_CANDIDATE, null);
        }
        HandshakeTranscript transcript = handshakeTranscript(identity, invitation.sessionId(),
            invitation.capability(), host.nodeId(), host.publicKeyEncoded());
        emit(OnboardingEventType.TRAVERSAL_STARTED, "");
        return connectAndRun(identity, transcript, pairs, endpoint.udp(), applicationHandler,
            sessionAction, keepAlive, _ -> {
            });
      } catch (OnboardingFailure failure) {
        throw failure;
      } catch (Exception failure) {
        throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
      }
    }

    private PeerSession connectCompactPrepared(boolean keepAlive) throws OnboardingFailure {
      try {
        List<CandidatePair> pairs = CandidatePairs.generate(localDescriptor.candidates(),
            List.of(compactInvitation.endpoint()), 8);
        if (pairs.isEmpty()) {
          throw failure(OnboardingFailureCode.NO_USABLE_CANDIDATE, null);
        }
        HandshakeTranscript transcript = handshakeTranscript(identity,
            compactInvitation.sessionId(), compactInvitation.capability(),
            compactInvitation.hostNodeId(), compactInvitation.publicKeyEncoded());
        emit(OnboardingEventType.TRAVERSAL_STARTED, "");
        return connectAndRun(identity, transcript, pairs, endpoint.udp(), applicationHandler,
            sessionAction, keepAlive, session -> {
              CompactDescriptorExchange.exchangeDescriptor(session, localDescriptor,
                  compactInvitation.hostNodeId(), compactInvitation.publicKeyEncoded(),
                  compactInvitation.expiresAt());
              emit(OnboardingEventType.DESCRIPTORS_EXCHANGED, "");
            });
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
