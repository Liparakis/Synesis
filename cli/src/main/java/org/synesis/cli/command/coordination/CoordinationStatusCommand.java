package org.synesis.cli.command.coordination;


import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.CoordinationCliSupport;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationSettings;
import org.synesis.coordination.replication.LeaderReplication;
import org.synesis.coordination.replication.MemberReplication;
import org.synesis.coordination.replication.ReplicationJournal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Inspects durable coordination state without mutating it.
 */
@Command(name = "status", description = "Inspect durable coordination state.", mixinStandardHelpOptions = true)
public final class CoordinationStatusCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", description = "Initialized project directory.")
    private Path project;
    @Option(names = "--data", description = "Coordinator state directory.")
    private Path data;

    /**
     * Creates a status command.
     *
     * @param runtime composed CLI runtime
     */
    public CoordinationStatusCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Prints the current sequence and projections.
     *
     * @return stable process exit code
     */
    @Override
    public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            var projectData = CoordinationCliSupport.data(location, data);
            var replication = ReplicationSettings.read(projectData);
            var store = new PredictionEventStore(projectData, location.projectId());
            runtime.terminal().stdout("COORDINATION_STATUS=PASS");
            runtime.terminal().stdout("PROJECT_ID=" + location.projectId());
            runtime.terminal().stdout("PROJECT_SEQUENCE=" + store.headSequence());
            runtime.terminal().stdout("PREDICTIONS=" + store.projection().snapshot().size());
            runtime.terminal().stdout("TASKS=" + store.coordinationProjection().tasks().size());
            runtime.terminal().stdout("OWNERSHIPS=" + store.coordinationProjection().ownerships().size());
            // Entries of other computers that counted for nothing, or wait for what they name (ADR-0098 step 4).
            store.logHeads().forEach((log, head) -> {
                if (!log.equals(PredictionEventStore.OWN_LOG)) {
                    runtime.terminal().stdout("LOG_COPY=" + log + " " + head);
                }
            });
            for (String problem : store.problems()) {
                runtime.terminal().stdout("LOG_PROBLEM=" + problem);
            }
            replication.ifPresent(settings -> {
                runtime.terminal().stdout("REPLICATION_ROLE=" + (settings.isMember() ? "member" : "leader"));
                if (settings.isMember()) {
                    runtime.terminal().stdout("REPLICATION_LEADER=" + settings.leaderNodeId());
                    runtime.terminal().stdout("REPLICATION_STATE="
                            + MemberReplication.recordedState(projectData).orElse("NO_COORDINATION_SERVER"));
                } else {
                    // The role file names the members; the running leader server only says how each is connected.
                    var recorded = LeaderReplication.recordedMembers(projectData);
                    settings.members().stream().sorted().forEach(node -> runtime.terminal().stdout("REPLICATION_MEMBER=" + node + " "
                            + recorded.map(members -> connection(members.get(node))).orElse("STATE=NO_COORDINATION_SERVER")));
                }
                // The reason, the time and the details of the last session that ended; more is in replication.log.
                ReplicationJournal.lastDisconnect(projectData)
                        .ifPresent(line -> runtime.terminal().stdout("REPLICATION_LAST_DISCONNECT=" + line));
            });
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("COORDINATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }

    /**
     * A member the running server has not recorded was named after its last write and has not connected since.
     */
    private static String connection(LeaderReplication.MemberConnection recorded) {
        return recorded == null ? "STATE=DISCONNECTED" : "STATE=" + recorded.state() + " since=" + recorded.since();
    }
}
