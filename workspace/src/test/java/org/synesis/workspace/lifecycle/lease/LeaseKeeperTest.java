package org.synesis.workspace.lifecycle.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;

/**
 * The run controller keeps the session of an agent whose process has stopped and that it will start
 * again on the same connection (ADR-0099, amendment). In the overnight run every wait closed the lease
 * and the lane went to recovery within seconds.
 */
class LeaseKeeperTest {

    private final SessionLeaseService leases = new SessionLeaseService();
    private final SessionLeasePolicy policy = new SessionLeasePolicy();
    private final SessionLeaseStore store = new SessionLeaseStore();

    @Test
    void theKeeperOfAStoppedChildKeepsItsSessionActiveAndTheChildsCloseNoLongerEndsIt(@TempDir Path temp) throws Exception {
        Path root = project(temp);
        Process child = longLivedChild();
        try {
            lease(root, "conn-kept", identityOf(child.toHandle()));
            assertTrue(leases.keep(root, "conn-kept", policy));
            child.destroy();
            child.waitFor(10, TimeUnit.SECONDS);

            // The child's MCP server closing at end of input names a process the lease no longer does.
            assertFalse(leases.markClosedCleanly(root, "conn-kept", child.pid()));
            SessionLeaseRecord kept = store.load(root, "conn-kept").orElseThrow();
            assertEquals(ProcessHandle.current().pid(), kept.processIdentity().pid());
            assertLive(leases.evaluateLiveness(kept, policy));

            // A resumed MCP server on the same connection renews the kept lease.
            SessionLeaseRecord renewed = leases.createOrRenewLease(root, "proj-1", "codex", "conn-kept", "node", "sess-1", policy);
            assertEquals(ProcessHandle.current().pid(), renewed.processIdentity().pid());
            assertLive(leases.evaluateLiveness(renewed, policy));
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void aProcessCannotKeepTheSessionOfAProcessItDidNotStart(@TempDir Path temp) throws Exception {
        Path root = project(temp);
        ProcessHandle foreign = foreignProcess();
        try {
            assertTrue(ProcessHandle.current().descendants().noneMatch(process -> process.pid() == foreign.pid()), "the foreign process must not descend from this one");
            lease(root, "conn-foreign", identityOf(foreign));

            assertFalse(leases.keep(root, "conn-foreign", policy));
            assertEquals(foreign.pid(), store.load(root, "conn-foreign").orElseThrow().processIdentity().pid());
        } finally {
            foreign.destroyForcibly();
        }
    }

    @Test
    void aClosedLeaseIsNotReopenedAndAMissingOneIsNotCreated(@TempDir Path temp) throws Exception {
        Path root = project(temp);
        Process child = longLivedChild();
        try {
            lease(root, "conn-closed", identityOf(child.toHandle()));
            assertTrue(leases.markClosedCleanly(root, "conn-closed", child.pid()));

            assertFalse(leases.keep(root, "conn-closed", policy));
            assertEquals(SessionLeaseState.CLOSED_CLEANLY, store.load(root, "conn-closed").orElseThrow().leaseState());
            assertFalse(leases.keep(root, "conn-never-opened", policy));
            assertTrue(store.load(root, "conn-never-opened").isEmpty());
        } finally {
            child.destroyForcibly();
        }
    }

    /**
     * A live keeper reads as ACTIVE. On Windows the JDK reports no command line, so a live process's
     * evidence is unverified and reads AMBIGUOUS, which is never recovered either; the lease of the
     * killed child left as it was would have become recoverable.
     */
    private static void assertLive(SessionLeaseState state) {
        assertTrue(state == SessionLeaseState.ACTIVE || state == SessionLeaseState.AMBIGUOUS, state.toString());
    }

    private static Path project(Path temp) throws Exception {
        Path root = temp.resolve("control-repo");
        Files.createDirectories(root);
        new ProjectApplicationService().init(root);
        return root;
    }

    private void lease(Path root, String connection, SessionProcessIdentity identity) throws Exception {
        long now = System.currentTimeMillis();
        store.save(root, new SessionLeaseRecord(1, "proj-1", "codex", connection, "node", "sess-1", identity, "0.1.0-SNAPSHOT", now, now, SessionLeaseState.ACTIVE));
    }

    private static SessionProcessIdentity identityOf(ProcessHandle process) {
        String executable = process.info().command().orElse("java");
        long start = process.info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
        return new SessionProcessIdentity(process.pid(), executable, process.info().commandLine().orElse(executable), start, "nonce");
    }

    private static Process longLivedChild() throws Exception {
        List<String> command = windows() ? List.of("ping", "-n", "60", "127.0.0.1") : List.of("sleep", "60");
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    /**
     * A live process this one did not start: a launcher starts it and exits, so it descends from no
     * process of this JVM. The JVM's own parent is not used, since a fresh Windows 11 install hides it
     * from {@link ProcessHandle#parent()}.
     */
    private static ProcessHandle foreignProcess() throws Exception {
        List<String> launcher = windows()
                ? List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", "(Start-Process ping -ArgumentList '-n','60','127.0.0.1' -WindowStyle Hidden -PassThru).Id")
                : List.of("sh", "-c", "sleep 60 >/dev/null 2>&1 & echo $!");
        Process started = new ProcessBuilder(launcher).redirectErrorStream(true).start();
        List<String> output = new String(started.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip().lines().toList();
        assertTrue(started.waitFor(60, TimeUnit.SECONDS), "launcher did not exit");
        return ProcessHandle.of(Long.parseLong(output.getLast().strip())).orElseThrow();
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
