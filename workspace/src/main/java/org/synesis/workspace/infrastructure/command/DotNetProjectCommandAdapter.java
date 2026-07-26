package org.synesis.workspace.infrastructure.command;

import org.synesis.workspace.application.project.ProjectCommandAdapter;
import org.synesis.workspace.application.project.ProjectCommandIntent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for executing .NET CLI build system commands inside the assigned worktree.
 *
 * @since 1.0
 */
public final class DotNetProjectCommandAdapter implements ProjectCommandAdapter {

    /**
     * Creates a .NET project command adapter.
     */
    public DotNetProjectCommandAdapter() {
    }

    @Override
    public String id() {
        return "dotnet";
    }

    @Override
    public boolean supports(Path worktreePath) {
        if (worktreePath == null || !Files.isDirectory(worktreePath)) {
            return false;
        }
        try (var stream = Files.list(worktreePath)) {
            return stream.anyMatch(f -> {
                String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.endsWith(".csproj") || name.endsWith(".fsproj") || name.endsWith(".sln");
            });
        } catch (IOException ex) {
            return false;
        }
    }

    @Override
    public List<String> buildCommandTokens(Path worktreePath, ProjectCommandIntent intent) {
        if (intent == null || intent.type() == null) {
            throw new IllegalArgumentException("Invalid command intent");
        }

        List<String> tokens = new ArrayList<>();
        tokens.add("dotnet");

        String type = intent.type().toLowerCase(Locale.ROOT);
        switch (type) {
            case "build" -> tokens.add("build");
            case "test" -> {
                tokens.add("test");
                if (intent.target() != null && !intent.target().isBlank()) {
                    tokens.add("--filter");
                    tokens.add("FullyQualifiedName~" + intent.target().trim());
                }
            }
            case "lint", "format_check" -> {
                tokens.add("format");
                tokens.add("--verify-no-changes");
            }
            default -> throw new IllegalArgumentException("Unsupported .NET command type: " + type);
        }

        return tokens;
    }
}
