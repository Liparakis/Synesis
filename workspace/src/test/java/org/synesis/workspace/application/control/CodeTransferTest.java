package org.synesis.workspace.application.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.persistence.CodeTransferCodec;
import org.synesis.coordination.persistence.CodeTransferMessage;
import org.synesis.coordination.persistence.ReplicationExchange;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;
import org.synesis.workspace.test.TestGit;

/**
 * Code over Link between two real repositories, with the Link exchange replaced by a direct call into the holder.
 */
final class CodeTransferTest {

    private static final UUID PROJECT = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");

    @TempDir
    Path temp;
    private Path holder;
    private Path holderCoordination;
    private Path requesterCoordination;
    private final Set<String> namedByLog = new java.util.HashSet<>();
    private final Random random = new Random(7);

    @BeforeEach
    void holderWithTwoCommits() throws Exception {
        holder = repository("holder");
        holderCoordination = Files.createDirectories(temp.resolve("holder-coordination"));
        requesterCoordination = Files.createDirectories(temp.resolve("requester-coordination"));
        commit(holder, "one", 100);
        commit(holder, "two", 100);
    }

    @Test
    void aSnapshotRefArrivesInARepositoryThatLacksIt() throws Exception {
        Path requester = temp.resolve("requester");
        TestGit.run(temp, "clone", "-q", holder.toString(), requester.toString());
        String base = head(requester);
        commit(holder, "lane work", 100);
        String snapshot = head(holder);
        TestGit.run(holder, "update-ref", "refs/synesis/snapshots/snap_abc", snapshot);
        TestGit.run(holder, "reset", "-q", "--hard", "HEAD~1");

        String fetched = client(requester, server(Long.MAX_VALUE)).fetch("refs/synesis/snapshots/snap_abc", List.of(base));

        assertEquals(snapshot, fetched);
        assertTrue(holds(requester, snapshot));
        assertEquals("", TestGit.output(requester, "for-each-ref", "refs/synesis/"));
        assertTrue(empty(requesterCoordination.resolve(CodeTransferClient.INCOMING)));
    }

    @Test
    void theDefaultBranchFillsAnEmptyRepositoryInSeveralPieces() throws Exception {
        for (int index = 0; index < 4; index++) {
            commit(holder, "big " + index, 4096);
        }
        Path requester = repository("requester");
        List<String> tips = new ArrayList<>();
        CodeTransferServer server = server(6000);
        ReplicationExchange recording = request -> {
            byte[] answer = direct(server).exchange(request);
            if (CodeTransferCodec.decode(answer) instanceof CodeTransferMessage.Piece piece) {
                tips.add(piece.tip());
            }
            return answer;
        };

        String fetched = client(requester, recording).fetch("HEAD", List.of());

        assertEquals(head(holder), fetched);
        assertTrue(tips.size() >= 3, "pieces: " + tips);
        assertEquals(tips.size(), Set.copyOf(tips).size());
        assertEquals(head(holder), GitProcessRunner.run(requester, "rev-parse", fetched + "^{commit}"));
        assertEquals(TestGit.output(holder, "rev-parse", "HEAD^{tree}"), TestGit.output(requester, "rev-parse", fetched + "^{tree}"));
    }

    @Test
    void anInterruptedTransferResumesFromItsFirstMissingChunk() throws Exception {
        commit(holder, "large", 600 * 1024);
        String wanted = head(holder);
        namedByLog.add(wanted);
        Path requester = repository("requester");
        CodeTransferServer server = server(Long.MAX_VALUE);
        List<Integer> asked = new ArrayList<>();
        ReplicationExchange cutAtTheThirdChunk = request -> {
            if (CodeTransferCodec.decode(request) instanceof CodeTransferMessage.Chunk chunk) {
                if (chunk.index() == 2) {
                    throw new IOException("stream reset");
                }
                asked.add(chunk.index());
            }
            return direct(server).exchange(request);
        };

        assertThrows(IOException.class, () -> client(requester, cutAtTheThirdChunk).fetch(wanted, List.of()));
        assertEquals(List.of(0, 1), asked);
        asked.clear();
        ReplicationExchange recording = request -> {
            if (CodeTransferCodec.decode(request) instanceof CodeTransferMessage.Chunk chunk) {
                asked.add(chunk.index());
            }
            return direct(server).exchange(request);
        };
        String fetched = client(requester, recording).fetch(wanted, List.of());

        assertEquals(wanted, fetched);
        assertFalse(asked.contains(0) || asked.contains(1), "asked again: " + asked);
        assertTrue(asked.contains(2), "asked: " + asked);
    }

