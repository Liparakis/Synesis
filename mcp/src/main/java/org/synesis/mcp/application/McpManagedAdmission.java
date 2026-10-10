package org.synesis.mcp.application;

import static org.synesis.mcp.application.McpJsonRpcResponses.errorTextResultResponse;
import static org.synesis.mcp.application.McpJsonRpcResponses.object;

import java.nio.file.Path;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentRecord;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentService;
import org.synesis.workspace.application.provider.continuity.ProviderContinuityMode;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Decides whether this connection's exact provider binding may act on a tool call under managed
 * continuity.
 *
 * <p>The launcher's startup proof authenticates only the process-local transport. Everything this
 * class consults after that is immutable launch evidence plus the durable attachment record, which is
 * re-read on every call so that a pending, disconnected, superseded or completed attachment cannot
 * act. It is separate from the handler because the decision never depends on mutable connection
 * state: the root and its resolution flag arrive as arguments at the call.</p>
 */
final class McpManagedAdmission {

    /**
     * Whether the launcher authenticated this process as managed continuity.
     */
    private final boolean managedAdmission;
    /**
     * Binding and generation authenticated for a managed process-local connection.
     */
    private final String managedBindingSessionId;
    /**
     * Authenticated generation of the managed attachment for this connection.
     */
    private final long managedAttachmentGeneration;
    /**
     * Stable provider ID associated with this connection's binding.
     */
    private final String provider;
    /**
     * Connection incarnation used to prevent cross-connection authority reuse.
     */
    private final String connectionInstanceId;
    /**
     * Resolves the exact provider and connection binding used for authority checks.
     */
    private final SessionAuthorityResolver authorityResolver;

    /**
     * Creates the admission policy for one connection.
     *
     * @param managedAdmission             whether the launcher authenticated this process as managed continuity
     * @param managedBindingSessionId      binding authenticated for a managed connection, or {@code null}
     * @param managedAttachmentGeneration  authenticated attachment generation, or {@code 0} when unmanaged
     * @param provider                     stable provider ID associated with this connection's binding
     * @param connectionInstanceId         connection incarnation used to prevent cross-connection authority reuse
     * @param authorityResolver            resolver for the exact provider and connection binding
     */
    McpManagedAdmission(boolean managedAdmission,
            String managedBindingSessionId,
            long managedAttachmentGeneration,
            String provider,
            String connectionInstanceId,
            SessionAuthorityResolver authorityResolver) {
        this.managedAdmission = managedAdmission;
        this.managedBindingSessionId = managedBindingSessionId;
        this.managedAttachmentGeneration = managedAttachmentGeneration;
        this.provider = provider;
        this.connectionInstanceId = connectionInstanceId;
        this.authorityResolver = authorityResolver;
    }

    /**
     * Reads the current managed attachment fence for this exact process.
     *
     * <p>The startup proof authenticates the process-local launch. The durable
     * status is intentionally consulted again for every tool call so a pending connection cannot
     * create coordination state and the same connection becomes eligible only after trusted lifecycle
     * activation.</p>
     *
     * @param completedReviewTool whether the requested tool is one of the three review coordination calls
     * @param activeProjectRoot the connection's currently selected control project root
     * @param projectRootResolved whether this connection has resolved a project root
     * @return current managed admission state
     */
    ManagedAdmissionState managedAdmissionState(boolean completedReviewTool, Path activeProjectRoot, boolean projectRootResolved) {
        if (!managedAdmission) {
            return prooflessManagedAdmissionState(activeProjectRoot, projectRootResolved);
        }
        try {
            ProjectLocation location = new ProjectApplicationService().locate(activeProjectRoot);
            var binding = authorityResolver.resolveReview(location, provider, connectionInstanceId);
            if (!managedBindingSessionId.equals(binding.sessionId())) {
                return ManagedAdmissionState.REJECTED;
            }
            boolean completed = "COMPLETED".equalsIgnoreCase(binding.status());
            if (completed && !completedReviewTool) {
                return ManagedAdmissionState.REJECTED;
            }
            ManagedAttachmentRecord record = ManagedAttachmentService.storeFor(location, managedBindingSessionId).read().orElse(null);
            if (record == null || record.mode() != ProviderContinuityMode.MANAGED_CONTINUITY || record.generation() != managedAttachmentGeneration || !provider.equals(record.provider())) {
                return ManagedAdmissionState.REJECTED;
            }
            if (completed) {
                return ManagedAdmissionState.REVIEW_ONLY;
            }
            return switch (record.status()) {
                case PENDING_ACTIVATION -> ManagedAdmissionState.PENDING;
                case ACTIVE -> ManagedAdmissionState.ACTIVE;
                case DISCONNECTED, TERMINAL -> ManagedAdmissionState.REJECTED;
            };
        } catch (Exception rejected) {
            return ManagedAdmissionState.REJECTED;
        }
    }

