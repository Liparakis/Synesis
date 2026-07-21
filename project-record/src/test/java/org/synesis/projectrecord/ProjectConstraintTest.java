package org.synesis.projectrecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

final class ProjectConstraintTest {

    @Test
    void typedConstraintCreationAndExtraction() throws Exception {
        Ed25519Signer signer = Ed25519Signer.generate();
        UUID projectId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();

        DecisionRecord record = ProjectConstraint.createTypedRecord(projectId, recordId, signer.nodeId(),
                ProjectConstraint.Effect.BLOCK, "src/protocol/**",
                "Lock protocol wire format", "Protocol formats frozen.", signer);

        assertTrue(record.verify());
        ProjectConstraint constraint = ProjectConstraint.fromRecord(record);
        assertNotNull(constraint);
        assertEquals(ProjectConstraint.Source.TYPED, constraint.source());
        assertEquals(ProjectConstraint.Effect.BLOCK, constraint.effect());
        assertEquals(ProjectConstraint.ConstraintStatus.ACTIVE, constraint.status());
        assertEquals(List.of("src/protocol/**"), constraint.scopes());

        assertTrue(constraint.appliesTo("src/protocol/RecordMessage.java"));
        assertFalse(constraint.appliesTo("src/ui/Component.java"));
    }

    @Test
    void legacyInferredConstraintFallback() throws Exception {
        Ed25519Signer signer = Ed25519Signer.generate();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        byte[] digest = new byte[32];

        DecisionRecord legacyRecord = DecisionRecord.create(UUID.randomUUID(), UUID.randomUUID(), 1, null,
                signer.nodeId(), signer.nodeId(), DecisionStatus.PROPOSED, now, now,
                "CONSTRAINT: Lock legacy path", "Legacy rationale",
                List.of(new DecisionEvidence("text", "src/legacy/**", digest)), signer);

        ProjectConstraint constraint = ProjectConstraint.fromRecord(legacyRecord);
        assertNotNull(constraint);
        assertEquals(ProjectConstraint.Source.LEGACY_INFERRED, constraint.source());
        assertEquals(ProjectConstraint.Effect.BLOCK, constraint.effect());
        assertTrue(constraint.appliesTo("src/legacy/OldFile.java"));
    }

    @Test
    void nonConstraintRecordReturnsNull() throws Exception {
        Ed25519Signer signer = Ed25519Signer.generate();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        byte[] digest = new byte[32];

        DecisionRecord record = DecisionRecord.create(UUID.randomUUID(), UUID.randomUUID(), 1, null,
                signer.nodeId(), signer.nodeId(), DecisionStatus.ACCEPTED, now, now,
                "Standard Decision", "No constraint info",
                List.of(new DecisionEvidence("text", "ref", digest)), signer);

        assertNull(ProjectConstraint.fromRecord(record));
    }
}