    @Test
    void aChunkThatDoesNotMatchItsHashIsRefusedAndNotKept() throws Exception {
        String wanted = head(holder);
        namedByLog.add(wanted);
        Path requester = repository("requester");
        CodeTransferServer server = server(Long.MAX_VALUE);
        ReplicationExchange corrupting = request -> {
            byte[] answer = direct(server).exchange(request);
            if (CodeTransferCodec.decode(answer) instanceof CodeTransferMessage.ChunkData data) {
                byte[] bytes = data.bytes();
                bytes[bytes.length / 2] ^= 1;
                return CodeTransferCodec.encode(new CodeTransferMessage.ChunkData(data.index(), bytes, data.sha256()));
            }
            return answer;
        };

        CodeRemote.Refused refused = assertThrows(CodeRemote.Refused.class,
                () -> client(requester, corrupting).fetch(wanted, List.of()));

        assertEquals(CodeTransferMessage.PIECE_CORRUPT, refused.code());
        assertFalse(holds(requester, wanted));
        try (Stream<Path> files = Files.walk(requesterCoordination)) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".chunk")));
        }
    }

    @Test
    void onlyWhatTheHolderServesIsServed() throws Exception {
        Path requester = repository("requester");
        CodeTransferClient client = client(requester, server(Long.MAX_VALUE));
        String head = head(holder);
        String unnamed = TestGit.output(holder, "rev-parse", "HEAD~1");
        TestGit.run(holder, "branch", "other");
        TestGit.run(holder, "tag", "v1");

        for (String wanted : List.of("refs/heads/other", "refs/heads/main", "refs/tags/v1", "main", "HEAD~1",
                "refs/synesis/snapshots/../../heads/other", "refs/synesis/incoming/x", unnamed)) {
            assertEquals(CodeTransferMessage.NOT_SERVED,
                    assertThrows(CodeRemote.Refused.class, () -> client.fetch(wanted, List.of())).code(), wanted);
        }
        String missing = "0".repeat(40);
        namedByLog.add(missing);
        assertEquals(CodeTransferMessage.NOT_HELD,
                assertThrows(CodeRemote.Refused.class, () -> client.fetch(missing, List.of())).code());
        assertEquals(CodeTransferMessage.NOT_HELD, assertThrows(CodeRemote.Refused.class,
                () -> client.fetch("refs/synesis/prepared/cmp_absent", List.of())).code());
        assertEquals(head, client.fetch(head, List.of()));
        namedByLog.add(unnamed);
        assertEquals(unnamed, client(repository("second"), server(Long.MAX_VALUE)).fetch(unnamed, List.of()));
    }

    @Test
    void preparingAPieceDropsPiecesOlderThanADay() throws Exception {
        Path pieces = Files.createDirectories(holderCoordination.resolve(CodeTransferServer.PIECES));
        Path stale = Files.writeString(pieces.resolve("b".repeat(64) + ".bundle"), "old");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofHours(25))));
        Path fresh = Files.writeString(pieces.resolve("c".repeat(64) + ".bundle"), "new");

        client(repository("requester"), server(Long.MAX_VALUE)).fetch("HEAD", List.of());

        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh));
    }

    private CodeTransferServer server(long pieceLimitBytes) {
        return new CodeTransferServer(holder, holderCoordination, namedByLog::contains, pieceLimitBytes);
    }

    private CodeTransferClient client(Path requester, CodeTransferServer server) {
        return client(requester, direct(server));
    }

    private CodeTransferClient client(Path requester, ReplicationExchange exchange) {
        return new CodeTransferClient(exchange, PROJECT, requester, requesterCoordination);
    }

    private static ReplicationExchange direct(CodeTransferServer server) {
        return request -> CodeTransferCodec.encode(
                server.answer((CodeTransferMessage.Request) CodeTransferCodec.decode(request)));
    }

    private Path repository(String name) throws IOException {
        Path repository = Files.createDirectories(temp.resolve(name));
        TestGit.run(repository, "init", "-q", "-b", "main");
        return repository;
    }

    /** Commits a new file of incompressible bytes, so each commit adds about its size to the history. */
    private void commit(Path repository, String message, int bytes) throws IOException {
        byte[] content = new byte[bytes];
        random.nextBytes(content);
        Files.write(repository.resolve(UUID.randomUUID() + ".bin"), content);
        TestGit.run(repository, "add", "-A");
        TestGit.run(repository, "commit", "-q", "-m", message);
    }

    private static String head(Path repository) throws IOException {
        return TestGit.output(repository, "rev-parse", "HEAD");
    }

    private static boolean holds(Path repository, String commit) throws IOException {
        return GitProcessRunner.runResult(repository, "cat-file", "-e", commit + "^{commit}").exitCode() == 0;
    }

    private static boolean empty(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return true;
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.findAny().isEmpty();
        }
    }
}
