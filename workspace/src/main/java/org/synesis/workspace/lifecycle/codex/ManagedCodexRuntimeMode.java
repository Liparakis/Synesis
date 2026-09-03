package org.synesis.workspace.lifecycle.codex;

/**
 * Explicit Codex runtime-home strategies.
 */
public enum ManagedCodexRuntimeMode {
    /** Dedicated isolated home, subject to the existing auth safety policy. */
    ISOLATED_DEDICATED_HOME,
    /** Provider-owned normal home with Synesis-managed authority controls. */
    NORMAL_PROVIDER_HOME_MANAGED
}
