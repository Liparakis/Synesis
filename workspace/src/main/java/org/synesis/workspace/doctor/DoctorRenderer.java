package org.synesis.workspace.doctor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Formatter and renderer for doctor diagnostic reports.
 *
 * @since 1.0
 */
public final class DoctorRenderer {

    private DoctorRenderer() {
    }

    /**
     * Renders a concise doctor report string.
     *
     * @param report doctor report
     * @return concise text representation
     */
    public static String renderConcise(DoctorReport report) {
        Objects.requireNonNull(report, "report");
        StringBuilder rendered = new StringBuilder();
        rendered.append("DOCTOR_RESULT=").append(report.overallStatus().name()).append("\n");
        rendered.append("FINDINGS=").append(report.findings().size()).append("\n");
        rendered.append("CRITICAL=").append(report.criticalCount()).append("\n");
        rendered.append("ERRORS=").append(report.errorCount()).append("\n");
        rendered.append("WARNINGS=").append(report.warningCount()).append("\n");
        rendered.append("CLEANUP_RECOMMENDED=").append(report.cleanupRecommended()).append("\n");
        rendered.append("RECONCILIATION_RECOMMENDED=").append(report.reconciliationRecommended()).append("\n");
        rendered.append("REPAIR_AVAILABLE=").append(report.repairAvailable()).append("\n");
        rendered.append("MUTATIONS_PERFORMED=0\n");

        DoctorRecommendation primaryRec = derivePrimaryRecommendation(report);
        rendered.append("NEXT_ACTION=").append(primaryRec.value());

        return rendered.toString();
    }

    /**
     * Renders a detailed verbose doctor report string.
     *
     * @param report doctor report
     * @return verbose text representation
     */
    public static String renderVerbose(DoctorReport report) {
        Objects.requireNonNull(report, "report");
        StringBuilder rendered = new StringBuilder(renderConcise(report));
        rendered.append("\n--- DIAGNOSTIC FINDINGS ---\n");
        for (DoctorFinding finding : report.findings()) {
            rendered.append("[").append(finding.severity().name()).append("] ").append(finding.code().value()).append(" (").append(finding.confidence().name()).append(") - ").append(finding.summary()).append("\n");
            rendered.append("  Explanation: ").append(finding.explanation()).append("\n");
            rendered.append("  Recommendation: ").append(finding.recommendation().value()).append("\n");
            rendered.append("  Repair Supported: ").append(finding.repairSupported()).append("\n");
        }
        return rendered.toString();
    }

    /**
     * Renders a doctor report as a compact JSON string.
     *
     * @param report doctor report
     * @return JSON representation
     */
    public static String renderJson(DoctorReport report) {
        Objects.requireNonNull(report, "report");
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("doctorResult", report.overallStatus().name());
        map.put("findingsCount", report.findings().size());
        map.put("critical", report.criticalCount());
        map.put("errors", report.errorCount());
        map.put("warnings", report.warningCount());
        map.put("info", report.infoCount());
        map.put("cleanupRecommended", report.cleanupRecommended());
        map.put("reconciliationRecommended", report.reconciliationRecommended());
        map.put("repairAvailable", report.repairAvailable());
        map.put("mutationsPerformed", 0);
        map.put("nextAction", derivePrimaryRecommendation(report).value());

        List<Map<String, Object>> findingsList = new ArrayList<>();
        for (DoctorFinding finding : report.findings()) {
            Map<String, Object> findingFields = findingMap(finding, finding.code().value());
            findingFields.put("evidenceFingerprint", finding.evidenceFingerprint());
            findingsList.add(findingFields);
        }
        map.put("findings", findingsList);

        return ProviderJson.write(map);
    }

    /**
     * Builds the shared public fields for one finding while letting each wire surface choose its
     * established code representation.
     *
     * @param finding diagnostic finding
     * @param code    wire representation for the finding code
     * @return mutable insertion-ordered finding map
     */
    public static Map<String, Object> findingMap(DoctorFinding finding, String code) {
        Objects.requireNonNull(finding, "finding");
        Objects.requireNonNull(code, "code");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("severity", finding.severity().name());
        result.put("confidence", finding.confidence().name());
        result.put("summary", finding.summary());
        result.put("explanation", finding.explanation());
        result.put("affectedResourceType", finding.affectedResourceType());
        result.put("repairSupported", finding.repairSupported());
        result.put("recommendation", finding.recommendation().value());
        return result;
    }

    private static DoctorRecommendation derivePrimaryRecommendation(DoctorReport report) {
        if (report.overallStatus() == DoctorStatus.UNSAFE) {
            return DoctorRecommendation.HUMAN_REVIEW_REQUIRED;
        }
        if (report.repairAvailable()) {
            return DoctorRecommendation.PREPARE_REPAIR_PLAN;
        }
        if (report.reconciliationRecommended()) {
            return DoctorRecommendation.PREPARE_RECONCILIATION_PLAN;
        }
        if (report.cleanupRecommended()) {
            return DoctorRecommendation.RUN_CLEANUP_DRY_RUN;
        }
        return DoctorRecommendation.NO_ACTION;
    }
}
