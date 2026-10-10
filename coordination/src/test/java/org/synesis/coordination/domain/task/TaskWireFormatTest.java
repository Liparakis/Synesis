package org.synesis.coordination.domain.task;

import static org.synesis.coordination.WireGolden.id;
import static org.synesis.coordination.WireGolden.pin;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;

/**
 * Pins the persisted bytes of the task payloads.
 */
class TaskWireFormatTest {

    private static CompletionPreparedPayload prepared() {
        return new CompletionPreparedPayload(id(1),
                "completion-é",
                id(2),
                3,
                "base0123",
                "refs/prepared/Δ",
                "tree4567",
                List.of("src/a.java", "src/é.java"));
    }

    @Test
    void completionPrepared() throws Exception {
        pin("completionPrepared", "4350525000000001123456789abcdef00fedcba9876543010000000d636f6d706c6574696f6e2dc3a9123456789abcdef00fedcba987654302000000000000000300000008626173653031323300000010726566732f70726570617265642fce94000000087472656534353637000000020000000a7372632f612e6a6176610000000b7372632fc3a92e6a617661", "trailing=CoordinationException: trailing prepared payload bytes | truncated=CoordinationException: truncated payload", prepared(), CompletionPreparedPayload::encode,
                CompletionPreparedPayload::decode);
    }

    @Test
    void completionUnwound() throws Exception {
        WorkIntent replacement = new WorkIntent(id(2),
                id(3),
                "agt-Δοκιμή",
                "codex",
                id(1),
                "goal ✓",
                "acceptance",
                "base",
                List.of(ResourceSelector.pathExact("src/a.py")),
                4,
                id(4),
                id(5),
                WorkIntent.Status.ANNOUNCED);
        CompletionUnwoundPayload payload = new CompletionUnwoundPayload(prepared(), replacement);
        pin("completionUnwound", "535557310000008e4350525000000001123456789abcdef00fedcba9876543010000000d636f6d706c6574696f6e2dc3a9123456789abcdef00fedcba987654302000000000000000300000008626173653031323300000010726566732f70726570617265642fce94000000087472656534353637000000020000000a7372632f612e6a6176610000000b7372632fc3a92e6a617661000000b553494e37123456789abcdef00fedcba987654302123456789abcdef00fedcba987654303123456789abcdef00fedcba987654304123456789abcdef00fedcba987654305000000106167742dce94cebfcebaceb9cebcceae00000005636f646578123456789abcdef00fedcba98765430100000008676f616c20e29c930000000a616363657074616e6365000000046261736500000000000000040000000100000000087372632f612e7079010000000000000000", "trailing=CoordinationException: trailing unwind bytes | truncated=CoordinationException: truncated payload", payload, CompletionUnwoundPayload::encode, CompletionUnwoundPayload::decode);
    }

    @Test
    void taskSnapshot() throws Exception {
        SnapshotProvenance provenance = new SnapshotProvenance(id(2),
                id(3),
                id(4),
                "agt-Δοκιμή",
                "binding-é",
                5,
                List.of("contract@1"),
                List.of("handoff-1"),
                List.of("src/a.py"),
                "refs/snap",
                "integrity ✓",
                "digest");
        TaskSnapshotPayload payload = new TaskSnapshotPayload(id(1),
                "snap-é",
                "node-a",
                "sup-a",
                "wrk-a",
                "ses-a",
                "base0123",
                "commit4567",
                List.of("src/a.java"),
                List.of("domain.persistence"),
                "summary Δ",
                provenance,
                true);
        pin("taskSnapshot", "534e415000000005123456789abcdef00fedcba98765430100000007736e61702dc3a9000000066e6f64652d61000000057375702d610000000577726b2d61000000057365732d610000000862617365303132330000000a636f6d6d697434353637000000010000000a7372632f612e6a6176610000000100000012646f6d61696e2e70657273697374656e63650000000a73756d6d61727920ce94123456789abcdef00fedcba987654302123456789abcdef00fedcba987654303123456789abcdef00fedcba987654304000000106167742dce94cebfcebaceb9cebcceae0000000a62696e64696e672dc3a90000000000000005000000010000000a636f6e74726163744031000000010000000968616e646f66662d3100000001000000087372632f612e707900000009726566732f736e61700000000d696e7465677269747920e29c930000000664696765737401", "trailing=CoordinationException: Trailing task snapshot payload bytes | truncated=CoordinationException: Malformed task snapshot payload <- EOFException", payload, TaskSnapshotPayload::encode, TaskSnapshotPayload::decode);
    }

    @Test
    void taskClaim() throws Exception {
        TaskClaim claim = new TaskClaim(id(1), "node-é", "sup-a", "wrk-Δ");
        pin("taskClaim", "5354433100000001123456789abcdef00fedcba987654301000000076e6f64652dc3a9000000057375702d610000000677726b2dce94", "trailing=CoordinationException: trailing claim bytes | truncated=CoordinationException: truncated text", claim, TaskClaim::encoded, TaskClaim::decode);
    }

    @Test
    void coordinationTask() throws Exception {
        CoordinationTask task = new CoordinationTask(id(1), id(2), "title ✓", "domain.persistence", "node-é", "sup-a",
                "wrk-Δ");
        pin("coordinationTask", "5343543100000001123456789abcdef00fedcba987654301123456789abcdef00fedcba987654302000000097469746c6520e29c9300000012646f6d61696e2e70657273697374656e6365000000076e6f64652dc3a9000000057375702d610000000677726b2dce94", "trailing=CoordinationException: trailing task bytes | truncated=CoordinationException: truncated text", task, CoordinationTask::encoded, CoordinationTask::decode);
    }
}
