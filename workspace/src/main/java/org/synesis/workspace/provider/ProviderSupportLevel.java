package org.synesis.workspace.provider;

/** Support maturity exposed by a provider integration. */
public enum ProviderSupportLevel {
    /** Real-agent validation is incomplete but the integration is usable. */
    BETA,
    /** Contract-tested integration without real-agent validation. */
    EXPERIMENTAL,
    /** Provider is known but not installable. */
    UNAVAILABLE
}
