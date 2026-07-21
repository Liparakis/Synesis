package org.synesis.projectrecord;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Minimal JDK-only launcher for safe local decision inspection.
 */
public final class DecisionRecordCli {
    private DecisionRecordCli() {
    }

    /**
     * Runs {@code inspect <profile-dir> <project-id> <record-id>}.
     *
     * @param args exact launcher arguments
     * @throws Exception if arguments, storage, or record validation fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println("Usage: synesis-project-record inspect <profile-dir> <project-id> <record-id>");
            return;
        }
        if (args.length != 4 || !"inspect".equals(args[0])) {
            throw new IllegalArgumentException("usage: inspect <profile-dir> <project-id> <record-id>");
        }
        UUID projectId = UUID.fromString(args[2]);
        UUID recordId = UUID.fromString(args[3]);
        DecisionStore store = new DecisionStore(Path.of(args[1]), projectId);
        Optional<DecisionRecord> result = store.head(recordId);
        if (result.isEmpty()) throw new IllegalArgumentException("decision not found");
        DecisionRecord record = result.get();
        System.out.println("RECORD_TYPE=decision");
        System.out.println("PROJECT_ID=" + record.projectId());
        System.out.println("RECORD_ID=" + record.recordId());
        System.out.println("REVISION=" + record.revision());
        System.out.println("DIGEST=" + record.digestHex());
        System.out.println("OWNER_NODE_ID=" + record.ownerNodeId());
        System.out.println("AUTHOR_NODE_ID=" + record.authorNodeId());
        System.out.println("STATUS=" + record.status());
        System.out.println("CREATED_AT=" + record.createdAt());
        System.out.println("UPDATED_AT=" + record.updatedAt());
        System.out.println("TITLE=" + safe(record.title()));
        System.out.println("RATIONALE=" + safe(record.rationale()));
        System.out.println("EVIDENCE_COUNT=" + record.evidence().size());
        System.out.println("SIGNATURE_VALID=" + record.verify());
    }

    private static String safe(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }
}
