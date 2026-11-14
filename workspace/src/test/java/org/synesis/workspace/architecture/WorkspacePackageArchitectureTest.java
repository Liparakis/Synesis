package org.synesis.workspace.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the workspace package boundaries explicit without depending on a third-party architecture
 * test framework.
 */
class WorkspacePackageArchitectureTest {

  private static Stream<String> productionSources() throws IOException {
    Path root = Path.of("src/main/java");
    if (!Files.isDirectory(root)) {
      root = Path.of("workspace/src/main/java");
    }
    try (Stream<Path> paths = Files.walk(root)) {
      return paths.filter(path -> path.toString()
              .endsWith(".java"))
          .map(WorkspacePackageArchitectureTest::read)
          .toList()
          .stream();
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot read " + path, failure);
    }
  }

  /**
   * Rejects package names and imports that would reintroduce the removed integration branch.
   *
   * @throws IOException if production sources cannot be read
   */
  @Test
  void removedIntegrationBranchDoesNotReturn() throws IOException {
    String removedIntegration = "org.synesis.workspace." + "integration";
    String broadInfrastructure = "workspace.infrastructure." + "workspace";
    assertFalse(productionSources().anyMatch(source -> source.contains(removedIntegration)));
    assertFalse(productionSources().anyMatch(source -> source.contains(broadInfrastructure)));
  }

  /**
   * Ensures project and lifecycle code do not depend on delivery adapters.
   *
   * @throws IOException if production sources cannot be read
   */
  @Test
  void projectAndLifecycleDoNotDependOnDeliveryModules() throws IOException {
    List<String> projectAndLifecycle = productionSources()
        .filter(source -> source.contains("package org.synesis.workspace.project;")
            || source.contains("package org.synesis.workspace.lifecycle."))
        .toList();
    assertTrue(projectAndLifecycle.stream()
        .noneMatch(source -> source.contains("org.synesis.cli")
            || source.contains("org.synesis.mcp")));
  }

  /**
   * Ensures provider-neutral contracts do not import a concrete provider implementation.
   *
   * @throws IOException if production sources cannot be read
   */
  @Test
  void providerContractsRemainProviderNeutral() throws IOException {
    List<String> contracts = productionSources()
        .filter(source -> source.contains("package org.synesis.workspace.provider;")
            && (source.contains("public interface ProviderIntegration")
            || source.contains("public enum ProviderSupportLevel")))
        .toList();
    assertFalse(contracts.isEmpty());
    assertTrue(contracts.stream()
        .noneMatch(source -> source.contains("provider.codex")
            || source.contains("provider.claude")));
  }

  /**
   * Ensures production sources do not refer to test source locations.
   *
   * @throws IOException if production sources cannot be read
   */
  @Test
  void productionDoesNotDependOnTestCode() throws IOException {
    assertTrue(productionSources().noneMatch(source -> source.contains("src/test")
        || source.contains("org.synesis.workspace.test")));
  }

  /**
   * Keeps the platform-specific metadata visibility operation behind one
   * storage seam instead of allowing security or application services to
   * interpret filesystem presentation state.
   *
   * @throws IOException if production sources cannot be read
   */
  @Test
  void metadataVisibilityHasOneProductionOwner() throws IOException {
    List<String> dosAttributeUsers = productionSources()
        .filter(source -> source.contains("DosFileAttributeView"))
        .toList();
    assertEquals(1, dosAttributeUsers.size());
    assertTrue(dosAttributeUsers.getFirst()
        .contains("package org.synesis.workspace.infrastructure.filesystem;"));
  }

  /**
   * Keeps Codex, process, and control-plane internals grouped under their owners.
   */
  @Test
  void supportingImplementationsStayInFocusedPackages() {
    Path sources = Path.of("src/main/java/org/synesis/workspace");
    assertTrue(Files.isRegularFile(sources.resolve("lifecycle/process/GitProcessRunner.java")));
    assertTrue(Files.isRegularFile(sources.resolve("lifecycle/process/SemanticIndexFingerprint.java")));
    assertTrue(Files.isRegularFile(sources.resolve("lifecycle/cleanup/inventory/LifecycleInventoryService.java")));
    assertTrue(Files.isRegularFile(sources.resolve("lifecycle/cleanup/plan/CleanupPlanService.java")));
    assertTrue(Files.isRegularFile(sources.resolve("lifecycle/cleanup/execution/CleanupExecutionService.java")));
    assertTrue(Files.isRegularFile(sources.resolve("lifecycle/codex/protocol/CodexAppServerProtocolClient.java")));
    assertTrue(Files.isRegularFile(sources.resolve("lifecycle/codex/protocol/LifecycleControlRequestEnvelope.java")));
    assertTrue(Files.isRegularFile(sources.resolve("lifecycle/codex/protocol/LifecycleControlResponse.java")));
    assertTrue(Files.isRegularFile(sources.resolve("lifecycle/codex/state/CodexLifecycleStateStore.java")));
    assertTrue(Files.isRegularFile(sources.resolve("lifecycle/codex/runtime/CodexManagedRuntimeHome.java")));
    assertTrue(Files.isRegularFile(sources.resolve("application/project/execution/ProjectProcessExecutor.java")));
    assertTrue(Files.isRegularFile(sources.resolve("application/workspace/read/WorkspaceReadService.java")));
    assertTrue(Files.isRegularFile(sources.resolve("application/workspace/mutation/WorkspacePatchService.java")));
    assertTrue(Files.isRegularFile(sources.resolve("transport/control/network/LinkNetworkProjection.java")));
  }

