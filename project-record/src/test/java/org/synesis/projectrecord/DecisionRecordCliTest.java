package org.synesis.projectrecord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Verifies the launcher renders safe local inspection output. */
final class DecisionRecordCliTest {
    @Test
    void inspectPrintsReadableVerifiedFieldsWithoutPrivateMaterial() throws Exception {
        UUID project = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        Path root = Files.createTempDirectory("decision-cli-");
        Ed25519Signer signer = Ed25519Signer.generate();
        DecisionRecord record = DecisionRecord.create(project, recordId, 1, null, signer.nodeId(), signer.nodeId(),
                DecisionStatus.ACCEPTED, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"), "Title", "Rationale", List.of(
                        new DecisionEvidence("test", "reference", new byte[32])), signer);
        new DecisionStore(root, project).save(record, null);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
            DecisionRecordCli.main(new String[] {"inspect", root.toString(), project.toString(), recordId.toString()});
        } finally {
            System.setOut(original);
        }
        String output = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(output.contains("RECORD_TYPE=decision"));
        assertTrue(output.contains("SIGNATURE_VALID=true"));
        assertTrue(output.contains("TITLE=Title"));
        assertFalse(output.contains("PRIVATE"));
    }
}
