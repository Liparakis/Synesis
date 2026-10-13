package org.synesis.coordination.domain.integration;

import static org.synesis.coordination.WireGolden.id;
import static org.synesis.coordination.WireGolden.pin;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;

/**
 * Pins the persisted bytes of the implementation and integration-attempt payloads.
 */
class IntegrationWireFormatTest {

    private static final CapabilityRequestHandle HANDLE = new CapabilityRequestHandle("req_ABCDEFGHJKLM2345");

    @Test
    void implementationEvent() throws Exception {
        ImplementationEventPayload payload = new ImplementationEventPayload(HANDLE,
                id(1),
                2,
                "base0123",
                "commit4567",
                List.of("src/a.java", "src/é.java"),
                "summary ✓",
                "FAILED",
                "reason Δ",
                List.of("TestOne"),
                "/work/tree",
                "ses-é");
        pin("implementationEvent", "494d504c00000003000000147265715f41424344454647484a4b4c4d32333435123456789abcdef00fedcba987654301000000020000000862617365303132330000000a636f6d6d697434353637000000020000000a7372632f612e6a6176610000000b7372632fc3a92e6a6176610000000b73756d6d61727920e29c93000000064641494c454400000009726561736f6e20ce940000000100000007546573744f6e650000000a2f776f726b2f74726565000000067365732dc3a9", "trailing=CoordinationException: Trailing implementation event payload bytes | truncated=CoordinationException: Truncated implementation payload", payload, ImplementationEventPayload::encode,
                ImplementationEventPayload::decode);
    }

    @Test
    void integrationAttempt() throws Exception {
        IntegrationAttemptPayload payload = new IntegrationAttemptPayload("attempt-é",
                id(1),
                List.of("snap-1", "snap-Δ"),
                "head0123",
                "commit4567",
                "FAILED",
                "conflict ✓");
        pin("integrationAttempt", "494e5447000000010000000a617474656d70742dc3a9123456789abcdef00fedcba9876543010000000200000006736e61702d3100000007736e61702dce940000000868656164303132330000000a636f6d6d697434353637000000064641494c45440000000c636f6e666c69637420e29c93", "trailing=CoordinationException: Trailing integration attempt payload bytes | truncated=CoordinationException: Truncated payload", payload, IntegrationAttemptPayload::encode,
                IntegrationAttemptPayload::decode);
    }

    @Test
    void validationRefusal() throws Exception {
        ValidationRefusalPayload payload = new ValidationRefusalPayload("attempt-é",
                "snap-Δ",
                id(1),
                id(2),
                id(3),
                2,
                id(4),
                3,
                "agt-é",
                "commit4567",
                "[\"git\"]",
                ".",
                "non_zero_exit",
                1,
                "output ✓");
        pin("validationRefusal", "49565246000000010000000a617474656d70742dc3a900000007736e61702dce94123456789abcdef00fedcba987654301123456789abcdef00fedcba987654302123456789abcdef00fedcba9876543030000000000000002123456789abcdef00fedcba9876543040000000000000003000000066167742dc3a90000000a636f6d6d697434353637000000075b22676974225d000000012e0000000d6e6f6e5f7a65726f5f65786974000000010000000a6f757470757420e29c93", "trailing=CoordinationException: Trailing validation refusal payload bytes | truncated=CoordinationException: Truncated payload", payload, ValidationRefusalPayload::encode,
                ValidationRefusalPayload::decode);
    }
}
