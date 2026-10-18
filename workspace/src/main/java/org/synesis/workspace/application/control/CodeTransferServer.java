package org.synesis.workspace.application.control;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.synesis.coordination.persistence.CodeTransferCodec;
import org.synesis.coordination.persistence.CodeTransferMessage;
import org.synesis.workspace.application.task.SnapshotRefs;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * The holder's side of Code over Link (ADR-0098 step 3): answers a peer's {@link CodeTransferMessage.Want} with the
 * next piece of history and its {@link CodeTransferMessage.Chunk}s with the piece's bytes.
 *
 * <p>The peer names what it wants; this computer decides what that is. It serves only snapshot and prepared refs
 * ({@link SnapshotRefs}), {@code HEAD} while it is a checked-out branch, and commit IDs that its caller says an entry of
 * its log names, or that equal that branch's head. Every Git argument it builds is either its own ref name or a
 * commit ID, which {@link CodeTransferMessage} admits only as full hexadecimal, so a peer never chooses a Git command
 * or option. Nothing is ever written into this computer's branches or tags.</p>
 *
 * <p>A piece is the history from the haves this computer holds to a tip, cut along first parents so that its
 * {@code rev-list --disk-usage} stays within the piece limit; a single commit over the limit is a piece on its own.
 * First parents make the size grow with each later cut, so the cut is a binary search. The bundle is written under
 * {@code <coordination>/code-pieces/}, named by a hash of (wanted commit, held haves), so a repeated want gets the
 * same bytes for the 24 hours a piece is kept. Its chunks are read from that file on demand.</p>
 */
public final class CodeTransferServer {

    /** The folder, inside the coordination folder, that holds prepared pieces. */
    static final String PIECES = "code-pieces";

    /** Where a piece's tip is named while Git writes its bundle, and the ref name the bundle carries. */
    static final String OUTGOING_PREFIX = "refs/synesis/outgoing/";

    private static final long DEFAULT_PIECE_LIMIT_BYTES = 150L * 1024 * 1024;
    private static final Duration KEPT = Duration.ofHours(24);
    private static final Pattern REF_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final Path repository;
    private final Path pieces;
    private final Predicate<String> namedByLog;
    private final long pieceLimitBytes;

    /**
     * Creates the holder's side for one project.
     *
     * @param repository            the project's checkout whose objects are served
     * @param coordinationDirectory the project's coordination folder
     * @param namedByLog            tells whether an entry of this computer's copy of the log names a commit ID
     */
    public CodeTransferServer(Path repository, Path coordinationDirectory, Predicate<String> namedByLog) {
        this(repository, coordinationDirectory, namedByLog, DEFAULT_PIECE_LIMIT_BYTES);
    }

