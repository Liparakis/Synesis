package org.synesis.coordination.domain.collaboration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.synesis.coordination.WireGolden.id;
import static org.synesis.coordination.WireGolden.pin;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.capability.CapabilityActorScope;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.LaneGrantId;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.RequestId;
import org.synesis.coordination.domain.identity.SnapshotReference;
import org.synesis.coordination.domain.identity.WorkIntentId;

/**
 * Pins the persisted bytes of every collaboration event payload so a codec refactor cannot change them unnoticed.
 */
class CollaborationWireFormatTest {

    private static WorkIntent intent() {
        return new WorkIntent(id(1),
                id(2),
                "agt-Δοκιμή",
                "codex",
                id(3),
                "implement ✓ feature",
                "tests pass é",
                "base0123",
                List.of(ResourceSelector.pathExact("src/a.py"), ResourceSelector.pathSubtree("src/lib")),
                7,
                id(4),
                id(5),
                WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.REVIEWER,
                List.of(ResourceSelector.pathExact("src/target.py")),
                List.of("domain.persistence"));
    }

    private static WorkIntent scopeIntent() {
        return new WorkIntent(id(1),
                id(2),
                "agt-owner",
                "codex",
                id(3),
                "goal",
                "acceptance",
                "base",
                List.of(ResourceSelector.pathExact("src/a.py")),
                3,
                id(4),
                id(5),
                WorkIntent.Status.ANNOUNCED);
    }

    @Test
    void announcedIntent() throws Exception {
        pin("intent", "53494e37123456789abcdef00fedcba987654301123456789abcdef00fedcba987654302123456789abcdef00fedcba987654304123456789abcdef00fedcba987654305000000106167742dce94cebfcebaceb9cebcceae00000005636f646578123456789abcdef00fedcba98765430300000015696d706c656d656e7420e29c9320666561747572650000000d7465737473207061737320c3a900000008626173653031323300000000000000070000000200000000087372632f612e707901000000077372632f6c69620200000001000000000d7372632f7461726765742e70790000000100000012646f6d61696e2e70657273697374656e6365", "trailing=CoordinationException: trailing intent bytes | truncated=CoordinationException: truncated text", intent(), CollaborationCodec::encodeIntent, CollaborationCodec::decodeIntent);
    }

    @Test
    void release() throws Exception {
        pin("release", "53524c31123456789abcdef00fedcba987654301", "trailing=CoordinationException: trailing release bytes | truncated=EOFException: null", id(1), CollaborationCodec::encodeRelease, CollaborationCodec::decodeRelease);
    }

    @Test
    void noChangeCompletion() throws Exception {
        NoChangeCompletion completion = new NoChangeCompletion(id(1),
                id(2),
                "agt-Δοκιμή",
                "codex",
                "binding-é",
                id(3),
                4,
                5,
                6,
                "commit0123",
                "nothing to change ✓");
        pin("noChangeCompletion",
                "534e4331123456789abcdef00fedcba987654301123456789abcdef00fedcba987654302000000106167742dce94cebfcebaceb9cebcceae00000005636f6465780000000a62696e64696e672dc3a9123456789abcdef00fedcba9876543030000000000000004000000000000000500000000000000060000000a636f6d6d697430313233000000156e6f7468696e6720746f206368616e676520e29c93",
                "trailing=CoordinationException: trailing no-change completion bytes | truncated=CoordinationException: truncated text",
                completion,
                CollaborationCodec::encodeNoChangeCompletion,
                CollaborationCodec::decodeNoChangeCompletion);
        assertTrue(CollaborationCodec.isNoChangeCompletion(CollaborationCodec.encodeNoChangeCompletion(completion)));
    }

    @Test
    void request() throws Exception {
        CoordinationRequest request = new CoordinationRequest(id(1),
                id(2),
                "agt-requester",
                "agt-Δοκιμή",
                id(3),
                CoordinationRequest.Kind.REVIEW,
                "please review é",
                CoordinationRequest.Status.PENDING,
                "snap-1");
        pin("request", "53525132123456789abcdef00fedcba987654301123456789abcdef00fedcba9876543020000000d6167742d726571756573746572000000106167742dce94cebfcebaceb9cebcceae123456789abcdef00fedcba9876543030300000010706c656173652072657669657720c3a90100000006736e61702d31", "trailing=CoordinationException: trailing request bytes | truncated=CoordinationException: truncated text", request, CollaborationCodec::encodeRequest, CollaborationCodec::decodeRequest);
    }

