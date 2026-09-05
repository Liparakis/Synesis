package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.atomic.AtomicReference;
import org.synesis.workspace.application.ProjectApplicationService;

/**
 * Event-driven, project-local wake relay owned by the existing Codex lifecycle
 * host.
 *
 * <p>The relay watches only the durable coordination event directory. It does
 * not poll a provider, inspect conversation content, choose tasks, or launch
 * participants. Each event causes a bounded projection scan; the admission
 * service then targets only an exact active participant with an exact dormant
 * thread and explicit pending inbox item. The relay is an owner-thread
 * responsibility, uses a daemon thread so it cannot outlive that owner process,
 * and is closed with its lifecycle owner.</p>
 *
 * @since 1.0
 */
final class CodexWakeCoordinator implements AutoCloseable {

    @FunctionalInterface
    interface ScanAction {
        /** Runs one bounded durable wake scan. */
        void run() throws Exception;
    }

    private final Path projectRoot;
    private final Path eventsDirectory;
    private final CodexWakeAdmissionService admissionService;
    private final ScanAction scanAction;
    private final AtomicReference<WatchService> watchService = new AtomicReference<>();
    private final Thread thread;
    private volatile boolean closed;
    private volatile String lastDiagnostic = "";
    private volatile long scanCount;

    /**
     * Creates a relay for one project lifecycle owner.
     *
     * @param location initialized project location
     * @param dispatcher exact lifecycle owner
     */
    CodexWakeCoordinator(ProjectApplicationService.ProjectLocation location,
            CodexWakeAdmissionService.LifecycleDispatcher dispatcher) {
        Path runtimeRoot = CodexWakeAdmissionService.runtimeRoot(location);
        this.projectRoot = location.root();
        this.eventsDirectory = location.root().resolve(".synesis").resolve("coordination").resolve("events")
                .toAbsolutePath().normalize();
        this.admissionService = new CodexWakeAdmissionService(dispatcher,
                runtimeRoot.resolve("wake-admissions.json"));
        this.scanAction = () -> this.admissionService.scan(this.projectRoot);
        this.thread = new Thread(this::run, "synesis-codex-wake-relay");
        this.thread.setDaemon(true);
    }

    /**
     * Creates a relay with an injected scan action for deterministic lifecycle tests.
     *
     * @param projectRoot project root identity
     * @param eventsDirectory durable event directory
     * @param scanAction bounded scan action
     */
    CodexWakeCoordinator(Path projectRoot, Path eventsDirectory, ScanAction scanAction) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.eventsDirectory = eventsDirectory.toAbsolutePath().normalize();
        this.admissionService = null;
        this.scanAction = scanAction;
        this.thread = new Thread(this::run, "synesis-codex-wake-relay");
        this.thread.setDaemon(true);
    }

    /**
     * Starts the bounded event-driven relay.
     *
     * @throws IOException when the coordination event directory cannot be watched
     */
    void start() throws IOException {
        Files.createDirectories(eventsDirectory);
        thread.start();
    }

    /**
     * Reports whether the bounded relay thread is alive.
     *
     * @return true while the relay is running
     */
    boolean isAlive() {
        return thread.isAlive();
    }

    /**
     * Returns the most recent bounded scan diagnostic.
     *
     * @return diagnostic or empty string
     */
    String lastDiagnostic() {
        return lastDiagnostic;
    }

    /**
     * Returns the number of completed durable projection scans.
     *
     * @return scan count
     */
    long scanCount() {
        return scanCount;
    }

    private void run() {
        try (WatchService service = FileSystems.getDefault().newWatchService()) {
            watchService.set(service);
            eventsDirectory.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            scanSafely();
            while (!closed) {
                WatchKey key = service.take();
                boolean relevant = key.pollEvents().stream().anyMatch(event -> {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        return true;
                    }
                    Object context = event.context();
                    return context != null && context.toString().endsWith(".sce");
                });
                if (!key.reset()) {
                    lastDiagnostic = "coordination_watch_key_invalid";
                    return;
                }
                if (relevant) {
                    scanSafely();
                }
            }
        } catch (java.nio.file.ClosedWatchServiceException closedService) {
            // Normal close unblocks WatchService.take() with this signal.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException failure) {
            if (!closed) {
                lastDiagnostic = diagnostic(failure);
            }
        } finally {
            watchService.set(null);
        }
    }

    private void scanSafely() {
        if (closed) {
            return;
        }
        try {
            scanAction.run();
            scanCount++;
            lastDiagnostic = "";
        } catch (Exception failure) {
            lastDiagnostic = diagnostic(failure);
        }
    }

    @Override
    public void close() {
        closed = true;
        WatchService service = watchService.getAndSet(null);
        if (service != null) {
            try {
                service.close();
            } catch (IOException ignored) {
                // Closing the watch is already the bounded shutdown action.
            }
        }
        thread.interrupt();
        try {
            thread.join(1_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String diagnostic(Exception failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
