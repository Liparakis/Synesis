package org.synesis.link.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

import org.junit.jupiter.api.Test;

/** Verifies identity generation, signatures, node IDs, and local persistence. */
final class NodeIdentityTest {

    @Test
    void generatesSignsVerifiesAndRedacts() throws GeneralSecurityException {
        NodeIdentity identity = NodeIdentity.generate();
        byte[] message = "synesis-link".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] signature = identity.sign(message);

        assertTrue(identity.verify(message, signature));
        assertFalse(identity.verify("other".getBytes(java.nio.charset.StandardCharsets.UTF_8), signature));
        assertTrue(identity.nodeId().startsWith("sl1-"));
        assertTrue(identity.nodeId().equals(NodeIdentity.deriveNodeId(identity.publicKeyEncoded())));
        assertFalse(identity.toString().contains("PRIVATE"));
        assertNotEquals(identity.nodeId(), NodeIdentity.generate().nodeId());
    }

    @Test
    void savesAndLoadsWithoutReplacing() throws Exception {
        Path path = Files.createTempFile("synesis-link-identity", ".bin");
        Files.delete(path);
        FileIdentityStore store = new FileIdentityStore(path);
        NodeIdentity original = NodeIdentity.generate();
        store.save(original);

        NodeIdentity loaded = store.load();
        assertTrue(loaded.nodeId().equals(original.nodeId()));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> store.save(NodeIdentity.generate()));
        Files.deleteIfExists(path);
    }
}
