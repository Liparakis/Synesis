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
        StringBuilder sb = new StringBuilder();
        sb.append("DOCTOR_RESULT=").append(report.overallStatus().name()).append("\n");
        sb.append("FINDINGS=").append(report.findings().size()).append("\n");
        sb.append("CRITICAL=").append(report.criticalCount()).append("\n");
        sb.append("ERRORS=").append(report.errorCount()).append("\n");
        sb.append("WARNINGS=").append(report.warningCount()).append("\n");
        sb.append("CLEANUP_RECOMMENDED=").append(report.cleanupRecommended()).append("\n");
        sb.append("RECONCILIATION_RECOMMENDED=").append(report.reconciliationRecommended()).append("\n");
        sb.append("REPAIR_AVAILABLE=").append(report.repairAvailable()).append("\n");
        sb.append("MUTATIONS_PERFORMED=0\n");

        DoctorRecommendation primaryRec = derivePrimaryRecommendation(report);
        sb.append("NEXT_ACTION=").append(primaryRec.value());

        return sb.toString();
    }

    /**
     * Renders a detailed verbose doctor report string.
     *
     * @param report doctor report
     * @return verbose text representation
     */
    public static String renderVerbose(DoctorReport report) {
        Objects.requireNonNull(report, "report");
        StringBuilder sb = new StringBuilder(renderConcise(report));
        sb.append("\n--- DIAGNOSTIC FINDINGS ---\n");
        for (DoctorFinding f : report.findings()) {
            sb.append("[").append(f.severity().name()).append("] ")
                    .append(f.code().value())
                    .append(" (").append(f.confidence().name()).append(") - ")
                    .append(f.summary()).append("\n");
            sb.append("  Explanation: ").append(f.explanation()).append("\n");
            sb.append("  Recommendation: ").append(f.recommendation().value()).append("\n");
            sb.append("  Repair Supported: ").append(f.repairSupported()).append("\n");
        }
        return sb.toString();
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
        for (DoctorFinding f : report.findings()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("code", f.code().value());
            fm.put("severity", f.severity().name());
            fm.put("confidence", f.confidence().name());
            fm.put("summary", f.summary());
            fm.put("explanation", f.explanation());
            fm.put("affectedResourceType", f.affectedResourceType());
            fm.put("repairSupported", f.repairSupported());
            fm.put("recommendation", f.recommendation().value());
            fm.put("evidenceFingerprint", f.evidenceFingerprint());
            findingsList.add(fm);
        }
        map.put("findings", findingsList);

        return ProviderJson.write(map);
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
