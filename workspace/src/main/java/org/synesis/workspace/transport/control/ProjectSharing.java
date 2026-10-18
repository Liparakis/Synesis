package org.synesis.workspace.transport.control;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.coordination.persistence.ReplicationPeers;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.link.protocol.ProjectInvitation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectIdentity;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * What a person's computer does to take part in a shared project when it makes or opens a project link (ADR-0098,
 * "Joining, revised"): say which Git remote mirrors the code, if any, find or prepare the folder that holds
 * the project, follow the inviter, and add an accepted joiner. It is the same work as {@code project share},
 * {@code project follow} and {@code project add-member}, which stay for scripts, decided once and in the same way.
 *
 * <p>Every refusal is a {@link Refusal} with a stable code and writes nothing. Nothing here clones: an empty folder is
 * made a copy of the project that holds no code yet, and the coordination server checks the leader's branch out in it
 * over Link once the owner has accepted this computer (ADR-0098 step 3, "Joining clones over Link").
 */
public final class ProjectSharing {

    /** The name given to the remote added for an invitation's code remote URL. */
    public static final String ADDED_REMOTE = "synesis";

    private static final Pattern REMOTE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private ProjectSharing() {
    }

    /**
     * Why a sharing step is refused.
     */
    public static final class Refusal extends Exception {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        /** The stable reason. */
        private final String code;

        /**
         * Creates a refusal.
         *
         * @param code stable reason, for example {@code ALREADY_A_MEMBER}
         */
        public Refusal(String code) {
            super(code);
            this.code = code;
        }

        /**
         * Returns why the step was refused.
         *
         * @return the stable reason
         */
        public String code() {
            return code;
        }
    }

    /**
     * What an invitation of this project says about code.
     *
     * @param remoteName the remote code moves through, or {@code null} when none can be named
     * @param url        that remote's URL without credentials, or {@code null} when none can be written into a link
     * @param reason     why there is no URL ({@code NO_GIT_REMOTE}, {@code SEVERAL_GIT_REMOTES}, {@code CODE_REMOTE_MISSING},
     *                   {@code URL_NOT_SHAREABLE}), or {@code null} when there is one
     */
    public record Leader(String remoteName, String url, String reason) {
    }

    /**
     * Tells whether this computer follows a leader.
     *
     * @param coordination the project's coordination folder
     * @return true when its role file makes it a member
     * @throws Refusal the role file's own code when it cannot be read
     */
    public static boolean isMember(Path coordination) throws Refusal {
        return read(coordination).filter(ReplicationSettings::isMember).isPresent();
    }

    /**
     * Says which code remote an invitation of this project names, and writes nothing: a computer that has no role file
     * keeps working exactly as before until it accepts a joiner ({@link #admit}).
     *
     * <p>The remote is the explicitly configured {@code code.remote}, or the project's only Git remote. With several
     * remotes and none configured nothing is guessed, and the invitation carries no URL.
     *
     * @param root         the project folder
     * @param coordination the project's coordination folder
     * @return the code remote the invitation names
     * @throws Refusal the role file's own code when it cannot be read
     * @throws IOException when Git cannot be run
     */
    public static Leader invitationRemote(Path root, Path coordination) throws Refusal, IOException {
        String configured = read(coordination).map(ReplicationSettings::codeRemote).orElse(null);
        List<String> remotes = remotes(root);
        String name = configured;
        if (configured == null && remotes.size() == 1) {
            name = remotes.getFirst();
        }
        if (name == null) {
            return new Leader(null, null, remotes.isEmpty() ? "NO_GIT_REMOTE" : "SEVERAL_GIT_REMOTES");
        }
        if (!remotes.contains(name)) {
            return new Leader(name, null, "CODE_REMOTE_MISSING");
        }
        String url = urlOf(root, name);
        return new Leader(name, url, url == null ? "URL_NOT_SHAREABLE" : null);
    }

