package org.synesis.workspace.application.agent.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.synesis.coordination.domain.collaboration.CollaborationProjection;
import org.synesis.coordination.domain.collaboration.WorkGroupProjection;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.contract.ContractProjection;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.integration.ConsumerAbsenceGuard;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.infrastructure.digest.Sha256;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Holds an agent that was told WAIT and ended its turn, until something is recorded for its own lane (ADR-0099).
 *
 * <p>Agents waited by sleeping, and slept through the review requests addressed to them. Now an agent ends its turn,
 * its provider's Stop hook calls {@link #awaitAddressed}, and the hook either lets it stop or tells it to call
 * {@code get_next_action}. Nothing here decides what the agent may do: a wake only says that the log now holds
 * something for its lane, and the agent's own next call is what answers it. A missed wake costs the hold, after which
 * the run controller resumes the stopped agent on the next progress; a duplicate costs one short turn.</p>
 *
 * <p>What is "for its lane" is read from the projections {@code get_next_action} reads, as a small view of the
 * lane: its requests, grants, reviews, task, contract and capability requests, unread messages and WorkGroup. Its lane
 * ending is a change too. The view the agent last saw, when it was told WAIT, is kept in a local note per connection,
 * so what arrived between that answer and the stop still wakes it. A wait that ends at a time rather than on an event
 * (a recovery deadline, a requester's answer period, a silent consumer's absence) is kept in the note as well, and
 * that time wakes the agent. The note is this computer's alone and never authority; without one the agent is not held.
 * An event replicated from another computer of the project lands in this computer's log like a local one, so it wakes
 * the agent the same way.</p>
 */
public final class LaneWake {

    /** Longest hold, Claude Code's own default hook timeout. Longer would hide for longer that a run is stuck. */
    public static final Duration HOLD = Duration.ofMinutes(10);

    /** Wakes allowed before the agent is told WAIT again. An agent that ignores its wakes is let stop. */
    static final int MAX_WAKES_WITHOUT_A_WAIT = 3;

    private static final long CHECK_EVERY_MILLIS = 400L;

    /** What each entry of the view is, as the woken agent is told. */
    private static final Map<String, String> WHAT_CHANGED = Map.of(
            "lane", "your lane changed",
            "requests", "a coordination request for your lane, or an answer to yours",
            "grants", "a lane grant for you",
            "reviews", "a review result",
            "task", "your task's completion state changed",
            "contracts", "a contract change request against your files, or an answer to yours",
            "capabilities", "a capability request of your lane changed",
            "messages", "a message",
            "group", "your WorkGroup's status changed");

    private LaneWake() {
    }

    /**
     * Who asked for capabilities on behalf of a lane, as capability requests record their requester.
     *
     * @param nodeId       node of the caller
     * @param supervisorId supervisor of the caller's session
     * @param workerId     worker of the caller's session
     */
    public record Requester(String nodeId, String supervisorId, String workerId) {
    }

    /**
     * Records that the agent behind one connection was just told WAIT, with its lane as it saw it.
     *
     * @param location             project location
     * @param connectionInstanceId connection the run controller gave the agent's process
     * @param participant          the participant of that connection's session
     * @param requester            the identity its capability requests carry, or {@code null} when unknown
     * @param waitEnds             when the answer said the wait ends, if it named a time
     * @throws IOException when the log or the note cannot be read or written
     */
    public static void noteWait(ProjectLocation location, String connectionInstanceId, String participant, Requester requester, OptionalLong waitEnds) throws IOException {
        PredictionEventStore store = open(location);
        OptionalLong deadline = earliest(waitEnds, consumerAbsence(location, store, participant));
        write(note(location, connectionInstanceId), new Note(participant, requester, deadline, 0), view(store, participant, requester));
    }

    /**
     * Forgets that the agent behind one connection waits: it was given something else to do.
     *
     * @param location             project location
     * @param connectionInstanceId connection the run controller gave the agent's process
     * @throws IOException when the note cannot be removed
     */
    public static void clearWait(ProjectLocation location, String connectionInstanceId) throws IOException {
        Files.deleteIfExists(note(location, connectionInstanceId));
    }

