package org.synesis.coordination.collaboration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/** Verifies targeted, epoch-fenced continuation grant behavior. */
final class WorkGroupServiceTest {
    @Test
    void singleUseGrantIsTargetedAndCannotReplay(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkGroupService service = new WorkGroupService(new PredictionEventStore(temp, project), identity);
        service.create(new WorkGroup(groupId, project, "parallel", "tests", 1, WorkGroup.Status.ACTIVE));
        UUID grantId = UUID.randomUUID();
        service.issue(new LaneGrant(grantId, groupId, intentId, "agt-target", 3, true));
        service.consume(grantId, "agt-target", intentId, 3);
        assertThrows(Exception.class, () -> service.consume(grantId, "agt-target", intentId, 3));
        assertThrows(Exception.class, () -> service.consume(UUID.randomUUID(), "agt-target", intentId, 3));
        assertTrue(new PredictionEventStore(temp, project).workGroupProjection().groups().size() == 1);
        service.close(groupId, WorkGroup.Status.COMPLETED, 1);
        assertTrue(new PredictionEventStore(temp, project).workGroupProjection().group(groupId)
                .orElseThrow().status() == WorkGroup.Status.COMPLETED);
    }

    @Test
    void revokedGrantCannotBeConsumed(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkGroupService service = new WorkGroupService(new PredictionEventStore(temp, project), identity);
        service.create(new WorkGroup(groupId, project, "delegation", "revoke", 1, WorkGroup.Status.ACTIVE));
        UUID grantId = UUID.randomUUID();
        service.issue(new LaneGrant(grantId, groupId, intentId, "agt-target", 4, true));
        service.revoke(grantId);
        assertThrows(Exception.class, () -> service.consume(grantId, "agt-target", intentId, 4));
    }

    @Test
    void grantCannotBeConsumedByWrongReviewer(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkGroupService service = new WorkGroupService(new PredictionEventStore(temp, project), identity);
        service.create(new WorkGroup(groupId, project, "review", "decision", 1, WorkGroup.Status.ACTIVE));
        UUID grantId = UUID.randomUUID();
        service.issue(new LaneGrant(grantId, groupId, intentId, "agt-reviewer", 1, true));
        assertThrows(Exception.class, () -> service.consume(grantId, "agt-other", intentId, 1));
        service.consume(grantId, "agt-reviewer", intentId, 1);
    }
}
