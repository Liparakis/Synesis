package org.synesis.coordination.domain.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
public record ContractRecord(UUID contractId, UUID projectId, long revision, String owner,
                             String contentHash, String body, Status status, UUID supersedes,
                             List<String> selectorRefs) {

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
        Objects.requireNonNull(contractId, "contract ID");
        Objects.requireNonNull(projectId, "project ID");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(contentHash, "content hash");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(status, "status");
        selectorRefs = List.copyOf(selectorRefs == null ? List.of() : selectorRefs);
        if (revision < 1 || owner.isBlank() || body.isBlank() || body.length() > 32768) {
            throw new IllegalArgumentException("invalid contract");
        }
        if (!contentHash.equals(hash(body))) {
            throw new IllegalArgumentException("content hash mismatch");
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
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
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
