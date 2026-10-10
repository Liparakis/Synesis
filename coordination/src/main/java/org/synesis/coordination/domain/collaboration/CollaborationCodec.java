package org.synesis.coordination.domain.collaboration;

import static org.synesis.coordination.domain.identity.WireFormat.encode;
import static org.synesis.coordination.domain.identity.WireFormat.readUuid;
import static org.synesis.coordination.domain.identity.WireFormat.writeUuid;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.capability.CapabilityActorScope;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.LaneGrantId;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.RequestId;
import org.synesis.coordination.domain.identity.SnapshotReference;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Canonical bounded encoding for signed collaboration event payloads.
 */
public final class CollaborationCodec {

    private static final int MAGIC_INTENT_V7 = 0x53494e37;
    private static final int MAGIC_RELEASE = 0x53524c31;
    private static final int MAGIC_NO_CHANGE_COMPLETION = 0x534e4331;
    private static final int MAGIC_REQUEST_V2 = 0x53525132;
    private static final int MAGIC_RESPONSE = 0x53525331;
    private static final int MAGIC_HEARTBEAT = 0x53484231;
    private static final int MAGIC_HANDOFF = 0x53484631;
    private static final int MAGIC_GROUP_V2 = 0x53474732;
    private static final int MAGIC_GRANT_V2 = 0x53475232;
    private static final int MAGIC_CONTINUATION_V2 = 0x53434e32;

    private static final WireFormat TEXT = WireFormat.bounded(8192, false, "text bound", "truncated text");
    private static final WireFormat TEXT_ALLOW_EMPTY = WireFormat.bounded(8192, true, "text bound", "truncated text");
    private static final WireFormat INTENT = WireFormat.bounded(1_000_000,
            false,
            "continuation intent too large",
            "truncated continuation intent");

    /**
     * Reads the fields that follow the magic number of one encoded form.
     *
     * @param <T> decoded type
     */
    @FunctionalInterface
    private interface Fields<T> {

        T read(DataInputStream in) throws IOException;
    }

    /**
     * Validates and initializes this collaboration codec.
     */
    private CollaborationCodec() {
    }

    /**
     * Encodes one announced intent.
     *
     * @param intent intent
     * @return canonical bytes
     */
    public static byte[] encodeIntent(WorkIntent intent) {
        return encode(MAGIC_INTENT_V7, out -> {
            writeUuid(out, intent.intentId());
            writeUuid(out, intent.projectId());
            writeUuid(out, intent.workGroupId());
            writeUuid(out, intent.authorityLineageId());
            TEXT.writeText(out, intent.participant());
            TEXT.writeText(out, intent.provider());
            writeUuid(out, intent.taskId());
            TEXT.writeText(out, intent.goal());
            TEXT.writeText(out, intent.acceptance());
            TEXT.writeText(out, intent.baseCommit());
            out.writeLong(intent.version());
            out.writeInt(intent.selectors().size());
            for (ResourceSelector selector : intent.selectors()) {
                out.writeByte(selector.kind().ordinal());
                TEXT.writeText(out, selector.value());
            }
            out.writeByte(intent.role().wireCode());
            out.writeInt(intent.reviewTargetSelectors().size());
            for (ResourceSelector selector : intent.reviewTargetSelectors()) {
                out.writeByte(selector.kind().ordinal());
                TEXT.writeText(out, selector.value());
            }
            out.writeInt(intent.knownDependencies().size());
            for (String dependency : intent.knownDependencies()) {
                TEXT.writeText(out, dependency);
            }
        });
    }

    /**
     * Decodes one announced intent.
     *
     * @param encoded bytes
     * @return intent
     * @throws IOException malformed bytes
     */
    public static WorkIntent decodeIntent(byte[] encoded) throws IOException {
        return decode(encoded, MAGIC_INTENT_V7, "intent", in -> {
            UUID intentId = readUuid(in);
            UUID projectId = readUuid(in);
            UUID workGroupId = readUuid(in);
            UUID authorityLineageId = readUuid(in);
            String participant = TEXT.readText(in);
            String provider = TEXT.readText(in);
            UUID taskId = readUuid(in);
            String goal = TEXT.readText(in);
            String acceptance = TEXT.readText(in);
            String baseCommit = TEXT.readText(in);
            long version = in.readLong();
            List<ResourceSelector> selectors = readSelectors(in, in.readInt(), false, "selector");
            WorkIntent.Role role = WorkIntent.Role.fromWireCode(in.readUnsignedByte());
            List<ResourceSelector> reviewTargetSelectors = readSelectors(in,
                    in.readInt(),
                    true,
                    "review target selector");
            List<String> knownDependencies = readKnownDependencies(in);
            return new WorkIntent(intentId,
                    projectId,
                    participant,
                    provider,
                    taskId,
                    goal,
                    acceptance,
                    baseCommit,
                    selectors,
                    version,
                    workGroupId,
                    authorityLineageId,
                    WorkIntent.Status.ANNOUNCED,
                    role,
                    reviewTargetSelectors,
                    knownDependencies);
        });
    }

