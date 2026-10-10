package org.synesis.coordination.domain.collaboration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.capability.CapabilityActorScope;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.LaneGrantId;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.RequestId;
import org.synesis.coordination.domain.identity.SnapshotReference;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationInvariantError;
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
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_INTENT_V7);
            uuid(out, intent.intentId());
            uuid(out, intent.projectId());
            uuid(out, intent.workGroupId());
            uuid(out, intent.authorityLineageId());
            text(out, intent.participant());
            text(out, intent.provider());
            uuid(out, intent.taskId());
            text(out, intent.goal());
            text(out, intent.acceptance());
            text(out, intent.baseCommit());
            out.writeLong(intent.version());
            out.writeInt(intent.selectors().size());
            for (ResourceSelector selector : intent.selectors()) {
                out.writeByte(selector.kind().ordinal());
                text(out, selector.value());
            }
            out.writeByte(intent.role().wireCode());
            out.writeInt(intent.reviewTargetSelectors().size());
            for (ResourceSelector selector : intent.reviewTargetSelectors()) {
                out.writeByte(selector.kind().ordinal());
                text(out, selector.value());
            }
            out.writeInt(intent.knownDependencies().size());
            for (String dependency : intent.knownDependencies()) {
                text(out, dependency);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }

    /**
     * Decodes one announced intent.
     *
     * @param encoded bytes
     * @return intent
     * @throws IOException malformed bytes
     */
    public static WorkIntent decodeIntent(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            int magic = in.readInt();
            if (magic != MAGIC_INTENT_V7) {
                throw new CoordinationException("unsupported intent format");
            }
            UUID intentId = readUuid(in);
            UUID projectId = readUuid(in);
            UUID workGroupId = readUuid(in);
            UUID authorityLineageId = readUuid(in);
            String participant = readText(in);
            String provider = readText(in);
            UUID taskId = readUuid(in);
            String goal = readText(in);
            String acceptance = readText(in);
            String baseCommit = readText(in);
            long version = in.readLong();
            List<ResourceSelector> selectors = readSelectors(in, in.readInt(), false, "selector");
            WorkIntent.Role role = WorkIntent.Role.fromWireCode(in.readUnsignedByte());
            List<ResourceSelector> reviewTargetSelectors = readSelectors(in,
                    in.readInt(),
                    true,
                    "review target selector");
            List<String> knownDependencies = readKnownDependencies(in);
            if (in.available() != 0) {
                throw new CoordinationException("trailing intent bytes");
            }
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
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed intent", failure);
        }
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
            selectors.add(new ResourceSelector(ResourceSelector.Kind.values()[kind], readText(in)));
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
            dependencies.add(readText(in));
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
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_RELEASE);
            uuid(out, intentId);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }

    /**
     * Decodes a release.
     *
     * @param encoded bytes
     * @return intent ID
     * @throws IOException malformed bytes
     */
    public static UUID decodeRelease(byte[] encoded) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
        if (in.readInt() != MAGIC_RELEASE) {
            throw new CoordinationException("unsupported release format");
        }
        UUID id = readUuid(in);
        if (in.available() != 0) {
            throw new CoordinationException("trailing release bytes");
        }
        return id;
    }

    /**
     * Returns whether a release payload carries explicit no-change evidence.
     *
     * @param encoded release payload
     * @return {@code true} for the no-change completion format
     */
    public static boolean isNoChangeCompletion(byte[] encoded) {
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
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_NO_CHANGE_COMPLETION);
            uuid(out, completion.intentId());
            uuid(out, completion.workGroupId());
            text(out, completion.participant());
            text(out, completion.provider());
            text(out, completion.bindingIdentity());
            uuid(out, completion.authorityLineageId());
            out.writeLong(completion.claimEpoch());
            out.writeLong(completion.workGroupVersion());
            out.writeLong(completion.expectedRevision());
            text(out, completion.workspaceCommit());
            text(out, completion.summary());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }

    /**
     * Decodes explicit successful completion evidence.
     *
     * @param encoded canonical bytes
     * @return completion evidence
     * @throws IOException malformed payload
     */
    public static NoChangeCompletion decodeNoChangeCompletion(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC_NO_CHANGE_COMPLETION) {
                throw new CoordinationException("unsupported no-change completion format");
            }
            NoChangeCompletion completion = new NoChangeCompletion(readUuid(in),
                    readUuid(in),
                    readText(in),
                    readText(in),
                    readText(in),
                    readUuid(in),
                    in.readLong(),
                    in.readLong(),
                    in.readLong(),
                    readText(in),
                    readText(in));
            if (in.available() != 0) {
                throw new CoordinationException("trailing no-change completion bytes");
            }
            return completion;
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed no-change completion", failure);
        }
    }

    /**
     * Encodes a coordination request.
     *
     * @param request request
     * @return encoded request
     */
    public static byte[] encodeRequest(CoordinationRequest request) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_REQUEST_V2);
            uuid(out, request.requestId());
            uuid(out, request.projectId());
            text(out, request.requester());
            text(out, request.target());
            uuid(out, request.conflictingIntentId());
            out.writeByte(request.kind().ordinal());
            text(out, request.proposal());
            out.writeBoolean(request.snapshotId() != null);
            if (request.snapshotId() != null) {
                text(out, request.snapshotId());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }

    /**
     * Decodes a coordination request.
     *
     * @param encoded encoded request
     * @return decoded request
     * @throws IOException malformed request
     */
    public static CoordinationRequest decodeRequest(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            int magic = in.readInt();
            if (magic != MAGIC_REQUEST_V2) {
                throw new CoordinationException("unsupported request format");
            }
            UUID requestId = readUuid(in), projectId = readUuid(in);
            String requester = readText(in), target = readText(in);
            UUID conflict = readUuid(in);
            int kind = in.readUnsignedByte();
            if (kind >= CoordinationRequest.Kind.values().length) {
                throw new CoordinationException("request kind");
            }
            String proposal = readText(in);
            String snapshotId = in.readBoolean() ? readText(in) : null;
            if (in.available() != 0) {
                throw new CoordinationException("trailing request bytes");
            }
            return new CoordinationRequest(requestId,
                    projectId,
                    requester,
                    target,
                    conflict,
                    CoordinationRequest.Kind.values()[kind],
                    proposal,
                    CoordinationRequest.Status.PENDING,
                    snapshotId);
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed request", failure);
        }
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
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_RESPONSE);
            uuid(out, requestId.value());
            out.writeByte(status.ordinal());
            text(out, proposal == null ? "" : proposal);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }

    /**
     * Decodes a request response.
     *
     * @param encoded encoded response
     * @return decoded response
     * @throws IOException malformed response
     */
    public static CoordinationResponsePayload decodeResponse(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC_RESPONSE) {
                throw new CoordinationException("unsupported response format");
            }
            UUID id = readUuid(in);
            int status = in.readUnsignedByte();
            if (status >= CoordinationRequest.Status.values().length) {
                throw new CoordinationException("request status");
            }
            String proposal = readTextAllowEmpty(in);
            if (in.available() != 0) {
                throw new CoordinationException("trailing response bytes");
            }
            return new CoordinationResponsePayload(new RequestId(id),
                    CoordinationRequest.Status.values()[status],
                    proposal);
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed response", failure);
        }
    }

    /**
     * Encodes an accepted handoff using semantically distinct identity values.
     *
     * @param handoff validated participant and epoch binding
     * @return encoded handoff payload
     */
    public static byte[] encodeHandoff(ClaimHandoff handoff) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_HANDOFF);
            uuid(out, handoff.intent().value());
            text(out, handoff.targetParticipant().value());
            out.writeLong(handoff.expectedEpoch().value());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }

    /**
     * Decodes an accepted handoff.
     *
     * @param encoded payload
     * @return handoff
     * @throws IOException malformed payload
     */
    public static ClaimHandoff decodeHandoff(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC_HANDOFF) {
                throw new CoordinationException("unsupported handoff format");
            }
            UUID id = readUuid(in);
            String target = readText(in);
            long version = in.readLong();
            if (in.available() != 0) {
                throw new CoordinationException("trailing handoff bytes");
            }
            return new ClaimHandoff(new WorkIntentId(id), new ParticipantId(target), new ClaimEpoch(version));
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed handoff", failure);
        }
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
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_CONTINUATION_V2);
            uuid(out, continuation.grant().value());
            uuid(out, continuation.sourceIntent().value());
            out.writeInt(intent.length);
            out.write(intent);
            text(out, continuation.sourceOwner().value());
            text(out, continuation.targetOwner().value());
            out.writeLong(continuation.claimEpoch().value());
            text(out, continuation.recoverySnapshot().value());
            out.writeBoolean(continuation.sourceActorScope() != null);
            if (continuation.sourceActorScope() != null) {
                writeActorScope(out, continuation.sourceActorScope());
                writeActorScope(out, continuation.targetActorScope());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }

    /**
     * Decodes an atomic continuation acceptance.
     *
     * @param encoded encoded payload
     * @return continuation payload
     * @throws IOException malformed payload
     */
    public static LaneContinuation decodeContinuation(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            int magic = in.readInt();
            if (magic != MAGIC_CONTINUATION_V2) {
                throw new CoordinationException("unsupported continuation format");
            }
            UUID grant = readUuid(in);
            UUID sourceIntent = readUuid(in);
            int length = in.readInt();
            if (length < 1 || length > 1_000_000) {
                throw new CoordinationException("continuation intent too large");
            }
            byte[] intentBytes = in.readNBytes(length);
            if (intentBytes.length != length) {
                throw new CoordinationException("truncated continuation intent");
            }
            WorkIntent targetIntent = decodeIntent(intentBytes);
            String source = readText(in);
            String target = readText(in);
            long epoch = in.readLong();
            String snapshot = readText(in);
            CapabilityActorScope sourceActor = null;
            CapabilityActorScope targetActor = null;
            if (in.readBoolean()) {
                sourceActor = readActorScope(in);
                targetActor = readActorScope(in);
            }
            if (in.available() != 0) {
                throw new CoordinationException("trailing continuation bytes");
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
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed continuation", failure);
        }
    }

    private static void writeActorScope(DataOutputStream out, CapabilityActorScope scope) throws IOException {
        text(out, scope.nodeId());
        text(out, scope.supervisorId());
        text(out, scope.workerId());
        text(out, scope.sessionId());
    }

    private static CapabilityActorScope readActorScope(DataInputStream in) throws IOException {
        return new CapabilityActorScope(readText(in), readText(in), readText(in), readText(in));
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
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_HEARTBEAT);
            text(out, participant.value());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }

    /**
     * Decodes a participant heartbeat.
     *
     * @param encoded encoded heartbeat
     * @return participant ID
     * @throws IOException malformed heartbeat
     */
    public static String decodeHeartbeat(byte[] encoded) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
        if (in.readInt() != MAGIC_HEARTBEAT) {
            throw new CoordinationException("unsupported heartbeat format");
        }
        String participant = readText(in);
        if (in.available() != 0) {
            throw new CoordinationException("trailing heartbeat bytes");
        }
        return participant;
    }

    /**
     * Encodes a server-issued inbox item identifier.
     *
     * @param id item identifier
     * @return encoded identifier
     */
    public static byte[] encodeUuidText(UUID id) {
        return encodeHeartbeat(CoordinationNullException.requireNonNull(id, "item ID").toString());
    }

    /**
     * Encodes a typed request or inbox item identifier.
     *
     * @param id typed request identity
     * @return encoded identifier payload
     */
    public static byte[] encodeUuidText(RequestId id) {
        return encodeUuidText(CoordinationNullException.requireNonNull(id, "item ID").value());
    }

    /**
     * Decodes a server-issued inbox item identifier.
     *
     * @param encoded encoded identifier
     * @return decoded identifier
     * @throws IOException malformed identifier
     */
    public static UUID decodeUuidText(byte[] encoded) throws IOException {
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
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_HEARTBEAT);
            text(out, evidence.participantId().value());
            text(out, evidence.snapshot().value());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }

    /**
     * Decodes a participant and immutable recovery snapshot reference.
     *
     * @param encoded encoded recovery payload
     * @return decoded recovery
     * @throws IOException malformed payload
     */
    public static RecoveryEvidence decodeRecovery(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC_HEARTBEAT) {
                throw new CoordinationException("unsupported recovery format");
            }
            String participant = readText(in);
            String snapshot = readText(in);
            if (in.available() != 0) {
                throw new CoordinationException("trailing recovery bytes");
            }
            return new RecoveryEvidence(new ParticipantId(participant), new SnapshotReference(snapshot));
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed recovery", failure);
        }
    }

    /**
     * Encodes a logical work group.
     *
     * @param group group to encode
     * @return canonical bytes
     */
    public static byte[] encodeWorkGroup(WorkGroup group) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_GROUP_V2);
            uuid(out, group.workGroupId());
            uuid(out, group.projectId());
            text(out, group.goal());
            text(out, group.acceptance());
            out.writeLong(group.version());
            out.writeByte(group.status().ordinal());
            out.writeBoolean(group.reviewPolicy().mandatory());
            out.writeBoolean(group.reviewPolicy().independenceRequired());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }

    /**
     * Decodes a logical work group.
     *
     * @param encoded bytes to decode
     * @return decoded group
     * @throws IOException if the payload is malformed
     */
    public static WorkGroup decodeWorkGroup(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            int magic = in.readInt();
            if (magic != MAGIC_GROUP_V2) {
                throw new CoordinationException("unsupported group format");
            }
            UUID id = readUuid(in), project = readUuid(in);
            String goal = readText(in), acceptance = readText(in);
            long version = in.readLong();
            int status = in.readUnsignedByte();
            ReviewPolicy reviewPolicy = new ReviewPolicy(in.readBoolean(), in.readBoolean());
            if (status >= WorkGroup.Status.values().length || in.available() != 0) {
                throw new CoordinationException("malformed group");
            }
            return new WorkGroup(id,
                    project,
                    goal,
                    acceptance,
                    version,
                    WorkGroup.Status.values()[status],
                    reviewPolicy);
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed group", failure);
        }
    }

    /**
     * Encodes a targeted lane grant.
     *
     * @param grant grant to encode
     * @return canonical bytes
     */
    public static byte[] encodeLaneGrant(LaneGrant grant) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_GRANT_V2);
            uuid(out, grant.grantId());
            uuid(out, grant.workGroupId());
            uuid(out, grant.targetIntentId());
            text(out, grant.targetParticipant());
            out.writeLong(grant.claimEpoch());
            out.writeBoolean(grant.singleUse());
            out.writeBoolean(grant.snapshotId() != null);
            if (grant.snapshotId() != null) {
                text(out, grant.snapshotId());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }

    /**
     * Decodes a targeted lane grant.
     *
     * @param encoded bytes to decode
     * @return decoded grant
     * @throws IOException if the payload is malformed
     */
    public static LaneGrant decodeLaneGrant(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            int magic = in.readInt();
            if (magic != MAGIC_GRANT_V2) {
                throw new CoordinationException("unsupported grant format");
            }
            UUID id = readUuid(in), group = readUuid(in), intent = readUuid(in);
            String target = readText(in);
            long epoch = in.readLong();
            boolean single = in.readBoolean();
            String snapshotId = in.readBoolean() ? readText(in) : null;
            if (in.available() != 0) {
                throw new CoordinationException("malformed grant");
            }
            return new LaneGrant(id, group, intent, target, epoch, single, snapshotId);
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed grant", failure);
        }
    }

    /**
     * Writes a UUID in the canonical binary payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    /**
     * Reads a UUID from the canonical binary payload format.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    /**
     * Writes a UTF-8 string as a length-prefixed value in the collaboration payload.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /**
     * Reads bounded UTF-8 text and validates its encoded length.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static String readText(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > 8192) {
            throw new CoordinationException("text bound");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new CoordinationException("truncated text");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Reads bounded UTF-8 text and validates its encoded length.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private static String readTextAllowEmpty(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 8192) {
            throw new CoordinationException("text bound");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new CoordinationException("truncated text");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

}
