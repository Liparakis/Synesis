package org.synesis.workspace.application;

import org.synesis.workspace.application.ProjectApplicationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestPayload;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;

/**
 * Application service for capability owners to respond to capability requests.
 *
 * <p>Handles tool calls for {@code synesis.respond_to_owner_request}.
 *
 * @since 1.0
 */
public final class CapabilityResponseService {

    private final ProjectApplicationService projectService;
    private final ProviderSessionBindingService bindingService;

    /**
     * Creates a capability response service.
     */
    public CapabilityResponseService() {
        this.projectService = new ProjectApplicationService();
        this.bindingService = new ProviderSessionBindingService();
    }

    /**
     * Request parameters for an owner capability response.
     *
     * @param projectRoot          control project root path
     * @param provider             provider identifier
     * @param connectionInstanceId connection instance identifier
     * @param requestHandle        public request handle locator
     * @param response             response type ("accept", "revise", "reject")
     * @param revision             revised contract specification (optional, for "revise")
     * @param reason               rejection or revision explanation (optional)
     */
    public record OwnerResponseRequest(
            Path projectRoot,
            String provider,
            String connectionInstanceId,
            String requestHandle,
            String response,
            CapabilityContract revision,
            String reason
    ) {
        /**
         * Validates non-null request parameters.
         */
        public OwnerResponseRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
            Objects.requireNonNull(requestHandle, "requestHandle");
            Objects.requireNonNull(response, "response");
        }
    }

    /**
     * Executes an owner capability request response (accept, revise, or reject).
     *
     * @param request response request payload
     * @return concise agent response
     */
    public AgentResponse respondToOwnerRequest(OwnerResponseRequest request) {
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

        try {
            Path coordDir = location.root().resolve(".synesis/coordination");
            PredictionEventStore store = new PredictionEventStore(coordDir, location.projectId());
            CapabilityRequestProjection projection = store.capabilityRequestProjection();

            String handleStr = request.requestHandle().trim();
            Optional<CapabilityRequestRecord> recOpt = projection.findByHandle(handleStr);
            if (recOpt.isEmpty()) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.REQUEST_NOT_FOUND, AgentNextAction.RETRY, null);
            }

            CapabilityRequestRecord record = recOpt.get();

            // Authorization check: ambient caller must match assigned owner
            if (!record.ownerNodeId().equals(ownerNodeId)) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, null);
            }

            String respType = request.response().trim().toLowerCase(Locale.ROOT);

            // Idempotency check: repeated identical response returns success
            if (record.state() == CapabilityLifecycleState.ACCEPTED && "accept".equals(respType)) {
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("request", record.handle().value());
                res.put("capability", record.capability());
                res.put("pending", 0);
                return AgentResponse.ready("isolated", 0);
            }
            if (record.state() == CapabilityLifecycleState.REJECTED && "reject".equals(respType)) {
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("request", record.handle().value());
                res.put("capability", record.capability());
                res.put("pending", 0);
                return AgentResponse.ready("isolated", 0);
            }

            // Conflicting or late response on terminal/non-awaiting request returns stale_request
            if (record.state() != CapabilityLifecycleState.AWAITING_OWNER) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.STALE_REQUEST, AgentNextAction.RETRY, null);
            }

            return switch (respType) {
                case "accept" -> {
                    CapabilityRequestPayload payload = new CapabilityRequestPayload(
                            record.handle(), record.capability(), record.requesterNodeId(), record.requesterSupervisorId(), record.requesterWorkerId(),
                            ownerNodeId, binding.supervisorId(), binding.workerId(),
                            record.contract(), CapabilityLifecycleState.ACCEPTED, null);
                    store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_ACCEPTED, ownerNodeId, payload.encode(), identity);

                    Map<String, Object> res = new LinkedHashMap<>();
                    res.put("request", record.handle().value());
                    res.put("capability", record.capability());
                    res.put("pending", 0);
                    yield AgentResponse.ready("isolated", 0);
                }
                case "revise" -> {
                    CapabilityContract revContract = request.revision() != null ? request.revision() : record.contract();
                    CapabilityRequestPayload payload = new CapabilityRequestPayload(
                            record.handle(), record.capability(), record.requesterNodeId(), record.requesterSupervisorId(), record.requesterWorkerId(),
                            ownerNodeId, binding.supervisorId(), binding.workerId(),
                            revContract, CapabilityLifecycleState.REVISION_REQUESTED, request.reason());
                    store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED, ownerNodeId, payload.encode(), identity);

                    Map<String, Object> res = new LinkedHashMap<>();
                    res.put("request", record.handle().value());
                    res.put("capability", record.capability());
                    res.put("pending", 0);
                    yield AgentResponse.ready("isolated", 0);
                }
                case "reject" -> {
                    String reason = request.reason() != null && !request.reason().isBlank() ? request.reason() : "Capability request rejected by owner";
                    CapabilityRequestPayload payload = new CapabilityRequestPayload(
                            record.handle(), record.capability(), record.requesterNodeId(), record.requesterSupervisorId(), record.requesterWorkerId(),
                            ownerNodeId, binding.supervisorId(), binding.workerId(),
                            record.contract(), CapabilityLifecycleState.REJECTED, reason);
                    store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_REJECTED, ownerNodeId, payload.encode(), identity);

                    Map<String, Object> res = new LinkedHashMap<>();
                    res.put("request", record.handle().value());
                    res.put("capability", record.capability());
                    res.put("pending", 0);
                    yield AgentResponse.ready("isolated", 0);
                }
                default -> new AgentResponse(AgentStatus.BLOCKED, AgentReason.INVALID_PATH, AgentNextAction.RETRY, null);
            };

        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
        }
    }
}
