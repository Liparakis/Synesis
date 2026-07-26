package org.synesis.workspace.application;

import org.synesis.workspace.project.ProjectApplicationService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.domain.CapabilityLifecycleState;
import org.synesis.coordination.domain.CapabilityRequestRecord;
import org.synesis.coordination.domain.ImplementationEventPayload;
import org.synesis.coordination.domain.ImplementationRevisionRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.domain.PredictionEventType;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;

/**
 * Application service for owners to publish an immutable implementation snapshot.
 *
 * <p>When the owner calls {@code synesis.publish_implementation}, this service:
 * <ol>
 *   <li>Authorizes the ambient worker as the accepted owner of the request.</li>
 *   <li>Verifies the owner session and assigned worktree are active and trusted.</li>
 *   <li>Identifies the current HEAD commit in the owner's worktree as the snapshot commit.</li>
 *   <li>Computes the base commit (parent of HEAD) for diff derivation.</li>
 *   <li>Derives changed paths relative to the project root.</li>
 *   <li>Checks idempotency: identical commit SHA for existing revision returns the same number.</li>
 *   <li>Appends a signed {@code CAPABILITY_IMPLEMENTATION_PUBLISHED} event.</li>
 * </ol>
 *
 * @since 1.0
 */
public final class ImplementationPublicationService {

    private final ProjectApplicationService projectService;
    private final ProviderSessionBindingService bindingService;

    /**
     * Creates an implementation publication service.
     */
    public ImplementationPublicationService() {
        this.projectService = new ProjectApplicationService();
        this.bindingService = new ProviderSessionBindingService();
    }

    /**
     * Request parameters for publishing an implementation snapshot.
     *
     * @param projectRoot          control project root path
     * @param provider             provider identifier
     * @param connectionInstanceId connection instance identifier
     * @param requestHandle        public capability request handle
     * @param summary              human-readable implementation summary
     */
    public record PublishRequest(
            Path projectRoot,
            String provider,
            String connectionInstanceId,
            String requestHandle,
            String summary
    ) {
        /**
         * Validates non-null request parameters.
         */
        public PublishRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
            Objects.requireNonNull(requestHandle, "requestHandle");
        }
    }

    /**
     * Publishes an immutable implementation snapshot for the given capability request.
     *
     * @param request publish request payload
     * @return concise agent response
     */
    public AgentResponse publishImplementation(PublishRequest request) {
        Objects.requireNonNull(request, "request");

        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }

        ProjectApplicationService.ProjectLocation location;
        ProviderSessionBindingService.Binding binding;
        NodeIdentity identity;
        try {
            location = projectService.locate(root);
            var bindings = bindingService.list(location, request.provider());
            if (bindings.isEmpty()) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.SESSION_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
            }
            binding = bindings.getLast();
            if (!"BOUND".equals(binding.status()) || binding.worktreePath() == null) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.SESSION_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
            }
            identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }

        String ownerNodeId = identity.nodeId();
        Path ownerWorktreePath = Path.of(binding.worktreePath()).toAbsolutePath().normalize();

        try {
            Path coordDir = location.root().resolve(".synesis/coordination");
            PredictionEventStore store = new PredictionEventStore(coordDir, location.projectId());

            Optional<CapabilityRequestRecord> recOpt = store.capabilityRequestProjection().findByHandle(request.requestHandle());
            if (recOpt.isEmpty()) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.REQUEST_NOT_FOUND, AgentNextAction.RETRY, null);
            }
            CapabilityRequestRecord record = recOpt.get();

            // Authorization: caller must be the accepted owner (node + worker)
            if (!record.matchesOwner(ownerNodeId, binding.supervisorId(), binding.workerId())) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, null);
            }

            // State check: must be ACCEPTED or IMPLEMENTING
            CapabilityLifecycleState state = record.state();
            if (state != CapabilityLifecycleState.ACCEPTED && state != CapabilityLifecycleState.IMPLEMENTING) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.STALE_REQUEST, AgentNextAction.RETRY, null);
            }

            // Derive Git snapshot metadata from owner worktree
            String commitSha = gitRevParse(ownerWorktreePath, "HEAD");
            String baseCommit = deriveBaseCommit(ownerWorktreePath);
            List<String> changedPaths = deriveChangedPaths(ownerWorktreePath, baseCommit, location.root());
            String summary = (request.summary() != null && !request.summary().isBlank())
                    ? request.summary().substring(0, Math.min(request.summary().length(), ImplementationRevisionRecord.MAX_SUMMARY_LENGTH))
                    : "Implementation published";

            // Check idempotency: if latest revision has same commitSha, return existing
            Optional<ImplementationRevisionRecord> latestOpt = store.capabilityRequestProjection().findLatestImplementation(request.requestHandle());
            if (latestOpt.isPresent() && latestOpt.get().commitSha().equals(commitSha)) {
                int existingRevision = latestOpt.get().revisionNumber();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("request", record.handle().value());
                result.put("revision", existingRevision);
                return new AgentResponse(AgentStatus.WAITING, AgentReason.VALIDATION_REQUIRED, AgentNextAction.WAIT, result);
            }

            // Compute next revision number
            int nextRevision = latestOpt.map(r -> r.revisionNumber() + 1).orElse(1);

            // Append CAPABILITY_IMPLEMENTATION_PUBLISHED event
            ImplementationEventPayload payload = new ImplementationEventPayload(
                    record.handle(), nextRevision, baseCommit, commitSha,
                    changedPaths, summary, "", "", List.of(), "");
            store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_IMPLEMENTATION_PUBLISHED,
                    ownerNodeId, payload.encode(), identity);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("request", record.handle().value());
            result.put("revision", nextRevision);
            return new AgentResponse(AgentStatus.WAITING, AgentReason.VALIDATION_REQUIRED, AgentNextAction.WAIT, result);

        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
        }
    }

    private static String gitRevParse(Path workdir, String ref) throws IOException {
        return runGitOutput(workdir, "rev-parse", ref);
    }

    private static String deriveBaseCommit(Path ownerWorktreePath) {
        try {
            // Try parent of HEAD
            return runGitOutput(ownerWorktreePath, "rev-parse", "HEAD^");
        } catch (IOException e) {
            try {
                // If only one commit, use empty tree
                return runGitOutput(ownerWorktreePath, "hash-object", "-t", "tree", "/dev/null");
            } catch (IOException e2) {
                return "";
            }
        }
    }

    private static List<String> deriveChangedPaths(Path ownerWorktreePath, String baseCommit, Path projectRoot) {
        if (baseCommit.isBlank()) {
            return List.of();
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--name-only", baseCommit, "HEAD");
            pb.directory(ownerWorktreePath.toFile());
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();
            if (output.isBlank()) {
                return List.of();
            }
            List<String> paths = new ArrayList<>();
            for (String line : output.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isBlank()) {
                    paths.add(trimmed);
                    if (paths.size() >= ImplementationRevisionRecord.MAX_CHANGED_PATHS) {
                        break;
                    }
                }
            }
            return List.copyOf(paths);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String runGitOutput(Path workdir, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        for (String arg : args) {
            cmd.add(arg);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workdir.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes()).trim();
        try {
            int code = proc.waitFor();
            if (code != 0) {
                throw new IOException("git " + args[0] + " failed (code=" + code + "): " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git " + args[0] + " interrupted", e);
        }
        return output;
    }
}
