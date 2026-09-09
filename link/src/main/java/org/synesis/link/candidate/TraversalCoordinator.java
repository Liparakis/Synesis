package org.synesis.link.candidate;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.TraversalAnswer;
import org.synesis.link.protocol.TraversalOffer;

/**
 * Thread-safe bounded coordinator for one authenticated direct-traversal race.
 *
 * <p>This class coordinates state and deterministic duplicate winner selection;
 * it does not open sockets or replace the existing authenticated {@code CandidateRacer}. A caller
 * must invoke {@link #recordAuthenticated} only after the existing Link handshake has established
 * the expected remote identity.
 *
 * @since 1.0
 */
public final class TraversalCoordinator implements AutoCloseable {

  /**
   * Maximum number of candidate attempts retained in one plan.
   */
  public static final int MAX_ATTEMPTS = 32;
  private final NodeIdentity localIdentity;
  private final String expectedRemoteNodeId;
  private final UUID sessionId;
  private final int maximumAttempts;
  private final Map<String, Attempt> planned = new HashMap<>();
  private final Set<String> authenticated = new HashSet<>();
  private State state = State.NEW;
  private ConnectionFailureCategory failureCategory;
  private String winner;
  /**
   * Creates a bounded coordinator for one initiator-side exchange.
   *
   * @param localIdentity        local durable identity
   * @param expectedRemoteNodeId expected remote durable identity
   * @param sessionId            Link session ID bound to the exchange
   * @param maximumAttempts      maximum planned attempts, at most 32
   */
  public TraversalCoordinator(NodeIdentity localIdentity, String expectedRemoteNodeId,
      UUID sessionId, int maximumAttempts) {
    this.localIdentity = Objects.requireNonNull(localIdentity, "local identity");
    this.expectedRemoteNodeId = Objects.requireNonNull(expectedRemoteNodeId,
        "expected remote node ID");
    this.sessionId = Objects.requireNonNull(sessionId, "session ID");
    if (expectedRemoteNodeId.isBlank() || maximumAttempts < 1 || maximumAttempts > MAX_ATTEMPTS) {
      throw new IllegalArgumentException("invalid traversal coordinator bound");
    }
    this.maximumAttempts = maximumAttempts;
  }

  /**
   * Verifies and accepts the signed offer/answer pair.
   *
   * @param offer            initiator offer
   * @param answer           responder answer
   * @param now              current validation time
   * @param allowedClockSkew accepted issue-time skew
   * @throws GeneralSecurityException if a signature cannot be checked
   * @throws IOException              if an embedded record is malformed
   */
  public synchronized void accept(TraversalOffer offer, TraversalAnswer answer, Instant now,
      Duration allowedClockSkew) throws GeneralSecurityException, IOException {
    ensureState(State.NEW);
    Objects.requireNonNull(offer, "offer");
    Objects.requireNonNull(answer, "answer");
    if (!sessionId.equals(offer.sessionId()) || !sessionId.equals(answer.sessionId())
        || !localIdentity.nodeId().equals(offer.initiatorNodeId())
        || !expectedRemoteNodeId.equals(answer.responderNodeId())
        || !offer.verifyAt(now, allowedClockSkew, expectedRemoteNodeId)
        || !answer.verifyAt(now, allowedClockSkew, offer)) {
      throw new IllegalArgumentException("traversal exchange is not bound to this coordinator");
    }
    state = State.READY;
  }

  /**
   * Verifies and accepts a human-mediated exchange when this side is the answer signer but will
   * remain the QUIC initiator.
   *
   * <p>The signed traversal-record roles and the QUIC connection roles are
   * intentionally independent in the copy/paste flow: the host signs SLO1, the joiner signs SLA2,
   * and the joiner still opens the existing QUIC client connection. This method preserves both
   * identity checks without reusing the initiator-side role predicate incorrectly.
   *
   * @param offer            host-signed offer
   * @param answer           local responder-signed answer
   * @param now              current verification time
   * @param allowedClockSkew accepted issue-time skew
   * @throws GeneralSecurityException if a signature cannot be checked
   * @throws IOException              if an embedded record is malformed
   */
  public synchronized void acceptAsAnswerer(TraversalOffer offer, TraversalAnswer answer,
      Instant now,
      Duration allowedClockSkew) throws GeneralSecurityException, IOException {
    ensureState(State.NEW);
    Objects.requireNonNull(offer, "offer");
    Objects.requireNonNull(answer, "answer");
    if (!sessionId.equals(offer.sessionId()) || !sessionId.equals(answer.sessionId())
        || !expectedRemoteNodeId.equals(offer.initiatorNodeId())
        || !localIdentity.nodeId().equals(answer.responderNodeId())
        || !offer.verifyAt(now, allowedClockSkew, localIdentity.nodeId())
        || !answer.verifyAt(now, allowedClockSkew, offer)) {
      throw new IllegalArgumentException("traversal exchange is not bound to this coordinator");
    }
    state = State.READY;
  }

