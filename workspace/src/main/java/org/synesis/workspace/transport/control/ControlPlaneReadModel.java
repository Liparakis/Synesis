package org.synesis.workspace.transport.control;

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
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.task.CoordinationTask;

import org.synesis.workspace.application.agent.workflow.CollaborationResponseMapper;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.doctor.DoctorFinding;
import org.synesis.workspace.doctor.DoctorRenderer;
import org.synesis.workspace.doctor.DoctorReport;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.transport.control.network.NetworkPeer;
import org.synesis.workspace.transport.control.network.NetworkRoute;
import org.synesis.workspace.transport.control.network.NetworkSnapshot;
import org.synesis.workspace.transport.control.network.OverlayEdge;
import org.synesis.workspace.transport.control.network.OverlaySummary;
import org.synesis.workspace.transport.control.network.RelaySummary;

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
     * Creates a read model with an unconfigured network view.
     *
     * @param location        project location
     * @param coordination    durable coordination service
     * @param providerService provider lifecycle service
     * @param doctorService   read-only diagnostic service
     */
    public ControlPlaneReadModel(ProjectLocation location, CoordinationService coordination, ProviderApplicationService providerService, DoctorService doctorService) {
        this(location, coordination, providerService, doctorService, NetworkSnapshot::empty);
    }

    /**
     * Creates a read model with an injected Link/overlay/relay view.
     *
     * @param location        project location
     * @param coordination    durable coordination service
     * @param providerService provider lifecycle service
     * @param doctorService   read-only diagnostic service
     * @param network         network state source
     */
    public ControlPlaneReadModel(ProjectLocation location, CoordinationService coordination, ProviderApplicationService providerService, DoctorService doctorService, Supplier<NetworkSnapshot> network) {
        this(location, coordination, providerService, doctorService, network, null);
    }

    /**
     * Creates a read model with the installation-local known-project index.
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
        result.put("network", networkMap(networkSnapshot));
        result.put("diagnostics", diagnostics());
        return result;
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
     * Registers a selected project directory in the installation-local discovery index,
     * initializing it through the normal application path when it has not been initialized yet.
     *
     * @param root project directory to validate and remember
     * @throws KnownProjectRegistry.RegistryException when no registry is configured, or validation,
     *                                                initialization, or persistence fails
     */
    public void registerKnownProject(Path root) throws KnownProjectRegistry.RegistryException {
        KnownProjectRegistry registry = requireRegistry();
        try {
            registry.register(root);
            return;
        } catch (KnownProjectRegistry.RegistryException notInitialized) {
            if (!"NOT_FOUND".equals(notInitialized.code())) {
                throw notInitialized;
            }
        }
        try {
            new ProjectApplicationService().initForRegistration(root);
            registry.register(root);
        } catch (ProjectApplicationService.ProjectApplicationException failure) {
            throw new KnownProjectRegistry.RegistryException("PROJECT_INITIALIZATION_FAILED", failure.getMessage());
        }
    }

    /**
     * Removes a non-current project from the installation-local discovery index.
     *
     * @param projectId stable project identity to remove
     * @throws KnownProjectRegistry.RegistryException when removal is not allowed, no registry is
     *                                                configured, or the project is not present
     */
    public void removeKnownProject(UUID projectId) throws KnownProjectRegistry.RegistryException {
        Objects.requireNonNull(projectId, "project ID");
        if (projectId.equals(location.projectId())) {
            throw new KnownProjectRegistry.RegistryException("CURRENT_PROJECT_PROTECTED", "the current runtime project cannot be removed");
        }
        if (!requireRegistry().remove(projectId)) {
            throw new KnownProjectRegistry.RegistryException("PROJECT_NOT_FOUND", "project is not present in the registry");
        }
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
     * Returns the configured registry.
     *
     * @return the project registry
     * @throws KnownProjectRegistry.RegistryException {@code PROJECT_REGISTRY_UNAVAILABLE} when none is configured
     */
    private KnownProjectRegistry requireRegistry() throws KnownProjectRegistry.RegistryException {
        if (projectRegistry == null) {
            throw new KnownProjectRegistry.RegistryException("PROJECT_REGISTRY_UNAVAILABLE", "project registry is unavailable");
        }
        return projectRegistry;
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "RUNNING");
        result.put("apiVersion", API_VERSION);
        result.put("version", Optional.ofNullable(ControlPlaneReadModel.class.getPackage().getImplementationVersion()).orElse("development"));
        result.put("activeProjectCount", 1);
        result.put("headSequence", coordination.headSequence());
        result.put("activeAgentCount", coordination.collaborationProjection().participants().size());
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
    private List<Map<String, Object>> agents() {
        List<Participant> participants = coordination.collaborationProjection().participants();
        List<WorkIntent> intents = coordination.collaborationProjection().activeIntents();
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
    private List<Map<String, Object>> workgroups() {
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
    private List<Map<String, Object>> claims() {
        List<WorkIntent> intents = coordination.collaborationProjection().activeIntents();
        return intents.stream().sorted(Comparator.comparing(WorkIntent::intentId)).limit(MAX_COLLECTION_ITEMS).map(intent -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("intentId", intent.intentId().toString());
            result.put("participant", intent.participant());
            result.put("workGroupId", intent.workGroupId().toString());
            result.put("role", intent.role().wireValue());
            result.put("state", intent.status().name());
            result.put("selectors", selectors(intent.selectors()));
            result.put("conflicts", conflicts(intent));
            return result;
        }).toList();
    }

    /**
     * Finds the other intents whose selectors overlap the given intent's.
     *
     * @param intent intent to check
     * @return conflicts, excluding the intent itself
     */
    private List<Map<String, Object>> conflicts(WorkIntent intent) {
        return coordination.collaborationProjection().conflicts(intent.selectors()).stream().filter(conflict -> !conflict.intentId().equals(intent.intentId().toString())).limit(MAX_COLLECTION_ITEMS).map(conflict -> {
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
    private List<Map<String, Object>> capabilities() {
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
                Map.of("inputs", record.contract().inputs(), "output", record.contract().output(), "requiredBehavior", record.contract().requiredBehavior(), "acceptanceTests", record.contract().acceptanceTests()));
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
     * Converts the network view into its JSON-compatible form. The projection has already bounded its lists.
     *
     * @param snapshot network view
     * @return network map
     */
    private static Map<String, Object> networkMap(NetworkSnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", snapshot.status());
        result.put("peers", snapshot.peers().stream().map(ControlPlaneReadModel::peerMap).toList());
        result.put("routes", snapshot.routes().stream().map(ControlPlaneReadModel::routeMap).toList());
        result.put("overlay", overlayMap(snapshot.overlay()));
        result.put("relay", relayMap(snapshot.relay()));
        return result;
    }

    /**
     * Converts a peer.
     *
     * @param peer network peer
     * @return peer map
     */
    private static Map<String, Object> peerMap(NetworkPeer peer) {
        return Map.of("nodeId",
                peer.nodeId(),
                "sessionId",
                peer.sessionId(),
                "liveness",
                peer.liveness(),
                "usable",
                peer.usable(),
                "establishedAt",
                peer.establishedAt(),
                "authenticated",
                peer.authenticated(),
                "health",
                peer.health());
    }

    /**
     * Converts a route.
     *
     * @param route network route
     * @return route map
     */
    private static Map<String, Object> routeMap(NetworkRoute route) {
        return Map.of("kind", route.kind(), "destinationNodeId", route.destinationNodeId(), "path", route.path(), "nextHop", route.nextHop(), "relayNodeId", route.relayNodeId());
    }

    /**
     * Converts the overlay summary.
     *
     * @param overlay overlay summary
     * @return overlay map
     */
    private static Map<String, Object> overlayMap(OverlaySummary overlay) {
        return Map.of("status",
                overlay.status(),
                "authorityNodeId",
                overlay.authorityNodeId(),
                "revision",
                overlay.revision(),
                "memberCount",
                overlay.memberCount(),
                "expiresAt",
                overlay.expiresAt(),
                "members",
                overlay.members().stream().map(member -> Map.of("nodeId", member.nodeId(), "status", member.status())).toList(),
                "directEdges",
                overlay.directEdges().stream().map(ControlPlaneReadModel::edgeMap).toList(),
                "desiredEdges",
                overlay.desiredEdges().stream().map(ControlPlaneReadModel::edgeMap).toList());
    }

    /**
     * Converts an overlay edge.
     *
     * @param edge overlay edge
     * @return edge map
     */
    private static Map<String, Object> edgeMap(OverlayEdge edge) {
        return Map.of("from", edge.fromNodeId(), "to", edge.toNodeId(), "status", edge.status());
    }

    /**
     * Converts the relay summary.
     *
     * @param relay relay summary
     * @return relay map
     */
    private static Map<String, Object> relayMap(RelaySummary relay) {
        return Map.of("status",
                relay.status(),
                "localAddress",
                relay.localAddress(),
                "activeConnections",
                relay.activeConnections(),
                "configured",
                relay.configured(),
                "connected",
                relay.connected(),
                "relayIdentity",
                relay.relayIdentity(),
                "authorized",
                relay.authorized(),
                "activeRouteUsage",
                relay.activeRouteUsage());
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
        return Map.of("kind", selector.kind().name(), "value", selector.value());
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