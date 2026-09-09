package org.synesis.workspace.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.ProjectApplicationService;

/** Verifies durable known-project discovery without project-state aggregation. */
final class KnownProjectRegistryTest {

  @Test
  void observationIsIdempotentAndSurvivesRegistryRestart() throws Exception {
    Path root = Files.createTempDirectory("synesis-known-project-");
    try {
      ProjectApplicationService service = new ProjectApplicationService();
      ProjectApplicationService.ProjectLocation location = service.init(root, false).location();
      KnownProjectRegistry first = new KnownProjectRegistry(root.resolve("state/registry.json"));

      first.observe(location);
      KnownProjectRegistry.ProjectView initial = first.projects().getFirst();
      first.observe(location);
      List<KnownProjectRegistry.ProjectView> repeated = first.projects();
      KnownProjectRegistry.ProjectView afterRepeat = repeated.getFirst();

      assertEquals(1, repeated.size());
      assertEquals(initial.projectId(), afterRepeat.projectId());
      assertEquals(initial.firstObservedAt(), afterRepeat.firstObservedAt());
      assertFalse(afterRepeat.lastObservedAt().isBefore(initial.lastObservedAt()));

      KnownProjectRegistry restarted = new KnownProjectRegistry(root.resolve("state/registry.json"));
      assertEquals(repeated, restarted.projects());
      assertTrue(Files.exists(restarted.registryFile()));
    } finally {
      delete(root);
    }
  }

  @Test
  void projectsRemainDistinctAndOnlyDiscoveryMetadataIsPersisted() throws Exception {
    Path root = Files.createTempDirectory("synesis-known-projects-");
    try {
      ProjectApplicationService service = new ProjectApplicationService();
      Path one = Files.createDirectories(root.resolve("one"));
      Path two = Files.createDirectories(root.resolve("two"));
      ProjectApplicationService.ProjectLocation first = service.init(one, false)
          .location();
      ProjectApplicationService.ProjectLocation second = service.init(two, false)
          .location();
      KnownProjectRegistry registry = new KnownProjectRegistry(root.resolve("state/registry.json"));

      registry.observe(first);
      registry.observe(second);
      List<KnownProjectRegistry.ProjectView> views = registry.projects(Set.of(first.projectId()));
      String json = Files.readString(registry.registryFile(), StandardCharsets.UTF_8);

      assertEquals(2, views.size());
      assertNotEquals(views.get(0).projectId(), views.get(1).projectId());
      assertEquals(KnownProjectRegistry.Status.LIVE,
          views.stream().filter(view -> view.projectId().equals(first.projectId())).findFirst()
              .orElseThrow().status());
      assertEquals(KnownProjectRegistry.Status.INACTIVE,
          views.stream().filter(view -> view.projectId().equals(second.projectId())).findFirst()
              .orElseThrow().status());
      assertTrue(json.contains("projectId"));
      assertTrue(json.contains("lastObservedAt"));
      assertFalse(json.contains("agents"));
      assertFalse(json.contains("workgroups"));
      assertFalse(json.contains("claims"));
      assertFalse(json.contains("capabilities"));
      assertFalse(json.contains("peers"));
      assertFalse(json.contains("diagnostics"));
    } finally {
      delete(root);
    }
  }

  @Test
  void unavailableAndMismatchedPathsAreNotReportedLive() throws Exception {
      Path root = Files.createTempDirectory("synesis-known-project-status-");
    try {
      ProjectApplicationService service = new ProjectApplicationService();
      Path gone = Files.createDirectories(root.resolve("gone"));
      Path mismatchRoot = Files.createDirectories(root.resolve("mismatch"));
      ProjectApplicationService.ProjectLocation unavailable = service.init(gone, false)
          .location();
      Path registryPath = root.resolve("state/registry.json");
      KnownProjectRegistry registry = new KnownProjectRegistry(registryPath);
      registry.observe(unavailable);
      delete(unavailable.root());

      KnownProjectRegistry.ProjectView missing = registry.projects().getFirst();
      assertEquals(KnownProjectRegistry.Status.UNAVAILABLE, missing.status());

      ProjectApplicationService.ProjectLocation mismatch = service.init(mismatchRoot, false)
          .location();
      registry.observe(mismatch);
      Path metadata = mismatch.metadataFile();
      String original = Files.readString(metadata, StandardCharsets.UTF_8);
      Files.writeString(metadata, original.replace(mismatch.projectId().toString(),
          UUID.randomUUID().toString()), StandardCharsets.UTF_8);

      KnownProjectRegistry.ProjectView changed = registry.projects().stream()
          .filter(view -> view.projectId().equals(mismatch.projectId())).findFirst().orElseThrow();
      assertEquals(KnownProjectRegistry.Status.IDENTITY_MISMATCH, changed.status());
      assertNotEquals(KnownProjectRegistry.Status.LIVE, changed.status());
    } finally {
      delete(root);
    }
  }

  private static void delete(Path root) throws Exception {
    if (root == null || Files.notExists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (java.io.IOException failure) {
          throw new RuntimeException(failure);
        }
      });
    }
  }
}
