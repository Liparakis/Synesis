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
 * @param capabilityRequestHandle exact requester-owned capability under validation, or {@code null}
 * @param implementationRevision exact implementation revision under validation, or {@code null}
 */
public record ReadRequest(Path controlRoot,
        String provider,
        String connectionInstanceId,
        String relativePath,
        Integer startLine,
        Integer endLine,
        Integer maxBytes,
        String capabilityRequestHandle,
        Integer implementationRevision) {

    /**
     * Retains the normal lane/review read request shape.
     *
     * @param controlRoot project control root
     * @param provider provider identifier
     * @param connectionInstanceId exact provider connection
     * @param relativePath repository-relative file path
     * @param startLine first line to read
     * @param endLine last line to read
     * @param maxBytes maximum UTF-8 response size
     */
    public ReadRequest(Path controlRoot, String provider, String connectionInstanceId, String relativePath, Integer startLine, Integer endLine, Integer maxBytes) {
        this(controlRoot, provider, connectionInstanceId, relativePath, startLine, endLine, maxBytes, null, null);
    }

    /**
     * Validates request parameters.
     */
    public ReadRequest {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
    }
}
