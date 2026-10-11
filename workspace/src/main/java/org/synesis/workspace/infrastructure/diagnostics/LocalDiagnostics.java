package org.synesis.workspace.infrastructure.diagnostics;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;

/**
 * Keeps what is needed to find out why something failed, in the project's local, unshared state
 * (ADR-0091).
 *
 * <p>An answer to an agent says that something failed; it must not carry a stack. Before this, a
 * caught exception became such an answer and was gone: a session that could not be bound once in
 * a real run left nothing to say why, and a hook that should have denied a write left no sign of
 * having run. One line is written per entry, so a file cut short by a crash loses one entry.</p>
 *
 * <p>Recording never fails the operation it describes. When the entry cannot be written the
 * caller gets an empty identifier and carries on.</p>
 */
public final class LocalDiagnostics {

    private static final String DIRECTORY = ".synesis/local/diagnostics";
    private static final String FAILURE_POINTS = "failure-points.jsonl";
    private static final String HOOKS = "hooks.jsonl";
    /** One earlier file is kept beside the current one, so the two bound what a project holds. */
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    private static final int MAX_STACK_CHARACTERS = 16 * 1024;

    private LocalDiagnostics() {
    }

    /**
     * Records where and why something failed.
     *
     * @param projectRoot project the failure belongs to, or {@code null} when none was resolved
     * @param component   the part of Synesis that failed
     * @param operation   what it was doing
     * @param reason      the reason given to the caller
     * @param cause       the exception behind it, or {@code null}
     * @return the entry's identifier, or an empty string when it could not be written
     */
    public static String failurePoint(Path projectRoot, String component, String operation, String reason, Throwable cause) {
        String id = "fp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("at", Instant.now().toString());
        entry.put("component", component);
        entry.put("operation", operation);
        entry.put("reason", String.valueOf(reason));
        if (cause != null) {
            List<String> chain = new ArrayList<>();
            for (Throwable link = cause; link != null && chain.size() < 16; link = link.getCause()) {
                chain.add(link.getClass().getName() + ": " + link.getMessage());
            }
            entry.put("causes", chain);
            StringWriter stack = new StringWriter();
            cause.printStackTrace(new PrintWriter(stack));
            entry.put("stack", stack.toString().length() > MAX_STACK_CHARACTERS ? stack.toString().substring(0, MAX_STACK_CHARACTERS) : stack.toString());
        }
        return append(projectRoot, FAILURE_POINTS, entry) ? id : "";
    }

    /**
     * Records that a provider hook ran, what it decided and why.
     *
     * @param projectRoot project the hook resolved, or {@code null} when it resolved none
     * @param provider    provider whose hook ran
     * @param event       hook event name as the provider sent it
     * @param outcome     what the hook decided
     * @param reason      why, in the hook's own words
     */
    public static void hook(Path projectRoot, String provider, String event, String outcome, String reason) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("at", Instant.now().toString());
        entry.put("provider", provider);
        entry.put("event", String.valueOf(event));
        entry.put("outcome", String.valueOf(outcome));
        entry.put("reason", String.valueOf(reason));
        append(projectRoot, HOOKS, entry);
    }

    /**
     * Returns where the diagnostics of a project are kept.
     *
     * @param projectRoot project root, or {@code null} for what belongs to no project
     * @return the diagnostics directory
     */
    public static Path directory(Path projectRoot) {
        return projectRoot == null ? AdministrativeStateLocator.applicationStateRoot().resolve("diagnostics") : projectRoot.resolve(DIRECTORY);
    }

    private static synchronized boolean append(Path projectRoot, String fileName, Map<String, Object> entry) {
        try {
            Path directory = directory(projectRoot);
            Files.createDirectories(directory);
            Path file = directory.resolve(fileName);
            if (Files.exists(file) && Files.size(file) > MAX_FILE_BYTES) {
                Files.move(file, directory.resolve(fileName + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, ProviderJson.write(entry) + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (Exception unwritable) {
            return false;
        }
    }
}
