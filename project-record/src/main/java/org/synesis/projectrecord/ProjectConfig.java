package org.synesis.projectrecord;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable local project namespace and explicit authenticated peer allowlist. */
public final class ProjectConfig {
    /** Maximum UTF-8 configuration file size. */
    public static final int MAX_BYTES = 4_096;
    /** Maximum configured peer count. */
    public static final int MAX_PEERS = 32;
    private static final String PROJECT_KEY = "projectId";
    private final UUID projectId;
    private final Set<String> peerNodeIds;

    /**
     * Creates a bounded immutable configuration.
     *
     * @param projectId project namespace
     * @param peerNodeIds explicit allowed authenticated node IDs
     * @throws NullPointerException if an argument or peer is null
     * @throws IllegalArgumentException if a peer ID or count is invalid
     */
    public ProjectConfig(UUID projectId, Set<String> peerNodeIds) {
        this.projectId = Objects.requireNonNull(projectId, "project ID");
        Objects.requireNonNull(peerNodeIds, "peer node IDs");
        if (peerNodeIds.size() > MAX_PEERS) throw new IllegalArgumentException("too many configured peers");
        LinkedHashSet<String> checked = new LinkedHashSet<>();
        for (String peer : peerNodeIds) {
            if (peer == null || !peer.matches("sl1-[0-9a-f]{64}")) throw new IllegalArgumentException("invalid peer ID");
            checked.add(peer);
        }
        this.peerNodeIds = Set.copyOf(checked);
    }

    /** Returns the configured project UUID.
     * @return configured project UUID
     */
    public UUID projectId() { return projectId; }

    /** Returns the immutable explicit peer allowlist.
     * @return immutable allowlist
     */
    public Set<String> peerNodeIds() { return peerNodeIds; }

    /** Tests whether an authenticated remote node is explicitly allowed.
     * @param remoteNodeId authenticated remote ID
     * @return whether it is explicitly allowed
     */
    public boolean allows(String remoteNodeId) { return peerNodeIds.contains(remoteNodeId); }

    /**
     * Loads strict UTF-8 {@code projectId=} and repeated {@code peer=} lines.
     *
     * @param path configuration file
     * @return validated configuration
     * @throws IOException if bytes, keys, UUID, or bounds are invalid
     */
    public static ProjectConfig load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("project configuration exceeds bound");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("invalid project configuration UTF-8", exception);
        }
        UUID project = null;
        LinkedHashSet<String> peers = new LinkedHashSet<>();
        for (String line : text.split("\\n", -1)) {
            if (line.isEmpty()) continue;
            int separator = line.indexOf('=');
            if (separator <= 0 || separator != line.lastIndexOf('=')) throw new IOException("malformed project configuration");
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            try {
                if (PROJECT_KEY.equals(key)) {
                    if (project != null) throw new IOException("duplicate project ID");
                    project = UUID.fromString(value);
                } else if ("peer".equals(key)) {
                    if (!peers.add(value)) throw new IOException("duplicate peer ID");
                    if (peers.size() > MAX_PEERS) throw new IOException("too many peers");
                } else {
                    throw new IOException("unknown project configuration key");
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("invalid project configuration value", exception);
            }
        }
        if (project == null) throw new IOException("project ID is missing");
        try {
            return new ProjectConfig(project, peers);
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid project configuration", exception);
        }
    }

    /**
     * Atomically writes the bounded configuration.
     *
     * @param path target configuration file
     * @throws IOException if writing or atomic replacement fails
     */
    public void save(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        StringBuilder text = new StringBuilder("projectId=").append(projectId).append('\n');
        peerNodeIds.stream().sorted().forEach(peer -> text.append("peer=").append(peer).append('\n'));
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("project configuration exceeds bound");
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
