package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.synesis.workspace.provider.ProviderIntegration;

/**
 * Lists the files that setting up a project for agents created or changed, so {@code synesis init}
 * can tell the person every place it wrote to, inside the project and outside it (ADR-0103 stage 2).
 *
 * <p>Only the places setup can write are watched: Synesis's own {@code .synesis} directory, Git's
 * private exclude file, {@code AGENTS.md}, the files the caller names (the registry of known projects
 * in Synesis's per-computer state folder), and, for every registered provider, its project
 * configuration, its MCP configuration (the user-wide Codex file among them), the instruction
 * file an earlier version wrote, and its installed manual. The isolated worktree a connected agent
 * is given lives in Synesis's own per-computer workspace folder and is reported by the provider
 * install that creates it, not here. Watching these instead of walking the
 * project keeps the cost independent of the project's size and never reads user code. The file
 * contents are compared by hash and are not kept or printed. A file Synesis removed is reported by
 * the caller that removed it.</p>
 */
public final class TouchedFiles {

    private static final String PROJECT_STATE = ".synesis";

    /**
     * What happened to a watched path.
     */
    public enum Kind {
        /** The path did not exist before. */
        CREATED,
        /** The path existed and its content changed. */
        CHANGED
    }

    /**
     * One created or changed path.
     *
     * @param kind what happened
     * @param path the path, relative to the project root with {@code /} separators when inside it,
     *             otherwise absolute
     */
    public record Entry(Kind kind, String path) {

    }

    private final Path root;
    private final List<Path> elsewhere;
    private final Map<Path, String> before;

    private TouchedFiles(Path root, List<Path> elsewhere, Map<Path, String> before) {
        this.root = root;
        this.elsewhere = elsewhere;
        this.before = before;
    }

    /**
     * Records the watched paths as they are now.
     *
     * @param projectRoot project root
     * @param elsewhere   further files setup writes that this class does not know, such as the registry of known projects
     * @return a snapshot to ask {@link #changes()} of after setup
     */
    public static TouchedFiles watch(Path projectRoot, List<Path> elsewhere) {
        Path root = projectRoot.toAbsolutePath().normalize();
        return new TouchedFiles(root, List.copyOf(elsewhere), snapshot(root, elsewhere));
    }

    /**
     * Compares the watched paths with the snapshot.
     *
     * @return created and changed paths, project paths first, each group in path order
     */
    public List<Entry> changes() {
        Map<Path, String> after = snapshot(root, elsewhere);
        List<Entry> inside = new ArrayList<>();
        List<Entry> outside = new ArrayList<>();
        after.forEach((path, hash) -> {
            String was = before.get(path);
            if (was == null || !was.equals(hash)) {
                boolean project = path.startsWith(root);
                (project ? inside : outside).add(new Entry(was == null ? Kind.CREATED : Kind.CHANGED, project ? root.relativize(path).toString().replace('\\', '/') + (path.getFileName().toString().equals(PROJECT_STATE) ? "/" : "") : path.toString()));
            }
        });
        inside.addAll(outside);
        return List.copyOf(inside);
    }

    private static Map<Path, String> snapshot(Path root, List<Path> elsewhere) {
        Map<Path, String> state = new TreeMap<>();
        // Only whether the state directory exists: its files change on every command and are not setup's output.
        if (Files.isDirectory(root.resolve(PROJECT_STATE))) {
            state.put(root.resolve(PROJECT_STATE), "directory");
        }
        record(state, root.resolve(".git/info/exclude"));
        record(state, root.resolve("AGENTS.md"));
        elsewhere.forEach(file -> record(state, file));
        ProviderManualService manuals = new ProviderManualService();
        for (ProviderIntegration provider : ProviderRegistry.providers()) {
            record(state, provider.configurationPath(root));
            record(state, provider.mcpConfigurationPath(root));
            provider.retiredProjectInstructions(root).ifPresent(path -> record(state, path));
            Path manual = manuals.skillDirectory(provider.id());
            // The empty file installation takes its cross-process lock on stays beside the manual.
            record(state, manual.resolveSibling(ProviderManualService.MANUAL_NAME + ".lock"));
            if (Files.isDirectory(manual)) {
                try (Stream<Path> files = Files.walk(manual)) {
                    files.filter(Files::isRegularFile).forEach(file -> record(state, file));
                } catch (IOException unreadable) {
                    // A manual that cannot be listed is reported by the provider install that wrote it.
                }
            }
        }
        return state;
    }

    private static void record(Map<Path, String> state, Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            state.put(path.toAbsolutePath().normalize(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        } catch (IOException | NoSuchAlgorithmException unreadable) {
            state.put(path.toAbsolutePath().normalize(), Objects.toString(unreadable.getClass().getSimpleName()));
        }
    }
}