    /**
     * Holds a stopping agent until something is recorded for its lane, its wait's time comes, or the hold is up.
     *
     * @param location             project location
     * @param connectionInstanceId connection the run controller gave the agent's process
     * @param atMost               longest hold
     * @return what arrived, worded for the agent, or empty when it may stop: it was not told WAIT, held no open lane,
     *         ignored its last wakes, nothing arrived in time, or its state could not be read
     */
    public static Optional<String> awaitAddressed(ProjectLocation location, String connectionInstanceId, Duration atMost) {
        Path notePath = note(location, connectionInstanceId);
        try {
            if (!Files.isRegularFile(notePath)) {
                return Optional.empty();
            }
            Map<?, ?> waiting = (Map<?, ?>) ProviderJson.parse(Files.readString(notePath, StandardCharsets.UTF_8));
            Note note = Note.of(waiting);
            Map<?, ?> seen = (Map<?, ?>) waiting.get("view");
            Optional<String> woken = seen.isEmpty() || note.wakes() >= MAX_WAKES_WITHOUT_A_WAIT ? Optional.empty() : watch(location, notePath, note, seen, atMost);
            if (woken.isEmpty()) {
                // It stops; whatever runs it again is told WAIT afresh.
                Files.deleteIfExists(notePath);
            }
            return woken;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException unreadable) {
            // Holding an agent on state that cannot be read would keep it from stopping for nothing.
            return Optional.empty();
        }
    }

