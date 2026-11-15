package org.synesis.coordination.domain.capability;

import static org.synesis.coordination.WireGolden.id;
import static org.synesis.coordination.WireGolden.pin;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the persisted bytes of the capability request payload.
 */
class CapabilityRequestPayloadWireTest {

    private static final String FORMAT_FOUR = "5343525000000004000000147265715f41424344454647484a4b4c4d3233343500000012646f6d61696e2e70657273697374656e63650000000e6e6f64652d7265717565737465720000000d7375702d7265717565737465720000000d77726b2d7265717565737465720000000a6e6f64652d6f776e6572000000097375702d6f776e65720000000977726b2d6f776e6572123456789abcdef00fedcba98765430100000000000000050000000a707572706f736520c3a900000009696e7075747320ce94000000066f7574707574000000020000000c6265686176696f72206f6e65000000106265686176696f722074776f20e29c93000000010000000874657374206f6e65000000066572726f727300000007656666656374730000000a696e76617269616e747300000006636f6d70617400000004706572660000000b636f6e63757272656e63790000000e6177616974696e675f6f776e65720000000a726561736f6e20e29c93";
    private static final CapabilityRequestHandle HANDLE = new CapabilityRequestHandle("req_ABCDEFGHJKLM2345");

    /** Format 4, the form before {@code publishedContractRevision}: still read, with that field 0. */
    @Test
    void formatFourIsStillRead() throws Exception {
        CapabilityRequestPayload decoded = CapabilityRequestPayload.decode(org.synesis.coordination.WireGolden.bytes(FORMAT_FOUR));
        org.junit.jupiter.api.Assertions.assertEquals(0, decoded.publishedContractRevision());
        org.junit.jupiter.api.Assertions.assertEquals("domain.persistence", decoded.capability());
        org.junit.jupiter.api.Assertions.assertEquals(5, decoded.expectedContractRevision());
    }

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
                5,
                7);
        pin("capabilityRequest", "5343525000000005000000147265715f41424344454647484a4b4c4d3233343500000012646f6d61696e2e70657273697374656e63650000000e6e6f64652d7265717565737465720000000d7375702d7265717565737465720000000d77726b2d7265717565737465720000000a6e6f64652d6f776e6572000000097375702d6f776e65720000000977726b2d6f776e6572123456789abcdef00fedcba98765430100000000000000050000000a707572706f736520c3a900000009696e7075747320ce94000000066f7574707574000000020000000c6265686176696f72206f6e65000000106265686176696f722074776f20e29c93000000010000000874657374206f6e65000000066572726f727300000007656666656374730000000a696e76617269616e747300000006636f6d70617400000004706572660000000b636f6e63757272656e63790000000e6177616974696e675f6f776e65720000000a726561736f6e20e29c930000000000000007", "trailing=CoordinationException: Trailing capability request payload bytes | truncated=CoordinationException: Malformed capability request payload <- EOFException", payload, CapabilityRequestPayload::encode, CapabilityRequestPayload::decode);
    }
}