  /**
   * Creates a bounded attempt plan from already-normalized candidate pairs.
   *
   * @param pairs ranked compatible candidate pairs
   * @return deduplicated deterministic attempt plan
   */
  public synchronized List<Attempt> plan(List<CandidatePair> pairs) {
    ensureState(State.READY);
    Objects.requireNonNull(pairs, "candidate pairs");
    List<Attempt> result = new ArrayList<>();
    Set<String> identifiers = new HashSet<>();
    for (CandidatePair pair : pairs) {
      CandidatePair value = Objects.requireNonNull(pair, "candidate pair");
      String identifier = value.identifier();
      if (identifiers.add(identifier)) {
        Attempt attempt = new Attempt(identifier, value, result.size());
        result.add(attempt);
        planned.put(identifier, attempt);
        if (result.size() == maximumAttempts) {
          break;
        }
      }
    }
    state = result.isEmpty() ? State.FAILED : State.ATTEMPTING;
    if (result.isEmpty()) {
      failureCategory = ConnectionFailureCategory.NO_COMPATIBLE_CANDIDATE;
    }
    return List.copyOf(result);
  }

  /**
   * Records one authenticated usable attempt for later deterministic selection.
   *
   * @param attemptIdentifier         planned attempt identifier
   * @param authenticatedRemoteNodeId identity returned by the Link handshake
   * @return {@code true} if this was a new valid authenticated attempt
   */
  public synchronized boolean recordAuthenticated(String attemptIdentifier,
      String authenticatedRemoteNodeId) {
    Objects.requireNonNull(attemptIdentifier, "attempt identifier");
    Objects.requireNonNull(authenticatedRemoteNodeId, "authenticated remote node ID");
    if (state != State.ATTEMPTING || !expectedRemoteNodeId.equals(authenticatedRemoteNodeId)
        || !planned.containsKey(attemptIdentifier)) {
      return false;
    }
    return authenticated.add(attemptIdentifier);
  }

  /**
   * Selects the lexicographically smallest authenticated attempt.
   *
   * <p>The caller should invoke this after its bounded duplicate-settle
   * window. Selecting from a set, rather than accepting the first callback, gives both peers the
   * same deterministic result when simultaneous QUIC connections succeed.
   *
   * @return selected attempt identifier, or empty when none is ready
   */
  public synchronized Optional<String> selectWinner() {
    if (state == State.ESTABLISHED) {
      return Optional.of(winner);
    }
    if (state != State.ATTEMPTING || authenticated.isEmpty()) {
      return Optional.empty();
    }
    winner = authenticated.stream().sorted().findFirst().orElseThrow();
    state = State.ESTABLISHED;
    return Optional.of(winner);
  }

  /**
   * Records a terminal traversal failure if no attempt was selected.
   *
   * @param category truthful failure category
   * @return {@code true} if the state changed
   */
  public synchronized boolean fail(ConnectionFailureCategory category) {
    Objects.requireNonNull(category, "failure category");
    if (state == State.ESTABLISHED || state == State.FAILED || state == State.CLOSED) {
      return false;
    }
    state = State.FAILED;
    failureCategory = category;
    return true;
  }

  /**
   * Returns the current coordinator state.
   *
   * @return current coordinator state
   */
  public synchronized State state() {
    return state;
  }

  /**
   * Returns whether a deterministic authenticated winner was selected.
   *
   * @return whether a winner was selected
   */
  public synchronized boolean isEstablished() {
    return state == State.ESTABLISHED;
  }

  /**
   * Returns the selected attempt identifier, or {@code null} before selection.
   *
   * @return selected attempt identifier
   */
  public synchronized String winner() {
    return winner;
  }

  /**
   * Returns the terminal failure category, or {@code null} when not failed.
   *
   * @return terminal failure category
   */
  public synchronized ConnectionFailureCategory failureCategory() {
    return failureCategory;
  }

  /**
   * Releases all planned and authenticated attempt state.
   */
  @Override
  public synchronized void close() {
    planned.clear();
    authenticated.clear();
    state = State.CLOSED;
  }

  private void ensureState(State expected) {
    if (state != expected) {
      throw new IllegalStateException(
          "traversal coordinator is " + state + ", expected " + expected);
    }
  }

  /**
   * Lifecycle states for one direct-traversal exchange.
   */
  public enum State {
    /**
     * No verified offer/answer has been accepted.
     */
    NEW,
    /**
     * Both signed exchange records have been verified.
     */
    READY,
    /**
     * Candidate attempts may be made.
     */
    ATTEMPTING,
    /**
     * One deterministic authenticated attempt was selected.
     */
    ESTABLISHED,
    /**
     * Traversal ended without a usable authenticated attempt.
     */
    FAILED,
    /**
     * The coordinator has released its state.
     */
    CLOSED
  }

  /**
   * One bounded candidate attempt in deterministic plan order.
   *
   * @param identifier stable pair identifier
   * @param pair       candidate pair to attempt
   * @param rank       zero-based plan rank
   */
  public record Attempt(String identifier, CandidatePair pair, int rank) {

    /**
     * Validates the immutable attempt descriptor.
     */
    public Attempt {
      Objects.requireNonNull(identifier, "attempt identifier");
      Objects.requireNonNull(pair, "candidate pair");
      if (identifier.isBlank() || rank < 0) {
        throw new IllegalArgumentException("invalid traversal attempt");
      }
    }
  }
}