    private static Optional<String> watch(ProjectLocation location, Path notePath, Note note, Map<?, ?> seen, Duration atMost) throws IOException, InterruptedException {
        long holdEnds = System.currentTimeMillis() + atMost.toMillis();
        long head = -1L;
        while (true) {
            long current = PredictionEventStore.recordedEvents(location.coordinationDirectory());
            if (current != head) {
                head = current;
                Map<String, String> now = view(open(location), note.participant(), note.requester());
                List<String> changed = now.isEmpty() ? List.of() : now.keySet().stream().filter(entry -> !now.get(entry).equals(seen.get(entry))).toList();
                if (now.isEmpty() || !changed.isEmpty()) {
                    write(notePath, note.woken(), now);
                    return Optional.of("Synesis recorded something for your lane: " + (now.isEmpty() ? "your lane has ended" : changed.stream().map(WHAT_CHANGED::get).collect(Collectors.joining("; ")))
                            + ". Call get_next_action to see what to do.");
                }
            }
            long nowMillis = System.currentTimeMillis();
            if (note.deadline().isPresent() && nowMillis >= note.deadline().getAsLong()) {
                write(notePath, note.woken().withoutDeadline(), castView(seen));
                return Optional.of("The time Synesis gave your wait has passed. Call get_next_action to see what to do.");
            }
            if (nowMillis >= holdEnds) {
                return Optional.empty();
            }
            Thread.sleep(CHECK_EVERY_MILLIS);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> castView(Map<?, ?> view) {
        return (Map<String, String>) view;
    }

    /**
     * The lane of one participant as the log shows it, one entry per kind of thing that can be waiting for it. Empty
     * when the participant holds no open lane.
     */
    static Map<String, String> view(PredictionEventStore store, String participant, Requester requester) {
        Optional<WorkIntent> lane = WorkspaceCollaborationService.laneOf(store, participant);
        if (lane.isEmpty()) {
            return Map.of();
        }
        WorkIntent intent = lane.get();
        UUID lineage = intent.authorityLineageId();
        CollaborationProjection collaboration = store.collaborationProjection();
        WorkGroupProjection groups = store.workGroupProjection();
        ContractProjection contracts = store.contractProjection();
        Map<String, String> view = new TreeMap<>();
        view.put("lane", intent.status() + " " + intent.role() + " " + intent.version());
        view.put("requests", sorted(collaboration.requests().stream()
                .filter(request -> request.requester().equals(participant) || request.target().equals(participant)
                        || collaboration.intent(request.conflictingIntentId()).map(target -> target.authorityLineageId().equals(lineage)).orElse(false))
                .map(request -> request.requestId() + "=" + request.status())));
        // A grant names its reviewer; a review result names its reviewer and the implementer.
        view.put("grants", sorted(groups.grants().stream().filter(grant -> grant.targetParticipant().equals(participant))
                .map(grant -> grant.grantId() + "=" + groups.grantConsumed(grant.grantId()) + "/" + groups.grantSuperseded(grant.grantId()))));
        view.put("reviews", sorted(groups.reviewValidations().stream()
                .filter(review -> participant.equals(review.targetParticipant()) || participant.equals(review.sourceParticipant()) || intent.taskId().equals(review.taskId()))
                .map(review -> review.grantId() + "=" + review.result())));
        view.put("task", String.valueOf(store.taskCompletionProjection().taskState(intent.taskId())));
        view.put("contracts", sorted(Stream.concat(contracts.openChangeRequestsFor(lineage).stream(), contracts.changeRequestsFiledBy(lineage).stream())
                .map(change -> change.id() + "=" + change.state() + "@" + change.answeredByRevision()).distinct()));
        // The owner side carries the owner's lineage; the consumer side is matched by who asked, as the projection does.
        view.put("capabilities", sorted(store.capabilityRequestProjection().records().values().stream()
                .filter(request -> lineage.equals(request.authorityLineageId())
                        || requester != null && request.matchesRequester(requester.nodeId(), requester.supervisorId(), requester.workerId()))
                .map(request -> request.handle().value() + "=" + request.state() + "@" + request.contractRevision())));
        view.put("messages", sorted(store.conversationProjection().unread(lineage).stream().map(message -> message.id().toString())));
        view.put("group", groups.group(intent.workGroupId()).map(group -> group.status().name()).orElse(""));
        return view;
    }

    /** When the owner lane of this participant stops waiting for a silent consumer (ADR-0092). */
    private static OptionalLong consumerAbsence(ProjectLocation location, PredictionEventStore store, String participant) {
        try {
            Optional<UUID> lineage = WorkspaceCollaborationService.laneOf(store, participant).map(WorkIntent::authorityLineageId);
            return lineage.isEmpty() ? OptionalLong.empty() : ConsumerAbsenceGuard.owed(location.root(), store).stream()
                    .filter(owed -> owed.binding().authorityLineageId().equals(lineage.get())).mapToLong(ConsumerAbsenceGuard.Owed::absentAtEpochMillis).min();
        } catch (Exception unreadable) {
            return OptionalLong.empty();
        }
    }

    private static OptionalLong earliest(OptionalLong first, OptionalLong second) {
        return first.isEmpty() ? second : second.isEmpty() ? first : OptionalLong.of(Math.min(first.getAsLong(), second.getAsLong()));
    }

    private static String sorted(Stream<String> values) {
        return values.sorted().collect(Collectors.joining(","));
    }

    private static PredictionEventStore open(ProjectLocation location) throws IOException {
        try {
            return location.openCoordinationStore();
        } catch (GeneralSecurityException unverifiable) {
            throw new IOException("coordination log could not be verified", unverifiable);
        }
    }

    /** One note per connection, named by its fingerprint as provider session bindings name it. */
    private static Path note(ProjectLocation location, String connectionInstanceId) {
        return location.synesisDirectory().resolve("local").resolve("waits").resolve(Sha256.hex(connectionInstanceId) + ".json");
    }

    private static void write(Path notePath, Note note, Map<String, String> view) throws IOException {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("participant", note.participant());
        content.put("wakes", note.wakes());
        if (note.requester() != null) {
            content.put("requester", java.util.Arrays.asList(note.requester().nodeId(), note.requester().supervisorId(), note.requester().workerId()));
        }
        note.deadline().ifPresent(deadline -> content.put("deadline", deadline));
        content.put("view", view);
        Files.createDirectories(notePath.getParent());
        Path written = Files.createTempFile(notePath.getParent(), "wait", ".tmp");
        try {
            Files.writeString(written, ProviderJson.write(content), StandardCharsets.UTF_8);
            Files.move(written, notePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(written);
        }
    }

    /** What the note says about the wait, beside the view. */
    private record Note(String participant, Requester requester, OptionalLong deadline, int wakes) {

        static Note of(Map<?, ?> stored) {
            Requester requester = stored.get("requester") instanceof List<?> ids && ids.size() == 3
                    ? new Requester((String) ids.get(0), (String) ids.get(1), (String) ids.get(2)) : null;
            OptionalLong deadline = stored.get("deadline") instanceof Number at ? OptionalLong.of(at.longValue()) : OptionalLong.empty();
            return new Note((String) stored.get("participant"), requester, deadline, ((Number) stored.get("wakes")).intValue());
        }

        Note woken() {
            return new Note(participant, requester, deadline, wakes + 1);
        }

        Note withoutDeadline() {
            return new Note(participant, requester, OptionalLong.empty(), wakes);
        }
    }
}