    CodeTransferServer(Path repository, Path coordinationDirectory, Predicate<String> namedByLog, long pieceLimitBytes) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.pieces = Objects.requireNonNull(coordinationDirectory, "coordinationDirectory").resolve(PIECES);
        this.namedByLog = Objects.requireNonNull(namedByLog, "namedByLog");
        this.pieceLimitBytes = pieceLimitBytes;
    }

    /**
     * Answers one request. Preparing a piece runs Git for up to {@link GitProcessRunner#runBundle}'s bound, so the
     * caller must not hold a lock.
     *
     * @param request a peer's decoded request
     * @return a {@link CodeTransferMessage.Piece}, a {@link CodeTransferMessage.ChunkData} or a
     *         {@link CodeTransferMessage.Unavailable}
     */
    public CodeTransferMessage answer(CodeTransferMessage.Request request) {
        try {
            return switch (request) {
                case CodeTransferMessage.Want want -> want(want);
                case CodeTransferMessage.Chunk chunk -> chunk(chunk);
            };
        } catch (IOException failed) {
            return new CodeTransferMessage.Unavailable(CodeTransferMessage.PIECE_FAILED);
        }
    }

    private CodeTransferMessage want(CodeTransferMessage.Want want) throws IOException {
        String wanted = want.wanted();
        String branchHead = branchHead();
        boolean served = "HEAD".equals(wanted) ? branchHead != null
                : CodeTransferCodec.isCommitId(wanted) ? wanted.equals(branchHead) || namedByLog.test(wanted)
                : isServedRef(wanted);
        if (!served) {
            return new CodeTransferMessage.Unavailable(CodeTransferMessage.NOT_SERVED);
        }
        String target = "HEAD".equals(wanted) ? branchHead : commit(wanted);
        if (target == null) {
            return new CodeTransferMessage.Unavailable(CodeTransferMessage.NOT_HELD);
        }
        String branch = "HEAD".equals(wanted) ? branchName() : "";
        List<String> exclusions = new ArrayList<>();
        for (String have : want.have().stream().distinct().sorted().toList()) {
            if (commit(have) != null) {
                exclusions.add("^" + have);
            }
        }
        String pieceId = sha256Hex(target + "\n" + String.join("\n", exclusions));
        Path bundle = pieces.resolve(pieceId + ".bundle");
        if (!Files.isRegularFile(bundle)) {
            long commits = Long.parseLong(GitProcessRunner.runBundle(repository,
                    withExclusions(exclusions, "rev-list", "--first-parent", "--count", target)));
            if (commits == 0) {
                return new CodeTransferMessage.Piece(pieceId, 0, 0, target, target, branch);
            }
            prepare(bundle, pieceId, cut(target, exclusions, commits), exclusions);
        }
        String head = GitProcessRunner.run(repository, "bundle", "list-heads", bundle.toString()).split("\\s+")[0];
        long size = Files.size(bundle);
        return new CodeTransferMessage.Piece(pieceId, size,
                (int) ((size + CodeTransferMessage.CHUNK_BYTES - 1) / CodeTransferMessage.CHUNK_BYTES), head, target, branch);
    }

    private CodeTransferMessage chunk(CodeTransferMessage.Chunk chunk) throws IOException {
        try (FileChannel file = FileChannel.open(pieces.resolve(chunk.pieceId() + ".bundle"), StandardOpenOption.READ)) {
            long offset = (long) chunk.index() * CodeTransferMessage.CHUNK_BYTES;
            if (offset >= file.size()) {
                return new CodeTransferMessage.Unavailable(CodeTransferMessage.PIECE_UNKNOWN);
            }
            ByteBuffer bytes = ByteBuffer.allocate((int) Math.min(CodeTransferMessage.CHUNK_BYTES, file.size() - offset));
            while (bytes.hasRemaining()) {
                if (file.read(bytes, offset + bytes.position()) < 0) {
                    throw new EOFException("piece shorter than its size");
                }
            }
            return CodeTransferMessage.ChunkData.of(chunk.index(), bytes.array());
        } catch (NoSuchFileException expired) {
            return new CodeTransferMessage.Unavailable(CodeTransferMessage.PIECE_UNKNOWN);
        }
    }

    /** Whether a name is exactly a snapshot or prepared ref, with an ID Git can take only as a ref name. */
    static boolean isServedRef(String name) {
        String id = name.substring(name.lastIndexOf('/') + 1);
        return SnapshotRefs.isTrashRef(name) || REF_ID.matcher(id).matches()
                && (name.equals(SnapshotRefs.snapshotRef(id)) || name.equals(SnapshotRefs.preparedRef(id)));
    }

    /** The head of the checked-out branch, or null when HEAD is detached or the branch has no commit yet. */
    private String branchHead() throws IOException {
        return GitProcessRunner.runOptional(repository, "symbolic-ref", "-q", "HEAD").startsWith("refs/heads/")
                ? commit("HEAD") : null;
    }

    /** The checked-out branch's short name, or empty when a peer could not be given it as a branch name. */
    private String branchName() throws IOException {
        String name = GitProcessRunner.runOptional(repository, "symbolic-ref", "-q", "--short", "HEAD");
        return CodeTransferCodec.isBranchName(name) ? name : "";
    }

    /** The commit a served name or a well-formed commit ID resolves to here, or null when it is not held. */
    private String commit(String revision) throws IOException {
        String commit = GitProcessRunner.runOptional(repository, "rev-parse", "--verify", "-q", revision + "^{commit}");
        return commit.isEmpty() ? null : commit;
    }

    /**
     * The latest first-parent ancestor of {@code target} (itself included) whose history beyond the haves fits the
     * piece limit, or the earliest when none does.
     */
    private String cut(String target, List<String> exclusions, long commits) throws IOException {
        if (diskUsage(target, exclusions) <= pieceLimitBytes) {
            return target;
        }
        String cut = ancestor(target, commits - 1);
        long low = 1;
        long high = commits - 1;
        while (low <= high) {
            long middle = (low + high) >>> 1;
            String candidate = ancestor(target, middle);
            if (diskUsage(candidate, exclusions) <= pieceLimitBytes) {
                cut = candidate;
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }
        return cut;
    }

    private String ancestor(String target, long generations) throws IOException {
        return GitProcessRunner.run(repository, "rev-parse", "--verify", target + "~" + generations);
    }

    private long diskUsage(String tip, List<String> exclusions) throws IOException {
        return Long.parseLong(GitProcessRunner.runBundle(repository,
                withExclusions(exclusions, "rev-list", "--objects", "--disk-usage", tip)));
    }

    /**
     * Writes the bundle beside its final name and moves it there, so a piece file is always whole. When a concurrent
     * want for the same piece got there first, its file stands and this one is dropped: a requester may already be
     * reading its chunks.
     */
    private void prepare(Path bundle, String pieceId, String tip, List<String> exclusions) throws IOException {
        Files.createDirectories(pieces);
        deleteExpired();
        Path partial = pieces.resolve(pieceId + "." + UUID.randomUUID() + ".partial");
        String ref = OUTGOING_PREFIX + pieceId;
        try {
            // git bundle takes the tips it records from ref names only.
            GitProcessRunner.run(repository, "update-ref", ref, tip);
            try {
                GitProcessRunner.runBundle(repository, withExclusions(exclusions, "bundle", "create", partial.toString(), ref));
            } finally {
                GitProcessRunner.runOptional(repository, "update-ref", "-d", ref);
            }
            Files.move(partial, bundle);
        } catch (FileAlreadyExistsException concurrent) {
            // The other want's piece is used.
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private void deleteExpired() throws IOException {
        Instant oldest = Instant.now().minus(KEPT);
        try (Stream<Path> files = Files.list(pieces)) {
            for (Path file : files.toList()) {
                if (Files.getLastModifiedTime(file).toInstant().isBefore(oldest)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /** Git arguments followed by the exclusions of the held haves. */
    private static String[] withExclusions(List<String> exclusions, String... arguments) {
        List<String> command = new ArrayList<>(List.of(arguments));
        command.addAll(exclusions);
        return command.toArray(String[]::new);
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException required) {
            throw new IllegalStateException("every Java runtime provides SHA-256", required);
        }
    }
}
