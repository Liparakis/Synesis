package org.synesis.workspace.application.project.execution.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * macOS sandbox built on {@code sandbox-exec} (ADR-0104, "macOS: sandbox-exec").
 *
 * <p><b>UNVERIFIED.</b> No Mac was available when this was written; the profile follows the documented Scheme
 * (SBPL) syntax and has never run on macOS. Unless {@link #UNVERIFIED_PROPERTY} is set, {@link #probe()} reports
 * unavailable, so {@code run_command} refuses on macOS (fail closed). What a Mac must check before the switch
 * is removed is the stage 4 checklist in the ADR.</p>
 *
 * <p>Seatbelt applies the last matching rule, so the profile is ordered: write denied everywhere, the hidden roots
 * denied for read and write, the read-only roots readable, the writable roots read-write, then the paths that stay
 * read-only inside a writable root denied for write again. The network is allowed on purpose (the owner's decision).</p>
 *
 * @since 1.0
 */
public final class MacSandboxExecSandbox implements Sandbox {

    /**
     * Test-only JVM property that lets the probe run the real {@code sandbox-exec} on a Mac, to verify this class.
     * It is a JVM option that a confined command cannot set.
     */
    public static final String UNVERIFIED_PROPERTY = "synesis.sandbox.macosUnverified";

    private static final Path SANDBOX_EXEC = Path.of("/usr/bin/sandbox-exec");
    private static final String FIX = "this sandbox is written but not yet verified on a real Mac: run the ADR-0104 stage 4 checklist on a Mac "
            + "with -D" + UNVERIFIED_PROPERTY + "=true (adversarial test, git status/diff/add, a toolchain build, loopback), "
            + "then remove the switch; if sandbox-exec is missing from this macOS, run 'synesis doctor'";
    private static final long PROBE_SECONDS = 20;

    private SandboxAvailability availability;

    /**
     * Creates the sandbox. Nothing runs until the first {@link #probe()}.
     */
    public MacSandboxExecSandbox() {
    }

    @Override
    public String name() {
        return "sandbox-exec";
    }

    @Override
    public synchronized SandboxAvailability probe() {
        if (availability == null) {
            availability = Boolean.getBoolean(UNVERIFIED_PROPERTY) ? tryOnce()
                    : SandboxAvailability.unavailable("macOS sandbox not yet verified on a real Mac (ADR-0104 stage 4)", FIX);
        }
        return availability;
    }

    @Override
    public WrappedCommand wrap(SandboxPolicy policy, List<String> argv, Map<String, String> environment) throws SandboxSetupException {
        SandboxAvailability probe = probe();
        if (!probe.available()) {
            throw new SandboxSetupException(probe.reason());
        }
        return build(policy, argv, environment);
    }

    private static WrappedCommand build(SandboxPolicy policy, List<String> argv, Map<String, String> environment) throws SandboxSetupException {
        List<String> command = new ArrayList<>(List.of(SANDBOX_EXEC.toString(), "-p", profile(policy)));
        command.addAll(argv);
        Map<String, String> confined = new LinkedHashMap<>(environment);
        confined.putAll(policy.environment());
        return new WrappedCommand(command, confined);
    }

    /**
     * Generates the Scheme profile for one run. Package-private so the text can be tested on any computer.
     *
     * @param policy what the command may read and write
     * @return the profile source
     * @throws SandboxSetupException if a path cannot be written into a profile string
     */
    static String profile(SandboxPolicy policy) throws SandboxSetupException {
        StringBuilder profile = new StringBuilder("(version 1)\n(allow default)\n(deny file-write* (subpath \"/\"))\n");
        profile.append("(allow file-write* (literal \"/dev/null\") (literal \"/dev/zero\") (literal \"/dev/tty\") (literal \"/dev/dtracehelper\") (subpath \"/dev/fd\"))\n");
        List<Path> hidden = new ArrayList<>(List.of(Path.of(System.getProperty("user.home"))));
        hidden.addAll(policy.hidden());
        rule(profile, "(deny file-read* file-write*", hidden);
        rule(profile, "(allow file-read*", policy.readOnly());
        rule(profile, "(allow file-read* file-write*", policy.writable());
        rule(profile, "(deny file-write*", policy.readOnlyWithin());
        // shortcut: an empty read-only file keeps its content (a Seatbelt rule cannot make a file read as empty); the caller creates it empty.
        for (Path file : policy.emptyReadOnlyFiles()) {
            profile.append("(deny file-write* (literal ").append(quote(file)).append("))\n");
        }
        return profile.toString();
    }

    private static void rule(StringBuilder profile, String head, List<Path> paths) throws SandboxSetupException {
        if (paths.isEmpty()) {
            return;
        }
        profile.append(head);
        for (Path path : paths.stream().sorted(Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString)).toList()) {
            profile.append(" (subpath ").append(quote(path)).append(')');
        }
        profile.append(")\n");
    }

    /**
     * Quotes a path as a Scheme string. A quote or backslash is escaped, so it cannot end the string and add a rule;
     * a control character is refused, since the escapes for it are not known to be uniform across macOS versions.
     */
    private static String quote(Path path) throws SandboxSetupException {
        String text = path.toString();
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c < 0x20 || c == 0x7f) {
                throw new SandboxSetupException("a sandbox path contains a control character: " + text.replace('\n', ' '));
            }
            if (c == '"' || c == '\\') {
                quoted.append('\\');
            }
            quoted.append(c);
        }
        return quoted.append('"').toString();
    }

    private static SandboxAvailability tryOnce() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            return SandboxAvailability.unavailable("this is not macOS", FIX);
        }
        if (!Files.isExecutable(SANDBOX_EXEC)) {
            return SandboxAvailability.unavailable("sandbox-exec is missing from this macOS", FIX);
        }
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("synesis-sandbox-probe-").toRealPath();
            return prove(scratch);
        } catch (IOException | SandboxSetupException failure) {
            return SandboxAvailability.unavailable("the sandbox-exec probe could not run: " + failure.getMessage(), FIX);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return SandboxAvailability.unavailable("the sandbox-exec probe was interrupted", FIX);
        } finally {
            deleteQuietly(scratch);
        }
    }

    /**
     * Same facts as the Linux probe: it writes its own folder, cannot write the read-only control Git directory,
     * cannot read what is hidden, and nothing reached the host.
     */
    private static SandboxAvailability prove(Path scratch) throws IOException, InterruptedException, SandboxSetupException {
        Path control = Files.createDirectories(scratch.resolve("control"));
        Path controlGit = Files.createDirectories(control.resolve(".git"));
        Files.writeString(control.resolve("secret"), "secret");
        Path lane = Files.createDirectories(scratch.resolve("lane"));
        SandboxPolicy policy = new SandboxPolicy(List.of(lane), List.of(controlGit), List.of(), List.of(), List.of(control), Map.of());
        String script = "echo own > \"$1/own\" || exit 10; "
                + "if (echo x > \"$2/.git/leak\") 2>/dev/null; then exit 11; fi; "
                + "if cat \"$2/secret\" 2>/dev/null; then exit 12; fi; "
                + "if (echo x > \"$2/leak\") 2>/dev/null; then exit 13; fi";
        WrappedCommand wrapped = build(policy, List.of("/bin/sh", "-c", script, "probe", lane.toString(), control.toString()), Map.of("PATH", "/usr/bin:/bin"));
        ProcessBuilder builder = new ProcessBuilder(wrapped.argv()).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(wrapped.environment());
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return SandboxAvailability.unavailable("the sandbox-exec probe timed out", FIX);
        }
        String detail = new String(output, StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0) {
            return SandboxAvailability.unavailable("the sandbox-exec probe failed with exit " + process.exitValue() + (detail.isEmpty() ? "" : ": " + detail), FIX);
        }
        if (!Files.isRegularFile(lane.resolve("own")) || Files.exists(controlGit.resolve("leak")) || Files.exists(control.resolve("leak"))
                || !"secret".equals(Files.readString(control.resolve("secret")))) {
            return SandboxAvailability.unavailable("the sandbox-exec probe showed a command reaching the host's read-only or hidden state", FIX);
        }
        return SandboxAvailability.ready();
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
                    // A scratch folder under the system temporary directory; leftover scratch is harmless.
                }
            });
        } catch (IOException ignored) {
            // Same: leftover scratch is harmless.
        }
    }
}
