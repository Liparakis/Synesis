package org.synesis.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import org.synesis.cli.diagnostics.ReadinessInspector;
import org.synesis.cli.diagnostics.ReadinessReport;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.candidate.CandidateProvider;
import org.synesis.link.candidate.CandidateType;

/** Verifies read-only identity inspection outcomes. */
final class ReadinessInspectorTest {
    @Test
    void absentIdentityPassesWithInformationAndDoesNotCreateProfile() throws Exception {
        Path profile = Files.createTempDirectory("synesis-doctor").resolve("profile");
        ReadinessReport report = new ReadinessInspector(profile).inspect();
        assertTrue(report.identityReady());
        assertFalse(Files.exists(profile));
    }

    @Test
    void validIdentityIsReportedWithoutMutation() throws Exception {
        Path profile = Files.createTempDirectory("synesis-doctor");
        new IdentityBootstrap(profile).loadOrCreate();
        String before = Files.readString(profile.resolve("identity.pub"));
        ReadinessReport report = new ReadinessInspector(profile).inspect();
        assertTrue(report.identityReady());
        assertTrue(report.identityDetail().equals("IDENTITY_VALID"));
        assertTrue(before.equals(Files.readString(profile.resolve("identity.pub"))));
    }

    @Test
    void corruptIdentityFailsWithoutRepair() throws Exception {
        Path profile = Files.createTempDirectory("synesis-doctor");
        Files.write(profile.resolve("identity.bin"), new byte[] {1, 2, 3});
        ReadinessReport report = new ReadinessInspector(profile).inspect();
        assertFalse(report.identityReady());
        assertFalse(Files.exists(profile.resolve("identity.pub")));
    }

    @Test
    void regularFileAtProfilePathIsInaccessible() throws Exception {
        Path profile = Files.createTempFile("synesis-doctor", ".profile");
        ReadinessReport report = new ReadinessInspector(profile).inspect();
        assertFalse(report.profileReady());
    }

    @Test
    void noCandidateProviderIsReportedAsAReadinessFailure() throws Exception {
        Path profile = Files.createTempDirectory("synesis-doctor");
        CandidateProvider empty = new CandidateProvider() {
            @Override public String id() { return "test-empty"; }
            @Override public java.util.Set<CandidateType> supportedTypes() { return java.util.Set.of(); }
            @Override public java.util.concurrent.CompletableFuture<java.util.List<org.synesis.link.candidate.Candidate>> gather(
                    org.synesis.link.candidate.CandidateCancellation cancellation) {
                return java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of());
            }
        };
        ReadinessReport report = new ReadinessInspector(profile, empty).inspect();
        assertFalse(report.candidatesReady());
    }
}
