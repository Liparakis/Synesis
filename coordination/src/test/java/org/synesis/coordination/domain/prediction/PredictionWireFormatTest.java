package org.synesis.coordination.domain.prediction;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.synesis.coordination.WireGolden.edges;
import static org.synesis.coordination.WireGolden.hex;
import static org.synesis.coordination.WireGolden.id;
import static org.synesis.coordination.WireGolden.pin;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.WireGolden;

/**
 * Pins the persisted bytes of prediction contracts and signed events, and the wire code of every event type.
 */
class PredictionWireFormatTest {

    private static final Map<PredictionEventType, Integer> WIRE_CODES = Map.ofEntries(
            entry(PredictionEventType.PREDICTION_CREATED, 0),
            entry(PredictionEventType.PREDICTION_ROUTED, 1),
            entry(PredictionEventType.REQUEST_RECEIVED, 2),
            entry(PredictionEventType.ACCEPTED_EXACT, 3),
            entry(PredictionEventType.ACCEPTED_EQUIVALENT, 4),
            entry(PredictionEventType.CONTRACT_REVISED, 5),
            entry(PredictionEventType.IMPLEMENTATION_STARTED, 6),
            entry(PredictionEventType.PATCH_READY, 7),
            entry(PredictionEventType.CAPABILITY_AVAILABLE, 8),
            entry(PredictionEventType.VALIDATION_STARTED, 9),
            entry(PredictionEventType.SPECULATION_RETIRED, 10),
            entry(PredictionEventType.PREDICTION_INVALIDATED, 11),
            entry(PredictionEventType.REQUEST_REJECTED, 12),
            entry(PredictionEventType.PREDICTION_EXPIRED, 13),
            entry(PredictionEventType.TASK_CREATED, 14),
            entry(PredictionEventType.TASK_CLAIMED, 15),
            entry(PredictionEventType.TASK_RELEASED, 16),
            entry(PredictionEventType.OWNERSHIP_CLAIMED, 17),
            entry(PredictionEventType.OWNERSHIP_RELEASED, 18),
            entry(PredictionEventType.CAPABILITY_REQUEST_CREATED, 19),
            entry(PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED, 20),
            entry(PredictionEventType.CAPABILITY_REQUEST_ACCEPTED, 21),
            entry(PredictionEventType.CAPABILITY_REQUEST_REJECTED, 22),
            entry(PredictionEventType.CAPABILITY_REQUEST_CANCELLED, 23),
            entry(PredictionEventType.CAPABILITY_REQUEST_SUPERSEDED, 24),
            entry(PredictionEventType.CAPABILITY_IMPLEMENTATION_PUBLISHED, 25),
            entry(PredictionEventType.CAPABILITY_VALIDATION_STARTED, 26),
            entry(PredictionEventType.CAPABILITY_IMPLEMENTATION_VALIDATED, 27),
            entry(PredictionEventType.CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED, 28),
            entry(PredictionEventType.TASK_COMPLETION_REQUESTED, 29),
            entry(PredictionEventType.TASK_SNAPSHOT_CREATED, 30),
            entry(PredictionEventType.TASK_WAITING_FOR_DEPENDENCIES, 31),
            entry(PredictionEventType.INTEGRATION_ATTEMPT_STARTED, 32),
            entry(PredictionEventType.INTEGRATION_ATTEMPT_FAILED, 33),
            entry(PredictionEventType.INTEGRATION_CONFLICTED, 34),
            entry(PredictionEventType.INTEGRATION_COMMIT_CREATED, 35),
            entry(PredictionEventType.CONTROL_BRANCH_ADVANCED, 36),
            entry(PredictionEventType.TASK_INTEGRATED, 37),
            entry(PredictionEventType.SESSION_FINALIZED, 38),
            entry(PredictionEventType.SESSION_ABANDONED, 39),
            entry(PredictionEventType.TASK_CANCELLATION_REQUESTED, 40),
            entry(PredictionEventType.TASK_CANCELLED, 41),
            entry(PredictionEventType.DEPENDENCY_INVALIDATED, 42),
            entry(PredictionEventType.WORK_INTENT_ANNOUNCED, 43),
            entry(PredictionEventType.WORK_INTENT_RELEASED, 44),
            entry(PredictionEventType.COORDINATION_REQUESTED, 45),
            entry(PredictionEventType.COORDINATION_RESPONDED, 46),
            entry(PredictionEventType.PARTICIPANT_HEARTBEAT, 47),
            entry(PredictionEventType.CLAIM_HANDOFF_ACCEPTED, 48),
            entry(PredictionEventType.PARTICIPANT_ABANDONED, 49),
            entry(PredictionEventType.CONTRACT_PUBLISHED, 50),
            entry(PredictionEventType.CONTRACT_DEPENDENCY_BOUND, 51),
            entry(PredictionEventType.CONTRACT_SUPERSEDED, 52),
            entry(PredictionEventType.WORK_GROUP_CREATED, 53),
            entry(PredictionEventType.LANE_GRANT_ISSUED, 54),
            entry(PredictionEventType.LANE_GRANT_CONSUMED, 55),
            entry(PredictionEventType.LANE_REVOKED, 56),
            entry(PredictionEventType.WORK_GROUP_STATUS_CHANGED, 57),
            entry(PredictionEventType.PARTICIPANT_SUSPENDED, 58),
            entry(PredictionEventType.RECOVERY_SNAPSHOT_HELD, 59),
            entry(PredictionEventType.PARTICIPANT_REVOKED, 60),
            entry(PredictionEventType.INBOX_ITEM_ACKNOWLEDGED, 61),
            entry(PredictionEventType.PARTICIPANT_CANCELLED, 62),
            entry(PredictionEventType.LANE_CONTINUATION_ACCEPTED, 63),
            entry(PredictionEventType.PARTICIPANT_DETACHED, 64),
            entry(PredictionEventType.COMPLETION_PREPARED, 65),
            entry(PredictionEventType.INTEGRATION_BLOCKED, 66),
            entry(PredictionEventType.REPAIR_REQUIRED, 67),
            entry(PredictionEventType.REPAIR_LANE_CREATED, 68),
            entry(PredictionEventType.COMPLETION_UNWOUND, 69),
            entry(PredictionEventType.REVIEW_VALIDATION_RECORDED, 70),
            entry(PredictionEventType.PROVIDER_SESSION_TERMINALIZED, 71),
            entry(PredictionEventType.WORK_INTENT_SCOPE_EXTENDED, 72),
            entry(PredictionEventType.OPERATOR_REQUEST_RAISED, 73),
            entry(PredictionEventType.OPERATOR_REQUEST_CLOSED, 74),
            entry(PredictionEventType.CONTRACT_CHANGE_REQUESTED, 75),
            entry(PredictionEventType.CONTRACT_CHANGE_ANSWERED, 76),
            entry(PredictionEventType.CONTRACT_LANE_ENDED, 77),
            entry(PredictionEventType.CONVERSATION_MESSAGE_SENT, 78),
            entry(PredictionEventType.CONVERSATION_MESSAGES_DELIVERED, 79),
            entry(PredictionEventType.INTEGRATION_VALIDATION_REFUSED, 80),
            entry(PredictionEventType.REMERGE_SNAPSHOT_CREATED, 81),
            entry(PredictionEventType.COMBINE_REQUIRED, 82),
            entry(PredictionEventType.PROJECT_SHARED, 83),
            entry(PredictionEventType.JOIN_REQUESTED, 84),
            entry(PredictionEventType.JOIN_ANSWERED, 85),
            entry(PredictionEventType.JOIN_POLICY_SET, 86),
            entry(PredictionEventType.MEMBER_REMOVED, 87),
            entry(PredictionEventType.MEMBER_CARD, 88),
            entry(PredictionEventType.OWNER_CHANGED, 89),
            entry(PredictionEventType.WORK_TRASHED, 90),
            entry(PredictionEventType.TRASH_RESTORED, 91),
            entry(PredictionEventType.TRASH_EMPTIED, 92)
    );

