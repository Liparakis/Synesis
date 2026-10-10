package org.synesis.link.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the link packages layered: identity at the bottom, candidate and protocol data types below
 * session, session below the transports, onboarding and the demo CLI on top.
 */
class LinkPackageArchitectureTest {

  private static final Pattern PACKAGE = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);
  private static final Pattern IMPORT = Pattern.compile(
      "^import (?:static )?(org\\.synesis\\.link[\\w.]*?)(?:\\.\\*)?;", Pattern.MULTILINE);

  private static Map<String, Set<String>> packageGraph() throws IOException {
    Path root = Path.of("src/main/java");
    if (!Files.isDirectory(root)) {
      root = Path.of("link/src/main/java");
    }
    Map<Path, String> sources = new HashMap<>();
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
        sources.put(path, Files.readString(path));
      }
    }
    Map<String, Set<String>> graph = new TreeMap<>();
    for (String source : sources.values()) {
      Matcher declared = PACKAGE.matcher(source);
      if (declared.find()) {
        graph.computeIfAbsent(declared.group(1), key -> new TreeSet<>());
      }
    }
    for (String source : sources.values()) {
      Matcher declared = PACKAGE.matcher(source);
      if (!declared.find()) {
        continue;
      }
      String from = declared.group(1);
      Matcher imports = IMPORT.matcher(source);
      while (imports.find()) {
        String target = imports.group(1);
        while (!graph.containsKey(target) && target.contains(".")) {
          target = target.substring(0, target.lastIndexOf('.'));
        }
        if (graph.containsKey(target) && !target.equals(from)) {
          graph.get(from).add(target);
        }
      }
    }
    return graph;
  }

  private static boolean reaches(Map<String, Set<String>> graph, String from, String to,
      Set<String> seen) {
    if (!seen.add(from)) {
      return false;
    }
    for (String next : graph.get(from)) {
      if (next.equals(to) || reaches(graph, next, to, seen)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Fails when any link package can reach itself through imports of other link packages.
   *
   * @throws IOException if link sources cannot be read
   */
  @Test
  void linkPackagesAreAcyclic() throws IOException {
    Map<String, Set<String>> graph = packageGraph();
    assertFalse(graph.isEmpty());
    Set<String> cyclic = new HashSet<>();
    for (String pkg : graph.keySet()) {
      if (reaches(graph, pkg, pkg, new HashSet<>())) {
        cyclic.add(pkg);
      }
    }
    assertTrue(cyclic.isEmpty(), "package cycle among " + new TreeSet<>(cyclic));
  }
}
