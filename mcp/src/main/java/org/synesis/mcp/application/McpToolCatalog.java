package org.synesis.mcp.application;

import java.util.List;

/**
 * Authoritative prerelease MCP tool-name catalog.
 *
 * <p>The wire names are intentionally raw. Provider configuration may add its
 * own display namespace, but Synesis advertises and dispatches only these
 * names. The list is immutable and ordered to keep tools/list deterministic.
 *
 * @since 1.0
 */
public final class McpToolCatalog {

    /** MCP session establishment and lane binding tool. */
    public static final String ENSURE_SESSION = "ensure_session";
    /** MCP read tool. */
    public static final String READ_FILE = "read_file";
    /** MCP revision-checked mutation tool. */
    public static final String APPLY_PATCH = "apply_patch";
    /** MCP approved command-intent tool. */
    public static final String RUN_COMMAND = "run_command";
    /** MCP durable inbox/action discovery tool. */
    public static final String GET_NEXT_ACTION = "get_next_action";
    /** MCP coordination request tool. */
    public static final String REQUEST_COORDINATION = "request_coordination";
    /** MCP coordination response and validation tool. */
    public static final String RESPOND_COORDINATION = "respond_coordination";
    /** MCP capability implementation publication tool. */
    public static final String PUBLISH_CAPABILITY_IMPLEMENTATION = "publish_capability_implementation";
    /** MCP isolated lane completion tool. */
    public static final String FINISH_LANE = "finish_lane";
    /** MCP isolated lane cancellation tool. */
    public static final String CANCEL_LANE = "cancel_lane";

    /** Ordered raw names advertised by tools/list. */
    public static final List<String> RAW_NAMES = List.of(
            ENSURE_SESSION, READ_FILE, APPLY_PATCH, RUN_COMMAND, GET_NEXT_ACTION,
            REQUEST_COORDINATION, RESPOND_COORDINATION, PUBLISH_CAPABILITY_IMPLEMENTATION,
            FINISH_LANE, CANCEL_LANE);

    private McpToolCatalog() {
        // Constants only.
    }
}
