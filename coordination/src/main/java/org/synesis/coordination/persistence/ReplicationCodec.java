package org.synesis.coordination.persistence;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.exception.CoordinationException;

/**
 * Wire form of {@link ReplicationMessage}, version 1: {@code "SRL1"}, a version byte, a kind byte and the fields
 * of that kind, big-endian. A pull names the log it wants since compatibility 8 (ADR-0098 step 4). Every length is checked before allocation and no byte may be left over. The layout
 * is in {@code docs/protocol/WIRE_FORMAT.md}.
 */
public final class ReplicationCodec {

    private static final int MAGIC = 0x53524C31;
    private static final int VERSION = 1;

    private static final int PROPOSE = 1;
    private static final int PULL = 2;
    private static final int NOTICE = 3;
    private static final int HEADS = 4;
    private static final int CLAIM_SEEN = 5;
    private static final int CLAIM_INTENT = 6;
    private static final int ACCEPTED = 16;
    private static final int HEAD_MOVED = 17;
    private static final int REFUSED = 18;
    private static final int UNREACHABLE = 19;
    private static final int NO_ANSWER = 20;
    private static final int ENTRIES = 21;
    private static final int NOTICED = 22;
    private static final int HEAD_LIST = 23;
    private static final int CLAIM_ANSWER = 24;

    private ReplicationCodec() {
    }

    /**
     * Tells whether a payload is addressed to this protocol.
     *
     * @param payload candidate payload
     * @return true when it starts with the magic number
     */
    public static boolean looksLike(byte[] payload) {
        return payload != null && payload.length >= Integer.BYTES
                && (((payload[0] & 0xff) << 24) | ((payload[1] & 0xff) << 16) | ((payload[2] & 0xff) << 8)
                | (payload[3] & 0xff)) == MAGIC;
    }

    /**
     * Encodes a message.
     *
     * @param message the message
     * @return the bytes, at most {@link ReplicationMessage#MAX_BYTES}
     * @throws IllegalArgumentException when a field is out of its bound
     */
    public static byte[] encode(ReplicationMessage message) {
        byte[] encoded = WireFormat.encode(MAGIC, out -> {
            out.writeByte(VERSION);
            switch (message) {
                case ReplicationMessage.Propose propose -> {
                    out.writeByte(PROPOSE);
                    WireFormat.writeUuid(out, propose.project());
                    writeEntry(out, propose.event());
                }
                case ReplicationMessage.Pull pull -> {
                    out.writeByte(PULL);
                    WireFormat.writeUuid(out, pull.project());
                    writeNode(out, pull.log());
                    out.writeLong(pull.after());
                    out.writeInt(pull.maxEntries());
                    out.writeInt(pull.maxBytes());
                }
                case ReplicationMessage.Notice notice -> {
                    out.writeByte(NOTICE);
                    WireFormat.writeUuid(out, notice.project());
                    out.writeLong(notice.head());
                }
                case ReplicationMessage.Outcome outcome -> writeOutcome(out, outcome.outcome());
                case ReplicationMessage.Entries entries -> {
                    out.writeByte(ENTRIES);
                    out.writeShort(entries.entries().size());
                    for (byte[] entry : entries.entries()) {
                        writeEntry(out, entry);
                    }
                }
                case ReplicationMessage.Noticed noticed -> out.writeByte(NOTICED);
                case ReplicationMessage.Heads heads -> {
                    out.writeByte(HEADS);
                    WireFormat.writeUuid(out, heads.project());
                }
                case ReplicationMessage.ClaimSeen seen -> {
                    out.writeByte(CLAIM_SEEN);
                    WireFormat.writeUuid(out, seen.project());
                    writeEntry(out, seen.entry());
                }
                case ReplicationMessage.ClaimIntent intent -> {
                    out.writeByte(CLAIM_INTENT);
                    WireFormat.writeUuid(out, intent.project());
                    writeEntry(out, intent.intent());
                }
                case ReplicationMessage.ClaimAnswer answer -> {
                    byte[] code = answer.code().getBytes(StandardCharsets.UTF_8);
                    if (code.length > ReplicationMessage.MAX_CODE_BYTES) {
                        throw new IllegalArgumentException("claim answer code out of bounds");
                    }
                    out.writeByte(CLAIM_ANSWER);
                    out.writeByte(code.length);
                    out.write(code);
                }
                case ReplicationMessage.HeadList list -> {
                    if (list.heads().size() > ReplicationMessage.MAX_LOGS) {
                        throw new IllegalArgumentException("too many logs");
                    }
                    out.writeByte(HEAD_LIST);
                    out.writeShort(list.heads().size());
                    for (Map.Entry<String, Long> head : new java.util.TreeMap<>(list.heads()).entrySet()) {
                        writeNode(out, head.getKey());
                        out.writeLong(head.getValue());
                    }
                }
            }
        });
        if (encoded.length > ReplicationMessage.MAX_BYTES) {
            throw new IllegalArgumentException("replication message exceeds bound");
        }
        return encoded;
    }

