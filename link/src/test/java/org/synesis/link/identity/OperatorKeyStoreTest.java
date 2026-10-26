package org.synesis.link.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The operator key is the one key a person guards with a passphrase (ADR-0104, owner's decision 1).
 */
class OperatorKeyStoreTest {

    @TempDir
    Path tmp;

    private OperatorKeyStore store() {
        // A low cost keeps the suite quick; the format records the cost the key was written with.
        return new OperatorKeyStore(tmp.resolve("operator/operator.key"), 1_000);
    }

    @Test
    void theRightPassphraseUnlocksTheKeyThatWasCreatedAndItSigns() throws Exception {
        OperatorKeyStore store = store();
        NodeIdentity created = store.create("correct horse".toCharArray());

        NodeIdentity unlocked = store.unlock("correct horse".toCharArray());

        assertArrayEquals(created.publicKeyEncoded(), unlocked.publicKeyEncoded());
        assertArrayEquals(created.publicKeyEncoded(), store.publicKey());
        byte[] statement = "decide".getBytes(StandardCharsets.UTF_8);
        assertTrue(NodeIdentity.verifySignature(store.publicKey(), statement, unlocked.sign(statement)));
    }

    @Test
    void aWrongPassphraseFailsWithItsCodeAndGivesNoKey() throws Exception {
        OperatorKeyStore store = store();
        store.create("correct horse".toCharArray());

        OperatorKeyException refused = assertThrows(OperatorKeyException.class, () -> store.unlock("battery staple".toCharArray()));

        assertEquals("OPERATOR_PASSPHRASE_WRONG", refused.code());
    }

    @Test
    void theFileHoldsNeitherThePassphraseNorThePrivateKeyInTheClear() throws Exception {
        OperatorKeyStore store = store();
        String passphrase = "a passphrase nobody else has";
        NodeIdentity identity = store.create(passphrase.toCharArray());

        byte[] file = Files.readAllBytes(tmp.resolve("operator/operator.key"));

        assertFalse(contains(file, passphrase.getBytes(StandardCharsets.UTF_8)), "passphrase in the file");
        assertFalse(contains(file, identity.privateKeyEncodedForStore()), "private key in the file");
        // The tail of the PKCS#8 form is the secret scalar; a partial copy of it would also be a leak.
        byte[] encoded = identity.privateKeyEncodedForStore();
        byte[] scalar = java.util.Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        assertFalse(contains(file, scalar), "private scalar in the file");
        assertTrue(contains(file, identity.publicKeyEncoded()), "the public key is stored in the clear");
    }

    @Test
    void aChangedByteOfTheFileDoesNotUnlock() throws Exception {
        OperatorKeyStore store = store();
        store.create("correct horse".toCharArray());
        Path path = tmp.resolve("operator/operator.key");
        byte[] bytes = Files.readAllBytes(path);
        // The public key sits in the authenticated header: swapping it for another would otherwise register that key.
        bytes[bytes.length / 2 - 40] ^= 1;
        Files.write(path, bytes);

        assertThrows(IOException.class, () -> store.unlock("correct horse".toCharArray()));
    }

    @Test
    void aKeyIsNeverOverwrittenAndTwoKeysDiffer() throws Exception {
        OperatorKeyStore store = store();
        NodeIdentity first = store.create("correct horse".toCharArray());

        assertThrows(FileAlreadyExistsException.class, () -> store.create("another one".toCharArray()));
        assertArrayEquals(first.publicKeyEncoded(), store.publicKey());
        OperatorKeyStore other = new OperatorKeyStore(tmp.resolve("other.key"), 1_000);
        assertFalse(java.util.Arrays.equals(first.publicKeyEncoded(), other.create("correct horse".toCharArray()).publicKeyEncoded()));
    }

    @Test
    void aNewKeyNeedsAtLeastEightCharactersAndAShortOneCreatesNothing() {
        OperatorKeyStore store = store();

        OperatorKeyException refused = assertThrows(OperatorKeyException.class, () -> store.create("short".toCharArray()));

        assertEquals("OPERATOR_PASSPHRASE_TOO_SHORT", refused.code());
        assertFalse(store.exists());
    }

    @Test
    void aCreatorThatLosesARaceNeverReplacesTheKey() throws Exception {
        OperatorKeyStore first = store();
        OperatorKeyStore racing = store();
        NodeIdentity kept = first.create("correct horse".toCharArray());

        assertThrows(FileAlreadyExistsException.class, () -> racing.create("battery staple".toCharArray()));

        assertArrayEquals(kept.publicKeyEncoded(), racing.publicKey());
        try (var files = Files.list(tmp.resolve("operator"))) {
            assertEquals(1, files.count(), "the losing creator leaves no temporary file behind");
        }
    }

    @Test
    void anEmptyPassphraseAndAMissingFileAreRefused() {
        OperatorKeyStore store = store();

        assertThrows(IllegalArgumentException.class, () -> store.create(new char[0]));
        assertFalse(store.exists());
        assertThrows(IOException.class, () -> store.unlock("x".toCharArray()));
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start + needle.length <= haystack.length; start++) {
            for (int i = 0; i < needle.length; i++) {
                if (haystack[start + i] != needle[i]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
