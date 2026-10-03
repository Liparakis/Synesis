package org.synesis.workspace.application.workspace.mutation;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Parameters for a patch application request.
 *
 * @param controlRoot          canonical control project root path
 * @param provider             stable provider identifier
 * @param connectionInstanceId process connection instance identifier
 * @param relativePath         repository-relative target path
 * @param create               {@code true} for create mode, {@code false} for modify mode
 * @param content              new proposed full content (for create mode)
 * @param expectedHash         SHA-256 hex string of existing content (required for modify mode)
 * @param edits                ordered list of replacement edits (for modify mode)
 */
public record PatchRequest(Path controlRoot, String provider, String connectionInstanceId, String relativePath, boolean create, String content, String expectedHash, List<PatchEdit> edits) {

    /**
     * Validates patch request fields.
     */
    public PatchRequest {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        Objects.requireNonNull(relativePath, "relativePath");
    }
}
