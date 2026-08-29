package org.synesis.coordination.domain.capability;


/**
 * Strategy interface for generating durable capability request handles.
 *
 * <p>Allows injectability of deterministic generators in tests and secure
 * random generators in production environments.
 *
 * @since 1.0
 */
public interface CapabilityRequestHandleGenerator {

    /**
     * Generates a new unique capability request handle locator.
     *
     * @return generated request handle
     */
    CapabilityRequestHandle generate();
}
