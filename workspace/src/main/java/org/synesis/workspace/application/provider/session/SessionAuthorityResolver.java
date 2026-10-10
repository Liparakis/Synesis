package org.synesis.workspace.application.provider.session;

import org.synesis.workspace.application.provider.ProviderBindingStatus;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;

import java.util.Objects;
import java.util.function.Predicate;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.infrastructure.digest.Sha256;

/**
 * Resolves one exact provider connection to its authenticated session binding.
 */
public final class SessionAuthorityResolver {

    private final ProviderSessionBindingService bindingService;

    /**
     * Creates a resolver backed by the supplied binding service.
     *
     * @param bindingService binding service
     */
    public SessionAuthorityResolver(ProviderSessionBindingService bindingService) {
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
    }

    /**
     * Resolves an exact active binding; never falls back to a latest provider binding.
     *
     * @param location             project location
     * @param provider             provider ID
     * @param connectionInstanceId exact connection ID
     * @return active binding
     * @throws Exception when the binding cannot be resolved
     */
    public ProviderSessionBindingService.Binding resolve(ProjectLocation location, String provider, String connectionInstanceId) throws Exception {
        return resolveExact(location, provider, connectionInstanceId, status -> status == ProviderBindingStatus.BOUND);
    }

    /**
     * Resolves an exact terminal completed binding for an idempotent completion retry. This never
     * grants mutation authority; it only permits the caller that owns the recorded connection to
     * retrieve its durable result.
     *
     * @param location             project location
     * @param provider             provider ID
     * @param connectionInstanceId exact connection ID
     * @return completed binding
     * @throws Exception when no exact completed binding exists
     */
    public ProviderSessionBindingService.Binding resolveCompleted(ProjectLocation location, String provider, String connectionInstanceId) throws Exception {
        return resolveExact(location, provider, connectionInstanceId, status -> status == ProviderBindingStatus.COMPLETED);
    }

    /**
     * Resolves an exact binding for review-only coordination authority.
     *
     * <p>A completed lane may still act on a grant targeted to its stable
     * participant so it can review a sibling lane in the same WorkGroup.  This method does not reopen
     * the lane for workspace reads or mutations; the consuming operation must still enforce the grant
     * participant, intent, and epoch.</p>
     *
     * @param location             initialized project
     * @param provider             provider ID
     * @param connectionInstanceId exact connection evidence
     * @return exact bound or completed binding
     * @throws Exception when no exact review authority exists
     */
    public ProviderSessionBindingService.Binding resolveReview(ProjectLocation location, String provider, String connectionInstanceId) throws Exception {
        return resolveExact(location, provider, connectionInstanceId, ProviderBindingStatus::isBoundOrCompleted);
    }

    /**
     * The connection evidence is hashed as given (no trimming and no fallback nonce, unlike
     * {@code ProviderSessionBindingService.ensure}), and the status is compared ignoring case, as
     * every resolver here always has.
     */
    private ProviderSessionBindingService.Binding resolveExact(ProjectLocation location, String provider, String connectionInstanceId, Predicate<ProviderBindingStatus> acceptedStatus) throws Exception {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        String fingerprint = Sha256.hex(connectionInstanceId);
        ProviderSessionBindingService.Binding binding = bindingService.list(location, provider).stream()
                .filter(candidate -> fingerprint.equals(candidate.providerInstanceFingerprint()) || connectionInstanceId.equals(candidate.sessionId()))
                .filter(candidate -> acceptedStatus.test(ProviderBindingStatus.parseIgnoreCase(candidate.status()))).findFirst().orElseThrow(() -> new IllegalStateException("SESSION_NOT_FOUND"));
        if (bindingService.isSessionTerminal(location, binding.sessionId())) {
            throw new IllegalStateException("SESSION_TERMINAL");
        }
        return binding;
    }
}
