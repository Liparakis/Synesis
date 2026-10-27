package org.synesis.workspace.application.project.execution.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Linux sandbox built on bubblewrap (ADR-0104, "Linux: bubblewrap").
 *
 * <p>The root file system is mounted read-only. The areas the policy hides are overlaid with an empty in-memory
 * file system, so the control checkout's working files, {@code .synesis} and the keys do not exist for the command;
 * what the command writes there lives in memory and ends with the run. Then, in this order, the read-only paths
 * are bound back, the writable paths are bound back, and the paths that must stay read-only inside a writable one
 * are bound read-only over it. The order is what makes the control Git directory read-only while the lane's own
 * administrative directory inside it is writable. The network is shared on purpose (the owner's decision).
 * The command runs in its own session, process, IPC and UTS namespaces, and dies with its parent.</p>
 *
 * @since 1.0
 */
public final class LinuxBubblewrapSandbox implements Sandbox {

    private static final String FIX = "install bubblewrap (sudo apt install bubblewrap, or your distribution's package), "
            + "and if user namespaces are restricted, run 'synesis doctor' for the one setting to change";
    private static final long PROBE_SECONDS = 20;

    private Path executable;
    private SandboxAvailability availability;

    /**
     * Creates the sandbox. Nothing runs until the first {@link #probe()}.
     */
    public LinuxBubblewrapSandbox() {
    }

    @Override
    public String name() {
        return "bubblewrap";
    }

    @Override
    public synchronized SandboxAvailability probe() {
        if (availability == null) {
            availability = tryOnce();
        }
        return availability;
    }

    @Override
    public synchronized WrappedCommand wrap(SandboxPolicy policy, List<String> argv, Map<String, String> environment) throws SandboxSetupException {
        if (!probe().available()) {
            throw new SandboxSetupException(availability.reason());
        }
        return build(policy, argv, environment);
    }

    private WrappedCommand build(SandboxPolicy policy, List<String> argv, Map<String, String> environment) {
        List<String> command = new ArrayList<>(List.of(executable.toString(),
                "--unshare-user", "--unshare-pid", "--unshare-ipc", "--unshare-uts", "--die-with-parent", "--new-session", "--cap-drop", "ALL",
                "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp"));
        List<Path> hidden = new ArrayList<>(List.of(Path.of(System.getProperty("user.home")), Path.of("/run/user")));
        policy.hidden().stream().sorted(shallowFirst()).forEach(hidden::add);
        hidden.forEach(path -> hide(command, path));
        policy.readOnly().stream().sorted(shallowFirst()).forEach(path -> bind(command, "--ro-bind", path));
        policy.writable().stream().sorted(shallowFirst()).forEach(path -> bind(command, "--bind", path));
        policy.readOnlyWithin().stream().sorted(shallowFirst()).forEach(path -> bind(command, "--ro-bind", path));
        for (Path file : policy.emptyReadOnlyFiles()) {
            command.addAll(List.of("--ro-bind", "/dev/null", file.toString()));
        }
        // Last, because a mount point cannot be created inside a read-only file system: what a command writes into
        // a hidden area then fails loudly instead of landing in memory and vanishing with the run.
        hidden.stream().filter(path -> path.getNameCount() > 0 && Files.isDirectory(path)).forEach(path -> command.addAll(List.of("--remount-ro", path.toString())));
        command.add("--");
        command.addAll(argv);
        Map<String, String> confined = new LinkedHashMap<>(environment);
        confined.putAll(policy.environment());
        return new WrappedCommand(command, confined);
    }

    private static Comparator<Path> shallowFirst() {
        return Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString);
    }

    private static void hide(List<String> command, Path path) {
        if (path.getNameCount() == 0) {
            return;
        }
        if (Files.isDirectory(path)) {
            command.addAll(List.of("--tmpfs", path.toString()));
        } else if (Files.exists(path)) {
            command.addAll(List.of("--ro-bind", "/dev/null", path.toString()));
        }
    }

    private static void bind(List<String> command, String option, Path path) {
        if (Files.exists(path)) {
            command.addAll(List.of(option, path.toString(), path.toString()));
        }
    }

    private SandboxAvailability tryOnce() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
            return SandboxAvailability.unavailable("this is not Linux", FIX);
        }
        executable = onPath("bwrap");
        if (executable == null) {
            return SandboxAvailability.unavailable("bubblewrap (bwrap) is not on PATH", FIX);
        }
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("synesis-sandbox-probe-");
            return prove(scratch);
        } catch (IOException failure) {
            return SandboxAvailability.unavailable("the bubblewrap probe could not run: " + failure.getMessage(), FIX);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return SandboxAvailability.unavailable("the bubblewrap probe was interrupted", FIX);
        } finally {
            deleteQuietly(scratch);
        }
    }

    /**
     * Runs one trivial confined command that checks one positive fact (it writes its own temp) and two negative ones
     * (it cannot write the read-only control Git directory, it cannot read what is hidden), and then checks from
     * outside that nothing reached the host. A probe that cannot prove the negatives is unavailable.
     */
    private SandboxAvailability prove(Path scratch) throws IOException, InterruptedException {
        Path control = Files.createDirectories(scratch.resolve("control"));
        Path controlGit = Files.createDirectories(control.resolve(".git"));
        Files.writeString(control.resolve("secret"), "secret");
        Path lane = Files.createDirectories(scratch.resolve("lane"));
        SandboxPolicy policy = new SandboxPolicy(List.of(lane), List.of(controlGit), List.of(), List.of(), List.of(control), Map.of());
        String script = "echo own > \"$1/own\" || exit 10; "
                + "if (echo x > \"$2/.git/leak\") 2>/dev/null; then exit 11; fi; "
                + "if cat \"$2/secret\" 2>/dev/null; then exit 12; fi; "
                + "if (echo x > \"$2/leak\") 2>/dev/null && [ -e \"$2/secret\" ]; then exit 13; fi";
        WrappedCommand wrapped = build(policy, List.of("/bin/sh", "-c", script, "probe", lane.toString(), control.toString()), Map.of("PATH", "/usr/bin:/bin"));
        ProcessBuilder builder = new ProcessBuilder(wrapped.argv()).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(wrapped.environment());
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return SandboxAvailability.unavailable("the bubblewrap probe timed out", FIX);
        }
        String detail = new String(output, StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0) {
            return failedProbe(process.exitValue(), detail, userNamespacesRestricted());
        }
        if (!Files.isRegularFile(lane.resolve("own")) || Files.exists(controlGit.resolve("leak")) || Files.exists(control.resolve("leak"))
                || !"secret".equals(Files.readString(control.resolve("secret")))) {
            return SandboxAvailability.unavailable("the bubblewrap probe showed a command reaching the host's read-only or hidden state", FIX);
        }
        return SandboxAvailability.ready();
    }

    /**
     * Chooses the reason and fix for a probe that ran and failed. Ubuntu 24.04 and later refuse a user namespace to a
     * program that has no AppArmor profile allowing it; the narrow remedy is a profile for bwrap alone. Turning the
     * sysctl off is never suggested, because it lifts the restriction for every program on the computer.
     */
    static SandboxAvailability failedProbe(int exit, String detail, boolean userNamespacesRestricted) {
        String reason = "the bubblewrap probe failed with exit " + exit + (detail.isEmpty() ? "" : ": " + detail);
        if (userNamespacesRestricted && (detail.contains("uid map") || detail.contains("user namespace"))) {
            return SandboxAvailability.unavailable(reason + " (this kernel sets kernel.apparmor_restrict_unprivileged_userns=1, which refuses bwrap a user namespace)",
                    "create /etc/apparmor.d/bwrap containing 'abi <abi/4.0>, include <tunables/global> profile bwrap /usr/bin/bwrap flags=(unconfined) { userns, }' "
                            + "(allows user namespaces for /usr/bin/bwrap only), then run 'sudo systemctl reload apparmor'; do not turn the sysctl off");
        }
        return SandboxAvailability.unavailable(reason, FIX);
    }

    private static boolean userNamespacesRestricted() {
        try {
            return "1".equals(Files.readString(Path.of("/proc/sys/kernel/apparmor_restrict_unprivileged_userns")).strip());
        } catch (IOException absent) {
            return false;
        }
    }

    private static Path onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String directory : path.split(":")) {
            if (!directory.isEmpty() && Files.isExecutable(Path.of(directory, program))) {
                return Path.of(directory, program);
            }
        }
        return null;
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
                    // A scratch folder under the system temporary directory; the next cleanup of it ends the leftover.
                }
            });
        } catch (IOException ignored) {
            // Same: leftover scratch is harmless.
        }
    }
}
