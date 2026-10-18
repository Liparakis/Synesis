package org.synesis.coordination.domain.membership;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.link.identity.NodeIdentity;

/** The recovery key's words and key derivation (ADR-0098 step 6, designer item 11). */
final class RecoveryKeyTest {

    private static String repeat(String word, int times, String last) {
        return (word + " ").repeat(times) + last;
    }

    private static String eightThrice(String last) {
        String first = "legal winner thank year wave sausage worth useful";
        return first + " " + first + " legal winner thank year wave sausage worth " + last;
    }

    /** Official BIP-39 vectors for 256 bits of entropy. */
    static Stream<String> vectors() {
        return Stream.of(
                repeat("abandon", 23, "art"),
                eightThrice("title"),
                "letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic avoid"
                        + " letter advice cage absurd amount doctor acoustic bless",
                repeat("zoo", 23, "vote"));
    }

    @ParameterizedTest
    @MethodSource("vectors")
    void aBip39VectorReadsBackToItself(String vector) {
        assertEquals(24, vector.split(" ").length);
        assertEquals(vector, String.join(" ", RecoveryKey.fromWords(vector).words()));
    }

    @Test
    void aWrongChecksumIsRefused() {
        CoordinationArgumentException failure = assertThrows(CoordinationArgumentException.class,
                () -> RecoveryKey.fromWords(repeat("abandon", 23, "abandon")));
        assertTrue(failure.getMessage().contains("RECOVERY_WORDS_CHECKSUM"), failure.getMessage());
    }

    @Test
    void anUnknownWordAndAWrongCountAreRefused() {
        CoordinationArgumentException unknown = assertThrows(CoordinationArgumentException.class,
                () -> RecoveryKey.fromWords(repeat("abandon", 23, "notaword")));
        assertTrue(unknown.getMessage().contains("RECOVERY_WORD_UNKNOWN"), unknown.getMessage());

        CoordinationArgumentException few = assertThrows(CoordinationArgumentException.class,
                () -> RecoveryKey.fromWords(repeat("abandon", 22, "art")));
        assertTrue(few.getMessage().contains("RECOVERY_WORDS_COUNT"), few.getMessage());
        assertThrows(CoordinationArgumentException.class, () -> RecoveryKey.fromWords(repeat("abandon", 24, "art")));
        assertThrows(CoordinationArgumentException.class, () -> RecoveryKey.fromWords(repeat("abandon", 11, "about")));
        assertThrows(CoordinationArgumentException.class, () -> RecoveryKey.fromWords(""));
        assertThrows(CoordinationArgumentException.class, () -> RecoveryKey.fromWords("   "));
        assertThrows(NullPointerException.class, () -> RecoveryKey.fromWords(null));
    }

    @Test
    void caseAndSpacingAreTolerated() {
        String vector = repeat("zoo", 23, "vote");
        RecoveryKey plain = RecoveryKey.fromWords(vector);
        RecoveryKey messy = RecoveryKey.fromWords("  " + vector.toUpperCase().replace(" ", " \n\t ") + "\n ");
        assertEquals(plain.words(), messy.words());
        assertArrayEquals(plain.publicKey(), messy.publicKey());
    }

    @Test
    void theSameWordsAlwaysGiveTheSameKeyAndDifferentWordsGiveAnother() {
        String zero = repeat("abandon", 23, "art");
        String ones = repeat("zoo", 23, "vote");
        assertArrayEquals(RecoveryKey.fromWords(zero).publicKey(), RecoveryKey.fromWords(zero).publicKey());
        assertArrayEquals(RecoveryKey.fromWords(zero).identity().publicKeyEncoded(), RecoveryKey.fromWords(zero).publicKey());
        assertFalse(Arrays.equals(RecoveryKey.fromWords(zero).publicKey(), RecoveryKey.fromWords(ones).publicKey()));
    }

    @Test
    void aGeneratedKeyHasTwentyFourWordsThatReadBackToTheSameKey() {
        RecoveryKey generated = RecoveryKey.generate();
        List<String> words = generated.words();
        assertEquals(24, words.size());
        RecoveryKey back = RecoveryKey.fromWords(String.join(" ", words));
        assertEquals(words, back.words());
        assertArrayEquals(generated.publicKey(), back.publicKey());
        assertNotEquals(words, RecoveryKey.generate().words());
    }

    @Test
    void theIdentitySignsAndThePublicKeyVerifies() throws Exception {
        RecoveryKey key = RecoveryKey.generate();
        byte[] message = "statement".getBytes(StandardCharsets.UTF_8);
        byte[] signature = key.identity().sign(message);
        assertTrue(NodeIdentity.verifySignature(key.publicKey(), message, signature));
        assertFalse(NodeIdentity.verifySignature(key.publicKey(), "other".getBytes(StandardCharsets.UTF_8), signature));
        assertFalse(NodeIdentity.verifySignature(RecoveryKey.generate().publicKey(), message, signature));
        RecoveryKey again = RecoveryKey.fromWords(String.join(" ", key.words()));
        assertTrue(NodeIdentity.verifySignature(key.publicKey(), message, again.identity().sign(message)));
    }

    @Test
    void theWordListIsTheOfficialEnglishList() throws Exception {
        List<String> words;
        try (InputStream in = RecoveryKey.class.getResourceAsStream("bip39-english.txt")) {
            words = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        assertEquals(2048, words.size());
        assertEquals(words.stream().sorted().toList(), words);
        assertEquals(2048, words.stream().distinct().count());
        String joined = String.join("\n", words) + "\n";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
        assertEquals("2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda", HexFormat.of().formatHex(digest));
    }
}
