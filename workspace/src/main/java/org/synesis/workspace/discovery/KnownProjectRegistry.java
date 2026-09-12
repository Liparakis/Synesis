package org.synesis.workspace.discovery;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;

/**
 * Persists the installation-local index of Synesis projects observed through
 * validated Synesis lifecycle paths.
 *
 * <p>This registry is discovery metadata only. It does not own a project
 * runtime, cache project-local coordination state, scan the filesystem, or
 * persist liveness. Instances are safe for independent CLI, UI, and MCP
 * processes because updates use a bounded file lock and atomic replacement.
 * The supplied {@link ProjectApplicationService} remains the authority for
 * validating a remembered project identity.</p>
 *
 * @since 1.0
 */
public final class KnownProjectRegistry {

  /** Current registry document schema. */
  public static final int SCHEMA_VERSION = 1;
  /** Maximum number of remembered projects. */
  public static final int MAX_PROJECTS = 256;
  /** Maximum registry document size in UTF-8 bytes. */
  public static final int MAX_BYTES = 1_048_576;

  private static final String PROJECTS_DIRECTORY = "projects";
  private static final String REGISTRY_FILE = "registry.json";
  private static final String LOCK_FILE = "registry.lock";
  private final Path registryFile;
  private final Path lockFile;
  private final ProjectApplicationService projectService;

  /**
   * Creates a registry at the existing host-level Synesis state location.
   */
  public KnownProjectRegistry() {
    this(defaultFile(), new ProjectApplicationService());
  }

  /**
   * Creates a registry at an explicit file, primarily for isolated tests.
   *
   * @param registryFile registry JSON file
   */
  public KnownProjectRegistry(Path registryFile) {
    this(registryFile, new ProjectApplicationService());
  }

  /**
   * Creates a registry with explicit storage and identity validation services.
   *
   * @param registryFile  registry JSON file
   * @param projectService existing project identity authority
   */
  public KnownProjectRegistry(Path registryFile, ProjectApplicationService projectService) {
    this.registryFile = normalize(Objects.requireNonNull(registryFile, "registry file"));
    this.lockFile = this.registryFile.resolveSibling(LOCK_FILE);
    this.projectService = Objects.requireNonNull(projectService, "project service");
  }

  /**
   * Returns the default installation-local registry file.
   *
   * @return registry file below the existing Synesis application state root
   */
  public static Path defaultFile() {
    return AdministrativeStateLocator.applicationStateRoot()
        .resolve(PROJECTS_DIRECTORY)
        .resolve(REGISTRY_FILE)
        .toAbsolutePath()
        .normalize();
  }

  /**
   * Records one already validated project observation.
   *
   * <p>Observation is idempotent for the same project UUID and normalized
   * path. A path already belonging to another UUID, or a UUID already bound
   * to another path, is rejected rather than silently merged.</p>
   *
   * @param location validated Synesis project location
   * @throws RegistryException if identity validation or durable persistence fails
   */
  public void observe(ProjectApplicationService.ProjectLocation location)
      throws RegistryException {
    Objects.requireNonNull(location, "location");
    ProjectApplicationService.ProjectLocation validated = validate(location.root());
    if (!validated.projectId().equals(location.projectId())) {
      throw new RegistryException("IDENTITY_MISMATCH",
          "observed project identity does not match the supplied location");
    }
    Path root = normalize(validated.root());
    Instant observedAt = Instant.now();
    locked(() -> {
      List<Entry> entries = readEntries();
      Entry byId = entries.stream()
          .filter(entry -> entry.projectId().equals(validated.projectId()))
          .findFirst()
          .orElse(null);
      Entry byPath = entries.stream()
          .filter(entry -> entry.path().equals(root))
          .findFirst()
          .orElse(null);
      if (byId != null && !byId.path().equals(root)) {
        throw new RegistryException("IDENTITY_MISMATCH",
            "project identity is already remembered at another path");
      }
      if (byPath != null && !byPath.projectId().equals(validated.projectId())) {
        throw new RegistryException("IDENTITY_MISMATCH",
            "remembered path belongs to another project identity");
      }
      if (byId == null) {
        if (entries.size() >= MAX_PROJECTS) {
          throw new RegistryException("REGISTRY_FULL", "known project limit reached");
        }
        entries.add(new Entry(validated.projectId(), root, validated.createdAt(), observedAt,
            observedAt));
      } else {
        int index = entries.indexOf(byId);
        entries.set(index, new Entry(byId.projectId(), byId.path(), byId.createdAt(),
            byId.firstObservedAt(), observedAt));
      }
      writeEntries(entries);
      return null;
    });
  }

  /**
   * Validates and remembers an existing Synesis project directory.
   *
   * @param root project directory to register
   * @return the durable discovery view after registration
   * @throws RegistryException if the directory is not an initialized Synesis
   *     project or the registry cannot be updated
   */
  public ProjectView register(Path root) throws RegistryException {
    ProjectApplicationService.ProjectLocation location = validate(
        Objects.requireNonNull(root, "project root"));
    observe(location);
    return projects().stream()
        .filter(project -> project.projectId().equals(location.projectId()))
        .findFirst()
        .orElseThrow(() -> new RegistryException("REGISTRY_PERSISTENCE_FAILED",
            "registered project is missing from the registry"));
  }