  private static final String ROOT = "org.synesis.workspace";

  private static final Pattern PACKAGE_REFERENCE =
      Pattern.compile("org\\.synesis\\.workspace(?:\\.[a-z][A-Za-z0-9_]*)*");

  private static final Pattern PACKAGE_DECLARATION =
      Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);

  /**
   * Package cycles that remain in the workspace module, relative to the workspace root. A package
   * counts as part of a cycle group when it can reach every other member through imports and
   * qualified references. Removing a cycle means deleting its entry here; adding a package to a
   * group or creating a new group fails the build.
   *
   * <p>{@code discovery} joined on 2026-11-15 (owner's choice): the lane sandbox hides every project root the known-project
   * registry names (ADR-0104, "Residual"), and the registry validates projects through {@code application}. The
   * alternative was a second reader of the registry file.</p>
   */
  private static final Set<Set<String>> ALLOWED_CYCLE_GROUPS = Set.of(Set.of(
      "agent", "application", "application.collaboration", "application.constraint", "discovery",
      "application.integration", "application.project", "application.project.execution",
      "application.provider", "application.provider.continuity", "application.provider.session",
      "application.workspace", "doctor", "lifecycle.cleanup.inventory", "lifecycle.cleanup.plan",
      "lifecycle.command", "lifecycle.recovery", "migration", "provider.codex"));

  /**
   * Package pairs that depend on each other directly, relative to the workspace root. These are the
   * shortest cycles inside {@link #ALLOWED_CYCLE_GROUPS}.
   */
  private static final Set<Set<String>> ALLOWED_MUTUAL_PAIRS = Set.of(
      Set.of("application", "application.project"),
      Set.of("application", "application.provider"),
      Set.of("application.collaboration", "application.integration"),
      Set.of("application.collaboration", "application.provider"),
      Set.of("application.collaboration", "application.provider.session"),
      Set.of("application.project", "application.workspace"),
      Set.of("application.provider", "application.provider.session"));

  /**
   * Fails on any package cycle that is not one of the listed remaining cycles.
   *
   * @throws IOException if production sources cannot be read
   */
  @Test
  void packageCyclesAreExactlyTheAllowListed() throws IOException {
    Map<String, Set<String>> graph = packageGraph();
    Map<String, Set<String>> reach = new HashMap<>();
    for (String start : graph.keySet()) {
      Set<String> seen = new HashSet<>();
      List<String> pending = new ArrayList<>(graph.get(start));
      while (!pending.isEmpty()) {
        String next = pending.removeLast();
        if (seen.add(next)) {
          pending.addAll(graph.get(next));
        }
      }
      reach.put(start, seen);
    }

    Set<Set<String>> groups = new HashSet<>();
    Set<Set<String>> pairs = new HashSet<>();
    for (String left : graph.keySet()) {
      Set<String> group = reach.get(left).stream()
          .filter(right -> reach.get(right).contains(left))
          .collect(Collectors.toCollection(HashSet::new));
      if (reach.get(left).contains(left)) {
        group.add(left);
        groups.add(relative(group));
      }
      for (String right : graph.get(left)) {
        if (graph.get(right).contains(left)) {
          pairs.add(relative(Set.of(left, right)));
        }
      }
    }
    assertEquals(ALLOWED_CYCLE_GROUPS, groups);
    assertEquals(ALLOWED_MUTUAL_PAIRS, pairs);
  }

  private static Set<String> relative(Set<String> packages) {
    return packages.stream()
        .map(name -> name.substring(ROOT.length() + 1))
        .collect(Collectors.toSet());
  }

  private static Map<String, Set<String>> packageGraph() throws IOException {
    List<String> sources = productionSources().toList();
    Set<String> known = new HashSet<>();
    for (String source : sources) {
      known.add(declaredPackage(source));
    }
    Map<String, Set<String>> graph = new HashMap<>();
    known.forEach(name -> graph.put(name, new HashSet<>()));
    for (String source : sources) {
      String owner = declaredPackage(source);
      Matcher references = PACKAGE_REFERENCE.matcher(source);
      while (references.find()) {
        String target = references.group();
        if (!target.equals(owner) && known.contains(target)) {
          graph.get(owner).add(target);
        }
      }
    }
    return graph;
  }

  private static String declaredPackage(String source) {
    Matcher declaration = PACKAGE_DECLARATION.matcher(source);
    assertTrue(declaration.find());
    return declaration.group(1);
  }
}
