package org.synesis.workspace.application.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.synesis.workspace.test.PortableTestCommand;

/** Tests deterministic direct-process outcomes and bounded stream evidence. */
class ProjectProcessExecutorTest {

    private static ProjectProcessExecutor.ExecutionResult run(Path root, List<String> argv, int timeout) {
        return new ProjectProcessExecutor().execute(new ProjectProcessExecutor.ExecutionRequest(
                argv, root, ".", timeout, root));
    }

    @Test
    void noOutputSuccessIsComplete() throws Exception {
        Path root = Files.createTempDirectory("synesis-exec-empty-");
        var result = run(root, PortableTestCommand.fixture("exit"), 10);
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, result.outcome());
        assertEquals(0, result.stdoutBytesRead());
        assertEquals(0, result.stdoutBytesRetained());
        assertFalse(result.stdoutTruncated());
        assertFalse(result.stderrTruncated());
    }

    @Test
    void missingExecutableDoesNotPretendToStart() throws Exception {
        Path root = Files.createTempDirectory("synesis-exec-missing-");
        var result = run(root, List.of("synesis-missing-executable-037"), 10);
        assertEquals(ProjectProcessExecutor.Outcome.COMMAND_EXECUTABLE_NOT_FOUND, result.outcome());
        assertEquals(0, result.stdoutBytesRead());
        assertEquals(0, result.stderrBytesRead());
        assertEquals(0, result.stdoutBytesRetained());
        assertEquals(0, result.stderrBytesRetained());
        assertFalse(result.stdoutTruncated());
        assertFalse(result.stderrTruncated());
    }

    @Test
    void stdoutOverflowRetainsHeadAndTailAndCountsAllReadBytes() throws Exception {
        Path root = Files.createTempDirectory("synesis-exec-stdout-");
        var result = run(root, PortableTestCommand.fixture("stdout", "A", "100000"), 20);
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, result.outcome());
        assertTrue(result.stdoutBytesRead() >= 100000, result.toString());
        assertEquals(ProjectProcessExecutor.MAX_RETAINED_BYTES, result.stdoutBytesRetained());
        assertTrue(result.stdoutTruncated());
        assertTrue(result.stdout().contains(ProjectProcessExecutor.TRUNCATION_MARKER));
        assertFalse(result.stderrTruncated());
    }

    @Test
    void stderrOnlyOverflowRetainsHeadAndTailAndCountsAllReadBytes() throws Exception {
        Path root = Files.createTempDirectory("synesis-exec-stderr-");
        var result = run(root, PortableTestCommand.fixture("stderr", "E", "100000"), 20);
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, result.outcome());
        assertTrue(result.stderrBytesRead() >= 100000, result.toString());
        assertEquals(ProjectProcessExecutor.MAX_RETAINED_BYTES, result.stderrBytesRetained());
        assertTrue(result.stderrTruncated());
        assertTrue(result.stderr().contains(ProjectProcessExecutor.TRUNCATION_MARKER));
        assertFalse(result.stdoutTruncated());
    }

    @Test
    void exactLimitOutputIsCompleteWhenEofIsObserved() throws Exception {
        Path root = Files.createTempDirectory("synesis-exec-limit-");
        var result = run(root, PortableTestCommand.fixture("stdout", "L", "65536"), 20);
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, result.outcome());
        assertEquals(65536, result.stdoutBytesRead());
        assertEquals(65536, result.stdoutBytesRetained());
        assertFalse(result.stdoutTruncated(), result.toString());
        assertFalse(result.stdout().contains(ProjectProcessExecutor.TRUNCATION_MARKER));
    }

    @Test
    void exactLimitMultibyteOutputRemainsCompleteAcrossInternalBufferBoundary() throws Exception {
        Path root = Files.createTempDirectory("synesis-exec-limit-utf8-");
        var result = run(root, PortableTestCommand.fixture("utf8", "16384"), 20);
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, result.outcome());
        assertEquals(65536, result.stdoutBytesRead());
        assertEquals(65536, result.stdoutBytesRetained());
        assertFalse(result.stdoutTruncated(), result.toString());
        assertFalse(result.stdout().contains(ProjectProcessExecutor.TRUNCATION_MARKER));
        assertFalse(result.stdout().contains("\uFFFD"), result.stdout());
    }

    @Test
    void simultaneousStreamsAreDrainedWithoutDeadlock() throws Exception {
        Path root = Files.createTempDirectory("synesis-exec-both-");
        var result = run(root, PortableTestCommand.fixture("both"), 20);
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, result.outcome());
        assertTrue(result.stdoutBytesRead() >= 100000, result.toString());
        assertTrue(result.stderrBytesRead() >= 100000, result.toString());
        assertTrue(result.stdoutTruncated());
        assertTrue(result.stderrTruncated());
    }

    @Test
    void multibyteUtf8BoundaryDoesNotLeakReplacementAtRetainedEdges() throws Exception {
        Path root = Files.createTempDirectory("synesis-exec-utf8-");
        var result = run(root, PortableTestCommand.fixture("utf8-prefix", "x", "20000"), 20);
        assertEquals(ProjectProcessExecutor.Outcome.COMPLETED, result.outcome());
        assertTrue(result.stdoutTruncated());
        assertTrue(result.stdoutBytesRead() > result.stdoutBytesRetained());
        assertTrue(result.stdout().contains(ProjectProcessExecutor.TRUNCATION_MARKER));
        String aroundMarker = result.stdout().replace(ProjectProcessExecutor.TRUNCATION_MARKER, "");
        assertFalse(aroundMarker.contains("\uFFFD"), result.stdout());
    }

    @Test
    void nonZeroExitPreservesEvidence() throws Exception {
        Path root = Files.createTempDirectory("synesis-exec-nonzero-");
        var result = run(root, PortableTestCommand.fixture("fail", "failed", "7"), 10);
        assertEquals(ProjectProcessExecutor.Outcome.NON_ZERO_EXIT, result.outcome());
        assertEquals(7, result.exitCode());
        assertTrue(result.stderr().contains("failed"));
        assertTrue(result.stderrBytesRead() >= 5);
        assertFalse(result.stderrTruncated());
    }

    @Test
    void timeoutPreservesPartialOutputAndTerminatesTree() throws Exception {
        Path root = Files.createTempDirectory("synesis-exec-timeout-");
        var result = run(root, PortableTestCommand.fixture("partial", "partial", "1", "5"), 1);
        assertEquals(ProjectProcessExecutor.Outcome.COMMAND_TIMED_OUT, result.outcome());
        assertTrue(result.stdout().contains("partial"));
        assertTrue(result.stdoutBytesRead() >= 7);
    }

    @Test
    void timeoutPreservesPartialTruncatedOutput() throws Exception {
        Path root = Files.createTempDirectory("synesis-exec-timeout-overflow-");
        var result = run(root, PortableTestCommand.fixture("partial", "T", "100000", "5"), 1);
        assertEquals(ProjectProcessExecutor.Outcome.COMMAND_TIMED_OUT, result.outcome());
        assertTrue(result.stdoutBytesRead() >= 100000, result.toString());
        assertTrue(result.stdoutTruncated(), result.toString());
        assertTrue(result.stdout().contains(ProjectProcessExecutor.TRUNCATION_MARKER));
    }

    @Test
    void cancellationPreservesPartialOutput() throws Exception {
        Path root = Files.createTempDirectory("synesis-exec-cancel-");
        AtomicReference<ProjectProcessExecutor.ExecutionResult> reference = new AtomicReference<>();
        Thread caller = new Thread(() -> reference.set(run(root,
                PortableTestCommand.fixture("partial", "partial", "1", "5"), 30)));
        caller.start();
        Thread.sleep(250);
        caller.interrupt();
        caller.join(10_000);
        assertTrue(reference.get() != null, "cancelled execution did not return");
        assertEquals(ProjectProcessExecutor.Outcome.COMMAND_CANCELLED, reference.get().outcome());
        assertTrue(reference.get().stdout().contains("partial"));
    }

    @Test
    void rejectsAbsoluteAndEscapingWorkingDirectories() throws Exception {
        Path root = Files.createTempDirectory("synesis-exec-path-");
        var absolute = new ProjectProcessExecutor().execute(new ProjectProcessExecutor.ExecutionRequest(
                PortableTestCommand.fixture("exit"), root,
                root.toString(), 10, root));
        var escape = new ProjectProcessExecutor().execute(new ProjectProcessExecutor.ExecutionRequest(
                PortableTestCommand.fixture("exit"), root,
                "..", 10, root));
        assertEquals(ProjectProcessExecutor.Outcome.COMMAND_WORKING_DIRECTORY_INVALID, absolute.outcome());
        assertEquals(ProjectProcessExecutor.Outcome.COMMAND_WORKING_DIRECTORY_INVALID, escape.outcome());
    }

    @Test
    void rejectsMissingAndSymlinkEscapingWorkingDirectories() throws Exception {
        Path root = Files.createTempDirectory("synesis-exec-symlink-");
        var missing = new ProjectProcessExecutor().execute(new ProjectProcessExecutor.ExecutionRequest(
                List.of("powershell.exe", "-NoProfile", "-Command", "exit 0"), root,
                "missing", 10, root));
        assertEquals(ProjectProcessExecutor.Outcome.COMMAND_WORKING_DIRECTORY_INVALID, missing.outcome());
        Path outside = Files.createTempDirectory("synesis-exec-outside-");
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException failure) {
            return;
        }
        var escaped = new ProjectProcessExecutor().execute(new ProjectProcessExecutor.ExecutionRequest(
                List.of("powershell.exe", "-NoProfile", "-Command", "exit 0"), root,
                "link", 10, root));
        assertEquals(ProjectProcessExecutor.Outcome.COMMAND_WORKING_DIRECTORY_INVALID, escaped.outcome());
    }
}
