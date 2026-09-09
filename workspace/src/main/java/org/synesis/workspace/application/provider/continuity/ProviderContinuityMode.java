package org.synesis.workspace.application.provider.continuity;

/**
 * Provider-session continuity profiles exposed by Synesis.
 *
 * <p>The profile is explicit. A normal provider connection remains
 * {@link #SESSION_BOUND}; it is never silently upgraded because a matching project, provider, or
 * thread happens to exist.</p>
 */
public enum ProviderContinuityMode {
  /**
   * One transport connection owns one session binding.
   */
  SESSION_BOUND,
  /**
   * Synesis authenticates a replacement managed runtime attachment.
   */
  MANAGED_CONTINUITY,
  /**
   * A future provider-native assertion profile.
   */
  NATIVE_CONTINUITY
}
