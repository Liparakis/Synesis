package org.synesis.coordination.domain;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * Production implementation of {@link CapabilityRequestHandleGenerator} using {@link SecureRandom}.
 *
 * <p>Generates handles containing 128 bits (16 bytes) of cryptographic randomness,
 * formatted as {@code req_<BASE32_TOKEN>}.
 *
 * @since 1.0
 */
public final class SecureRandomCapabilityRequestHandleGenerator implements CapabilityRequestHandleGenerator {

    private static final char[] BASE32_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final SecureRandom random;

    /**
     * Creates a handle generator backed by a new {@link SecureRandom}.
     */
    public SecureRandomCapabilityRequestHandleGenerator() {
        this(new SecureRandom());
    }

    /**
     * Creates a handle generator backed by the supplied {@link SecureRandom}.
     *
     * @param random secure random instance
     */
    public SecureRandomCapabilityRequestHandleGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public CapabilityRequestHandle generate() {
        byte[] bytes = new byte[16]; // 128 bits >= 96 bits required
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("req_");
        for (byte b : bytes) {
            int val = b & 0xFF;
            sb.append(BASE32_ALPHABET[(val >> 3) & 0x1F]);
            sb.append(BASE32_ALPHABET[val & 0x07]);
        }
        return CapabilityRequestHandle.parse(sb.toString());
    }
}
