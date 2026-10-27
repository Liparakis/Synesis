package org.synesis.workspace.application.project.execution.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.synesis.workspace.application.project.execution.sandbox.WindowsIntegrityLabels.Level;

/**
 * Windows sandbox built on the low mandatory integrity level (ADR-0104, "Windows: a low-integrity process"). It
 * needs no administrator rights and shows no prompt.
 *
 * <p>The command runs with a Low copy of the user's token. Windows lets a Low process write only objects that carry
 * a Low label, and read nothing that carries a no-read-up label above Low. The sandbox therefore maps the
 * {@link SandboxPolicy} onto labels, all set by this (Medium) process on the user's own files:</p>
 * <ul>
 *   <li>{@code writable}: labelled Low when a run starts on it and unlabelled when the next run starts anywhere
 *   else (or the process ends), so a worktree is not writable by another lane's command; the label is kept between
 *   two runs on the same root because relabelling a worktree takes time in proportion to its files;</li>
 *   <li>{@code hidden}: labelled Medium with no-read-up, which also makes it unwritable; the label is inheritable
 *   and stays, so files created later are hidden too;</li>
 *   <li>{@code readOnly} below a hidden folder (the control repository's Git directory): Medium without no-read-up,
 *   so it is readable but not writable;</li>
 *   <li>{@code readOnlyWithin} and {@code emptyReadOnlyFiles}: labelled Medium inside the Low area, and held open
 *   without delete sharing for the run, because a Low process may delete or rename a file inside a Low folder
 *   whatever the file's own label says.</li>
 * </ul>
 *
 * <p>Limits, stated rather than hidden: all Low objects share one level, so two lanes that run <em>at the same
 * moment</em> can write each other (the label is Low only while a lane runs); the network and loopback stay open;
 * a path the policy names as read-only inside the Low area but that does not exist (for example {@code .claude}
 * before the provider created it) can be created by the command, as on Linux; a Synesis crash during a run leaves
 * that lane's label Low until its next run; a user-profile folder that is not hidden stays readable.</p>
 *
 * @since 1.0
 */
public final class WindowsLowIntegritySandbox implements Sandbox {

    private static final String FIX = "Synesis starts agent commands as a low-integrity process, which needs no administrator rights; run 'synesis doctor' to see "
            + "which step failed";
    private static final long PROBE_SECONDS = 30;
    // The Windows tools are named by full path and the level by its SID: the daemon inherits the PATH and the language of
    // whoever started it, and a Git Bash PATH puts an MSYS whoami (no /groups) ahead of System32 (sbx-run2, exit 13).
    private static final String PROBE_SCRIPT = String.join("\r\n",
            "@echo off",
            "\"%SystemRoot%\\System32\\whoami.exe\" /groups /fo csv | \"%SystemRoot%\\System32\\findstr.exe\" /c:\"S-1-16-4096\" >nul || exit /b 13",
            "echo own> \"%PROBE_LANE%\\own\" || exit /b 10",
            "echo x> \"%PROBE_CONTROL%\\leak\" 2>nul && exit /b 11",
            "type \"%PROBE_HIDDEN%\\secret\" >nul 2>nul && exit /b 12",
            "exit /b 0", "");

    private final Set<Path> labelledOnce = new HashSet<>();
    private final Set<Path> readableFolders = new HashSet<>();
    private final Map<Path, Integer> lowRoots = new HashMap<>();
    /**
     * Roots that are still labelled Low although no run uses them. Relabelling a worktree costs time in proportion
     * to its files, and an agent runs many commands in a row in one lane, so the label is kept until a run in
     * another place starts (or the process ends) and only then restored.
     */
    private final Set<Path> lingering = new LinkedHashSet<>();
    private boolean shutdownHookAdded;
    private final Map<String, String> probeEnvironment;
    private SandboxAvailability availability;

