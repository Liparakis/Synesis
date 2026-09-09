package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Admits a durable, explicitly targeted coordination item to one dormant Codex participant and
 * routes it through the existing exact lifecycle owner.
 *
 * <p>This service is intentionally narrower than an orchestrator. It does not
 * choose work, launch a new participant, or interpret private conversation context. It only joins a
 * durable target participant, an exact lifecycle checkpoint, and one already projected inbox item.
 * A live attachment receives {@code NOTIFY}; an attachment that is no longer retained by the owner
 * receives {@code RESUME}. The owner remains responsible for all provider protocol, process,
 * thread, and authority checks.</p>
 *
 * @since 1.0
 */
public final class CodexWakeAdmissionService {

  /**
   * Prefix used when a wake request carries the stored binding fingerprint.
   */
  public static final String FINGERPRINT_CONNECTION_PREFIX = "synesis-binding-fingerprint:";

  /**
   * Continuation prompt that reconnects the participant to durable coordination.
   */
  public static final String GET_NEXT_ACTION_INPUT =
      "A durable Synesis coordination item is now actionable for this participant. "
          + "Call get_next_action with no arguments, preserve the current project and "
          + "session authority, and continue the associated collaboration.";

  private static final int MAX_ADMISSION_ENTRIES = 2_048;
  private static final long MAX_ADMISSION_FILE_BYTES = 512L * 1024L;
  private final LifecycleDispatcher dispatcher;
  private final AdmissionStore admissions;

