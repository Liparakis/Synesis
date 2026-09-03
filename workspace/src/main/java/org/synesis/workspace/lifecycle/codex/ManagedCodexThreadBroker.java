package org.synesis.workspace.lifecycle.codex;

import java.util.Objects;
import org.synesis.workspace.application.provider.continuity.ProviderThreadOwnershipRecord;

/**
 * Trusted broker state for one immutable managed Codex provider-thread pin.
 *
 * <p>The broker accepts ownership produced by the durable Synesis store and
 * exposes only the pinned selector. It is not a model-facing App Server
 * protocol passthrough.</p>
 */
public final class ManagedCodexThreadBroker {

    private final ProviderThreadOwnershipRecord ownership;

    /**
     * Creates a broker from an active durable ownership record.
     *
     * @param ownership exact active provider-thread ownership
     */
    public ManagedCodexThreadBroker(ProviderThreadOwnershipRecord ownership) {
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        if (ownership.status() != ProviderThreadOwnershipRecord.Status.ACTIVE
                || !"codex".equals(ownership.provider())) {
            throw new IllegalArgumentException("managed Codex broker requires active Codex ownership");
        }
    }

    /**
     * Returns the exact binding that owns the pin.
     *
     * @return exact binding session identifier
     */
    public String bindingSessionId() {
        return ownership.bindingSessionId();
    }

    /**
     * Returns the provider-owned exact thread selector.
     *
     * @return pinned provider thread identifier
     */
    public String pinnedThreadId() {
        return ownership.providerThreadId();
    }

    /**
     * Returns the durable owner used to create this broker.
     *
     * @return active provider-thread ownership
     */
    public ProviderThreadOwnershipRecord ownership() {
        return ownership;
    }

    /**
     * Verifies an exact provider result before authority activation.
     *
     * @param returnedThreadId provider-returned thread selector
     * @throws IllegalStateException when the provider returned another thread
     */
    public void verifyReturnedThread(String returnedThreadId) {
        if (!pinnedThreadId().equals(returnedThreadId)) {
            throw new IllegalStateException("managed_provider_thread_mismatch");
        }
    }

    /**
     * Rejects an attempted caller-selected thread.
     *
     * @param requestedThreadId untrusted requested selector
     * @throws IllegalStateException when it is not the immutable pin
     */
    public void requirePinnedThread(String requestedThreadId) {
        verifyReturnedThread(requestedThreadId);
    }
}