    /**
     * Creates the sandbox. Nothing runs until the first {@link #probe()}.
     */
    public WindowsLowIntegritySandbox() {
        this(System.getenv());
    }

    /**
     * Creates a sandbox whose probe child starts from the given environment, so a test can hand it the PATH of a Git
     * Bash session, where an MSYS {@code whoami} comes before the Windows one.
     */
    WindowsLowIntegritySandbox(Map<String, String> probeEnvironment) {
        this.probeEnvironment = Map.copyOf(probeEnvironment);
    }

    @Override
    public String name() {
        return "windows-low-integrity";
    }

    @Override
    public synchronized SandboxAvailability probe() {
        if (availability == null) {
            availability = tryOnce();
        }
        return availability;
    }

    @Override
    public WrappedCommand wrap(SandboxPolicy policy, List<String> argv, Map<String, String> environment) throws SandboxSetupException {
        SandboxAvailability probed = probe();
        if (!probed.available()) {
            throw new SandboxSetupException(probed.reason());
        }
        return prepare(policy, argv, environment);
    }

    private WrappedCommand prepare(SandboxPolicy policy, List<String> argv, Map<String, String> environment) throws SandboxSetupException {
        Run run = new Run();
        try {
            run.label(policy);
        } catch (IOException | RuntimeException failure) {
            run.finish();
            throw new SandboxSetupException("the integrity labels for this run could not be set: " + failure.getMessage());
        }
        Map<String, String> confined = new LinkedHashMap<>(environment);
        policy.environment().forEach((name, value) -> {
            confined.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
            confined.put(name, value);
        });
        return new WrappedCommand(argv, confined, run);
    }

    /**
     * What one run holds: the Low roots it counted, the files it keeps open, and the process.
     */
    private final class Run implements WrappedCommand.Hosting {

        private final List<Path> acquired = new ArrayList<>();
        private final List<WindowsFileHold> holds = new ArrayList<>();
        private Process process;

        private void label(SandboxPolicy policy) throws IOException {
            restoreLingeringExcept(Set.copyOf(policy.writable()));
            List<Path> hidden = existing(policy.hidden());
            List<Path> readOnly = existing(policy.readOnly()).stream().filter(path -> hidden.stream().anyMatch(path::startsWith)).toList();
            Set<Path> reachable = new HashSet<>(existing(policy.readOnly()));
            reachable.addAll(policy.writable());
            reachable.addAll(policy.readOnlyWithin());
            reachable.addAll(policy.emptyReadOnlyFiles());
            Set<Path> chain = readableAncestors(hidden, reachable);
            for (Path path : hidden) {
                if (!chain.contains(path)) {
                    labelOnce(path, Level.MEDIUM, true, reachable);
                }
            }
            for (Path folder : chain.stream().sorted(Comparator.comparingInt(Path::getNameCount)).toList()) {
                readableFolder(folder);
                try (var children = Files.newDirectoryStream(folder)) {
                    for (Path child : children) {
                        if (!chain.contains(child) && !reachable.contains(child)) {
                            labelOnce(child, Level.MEDIUM, true, reachable);
                        }
                    }
                }
            }
            for (Path path : readOnly) {
                labelOnce(path, Level.MEDIUM, false, Set.of());
            }
            for (Path path : existing(policy.writable())) {
                acquireLow(path);
            }
            for (Path path : existing(policy.readOnlyWithin())) {
                pin(path);
            }
            for (Path file : policy.emptyReadOnlyFiles()) {
                if (!Files.exists(file)) {
                    Files.createFile(file);
                }
                pin(file);
            }
        }

