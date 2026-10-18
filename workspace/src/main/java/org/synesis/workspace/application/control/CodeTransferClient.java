package org.synesis.workspace.application.control;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.synesis.coordination.persistence.CodeTransferCodec;
import org.synesis.coordination.persistence.CodeTransferMessage;
import org.synesis.coordination.persistence.ReplicationExchange;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * The requester's side of Code over Link (ADR-0098 step 3): brings the objects of one wanted commit from one peer
 * ({@link CodeTransferServer} on the other side) into the local repository, piece by piece.
 *
 * <p>Each piece is checked before anything reads it: every chunk against its SHA-256, the whole with
 * {@code git bundle verify}, then a fetch with {@code fetch.fsckObjects=true} into
 * {@code refs/synesis/incoming/<pieceId>}, whose commit must be the piece's tip. That ref is a transfer buffer, not
 * work, and is deleted with the piece's files once the piece is in. This class moves no snapshot ref and no branch;
 * its caller decides what the fetched commit becomes.</p>
 *
 * <p>Chunks are kept under {@code <coordination>/code-incoming/<pieceId>/} as they arrive, each written whole, so a
 * transfer cut off by a lost exchange resumes from the first missing chunk. A piece that fails a check after it
 * arrived is deleted, whatever the failure, because chunks of a piece the peer prepared again need not fit the
 * earlier ones; asking again then fetches it whole.</p>
 */
public final class CodeTransferClient {

    /** The folder, inside the coordination folder, that holds pieces being received. */
    static final String INCOMING = "code-incoming";

    private static final String INCOMING_PREFIX = "refs/synesis/incoming/";

    private final ReplicationExchange peer;
    private final UUID project;
    private final Path repository;
    private final Path incoming;

    /**
     * Creates the requester's side for one project and one peer.
     *
     * @param peer                  one request and one answer with the peer, as on its Link session
     * @param project               the project
     * @param repository            the local checkout that receives the objects
     * @param coordinationDirectory the project's coordination folder
     */
    public CodeTransferClient(ReplicationExchange peer, UUID project, Path repository, Path coordinationDirectory) {
        this.peer = Objects.requireNonNull(peer, "peer");
        this.project = Objects.requireNonNull(project, "project");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.incoming = Objects.requireNonNull(coordinationDirectory, "coordinationDirectory").resolve(INCOMING);
    }

    /**
     * Makes the wanted commit's objects present here. Asks for one piece after another, each time naming the last
     * piece's tip as held, until a piece's tip is the commit the wanted name resolved to on the peer.
     *
     * @param wanted a name {@link CodeTransferMessage.Want#wanted()} allows
     * @param haves  commit IDs this repository holds, which the peer leaves out of the pieces
     * @return the wanted commit, now present here
     * @throws CodeRemote.Refused the peer's {@link CodeTransferMessage.Unavailable} code, or
     *                            {@link CodeTransferMessage#PIECE_CORRUPT} when what arrived fails a check
     * @throws IOException        when an exchange or Git fails; asking again resumes
     */
    public String fetch(String wanted, List<String> haves) throws IOException {
        if (CodeTransferCodec.isCommitId(wanted) && holds(wanted)) {
            return wanted;
        }
        String lastTip = null;
        while (true) {
            List<String> have = new ArrayList<>(haves);
            if (lastTip != null) {
                have.add(lastTip);
            }
            CodeTransferMessage.Piece piece = switch (ask(new CodeTransferMessage.Want(project, wanted, have))) {
                case CodeTransferMessage.Piece answer -> answer;
                case CodeTransferMessage.Unavailable unavailable -> throw refused(unavailable, wanted);
                default -> throw new IOException("the peer answered a want with another kind of message");
            };
            if ((CodeTransferCodec.isCommitId(wanted) && !wanted.equals(piece.target())) || piece.tip().equals(lastTip)) {
                throw new CodeRemote.Refused(CodeTransferMessage.PIECE_CORRUPT, "the peer's piece does not lead to " + wanted);
            }
            String tip = piece.chunkCount() == 0 ? present(piece.tip()) : receive(piece);
            if (tip.equals(piece.target())) {
                return tip;
            }
            lastTip = tip;
        }
    }

