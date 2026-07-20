package org.synesis.link.session;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.HandshakeProof;

/**
 * Establishes a transport-neutral session only after both identity proofs pass.
 *
 * <p>Callers must supply the expected remote node ID; this class never performs
 * trust-on-first-use. The same transcript fingerprint cannot be accepted twice
 * by one {@link ReplayGuard}.
 *
 * @since 1.0
 */
public final class SessionAuthenticator {

    private SessionAuthenticator() {
    }

    /**
     * Creates the local proof for the supplied transcript and role.
     *
     * @param identity   local signing identity
     * @param transcript complete shared transcript
     * @param role       local role
     * @return signed proof
     * @throws GeneralSecurityException if signing fails
     */
    public static HandshakeProof createProof(NodeIdentity identity, HandshakeTranscript transcript,
                                             HandshakeRole role) throws GeneralSecurityException {
        Objects.requireNonNull(transcript, "transcript");
        Objects.requireNonNull(role, "role");
        return HandshakeProof.create(identity, transcript.version(), transcript.sessionId(),
                transcript.proofChallenge(role));
    }

    /**
     * Authenticates both role proofs and publishes the resulting session.
     *
     * @param localIdentity        local signing identity
     * @param expectedRemoteNodeId required trusted remote node ID
     * @param transcript           complete shared transcript
     * @param localProof           local role proof
     * @param remoteProof          remote role proof
     * @param replayGuard          bounded replay guard
     * @param establishedAt        local establishment time
     * @return immutable authenticated session
     * @throws GeneralSecurityException if proof verification fails
     * @throws IllegalArgumentException if identity, ALPN, or transcript bindings fail
     * @throws IllegalStateException    if the transcript was already accepted
     */
    public static PeerSession establish(NodeIdentity localIdentity, String expectedRemoteNodeId,
                                        HandshakeTranscript transcript, HandshakeProof localProof, HandshakeProof remoteProof,
                                        ReplayGuard replayGuard, Instant establishedAt) throws GeneralSecurityException {
        Objects.requireNonNull(localIdentity, "local identity");
        Objects.requireNonNull(transcript, "transcript");
        Objects.requireNonNull(localProof, "local proof");
        Objects.requireNonNull(remoteProof, "remote proof");
        Objects.requireNonNull(replayGuard, "replay guard");
        Objects.requireNonNull(establishedAt, "established at");

        HandshakeRole localRole = roleFor(transcript, localIdentity.nodeId());
        HandshakeRole remoteRole = localRole.opposite();
        String remoteNodeId = transcript.nodeId(remoteRole);
        if (expectedRemoteNodeId != null && !remoteNodeId.equals(expectedRemoteNodeId)) {
            throw new IllegalArgumentException("expected remote identity does not match transcript");
        }
        if (!transcript.alpn().equals(org.synesis.link.SynesisLink.ALPN)) {
            throw new IllegalArgumentException("unexpected Synesis Link ALPN");
        }
        if (!localIdentity.nodeId().equals(transcript.nodeId(localRole))
                || !java.util.Arrays.equals(localIdentity.publicKeyEncoded(), transcript.publicKeyEncoded(localRole))) {
            throw new IllegalArgumentException("local identity does not match transcript");
        }
        verifyProof(localProof, transcript, localRole);
        verifyProof(remoteProof, transcript, remoteRole);
        if (expectedRemoteNodeId != null && !remoteProof.nodeId().equals(expectedRemoteNodeId)
                || !java.util.Arrays.equals(remoteProof.publicKeyEncoded(), transcript.publicKeyEncoded(remoteRole))) {
            throw new IllegalArgumentException("remote identity proof does not match expectation");
        }
        String fingerprint = fingerprint(transcript);
        if (!replayGuard.accept(fingerprint)) {
            throw new IllegalStateException("handshake replay rejected");
        }
        return new PeerSession(localIdentity.nodeId(), remoteNodeId,
                transcript.publicKeyEncoded(remoteRole), transcript.sessionId(),
                transcript.epoch(localRole), transcript.epoch(remoteRole), transcript.version(), establishedAt);
    }

    private static void verifyProof(HandshakeProof proof, HandshakeTranscript transcript, HandshakeRole role)
            throws GeneralSecurityException {
        if (!proof.verify(transcript.version(), transcript.sessionId(), transcript.proofChallenge(role),
                transcript.nodeId(role))) {
            throw new IllegalArgumentException("invalid identity proof");
        }
        if (!java.util.Arrays.equals(proof.publicKeyEncoded(), transcript.publicKeyEncoded(role))) {
            throw new IllegalArgumentException("proof key does not match transcript");
        }
    }

    private static HandshakeRole roleFor(HandshakeTranscript transcript, String nodeId) {
        if (transcript.nodeId(HandshakeRole.INITIATOR).equals(nodeId)) {
            return HandshakeRole.INITIATOR;
        }
        if (transcript.nodeId(HandshakeRole.RESPONDER).equals(nodeId)) {
            return HandshakeRole.RESPONDER;
        }
        throw new IllegalArgumentException("local identity is absent from transcript");
    }

    private static String fingerprint(HandshakeTranscript transcript) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(transcript.encoded());
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java platform", impossible);
        }
    }
}
