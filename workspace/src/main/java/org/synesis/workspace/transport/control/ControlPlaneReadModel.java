package org.synesis.workspace.transport.control;

import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.ordered;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Supplier;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.CollaborationProjection;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.membership.MembershipProjection;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.task.CoordinationTask;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.link.identity.IdentityBootstrap;

import org.synesis.workspace.application.conversation.ConversationService;
import org.synesis.workspace.application.agent.workflow.CollaborationResponseMapper;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.discovery.InstallationHome;
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
    /** Newest messages of one conversation the dashboard is sent. */
    private static final int MAX_CONVERSATION_MESSAGES = 50;

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
        // Served from the installation's own workspace, the page has no current project.
        result.put("project", InstallationHome.is(location.root()) ? null : project());
        result.put("knownProjects", knownProjects());
        result.put("providers", providers());
        result.put("agents", agents());
        result.put("workgroups", workgroups());
        result.put("claims", claims());
        result.put("capabilities", capabilities());
        result.put("conversations", conversations());
        result.put("operatorRequests", operatorRequests());
        List<Map<String, Object>> trash = trash();
        Map<String, String> workGroupOfTask = workGroupOfTask(trash);
        result.put("tasks", tasks(workGroupOfTask));
        result.put("disputes", disputes(workGroupOfTask));
        result.put("trash", trash);
        result.put("ownerships", ownerships(workGroupOfTask));
        result.put("membership", membership());
        result.put("network", networkSnapshot.toMap());
        result.put("diagnostics", diagnostics());
        return result;
    }

    /**
     * Builds the Link, overlay, and relay view from the injected network source.
     *
     * @return JSON-compatible network map
     */
    Map<String, Object> network() {
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
     * Returns the location of the project this runtime serves.
     *
     * @return project location
     */
    ProjectLocation location() {
        return location;
    }

    /**
     * Returns the provider service the model lists providers from, so agents are started only on providers it shows installed.
     *
     * @return provider lifecycle service
     */
    ProviderApplicationService providerService() {
        return providerService;
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
    List<Map<String, Object>> workgroups() {
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
     * Builds what agents said to each other, for the operator to read (ADR-0093). The operator
     * reads every conversation; the dashboard has no way to post in one.
     *
     * @return conversations in the order each began, each with its newest messages
     */
    List<Map<String, Object>> conversations() {
        return ConversationService.conversations(coordination.collaborationProjection(), coordination.conversationProjection().messages(), MAX_CONVERSATION_MESSAGES).stream().limit(MAX_COLLECTION_ITEMS).toList();
    }

    /**
     * Builds the open operator requests, oldest first.
     *
     * @return what the project is waiting on an operator for, or was asked by a lane
     */
    List<Map<String, Object>> operatorRequests() {
        return coordination.operatorRequestProjection().open().stream().limit(MAX_COLLECTION_ITEMS)
                .map(request -> ordered("id", request.id(), "condition", request.condition(), "raisedBy", request.raisedBy(), "summary", request.summary(), "evidence", request.evidence()))
                .toList();
    }

    /**
     * Builds the capability request list, ordered by handle.
     *
     * @return capability requests
     */
    List<Map<String, Object>> capabilities() {
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
     * @param workGroupOfTask the WorkGroup of each task that has exactly one
     * @return tasks with id, title, capability, owner and WorkGroup only
     */
    private List<Map<String, Object>> tasks(Map<String, String> workGroupOfTask) {
        return coordination.coordinationProjection().tasks().values().stream().sorted(Comparator.comparing(view -> view.task().taskId())).limit(MAX_COLLECTION_ITEMS).map(view -> {
            CoordinationTask task = view.task();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", task.taskId().toString());
            result.put("title", task.title());
            result.put("capability", task.capability());
            result.put("owner", view.ownerNodeId());
            result.put("workGroupId", workGroupOfTask.get(task.taskId().toString()));
            return result;
        }).toList();
    }

    /**
     * Builds the record of review disputes, oldest first: each snapshot whose reviewers disagreed, their verdicts now,
     * and the snapshot's state, which says whether the dispute is open ({@code review_disputed}) or how it ended
     * (ADR-0098 step 7).
     *
     * @param workGroupOfTask the WorkGroup of each task that has exactly one
     * @return disputes
     */
    private List<Map<String, Object>> disputes(Map<String, String> workGroupOfTask) {
        var completion = coordination.taskCompletionProjection();
        return completion.disputedSnapshots().stream().limit(MAX_COLLECTION_ITEMS).map(id -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("snapshotId", id.value());
            completion.findSnapshotById(id).ifPresent(snapshot -> {
                result.put("taskId", snapshot.taskId().toString());
                result.put("workGroupId", workGroupOfTask.get(snapshot.taskId().toString()));
                result.put("author", snapshot.provenance().participant());
            });
            result.put("verdicts", new java.util.TreeMap<>(completion.verdicts(id)));
            result.put("state", completion.snapshotState(id).map(TaskCompletionState::value).orElse("unknown"));
            return result;
        }).toList();
    }

    /**
     * Builds the trash, oldest first: work taken over, finished after its goal was met, or given up in a dispute, and
     * whether a person restored or emptied it (ADR-0098 step 7).
     *
     * @return trashed work
     */
    private List<Map<String, Object>> trash() {
        return org.synesis.workspace.application.collaboration.WorkTrash.describe(coordination.trashProjection()).stream()
                .limit(MAX_COLLECTION_ITEMS).toList();
    }

    /**
     * Builds the ownership list, ordered by capability.
     *
     * @param workGroupOfTask the WorkGroup of each task that has exactly one
     * @return ownership claims
     */
    private List<Map<String, Object>> ownerships(Map<String, String> workGroupOfTask) {
        return coordination.coordinationProjection().ownerships().values().stream().sorted(Comparator.comparing(OwnershipClaim::capability)).limit(MAX_COLLECTION_ITEMS).map(claim -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("capability", claim.capability());
            result.put("taskId", claim.taskId().toString());
            result.put("workGroupId", workGroupOfTask.get(claim.taskId().toString()));
            result.put("owner", claim.ownerNodeId());
            result.put("protectedScopes", claim.protectedScopes());
            result.put("intentVersion", claim.intentVersion());
            return result;
        }).toList();
    }

    /**
     * Finds the WorkGroup of each task: the group its lanes work in, active or trashed. A task with no lane, or with
     * lanes in several groups (restored trash is worked in a group of its own), has no entry, so no record claims a
     * WorkGroup it may not belong to.
     *
     * @param trash the trash section already built
     * @return WorkGroup identifier by task identifier
     */
    private Map<String, String> workGroupOfTask(List<Map<String, Object>> trash) {
        Map<String, Set<String>> groups = new HashMap<>();
        for (WorkIntent intent : coordination.collaborationProjection().activeIntents()) {
            groups.computeIfAbsent(intent.taskId().toString(), task -> new TreeSet<>()).add(intent.workGroupId().toString());
        }
        for (Map<String, Object> item : trash) {
            groups.computeIfAbsent(String.valueOf(item.get("taskId")), task -> new TreeSet<>()).add(String.valueOf(item.get("workGroupId")));
        }
        Map<String, String> result = new HashMap<>();
        groups.forEach((task, taskGroups) -> {
            if (taskGroups.size() == 1) {
                result.put(task, taskGroups.iterator().next());
            }
        });
        return result;
    }

    /**
     * Builds who belongs to the project: its owner, whether this computer is that owner, and each computer with its
     * connection (ADR-0103 stage 3). It is the rule {@code synesis status} follows ({@link ProjectMembers}), so the CLI
     * and the page agree. The process that serves a snapshot is the project's runtime, so connections are as it
     * recorded them. A role file that cannot be read leaves the project looking like one on a single computer; it never
     * invents a member, and removing one is checked again by the owner rule of the log.
     *
     * @return membership map; {@code members} is empty for a project on one computer
     */
    private Map<String, Object> membership() {
        Path data = location.coordinationDirectory();
        var inspection = new IdentityBootstrap(location.linkDirectory()).inspect();
        String self = inspection.identityPresent() && inspection.identityValid() ? inspection.nodeId() : "";
        Optional<ReplicationSettings> settings;
        try {
            settings = ReplicationSettings.read(data);
        } catch (IOException unreadable) {
            settings = Optional.empty();
        }
        MembershipProjection projection = coordination.membershipProjection();
        ProjectMembers members = ProjectMembers.read(data, projection, settings, self, true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shared", members.shared());
        result.put("owner", members.owner().isEmpty() ? null : members.owner());
        result.put("self", self.isEmpty() ? null : self);
        result.put("selfIsOwner", !self.isEmpty() && self.equals(members.owner()));
        result.put("members", members.members().stream().limit(MAX_COLLECTION_ITEMS).map(member -> ordered("nodeId", member.node(), "self", member.self(), "owner", member.owner(),
                "state", member.state(), "since", member.since().map(Instant::toString).orElse(null), "lastReceived", member.lastReceived().map(Instant::toString).orElse(null))).toList());
        return result;
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
        Map<String, Object> result = DoctorRenderer.findingMap(finding, finding.code().name());
        result.put("group", finding.group().value());
        result.put("mark", finding.confidence().mark());
        result.put("fix", finding.fix());
        return result;
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