  /**
   * Removes one project from this installation's discovery registry.
   *
   * <p>This operation never touches the project directory or its local
   * Synesis state.</p>
   *
   * @param projectId stable project identity to remove
   * @return {@code true} when an entry was removed, otherwise {@code false}
   * @throws RegistryException if the registry cannot be read or persisted
   */
  public boolean remove(UUID projectId) throws RegistryException {
    Objects.requireNonNull(projectId, "project ID");
    return locked(() -> {
      List<Entry> entries = readEntries();
      boolean removed = entries.removeIf(entry -> entry.projectId().equals(projectId));
      if (removed) {
        writeEntries(entries);
      }
      return removed;
    });
  }

  /**
   * Reads known projects without claiming any runtime is live.
   *
   * @return immutable discovery views
   * @throws RegistryException if the registry cannot be read or validated
   */
  public List<ProjectView> projects() throws RegistryException {
    return projects(Set.of());
  }

  /**
   * Reads known projects and marks only the explicitly owned live identities
   * as live for this response.
   *
   * @param liveProjectIds project identities served by the current process
   * @return immutable discovery views with process-local status
   * @throws RegistryException if the registry cannot be read or validated
   */
  public List<ProjectView> projects(Set<UUID> liveProjectIds) throws RegistryException {
    Objects.requireNonNull(liveProjectIds, "live project IDs");
    if (liveProjectIds.stream().anyMatch(Objects::isNull)) {
      throw new NullPointerException("live project IDs contains null");
    }
    return locked(() -> readEntries().stream()
        .sorted(Comparator.comparing(Entry::projectId))
        .map(entry -> view(entry, liveProjectIds))
        .toList());
  }

  /**
   * Returns the registry file used by this instance.
   *
   * @return normalized registry file
   */
  public Path registryFile() {
    return registryFile;
  }

  private ProjectView view(Entry entry, Set<UUID> liveProjectIds) {
    Status status;
    if (!Files.isDirectory(entry.path())) {
      status = Status.UNAVAILABLE;
    } else {
      try {
        ProjectApplicationService.ProjectLocation current = projectService.require(entry.path());
        if (!entry.projectId().equals(current.projectId())) {
          status = Status.IDENTITY_MISMATCH;
        } else {
          status = liveProjectIds.contains(entry.projectId()) ? Status.LIVE : Status.INACTIVE;
        }
      } catch (ProjectApplicationService.ProjectApplicationException unavailable) {
        status = unavailable.code().equals("MALFORMED")
            ? Status.IDENTITY_MISMATCH : Status.UNAVAILABLE;
      }
    }
    return new ProjectView(entry.projectId(), entry.path(), entry.createdAt(),
        entry.firstObservedAt(), entry.lastObservedAt(), status);
  }

  private ProjectApplicationService.ProjectLocation validate(Path root) throws RegistryException {
    try {
      return projectService.require(root);
    } catch (ProjectApplicationService.ProjectApplicationException failure) {
      throw new RegistryException(failure.code(), failure.getMessage(), failure);
    }
  }

  private List<Entry> readEntries() throws RegistryException {
    if (Files.notExists(registryFile)) {
      return new ArrayList<>();
    }
    try {
      byte[] bytes = Files.readAllBytes(registryFile);
      if (bytes.length == 0 || bytes.length > MAX_BYTES) {
        throw new IOException("registry exceeds size bound");
      }
      Object parsed = ProviderJson.parse(new String(bytes, StandardCharsets.UTF_8));
      if (!(parsed instanceof Map<?, ?> raw)) {
        throw new IOException("registry root must be an object");
      }
      Map<String, Object> document = stringMap(raw);
      if (integer(document.get("schemaVersion"), "schemaVersion") != SCHEMA_VERSION) {
        throw new IOException("unsupported registry schema");
      }
      Object rawProjects = document.get("projects");
      if (!(rawProjects instanceof List<?> list) || list.size() > MAX_PROJECTS) {
        throw new IOException("invalid project list");
      }
      List<Entry> entries = new ArrayList<>();
      Set<UUID> identities = new LinkedHashSet<>();
      Set<Path> paths = new LinkedHashSet<>();
      for (Object rawEntry : list) {
        if (!(rawEntry instanceof Map<?, ?> map)) {
          throw new IOException("project entry must be an object");
        }
        Map<String, Object> value = stringMap(map);
        if (!value.keySet().equals(Set.of("projectId", "path", "createdAt",
            "firstObservedAt", "lastObservedAt"))) {
          throw new IOException("unsupported project registry field");
        }
        Entry entry = new Entry(UUID.fromString(text(value, "projectId")),
            normalize(Path.of(text(value, "path"))),
            Instant.parse(text(value, "createdAt")),
            Instant.parse(text(value, "firstObservedAt")),
            Instant.parse(text(value, "lastObservedAt")));
        if (!identities.add(entry.projectId()) || !paths.add(entry.path())
            || entry.firstObservedAt().isAfter(entry.lastObservedAt())) {
          throw new IOException("duplicate or invalid project registry entry");
        }
        entries.add(entry);
      }
      return entries;
    } catch (Exception failure) {
      throw new RegistryException("REGISTRY_MALFORMED", "known project registry is malformed",
          failure);
    }
  }

