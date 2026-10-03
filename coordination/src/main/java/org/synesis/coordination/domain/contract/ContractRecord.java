package org.synesis.coordination.domain.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.identity.ContractId;
import org.synesis.coordination.domain.identity.ContractRevision;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationInvariantError;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Stable, revisioned contract shared by cooperating participants.
 *
 * @param contractId   contract identifier
 * @param projectId    owning project
 * @param revision     monotonically increasing revision
 * @param owner        opaque owner handle
 * @param contentHash  SHA-256 body hash
 * @param body         bounded contract body
 * @param status       lifecycle status
 * @param supersedes   prior contract identifier, if any
 * @param selectorRefs declared repository selectors
 */
public record ContractRecord(UUID contractId, UUID projectId, long revision, String owner, String contentHash,
                             String body, Status status, UUID supersedes, List<String> selectorRefs) {

    /**
     * Validates and canonicalizes a contract record.
     *
     * @param contractId   contract identifier
     * @param projectId    owning project
     * @param revision     revision
     * @param owner        owner handle
     * @param contentHash  body hash
     * @param body         body
     * @param status       lifecycle status
     * @param supersedes   prior identifier
     * @param selectorRefs selectors
     */
    public ContractRecord {
        CoordinationNullException.requireNonNull(contractId, "contract ID");
        CoordinationNullException.requireNonNull(projectId, "project ID");
        CoordinationNullException.requireNonNull(owner, "owner");
        CoordinationNullException.requireNonNull(contentHash, "content hash");
        CoordinationNullException.requireNonNull(body, "body");
        CoordinationNullException.requireNonNull(status, "status");
        selectorRefs = List.copyOf(selectorRefs == null ? List.of() : selectorRefs);
        if (revision < 1 || owner.isBlank() || body.isBlank() || body.length() > 32768) {
            throw new CoordinationArgumentException("invalid contract");
        }
        if (!contentHash.equals(hash(body))) {
            throw new CoordinationArgumentException("content hash mismatch");
        }
    }

    /**
     * Creates a first contract revision.
     *
     * @param contractId contract identifier
     * @param projectId  owning project
     * @param owner      owner handle
     * @param body       body
     * @param selectors  selectors
     * @return first revision
     */
    public static ContractRecord create(UUID contractId,
            UUID projectId,
            String owner,
            String body,
            List<String> selectors) {
        return new ContractRecord(contractId, projectId, 1, owner, hash(body), body, Status.ACTIVE, null, selectors);
    }

    /**
     * Computes the canonical SHA-256 content hash.
     *
     * @param body contract body
     * @return lowercase hexadecimal SHA-256 digest
     */
    public static String hash(String body) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new CoordinationInvariantError(impossible);
        }
    }

    /**
     * Returns the typed identity of this contract.
     *
     * @return contract identity
     */
    public ContractId identity() {
        return new ContractId(contractId);
    }

    /**
     * Returns this contract's typed revision.
     *
     * @return contract revision
     */
    public ContractRevision contractRevision() {
        return new ContractRevision(revision);
    }

    /**
     * Contract lifecycle statuses.
     */
    public enum Status {
        /**
         * Current usable revision.
         */
        ACTIVE,
        /**
         * Replaced by a newer revision.
         */
        SUPERSEDED
    }
}
