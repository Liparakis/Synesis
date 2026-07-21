package org.synesis.projectrecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.transport.Onboarding;

/** Test-only independent JVM endpoint for project-wide reconciliation test. */
public final class ProjectReconciliationPeerProcess {
    private static final UUID RECORD_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECORD_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RECORD_C = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID RECORD_D = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 9) throw new IllegalArgumentException("expected mode and eight paths/values");
        String mode = arguments[0];
        Path profile = Path.of(arguments[1]);
        UUID project = UUID.fromString(arguments[2]);
        Path joinId = Path.of(arguments[3]);
        Path hostId = Path.of(arguments[4]);
        Path invitation = Path.of(arguments[5]);
        Path marker = Path.of(arguments[6]);
        Path outcomes = Path.of(arguments[7]);
        String scenario = arguments[8];

        if ("join".equals(mode)) join(profile, project, joinId, hostId, invitation, marker, outcomes, scenario);
        else if ("host".equals(mode)) host(profile, project, joinId, hostId, invitation, marker, outcomes, scenario);
        else throw new IllegalArgumentException("unknown mode");
    }

    private static void host(Path profile, UUID project, Path joinId, Path hostId, Path invitation,
            Path marker, Path outcomes, String scenario) throws Exception {
        NodeIdentity identity = new IdentityBootstrap(profile.resolve("link")).loadOrCreate().identity();
        Files.writeString(hostId, identity.nodeId());
        waitFor(joinId);

        ProjectConfig config = new ProjectConfig(project, Set.of(Files.readString(joinId).trim()));
        config.save(profile.resolve("project.conf"));
        DecisionStore store = new DecisionStore(profile.resolve("records"), project);
        Ed25519Signer signer = Ed25519Signer.from(identity);

        // Pre-populate Host store depending on the scenario
        if ("conflict".equals(scenario)) {
            // Host has RECORD_A revision 1 (diff title/digest) -> conflict!
            DecisionRecord r1 = record(project, RECORD_A, 1, null, "Host version of A", signer);
            store.save(r1, null);
        } else if ("normal".equals(scenario) || "corrupt".equals(scenario)) {
            // Host has RECORD_B revision 1 and 2 (client starts empty for B)
            DecisionRecord rb1 = record(project, RECORD_B, 1, null, "Record B rev 1", signer);
            store.save(rb1, null);
            DecisionRecord rb2 = record(project, RECORD_B, 2, rb1.digest(), "Record B rev 2", signer);
            store.save(rb2, store.headState(RECORD_B).orElse(null));
        }

        ProjectReconciliationSync sync = new ProjectReconciliationSync(identity.nodeId(), config, store);
        Onboarding onboarding = new Onboarding(profile.resolve("link"), event -> {
            if (event.type() == org.synesis.link.transport.OnboardingEventType.SHARE_LINK) {
                try { Files.writeString(invitation, event.value()); } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            }
        });
        onboarding.host(Files.readString(joinId).trim(), sync.handler(), session -> {
            try { waitFor(marker); } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
    }

    private static void join(Path profile, UUID project, Path joinId, Path hostId, Path invitation,
            Path marker, Path outcomes, String scenario) throws Exception {
        NodeIdentity identity = new IdentityBootstrap(profile.resolve("link")).loadOrCreate().identity();
        Files.writeString(joinId, identity.nodeId());
        waitFor(invitation);
        waitFor(hostId);

        ProjectConfig config = new ProjectConfig(project, Set.of(Files.readString(hostId).trim()));
        config.save(profile.resolve("project.conf"));
        DecisionStore store = new DecisionStore(profile.resolve("records"), project);
        Ed25519Signer signer = Ed25519Signer.from(identity);

        // Pre-populate Joiner store depending on the scenario
        if ("conflict".equals(scenario)) {
            // Joiner has RECORD_A revision 1 (diff title/digest) -> conflict!
            DecisionRecord r1 = record(project, RECORD_A, 1, null, "Joiner version of A", signer);
            store.save(r1, null);
        } else if ("normal".equals(scenario) || "corrupt".equals(scenario)) {
            // Joiner has RECORD_A revision 2
            DecisionRecord r1 = record(project, RECORD_A, 1, null, "Record A rev 1", signer);
            store.save(r1, null);
            DecisionRecord r2 = record(project, RECORD_A, 2, r1.digest(), "Record A rev 2", signer);
            store.save(r2, store.headState(RECORD_A).orElse(null));

            // Joiner has RECORD_C revision 1 (local-only)
            DecisionRecord rc1 = record(project, RECORD_C, 1, null, "Record C rev 1", signer);
            store.save(rc1, null);
        }

        if ("corrupt".equals(scenario)) {
            // Intentionally corrupt a file locally on Joiner
            Path corruptFile = profile.resolve("records/decisions").resolve(RECORD_A.toString()).resolve("1.sdr");
            Files.writeString(corruptFile, "corrupted bytes!");
        }

        ProjectReconciliationSync sync = new ProjectReconciliationSync(identity.nodeId(), config, store);
        Onboarding onboarding = new Onboarding(profile.resolve("link"), event -> { });
        onboarding.join(Files.readString(invitation), sync.handler(), session -> {
            try {
                ProjectReconciliationSync.ReconciliationResult result = sync.syncProject(session);
                Files.writeString(outcomes, result.success() + "," + result.reconciledCount() + "," + result.addedLocal() + "," + result.addedRemote() + "," + result.corruptLocalCount());
                Files.writeString(marker, "done");
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
    }

    private static DecisionRecord record(UUID project, UUID recordId, long revision, byte[] predecessor, String title, Ed25519Signer signer)
            throws Exception {
        return DecisionRecord.create(project, recordId, revision,
                predecessor, signer.nodeId(), signer.nodeId(), DecisionStatus.PROPOSED, TIME, TIME, title,
                "rationale", List.of(new DecisionEvidence("test", "process", new byte[32])), signer);
    }

    private static void waitFor(Path path) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(20);
        if (!Files.exists(path)) throw new IllegalStateException("timed out waiting for " + path);
    }
}
