package org.synesis.mcp.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.GitProcessRunner;

/**
 * Compares provider-advertised roots with an explicitly pinned MCP control checkout without
 * creating or repairing repository-private state.
 */
final class McpProjectRootAuthority {

  private static final String ASSIGNED_WORKSPACE_MARKER = ".synesis/local/workspace-binding.json";

  private McpProjectRootAuthority() {
  }

  static Selection select(Path pinnedRoot, List<Path> candidates, Path userHome) {
    ProjectIdentity pinned = inspect(pinnedRoot, true);
    if (pinned == null) {
      return Selection.rejected("PROJECT_ROOT_PIN_INVALID");
    }
    if (candidates == null || candidates.isEmpty()) {
      return Selection.accepted(pinned.root());
    }

    for (Path candidate : candidates) {
      Path normalized = normalize(candidate);
      if (normalized == null || normalized.equals(userHome)) {
        continue;
      }
      if (isAssignedWorkspace(normalized)) {
        return Selection.rejected("PROJECT_ROOT_ASSIGNED_WORKTREE_REJECTED");
      }
      ProjectIdentity advertised = inspect(normalized, false);
      if (advertised == null) {
        return Selection.rejected("PROJECT_ROOT_PIN_VERIFICATION_FAILED");
      }
      if (!pinned.projectId()
          .equals(advertised.projectId())
          || !pinned.gitCommonDirectory()
          .equals(advertised.gitCommonDirectory())) {
        return Selection.rejected("PROJECT_ROOT_PIN_MISMATCH");
      }
    }
    return Selection.accepted(pinned.root());
  }

  static boolean isAssignedWorkspace(Path root) {
    Path normalized = normalize(root);
    if (normalized == null) {
      return false;
    }
    String portable = normalized.toString()
        .replace('\\', '/');
    Path reservedWorkspaces = AdministrativeStateLocator.applicationStateRoot()
        .resolve("workspaces")
        .toAbsolutePath()
        .normalize();
    Path real = normalized;
    try {
      real = normalized.toRealPath();
    } catch (Exception unavailable) {
      // Nonexistent paths are still classified by their lexical location.
    }
    return portable.contains("/.synesis/local/worktrees/")
        || normalized.startsWith(reservedWorkspaces)
        || real.startsWith(reservedWorkspaces)
        || Files.isRegularFile(normalized.resolve(ASSIGNED_WORKSPACE_MARKER));
  }

  /**
   * Resolves an advertised project root to the main checkout that owns its Git common directory.
   *
   * <p>A normal checkout is already the control checkout.  A linked
   * worktree is resolved through Git's read-only worktree projection and is accepted only when
   * exactly one listed main checkout has the same initialized Synesis project identity.</p>
   *
   * @param advertisedRoot provider-advertised project root
   * @return the matching main checkout, or {@code null} when it cannot be proven uniquely
   */
  static Path resolveMainCheckout(Path advertisedRoot) {
    ProjectIdentity advertised = inspect(advertisedRoot, false);
    if (advertised == null) {
      return null;
    }
    if (Files.isDirectory(advertised.root()
        .resolve(".git"))) {
      return advertised.root();
    }
    if (!Files.isRegularFile(advertised.root()
        .resolve(".git"))) {
      return null;
    }

    try {
      GitProcessRunner.Result result = GitProcessRunner.runResult(advertised.root(),
          "worktree", "list", "--porcelain");
      if (result.exitCode() != 0) {
        return null;
      }
      Path resolved = null;
      for (String line : result.output()
          .lines()
          .toList()) {
        if (!line.startsWith("worktree ")) {
          continue;
        }
        String listedText = line.substring("worktree ".length())
            .trim();
        if (listedText.isEmpty()) {
          continue;
        }
        Path listedRoot = normalize(Path.of(listedText));
        ProjectIdentity main = inspect(listedRoot, true);
        if (main == null
            || !advertised.projectId()
            .equals(main.projectId())
            || !advertised.gitCommonDirectory()
            .equals(main.gitCommonDirectory())) {
          continue;
        }
        if (resolved != null && !resolved.equals(main.root())) {
          return null;
        }
        resolved = main.root();
      }
      return resolved;
    } catch (Exception unavailable) {
      return null;
    }
  }

  private static ProjectIdentity inspect(Path root, boolean requireMainCheckout) {
    Path normalized = normalize(root);
    if (normalized == null || isAssignedWorkspace(normalized)) {
      return null;
    }
    Path dotGit = normalized.resolve(".git");
    if (requireMainCheckout ? !Files.isDirectory(dotGit)
        : !(Files.isDirectory(dotGit) || Files.isRegularFile(dotGit))) {
      return null;
    }
    Path metadata = normalized.resolve(".synesis/project.json");
    if (!Files.isRegularFile(metadata)) {
      return null;
    }
    try {
      Object parsed = ProviderJson.parse(Files.readString(metadata, StandardCharsets.UTF_8));
      if (!(parsed instanceof Map<?, ?> values)
          || !(values.get("projectId") instanceof String projectIdText)) {
        return null;
      }
      UUID projectId = UUID.fromString(projectIdText);
      Path common = new AdministrativeStateLocator().resolveGitCommonDirectory(normalized);
      return new ProjectIdentity(normalized, projectId, common);
    } catch (Exception invalid) {
      return null;
    }
  }

  private static Path normalize(Path path) {
    try {
      return path == null ? null : path.toAbsolutePath()
                                   .normalize();
    } catch (Exception invalid) {
      return null;
    }
  }

  record Selection(Path root, String rejection) {

    static Selection accepted(Path root) {
      return new Selection(root, null);
    }

    static Selection rejected(String rejection) {
      return new Selection(null, rejection);
    }

    boolean rejected() {
      return rejection != null;
    }
  }

  private record ProjectIdentity(Path root, UUID projectId, Path gitCommonDirectory) {

  }
}