    @Test
    void everyEventTypeHasItsHistoricalWireCode() {
        for (PredictionEventType type : PredictionEventType.values()) {
            assertTrue(WIRE_CODES.containsKey(type), "missing from the wire-code table: " + type);
            assertEquals(WIRE_CODES.get(type), type.wireCode(), type.name());
        }
        assertEquals(PredictionEventType.values().length, WIRE_CODES.size());
    }

    @Test
    void wireCodesAreUniqueAndResolveBack() {
        assertEquals(WIRE_CODES.size(), WIRE_CODES.values().stream().distinct().count());
        WIRE_CODES.forEach((type, code) -> assertEquals(type, PredictionEventType.fromWireCode(code)));
    }

    @Test
    void contract() throws Exception {
        PredictionContract contract = new PredictionContract(id(1),
                id(2),
                "node-requester",
                "sup-requester",
                "wrk-requester",
                id(3),
                "domain.persistence",
                "node-owner",
                "sup-owner",
                List.of("src/a/**", "src/é/**"),
                10,
                "base0123",
                List.of("hash-1"),
                4,
                "purpose Δοκιμή",
                "inputs",
                "outputs ✓",
                "behavior",
                "errors",
                "effects",
                "invariants",
                "compat",
                "perf",
                "concurrency",
                List.of("test one"),
                80,
                20,
                1_700_000_000_000L);
        pin("predictionContract", "5343503101123456789abcdef00fedcba987654301123456789abcdef00fedcba9876543020000000e6e6f64652d7265717565737465720000000d7375702d7265717565737465720000000d77726b2d72657175657374657200000012646f6d61696e2e70657273697374656e63650000000a6e6f64652d6f776e6572000000097375702d6f776e657200000008626173653031323300000014707572706f736520ce94cebfcebaceb9cebcceae00000006696e707574730000000b6f75747075747320e29c93000000086265686176696f72000000066572726f727300000007656666656374730000000a696e76617269616e747300000006636f6d70617400000004706572660000000b636f6e63757272656e6379123456789abcdef00fedcba98765430300000002000000087372632f612f2a2a000000097372632fc3a92f2a2a000000000000000a0000000100000006686173682d310000000000000004000000010000000874657374206f6e6500000050000000140000018bcfe56800", "trailing=CoordinationException: trailing contract bytes | truncated=CoordinationException: malformed prediction contract <- EOFException", contract, PredictionContract::encoded, PredictionContract::decode);
    }