  /**
   * Creates a wake admission service.
   *
   * @param dispatcher exact Codex lifecycle owner
   * @param admissions durable admission ledger
   */
  public CodexWakeAdmissionService(LifecycleDispatcher dispatcher, AdmissionStore admissions) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.admissions = Objects.requireNonNull(admissions, "admissions");
  }

  /**
   * Creates a wake admission service backed by a bounded project-local ledger.
   *
   * @param dispatcher    exact Codex lifecycle owner
   * @param admissionFile durable ledger path
   */
  public CodexWakeAdmissionService(LifecycleDispatcher dispatcher, Path admissionFile) {
    this(dispatcher, new FileAdmissionStore(admissionFile));
  }

  /**
   * Returns the runtime root shared with the existing project lifecycle owner.
   *
   * @param location initialized project location
   * @return lifecycle runtime root
   */
  public static Path runtimeRoot(ProjectApplicationService.ProjectLocation location) {
    Objects.requireNonNull(location, "location");
    return location.synesisDirectory()
        .resolve("local")
        .resolve("runtime")
        .resolve("codex-lifecycle")
        .toAbsolutePath()
        .normalize();
  }

  private static boolean eligibleBinding(ProviderSessionBindingService.Binding binding,
      String projectId) {
    return projectId.equals(binding.projectId()) && "codex".equals(binding.provider())
        && "BOUND".equals(binding.status()) && "VERIFIED".equals(binding.verificationState())
        && "VERIFIED".equals(binding.providerTrustState()) && binding.worktreePath() != null
        && binding.controlCheckoutPath() != null && binding.gitCommonDir() != null
        && binding.branch() != null && binding.baseCommit() != null;
  }

  private static ActionableItem targetedPendingRequest(PredictionEventStore store,
      ProviderSessionBindingService.Binding binding, String participant) {
    ActionableItem capability = store.capabilityRequestProjection()
        .findAllForRequester(binding.nodeId())
        .stream()
        .map(request -> capabilityAction(request, binding, participant))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
    if (capability != null) {
      return capability;
    }
    return store.collaborationProjection()
        .requests()
        .stream()
        .filter(request -> request.status() == CoordinationRequest.Status.PENDING)
        .filter(request -> !store.collaborationProjection()
            .inboxAcknowledged(request.requestId()))
        .filter(request -> request.target()
            .equals(participant))
        .map(request -> new ActionableItem("coordination-request:" + request.requestId(),
            participant))
        .findFirst()
        .orElse(null);
  }

  static ActionableItem capabilityAction(CapabilityRequestRecord request,
      ProviderSessionBindingService.Binding binding, String participant) {
    if (!request.matchesRequester(binding.nodeId(), binding.supervisorId(), binding.workerId())
        || request.state() != CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE) {
      return null;
    }
    return new ActionableItem("capability-request:" + request.handle()
        .value(), participant);
  }

  private static boolean isDormant(CodexLifecycleStateStore.Checkpoint checkpoint) {
    return Set.of(CodexLifecycleStateStore.State.IDLE, CodexLifecycleStateStore.State.COMPLETED,
            CodexLifecycleStateStore.State.INTERRUPTED, CodexLifecycleStateStore.State.STOPPED)
        .contains(checkpoint.state());
  }

  private static String admissionKey(DormantBinding candidate) {
    return "wake|" + candidate.binding()
        .projectId() + "|" + candidate.binding()
        .sessionId() + "|"
        + candidate.checkpoint()
        .threadId() + "|" + candidate.action()
        .key();
  }

  private static WakeResult result(Outcome outcome,
      LifecycleControlRequestEnvelope.Operation operation,
      DormantBinding candidate, String admissionKey, String diagnostic) {
    return new WakeResult(outcome,
        operation,
        candidate.action()
            .participant(),
        candidate.binding()
            .sessionId(),
        candidate.checkpoint()
            .threadId(),
        admissionKey,
        diagnostic);
  }

  private static String diagnostic(Exception failure) {
    return failure.getMessage() == null ? failure.getClass()
                                          .getSimpleName() : failure.getMessage();
  }

  private static void requireText(String value, String label) {
    if (value == null || value.isBlank()
        || value.length() > LifecycleControlRequestEnvelope.MAX_TEXT_BYTES) {
      throw new IllegalArgumentException(label + " invalid");
    }
  }

  /**
   * Admits one exact dormant participant if its durable item is still actionable.
   *
   * @param candidate exact binding, lifecycle, lane, and item association
   * @return bounded admission result
   * @throws IOException when durable admission state cannot be read or written
   */
  public synchronized WakeResult admit(DormantBinding candidate) throws IOException {
    Objects.requireNonNull(candidate, "candidate");
    String participant = candidate.action()
        .participant();
    String bindingSessionId = candidate.binding()
        .sessionId();
    String threadId = candidate.checkpoint()
        .threadId();
    String admissionKey = admissionKey(candidate);
    if (!isDormant(candidate.checkpoint())) {
      return result(Outcome.NOT_DORMANT, null, candidate, admissionKey,
          "lifecycle_state_not_dormant");
    }
    if (threadId == null || threadId.isBlank()) {
      return result(Outcome.REJECTED, null, candidate, admissionKey, "lifecycle_thread_missing");
    }
    if (admissions.contains(admissionKey)) {
      return result(Outcome.SKIPPED_ALREADY_ADMITTED, null, candidate, admissionKey,
          "wake_admission_already_recorded");
    }

    LifecycleControlRequestEnvelope.Operation operation =
        dispatcher.attachmentAlive(bindingSessionId)
            ? LifecycleControlRequestEnvelope.Operation.NOTIFY
            : LifecycleControlRequestEnvelope.Operation.RESUME;
    long deadline = System.currentTimeMillis() + 30_000L;
    LifecycleControlRequestEnvelope.AuthorityContext authority =
        new LifecycleControlRequestEnvelope.AuthorityContext(
            candidate.binding()
                .projectId(),
            candidate.binding()
                .controlCheckoutPath(),
            "codex",
            FINGERPRINT_CONNECTION_PREFIX + candidate.binding()
                .providerInstanceFingerprint(),
            bindingSessionId,
            candidate.binding()
                .providerInstanceFingerprint(),
            candidate.binding()
                .bindingVersion(),
            participant,
            candidate.workIntentId(),
            candidate.laneEpoch(),
            candidate.canonicalWorktree(),
            candidate.realWorktree(),
            candidate.binding()
                .gitCommonDir(),
            candidate.binding()
                .branch(),
            candidate.binding()
                .baseCommit(),
            candidate.binding()
                .supervisorId(),
            candidate.binding()
                .workerId());
    LifecycleControlRequestEnvelope request = new LifecycleControlRequestEnvelope(
        UUID.nameUUIDFromBytes(admissionKey.getBytes(StandardCharsets.UTF_8)),
        dispatcher.hostInstanceId(),
        authority,
        operation,
        candidate.checkpoint()
            .revision(),
        threadId,
        candidate.checkpoint()
            .turnId(),
        true,
        GET_NEXT_ACTION_INPUT,
        deadline,
        Map.of());
    try {
      CodexLifecycleHttpClient.Response response = dispatcher.dispatch(request);
      if (!response.success()) {
        return result(Outcome.REJECTED, operation, candidate, admissionKey, response.diagnostic());
      }
      admissions.record(admissionKey);
      return result(Outcome.DISPATCHED, operation, candidate, admissionKey, response.diagnostic());
    } catch (Exception failure) {
      return result(Outcome.REJECTED, operation, candidate, admissionKey, diagnostic(failure));
    }
  }

  /**
   * Scans durable collaboration state and admits targeted items for eligible Codex bindings.
   *
   * <p>The scan is intentionally fail-closed: malformed binding, lifecycle, or
   * coordination state raises an error rather than guessing a participant.</p>
   *
   * @param projectRoot control project root
   * @return one result for each targeted dormant candidate observed
   * @throws Exception when project or durable state cannot be read
   */
  public List<WakeResult> scan(Path projectRoot) throws Exception {
    ProjectApplicationService.ProjectLocation location =
        new ProjectApplicationService().locate(projectRoot.toAbsolutePath()
            .normalize());
    PredictionEventStore store = new PredictionEventStore(
        location.root()
            .resolve(".synesis/coordination"), location.projectId());
    ProviderSessionBindingService bindings = new ProviderSessionBindingService();
    CodexLifecycleStateStore lifecycle = new CodexLifecycleStateStore(
        runtimeRoot(location).resolve("bindings"));
    List<WakeResult> results = new ArrayList<>();
    for (ProviderSessionBindingService.Binding binding : bindings.list(location, "codex")) {
      if (!eligibleBinding(binding,
          location.projectId()
              .toString())) {
        continue;
      }
      CodexLifecycleStateStore.Checkpoint checkpoint =
          lifecycle.read(binding.sessionId(),
              location.projectId()
                  .toString());
      if (!isDormant(checkpoint) || checkpoint.threadId() == null) {
        continue;
      }
      String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
      if (store.collaborationProjection()
          .participants()
          .stream()
          .noneMatch(item -> item.id()
              .equals(participant) && item.state() == Participant.State.ACTIVE)) {
        continue;
      }
      WorkIntent intent = store.collaborationProjection()
          .activeIntents()
          .stream()
          .filter(item -> item.participant()
              .equals(participant))
          .findFirst()
          .orElse(null);
      if (intent == null || intent.status() != WorkIntent.Status.ANNOUNCED) {
        continue;
      }
      ActionableItem action = targetedPendingRequest(store, binding, participant);
      if (action == null) {
        continue;
      }
      Path canonicalWorktree;
      Path realWorktree;
      try {
        canonicalWorktree = Path.of(binding.worktreePath())
            .toAbsolutePath()
            .normalize();
        realWorktree = canonicalWorktree.toRealPath();
      } catch (Exception failure) {
        throw new IOException("wake_worktree_unresolvable", failure);
      }
      results.add(admit(new DormantBinding(binding,
          checkpoint,
          intent.intentId()
              .toString(),
          intent.version(),
          action,
          canonicalWorktree.toString(),
          realWorktree.toString())));
    }
    return List.copyOf(results);
  }

  /**
   * Wake admission outcomes.
   */
  public enum Outcome {
    /**
     * A lifecycle continuation was submitted successfully.
     */
    DISPATCHED,
    /**
     * The same participant/thread/action was already admitted.
     */
    SKIPPED_ALREADY_ADMITTED,
    /**
     * The checkpoint is not a dormant state eligible for a new turn.
     */
    NOT_DORMANT,
    /**
     * The candidate did not contain a currently actionable durable item.
     */
    NOT_ACTIONABLE,
    /**
     * The owner rejected or could not durably record the wake attempt.
     */
    REJECTED
  }

  /**
   * Provider lifecycle owner boundary used by the admission service.
   */
  public interface LifecycleDispatcher {

    /**
     * Returns the exact owner instance identity.
     *
     * @return owner identity
     */
    String hostInstanceId();

    /**
     * Reports whether the owner still retains the exact binding attachment.
     *
     * @param bindingSessionId exact binding session
     * @return {@code true} only when that attachment is live
     */
    boolean attachmentAlive(String bindingSessionId);

    /**
     * Submits one immutable lifecycle request through the owner.
     *
     * @param request unsigned request whose owner signs/validates it in-process
     * @return bounded lifecycle response
     * @throws Exception when owner validation or lifecycle execution fails
     */
    CodexLifecycleHttpClient.Response dispatch(LifecycleControlRequestEnvelope request)
        throws Exception;
  }

  /**
   * Durable at-most-once admission ledger boundary.
   */
  public interface AdmissionStore {

    /**
     * Tests whether an admission key has already been attempted successfully.
     *
     * @param key admission key
     * @return whether the key is recorded
     * @throws IOException when the ledger cannot be read
     */
    boolean contains(String key) throws IOException;

    /**
     * Records one successful admission.
     *
     * @param key admission key
     * @throws IOException when the ledger cannot be persisted
     */
    void record(String key) throws IOException;
  }

  /**
   * An explicitly targeted durable coordination item.
   *
   * @param key         stable item identity used for at-most-once admission
   * @param participant exact opaque Synesis participant handle
   */
  public record ActionableItem(String key, String participant) {

    /**
     * Validates the bounded item identity.
     */
    public ActionableItem {
      requireText(key, "key");
      requireText(participant, "participant");
    }
  }

  /**
   * Exact dormant binding and lifecycle state selected for admission.
   *
   * @param binding           verified provider binding
   * @param checkpoint        durable Codex lifecycle checkpoint
   * @param workIntentId      exact active Synesis intent
   * @param laneEpoch         exact active intent version
   * @param action            targeted durable item
   * @param canonicalWorktree canonical assigned worktree
   * @param realWorktree      real assigned worktree
   */
  public record DormantBinding(ProviderSessionBindingService.Binding binding,
                               CodexLifecycleStateStore.Checkpoint checkpoint, String workIntentId,
                               long laneEpoch,
                               ActionableItem action, String canonicalWorktree,
                               String realWorktree) {

    /**
     * Validates exact binding/checkpoint/action identity.
     */
    public DormantBinding {
      Objects.requireNonNull(binding, "binding");
      Objects.requireNonNull(checkpoint, "checkpoint");
      Objects.requireNonNull(action, "action");
      requireText(workIntentId, "workIntentId");
      if (laneEpoch < 1L) {
        throw new IllegalArgumentException("laneEpoch must be positive");
      }
      String expectedParticipant = WorkspaceCollaborationService.participantHandle(
          binding.sessionId());
      if (!expectedParticipant.equals(action.participant())) {
        throw new IllegalArgumentException("wake participant does not match binding");
      }
      if (!binding.sessionId()
          .equals(checkpoint.bindingSessionId())
          || !binding.projectId()
          .equals(checkpoint.projectId())
          || !"codex".equals(binding.provider())
          || !"codex".equals(checkpoint.provider())) {
        throw new IllegalArgumentException("wake binding/checkpoint identity mismatch");
      }
      if (canonicalWorktree == null || canonicalWorktree.isBlank()) {
        canonicalWorktree = binding.worktreePath();
      }
      if (realWorktree == null || realWorktree.isBlank()) {
        realWorktree = canonicalWorktree;
      }
      requireText(canonicalWorktree, "canonicalWorktree");
      requireText(realWorktree, "realWorktree");
    }

    /**
     * Creates a candidate using the binding's recorded worktree identity.
     *
     * @param binding      verified provider binding
     * @param checkpoint   durable lifecycle checkpoint
     * @param workIntentId exact active intent
     * @param laneEpoch    exact intent version
     * @param action       targeted durable item
     */
    public DormantBinding(ProviderSessionBindingService.Binding binding,
        CodexLifecycleStateStore.Checkpoint checkpoint, String workIntentId, long laneEpoch,
        ActionableItem action) {
      this(binding, checkpoint, workIntentId, laneEpoch, action,
          binding.worktreePath(), binding.worktreePath());
    }
  }

  /**
   * Result of one bounded wake admission attempt.
   *
   * @param outcome          admission classification
   * @param operation        selected lifecycle operation, or {@code null}
   * @param participant      exact participant
   * @param bindingSessionId exact binding session
   * @param threadId         exact Codex thread
   * @param admissionKey     durable deduplication key
   * @param diagnostic       bounded diagnostic
   */
  public record WakeResult(Outcome outcome, LifecycleControlRequestEnvelope.Operation operation,
                           String participant, String bindingSessionId, String threadId,
                           String admissionKey,
                           String diagnostic) {

    /**
     * Validates and freezes the result.
     */
    public WakeResult {
      Objects.requireNonNull(outcome, "outcome");
      requireText(participant, "participant");
      requireText(bindingSessionId, "bindingSessionId");
      requireText(admissionKey, "admissionKey");
      diagnostic = diagnostic == null ? "" : diagnostic;
    }
  }

  /**
   * Bounded file-backed wake-admission ledger.
   *
   * <p>The ledger contains only wake keys, not provider credentials or
   * conversation content. It is written atomically and remains bounded.</p>
   */
  public static final class FileAdmissionStore implements AdmissionStore {

    private final Path file;

    /**
     * Creates a file-backed admission ledger.
     *
     * @param file ledger path
     */
    public FileAdmissionStore(Path file) {
      this.file = Objects.requireNonNull(file, "file")
          .toAbsolutePath()
          .normalize();
    }

    @Override
    public synchronized boolean contains(String key) throws IOException {
      return read().contains(key);
    }

    @Override
    public synchronized void record(String key) throws IOException {
      requireText(key, "admission key");
      LinkedHashSet<String> entries = read();
      if (!entries.contains(key) && entries.size() >= MAX_ADMISSION_ENTRIES) {
        throw new IOException("wake admission ledger exceeds bound");
      }
      entries.add(key);
      Files.createDirectories(file.getParent());
      Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(temporary, ProviderJson.write(Map.of("entries", List.copyOf(entries)))
              + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      try {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
      }
    }

    private LinkedHashSet<String> read() throws IOException {
      if (!Files.isRegularFile(file)) {
        return new LinkedHashSet<>();
      }
      if (Files.size(file) > MAX_ADMISSION_FILE_BYTES) {
        throw new IOException("wake admission ledger exceeds bound");
      }
      try {
        Object parsed = ProviderJson.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> raw) || !(raw.get("entries") instanceof List<?> values)) {
          throw new IOException("malformed wake admission ledger");
        }
        if (values.size() > MAX_ADMISSION_ENTRIES) {
          throw new IOException("wake admission ledger exceeds bound");
        }
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        for (Object value : values) {
          if (!(value instanceof String entry)) {
            throw new IOException("malformed wake admission entry");
          }
          requireText(entry, "admission entry");
          entries.add(entry);
        }
        if (entries.size() > MAX_ADMISSION_ENTRIES) {
          throw new IOException("wake admission ledger exceeds bound");
        }
        return entries;
      } catch (RuntimeException failure) {
        throw new IOException("malformed wake admission ledger", failure);
      }
    }
  }
}