    @Test
    void requestWithoutSnapshot() throws Exception {
        CoordinationRequest request = new CoordinationRequest(id(1),
                id(2),
                "agt-requester",
                "agt-target",
                id(3),
                CoordinationRequest.Kind.HANDOFF,
                "take over",
                CoordinationRequest.Status.PENDING);
        pin("requestWithoutSnapshot",
                "53525132123456789abcdef00fedcba987654301123456789abcdef00fedcba9876543020000000d6167742d7265717565737465720000000a6167742d746172676574123456789abcdef00fedcba987654303010000000974616b65206f76657200",
                "trailing=CoordinationException: trailing request bytes | truncated=CoordinationException: malformed request <- EOFException",
                request,
                CollaborationCodec::encodeRequest,
                CollaborationCodec::decodeRequest);
    }

    @Test
    void response() throws Exception {
        CoordinationResponsePayload response = new CoordinationResponsePayload(new RequestId(id(1)),
                CoordinationRequest.Status.ACCEPTED,
                "ok é");
        pin("response",
                "53525331123456789abcdef00fedcba98765430101000000056f6b20c3a9",
                "trailing=CoordinationException: trailing response bytes | truncated=CoordinationException: truncated text",
                response,
                payload -> CollaborationCodec.encodeResponse(payload.request(), payload.status(), payload.proposal()),
                CollaborationCodec::decodeResponse);
    }

    @Test
    void responseWithEmptyProposal() throws Exception {
        CoordinationResponsePayload response = new CoordinationResponsePayload(new RequestId(id(1)),
                CoordinationRequest.Status.REJECTED,
                "");
        pin("responseEmpty",
                "53525331123456789abcdef00fedcba9876543010300000000",
                "trailing=CoordinationException: trailing response bytes | truncated=CoordinationException: malformed response <- EOFException",
                response,
                payload -> CollaborationCodec.encodeResponse(payload.request(), payload.status(), payload.proposal()),
                CollaborationCodec::decodeResponse);
    }

    @Test
    void handoff() throws Exception {
        ClaimHandoff handoff = new ClaimHandoff(new WorkIntentId(id(1)),
                new ParticipantId("agt-Δοκιμή"),
                new ClaimEpoch(9));
        pin("handoff", "53484631123456789abcdef00fedcba987654301000000106167742dce94cebfcebaceb9cebcceae0000000000000009", "trailing=CoordinationException: trailing handoff bytes | truncated=CoordinationException: malformed handoff <- EOFException", handoff, CollaborationCodec::encodeHandoff, CollaborationCodec::decodeHandoff);
    }

    @Test
    void continuation() throws Exception {
        LaneContinuation continuation = new LaneContinuation(new LaneGrantId(id(1)),
                new WorkIntentId(id(2)),
                intent(),
                new ParticipantId("agt-old"),
                new ParticipantId("agt-Δοκιμή"),
                new ClaimEpoch(6),
                new SnapshotReference("snap-é"),
                new CapabilityActorScope("node-a", "sup-a", "wrk-a", "ses-a"),
                new CapabilityActorScope("node-b", "sup-b", "wrk-b", "ses-b"));
        pin("continuation",
                "53434e32123456789abcdef00fedcba987654301123456789abcdef00fedcba987654302000000fd53494e37123456789abcdef00fedcba987654301123456789abcdef00fedcba987654302123456789abcdef00fedcba987654304123456789abcdef00fedcba987654305000000106167742dce94cebfcebaceb9cebcceae00000005636f646578123456789abcdef00fedcba98765430300000015696d706c656d656e7420e29c9320666561747572650000000d7465737473207061737320c3a900000008626173653031323300000000000000070000000200000000087372632f612e707901000000077372632f6c69620200000001000000000d7372632f7461726765742e70790000000100000012646f6d61696e2e70657273697374656e6365000000076167742d6f6c64000000106167742dce94cebfcebaceb9cebcceae000000000000000600000007736e61702dc3a901000000066e6f64652d61000000057375702d610000000577726b2d61000000057365732d61000000066e6f64652d62000000057375702d620000000577726b2d62000000057365732d62",
                "trailing=CoordinationException: trailing continuation bytes | truncated=CoordinationException: truncated text",
                continuation,
                CollaborationCodec::encodeContinuation,
                CollaborationCodec::decodeContinuation);
    }

