package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.synesis.cli.command.coordination.TestComputers.Computer;
import org.synesis.cli.command.coordination.TestComputers.Response;

import org.synesis.coordination.domain.prediction.PredictionEventType;

import org.synesis.link.session.PeerSession;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Two real Synesis computers joined by a real Link on loopback, for scripted scenarios (ADR-0102 item 1). The lab owns the
 * set-up that every scenario repeats (a project of mandatory review, a second computer that joined it by a project link,
 * the provider installed on both) and the two things a scenario does to the world: {@link #split} and {@link #heal}.
 * Everything else a scenario does is an {@link ScenarioAgent} calling MCP tools, and what it asserts it reads from the
 * logs and from Git, so a scenario is a short list of named steps.
 *
 * <p>Waits are on conditions, each with a bounded timeout and a name that the failure message carries. The merge driver of
 * both computers runs every second, so a scenario never waits for the launcher's 3 s and 30 s.</p>
 *
 * <p>The two computers are {@link #owner} (which created the project) and {@link #friend} (which joined it); the scenario
 * that depends on the node ID order asks {@link #higher()} and {@link #lower()}, since IDs are random.</p>
 */
final class ScenarioLab implements AutoCloseable {

    private static final long STEP_TIMEOUT_SECONDS = 60;
    private static final String BASELINE_SHARED = "one\ntwo\nthree\n";

    final TestComputers computers;
    final Computer owner;
    final Computer friend;
    /** The name of the branch both computers keep their copy of the project on. */
    final String branch;
    /** Every agent made here, so that a failure message can name the participants a log mentions. */
    private final List<ScenarioAgent> agents = new java.util.concurrent.CopyOnWriteArrayList<>();

    private ScenarioLab(TestComputers computers, Computer owner, Computer friend, String branch) {
        this.computers = computers;
        this.owner = owner;
        this.friend = friend;
        this.branch = branch;
    }

    /**
     * Creates the project on a computer, shares it by a project link with a second computer that holds a clone, and returns
     * once both are members and connected. The project requires review of every snapshot, so each computer needs two agents
     * that review each other.
     */
    static ScenarioLab open(Path temp) throws Exception {
        TestComputers farm = new TestComputers().mergeEvery(300, 1_000);
        ProjectApplicationService service = farm.service();
        Path ownerRoot = Files.createDirectories(temp.resolve("alpha"));
        GitProcessRunner.run(ownerRoot, "init");
        configureGit(ownerRoot);
        Files.writeString(ownerRoot.resolve("README.md"), "# Scenario project\n");
        Files.writeString(ownerRoot.resolve("shared.txt"), BASELINE_SHARED);
        GitProcessRunner.run(ownerRoot, "add", ".");
        GitProcessRunner.run(ownerRoot, "commit", "-m", "baseline");
        ProjectLocation ownerProject = service.initForRegistration(ownerRoot, false).location();
        Files.writeString(ownerProject.metadataFile(), "{\"schemaVersion\":2,\"projectId\":\"" + ownerProject.projectId() + "\",\"createdAt\":\"" + ownerProject.createdAt()
                + "\",\"review\":{\"mandatory\":true}}");
        GitProcessRunner.run(ownerRoot, "add", "--", ownerRoot.relativize(ownerProject.metadataFile()).toString().replace('\\', '/'));
        GitProcessRunner.run(ownerRoot, "commit", "-m", "require review");
        String branch = GitProcessRunner.run(ownerRoot, "symbolic-ref", "--short", "HEAD").strip();

        Path friendRoot = temp.resolve("beta");
        GitProcessRunner.run(temp, "clone", ownerRoot.toString(), friendRoot.toString());
        configureGit(friendRoot);
        // The clone's origin is the owner's folder; the computers exchange code over Link, so neither has a code remote.
        GitProcessRunner.run(friendRoot, "remote", "remove", "origin");
        ProjectLocation friendProject = service.initReplica(friendRoot, ownerProject.identity(), false).location();
        KnownProjectRegistry registry = new KnownProjectRegistry(temp.resolve("registry/known.json"));
        registry.register(friendRoot);

        Computer alpha = farm.start(ownerProject, null);
        Computer beta = farm.start(friendProject, registry);
        ScenarioLab lab = new ScenarioLab(farm, alpha, beta, branch);
        try {
            installProvider(ownerProject);
            installProvider(friendProject);
            lab.join();
            return lab;
        } catch (Exception | Error failed) {
            lab.close();
            throw failed;
        }
    }

    /** The friend opens the owner's project link, the owner accepts it, and the computers are members and linked. */
    private void join() throws Exception {
        Response invited = owner.post("invite", Map.of("project", true));
        assertEquals(201, invited.status(), invited.toString());
        Response opened = friend.post("open-project-link", Map.of("link", invited.body().get("inviteUri")));
        assertEquals(200, opened.status(), opened.toString());
        await("the owner holds the friend's join request", () -> {
            try {
                return owner.joinRequests().size() == 1;
            } catch (Exception unavailable) {
                return false;
            }
        });
        Response accepted = owner.post("accept-join", Map.of("nodeId", friend.node.nodeId()));
        assertEquals(200, accepted.status(), accepted.toString());
        await("both computers name both as members", () -> owner.namesMember(friend.node.nodeId()) && friend.namesMember(friend.node.nodeId()) && friend.namesMember(owner.node.nodeId()));
        await("the two computers are linked", this::linked);
    }

    /** The computer with the higher node ID, which is the one that re-merges when the two copies of the branch have diverged. */
    Computer higher() {
        return owner.node.nodeId().compareTo(friend.node.nodeId()) > 0 ? owner : friend;
    }

    Computer lower() {
        return higher() == owner ? friend : owner;
    }

    /** An agent of {@code computer} named {@code name}; the name is its connection, so it is also its session's name. */
    ScenarioAgent agent(Computer computer, String name) {
        ScenarioAgent created = new ScenarioAgent(computer, name);
        agents.add(created);
        return created;
    }

    // ---- the Link ----

    /** Whether each computer holds a usable session with the other. */
    boolean linked() {
        return sessionWith(owner, friend) && sessionWith(friend, owner);
    }

    private static boolean sessionWith(Computer holder, Computer other) {
        return holder.owner.get().sessions().stream().anyMatch(session -> session.isUsable() && session.remoteNodeId().equals(other.node.nodeId()));
    }

    /** Cuts the Link between the computers, both ways, until {@link #heal}; the internet is not touched, so agents go on. */
    void split() {
        owner.splitLink();
        friend.splitLink();
        await("neither computer holds a session", () -> !owner.owner.get().sessions().stream().anyMatch(PeerSession::isUsable)
                && !friend.owner.get().sessions().stream().anyMatch(PeerSession::isUsable));
    }

    /** Lets both computers dial and host again, as a restart of each Link owner does, and returns once they are linked. */
    void heal() {
        friend.healLink();
        owner.healLink();
        await("the computers are linked again", this::linked);
    }

    // ---- driving agents and waiting ----

    /**
     * Lets the agents act as models do, each taking the step its next action recommends, until {@code done}. The agents
     * must make progress before the timeout, or the failure says which condition was awaited.
     */
    void drive(String what, BooleanSupplier done, ScenarioAgent... agents) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STEP_TIMEOUT_SECONDS);
        while (!done.getAsBoolean()) {
            boolean progressed = false;
            for (ScenarioAgent agent : agents) {
                progressed |= agent.step() == ScenarioAgent.Step.ACTED;
                if (done.getAsBoolean()) {
                    return;
                }
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("not reached within " + STEP_TIMEOUT_SECONDS + " s: " + what + dump() + describe(agents));
            }
            if (!progressed) {
                pause();
            }
        }
    }

    void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STEP_TIMEOUT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("not reached within " + STEP_TIMEOUT_SECONDS + " s: " + what + dump());
            }
            pause();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", interrupted);
        }
    }

    /** What each agent's next action says, for a failure message. */
    private static String describe(ScenarioAgent... agents) {
        StringBuilder out = new StringBuilder();
        for (ScenarioAgent agent : agents) {
            Map<String, Object> next = agent.next();
            Map<String, Object> inner = ScenarioAgent.inner(next);
            out.append(agent.name).append(" calls:\n");
            agent.trace.forEach(line -> out.append("      ").append(line).append('\n'));
            String whole = String.valueOf(next);
            out.append(agent.name).append(": ").append(next.get("status")).append(' ').append(next.get("reason")).append(' ').append(next.get("nextAction")).append(" state=")
                    .append(inner.get("state")).append(" participant=").append(agent.participant()).append("\n    ").append(whole.length() > 1500 ? whole.substring(0, 1500) : whole).append('\n');
        }
        return out.toString();
    }

    /** What a failure message carries about the two computers: heads, Git history and what their logs hold, by type. */
    private String dump() {
        StringBuilder out = new StringBuilder("\n");
        out.append("higher is ").append(higher() == owner ? "owner" : "friend").append(", linked ").append(linked()).append('\n');
        for (Computer computer : List.of(owner, friend)) {
            out.append(computer == owner ? "owner " : "friend ").append(computer.node.nodeId(), 0, 8).append(" head ").append(head(computer));
            try {
                java.util.Map<String, Long> types = new java.util.TreeMap<>();
                computer.store().events().forEach(event -> types.merge(event.type().name(), 1L, Long::sum));
                out.append(" entries ").append(computer.store().events().size()).append(' ').append(types);
            } catch (Exception unreadable) {
                out.append(" entries unreadable");
            }
            try {
                var groups = computer.store().workGroupProjection();
                for (var grant : groups.grants()) {
                    String who = agents.stream().filter(agent -> grant.targetParticipant().equals(agent.participant())).map(agent -> agent.name).findFirst().orElse(grant.targetParticipant());
                    out.append("\n  grant to ").append(who).append(" snapshot ").append(grant.snapshotId()).append(groups.grantConsumed(grant.grantId()) ? " consumed" : "")
                            .append(groups.grantRevoked(new org.synesis.coordination.domain.identity.LaneGrantId(grant.grantId())) ? " revoked" : "")
                            .append(groups.grantAvailable(grant.grantId()) ? " available" : "");
                }
            } catch (Exception unreadable) {
                out.append("\n  (grants unreadable)");
            }
            out.append("\n  members record: ").append(org.synesis.coordination.replication.LeaderReplication.recordedMembers(computer.location.coordinationDirectory()).map(String::valueOf).orElse("none"));
            try {
                out.append("\n  status: ").append(GitProcessRunner.run(computer.location.root(), "status", "--short", "--branch").replace("\n", " | "));
                out.append("\n  refs: ").append(GitProcessRunner.run(computer.location.root(), "for-each-ref", "--format=%(refname) %(objectname:short)").replace("\n", " | "));
            } catch (Exception unreadable) {
                out.append("\n  (git status unreadable)");
            }
            try {
                out.append("\n").append(GitProcessRunner.run(computer.location.root(), "log", "--oneline", "--graph", "--all", "-14"));
            } catch (Exception unreadable) {
                out.append("\n(git log unreadable)");
            }
            out.append('\n');
        }
        return out.toString();
    }

    // ---- what to read ----

    /** The commit this computer's copy of the branch is at. */
    String head(Computer computer) {
        try {
            return GitProcessRunner.run(computer.location.root(), "rev-parse", branch).strip();
        } catch (Exception unreadable) {
            return "unreadable";
        }
    }

    /** The text of a file on this computer's copy of the branch, or {@code null} when it has none there. */
    String onBranch(Computer computer, String path) {
        try {
            return GitProcessRunner.run(computer.location.root(), "show", branch + ":" + path).replace("\r\n", "\n");
        } catch (Exception absent) {
            return null;
        }
    }

    /** Whether this computer's copy of the branch holds the file. */
    boolean hasOnBranch(Computer computer, String path) {
        return onBranch(computer, path) != null;
    }

    /** Both copies of the branch are the same commit. */
    boolean mainsEqual() {
        String ours = head(owner);
        return !"unreadable".equals(ours) && ours.equals(head(friend));
    }

    /**
     * Each computer holds a byte-identical copy of the other's log, and the entries it holds are the entries the other holds
     * (ADR-0098 step 4, "Layout"). Compares the files, not what they decode to.
     */
    void assertLogsIdentical() throws IOException {
        assertCopy(owner, friend);
        assertCopy(friend, owner);
        Path left = owner.location.coordinationDirectory();
        Path right = friend.location.coordinationDirectory();
        assertEquals(logFiles(left.resolve("events")).size() + logFiles(left.resolve("peers").resolve(friend.node.nodeId()).resolve("events")).size(),
                logFiles(right.resolve("events")).size() + logFiles(right.resolve("peers").resolve(owner.node.nodeId()).resolve("events")).size(),
                "both computers hold the same number of entries");
    }

    private static void assertCopy(Computer writer, Computer holder) throws IOException {
        Path own = writer.location.coordinationDirectory().resolve("events");
        Path copy = holder.location.coordinationDirectory().resolve("peers").resolve(writer.node.nodeId()).resolve("events");
        List<Path> written = logFiles(own);
        List<Path> copied = logFiles(copy);
        assertEquals(written.stream().map(path -> path.getFileName().toString()).toList(), copied.stream().map(path -> path.getFileName().toString()).toList(),
                holder.node.nodeId().substring(0, 8) + " holds the entries " + writer.node.nodeId().substring(0, 8) + " wrote");
        for (int index = 0; index < written.size(); index++) {
            assertArrayEquals(Files.readAllBytes(written.get(index)), Files.readAllBytes(copied.get(index)), "entry " + written.get(index).getFileName());
        }
    }

    private static List<Path> logFiles(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".sce")).sorted().toList();
        }
    }

    /** Every file of the branch is the same on both computers; used after the copies are equal, to catch a half-moved checkout. */
    void assertCheckoutsMatchBranch() throws Exception {
        for (Computer computer : List.of(owner, friend)) {
            assertEquals("", GitProcessRunner.run(computer.location.root(), "status", "--porcelain", "--untracked-files=no").strip(), "the control checkout is clean");
        }
    }

    // ---- set-up ----

    private static void configureGit(Path root) throws Exception {
        GitProcessRunner.run(root, "config", "user.name", "Scenario");
        GitProcessRunner.run(root, "config", "user.email", "scenario@example.test");
    }

    /** The normal project provider install, with a provider home of its own, as the MCP tests install it. */
    private static void installProvider(ProjectLocation location) throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousLauncher = System.getProperty("synesis.launcher");
        String previousMcpLauncher = System.getProperty("synesis.mcp.launcher");
        Path isolatedHome = Files.createTempDirectory("synesis-scenario-provider-home-");
        System.setProperty("user.home", isolatedHome.toString());
        try {
            ensureLauncher("synesis.launcher");
            ensureLauncher("synesis.mcp.launcher");
            ProviderApplicationService.ProviderResult result = new ProviderApplicationService().install(location, "codex");
            if (result.exitCode() != 0 || !"BOUND".equals(result.values().get("SESSION_BINDING"))) {
                throw new IllegalStateException("Provider installation did not bind a session: " + result.values());
            }
        } finally {
            restoreProperty("user.home", previousHome);
            restoreProperty("synesis.launcher", previousLauncher);
            restoreProperty("synesis.mcp.launcher", previousMcpLauncher);
        }
        new ProviderManualService().install("codex");
    }

    private static void ensureLauncher(String property) throws IOException {
        String configured = System.getProperty(property);
        if (configured != null && Files.isRegularFile(Path.of(configured))) {
            return;
        }
        System.setProperty(property, Files.createTempFile("synesis-scenario", ".launcher").toString());
    }

    private static void restoreProperty(String property, String previous) {
        if (previous == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previous);
        }
    }

    @Override
    public void close() {
        computers.close();
    }

    /** The trash this computer lists in its snapshot, oldest first (ADR-0098 step 7, item 13). */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> trash(Computer computer) {
        try {
            return (List<Map<String, Object>>) computer.read("snapshot").body().get("trash");
        } catch (Exception unreadable) {
            return List.of();
        }
    }

    /** How many entries of a type {@code writer} wrote that {@code holder}'s log, own and copied, holds. */
    long entriesWritten(Computer holder, Computer writer, PredictionEventType type) throws Exception {
        return holder.store().events().stream().filter(event -> event.type() == type && event.actorNodeId().equals(writer.node.nodeId())).count();
    }

    /** {@link #entriesWritten} for a step that cannot throw; an unreadable log fails the scenario. */
    long entriesWrittenUnchecked(Computer holder, Computer writer, PredictionEventType type) {
        try {
            return entriesWritten(holder, writer, type);
        } catch (Exception unreadable) {
            throw new AssertionError("the log of " + holder.node.nodeId() + " cannot be read", unreadable);
        }
    }
}
