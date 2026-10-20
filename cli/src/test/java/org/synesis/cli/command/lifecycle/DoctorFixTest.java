package org.synesis.cli.command.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;

import org.synesis.coordination.persistence.ReplicationSettings;

import org.synesis.link.onboarding.Onboarding;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.doctor.NetworkProbe;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecyclePathVerifier;
import org.synesis.workspace.lifecycle.repair.RepairService;

import picocli.CommandLine;

/**
 * {@code synesis doctor} reports the environment, network and work groups and, with {@code --fix}, makes exactly the safe
 * repairs of {@link DoctorFixes} and nothing else (ADR-0103, decision 4).
 */
class DoctorFixTest {

    @TempDir
    Path tmp;
    private Path root;
    private String previousHome;
    private final List<Path> started = new ArrayList<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private boolean runtimeRuns;
    private Optional<NetworkProbe.Firewall> firewall = Optional.empty();

    @BeforeEach
    void project() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(tmp.resolve("home")).toString());
        root = Files.createDirectories(tmp.resolve("project"));
        new ProjectApplicationService().init(root);
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousHome);
        }
    }

    private int doctor(String... options) {
        ConsoleTerminal terminal = new ConsoleTerminal(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        CliRuntime cli = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), terminal);
        NetworkProbe probe = new NetworkProbe() {
            @Override
            public Optional<Boolean> runtimeRunning(Path projectRoot) {
                return Optional.of(runtimeRuns);
            }

            @Override
            public Optional<Firewall> firewall() {
                return firewall;
            }
        };
        List<String> arguments = new ArrayList<>(List.of("--project", root.toString()));
        arguments.addAll(List.of(options));
        return new CommandLine(new DoctorCommand(cli, new DoctorService(probe), new RepairService(), project -> started.add(project))).execute(arguments.toArray(String[]::new));
    }

    private void share() throws IOException {
        ReplicationSettings.leader(Set.of("member-node")).write(Files.createDirectories(root.resolve(".synesis/coordination")));
    }

    private Path staleLock() throws IOException {
        Path admin = Files.createDirectories(LifecyclePathVerifier.resolveWorkspaceRoot(root).resolve("admin"));
        return Files.writeString(admin.resolve("cleanup-execution.lock"), "{ \"pid\": 9999999 }");
    }

    private void connectBrokenCodex() throws IOException {
        Path metadata = root.resolve(".synesis/local/providers/codex.json");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "{}");
    }

    private List<String> stdout() {
        return out.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private List<String> stderr() {
        return err.toString(StandardCharsets.UTF_8).lines().toList();
    }

    @Test
    void withoutFixDoctorOnlyReadsEvenWhenRepairsAreDue() throws Exception {
        share();
        Path lock = staleLock();

        assertEquals(ExitCodes.OK, doctor());

        assertTrue(Files.exists(lock), "the stale lock is still there");
        assertEquals(List.of(), started, "no runtime was started");
        assertTrue(stdout().contains("MUTATIONS_PERFORMED=0"), stdout().toString());
        assertTrue(stdout().contains("NETWORK_FINDING_1=project_runtime_not_running"), stdout().toString());
        assertTrue(stdout().stream().noneMatch(line -> line.startsWith("FIX_APPLIED=") || line.startsWith("FIXES_")), stdout().toString());
    }

    @Test
    void fixClearsAStaleSynesisLockAndStartsTheRuntimeOfASharedProjectAndSaysSo() throws Exception {
        share();
        Path lock = staleLock();

        assertEquals(ExitCodes.OK, doctor("--fix"));

        assertFalse(Files.exists(lock), "a lock whose process is gone was removed");
        assertEquals(List.of(root.toRealPath()), started.stream().map(path -> {
            try {
                return path.toRealPath();
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }).toList(), "the runtime was asked for once");
        List<String> lines = stdout();
        assertTrue(lines.contains("FIXES_APPLIED=2"), lines.toString());
        assertTrue(lines.contains("FIX_APPLIED=administrative_state COMPLETED=1"), lines.toString());
        assertTrue(lines.contains("FIX_APPLIED=project_runtime_not_running"), lines.toString());
        assertTrue(lines.contains("FIXES_SKIPPED=0"), lines.toString());
        assertTrue(lines.contains("MUTATIONS_PERFORMED=2"), "the report after the repairs says how many changed something: " + lines);
    }

    @Test
    void fixWithNothingToRepairChangesNothing() {
        assertEquals(ExitCodes.OK, doctor("--fix"));

        List<String> lines = stdout();
        assertTrue(lines.contains("FIXES_APPLIED=0"), lines.toString());
        assertTrue(lines.contains("FIXES_SKIPPED=0"), lines.toString());
        assertTrue(lines.contains("MUTATIONS_PERFORMED=0"), lines.toString());
        assertEquals(List.of(), started);
    }

    @Test
    void fixNeverTouchesAUsersFileTheFirewallOrAnOperatorRequestAndPrintsTheirFixInstead() throws Exception {
        share();
        runtimeRuns = true;
        firewall = Optional.of(NetworkProbe.Firewall.MISSING);
        Path notes = Files.writeString(root.resolve("notes.txt"), "mine\n");

        assertEquals(ExitCodes.OK, doctor("--fix"));

        assertEquals("mine\n", Files.readString(notes));
        assertEquals(List.of(), started, "the runtime already runs");
        List<String> lines = stdout();
        assertTrue(lines.contains("FIXES_APPLIED=0"), lines.toString());
        assertTrue(lines.contains("NETWORK_FINDING_1=link_firewall_missing"), "still reported after --fix: " + lines);
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("NETWORK_FINDING_1_FIX=run the Synesis installer again")), lines.toString());
    }

    @Test
    void fixThatCannotReinstallAnAgentSaysWhyAndRemainsASkip() throws Exception {
        Assumptions.assumeFalse(Files.isRegularFile(new ProviderApplicationService().launcherPath()), "a Synesis launcher is installed or on the PATH");
        connectBrokenCodex();

        assertEquals(ExitCodes.OK, doctor("--fix"));

        List<String> lines = stdout();
        assertTrue(lines.contains("FIXES_APPLIED=0"), lines.toString());
        assertTrue(lines.contains("FIX_SKIPPED=provider_files_stale PROVIDER=codex REASON=LAUNCHER_MISSING"), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("ENVIRONMENT_FINDING_") && line.endsWith("_FIX=run 'synesis doctor --fix', which writes codex's managed files again like 'synesis provider install codex'")),
                "the finding that --fix could not clear is still reported with its fix: " + lines);
    }

    @Test
    void fixWithJsonKeepsStandardOutputOneDocumentAndTellsTheRepairsOnStandardError() throws Exception {
        share();
        staleLock();

        assertEquals(ExitCodes.OK, doctor("--fix", "--json"));

        assertEquals(1, stdout().size(), stdout().toString());
        assertTrue(stdout().getFirst().startsWith("{") && stdout().getFirst().contains("\"mutationsPerformed\":2"), stdout().toString());
        assertTrue(stderr().contains("FIX_APPLIED=project_runtime_not_running"), stderr().toString());
    }
}
