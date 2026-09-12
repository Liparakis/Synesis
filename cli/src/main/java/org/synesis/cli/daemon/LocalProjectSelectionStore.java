package org.synesis.cli.daemon;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded, process-local state for one-shot local project selection.
 *
 * <p>The store deliberately retains no durable state. The original link is
 * kept only until the first valid selection, because the daemon must dispatch
 * the exact bytes that caused the selection request.</p>
 */
final class LocalProjectSelectionStore {

  static final int DEFAULT_MAX_CONTEXTS = 64;
  static final Duration DEFAULT_TTL = Duration.ofMinutes(2);

  private final Clock clock;
  private final int maxContexts;
  private final Duration ttl;
  private final Map<UUID, Context> contexts = new LinkedHashMap<>();

  LocalProjectSelectionStore() {
    this(Clock.systemUTC(), DEFAULT_MAX_CONTEXTS, DEFAULT_TTL);
  }

  LocalProjectSelectionStore(Clock clock, int maxContexts, Duration ttl) {
    this.clock = Objects.requireNonNull(clock, "clock");
    if (maxContexts <= 0) {
      throw new IllegalArgumentException("max contexts must be positive");
    }
    this.maxContexts = maxContexts;
    this.ttl = Objects.requireNonNull(ttl, "ttl");
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
  }

  synchronized Creation create(String invitation, byte[] invitationDigest,
      List<UUID> eligibleProjectIds) {
    Objects.requireNonNull(invitation, "invitation");
    byte[] digest = exactDigest(invitationDigest);
    List<UUID> projects = immutableProjects(eligibleProjectIds);
    Instant now = clock.instant();
    expire(now);
    if (contexts.size() >= maxContexts) {
      throw new CapacityExceededException();
    }
    UUID selectionId;
    do {
      selectionId = UUID.randomUUID();
    } while (contexts.containsKey(selectionId));
    Instant expiresAt = now.plus(ttl);
    contexts.put(selectionId, new Context(selectionId, digest, projects, invitation, now,
        expiresAt, State.PENDING));
    return new Creation(selectionId, projects, expiresAt);
  }

  synchronized Claim claim(UUID selectionId, UUID selectedProjectId, byte[] invitationDigest,
      List<UUID> currentlyEligibleProjectIds) {
    Objects.requireNonNull(selectionId, "selection ID");
    Objects.requireNonNull(selectedProjectId, "selected project ID");
    byte[] digest = exactDigest(invitationDigest);
    List<UUID> eligible = immutableProjects(currentlyEligibleProjectIds);
    Context context = contexts.get(selectionId);
    return claim(selectionId, selectedProjectId, currentlyEligibleProjectIds, context, digest);
  }

  synchronized Claim claim(UUID selectionId, UUID selectedProjectId,
      List<UUID> currentlyEligibleProjectIds) {
    Objects.requireNonNull(selectionId, "selection ID");
    Objects.requireNonNull(selectedProjectId, "selected project ID");
    List<UUID> eligible = immutableProjects(currentlyEligibleProjectIds);
    Context context = contexts.get(selectionId);
    return claim(selectionId, selectedProjectId, eligible, context, null);
  }

  private Claim claim(UUID selectionId, UUID selectedProjectId, List<UUID> eligible,
      Context context, byte[] expectedDigest) {
    if (context == null) {
      return Claim.failure("SELECTION_UNKNOWN");
    }
    Instant now = clock.instant();
    if (!now.isBefore(context.expiresAt())) {
      contexts.put(selectionId, context.expired());
      return Claim.failure("SELECTION_EXPIRED");
    }
    if (context.state() == State.CONSUMED) {
      return Claim.failure("SELECTION_CONSUMED");
    }
    if (expectedDigest != null && !MessageDigest.isEqual(context.invitationDigest(), expectedDigest)) {
      return Claim.failure("INVITATION_MISMATCH");
    }
    if (!context.eligibleProjectIds().contains(selectedProjectId)) {
      return Claim.failure("PROJECT_NOT_ELIGIBLE");
    }
    if (!eligible.contains(selectedProjectId)) {
      contexts.put(selectionId, context.consumed());
      return Claim.failure("PROJECT_UNAVAILABLE");
    }
    contexts.put(selectionId, context.consumed());
    return Claim.success(context.invitation(), selectedProjectId);
  }

