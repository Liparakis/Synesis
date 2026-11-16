package org.synesis.workspace.lifecycle.process;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Synesis's own git commands start no detached maintenance: Git 2.29+ runs {@code git maintenance run --auto --detach}
 * after a commit, which kept writing in {@code .git} while a test, or Synesis, deleted the checkout.
 */
final class GitProcessRunnerMaintenanceTest {

    @Test
    void autoMaintenanceIsOffForEveryCommandSynesisRuns(@TempDir Path repository) throws Exception {
        GitProcessRunner.run(repository, "init");

        assertEquals("false", GitProcessRunner.run(repository, "config", "--get", "maintenance.auto").strip());
        assertEquals("0", GitProcessRunner.run(repository, "config", "--get", "gc.auto").strip());
    }
}
