package org.synesis.coordination.domain.contract;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded canonical encoding for contract events. */
public final class ContractCodec {
    private static final int MAGIC_PUBLISH = 0x53435031;
    private static final int MAGIC_DEPEND = 0x53434431;
    private static final int MAGIC_SUPERSEDE = 0x53435331;
    private ContractCodec() { }

    /** Encodes a contract publication.
     * @param record contract record
     * @return canonical event payload
     */
    public static byte[] encodePublish(ContractRecord record) {
        try { ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(b);
            out.writeInt(MAGIC_PUBLISH); uuid(out, record.contractId()); uuid(out, record.projectId()); out.writeLong(record.revision());
            text(out, record.owner()); text(out, record.contentHash()); text(out, record.body()); uuidNullable(out, record.supersedes()); out.writeInt(record.selectorRefs().size());
            for (String ref : record.selectorRefs()) text(out, ref); out.flush(); return b.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    /** Decodes a contract publication.
     * @param encoded event payload
     * @return decoded record
     * @throws IOException if malformed
     */
    public static ContractRecord decodePublish(byte[] encoded) throws IOException {
        try { DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded)); if (in.readInt() != MAGIC_PUBLISH) throw new IOException("contract format");
            UUID id = uuid(in), project = uuid(in); long revision = in.readLong(); String owner = text(in), hash = text(in), body = text(in); UUID supersedes = uuidNullable(in);
            int count = in.readInt(); if (count < 0 || count > 128) throw new IOException("selector bound"); List<String> refs = new ArrayList<>(); for (int i=0;i<count;i++) refs.add(text(in));
            if (in.available()!=0) throw new IOException("trailing contract bytes"); return new ContractRecord(id, project, revision, owner, hash, body, ContractRecord.Status.ACTIVE, supersedes, refs);
        } catch (RuntimeException | java.io.EOFException failure) { throw new IOException("malformed contract", failure); }
    }

    /** Encodes an explicit contract dependency.
     * @param dependency dependency
     * @return canonical event payload
     */
    public static byte[] encodeDependency(ContractDependency dependency) {
        try { ByteArrayOutputStream b=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(b); out.writeInt(MAGIC_DEPEND); uuid(out, dependency.intentId()); text(out, dependency.participant()); uuid(out, dependency.contractId()); out.writeLong(dependency.revision()); out.flush(); return b.toByteArray(); }
        catch(IOException impossible){throw new AssertionError(impossible);}
    }

    /** Decodes an explicit contract dependency.
     * @param encoded event payload
     * @return decoded dependency
     * @throws IOException if malformed
     */
    public static ContractDependency decodeDependency(byte[] encoded) throws IOException {
        try { DataInputStream in=new DataInputStream(new ByteArrayInputStream(encoded)); if(in.readInt()!=MAGIC_DEPEND) throw new IOException("dependency format"); UUID intent=uuid(in); String participant=text(in); UUID contract=uuid(in); long revision=in.readLong(); if(in.available()!=0) throw new IOException("trailing dependency bytes"); return new ContractDependency(intent,participant,contract,revision,ContractDependency.State.ACCEPTED); }
        catch(RuntimeException|java.io.EOFException failure){throw new IOException("malformed dependency",failure);}
    }

    /** Encodes a contract supersession.
     * @param contractId contract identifier
     * @param oldRevision replaced revision
     * @param newRevision replacement revision
     * @return canonical event payload
     */
    public static byte[] encodeSupersede(UUID contractId, long oldRevision, long newRevision) {
        try { ByteArrayOutputStream b=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(b); out.writeInt(MAGIC_SUPERSEDE); uuid(out, contractId); out.writeLong(oldRevision); out.writeLong(newRevision); out.flush(); return b.toByteArray(); }
        catch(IOException impossible){throw new AssertionError(impossible);}
    }

    /** Decoded supersession.
     * @param contractId contract identifier
     * @param oldRevision replaced revision
     * @param newRevision replacement revision
     */
    public record Supersede(UUID contractId, long oldRevision, long newRevision) { }
    /** Decodes a supersession.
     * @param encoded event payload
     * @return decoded supersession
     * @throws IOException if malformed
     */
    public static Supersede decodeSupersede(byte[] encoded) throws IOException { DataInputStream in=new DataInputStream(new ByteArrayInputStream(encoded)); if(in.readInt()!=MAGIC_SUPERSEDE) throw new IOException("supersession format"); UUID id=uuid(in); long oldRevision=in.readLong(), newRevision=in.readLong(); if(in.available()!=0) throw new IOException("trailing supersession bytes"); return new Supersede(id,oldRevision,newRevision); }

    private static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void uuidNullable(DataOutputStream out, UUID id) throws IOException { out.writeBoolean(id!=null); if(id!=null) uuid(out,id); }
    private static UUID uuidNullable(DataInputStream in) throws IOException { return in.readBoolean()?uuid(in):null; }
    private static void text(DataOutputStream out, String value) throws IOException { byte[] b=value.getBytes(StandardCharsets.UTF_8); if(b.length>32768) throw new IOException("text bound"); out.writeInt(b.length); out.write(b); }
    private static String text(DataInputStream in) throws IOException { int n=in.readInt(); if(n<1||n>32768) throw new IOException("text bound"); byte[] b=in.readNBytes(n); if(b.length!=n) throw new IOException("truncated text"); return new String(b,StandardCharsets.UTF_8); }
}
