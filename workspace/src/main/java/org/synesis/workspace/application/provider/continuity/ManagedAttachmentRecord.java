package org.synesis.workspace.application.provider.continuity;

import java.util.Objects;

/**
 * Adapter-private durable managed attachment metadata.
 *
 * <p>Only the SHA-256 proof hash is stored. The record references the existing
 * binding and exact provider thread; it is not a replacement coordination
 * identity.</p>
 *
 * @param schemaVersion        durable record schema version
 * @param projectId            existing Synesis project identity
 * @param provider             canonical provider identifier
 * @param mode                 provider continuity mode
 * @param bindingSessionId     existing provider binding session
 * @param threadId             exact provider thread
 * @param generation           attachment generation fence
 * @param proofHash            SHA-256 hash of the attachment proof
 * @param runtimeHomeId        opaque isolated runtime-home identity
 * @param status               attachment lifecycle state
 * @param revision             adapter-private record revision
 * @param updatedAtEpochMillis record update time
 */
public record ManagedAttachmentRecord(int schemaVersion, String projectId, String provider,
                                     ProviderContinuityMode mode, String bindingSessionId,
                                     String threadId, long generation, String proofHash,
                                     String runtimeHomeId, Status status, long revision,
                                     long updatedAtEpochMillis) {

    /** Current durable record format. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Durable attachment lifecycle states. */
    public enum Status {
        /** Current attachment is accepted. */
        ACTIVE,
        /** Proof exists but the trusted provider-thread join is not complete. */
        PENDING_ACTIVATION,
        /** No current process is attached, but replacement may be authenticated. */
        DISCONNECTED,
        /** Binding or logical session is terminal and cannot be revived. */
        TERMINAL
    }

    /** Validates the durable format and bounded fields. */
    public ManagedAttachmentRecord {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported managed attachment format");
        }
        requireText(projectId, "projectId");
        requireText(provider, "provider");
        Objects.requireNonNull(mode, "mode");
        requireText(bindingSessionId, "bindingSessionId");
        requireText(threadId, "threadId");
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be positive");
        }
        requireHex(proofHash, "proofHash");
        requireText(runtimeHomeId, "runtimeHomeId");
        Objects.requireNonNull(status, "status");
        if (revision < 1 || updatedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("invalid managed attachment revision");
        }
    }

    private static void requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.length() > 8_192) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }

    private static void requireHex(String value, String label) {
        requireText(value, label);
        if (!value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(label + " is not a SHA-256 digest");
        }
    }
}
