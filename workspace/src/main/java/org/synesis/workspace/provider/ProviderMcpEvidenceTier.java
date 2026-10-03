package org.synesis.workspace.provider;

/**
 * Evidence tier for a provider's Synesis MCP connection and managed mutation path.
 *
 * <p>This is intentionally separate from {@link ProviderSupportLevel}: MCP evidence
 * does not prove native hook enforcement or zero-touch provider maturity.</p>
 */
public enum ProviderMcpEvidenceTier {
    /**
     * Provider configuration was identified, but a live MCP flow is unverified.
     */
    MCP_CONFIG_DISCOVERED,
    /**
     * A live MCP process connected and exposed the expected Synesis tools.
     */
    MCP_CONNECTED,
    /**
     * A live MCP flow read, created, reread, and preserved a revision in an assigned worktree.
     */
    MCP_CONFIRMED_WORKING
}