    /** Signed with a throwaway key; the bytes were embedded once because the key is random. */
    private static final String SIGNED_EVENT = "5343453100000001f59dd70c30ce3090b6ea53bcdaa2107e123456789abcdef00fedcba987654301123456789abcdef00fedcba98765430200000000000000030000002c00000044736c312d643230623735643533343135623537396438336639646561633230353166346464633737613737373239653035366332656366393738653431313634303732330000018bcfe568000000001453524c31123456789abcdef00fedcba98765430900000020000000000000000000000000000000000000000000000000000000000000000000000020507a66403d8b510ba88812faea0b9a4657bb9cb1558bf250fc89754f8c0298cb0000002c302a300506032b65700321008c92645dd5a458347cd213dc6412d3beeb1cea3268e1cfcf1d9c887dabe9e9c200000040ba86a93adbfa3db09a36bd6683b340ee7ec6d20e3c62fca11731e17824755915b8951645ede1fc675db090643754788efaf5e7b0a15a15c62664c1638d15250b";

    @Test
    void signedEvent() throws Exception {
        byte[] golden = WireGolden.bytes(SIGNED_EVENT);
        PredictionEvent event = PredictionEvent.decode(golden);

        assertEquals(PredictionEventType.WORK_INTENT_RELEASED, event.type());
        assertEquals(44, event.type().wireCode());
        assertEquals(3L, event.sequence());
        assertEquals(1_700_000_000_000L, event.createdAtEpochMillis());
        assertEquals(id(1), event.projectId());
        assertEquals(id(2), event.predictionId());
        assertTrue(event.verify());
        assertEquals(SIGNED_EVENT, hex(event.encoded()));
        assertEquals("trailing=CoordinationException: trailing event bytes | truncated=CoordinationException: truncated event", edges(PredictionEvent::decode, golden), "signedEvent edges");
    }
}
