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
    private static final int MAGIC_RELEASE = 0x53524c31;
    private static final int MAGIC_REQUEST = 0x53525131;
    private static final int MAGIC_RESPONSE = 0x53525331;

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
            out.writeInt(MAGIC_INTENT);
            uuid(out, intent.intentId());
            uuid(out, intent.projectId());
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
            if (in.readInt() != MAGIC_INTENT) {
                throw new IOException("unsupported intent format");
            }
            UUID intentId = readUuid(in);
            UUID projectId = readUuid(in);
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
                    baseCommit, selectors, version, WorkIntent.Status.ANNOUNCED);
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

    /** Encodes a coordination request. */
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

    /** Decodes a coordination request. */
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

    /** Encodes a request response and optional revised proposal. */
    public static byte[] encodeResponse(UUID requestId, CoordinationRequest.Status status, String proposal) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC_RESPONSE); uuid(out, requestId); out.writeByte(status.ordinal()); text(out, proposal == null ? "" : proposal);
            out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    /** Decodes a request response. */
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

    /** Decoded request response. */
    public record Response(UUID requestId, CoordinationRequest.Status status, String proposal) { }

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
