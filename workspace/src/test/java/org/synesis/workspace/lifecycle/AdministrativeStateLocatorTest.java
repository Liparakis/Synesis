package org.synesis.workspace.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.synesis.workspace.test.TestGit.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies canonical Git-common-directory administrative identity resolution.
 */
class AdministrativeStateLocatorTest {

  private static void init(Path root) throws Exception {
    Files.createDirectories(root);
    run(root, "init");
    run(root, "config", "user.name", "Test User");
    run(root, "config", "user.email", "test@example.com");
    Files.writeString(root.resolve("README.md"), "baseline\n");
    run(root, "add", "README.md");
    run(root, "commit", "-m", "baseline");
  }

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
    assertTrue(first.administrativeRoot()
        .endsWith(Path.of(first.repositoryIdentity(), "admin")));
  }

  @Test
  void unrelatedRepositoriesDoNotShareAdministrativeIdentity(@TempDir Path temp) throws Exception {
    Path first = temp.resolve("first");
    Path second = temp.resolve("second");
    init(first);
    init(second);

    AdministrativeStateLocator locator = new AdministrativeStateLocator();
    assertNotEquals(locator.resolve(first)
            .repositoryIdentity(),
        locator.resolve(second)
            .repositoryIdentity());
  }

  @Test
  void aDirectoryWithAnEmptyGitDirectoryIsNoRepositoryAndIsRefused(@TempDir Path temp) throws Exception {
    // Git would otherwise find a repository above the temporary directory.
    for (Path above = temp.toAbsolutePath().normalize(); above != null; above = above.getParent()) {
      assumeTrue(!Files.exists(above.resolve(".git")), "a repository lies above the temporary directory: " + above);
    }
    Path hollow = Files.createDirectories(temp.resolve("hollow"));
    Files.createDirectories(hollow.resolve(".git"));

    AdministrativeStateLocator locator = new AdministrativeStateLocator();
    IOException refused = assertThrows(IOException.class, () -> locator.resolveGitCommonDirectory(hollow));
    assertEquals("GIT_COMMON_DIRECTORY_UNAVAILABLE", refused.getMessage());
    assertThrows(IOException.class, () -> locator.resolve(hollow));
  }

  @Test
  void identityIsIndependentOfPathSeparator(@TempDir Path temp) {
    Path path = temp.resolve(".git");
    String forward = AdministrativeStateLocator.identity(path);
    String normalized = AdministrativeStateLocator.identity(Path.of(path.toString()
        .replace('\\', '/')));
    assertEquals(forward, normalized);
  }
}