  private void writeEntries(List<Entry> entries) throws RegistryException {
    List<Map<String, Object>> projects = entries.stream()
        .sorted(Comparator.comparing(Entry::projectId))
        .map(entry -> {
          Map<String, Object> value = new LinkedHashMap<>();
          value.put("projectId", entry.projectId().toString());
          value.put("path", entry.path().toString());
          value.put("createdAt", entry.createdAt().toString());
          value.put("firstObservedAt", entry.firstObservedAt().toString());
          value.put("lastObservedAt", entry.lastObservedAt().toString());
          return value;
        })
        .toList();
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("schemaVersion", SCHEMA_VERSION);
    document.put("projects", projects);
    byte[] bytes = (ProviderJson.write(document) + System.lineSeparator())
        .getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_BYTES) {
      throw new RegistryException("REGISTRY_FULL", "known project registry exceeds size bound");
    }
    Path parent = registryFile.getParent();
    try {
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Path temporary = registryFile.resolveSibling(REGISTRY_FILE + ".tmp-" + UUID.randomUUID());
      try {
        Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
          Files.move(temporary, registryFile, StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
          Files.move(temporary, registryFile, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException failure) {
      throw new RegistryException("REGISTRY_PERSISTENCE_FAILED",
          "could not persist known project registry", failure);
    }
  }

  private <T> T locked(IoOperation<T> operation) throws RegistryException {
    try {
      Path parent = lockFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
          StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
        if (!lock.isValid()) {
          throw new RegistryException("REGISTRY_LOCK_FAILED", "known project registry lock is invalid");
        }
        return operation.run();
      }
    } catch (RegistryException failure) {
      throw failure;
    } catch (OverlappingFileLockException | IOException failure) {
      throw new RegistryException("REGISTRY_LOCK_FAILED",
          "could not access known project registry", failure);
    }
  }

  private static Map<String, Object> stringMap(Map<?, ?> raw) throws IOException {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IOException("registry field name must be text");
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  private static int integer(Object value, String name) throws IOException {
    if (!(value instanceof Number number) || number.doubleValue() != SCHEMA_VERSION) {
      throw new IOException("invalid " + name);
    }
    return number.intValue();
  }

  private static String text(Map<String, Object> value, String name) throws IOException {
    Object result = value.get(name);
    if (!(result instanceof String text) || text.isBlank()) {
      throw new IOException("missing " + name);
    }
    return text;
  }

  private static Path normalize(Path path) {
    return path.toAbsolutePath().normalize();
  }

  private record Entry(UUID projectId, Path path, Instant createdAt, Instant firstObservedAt,
                       Instant lastObservedAt) {
  }

  /** Runtime reachability derived for one registry response. */
  public enum Status {
    /** The current process owns a reachable runtime for the project. */
    LIVE,
    /** The project is known, but no runtime is reachable through this process. */
    INACTIVE,
    /** The remembered path is unavailable or no longer a valid Synesis project. */
    UNAVAILABLE,
    /** The remembered path resolves to a different or malformed identity. */
    IDENTITY_MISMATCH
  }

  /**
   * Safe discovery metadata and read-time reachability status for one project.
   *
   * @param projectId       stable project identity
   * @param path            remembered local project path
   * @param createdAt       project creation time from project metadata
   * @param firstObservedAt first valid observation time
   * @param lastObservedAt  most recent valid observation time
   * @param status          read-time status, never persisted
   */
  public record ProjectView(UUID projectId, Path path, Instant createdAt, Instant firstObservedAt,
                            Instant lastObservedAt, Status status) {

    /** Validates immutable, non-secret discovery values. */
    public ProjectView {
      Objects.requireNonNull(projectId, "project ID");
      path = normalize(Objects.requireNonNull(path, "path"));
      Objects.requireNonNull(createdAt, "created at");
      Objects.requireNonNull(firstObservedAt, "first observed at");
      Objects.requireNonNull(lastObservedAt, "last observed at");
      Objects.requireNonNull(status, "status");
    }
  }

  /**
   * Bounded failure from identity validation or registry persistence.
   */
  public static final class RegistryException extends Exception {

    @java.io.Serial
    private static final long serialVersionUID = 1L;
    /** Stable diagnostic code for this failure. */
    private final String code;

    /**
     * Creates a registry failure.
     *
     * @param code    stable failure code
     * @param message safe diagnostic message
     */
    public RegistryException(String code, String message) {
      super(message);
      this.code = Objects.requireNonNull(code, "code");
    }

    private RegistryException(String code, String message, Throwable cause) {
      super(message, cause);
      this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * Returns the stable failure code.
     *
     * @return failure code
     */
    public String code() {
      return code;
    }
  }

  @FunctionalInterface
  private interface IoOperation<T> {
    T run() throws RegistryException;
  }
}