  synchronized Description describe(UUID selectionId) {
    Objects.requireNonNull(selectionId, "selection ID");
    Context context = contexts.get(selectionId);
    if (context == null) {
      return Description.failure("SELECTION_UNKNOWN");
    }
    Instant now = clock.instant();
    if (!now.isBefore(context.expiresAt()) && context.state() == State.PENDING) {
      context = context.expired();
      contexts.put(selectionId, context);
    }
    if (context.state() != State.PENDING) {
      return Description.failure(context.state() == State.EXPIRED
          ? "SELECTION_EXPIRED" : "SELECTION_CONSUMED");
    }
    return Description.success(context.eligibleProjectIds(), context.expiresAt());
  }

  synchronized int size() {
    expire(clock.instant());
    return contexts.size();
  }

  private void expire(Instant now) {
    contexts.replaceAll((id, context) -> !now.isBefore(context.expiresAt())
        && context.state() == State.PENDING ? context.expired() : context);
    contexts.entrySet().removeIf(entry -> entry.getValue().state() != State.PENDING
        && !now.isBefore(entry.getValue().expiresAt().plus(ttl)));
  }

  private static List<UUID> immutableProjects(List<UUID> projectIds) {
    Objects.requireNonNull(projectIds, "project IDs");
    List<UUID> copy = new ArrayList<>(projectIds.size());
    for (UUID projectId : projectIds) {
      if (projectId == null || copy.contains(projectId)) {
        throw new IllegalArgumentException("project IDs must be unique and non-null");
      }
      copy.add(projectId);
    }
    return List.copyOf(copy);
  }

  private static byte[] exactDigest(byte[] digest) {
    Objects.requireNonNull(digest, "invitation digest");
    if (digest.length != 32) {
      throw new IllegalArgumentException("invitation digest must be SHA-256");
    }
    return digest.clone();
  }

  private enum State {
    PENDING,
    EXPIRED,
    CONSUMED
  }

  private record Context(UUID selectionId, byte[] invitationDigest, List<UUID> eligibleProjectIds,
                         String invitation, Instant createdAt, Instant expiresAt, State state) {

    private Context {
      invitationDigest = invitationDigest.clone();
      eligibleProjectIds = List.copyOf(eligibleProjectIds);
    }

    private Context expired() {
      return new Context(selectionId, invitationDigest, eligibleProjectIds, null, createdAt,
          expiresAt, State.EXPIRED);
    }

    private Context consumed() {
      return new Context(selectionId, invitationDigest, eligibleProjectIds, null, createdAt,
          expiresAt, State.CONSUMED);
    }
  }

  record Creation(UUID selectionId, List<UUID> eligibleProjectIds, Instant expiresAt) {
  }

  record Claim(boolean accepted, String error, String invitation, UUID selectedProjectId) {

    static Claim success(String invitation, UUID selectedProjectId) {
      return new Claim(true, null, invitation, selectedProjectId);
    }

    static Claim failure(String error) {
      return new Claim(false, error, null, null);
    }
  }

  record Description(boolean available, String error, List<UUID> eligibleProjectIds,
                     Instant expiresAt) {

    static Description success(List<UUID> eligibleProjectIds, Instant expiresAt) {
      return new Description(true, null, List.copyOf(eligibleProjectIds), expiresAt);
    }

    static Description failure(String error) {
      return new Description(false, error, List.of(), null);
    }
  }

  static final class CapacityExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
