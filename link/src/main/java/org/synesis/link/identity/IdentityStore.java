package org.synesis.link.identity;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Storage boundary for a node identity.
 *
 * <p>Implementations own their storage resources and must not expose private
 * key material in exceptions or logs. Implementations are not required to be
 * thread-safe unless their documentation says otherwise.
 *
 * @since 1.0
 */
public interface IdentityStore {

    /**
     * Loads the identity from this store.
     *
     * @return the stored identity; never {@code null}
     * @throws IOException if storage cannot be read
     * @throws GeneralSecurityException if stored key material is invalid
     */
    NodeIdentity load() throws IOException, GeneralSecurityException;

    /**
     * Saves an identity without silently replacing existing key material.
     *
     * @param identity identity to persist; never {@code null}
     * @throws IOException if storage cannot be written or already exists
     * @throws GeneralSecurityException if the identity cannot be encoded
     */
    void save(NodeIdentity identity) throws IOException, GeneralSecurityException;
}
