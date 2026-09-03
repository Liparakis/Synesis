package org.synesis.workspace.application.provider.continuity;

import java.util.Objects;

/**
 * Durable ownership of one provider thread by one existing Synesis binding.
 *
 * <p>This record is a provider-resource lease, not a second Synesis identity.
 * Its key is the provider and exact provider thread; attachment generations
 * remain a separate runtime-freshness fence.</p>
 *
 * @param schemaVersion durable record schema version
 * @param projectId project owning the binding
 * @param provider canonical provider identifier
 * @param providerThreadId exact provider thread selector
 * @param bindingSessionId existing Synesis provider binding
 * @param status ownership lifecycle state
 * @param revision monotonically increasing record revision
 * @param acquiredAtEpochMillis first acquisition time
 * @param updatedAtEpochMillis last record update time
 */
public record ProviderThreadOwnershipRecord(
        int schemaVersion,
        String projectId,
        String provider,
        String providerThreadId,
        String bindingSessionId,
        Status status,
        long revision,
        long acquiredAtEpochMillis,
        long updatedAtEpochMillis) {

    /** Current durable ownership record format. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Lifecycle states for a provider-thread ownership record. */
    public enum Status {
        /** The provider thread is owned by the binding. */
        ACTIVE,
        /** Ownership was released by an explicit lawful terminal lifecycle. */
        RELEASED
    }

    /** Validates the durable ownership record. */
    public ProviderThreadOwnershipRecord {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported provider-thread ownership format");
        }
        requireText(projectId, "projectId");
        requireProvider(provider);
        requireText(providerThreadId, "providerThreadId");
        requireText(bindingSessionId, "bindingSessionId");
        Objects.requireNonNull(status, "status");
        if (revision < 1 || acquiredAtEpochMillis <= 0 || updatedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("invalid provider-thread ownership timestamps or revision");
        }
    }

    private static void requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.length() > 8_192 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }

    private static void requireProvider(String value) {
        requireText(value, "provider");
        if (!value.matches("[a-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("provider is invalid");
        }
    }
}
