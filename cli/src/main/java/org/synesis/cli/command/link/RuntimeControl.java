package org.synesis.cli.command.link;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * The control plane of a project's running runtime, the process that owns the project's Link sessions. A command
 * line client reaches it so that the sessions it establishes stay with that runtime, and are the ones replication
 * runs on, instead of ending with the command (ADR-0098).
 */
interface RuntimeControl {

    /**
     * Opens the control plane of a project's runtime.
     */
    @FunctionalInterface
    interface Source {

        /**
         * @param project project directory
         * @return the runtime's control plane, starting the runtime if it is not running
         * @throws IOException when the runtime cannot be reached
         */
        RuntimeControl open(Path project) throws IOException;
    }

    /**
     * The runtime answered with an error.
     */
    final class Rejected extends Exception {

        private static final long serialVersionUID = 1L;

        private final String code;

        Rejected(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /**
     * Runs one control-plane command ({@code POST /api/v1/commands/<name>}).
     *
     * @param name command name
     * @param body JSON-compatible request body
     * @return the JSON response
     * @throws IOException when the runtime cannot be reached or answers with something that is not JSON
     * @throws Rejected    when the runtime refuses the command
     */
    Map<String, Object> command(String name, Map<String, Object> body) throws IOException, Rejected;

    /**
     * Reads one control-plane route ({@code GET /api/v1/<route>}).
     *
     * @param route route below {@code /api/v1/}
     * @return the JSON response
     * @throws IOException when the runtime cannot be reached or answers with something that is not JSON
     * @throws Rejected    when the runtime refuses the read
     */
    Map<String, Object> read(String route) throws IOException, Rejected;
}
