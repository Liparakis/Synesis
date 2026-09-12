package org.synesis.cli.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * Bounded wire contract for the installation daemon's authenticated local
 * requests.
 *
 * <p>The protocol is deliberately smaller than a general RPC system. It is a
 * single JSON object per line and carries no project authority material.</p>
 */
public final class DaemonProtocol {

  /** Maximum UTF-8 bytes accepted for one request line. */
  public static final int MAX_REQUEST_BYTES = 8 * 1024;
  /** Maximum characters accepted for the IPC bearer. */
  public static final int MAX_TOKEN_CHARS = 256;
  /** Maximum characters accepted for a project path. */
  public static final int MAX_PROJECT_CHARS = 4 * 1024;
  /** Maximum characters accepted for a deep-link URI. */
  public static final int MAX_URI_CHARS = 64 * 1024;

  private DaemonProtocol() {
  }

  /**
   * Parses and validates one request line.
   *
   * @param line request line without its transport newline
   * @return validated request
   * @throws IOException when the line is malformed, oversized, or unsupported
   */
  public static Request parse(String line) throws IOException {
    Objects.requireNonNull(line, "line");
    if (line.isBlank() || line.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
      throw new IOException("daemon request is empty or oversized");
    }
    final Object value;
    try {
      value = ProviderJson.parse(line);
    } catch (RuntimeException failure) {
      throw new IOException("daemon request is malformed", failure);
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new IOException("daemon request must be an object");
    }
    String token = boundedText(map.get("token"), "token", MAX_TOKEN_CHARS, true);
    String operationName = boundedText(map.get("operation"), "operation", 32, true);
    Operation operation;
    try {
      operation = Operation.valueOf(operationName);
    } catch (IllegalArgumentException failure) {
      throw new IOException("daemon operation is unsupported", failure);
    }
    String project = boundedText(map.get("project"), "project", MAX_PROJECT_CHARS, false);
    String uri = boundedText(map.get("uri"), "uri", MAX_URI_CHARS, false);
    String selectionId = boundedText(map.get("selectionId"), "selectionId", 64, false);
    String projectId = boundedText(map.get("projectId"), "projectId", 64, false);
    Object openBrowserValue = map.get("openBrowser");
    boolean openBrowser = openBrowserValue == null || openBrowserValue instanceof Boolean open
        && open;
    if (openBrowserValue != null && !(openBrowserValue instanceof Boolean)) {
      throw new IOException("openBrowser is invalid");
    }
    if ((operation == Operation.OPEN_UI || operation == Operation.OPEN_PROJECT)
        && project == null) {
      throw new IOException("project is required for " + operation);
    }
    if (operation == Operation.OPEN_URI && uri == null) {
      throw new IOException("uri is required for OPEN_URI");
    }
    if ((operation == Operation.GET_SELECTION || operation == Operation.SELECT_PROJECT)
        && selectionId == null) {
      throw new IOException("selectionId is required for " + operation);
    }
    if (operation == Operation.SELECT_PROJECT && projectId == null) {
      throw new IOException("projectId is required for SELECT_PROJECT");
    }
    if (selectionId != null && !uuid(selectionId)) {
      throw new IOException("selectionId is invalid");
    }
    if (projectId != null && !uuid(projectId)) {
      throw new IOException("projectId is invalid");
    }
    return new Request(token, operation, project, uri, selectionId, projectId, openBrowser);
  }

  /**
   * Serializes a request for tests and the local client.
   *
   * @param values JSON-compatible request values
   * @return one request line
   */
  public static String writeRequest(Map<String, Object> values) {
    return ProviderJson.write(Objects.requireNonNull(values, "values"));
  }

  private static String boundedText(Object value, String field, int limit, boolean required)
      throws IOException {
    if (value == null) {
      if (required) {
        throw new IOException(field + " is required");
      }
      return null;
    }
    if (!(value instanceof String text) || text.isBlank() || text.length() > limit) {
      throw new IOException(field + " is invalid or oversized");
    }
    return text;
  }

  private static boolean uuid(String value) {
    try {
      UUID.fromString(value);
      return true;
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  /** Supported local daemon operations. */
  public enum Operation {
    /** Open the current project's browser UI through the daemon. */
    OPEN_UI,
    /** Open a selected project through the daemon. */
    OPEN_PROJECT,
    /** Hand a bounded Synesis URI to the daemon. */
    OPEN_URI,
    /** Return non-secret daemon status. */
    STATUS,
    /** Read the safe projection of a pending local project selection. */
    GET_SELECTION,
    /** Claim a pending local project selection. */
    SELECT_PROJECT
  }

  /**
   * Immutable parsed daemon request.
   *
   * @param token authenticated local bearer; safe only inside the daemon call
   * @param operation supported installation operation
   * @param project normalized project path when the operation targets a project
   * @param uri bounded Synesis URI when the operation hands off a deep link
   * @param selectionId pending selection identifier, when applicable
   * @param projectId selected project identifier, when applicable
   * @param openBrowser whether the daemon may open the default browser
   */
  public record Request(String token, Operation operation, String project, String uri,
                        String selectionId, String projectId, boolean openBrowser) {
  }
}