    /**
     * Prevents an ordinary provider connection from reusing an exact binding reserved for managed
     * continuity without presenting its launch proof.
     *
     * <p>This check runs after MCP root resolution so a user-level unpinned launcher can start
     * before Codex advertises the project context. The exact provider connection ID remains the
     * selector; another connection's managed record is never treated as authority.</p>
     *
     * @param activeProjectRoot the connection's currently selected control project root
     * @param projectRootResolved whether this connection has resolved a project root
     * @return the admission state for this exact provider connection
     */
    private ManagedAdmissionState prooflessManagedAdmissionState(Path activeProjectRoot, boolean projectRootResolved) {
        if (!"codex".equals(provider)) {
            return ManagedAdmissionState.ACTIVE;
        }
        if (!projectRootResolved) {
            return ManagedAdmissionState.ACTIVE;
        }
        try {
            ProjectLocation location = new ProjectApplicationService().locate(activeProjectRoot);
            var binding = new ProviderSessionBindingService().find(location, provider, connectionInstanceId).orElse(null);
            if (binding == null) {
                return ManagedAdmissionState.ACTIVE;
            }
            ManagedAttachmentRecord record = ManagedAttachmentService.storeFor(location, binding.sessionId()).read().orElse(null);
            return record != null && record.mode() == ProviderContinuityMode.MANAGED_CONTINUITY
                    ? ManagedAdmissionState.PROOF_REQUIRED
                    : ManagedAdmissionState.ACTIVE;
        } catch (Exception rejected) {
            return ManagedAdmissionState.REJECTED;
        }
    }

    /**
     * Creates the deterministic response for a quarantined or fenced managed transport without
     * invoking any application mutation service.
     *
     * @param id    JSON-RPC request identifier
     * @param state the non-admissible state, {@code PENDING} or {@code REJECTED}
     * @return serialized error result
     */
    String managedAdmissionResponse(Object id, ManagedAdmissionState state) {
        String reason = switch (state) {
            case PENDING -> "managed_attachment_pending";
            case PROOF_REQUIRED -> "managed_attachment_proof_required";
            case ACTIVE, REVIEW_ONLY, REJECTED -> "managed_attachment_rejected";
        };
        return errorTextResultResponse(id, ProviderJson.write(object("status", "blocked", "reason", reason)));
    }

    /**
     * Managed connection admission states visible only inside the MCP application package.
     */
    enum ManagedAdmissionState {
        /**
         * The exact managed attachment is active.
         */
        ACTIVE,
        /**
         * The completed binding may continue exact review coordination only.
         */
        REVIEW_ONLY,
        /**
         * The proof-bearing transport is connected but has no authority.
         */
        PENDING,
        /** The exact provider binding is managed and requires its process-private proof. */
        PROOF_REQUIRED,
        /**
         * The attachment or exact binding is no longer admissible.
         */
        REJECTED
    }
}
