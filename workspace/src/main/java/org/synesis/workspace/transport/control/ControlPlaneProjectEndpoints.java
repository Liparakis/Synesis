package org.synesis.workspace.transport.control;

import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.API_VERSION;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.failure;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.requiredText;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.sendJson;
import static org.synesis.workspace.transport.control.ControlPlaneHttpJson.uuid;

import com.sun.net.httpserver.HttpExchange;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.synesis.workspace.discovery.KnownProjectRegistry;
import org.synesis.workspace.transport.control.ControlPlaneHttpHandler.SelectionGateway;

/**
 * Project selection and registry endpoints: the native folder picker, known-project registration
 * and removal, and the daemon-backed pending project selection.
 *
 * <p>Separate from the handler because these commands share no state with sessions or Link
 * onboarding; they depend only on the read model's registry and the daemon selection bridge.
 */
final class ControlPlaneProjectEndpoints {

    /**
     * Maximum accepted length of a textual UUID field.
     */
    private static final int MAX_UUID_LENGTH = 64;

    /**
     * Maximum accepted length of a project path.
     */
    private static final int MAX_PATH_LENGTH = 4_096;

    /**
     * Maximum time the native folder picker may keep the user's dialog open.
     */
    private static final Duration PICKER_TIMEOUT = Duration.ofMinutes(10);

    /**
     * Error code shared by every folder-picker failure.
     */
    private static final String PICKER_UNAVAILABLE_CODE = "PROJECT_PICKER_UNAVAILABLE";

    /**
     * Default folder-picker failure message.
     */
    private static final String PICKER_UNAVAILABLE_MESSAGE = "the Windows Explorer folder picker is unavailable";

    /**
     * Public-safe read model backing snapshots, diagnostics, and project registry access.
     */
    private final ControlPlaneReadModel readModel;

    /**
     * Bridge to the installation daemon's project-selection state.
     */
    private final SelectionGateway selectionGateway;

    /**
     * Creates the project endpoints.
     *
     * @param readModel        public-safe read model backing the project registry
     * @param selectionGateway authenticated local daemon selection bridge
     */
    ControlPlaneProjectEndpoints(ControlPlaneReadModel readModel, SelectionGateway selectionGateway) {
        this.readModel = readModel;
        this.selectionGateway = selectionGateway;
    }

