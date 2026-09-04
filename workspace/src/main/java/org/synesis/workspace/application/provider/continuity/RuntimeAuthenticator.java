package org.synesis.workspace.application.provider.continuity;

import java.util.Objects;
import org.synesis.workspace.application.ProjectApplicationService;

/**
 * Provider-neutral seam for authenticating a runtime attachment to an
 * existing logical provider binding.
 *
 * <p>Implementations return only an already-authorized binding reference and
 * its current attachment generation. They do not create participants,
 * WorkIntents, claims, or workgroups.</p>
 */
@FunctionalInterface
public interface RuntimeAuthenticator {

    /**
     * Authenticates one exact runtime attachment request.
     *
     * @param location project location
     * @param request  exact attachment request
     * @return authenticated existing runtime
     * @throws Exception when authentication fails closed
     */
    AuthenticatedRuntime authenticate(ProjectApplicationService.ProjectLocation location,
            AttachmentRequest request) throws Exception;

    /**
     * Exact selector and proof presented by a replacement runtime.
     *
     * @param provider           canonical provider identifier
     * @param bindingSessionId   existing provider binding session
     * @param threadId           exact provider thread, or {@code null} for a pending first generation
     * @param expectedGeneration expected attachment generation
     * @param proof              raw proof held only for authentication
     */
    record AttachmentRequest(String provider, String bindingSessionId, String threadId,
                             long expectedGeneration, String proof) {

        /** Validates and freezes the request without retaining a secret copy. */
        public AttachmentRequest {
            requireText(provider, "provider");
            requireText(bindingSessionId, "bindingSessionId");
            if (threadId != null) {
                requireText(threadId, "threadId");
            }
            if (expectedGeneration < 1) {
                throw new IllegalArgumentException("expectedGeneration must be positive");
            }
            requireText(proof, "proof");
        }

        private static void requireText(String value, String label) {
            Objects.requireNonNull(value, label);
            if (value.isBlank() || value.length() > 8_192) {
                throw new IllegalArgumentException(label + " is invalid");
            }
        }
    }

    /**
     * Existing logical binding authenticated for the current attachment.
     *
     * @param provider             canonical provider identifier
     * @param bindingSessionId     existing provider binding session
     * @param mode                 continuity mode
     * @param authenticationMethod bounded authentication method label
     * @param attachmentGeneration authenticated attachment generation
     */
    record AuthenticatedRuntime(String provider, String bindingSessionId,
                                ProviderContinuityMode mode, String authenticationMethod,
                                long attachmentGeneration) {

        /** Validates the bounded authentication result. */
        public AuthenticatedRuntime {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(bindingSessionId, "bindingSessionId");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(authenticationMethod, "authenticationMethod");
            if (provider.isBlank() || bindingSessionId.isBlank() || authenticationMethod.isBlank()
                    || attachmentGeneration < 1) {
                throw new IllegalArgumentException("invalid authenticated runtime");
            }
        }
    }
}
