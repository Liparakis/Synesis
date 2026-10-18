package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.synesis.coordination.exception.CoordinationException;

/**
 * Keeps the log of a project shared with a step-2 build as an archive, once (ADR-0098 step 4, designer item 6, and
 * the owner's answer 9).
 *
 * <p>On a step-2 computer {@code events/} was one shared log: the leader's, holding every member's entries, or a
 * member's copy of it. It cannot become a computer's own log, because it is not signed by this computer alone. So the
 * first open by a step-4 build of a shared project's folder that has no {@code applied.order} yet moves
 * {@code events/} to {@code events-step2/} and {@code replication.properties} to
 * {@code replication.properties.step2}, and the project starts a fresh log on one computer, to be invited again. A
 * project on one computer changes nothing: its {@code events/} is already its own log. Nothing is deleted.</p>
 *
 * <p>The decision is made once: every open by a step-4 build creates {@code applied.order}, so a later open never
 * takes a step-4 log for a step-2 one.</p>
 */
final class Step2Archive {

    static final String ARCHIVE = "events-step2";

    private Step2Archive() {
    }

    /**
     * Archives a step-2 shared log if this folder holds one, then marks the folder as opened by step 4.
     *
     * @param root the coordination folder
     * @throws IOException when the folder cannot be read or the archive cannot be made
     */
    static void openOnce(Path root) throws IOException {
        Path order = root.resolve(AppliedOrder.FILE);
        if (Files.exists(order)) {
            return;
        }
        Files.createDirectories(root);
        try (ProjectAppendLock lock = ProjectAppendLock.acquireNamed(root, AppliedOrder.LOCK)) {
            if (!lock.isHeld() || Files.exists(order)) {
                return;
            }
            if (sharedStep2(root)) {
                Files.move(root.resolve("events"), free(root, ARCHIVE));
                Files.move(root.resolve(ReplicationSettings.FILE), free(root, ReplicationSettings.FILE + ".step2"));
            }
            Files.createFile(order);
        }
    }

    private static boolean sharedStep2(Path root) throws IOException {
        Path events = root.resolve("events");
        if (!Files.isDirectory(events)) {
            return false;
        }
        try (var files = Files.list(events)) {
            if (files.noneMatch(path -> path.getFileName().toString().endsWith(".sce"))) {
                return false;
            }
        }
        Optional<ReplicationSettings> settings;
        try {
            settings = ReplicationSettings.read(root);
        } catch (CoordinationException unreadable) {
            // A role that cannot be read refuses every append anyway; its log is left where it is.
            return false;
        }
        return settings.isPresent() && (settings.get().isMember() || !settings.get().members().isEmpty());
    }

    /** The name itself, or the name with the first free counter. */
    private static Path free(Path root, String name) {
        Path candidate = root.resolve(name);
        for (int counter = 2; Files.exists(candidate); counter++) {
            candidate = root.resolve(name + "-" + counter);
        }
        return candidate;
    }
}
