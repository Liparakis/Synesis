package org.synesis.workspace.application.agent.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;

/**
 * Where a person leaves the project's agents a task after they started (ADR-0103, stage 4 amendment).
 *
 * <p>A task is a text file under {@code agent-runs/<project>/tasks/} of the application state, not an entry of the
 * coordination log. That is the rule that it carries no authority, kept by construction: nothing in coordination
 * reads this directory, so a task cannot be a claim, a review verdict, an approval, a merge or a policy, whatever
 * its text says. The only reader is {@link AgentRunController}, which hands the newest text to agents that wait for
 * a task or are stopped, as their prompt. The agent then goes through Synesis exactly as with a task given at start.</p>
 *
 * <p>The directory belongs to this computer and this user, like the run logs beside it; it is never replicated.</p>
 */
public final class AgentTaskInbox implements AgentRunController.TaskInbox {

    /** The longest task accepted, in characters. */
    public static final int MAX_TASK_CHARS = 20_000;

    private static final String SUFFIX = ".txt";

    private static final java.util.concurrent.atomic.AtomicLong LAST_STAMP = new java.util.concurrent.atomic.AtomicLong();

    private final Path directory;

    /**
     * Opens the inbox of a project in the application state of this installation.
     *
     * @param location the project
     */
    public AgentTaskInbox(ProjectLocation location) {
        this(AdministrativeStateLocator.applicationStateRoot(), location.projectId());
    }

    /**
     * Opens the inbox of a project under an explicit state root.
     *
     * @param stateRoot the application state root
     * @param projectId the project
     */
    public AgentTaskInbox(Path stateRoot, UUID projectId) {
        directory = Objects.requireNonNull(stateRoot, "stateRoot").resolve("agent-runs").resolve(Objects.requireNonNull(projectId, "projectId").toString()).resolve("tasks");
    }

    /**
     * Leaves a task for the agents of the project that wait for one. The write is atomic, so a reader sees a whole
     * task or none.
     *
     * @param text the task
     * @return the identifier of the task, which orders it among the others
     * @throws IllegalArgumentException when the text is blank or longer than {@link #MAX_TASK_CHARS}
     * @throws IOException              when it cannot be written
     */
    public String send(String text) throws IOException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a task is required");
        }
        if (text.length() > MAX_TASK_CHARS) {
            throw new IllegalArgumentException("a task may hold at most " + MAX_TASK_CHARS + " characters");
        }
        Files.createDirectories(directory);
        String id = String.format("%020d-%s", nextStamp(), UUID.randomUUID());
        Path written = Files.writeString(directory.resolve(id + ".tmp"), text.strip(), StandardCharsets.UTF_8);
        Files.move(written, directory.resolve(id + SUFFIX), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        // Only the newest task is ever read, so an older one can no longer matter and would otherwise stay for ever.
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(candidate -> candidate.getFileName().toString().endsWith(SUFFIX) && candidate.getFileName().toString().compareTo(id + SUFFIX) < 0).toList()) {
                Files.deleteIfExists(file);
            }
        }
        return id;
    }

    /** Strictly increasing within the process, so two tasks sent in the same millisecond still order as sent and pruning keeps the later one. */
    private static long nextStamp() {
        return LAST_STAMP.updateAndGet(last -> Math.max(last + 1, System.currentTimeMillis()));
    }

    @Override
    public Optional<AgentRunController.Task> newest() throws IOException {
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        Optional<Path> newest;
        try (Stream<Path> files = Files.list(directory)) {
            newest = files.filter(file -> file.getFileName().toString().endsWith(SUFFIX)).max(java.util.Comparator.comparing(file -> file.getFileName().toString()));
        }
        if (newest.isEmpty()) {
            return Optional.empty();
        }
        String name = newest.get().getFileName().toString();
        try {
            return Optional.of(new AgentRunController.Task(name.substring(0, name.length() - SUFFIX.length()), Files.readString(newest.get(), StandardCharsets.UTF_8)));
        } catch (java.nio.file.NoSuchFileException superseded) {
            // A newer task pruned this one between listing and reading; the newer one is what a reader wants.
            return newest();
        }
    }
}