    /**
     * Makes this computer the leader of the project, keeping its members, and adds a node to the member list. Only an
     * accepted join writes the role file. Code moves over Link; the mirror is the remote whose URL the invitation
     * carried, else the one the role file named, else none.
     *
     * <p>The read, check and write run under the project append lock, so two accepts, an accept and a follow, or an
     * accept and an agent's append never lose each other's change.
     *
     * @param root          the project folder
     * @param coordination  the project's coordination folder
     * @param nodeId        the node an accepted request came from
     * @param codeRemoteUrl the URL the invitation carried, or {@code null} when it carried none
     * @throws Refusal {@code NOT_A_LEADER} on a member
     * @throws IOException when the lock cannot be taken or the role file cannot be written
     */
    @SuppressWarnings("try") // the lock is held for its scope only
    public static void admit(Path root, Path coordination, org.synesis.coordination.application.MembershipService membership, String nodeId, String codeRemoteUrl)
            throws Refusal, IOException {
        // The acceptance is an entry of this computer's log (ADR-0098 step 6): the owner's, or under the any-member
        // policy any member's. A project accepted into for the first time is shared by this computer first.
        try {
            membership.share(Optional.empty());
            membership.requestJoin(nodeId);
            membership.answer(nodeId, true);
        } catch (java.security.GeneralSecurityException unsigned) {
            throw new IOException("the acceptance could not be signed", unsigned);
        }
        try (ProjectAppendLock ignored = ProjectAppendLock.acquire(coordination)) {
            Optional<ReplicationSettings> found = read(coordination);
            String remote = codeRemoteUrl == null ? null : remoteWithUrl(root, codeRemoteUrl);
            if (remote == null) {
                remote = found.map(ReplicationSettings::codeRemote).orElse(null);
            }
            ReplicationSettings wanted = ReplicationSettings.sharedProject(remote);
            if (found.isEmpty() || !found.get().equals(wanted)) {
                wanted.write(coordination);
            }
        }
    }

    /**
     * The change to the running process that goes with a role: the live replication side takes the role the file now
     * names. It runs inside the lock, and a failure of it undoes the file.
     */
    @FunctionalInterface
    public interface LiveSwitch {

        /**
         * Switches the running side.
         *
         * @throws IOException when it cannot be switched, for example because it follows another leader
         */
        void run() throws IOException;
    }

    /**
     * Makes this computer a member that follows the inviter of a project link, and sets the code remote from the
     * link. The role is refused when it would strand work: another leader, members of its own, or a log that has
     * entries (a member's log is a copy of the leader's, and an entry made before following would not be on it).
     *
     * <p>Everything runs under the project append lock, so no agent can append between the empty-log check and the
     * role, and two opens of different links cannot each write a role. {@code live} switches the running side inside
     * the same lock; if it fails the role file and the remote this call added are put back, so the file never names
     * a leader the running side does not follow.
     *
     * @param root        the project folder
     * @param coordination the project's coordination folder
     * @param invitation  the verified project link
     * @param ownNodeId   this computer's node ID
     * @param live        the switch of the running side
     * @return the name of the remote code moves through, or {@code null} when the link names none and none was set
     * @throws Refusal {@code INVITER_IS_THIS_COMPUTER}, {@code FOLLOWING_ANOTHER_LEADER}, {@code ALREADY_A_LEADER},
     *                 {@code LOG_NOT_EMPTY}, {@code CODE_REMOTE_NAME_TAKEN}, {@code CODE_REMOTE_NOT_ADDED}
     * @throws IOException when the lock, the role file or the live switch fails; nothing is left written
     */
    @SuppressWarnings("try") // the lock is held for its scope only
    public static String follow(Path root, Path coordination, ProjectInvitation invitation, String ownNodeId, LiveSwitch live) throws Refusal, IOException {
        String leader = invitation.hostNodeId();
        if (leader.equals(ownNodeId)) {
            throw new Refusal("INVITER_IS_THIS_COMPUTER");
        }
        try (ProjectAppendLock ignored = ProjectAppendLock.acquire(coordination)) {
            Optional<ReplicationSettings> found = read(coordination);
            if (found.isPresent() && found.get().isMember()) {
                if (!leader.equals(found.get().leaderNodeId())) {
                    throw new Refusal("FOLLOWING_ANOTHER_LEADER");
                }
            } else if (found.isPresent() && !found.get().members().isEmpty()) {
                throw new Refusal("ALREADY_A_LEADER");
            } else if (PredictionEventStore.recordedEvents(coordination) > 0) {
                throw new Refusal("LOG_NOT_EMPTY");
            }
            // A folder that gets its repository over Link has none to add a remote to yet, and Git run there would find
            // the repository of an enclosing folder; it follows with no mirror.
            boolean repository = Files.exists(root.resolve(".git"));
            boolean hadAddedRemote = repository && remotes(root).contains(ADDED_REMOTE);
            String remote = repository && invitation.codeRemoteUrl().isPresent()
                    ? remoteFor(root, invitation.codeRemoteUrl().get())
                    : found.map(ReplicationSettings::codeRemote).orElse(null);
            boolean addedRemote = ADDED_REMOTE.equals(remote) && !hadAddedRemote;
            Path peersFile = coordination.resolve(ReplicationPeers.FILE);
            byte[] peersBefore = Files.exists(peersFile) ? Files.readAllBytes(peersFile) : null;
            try {
                ReplicationSettings.sharedProject(remote).write(coordination);
                // A joiner reconnects to its inviter only; records of any other node (from an earlier role) must not be
                // resumed. The inviter is recorded as dialed now: until the owner's entries arrive, the computers this
                // one dialed are the ones it trusts (ADR-0098 step 6, designer item 15).
                ReplicationPeers.retainOnly(coordination, Set.of(leader));
                ReplicationPeers.rememberDialed(coordination, leader, invitation.invitation().shareLink());
                live.run();
            } catch (IOException | RuntimeException failed) {
                if (found.isPresent()) {
                    found.get().write(coordination);
                } else {
                    Files.deleteIfExists(coordination.resolve(ReplicationSettings.FILE));
                }
                // The inviter is trusted by its dial line until the owner's entries arrive: a join that failed must not
                // leave it trusted.
                if (peersBefore == null) {
                    Files.deleteIfExists(peersFile);
                } else {
                    Files.write(peersFile, peersBefore);
                }
                if (addedRemote) {
                    GitProcessRunner.runResult(root, "remote", "remove", ADDED_REMOTE);
                }
                throw failed;
            }
            return remote;
        }
    }

