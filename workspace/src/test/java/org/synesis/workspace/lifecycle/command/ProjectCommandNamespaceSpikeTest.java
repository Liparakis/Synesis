package org.synesis.workspace.lifecycle.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.project.ProjectCommandAdmissionService;
import org.synesis.workspace.lifecycle.lease.SessionProcessIdentity;

/**
 * Tests the first bounded durable-command namespace implementation spike.
 */
class ProjectCommandNamespaceSpikeTest {

    private static Process startLockProbe(String javaExecutable, String mode, Path lockPath)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
                CommandLockProbe.class.getName(), mode, lockPath.toString())
                .redirectErrorStream(true);
        builder.environment()
                .remove("JAVA_TOOL_OPTIONS");
        builder.environment()
                .remove("JDK_JAVA_OPTIONS");
        builder.environment()
                .remove("_JAVA_OPTIONS");
        return builder.start();
    }

    private static Map<String, Object> castMap(Object value) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    @Test
    void publishesPermanentLocksAndAtomicallyReplacesMetadata(@TempDir Path temp) throws Exception {
        ProjectCommandNamespace namespace = ProjectCommandNamespace.open(temp.resolve("commands"));

        Path namespaceLock = namespace.namespaceLockPath();
        assertTrue(Files.isRegularFile(namespaceLock));
        assertTrue(Files.isRegularFile(namespace.worktreeLockPath("worktree-a")));
        Object namespaceFileKey = Files.readAttributes(namespaceLock, "basic:fileKey")
                .get("fileKey");

        namespace.writeIndex(Map.of("scopeCount", 1L));
        namespace.writeIndex(Map.of("scopeCount", 2L));

        assertEquals(2L,
                ((Number) namespace.readIndex()
                        .get("scopeCount")).longValue());
        assertEquals(namespaceFileKey,
                Files.readAttributes(namespaceLock, "basic:fileKey")
                        .get("fileKey"));
        namespace.close();
    }

    @Test
    void rejectsUnsupportedNewerFormat(@TempDir Path temp) throws Exception {
        ProjectCommandNamespace namespace = ProjectCommandNamespace.open(temp.resolve("commands"));
        Map<String, Object> newer = new java.util.LinkedHashMap<>();
        newer.put("schemaVersion", 99L);
        newer.put("scopeCount", 0L);
        Files.writeString(namespace.root()
                        .resolve("namespace.json"),
                org.synesis.workspace.infrastructure.json.ProviderJson.write(CommandDurableFormat.withIntegrity(newer)));

        assertThrows(CommandFormatException.class, namespace::readIndex);
        namespace.close();
    }

    @Test
    void canonicalizesTypedRequestIdsWithoutCollisions() {
        assertNotEquals(ProjectCommandCanonicalizer.requestId("1"),
                ProjectCommandCanonicalizer.requestId(1L));
        assertEquals("s:request", ProjectCommandCanonicalizer.requestId("request"));
        assertEquals("n:1", ProjectCommandCanonicalizer.requestId(1L));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectCommandCanonicalizer.requestId(true));
    }

    @Test
    void freshProcessAnchorsDoNotReuseConnectionIdentity() {
        ProjectCommandProcessAnchor first = ProjectCommandProcessAnchor.capture(
                "scope-a", new SessionProcessIdentity(1L, "java", "java", 2L, "nonce-a"), 10L);
        ProjectCommandProcessAnchor second = ProjectCommandProcessAnchor.capture(
                "scope-a", new SessionProcessIdentity(1L, "java", "java", 2L, "nonce-b"), 10L);

        assertNotEquals(first.anchorId(), second.anchorId());
        assertEquals(first.scopeLocator(), second.scopeLocator());
        assertEquals(10L, first.createdAtEpochMillis());
    }

    @Test
    void persistsAndVerifiesProcessAnchorAndScope(@TempDir Path temp) throws Exception {
        ProjectCommandNamespace namespace = ProjectCommandNamespace.open(temp.resolve("commands"));
        PhysicalWorktreeIdentity worktree = PhysicalWorktreeIdentity.capture(
                Files.createDirectories(temp.resolve("worktree")));
        namespace.publishScope(worktree);
        ProjectCommandProcessAnchor anchor = ProjectCommandProcessAnchor.capture(
                worktree.locator(), new SessionProcessIdentity(1L, "java", "java", 2L, "nonce"), 10L);

        namespace.writeAnchor(anchor);

        assertEquals(anchor, namespace.readAnchor(anchor.anchorId()));
        assertTrue(Files.isRegularFile(namespace.scopesPath()
                .resolve(worktree.locator())
                .resolve("scope.json")));
        assertTrue(Files.isDirectory(namespace.scopesPath()
                .resolve(worktree.locator())
                .resolve("records")));
        namespace.close();
    }

    @Test
    void sameRealPathHasStableVersionedLocator(@TempDir Path temp) throws Exception {
        Path worktree = Files.createDirectories(temp.resolve("worktree"));
        PhysicalWorktreeIdentity first = PhysicalWorktreeIdentity.capture(worktree);
        PhysicalWorktreeIdentity second = PhysicalWorktreeIdentity.capture(worktree.resolve("."));

        assertEquals(first.locator(), second.locator());
        assertEquals(first.realPath(), second.realPath());
        assertTrue(first.locator()
                .startsWith("v1-"));
    }

    @Test
    void storesTypedRequestRecordAndRejectsTampering(@TempDir Path temp) throws Exception {
        ProjectCommandNamespace namespace = ProjectCommandNamespace.open(temp.resolve("commands"));
        PhysicalWorktreeIdentity worktree = PhysicalWorktreeIdentity.capture(
                Files.createDirectories(temp.resolve("worktree")));
        namespace.publishScope(worktree);
        ProjectCommandProcessAnchor anchor = ProjectCommandProcessAnchor.capture(
                worktree.locator(), new SessionProcessIdentity(1L, "java", "java", 2L, "nonce-record"), 10L);
        namespace.writeAnchor(anchor);
        namespace.close();

        String requestId = ProjectCommandCanonicalizer.requestId(7L);
        String requestDigest = ProjectCommandCanonicalizer.requestDigest(
                java.util.List.of("echo", "ok"), ".", 10);
        ProjectCommandRecord record = new ProjectCommandRecord(
                anchor.anchorId(), worktree.locator(), requestId, requestDigest,
                ProjectCommandCanonicalizer.semanticDigest(requestDigest, "codex", "connection", worktree.locator()),
                ProjectCommandPhase.STARTING, null,
                false, null, false, false, null, 1L, 10L, 11L,
                Map.of("status", "starting"), Map.of());

        ProjectCommandStore store = new ProjectCommandStore(temp.resolve("commands"));
        store.save(record);
        assertEquals(record,
                store.find(worktree.locator(), anchor.anchorId(), requestId)
                        .orElseThrow());

        Path recordFile;
        try (java.util.stream.Stream<Path> paths = Files.walk(temp.resolve("commands")
                .resolve("scopes"))) {
            recordFile = paths.filter(path -> path.getFileName()
                            .toString()
                            .endsWith(".json"))
                    .filter(path -> path.toString()
                            .contains("records"))
                    .findFirst()
                    .orElseThrow();
        }
        Files.writeString(recordFile,
                Files.readString(recordFile)
                        .replace("starting", "tampered"));
        assertThrows(CommandFormatException.class,
                () -> store.find(worktree.locator(), anchor.anchorId(), requestId));
    }

    @Test
    void processLocalAdmissionSerializesProtection(@TempDir Path temp) throws Exception {
        PhysicalWorktreeIdentity worktree = PhysicalWorktreeIdentity.capture(
                Files.createDirectories(temp.resolve("worktree")));
        ProjectCommandProtectionService service = new ProjectCommandProtectionService(temp.resolve("commands"));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread owner = Thread.ofPlatform()
                .start(() -> {
                    try (ProjectCommandProtectionService.ProtectionPermit permit = service.acquire(worktree)) {
                        assertTrue(permit.isHeld());
                        held.countDown();
                        release.await();
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
        held.await();
        Thread contender = Thread.ofPlatform()
                .start(() -> {
                    try (ProjectCommandProtectionService.ProtectionPermit permit = service.acquire(worktree)) {
                        assertTrue(permit.isHeld());
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
        Thread.sleep(50L);
        release.countDown();
        owner.join();
        contender.join();
    }

    @Test
    void forkedProcessesCannotSharePermanentLock(@TempDir Path temp) throws Exception {
        Path lockPath = temp.resolve("namespace.lock");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name")
                                .toLowerCase()
                                .contains("win") ? "java.exe" : "java")
                .toString();
        Process holder = startLockProbe(javaExecutable, "hold", lockPath);
        try {
            assertEquals("ready",
                    new String(holder.getInputStream()
                            .readNBytes(6), java.nio.charset.StandardCharsets.UTF_8)
                            .trim());
            Process contender = startLockProbe(javaExecutable, "try", lockPath);
            assertTrue(contender.waitFor(5, TimeUnit.SECONDS));
            assertNotEquals(0, contender.exitValue());
        } finally {
            holder.getOutputStream()
                    .close();
            assertTrue(holder.waitFor(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsNonRegularPermanentLockObject(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("commands");
        Files.createDirectories(root);
        Files.createDirectory(root.resolve("namespace.lock"));
        assertThrows(java.io.IOException.class, () -> {
            try (ProjectCommandNamespace ignored = ProjectCommandNamespace.open(root)) {
                java.util.Objects.requireNonNull(ignored);
            }
        });
    }

    @Test
    void migratesSupportedOlderObjectWithExactBackupAndJournal(@TempDir Path temp) throws Exception {
        Path target = temp.resolve("old.json");
        Map<String, Object> older = new java.util.LinkedHashMap<>();
        older.put("schemaVersion", 1L);
        older.put("objectRevision", 1L);
        older.put("value", "preserve");
        String original = org.synesis.workspace.infrastructure.json.ProviderJson.write(
                CommandDurableFormat.withIntegrity(older));
        Files.writeString(target, original, StandardCharsets.UTF_8);
        Path backup = temp.resolve("backup")
                .resolve("old.json.bak");
        Path journal = temp.resolve("journal")
                .resolve("migration.json");
        try (CommandPermanentLock lock = CommandPermanentLock.open(temp.resolve("migration.lock"))) {
            new ProjectCommandFormatMigrationService().migrate(target, backup, journal, lock);
        }
        assertEquals(original, Files.readString(backup));
        assertTrue(Files.readString(target)
                .contains("\"schemaVersion\":2"));
        assertTrue(Files.isRegularFile(journal));
        CommandDurableFormat.verify(castMap(org.synesis.workspace.infrastructure.json.ProviderJson.parse(
                Files.readString(target))));
    }

    @Test
    void enforcesLiveAnchorCapacityAt8192And8193(@TempDir Path temp) throws Exception {
        Path namespaceRoot = temp.resolve("commands");
        ProjectCommandNamespace namespace = ProjectCommandNamespace.open(namespaceRoot);
        PhysicalWorktreeIdentity worktree = PhysicalWorktreeIdentity.capture(
                Files.createDirectories(temp.resolve("worktree")));
        Path records = namespace.publishScope(worktree)
                .resolve("records");
        namespace.close();

        Map<String, Object> minimal = new LinkedHashMap<>();
        minimal.put("anchorId", "anchor-capacity");
        String json = org.synesis.workspace.infrastructure.json.ProviderJson.write(
                CommandDurableFormat.withIntegrity(minimal));
        for (int index = 0; index < ProjectCommandAdmissionService.MAX_REQUEST_IDS_PER_LIVE_ANCHOR + 1; index++) {
            Files.writeString(records.resolve(index + ".json"), json, StandardCharsets.UTF_8);
        }

        ProjectCommandStore store = new ProjectCommandStore(namespaceRoot);
        assertEquals(ProjectCommandAdmissionService.MAX_REQUEST_IDS_PER_LIVE_ANCHOR + 1,
                store.countForAnchor(worktree.locator(), "anchor-capacity"));
    }

    @Test
    void compactsExpiredDeadAnchorAndRetainsPermanentLock(@TempDir Path temp) throws Exception {
        Path namespaceRoot = temp.resolve("commands");
        Path worktreePath = Files.createDirectories(temp.resolve("worktree"));
        PhysicalWorktreeIdentity worktree = PhysicalWorktreeIdentity.capture(worktreePath);
        long now = System.currentTimeMillis();
        ProjectCommandProcessAnchor anchor = ProjectCommandProcessAnchor.capture(worktree.locator(),
                new SessionProcessIdentity(Long.MAX_VALUE, "missing", "missing", 1L, "cleanup"), now - 10_000L);
        Path lockPath;
        try (ProjectCommandNamespace namespace = ProjectCommandNamespace.open(namespaceRoot)) {
            namespace.publishScope(worktree);
            lockPath = namespace.worktreeLockPath(worktree.locator());
            namespace.writeAnchor(anchor);
        }
        Object lockKey = Files.readAttributes(lockPath, "basic:fileKey")
                .get("fileKey");
        ProjectCommandStore store = new ProjectCommandStore(namespaceRoot);
        String requestId = "n:1";
        String digest = ProjectCommandCanonicalizer.requestDigest(java.util.List.of("echo", "ok"), ".", 10);
        String semantic = ProjectCommandCanonicalizer.semanticDigest(digest, "codex", "cleanup", worktree.locator());
        store.save(new ProjectCommandRecord(anchor.anchorId(), worktree.locator(), requestId, digest, semantic,
                ProjectCommandPhase.STARTING, null, false, null, false, false, null, 1L,
                now - 9_000L, now - 9_000L, Map.of(), Map.of()));
        store.save(new ProjectCommandRecord(anchor.anchorId(), worktree.locator(), requestId, digest, semantic,
                ProjectCommandPhase.RUNNING, null, false, null, false, false, null, 2L,
                now - 9_000L, now - 8_000L, Map.of(), Map.of("pid", 2L)));
        store.save(new ProjectCommandRecord(anchor.anchorId(), worktree.locator(), requestId, digest, semantic,
                ProjectCommandPhase.TERMINAL, ProjectCommandTerminalResolution.OBSERVED_COMMAND_TERMINAL,
                true, 0, true, true, null, 3L, now - 9_000L, now - 8_000L,
                Map.of("status", "completed", "result", Map.of("outcome", "completed")), Map.of()));

        ProjectCommandMaintenanceService.CleanupResult result =
                new ProjectCommandMaintenanceService().cleanupDeadAnchor(namespaceRoot, anchor.anchorId(),
                        Instant.ofEpochMilli(now), Duration.ofSeconds(1));

        assertTrue(result.lockRetained());
        assertTrue(Files.isRegularFile(result.historyPath()));
        assertTrue(Files.isRegularFile(lockPath));
        assertEquals(lockKey,
                Files.readAttributes(lockPath, "basic:fileKey")
                        .get("fileKey"));
        assertTrue(Files.notExists(namespaceRoot.resolve("process-scopes")
                .resolve(anchor.anchorId())));
    }
}
