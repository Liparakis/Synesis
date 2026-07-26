package org.synesis.cli.command.coordination;

import java.io.Serial;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.synesis.coordination.transport.http.CoordinationHttpClient;
import org.synesis.coordination.domain.prediction.PredictionEvent;

/**
 * Shared durable-cursor SSE follower for supervisor and events commands.
 */
public final class CoordinationEventFollower {

    private CoordinationEventFollower() {
    }

    /** Follows the coordination event stream for the requested duration.
     * @param endpoint coordination HTTP endpoint
     * @param cursorPath durable cursor path
     * @param durationSeconds maximum follow duration
     * @param consumer event consumer
     * @throws Exception if following fails
     */
    public static void follow(URI endpoint,
            Path cursorPath,
            int durationSeconds,
            java.util.function.Consumer<PredictionEvent> consumer)
            throws Exception {
        long initial = readCursor(cursorPath);
        AtomicLong cursor = new AtomicLong(initial);
        Runnable stream = () -> {
            try {
                new CoordinationHttpClient(endpoint).follow(initial, 0, event -> {
                    consumer.accept(event);
                    cursor.set(event.sequence());
                    writeCursor(cursorPath, event.sequence());
                });
            } catch (Exception failure) {
                if (!Thread.currentThread()
                        .isInterrupted()) {
                    throw new StreamFailure(failure);
                }
            }
        };
        if (durationSeconds <= 0) {
            stream.run();
            return;
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread follower = new Thread(() -> {
            try {
                stream.run();
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "synesis-coordination-follow");
        follower.setDaemon(true);
        follower.start();
        follower.join(durationSeconds * 1000L);
        if (follower.isAlive()) {
            follower.interrupt();
            follower.join(2_000L);
        }
        Throwable error = failure.get();
        if (error instanceof StreamFailure streamFailure) {
            throw streamFailure.cause;
        }
        if (error instanceof Exception exception) {
            throw exception;
        }
    }

    /** Reads a durable event cursor, defaulting to zero when absent.
     * @param path durable cursor path
     * @return cursor sequence
     * @throws Exception if the cursor cannot be read
     */
    public static long readCursor(Path path) throws Exception {
        if (Files.notExists(path)) {
            return 0;
        }
        return Long.parseLong(Files.readString(path, StandardCharsets.US_ASCII)
                .trim());
    }

    private static void writeCursor(Path path, long cursor) {
        try {
            Files.createDirectories(path.toAbsolutePath()
                    .normalize()
                    .getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, Long.toString(cursor), StandardCharsets.US_ASCII);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("CURSOR_WRITE_FAILED", failure);
        }
    }

    private static final class StreamFailure extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;
        private final Exception cause;

        private StreamFailure(Exception cause) {
            this.cause = cause;
        }
    }

}