    /**
     * Finds the folder of a project link's project on this computer.
     *
     * <p>With no folder given, the registry decides: a folder registered for the project is used. With a folder
     * given, it is accepted only if it is the project: its {@code .synesis/project.json} carries the link's identity,
     * or, for a clone Synesis never saw, a Git remote has the link's code remote URL. A clone that holds only the
     * project file is made a replica ({@code initReplica}, ADR-0080) and the folder is registered. An empty folder, or
     * a new one in a folder that exists, is made a replica with no repository yet, which the leader's branch is checked
     * out into over Link after the owner accepts this computer.
     *
     * @param registry   this installation's known projects
     * @param service    project initialization
     * @param invitation the verified project link
     * @param chosen     the folder the person chose, or {@code null}
     * @param preferred  the folder this person is working in, used when the registry knows several copies of the
     *                   project and this is one of them; otherwise the first by path. May be {@code null}
     * @return the folder, or empty when no folder was given and the project is not registered here
     * @throws Refusal {@code PROJECT_IDENTITY_MISMATCH}, {@code PROJECT_FOLDER_INVALID}, {@code PROJECT_FOLDER_NOT_THE_PROJECT},
     *                 or the registry's or initialization's code
     */
    public static Optional<Path> locate(KnownProjectRegistry registry, ProjectApplicationService service, ProjectInvitation invitation, Path chosen, Path preferred) throws Refusal {
        ProjectIdentity identity = new ProjectIdentity(invitation.projectId(), invitation.createdAt());
        try {
            if (chosen == null) {
                return known(registry, identity, preferred);
            }
            Path root = chosen.toAbsolutePath().normalize();
            if (!Files.exists(root) && root.getParent() != null && Files.isDirectory(root.getParent())) {
                Files.createDirectory(root);
            }
            if (!Files.isDirectory(root)) {
                throw new Refusal("PROJECT_FOLDER_INVALID");
            }
            if (isEmpty(root)) {
                // No .git: the first checkout over Link makes the repository (CodeOverLink.followMain).
                service.initReplica(root, identity, false);
                return Optional.of(registry.register(root).path());
            }
            Path synesis = root.resolve(".synesis");
            if (Files.exists(synesis.resolve("project.json"))) {
                if (!service.require(root).identity().matches(identity)) {
                    throw new Refusal("PROJECT_IDENTITY_MISMATCH");
                }
            } else if (Files.exists(synesis) || !hasRemote(root, invitation.codeRemoteUrl().orElse(null))) {
                throw new Refusal("PROJECT_FOLDER_NOT_THE_PROJECT");
            }
            if (!Files.isDirectory(synesis) || onlyProjectFile(synesis)) {
                service.initReplica(root, identity, false);
            }
            return Optional.of(registry.register(root).path());
        } catch (KnownProjectRegistry.RegistryException failure) {
            throw new Refusal(failure.code());
        } catch (ProjectApplicationService.ProjectApplicationException failure) {
            throw new Refusal(failure.code());
        } catch (IOException failure) {
            throw new Refusal("PROJECT_FOLDER_INVALID");
        }
    }