    @Test
    void continuationWithoutActorScopes() throws Exception {
        LaneContinuation continuation = new LaneContinuation(new LaneGrantId(id(1)),
                new WorkIntentId(id(2)),
                intent(),
                new ParticipantId("agt-old"),
                new ParticipantId("agt-new"),
                new ClaimEpoch(6),
                new SnapshotReference("snap"));
        pin("continuationNoScopes",
                "53434e32123456789abcdef00fedcba987654301123456789abcdef00fedcba987654302000000fd53494e37123456789abcdef00fedcba987654301123456789abcdef00fedcba987654302123456789abcdef00fedcba987654304123456789abcdef00fedcba987654305000000106167742dce94cebfcebaceb9cebcceae00000005636f646578123456789abcdef00fedcba98765430300000015696d706c656d656e7420e29c9320666561747572650000000d7465737473207061737320c3a900000008626173653031323300000000000000070000000200000000087372632f612e707901000000077372632f6c69620200000001000000000d7372632f7461726765742e70790000000100000012646f6d61696e2e70657273697374656e6365000000076167742d6f6c64000000076167742d6e6577000000000000000600000004736e617000",
                "trailing=CoordinationException: trailing continuation bytes | truncated=CoordinationException: malformed continuation <- EOFException",
                continuation,
                CollaborationCodec::encodeContinuation,
                CollaborationCodec::decodeContinuation);
    }

    @Test
    void heartbeat() throws Exception {
        pin("heartbeat",
                "53484231000000106167742dce94cebfcebaceb9cebcceae",
                "trailing=CoordinationException: trailing heartbeat bytes | truncated=CoordinationException: truncated text",
                "agt-Δοκιμή",
                participant -> CollaborationCodec.encodeHeartbeat(new ParticipantId(participant)),
                CollaborationCodec::decodeHeartbeat);
    }

    @Test
    void inboxItemId() throws Exception {
        UUID item = id(7);
        pin("inboxItemId",
                "534842310000002431323334353637382d396162632d646566302d306665642d636261393837363534333037",
                "trailing=CoordinationException: trailing heartbeat bytes | truncated=CoordinationException: truncated text",
                item,
                value -> CollaborationCodec.encodeUuidText(new RequestId(value)),
                CollaborationCodec::decodeUuidText);
    }

    @Test
    void recovery() throws Exception {
        RecoveryEvidence evidence = new RecoveryEvidence(new ParticipantId("agt-Δοκιμή"),
                new SnapshotReference("snap-é"));
        pin("recovery", "53484231000000106167742dce94cebfcebaceb9cebcceae00000007736e61702dc3a9", "trailing=CoordinationException: trailing recovery bytes | truncated=CoordinationException: truncated text", evidence, CollaborationCodec::encodeRecovery, CollaborationCodec::decodeRecovery);
    }

    @Test
    void workGroup() throws Exception {
        WorkGroup group = new WorkGroup(id(1),
                id(2),
                "ship it ✓",
                "all green é",
                4,
                WorkGroup.Status.ACTIVE,
                new ReviewPolicy(true, true));
        pin("workGroup", "53474732123456789abcdef00fedcba987654301123456789abcdef00fedcba9876543020000000b7368697020697420e29c930000000c616c6c20677265656e20c3a90000000000000004000101", "trailing=CoordinationException: malformed group | truncated=CoordinationException: malformed group <- EOFException", group, CollaborationCodec::encodeWorkGroup, CollaborationCodec::decodeWorkGroup);
    }

    @Test
    void laneGrant() throws Exception {
        LaneGrant grant = new LaneGrant(id(1), id(2), id(3), "agt-Δοκιμή", 5, true, "snap-é");
        pin("laneGrant", "53475232123456789abcdef00fedcba987654301123456789abcdef00fedcba987654302123456789abcdef00fedcba987654303000000106167742dce94cebfcebaceb9cebcceae0000000000000005010100000007736e61702dc3a9", "trailing=CoordinationException: malformed grant | truncated=CoordinationException: truncated text", grant, CollaborationCodec::encodeLaneGrant, CollaborationCodec::decodeLaneGrant);
    }

