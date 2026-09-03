package org.synesis.workspace.application.provider.continuity;

import java.util.Objects;

/**
 * Durable, generation-scoped evidence that a trusted managed runtime tree is
 * definitively dead.
 *
 * <p>The receipt contains process identity and supervisor provenance only. It
 * never contains an attachment proof or provider credential.</p>
 *
 * @param schemaVersion durable receipt format
 * @param projectId project identity
 * @param provider canonical provider identifier
 * @param bindingSessionId exact provider binding
 * @param generation exact managed attachment generation
 * @param rootPid previously owned process root
 * @param rootStartEpochMillis previously verified root start time
 * @param rootExecutable previously verified executable
 * @param rootCommandIdentity previously verified command identity
 * @param supervisorProvenance trusted supervisor identity
 * @param supervisorRevision trusted supervisor evidence revision
 * @param observedAtEpochMillis receipt creation time
 */
public record ManagedRuntimeDeathReceipt(int schemaVersion, String projectId, String provider,
        String bindingSessionId, long generation, long rootPid, long rootStartEpochMillis,
        String rootExecutable, String rootCommandIdentity, String supervisorProvenance,
        long supervisorRevision, long observedAtEpochMillis) {

    /** Current durable death-receipt format. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Validates the bounded, non-secret death evidence. */
    public ManagedRuntimeDeathReceipt {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported managed death receipt format");
        }
        requireText(projectId, "projectId");
        requireText(provider, "provider");
        requireText(bindingSessionId, "bindingSessionId");
        if (generation < 1 || rootPid < 1 || rootStartEpochMillis < 1 || supervisorRevision < 1
                || observedAtEpochMillis < 1) {
            throw new IllegalArgumentException("invalid managed death receipt scope");
        }
        requireText(rootExecutable, "rootExecutable");
        requireText(rootCommandIdentity, "rootCommandIdentity");
        requireText(supervisorProvenance, "supervisorProvenance");
    }

    private static void requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.length() > 8_192) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }
}