    private String present(String commit) throws IOException {
        if (!holds(commit)) {
            throw new CodeRemote.Refused(CodeTransferMessage.NOT_HELD, "the peer counted " + commit + " as held here");
        }
        return commit;
    }

    private String receive(CodeTransferMessage.Piece piece) throws IOException {
        Path folder = incoming.resolve(piece.pieceId());
        Files.createDirectories(folder);
        for (int index = 0; index < piece.chunkCount(); index++) {
            Path file = folder.resolve(index + ".chunk");
            if (!Files.exists(file)) {
                store(file, chunk(piece, index, folder));
            }
        }
        Path bundle = folder.resolve("piece.bundle");
        String ref = INCOMING_PREFIX + piece.pieceId();
        try {
            try (OutputStream out = Files.newOutputStream(bundle)) {
                for (int index = 0; index < piece.chunkCount(); index++) {
                    Files.copy(folder.resolve(index + ".chunk"), out);
                }
            }
            GitProcessRunner.runBundle(repository, "bundle", "verify", bundle.toString());
            GitProcessRunner.runBundle(repository, "-c", "fetch.fsckObjects=true", "fetch", "--no-tags",
                    "--no-write-fetch-head", bundle.toString(),
                    "+" + CodeTransferServer.OUTGOING_PREFIX + piece.pieceId() + ":" + ref);
            String fetched = GitProcessRunner.run(repository, "rev-parse", "--verify", ref + "^{commit}");
            if (!fetched.equals(piece.tip())) {
                throw new CodeRemote.Refused(CodeTransferMessage.PIECE_CORRUPT, "piece " + piece.pieceId()
                        + " brought " + fetched + " instead of " + piece.tip());
            }
            return fetched;
        } finally {
            GitProcessRunner.runOptional(repository, "update-ref", "-d", ref);
            delete(folder);
        }
    }

    private CodeTransferMessage.ChunkData chunk(CodeTransferMessage.Piece piece, int index, Path folder) throws IOException {
        CodeTransferMessage.ChunkData data = switch (ask(new CodeTransferMessage.Chunk(project, piece.pieceId(), index))) {
            case CodeTransferMessage.ChunkData answer -> answer;
            case CodeTransferMessage.Unavailable unavailable -> {
                if (CodeTransferMessage.PIECE_UNKNOWN.equals(unavailable.code())) {
                    // The peer no longer keeps the piece; one it prepares again may differ from these chunks.
                    delete(folder);
                }
                throw refused(unavailable, piece.pieceId());
            }
            default -> throw new IOException("the peer answered a chunk request with another kind of message");
        };
        long expected = index < piece.chunkCount() - 1 ? CodeTransferMessage.CHUNK_BYTES
                : piece.totalBytes() - (long) index * CodeTransferMessage.CHUNK_BYTES;
        if (data.index() != index || data.bytes().length != expected || !data.intact()) {
            throw new CodeRemote.Refused(CodeTransferMessage.PIECE_CORRUPT, "chunk " + index + " of piece " + piece.pieceId()
                    + " does not match its size or SHA-256");
        }
        return data;
    }

    private static void store(Path file, CodeTransferMessage.ChunkData data) throws IOException {
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        Files.write(partial, data.bytes());
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private CodeTransferMessage ask(CodeTransferMessage.Request request) throws IOException {
        return CodeTransferCodec.decode(peer.exchange(CodeTransferCodec.encode(request)));
    }

    private boolean holds(String commit) throws IOException {
        return GitProcessRunner.runResult(repository, "cat-file", "-e", commit + "^{commit}").exitCode() == 0;
    }

    private static CodeRemote.Refused refused(CodeTransferMessage.Unavailable unavailable, String subject) {
        return new CodeRemote.Refused(unavailable.code(), "the peer answered " + unavailable.code() + " for " + subject);
    }

    private static void delete(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
