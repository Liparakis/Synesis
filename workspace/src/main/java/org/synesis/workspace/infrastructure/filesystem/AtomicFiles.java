package org.synesis.workspace.infrastructure.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * File operations used by durable stores that write to a prepared sibling temporary file.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * Replaces a destination from its prepared sibling, falling back when atomic moves are not
     * supported, and always removes the temporary file after the move attempt.
     *
     * @param temporary   prepared temporary file
     * @param destination destination path
     * @throws IOException if replacement or temporary-file cleanup fails
     */
    public static void replaceFromTemporary(Path temporary, Path destination) throws IOException {
        Objects.requireNonNull(temporary, "temporary");
        Objects.requireNonNull(destination, "destination");
        try {
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
