package org.synesis.workspace.transport.control;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.synesis.coordination.application.CoordinationService;
import org.synesis.coordination.domain.membership.MembershipProjection;
import org.synesis.coordination.persistence.LogDump;
import org.synesis.coordination.persistence.LoopbackExchange;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.coordination.replication.LeaderReplication;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.transport.control.network.NetworkSnapshot;

/**
 * What {@code synesis status} shows: the project, whether its runtime runs, each agent and what it works on, what is
 * waiting for a person, and the project's other computers.
 *
 * <p>It is a view over {@link ControlPlaneReadModel}, the model the browser UI is served from, and over the
 * replication facts {@code synesis coordination status} prints, so the two never disagree and nothing here is a second
 * source of truth. It is built from files and reads them only, as {@code synesis log dump} does: the project's runtime
 * may be stopped, the logs are opened without creating, moving or removing anything (an ordinary open archives a
 * superseded log and removes interrupted writes), no identity is created and no private key is read.</p>
 *
 * @param projectId       the project's identifier
 * @param name            the project directory's name
 * @param shared          whether the project is shared with other computers
 * @param owner           the owner's node, or empty when the project has none
 * @param self            this computer's node, or empty when it has no readable identity
 * @param runtimeRunning  whether the project's runtime answers on the loopback address it recorded
 * @param agents          every agent the project knows, with the work it holds
 * @param needs           what is waiting for a person
 * @param computers       the project's other computers; empty for a project on one computer
 */
public record ProjectStatus(String projectId, String name, boolean shared, String owner, String self, boolean runtimeRunning,
                            List<Agent> agents, List<Need> needs, List<Computer> computers) {

    /**
     * One agent.
     *
     * @param id        the agent's identifier
     * @param provider  the provider it runs on
     * @param state     its lifecycle state, such as {@code ACTIVE}
     * @param lane      the goal of the lane it holds
     * @param role      the lane's role, such as {@code producer}, or empty
     * @param files     what its lane claims: paths and other selectors
     * @param waitingOn what the lane waits for
     */
    public record Agent(String id, String provider, String state, String lane, String role, List<String> files, List<String> waitingOn) {
    }

    /**
     * Something that waits for a person.
     *
     * @param kind    {@code operator} for an operator request, {@code join} for a computer asking to join
     * @param id      the request's identifier, or the asking computer's node
     * @param summary what is asked, on the words of the request
     */
    public record Need(String kind, String id, String summary) {
    }

    /**
     * Another computer of a shared project.
     *
     * @param node         the computer's node
     * @param state        {@code CONNECTED} or {@code DISCONNECTED} as the project's runtime recorded, or
     *                     {@code RUNTIME_STOPPED} when nothing says, because no runtime runs to record it
     * @param since        when the runtime recorded that state, or empty
     * @param lastReceived when entries of that computer's log last arrived on this one, or empty when none has
     */
    public record Computer(String node, String state, Optional<Instant> since, Optional<Instant> lastReceived) {
    }

    /**
     * Reads the status of a project.
     *
     * @param location the project
     * @return its status
     * @throws IOException              when the project's logs cannot be read
     * @throws GeneralSecurityException when an entry fails verification
     */
    public static ProjectStatus read(ProjectLocation location) throws IOException, GeneralSecurityException {
        Path data = location.coordinationDirectory();
        var inspection = new IdentityBootstrap(location.linkDirectory()).inspect();
        String self = inspection.identityPresent() && inspection.identityValid() ? inspection.nodeId() : "";
        String name = location.root().getFileName() == null ? location.root().toString() : location.root().getFileName().toString();
        var settings = ReplicationSettings.read(data);
        if (!Files.isDirectory(data)) {
            return new ProjectStatus(location.projectId().toString(), name, settings.isPresent(), "", self, false, List.of(), List.of(), List.of());
        }
        var store = PredictionEventStore.openReadOnly(data, location.projectId());
        var model = new ControlPlaneReadModel(location, CoordinationService.readOnly(store), new ProviderApplicationService(),
                new DoctorService(), NetworkSnapshot::empty, null);
        MembershipProjection membership = store.membershipProjection();
        boolean shared = membership.shared() || settings.isPresent();
        boolean running = new LoopbackExchange(data).runningServer().isPresent();
        List<Need> needs = new ArrayList<>();
        for (Map<String, Object> request : model.operatorRequests()) {
            needs.add(new Need("operator", String.valueOf(request.get("id")), String.valueOf(request.get("summary"))));
        }
        membership.openRequests().forEach(request -> needs.add(new Need("join", request.joinerNodeId(), "asks to join")));
        // An earlier role file names its leader as the one all others follow; the leader's own computer is the owner.
        String owner = membership.owner().orElse(settings.map(role -> role.isMember() ? role.leaderNodeId() : self).orElse(""));
        return new ProjectStatus(location.projectId().toString(), name,
                shared, owner, self, running, agents(model), List.copyOf(needs), computers(data, membership, settings, self, running));
    }

    private static List<Agent> agents(ControlPlaneReadModel model) {
        List<Agent> agents = new ArrayList<>();
        for (Map<String, Object> agent : model.agents()) {
            Map<?, ?> work = agent.get("currentWork") instanceof Map<?, ?> current ? current : Map.of();
            List<String> files = new ArrayList<>();
            if (agent.get("claims") instanceof List<?> claims) {
                claims.forEach(claim -> files.add(((Map<?, ?>) claim).get("value").toString()));
            }
            List<String> waiting = agent.get("waitingOn") instanceof List<?> dependencies ? dependencies.stream().map(String::valueOf).toList() : List.of();
            agents.add(new Agent(String.valueOf(agent.get("id")), String.valueOf(agent.get("provider")), String.valueOf(agent.get("state")),
                    String.valueOf(work.get("goal") != null ? work.get("goal") : agent.get("goal")), work.get("role") == null ? "" : work.get("role").toString(), List.copyOf(files), waiting));
        }
        return List.copyOf(agents);
    }

    /**
     * Lists the other computers by the rule {@code synesis coordination status} follows: a shared project's members
     * come from its logs, an older leader's from its role file, and an older member's computer is its leader.
     */
    private static List<Computer> computers(Path data, MembershipProjection membership, Optional<ReplicationSettings> settings, String self, boolean running) {
        TreeSet<String> nodes = new TreeSet<>();
        if (membership.shared()) {
            nodes.addAll(membership.members());
        } else if (settings.isPresent()) {
            ReplicationSettings role = settings.get();
            nodes.addAll(role.isMember() ? java.util.Set.of(role.leaderNodeId()) : role.members());
        }
        nodes.remove(self);
        var recorded = LeaderReplication.recordedMembers(data);
        List<Computer> computers = new ArrayList<>();
        for (String node : nodes) {
            var connection = recorded.map(members -> members.get(node));
            String state = !running ? "RUNTIME_STOPPED" : connection.isPresent() ? connection.get().state().name() : "DISCONNECTED";
            computers.add(new Computer(node, state, running ? connection.map(LeaderReplication.MemberConnection::since) : Optional.empty(), LogDump.lastArrival(data, node)));
        }
        return List.copyOf(computers);
    }
}
