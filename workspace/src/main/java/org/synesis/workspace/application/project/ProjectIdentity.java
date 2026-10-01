package org.synesis.workspace.application.project;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Portable immutable identity material for one logical Synesis project.
 *
 * <p>The identity deliberately contains no path, Git, runtime, provider,
 * credential, node, or membership data. Those values describe a local
 * realization or an authority decision, not the logical project.</p>
 *
 * @param projectId stable logical project identifier
 * @param createdAt immutable project-genesis timestamp
 * @since 1.0
 */
public record ProjectIdentity(UUID projectId, Instant createdAt) {

  /**
   * Validates portable identity material.
   *
   * @param projectId stable logical project identifier
   * @param createdAt immutable project-genesis timestamp
   */
  public ProjectIdentity {
    Objects.requireNonNull(projectId, "project ID");
    Objects.requireNonNull(createdAt, "created at");
  }

  /**
   * Compares identity material without consulting local paths or mutable state.
   *
   * @param other candidate identity
   * @return {@code true} when both immutable identity fields match
   */
  public boolean matches(ProjectIdentity other) {
    return other != null && projectId.equals(other.projectId)
        && createdAt.equals(other.createdAt);
  }
}
