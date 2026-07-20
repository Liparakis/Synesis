package org.synesis.link.transport;

/** Stable classifications for bounded terminal onboarding failures. */
public enum OnboardingFailureCode {
    /** The local identity could not be loaded or created. */
    IDENTITY_FAILED,
    /** The supplied invitation is malformed, invalid, or expired. */
    INVITE_INVALID,
    /** The invitation's authenticated host identity did not match. */
    HOST_IDENTITY_MISMATCH,
    /** Candidate gathering or pairing produced no usable route. */
    NO_USABLE_CANDIDATE,
    /** The host wait exceeded its bounded deadline. */
    HOST_TIMEOUT,
    /** A bounded connection attempt failed. */
    CONNECTION_FAILED,
    /** An unexpected onboarding fault occurred. */
    INTERNAL
}