        /**
         * The folders between a hidden root and the paths the command must reach. A path cannot be reached through a
         * folder the command may not read: Git fails to look at a parent that is no-read-up, and so does PowerShell
         * resolving its working folder. These folders stay readable (their entries can be listed), and what is hidden
         * is every other entry inside them.
         */
        private static Set<Path> readableAncestors(List<Path> hidden, Set<Path> reachable) {
            Set<Path> chain = new HashSet<>();
            for (Path path : reachable) {
                for (Path ancestor = path.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                    Path candidate = ancestor;
                    if (Files.isDirectory(candidate) && hidden.stream().anyMatch(candidate::startsWith) && reachable.stream().noneMatch(candidate::startsWith)) {
                        chain.add(candidate);
                    }
                }
            }
            return chain;
        }

        private void readableFolder(Path folder) throws IOException {
            synchronized (WindowsLowIntegritySandbox.this) {
                if (readableFolders.add(folder)) {
                    try {
                        WindowsIntegrityLabels.set(folder, Level.MEDIUM, false, false);
                    } catch (IOException | RuntimeException failure) {
                        readableFolders.remove(folder);
                        throw failure;
                    }
                }
            }
        }

        private void pin(Path path) throws IOException {
            WindowsIntegrityLabels.setVerified(path, Level.MEDIUM, false, Set.of());
            synchronized (WindowsLowIntegritySandbox.this) {
                holds.add(WindowsFileHold.open(path));
            }
        }

        /**
         * Labels a tree once per process. The label is inheritable and stays, so files created later are labelled
         * too; the tree is not walked again for every command.
         */
        private void labelOnce(Path path, Level level, boolean noRead, Set<Path> deliberate) throws IOException {
            synchronized (WindowsLowIntegritySandbox.this) {
                if (lowRoots.containsKey(path)) {
                    return;
                }
                if (labelledOnce.add(path)) {
                    try {
                        Set<Path> excluded = new HashSet<>(deliberate);
                        excluded.addAll(lowRoots.keySet());
                        WindowsIntegrityLabels.setVerified(path, level, noRead, excluded);
                    } catch (IOException | RuntimeException failure) {
                        labelledOnce.remove(path);
                        throw failure;
                    }
                }
            }
        }

        private void acquireLow(Path path) throws IOException {
            synchronized (WindowsLowIntegritySandbox.this) {
                if (!lowRoots.containsKey(path) && !lingering.remove(path) && WindowsIntegrityLabels.set(path, Level.LOW, false)) {
                    // Outside the user's profile the children may lack the right to be labelled; one call then skips them silently.
                    WindowsIntegrityLabels.setVerified(path, Level.LOW, false, Set.of());
                }
                lowRoots.merge(path, 1, Integer::sum);
                acquired.add(path);
            }
        }

        @Override
        public Process start(List<String> argv, Path workingDirectory, Map<String, String> environment) throws IOException {
            process = WindowsLowIntegrityLauncher.start(argv, workingDirectory, environment);
            return process;
        }

        @Override
        public void finish() {
            if (process instanceof WindowsLowIntegrityLauncher.LowIntegrityProcess started) {
                started.close();
            }
            synchronized (WindowsLowIntegritySandbox.this) {
                holds.forEach(WindowsFileHold::close);
                holds.clear();
                for (Path path : acquired) {
                    if (lowRoots.merge(path, -1, Integer::sum) == 0) {
                        lowRoots.remove(path);
                        lingering.add(path);
                    }
                }
                if (!lingering.isEmpty() && !shutdownHookAdded) {
                    shutdownHookAdded = true;
                    Runtime.getRuntime().addShutdownHook(new Thread(WindowsLowIntegritySandbox.this::restoreLingering, "synesis-sandbox-restore"));
                }
                acquired.clear();
            }
        }
    }

    private synchronized void restoreLingeringExcept(Set<Path> keep) {
        for (Path path : List.copyOf(lingering)) {
            if (!keep.contains(path)) {
                lingering.remove(path);
                restoreIdle(path);
            }
        }
    }

    private synchronized void restoreLingering() {
        restoreLingeringExcept(Set.of());
    }

