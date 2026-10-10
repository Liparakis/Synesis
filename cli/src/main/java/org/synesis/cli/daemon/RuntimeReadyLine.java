package org.synesis.cli.daemon;

import java.io.IOException;
import java.net.URI;

/**
 * The line a project runtime prints once it serves, and the parser the installation daemon uses to
 * find it. External agents and scripts read this line, so key names and order are a contract.
 */
public final class RuntimeReadyLine {

    private static final String MARKER = "COORDINATION_SERVE_READY";
    private static final String ENDPOINT = "endpoint";
    private static final String CONTROL_BOOTSTRAP = "controlBootstrap";
    private static final String CONTROL_COMMAND = "controlCommand";

    private RuntimeReadyLine() {
    }

    /**
     * Formats the ready line.
     *
     * @param endpoint            runtime endpoint without a trailing slash
     * @param project             project identifier
     * @param nodeId              node identifier
     * @param hostInstanceId      lifecycle host instance identifier
     * @param codexLifecycleRoute lifecycle route
     * @param controlBootstrap    one-time UI bootstrap token
     * @param controlCommand      runtime command token
     * @return the line to print
     */
    public static String format(String endpoint, String project, String nodeId, String hostInstanceId, String codexLifecycleRoute, String controlBootstrap, String controlCommand) {
        return MARKER + " " + ENDPOINT + "=" + endpoint + "/ project=" + project + " nodeId=" + nodeId + " hostInstanceId=" + hostInstanceId + " codexLifecycleRoute=" + codexLifecycleRoute
                + " controlPlaneRoute=/api/v1 " + CONTROL_BOOTSTRAP + "=" + controlBootstrap + " " + CONTROL_COMMAND + "=" + controlCommand + " uiRoute=/";
    }

    /**
     * Reports whether a runtime output line is the ready line.
     *
     * @param line one output line
     * @return whether it starts with the ready marker
     */
    static boolean isReady(String line) {
        return line.startsWith(MARKER + " ");
    }

    /**
     * Reads the fields the daemon needs from a ready line.
     *
     * @param line a line for which {@link #isReady(String)} holds
     * @return endpoint and tokens
     * @throws IOException when a required key is missing
     */
    static Parsed parse(String line) throws IOException {
        return new Parsed(URI.create(field(line, ENDPOINT)), field(line, CONTROL_BOOTSTRAP), field(line, CONTROL_COMMAND));
    }

    private static String field(String line, String name) throws IOException {
        String prefix = name + "=";
        for (String token : line.split(" ")) {
            if (token.startsWith(prefix)) {
                return token.substring(prefix.length());
            }
        }
        throw new IOException("project_runtime_ready_missing_" + name);
    }

    /**
     * Fields of a ready line the daemon uses.
     *
     * @param endpoint     runtime endpoint as printed
     * @param bootstrap    one-time UI bootstrap token
     * @param commandToken runtime command token
     */
    record Parsed(URI endpoint, String bootstrap, String commandToken) {

    }
}
