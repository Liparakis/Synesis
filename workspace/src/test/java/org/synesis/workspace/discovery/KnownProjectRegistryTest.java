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

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;

/**
 * Verifies durable known-project discovery without project-state aggregation.
 */
final class KnownProjectRegistryTest {

    /**
     * One runtime reads the registry for every snapshot while the browser removes entries from it.
     * The file lock is held per process, so two threads of one process must queue for it.
     */
    @Test
    void threadsOfOneProcessShareTheRegistryWithoutRefusingEachOther() throws Exception {
        Path root = Files.createTempDirectory("synesis-known-project-threads-");
        try {
            ProjectApplicationService service = new ProjectApplicationService();
            Path registryPath = root.resolve("state/registry.json");
            List<java.util.UUID> ids = new java.util.ArrayList<>();
            for (int index = 0; index < 4; index++) {
                ProjectLocation location = service.init(Files.createDirectories(root.resolve("project-" + index)), false).location();
                new KnownProjectRegistry(registryPath).observe(location);
                ids.add(location.projectId());
            }
            java.util.concurrent.ConcurrentLinkedQueue<Throwable> failures = new java.util.concurrent.ConcurrentLinkedQueue<>();
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<Thread> threads = new java.util.ArrayList<>();
            for (int reader = 0; reader < 3; reader++) {
                threads.add(new Thread(() -> {
                    try {
                        start.await();
                        // Each thread uses its own registry object, as the snapshot and a command do.
                        KnownProjectRegistry registry = new KnownProjectRegistry(registryPath);
                        for (int round = 0; round < 25; round++) {
                            registry.projects();
                        }
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                }));
            }
            threads.add(new Thread(() -> {
                try {
                    start.await();
                    KnownProjectRegistry registry = new KnownProjectRegistry(registryPath);
                    for (java.util.UUID id : ids) {
                        assertTrue(registry.remove(id));
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }));
            threads.forEach(Thread::start);
            start.countDown();
            for (Thread thread : threads) {
                thread.join(30_000);
            }
            assertEquals(List.of(), List.copyOf(failures));
            assertEquals(List.of(), new KnownProjectRegistry(registryPath).projects());
        } finally {
            delete(root);
        }
    }

    /** The workspace that serves the page of an installation without projects is not one of them. */
    @Test
    void theInstallationHomeIsInitializedOnceAndNeverBecomesAKnownProject() throws Exception {
        Path root = Files.createTempDirectory("synesis-known-project-home-");
        try {
            Path home = InstallationHome.ensure();
            assertEquals(InstallationHome.root(), home);
            ProjectLocation location = new ProjectApplicationService().require(home);
            assertEquals(location.projectId(), new ProjectApplicationService().require(InstallationHome.ensure()).projectId(), "the home workspace was initialized twice");
            assertTrue(InstallationHome.is(home.resolve("..").resolve(home.getFileName())));
            assertFalse(InstallationHome.is(root));

            KnownProjectRegistry registry = new KnownProjectRegistry(root.resolve("state/registry.json"));
            registry.observe(location);
            assertEquals(List.of(), registry.projects());
        } finally {
            delete(root);
        }
    }

    @Test
    void severalProjectsAreRemovedInOneWriteAndAbsentOnesAreIgnored() throws Exception {
        Path root = Files.createTempDirectory("synesis-known-project-batch-");
        try {
            ProjectApplicationService service = new ProjectApplicationService();
            KnownProjectRegistry registry = new KnownProjectRegistry(root.resolve("state/registry.json"));
            List<ProjectLocation> projects = new java.util.ArrayList<>();
            for (int index = 0; index < 3; index++) {
                ProjectLocation location = service.init(Files.createDirectories(root.resolve("project-" + index)), false).location();
                registry.observe(location);
                projects.add(location);
            }
            java.util.UUID unknown = java.util.UUID.randomUUID();

            java.util.Set<java.util.UUID> removed = registry.removeAll(java.util.Set.of(projects.get(0).projectId(), projects.get(2).projectId(), unknown));

            assertEquals(java.util.Set.of(projects.get(0).projectId(), projects.get(2).projectId()), removed);
            // A second registry object reads the file: the removal is durable, and the rest is intact.
            List<KnownProjectRegistry.ProjectView> remaining = new KnownProjectRegistry(root.resolve("state/registry.json")).projects();
            assertEquals(List.of(projects.get(1).projectId()), remaining.stream().map(KnownProjectRegistry.ProjectView::projectId).toList());
            assertEquals(java.util.Set.of(), registry.removeAll(java.util.Set.of(unknown)));
            assertTrue(Files.isDirectory(projects.get(0).root()), "removing a registry entry touched the project directory");
        } finally {
            delete(root);
        }
    }

    @Test
    void observationIsIdempotentAndSurvivesRegistryRestart() throws Exception {
        Path root = Files.createTempDirectory("synesis-known-project-");
        try {
            ProjectApplicationService service = new ProjectApplicationService();
            ProjectLocation location = service.init(root, false).location();
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
    void missingDiscoveryCacheIsRebuiltFromCanonicalProjectMetadata() throws Exception {
        Path root = Files.createTempDirectory("synesis-known-project-cache-recovery-");
        try {
            ProjectLocation location = new ProjectApplicationService().init(root, false).location();
            Path registryPath = root.resolve("state/registry.json");
            KnownProjectRegistry registry = new KnownProjectRegistry(registryPath);
            registry.observe(location);
            String metadataBefore = Files.readString(location.metadataFile(), StandardCharsets.UTF_8);

            Files.delete(registryPath);
            KnownProjectRegistry restarted = new KnownProjectRegistry(registryPath);
            assertTrue(restarted.projects().isEmpty());

            restarted.observe(location);

            assertEquals(location.projectId(), restarted.projects().getFirst().projectId());
            assertEquals(metadataBefore, Files.readString(location.metadataFile(), StandardCharsets.UTF_8));
            assertTrue(Files.exists(registryPath));
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
            ProjectLocation first = service.init(one, false).location();
            ProjectLocation second = service.init(two, false).location();
            KnownProjectRegistry registry = new KnownProjectRegistry(root.resolve("state/registry.json"));

            registry.observe(first);
            registry.observe(second);
            List<KnownProjectRegistry.ProjectView> views = registry.projects(Set.of(first.projectId()));
            String json = Files.readString(registry.registryFile(), StandardCharsets.UTF_8);

            assertEquals(2, views.size());
            assertNotEquals(views.get(0).projectId(), views.get(1).projectId());
            assertEquals(KnownProjectRegistry.Status.LIVE, views.stream().filter(view -> view.projectId().equals(first.projectId())).findFirst().orElseThrow().status());
            assertEquals(KnownProjectRegistry.Status.INACTIVE, views.stream().filter(view -> view.projectId().equals(second.projectId())).findFirst().orElseThrow().status());
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
    void oneLogicalProjectMayHaveMultipleValidatedLocalPaths() throws Exception {
        Path root = Files.createTempDirectory("synesis-known-replicas-");
        try {
            ProjectApplicationService service = new ProjectApplicationService();
            Path source = Files.createDirectories(root.resolve("source"));
            Path replica = Files.createDirectories(root.resolve("replica"));
            ProjectLocation original = service.init(source, false).location();
            ProjectLocation localReplica = service.initReplica(replica, original.identity(), false).location();
            KnownProjectRegistry registry = new KnownProjectRegistry(root.resolve("state/registry.json"));

            registry.observe(original);
            registry.observe(localReplica);

            List<KnownProjectRegistry.ProjectView> views = registry.projects();
            assertEquals(2, views.size());
            assertEquals(Set.of(source.toAbsolutePath().normalize(), replica.toAbsolutePath().normalize()), views.stream().map(KnownProjectRegistry.ProjectView::path).collect(java.util.stream.Collectors.toSet()));
            assertEquals(original.projectId(), views.get(0).projectId());
            assertEquals(original.projectId(), views.get(1).projectId());
            List<KnownProjectRegistry.ProjectView> pathScoped = registry.projectsForReplicas(Set.of(source));
            assertEquals(KnownProjectRegistry.Status.LIVE, pathScoped.stream().filter(view -> view.path().equals(source.toAbsolutePath().normalize())).findFirst().orElseThrow().status());
            assertEquals(KnownProjectRegistry.Status.INACTIVE, pathScoped.stream().filter(view -> view.path().equals(replica.toAbsolutePath().normalize())).findFirst().orElseThrow().status());
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
            ProjectLocation unavailable = service.init(gone, false).location();
            Path registryPath = root.resolve("state/registry.json");
            KnownProjectRegistry registry = new KnownProjectRegistry(registryPath);
            registry.observe(unavailable);
            delete(unavailable.root());

            KnownProjectRegistry.ProjectView missing = registry.projects().getFirst();
            assertEquals(KnownProjectRegistry.Status.UNAVAILABLE, missing.status());

            ProjectLocation mismatch = service.init(mismatchRoot, false).location();
            registry.observe(mismatch);
            Path metadata = mismatch.metadataFile();
            String original = Files.readString(metadata, StandardCharsets.UTF_8);
            Files.writeString(metadata, original.replace(mismatch.projectId().toString(), UUID.randomUUID().toString()), StandardCharsets.UTF_8);

            KnownProjectRegistry.ProjectView changed = registry.projects().stream().filter(view -> view.projectId().equals(mismatch.projectId())).findFirst().orElseThrow();
            assertEquals(KnownProjectRegistry.Status.IDENTITY_MISMATCH, changed.status());
            assertNotEquals(KnownProjectRegistry.Status.LIVE, changed.status());
        } finally {
            delete(root);
        }
    }

    @Test
    void registerAndRemoveChangeOnlyRegistryMetadata() throws Exception {
        Path root = Files.createTempDirectory("synesis-known-project-mutation-");
        try {
            ProjectApplicationService service = new ProjectApplicationService();
            Path project = Files.createDirectories(root.resolve("project"));
            ProjectLocation location = service.init(project, false).location();
            KnownProjectRegistry registry = new KnownProjectRegistry(root.resolve("state/registry.json"));

            KnownProjectRegistry.ProjectView registered = registry.register(project);
            assertEquals(location.projectId(), registered.projectId());
            assertEquals(1, registry.projects().size());

            assertTrue(registry.remove(location.projectId()));
            assertFalse(registry.remove(location.projectId()));
            assertTrue(Files.exists(location.metadataFile()));
            assertTrue(registry.projects().isEmpty());
        } finally {
            delete(root);
        }
    }

    @Test
    void fullRegistryReclaimsVanishedDirectoriesBeforeRefusingALiveProject() throws Exception {
        Path root = Files.createTempDirectory("synesis-known-project-full-");
        try {
            ProjectLocation location = new ProjectApplicationService().init(Files.createDirectories(root.resolve("live")), false).location();
            Path survivor = Files.createDirectories(root.resolve("still-present"));
            Path registryPath = root.resolve("state/registry.json");
            writeFullRegistry(registryPath, index -> index == 0 ? survivor : root.resolve("vanished-" + index));
            KnownProjectRegistry registry = new KnownProjectRegistry(registryPath);

            registry.observe(location);

            List<Path> remembered = registry.projects().stream().map(KnownProjectRegistry.ProjectView::path).toList();
            assertEquals(Set.of(survivor, location.root()), Set.copyOf(remembered));
        } finally {
            delete(root);
        }
    }

    @Test
    void fullRegistryOfPresentDirectoriesStillRefusesAnotherProject() throws Exception {
        Path root = Files.createTempDirectory("synesis-known-project-bound-");
        try {
            ProjectLocation location = new ProjectApplicationService().init(Files.createDirectories(root.resolve("live")), false).location();
            Path present = Files.createDirectories(root.resolve("present"));
            Path registryPath = root.resolve("state/registry.json");
            writeFullRegistry(registryPath, index -> {
                try {
                    return Files.createDirectories(present.resolve(Integer.toString(index)));
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });
            KnownProjectRegistry registry = new KnownProjectRegistry(registryPath);
            String before = Files.readString(registryPath, StandardCharsets.UTF_8);

            KnownProjectRegistry.RegistryException refused = org.junit.jupiter.api.Assertions.assertThrows(KnownProjectRegistry.RegistryException.class, () -> registry.observe(location));

            assertEquals("REGISTRY_FULL", refused.code());
            assertEquals(before, Files.readString(registryPath, StandardCharsets.UTF_8));
        } finally {
            delete(root);
        }
    }

    private static void writeFullRegistry(Path registryPath, java.util.function.IntFunction<Path> pathForIndex) throws Exception {
        StringBuilder json = new StringBuilder("{\"schemaVersion\":1,\"projects\":[");
        for (int index = 0; index < KnownProjectRegistry.MAX_PROJECTS; index++) {
            String path = pathForIndex.apply(index).toAbsolutePath().normalize().toString().replace("\\", "\\\\");
            json.append(index == 0 ? "" : ",").append("{\"projectId\":\"").append(new UUID(7L, index)).append("\",\"path\":\"").append(path)
                    .append("\",\"createdAt\":\"2026-01-01T00:00:00Z\",\"firstObservedAt\":\"2026-01-01T00:00:00Z\",\"lastObservedAt\":\"2026-01-01T00:00:00Z\"}");
        }
        Files.createDirectories(registryPath.getParent());
        Files.writeString(registryPath, json.append("]}").toString(), StandardCharsets.UTF_8);
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
