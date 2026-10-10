package org.synesis.workspace.transport.control;

import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.ordered;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Supplier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.CollaborationProjection;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.task.CoordinationTask;

import org.synesis.workspace.application.agent.workflow.CollaborationResponseMapper;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.doctor.DoctorFinding;
import org.synesis.workspace.doctor.DoctorRenderer;
import org.synesis.workspace.doctor.DoctorReport;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.transport.control.network.NetworkSnapshot;

/**
 * Explicit, public-safe read model for the local Synesis control plane.
 *
 * <p>The model is deliberately assembled field by field from durable projections. It does not
 * expose arbitrary filesystem reads, internal recovery references, authority lineages, provider
 * configuration, or raw diagnostic detail maps.
 */
public final class ControlPlaneReadModel {

    /**
     * Value of the {@code apiVersion} field in the snapshot and runtime section.
     */
    private static final String API_VERSION = "v1";

    /**
     * Upper bound on every collection in a response, keeping snapshots bounded.
     */
    private static final int MAX_COLLECTION_ITEMS = 256;

    /**
     * Location of the current project.
     */
    private final ProjectLocation location;

    /**
     * Durable coordination service for the current project.
     */
    private final CoordinationService coordination;

    /**
     * Provider lifecycle service for the current project.
     */
    private final ProviderApplicationService providerService;

    /**
     * Read-only diagnostic service for the current project.
     */
    private final DoctorService doctorService;

    /**
     * Source of the Link/overlay/relay view; evaluated once per snapshot.
     */
    private final Supplier<NetworkSnapshot> network;

    /**
     * Installation-local discovery index, or {@code null} when none is configured.
     */
    private final KnownProjectRegistry projectRegistry;

    /**
     * Creates a read model over the current project's durable services and network view.
     *
     * @param location        current project location
     * @param coordination    durable coordination service for the current project
     * @param providerService provider lifecycle service for the current project
     * @param doctorService   read-only diagnostic service for the current project
     * @param network         authoritative network state source
     * @param projectRegistry installation-local discovery index, or {@code null} to track only the current project
     */
    public ControlPlaneReadModel(ProjectLocation location,
            CoordinationService coordination,
            ProviderApplicationService providerService,
            DoctorService doctorService,
            Supplier<NetworkSnapshot> network,
            KnownProjectRegistry projectRegistry) {
        this.location = Objects.requireNonNull(location, "location");
        this.coordination = Objects.requireNonNull(coordination, "coordination");
        this.providerService = Objects.requireNonNull(providerService, "provider service");
        this.doctorService = Objects.requireNonNull(doctorService, "doctor service");
        this.network = Objects.requireNonNull(network, "network");
        this.projectRegistry = projectRegistry;
    }

    /**
     * Builds a complete bounded dashboard snapshot.
     *
     * @return JSON-compatible snapshot map
     */
    public Map<String, Object> snapshot() {
        NetworkSnapshot networkSnapshot = network.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apiVersion", API_VERSION);
        result.put("runtime", runtime(networkSnapshot));
        result.put("project", project());
        result.put("knownProjects", knownProjects());
        result.put("providers", providers());
        result.put("agents", agents());
        result.put("workgroups", workgroups());
        result.put("claims", claims());
        result.put("capabilities", capabilities());
        result.put("tasks", tasks());
        result.put("ownerships", ownerships());
        result.put("network", networkSnapshot.toMap());
        result.put("diagnostics", diagnostics());
        return result;
    }

    /**
     * Builds the Link, overlay, and relay view from the injected network source.
     *
     * @return JSON-compatible network map
     */
    public Map<String, Object> network() {
        return network.get().toMap();
    }