    /**
     * Decodes and strictly validates one message.
     *
     * @param bytes the encoded message
     * @return the message
     * @throws CoordinationException {@code REPLICATION_MESSAGE_INVALID} for a wrong magic, version or kind, a
     *                               length out of bounds, truncation, or a byte left over
     */
    public static ReplicationMessage decode(byte[] bytes) throws CoordinationException {
        if (bytes == null || bytes.length < 6 || bytes.length > ReplicationMessage.MAX_BYTES) {
            throw invalid(null);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) {
                throw invalid(null);
            }
            ReplicationMessage message = switch (in.readUnsignedByte()) {
                case PROPOSE -> new ReplicationMessage.Propose(WireFormat.readUuid(in), readEntry(in));
                case PULL -> new ReplicationMessage.Pull(WireFormat.readUuid(in), readNode(in), nonNegative(in.readLong()),
                        bounded(in.readInt(), 1, ReplicationMessage.MAX_ENTRIES),
                        bounded(in.readInt(), 1, ReplicationMessage.MAX_BYTES));
                case NOTICE -> new ReplicationMessage.Notice(WireFormat.readUuid(in), nonNegative(in.readLong()));
                case ACCEPTED -> outcome(new ProposalOutcome.Accepted(nonNegative(in.readLong())));
                case HEAD_MOVED -> outcome(new ProposalOutcome.HeadMoved(nonNegative(in.readLong())));
                case REFUSED -> outcome(new ProposalOutcome.Refused(readCode(in)));
                case UNREACHABLE -> outcome(new ProposalOutcome.Unreachable());
                case NO_ANSWER -> outcome(new ProposalOutcome.NoAnswer());
                case ENTRIES -> readEntries(in);
                case NOTICED -> new ReplicationMessage.Noticed();
                case HEADS -> new ReplicationMessage.Heads(WireFormat.readUuid(in));
                case CLAIM_SEEN -> new ReplicationMessage.ClaimSeen(WireFormat.readUuid(in), readEntry(in));
                case CLAIM_INTENT -> new ReplicationMessage.ClaimIntent(WireFormat.readUuid(in), readEntry(in));
                case CLAIM_ANSWER -> readClaimAnswer(in);
                case HEAD_LIST -> readHeads(in);
                default -> throw invalid(null);
            };
            if (in.available() != 0) {
                throw invalid(null);
            }
            return message;
        } catch (CoordinationException invalid) {
            throw invalid;
        } catch (IOException truncated) {
            throw invalid(truncated);
        }
    }

    private static ReplicationMessage outcome(ProposalOutcome outcome) {
        return new ReplicationMessage.Outcome(outcome);
    }

    private static void writeOutcome(DataOutputStream out, ProposalOutcome outcome) throws IOException {
        switch (outcome) {
            case ProposalOutcome.Accepted accepted -> {
                out.writeByte(ACCEPTED);
                out.writeLong(accepted.sequence());
            }
            case ProposalOutcome.HeadMoved moved -> {
                out.writeByte(HEAD_MOVED);
                out.writeLong(moved.leaderHead());
            }
            case ProposalOutcome.Refused refused -> {
                byte[] code = refused.code().getBytes(StandardCharsets.UTF_8);
                if (code.length == 0 || code.length > ReplicationMessage.MAX_CODE_BYTES) {
                    throw new IllegalArgumentException("refusal code out of bounds");
                }
                out.writeByte(REFUSED);
                out.writeByte(code.length);
                out.write(code);
            }
            case ProposalOutcome.Unreachable unreachable -> out.writeByte(UNREACHABLE);
            case ProposalOutcome.NoAnswer noAnswer -> out.writeByte(NO_ANSWER);
        }
    }

    private static void writeEntry(DataOutputStream out, byte[] entry) throws IOException {
        if (entry.length == 0 || entry.length > ReplicationMessage.MAX_ENTRY_BYTES) {
            throw new IllegalArgumentException("replicated entry out of bounds");
        }
        out.writeInt(entry.length);
        out.write(entry);
    }

    private static CoordinationException invalid(Throwable cause) {
        return cause == null ? new CoordinationException("REPLICATION_MESSAGE_INVALID")
                : new CoordinationException("REPLICATION_MESSAGE_INVALID", cause);
    }

    private static long nonNegative(long value) throws CoordinationException {
        if (value < 0) {
            throw invalid(null);
        }
        return value;
    }

    private static int bounded(int value, int minimum, int maximum) throws CoordinationException {
        if (value < minimum || value > maximum) {
            throw invalid(null);
        }
        return value;
    }

    private static byte[] readEntry(DataInputStream in) throws IOException {
        int length = bounded(in.readInt(), 1, ReplicationMessage.MAX_ENTRY_BYTES);
        byte[] entry = in.readNBytes(length);
        if (entry.length != length) {
            throw invalid(null);
        }
        return entry;
    }

    private static String readCode(DataInputStream in) throws IOException {
        int length = bounded(in.readUnsignedByte(), 1, ReplicationMessage.MAX_CODE_BYTES);
        byte[] code = in.readNBytes(length);
        if (code.length != length) {
            throw invalid(null);
        }
        return new String(code, StandardCharsets.UTF_8);
    }

    private static void writeNode(DataOutputStream out, String node) throws IOException {
        byte[] bytes = node.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > ReplicationMessage.MAX_NODE_BYTES) {
            throw new IllegalArgumentException("node ID out of bounds");
        }
        out.writeByte(bytes.length);
        out.write(bytes);
    }

    private static String readNode(DataInputStream in) throws IOException {
        int length = bounded(in.readUnsignedByte(), 1, ReplicationMessage.MAX_NODE_BYTES);
        byte[] node = in.readNBytes(length);
        if (node.length != length) {
            throw invalid(null);
        }
        return new String(node, StandardCharsets.UTF_8);
    }

    private static ReplicationMessage.ClaimAnswer readClaimAnswer(DataInputStream in) throws IOException {
        int length = bounded(in.readUnsignedByte(), 0, ReplicationMessage.MAX_CODE_BYTES);
        byte[] code = in.readNBytes(length);
        if (code.length != length) {
            throw invalid(null);
        }
        return new ReplicationMessage.ClaimAnswer(new String(code, StandardCharsets.UTF_8));
    }

    private static ReplicationMessage.HeadList readHeads(DataInputStream in) throws IOException {
        int count = bounded(in.readUnsignedShort(), 0, ReplicationMessage.MAX_LOGS);
        Map<String, Long> heads = new java.util.LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String node = readNode(in);
            if (heads.put(node, nonNegative(in.readLong())) != null) {
                throw invalid(null);
            }
        }
        return new ReplicationMessage.HeadList(heads);
    }

    private static ReplicationMessage.Entries readEntries(DataInputStream in) throws IOException {
        int count = bounded(in.readUnsignedShort(), 0, ReplicationMessage.MAX_ENTRIES);
        List<byte[]> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(readEntry(in));
        }
        return new ReplicationMessage.Entries(List.copyOf(entries));
    }
}
