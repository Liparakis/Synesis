package org.synesis.cli.diagnostics;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateCancellation;
import org.synesis.link.candidate.CandidateProvider;
import org.synesis.link.candidate.LocalInterfaceCandidateProvider;
import org.synesis.link.identity.IdentityBootstrap;

/**
 * Performs bounded, local-only readiness checks without identity creation or
 * network connection attempts.
 *
 * @since 1.0
 */
public final class ReadinessInspector {
    private final Path profileDirectory;
    private final CandidateProvider candidateProvider;

    /**
     * Creates an inspector for one profile directory.
     *
     * @param profileDirectory profile path; it is never created
     */
    public ReadinessInspector(Path profileDirectory) {
        this(profileDirectory, new LocalInterfaceCandidateProvider("doctor", 1, 0));
    }

    /**
     * Creates an inspector with an injected local candidate provider.
     *
     * <p>This constructor is intended for deterministic readiness tests; the
     * production constructor uses the platform local-interface provider.
     *
     * @param profileDirectory profile path; it is never created
     * @param candidateProvider bounded non-networking candidate provider
     */
    public ReadinessInspector(Path profileDirectory, CandidateProvider candidateProvider) {
        this.profileDirectory = Objects.requireNonNull(profileDirectory, "profileDirectory");
        this.candidateProvider = Objects.requireNonNull(candidateProvider, "candidateProvider");
    }

    /**
     * Inspects runtime, profile, identity, local candidates, and QUIC class
     * readiness without networking or repair.
     *
     * @return bounded readiness report
     */
    public ReadinessReport inspect() {
        boolean javaReady = Runtime.version().feature() >= 25;
        IdentityBootstrap.Inspection identity = new IdentityBootstrap(profileDirectory).inspect();
        List<Candidate> candidates = List.of();
        String candidateDetail;
        try {
            candidates = candidateProvider.gather((CandidateCancellation) () -> false)
                    .toCompletableFuture().join();
            candidateDetail = "COUNT=" + candidates.size();
        } catch (RuntimeException failure) {
            candidateDetail = "UNAVAILABLE";
        }
        boolean quicReady;
        String quicDetail;
        try {
            Class.forName("io.netty.handler.codec.quic.QuicChannel", true,
                    ReadinessInspector.class.getClassLoader());
            quicReady = true;
            quicDetail = "CLASS_READY";
        } catch (LinkageError | RuntimeException failure) {
            quicReady = false;
            quicDetail = "NATIVE_UNAVAILABLE";
        } catch (ClassNotFoundException failure) {
            quicReady = false;
            quicDetail = "CLASS_UNAVAILABLE";
        }
        boolean identityReady = !identity.identityPresent() || identity.identityValid();
        return new ReadinessReport(javaReady, identity.profileAccessible(), identityReady,
                !candidates.isEmpty(), quicReady, identity.detail(), candidateDetail, quicDetail);
    }
}
