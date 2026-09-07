package org.synesis.workspace.application.provider.continuity;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Atomic project-local store for trusted managed runtime death receipts.
 *
 * <p>Each receipt is immutable and scoped to one binding generation. A
 * conflicting second receipt for the same generation is rejected, preventing
 * a later caller from replacing trusted evidence with a different assertion.</p>
 */
public final class ManagedRuntimeDeathReceiptStore {

    private static final String DIRECTORY = "managed-continuity/death-receipts";
    private final Path root;

    /**
     * Creates a receipt store rooted at the supplied directory.
     *
     * @param root receipt directory
     */
    public ManagedRuntimeDeathReceiptStore(Path root) {
        this.root = Objects.requireNonNull(root, "root")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Returns the conventional project-local receipt store.
     *
     * @param location initialized project location
     * @return receipt store
     */
    public static ManagedRuntimeDeathReceiptStore storeFor(ProjectApplicationService.ProjectLocation location) {
        Objects.requireNonNull(location, "location");
        return new ManagedRuntimeDeathReceiptStore(location.synesisDirectory()
                .resolve("local/runtime")
                .resolve(DIRECTORY));
    }

    private static void withLock(Path file, LockedOperation operation) throws IOException {
        Path lock = file.resolveSibling(file.getFileName() + ".lock");
        Files.createDirectories(lock.getParent());
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held;
            try {
                held = channel.lock();
            } catch (OverlappingFileLockException failure) {
                throw new IOException("managed death receipt update already active", failure);
            }
            try (held) {
                operation.run();
            }
        }
    }

    private static String text(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return text;
    }

    private static Number number(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof Number number)) {
            throw new IllegalArgumentException("missing " + key);
        }
        return number;
    }

    /**
     * Reads the receipt for one exact binding generation.
     *
     * @param bindingSessionId exact binding
     * @param generation       exact attachment generation
     * @return receipt when trusted evidence exists
     * @throws IOException when the receipt is malformed
     */
    public synchronized Optional<ManagedRuntimeDeathReceipt> read(String bindingSessionId, long generation)
            throws IOException {
        Path file = file(bindingSessionId, generation);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            Object parsed = ProviderJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> raw)) {
                throw new IOException("managed death receipt must be an object");
            }
            Map<String, Object> value = new LinkedHashMap<>();
            raw.forEach((key, item) -> value.put(String.valueOf(key), item));
            ManagedRuntimeDeathReceipt receipt = new ManagedRuntimeDeathReceipt(
                    number(value, "schemaVersion").intValue(), text(value, "projectId"), text(value, "provider"),
                    text(value, "bindingSessionId"), number(value, "generation").longValue(),
                    number(value, "rootPid").longValue(), number(value, "rootStartEpochMillis").longValue(),
                    text(value, "rootExecutable"), text(value, "rootCommandIdentity"),
                    text(value, "supervisorProvenance"), number(value, "supervisorRevision").longValue(),
                    number(value, "observedAtEpochMillis").longValue());
            if (!bindingSessionId.equals(receipt.bindingSessionId()) || generation != receipt.generation()) {
                throw new IOException("managed death receipt identity mismatch");
            }
            return Optional.of(receipt);
        } catch (RuntimeException failure) {
            throw new IOException("malformed managed death receipt", failure);
        }
    }

    /**
     * Records trusted evidence exactly once for one binding generation.
     *
     * @param receipt supervisor-produced death receipt
     * @throws IOException when a conflicting receipt or persistence failure occurs
     */
    public synchronized void write(ManagedRuntimeDeathReceipt receipt) throws IOException {
        Objects.requireNonNull(receipt, "receipt");
        Path file = file(receipt.bindingSessionId(), receipt.generation());
        withLock(file, () -> {
            Optional<ManagedRuntimeDeathReceipt> prior = read(receipt.bindingSessionId(), receipt.generation());
            if (prior.isPresent() && !prior.get()
                    .equals(receipt)) {
                throw new IOException("managed death receipt conflict");
            }
            if (prior.isPresent()) {
                return;
            }
            Files.createDirectories(file.getParent());
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", receipt.schemaVersion());
            value.put("projectId", receipt.projectId());
            value.put("provider", receipt.provider());
            value.put("bindingSessionId", receipt.bindingSessionId());
            value.put("generation", receipt.generation());
            value.put("rootPid", receipt.rootPid());
            value.put("rootStartEpochMillis", receipt.rootStartEpochMillis());
            value.put("rootExecutable", receipt.rootExecutable());
            value.put("rootCommandIdentity", receipt.rootCommandIdentity());
            value.put("supervisorProvenance", receipt.supervisorProvenance());
            value.put("supervisorRevision", receipt.supervisorRevision());
            value.put("observedAtEpochMillis", receipt.observedAtEpochMillis());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, ProviderJson.write(value) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        });
    }

    /**
     * Returns the receipt directory.
     *
     * @return receipt root
     */
    public Path root() {
        return root;
    }

    private Path file(String bindingSessionId, long generation) {
        if (bindingSessionId == null || bindingSessionId.isBlank() || generation < 1) {
            throw new IllegalArgumentException("invalid death receipt key");
        }
        return root.resolve(bindingSessionId + ".generation-" + generation + ".death.json");
    }

    @FunctionalInterface
    private interface LockedOperation {

        void run() throws IOException;
    }
}
