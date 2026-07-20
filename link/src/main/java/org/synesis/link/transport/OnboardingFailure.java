package org.synesis.link.transport;

import java.util.Objects;

/**
 * Typed failure at the Link onboarding boundary.
 *
 * <p>Messages are deliberately generic and must not contain invitations,
 * private keys, or profile paths. The causal exception is retained for local
 * diagnostics but is not part of the stable terminal contract.
 *
 * @since 1.0
 */
public final class OnboardingFailure extends Exception {
    private static final long serialVersionUID = 1L;
    /** Stable typed classification for this failure. */
    private final OnboardingFailureCode code;

    /**
     * Creates a redacted typed failure.
     *
     * @param code stable failure classification
     * @param cause underlying failure, or {@code null}
     */
    public OnboardingFailure(OnboardingFailureCode code, Throwable cause) {
        super(Objects.requireNonNull(code, "code").name(), cause);
        this.code = code;
    }

    /**
     * Returns the stable failure classification.
     *
     * @return failure code
     */
    public OnboardingFailureCode code() {
        return code;
    }
}
