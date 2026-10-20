package org.synesis.workspace.doctor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        return renderConcise(report, 0);
    }

    /**
     * Renders a concise doctor report string after {@code doctor --fix} made some repairs.
     *
     * @param report    doctor report, made after the repairs
     * @param mutations how many repairs changed something
     * @return concise text representation
     */
    public static String renderConcise(DoctorReport report, int mutations) {
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
        rendered.append("MUTATIONS_PERFORMED=").append(mutations).append("\n");

        DoctorRecommendation primaryRec = derivePrimaryRecommendation(report);
        rendered.append("NEXT_ACTION=").append(primaryRec.value());

        for (DoctorGroup group : NAMED_GROUPS) {
            appendGroup(rendered, report, group);
        }
        return rendered.toString();
    }

    /**
     * The groups the report prints finding by finding; the project's own findings keep their counts above.
     */
    private static final List<DoctorGroup> NAMED_GROUPS = List.of(DoctorGroup.ENVIRONMENT, DoctorGroup.NETWORK, DoctorGroup.WORK_GROUPS);

    /**
     * Prints one group as {@code <GROUP>_STATUS} and, for each finding, its code, mark, message and fix. The network
     * group says it does not apply to a project that is not shared.
     */
    private static void appendGroup(StringBuilder rendered, DoctorReport report, DoctorGroup group) {
        String key = group.name();
        List<DoctorFinding> found = report.findings().stream().filter(finding -> finding.group() == group).toList();
        rendered.append('\n').append(key).append("_STATUS=").append(groupStatus(report, group, found));
        rendered.append('\n').append(key).append("_FINDINGS=").append(found.size());
        for (int i = 0; i < found.size(); i++) {
            DoctorFinding finding = found.get(i);
            String prefix = key + "_FINDING_" + (i + 1);
            rendered.append('\n').append(prefix).append('=').append(finding.code().value());
            rendered.append('\n').append(prefix).append("_MARK=").append(finding.confidence().mark());
            rendered.append('\n').append(prefix).append("_MESSAGE=").append(finding.summary());
            rendered.append('\n').append(prefix).append("_FIX=").append(finding.fix());
            finding.details().forEach((name, value) -> rendered.append('\n').append(prefix).append('_').append(name.toUpperCase(Locale.ROOT)).append('=').append(value));
        }
    }

    private static String groupStatus(DoctorReport report, DoctorGroup group, List<DoctorFinding> found) {
        if (group == DoctorGroup.NETWORK && !report.shared()) {
            return "NOT_APPLICABLE";
        }
        return found.stream().allMatch(finding -> finding.severity() == DoctorSeverity.INFO) ? "OK" : "ATTENTION";
    }

    /**
     * Renders a detailed verbose doctor report string.
     *
     * @param report doctor report
     * @return verbose text representation
     */
    public static String renderVerbose(DoctorReport report) {
        return renderVerbose(report, 0);
    }

    /**
     * Renders a detailed verbose doctor report string after {@code doctor --fix} made some repairs.
     *
     * @param report    doctor report, made after the repairs
     * @param mutations how many repairs changed something
     * @return verbose text representation
     */
    public static String renderVerbose(DoctorReport report, int mutations) {
        Objects.requireNonNull(report, "report");
        StringBuilder rendered = new StringBuilder(renderConcise(report, mutations));
        rendered.append("\n--- DIAGNOSTIC FINDINGS ---\n");
        for (DoctorFinding finding : report.findings()) {
            rendered.append("[").append(finding.severity().name()).append("] ").append(finding.code().value()).append(" (").append(finding.confidence().name()).append(") - ").append(finding.summary()).append("\n");
            rendered.append("  Explanation: ").append(finding.explanation()).append("\n");
            rendered.append("  Recommendation: ").append(finding.recommendation().value()).append("\n");
            rendered.append("  Repair Supported: ").append(finding.repairSupported()).append("\n");
            rendered.append("  Group: ").append(finding.group().value()).append(" (").append(finding.confidence().mark()).append(")\n");
            rendered.append("  Fix: ").append(finding.fix()).append("\n");
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
        return renderJson(report, 0);
    }

    /**
     * Renders a doctor report as a compact JSON string after {@code doctor --fix} made some repairs.
     *
     * @param report    doctor report, made after the repairs
     * @param mutations how many repairs changed something
     * @return JSON representation
     */
    public static String renderJson(DoctorReport report, int mutations) {
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
        map.put("mutationsPerformed", mutations);
        map.put("nextAction", derivePrimaryRecommendation(report).value());

        List<Map<String, Object>> findingsList = new ArrayList<>();
        for (DoctorFinding finding : report.findings()) {
            Map<String, Object> findingFields = findingMap(finding, finding.code().value());
            findingFields.put("evidenceFingerprint", finding.evidenceFingerprint());
            findingFields.put("group", finding.group().value());
            findingFields.put("mark", finding.confidence().mark());
            findingFields.put("fix", finding.fix());
            findingsList.add(findingFields);
        }
        map.put("findings", findingsList);
        Map<String, Object> groups = new LinkedHashMap<>();
        for (DoctorGroup group : NAMED_GROUPS) {
            groups.put(group.value(), groupStatus(report, group, report.findings().stream().filter(finding -> finding.group() == group).toList()));
        }
        map.put("groups", groups);

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
