package org.synesis.workspace.application.control;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.persistence.CodeTransferCodec;
import org.synesis.coordination.persistence.CodeTransferMessage;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationExchange;
import org.synesis.workspace.lifecycle.RepositoryPrivateStateService;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Code over Link on one computer's coordination server (ADR-0098 step 3), which alone holds the project's Link
 * sessions. It serves the computers of the project ({@link #serve}), moves code here for this computer's processes
 * ({@link #fetch}, the loopback {@code /code} route), and keeps a member's checked-out branch on the leader's
 * ({@link #followMain}).
 *
 * <p>Which computers to ask comes from {@link Peers}: a member asks its leader; the leader asks the member whose
 * entry names the commit, then every other connected member. A commit is asked for only when an entry of this
 * computer's log names it, and a snapshot ref is set only to the commit the entry naming the ref gives, so nothing
 * a peer sends becomes a ref the log does not name.</p>
 *
 * <p>Transfers run one at a time, so two of them never share the piece files of {@link CodeTransferClient}; each
 * gives up after {@value #TRANSFER_MINUTES} minutes, the bundle bound, and asks again after an exchange that got no
 * answer: a holder that is still preparing a large piece answers the same want with it once it is ready. No lock of
 * the log is held.</p>
 */
public final class CodeOverLink implements AutoCloseable {

    /** No computer of the project that may hold the code is connected. */
    public static final String PEER_UNREACHABLE = "CODE_PEER_UNREACHABLE";

    /** A folder with no commit yet holds files of its own, which the first checkout of the leader's branch would overwrite. */
    public static final String FOLDER_NOT_EMPTY = "CODE_FOLDER_NOT_EMPTY";

    /** What Synesis itself writes into a project folder; nothing else may be in a folder that gets its first checkout. */
    private static final Set<String> SYNESIS_FILES = Set.of(".git", ".synesis", ".codex", ".claude", ".mcp.json");

    private static final int TRANSFER_MINUTES = 10;
    private static final Duration TRANSFER_BOUND = Duration.ofMinutes(TRANSFER_MINUTES);
    private static final long RETRY_PAUSE_MILLIS = 1_000;
    // Link takes four streams per session at once; more serving threads would only queue.
    private static final int SERVING_THREADS = 4;
    /** How long {@link #betweenPasses} waits for a running pass, and how often it looks. */
    private static final java.time.Duration PASS_WAIT = java.time.Duration.ofSeconds(30);
    private static final java.time.Duration PASS_POLL = java.time.Duration.ofMillis(50);
    private static final System.Logger LOG = System.getLogger(CodeOverLink.class.getName());

    /**
     * The computers this one exchanges code with now, as its replication side sees them.
     */
    public interface Peers {

        /**
         * The project's computers with a live session now.
         *
         * @return each one's node and one request and one answer with it
         */
        Map<String, ReplicationExchange> live();

        /**
         * Tells whether this computer follows a leader, which is then its one peer.
         *
         * @return true on a member
         */
        boolean member();
    }

    private final Path repository;
    private final Path coordination;
    private final UUID project;
    private final Peers peers;
    private final CodeTransferServer holder;
    private final ExecutorService serving = Executors.newFixedThreadPool(SERVING_THREADS, daemon("synesis-code-serve"));
    private final ExecutorService following = Executors.newSingleThreadExecutor(daemon("synesis-code-follow"));
    private final AtomicBoolean followQueued = new AtomicBoolean();
    private volatile java.util.function.Consumer<String> diverged = head -> {
    };
    private final ConcurrentHashMap<ByteBuffer, CompletableFuture<byte[]>> answering = new ConcurrentHashMap<>();
    private final Object transfers = new Object();
    private long namedAtEvents = -1;
    private LogNamedCommits named;

    /**
     * Creates the code side of one project's coordination server.
     *
     * @param repository   the project's control checkout, whose objects are served and which receives code
     * @param coordination the project's coordination folder
     * @param project      the project
     * @param peers        the computers it exchanges code with
     */
    public CodeOverLink(Path repository, Path coordination, UUID project, Peers peers) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.coordination = Objects.requireNonNull(coordination, "coordination");
        this.project = Objects.requireNonNull(project, "project");
        this.peers = Objects.requireNonNull(peers, "peers");
        this.holder = new CodeTransferServer(repository, coordination, commit -> named().names(commit));
    }

    /**
     * Answers one {@code SCX1} request of a computer of the project; the caller has checked that the sender is one.
     * Preparing a piece can take longer than one Link exchange waits, so the work runs off the Link thread, and a
     * request that arrives again while its answer is being made waits for that answer instead of making another.
     *
     * @param payload the request
     * @return the encoded answer
     */
    public CompletionStage<byte[]> serve(byte[] payload) {
        CodeTransferMessage.Request request;
        try {
            if (!(CodeTransferCodec.decode(payload) instanceof CodeTransferMessage.Request asked) || !project.equals(projectOf(asked))) {
                return CompletableFuture.completedFuture(unavailable(CodeTransferMessage.NOT_SERVED));
            }
            request = asked;
        } catch (CoordinationException malformed) {
            return CompletableFuture.completedFuture(unavailable(malformed.code()));
        }
        if (!Files.exists(repository.resolve(".git"))) {
            // A computer that joined into an empty folder holds nothing until its first checkout.
            return CompletableFuture.completedFuture(unavailable(CodeTransferMessage.NOT_HELD));
        }
        ByteBuffer key = ByteBuffer.wrap(payload.clone());
        CompletableFuture<byte[]> mine = new CompletableFuture<>();
        CompletableFuture<byte[]> earlier = answering.putIfAbsent(key, mine);
        if (earlier != null) {
            return earlier;
        }
        try {
            serving.execute(() -> {
                byte[] answer;
                try {
                    answer = CodeTransferCodec.encode(holder.answer(request));
                } catch (RuntimeException failed) {
                    LOG.log(System.Logger.Level.WARNING, "A code request of another computer could not be answered.", failed);
                    answer = unavailable(CodeTransferMessage.PIECE_FAILED);
                }
                // Removed before it completes, so a request that comes after the answer is answered afresh.
                answering.remove(key, mine);
                mine.complete(answer);
            });
        } catch (RejectedExecutionException closed) {
            answering.remove(key, mine);
            mine.completeExceptionally(closed);
        }
        return mine;
    }

    private static UUID projectOf(CodeTransferMessage.Request request) {
        return switch (request) {
            case CodeTransferMessage.Want want -> want.project();
            case CodeTransferMessage.Chunk chunk -> chunk.project();
        };
    }

    /**
     * Answers one {@link CodeTransferMessage.Fetch} of a process of this computer, once the code is here or cannot be
     * had. It can wait for a whole transfer.
     *
     * @param request the encoded request
     * @return the encoded {@link CodeTransferMessage.Fetched} or {@link CodeTransferMessage.Unavailable}; never throws
     */
    public byte[] fetch(byte[] request) {
        try {
            if (!(CodeTransferCodec.decode(request) instanceof CodeTransferMessage.Fetch fetch) || !project.equals(fetch.project())) {
                return unavailable(CodeTransferMessage.NOT_SERVED);
            }
            return CodeTransferCodec.encode("HEAD".equals(fetch.wanted()) ? followMain() : present(fetch.wanted()));
        } catch (CoordinationException malformed) {
            return unavailable(malformed.code());
        } catch (IOException | RuntimeException failed) {
            LOG.log(System.Logger.Level.WARNING, "Code could not be fetched for a process of this computer.", failed);
            return unavailable(CodeTransferMessage.PIECE_FAILED);
        }
    }

    /**
     * What a computer does after each pull, on its own thread, returning at once. It is level-triggered: the pull
     * that brought the entries naming a head is not always the one after which its commit can be had or this
     * computer's own pass has finished, so every pull runs it again until this copy has met every head; a head
     * already met costs only a comparison. A member with no commit yet checks the
     * owner's branch out ({@link #followMain}); otherwise every head another computer's pass advanced to that this copy
     * lacks is fetched and, when this copy is behind it and the audit passes, taken by fast-forward (ADR-0098 step 5,
     * {@link MainMeeting}). Requests made while one is waiting to run count once.
     */
    public void mainMayHaveMoved() {
        if (!followQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            following.execute(() -> {
                followQueued.set(false);
                try {
                    followAfterPull();
                } catch (IOException | RuntimeException failed) {
                    LOG.log(System.Logger.Level.WARNING, "Following the leader's branch failed; the next pull tries again.", failed);
                }
            });
        } catch (RejectedExecutionException closed) {
            followQueued.set(false);
        }
    }

    /**
     * Moves code only when it is missing: the first checkout of a member, or a head another computer advanced to that
     * this computer does not hold. A head that is here already is only compared, so a branch that cannot fast-forward
     * (a person's change, a detached checkout) costs nothing on the next pull.
     */
    private void followAfterPull() throws IOException {
        String head = head();
        if (head.isEmpty()) {
            if (peers.member() && !(followMain() instanceof CodeTransferMessage.Fetched)) {
                LOG.log(System.Logger.Level.INFO, "The leader's branch could not be followed yet; the next pull tries again.");
            }
            return;
        }
        List<PredictionEvent> events;
        try {
            events = new PredictionEventStore(coordination, project).events();
        } catch (java.security.GeneralSecurityException unreadable) {
            throw new IOException("the log could not be verified", unreadable);
        }
        for (String other : MainMeeting.lastHeads(events).values()) {
            meet(other);
        }
    }

    /**
     * Takes another computer's head when this copy is behind it and the audit passes; leaves a diverged head to the
     * re-merge of the higher node (designer item 5).
     */
    private void meet(String other) throws IOException {
        String own = head();
        if (!CodeRemote.hasCommit(repository, other) && !(present(other) instanceof CodeTransferMessage.Fetched)) {
            LOG.log(System.Logger.Level.INFO, "Another computer's main could not be fetched yet; the next pull tries again.");
            return;
        }
        MainMeeting.Relation relation = MainMeeting.relate(repository, own, other);
        if (relation == MainMeeting.Relation.DIVERGED) {
            diverged.accept(other);
            return;
        }
        if (relation != MainMeeting.Relation.BEHIND) {
            return;
        }
        List<String> problems;
        try {
            problems = MainMeeting.audit(repository, new PredictionEventStore(coordination, project), own, other);
        } catch (java.security.GeneralSecurityException unreadable) {
            throw new IOException("the log could not be verified", unreadable);
        }
        if (!problems.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "Another computer's main is not taken: {0}", problems);
            return;
        }
        // Not while this computer's own pass moves its branch: the pass holds the integration lock.
        try (var onePass = betweenPasses()) {
            if (onePass == null) {
                LOG.log(System.Logger.Level.INFO, "Another computer's main is not taken while this computer's own pass runs; the next pull tries again.");
                return;
            }
            synchronized (transfers) {
                followBranch(other);
            }
        }
    }

    /**
     * Takes the integration lock between two passes. A single try is not enough: passes run back to back (at an
     * interval and after every entry), so one try per pull found a pass running at every pull for minutes under load,
     * and a head this copy was behind was never taken (the flake of {@code OverlapAndCombineScenarioTest}, ADR-0098
     * step 5 item 46). This runs on the following thread, so it may wait; a pass that outlasts the wait is left to the
     * next pull.
     *
     * @return the held lock, or {@code null} when a pass held it for the whole wait or the thread was interrupted
     * @throws IOException when the lock file cannot be opened
     */
    private org.synesis.coordination.persistence.ProjectIntegrationLock betweenPasses() throws IOException {
        long giveUp = System.nanoTime() + PASS_WAIT.toNanos();
        org.synesis.coordination.persistence.ProjectIntegrationLock held;
        while ((held = org.synesis.coordination.persistence.ProjectIntegrationLock.tryAcquire(coordination)) == null && System.nanoTime() < giveUp) {
            try {
                Thread.sleep(PASS_POLL.toMillis());
            } catch (InterruptedException closing) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return held;
    }

    /**
     * Tells a listener of a head another computer advanced to that has diverged from this copy, so the re-merge of
     * the higher node can start (ADR-0098 step 5, item 4).
     *
     * @param listener told the diverged head, on the following thread; it must return at once
     */
    public void whenDiverged(java.util.function.Consumer<String> listener) {
        diverged = Objects.requireNonNull(listener, "listener");
    }

    /** The checked-out commit, or empty when there is no repository or no commit yet. */
    private String head() throws IOException {
        return Files.exists(repository.resolve(".git")) ? GitProcessRunner.runOptional(repository, "rev-parse", "--verify", "-q", "HEAD") : "";
    }

    /**
     * Brings this member's checked-out branch up to the leader's head: fetches it over Link and fast-forwards by
     * {@link CodeRemote#followTo}'s rule. A computer that has no repository yet, which is how a computer that joined
     * into an empty folder starts, gets the leader's branch checked out under the leader's branch name. On the leader
     * it only reports its own head.
     *
     * @return {@link CodeTransferMessage.Fetched} with the head afterwards, or why not
     * @throws IOException when Git cannot be run
     */
    CodeTransferMessage followMain() throws IOException {
        if (!peers.member()) {
            String head = GitProcessRunner.runOptional(repository, "rev-parse", "--verify", "-q", "HEAD");
            return head.isEmpty() ? new CodeTransferMessage.Unavailable(CodeTransferMessage.NOT_HELD) : new CodeTransferMessage.Fetched(head);
        }
        synchronized (transfers) {
            String head = head();
            if (head.isEmpty() && !onlySynesisFiles()) {
                LOG.log(System.Logger.Level.WARNING, "The project folder has no commit but holds files of its own; the leader's branch is not checked out over them.");
                return new CodeTransferMessage.Unavailable(FOLDER_NOT_EMPTY);
            }
            // A folder of its own with no .git: Git run there would find a repository of an enclosing folder.
            if (!Files.exists(repository.resolve(".git"))) {
                GitProcessRunner.run(repository, "init", "-q");
            }
            Outcome<CodeTransferClient.Head> leader = transfer(new ArrayList<>(peers.live().keySet()),
                    client -> client.fetchHead(head.isEmpty() ? List.of() : List.of(head)));
            if (leader.refusal() != null) {
                return leader.refusal();
            }
            if (head.isEmpty()) {
                checkOut(leader.value());
            } else {
                followBranch(leader.value().commit());
            }
            return new CodeTransferMessage.Fetched(GitProcessRunner.run(repository, "rev-parse", "HEAD"));
        }
    }

    /** Fast-forwards the checked-out branch to a leader's head held here, by {@link CodeRemote#followTo}'s rule. */
    private void followBranch(String leaderHead) throws IOException {
        if (GitProcessRunner.runOptional(repository, "symbolic-ref", "-q", "HEAD").isEmpty()) {
            // A detached checkout is a person's; only a branch follows.
            LOG.log(System.Logger.Level.INFO, "The checkout is detached, so it does not follow another computer's main.");
            return;
        }
        try {
            CodeRemote.followTo(repository, leaderHead);
        } catch (IOException failed) {
            throw failed;
        } catch (Exception failed) {
            throw new IOException("the leader's branch could not be followed", failed);
        }
    }

    private boolean onlySynesisFiles() throws IOException {
        try (var children = Files.list(repository)) {
            return children.allMatch(child -> SYNESIS_FILES.contains(child.getFileName().toString()));
        }
    }

    /**
     * Checks the leader's branch out in a repository that has no commit yet, in a folder that holds only what Synesis
     * wrote there ({@link #SYNESIS_FILES}). That file ({@code .synesis/project.json}) is in the way of the same file
     * of the project, so the checkout overwrites it, and nothing else is there to overwrite. Synesis writes no
     * {@code AGENTS.md} (ADR-0103 decision 9), so one in the folder is the user's and keeps the folder from counting
     * as holding only Synesis's files; joining initializes the replica first, which has already removed the section
     * an earlier version wrote there. The Git exclusions of
     * Synesis's private folders are written now, because the folder had no repository to hold them before.
     */
    private void checkOut(CodeTransferClient.Head leader) throws IOException {
        if (leader.branch().isEmpty() || GitProcessRunner.runResult(repository, "check-ref-format", "--branch", leader.branch()).exitCode() != 0) {
            throw new IOException("the leader's branch has no name this computer can give its own");
        }
        GitProcessRunner.runBundle(repository, "checkout", "-q", "-f", "-B", leader.branch(), leader.commit());
        RepositoryPrivateStateService.ensure(repository);
    }

    /** Makes a commit the log names present, with the snapshot and prepared refs the log gives it. */
    private CodeTransferMessage present(String commit) throws IOException {
        LogNamedCommits log = named();
        if (!log.names(commit)) {
            return new CodeTransferMessage.Unavailable(CodeTransferMessage.NOT_SERVED);
        }
        if (!CodeRemote.hasCommit(repository, commit)) {
            // A snapshot is asked for by its ref, which its author serves before its own copy of the log has the entry.
            String wanted = log.refs(commit).stream().filter(CodeTransferServer::isServedRef).findFirst().orElse(commit);
            synchronized (transfers) {
                Outcome<String> fetched = transfer(holdersFirst(log, commit), client -> client.fetch(wanted, haves()));
                if (fetched.refusal() != null) {
                    return fetched.refusal();
                }
                if (!fetched.value().equals(commit)) {
                    return new CodeTransferMessage.Unavailable(CodeTransferMessage.PIECE_CORRUPT);
                }
            }
        }
        for (String ref : log.refs(commit)) {
            if (CodeTransferServer.isServedRef(ref)) {
                // Created only where absent (the zero ID as the old value): a ref that names another commit is left for
                // the readers' checks to refuse.
                GitProcessRunner.runOptional(repository, "update-ref", ref, commit, "0".repeat(commit.length()));
            }
        }
        return new CodeTransferMessage.Fetched(commit);
    }

    /** The connected computers to ask for a commit: on the leader, its writers first. */
    private List<String> holdersFirst(LogNamedCommits log, String commit) {
        Map<String, ReplicationExchange> live = peers.live();
        List<String> order = new ArrayList<>();
        // The computers whose entries name the commit made it (ADR-0098 step 5: any computer may hold a main).
        log.writers(commit).stream().filter(live::containsKey).forEach(order::add);
        live.keySet().stream().filter(node -> !order.contains(node)).sorted().forEach(order::add);
        return order;
    }

    private List<String> haves() throws IOException {
        String head = GitProcessRunner.runOptional(repository, "rev-parse", "--verify", "-q", "HEAD");
        return head.isEmpty() ? List.of() : List.of(head);
    }

    @FunctionalInterface
    private interface Attempt<T> {
        T run(CodeTransferClient client) throws IOException;
    }

    /** A transfer's result, or the refusal to answer with. */
    private record Outcome<T>(T value, CodeTransferMessage.Unavailable refusal) {
    }

    /**
     * Runs one transfer against each node in turn until one succeeds. An exchange that got no answer is asked again
     * until the bound; a peer's refusal moves on to the next node, except that a piece the peer no longer keeps is
     * asked for once more, whole.
     */
    private <T> Outcome<T> transfer(List<String> nodes, Attempt<T> attempt) {
        long deadline = System.nanoTime() + TRANSFER_BOUND.toNanos();
        String last = PEER_UNREACHABLE;
        for (String node : nodes) {
            boolean pieceAskedAgain = false;
            while (System.nanoTime() < deadline) {
                ReplicationExchange peer = peers.live().get(node);
                if (peer == null) {
                    break;
                }
                try {
                    return new Outcome<>(attempt.run(new CodeTransferClient(peer, project, repository, coordination)), null);
                } catch (CodeRemote.Refused refused) {
                    last = refused.code();
                    if (pieceAskedAgain || !CodeTransferMessage.PIECE_UNKNOWN.equals(refused.code())) {
                        break;
                    }
                    pieceAskedAgain = true;
                } catch (ConnectException notSent) {
                    last = PEER_UNREACHABLE;
                    break;
                } catch (IOException noAnswer) {
                    last = PEER_UNREACHABLE;
                    if (!pause()) {
                        break;
                    }
                }
            }
        }
        return new Outcome<>(null, new CodeTransferMessage.Unavailable(last));
    }

    private static boolean pause() {
        try {
            Thread.sleep(RETRY_PAUSE_MILLIS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** What this computer's log names, read again only when the log has new entries. */
    private synchronized LogNamedCommits named() {
        long events = PredictionEventStore.recordedEvents(coordination);
        if (named == null || events != namedAtEvents) {
            try {
                named = LogNamedCommits.of(new PredictionEventStore(coordination, project).events());
                namedAtEvents = events;
            } catch (IOException | java.security.GeneralSecurityException unreadable) {
                // Nothing is served by commit ID until the log can be read.
                return LogNamedCommits.of(List.of());
            }
        }
        return named;
    }

    private static byte[] unavailable(String code) {
        return CodeTransferCodec.encode(new CodeTransferMessage.Unavailable(code.length() > CodeTransferMessage.MAX_CODE_BYTES
                ? code.substring(0, CodeTransferMessage.MAX_CODE_BYTES) : code));
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Stops serving and following; a transfer under way ends at its next exchange.
     */
    @Override
    public void close() {
        serving.shutdownNow();
        following.shutdownNow();
    }
}
