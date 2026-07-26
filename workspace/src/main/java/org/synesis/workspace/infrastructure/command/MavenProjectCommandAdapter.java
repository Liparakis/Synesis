package org.synesis.workspace.infrastructure.command;

import org.synesis.workspace.application.ProjectCommandAdapter;
import org.synesis.workspace.application.ProjectCommandIntent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for executing Apache Maven build system commands inside the assigned worktree.
 *
 * @since 1.0
 */
public final class MavenProjectCommandAdapter implements ProjectCommandAdapter {

    /**
     * Creates a Maven project command adapter.
     */
    public MavenProjectCommandAdapter() {
    }

    @Override
    public String id() {
        return "maven";
    }

    @Override
    public boolean supports(Path worktreePath) {
        if (worktreePath == null || !Files.isDirectory(worktreePath)) {
            return false;
        }
        return Files.exists(worktreePath.resolve("pom.xml"));
    }

    @Override
    public List<String> buildCommandTokens(Path worktreePath, ProjectCommandIntent intent) {
        if (intent == null || intent.type() == null) {
            throw new IllegalArgumentException("Invalid command intent");
        }

        boolean isWin = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String wrapper = isWin ? "mvnw.cmd" : "./mvnw";
        String executable = Files.exists(worktreePath.resolve(isWin ? "mvnw.cmd" : "mvnw"))
                ? worktreePath.resolve(wrapper).toAbsolutePath().normalize().toString()
                : (isWin ? "mvn.cmd" : "mvn");

        List<String> tokens = new ArrayList<>();
        tokens.add(executable);

        String type = intent.type().toLowerCase(Locale.ROOT);
        switch (type) {
            case "build" -> tokens.add("compile");
            case "test" -> {
                if (intent.target() != null && !intent.target().isBlank()) {
                    tokens.add("-Dtest=" + intent.target().trim());
                }
                tokens.add("test");
            }
            case "lint" -> tokens.add("checkstyle:check");
            case "format_check" -> tokens.add("spotless:check");
            default -> throw new IllegalArgumentException("Unsupported Maven command type: " + type);
        }

        return tokens;
    }
}