    /**
     * Reads one bounded selector list from the intent wire format.
     *
     * @param in           input positioned at the list values
     * @param count        encoded selector count
     * @param emptyAllowed whether an empty list is valid for this selector role
     * @param label        error-code prefix for this selector role
     * @return selectors in encoded order
     * @throws IOException when count or selector kind is invalid
     */
    private static List<ResourceSelector> readSelectors(DataInputStream in,
            int count,
            boolean emptyAllowed,
            String label) throws IOException {
        if (count < (emptyAllowed ? 0 : 1) || count > 128) {
            throw new CoordinationException(label + " bound");
        }
        List<ResourceSelector> selectors = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int kind = in.readUnsignedByte();
            if (kind >= ResourceSelector.Kind.values().length) {
                throw new CoordinationException(label + " kind");
            }
            selectors.add(new ResourceSelector(ResourceSelector.Kind.values()[kind], TEXT.readText(in)));
        }
        return selectors;
    }

    /**
     * Reads the known capability dependencies from the intent wire format.
     *
     * @param in input positioned at the dependency count
     * @return dependencies in encoded order
     * @throws IOException when the count or a dependency is malformed
     */
    private static List<String> readKnownDependencies(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 50) {
            throw new CoordinationException("known dependency bound");
        }
        List<String> dependencies = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            dependencies.add(TEXT.readText(in));
        }
        return dependencies;
    }

    /**
     * Encodes an intent release.
     *
     * @param intentId intent ID
     * @return canonical bytes
     */
    public static byte[] encodeRelease(UUID intentId) {
        return encode(MAGIC_RELEASE, out -> writeUuid(out, intentId));
    }

    /**
     * Decodes a release.
     *
     * @param encoded bytes
     * @return intent ID
     * @throws IOException malformed bytes
     */
    static UUID decodeRelease(byte[] encoded) throws IOException {
        return decode(encoded, MAGIC_RELEASE, "release", WireFormat::readUuid);
    }

    /**
     * Returns whether a release payload carries explicit no-change evidence.
     *
     * @param encoded release payload
     * @return {@code true} for the no-change completion format
     */
    static boolean isNoChangeCompletion(byte[] encoded) {
        return encoded != null && encoded.length >= 4 &&
                ((encoded[0] & 0xff) << 24 | (encoded[1] & 0xff) << 16 | (encoded[2] & 0xff) << 8 | (encoded[3] & 0xff))
                        == MAGIC_NO_CHANGE_COMPLETION;
    }

    /**
     * Encodes explicit successful completion evidence for a clean intent.
     *
     * @param completion completion evidence
     * @return canonical bytes
     */
    public static byte[] encodeNoChangeCompletion(NoChangeCompletion completion) {
        CoordinationNullException.requireNonNull(completion, "completion");
        return encode(MAGIC_NO_CHANGE_COMPLETION, out -> {
            writeUuid(out, completion.intentId());
            writeUuid(out, completion.workGroupId());
            TEXT.writeText(out, completion.participant());
            TEXT.writeText(out, completion.provider());
            TEXT.writeText(out, completion.bindingIdentity());
            writeUuid(out, completion.authorityLineageId());
            out.writeLong(completion.claimEpoch());
            out.writeLong(completion.workGroupVersion());
            out.writeLong(completion.expectedRevision());
            TEXT.writeText(out, completion.workspaceCommit());
            TEXT.writeText(out, completion.summary());
        });
    }

    /**
     * Decodes explicit successful completion evidence.
     *
     * @param encoded canonical bytes
     * @return completion evidence
     * @throws IOException malformed payload
     */
    static NoChangeCompletion decodeNoChangeCompletion(byte[] encoded) throws IOException {
        return decode(encoded, MAGIC_NO_CHANGE_COMPLETION, "no-change completion", in -> new NoChangeCompletion(
                readUuid(in),
                readUuid(in),
                TEXT.readText(in),
                TEXT.readText(in),
                TEXT.readText(in),
                readUuid(in),
                in.readLong(),
                in.readLong(),
                in.readLong(),
                TEXT.readText(in),
                TEXT.readText(in)));
    }

    /**
     * Encodes a coordination request.
     *
     * @param request request
     * @return encoded request
     */
    public static byte[] encodeRequest(CoordinationRequest request) {
        return encode(MAGIC_REQUEST_V2, out -> {
            writeUuid(out, request.requestId());
            writeUuid(out, request.projectId());
            TEXT.writeText(out, request.requester());
            TEXT.writeText(out, request.target());
            writeUuid(out, request.conflictingIntentId());
            out.writeByte(request.kind().ordinal());
            TEXT.writeText(out, request.proposal());
            out.writeBoolean(request.snapshotId() != null);
            if (request.snapshotId() != null) {
                TEXT.writeText(out, request.snapshotId());
            }
        });
    }

    /**
     * Decodes a coordination request.
     *
     * @param encoded encoded request
     * @return decoded request
     * @throws IOException malformed request
     */
    public static CoordinationRequest decodeRequest(byte[] encoded) throws IOException {
        return decode(encoded, MAGIC_REQUEST_V2, "request", in -> {
            UUID requestId = readUuid(in), projectId = readUuid(in);
            String requester = TEXT.readText(in), target = TEXT.readText(in);
            UUID conflict = readUuid(in);
            int kind = in.readUnsignedByte();
            if (kind >= CoordinationRequest.Kind.values().length) {
                throw new CoordinationException("request kind");
            }
            String proposal = TEXT.readText(in);
            String snapshotId = in.readBoolean() ? TEXT.readText(in) : null;
            return new CoordinationRequest(requestId,
                    projectId,
                    requester,
                    target,
                    conflict,
                    CoordinationRequest.Kind.values()[kind],
                    proposal,
                    CoordinationRequest.Status.PENDING,
                    snapshotId);
        });
    }

    /**
     * Encodes a response using the typed request identity.
     *
     * @param requestId typed identity of the answered request
     * @param status    resulting request status
     * @param proposal  response text retained with the decision
     * @return bounded encoded response payload
     */
    public static byte[] encodeResponse(RequestId requestId, CoordinationRequest.Status status, String proposal) {
        return encode(MAGIC_RESPONSE, out -> {
            writeUuid(out, requestId.value());
            out.writeByte(status.ordinal());
            TEXT.writeText(out, proposal == null ? "" : proposal);
        });
    }

    /**
     * Decodes a request response.
     *
     * @param encoded encoded response
     * @return decoded response
     * @throws IOException malformed response
     */
    static CoordinationResponsePayload decodeResponse(byte[] encoded) throws IOException {
        return decode(encoded, MAGIC_RESPONSE, "response", in -> {
            UUID id = readUuid(in);
            int status = in.readUnsignedByte();
            if (status >= CoordinationRequest.Status.values().length) {
                throw new CoordinationException("request status");
            }
            String proposal = TEXT_ALLOW_EMPTY.readText(in);
            return new CoordinationResponsePayload(new RequestId(id),
                    CoordinationRequest.Status.values()[status],
                    proposal);
        });
    }

    /**
     * Encodes an accepted handoff using semantically distinct identity values.
     *
     * @param handoff validated participant and epoch binding
     * @return encoded handoff payload
     */
    public static byte[] encodeHandoff(ClaimHandoff handoff) {
        return encode(MAGIC_HANDOFF, out -> {
            writeUuid(out, handoff.intent().value());
            TEXT.writeText(out, handoff.targetParticipant().value());
            out.writeLong(handoff.expectedEpoch().value());
        });
    }

    /**
     * Decodes an accepted handoff.
     *
     * @param encoded payload
     * @return handoff
     * @throws IOException malformed payload
     */
    static ClaimHandoff decodeHandoff(byte[] encoded) throws IOException {
        return decode(encoded, MAGIC_HANDOFF, "handoff", in -> {
            UUID id = readUuid(in);
            String target = TEXT.readText(in);
            long version = in.readLong();
            return new ClaimHandoff(new WorkIntentId(id), new ParticipantId(target), new ClaimEpoch(version));
        });
    }

    /**
     * Encodes an atomic continuation acceptance.
     *
     * @param continuation continuation payload
     * @return encoded payload
     */
    public static byte[] encodeContinuation(LaneContinuation continuation) {
        CoordinationNullException.requireNonNull(continuation, "continuation");
        byte[] intent = encodeIntent(continuation.targetIntent());
        return encode(MAGIC_CONTINUATION_V2, out -> {
            writeUuid(out, continuation.grant().value());
            writeUuid(out, continuation.sourceIntent().value());
            INTENT.writeBytes(out, intent);
            TEXT.writeText(out, continuation.sourceOwner().value());
            TEXT.writeText(out, continuation.targetOwner().value());
            out.writeLong(continuation.claimEpoch().value());
            TEXT.writeText(out, continuation.recoverySnapshot().value());
            out.writeBoolean(continuation.sourceActorScope() != null);
            if (continuation.sourceActorScope() != null) {
                writeActorScope(out, continuation.sourceActorScope());
                writeActorScope(out, continuation.targetActorScope());
            }
        });
    }

    /**
     * Decodes an atomic continuation acceptance.
     *
     * @param encoded encoded payload
     * @return continuation payload
     * @throws IOException malformed payload
     */
    public static LaneContinuation decodeContinuation(byte[] encoded) throws IOException {
        return decode(encoded, MAGIC_CONTINUATION_V2, "continuation", in -> {
            UUID grant = readUuid(in);
            UUID sourceIntent = readUuid(in);
            WorkIntent targetIntent = decodeIntent(INTENT.readBytes(in));
            String source = TEXT.readText(in);
            String target = TEXT.readText(in);
            long epoch = in.readLong();
            String snapshot = TEXT.readText(in);
            CapabilityActorScope sourceActor = null;
            CapabilityActorScope targetActor = null;
            if (in.readBoolean()) {
                sourceActor = readActorScope(in);
                targetActor = readActorScope(in);
            }
            return new LaneContinuation(new LaneGrantId(grant),
                    new WorkIntentId(sourceIntent),
                    targetIntent,
                    new ParticipantId(source),
                    new ParticipantId(target),
                    new ClaimEpoch(epoch),
                    new SnapshotReference(snapshot),
                    sourceActor,
                    targetActor);
        });
    }

    private static void writeActorScope(DataOutputStream out, CapabilityActorScope scope) throws IOException {
        TEXT.writeText(out, scope.nodeId());
        TEXT.writeText(out, scope.supervisorId());
        TEXT.writeText(out, scope.workerId());
        TEXT.writeText(out, scope.sessionId());
    }

    private static CapabilityActorScope readActorScope(DataInputStream in) throws IOException {
        return new CapabilityActorScope(TEXT.readText(in), TEXT.readText(in), TEXT.readText(in), TEXT.readText(in));
    }

    /**
     * Encodes a participant heartbeat.
     *
     * @param participant participant ID
     * @return encoded heartbeat
     */
    public static byte[] encodeHeartbeat(String participant) {
        return encodeHeartbeat(new ParticipantId(participant));
    }

    /**
     * Encodes a heartbeat from a typed participant identity.
     *
     * @param participant participant incarnation to identify
     * @return encoded heartbeat payload
     */
    public static byte[] encodeHeartbeat(ParticipantId participant) {
        return encode(MAGIC_HEARTBEAT, out -> TEXT.writeText(out, participant.value()));
    }

    /**
     * Decodes a participant heartbeat.
     *
     * @param encoded encoded heartbeat
     * @return participant ID
     * @throws IOException malformed heartbeat
     */
    static String decodeHeartbeat(byte[] encoded) throws IOException {
        return decode(encoded, MAGIC_HEARTBEAT, "heartbeat", TEXT::readText);
    }

    /**
     * Encodes a typed request or inbox item identifier.
     *
     * @param id typed request identity
     * @return encoded identifier payload
     */
    public static byte[] encodeUuidText(RequestId id) {
        return encodeHeartbeat(CoordinationNullException.requireNonNull(id, "item ID").value().toString());
    }

    /**
     * Decodes a server-issued inbox item identifier.
     *
     * @param encoded encoded identifier
     * @return decoded identifier
     * @throws IOException malformed identifier
     */
    static UUID decodeUuidText(byte[] encoded) throws IOException {
        try {
            return UUID.fromString(decodeHeartbeat(encoded));
        } catch (IllegalArgumentException failure) {
            throw new CoordinationException("malformed inbox item ID", failure);
        }
    }

    /**
     * Encodes recovery evidence using the typed participant and snapshot reference.
     *
     * @param evidence participant and immutable snapshot association
     * @return encoded recovery payload
     */
    public static byte[] encodeRecovery(RecoveryEvidence evidence) {
        return encode(MAGIC_HEARTBEAT, out -> {
            TEXT.writeText(out, evidence.participantId().value());
            TEXT.writeText(out, evidence.snapshot().value());
        });
    }

    /**
     * Decodes a participant and immutable recovery snapshot reference.
     *
     * @param encoded encoded recovery payload
     * @return decoded recovery
     * @throws IOException malformed payload
     */
    static RecoveryEvidence decodeRecovery(byte[] encoded) throws IOException {
        return decode(encoded, MAGIC_HEARTBEAT, "recovery", in -> {
            String participant = TEXT.readText(in);
            String snapshot = TEXT.readText(in);
            return new RecoveryEvidence(new ParticipantId(participant), new SnapshotReference(snapshot));
        });
    }

    /**
     * Encodes a logical work group.
     *
     * @param group group to encode
     * @return canonical bytes
     */
    public static byte[] encodeWorkGroup(WorkGroup group) {
        return encode(MAGIC_GROUP_V2, out -> {
            writeUuid(out, group.workGroupId());
            writeUuid(out, group.projectId());
            TEXT.writeText(out, group.goal());
            TEXT.writeText(out, group.acceptance());
            out.writeLong(group.version());
            out.writeByte(group.status().ordinal());
            out.writeBoolean(group.reviewPolicy().mandatory());
            out.writeBoolean(group.reviewPolicy().independenceRequired());
        });
    }

    /**
     * Decodes a logical work group.
     *
     * @param encoded bytes to decode
     * @return decoded group
     * @throws IOException if the payload is malformed
     */
    public static WorkGroup decodeWorkGroup(byte[] encoded) throws IOException {
        return decode(encoded, MAGIC_GROUP_V2, "group", in -> {
            UUID id = readUuid(in), project = readUuid(in);
            String goal = TEXT.readText(in), acceptance = TEXT.readText(in);
            long version = in.readLong();
            int status = in.readUnsignedByte();
            ReviewPolicy reviewPolicy = new ReviewPolicy(in.readBoolean(), in.readBoolean());
            if (status >= WorkGroup.Status.values().length) {
                throw new CoordinationException("malformed group");
            }
            return new WorkGroup(id,
                    project,
                    goal,
                    acceptance,
                    version,
                    WorkGroup.Status.values()[status],
                    reviewPolicy);
        });
    }

    /**
     * Encodes a targeted lane grant.
     *
     * @param grant grant to encode
     * @return canonical bytes
     */
    public static byte[] encodeLaneGrant(LaneGrant grant) {
        return encode(MAGIC_GRANT_V2, out -> {
            writeUuid(out, grant.grantId());
            writeUuid(out, grant.workGroupId());
            writeUuid(out, grant.targetIntentId());
            TEXT.writeText(out, grant.targetParticipant());
            out.writeLong(grant.claimEpoch());
            out.writeBoolean(grant.singleUse());
            out.writeBoolean(grant.snapshotId() != null);
            if (grant.snapshotId() != null) {
                TEXT.writeText(out, grant.snapshotId());
            }
        });
    }

    /**
     * Decodes a targeted lane grant.
     *
     * @param encoded bytes to decode
     * @return decoded grant
     * @throws IOException if the payload is malformed
     */
    public static LaneGrant decodeLaneGrant(byte[] encoded) throws IOException {
        return decode(encoded, MAGIC_GRANT_V2, "grant", in -> {
            UUID id = readUuid(in), group = readUuid(in), intent = readUuid(in);
            String target = TEXT.readText(in);
            long epoch = in.readLong();
            boolean single = in.readBoolean();
            String snapshotId = in.readBoolean() ? TEXT.readText(in) : null;
            return new LaneGrant(id, group, intent, target, epoch, single, snapshotId);
        });
    }

    /**
     * Decodes one form: checks the magic number, reads the fields, and rejects trailing bytes.
     *
     * @param encoded bytes to decode
     * @param magic   magic number of the form
     * @param noun    name of the form in failure messages
     * @param fields  reader of the fields after the magic number
     * @return the decoded value
     * @throws IOException when the bytes are not exactly one well-formed instance of the form
     */
    private static <T> T decode(byte[] encoded, int magic, String noun, Fields<T> fields) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != magic) {
                throw new CoordinationException("unsupported " + noun + " format");
            }
            T value = fields.read(in);
            if (in.available() != 0) {
                throw new CoordinationException("trailing " + noun + " bytes");
            }
            return value;
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed " + noun, failure);
        }
    }

}
