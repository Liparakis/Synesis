package org.synesis.cli.command.log;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.CoordinationCliSupport;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.persistence.LogDump;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Prints every coordination log this computer holds, in the order it applied them, and what the projections derive
 * from them. It only reads files, so it works while the project's runtime is stopped and changes nothing.
 */
@Command(name = "dump", mixinStandardHelpOptions = true,
        description = "Print every log this computer holds, in applied order, and what the projections derive.")
public final class LogDumpCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", description = "Initialized project directory.")
    private Path project;
    @Option(names = "--data", description = "Coordinator state directory.")
    private Path data;
    @Option(names = "--type", description = "Print only entries of this type, such as WORK_INTENT_ANNOUNCED or UNKNOWN. The summary is unchanged.")
    private String type;

    /**
     * Creates a log dump command.
     *
     * @param runtime composed CLI runtime
     */
    public LogDumpCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Prints the entries, the problems found while reading, and the summary.
     *
     * @return stable process exit code
     */
    @Override
    public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            LogDump.Dump dump = LogDump.read(CoordinationCliSupport.data(location, data), location.projectId());
            List<LogDump.Entry> entries = type == null ? dump.entries() : LogDump.only(dump.entries(), type.strip());
            runtime.terminal().stdout("LOG_DUMP=PASS");
            runtime.terminal().stdout("PROJECT_ID=" + location.projectId());
            if (type != null) {
                runtime.terminal().stdout("LOG_TYPE=" + type.strip());
            }
            runtime.terminal().stdout("LOG_ENTRIES=" + entries.size());
            entries.forEach(entry -> runtime.terminal().stdout("LOG_ENTRY=" + entry));
            dump.problems().forEach(problem -> runtime.terminal().stdout("LOG_PROBLEM=" + problem));
            dump.summary().forEach(fact -> runtime.terminal().stdout(fact.key() + "=" + fact.value()));
            return ExitCodes.OK;
        } catch (Exception failure) {
            return CoordinationCliSupport.fail(runtime, "LOG_DUMP_ERROR", failure);
        }
    }
}
