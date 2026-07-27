package org.synesis.workspace.provider.codex;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.workspace.WorkspaceMutationBroker;

/** Routes supported Codex native patches through the Synesis mutation broker. */
public final class CodexNativePatchRouter {

    private final CodexApplyPatchParser parser = new CodexApplyPatchParser();
    private final WorkspaceMutationBroker broker = new WorkspaceMutationBroker();

    /** Creates a native patch router. */
    public CodexNativePatchRouter() {
    }

    /** Routes one complete Codex patch into the assigned worktree. */
    public RouteResult route(ProjectApplicationService.ProjectLocation location,
            ProviderSessionBindingService.Binding binding, String patch) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(binding, "binding");
        CodexApplyPatchParser.ParseResult parsed = parser.parse(patch);
        if (!parsed.valid()) {
            return new RouteResult(false, "Invalid Codex patch: " + parsed.errorMessage());
        }
        if (parsed.changes().stream().anyMatch(change -> change.operation() == CodexApplyPatchParser.Operation.DELETE
                || change.operation() == CodexApplyPatchParser.Operation.MOVE)) {
            return new RouteResult(false, "Synesis native routing currently supports add/update patches only");
        }
        if (parsed.changes().size() != 1) {
            return new RouteResult(false, "Synesis native routing currently requires one file per patch");
        }
        Path worktree = Path.of(binding.worktreePath()).toAbsolutePath().normalize();
        List<String> lines = Arrays.asList(patch.replace("\r", "").split("\n", -1));
        for (CodexApplyPatchParser.FileChange change : parsed.changes()) {
            String section = section(lines, change.sourcePath());
            Path target = worktree.resolve(change.sourcePath()).normalize();
            if (!target.startsWith(worktree)) {
                return new RouteResult(false, "Native patch target escapes the assigned worktree");
            }
            try {
                String before = Files.exists(target) ? Files.readString(target, StandardCharsets.UTF_8) : "";
                String after = change.operation() == CodexApplyPatchParser.Operation.ADD
                        ? addedContent(section)
                        : updatedContent(before, section);
                WorkspaceMutationBroker.MutationResult result = broker.applyMutation(
                        new WorkspaceMutationBroker.MutationRequest(location, "codex", binding.sessionId(),
                                change.sourcePath(), "apply_patch", after, true, false));
                if (!result.success()) {
                    return new RouteResult(false, result.message());
                }
            } catch (Exception failure) {
                return new RouteResult(false, "Could not route native patch: " + failure.getMessage());
            }
        }
        return new RouteResult(true, "Synesis applied the native patch in the assigned worktree; do not retry it natively.");
    }

    private static String section(List<String> lines, String path) {
        String marker = "*** Update File: " + path;
        String addMarker = "*** Add File: " + path;
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (marker.equals(lines.get(i)) || addMarker.equals(lines.get(i))) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) {
            throw new IllegalArgumentException("Patch section not found for " + path);
        }
        int end = start;
        while (end < lines.size() && !lines.get(end).startsWith("*** ")) {
            end++;
        }
        return String.join("\n", lines.subList(start, end));
    }

    private static String addedContent(String section) {
        List<String> result = new ArrayList<>();
        for (String line : section.split("\n", -1)) {
            if (line.startsWith("+")) {
                result.add(line.substring(1));
            }
        }
        return String.join("\n", result);
    }

    private static String updatedContent(String before, String section) {
        List<String> current = new ArrayList<>(Arrays.asList(before.replace("\r", "").split("\n", -1)));
        List<String> oldLines = new ArrayList<>();
        List<String> newLines = new ArrayList<>();
        for (String line : section.split("\n", -1)) {
            if (line.startsWith("@@")) {
                applyHunk(current, oldLines, newLines);
                oldLines.clear();
                newLines.clear();
            } else if (line.startsWith(" ")) {
                String value = line.substring(1);
                oldLines.add(value);
                newLines.add(value);
            } else if (line.startsWith("-")) {
                oldLines.add(line.substring(1));
            } else if (line.startsWith("+")) {
                newLines.add(line.substring(1));
            }
        }
        applyHunk(current, oldLines, newLines);
        return String.join("\n", current);
    }

    private static void applyHunk(List<String> current, List<String> oldLines, List<String> newLines) {
        if (oldLines.isEmpty() && newLines.isEmpty()) {
            return;
        }
        int index = find(current, oldLines);
        if (index < 0) {
            throw new IllegalArgumentException("Patch context does not match the assigned worktree");
        }
        current.subList(index, index + oldLines.size()).clear();
        current.addAll(index, newLines);
    }

    private static int find(List<String> current, List<String> wanted) {
        if (wanted.isEmpty()) {
            return current.size();
        }
        for (int i = 0; i + wanted.size() <= current.size(); i++) {
            if (current.subList(i, i + wanted.size()).equals(wanted)) {
                return i;
            }
        }
        return -1;
    }

    /** Result of a routed native patch. */
    public record RouteResult(boolean handled, String message) {
        /** Validates the route result. */
        public RouteResult {
            Objects.requireNonNull(message, "message");
        }
    }
}
