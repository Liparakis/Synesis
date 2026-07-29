package org.synesis.coordination.domain.collaboration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Canonical bounded encoding for signed collaboration event payloads. */
public final class CollaborationCodec {
    private static final int MAGIC_INTENT = 0x53494e31;
    private static final int MAGIC_INTENT_V2 = 0x53494e32;
    private static final int MAGIC_RELEASE = 0x53524c31;
    private static final int MAGIC_REQUEST = 0x53525131;
    private static final int MAGIC_RESPONSE = 0x53525331;
    private static final int MAGIC_HEARTBEAT = 0x53484231;
    private static final int MAGIC_HANDOFF = 0x53484631;
    private static final int MAGIC_GROUP = 0x53474731;
    private static final int MAGIC_GRANT = 0x53475231;

    private CollaborationCodec() {
    }

    /**
     * Encodes one announced intent.
     * @param intent intent
     * @return canonical bytes
     */
    public static byte[] encodeIntent(WorkIntent intent) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_INTENT_V2);
            uuid(out, intent.intentId());
            uuid(out, intent.projectId());
            uuid(out, intent.workGroupId());
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
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /**
     * Decodes one announced intent.
     * @param encoded bytes
     * @return intent
     * @throws IOException malformed bytes
     */
    public static WorkIntent decodeIntent(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            int magic = in.readInt();
            if (magic != MAGIC_INTENT && magic != MAGIC_INTENT_V2) {
                throw new IOException("unsupported intent format");
            }
            UUID intentId = readUuid(in);
            UUID projectId = readUuid(in);
            UUID workGroupId = magic == MAGIC_INTENT_V2 ? readUuid(in) : intentId;
            String participant = readText(in);
            String provider = readText(in);
            UUID taskId = readUuid(in);
            String goal = readText(in);
            String acceptance = readText(in);
            String baseCommit = readText(in);
            long version = in.readLong();
            int count = in.readInt();
            if (count < 1 || count > 128) {
                throw new IOException("selector bound");
            }
            List<ResourceSelector> selectors = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                int kind = in.readUnsignedByte();
                if (kind >= ResourceSelector.Kind.values().length) {
                    throw new IOException("selector kind");
                }
                selectors.add(new ResourceSelector(ResourceSelector.Kind.values()[kind], readText(in)));
            }
            if (in.available() != 0) {
                throw new IOException("trailing intent bytes");
            }
            return new WorkIntent(intentId, projectId, participant, provider, taskId, goal, acceptance,
                    baseCommit, selectors, version, workGroupId, WorkIntent.Status.ANNOUNCED);
        } catch (RuntimeException | java.io.EOFException failure) {
            throw new IOException("malformed intent", failure);
        }
    }

    /**
     * Encodes an intent release.
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
            throw new AssertionError(impossible);
        }
    }

    /**
     * Decodes a release.
     * @param encoded bytes
     * @return intent ID
     * @throws IOException malformed bytes
     */
    public static UUID decodeRelease(byte[] encoded) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
        if (in.readInt() != MAGIC_RELEASE) {
            throw new IOException("unsupported release format");
        }
        UUID id = readUuid(in);
        if (in.available() != 0) {
            throw new IOException("trailing release bytes");
        }
        return id;
    }

    /** Encodes a coordination request.
     * @param request request
     * @return encoded request
     */
    public static byte[] encodeRequest(CoordinationRequest request) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_REQUEST);
            uuid(out, request.requestId()); uuid(out, request.projectId());
            text(out, request.requester()); text(out, request.target());
            uuid(out, request.conflictingIntentId()); out.writeByte(request.kind().ordinal());
            text(out, request.proposal()); out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    /** Decodes a coordination request.
     * @param encoded encoded request
     * @return decoded request
     * @throws IOException malformed request
     */
    public static CoordinationRequest decodeRequest(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC_REQUEST) throw new IOException("unsupported request format");
            UUID requestId = readUuid(in), projectId = readUuid(in);
            String requester = readText(in), target = readText(in);
            UUID conflict = readUuid(in);
            int kind = in.readUnsignedByte();
            if (kind >= CoordinationRequest.Kind.values().length) throw new IOException("request kind");
            String proposal = readText(in);
            if (in.available() != 0) throw new IOException("trailing request bytes");
            return new CoordinationRequest(requestId, projectId, requester, target, conflict,
                    CoordinationRequest.Kind.values()[kind], proposal, CoordinationRequest.Status.PENDING);
        } catch (RuntimeException | java.io.EOFException failure) { throw new IOException("malformed request", failure); }
    }

    /** Encodes a request response and optional revised proposal.
     * @param requestId request ID
     * @param status response status
     * @param proposal revised proposal
     * @return encoded response
     */
    public static byte[] encodeResponse(UUID requestId, CoordinationRequest.Status status, String proposal) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_RESPONSE); uuid(out, requestId); out.writeByte(status.ordinal()); text(out, proposal == null ? "" : proposal);
            out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    /** Decodes a request response.
     * @param encoded encoded response
     * @return decoded response
     * @throws IOException malformed response
     */
    public static Response decodeResponse(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC_RESPONSE) throw new IOException("unsupported response format");
            UUID id = readUuid(in); int status = in.readUnsignedByte();
            if (status < 0 || status >= CoordinationRequest.Status.values().length) throw new IOException("request status");
            String proposal = readTextAllowEmpty(in); if (in.available() != 0) throw new IOException("trailing response bytes");
            return new Response(id, CoordinationRequest.Status.values()[status], proposal);
        } catch (RuntimeException | java.io.EOFException failure) { throw new IOException("malformed response", failure); }
    }

    /** Decoded request response.
     * @param requestId request ID
     * @param status status
     * @param proposal proposal
     */
    public record Response(UUID requestId, CoordinationRequest.Status status, String proposal) { }

    /** Handoff acceptance payload.
     * @param intentId intent ID
     * @param target target participant
     * @param expectedVersion expected claim epoch
     */
    public record Handoff(UUID intentId, String target, long expectedVersion) { }

    /** Encodes an accepted handoff.
     * @param intentId intent ID
     * @param target target participant
     * @param expectedVersion expected claim epoch
     * @return encoded handoff
     */
    public static byte[] encodeHandoff(UUID intentId, String target, long expectedVersion) {
        try { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_HANDOFF); uuid(out, intentId); text(out, target); out.writeLong(expectedVersion); out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    /** Decodes an accepted handoff.
     * @param encoded payload
     * @return handoff
     * @throws IOException malformed payload
     */
    public static Handoff decodeHandoff(byte[] encoded) throws IOException {
        try { DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC_HANDOFF) throw new IOException("unsupported handoff format");
            UUID id = readUuid(in); String target = readText(in); long version = in.readLong();
            if (in.available() != 0) throw new IOException("trailing handoff bytes");
            return new Handoff(id, target, version);
        } catch (RuntimeException | java.io.EOFException failure) { throw new IOException("malformed handoff", failure); }
    }

    /** Encodes a participant heartbeat.
     * @param participant participant ID
     * @return encoded heartbeat
     */
    public static byte[] encodeHeartbeat(String participant) {
        try { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_HEARTBEAT); text(out, participant); out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    /** Decodes a participant heartbeat.
     * @param encoded encoded heartbeat
     * @return participant ID
     * @throws IOException malformed heartbeat
     */
    public static String decodeHeartbeat(byte[] encoded) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
        if (in.readInt() != MAGIC_HEARTBEAT) throw new IOException("unsupported heartbeat format");
        String participant = readText(in);
        if (in.available() != 0) throw new IOException("trailing heartbeat bytes");
        return participant;
    }

    /** Encodes a logical work group. @param group group @return canonical bytes */
    public static byte[] encodeWorkGroup(WorkGroup group) {
        try { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_GROUP); uuid(out, group.workGroupId()); uuid(out, group.projectId());
            text(out, group.goal()); text(out, group.acceptance()); out.writeLong(group.version());
            out.writeByte(group.status().ordinal()); out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    /** Decodes a logical work group. @param encoded bytes @return group @throws IOException malformed payload */
    public static WorkGroup decodeWorkGroup(byte[] encoded) throws IOException {
        try { DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC_GROUP) throw new IOException("unsupported group format");
            UUID id = readUuid(in), project = readUuid(in); String goal = readText(in), acceptance = readText(in);
            long version = in.readLong(); int status = in.readUnsignedByte();
            if (status >= WorkGroup.Status.values().length || in.available() != 0) throw new IOException("malformed group");
            return new WorkGroup(id, project, goal, acceptance, version, WorkGroup.Status.values()[status]);
        } catch (RuntimeException | java.io.EOFException failure) { throw new IOException("malformed group", failure); }
    }

    /** Encodes a targeted lane grant. @param grant grant @return canonical bytes */
    public static byte[] encodeLaneGrant(LaneGrant grant) {
        try { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_GRANT); uuid(out, grant.grantId()); uuid(out, grant.workGroupId()); uuid(out, grant.targetIntentId());
            text(out, grant.targetParticipant()); out.writeLong(grant.claimEpoch()); out.writeBoolean(grant.singleUse());
            out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    /** Decodes a targeted lane grant. @param encoded bytes @return grant @throws IOException malformed payload */
    public static LaneGrant decodeLaneGrant(byte[] encoded) throws IOException {
        try { DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC_GRANT) throw new IOException("unsupported grant format");
            UUID id = readUuid(in), group = readUuid(in), intent = readUuid(in); String target = readText(in);
            long epoch = in.readLong(); boolean single = in.readBoolean();
            if (in.available() != 0) throw new IOException("malformed grant");
            return new LaneGrant(id, group, intent, target, epoch, single);
        } catch (RuntimeException | java.io.EOFException failure) { throw new IOException("malformed grant", failure); }
    }

    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > 8192) {
            throw new IOException("text bound");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("truncated text");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readTextAllowEmpty(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 8192) throw new IOException("text bound");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new IOException("truncated text");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