    private static Optional<Path> known(KnownProjectRegistry registry, ProjectIdentity identity, Path preferred) throws KnownProjectRegistry.RegistryException, Refusal {
        List<Path> copies = new ArrayList<>();
        boolean sameIdOtherCreation = false;
        for (KnownProjectRegistry.ProjectView view : registry.projects()) {
            if (!view.projectId().equals(identity.projectId())) {
                continue;
            }
            if (!view.createdAt().equals(identity.createdAt())) {
                sameIdOtherCreation = true;
            } else if (view.status() == KnownProjectRegistry.Status.LIVE || view.status() == KnownProjectRegistry.Status.INACTIVE) {
                copies.add(view.path());
            }
        }
        if (copies.isEmpty() && sameIdOtherCreation) {
            throw new Refusal("PROJECT_IDENTITY_MISMATCH");
        }
        if (preferred != null && copies.contains(preferred.toAbsolutePath().normalize())) {
            return Optional.of(preferred.toAbsolutePath().normalize());
        }
        return copies.stream().findFirst();
    }

    private static boolean isEmpty(Path folder) throws IOException {
        try (var children = Files.list(folder)) {
            return children.findAny().isEmpty();
        }
    }

    private static boolean onlyProjectFile(Path synesis) throws IOException {
        try (var children = Files.list(synesis)) {
            return children.allMatch(child -> "project.json".equals(child.getFileName().toString()));
        }
    }

    private static Optional<ReplicationSettings> read(Path coordination) throws Refusal {
        try {
            return ReplicationSettings.read(coordination);
        } catch (CoordinationException unreadable) {
            throw new Refusal(unreadable.code());
        }
    }

    private static boolean hasRemote(Path root, String url) throws IOException {
        return url != null && remoteWithUrl(root, url) != null;
    }

    /** The remote of the clone that has this URL (spelled alike), or null. */
    private static String remoteWithUrl(Path root, String url) throws IOException {
        String wanted = ProjectInvitation.normalizedUrl(url);
        for (String name : remotes(root)) {
            if (ProjectInvitation.normalizedUrl(rawUrlOf(root, name)).equals(wanted)) {
                return name;
            }
        }
        return null;
    }

    /**
     * The remote of the clone that has this URL; if none has it, one named {@value #ADDED_REMOTE} is added.
     */
    private static String remoteFor(Path root, String url) throws Refusal, IOException {
        String existing = remoteWithUrl(root, url);
        if (existing != null) {
            return existing;
        }
        if (remotes(root).contains(ADDED_REMOTE)) {
            throw new Refusal("CODE_REMOTE_NAME_TAKEN");
        }
        if (GitProcessRunner.runResult(root, "remote", "add", "--", ADDED_REMOTE, url).exitCode() != 0) {
            throw new Refusal("CODE_REMOTE_NOT_ADDED");
        }
        return ADDED_REMOTE;
    }

    /** Remote names that the role file can hold. */
    private static List<String> remotes(Path root) throws IOException {
        var result = GitProcessRunner.runResult(root, "remote");
        List<String> names = new ArrayList<>();
        if (result.exitCode() == 0) {
            for (String line : result.output().split("\\R")) {
                if (REMOTE_NAME.matcher(line.strip()).matches()) {
                    names.add(line.strip());
                }
            }
        }
        return names;
    }

    private static String rawUrlOf(Path root, String name) throws IOException {
        var result = GitProcessRunner.runResult(root, "remote", "get-url", name);
        return result.exitCode() == 0 ? result.output().strip() : "";
    }

    /** The URL as it may be written into a link, or null when it cannot be (credentials removed, still unusable). */
    private static String urlOf(Path root, String name) throws IOException {
        String url = ProjectInvitation.publicUrl(rawUrlOf(root, name));
        boolean usable = !url.isBlank() && url.getBytes(StandardCharsets.UTF_8).length <= ProjectInvitation.MAX_URL_BYTES
                && url.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)) && !url.startsWith("-") && !url.matches("^[A-Za-z0-9]+::.*") && ProjectInvitation.isFetchableUrl(url);
        return usable ? url : null;
    }
}
