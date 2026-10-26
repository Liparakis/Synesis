package org.synesis.workspace.application.project.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.synesis.workspace.application.project.execution.sandbox.SandboxPolicy;
import org.synesis.workspace.lifecycle.process.GitWorktreeBinding;

/**
 * Derives what a command in one worktree may touch (ADR-0104, "One choke point and one policy"). The inputs are the
 * worktree and control root the caller took from the authoritative lane binding. Nothing is read from the
 * command or from files the worktree can rewrite, except where noted.
 *
 * <p>The lane's own Git administrative directory ({@code <control>/.git/worktrees/<name>}) is found from the
 * control side: the entry whose {@code gitdir} file names this worktree's {@code .git}. The worktree's own
 * {@code .git} file is not believed, because the command can rewrite it.</p>
 */
final class LaneSandboxPolicy {

    /**
     * Provider configuration that the lane's next provider session reads. A command that could rewrite it could
     * loosen the guards or add an MCP server the provider would start unsandboxed.
     */
    private static final List<String> PROVIDER_CONFIGURATION = List.of(".claude", ".codex", ".mcp.json");
    /**
     * Files of the lane's Git administrative directory that decide which repository and which configuration Git
     * uses there. They stay read-only so that Synesis's own Git on the lane never reads configuration a command wrote.
     */
    private static final List<String> POINTER_FILES = List.of("gitdir", "commondir");
    private static final String WORKTREE_CONFIG = "config.worktree";

    private LaneSandboxPolicy() {
    }

    static SandboxPolicy derive(Path worktreeRoot, Path controlRoot, Path privateTemp) {
        Path worktree = real(worktreeRoot);
        Path temp = real(privateTemp);
        List<Path> writable = new ArrayList<>(List.of(worktree, temp));
        List<Path> readOnly = new ArrayList<>();
        List<Path> readOnlyWithin = new ArrayList<>();
        List<Path> emptyReadOnlyFiles = new ArrayList<>();
        List<Path> hidden = new ArrayList<>();
        Map<String, String> environment = new LinkedHashMap<>();

        for (String name : PROVIDER_CONFIGURATION) {
            readOnlyWithin.add(worktree.resolve(name));
        }
        if (Files.isRegularFile(worktree.resolve(".git"))) {
            readOnlyWithin.add(worktree.resolve(".git"));
        }
        Path home = temp.resolve("home");
        environment.put("HOME", home.toString());
        environment.put("USERPROFILE", home.toString());
        environment.put("TMPDIR", temp.toString());
        environment.put("TEMP", temp.toString());
        environment.put("TMP", temp.toString());
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        environment.put("GIT_TERMINAL_PROMPT", "0");

        if (controlRoot != null) {
            Path control = real(controlRoot);
            hidden.add(control);
            Path controlGit = control.resolve(".git");
            if (Files.isDirectory(controlGit)) {
                readOnly.add(controlGit);
                // New objects go to a private directory, and the control objects are a read-only alternate: a
                // writable shared object store would let a command overwrite a loose object of main.
                environment.put("GIT_OBJECT_DIRECTORY", temp.resolve("objects").toString());
                environment.put("GIT_ALTERNATE_OBJECT_DIRECTORIES", controlGit.resolve("objects").toString());
                Path administrative = GitWorktreeBinding.administrativeDirectory(controlGit, worktree).orElse(null);
                if (administrative != null) {
                    writable.add(administrative);
                    for (String name : POINTER_FILES) {
                        readOnlyWithin.add(administrative.resolve(name));
                    }
                    emptyReadOnlyFiles.add(administrative.resolve(WORKTREE_CONFIG));
                }
            }
        }
        Path siblings = worktree.getParent();
        if (siblings != null) {
            hidden.add(siblings);
        }
        applicationState().forEach(hidden::add);
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank() && Files.isDirectory(Path.of(javaHome))) {
            readOnly.add(real(Path.of(javaHome)));
        }
        return new SandboxPolicy(writable, readOnly, readOnlyWithin, emptyReadOnlyFiles, hidden, environment);
    }

    /**
     * Both places Synesis keeps application state: {@code ~/.synesis/Synesis} or {@code %LOCALAPPDATA%\Synesis}, and
     * the per-user state folder of the host. Lane worktrees, integration worktrees, keys and the daemon endpoint live
     * there.
     */
    private static List<Path> applicationState() {
        String home = System.getProperty("user.home");
        String local = System.getenv("LOCALAPPDATA");
        Path base = local == null || local.isBlank() ? Path.of(home, ".synesis") : Path.of(local);
        List<Path> roots = new ArrayList<>(List.of(base.resolve("Synesis"), Path.of(home, ".synesis")));
        String stateHome = System.getenv("XDG_STATE_HOME");
        roots.add((stateHome == null || stateHome.isBlank() ? Path.of(home, ".local", "state") : Path.of(stateHome)).resolve("Synesis"));
        return roots.stream().filter(Files::isDirectory).map(LaneSandboxPolicy::real).toList();
    }

    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException unavailable) {
            return path.toAbsolutePath().normalize();
        }
    }
}
