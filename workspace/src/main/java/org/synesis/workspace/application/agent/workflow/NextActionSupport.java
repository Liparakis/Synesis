package org.synesis.workspace.application.agent.workflow;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.application.project.ProjectLocation;

/**
 * Lookups and wire-map builders shared by the next-action projections, together with the single
 * place that opens the durable event store and reports suppressed failures.
 *
 * <p>Kept apart from {@link AgentNextActionService} so the projections can use them without
 * depending on the orchestrator that calls them. The logger keeps the service's name so existing
 * log configuration for suppressed failures continues to apply.</p>
 */
final class NextActionSupport {

    /**
     * Logger used to surface failures that are deliberately converted into a fallback result.
     */
    private static final System.Logger LOGGER = System.getLogger(AgentNextActionService.class.getName());

    /**
     * Project-relative directory holding the durable coordination event store.
     */
    private static final String COORDINATION_DIR = ".synesis/coordination";

    /**
     * Sub-directory of {@link #COORDINATION_DIR} whose existence signals a populated event log.
     */
    private static final String EVENTS_DIR = "events";

    private NextActionSupport() {
    }

    /**
     * Opens the durable event store of a project without checking that it exists yet.
     *
     * @param location project location
     * @return a new store snapshot
     * @throws Exception if the store cannot be opened
     */
    static PredictionEventStore newEventStore(ProjectLocation location) throws Exception {
        return new PredictionEventStore(location.root().resolve(COORDINATION_DIR), location.projectId());
    }

    /**
     * Opens the durable event store only when an event log exists.
     *
     * @param location project location
     * @return a new store snapshot, or empty when the project has no event log yet
     * @throws Exception if the store cannot be opened
     */
    static Optional<PredictionEventStore> openEventStore(ProjectLocation location) throws Exception {
        if (!Files.exists(location.root().resolve(COORDINATION_DIR).resolve(EVENTS_DIR))) {
            return Optional.empty();
        }
        return Optional.of(newEventStore(location));
    }

    /**
     * Finds the first active intent owned by a participant.
     *
     * @param store         current project projection
     * @param participantId exact participant handle
     * @return the participant's active intent, if any
     */
    static Optional<WorkIntent> activeIntentOf(PredictionEventStore store, String participantId) {
        return store.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(participantId)).findFirst();
    }

    /**
     * Returns the lifecycle state of a snapshot, defaulting to {@link TaskCompletionState#ACTIVE}.
     *
     * @param store    current project projection
     * @param snapshot the snapshot to look up
     * @return the recorded state, or {@code ACTIVE} when none has been recorded
     */
    static TaskCompletionState snapshotStateOf(PredictionEventStore store, TaskSnapshotRecord snapshot) {
        return store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElse(TaskCompletionState.ACTIVE);
    }

    /**
     * Returns whether an intent is a producer lane owned by the participant.
     *
     * @param intent        the intent to test
     * @param participantId exact participant handle
     * @return {@code true} for the participant's own {@code PRODUCER} intents
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    static boolean isProducerLane(WorkIntent intent, String participantId) {
        return participantId.equals(intent.participant()) && intent.role() == WorkIntent.Role.PRODUCER;
    }

    /**
     * Appends the server-derived revision and ownership fields every finish payload carries.
     *
     * @param payload          payload being built
     * @param intent           the lane's intent
     * @param participantId    exact caller participant
     * @param workGroupVersion current optimistic version of the work group
     * @param expectedRevision current head sequence of the event store
     */
    static void putLaneFence(Map<String, Object> payload, WorkIntent intent, String participantId, long workGroupVersion, long expectedRevision) {
        payload.put(NextActionKeys.INTENT_ID, intent.intentId().toString());
        payload.put(NextActionKeys.WORK_GROUP_ID, intent.workGroupId().toString());
        payload.put(NextActionKeys.CLAIM_EPOCH, intent.version());
        payload.put("workGroupVersion", workGroupVersion);
        payload.put("expectedRevision", expectedRevision);
        payload.put(NextActionKeys.PARTICIPANT, participantId);
    }

    /**
     * Appends the three-part protocol directive that tells the provider which tool to call next.
     *
     * @param result  response detail map being built
     * @param action  protocol action, such as {@link NextActionProtocol#ACTION_WAIT}
     * @param kind    protocol kind qualifying the action
     * @param payload executable arguments for the action
     */
    static void putNextProtocol(Map<String, Object> result, String action, String kind, Map<String, Object> payload) {
        result.put(NextActionKeys.NEXT_PROTOCOL_ACTION, action);
        result.put(NextActionKeys.NEXT_PROTOCOL_KIND, kind);
        result.put(NextActionKeys.NEXT_PROTOCOL_PAYLOAD, payload);
    }

    /**
     * Converts an intent's selectors into their public wire representation.
     *
     * @param intent the intent whose selectors to convert
     * @return the selector maps in intent order
     */
    static List<Map<String, Object>> selectorMaps(WorkIntent intent) {
        return intent.selectors().stream().map(CollaborationResponseMapper::selectorMap).toList();
    }

    /**
     * Reads a list of maps out of a collaboration projection.
     *
     * @param collaboration public collaboration projection
     * @param key           key whose value is a list of maps
     * @return the stored list, or an empty list when the key is absent
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> projectedList(Map<String, Object> collaboration, String key) {
        return (List<Map<String, Object>>) collaboration.getOrDefault(key, List.of());
    }

    /**
     * Returns whether a string is {@code null} or consists only of whitespace.
     *
     * @param value the string to test
     * @return {@code true} if {@code value} is {@code null} or blank
     */
    static boolean isNullOrBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Reports a failure that is deliberately converted into a fallback result.
     *
     * <p>Keeping the fallback while still emitting the cause makes a persistently failing
     * projection diagnosable instead of silently degrading to a less specific response.</p>
     *
     * @param operation short description of the step that failed
     * @param failure   the suppressed failure
     */
    static void logSuppressed(String operation, Exception failure) {
        LOGGER.log(System.Logger.Level.WARNING, "Next-action step failed and was suppressed: " + operation, failure);
    }
}
