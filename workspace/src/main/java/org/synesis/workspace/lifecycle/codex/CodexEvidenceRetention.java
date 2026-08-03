package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded cleanup for closed Codex connection-generation evidence.
 *
 * <p>Only closed generations with durable manifests are candidates. The
 * newest eight unreferenced generations are retained; any file whose name is
 * referenced by project state, an acceptance report, or a checkpoint is
 * preserved. Cleanup is best effort and never changes lifecycle state.
 *
 * @since 1.0
 */
public final class CodexEvidenceRetention {

    /** Maximum unreferenced closed generations retained for one binding. */
    public static final int MAX_UNREFERENCED_GENERATIONS = 8;
    private static final Pattern GENERATION = Pattern.compile("generation-(\\d+)\\.jsonl");

    private CodexEvidenceRetention() {
        // Utility class.
    }

    /**
     * Result of one deterministic cleanup attempt.
     *
     * @param deleted generations deleted
     * @param retained generations retained
     * @param failures delete or discovery failures
     */
    public record CleanupResult(int deleted, int retained, int failures) {
        /**
         * Returns whether cleanup completed without delete failures.
         *
         * @return success state
         */
        public boolean successful() {
            return failures == 0;
        }
    }

    /**
     * Cleans old unreferenced generation journals after manifests are durable.
     *
     * @param evidenceDirectory binding evidence directory
     * @param activeGeneration generation referenced by the active checkpoint
     * @param projectRoot project root used to find durable references
     * @return bounded cleanup result
     */
    public static CleanupResult cleanup(Path evidenceDirectory, long activeGeneration, Path projectRoot) {
        Objects.requireNonNull(evidenceDirectory, "evidenceDirectory");
        Objects.requireNonNull(projectRoot, "projectRoot");
        if (!Files.isDirectory(evidenceDirectory)) {
            return new CleanupResult(0, 0, 0);
        }
        try {
            List<Path> files;
            try (var stream = Files.list(evidenceDirectory)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(path -> GENERATION.matcher(path.getFileName().toString()).matches())
                        .sorted(Comparator.comparingLong(CodexEvidenceRetention::generation).reversed())
                        .toList();
            }
            int unreferenced = 0;
            int retained = 0;
            int deleted = 0;
            int failures = 0;
            for (Path journal : files) {
                long generation = generation(journal);
                Path manifest = journal.resolveSibling(journal.getFileName() + ".manifest.json");
                if (!Files.isRegularFile(manifest) || generation == activeGeneration
                        || referenced(projectRoot, evidenceDirectory, journal.getFileName().toString())
                        || referenced(projectRoot, evidenceDirectory, manifest.getFileName().toString())) {
                    retained++;
                    continue;
                }
                if (unreferenced++ < MAX_UNREFERENCED_GENERATIONS) {
                    retained++;
                    continue;
                }
                try {
                    Files.deleteIfExists(journal);
                    Files.deleteIfExists(manifest);
                    deleted++;
                } catch (IOException failure) {
                    failures++;
                }
            }
            return new CleanupResult(deleted, retained, failures);
        } catch (IOException failure) {
            return new CleanupResult(0, 0, 1);
        }
    }

    private static long generation(Path path) {
        Matcher matcher = GENERATION.matcher(path.getFileName().toString());
        return matcher.matches() ? Long.parseLong(matcher.group(1)) : Long.MIN_VALUE;
    }

    private static boolean referenced(Path root, Path evidenceDirectory, String fileName) {
        try (var stream = Files.walk(root)) {
            Path evidence = evidenceDirectory.toAbsolutePath().normalize();
            return stream.filter(Files::isRegularFile)
                    .filter(path -> !path.toAbsolutePath().normalize().startsWith(evidence))
                    .anyMatch(path -> contains(path, fileName));
        } catch (IOException failure) {
            return true;
        }
    }

    private static boolean contains(Path path, String value) {
        try {
            if (Files.size(path) > 1_048_576L) {
                return false;
            }
            return Files.readString(path, StandardCharsets.UTF_8).contains(value);
        } catch (IOException | RuntimeException failure) {
            return true;
        }
    }
}
