package org.synesis.workspace.application.collaboration;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ClaimResult;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.application.ContractService;
import org.synesis.coordination.domain.contract.ContractDependency;
import org.synesis.coordination.domain.contract.ContractRecord;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.SessionAuthorityResolver;

/** Resolves authenticated workspace sessions into collaboration intents and claims. */
public final class WorkspaceCollaborationService {
    private final ProjectApplicationService projectService = new ProjectApplicationService();
    private final ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
    private final SessionAuthorityResolver authorityResolver = new SessionAuthorityResolver(bindingService);

    /** Creates a workspace collaboration adapter. */
    public WorkspaceCollaborationService() {
    }

    /** Announces an intent for one verified provider session.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId connection ID
     * @param goal goal
     * @param acceptance acceptance criteria
     * @param selectors requested selectors
     * @return claim result
     * @throws Exception when the project, session, identity, or event store cannot be resolved
     */
    public ClaimResult announce(Path projectRoot, String provider, String connectionInstanceId,
                                String goal, String acceptance, List<ResourceSelector> selectors)
            throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        String participant = participantHandle(binding.sessionId());
        var existing = store.collaborationProjection().participants().stream()
                .filter(candidate -> candidate.id().equals(participant)).findFirst();
        if (existing.isPresent() && existing.get().state() != Participant.State.ACTIVE) {
            throw new IOException("SESSION_EPOCH_FENCED");
        }
        WorkIntentService service = new WorkIntentService(store, identity);
        UUID taskId = UUID.nameUUIDFromBytes(connectionInstanceId.getBytes(StandardCharsets.UTF_8));
        WorkIntent intent = new WorkIntent(UUID.nameUUIDFromBytes((provider + ":" + binding.sessionId())
                .getBytes(StandardCharsets.UTF_8)), location.projectId(), participant,
                provider, taskId, goal == null ? "Unspecified work" : goal,
                acceptance == null ? "Unspecified acceptance" : acceptance,
                binding.baseCommit(), selectors, 1, WorkIntent.Status.ANNOUNCED);
        return service.announce(intent);
    }

    /** Releases the exact session intent and all of its claims.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @throws Exception when resolution or append fails
     */
    public void release(Path projectRoot, String provider, String connectionInstanceId) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        UUID intentId = UUID.nameUUIDFromBytes((provider + ":" + binding.sessionId()).getBytes(StandardCharsets.UTF_8));
        new WorkIntentService(store, identity).release(intentId, participantHandle(binding.sessionId()));
    }

    /** Opens a request against a conflicting intent owned by another participant.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @param conflictingIntentId conflicting intent
     * @param kind request kind
     * @param proposal proposal
     * @return durable request
     * @throws Exception when resolution or append fails
     */
    public CoordinationRequest request(Path projectRoot, String provider, String connectionInstanceId,
            UUID conflictingIntentId, CoordinationRequest.Kind kind, String proposal) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        return new WorkIntentService(store, identity).request(participantHandle(binding.sessionId()), conflictingIntentId, kind, proposal);
    }

    /** Responds to a request addressed to the exact provider session.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @param requestId request ID
     * @param status response status
     * @param proposal revised proposal
     * @throws Exception when resolution or append fails
     */
    public void respond(Path projectRoot, String provider, String connectionInstanceId,
            UUID requestId, CoordinationRequest.Status status, String proposal) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new WorkIntentService(store, identity).respond(participantHandle(binding.sessionId()), requestId, status, proposal);
    }

    /** Offers a claim handoff to an active target participant.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @param intentId intent ID
     * @param target target participant
     * @param proposal handoff proposal
     * @return pending request
     * @throws Exception when resolution or append fails
     */
    public CoordinationRequest handoff(Path projectRoot, String provider, String connectionInstanceId,
            UUID intentId, String target, String proposal) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        return new WorkIntentService(store, identity).offerHandoff(participantHandle(binding.sessionId()), intentId, target, proposal);
    }

    /** Records verified activity for the exact provider session's participant.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @throws Exception when resolution or append fails
     */
    public void heartbeat(Path projectRoot, String provider, String connectionInstanceId) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new WorkIntentService(store, identity).heartbeat(participantHandle(binding.sessionId()));
    }

    /** Lists active intents and pending/resolved coordination requests.
     * @param projectRoot project root
     * @return collaboration snapshot
     * @throws Exception when project state cannot be read
     */
    public CollaborationSnapshot status(Path projectRoot) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        WorkIntentService service = new WorkIntentService(store,
                new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity());
        return new CollaborationSnapshot(service.activeIntents(), service.requests(),
                store.collaborationProjection().participants());
    }

    /** Publishes a signed shared contract revision for this project.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @param contractId contract identifier
     * @param body contract body
     * @param selectors declared selector references
     * @return published contract
     * @throws Exception when session or persistence resolution fails
     */
    public ContractRecord publishContract(Path projectRoot, String provider, String connectionInstanceId,
            UUID contractId, String body, List<String> selectors) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        return new ContractService(store, identity).publish(contractId, participantHandle(binding.sessionId()), body, selectors);
    }

    /** Binds an intent to an exact active contract revision.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @param intentId intent identifier
     * @param contractId contract identifier
     * @param revision exact revision
     * @throws Exception when session or persistence resolution fails
     */
    public void bindContract(Path projectRoot, String provider, String connectionInstanceId,
            UUID intentId, UUID contractId, long revision) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new ContractService(store, identity).bind(intentId, participantHandle(binding.sessionId()), contractId, revision);
    }

    /** Lists replayed contracts and exact consumer dependencies.
     * @param projectRoot project root
     * @return contract snapshot
     * @throws Exception when project state cannot be read
     */
    public ContractSnapshot contractStatus(Path projectRoot) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        ContractService service = new ContractService(store,
                new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity());
        return new ContractSnapshot(service.contracts(), service.dependencies());
    }

    /** Shared contract discovery result used by CLI and MCP adapters.
     * @param contracts contract revisions
     * @param dependencies consumer bindings
     */
    public record ContractSnapshot(List<ContractRecord> contracts, List<ContractDependency> dependencies) { }

    /** Shared collaboration discovery result used by CLI and MCP adapters.
     * @param intents intents
     * @param requests requests
     * @param participants participants
     */
    public record CollaborationSnapshot(List<WorkIntent> intents, List<CoordinationRequest> requests,
            List<Participant> participants) { }

    /** Returns whether the session owns the target or no collaboration protocol is active.
     * @param projectRoot project root
     * @param provider provider
     * @param connectionInstanceId connection ID
     * @param relativePath repository-relative target
     * @return authorization decision
     */
    public boolean permitsMutation(Path projectRoot, String provider, String connectionInstanceId,
                                   String relativePath) {
        return "allowed".equals(mutationReason(projectRoot, provider, connectionInstanceId, relativePath));
    }

    /** Returns a stable mutation authorization classification.
     * @param projectRoot project root
     * @param provider provider
     * @param connectionInstanceId connection ID
     * @param relativePath repository-relative target
     * @return {@code allowed}, {@code overlapping_claim}, or {@code coordination_intent_required}
     */
    public String mutationReason(Path projectRoot, String provider, String connectionInstanceId,
                                 String relativePath) {
        try {
            ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
            NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
            PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
            WorkIntentService service = new WorkIntentService(store, identity);
            if (service.activeIntents().isEmpty() && !store.collaborationProjection().activated()) {
                return "allowed";
            }
            ResourceSelector selector = ResourceSelector.pathExact(relativePath);
            ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
            if (service.owns(participantHandle(binding.sessionId()), selector)) {
                return "allowed";
            }
            return service.activeIntents().stream().anyMatch(intent -> intent.selectors().stream()
                    .anyMatch(selector::overlaps)) ? "overlapping_claim" : "coordination_intent_required";
        } catch (Exception failure) {
            return "coordination_intent_required";
        }
    }

    /** Returns the stable opaque participant handle for a connection.
     * @param connectionInstanceId connection ID
     * @return opaque participant handle
     */
    public static String participantHandle(String connectionInstanceId) {
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        return "agt_" + UUID.nameUUIDFromBytes(connectionInstanceId.getBytes(StandardCharsets.UTF_8));
    }

    private ProviderSessionBindingService.Binding binding(ProjectApplicationService.ProjectLocation location,
                                                           String provider, String connectionId) throws Exception {
        return authorityResolver.resolve(location, provider, connectionId);
    }
}
