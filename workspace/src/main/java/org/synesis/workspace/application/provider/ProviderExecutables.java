package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds a provider's executable on a search path and turns a command into something the platform
 * can start. Starting an agent and discovering one must resolve the executable the same way, or
 * Synesis would report an agent as found that it then cannot start.
 */
public final class ProviderExecutables {

    private static final java.util.regex.Pattern CMD_QUOTED = java.util.regex.Pattern.compile("[\\s<>&|^]");

    private ProviderExecutables() {
    }

    /**
     * Finds an executable by name on a search path.
     *
     * <p>Windows starts only {@code .exe} files by bare name and provider tools are also shipped as
     * {@code .cmd} shims, so those extensions are tried in that order. Elsewhere the file must be
     * executable. Nothing is read from the file.</p>
     *
     * @param name         executable name without extension
     * @param pathVariable the search path, separated by the platform's path separator; may be {@code null}
     * @param windows      whether to resolve as Windows does
     * @return the first match in search-path order, or empty
     */
    public static Optional<Path> find(String name, String pathVariable, boolean windows) {
        if (pathVariable == null) {
            return Optional.empty();
        }
        for (String directory : pathVariable.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            for (String extension : windows ? List.of(".exe", ".cmd", ".bat") : List.of("")) {
                try {
                    Path candidate = Path.of(directory, name + extension);
                    if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                        return Optional.of(candidate);
                    }
                } catch (java.nio.file.InvalidPathException ignored) {
                    // A malformed search-path entry cannot hold the executable.
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves a command's executable so a script shim can be started as reliably as a binary.
     *
     * <p>The arguments passed to the command interpreter are built by Synesis from validated tokens and
     * paths, never from a prompt. Other platforms start the command as it is.</p>
     *
     * @param command      argv whose first element is the executable name
     * @param pathVariable the search path; may be {@code null}
     * @param windows      whether to resolve as Windows does
     * @return argv that can be given to a process builder
     * @throws IOException when the executable is not on the search path or an argument cannot be quoted safely
     */
    public static List<String> command(List<String> command, String pathVariable, boolean windows) throws IOException {
        if (!windows) {
            return command;
        }
        String name = command.getFirst();
        Path found = find(name, pathVariable, true).orElseThrow(() -> new IOException("provider executable not found on PATH: " + name));
        List<String> resolved = new ArrayList<>(command);
        resolved.set(0, found.toAbsolutePath().toString());
        if (found.getFileName().toString().endsWith(".exe")) {
            return resolved;
        }
        // The interpreter reads < > & | ^ outside quotes as its own operators (a Codex hook's
        // trust key holds < and >), and /c alone strips the first and last quote of a line that
        // starts with one, which breaks a shim in a folder with a space. So the line goes as one
        // argument under /s, which strips only the quotes around it, with each argument that holds
        // a space or an operator quoted inside it.
        StringBuilder line = new StringBuilder("\"");
        for (String argument : resolved) {
            if (argument.contains("\"")) {
                throw new IOException("a provider argument holds a quote: " + argument);
            }
            line.append(line.length() == 1 ? "" : " ").append(CMD_QUOTED.matcher(argument).find() ? "\"" + argument + "\"" : argument);
        }
        return List.of("cmd.exe", "/d", "/s", "/c", line.append('"').toString());
    }
}
