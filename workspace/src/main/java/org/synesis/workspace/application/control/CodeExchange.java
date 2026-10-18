package org.synesis.workspace.application.control;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.persistence.CodeTransferCodec;
import org.synesis.coordination.persistence.CodeTransferMessage;
import org.synesis.coordination.persistence.LoopbackExchange;
import org.synesis.coordination.persistence.ReplicationExchange;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * How a process of a shared project gets and gives the code its log names (ADR-0098 step 3, "Code over Link"). Link
 * is the path that always exists: the process asks its computer's coordination server, which holds the Link
 * sessions ({@link CodeOverLink}), to bring a commit here or to bring the checked-out branch up to the leader's. A
 * Git remote named by {@code code.remote} is a mirror: fetched from after Link, and given every hand-in and the
 * leader's branch as before.
 *
 * <p>{@link #of} is the gate. A coordination folder with no replication settings is a single computer: it answers
 * empty, and callers move no code, exactly as before. A hand-in pushes nothing over Link: the computer that made a
 * commit keeps its refs and serves it to the computers that ask.</p>
 *
 * <p>Every failure to get code is a {@link CodeRemote.Refused} with {@link CodeRemote#UNAVAILABLE}, which every
 * caller already answers with "try again": a computer that is not connected, an answer that did not come, and a
 * commit the other computer does not hold or its log does not name yet all pass with time.</p>
 */
public final class CodeExchange {

    private static final ConcurrentHashMap<Path, ReplicationExchange> SERVERS = new ConcurrentHashMap<>();

    private final Path coordination;
    private final UUID project;
    private final boolean member;
    private final Optional<CodeRemote> mirror;

    private CodeExchange(Path coordination, UUID project, ReplicationSettings settings) {
        this.coordination = coordination;
        this.project = project;
        this.member = settings.isMember();
        this.mirror = CodeRemote.of(settings);
    }

    /**
     * Reads whether this computer exchanges code with others.
     *
     * @param coordinationDirectory the folder holding {@code replication.properties}
     * @param project               the project
     * @return empty for a single computer (no settings file); otherwise the exchange
     * @throws CodeRemote.Refused the role file's own code when it cannot be read
     */
    public static Optional<CodeExchange> of(Path coordinationDirectory, UUID project) throws CodeRemote.Refused {
        Objects.requireNonNull(coordinationDirectory, "coordinationDirectory");
        Objects.requireNonNull(project, "project");
        try {
            return ReplicationSettings.read(coordinationDirectory).map(settings -> new CodeExchange(coordinationDirectory, project, settings));
        } catch (CoordinationException invalid) {
            throw new CodeRemote.Refused(invalid.code(), "replication settings cannot be read");
        }
    }

    /**
     * Makes this process exchange code through the given answerer instead of its coordination server's {@code /code}
     * route: the coordination server registers its own code side, so its merge pass asks it without a loopback request,
     * and a test registers a computer it composes. The answerer takes encoded {@link CodeTransferMessage.Fetch}es.
     *
     * @param coordinationDirectory the folder holding {@code replication.properties}
     * @param server                the answerer, or null to go back to the loopback route
     * @throws IOException when the folder cannot be resolved
     */
    public static void register(Path coordinationDirectory, ReplicationExchange server) throws IOException {
        Path key = key(coordinationDirectory);
        if (server == null) {
            SERVERS.remove(key);
        } else {
            SERVERS.put(key, server);
        }
    }

    /**
     * Gives the mirror the refs of a commit an entry is about to name, when there is a mirror. Over Link nothing
     * moves: this computer keeps the refs and serves the commit to whoever asks.
     *
     * @param repository a worktree of the project's clone
     * @param refs       full ref names, each under {@code refs/synesis/}
     * @throws CodeRemote.Refused {@link CodeRemote#UNAVAILABLE} when the mirror refuses the push
     */
    public void handIn(Path repository, String... refs) throws CodeRemote.Refused {
        if (mirror.isPresent()) {
            mirror.get().pushRefs(repository, refs);
        }
    }

    /**
     * Makes sure a commit the log names is in this computer's repository before a caller reads it, with the snapshot
     * refs the log gives it: over Link, then from the mirror. Nothing is done when the commit is here.
     *
     * @param repository a worktree of the project's clone
     * @param commit     the commit the log names
     * @throws CodeRemote.Refused {@link CodeRemote#UNAVAILABLE} when it cannot be had now
     */
    public void ensureCommit(Path repository, String commit) throws CodeRemote.Refused {
        if (!CodeRemote.hasCommit(repository, commit)) {
            bring(repository, commit, commit);
        }
    }

    /**
     * {@link #ensureCommit(Path, String)} on a computer that exchanges code; nothing on a single computer.
     *
     * @param coordinationDirectory the folder holding {@code replication.properties}
     * @param project               the project
     * @param repository            a worktree of the project's clone
     * @param commit                the commit the log names
     * @throws CodeRemote.Refused {@link CodeRemote#UNAVAILABLE} when it cannot be had now, or the role file's code
     */
    public static void ensureCommit(Path coordinationDirectory, UUID project, Path repository, String commit) throws CodeRemote.Refused {
        Optional<CodeExchange> exchange = of(coordinationDirectory, project);
        if (exchange.isPresent()) {
            exchange.get().ensureCommit(repository, commit);
        }
    }

    /**
     * What a member does before it gives out a new lane: {@link #followLeader}. Nothing happens on a single computer
     * or on the leader.
     *
     * @param coordinationDirectory the folder holding {@code replication.properties}
     * @param project               the project
     * @param controlRoot           the control checkout
     * @return true when this is a member and its checkout was brought up to date
     * @throws CodeRemote.Refused {@link CodeRemote#UNAVAILABLE} when the leader's head cannot be had now, or the role
     *                            file's code
     */
    public static boolean followLeaderIfMember(Path coordinationDirectory, UUID project, Path controlRoot) throws CodeRemote.Refused {
        Optional<CodeExchange> exchange = of(coordinationDirectory, project);
        return exchange.isPresent() && exchange.get().followLeader(controlRoot);
    }

    /**
     * Makes sure each snapshot ref the log names is here and names its commit: what the leader's merge pass needs
     * before it decides anything. A ref that is here already is left as it is, for the pass's checks.
     *
     * @param repository the control checkout
     * @param refs       each snapshot ref with the commit the log gives it
     * @throws CodeRemote.Refused {@link CodeRemote#UNAVAILABLE} when one cannot be had now
     */
    public void ensureSnapshots(Path repository, Map<String, String> refs) throws CodeRemote.Refused {
        for (Map.Entry<String, String> ref : refs.entrySet()) {
            String held = refHead(repository, ref.getKey());
            if (held.isEmpty()) {
                bring(repository, ref.getValue(), ref.getKey());
            }
        }
    }

    /**
     * Brings a member's checked-out branch up to the leader's head, over Link, else from the mirror; nothing on the
     * leader, whose branch the integration pass moves.
     *
     * @param controlRoot the control checkout
     * @return true on a member, whose checkout was followed
     * @throws CodeRemote.Refused {@link CodeRemote#UNAVAILABLE} when the leader's head cannot be had now
     */
    public boolean followLeader(Path controlRoot) throws CodeRemote.Refused {
        if (!member) {
            return false;
        }
        try {
            ask(new CodeTransferMessage.Fetch(project, "HEAD"));
        } catch (CodeRemote.Refused overLink) {
            if (mirror.isEmpty()) {
                throw overLink;
            }
            mirror.get().followLeader(controlRoot);
        }
        return true;
    }

    /**
     * Gives the mirror the leader's control branch once a pass has run, when there is a mirror. Over Link nothing is
     * sent: the {@code CONTROL_BRANCH_ADVANCED} entry tells the members, which then fetch the head.
     *
     * @param controlRoot the control checkout
     * @throws CodeRemote.Refused {@link CodeRemote#UNAVAILABLE} when the push fails
     */
    public void publishControlBranch(Path controlRoot) throws CodeRemote.Refused {
        if (mirror.isPresent()) {
            mirror.get().publishControlBranch(controlRoot);
        }
    }

    /** Asks over Link, then the mirror, until {@code check} (a commit, or a ref) resolves here. */
    private void bring(Path repository, String commit, String check) throws CodeRemote.Refused {
        CodeRemote.Refused overLink;
        try {
            ask(new CodeTransferMessage.Fetch(project, commit));
            return;
        } catch (CodeRemote.Refused refused) {
            overLink = refused;
        }
        if (mirror.isEmpty()) {
            throw overLink;
        }
        mirror.get().fetchSynesisRefs(repository);
        if (refHead(repository, check).isEmpty()) {
            throw overLink;
        }
    }

    private void ask(CodeTransferMessage.Fetch fetch) throws CodeRemote.Refused {
        CodeTransferMessage answer;
        try {
            answer = CodeTransferCodec.decode(server().exchange(CodeTransferCodec.encode(fetch)));
        } catch (IOException unanswered) {
            throw new CodeRemote.Refused(CodeRemote.UNAVAILABLE, "this computer's coordination server did not bring the code: "
                    + unanswered.getMessage() + ". Start Synesis on this computer if it is not running; then try again.");
        }
        if (answer instanceof CodeTransferMessage.Unavailable unavailable) {
            throw new CodeRemote.Refused(CodeRemote.UNAVAILABLE, "the code could not be had over Link yet (" + unavailable.code()
                    + "): the computer that holds it may not be connected, or may not have it yet. Try again.");
        }
        if (!(answer instanceof CodeTransferMessage.Fetched)) {
            throw new CodeRemote.Refused(CodeRemote.UNAVAILABLE, "this computer's coordination server answered a code request with another kind of message");
        }
    }

    private ReplicationExchange server() throws IOException {
        ReplicationExchange registered = SERVERS.get(key(coordination));
        return registered != null ? registered : LoopbackExchange.forCode(coordination);
    }

    private static String refHead(Path repository, String revision) {
        try {
            return GitProcessRunner.runOptional(repository, "rev-parse", "--verify", "-q", revision + "^{commit}");
        } catch (IOException unreadable) {
            return "";
        }
    }

    private static Path key(Path coordinationDirectory) throws IOException {
        Files.createDirectories(Objects.requireNonNull(coordinationDirectory, "coordinationDirectory"));
        return coordinationDirectory.toRealPath();
    }
}
