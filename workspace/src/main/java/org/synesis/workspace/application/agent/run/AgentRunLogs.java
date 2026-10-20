package org.synesis.workspace.application.agent.run;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.synesis.workspace.application.provider.ProviderRegistry;
import org.synesis.workspace.provider.AgentLogEntry;
import org.synesis.workspace.provider.ProviderIntegration;

/**
 * Reads back what {@code synesis agents run} kept of its agents: one directory for each run under
 * {@code agent-runs/<project>/} of the application state, holding one log file for each process the run started or
 * resumed ({@link ProviderAgentProcesses}). The files hold the provider's output as it was printed, so this reads them
 * through the provider's own decoder ({@link ProviderIntegration.HeadlessSession#logEntries}) and never changes them.
 *
 * <p>The files can hold anything the agent read, secrets included. What is shown is shortened to one line and has
 * values of secret-looking fields, bearer tokens, well-known token shapes, private keys and recovery words replaced
 * first. That is a pattern match, not a guarantee: a secret in a shape none of them names would be shown, which is
 * why only a short head of each entry is.</p>
 */
public final class AgentRunLogs {

    private static final String RUNS = "agent-runs";
    private static final Pattern RUN_NAME = Pattern.compile("run-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern LOG_NAME = Pattern.compile("([a-z0-9]+)-(\\d+)-(\\d+)\\.log");
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{Cc}\\p{Cf}\\u2028\\u2029\\s]+");
    private static final String REDACTED = "[redacted]";
    /** The separator between a field's name and its value, in plain text or in JSON text that is itself escaped. */
    private static final String SEPARATOR = "(\\\\*[\"']?\\s*[:=]\\s*\\\\*[\"']?)";

    /** What is replaced and with what; the rules that name a field keep its name and separator. */
    private record Rule(Pattern pattern, String replacement) {
    }

    private static final List<Rule> SECRETS = List.of(
            new Rule(Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)"), "[redacted private key]"),
            // Everything to the end of the value: the words of a recovery key hold spaces.
            new Rule(Pattern.compile("(?i)(recovery[_ -]?(?:words|phrase|key)|mnemonic|seed[_ -]?phrase)" + SEPARATOR + "[^\"'\\\\\\n]*"), "$1$2" + REDACTED),
            new Rule(Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}"), "$1 " + REDACTED),
            // Starts at the keyword, not before it: a prefix that may be any length makes the match quadratic on a long word.
            new Rule(Pattern.compile("(?i)((?:token|secret|passw(?:or)?d|passphrase|api[_-]?key|private[_-]?key|access[_-]?key|authorization|credential|cookie)[\\w.-]*)" + SEPARATOR + "[^\\s\"'\\\\,;&}\\]]+"), "$1$2" + REDACTED),
            new Rule(Pattern.compile("\\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[abprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{30,})"), REDACTED),
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*"), REDACTED));

    private AgentRunLogs() {
    }

    /**
     * One log file of a run.
     *
     * @param agent  the agent that was started, {@code <provider>#<number>} as {@code agents run} names it
     * @param launch which start or resume of the run wrote the file, counted from 1 over all agents of the run
     * @param file   the log file
     */
    public record Log(String agent, int launch, Path file) {
    }

    /**
     * Finds a run of a project.
     *
     * @param stateRoot the application state root
     * @param projectId the project
     * @param name      the run's directory name, {@code run-<uuid>}, or {@code null} for the newest run
     * @return the run directory, or empty when the project has no such run
     * @throws IOException              when the runs cannot be listed
     * @throws IllegalArgumentException when the name is not the name of a run
     */
    public static Optional<Path> run(Path stateRoot, UUID projectId, String name) throws IOException {
        Path runs = stateRoot.resolve(RUNS).resolve(projectId.toString());
        if (name != null) {
            if (!RUN_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("a run is named run-<uuid>: " + name);
            }
            return Optional.of(runs.resolve(name)).filter(Files::isDirectory);
        }
        if (!Files.isDirectory(runs)) {
            return Optional.empty();
        }
        try (Stream<Path> listing = Files.list(runs)) {
            // A directory's time moves when a process of the run writes its first log file into it.
            return listing.filter(Files::isDirectory).filter(path -> RUN_NAME.matcher(path.getFileName().toString()).matches())
                    .max(Comparator.comparing((Path path) -> modified(path)).thenComparing(Path::getFileName));
        }
    }

    private static java.nio.file.attribute.FileTime modified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException unreadable) {
            return java.nio.file.attribute.FileTime.fromMillis(0L);
        }
    }

    /**
     * Lists the log files of a run in the order the processes started.
     *
     * @param run   a run directory
     * @param agent {@code claude} for every agent of that provider, {@code claude#1} or {@code claude-1} for one, or
     *              {@code null} for all
     * @return the log files
     * @throws IOException when the run cannot be listed
     */
    public static List<Log> logs(Path run, String agent) throws IOException {
        String wanted = agent == null ? null : agent.strip().replace('#', '-');
        List<Log> logs = new ArrayList<>();
        try (Stream<Path> listing = Files.list(run)) {
            for (Path file : listing.toList()) {
                Matcher name = LOG_NAME.matcher(file.getFileName().toString());
                // A link is not a log the run wrote, so it is never followed to read something else.
                if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) && name.matches() && (wanted == null || wanted.equals(name.group(1)) || wanted.equals(name.group(1) + "-" + name.group(2)))) {
                    logs.add(new Log(name.group(1) + "#" + name.group(2), Integer.parseInt(name.group(3)), file));
                }
            }
        }
        logs.sort(Comparator.comparingInt(Log::launch));
        return logs;
    }

    /**
     * Decodes a log file with its provider's decoder and makes each entry safe to show: secrets replaced, one line,
     * shortened.
     *
     * @param log a log file of a run
     * @return what the agent did, in order; empty when the provider has no decoder
     * @throws IOException when the file cannot be read
     */
    public static List<AgentLogEntry> read(Log log) throws IOException {
        ProviderIntegration provider = ProviderRegistry.find(log.agent().substring(0, log.agent().indexOf('#')));
        if (provider == null || provider.headlessSession().isEmpty()) {
            return List.of();
        }
        List<AgentLogEntry> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(log.file()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (AgentLogEntry entry : provider.headlessSession().orElseThrow().logEntries(line)) {
                    entries.add(new AgentLogEntry(entry.kind(), clean(entry.name(), 80), clean(entry.text(), limit(entry.kind()))));
                }
            }
        }
        return entries;
    }

    private static int limit(AgentLogEntry.Kind kind) {
        return switch (kind) {
            case SAID -> 600;
            case CALLED -> 300;
            case RESULT -> 240;
            case FAILED -> 400;
        };
    }

    /**
     * Makes text safe to print on one line: secrets replaced, control and formatting characters and line breaks turned
     * into single spaces, and the end cut off with the number of characters left out.
     *
     * @param text  text an agent or a tool wrote
     * @param limit longest result before the note about what was cut
     * @return the text to show
     */
    static String clean(String text, int limit) {
        String safe = text;
        for (Rule secret : SECRETS) {
            safe = secret.pattern().matcher(safe).replaceAll(secret.replacement());
        }
        safe = WHITESPACE.matcher(safe).replaceAll(" ").strip();
        return safe.length() <= limit ? safe : safe.substring(0, limit) + "... (+" + (safe.length() - limit) + " characters)";
    }
}