    /**
     * Builds the current read-only doctor view.
     *
     * @return JSON-compatible diagnostics map
     */
    public Map<String, Object> diagnostics() {
        DoctorReport report = doctorService.diagnose(location.root());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", report.schemaVersion());
        result.put("reportId", report.reportId());
        result.put("projectId", report.projectId());
        result.put("timestampEpochMillis", report.timestampEpochMillis());
        result.put("overallStatus", report.overallStatus().name());
        result.put("criticalCount", report.criticalCount());
        result.put("errorCount", report.errorCount());
        result.put("warningCount", report.warningCount());
        result.put("infoCount", report.infoCount());
        result.put("cleanupRecommended", report.cleanupRecommended());
        result.put("reconciliationRecommended", report.reconciliationRecommended());
        result.put("repairAvailable", report.repairAvailable());
        result.put("findings", report.findings().stream().limit(MAX_COLLECTION_ITEMS).map(ControlPlaneReadModel::finding).toList());
        return result;
    }

    /**
     * Returns the current project identity.
     *
     * @return project identifier
     */
    public UUID projectId() {
        return location.projectId();
    }

    /**
     * Returns bounded discovery metadata for projects known to this installation.
     *
     * <p>Only the current control-plane project is marked live. Other entries are read from
     * validated metadata and never from stale runtime snapshots. Without a registry the current
     * project is the only entry; a registry that cannot be read yields an empty list.
     *
     * @return JSON-compatible known-project list
     */
    public List<Map<String, Object>> knownProjects() {
        if (projectRegistry == null) {
            Map<String, Object> current = project();
            current.put("firstObservedAt", current.get("createdAt"));
            current.put("lastObservedAt", current.get("createdAt"));
            current.put("status", "LIVE");
            return List.of(current);
        }
        try {
            return projectRegistry.projectsForReplicas(Set.of(location.root())).stream().limit(MAX_COLLECTION_ITEMS).map(view -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", view.projectId().toString());
                result.put("name", displayName(view.path()));
                result.put("path", view.path().toString());
                result.put("createdAt", view.createdAt().toString());
                result.put("firstObservedAt", view.firstObservedAt().toString());
                result.put("lastObservedAt", view.lastObservedAt().toString());
                result.put("status", view.status().name());
                return result;
            }).toList();
        } catch (KnownProjectRegistry.RegistryException unavailable) {
            return List.of();
        }
    }

    /**
     * Names a project directory by its last path segment, falling back to the whole path for a root.
     *
     * @param path project directory
     * @return display name
     */
    private static String displayName(Path path) {
        Path fileName = path.getFileName();
        return (fileName == null ? path : fileName).toString();
    }

    /**
     * Caps a list at {@link #MAX_COLLECTION_ITEMS}.
     *
     * @param values source list
     * @param <T>    element type
     * @return at most {@link #MAX_COLLECTION_ITEMS} leading elements
     */
    private static <T> List<T> bounded(List<T> values) {
        return values.stream().limit(MAX_COLLECTION_ITEMS).toList();
    }

    /**
     * Builds the runtime summary section.
     *
     * @param networkSnapshot network view captured for this snapshot
     * @return runtime map
     */
    private Map<String, Object> runtime(NetworkSnapshot networkSnapshot) {
        CollaborationProjection collaboration = coordination.collaborationProjection();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "RUNNING");
        result.put("apiVersion", API_VERSION);
        result.put("version", Optional.ofNullable(ControlPlaneReadModel.class.getPackage().getImplementationVersion()).orElse("development"));
        result.put("activeProjectCount", 1);
        result.put("headSequence", coordination.headSequence());
        result.put("activeAgentCount", collaboration.participants().size());
        result.put("connectedPeerCount", networkSnapshot.peers().size());
        result.put("networkStatus", networkSnapshot.status());
        result.put("relayStatus", networkSnapshot.relay().status());
        return result;
    }

    /**
     * Builds the current project section.
     *
     * @return mutable project map
     */
    private Map<String, Object> project() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", location.projectId().toString());
        result.put("name", displayName(location.root()));
        result.put("path", location.root().toString());
        result.put("createdAt", location.createdAt().toString());
        return result;
    }

    /**
     * Builds the provider list.
     *
     * @return providers with id, support level, and status only
     */
    private List<Map<String, Object>> providers() {
        return providerService.list(location).stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", row.id());
            result.put("supportLevel", row.supportLevel().name());
            result.put("status", row.status());
            return result;
        }).toList();
    }

    /**
     * Builds the agent list: every known participant, followed by synthetic {@code UNKNOWN} entries
     * for participants that hold active intents without being registered.
     *
     * @return agents, capped at {@link #MAX_COLLECTION_ITEMS}
     */
    public List<Map<String, Object>> agents() {
        CollaborationProjection collaboration = coordination.collaborationProjection();
        List<Participant> participants = collaboration.participants();
        List<WorkIntent> intents = collaboration.activeIntents();
        Map<String, List<WorkIntent>> byParticipant = new LinkedHashMap<>();
        intents.forEach(intent -> byParticipant.computeIfAbsent(intent.participant(), ignored -> new ArrayList<>()).add(intent));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Participant participant : participants) {
            result.add(agent(participant, byParticipant.getOrDefault(participant.id(), List.of())));
        }
        byParticipant.forEach((participantId, participantIntents) -> {
            if (participants.stream().noneMatch(participant -> participant.id().equals(participantId))) {
                result.add(unregisteredAgent(participantId, participantIntents.getFirst()));
            }
        });
        return bounded(result);
    }

    /**
     * Builds a registered agent.
     *
     * @param participant registered participant
     * @param intents     the participant's active intents
     * @return agent map
     */
    private static Map<String, Object> agent(Participant participant, List<WorkIntent> intents) {
        Map<String, Object> result = CollaborationResponseMapper.participantFields(participant);
        result.put("claims", selectors(participant.claims()));
        result.put("currentWork", intents.stream().findFirst().map(ControlPlaneReadModel::workIntent).orElse(null));
        result.put("waitingOn", intents.stream().flatMap(intent -> intent.knownDependencies().stream()).distinct().toList());
        return result;
    }

    /**
     * Builds an agent for a participant that has active work but no registration, so its work stays
     * visible without claiming a verified state.
     *
     * @param participantId participant id taken from the intent
     * @param intent        the participant's first active intent
     * @return agent map with state {@code UNKNOWN}
     */
    private static Map<String, Object> unregisteredAgent(String participantId, WorkIntent intent) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", participantId);
        result.put("provider", intent.provider());
        result.put("goal", intent.goal());
        result.put("state", "UNKNOWN");
        result.put("lastVerifiedActivity", null);
        result.put("claims", selectors(intent.selectors()));
        result.put("currentWork", workIntent(intent));
        return result;
    }

    /**
     * Builds the work group list, ordered by id.
     *
     * @return work groups with the distinct participants of their active intents
     */
    public List<Map<String, Object>> workgroups() {
        List<WorkIntent> intents = coordination.collaborationProjection().activeIntents();
        return coordination.workGroupProjection().groups().stream().sorted(Comparator.comparing(WorkGroup::workGroupId)).limit(MAX_COLLECTION_ITEMS).map(group -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", group.workGroupId().toString());
            result.put("status", group.status().name());
            result.put("version", group.version());
            result.put("goal", group.goal());
            result.put("acceptance", group.acceptance());
            result.put("participants", intents.stream().filter(intent -> group.workGroupId().equals(intent.workGroupId())).map(WorkIntent::participant).distinct().limit(MAX_COLLECTION_ITEMS).toList());
            return result;
        }).toList();
    }

    /**
     * Builds the claim list: one entry per active intent, ordered by intent id.
     *
     * @return claims with their conflicting selectors
     */
    public List<Map<String, Object>> claims() {
        CollaborationProjection collaboration = coordination.collaborationProjection();
        return collaboration.activeIntents().stream().sorted(Comparator.comparing(WorkIntent::intentId)).limit(MAX_COLLECTION_ITEMS).map(intent -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("intentId", intent.intentId().toString());
            result.put("participant", intent.participant());
            result.put("workGroupId", intent.workGroupId().toString());
            result.put("role", intent.role().wireValue());
            result.put("state", intent.status().name());
            result.put("selectors", selectors(intent.selectors()));
            result.put("conflicts", conflicts(collaboration, intent));
            return result;
        }).toList();
    }

    /**
     * Finds the other intents whose selectors overlap the given intent's.
     *
     * @param collaboration the claims view the section is built from
     * @param intent        intent to check
     * @return conflicts, excluding the intent itself
     */
    private static List<Map<String, Object>> conflicts(CollaborationProjection collaboration, WorkIntent intent) {
        return collaboration.conflicts(intent.selectors()).stream().filter(conflict -> !conflict.intentId().equals(intent.intentId().toString())).limit(MAX_COLLECTION_ITEMS).map(conflict -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("participant", conflict.participant());
            result.put("intentId", conflict.intentId());
            result.put("selector", selector(conflict.selector()));
            return result;
        }).toList();
    }

    /**
     * Builds the capability request list, ordered by handle.
     *
     * @return capability requests
     */
    public List<Map<String, Object>> capabilities() {
        return coordination.capabilityRequestProjection().records().values().stream().sorted(Comparator.comparing(record -> record.handle().value())).limit(MAX_COLLECTION_ITEMS).map(ControlPlaneReadModel::capability)
                .toList();
    }

    /**
     * Builds one capability request.
     *
     * @param record capability request record
     * @return capability map
     */
    private static Map<String, Object> capability(CapabilityRequestRecord record) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("handle", record.handle().value());
        result.put("capability", record.capability());
        result.put("requester", record.requesterNodeId());
        result.put("owner", record.ownerNodeId());
        result.put("state", record.state().name());
        result.put("reason", record.reason());
        result.put("createdAtEpochMillis", record.createdAtEpochMillis());
        result.put("updatedAtEpochMillis", record.updatedAtEpochMillis());
        result.put("contract",
                ordered("inputs", record.contract().inputs(), "output", record.contract().output(), "requiredBehavior", record.contract().requiredBehavior(), "acceptanceTests", record.contract().acceptanceTests()));
        return result;
    }

    /**
     * Builds the task list, ordered by task id.
     *
     * @return tasks with id, title, capability, and owner only
     */
    private List<Map<String, Object>> tasks() {
        return coordination.coordinationProjection().tasks().values().stream().sorted(Comparator.comparing(view -> view.task().taskId())).limit(MAX_COLLECTION_ITEMS).map(view -> {
            CoordinationTask task = view.task();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", task.taskId().toString());
            result.put("title", task.title());
            result.put("capability", task.capability());
            result.put("owner", view.ownerNodeId());
            return result;
        }).toList();
    }

    /**
     * Builds the ownership list, ordered by capability.
     *
     * @return ownership claims
     */
    private List<Map<String, Object>> ownerships() {
        return coordination.coordinationProjection().ownerships().values().stream().sorted(Comparator.comparing(OwnershipClaim::capability)).limit(MAX_COLLECTION_ITEMS).map(claim -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("capability", claim.capability());
            result.put("taskId", claim.taskId().toString());
            result.put("owner", claim.ownerNodeId());
            result.put("protectedScopes", claim.protectedScopes());
            result.put("intentVersion", claim.intentVersion());
            return result;
        }).toList();
    }

    /**
     * Converts selectors.
     *
     * @param selectors resource selectors
     * @return selector maps
     */
    private static List<Map<String, Object>> selectors(List<ResourceSelector> selectors) {
        return selectors.stream().map(ControlPlaneReadModel::selector).toList();
    }

    /**
     * Converts one selector.
     *
     * @param selector resource selector
     * @return selector map
     */
    private static Map<String, Object> selector(ResourceSelector selector) {
        return ordered("kind", selector.kind().name(), "value", selector.value());
    }

    /**
     * Converts a doctor finding, rendering its code by name.
     *
     * @param finding doctor finding
     * @return finding map without raw detail
     */
    private static Map<String, Object> finding(DoctorFinding finding) {
        return DoctorRenderer.findingMap(finding, finding.code().name());
    }

    /**
     * Converts a work intent.
     *
     * @param intent work intent
     * @return intent map
     */
    private static Map<String, Object> workIntent(WorkIntent intent) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intentId", intent.intentId().toString());
        result.put("taskId", intent.taskId().toString());
        result.put("goal", intent.goal());
        result.put("acceptance", intent.acceptance());
        result.put("status", intent.status().name());
        result.put("role", intent.role().wireValue());
        result.put("workGroupId", intent.workGroupId().toString());
        return result;
    }
}