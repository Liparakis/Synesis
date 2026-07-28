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
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;

/** Resolves authenticated workspace sessions into collaboration intents and claims. */
public final class WorkspaceCollaborationService {
    private final ProjectApplicationService projectService = new ProjectApplicationService();
    private final ProviderSessionBindingService bindingService = new ProviderSessionBindingService();

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
        WorkIntentService service = new WorkIntentService(store, identity);
        UUID taskId = UUID.nameUUIDFromBytes(connectionInstanceId.getBytes(StandardCharsets.UTF_8));
        WorkIntent intent = new WorkIntent(UUID.nameUUIDFromBytes((provider + ":" + binding.sessionId())
                .getBytes(StandardCharsets.UTF_8)), location.projectId(), participantHandle(binding.sessionId()),
                provider, taskId, goal == null ? "Unspecified work" : goal,
                acceptance == null ? "Unspecified acceptance" : acceptance,
                binding.baseCommit(), selectors, 1, WorkIntent.Status.ANNOUNCED);
        return service.announce(intent);
    }

    /** Releases the exact session intent and all of its claims. */
    public void release(Path projectRoot, String provider, String connectionInstanceId) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        UUID intentId = UUID.nameUUIDFromBytes((provider + ":" + binding.sessionId()).getBytes(StandardCharsets.UTF_8));
        new WorkIntentService(store, identity).release(intentId, participantHandle(binding.sessionId()));
    }

    /** Opens a request against a conflicting intent owned by another participant. */
    public CoordinationRequest request(Path projectRoot, String provider, String connectionInstanceId,
            UUID conflictingIntentId, CoordinationRequest.Kind kind, String proposal) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        return new WorkIntentService(store, identity).request(participantHandle(binding.sessionId()), conflictingIntentId, kind, proposal);
    }

    /** Responds to a request addressed to the exact provider session. */
    public void respond(Path projectRoot, String provider, String connectionInstanceId,
            UUID requestId, CoordinationRequest.Status status, String proposal) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new WorkIntentService(store, identity).respond(participantHandle(binding.sessionId()), requestId, status, proposal);
    }

    /** Lists active intents and pending/resolved coordination requests. */
    public CollaborationSnapshot status(Path projectRoot) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        WorkIntentService service = new WorkIntentService(store,
                new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity());
        return new CollaborationSnapshot(service.activeIntents(), service.requests(),
                store.collaborationProjection().participants());
    }

    /** Shared collaboration discovery result used by CLI and MCP adapters. */
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
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(connectionId.getBytes(StandardCharsets.UTF_8)));
        return bindingService.list(location, provider).stream()
                .filter(candidate -> fingerprint.equals(candidate.providerInstanceFingerprint()))
                .findFirst()
                .orElseThrow(() -> new IOException("SESSION_NOT_FOUND"));
    }
}
