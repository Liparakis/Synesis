package org.synesis.projectrecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        assertEquals(DecisionRecord.RecordType.PROJECT_CONSTRAINT, record.recordType());

        ProjectConstraint constraint = ProjectConstraint.fromRecord(record);
        assertNotNull(constraint);
        assertEquals(ProjectConstraint.Effect.BLOCK, constraint.effect());
        assertEquals(ProjectConstraint.ConstraintStatus.ACTIVE, constraint.status());
        assertEquals(List.of("src/protocol/**"), constraint.scopes());

        assertTrue(constraint.appliesTo("src/protocol/RecordMessage.java"));
        assertFalse(constraint.appliesTo("src/ui/Component.java"));
    }

    @Test
    void ordinaryDecisionWithConstraintTitleIsNotTreatedAsConstraint() throws Exception {
        Ed25519Signer signer = Ed25519Signer.generate();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        byte[] digest = new byte[32];

        // Decision record with title starting with "CONSTRAINT:" but recordType = DECISION
        DecisionRecord decisionRecord = DecisionRecord.create(UUID.randomUUID(), UUID.randomUUID(), 1, null,
                signer.nodeId(), signer.nodeId(), DecisionStatus.PROPOSED, now, now,
                "CONSTRAINT: Do not modify protocol", "Ordinary decision rationale",
                List.of(new DecisionEvidence("text", "src/protocol/**", digest)), signer);

        assertEquals(DecisionRecord.RecordType.DECISION, decisionRecord.recordType());
        // Must return null — non-constraint records are never constraints
        assertNull(ProjectConstraint.fromRecord(decisionRecord));
    }

    @Test
    void rejectsSelfSupersession() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () ->
                new ProjectConstraint(id, "Title", "Rat", ProjectConstraint.ConstraintStatus.ACTIVE,
                        ProjectConstraint.Effect.BLOCK, List.of("src/**"), List.of(id)));
    }
}
