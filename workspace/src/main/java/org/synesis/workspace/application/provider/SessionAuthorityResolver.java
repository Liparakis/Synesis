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
        return bindingService.list(location, provider).stream()
                .filter(candidate -> fingerprint.equals(candidate.providerInstanceFingerprint())
                        || connectionInstanceId.equals(candidate.sessionId()))
                .filter(candidate -> "BOUND".equalsIgnoreCase(candidate.status()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("SESSION_NOT_FOUND"));
    }
}
