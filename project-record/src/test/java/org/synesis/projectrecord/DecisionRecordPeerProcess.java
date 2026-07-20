package org.synesis.projectrecord;

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

/** Test-only independent JVM endpoint for CP-R4 record exchange evidence. */
public final class DecisionRecordPeerProcess {
    private static final UUID RECORD_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

    private DecisionRecordPeerProcess() { }

    /** Runs one bounded host or join process. @param arguments process arguments */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 8) throw new IllegalArgumentException("expected mode and seven paths/values");
        String mode = arguments[0];
        Path profile = Path.of(arguments[1]);
        UUID project = UUID.fromString(arguments[2]);
        Path joinId = Path.of(arguments[3]);
        Path hostId = Path.of(arguments[4]);
        Path invitation = Path.of(arguments[5]);
        Path marker = Path.of(arguments[6]);
        Path outcomes = Path.of(arguments[7]);
        if ("join".equals(mode)) join(profile, project, joinId, hostId, invitation, marker, outcomes);
        else if ("host".equals(mode)) host(profile, project, joinId, hostId, invitation, marker, outcomes);
        else throw new IllegalArgumentException("unknown mode");
    }

    private static void host(Path profile, UUID project, Path joinId, Path hostId, Path invitation,
            Path marker, Path outcomes) throws Exception {
        NodeIdentity identity = new IdentityBootstrap(profile.resolve("link")).loadOrCreate().identity();
        Files.writeString(hostId, identity.nodeId());
        waitFor(joinId);
        ProjectConfig config = new ProjectConfig(project, Set.of(Files.readString(joinId).trim()));
        config.save(profile.resolve("project.conf"));
        DecisionStore store = new DecisionStore(profile.resolve("records"), project);
        ProjectRecordSync sync = new ProjectRecordSync(config, store);
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
            Path marker, Path outcomes) throws Exception {
        NodeIdentity identity = new IdentityBootstrap(profile.resolve("link")).loadOrCreate().identity();
        Files.writeString(joinId, identity.nodeId());
        waitFor(invitation);
        waitFor(hostId);
        ProjectConfig config = new ProjectConfig(project, Set.of(Files.readString(hostId).trim()));
        config.save(profile.resolve("project.conf"));
        DecisionStore store = new DecisionStore(profile.resolve("records"), project);
        ProjectRecordSync sync = new ProjectRecordSync(config, store);
        Ed25519Signer signer = Ed25519Signer.from(identity);
        Onboarding onboarding = new Onboarding(profile.resolve("link"), event -> { });
        onboarding.join(Files.readString(invitation), sync.handler(), session -> {
            try {
                DecisionRecord first = record(signer, 1, null, "first");
                DecisionRecord second = record(signer, 2, first.digest(), "successor");
                DecisionRecord conflict = record(signer, 2, first.digest(), "divergent");
                List<ProjectRecordSync.SyncOutcome> results = List.of(sync.publish(session, first),
                        sync.publish(session, first), sync.publish(session, second), sync.publish(session, first),
                        sync.publish(session, conflict));
                if (sync.sync(session, RECORD_ID).code() != ProjectRecordSync.Code.DUPLICATE) {
                    throw new IllegalStateException("sync did not observe the shared head");
                }
                Files.writeString(outcomes, results.stream().map(value -> value.code().name())
                        .reduce((left, right) -> left + "," + right).orElse(""));
                Files.writeString(marker, "done");
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
    }

    private static DecisionRecord record(Ed25519Signer signer, long revision, byte[] predecessor, String title)
            throws Exception {
        return DecisionRecord.create(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), RECORD_ID, revision,
                predecessor, signer.nodeId(), signer.nodeId(), DecisionStatus.PROPOSED, TIME, TIME, title,
                "rationale", List.of(new DecisionEvidence("test", "process", new byte[32])), signer);
    }

    private static void waitFor(Path path) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(20);
        if (!Files.exists(path)) throw new IllegalStateException("timed out waiting for " + path);
    }
}
