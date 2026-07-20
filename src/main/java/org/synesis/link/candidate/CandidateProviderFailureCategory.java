package org.synesis.link.candidate;

/** Bounded categories for provider diagnostics. */
public enum CandidateProviderFailureCategory {
    /** Provider completed with usable candidates. */
    SUCCESS,
    /** Provider exceeded its individual deadline. */
    TIMEOUT,
    /** Operation cancellation stopped the provider. */
    CANCELLED,
    /** Provider failed without exposing unsafe details. */
    FAILED,
    /** Provider returned invalid or excessive data. */
    INVALID_RESULT,
    /** Provider was skipped by an operation bound. */
    RESOURCE_LIMIT_EXCEEDED
}
