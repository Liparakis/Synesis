package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.collaboration.ResourceSelector;

/** Tests deterministic pre-merge compatibility decisions. */
class IntegrationCompatibilityServiceTest {
    private static final UUID CONTRACT = UUID.randomUUID();
    private final IntegrationCompatibilityService service = new IntegrationCompatibilityService();

    @Test
    void compatibleIndependentSnapshotPasses() {
        var snapshot = new IntegrationCompatibilityService.SnapshotInput("snap-a", "head",
                List.of("src/a.py"), List.of(ResourceSelector.pathExact("src/a.py")),
                List.of(new IntegrationCompatibilityService.ContractReference(CONTRACT, 1)), List.of());
        var result = service.check(new IntegrationCompatibilityService.CheckRequest("head", List.of(snapshot),
                List.of(new IntegrationCompatibilityService.CurrentContract(CONTRACT, 1, true)), true));
        assertTrue(result.accepted());
    }

    @Test
    void staleAndOverlappingSnapshotsBlockWithActions() {
        var first = new IntegrationCompatibilityService.SnapshotInput("snap-a", "old",
                List.of("src/task_tracker.py"), List.of(ResourceSelector.pathExact("src/task_tracker.py")), List.of(), List.of());
        var second = new IntegrationCompatibilityService.SnapshotInput("snap-b", "head",
                List.of("src/task_tracker.py"), List.of(ResourceSelector.pathExact("src/task_tracker.py")), List.of(), List.of());
        var result = service.check(new IntegrationCompatibilityService.CheckRequest("head", List.of(first, second), List.of(), true));
        assertFalse(result.accepted());
        assertTrue(result.failures().contains(IntegrationCompatibilityService.FailureCode.STALE_BASE));
        assertTrue(result.failures().contains(IntegrationCompatibilityService.FailureCode.OVERLAPPING_SNAPSHOT));
    }

    @Test
    void uncoveredDirectAndStaleContractChangesBlock() {
        var snapshot = new IntegrationCompatibilityService.SnapshotInput("snap-a", "head",
                List.of("src/api.py"), List.of(),
                List.of(new IntegrationCompatibilityService.ContractReference(CONTRACT, 1)), List.of("src/api.py"));
        var result = service.check(new IntegrationCompatibilityService.CheckRequest("head", List.of(snapshot),
                List.of(new IntegrationCompatibilityService.CurrentContract(CONTRACT, 2, true)), false));
        assertFalse(result.accepted());
        assertTrue(result.failures().contains(IntegrationCompatibilityService.FailureCode.UNCOVERED_PATH));
        assertTrue(result.failures().contains(IntegrationCompatibilityService.FailureCode.STALE_CONTRACT));
        assertTrue(result.failures().contains(IntegrationCompatibilityService.FailureCode.OUT_OF_BAND_MUTATION));
        assertTrue(result.failures().contains(IntegrationCompatibilityService.FailureCode.TESTS_FAILED));
    }
}
