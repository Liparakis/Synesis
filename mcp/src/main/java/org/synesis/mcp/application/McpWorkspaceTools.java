package org.synesis.mcp.application;

import static org.synesis.mcp.application.McpToolArguments.stringArgument;
import static org.synesis.mcp.application.McpToolArguments.validationSnapshotSelector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.application.workspace.mutation.PatchEdit;
import org.synesis.workspace.application.workspace.mutation.PatchRequest;
import org.synesis.workspace.application.workspace.read.ReadRequest;
import org.synesis.workspace.application.workspace.mutation.WorkspacePatchService;
import org.synesis.workspace.application.workspace.read.WorkspaceReadService;

/**
 * Runs the {@code read_file} and {@code apply_patch} tools by translating their arguments into
 * requests to the workspace services.
 *
 * <p>The services re-check the exact session binding and workspace authority on every request, so
 * this class keeps no connection state; the selected project root arrives as an argument at each
 * call.</p>
 */
final class McpWorkspaceTools {

    /**
     * Service that reads project files under the admitted workspace authority.
     */
    private final WorkspaceReadService readService;
    /**
     * Service that applies workspace edits under the admitted workspace authority.
     */
    private final WorkspacePatchService patchService;
    /**
     * Stable provider ID associated with this connection's binding.
     */
    private final String provider;
    /**
     * Connection incarnation used to prevent cross-connection authority reuse.
     */
    private final String connectionInstanceId;

    /**
     * Creates the workspace tools for one connection.
     *
     * @param readService          service that reads project files under the admitted workspace authority
     * @param patchService         service that applies workspace edits under the admitted workspace authority
     * @param provider             stable provider ID associated with this connection's binding
     * @param connectionInstanceId connection incarnation used to prevent cross-connection authority reuse
     */
    McpWorkspaceTools(WorkspaceReadService readService, WorkspacePatchService patchService, String provider, String connectionInstanceId) {
        this.readService = readService;
        this.patchService = patchService;
        this.provider = provider;
        this.connectionInstanceId = connectionInstanceId;
    }

    /**
     * Reads a file, or a requester validation snapshot file, under the admitted workspace authority.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param activeProjectRoot the connection's currently selected control project root
     * @return the response to serialize
     */
    AgentResponse readFile(Map<String, Object> arguments, Path activeProjectRoot) {
        AgentResponse agentResponse;
        String path = stringArgument(arguments, "path", "relativePath");
        Integer startLine = (arguments != null && arguments.get("startLine") instanceof Number n) ? n.intValue() : null;
        Integer endLine = (arguments != null && arguments.get("endLine") instanceof Number n) ? n.intValue() : null;
        Integer maxBytes = (arguments != null && arguments.get("maxBytes") instanceof Number n) ? n.intValue() : null;
        try {
            var snapshot = validationSnapshotSelector(arguments);
            ReadRequest readReq = new ReadRequest(activeProjectRoot,
                    provider,
                    connectionInstanceId,
                    path,
                    startLine,
                    endLine,
                    maxBytes,
                    snapshot.capabilityRequestHandle(),
                    snapshot.implementationRevision());
            agentResponse = readService.readFile(readReq);
        } catch (IllegalArgumentException invalidValidationEvidence) {
            agentResponse = AgentResponse.blocked(AgentReason.INVALID_PATH);
        }
        return agentResponse;
    }

    /**
     * Applies a patch under the admitted workspace authority.
     *
     * @param arguments tool arguments, possibly {@code null}
     * @param activeProjectRoot the connection's currently selected control project root
     * @return the response to serialize
     */
    AgentResponse applyPatch(Map<String, Object> arguments, Path activeProjectRoot) {
        AgentResponse agentResponse;
        String path = stringArgument(arguments, "path", "relativePath");
        boolean create = arguments != null && Boolean.TRUE.equals(arguments.get("create"));
        String content = stringArgument(arguments, "content", "newContent");
        String expectedHash = stringArgument(arguments, "expectedHash", "expectedRevision");

        List<PatchEdit> patchEdits = new ArrayList<>();
        if (arguments != null && arguments.get("edits") instanceof List<?> editsList) {
            for (Object item : editsList) {
                if (item instanceof Map<?, ?> editMap) {
                    Object find = editMap.get("find");
                    Object replace = editMap.get("replace");
                    if (find != null && !(find instanceof String) || replace != null && !(replace instanceof String)) {
                        return AgentResponse.blocked(AgentReason.INVALID_PATH);
                    }
                    int expectedOccurrences = (editMap.get("expectedOccurrences") instanceof Number n) ? n.intValue() : 0;
                    if (find != null && replace != null && expectedOccurrences >= 1) {
                        patchEdits.add(new PatchEdit((String) find, (String) replace, expectedOccurrences));
                    }
                }
            }
        }

        PatchRequest patchReq = new PatchRequest(activeProjectRoot, provider, connectionInstanceId, path, create, content, expectedHash, patchEdits);
        agentResponse = patchService.applyPatch(patchReq);
        return agentResponse;
    }
}
