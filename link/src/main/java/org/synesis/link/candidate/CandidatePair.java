package org.synesis.link.candidate;

import java.util.Objects;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Immutable compatible local/remote direct-connectivity pair.
 *
 * @param local local endpoint candidate
 * @param remote signed remote endpoint candidate
 */
public record CandidatePair(Candidate local, Candidate remote) {
    /** Validates pair values; compatibility is enforced by {@link CandidatePairs}. */
    public CandidatePair {
        Objects.requireNonNull(local, "local candidate");
        Objects.requireNonNull(remote, "remote candidate");
    }

    /**
     * Returns the stable pair identifier used in bounded diagnostics.
     *
     * @return deterministic candidate-pair identifier
     */
    public String identifier() {
        return local.type() + "/" + remote.type() + "/h" + fingerprint(local) + ":" + local.port()
                + "/h" + fingerprint(remote) + ":" + remote.port();
    }

    private static String fingerprint(Candidate candidate) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(candidate.address().getAddress());
            return HexFormat.of().formatHex(digest.digest()).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
