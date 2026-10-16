package org.synesis.cli.command.project;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.daemon.DaemonClient;
import org.synesis.cli.exit.ExitCodes;

import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.persistence.ReplicationSettings;

import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

import picocli.CommandLine.Option;

/**
 * Base of the commands that change this computer's role in a project that runs on several computers (ADR-0098):
 * they edit {@code replication.properties} in the project's coordination folder, which every process that opens the
 * log reads, and print what they did as {@code KEY=VALUE} lines.
 *
 * <p>The role file is state of this computer and is never replicated. A refusal is printed as {@code ERROR=<code>}
 * and exits {@link ExitCodes#LOCAL_CONFIGURATION}; nothing is written on a refusal.</p>
 */
abstract class ProjectRoleCommand implements Callable<Integer> {

    /**
     * Stops a project's runtime so that it starts again with the role now on disk; true when one was stopped.
     */
    @FunctionalInterface
    interface RuntimeStopper {

        boolean stop(Path project) throws IOException;
    }

    /**
     * Why a role change is refused.
     */
    static final class Refusal extends Exception {

        private static final long serialVersionUID = 1L;

        private final String code;

        Refusal(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /**
     * This project on this computer: where its role file is, and the role found there.
     *
     * @param location the project
     * @param settings the role file's content, or empty when none exists
     */
    record Role(ProjectLocation location, Optional<ReplicationSettings> settings) {

        Path coordination() {
            return location.coordinationDirectory();
        }

        /**
         * The settings of the computer that decides, or a refusal when this computer is not that.
         */
        ReplicationSettings leader() throws Refusal {
            ReplicationSettings found = settings.orElseThrow(() -> new Refusal("NOT_A_LEADER"));
            if (found.isMember()) {
                throw new Refusal("ALREADY_A_MEMBER");
            }
            return found;
        }

        void write(ReplicationSettings changed) throws IOException {
            changed.write(coordination());
        }
    }

    /** Never starts anything: the daemon is asked only when it already runs. */
    static final RuntimeStopper DAEMON = DaemonClient::stopProjectRuntime;

    final CliRuntime runtime;
    private final RuntimeStopper stopper;
    @Option(names = "--project", description = "Initialized project directory (default: the current directory).")
    private String project;

    ProjectRoleCommand(CliRuntime runtime, RuntimeStopper stopper) {
        this.runtime = runtime;
        this.stopper = stopper;
    }

    /**
     * Applies the change.
     *
     * @param role this project's role as found
     * @throws Refusal when the change is not allowed
     * @throws IOException when the role file cannot be written
     * @throws Exception when the node identity cannot be loaded
     */
    abstract void apply(Role role) throws Exception;

    /**
     * Whether the running project runtime must start again to see the change. A change of the leader's member list
     * does not: the leader reads it per request.
     */
    abstract boolean needsRuntimeRestart();

    @Override
    public final Integer call() {
        try {
            ProjectLocation location = ProjectOptions.locate(runtime, project);
            Optional<ReplicationSettings> settings;
            try {
                settings = ReplicationSettings.read(location.coordinationDirectory());
            } catch (CoordinationException unreadable) {
                throw new Refusal(unreadable.code());
            }
            apply(new Role(location, settings));
            if (needsRuntimeRestart()) {
                runtime.terminal().stdout("RUNTIME_STOPPED=" + stopper.stop(location.root()));
            }
            return ExitCodes.OK;
        } catch (Refusal refusal) {
            runtime.terminal().stderr("ERROR=" + refusal.code());
        } catch (ProjectApplicationException failure) {
            runtime.terminal().stderr("ERROR=" + failure.code());
        } catch (CoordinationException failure) {
            runtime.terminal().stderr("ERROR=" + failure.code());
        } catch (IOException failure) {
            runtime.terminal().stderr("ERROR=PROJECT_STORAGE_FAILED");
        } catch (Exception failure) {
            runtime.terminal().stderr("ERROR=PROJECT_INVALID");
        }
        return ExitCodes.LOCAL_CONFIGURATION;
    }

    /**
     * Refuses a remote name the clone does not have, so that a typo surfaces here and not at the first push.
     *
     * @param role this project
     * @param name remote name
     * @throws Refusal {@code CODE_REMOTE_UNKNOWN}
     */
    static void requireRemote(Role role, String name) throws Refusal {
        if (name.startsWith("-")) {
            throw new Refusal("CODE_REMOTE_INVALID");
        }
        try {
            if (GitProcessRunner.runResult(role.location().root(), "remote", "get-url", name).exitCode() == 0) {
                return;
            }
        } catch (IOException unreadable) {
            // A clone Git cannot be run in has no remote to name.
        }
        throw new Refusal("CODE_REMOTE_UNKNOWN");
    }
}
