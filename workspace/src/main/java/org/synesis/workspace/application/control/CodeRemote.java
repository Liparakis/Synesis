package org.synesis.workspace.application.control;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * The Git remote a shared project may keep as a mirror of its code (ADR-0098 step 3), named by {@code code.remote}
 * in {@code replication.properties}; {@link CodeExchange} moves code over Link first and uses this after it. The
 * coordination log names commits (lane snapshots under {@code refs/synesis/snapshots/}, prepared completions under
 * {@code refs/synesis/prepared/}); the objects travel here or over Link, never in the log.
 *
 * <p>{@link #of} answers empty with settings that name no remote. The remote is never guessed, because pushing a
 * project's work to a remote nobody chose is not recoverable.</p>
 *
 * <p>Only {@code refs/synesis/*} and the control branch are ever pushed. Every command runs through
 * {@link GitProcessRunner} (no global configuration, hooks off, no signing) with its remote timeout. A remote
 * that cannot be reached, refuses the ref or times out is {@link #UNAVAILABLE} with a short reason a person can act
 * on ({@link Refused#detail()}): Git's own line, with any credentials in a URL removed, and the command to run.</p>
 */
public final class CodeRemote {

    private static final System.Logger LOG = System.getLogger(CodeRemote.class.getName());

    /** The remote could not be pushed to or fetched from, or the code could not be had over Link: try again later. */
    public static final String UNAVAILABLE = "CODE_REMOTE_UNAVAILABLE";

    private static final String SYNESIS_NAMESPACE = "refs/synesis/";
    private static final int MAX_REASON_CHARS = 200;
    private static final Pattern URL_CREDENTIALS = Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*://)[^/@\\s]+@");

    private final String name;

    private CodeRemote(String name) {
        this.name = name;
    }

    /**
     * The mirror the settings name.
     *
     * @param settings this computer's replication settings
     * @return the remote, or empty when the settings name none
     */
    static Optional<CodeRemote> of(ReplicationSettings settings) {
        return Optional.ofNullable(settings.codeRemote()).map(CodeRemote::new);
    }

    /**
     * Asks a remote for its head the way a fetch would ({@code git ls-remote}), with no prompt: what a member checks
     * when it joins, because every lane it starts fetches the leader's head through this remote first.
     *
     * @param repository a worktree of the project's clone
     * @param remoteName the remote code moves through
     * @throws Refused {@link #UNAVAILABLE} when the remote cannot be read
     */
    public static void check(Path repository, String remoteName) throws Refused {
        if (Objects.requireNonNull(remoteName, "remoteName").isBlank() || remoteName.startsWith("-")) {
            // The name can come from another runtime's answer; Git must never read it as an option.
            throw new Refused(UNAVAILABLE, "the code remote name is not usable: " + remoteName.strip());
        }
        new CodeRemote(remoteName).remote(repository, List.of("ls-remote", remoteName, "HEAD"));
    }

    /**
     * Pushes exactly the given Synesis refs, unforced, so that a commit the next log entry names is on the
     * remote before the entry is proposed.
     *
     * @param repository a worktree of the project's clone
     * @param refs       full ref names, each under {@code refs/synesis/}
     * @throws Refused {@link #UNAVAILABLE} when the push fails
     */
    void pushRefs(Path repository, String... refs) throws Refused {
        List<String> command = new ArrayList<>(List.of("push", "--no-verify", name));
        for (String ref : refs) {
            if (!ref.startsWith(SYNESIS_NAMESPACE)) {
                throw new IllegalArgumentException("only refs under " + SYNESIS_NAMESPACE + " are pushed: " + ref);
            }
            command.add(ref + ":" + ref);
        }
        remote(repository, command);
    }

    /**
     * Fetches every Synesis ref, without forcing: a local ref that differs from the remote's stays as it is, and
     * the existing checks against the log then refuse it.
     *
     * @param repository a worktree of the project's clone
     * @throws Refused {@link #UNAVAILABLE} when the fetch fails
     */
    void fetchSynesisRefs(Path repository) throws Refused {
        remote(repository, List.of("fetch", "--no-tags", "--no-write-fetch-head", name, "refs/synesis/*:refs/synesis/*"));
    }

    static boolean hasCommit(Path repository, String commit) {
        try {
            return GitProcessRunner.runResult(repository, "cat-file", "-e", commit + "^{commit}").exitCode() == 0;
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * Brings a member's control checkout up to the leader's integrated head: fetches the branch and, only when the
     * checkout holds nothing Synesis did not put there and the branch can fast-forward, fast-forwards it. This is the
     * operation the leader performs on its own checkout at every integration, so it adds no new way to change a
     * checkout. Otherwise the checkout is left alone, and a lane based on it is based on an older head, which the
     * leader still accepts while it is an ancestor of the leader's head.
     *
     * @param controlRoot the control checkout
     * @throws Refused {@link #UNAVAILABLE} when the branch cannot be fetched
     */
    void followLeader(Path controlRoot) throws Refused {
        try {
            String branch = GitProcessRunner.runOptional(controlRoot, "symbolic-ref", "--short", "-q", "HEAD");
            if (branch.isEmpty()) {
                return;
            }
            String tracking = trackingRef(branch);
            remote(controlRoot, List.of("fetch", "--no-tags", "--no-write-fetch-head", name, "+refs/heads/" + branch + ":" + tracking));
            followTo(controlRoot, GitProcessRunner.run(controlRoot, "rev-parse", tracking));
        } catch (Refused refused) {
            throw refused;
        } catch (Exception failure) {
            throw new Refused(UNAVAILABLE, reason(failure));
        }
    }

    /**
     * Fast-forwards a member's control checkout to a leader's head it now holds, only when that head is ahead of the
     * checkout's and the checkout holds nothing Synesis did not put there. Over a remote and over Link alike, this is
     * the one way a member's checkout follows.
     *
     * @param controlRoot the control checkout
     * @param leaderHead  the leader's head, present here
     * @throws Exception when Git cannot read or move the checkout
     */
    static void followTo(Path controlRoot, String leaderHead) throws Exception {
        String head = GitProcessRunner.run(controlRoot, "rev-parse", "HEAD");
        if (head.equals(leaderHead)) {
            return;
        }
        if (!GitProcessRunner.isAncestor(controlRoot, head, leaderHead)) {
            LOG.log(System.Logger.Level.INFO, "The checkout does not fast-forward to {0}: its head {1} is not an ancestor of it.", leaderHead, head);
            return;
        }
        List<String> foreign = ControlBranchAdvancementService.foreignChanges(controlRoot);
        if (!foreign.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "The checkout does not fast-forward to {0}: it holds changes Synesis did not make: {1}", leaderHead, foreign);
            return;
        }
        GitProcessRunner.run(controlRoot, "merge", "--ff-only", leaderHead);
    }

    /**
     * Publishes the leader's control branch when the remote does not hold its head yet. It asks no network
     * question: the remote-tracking ref a push or fetch leaves is the last head the remote is known to hold, so a
     * push that failed leaves it behind and the next call pushes again.
     *
     * @param controlRoot the control checkout
     * @throws Refused {@link #UNAVAILABLE} when the push fails
     */
    void publishControlBranch(Path controlRoot) throws Refused {
        try {
            String branch = GitProcessRunner.runOptional(controlRoot, "symbolic-ref", "--short", "-q", "HEAD");
            if (branch.isEmpty()) {
                return;
            }
            String head = GitProcessRunner.run(controlRoot, "rev-parse", "HEAD");
            if (head.equals(GitProcessRunner.runOptional(controlRoot, "rev-parse", "--verify", "-q", trackingRef(branch)))) {
                return;
            }
            remote(controlRoot, List.of("push", "--no-verify", name, head + ":refs/heads/" + branch));
            GitProcessRunner.run(controlRoot, "update-ref", trackingRef(branch), head);
        } catch (Refused refused) {
            throw refused;
        } catch (Exception failure) {
            throw new Refused(UNAVAILABLE, reason(failure));
        }
    }

    private String trackingRef(String branch) {
        return "refs/remotes/" + name + "/" + branch;
    }

    private void remote(Path repository, List<String> command) throws Refused {
        try {
            GitProcessRunner.runRemote(repository, command.toArray(String[]::new));
        } catch (IOException failure) {
            // Agents cannot answer a sign-in prompt, so the person is told how to give Git the credential once. A
            // refused push is as likely a rejected ref as a missing sign-in, so it is only pointed at the remote.
            String next = "push".equals(command.getFirst())
                    ? ". Check in a terminal in this project's folder that `git push --dry-run " + name + "` works; then try again."
                    : ". Run `git fetch " + name + "` once in a terminal in this project's folder and sign in if Git asks, so that it can fetch without asking; then try again.";
            throw new Refused(UNAVAILABLE, reason(failure) + next);
        }
    }

    /**
     * Git's own explanation: its first {@code fatal:} or {@code error:} line, which says what refused (the lines after
     * it are generic advice), else its last line of its own. Lines the remote server wrote ({@code remote:}) are not
     * Git's and are left out. Credentials in a URL are removed, because the reason is shown to agents and stored in
     * the session binding.
     */
    private static String reason(Exception failure) {
        String message = String.valueOf(failure.getMessage());
        int output = message.indexOf("output=");
        String text = (output >= 0 ? message.substring(output + "output=".length()) : message).strip();
        List<String> lines = text.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("remote:")).toList();
        text = lines.stream().filter(line -> line.startsWith("fatal:") || line.startsWith("error:")).findFirst()
                .orElse(lines.isEmpty() ? text : lines.getLast());
        text = URL_CREDENTIALS.matcher(text).replaceAll("$1");
        return text.length() > MAX_REASON_CHARS ? text.substring(0, MAX_REASON_CHARS) : text;
    }

    /**
     * A refusal to exchange code, with the code a caller reports and a short reason.
     */
    public static final class Refused extends IOException {

        /** Serialization version. */
        private static final long serialVersionUID = 1L;

        /** The refusal code. */
        private final String code;

        /** What was missing, or Git's reason and what to run. */
        private final String detail;

        /**
         * Creates a refusal.
         *
         * @param code   the refusal code
         * @param reason Git's own short explanation, or what was missing
         */
        Refused(String code, String reason) {
            super(code + ": " + reason);
            this.code = code;
            this.detail = reason;
        }

        /**
         * Returns what a person reads: what was missing, or Git's reason with no credentials and the command to run.
         *
         * @return the detail
         */
        public String detail() {
            return detail;
        }

        /**
         * Returns the refusal code.
         *
         * @return {@link CodeRemote#UNAVAILABLE}, a role-file code, or a
         *         {@link org.synesis.coordination.persistence.CodeTransferMessage} code from a Link transfer
         */
        public String code() {
            return code;
        }

        /**
         * Tells whether the refusal passes with time: no computer or mirror that holds the code answered now. Any other
         * code (role settings that cannot be read) needs a person.
         *
         * @return true for {@link CodeRemote#UNAVAILABLE}
         */
        public boolean retryable() {
            return UNAVAILABLE.equals(code);
        }
    }
}
