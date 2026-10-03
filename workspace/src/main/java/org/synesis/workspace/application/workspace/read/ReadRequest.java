package org.synesis.workspace.application.workspace.read;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Request parameters for reading a repository file.
 *
 * @param controlRoot          canonical control project root path
 * @param provider             stable provider name
 * @param connectionInstanceId unique process connection instance ID
 * @param relativePath         repository-relative file path
 * @param startLine            1-based starting line number (optional)
 * @param endLine              1-based ending line number (optional)
 * @param maxBytes             maximum UTF-8 bytes to return (optional)
 */
public record ReadRequest(Path controlRoot, String provider, String connectionInstanceId, String relativePath, Integer startLine, Integer endLine, Integer maxBytes) {

    /**
     * Validates request parameters.
     */
    public ReadRequest {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
    }
}
