package org.synesis.workspace.transport.control;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.task.CoordinationTask;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.doctor.DoctorFinding;
import org.synesis.workspace.doctor.DoctorReport;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.discovery.KnownProjectRegistry;

/**
 * Explicit, public-safe read model for the local Synesis control plane.
 *
 * <p>The model is deliberately assembled field by field from durable
 * projections. It does not expose arbitrary filesystem reads, internal recovery references,
 * authority lineages, provider configuration, or raw diagnostic detail maps.
 */
public final class ControlPlaneReadModel {

  private static final int MAX_COLLECTION_ITEMS = 256;

  private final ProjectApplicationService.ProjectLocation location;
  private final CoordinationService coordination;
  private final ProviderApplicationService providerService;
  private final DoctorService doctorService;
  private final Supplier<NetworkSnapshot> network;
  private final KnownProjectRegistry projectRegistry;

  /**
   * Creates a read model with an unconfigured network view.
   *
   * @param location        project location
   * @param coordination    durable coordination service
   * @param providerService provider lifecycle service
   * @param doctorService   read-only diagnostic service
   */
  public ControlPlaneReadModel(ProjectApplicationService.ProjectLocation location,
      CoordinationService coordination, ProviderApplicationService providerService,
      DoctorService doctorService) {
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
  public ControlPlaneReadModel(ProjectApplicationService.ProjectLocation location,
      CoordinationService coordination, ProviderApplicationService providerService,
      DoctorService doctorService, Supplier<NetworkSnapshot> network) {
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
   * @param projectRegistry installation-local discovery index
   */
  public ControlPlaneReadModel(ProjectApplicationService.ProjectLocation location,
      CoordinationService coordination, ProviderApplicationService providerService,
      DoctorService doctorService, Supplier<NetworkSnapshot> network,
      KnownProjectRegistry projectRegistry) {
    this.location = Objects.requireNonNull(location, "location");
    this.coordination = Objects.requireNonNull(coordination, "coordination");
    this.providerService = Objects.requireNonNull(providerService, "provider service");
    this.doctorService = Objects.requireNonNull(doctorService, "doctor service");
    this.network = Objects.requireNonNull(network, "network");
    this.projectRegistry = projectRegistry;
  }

  private static List<Map<String, Object>> selectors(List<ResourceSelector> selectors) {
    return selectors.stream().map(ControlPlaneReadModel::selector).toList();
  }

  private static Map<String, Object> selector(ResourceSelector selector) {
    return Map.of("kind", selector.kind().name(), "value", selector.value());
  }

  private static <T> List<T> bounded(List<T> values) {
    return bounded(values, MAX_COLLECTION_ITEMS);
  }

  private static <T> List<T> bounded(List<T> values, int limit) {
    return values.stream().limit(limit).toList();
  }

  /**
   * Builds a complete bounded dashboard snapshot.
   *
   * @return JSON-compatible snapshot map
   */
  public Map<String, Object> snapshot() {
    NetworkSnapshot networkSnapshot = network.get();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("apiVersion", "v1");
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
    result.put("findings",
        report.findings().stream().limit(MAX_COLLECTION_ITEMS).map(this::finding).toList());
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
   * <p>Only the current control-plane project is marked live. Other entries
   * are read from validated metadata and never from stale runtime snapshots.</p>
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
      return projectRegistry.projects(Set.of(location.projectId())).stream()
          .limit(MAX_COLLECTION_ITEMS)
          .map(view -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", view.projectId().toString());
            result.put("name", view.path().getFileName() == null
                ? view.path().toString() : view.path().getFileName().toString());
            result.put("path", view.path().toString());
            result.put("createdAt", view.createdAt().toString());
            result.put("firstObservedAt", view.firstObservedAt().toString());
            result.put("lastObservedAt", view.lastObservedAt().toString());
            result.put("status", view.status().name());
            return result;
          })
          .toList();
    } catch (KnownProjectRegistry.RegistryException unavailable) {
      return List.of();
    }
  }

