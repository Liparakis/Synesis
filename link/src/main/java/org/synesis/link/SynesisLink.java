package org.synesis.link;

/**
 * Stable protocol metadata for Synesis Link.
 *
 * <p>This class is stateless and thread-safe. It does not create connections,
 * authenticate peers, or imply that direct connectivity is available.
 *
 * @since 1.0
 */
public final class SynesisLink {

    /**
     * The ALPN identifier negotiated by Synesis Link v1.
     *
     * <p>This is a protocol identifier, not a connectivity guarantee. It is
     * immutable and safe to log.
     */
    public static final String ALPN = "synesis-link/1";

    private SynesisLink() {
    }
}
