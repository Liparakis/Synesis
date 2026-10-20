package org.synesis.coordination.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * A read-only service shows a project whose runtime is stopped as its files are: it replays them without creating,
 * moving or removing anything, and it decides and appends nothing.
 */
final class ReadOnlyCoordinationServiceTest {

    @TempDir
    Path root;

    private final UUID project = UUID.randomUUID();
    private final NodeIdentity node = generate();

    private static NodeIdentity generate() {
        try {
            return NodeIdentity.generate();
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void announce() throws Exception {
        UUID intent = UUID.randomUUID();
        WorkIntent lane = new WorkIntent(intent, project, "agt-a", "codex", UUID.randomUUID(), "Build a board", "CRUD", "base", List.of(ResourceSelector.pathExact("src/a.py")), 1,
                UUID.randomUUID(), WorkIntent.defaultAuthorityLineage(intent), WorkIntent.Status.ANNOUNCED, WorkIntent.Role.PRODUCER, List.of());
        assertTrue(new WorkIntentService(new PredictionEventStore(root, project), node).announce(lane).acquired());
    }

    private static List<String> listing(Path folder) throws IOException {
        try (var files = Files.walk(folder)) {
            return files.map(path -> folder.relativize(path) + ":" + (Files.isRegularFile(path) ? path.toFile().length() : -1) + ":" + path.toFile().lastModified()).sorted().collect(Collectors.toList());
        }
    }

    @Test
    void readsTheLogWithoutTouchingAnyFileEvenAnAbandonedTemporaryOne() throws Exception {
        announce();
        Path abandoned = root.resolve("events").resolve("00000000000000000099.sce.tmp-old");
        Files.writeString(abandoned, "left by a writer that died");
        Files.setLastModifiedTime(abandoned, FileTime.fromMillis(1_000L));
        List<String> before = listing(root);

        CoordinationService service = CoordinationService.readOnly(PredictionEventStore.openReadOnly(root, project));

        assertEquals(1, service.collaborationProjection().activeIntents().size());
        assertEquals(before, listing(root), "an ordinary open would have removed the abandoned file");
        assertTrue(Files.exists(abandoned));
    }

    @Test
    void refusesEverythingThatWouldAppend() throws Exception {
        announce();
        CoordinationService service = CoordinationService.readOnly(PredictionEventStore.openReadOnly(root, project));
        CoordinationCommand command = CoordinationCommand.create(UUID.randomUUID(), project, UUID.randomUUID(), PredictionEventType.TASK_RELEASED, node.nodeId(), new byte[0], node);
        List<String> before = listing(root);

        assertThrows(CoordinationException.class, () -> service.submit(command));
        assertEquals(before, listing(root));
    }
}
