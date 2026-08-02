package org.synesis.workspace.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies canonical Git-common-directory administrative identity resolution. */
class AdministrativeStateLocatorTest {

    @Test
    void sameRepositoryHasStableIdentity(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("repo");
        init(root);
        AdministrativeStateLocator locator = new AdministrativeStateLocator();

        AdministrativeStateLocator.Resolution first = locator.resolve(root);
        AdministrativeStateLocator.Resolution second = locator.resolve(root.resolve("."));

        assertEquals(first.commonDirectory(), second.commonDirectory());
        assertEquals(first.repositoryIdentity(), second.repositoryIdentity());
        assertEquals(first.administrativeRoot(), second.administrativeRoot());
        assertTrue(first.administrativeRoot().endsWith(Path.of(first.repositoryIdentity(), "admin")));
    }

    @Test
    void unrelatedRepositoriesDoNotShareAdministrativeIdentity(@TempDir Path temp) throws Exception {
        Path first = temp.resolve("first");
        Path second = temp.resolve("second");
        init(first);
        init(second);

        AdministrativeStateLocator locator = new AdministrativeStateLocator();
        assertNotEquals(locator.resolve(first).repositoryIdentity(), locator.resolve(second).repositoryIdentity());
    }

    @Test
    void identityIsIndependentOfPathSeparator(@TempDir Path temp) {
        Path path = temp.resolve(".git");
        String forward = AdministrativeStateLocator.identity(path);
        String normalized = AdministrativeStateLocator.identity(Path.of(path.toString().replace('\\', '/')));
        assertEquals(forward, normalized);
    }

    private static void init(Path root) throws Exception {
        Files.createDirectories(root);
        run(root, "git", "init");
        run(root, "git", "config", "user.name", "Test User");
        run(root, "git", "config", "user.email", "test@example.com");
        Files.writeString(root.resolve("README.md"), "baseline\n");
        run(root, "git", "add", "README.md");
        run(root, "git", "commit", "-m", "baseline");
    }

    private static void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command) + ": " + output);
        }
    }
}