  private Map<String, Object> runtime(NetworkSnapshot networkSnapshot) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", "RUNNING");
    result.put("apiVersion", "v1");
    result.put("version", Optional.ofNullable(ControlPlaneReadModel.class.getPackage()
        .getImplementationVersion()).orElse("development"));
    result.put("activeProjectCount", 1);
    result.put("headSequence", coordination.headSequence());
    result.put("activeAgentCount", coordination.collaborationProjection().participants().size());
    result.put("connectedPeerCount", networkSnapshot.peers().size());
    result.put("networkStatus", networkSnapshot.status());
    result.put("relayStatus", networkSnapshot.relay().status());
    return result;
  }

  private Map<String, Object> project() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", location.projectId().toString());
    result.put("name", location.root().getFileName() == null ? location.root().toString()
        : location.root().getFileName().toString());
    result.put("path", location.root().toString());
    result.put("createdAt", location.createdAt().toString());
    return result;
  }

  private List<Map<String, Object>> providers() {
    return providerService.list(location).stream().map(row -> {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("id", row.id());
      result.put("supportLevel", row.supportLevel().name());
      result.put("status", row.status());
      return result;
    }).toList();
  }

  private List<Map<String, Object>> agents() {
    List<Participant> participants = coordination.collaborationProjection().participants();
    List<WorkIntent> intents = coordination.collaborationProjection().activeIntents();
    Map<String, List<WorkIntent>> byParticipant = new LinkedHashMap<>();
    intents.forEach(
        intent -> byParticipant.computeIfAbsent(intent.participant(), ignored -> new ArrayList<>())
            .add(intent));
    List<Map<String, Object>> result = new ArrayList<>();
    for (Participant participant : participants) {
      result.add(agent(participant, byParticipant.getOrDefault(participant.id(), List.of())));
    }
    byParticipant.forEach((participantId, participantIntents) -> {
      if (participants.stream().noneMatch(participant -> participant.id().equals(participantId))) {
        WorkIntent intent = participantIntents.getFirst();
        Map<String, Object> synthetic = new LinkedHashMap<>();
        synthetic.put("id", participantId);
        synthetic.put("provider", intent.provider());
        synthetic.put("goal", intent.goal());
        synthetic.put("state", "UNKNOWN");
        synthetic.put("lastVerifiedActivity", null);
        synthetic.put("claims", selectors(intent.selectors()));
        synthetic.put("currentWork", workIntent(intent));
        result.add(synthetic);
      }
    });
    return bounded(result);
  }

  private Map<String, Object> agent(Participant participant, List<WorkIntent> intents) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", participant.id());
    result.put("provider", participant.provider());
    result.put("goal", participant.goal());
    result.put("state", participant.state().name());
    result.put("lastVerifiedActivity", participant.lastVerifiedActivity());
    result.put("claims", selectors(participant.claims()));
    result.put("currentWork", intents.stream().findFirst().map(this::workIntent).orElse(null));
    result.put("waitingOn",
        intents.stream().flatMap(intent -> intent.knownDependencies().stream()).distinct()
            .toList());
    return result;
  }

  private List<Map<String, Object>> workgroups() {
    List<WorkIntent> intents = coordination.collaborationProjection().activeIntents();
    return coordination.workGroupProjection().groups().stream()
        .sorted(Comparator.comparing(WorkGroup::workGroupId))
        .limit(MAX_COLLECTION_ITEMS)
        .map(group -> {
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("id", group.workGroupId().toString());
          result.put("status", group.status().name());
          result.put("version", group.version());
          result.put("goal", group.goal());
          result.put("acceptance", group.acceptance());
          result.put("participants",
              intents.stream().filter(intent -> group.workGroupId().equals(intent.workGroupId()))
                  .map(WorkIntent::participant).distinct().limit(MAX_COLLECTION_ITEMS).toList());
          return result;
        }).toList();
  }

  private List<Map<String, Object>> claims() {
    List<WorkIntent> intents = coordination.collaborationProjection().activeIntents();
    return intents.stream().sorted(Comparator.comparing(WorkIntent::intentId))
        .limit(MAX_COLLECTION_ITEMS).map(intent -> {
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

  private List<Map<String, Object>> conflicts(WorkIntent intent) {
    return coordination.collaborationProjection().conflicts(intent.selectors()).stream()
        .filter(conflict -> !conflict.intentId().equals(intent.intentId().toString()))
        .limit(MAX_COLLECTION_ITEMS)
        .map(conflict -> {
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("participant", conflict.participant());
          result.put("intentId", conflict.intentId());
          result.put("selector", selector(conflict.selector()));
          return result;
        }).toList();
  }

  private List<Map<String, Object>> capabilities() {
    return coordination.capabilityRequestProjection().records().values().stream()
        .sorted(Comparator.comparing(record -> record.handle().value()))
        .limit(MAX_COLLECTION_ITEMS)
        .map(this::capability).toList();
  }

  private Map<String, Object> capability(CapabilityRequestRecord record) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("handle", record.handle().value());
    result.put("capability", record.capability());
    result.put("requester", record.requesterNodeId());
    result.put("owner", record.ownerNodeId());
    result.put("state", record.state().name());
    result.put("reason", record.reason());
    result.put("createdAtEpochMillis", record.createdAtEpochMillis());
    result.put("updatedAtEpochMillis", record.updatedAtEpochMillis());
    result.put("contract", Map.of(
        "inputs", record.contract().inputs(),
        "output", record.contract().output(),
        "requiredBehavior", record.contract().requiredBehavior(),
        "acceptanceTests", record.contract().acceptanceTests()));
    return result;
  }

  private List<Map<String, Object>> tasks() {
    return coordination.coordinationProjection().tasks().values().stream()
        .sorted(Comparator.comparing(view -> view.task().taskId()))
        .limit(MAX_COLLECTION_ITEMS)
        .map(view -> {
          CoordinationTask task = view.task();
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("id", task.taskId().toString());
          result.put("title", task.title());
          result.put("capability", task.capability());
          result.put("owner", view.ownerNodeId());
          return result;
        }).toList();
  }

  private List<Map<String, Object>> ownerships() {
    return coordination.coordinationProjection().ownerships().values().stream()
        .sorted(Comparator.comparing(OwnershipClaim::capability)).limit(MAX_COLLECTION_ITEMS)
        .map(claim -> {
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("capability", claim.capability());
          result.put("taskId", claim.taskId().toString());
          result.put("owner", claim.ownerNodeId());
          result.put("protectedScopes", claim.protectedScopes());
          result.put("intentVersion", claim.intentVersion());
          return result;
        }).toList();
  }

  private Map<String, Object> networkMap(NetworkSnapshot snapshot) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", snapshot.status());
    result.put("peers", snapshot.peers().stream().map(peer -> Map.of(
        "nodeId", peer.nodeId(),
        "sessionId", peer.sessionId(),
        "liveness", peer.liveness(),
        "usable", peer.usable(),
        "establishedAt", peer.establishedAt(),
        "authenticated", peer.authenticated(),
        "health", peer.health())).toList());
    result.put("routes", snapshot.routes().stream().map(route -> Map.of(
        "kind", route.kind(),
        "destinationNodeId", route.destinationNodeId(),
        "path", route.path(),
        "nextHop", route.nextHop(),
        "relayNodeId", route.relayNodeId())).toList());
    result.put("overlay", Map.of(
        "status", snapshot.overlay().status(),
        "authorityNodeId", snapshot.overlay().authorityNodeId(),
        "revision", snapshot.overlay().revision(),
        "memberCount", snapshot.overlay().memberCount(),
        "expiresAt", snapshot.overlay().expiresAt(),
        "members", snapshot.overlay().members().stream().map(member -> Map.of(
            "nodeId", member.nodeId(), "status", member.status())).toList(),
        "directEdges", snapshot.overlay().directEdges().stream().map(edge -> Map.of(
            "from", edge.fromNodeId(), "to", edge.toNodeId(), "status", edge.status())).toList(),
        "desiredEdges", snapshot.overlay().desiredEdges().stream().map(edge -> Map.of(
            "from", edge.fromNodeId(), "to", edge.toNodeId(), "status", edge.status())).toList()));
    result.put("relay", Map.of(
        "status", snapshot.relay().status(),
        "localAddress", snapshot.relay().localAddress(),
        "activeConnections", snapshot.relay().activeConnections(),
        "configured", snapshot.relay().configured(),
        "connected", snapshot.relay().connected(),
        "relayIdentity", snapshot.relay().relayIdentity(),
        "authorized", snapshot.relay().authorized(),
        "activeRouteUsage", snapshot.relay().activeRouteUsage()));
    return result;
  }

  private Map<String, Object> finding(DoctorFinding finding) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("code", finding.code().name());
    result.put("severity", finding.severity().name());
    result.put("confidence", finding.confidence().name());
    result.put("summary", finding.summary());
    result.put("explanation", finding.explanation());
    result.put("affectedResourceType", finding.affectedResourceType());
    result.put("repairSupported", finding.repairSupported());
    result.put("recommendation", finding.recommendation().value());
    return result;
  }

  private Map<String, Object> workIntent(WorkIntent intent) {
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

  /**
   * Link, overlay, and relay state supplied by the long-lived runtime owner.
   *
   * @param status  aggregate network status
   * @param peers   connected physical sessions
   * @param routes  projected overlay routes
   * @param overlay overlay membership summary
   * @param relay   relay summary
   */
  public record NetworkSnapshot(String status, List<NetworkPeer> peers, List<NetworkRoute> routes,
                                OverlaySummary overlay, RelaySummary relay) {

    /**
     * Validates and copies the network view.
     */
    public NetworkSnapshot {
      Objects.requireNonNull(status, "status");
      peers = bounded(Objects.requireNonNull(peers, "peers"));
      routes = bounded(Objects.requireNonNull(routes, "routes"));
      Objects.requireNonNull(overlay, "overlay");
      Objects.requireNonNull(relay, "relay");
    }

    /**
     * Returns the safe unconfigured network state used before a runtime owner injects Link and
     * overlay views.
     *
     * @return empty network snapshot
     */
    public static NetworkSnapshot empty() {
      return new NetworkSnapshot("UNCONFIGURED", List.of(), List.of(),
          new OverlaySummary("UNCONFIGURED", "", 0, 0, ""),
          new RelaySummary("UNCONFIGURED", "", 0));
    }
  }

  /**
   * Safe physical Link session summary.
   *
   * @param nodeId        remote node identity
   * @param sessionId     session identity
   * @param liveness      liveness state
   * @param usable        whether the session is usable
   * @param establishedAt establishment timestamp
   * @param authenticated whether the session identity was authenticated
   * @param health        safe peer health classification
   */
  public record NetworkPeer(String nodeId, String sessionId, String liveness, boolean usable,
                            String establishedAt, boolean authenticated, String health) {

    /**
     * Creates a peer summary using the usable state as a health hint.
     *
     * @param nodeId        remote node identity
     * @param sessionId     session identity
     * @param liveness      liveness state
     * @param usable        whether the session is usable
     * @param establishedAt establishment timestamp
     */
    public NetworkPeer(String nodeId, String sessionId, String liveness, boolean usable,
        String establishedAt) {
      this(nodeId, sessionId, liveness, usable, establishedAt, true,
          usable ? "HEALTHY" : "UNAVAILABLE");
    }

    /**
     * Validates the physical peer summary.
     */
    public NetworkPeer {
      Objects.requireNonNull(nodeId, "node ID");
      Objects.requireNonNull(sessionId, "session ID");
      Objects.requireNonNull(liveness, "liveness");
      Objects.requireNonNull(establishedAt, "established at");
      Objects.requireNonNull(health, "health");
    }
  }

  /**
   * Safe overlay route summary.
   *
   * @param kind              route kind
   * @param destinationNodeId destination node
   * @param path              route node path
   * @param nextHop           next hop node
   * @param relayNodeId       relay node, or empty
   */
  public record NetworkRoute(String kind, String destinationNodeId, List<String> path,
                             String nextHop, String relayNodeId) {

    /**
     * Validates and copies the route path.
     */
    public NetworkRoute {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(destinationNodeId, "destination node ID");
      path = bounded(Objects.requireNonNull(path, "path"), 32);
      nextHop = nextHop == null ? "" : nextHop;
      relayNodeId = relayNodeId == null ? "" : relayNodeId;
    }
  }

  /**
   * Overlay membership summary.
   *
   * @param status          overlay status
   * @param authorityNodeId membership authority node
   * @param revision        membership revision
   * @param memberCount     member count
   * @param expiresAt       membership expiry timestamp
   * @param members         membership nodes
   * @param directEdges     physical adjacency edges
   * @param desiredEdges    desired topology edges
   */
  public record OverlaySummary(String status, String authorityNodeId, long revision,
                               int memberCount, String expiresAt, List<OverlayMember> members,
                               List<OverlayEdge> directEdges, List<OverlayEdge> desiredEdges) {

    /**
     * Creates a summary without graph details.
     *
     * @param status          overlay status
     * @param authorityNodeId membership authority node
     * @param revision        membership revision
     * @param memberCount     member count
     * @param expiresAt       membership expiry timestamp
     */
    public OverlaySummary(String status, String authorityNodeId, long revision,
        int memberCount, String expiresAt) {
      this(status, authorityNodeId, revision, memberCount, expiresAt, List.of(), List.of(),
          List.of());
    }

    /**
     * Validates the overlay summary.
     */
    public OverlaySummary {
      Objects.requireNonNull(status, "status");
      authorityNodeId = authorityNodeId == null ? "" : authorityNodeId;
      expiresAt = expiresAt == null ? "" : expiresAt;
      members = bounded(Objects.requireNonNull(members, "members"));
      directEdges = bounded(Objects.requireNonNull(directEdges, "direct edges"));
      desiredEdges = bounded(Objects.requireNonNull(desiredEdges, "desired edges"));
    }
  }

  /**
   * Relay summary.
   *
   * @param status            relay status
   * @param localAddress      local relay address
   * @param activeConnections active relay connection count
   * @param configured        whether relay configuration exists
   * @param connected         whether the relay client is connected
   * @param relayIdentity     relay node identity
   * @param authorized        whether the local node is authorized
   * @param activeRouteUsage  active route usage count
   */
  public record RelaySummary(String status, String localAddress, int activeConnections,
                             boolean configured, boolean connected, String relayIdentity,
                             boolean authorized, int activeRouteUsage) {

    /**
     * Creates a relay summary with conservative state defaults.
     *
     * @param status            relay status
     * @param localAddress      local relay address
     * @param activeConnections active relay connection count
     */
    public RelaySummary(String status, String localAddress, int activeConnections) {
      this(status, localAddress, activeConnections, !"UNCONFIGURED".equals(status)
          && !"DISABLED".equals(status), "CONNECTED".equals(status), "", false, 0);
    }

    /**
     * Validates the relay summary.
     */
    public RelaySummary {
      Objects.requireNonNull(status, "status");
      localAddress = localAddress == null ? "" : localAddress;
    }
  }

  /**
   * Safe overlay membership node.
   *
   * @param nodeId member node identity
   * @param status member status
   */
  public record OverlayMember(String nodeId, String status) {

    /**
     * Validates the membership node summary.
     */
    public OverlayMember {
      Objects.requireNonNull(nodeId, "node ID");
      Objects.requireNonNull(status, "status");
    }
  }

  /**
   * Safe overlay graph edge.
   *
   * @param fromNodeId source node
   * @param toNodeId   destination node
   * @param status     edge state
   */
  public record OverlayEdge(String fromNodeId, String toNodeId, String status) {

    /**
     * Validates the edge summary.
     */
    public OverlayEdge {
      Objects.requireNonNull(fromNodeId, "from node ID");
      Objects.requireNonNull(toNodeId, "to node ID");
      Objects.requireNonNull(status, "status");
    }
  }
}
