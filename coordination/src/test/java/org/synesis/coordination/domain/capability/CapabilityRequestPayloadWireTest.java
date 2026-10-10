package org.synesis.coordination.domain.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.synesis.coordination.WireGolden.id;
import static org.synesis.coordination.WireGolden.pin;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the persisted bytes of the capability request payload and its derived unresolved lineage.
 */
class CapabilityRequestPayloadWireTest {

    private static final CapabilityRequestHandle HANDLE = new CapabilityRequestHandle("req_ABCDEFGHJKLM2345");

    @Test
    void currentFormat() throws Exception {
        CapabilityContract contract = new CapabilityContract("purpose é",
                "inputs Δ",
                "output",
                List.of("behavior one", "behavior two ✓"),
                "errors",
                "effects",
                "invariants",
                "compat",
                "perf",
                "concurrency",
                List.of("test one"));
        CapabilityRequestPayload payload = new CapabilityRequestPayload(HANDLE,
                "domain.persistence",
                "node-requester",
                "sup-requester",
                "wrk-requester",
                "node-owner",
                "sup-owner",
                "wrk-owner",
                id(1),
                contract,
                CapabilityLifecycleState.AWAITING_OWNER,
                "reason ✓",
                5);
        pin("capabilityRequest", "5343525000000004000000147265715f41424344454647484a4b4c4d3233343500000012646f6d61696e2e70657273697374656e63650000000e6e6f64652d7265717565737465720000000d7375702d7265717565737465720000000d77726b2d7265717565737465720000000a6e6f64652d6f776e6572000000097375702d6f776e65720000000977726b2d6f776e6572123456789abcdef00fedcba98765430100000000000000050000000a707572706f736520c3a900000009696e7075747320ce94000000066f7574707574000000020000000c6265686176696f72206f6e65000000106265686176696f722074776f20e29c93000000010000000874657374206f6e65000000066572726f727300000007656666656374730000000a696e76617269616e747300000006636f6d70617400000004706572660000000b636f6e63757272656e63790000000e6177616974696e675f6f776e65720000000a726561736f6e20e29c93", "trailing=CoordinationException: Trailing capability request payload bytes | truncated=CoordinationException: Truncated text payload", payload, CapabilityRequestPayload::encode, CapabilityRequestPayload::decode);
    }

    @Test
    void unresolvedLineageIdIsDerivedFromTheHandle() {
        CapabilityContract contract = new CapabilityContract("p", "i", "o", List.of("b"), "e", "s", "v", "c", "f", "n",
                List.of("t"));
        CapabilityRequestPayload payload = new CapabilityRequestPayload(HANDLE,
                "cap",
                "node-requester",
                "node-owner",
                contract,
                CapabilityLifecycleState.AWAITING_OWNER,
                null);
        assertEquals("919dc2ad-a23e-3472-9910-8e8ab021c6e6", payload.authorityLineageId().toString(), "capLineage lineage");
    }
}
