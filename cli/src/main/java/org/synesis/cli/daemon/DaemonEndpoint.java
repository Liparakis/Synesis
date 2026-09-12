package org.synesis.cli.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;

/**
 * Reads and writes the daemon's non-authoritative local endpoint document.
 */
final class DaemonEndpoint {

  static final int PROTOCOL_VERSION = 1;
  private static final String DIRECTORY = "daemon";
  private static final String ENDPOINT_FILE = "endpoint.json";
  private static final String LOCK_FILE = "daemon.lock";

  private DaemonEndpoint() {
  }

  static Path directory() {
    return AdministrativeStateLocator.applicationStateRoot().resolve(DIRECTORY)
        .toAbsolutePath().normalize();
  }

  static Path endpointFile() {
    return endpointFile(directory());
  }

  static Path lockFile() {
    return lockFile(directory());
  }

  static Path endpointFile(Path directory) {
    return Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize()
        .resolve(ENDPOINT_FILE);
  }

  static Path lockFile(Path directory) {
    return Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize()
        .resolve(LOCK_FILE);
  }

  static void write(int port, String token, long pid) throws IOException {
    write(endpointFile(), port, token, pid);
  }

  static void write(Path endpointFile, int port, String token, long pid) throws IOException {
    Objects.requireNonNull(token, "token");
    Path target = Objects.requireNonNull(endpointFile, "endpoint file").toAbsolutePath().normalize();
    Files.createDirectories(target.getParent());
    Path temporary = target.resolveSibling(ENDPOINT_FILE + ".tmp-" + pid);
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("protocolVersion", PROTOCOL_VERSION);
    value.put("port", port);
    value.put("token", token);
    value.put("pid", pid);
    Files.writeString(temporary, ProviderJson.write(value) + System.lineSeparator(),
        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    try {
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  static Record read() throws IOException {
    return read(endpointFile());
  }

  static Record read(Path endpointFile) throws IOException {
    String raw = Files.readString(
        Objects.requireNonNull(endpointFile, "endpoint file").toAbsolutePath().normalize(),
        StandardCharsets.UTF_8);
    Object parsed;
    try {
      parsed = ProviderJson.parse(raw);
    } catch (RuntimeException failure) {
      throw new IOException("daemon endpoint is malformed", failure);
    }
    if (!(parsed instanceof Map<?, ?> map)) {
      throw new IOException("daemon endpoint is not an object");
    }
    long version = number(map.get("protocolVersion"), "protocolVersion");
    long port = number(map.get("port"), "port");
    long pid = number(map.get("pid"), "pid");
    Object tokenValue = map.get("token");
    if (version != PROTOCOL_VERSION || port < 1 || port > 65_535 || pid < 1
        || !(tokenValue instanceof String token) || token.isBlank()
        || token.length() > DaemonProtocol.MAX_TOKEN_CHARS) {
      throw new IOException("daemon endpoint is invalid");
    }
    return new Record((int) port, token, pid);
  }

  static void clearIfOwned(String token) {
    clearIfOwned(endpointFile(), token);
  }

  static void clearIfOwned(Path endpointFile, String token) {
    try {
      Record current = read(endpointFile);
      if (current.token().equals(token)) {
        Files.deleteIfExists(endpointFile);
      }
    } catch (IOException ignored) {
      // Cleanup is best effort after the lock is already being released.
    }
  }

  private static long number(Object value, String name) throws IOException {
    if (!(value instanceof Number number)) {
      throw new IOException("daemon endpoint " + name + " is invalid");
    }
    return number.longValue();
  }

  /** Immutable endpoint state. */
  record Record(int port, String token, long pid) {
  }
}