    @Test
    void laneGrantWithoutSnapshot() throws Exception {
        LaneGrant grant = new LaneGrant(id(1), id(2), id(3), "agt-target", 5, false);
        pin("laneGrantNoSnapshot",
                "53475232123456789abcdef00fedcba987654301123456789abcdef00fedcba987654302123456789abcdef00fedcba9876543030000000a6167742d74617267657400000000000000050000",
                "trailing=CoordinationException: malformed grant | truncated=CoordinationException: malformed grant <- EOFException",
                grant,
                CollaborationCodec::encodeLaneGrant,
                CollaborationCodec::decodeLaneGrant);
    }

    @Test
    void repairLane() throws Exception {
        RepairLanePayload payload = new RepairLanePayload(id(1), intent(), "snap-é", "head0123", 3, 4);
        pin("repairLane", "52505232123456789abcdef00fedcba987654301000000fd53494e37123456789abcdef00fedcba987654301123456789abcdef00fedcba987654302123456789abcdef00fedcba987654304123456789abcdef00fedcba987654305000000106167742dce94cebfcebaceb9cebcceae00000005636f646578123456789abcdef00fedcba98765430300000015696d706c656d656e7420e29c9320666561747572650000000d7465737473207061737320c3a900000008626173653031323300000000000000070000000200000000087372632f612e707901000000077372632f6c69620200000001000000000d7372632f7461726765742e70790000000100000012646f6d61696e2e70657273697374656e636500000007736e61702dc3a900000008686561643031323300000000000000030000000000000004", "trailing=accepted | truncated=EOFException: null", payload, RepairLanePayload::encode, RepairLanePayload::decode);
    }

    @Test
    void reviewValidation() throws Exception {
        ReviewValidationPayload payload = new ReviewValidationPayload(id(1),
                id(2),
                id(3),
                "agt-Δοκιμή",
                4,
                id(5),
                "snap-é",
                "REJECTED",
                "needs work ✓",
                "agt-reviewer");
        pin("reviewValidation", "53525631123456789abcdef00fedcba987654301123456789abcdef00fedcba987654302123456789abcdef00fedcba987654303000000106167742dce94cebfcebaceb9cebcceae0000000000000004123456789abcdef00fedcba98765430500000007736e61702dc3a90000000852454a45435445440000000e6e6565647320776f726b20e29c930000000c6167742d7265766965776572", "trailing=CoordinationException: trailing review validation bytes | truncated=CoordinationException: truncated review validation", payload, ReviewValidationPayload::encode, ReviewValidationPayload::decode);
    }

    @Test
    void providerSessionTerminal() throws Exception {
        ProviderSessionTerminalPayload payload = new ProviderSessionTerminalPayload("ses-Δ",
                "codex",
                "agt-Δοκιμή",
                "finished é",
                12);
        pin("providerSessionTerminal",
                "53544d31000000067365732dce9400000005636f646578000000106167742dce94cebfcebaceb9cebcceae0000000b66696e697368656420c3a9000000000000000c",
                "trailing=CoordinationException: trailing provider session terminal bytes | truncated=CoordinationException: malformed provider session terminal payload <- EOFException",
                payload,
                ProviderSessionTerminalPayload::encode,
                ProviderSessionTerminalPayload::decode);
    }

    @Test
    void workIntentScopeExtension() throws Exception {
        WorkIntentScopeExtension extension = new WorkIntentScopeExtension(scopeIntent(), 2);
        pin("workIntentScopeExtension",
                "535845310000000000000002000000aa53494e37123456789abcdef00fedcba987654301123456789abcdef00fedcba987654302123456789abcdef00fedcba987654304123456789abcdef00fedcba987654305000000096167742d6f776e657200000005636f646578123456789abcdef00fedcba98765430300000004676f616c0000000a616363657074616e6365000000046261736500000000000000030000000100000000087372632f612e7079010000000000000000",
                "trailing=CoordinationException: trailing work-intent scope extension bytes | truncated=CoordinationException: work-intent scope extension payload bound",
                extension,
                WorkIntentScopeExtension::encode,
                WorkIntentScopeExtension::decode);
    }
}