    /**
     * Opens the native Windows folder picker and reports the chosen folder.
     *
     * <p>Blocks the calling handler thread until the user closes the dialog or
     * {@link #PICKER_TIMEOUT} elapses.
     *
     * @param exchange HTTP exchange
     * @throws IOException when the picker output cannot be read or the response written
     * @throws ApiFailure  503 when the picker is unavailable, times out, or returns an invalid path
     */
    void chooseProject(HttpExchange exchange) throws IOException, ApiFailure {
        if (GraphicsEnvironment.isHeadless() || !isWindows()) {
            throw pickerUnavailable(PICKER_UNAVAILABLE_MESSAGE);
        }
        Path picker = nativeFolderPicker();
        if (picker == null) {
            throw pickerUnavailable(PICKER_UNAVAILABLE_MESSAGE);
        }
        Process process = runPicker(picker);
        String output = removeTerminalLineBreak(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        int exitCode = process.exitValue();
        if (exitCode == 2 && output.isEmpty()) {
            sendJson(exchange, 200, Map.of("apiVersion", API_VERSION, "state", "CANCELLED", "path", "", "name", ""));
            return;
        }
        if (exitCode != 0 || output.isEmpty()) {
            throw pickerUnavailable(PICKER_UNAVAILABLE_MESSAGE);
        }
        Path selected;
        try {
            selected = Path.of(output).toAbsolutePath().normalize();
        } catch (InvalidPathException malformed) {
            throw pickerUnavailable("the Windows Explorer folder picker returned an invalid folder");
        }
        Path fileName = selected.getFileName();
        sendJson(exchange, 200, Map.of("apiVersion", API_VERSION, "state", "SELECTED", "path", selected.toString(), "name", fileName == null ? selected.toString() : fileName.toString()));
    }

    /**
     * Starts the picker and waits for it to exit.
     *
     * @param picker picker executable
     * @return the exited process, with stderr merged into stdout
     * @throws ApiFailure 503 when the picker cannot start, is interrupted, or exceeds {@link #PICKER_TIMEOUT}
     */
    private static Process runPicker(Path picker) throws ApiFailure {
        try {
            Process process = new ProcessBuilder(picker.toString()).redirectErrorStream(true).start();
            if (!process.waitFor(PICKER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw pickerUnavailable("the Windows Explorer folder picker timed out");
            }
            return process;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw pickerUnavailable("the Windows Explorer folder picker was interrupted");
        } catch (IOException unavailable) {
            throw pickerUnavailable(PICKER_UNAVAILABLE_MESSAGE);
        }
    }

    /**
     * Builds the failure shared by every folder-picker error.
     *
     * @param message client-visible detail
     * @return 503 failure
     */
    private static ApiFailure pickerUnavailable(String message) {
        return failure(503, PICKER_UNAVAILABLE_CODE, message);
    }

    /**
     * Checks whether the JVM runs on Windows.
     *
     * @return {@code true} when {@code os.name} contains {@code win}
     */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Locates the bundled folder-picker executable relative to the running CLI jar.
     *
     * <p>Only the packaged layout {@code <payload>/<dir>/synesis-cli.jar} is honoured, so the
     * route can never be pointed at an arbitrary executable.
     *
     * @return the picker executable, or {@code null} when not running from the packaged layout
     */
    private static Path nativeFolderPicker() {
        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(Pattern.quote(File.pathSeparator))) {
            try {
                Path jar = Path.of(entry).toAbsolutePath().normalize();
                Path fileName = jar.getFileName();
                if (fileName == null || !"synesis-cli.jar".equalsIgnoreCase(fileName.toString())) {
                    continue;
                }
                Path jarDirectory = jar.getParent();
                Path payload = jarDirectory == null ? null : jarDirectory.getParent();
                if (payload == null) {
                    continue;
                }
                Path picker = payload.resolve("bin").resolve("synesis-folder-picker.exe");
                if (Files.isRegularFile(picker)) {
                    return picker;
                }
            } catch (InvalidPathException ignored) {
                // Continue searching the remaining classpath entries.
            }
        }
        return null;
    }

    /**
     * Strips a single trailing {@code \n} or {@code \r\n}.
     *
     * @param value process output
     * @return the value without its terminal line break
     */
    private static String removeTerminalLineBreak(String value) {
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * Registers a folder as a known project.
     *
     * @param exchange HTTP exchange
     * @param body     request body containing {@code path}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the path is invalid or the registry rejects it
     */
    void registerProject(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        String rawPath = requiredText(body, "path", MAX_PATH_LENGTH);
        Path path;
        try {
            path = Path.of(rawPath).toAbsolutePath().normalize();
        } catch (InvalidPathException malformed) {
            throw failure(400, "PROJECT_PATH_INVALID", "project path is invalid");
        }
        try {
            readModel.registerKnownProject(path);
        } catch (KnownProjectRegistry.RegistryException rejected) {
            throw registryFailure(rejected);
        }
        sendJson(exchange, 200, Map.of("apiVersion", API_VERSION, "state", "REGISTERED", "projects", readModel.knownProjects()));
    }

    /**
     * Removes a known project from the registry.
     *
     * @param exchange HTTP exchange
     * @param body     request body containing {@code projectId}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the id is invalid or the registry rejects the removal
     */
    void removeProject(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        UUID projectId = uuid(requiredText(body, "projectId", MAX_UUID_LENGTH), "projectId");
        try {
            readModel.removeKnownProject(projectId);
        } catch (KnownProjectRegistry.RegistryException rejected) {
            throw registryFailure(rejected);
        }
        sendJson(exchange, 200, Map.of("apiVersion", API_VERSION, "state", "REMOVED", "projectId", projectId.toString(), "projects", readModel.knownProjects()));
    }

    /**
     * Maps a registry rejection to an HTTP failure; unknown codes are treated as 503.
     *
     * @param failure registry exception
     * @return API failure carrying the registry's own code and message
     */
    private static ApiFailure registryFailure(KnownProjectRegistry.RegistryException failure) {
        int status = switch (failure.code()) {
            case "CURRENT_PROJECT_PROTECTED", "PROJECT_NOT_FOUND", "IDENTITY_MISMATCH", "REGISTRY_FULL" -> 409;
            case "NOT_FOUND", "CONFLICT", "MALFORMED", "PROJECT_INVALID", "GIT_INIT_FAILED", "PROJECT_INITIALIZATION_FAILED" -> 400;
            default -> 503;
        };
        return failure(status, failure.code(), failure.getMessage());
    }

    /**
     * Reads a pending project selection from the daemon.
     *
     * @param exchange       HTTP exchange
     * @param rawSelectionId selection id taken from the path
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the id is invalid or the daemon reports failure
     */
    void selection(HttpExchange exchange, String rawSelectionId) throws IOException, ApiFailure {
        UUID selectionId = uuid(rawSelectionId, "selectionId");
        sendSelectionResult(exchange, daemonSelection(() -> selectionGateway.describe(selectionId)));
    }

    /**
     * Claims a project for a pending selection through the daemon.
     *
     * @param exchange HTTP exchange
     * @param body     request body containing {@code selectionId} and {@code projectId}
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when an id is invalid or the daemon reports failure
     */
    void selectProject(HttpExchange exchange, Map<String, Object> body) throws IOException, ApiFailure {
        UUID selectionId = uuid(requiredText(body, "selectionId", MAX_UUID_LENGTH), "selectionId");
        UUID projectId = uuid(requiredText(body, "projectId", MAX_UUID_LENGTH), "projectId");
        sendSelectionResult(exchange, daemonSelection(() -> selectionGateway.select(selectionId, projectId)));
    }

    /**
     * Sends a daemon result, converting an explicit {@code ok: false} into a failure.
     *
     * @param exchange HTTP exchange
     * @param result   daemon result
     * @throws IOException when the response cannot be written
     * @throws ApiFailure  when the daemon reported failure
     */
    private static void sendSelectionResult(HttpExchange exchange, Map<String, Object> result) throws IOException, ApiFailure {
        if (Boolean.FALSE.equals(result.get("ok"))) {
            throw selectionFailure(result);
        }
        sendJson(exchange, 200, result);
    }

    /**
     * Runs a daemon call, hiding transport detail from the client.
     *
     * @param call daemon call
     * @return daemon result
     * @throws ApiFailure 503 when the daemon cannot be reached
     */
    private static Map<String, Object> daemonSelection(SelectionCall call) throws ApiFailure {
        try {
            return call.run();
        } catch (IOException unavailable) {
            throw failure(503, "SELECTION_UNAVAILABLE", "local project selection is unavailable");
        }
    }

    /**
     * Maps a daemon-reported selection error to an HTTP failure; unknown codes are treated as 400.
     *
     * @param result daemon result whose {@code error} field names the failure
     * @return API failure with a generic message
     */
    private static ApiFailure selectionFailure(Map<String, Object> result) {
        String code = String.valueOf(result.getOrDefault("error", "SELECTION_FAILED"));
        int status = switch (code) {
            case "SELECTION_UNKNOWN" -> 404;
            case "SELECTION_EXPIRED" -> 410;
            case "SELECTION_CONSUMED", "PROJECT_NOT_ELIGIBLE", "PROJECT_UNAVAILABLE" -> 409;
            default -> 400;
        };
        return failure(status, code, "project selection cannot be completed");
    }

    /**
     * A daemon call that may fail with an {@link IOException}.
     */
    @FunctionalInterface
    private interface SelectionCall {

        /**
         * Performs the call.
         *
         * @return daemon result
         * @throws IOException when the daemon cannot be reached
         */
        Map<String, Object> run() throws IOException;
    }
}