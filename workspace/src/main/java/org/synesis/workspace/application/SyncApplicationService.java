package org.synesis.workspace.application;

import java.nio.file.Path;
import java.util.Objects;


/** Delegates bounded synchronization orchestration without exposing CLI parsing to callers. */
public final class SyncApplicationService {
    /** Creates the service. */
    public SyncApplicationService() {
    }

    /**
     * Runs a bounded host operation using the legacy orchestration path.
     *
     * @param profile local profile directory
     * @param project optional project identifier
     * @param record optional record identifier
     * @return structured status
     */
    public SyncResult host(Path profile, String project, String record) {
        return invoke(profile, project, record, null, null);
    }

    /**
     * Runs a bounded join operation using the legacy orchestration path.
     *
     * @param profile local profile directory
     * @param project project identifier
     * @param record optional record identifier
     * @param expectedHost optional expected host node ID
     * @param invitation signed invitation link
     * @return structured status
     */
    public SyncResult join(Path profile, String project, String record, String expectedHost, String invitation) {
        return invoke(profile, project, record, expectedHost, invitation);
    }

    private SyncResult invoke(Path profile, String project, String record, String expectedHost, String invitation) {
        Objects.requireNonNull(profile, "profile");
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add("--profile");
        args.add(profile.toString());
        args.add("sync");
        args.add(invitation == null ? "host" : "join");
        if (project != null) { args.add("--project"); args.add(project); }
        if (record != null) { args.add("--record"); args.add(record); }
        if (expectedHost != null) { args.add("--expect-host"); args.add(expectedHost); }
        if (invitation != null) args.add(invitation);
        return new SyncResult(WorkspaceOperations.run(args.toArray(String[]::new)), java.util.Map.of());
    }

    /**
     * Structured synchronization result.
     * @param exitCode operation exit code
     * @param values machine-readable values, when available
     */
    public record SyncResult(int exitCode, java.util.Map<String, String> values) {
        /** Validates the result. */
        public SyncResult {
            Objects.requireNonNull(values, "values");
        }
    }
}
