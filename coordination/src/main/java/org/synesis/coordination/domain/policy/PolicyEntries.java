package org.synesis.coordination.domain.policy;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * The signed payload of {@code PROJECT_POLICY_SET} (ADR-0104, Part A): one owner decision, named from a closed set.
 * An unknown name makes the entry unreadable, so a newer writer cannot slip a policy an older reader does not
 * understand past it.
 */
public final class PolicyEntries {

    private static final WireFormat TEXT = WireFormat.bounded(256, false, "text bound", "truncated payload");
    private static final WireFormat VALUE = WireFormat.bounded(16_384, true, "policy value bound", "truncated payload").checkingWrites(16_384, "policy value bound");
    private static final WireFormat SIGNATURE = WireFormat.bounded(128, false, "signature bound", "truncated payload");

    private PolicyEntries() {
    }

    /**
     * What a decision decides: the closed set of ADR-0104's table. The first entry of a project names
     * {@link #OPERATOR_KEY}; every later one is signed by that key.
     */
    public enum PolicyName {
        /** The operator key's public half, base64 of its X.509 form. Registered once; replaced only by the key it names. */
        OPERATOR_KEY("operator.key"),
        /** The validation command as JSON text, or empty for none. */
        VALIDATION("validation"),
        /** {@code true} when the validation command also runs in each lane alone. */
        VALIDATION_ALSO_IN_LANE("validation.alsoInLane"),
        /** {@code MANDATORY} or {@code NONE}: whether every work group needs an independent review. */
        REVIEW("review"),
        /** {@code ALLOW} or {@code DENY}: whether agents' commands may run on a computer with no sandbox. */
        SANDBOX_UNSANDBOXED("sandbox.unsandboxed"),
        /** Lines of {@code ro:<path>} or {@code rw:<path>}: the extra folders a sandboxed command may read or write. */
        SANDBOX_PATHS("sandbox.paths");

        private final String wireName;

        PolicyName(String wireName) {
            this.wireName = wireName;
        }

        /**
         * Returns the name as it is written and printed.
         *
         * @return the wire name
         */
        public String wireName() {
            return wireName;
        }

        /**
         * Resolves a wire name.
         *
         * @param name the name
         * @return the decision it names
         * @throws CoordinationException {@code POLICY_NAME_UNKNOWN} for a name outside the closed set
         */
        public static PolicyName of(String name) throws CoordinationException {
            for (PolicyName candidate : values()) {
                if (candidate.wireName.equals(name)) {
                    return candidate;
                }
            }
            throw new CoordinationException("POLICY_NAME_UNKNOWN");
        }

        /**
         * Refuses a value this decision cannot take.
         *
         * @param value the value
         * @throws CoordinationArgumentException when the value is not one this decision takes
         */
        void requireValid(String value) {
            VALUE.requireWritable(value, "policy value");
            boolean valid = switch (this) {
                case OPERATOR_KEY -> validKey(value);
                case VALIDATION -> true;
                case VALIDATION_ALSO_IN_LANE -> value.equals("true") || value.equals("false");
                case REVIEW -> value.equals("MANDATORY") || value.equals("NONE");
                case SANDBOX_UNSANDBOXED -> value.equals("ALLOW") || value.equals("DENY");
                case SANDBOX_PATHS -> value.lines().allMatch(line -> line.startsWith("ro:") && line.length() > 3
                        || line.startsWith("rw:") && line.length() > 3);
            };
            if (!valid) {
                throw new CoordinationArgumentException("policy value is not one " + wireName + " takes");
            }
        }

        private static boolean validKey(String value) {
            try {
                int length = Base64.getDecoder().decode(value).length;
                return length > 0 && length <= 256;
            } catch (IllegalArgumentException malformed) {
                return false;
            }
        }
    }

    /**
     * {@code PROJECT_POLICY_SET}: the owner decides one thing.
     *
     * @param revision          the owner-statement revision its writer held
     * @param name              what is decided
     * @param value             the decision; empty only where the decision takes none
     * @param operatorSignature the operator key's signature over {@link #statement}
     */
    public record ProjectPolicySet(long revision, PolicyName name, String value, byte[] operatorSignature) {

        private static final int MAGIC = 0x53505331;

        /** Validates the fields. */
        public ProjectPolicySet {
            if (revision < 0) {
                throw new CoordinationArgumentException("revision must not be negative");
            }
            CoordinationNullException.requireNonNull(name, "name");
            CoordinationNullException.requireNonNull(value, "value");
            name.requireValid(value);
            CoordinationNullException.requireNonNull(operatorSignature, "operatorSignature");
            if (operatorSignature.length == 0 || operatorSignature.length > 128) {
                throw new CoordinationArgumentException("operator signature out of bounds");
            }
            operatorSignature = operatorSignature.clone();
        }

        /**
         * The bytes the operator key signs: the project, the revision, the name and the value, so a statement cannot
         * be replayed in another project, at another revision, or for another decision.
         *
         * @param projectId the project
         * @return the statement
         */
        public byte[] statement(UUID projectId) {
            return statement(projectId, revision, name, value);
        }

        /**
         * The bytes an operator key signs for one decision.
         *
         * @param projectId the project
         * @param revision  the revision it is written at
         * @param name      what is decided
         * @param value     the decision
         * @return the statement
         */
        public static byte[] statement(UUID projectId, long revision, PolicyName name, String value) {
            byte[] domain = "synesis/PROJECT_POLICY_SET/v1".getBytes(StandardCharsets.UTF_8);
            byte[] wireName = name.wireName().getBytes(StandardCharsets.UTF_8);
            byte[] text = value.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(domain.length + 16 + 8 + 4 + wireName.length + 4 + text.length);
            buffer.put(domain).putLong(projectId.getMostSignificantBits()).putLong(projectId.getLeastSignificantBits())
                    .putLong(revision).putInt(wireName.length).put(wireName).putInt(text.length).put(text);
            return buffer.array();
        }

        /**
         * Returns the encoded payload.
         *
         * @return the encoded payload
         */
        public byte[] encode() {
            return WireFormat.encode(MAGIC, out -> {
                out.writeLong(revision);
                TEXT.writeText(out, name.wireName());
                VALUE.writeText(out, value);
                SIGNATURE.writeBytes(out, operatorSignature);
            });
        }

        /**
         * Decodes and validates one payload.
         *
         * @param encoded bytes
         * @return the payload
         * @throws IOException when malformed, or the name is outside the closed set
         */
        public static ProjectPolicySet decode(byte[] encoded) throws IOException {
            CoordinationNullException.requireNonNull(encoded, "encoded");
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
                if (in.readInt() != MAGIC) {
                    throw new CoordinationException("unsupported policy payload");
                }
                ProjectPolicySet value = new ProjectPolicySet(in.readLong(), PolicyName.of(TEXT.readText(in)), VALUE.readText(in), SIGNATURE.readBytes(in));
                if (in.available() != 0) {
                    throw new CoordinationException("trailing policy bytes");
                }
                return value;
            } catch (CoordinationException failure) {
                throw failure;
            } catch (RuntimeException | EOFException failure) {
                throw new CoordinationException("malformed policy payload", failure);
            }
        }
    }
}
