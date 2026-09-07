package org.synesis.workspace.lifecycle.codex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Focused real Windows Job Object containment test.
 */
class WindowsJobObjectProcessTreeSupervisorTest {

    /**
     * Proves assignment happens before the launched root is allowed to run.
     */
    @Test
    void launchesAndTeardownsRootAndDescendantAsOneOwnedJob() throws Exception {
        Assumptions.assumeTrue(WindowsJobObjectProcessTreeSupervisor.isWindows());
        try (WindowsJobObjectProcessTreeSupervisor supervisor = new WindowsJobObjectProcessTreeSupervisor()) {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
            Process process = supervisor.launch(List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                    ChildFixture.class.getName()), Path.of("."), new HashMap<>(System.getenv()));
            assertTrue(supervisor.owns(process));
            try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                assertTrue("READY".equals(output.readLine()));
                long childPid = Long.parseLong(output.readLine());
                assertTrue(ProcessHandle.of(childPid)
                        .map(ProcessHandle::isAlive)
                        .orElse(false));
                assertTrue(supervisor.teardownAndProveEmpty(process));
                assertFalse(process.isAlive());
                assertFalse(ProcessHandle.of(childPid)
                        .map(ProcessHandle::isAlive)
                        .orElse(false));
            }
        }
    }

    /**
     * Child used only to create a normal descendant below the contained root.
     */
    public static final class ChildFixture {

        private ChildFixture() {
        }

        /**
         * @param arguments ignored
         */
        public static void main(String[] arguments) throws Exception {
            Process child = new ProcessBuilder("cmd.exe", "/c", "ping", "127.0.0.1", "-n", "30")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            System.out.println("READY");
            System.out.println(child.pid());
            System.out.flush();
            child.waitFor(30, TimeUnit.SECONDS);
        }
    }
}
