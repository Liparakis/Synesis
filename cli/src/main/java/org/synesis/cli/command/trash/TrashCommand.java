package org.synesis.cli.command.trash;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.collaboration.WorkTrash;
import org.synesis.workspace.application.project.ProjectLocation;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Parent command for the trash (ADR-0098 step 7): work taken over, finished after its goal was met, or given up in a
 * dispute, kept until a person restores or empties it.
 */
@Command(name = "trash", description = "See, restore or empty work that went to the trash.", mixinStandardHelpOptions = true)
public final class TrashCommand {

    /**
     * Creates the parent command.
     */
    public TrashCommand() {
    }

    /**
     * Lists the trash, one line per piece of work.
     */
    @Command(name = "list", description = "List the work in the trash.", mixinStandardHelpOptions = true)
    public static final class List implements Callable<Integer> {

        private final CliRuntime runtime;

        @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
        private String project;

        /**
         * Creates the command.
         *
         * @param runtime CLI runtime
         */
        public List(CliRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public Integer call() {
            try {
                ProjectLocation location = ProjectOptions.locate(runtime, project);
                java.util.List<Map<String, Object>> trash = WorkTrash.describe(location.openCoordinationStore().trashProjection());
                for (Map<String, Object> item : trash) {
                    runtime.terminal().stdout("TRASH=" + item.get("laneId") + " EPOCH=" + item.get("epoch") + " REASON=" + item.get("reason")
                            + " COMMIT=" + item.get("commit") + " TRASHED_BY=" + item.get("trashedBy")
                            + (Boolean.TRUE.equals(item.get("emptied")) ? " EMPTIED" : "")
                            + (((java.util.List<?>) item.get("restoredInto")).isEmpty() ? "" : " RESTORED_INTO=" + String.join(",",
                                    ((java.util.List<?>) item.get("restoredInto")).stream().map(String::valueOf).toList())));
                }
                runtime.terminal().stdout("TRASH_ITEMS=" + trash.size());
                return ExitCodes.OK;
            } catch (Exception failure) {
                runtime.terminal().stderr("TRASH_ERROR=" + failure.getMessage());
                return ExitCodes.LOCAL_CONFIGURATION;
            }
        }
    }

    /**
     * Brings trashed work back in a work group of its own.
     */
    @Command(name = "restore", description = "Bring trashed work back in a work group of its own; each producer lane announced there starts with the work.",
            mixinStandardHelpOptions = true)
    public static final class Restore implements Callable<Integer> {

        private final CliRuntime runtime;

        @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
        private String project;

        @Parameters(index = "0", description = "Lane, as `synesis trash list` prints it.")
        private String lane;

        @Parameters(index = "1", description = "Epoch, as `synesis trash list` prints it.")
        private long epoch;

        /**
         * Creates the command.
         *
         * @param runtime CLI runtime
         */
        public Restore(CliRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public Integer call() {
            try {
                ProjectLocation location = ProjectOptions.locate(runtime, project);
                UUID group = WorkTrash.restore(location, location.loadOrCreateIdentity(), UUID.fromString(lane), epoch);
                runtime.terminal().stdout("TRASH_RESTORED=" + lane + " EPOCH=" + epoch + " WORK_GROUP=" + group);
                return ExitCodes.OK;
            } catch (Exception failure) {
                runtime.terminal().stderr("TRASH_NOT_RESTORED=" + failure.getMessage());
                return ExitCodes.COMMAND_FAILED;
            }
        }
    }

    /**
     * Deletes trashed work for good, on every computer; asks for {@code --yes}.
     */
    @Command(name = "empty", description = "Delete trashed work for good, on every computer. Cannot be undone.", mixinStandardHelpOptions = true)
    public static final class Empty implements Callable<Integer> {

        private final CliRuntime runtime;

        @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
        private String project;

        @Option(names = "--yes", description = "Confirm that the work is deleted for good.")
        private boolean confirmed;

        @Parameters(index = "0", description = "Lane, as `synesis trash list` prints it.")
        private String lane;

        @Parameters(index = "1", description = "Epoch, as `synesis trash list` prints it.")
        private long epoch;

        /**
         * Creates the command.
         *
         * @param runtime CLI runtime
         */
        public Empty(CliRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public Integer call() {
            if (!confirmed) {
                runtime.terminal().stderr("TRASH_NOT_EMPTIED=CONFIRMATION_REQUIRED add --yes to delete this work for good, on every computer");
                return ExitCodes.COMMAND_FAILED;
            }
            try {
                ProjectLocation location = ProjectOptions.locate(runtime, project);
                WorkTrash.empty(location, location.loadOrCreateIdentity(), UUID.fromString(lane), epoch);
                runtime.terminal().stdout("TRASH_EMPTIED=" + lane + " EPOCH=" + epoch);
                return ExitCodes.OK;
            } catch (Exception failure) {
                runtime.terminal().stderr("TRASH_NOT_EMPTIED=" + failure.getMessage());
                return ExitCodes.COMMAND_FAILED;
            }
        }
    }
}
