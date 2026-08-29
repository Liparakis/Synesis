package org.synesis.workspace.application.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import org.synesis.workspace.application.ProjectApplicationService;

/** Resolves one exact provider connection to its authenticated session binding. */
public final class SessionAuthorityResolver {
    private final ProviderSessionBindingService bindingService;

    /** Creates a resolver backed by the supplied binding service.
     * @param bindingService binding service
     */
    public SessionAuthorityResolver(ProviderSessionBindingService bindingService) {
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
    }

    /** Resolves an exact active binding; never falls back to a latest provider binding.
     * @param location project location
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @return active binding
     * @throws Exception when the binding cannot be resolved
     */
    public ProviderSessionBindingService.Binding resolve(ProjectApplicationService.ProjectLocation location,
            String provider, String connectionInstanceId) throws Exception {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(connectionInstanceId.getBytes(StandardCharsets.UTF_8)));
        ProviderSessionBindingService.Binding binding = bindingService.list(location, provider).stream()
                .filter(candidate -> fingerprint.equals(candidate.providerInstanceFingerprint())
                        || connectionInstanceId.equals(candidate.sessionId()))
                .filter(candidate -> "BOUND".equalsIgnoreCase(candidate.status()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("SESSION_NOT_FOUND"));
        if (bindingService.isSessionTerminal(location, binding.sessionId())) {
            throw new IllegalStateException("SESSION_TERMINAL");
        }
        return binding;
    }

    /**
     * Resolves an exact terminal completed binding for an idempotent completion
     * retry. This never grants mutation authority; it only permits the caller
     * that owns the recorded connection to retrieve its durable result.
     *
     * @param location project location
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @return completed binding
     * @throws Exception when no exact completed binding exists
     */
    public ProviderSessionBindingService.Binding resolveCompleted(ProjectApplicationService.ProjectLocation location,
            String provider, String connectionInstanceId) throws Exception {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(connectionInstanceId.getBytes(StandardCharsets.UTF_8)));
        ProviderSessionBindingService.Binding binding = bindingService.list(location, provider).stream()
                .filter(candidate -> fingerprint.equals(candidate.providerInstanceFingerprint())
                        || connectionInstanceId.equals(candidate.sessionId()))
                .filter(candidate -> "COMPLETED".equalsIgnoreCase(candidate.status()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("SESSION_NOT_FOUND"));
        if (bindingService.isSessionTerminal(location, binding.sessionId())) {
            throw new IllegalStateException("SESSION_TERMINAL");
        }
        return binding;
    }

    /**
     * Resolves an exact binding for review-only coordination authority.
     *
     * <p>A completed lane may still act on a grant targeted to its stable
     * participant so it can review a sibling lane in the same WorkGroup.  This
     * method does not reopen the lane for workspace reads or mutations; the
     * consuming operation must still enforce the grant participant, intent,
     * and epoch.</p>
     *
     * @param location initialized project
     * @param provider provider ID
     * @param connectionInstanceId exact connection evidence
     * @return exact bound or completed binding
     * @throws Exception when no exact review authority exists
     */
    public ProviderSessionBindingService.Binding resolveReview(ProjectApplicationService.ProjectLocation location,
            String provider, String connectionInstanceId) throws Exception {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(connectionInstanceId.getBytes(StandardCharsets.UTF_8)));
        ProviderSessionBindingService.Binding binding = bindingService.list(location, provider).stream()
                .filter(candidate -> fingerprint.equals(candidate.providerInstanceFingerprint())
                        || connectionInstanceId.equals(candidate.sessionId()))
                .filter(candidate -> "BOUND".equalsIgnoreCase(candidate.status())
                        || "COMPLETED".equalsIgnoreCase(candidate.status()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("SESSION_NOT_FOUND"));
        if (bindingService.isSessionTerminal(location, binding.sessionId())) {
            throw new IllegalStateException("SESSION_TERMINAL");
        }
        return binding;
    }
}
