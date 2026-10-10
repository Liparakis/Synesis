package org.synesis.workspace.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Owns where migrations keep their administrative state and how their execution journals are
 * appended.
 *
 * <p>The default root keeps the Windows layout ({@code %LOCALAPPDATA%\Synesis\admin}) on every
 * host; it is not {@code AdministrativeStateLocator.applicationStateRoot()}, which moves state on
 * other hosts. Journals are line oriented: {@code state=...} lines and JSON {@code
 * MIGRATION_TARGET} lines written here, plus {@code outcome=...} and {@code provider=...} lines
 * appended through {@link #appendLine}. {@code DoctorService} classifies them from the outside.
 */
final class MigrationLayout {

    private static final String EXECUTIONS = "migration-executions";
    private static final String UPDATE_EXECUTIONS = "update-executions";

    private MigrationLayout() {
    }

    static Path defaultAdminRoot() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = Path.of(System.getProperty("user.home"), "AppData", "Local").toString();
        }
        return Path.of(base, "Synesis", "admin");
    }

    /**
     * Returns the journal directories of the default root, or none when no home can be resolved.
     * Update journals are written by the launcher, not by this package.
     */
    static List<Path> transactionJournalDirectories() {
        String home = System.getProperty("user.home");
        String local = System.getenv("LOCALAPPDATA");
        if ((local == null || local.isBlank()) && (home == null || home.isBlank())) {
            return List.of();
        }
        Path admin = defaultAdminRoot();
        return List.of(admin.resolve(UPDATE_EXECUTIONS), admin.resolve(EXECUTIONS));
    }

    static Path plans(Path adminRoot) {
        return adminRoot.resolve("migration-plans");
    }

    static Path backupRoot(Path adminRoot, String planId) {
        return adminRoot.toAbsolutePath().normalize().resolve("migration-backups").resolve(planId);
    }

    static Path journal(Path adminRoot, String planId) {
        return adminRoot.toAbsolutePath().normalize().resolve(EXECUTIONS).resolve(planId + ".jsonl");
    }

    static void appendLine(Path journal, String line) throws IOException {
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static void appendState(Path journal, String state) throws IOException {
        appendLine(journal, "state=" + state);
    }

    static void appendTargetHash(Path journal, Path target, String hash) throws IOException {
        appendLine(journal, ProviderJson.write(Map.of("state", "MIGRATION_TARGET", "target", target.toString(), "hash", hash)));
    }
}
