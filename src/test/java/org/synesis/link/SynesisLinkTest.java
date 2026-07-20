package org.synesis.link;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Verifies the first stable protocol value. */
final class SynesisLinkTest {

    @Test
    void exposesTheV1Alpn() {
        assertEquals("synesis-link/1", SynesisLink.ALPN);
    }
}
