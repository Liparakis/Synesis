package org.synesis.workspace.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for executing Node.js npm package commands inside the assigned worktree.
 *
 * @since 1.0
 */
public final class NpmProjectCommandAdapter implements ProjectCommandAdapter {

    /**
     * Creates an npm project command adapter.
     */
    public NpmProjectCommandAdapter() {
    }

    @Override
    public String id() {
        return "npm";
    }

    @Override
    public boolean supports(Path worktreePath) {
        if (worktreePath == null || !Files.isDirectory(worktreePath)) {
            return false;
        }
        return Files.exists(worktreePath.resolve("package.json"));
    }

    @Override
    public List<String> buildCommandTokens(Path worktreePath, ProjectCommandIntent intent) {
        if (intent == null || intent.type() == null) {
            throw new IllegalArgumentException("Invalid command intent");
        }

        boolean isWin = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String executable = isWin ? "npm.cmd" : "npm";

        List<String> tokens = new ArrayList<>();
        tokens.add(executable);

        String type = intent.type().toLowerCase(Locale.ROOT);
        switch (type) {
            case "build" -> {
                tokens.add("run");
                tokens.add("build");
            }
            case "test" -> {
                tokens.add("test");
                if (intent.target() != null && !intent.target().isBlank()) {
                    tokens.add("--");
                    tokens.add(intent.target().trim());
                }
            }
            case "lint" -> {
                tokens.add("run");
                tokens.add("lint");
            }
            case "format_check" -> {
                tokens.add("run");
                tokens.add("format:check");
            }
            default -> throw new IllegalArgumentException("Unsupported npm command type: " + type);
        }

        return tokens;
    }
}
