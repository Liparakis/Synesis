package org.synesis.workspace.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recognises a command that does nothing but sleep (ADR-0099).
 *
 * <p>Agents told to wait slept instead of ending their turn, through {@code run_command} and through their own shell,
 * for up to fifteen minutes at a time, while the review they waited for was asked of them and went unanswered. A
 * pure sleep has no use in a Synesis project: an agent waits by ending its turn and is woken. A command that sleeps
 * and then does anything else is not one, so nothing that does work is refused by this rule.</p>
 */
public final class SleepCommands {

    private static final Pattern NUMBER = Pattern.compile("\\d+(\\.\\d+)?");
    private static final Pattern DURATION = Pattern.compile("\\d+(\\.\\d+)?[smhd]?");
    private static final Pattern PYTHON_SLEEP = Pattern.compile("(import time|from time import sleep|(time\\.)?sleep\\(\\s*\\d+(\\.\\d+)?\\s*\\))");
    private static final Pattern NODE_SLEEP = Pattern.compile("setTimeout\\(\\s*(\\(\\)\\s*=>\\s*\\{\\s*}|function\\s*\\(\\)\\s*\\{\\s*})\\s*,\\s*\\d+\\s*\\);?");
    private static final Set<String> SAYING = Set.of("echo", "true", ":", "write-output", "write-host");
    private static final Pattern PYTHON_PRINT = Pattern.compile("print\\((\"[^\"]*\"|'[^']*')?\\)");
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "::1");
    private static final Set<String> REDIRECTIONS = Set.of(">", ">>", "nul", "NUL", "/dev/null", ">nul", ">NUL", ">/dev/null", "2>&1", "2>/dev/null", "2>nul", "1>nul");

    private SleepCommands() {
    }

    /**
     * Tells whether a shell command line only sleeps: every command it chains with {@code &&}, {@code ;} or a new line
     * is a sleep.
     *
     * @param commandLine command line as a shell tool receives it
     * @return {@code true} when it does nothing but sleep
     */
    public static boolean onlySleeps(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return false;
        }
        List<List<String>> commands = split(commandLine);
        // "sleep 600 && echo done" is the same wait: saying something afterwards is not work.
        return commands.stream().anyMatch(SleepCommands::onlySleeps) && commands.stream().allMatch(command -> onlySleeps(command) || onlySays(command));
    }

    /**
     * Tells whether an argument vector only sleeps, directly or through a shell that runs a sleep.
     *
     * @param argv program and arguments, as {@code run_command} receives them
     * @return {@code true} when it does nothing but sleep
     */
    public static boolean onlySleeps(List<String> argv) {
        List<String> words = new ArrayList<>(argv);
        while (!words.isEmpty() && REDIRECTIONS.contains(words.getLast())) {
            words.removeLast();
        }
        if (words.isEmpty()) {
            return false;
        }
        List<String> rest = words.subList(1, words.size());
        return switch (program(words.getFirst())) {
            case "sleep" -> !rest.isEmpty() && rest.stream().allMatch(word -> DURATION.matcher(word).matches());
            case "start-sleep" -> rest.stream().anyMatch(word -> NUMBER.matcher(word).matches())
                    && rest.stream().allMatch(word -> NUMBER.matcher(word).matches() || word.startsWith("-"));
            case "timeout" -> rest.stream().anyMatch(word -> NUMBER.matcher(word).matches())
                    && rest.stream().allMatch(word -> NUMBER.matcher(word).matches() || word.equalsIgnoreCase("/t") || word.equalsIgnoreCase("/nobreak"));
            case "ping" -> rest.size() == 3 && Set.of("-n", "-c").contains(rest.get(0)) && NUMBER.matcher(rest.get(1)).matches() && LOOPBACK.contains(rest.get(2));
            case "python", "python3", "py" -> rest.size() == 2 && rest.get(0).equals("-c") && pythonOnlySleeps(rest.get(1));
            case "node" -> rest.size() == 2 && Set.of("-e", "--eval").contains(rest.get(0)) && NODE_SLEEP.matcher(rest.get(1).trim()).matches();
            case "sh", "bash", "zsh" -> rest.size() == 2 && rest.get(0).equals("-c") && onlySleeps(rest.get(1));
            case "cmd" -> afterFlag(rest, Set.of("/c", "/k")).map(SleepCommands::onlySleeps).orElse(false);
            case "powershell", "pwsh" -> afterFlag(rest, Set.of("-command", "-c")).map(SleepCommands::onlySleeps).orElse(false);
            default -> false;
        };
    }

    private static boolean onlySays(List<String> command) {
        return SAYING.contains(program(command.getFirst()));
    }

    private static String program(String word) {
        String name = word.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    private static boolean pythonOnlySleeps(String code) {
        String[] statements = code.split("[;\\n]");
        boolean sleeps = false;
        for (String statement : statements) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (PYTHON_PRINT.matcher(trimmed).matches()) {
                continue;
            }
            if (!PYTHON_SLEEP.matcher(trimmed).matches()) {
                return false;
            }
            sleeps |= trimmed.contains("sleep(");
        }
        return sleeps;
    }

    /** The command a shell is told to run: everything after its run-this flag, joined back into one line. */
    private static java.util.Optional<String> afterFlag(List<String> arguments, Set<String> flags) {
        for (int index = 0; index < arguments.size(); index++) {
            if (flags.contains(arguments.get(index).toLowerCase(Locale.ROOT))) {
                return index + 1 < arguments.size() ? java.util.Optional.of(String.join(" ", arguments.subList(index + 1, arguments.size()))) : java.util.Optional.empty();
            }
        }
        return java.util.Optional.empty();
    }

    /** Splits a command line into its chained commands, each into words; quotes group words and are removed. */
    private static List<List<String>> split(String commandLine) {
        List<List<String>> commands = new ArrayList<>();
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean inWord = false;
        for (int index = 0; index < commandLine.length(); index++) {
            char next = commandLine.charAt(index);
            if (quote != 0) {
                if (next == quote) {
                    quote = 0;
                } else {
                    word.append(next);
                }
            } else if (next == '"' || next == '\'') {
                quote = next;
                inWord = true;
            } else if (Character.isWhitespace(next) && next != '\n') {
                inWord = endWord(words, word, inWord);
            } else if (next == ';' || next == '\n' || (next == '&' && index + 1 < commandLine.length() && commandLine.charAt(index + 1) == '&')) {
                inWord = endWord(words, word, inWord);
                endCommand(commands, words);
                index += next == '&' ? 1 : 0;
            } else {
                word.append(next);
                inWord = true;
            }
        }
        endWord(words, word, inWord);
        endCommand(commands, words);
        return commands;
    }

    private static boolean endWord(List<String> words, StringBuilder word, boolean inWord) {
        if (inWord) {
            words.add(word.toString());
            word.setLength(0);
        }
        return false;
    }

    private static void endCommand(List<List<String>> commands, List<String> words) {
        if (!words.isEmpty()) {
            commands.add(List.copyOf(words));
            words.clear();
        }
    }
}
