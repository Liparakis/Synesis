package org.synesis.workspace.infrastructure.command;

import org.synesis.workspace.application.ProjectCommandAdapter;
import org.synesis.workspace.application.ProjectCommandIntent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for executing Gradle build system commands inside the assigned worktree.
 *
 * @since 1.0
 */
public final class GradleProjectCommandAdapter implements ProjectCommandAdapter {

    /**
     * Creates a Gradle project command adapter.
     */
    public GradleProjectCommandAdapter() {
    }

    @Override
    public String id() {
        return "gradle";
    }

    @Override
    public boolean supports(Path worktreePath) {
        if (worktreePath == null || !Files.isDirectory(worktreePath)) {
            return false;
        }
        return Files.exists(worktreePath.resolve("build.gradle"))
                || Files.exists(worktreePath.resolve("build.gradle.kts"))
                || Files.exists(worktreePath.resolve("settings.gradle"))
                || Files.exists(worktreePath.resolve("settings.gradle.kts"));
    }

    @Override
    public List<String> buildCommandTokens(Path worktreePath, ProjectCommandIntent intent) {
        if (intent == null || intent.type() == null) {
            throw new IllegalArgumentException("Invalid command intent");
        }

        boolean isWin = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String wrapper = isWin ? "gradlew.bat" : "./gradlew";
        String executable = Files.exists(worktreePath.resolve(isWin ? "gradlew.bat" : "gradlew"))
                ? worktreePath.resolve(wrapper).toAbsolutePath().normalize().toString()
                : (isWin ? "gradle.bat" : "gradle");

        List<String> tokens = new ArrayList<>();
        tokens.add(executable);

        String type = intent.type().toLowerCase(Locale.ROOT);
        switch (type) {
            case "build" -> tokens.add("build");
            case "test" -> {
                tokens.add("test");
                if (intent.target() != null && !intent.target().isBlank()) {
                    tokens.add("--tests");
                    tokens.add(intent.target().trim());
                }
            }
            case "lint" -> tokens.add("check");
            case "format_check" -> tokens.add("formatCheck");
            default -> throw new IllegalArgumentException("Unsupported Gradle command type: " + type);
        }

        return tokens;
    }
}
