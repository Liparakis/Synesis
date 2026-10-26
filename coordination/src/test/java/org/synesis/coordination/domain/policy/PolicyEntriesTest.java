package org.synesis.coordination.domain.policy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.policy.PolicyEntries.PolicyName;
import org.synesis.coordination.domain.policy.PolicyEntries.ProjectPolicySet;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;

/**
 * The wire form of one decision: round trip, the closed set of names, bounds, and what the operator key signs.
 */
class PolicyEntriesTest {

    private static final byte[] SIGNATURE = new byte[64];

    @Test
    void everyDecisionRoundTripsWithItsValueAndSignature() throws Exception {
        String key = Base64.getEncoder().encodeToString(new byte[44]);
        for (ProjectPolicySet set : new ProjectPolicySet[] {
                new ProjectPolicySet(0, PolicyName.OPERATOR_KEY, key, SIGNATURE),
                new ProjectPolicySet(3, PolicyName.VALIDATION, "{\"argv\":[\"x\"]}", SIGNATURE),
                new ProjectPolicySet(4, PolicyName.VALIDATION, "", SIGNATURE),
                new ProjectPolicySet(5, PolicyName.VALIDATION_ALSO_IN_LANE, "true", SIGNATURE),
                new ProjectPolicySet(6, PolicyName.REVIEW, "MANDATORY", SIGNATURE),
                new ProjectPolicySet(7, PolicyName.SANDBOX_UNSANDBOXED, "ALLOW", SIGNATURE),
                new ProjectPolicySet(8, PolicyName.SANDBOX_PATHS, "ro:/opt/tools\nrw:/home/x/.gradle", SIGNATURE)}) {
            ProjectPolicySet decoded = ProjectPolicySet.decode(set.encode());

            assertEquals(set.revision(), decoded.revision());
            assertEquals(set.name(), decoded.name());
            assertEquals(set.value(), decoded.value());
            assertArrayEquals(set.operatorSignature(), decoded.operatorSignature());
        }
    }

    @Test
    void aValueTheNameDoesNotTakeIsRefused() {
        assertThrows(CoordinationArgumentException.class, () -> new ProjectPolicySet(1, PolicyName.REVIEW, "SOMETIMES", SIGNATURE));
        assertThrows(CoordinationArgumentException.class, () -> new ProjectPolicySet(1, PolicyName.SANDBOX_UNSANDBOXED, "allow", SIGNATURE));
        assertThrows(CoordinationArgumentException.class, () -> new ProjectPolicySet(1, PolicyName.VALIDATION_ALSO_IN_LANE, "", SIGNATURE));
        assertThrows(CoordinationArgumentException.class, () -> new ProjectPolicySet(1, PolicyName.SANDBOX_PATHS, "/no/prefix", SIGNATURE));
        assertThrows(CoordinationArgumentException.class, () -> new ProjectPolicySet(1, PolicyName.OPERATOR_KEY, "not base64 !", SIGNATURE));
        assertThrows(CoordinationArgumentException.class, () -> new ProjectPolicySet(1, PolicyName.OPERATOR_KEY, "", SIGNATURE));
    }

    @Test
    void theBoundsOfARevisionAValueAndASignatureHold() {
        assertThrows(CoordinationArgumentException.class, () -> new ProjectPolicySet(-1, PolicyName.REVIEW, "NONE", SIGNATURE));
        assertThrows(CoordinationArgumentException.class, () -> new ProjectPolicySet(1, PolicyName.REVIEW, "NONE", new byte[0]));
        assertThrows(CoordinationArgumentException.class, () -> new ProjectPolicySet(1, PolicyName.REVIEW, "NONE", new byte[129]));
        assertThrows(CoordinationArgumentException.class, () -> new ProjectPolicySet(1, PolicyName.VALIDATION, "x".repeat(16_385), SIGNATURE));
    }

    @Test
    void aNameOutsideTheClosedSetAndABadPayloadAreRefusedOnDecode() throws Exception {
        byte[] good = new ProjectPolicySet(1, PolicyName.REVIEW, "NONE", SIGNATURE).encode();
        byte[] renamed = new String(good, java.nio.charset.StandardCharsets.ISO_8859_1).replace("review", "reviewx")
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

        assertThrows(IOException.class, () -> ProjectPolicySet.decode(renamed));
        assertEquals("POLICY_NAME_UNKNOWN", assertThrows(CoordinationException.class, () -> PolicyName.of("gates")).code());
        assertThrows(IOException.class, () -> ProjectPolicySet.decode(java.util.Arrays.copyOf(good, good.length - 1)));
        assertThrows(IOException.class, () -> ProjectPolicySet.decode(java.util.Arrays.copyOf(good, good.length + 1)));
        assertThrows(IOException.class, () -> ProjectPolicySet.decode(new byte[] {1, 2, 3, 4}));
    }

    @Test
    void theStatementNamesTheProjectTheRevisionTheNameAndTheValue() {
        UUID project = UUID.randomUUID();
        byte[] base = ProjectPolicySet.statement(project, 1, PolicyName.REVIEW, "NONE");

        assertFalse(java.util.Arrays.equals(base, ProjectPolicySet.statement(UUID.randomUUID(), 1, PolicyName.REVIEW, "NONE")));
        assertFalse(java.util.Arrays.equals(base, ProjectPolicySet.statement(project, 2, PolicyName.REVIEW, "NONE")));
        assertFalse(java.util.Arrays.equals(base, ProjectPolicySet.statement(project, 1, PolicyName.VALIDATION, "NONE")));
        assertFalse(java.util.Arrays.equals(base, ProjectPolicySet.statement(project, 1, PolicyName.REVIEW, "MANDATORY")));
        assertArrayEquals(base, new ProjectPolicySet(1, PolicyName.REVIEW, "NONE", SIGNATURE).statement(project));
    }
}