    /**
     * Takes the Low label off an idle root, so it is again what it was: the label it inherits, which is no-read-up
     * when it sits in the application state.
     */
    private void restoreIdle(Path path) {
        try {
            WindowsIntegrityLabels.remove(path);
            if (readableFolders.contains(path.getParent())) {
                // The readable folder does not pass its label down, so the idle entry would be left unlabelled and readable.
                WindowsIntegrityLabels.set(path, Level.MEDIUM, true);
            }
        } catch (IOException ignored) {
            // The root is gone or no longer ours; the next run labels it again before it relies on the label.
        }
    }

    private static List<Path> existing(List<Path> paths) {
        return paths.stream().filter(Files::exists).sorted(Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString)).toList();
    }

    private SandboxAvailability tryOnce() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return SandboxAvailability.unavailable("this is not Windows", FIX);
        }
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("synesis-sandbox-probe-").toRealPath();
            return prove(scratch);
        } catch (IOException | SandboxSetupException failure) {
            return SandboxAvailability.unavailable("the low-integrity probe could not run: " + failure.getMessage(), FIX);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return SandboxAvailability.unavailable("the low-integrity probe was interrupted", FIX);
        } finally {
            deleteQuietly(scratch);
        }
    }

    /**
     * Starts one real low-integrity child that checks one positive fact (it writes its Low folder) and three
     * negative ones (it really runs at the Low level, it cannot write an unlabelled file, it cannot read a
     * no-read-up file), then checks from outside that nothing leaked. A probe that cannot prove the negatives is
     * unavailable.
     */
    private SandboxAvailability prove(Path scratch) throws IOException, SandboxSetupException, InterruptedException {
        Path lane = Files.createDirectories(scratch.resolve("lane"));
        Path control = Files.createDirectories(scratch.resolve("control"));
        Path hidden = Files.createDirectories(scratch.resolve("hidden"));
        Files.writeString(hidden.resolve("secret"), "secret");
        Files.writeString(lane.resolve("probe.cmd"), PROBE_SCRIPT, StandardCharsets.US_ASCII);
        Map<String, String> environment = new HashMap<>(probeEnvironment);
        environment.put("PROBE_LANE", lane.toString());
        environment.put("PROBE_CONTROL", control.toString());
        environment.put("PROBE_HIDDEN", hidden.toString());
        SandboxPolicy policy = new SandboxPolicy(List.of(lane), List.of(), List.of(), List.of(), List.of(hidden), Map.of());
        WrappedCommand wrapped = prepare(policy, List.of("cmd.exe", "/d", "/c", lane.resolve("probe.cmd").toString()), environment);
        try {
            Process child = wrapped.hosting().start(wrapped.argv(), lane, wrapped.environment());
            child.getOutputStream().close();
            if (!child.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                return SandboxAvailability.unavailable("the low-integrity probe timed out", FIX);
            }
            if (child.exitValue() != 0) {
                return SandboxAvailability.unavailable("the low-integrity probe failed with exit " + child.exitValue() + probeMeaning(child.exitValue()), FIX);
            }
        } finally {
            wrapped.hosting().finish();
        }
        if (!Files.isRegularFile(lane.resolve("own")) || Files.exists(control.resolve("leak")) || !"secret".equals(Files.readString(hidden.resolve("secret")))) {
            return SandboxAvailability.unavailable("the low-integrity probe showed a command reaching state it must not reach", FIX);
        }
        return SandboxAvailability.ready();
    }

    private static String probeMeaning(int exit) {
        return switch (exit) {
            case 10 -> " (the command could not write its own folder)";
            case 11 -> " (the command could write a file that is not labelled Low)";
            case 12 -> " (the command could read a no-read-up file)";
            case 13 -> " (the command did not run at the Low integrity level)";
            default -> "";
        };
    }

    private static void deleteQuietly(Path root) {
        if (root == null) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A scratch folder under the system temporary directory; a leftover is harmless.
                }
            });
        } catch (IOException ignored) {
            // Same: leftover scratch is harmless.
        }
    }
}
