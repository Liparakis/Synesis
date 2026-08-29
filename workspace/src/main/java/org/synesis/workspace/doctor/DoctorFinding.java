package org.synesis.workspace.doctor;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable diagnostic finding record capturing one observed condition.
 *
 * @param code                 machine-readable finding code
 * @param severity             severity classification
 * @param confidence           evidence confidence level
 * @param summary              concise summary string
 * @param explanation          bounded explanation string
 * @param affectedResourceType resource type string
 * @param repairSupported      {@code true} if automated administrative repair is supported
 * @param recommendation       recommended next action
 * @param evidenceFingerprint  evidence fingerprint SHA-256 hash
 * @param details              operator-level diagnostic details map
 * @since 1.0
 */
public record DoctorFinding(
        DoctorFindingCode code,
        DoctorSeverity severity,
        DoctorConfidence confidence,
        String summary,
        String explanation,
        String affectedResourceType,
        boolean repairSupported,
        DoctorRecommendation recommendation,
        String evidenceFingerprint,
        Map<String, String> details
) {

    /**
     * Invariant validation.
     */
    public DoctorFinding {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(explanation, "explanation");
        Objects.requireNonNull(affectedResourceType, "affectedResourceType");
        Objects.requireNonNull(recommendation, "recommendation");
        Objects.requireNonNull(evidenceFingerprint, "evidenceFingerprint");
        Objects.requireNonNull(details, "details");
    }
}
