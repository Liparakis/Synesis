package org.synesis.workspace.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * The shared mechanics of the cleanup, reconciliation, and repair execution journals: one
 * {@code <execution-id>.jsonl} file per run in the journal directory of its kind, one JSON object
 * per line, appended and never rewritten.
 *
 * <p>Each journal keeps its own record-to-map conversion and key names, which are persisted.</p>
 *
 * @since 1.0
 */
public final class AdministrativeJournal {

    private final Path journalFile;

    /**
     * Opens the journal of one execution, creating its directory.
     *
     * @param directory   journal directory of the execution kind
     * @param executionId execution run identifier
     * @throws IOException if the directory cannot be created
     */
    public AdministrativeJournal(Path directory, String executionId) throws IOException {
        Files.createDirectories(directory);
        this.journalFile = directory.resolve(executionId + ".jsonl");
    }

    /**
     * Appends one entry as a JSON line.
     *
     * @param entry the entry's keys in persisted order
     * @throws IOException if writing fails
     */
    public synchronized void append(Map<String, Object> entry) throws IOException {
        Objects.requireNonNull(entry, "entry");
        Files.writeString(journalFile, ProviderJson.write(entry) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Reads every journal of a kind to find the entries a plan already completed.
     *
     * <p>A journal that cannot be read or parsed is skipped, so damage reads as "not completed"
     * and the entry may run again.</p>
     *
     * @param controlRoot    control project root path
     * @param directoryOf    resolves the journal directory of the execution kind
     * @param planId         plan identifier
     * @param idKey          journal key holding the plan entry identifier
     * @param completedState value of the {@code state} key that marks an entry completed
     * @return identifiers of the entries completed for the plan
     */
    @SuppressWarnings("unchecked")
    public static Set<String> completedIds(Path controlRoot, Function<Path, Path> directoryOf, String planId, String idKey, String completedState) {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(planId, "planId");

        Set<String> completed = new HashSet<>();
        try {
            Path directory = directoryOf.apply(controlRoot.toAbsolutePath().normalize());
            if (!Files.isDirectory(directory)) {
                return completed;
            }

            try (var stream = Files.list(directory)) {
                for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                    try {
                        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                            if (line.isBlank()) {
                                continue;
                            }
                            Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(line);
                            String foundPlanId = (String) map.get("planId");
                            String id = (String) map.get(idKey);
                            String state = (String) map.get("state");
                            if (planId.equals(foundPlanId) && completedState.equals(state) && id != null) {
                                completed.add(id);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return completed;
    }
}
