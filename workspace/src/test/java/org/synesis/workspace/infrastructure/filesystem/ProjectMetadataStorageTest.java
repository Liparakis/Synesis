package org.synesis.workspace.infrastructure.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import org.junit.jupiter.api.Test;

/**
 * Verifies the canonical project metadata boundary and local visibility policy.
 */
final class ProjectMetadataStorageTest {

  @Test
  void createsCanonicalRootAndUsesPlatformVisibilityPolicy() throws Exception {
    Path project = Files.createTempDirectory("synesis-storage-create-");
    ProjectMetadataStorage storage = new ProjectMetadataStorage();

    ProjectMetadataStorage.VisibilityResult result = storage.ensure(project);

    assertTrue(Files.isDirectory(project.resolve(".synesis")));
    assertEquals(project.resolve(".synesis").toAbsolutePath().normalize(), storage.root(project));
    DosFileAttributeView dos = Files.getFileAttributeView(storage.root(project),
        DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (dos == null) {
      assertFalse(result.applicable());
      assertFalse(result.hidden());
    } else {
      assertTrue(result.applicable());
      assertTrue(result.hidden());
      assertEquals(Boolean.TRUE, Files.getAttribute(storage.root(project), "dos:hidden",
          LinkOption.NOFOLLOW_LINKS));
    }
  }

  @Test
  void reensuresHiddenAfterPresentationAttributeIsLostWithoutChangingMetadata() throws Exception {
    Path project = Files.createTempDirectory("synesis-storage-reensure-");
    ProjectMetadataStorage storage = new ProjectMetadataStorage();
    storage.ensure(project);
    Path metadata = storage.projectMetadata(project);
    Files.writeString(metadata, "{\"projectId\":\"stable\"}\n");
    byte[] before = Files.readAllBytes(metadata);
    DosFileAttributeView dos = Files.getFileAttributeView(storage.root(project),
        DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (dos == null) {
      return;
    }
    dos.setHidden(false);

    ProjectMetadataStorage.VisibilityResult result = storage.ensure(project);

    assertTrue(result.hidden());
    assertEquals(Boolean.TRUE, Files.getAttribute(storage.root(project), "dos:hidden",
        LinkOption.NOFOLLOW_LINKS));
    assertEquals(java.util.Arrays.toString(before),
        java.util.Arrays.toString(Files.readAllBytes(metadata)));
  }

  @Test
  void visibilityFailureIsDiagnosticOnlyAndLeavesMetadataValid() throws Exception {
    Path project = Files.createTempDirectory("synesis-storage-failure-");
    ProjectMetadataStorage storage = new ProjectMetadataStorage(_ -> {
      throw new IOException("access denied");
    });
    Path metadata = storage.projectMetadata(project);
    Files.createDirectories(metadata.getParent());
    Files.writeString(metadata, "{\"projectId\":\"stable\"}\n");
    byte[] before = Files.readAllBytes(metadata);

    ProjectMetadataStorage.VisibilityResult result = storage.ensure(project);

    assertFalse(result.hidden());
    assertTrue(result.applicable());
    assertEquals("HIDDEN_APPLY_FAILED", result.diagnostic());
    assertEquals(java.util.Arrays.toString(before),
        java.util.Arrays.toString(Files.readAllBytes(metadata)));
    assertTrue(Files.isRegularFile(metadata));
  }

  @Test
  void unixLikePolicyDoesNotRequireExtraVisibilityMutationWhenDosViewIsAbsent() throws Exception {
    Path project = Files.createTempDirectory("synesis-storage-unix-");
    ProjectMetadataStorage storage = new ProjectMetadataStorage(_ ->
        new ProjectMetadataStorage.VisibilityResult(false, false, "DOS_HIDDEN_NOT_APPLICABLE"));

    ProjectMetadataStorage.VisibilityResult result = storage.ensure(project);

    assertFalse(result.hidden());
    assertFalse(result.applicable());
    assertEquals("DOS_HIDDEN_NOT_APPLICABLE", result.diagnostic());
    assertTrue(Files.isDirectory(project.resolve(".synesis")));
  }

  @Test
  void refusesMetadataRootSymlinkWithoutFollowingExternalTarget() throws Exception {
    Path project = Files.createTempDirectory("synesis-storage-link-");
    Path external = Files.createTempDirectory("synesis-storage-external-");
    try {
      Files.createSymbolicLink(project.resolve(".synesis"), external);
    } catch (UnsupportedOperationException | IOException unsupported) {
      return;
    }

    assertThrows(IOException.class, () -> new ProjectMetadataStorage().ensure(project));
    assertFalse(Files.exists(external.resolve("project.json")));
  }
}
