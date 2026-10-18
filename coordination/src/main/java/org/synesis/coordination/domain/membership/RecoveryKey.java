package org.synesis.coordination.domain.membership;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.link.identity.NodeIdentity;

/**
 * The project's recovery key (ADR-0098 step 6, designer item 11): 256 random bits shown once as 24 BIP-39 English
 * words and stored nowhere. The words give the bits back, checked by BIP-39's 8-bit checksum; SHA-256 over a fixed
 * domain and the bits is the 32-byte Ed25519 seed, so the same words always give the same key. Its public half goes in
 * {@code PROJECT_SHARED} or {@code OWNER_CHANGED}; the private half exists only while a person types the words.
 */
public final class RecoveryKey {

    private static final int ENTROPY_BYTES = 32;
    private static final int WORDS = 24;
    private static final byte[] DOMAIN = "synesis/recovery-key/v1".getBytes(StandardCharsets.US_ASCII);
    private static final List<String> LIST = readList();
    private static final Map<String, Integer> INDEX = index(LIST);

    private final byte[] entropy;

    private RecoveryKey(byte[] entropy) {
        this.entropy = entropy.clone();
    }

    /**
     * Makes a new recovery key from fresh random bits.
     *
     * @return the key
     */
    public static RecoveryKey generate() {
        byte[] entropy = new byte[ENTROPY_BYTES];
        new SecureRandom().nextBytes(entropy);
        return new RecoveryKey(entropy);
    }

    /**
     * Reads a recovery key back from its words, in any letter case and with any spacing between them.
     *
     * @param words the 24 words
     * @return the key
     * @throws CoordinationArgumentException when a word is not on the list, there are not 24, or the checksum fails
     */
    public static RecoveryKey fromWords(String words) {
        List<String> given = Arrays.stream(Objects.requireNonNull(words, "words").trim().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(word -> !word.isEmpty()).toList();
        if (given.size() != WORDS) {
            throw new CoordinationArgumentException("RECOVERY_WORDS_COUNT: expected 24 words, got " + given.size());
        }
        // 24 words of 11 bits: 256 bits of entropy, then the first 8 bits of its SHA-256.
        byte[] bits = new byte[33];
        int position = 0;
        for (String word : given) {
            Integer value = INDEX.get(word);
            if (value == null) {
                throw new CoordinationArgumentException("RECOVERY_WORD_UNKNOWN: " + word);
            }
            for (int bit = 10; bit >= 0; bit--, position++) {
                if ((value >> bit & 1) == 1) {
                    bits[position / 8] |= (byte) (0x80 >>> position % 8);
                }
            }
        }
        byte[] entropy = Arrays.copyOf(bits, ENTROPY_BYTES);
        if (sha256(entropy)[0] != bits[ENTROPY_BYTES]) {
            throw new CoordinationArgumentException("RECOVERY_WORDS_CHECKSUM: the words have a typo or are in the wrong order");
        }
        return new RecoveryKey(entropy);
    }

    /**
     * The words a person writes down.
     *
     * @return 24 words from the BIP-39 English list
     */
    public List<String> words() {
        byte[] bits = Arrays.copyOf(entropy, ENTROPY_BYTES + 1);
        bits[ENTROPY_BYTES] = sha256(entropy)[0];
        List<String> words = new ArrayList<>(WORDS);
        for (int word = 0; word < WORDS; word++) {
            int value = 0;
            for (int bit = 0; bit < 11; bit++) {
                int position = word * 11 + bit;
                value = value << 1 | (bits[position / 8] >> (7 - position % 8) & 1);
            }
            words.add(LIST.get(value));
        }
        return List.copyOf(words);
    }

    /**
     * The key itself, the same for the same words.
     *
     * @return a signing identity holding the recovery key
     */
    public NodeIdentity identity() {
        byte[] seed = sha256(concat(DOMAIN, entropy));
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            // The generator draws exactly the 32-byte private key from this source.
            generator.initialize(NamedParameterSpec.ED25519, new FixedSeed(seed));
            KeyPair pair = generator.generateKeyPair();
            return NodeIdentity.from(pair);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Ed25519 is not available", unavailable);
        }
    }

    /**
     * The recovery key's public half, as {@code PROJECT_SHARED} and {@code OWNER_CHANGED} carry it.
     *
     * @return the X.509-encoded public key
     */
    public byte[] publicKey() {
        return identity().publicKeyEncoded();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException missing) {
            throw new IllegalStateException(missing);
        }
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] joined = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, joined, left.length, right.length);
        return joined;
    }

    private static List<String> readList() {
        try (InputStream in = RecoveryKey.class.getResourceAsStream("bip39-english.txt")) {
            if (in == null) {
                throw new IllegalStateException("the BIP-39 word list is missing");
            }
            List<String> words = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().map(String::trim)
                    .filter(line -> !line.isEmpty()).toList();
            if (words.size() != 2048) {
                throw new IllegalStateException("the BIP-39 word list has " + words.size() + " words");
            }
            return words;
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static Map<String, Integer> index(List<String> list) {
        Map<String, Integer> index = new HashMap<>();
        for (int position = 0; position < list.size(); position++) {
            index.put(list.get(position), position);
        }
        return Map.copyOf(index);
    }

    /** A random source that returns one fixed seed, so key generation is deterministic. */
    private static final class FixedSeed extends SecureRandom {

        private static final long serialVersionUID = 1L;

        private final byte[] seed;

        private FixedSeed(byte[] seed) {
            this.seed = seed.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            if (bytes.length != seed.length) {
                throw new IllegalStateException("the generator asked for " + bytes.length + " bytes");
            }
            System.arraycopy(seed, 0, bytes, 0, bytes.length);
        }
    }
}
